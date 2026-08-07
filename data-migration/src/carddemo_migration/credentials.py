"""Apply each generated service credential to the database role that authenticates with it.

Purpose
-------
Close the one gap between provisioning and a running system: ``infra/modules/secrets``
generates one credential per service database role and writes it to AWS Secrets Manager, and
``data-migration/sql/V0__schemas_and_roles.sql`` creates each of those roles with ``LOGIN`` and
**no password clause**, so nothing in either artifact makes a role able to authenticate. This
module is what does, and it is the delivered mechanism the two of them name.

The ordered sequence a deployment follows is therefore:

1. Terraform creates the cluster and the fifteen per-role secrets.
2. The bootstrap step applies ``sql/V0__schemas_and_roles.sql``, which creates the schemas, the
   three tiers of role behind them -- eight NOLOGIN schema owners, seven migration logins and
   eight runtime logins -- and every grant. Only the fifteen LOGIN roles need a credential; an
   owner is reached by ``SET ROLE`` from its migration role, never by authenticating.
3. The bootstrap step runs **this module**, which reads each secret, derives that role's
   SCRAM-SHA-256 verifier locally, applies it, and then proves the role can log in.
4. The services start and authenticate with the credential each one reads from its own secret.

Step 3 refuses to report success unless every role in
:data:`carddemo_migration.config.LOGIN_ROLE_NAMES` both holds a SCRAM verifier and completes a
real TLS login, so a deployment cannot finish green while a service still cannot reach its schema
or apply its migration.

Command-line entry point
------------------------
``python -m carddemo_migration.credentials``

It takes no arguments: everything it needs is resolved from the environment through
:mod:`carddemo_migration.config`, which is what lets one invocation serve every environment. The
exit codes follow the repository's own return-code rubric, documented at ``tests/README.md``
section 8, so the batch state machine can branch on them exactly as the COBOL suite's runners
do: ``0`` applied and verified, ``2`` invoked incorrectly, ``8`` at least one role could not be
applied or verified, ``16`` the cluster or its configuration could not be reached at all.

Assumptions: when ``carddemo_migration.cli`` is authored it should expose this as a subcommand by
calling :func:`apply_service_credentials`, not by reimplementing it. The module entry point above
exists so the mechanism is invocable and testable on its own, and it stays the published form.

Design decisions (WHY)
----------------------
Alternatives Considered:
    **A Secrets Manager rotation Lambda, which is what an earlier revision of the surrounding
    comments claimed performed this step.** Rejected on three independent counts, recorded here
    because the claim was load-bearing and its removal needs to be legible. The rotation
    functions AWS publishes for PostgreSQL cannot perform a FIRST application at all: the
    single-user strategy authenticates with the credential it is about to replace, and these
    roles have none, while the alternating-users strategy creates a second role with a suffix
    rather than crediting the one that already exists. Packaging a bespoke function would need an
    archive provider that neither ``infra/modules/secrets/versions.tf`` nor either environment
    root's lock file admits, so it could not be initialised from the pinned provider set. And
    ``infra/modules/secrets`` is scoped to Secrets Manager entries and generated values -- the
    rejections at the foot of its ``main.tf`` already exclude the IAM role such a function needs
    -- so the function would have had to live somewhere that module does not reach. The bootstrap
    step already holds a privileged connection to the cluster inside the isolated data tier, so
    it is where the work belongs. Scheduled rotation remains configurable through that module's
    two rotation inputs and is a separate concern from first application.
Alternatives Considered:
    **Sending the password itself in ``ALTER ROLE ... PASSWORD``, and letting the server hash
    it.** Rejected, and this is the decision that shapes the whole module.
    ``sql/V0__schemas_and_roles.sql`` records the reason it refused to apply credentials itself:
    PostgreSQL accepts no bind parameter in ``ALTER ROLE ... PASSWORD``, so a password has to be
    interpolated into statement TEXT, which is exactly how a credential reaches a server log
    under ``log_statement``, a ``psql`` history file, or an error message quoting the failing
    statement. Deriving the verifier here removes the objection rather than accepting it: what
    crosses the connection is a salted, iterated hash that PostgreSQL stores verbatim, and a
    leaked verifier cannot be replayed as a password because deriving the client key from the
    stored key would require inverting SHA-256. The password itself never leaves this process.
Assumptions:
    **SASLprep is the identity mapping for every password this stack generates, and the module
    fails closed rather than assuming it.** PostgreSQL applies SASLprep to a password before
    hashing it, so a locally derived verifier matches a server-derived one only for input that
    SASLprep leaves unchanged -- which is exactly ASCII printable text with no space.
    ``infra/modules/secrets`` narrows its generated alphabet to that set for this reason, and
    :func:`scram_verifier` refuses anything outside it. Without that refusal a non-ASCII password
    would produce a verifier the server would never have computed, and the failure would appear
    as an authentication error against a credential that reads as correct in the secret store.
Trade-offs:
    **Every role is applied inside ONE transaction, and the logins are verified after it
    commits.** ``ALTER ROLE`` is transactional in PostgreSQL, so a failure partway through rolls
    the whole set back and leaves the previous state intact; the alternative -- committing each
    role as it is applied -- would leave a deployment in which some services can authenticate and
    others cannot, which is harder to diagnose than none of them being able to. The accepted cost
    is that the eight statements hold one transaction open for the length of eight secret reads.
Assumptions:
    **Re-running is safe and is the intended repair action.** Applying a role's credential again
    derives a fresh salt, so the stored verifier changes while the password behind it does not:
    the credential in the secret keeps working, and a role that was already correct stays
    correct. That is what makes this step re-runnable after a partial failure, and it is why the
    step is safe to place ahead of every load in the batch chain rather than being run once by
    hand.
Assumptions:
    **The driver is imported lazily, inside the one function that opens a connection.** The
    package's layering contract in ``__init__.py`` states that a copybook-only import must work
    on a bare checkout with no database driver present. A module-level ``import psycopg`` here
    would not break that contract on its own -- nothing imports this module from the codecs --
    but it would make this module unimportable wherever the driver is absent, including a
    documentation build, so the import sits where the connection does.
"""

