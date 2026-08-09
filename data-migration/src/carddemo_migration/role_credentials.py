"""Apply each generated credential to its PostgreSQL login role, without disclosing it.

Purpose
-------
Close the one gap that stood between a provisioned CardDemo stack and a stack whose services
could actually authenticate. ``data-migration/sql/V0__schemas_and_roles.sql`` creates all eight
bounded-context login roles with ``LOGIN`` and deliberately **no** password clause, because a
password written into a committed SQL file is the defect the whole migration is correcting.
``infra/modules/secrets`` generates one credential per role at apply time and stores it in AWS
Secrets Manager. Nothing joined the two: the roles existed, the credentials existed, and no role
could authenticate. This module is that join, and it is the mechanism named by
``var.service_credential_application = "external_bootstrap"``.

It does three things, and the third is as important as the first two:

1. :func:`scram_sha256_verifier` derives PostgreSQL's SCRAM-SHA-256 verifier from a plaintext
   password, entirely in this process.
2. :func:`apply_role_credential` issues ``ALTER ROLE ... PASSWORD '<verifier>'``, so the server
   is handed the verifier and never the password.
3. :func:`verify_role_credentials` **raises** when any role still has no stored credential, which
   is what makes an incomplete bootstrap a failure rather than a report.

Why the verifier is computed here rather than sent as a password
---------------------------------------------------------------
``ALTER ROLE ... PASSWORD 'plaintext'`` is the obvious form and it leaks. PostgreSQL accepts a
pre-computed verifier in the same position, and sending that instead means the plaintext never
crosses the connection at all. The difference is not theoretical -- it removes the password from
every one of these places at once:

* the server log, whenever ``log_statement`` is ``ddl`` or ``all``, and in any
  ``log_min_duration_statement`` slow-statement line;
* ``pg_stat_activity.query`` for the duration of the statement, readable by any
  ``pg_read_all_stats`` member;
* the ``psql`` history file, if an operator ever runs the statement by hand;
* the shell history of whatever invoked it.

V0 rejects the manual alternative for exactly these reasons, and this module is what makes that
rejection actionable rather than aspirational.

Why possession of the verifier is not possession of the password
---------------------------------------------------------------
A verifier holds ``StoredKey`` and ``ServerKey``, not the password and not ``ClientKey``. In
SCRAM the client proves itself with ``ClientProof = ClientKey XOR HMAC(StoredKey, AuthMessage)``.
``StoredKey`` is ``SHA256(ClientKey)``, so a holder of the verifier can compute the signature but
cannot recover ``ClientKey`` and therefore cannot produce a valid proof. That asymmetry is why
writing a verifier into a statement is materially different from writing a password into one, and
it is the property this module depends on.

Why there is no database driver imported at module scope
-------------------------------------------------------
Every function that touches a database takes an already-open connection. Nothing here imports
``psycopg``, opens a connection, resolves a region or reads an environment variable. Two
consequences were wanted. The verifier derivation -- the part that must be exactly right, because
a wrong verifier locks a role out with no error at the point of the mistake -- is testable with no
database and no AWS. And the caller keeps ownership of the connection, which matters because that
connection must be the one ``config.AuroraConnectionSettings`` produces: ``sslmode=verify-full``
with an explicit root certificate. A module that opened its own connection could quietly open a
weaker one.

Notes
-----
No function here logs, prints, returns or raises a password, a verifier, or any substring of
either. Failures name the ROLE. That is a deliberate constraint on this file and not an accident
of the current implementation: a bootstrap tool that reports what it failed to apply is a
bootstrap tool that writes credentials into a log.
"""

from __future__ import annotations

import base64
import hashlib
import hmac
import re
import secrets
from collections.abc import Mapping, Sequence
from typing import Any, Protocol

from carddemo_migration.config import (
    LOGIN_ROLE_NAMES,
    MIGRATION_SCHEMA_ROLES,
    SCHEMA_ROLES,
    quote_identifier,
)

