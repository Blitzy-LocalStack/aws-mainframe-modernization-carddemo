"""Open and certify the one session a per-dataset verification is allowed to run on.

Purpose
-------
Give the three per-dataset verification passes an authority that cannot alter what they certify.
Each of them reads the loaded rows back and compares them against the extract they came from, so
each needs row-level ``SELECT`` on a base table -- and each used to obtain it by connecting as the
bounded context's own service role, which holds ``SELECT``, ``INSERT`` and ``UPDATE`` on exactly
those rows. A verifier able to write what it verifies certifies nothing: a defect in it could
repair the evidence a reader is relying on it to judge, and no care taken in the client removes
that, because the authority is real whether or not it is used.

This module resolves, opens and CERTIFIES a session on ``carddemo_verifier`` instead -- the one
login role that may read every migrated record and write none of them. Certification is three
statements against the server, in a fixed order: ask who the session is, enforce read-only for
every transaction on it, then read that setting back from the server. Nothing here executes a
verification query; the passes do that on the connection this module hands them.

Parameters
----------
None
    A module takes no argument. Every published callable documents its own parameters at its own
    definition.

Returns
-------
None
    Importing binds names only. It opens no connection, constructs no client, reads no environment
    variable and imports neither third-party client at module scope.

Raises
------
VerificationSessionError
    Raised by :func:`require_verification_session` when a session is not the verifier, or when the
    server does not confirm the session is read-only. Nothing else is raised from this module:
    settings resolution raises :class:`~carddemo_migration.config.ConfigurationError` and the
    connection attempt raises :class:`~carddemo_migration.loaders.aurora.AuroraLoadError`, each at
    the boundary that owns it.

Design decisions (WHY)
----------------------
Assumptions:
    **The role, not the client, is what makes a verification read-only.**
    ``data-migration/sql/V0__schemas_and_roles.sql`` section 5b grants ``carddemo_verifier``
    ``USAGE`` and ``SELECT`` on the five loaded schemas and nothing else -- no write privilege of
    any kind, no ``CREATE``, no sequence privilege, no ownership -- and sets
    ``default_transaction_read_only`` on it. This module's job is to prove that the session in
    hand actually is that identity, because a connection may be pooled, handed on, or have issued
    ``SET ROLE`` between being opened and being used.
Alternatives Considered:
    **Reusing ``carddemo_reporting``, which is already read-only.** Rejected on a property of the
    pass rather than on preference: reporting holds ``SELECT`` on masked aggregate views only, so
    a primary account number reads back as its last four digits. Verification pass 2 compares a
    source record against the row it became field by field, so a masked read would differ from
    the extract on every row of every card and cross-reference record and report a correct load as
    a defect. Widening reporting to the base tables was the other way to close that, and it would
    undo the masking boundary for every reporting query -- a far larger exposure than a dedicated
    read-only identity.
Alternatives Considered:
    **Checking ``session_user`` rather than ``current_user``.** ``current_user`` is the identifier
    privilege decisions are made against and it follows a ``SET ROLE``; ``session_user`` does not.
    A session that authenticated as the verifier and then assumed a writable role would pass a
    ``session_user`` check and could still write.
Trade-offs:
    **Read-only-ness is asserted twice, by two mechanisms that fail differently.** The role's own
    default protects a caller that forgot to ask for it; the ``SET SESSION CHARACTERISTICS``
    statement issued here protects a session on a cluster where that default was lost or never
    applied; and reading ``transaction_read_only`` back afterwards is what makes the second
    provable rather than hopeful -- a driver, proxy or pooler that silently swallowed the
    statement is caught. The accepted cost is three round trips before the first read.
Trade-offs:
    **A credentialed identity that can read every migrated record in the clear.** That is the
    price of being able to prove a field was loaded correctly, and it is bounded rather than
    unbounded: the role cannot write, cannot create, cannot advance a sequence and owns nothing,
    the session it runs in is read-only, and no verification pass renders a field value into its
    report -- so a value read under this authority does not reach a log.
"""

from __future__ import annotations