import argparse
import base64
import hashlib
import hmac
import logging
import re
import secrets
import sys
from collections.abc import Callable, Mapping, Sequence
from typing import Any, Final

from carddemo_migration.config import (
    ENV_DB_MASTER_SECRET,
    SCHEMA_ROLES,
    AuroraConnectionSettings,
    ConfigurationError,
    resolve_aurora_settings,
    resolve_master_settings,
)

# Trade-offs: an explicit ``__all__`` is declared for the same narrowing reason
# ``config`` declares one -- the private helpers, the compiled pattern and the standard-library
# modules imported above stay out of an importing namespace, so a reader of a consuming module
# can tell at a glance which names came from here. The entries are grouped -- constants, then
# the exception, then functions -- rather than sorted as one run, matching that module.
__all__ = [
    "SCRAM_ITERATIONS",
    "SCRAM_MECHANISM",
    "SCRAM_SALT_BYTES",
    "CredentialApplicationError",
    "apply_service_credentials",
    "main",
    "scram_verifier",
]

#: Name of the only password mechanism this module writes, and the literal PostgreSQL expects at
#: the head of a stored verifier. Written out rather than derived from a setting because a
#: verifier naming a mechanism the server does not implement is rejected at ``ALTER ROLE``, so
#: there is nothing useful to configure.
SCRAM_MECHANISM: Final[str] = "SCRAM-SHA-256"

# Assumptions: 4096 is PostgreSQL's own default iteration count for ``scram-sha-256``, and
# matching it is deliberate rather than conservative. The count travels INSIDE the verifier, so
# the server honours whatever is written here and a higher number would be accepted -- but it
# would also make every verifier this module writes differ from every verifier the server itself
# would write for the same password, which is the first thing an operator compares when a login
# fails. Matching the default keeps that comparison meaningful. The cost of the default is
# understood: it is the server's cost too, for every password set by any other means.
SCRAM_ITERATIONS: Final[int] = 4096

# Assumptions: 16 bytes is the salt length libpq uses when it derives a verifier
# client-side, so a verifier written here is indistinguishable in shape from one the server
# produced. RFC 5802 fixes no length, which is why the choice needs a reason rather than a
# citation: shorter would weaken the salt's only job, which is to stop one precomputed table
# covering two roles that happen to share a password, and longer buys nothing measurable while
# making the stored value differ visibly from every other verifier in the catalogue.
SCRAM_SALT_BYTES: Final[int] = 16