__all__ = [
    "SCRAM_DEFAULT_ITERATIONS",
    "SCRAM_SALT_BYTES",
    "RoleCredentialError",
    "apply_role_credential",
    "bootstrap_role_credentials",
    "credentials_from_config",
    "roles_without_credential",
    "scram_sha256_verifier",
    "service_role_names",
    "verify_role_credentials",
]


SCRAM_DEFAULT_ITERATIONS = 4096
"""PBKDF2 iteration count written into the verifier.

WHY : Assumptions: this matches the server's own default for
``password_encryption = scram-sha-256``, which is what
``infra/modules/aurora-postgresql`` pins through its cluster parameter group. Matching it means a
credential applied by this module is indistinguishable from one the server would have derived
itself, so a later ``\\password`` in psql produces a verifier of the same strength and nothing
regresses silently.

Trade-offs: a higher count is stronger per guess and is accepted by the server, and it was
rejected as a DEFAULT for a reason specific to this position. These credentials are 32-character
values generated by ``random_password`` and never chosen by a human, so the brute-force resistance
that iteration count buys is already provided by the entropy of the value; raising it would slow
every authentication for a threat model that does not apply. A caller who needs a different count
passes ``iterations``.
"""

SCRAM_SALT_BYTES = 16
"""Length of the per-credential random salt, in bytes.

WHY : Assumptions: sixteen bytes is what the server generates and is ample, because a salt's job
is uniqueness across credentials rather than secrecy -- it is stored in the verifier in clear.
Drawn from :mod:`secrets` and never from :mod:`random`, so two roles bootstrapped in one process
cannot receive a predictable salt sequence.
"""


# Assumptions: this pattern is the injection control for
#       :func:`apply_role_credential`, and it is why that function can embed a
#       value in a statement at all. ``ALTER ROLE`` is utility (DDL) syntax and
#       PostgreSQL accepts NO bind parameter in the PASSWORD position, so the
#       value must be a literal -- the usual "always parameterise" answer is not
#       available here. Rather than trust that the verifier is well-formed, the
#       string is matched against this pattern first: it admits only
#       ``SCRAM-SHA-256$``, digits, base64 (``A-Za-z0-9+/=``) and the two
#       delimiters, so a matching string provably contains no single quote, no
#       backslash, no semicolon and no whitespace, and therefore cannot end the
#       literal or begin a second statement. The safety is a property of the
#       accepted alphabet rather than of escaping, which is the stronger form.
_VERIFIER_PATTERN = re.compile(
    r"\ASCRAM-SHA-256\$[0-9]+:[A-Za-z0-9+/]+={0,2}\$[A-Za-z0-9+/]+={0,2}:[A-Za-z0-9+/]+={0,2}\Z"
)


class RoleCredentialError(RuntimeError):
    """Raised when a credential cannot be derived, applied, or verified as present.

    Purpose
    -------
    Give the bootstrap step one failure type a caller can catch, and keep every message free of
    credential material. Instances name the ROLE, the STATEMENT that failed, or the RULE that was
    violated -- never a password, a verifier, or a fragment of either.
    """


class _Cursor(Protocol):
    """Structural type for the cursor behaviour this module uses.

    Purpose
    -------
    Describe the two cursor operations needed -- execute a statement, read rows -- without
    importing a driver. Declaring the shape keeps the type checker useful while leaving the
    concrete cursor to the caller's ``psycopg`` connection.
    """

    def execute(self, query: str, params: Sequence[Any] | None = ..., /) -> Any:
        """Execute one statement, optionally with bound parameters.

        Parameters
        ----------
        query : str
            The statement to execute.
        params : Sequence[Any] | None, optional
            Values bound by the driver, or ``None`` for a statement carrying no parameter.

        Returns
        -------
        Any
            Whatever the driver's own cursor returns, which this module never reads; rows
            are taken from :meth:`fetchall` instead so the protocol stays satisfiable by a
            test double that returns nothing useful here.
        """

    def fetchall(self) -> Sequence[Any]:
        """Return every remaining row of the current result.

        Returns
        -------
        Sequence[Any]
            The rows, each indexable by column position. Empty when the statement selected
            nothing, which is how a missing credential is reported to this module.
        """

    def __enter__(self) -> _Cursor:
        """Enter the cursor's context manager.

        Returns
        -------
        _Cursor
            This cursor, so a ``with`` statement binds the same object the caller opened.
        """

    def __exit__(self, *exc_info: object) -> None:
        """Leave the cursor's context manager, closing it.

        Parameters
        ----------
        *exc_info : object
            The exception triple Python supplies, ignored here: this protocol declares no
            suppression behaviour, so an exception raised inside the block propagates.

        Returns
        -------
        None
            Nothing. A falsey return is what lets the exception propagate, and returning
            ``None`` states that explicitly rather than relying on the default.
        """