# WHY : Trade-offs: the driver is NOT imported here, at module scope or otherwise. The loaders
#   package declares itself the only place in this tree permitted to import psycopg and keeps that
#   import inside a function body, so routing the connection through `loaders.aurora.connect` both
#   honours that invariant and keeps this module importable on a host with no driver installed --
#   which is what lets the session guard be driven by the in-process double.
from typing import TYPE_CHECKING, Any, Final

from carddemo_migration.config import (
    VERIFICATION_SCOPE,
    VERIFIER_ROLE,
    resolve_verifier_settings,
)
from carddemo_migration.loaders.aurora import connect

if TYPE_CHECKING:  # pragma: no cover - imported for annotations only
    from carddemo_migration.config import AuroraConnectionSettings

# WHY : Alternatives Considered: the surface is DECLARED rather than left to whatever names happen
#   to be bound, matching the four sibling modules in this subpackage, with the constants first and
#   the remaining names sorted.
__all__ = [
    "VERIFICATION_SCOPE",
    "VERIFIER_ROLE",
    "VerificationSessionError",
    "open_verification_connection",
    "require_verification_session",
    "verifier_role",
    "verifier_settings",
]

# WHY : Assumptions: the three probe statements are named constants and each is a FIXED literal.
#   None is composed from a caller's input, so no text from outside this file can reach the server
#   through this module -- which is the same discipline the two query-reading passes apply to their
#   committed SQL, expressed here for statements short enough to be inline and therefore easy to
#   assemble carelessly.
_CURRENT_USER_PROBE: Final[str] = "select current_user"
_ENFORCE_READ_ONLY: Final[str] = "SET SESSION CHARACTERISTICS AS TRANSACTION READ ONLY"
_READ_ONLY_PROBE: Final[str] = "show transaction_read_only"

# WHY : Assumptions: PostgreSQL reports a boolean GUC through `show` as the text `on` or `off`,
#   never as a boolean, so the expected value is a string. Comparing against a Python truth value
#   would be true for the string `"off"` as well, which is the silent direction of failure.
_READ_ONLY_ENABLED: Final[str] = "on"


class VerificationSessionError(RuntimeError):
    """Raised when a session offered for verification is not a certified read-only verifier session.

    Purpose
    -------
    Give a caller one name to catch for "this session may not certify a load", distinct from a
    configuration fault, a connection fault and a pass's own inability to reach a verdict.

    Parameters
    ----------
    None
        Inherits the base exception's own arguments.

    Returns
    -------
    None
        Exception classes are raised, not returned. The inapplicability is stated rather than left
        silent, so a reader can tell a class with no return from a docstring that forgot one.

    Raises
    ------
    None
        Declaring an exception class raises nothing.
    """


def verifier_role() -> str:
    """Return the name of the read-only role a per-dataset verification must run as.

    Parameters
    ----------
    None
        Reads :data:`~carddemo_migration.config.VERIFIER_ROLE`.

    Returns
    -------
    str
        The role name, resolved from the configuration module rather than spelled here so the name
        lives in exactly one place.

    Raises
    ------
    None
        Reading a declared constant cannot fail.
    """
    # WHY : Assumptions: the name is re-exported through a function rather than as a bare constant
    #   alias, matching `row_counts.reporting_role`, so that a caller's spelling does not have to
    #   change if the name ever comes to be derived rather than declared.
    return VERIFIER_ROLE


def verifier_settings() -> AuroraConnectionSettings:
    """Resolve the connection descriptor for the read-only verification role.

    Parameters
    ----------
    None
        Every value is resolved from the environment, Parameter Store and Secrets Manager by
        :func:`~carddemo_migration.config.resolve_verifier_settings`.

    Returns
    -------
    AuroraConnectionSettings
        A frozen descriptor whose rendering masks the password.

    Raises
    ------
    ConfigurationError
        Propagated when a parameter or the secret is absent, unreadable or malformed, when the
        secret's stored user is not this role, or when the transport requirements are unmet.
    """
    return resolve_verifier_settings()