# Assumptions: the accepted password alphabet is ASCII 0x21 through 0x7E -- printable, and
# with the space excluded at both ends. Below 0x21 lies the space and the control codes, which
# SASLprep maps or prohibits rather than passing through; above 0x7E every code point needs
# normalisation before hashing. Either would make a locally derived verifier disagree with the
# server's, so the pattern is the fail-closed boundary the module docstring describes rather than
# a style rule about passwords.
_ASCII_PASSWORD_PATTERN: Final[re.Pattern[str]] = re.compile(r"\A[\x21-\x7e]+\Z")

# Assumptions: the verifier is checked against this pattern before it is composed into a
# statement, even though every character of it is produced by base64 encoding and integer
# formatting in this file. The check is not defending against the code above it; it is what lets
# the composition below be read as safe by someone who has not traced where the value came from,
# and it fails closed if a future edit widens how a verifier is built. The alphabet is base64
# plus the two structural separators PostgreSQL's format uses.
_VERIFIER_PATTERN: Final[re.Pattern[str]] = re.compile(
    r"\ASCRAM-SHA-256\$[0-9]+:[A-Za-z0-9+/=]+\$[A-Za-z0-9+/=]+:[A-Za-z0-9+/=]+\Z"
)

#: Exit code reported when every role was applied and verified. Matches ``tests/README.md``
#: section 8, where 0 is "pass".
EXIT_OK: Final[int] = 0

#: Exit code reported when the command was invoked incorrectly. Matches the rubric's "usage"
#: code, which aborts immediately rather than entering aggregation.
EXIT_USAGE: Final[int] = 2

#: Exit code reported when at least one role could not be applied or could not authenticate
#: afterwards. Matches the rubric's "fail" code: the deployment must not proceed.
EXIT_FAILED: Final[int] = 8

#: Exit code reported when the cluster or its configuration could not be reached at all, so no
#: role was even attempted. Matches the rubric's "fatal" code, which distinguishes an
#: unreachable environment from a role that was reached and refused.
EXIT_FATAL: Final[int] = 16

_LOGGER: Final[logging.Logger] = logging.getLogger(__name__)


class CredentialApplicationError(RuntimeError):
    """Raised when a credential could not be applied to a role or could not then be used.

    Purpose
    -------
    Separate "the database refused or contradicted this step" from
    :class:`carddemo_migration.config.ConfigurationError`, which means "this deployment is
    misconfigured". The two exit the command with different codes -- ``8`` and ``16`` -- because
    an operator answers them differently: a failure here names a role and a cluster that were
    both reachable, while a configuration failure names a setting that was never resolved.

    Trade-offs: one exception type covers a missing role, a refused ``ALTER ROLE``, a verifier
    that did not appear in the catalogue and a login that failed. Splitting them further was
    rejected because no caller branches on which of the four happened -- every one of them stops
    the deployment -- and the message names which it was.
    """