class _Connection(Protocol):
    """Structural type for the connection behaviour this module uses.

    Purpose
    -------
    Accept any object exposing ``cursor()``, which every ``psycopg`` connection does, so that this
    module needs no driver import and its tests need no database. See the module docstring for why
    the connection is the caller's to open.
    """

    def cursor(self) -> _Cursor:
        """Return a new cursor over this connection.

        Returns
        -------
        _Cursor
            A cursor usable as a context manager, which is the only shape this module
            drives it in.
        """


def service_role_names() -> tuple[str, ...]:
    """Return the login role names that must hold a credential, in a stable order.

    Purpose
    -------
    Provide the one role inventory this module works from, taken from
    :data:`carddemo_migration.config.LOGIN_ROLE_NAMES` rather than restated here.

    WHY : Assumptions: single-sourcing the inventory is what stops this module and V0 from
    disagreeing about which roles exist. A hard-coded list here would be a second place to add a
    ninth bounded context, and the failure it produces is the quiet one -- a role created by V0
    that this module never applies a credential to would be reported as missing by
    :func:`verify_role_credentials` only if it appeared in this list, so an omission here would
    hide itself.

    WHY : Refactoring Rationale: this returned the EIGHT connection roles
    (``SCHEMA_ROLES.values()``). It now returns fifteen, because V0 creates seven
    ``carddemo_<context>_migrator`` login roles alongside them -- the credentials Flyway
    authenticates as so that no runtime credential holds DDL authority. Leaving them out was the
    self-hiding omission this docstring warns about in the paragraph above: the seven roles would
    exist with a null ``rolpassword``, :func:`verify_role_credentials` would report the cluster
    fully bootstrapped, and the first affected service would fail to start on an authentication
    error that named a role no bootstrap report had mentioned.

    WHY : Assumptions: this inventory is the LOGIN roles only, and excluding the owner roles is a
    correctness requirement rather than a simplification. V0 also creates eight
    ``carddemo_<context>_owner`` roles, which own the schemas and are created ``NOLOGIN``; a
    ``NOLOGIN`` role never authenticates, so its ``rolpassword`` is null permanently and
    correctly. Including one here would make :func:`verify_role_credentials` fail forever on a
    cluster that is in fact fully bootstrapped -- and a verification that cannot pass is
    indistinguishable, to whoever next reads it, from one that is not worth running. Verified
    against a live PostgreSQL 17.10 catalogue after replaying V0: of the twenty-three
    ``carddemo*`` roles it creates, exactly fifteen have ``rolcanlogin`` true and they are
    exactly :data:`~carddemo_migration.config.LOGIN_ROLE_NAMES`; the master user has a credential
    already, and all eight ``_owner`` roles have ``rolcanlogin`` false.

    Returns
    -------
    tuple of str
        Every service login role name -- eight connection roles and seven migration roles --
        sorted so that output ordering is deterministic across runs and a diff of two bootstrap
        logs is meaningful.
    """
    return LOGIN_ROLE_NAMES