def _require_verifier_settings(
    settings: AuroraConnectionSettings,
) -> AuroraConnectionSettings:
    """Refuse settings that name any role other than the verifier, before a connection is opened.

    Parameters
    ----------
    settings : AuroraConnectionSettings
        The descriptor a caller supplied or :func:`verifier_settings` resolved.

    Returns
    -------
    AuroraConnectionSettings
        The same descriptor, unmodified, when its user is the verifier.

    Raises
    ------
    VerificationSessionError
        If the descriptor's user is any other role. The message names both roles and nothing else
        about the descriptor, so no host, database name or credential reaches it.
    """
    # WHY : Assumptions: this check is on the SETTINGS and is deliberately not the only one. It
    #   catches the misconfiguration cheaply, before a connection exists, which is the difference
    #   between a named refusal and an authentication failure an operator has to interpret. The
    #   session check that follows is what covers a connection this module never opened.
    if settings.user != VERIFIER_ROLE:
        raise VerificationSessionError(
            f"a per-dataset verification must connect as {VERIFIER_ROLE!r} and these settings name"
            f" {settings.user!r}; a session able to write cannot certify what it verifies"
        )
    return settings


def open_verification_connection(
    settings: AuroraConnectionSettings | None = None,
) -> Any:
    """Open a connection on the read-only verification role and certify the session it carries.

    Purpose
    -------
    Be the one way a per-dataset verification acquires a database session, so that resolving the
    credential, refusing the wrong role and proving the session is read-only cannot be performed
    partially.

    Parameters
    ----------
    settings : AuroraConnectionSettings | None
        Connection parameters, or ``None`` to resolve them through :func:`verifier_settings`. A
        supplied descriptor is checked to name the verifier before it is used; the parameter exists
        so a caller holding already-resolved settings need not resolve them twice, not so a caller
        may choose an identity.

    Returns
    -------
    Any
        An open connection whose live session has been certified as the verifier and set read-only.
        The caller owns closing it; this function deliberately does not, because one connection
        serves every dataset in a run. The driver's connection type is not imported here, exactly
        as :func:`carddemo_migration.loaders.aurora.connect` does not import it.

    Raises
    ------
    VerificationSessionError
        If the settings or the live session name another role, or if the server does not report the
        session as read-only.
    ConfigurationError
        Propagated when the driver is absent or a parameter or secret cannot be resolved.
    AuroraLoadError
        Propagated when the driver is present and the connection attempt fails.
    """
    # WHY : Assumptions: `connect` is given the expected role as well, so the driver-level check
    #   this package already applies to every other connection applies here too. Three independent
    #   statements of the same requirement -- the settings, the connect-time expectation and the
    #   live session -- is not redundancy for its own sake: they read different things, in that
    #   order, and only the last of them survives a connection being pooled or handed on.
    resolved = _require_verifier_settings(verifier_settings() if settings is None else settings)
    connection = connect(resolved, expected_role=VERIFIER_ROLE)
    # WHY : Trade-offs: a failure to certify CLOSES the connection before propagating. Leaving it
    #   open would leak a session on a cluster connection limit for the lifetime of the process,
    #   and the caller cannot close what it was never handed. The close is guarded because a
    #   connection that failed to certify may also be one that is not behaving as a connection.
    try:
        require_verification_session(connection)
    except BaseException:
        try:
            connection.close()
        except Exception:  # pragma: no cover - a double close is not the failure being reported
            # WHY : Assumptions: a failure to close is SWALLOWED here and the original refusal is
            #   the one that propagates. The refusal is what an operator must act on; a secondary
            #   close error would replace it with a less informative one and hide the reason the
            #   session was rejected.
            pass
        raise
    return connection