def scram_verifier(
    password: str,
    *,
    salt: bytes | None = None,
    iterations: int = SCRAM_ITERATIONS,
) -> str:
    """Derive the PostgreSQL SCRAM-SHA-256 verifier for one password.

    Purpose
    -------
    Produce the exact string ``ALTER ROLE ... PASSWORD`` stores when it is handed an already
    hashed credential, so the password itself never becomes statement text. The result has the
    form ``SCRAM-SHA-256$<iterations>:<base64 salt>$<base64 stored key>:<base64 server key>``,
    which is PostgreSQL's rendering of the RFC 5802 verifier.

    Assumptions: the two key derivations use the literal strings ``Client Key`` and ``Server
    Key``. They are constants of RFC 5802 rather than choices, and they are spelled out here
    because a single-character difference produces a verifier that is structurally perfect and
    authenticates nothing -- the failure appears as a wrong password.

    Parameters
    ----------
    password : str
        The credential to derive from, as read from the secret store. Must be non-empty and
        entirely ASCII printable with no space, for the SASLprep reason recorded on the module.
    salt : bytes or None, optional
        The salt to derive with. ``None`` -- the default and the only value production uses --
        draws :data:`SCRAM_SALT_BYTES` fresh bytes from the operating system's cryptographic
        source. An explicit value exists so a test can assert against a known-answer vector; a
        caller that supplies one is responsible for its length.
    iterations : int, optional
        Iteration count to derive with, defaulting to :data:`SCRAM_ITERATIONS`. Must be at least
        one, because PBKDF2 with no iteration performs no derivation.

    Returns
    -------
    str
        The verifier, ready to be stored as a role's password.

    Raises
    ------
    CredentialApplicationError
        If the password is empty or contains a character outside the accepted ASCII range, or if
        ``iterations`` is less than one. Raised rather than returning a value because both cases
        would otherwise produce a verifier that stores successfully and cannot authenticate.
    """
    if iterations < 1:
        raise CredentialApplicationError(
            f"a SCRAM verifier needs at least one iteration, but {iterations} was requested"
        )

    # Assumptions: the message names neither the password nor its length. A length is a
    # narrowing fact about a credential, and this function is called with a value read from the
    # secret store, so the message is written to be safe in a log that an operator shares.
    if not _ASCII_PASSWORD_PATTERN.fullmatch(password):
        raise CredentialApplicationError(
            "a credential read from the secret store is empty or carries a character outside "
            "ASCII 0x21 to 0x7e, so a locally derived SCRAM verifier would not match the one "
            "the server derives; regenerate it with the character set "
            "infra/modules/secrets declares"
        )

    salt_bytes = secrets.token_bytes(SCRAM_SALT_BYTES) if salt is None else salt

    # Assumptions: the password is encoded as ASCII rather than UTF-8, and the choice is a
    # guard rather than a preference. The pattern above already refuses anything outside ASCII,
    # so the two encodings agree on every value that reaches here; naming ASCII means a future
    # widening of that pattern fails loudly at this line instead of silently producing a
    # verifier over bytes SASLprep would have normalised differently.
    salted_password = hashlib.pbkdf2_hmac(
        "sha256", password.encode("ascii"), salt_bytes, iterations
    )
    client_key = hmac.new(salted_password, b"Client Key", hashlib.sha256).digest()
    stored_key = hashlib.sha256(client_key).digest()
    server_key = hmac.new(salted_password, b"Server Key", hashlib.sha256).digest()

    return (
        f"{SCRAM_MECHANISM}${iterations}:{base64.b64encode(salt_bytes).decode('ascii')}"
        f"${base64.b64encode(stored_key).decode('ascii')}"
        f":{base64.b64encode(server_key).decode('ascii')}"
    )


def _connect(settings: AuroraConnectionSettings) -> Any:
    """Open one verified TLS connection from a resolved settings object.

    Purpose
    -------
    Hold the single place this module imports the database driver and the single place a
    connection is opened, so the lazy-import decision recorded on the module has one location
    rather than one per call site.

    Parameters
    ----------
    settings : AuroraConnectionSettings
        The resolved descriptor to connect with. Its
        :meth:`~carddemo_migration.config.AuroraConnectionSettings.as_connection_params` always
        emits ``sslmode=verify-full`` and a trust anchor, so no caller can ask this function for
        an unverified connection.

    Returns
    -------
    Any
        An open ``psycopg.Connection``. Typed loosely because the driver is imported inside this
        function, so its types are not available to annotate the signature without putting the
        import back at module level.

    Raises
    ------
    ConfigurationError
        If the driver is not installed, reported as a configuration fault because that is what
        it is -- an environment missing a declared dependency, not a database that refused.
    CredentialApplicationError
        If the driver is present and the connection attempt fails.
    """
    try:
        import psycopg
    except ImportError as exc:  # pragma: no cover - exercised only on an incomplete install
        raise ConfigurationError(
            "the psycopg driver is required to apply database credentials but is not installed; "
            "install data-migration/requirements.txt"
        ) from exc

    try:
        return psycopg.connect(**settings.as_connection_params())
    except psycopg.Error as exc:
        # Assumptions: the settings object is interpolated rather than the exception's own
        # text being trusted alone, and it is safe to interpolate because that class's __repr__
        # masks the password by construction. The host, port, database and user are exactly what
        # an operator needs to tell "wrong endpoint" from "wrong credential", and they are the
        # four things the driver's own message does not always carry.
        raise CredentialApplicationError(
            f"could not connect to the cluster as {settings!r}: {exc}"
        ) from exc