def scram_sha256_verifier(
    password: str,
    *,
    iterations: int = SCRAM_DEFAULT_ITERATIONS,
    salt: bytes | None = None,
) -> str:
    """Derive the PostgreSQL SCRAM-SHA-256 verifier for ``password``.

    Purpose
    -------
    Produce the exact string PostgreSQL stores in ``pg_authid.rolpassword``, so that
    :func:`apply_role_credential` can hand the server a verifier instead of a password. The
    format is
    ``SCRAM-SHA-256$<iterations>:<b64 salt>$<b64 StoredKey>:<b64 ServerKey>`` where
    ``SaltedPassword = PBKDF2-HMAC-SHA256(password, salt, iterations)``,
    ``ClientKey = HMAC(SaltedPassword, "Client Key")``, ``StoredKey = SHA256(ClientKey)`` and
    ``ServerKey = HMAC(SaltedPassword, "Server Key")``.

    WHY : Assumptions: the password is encoded UTF-8 and is NOT normalised. RFC 5802 specifies
    SASLprep, and PostgreSQL applies it only to passwords it hashes itself; a verifier supplied
    ready-made is stored verbatim. Skipping normalisation is therefore the behaviour that MATCHES
    the server for the values this module actually handles -- ``random_password`` output over a
    restricted ASCII alphabet, on which SASLprep is the identity function. Applying it would be
    the riskier choice, because a normalisation this module performed and the server did not would
    produce a verifier that authenticates for a password nobody holds.

    WHY : Trade-offs: the salt defaults to a fresh random value rather than being required, so the
    common call is short and correct. It is injectable purely so a test can pin it and compare
    against a known-good vector; production code never passes it. A fixed salt across roles would
    let one cracked verifier be reused against the others, which is why the default is per call
    and not per process.

    Parameters
    ----------
    password : str
        The plaintext credential to derive from. Never logged, returned, or included in any
        exception raised here.
    iterations : int, optional
        PBKDF2 iteration count, defaulting to :data:`SCRAM_DEFAULT_ITERATIONS`. Must be positive.
    salt : bytes or None, optional
        Salt to use. ``None`` -- the default and the only production value -- draws
        :data:`SCRAM_SALT_BYTES` fresh bytes from :mod:`secrets`.

    Returns
    -------
    str
        The verifier, safe to embed in an ``ALTER ROLE`` statement because its alphabet excludes
        every character that could terminate a SQL literal.

    Raises
    ------
    RoleCredentialError
        If ``password`` is not text or is empty, if ``iterations`` is not a positive integer, or
        if ``salt`` is supplied and is not non-empty bytes.
    """
    if not isinstance(password, str) or not password:
        # Assumptions: an empty password is refused rather than derived. A verifier for the
        #       empty string is perfectly computable and would be applied without complaint,
        #       leaving a role whose password is "" -- which is worse than the no-credential state
        #       this module exists to fix, because it authenticates.
        raise RoleCredentialError(
            "A credential must be a non-empty string. The value read for this role was empty or "
            "was not text; check that the Secrets Manager entry holds a JSON document with a "
            "non-blank 'password' key. The value itself is deliberately not shown."
        )
    if not isinstance(iterations, int) or isinstance(iterations, bool) or iterations < 1:
        raise RoleCredentialError(
            f"The SCRAM iteration count must be a positive integer, not {iterations!r}."
        )
    if salt is None:
        salt = secrets.token_bytes(SCRAM_SALT_BYTES)
    elif not isinstance(salt, bytes) or not salt:
        raise RoleCredentialError(
            "The SCRAM salt must be non-empty bytes when supplied, or None to generate one."
        )

    salted_password = hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), salt, iterations)
    client_key = hmac.new(salted_password, b"Client Key", hashlib.sha256).digest()
    stored_key = hashlib.sha256(client_key).digest()
    server_key = hmac.new(salted_password, b"Server Key", hashlib.sha256).digest()

    verifier = (
        f"SCRAM-SHA-256${iterations}:{base64.b64encode(salt).decode('ascii')}"
        f"${base64.b64encode(stored_key).decode('ascii')}:"
        f"{base64.b64encode(server_key).decode('ascii')}"
    )

    # Assumptions: the derived value is checked against the same pattern
    #       :func:`apply_role_credential` enforces, here at the point of
    #       construction. The two checks are not redundant: this one asserts that
    #       this function's own formatting is well-formed, so a future edit to the
    #       f-string above cannot produce a value that the other check rejects
    #       later with a message about its caller. Failing at the source names the
    #       real culprit.
    if not _VERIFIER_PATTERN.match(verifier):
        raise RoleCredentialError(
            "Internally derived SCRAM verifier does not match the expected format. This is a "
            "defect in scram_sha256_verifier rather than in its input."
        )
    return verifier