def _one_scalar(connection: Any, statement: str) -> object:
    """Execute one fixed statement and return the single value its one row projects.

    Parameters
    ----------
    connection : Any
        An open database connection, or the in-process double that stands in for one.
    statement : str
        One of this module's own probe constants. Never caller-supplied text.

    Returns
    -------
    object
        The projected value, exactly as the driver returned it.

    Raises
    ------
    VerificationSessionError
        If the statement yields no row, or a row of any arity other than one. Both mean the object
        supplied is not behaving as a connection, which is reported as such rather than surfaced
        later as an index error naming nothing.
    """
    # WHY : Assumptions: the cursor may or may not be a context manager, so both shapes are
    #   handled -- the driver's cursor is one and this package's in-process double returns a plain
    #   object. This is the same accommodation the three sibling passes make.
    candidate = connection.cursor()
    cursor = candidate.__enter__() if hasattr(candidate, "__enter__") else candidate
    try:
        cursor.execute(statement)
        row = cursor.fetchone()
    finally:
        if hasattr(candidate, "__exit__"):
            candidate.__exit__(None, None, None)
    if row is None:
        raise VerificationSessionError(
            f"the statement {statement!r} returned no row at all; it projects one by"
            " construction, so the connection is not behaving as a database connection"
        )
    values = tuple(row)
    if len(values) != 1:
        raise VerificationSessionError(
            f"the statement {statement!r} projected {len(values)} columns where it projects one;"
            " the connection is not behaving as a database connection"
        )
    return values[0]


def _execute(connection: Any, statement: str) -> None:
    """Execute one fixed statement that returns nothing.

    Parameters
    ----------
    connection : Any
        An open database connection, or the in-process double that stands in for one.
    statement : str
        One of this module's own probe constants. Never caller-supplied text.

    Returns
    -------
    None
        The statement's effect on the session is the result.

    Raises
    ------
    Exception
        Whatever the driver raises if the statement is refused. It is deliberately not translated:
        a server refusing ``SET SESSION CHARACTERISTICS`` is reporting something about the cluster
        that a wrapper of this module's own would only obscure.
    """
    candidate = connection.cursor()
    cursor = candidate.__enter__() if hasattr(candidate, "__enter__") else candidate
    try:
        cursor.execute(statement)
    finally:
        if hasattr(candidate, "__exit__"):
            candidate.__exit__(None, None, None)


def require_verification_session(connection: Any) -> str:
    """Certify a live session as the read-only verifier, or refuse it before anything is read.

    Purpose
    -------
    Establish the two properties a per-dataset verification depends on, by asking the SERVER rather
    than by trusting the settings some earlier call was handed: the session is the verifier, and
    the session cannot write.

    Parameters
    ----------
    connection : Any
        An open database connection, or the in-process double that stands in for one. It is not
        closed here; a caller that opened it owns it.

    Returns
    -------
    str
        The session's role name, so a caller may log which identity certified a dataset.

    Raises
    ------
    VerificationSessionError
        If the session authenticates as any role other than the verifier, or if the server reports
        the session as writable after being told to be read-only. Every message names roles and
        settings only -- no host, database name or credential reaches one.
    """
    # WHY : Assumptions: the ORDER of the three statements is load-bearing. The identity is
    #   established first, because a session that is not the verifier must be refused before this
    #   module changes anything about it; the enforcement follows; and the read-back is last,
    #   because it is the only one of the three that can prove the enforcement took effect.
    expected = verifier_role()
    observed = _one_scalar(connection, _CURRENT_USER_PROBE)
    role = observed.strip() if isinstance(observed, str) else str(observed)
    if role != expected:
        raise VerificationSessionError(
            f"a per-dataset verification must run on a session for {expected!r} and this"
            f" connection's session is {role!r}; a session able to write cannot certify what it"
            " verifies"
        )
    _execute(connection, _ENFORCE_READ_ONLY)
    reported = _one_scalar(connection, _READ_ONLY_PROBE)
    setting = reported.strip() if isinstance(reported, str) else str(reported)
    if setting.lower() != _READ_ONLY_ENABLED:
        # WHY : Assumptions: this refusal is not theatre even though the role's own default should
        #   already have produced `on`. It is the case where the default was never applied -- a
        #   cluster bootstrapped by an older revision of the bootstrap script, or a role recreated
        #   by hand -- and the statement issued above was then swallowed by a pooler or a proxy
        #   that resets session state. In that arrangement the session really can write, and every
        #   grant-based argument for safety is void.
        raise VerificationSessionError(
            f"the session for {expected!r} reports transaction_read_only={setting!r} after being"
            f" set read-only; verification requires the server to confirm"
            f" {_READ_ONLY_ENABLED!r}, because a writable session could alter the rows it is"
            " certifying"
        )
    return role