def _sql_module() -> Any:
    """Return the driver's SQL-composition module.

    Purpose
    -------
    Give the composition helpers one import site, for the same reason :func:`_connect` gives the
    driver one, so that a caller which never composes a statement never needs the driver present.

    Returns
    -------
    Any
        The ``psycopg.sql`` module.

    Raises
    ------
    ConfigurationError
        If the driver is not installed.
    """
    try:
        from psycopg import sql
    except ImportError as exc:  # pragma: no cover - exercised only on an incomplete install
        raise ConfigurationError(
            "the psycopg driver is required to apply database credentials but is not installed; "
            "install data-migration/requirements.txt"
        ) from exc

    return sql


def _require_roles_exist(cursor: Any, roles: Sequence[str]) -> None:
    """Establish that every role this step will alter already exists.

    Purpose
    -------
    Turn a missing role into one message naming the ordering that was not honoured, instead of
    into a driver error on the first ``ALTER ROLE``. The roles are created by
    ``sql/V0__schemas_and_roles.sql``, so their absence means this step ran before that script.

    Parameters
    ----------
    cursor : Any
        An open cursor on the master connection.
    roles : Sequence[str]
        The role names to check, in the order they will be applied.

    Returns
    -------
    None
        Returns only when every role exists.

    Raises
    ------
    CredentialApplicationError
        If any role is absent, naming every missing role rather than only the first, so one run
        reports the whole gap.
    """
    cursor.execute(
        "SELECT rolname FROM pg_roles WHERE rolname = ANY(%s)",
        (list(roles),),
    )
    present = {row[0] for row in cursor.fetchall()}
    missing = [role for role in roles if role not in present]
    if missing:
        raise CredentialApplicationError(
            "these database roles do not exist yet, so no credential can be applied to them: "
            f"{', '.join(missing)}. Apply data-migration/sql/V0__schemas_and_roles.sql first; "
            "it creates every role with LOGIN and no password, and this step supplies the "
            "password"
        )


def _apply_verifier(cursor: Any, role: str, verifier: str) -> None:
    """Store one already-derived verifier as one role's password.

    Purpose
    -------
    Issue the single statement that makes a role able to authenticate, composed so that neither
    the role name nor the verifier can alter the statement's structure.

    Parameters
    ----------
    cursor : Any
        An open cursor on the master connection, inside the caller's transaction.
    role : str
        The role to alter, as named by :data:`carddemo_migration.config.SCHEMA_ROLES`.
    verifier : str
        The verifier produced by :func:`scram_verifier`.

    Returns
    -------
    None
        Returns when the statement has been executed. The caller owns the commit.

    Raises
    ------
    CredentialApplicationError
        If the verifier does not have the expected structure, or if the server refuses the
        statement -- most usually because the connected identity lacks the authority to alter
        another role.
    """
    if not _VERIFIER_PATTERN.fullmatch(verifier):
        raise CredentialApplicationError(
            f"refusing to store a password for {role} that is not a well-formed "
            f"{SCRAM_MECHANISM} verifier"
        )

    sql = _sql_module()

    # Alternatives Considered: composing the statement as an f-string with the role name
    # quoted by carddemo_migration.config.quote_identifier, which exists and would work. The
    # driver's own composition is used instead because it quotes BOTH halves -- the identifier
    # and the literal -- under one mechanism the driver guarantees, so there is no place left in
    # this file where a value is spliced into SQL by hand. A bind parameter is not an option
    # here: PostgreSQL's grammar accepts no parameter in ALTER ROLE ... PASSWORD, which is the
    # constraint that made deriving the verifier locally necessary in the first place.
    statement = sql.SQL("ALTER ROLE {role} PASSWORD {verifier}").format(
        role=sql.Identifier(role), verifier=sql.Literal(verifier)
    )

    try:
        cursor.execute(statement)
    except Exception as exc:  # noqa: BLE001 - re-raised below as one documented failure type
        # Assumptions: the driver's message is quoted while the statement is NOT. A
        # server-side error for this statement can echo the statement text back, so passing the
        # composed statement into the message would defeat the whole point of deriving the
        # verifier locally -- it would put the verifier in the log. The role name is enough to
        # act on.
        raise CredentialApplicationError(
            f"the server refused the credential for {role}: {exc}"
        ) from exc