def apply_role_credential(
    connection: _Connection,
    role: str,
    password: str,
    *,
    iterations: int = SCRAM_DEFAULT_ITERATIONS,
) -> None:
    """Set ``role``'s stored credential from ``password``, sending only the derived verifier.

    Purpose
    -------
    Perform the single privileged statement the bootstrap exists to perform. The connection must
    belong to a role able to ``ALTER`` the target role -- the RDS-managed master user, reached
    through ``module.aurora.master_user_secret_arn`` -- because a role with no credential cannot
    connect to set its own.

    WHY : Assumptions: the role name is passed through
    :func:`carddemo_migration.config.quote_identifier` and the verifier is embedded as a literal.
    The asymmetry is deliberate and each half has its own reason. ``ALTER ROLE`` is utility syntax
    that accepts no bind parameter in EITHER position, so neither value can be parameterised and
    the usual answer does not apply. The identifier is therefore quoted by the shared helper that
    every other statement in this package uses, and the verifier is admitted only after matching
    an alphabet -- base64, digits and two delimiters -- that provably contains no quote, backslash,
    semicolon or whitespace. Validating the alphabet is stronger than escaping, because there is
    nothing left to escape.

    WHY : Trade-offs: this function does NOT commit. The caller owns the transaction, so that a
    run applying eight credentials either applies all eight or none. Committing per role was
    rejected because a partial success is the state hardest to recover from -- some services would
    authenticate and others would not, with nothing recording which.

    Parameters
    ----------
    connection : _Connection
        Open connection whose current role may alter ``role``. Not committed or closed here.
    role : str
        Login role to update. Validated by :func:`~carddemo_migration.config.quote_identifier`.
    password : str
        Plaintext credential. Never sent to the server, never logged, never in an exception.
    iterations : int, optional
        PBKDF2 iteration count, defaulting to :data:`SCRAM_DEFAULT_ITERATIONS`.

    Returns
    -------
    None
        Nothing. The effect is the altered role on the server, and the caller's own
        transaction decides whether it becomes durable; returning a value would invite a
        caller to treat the result as advisory when the only outcomes are success and the
        exception below.

    Raises
    ------
    RoleCredentialError
        If ``role`` is not one of the known service roles, if the credential cannot be derived,
        or if the statement fails.
    """
    known = service_role_names()
    if role not in known:
        # Assumptions: the role is checked against the shared inventory before any statement
        #       is built. Without this, a typo in a secret name would apply a credential to
        #       whatever role the typo happened to name -- including a superuser -- and the run
        #       would report success. Refusing an unknown name keeps the blast radius of a
        #       mis-keyed secret to a failed run.
        raise RoleCredentialError(
            f"Refusing to set a credential for unknown role {role!r}. This module applies "
            f"credentials only to the CardDemo service roles created by "
            f"data-migration/sql/V0__schemas_and_roles.sql: {', '.join(known)}."
        )

    verifier = scram_sha256_verifier(password, iterations=iterations)
    if not _VERIFIER_PATTERN.match(verifier):
        raise RoleCredentialError(
            f"Refusing to build an ALTER ROLE statement for {role!r}: the derived verifier "
            "contains characters outside the expected base64 alphabet, so it cannot be safely "
            "embedded as a SQL literal."
        )

    statement = f"ALTER ROLE {quote_identifier(role)} PASSWORD '{verifier}'"
    try:
        with connection.cursor() as cursor:
            cursor.execute(statement)
    except RoleCredentialError:
        raise
    except Exception as error:  # noqa: BLE001 - re-raised as this module's own type, see below
        # Trade-offs: the driver's exception is caught broadly and re-raised as
        #       RoleCredentialError with the ROLE named and the original attached as the cause.
        #       Catching broadly is right here because this module imports no driver, so it cannot
        #       name psycopg's exception classes without acquiring the dependency the module
        #       docstring explains it deliberately avoids. The original is preserved through
        #       `from error`, so nothing is hidden; what is gained is that a caller catches one
        #       type. The message says which role failed and never quotes the statement, because
        #       the statement contains the verifier.
        raise RoleCredentialError(
            f"Failed to set the stored credential for role {role!r}. The connecting role must be "
            f"able to ALTER it -- use the cluster's master user -- and the role must already "
            f"exist, which data-migration/sql/V0__schemas_and_roles.sql is what guarantees. The "
            f"failing statement is not reproduced here because it contains the credential "
            f"verifier."
        ) from error