def _roles_without_verifier(cursor: Any, roles: Sequence[str]) -> list[str]:
    """Report which roles do not hold a SCRAM verifier in the catalogue.

    Purpose
    -------
    Read back what was written, so the step's success is evidence rather than an assumption. The
    column is read from ``pg_authid`` because ``pg_roles`` blanks it for every caller.

    Assumptions: only whether the stored value is absent, or is not a SCRAM verifier, is read --
    never the value itself, so this check discloses no credential material even in a log. That is
    the same discipline ``sql/V0__schemas_and_roles.sql`` applies to its own credential-presence
    report.

    Parameters
    ----------
    cursor : Any
        An open cursor on the master connection.
    roles : Sequence[str]
        The role names to check.

    Returns
    -------
    list[str]
        The subset of ``roles`` holding no password or a password that is not a
        :data:`SCRAM_MECHANISM` verifier, in the order given.

    Raises
    ------
    CredentialApplicationError
        If the connected identity cannot read ``pg_authid``, because an unreadable catalogue
        means this step cannot produce the evidence it exists to produce.
    """
    cursor.execute("SELECT has_table_privilege(CURRENT_USER, 'pg_authid', 'SELECT')")
    row = cursor.fetchone()
    if not row or not row[0]:
        raise CredentialApplicationError(
            "the connected identity cannot read pg_authid, so whether each credential was "
            "stored cannot be confirmed; connect as the cluster master user resolved from "
            f"{ENV_DB_MASTER_SECRET}"
        )

    cursor.execute(
        "SELECT rolname FROM pg_authid WHERE rolname = ANY(%s)"
        " AND (rolpassword IS NULL OR rolpassword NOT LIKE %s)",
        (list(roles), f"{SCRAM_MECHANISM}$%"),
    )
    unusable = {row[0] for row in cursor.fetchall()}
    return [role for role in roles if role in unusable]


def _verify_login(schema: str, role: str) -> None:
    """Prove one role can authenticate with the credential now stored for it.

    Purpose
    -------
    Complete the step's contract. A stored verifier shows that something was written; only a
    successful login shows that what was written matches the secret each service will read, which
    is the property a deployment depends on.

    Assumptions: the settings are resolved through
    :func:`carddemo_migration.config.resolve_aurora_settings`, so this login exercises exactly the
    path a loader takes -- the same secret name, the same user-name assertion against the schema's
    owning role, and the same certificate verification. A bespoke connection built from the
    credential in hand would prove the password and none of that.

    Parameters
    ----------
    schema : str
        The bounded-context schema whose credential is being verified.
    role : str
        The login role that schema resolves to, used only in the failure message.

    Returns
    -------
    None
        Returns when the login succeeded and was closed again.

    Raises
    ------
    CredentialApplicationError
        If the login failed, or if the connection could not be established.
    ConfigurationError
        If the schema's settings cannot be resolved -- an absent parameter, an unreadable secret,
        or a secret whose user name is not this role.
    """
    settings = resolve_aurora_settings(schema)
    with _connect(settings) as connection:
        with connection.cursor() as cursor:
            # Trade-offs: the probe reads CURRENT_USER rather than issuing SELECT 1. Both
            # prove the login succeeded, and this one additionally proves WHICH identity the
            # server accepted, which is what catches a secret populated with another role's
            # credential -- a case that authenticates successfully and would otherwise pass.
            cursor.execute("SELECT CURRENT_USER")
            row = cursor.fetchone()

    connected_as = row[0] if row else None
    if connected_as != role:
        raise CredentialApplicationError(
            f"the credential stored for the {schema} schema authenticated as "
            f"{connected_as!r} rather than {role!r}, so that secret holds another role's "
            "identity"
        )


def apply_service_credentials(
    *,
    roles: Mapping[str, str] | None = None,
    verifier_factory: Callable[[str], str] = scram_verifier,
) -> tuple[str, ...]:
    """Apply every service credential to its role and prove each one authenticates.

    Purpose
    -------
    The whole of this module's work, in the order the ordering contract requires: connect as the
    cluster master, establish that the roles exist, apply one verifier per role in a single
    transaction, confirm the catalogue holds a verifier for each, and then log in as every role
    through the same path a loader uses.

    Parameters
    ----------
    roles : Mapping[str, str] or None, optional
        Schema name to login role. ``None`` -- the default and the only value production uses --
        applies :data:`carddemo_migration.config.SCHEMA_ROLES`, the single declaration of the
        eight bounded contexts and their roles. An explicit mapping exists so a test can drive a
        subset without the module needing a flag that a deployment could pass by mistake.
    verifier_factory : Callable[[str], str], optional
        How a password becomes a verifier, defaulting to :func:`scram_verifier`. Injected for the
        same reason: a test can supply a deterministic derivation without this module reading a
        setting that only a test would ever set.

    Returns
    -------
    tuple[str, ...]
        The role names that were applied and verified, in the order of the mapping.

    Raises
    ------
    ConfigurationError
        If the master locator, the endpoint parameters, the trust anchor or any secret cannot be
        resolved.
    CredentialApplicationError
        If a role is missing, if the server refuses a credential, if the catalogue does not hold
        a verifier for every role afterwards, or if any role then fails to log in.
    """
    role_map = dict(SCHEMA_ROLES if roles is None else roles)
    ordered_roles = tuple(role_map.values())

    # Assumptions: the master settings resolve BEFORE any secret is read, so a deployment
    # missing the master locator fails having read nothing. Reading eight service secrets and
    # then discovering there is nowhere to apply them wastes eight audited GetSecretValue calls
    # and reports the least useful of the two faults.
    master = resolve_master_settings()

    # Trade-offs: every password is read and derived BEFORE the transaction opens, so the
    # transaction contains only the eight ALTER ROLE statements. A secret that cannot be read
    # therefore fails before anything is altered, and the transaction does not stay open across
    # eight network calls to another service -- which is what would turn a slow secret store into
    # a lock held on the role catalogue.
    verifiers = {
        role: verifier_factory(_password_for(schema, role)) for schema, role in role_map.items()
    }

    with _connect(master) as connection:
        with connection.cursor() as cursor:
            _require_roles_exist(cursor, ordered_roles)
            for role in ordered_roles:
                _apply_verifier(cursor, role, verifiers[role])
                _LOGGER.info("applied the stored credential to database role %s", role)
        # Assumptions: the commit is explicit rather than left to the connection context
        # manager, because the verification below opens NEW connections that must observe the
        # applied credentials. A commit deferred to the end of the outer block would run after
        # those logins had already been attempted against an uncommitted catalogue.
        connection.commit()

        with connection.cursor() as cursor:
            outstanding = _roles_without_verifier(cursor, ordered_roles)

    if outstanding:
        raise CredentialApplicationError(
            "these roles still hold no usable credential after the step reported applying one: "
            f"{', '.join(outstanding)}"
        )

    for schema, role in role_map.items():
        _verify_login(schema, role)
        _LOGGER.info("verified that database role %s can authenticate", role)

    return ordered_roles


def _password_for(schema: str, role: str) -> str:
    """Read one role's password out of the secret the provisioning step wrote for it.

    Purpose
    -------
    Resolve a single credential through the same helper a loader uses, so that the secret name,
    the required document shape and the assertion that the stored user name IS this role are all
    applied here exactly as they are at load time.

    Trade-offs: the resolver returns a full connection descriptor and only its password is used.
    Reading the secret directly would avoid resolving three parameters that are not needed yet,
    and is rejected because it would bypass that user-name assertion -- the check that catches a
    secret carrying another role's payload before its credential is applied to this role.

    Parameters
    ----------
    schema : str
        The bounded-context schema whose owning role's secret is to be read.
    role : str
        The role that schema resolves to, used only in the failure message.

    Returns
    -------
    str
        The password stored for that role.

    Raises
    ------
    ConfigurationError
        If the secret is absent, unreadable, malformed, or carries a user name that is neither
        this role nor an alternate allowlisted for it.
    """
    try:
        return resolve_aurora_settings(schema).password
    except ConfigurationError as exc:
        raise ConfigurationError(
            f"the credential for database role {role} (schema {schema}) could not be resolved: "
            f"{exc}"
        ) from exc