def roles_without_credential(connection: _Connection) -> tuple[str, ...]:
    """Return the service roles that currently have no stored credential.

    Purpose
    -------
    Answer the question V0 can only report on: which of the fifteen login roles exist but cannot
    authenticate. Reads ``pg_authid.rolpassword`` and tests it for null, never selecting or
    returning the column's value.

    WHY : Assumptions: ``pg_authid`` is read rather than ``pg_roles`` because ``pg_roles`` blanks
    that column for every caller, so a check against it would report every role as having a
    credential and would always pass -- a verification that cannot fail. Reading ``pg_authid``
    requires a superuser or equivalent, which the bootstrap connection is;
    :func:`verify_role_credentials` documents why that requirement is treated as a hard one
    rather than skipped.

    WHY : Trade-offs: the role list is passed as a bound parameter and compared with a join rather
    than interpolated into an ``IN`` list. This is a plain ``SELECT`` where parameters ARE
    available, unlike the ``ALTER ROLE`` above, so the safe form is simply used.

    Parameters
    ----------
    connection : _Connection
        Open connection whose current role can read ``pg_authid``.

    Returns
    -------
    tuple of str
        Role names with a null ``rolpassword``, sorted. Empty when every role holds a credential.
        A role absent from the server entirely is NOT reported here -- absence is a schema
        problem that V0 owns, and conflating it with a missing credential would send an operator
        to the wrong tool.

    Raises
    ------
    RoleCredentialError
        If the catalogue cannot be read.
    """
    roles = list(service_role_names())
    query = (
        "SELECT candidate.role_name "
        "FROM unnest(%s::text[]) AS candidate(role_name) "
        "JOIN pg_authid ON pg_authid.rolname = candidate.role_name "
        "WHERE pg_authid.rolpassword IS NULL "
        "ORDER BY candidate.role_name"
    )
    try:
        with connection.cursor() as cursor:
            cursor.execute(query, (roles,))
            rows = cursor.fetchall()
    except Exception as error:  # noqa: BLE001 - normalised to this module's type, as above
        raise RoleCredentialError(
            "Could not read pg_authid to determine which roles hold a stored credential. This "
            "check requires a connection whose role can read that catalogue -- the cluster's "
            "master user can. An unreadable catalogue is treated as a failure rather than as "
            "evidence that no credential is missing."
        ) from error
    return tuple(str(row[0]) for row in rows)