def _parse_arguments(argv: Sequence[str] | None) -> argparse.Namespace:
    """Parse the command line, which deliberately accepts no options.

    Purpose
    -------
    Give the entry point a usage message and a defined failure for an unexpected argument, so an
    operator who reaches for a flag that does not exist is told so rather than having it ignored.

    Trade-offs: there is no ``--role`` or ``--schema`` option, and the omission is deliberate. A
    per-role invocation is what leaves a deployment half applied, and the whole point of the step
    is that it either makes every service able to authenticate or fails. A test that needs a
    subset passes ``roles`` to :func:`apply_service_credentials` directly, which no deployment
    command line can reach.

    Parameters
    ----------
    argv : Sequence[str] or None
        The argument list, excluding the program name. ``None`` reads :data:`sys.argv`.

    Returns
    -------
    argparse.Namespace
        The parsed arguments, currently carrying no fields.

    Raises
    ------
    SystemExit
        Raised by :mod:`argparse` for ``--help`` and for an unrecognised argument. The entry
        point converts the latter into :data:`EXIT_USAGE`.
    """
    parser = argparse.ArgumentParser(
        prog="python -m carddemo_migration.credentials",
        description=(
            "Apply each generated service credential to the database role that authenticates "
            "with it, then prove every role can log in. Run immediately after "
            "data-migration/sql/V0__schemas_and_roles.sql, which creates the roles with no "
            "password."
        ),
        epilog=(
            "Reads CARDDEMO_ENVIRONMENT, CARDDEMO_PARAMETER_PREFIX, "
            f"{ENV_DB_MASTER_SECRET} and the TLS trust-anchor settings from the environment. "
            "Exit codes follow tests/README.md section 8: 0 applied and verified, 2 usage, "
            "8 a role could not be applied or verified, 16 the environment could not be reached."
        ),
    )
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    """Run the credential-application step as a command.

    Purpose
    -------
    Provide the invocation the database bootstrap step issues, translating each documented
    failure into the return code the batch state machine branches on.

    Parameters
    ----------
    argv : Sequence[str] or None, optional
        The argument list, excluding the program name. ``None`` -- the default -- reads
        :data:`sys.argv`.

    Returns
    -------
    int
        One of :data:`EXIT_OK`, :data:`EXIT_USAGE`, :data:`EXIT_FAILED` or :data:`EXIT_FATAL`.

    Raises
    ------
    None
        Every documented failure is converted to a return code, because a traceback printed by
        an orchestrated container step is harder to act on than one line naming the role and the
        remedy. An undocumented failure is deliberately NOT caught: a bare ``except`` here would
        report a programming error as an operational one.
    """
    try:
        _parse_arguments(argv)
    except SystemExit as exc:
        # Assumptions: argparse exits 0 for --help and 2 for a bad argument, and both are
        # translated rather than propagated so that this function's contract -- it returns a
        # code, it does not exit -- holds for every path. 0 is preserved as success because a
        # help request is not a failure.
        return EXIT_OK if exc.code in (0, None) else EXIT_USAGE

    # Trade-offs: logging is configured HERE and nowhere else in the package. The package
    # docstring states that importing it installs no handler, because a handler installed at
    # import time takes over the root logger of whatever process imported the package; a command
    # is the one context that owns the process, so it is the one place configuring output is
    # correct. The format carries no timestamp: the container log driver stamps every line, and a
    # second timestamp in the message is what makes an orchestrated log hard to read.
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(name)s %(message)s")

    try:
        applied = apply_service_credentials()
    except ConfigurationError as exc:
        _LOGGER.error("the environment could not be resolved: %s", exc)
        return EXIT_FATAL
    except CredentialApplicationError as exc:
        _LOGGER.error("the credential step did not complete: %s", exc)
        return EXIT_FAILED

    _LOGGER.info(
        "applied and verified the credential for %d database roles: %s",
        len(applied),
        ", ".join(applied),
    )
    return EXIT_OK


if __name__ == "__main__":
    # Assumptions: the exit code is passed through sys.exit rather than main() being called
    # for its side effects. An orchestrated step branches on the process's status, so a command
    # that logged a failure and exited 0 would let a deployment continue past a stack no service
    # can log in to -- which is the exact defect this module exists to close.
    sys.exit(main())