def verify_role_credentials(connection: _Connection) -> None:
    """Raise unless every service role holds a stored credential.

    Purpose
    -------
    Be the FAILING verification that the bootstrap is complete. This is the counterpart to the
    ``RAISE NOTICE`` in ``data-migration/sql/V0__schemas_and_roles.sql``, and the difference in
    consequence is the whole point of it existing.

    WHY : Refactoring Rationale: V0 reports missing credentials and continues, and it is right to
    -- V0 CREATES the roles, and it necessarily runs before anything can apply a credential to
    them, so a V0 that failed on the condition would fail on every first run and could never
    succeed. The check therefore cannot live there. It lives here, in the step that runs AFTER
    application, where "a role has no credential" is unambiguously a failure and not an expected
    intermediate state. Reporting and asserting are both needed; what was missing was the second.

    WHY : Trade-offs: this raises instead of returning a list, so a caller cannot accidentally
    ignore it. A boolean or a list is easy to call and discard -- which is precisely how the
    original gap stayed invisible through an apply that succeeded -- whereas an exception has to
    be handled deliberately. :func:`roles_without_credential` remains available for a caller that
    genuinely wants to inspect rather than assert.

    Parameters
    ----------
    connection : _Connection
        Open connection whose current role can read ``pg_authid``.

    Returns
    -------
    None
        Nothing, deliberately. Returning normally is the assertion that every service role
        holds a credential; the trade-off recorded above is that a value a caller could
        discard is exactly how the original gap stayed invisible.

    Raises
    ------
    RoleCredentialError
        If any service role has no stored credential, or if the catalogue cannot be read.
    """
    outstanding = roles_without_credential(connection)
    if outstanding:
        raise RoleCredentialError(
            "These service roles exist with no stored credential and cannot authenticate: "
            f"{', '.join(outstanding)}. Run the credential bootstrap "
            "(carddemo_migration.role_credentials.bootstrap_role_credentials) against the "
            "cluster's master user before starting any service, because a service whose role "
            "has no password fails at datasource initialisation rather than at first request."
        )


def credentials_from_config() -> dict[str, str]:
    """Resolve one credential per service role from AWS Secrets Manager.

    Purpose
    -------
    Compose the mapping :func:`bootstrap_role_credentials` consumes, using the resolution
    machinery that already exists in :mod:`carddemo_migration.config` rather than a second copy of
    it. Every value is read at call time from Secrets Manager; none is committed anywhere.

    WHY : Assumptions: the schemas are iterated and ``resolve_aurora_settings`` is called per
    schema, because that function is the package's single entry point for "read this role's
    credential", and it already enforces the properties that matter -- the secret document must
    carry both keys, the connection parameters it returns are ``sslmode=verify-full`` with an
    explicit root certificate, and its ``__repr__`` redacts the password so an accidental log of
    the object cannot leak it. Reading the secrets directly with boto3 here would duplicate the
    resolution and lose all three.

    WHY : Refactoring Rationale: a second pass over ``MIGRATION_SCHEMA_ROLES`` calling
    ``resolve_migration_settings`` was added. Without it this function returned eight entries
    while :func:`service_role_names` expected fifteen, so :func:`bootstrap_role_credentials`
    would refuse the run for an incomplete mapping -- which is the right refusal, but the fix
    belongs here: the seven migration credentials exist in Secrets Manager and this is the
    function whose job is to read them. The passes are separate rather than merged because the two
    tiers resolve through two different entry points, and a single loop would have to branch on
    whether a schema has a migration role -- restating the exclusion
    ``MIGRATION_SCHEMA_ROLES`` already encodes.

    WHY : Trade-offs: the returned mapping holds plaintext credentials in process memory for the
    life of the bootstrap. That is unavoidable for anything that applies them, and it is bounded
    deliberately: the mapping is built immediately before use, is never written anywhere, and no
    function in this module logs or returns its values. It is called out because a caller holding
    this dictionary is holding every service credential at once and should not keep it.

    Returns
    -------
    dict of str to str
        Role name to plaintext credential, one entry per login role -- eight connection roles and
        seven migration roles.

    Raises
    ------
    RoleCredentialError
        If two schemas resolve to the same role with different credentials, which would mean the
        secrets and the role inventory disagree.
    ConfigurationError
        Propagated from :func:`carddemo_migration.config.resolve_aurora_settings` when a setting
        or secret is absent, unreadable or malformed.
    """
    # Assumptions: imported inside the function rather than at module scope. The import
    #       reaches boto3 through config's resolution path, and the module docstring records that
    #       importing this file must not require AWS or a driver -- that is what lets
    #       scram_sha256_verifier be tested in isolation. A top-level import would undo it.
    from carddemo_migration.config import resolve_aurora_settings, resolve_migration_settings

    credentials: dict[str, str] = {}
    for inventory, resolve in (
        (SCHEMA_ROLES, resolve_aurora_settings),
        (MIGRATION_SCHEMA_ROLES, resolve_migration_settings),
    ):
        for schema, role in sorted(inventory.items()):
            settings = resolve(schema)
            existing = credentials.get(role)
            if existing is not None and existing != settings.password:
                raise RoleCredentialError(
                    f"Two schemas resolve to role {role!r} with different credentials. The role "
                    f"inventory and the Secrets Manager entries disagree; resolve that before "
                    f"bootstrapping, because applying either value would leave one service "
                    f"unable to authenticate."
                )
            credentials[role] = settings.password
    return credentials


def bootstrap_role_credentials(
    connection: _Connection,
    credentials: Mapping[str, str] | None = None,
    *,
    iterations: int = SCRAM_DEFAULT_ITERATIONS,
) -> tuple[str, ...]:
    """Apply every service role's credential in one transaction, then verify none is missing.

    Purpose
    -------
    The entry point the ``external_bootstrap`` mechanism calls. Applies one credential per role
    using :func:`apply_role_credential`, then asserts completeness with
    :func:`verify_role_credentials` on the same connection, so a run that reports success has
    actually left every role able to authenticate.

    WHY : Assumptions: the whole set is applied before anything is verified, and the caller
    commits once around the call. A per-role commit-and-check was rejected because a partial
    bootstrap is the worst outcome available -- some services authenticate, others do not, and the
    stack half-starts. All-or-nothing means a failure leaves the cluster exactly as it was, which
    is a state an operator can reason about.

    WHY : Trade-offs: verification runs inside the same transaction as the application, so it
    reads this transaction's own uncommitted writes and therefore proves that the statements took
    effect -- not that a previous run had already done the work. Verifying after the commit was
    rejected for the opposite reason: it would pass on a run that applied nothing to a cluster
    that was already bootstrapped, which is exactly the false success this function must not
    produce.

    Parameters
    ----------
    connection : _Connection
        Open connection whose current role may alter the service roles and read ``pg_authid``.
        Not committed or closed here; the caller owns the transaction.
    credentials : Mapping of str to str, or None, optional
        Role name to plaintext credential. ``None`` -- the default -- resolves them with
        :func:`credentials_from_config`.
    iterations : int, optional
        PBKDF2 iteration count, defaulting to :data:`SCRAM_DEFAULT_ITERATIONS`.

    Returns
    -------
    tuple of str
        The role names whose credentials were applied, sorted. Never any credential.

    Raises
    ------
    RoleCredentialError
        If a credential is absent from the mapping, cannot be derived or applied, or if any role
        still has no stored credential once every statement has run.
    """
    resolved = dict(credentials) if credentials is not None else credentials_from_config()

    expected = service_role_names()
    missing = tuple(role for role in expected if not resolved.get(role))
    if missing:
        # Assumptions: a role absent from the mapping fails the run BEFORE any statement is
        #       issued, rather than being skipped. Skipping is how a partially bootstrapped
        #       cluster comes about, and the skip would be reported as a success for the roles
        #       that did have entries.
        raise RoleCredentialError(
            "No credential was supplied for these service roles: "
            f"{', '.join(missing)}. Every role in the inventory must have one, because a role "
            "left without a credential cannot authenticate and nothing later in the deployment "
            "reports it. Check that infra/modules/secrets created a Secrets Manager entry per "
            "role and that this process can read each one."
        )

    unexpected = tuple(sorted(role for role in resolved if role not in expected))
    if unexpected:
        raise RoleCredentialError(
            f"Refusing to bootstrap unknown roles: {', '.join(unexpected)}. This module applies "
            f"credentials only to the CardDemo service roles created by "
            f"data-migration/sql/V0__schemas_and_roles.sql. An unexpected name means the secret "
            f"inventory and the role inventory disagree, and applying it could target a role the "
            f"migration does not own."
        )

    applied: list[str] = []
    for role in expected:
        apply_role_credential(connection, role, resolved[role], iterations=iterations)
        applied.append(role)

    verify_role_credentials(connection)
    return tuple(applied)
