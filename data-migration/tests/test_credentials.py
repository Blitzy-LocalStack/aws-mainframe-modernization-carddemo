"""Drive the credential-application step over the whole login-role inventory it must credit.

Purpose
-------
Execute :mod:`carddemo_migration.credentials` rather than read it, and pin the one property its
own module docstring publishes: the step applies and then proves a credential for every role in
``carddemo_migration.config.LOGIN_ROLE_NAMES``. That claim was published while the step worked
from ``SCHEMA_ROLES`` alone -- the eight runtime connection roles -- so the seven
``carddemo_<context>_migrator`` logins were never credited, every service's Flyway task would
have failed to authenticate at start-up, and the run reported complete success anyway. The reason
it reported success is the reason this module exists: the read-back that is supposed to expose an
uncredited role was scoped to the SAME subset the application was, so the subset could never be
the finding.

Four properties are settled here and nowhere else in this folder:

* the production inventory is exactly ``LOGIN_ROLE_NAMES``, asserted as set equality in both
  directions rather than as a count, and the derivation fails closed rather than narrowing when
  the mappings it reads stop covering that tuple;
* each tier's password is read through the entry point for that tier -- a connection role's
  through ``resolve_aurora_settings``, a ``_migrator`` role's through
  ``resolve_migration_settings`` -- so no role is credited from its schema's other secret;
* a full run issues one ``ALTER ROLE`` per login role, inside one transaction, and then proves
  each of those roles can authenticate as ITSELF;
* a role the catalogue holds no verifier for is REPORTED, including one in the migration tier,
  which is the case the previous scoping made unreachable.

Alternatives Considered:
    Reusing ``conftest.py``'s :class:`FakeAuroraDatabase` instead of the narrow double below.
    Rejected on two measured incompatibilities rather than on preference. That double records and
    matches statements as text -- ``rows_for`` calls ``statement.casefold()`` -- and
    ``_apply_verifier`` deliberately passes a ``psycopg.sql.Composed`` object so that the driver
    quotes both the identifier and the literal, so the shared double raises ``AttributeError``
    before any assertion is reached. And this step opens one connection per role for its login
    proof, keyed on the ``user`` parameter, whereas the shared double answers every connection
    identically. The house precedent for a locally-declared stub is
    ``test_s3_stage.py``, whose own rationale ``test_shared_doubles.py`` records: two
    instruments answering different questions are kept apart rather than merged.

Assumptions:
    No test here reaches a network, a database, a credential or AWS. The driver's ``connect`` is
    substituted at the one seam :func:`carddemo_migration.credentials._connect` imports it
    through, and every settings object is built from literals -- an unresolvable ``.invalid``
    host and a synthetic password -- so a call that escaped the substitution would fail name
    resolution rather than reach anything.

Trade-offs:
    The double records each ``ALTER ROLE`` with everything after ``PASSWORD`` replaced by a
    marker. A recorded statement ends up in assertion output whenever a case fails, and the
    whole point of deriving the verifier locally is that verifier material does not reach a log;
    the same discipline is why ``FakeAuroraDatabase`` masks the password in its recorded
    connection parameters. The cost is that role-to-secret pairing cannot be read out of the
    statement log, so it is asserted directly against the resolver tiers instead, which is where
    the pairing decision actually lives.
"""

from __future__ import annotations

import re
from typing import Any, Final

import pytest

from carddemo_migration import credentials
from carddemo_migration.config import (
    LOGIN_ROLE_NAMES,
    MIGRATION_SCHEMA_ROLES,
    SCHEMA_ROLES,
    AuroraConnectionSettings,
)
from carddemo_migration.credentials import (
    CredentialApplicationError,
    apply_service_credentials,
)

# WHY : Assumptions: the host sits in the reserved ``.invalid`` top-level domain and the trust
#   anchor names a path that does not exist, for the same reason ``conftest.aurora_settings``
#   does -- an object that escaped the substitution below cannot reach anything, because name
#   resolution fails before a socket is opened.
_SYNTHETIC_HOST: Final[str] = "aurora.carddemo.invalid"
_SYNTHETIC_ANCHOR: Final[str] = "/nonexistent/synthetic-test-anchor.pem"

# WHY : Assumptions: the marker replaces the verifier in every recorded statement. It is a
#   literal rather than the module's own redaction constant because ``credentials`` publishes
#   none -- it withholds verifier material by never composing it into a message at all.
_VERIFIER_WITHHELD: Final[str] = "<withheld>"

# WHY : Assumptions: matched on the composed statement's rendered text, anchored at ``PASSWORD``
#   so that the identifier the driver quoted is kept and only the literal is dropped.
_PASSWORD_CLAUSE: Final[re.Pattern[str]] = re.compile(r"PASSWORD .*\Z", re.DOTALL)


def _synthetic_password(role: str) -> str:
    """Return a distinct synthetic password for one role.

    Purpose
    -------
    Give every role a value that identifies WHICH secret it came from, so a test can assert the
    role-to-secret pairing without a real credential existing anywhere in this repository.

    Parameters
    ----------
    role : str
        The login role the password stands in for.

    Returns
    -------
    str
        Printable ASCII with no space, which is the alphabet
        :func:`carddemo_migration.credentials.scram_verifier` admits, carrying the role name so
        the value is traceable to its role in a failure message.

    Raises
    ------
    None
        The value is composed, not validated; the module under test validates it.
    """
    return f"synthetic-{role}"


def _settings_for_role(role: str) -> AuroraConnectionSettings:
    """Build a validated settings object that authenticates as one role.

    Purpose
    -------
    Stand in for what ``config`` resolves from Parameter Store and Secrets Manager, with the two
    fields this step reads -- ``user`` and ``password`` -- carrying the role's identity so both
    the pairing and the login proof can be asserted.

    Parameters
    ----------
    role : str
        The login role the descriptor authenticates as.

    Returns
    -------
    AuroraConnectionSettings
        A frozen descriptor whose rendering masks the password, pointing at an unresolvable host.

    Raises
    ------
    ConfigurationError
        Propagated from the settings class if a value were blank or the port out of range, which
        none is.
    """
    return AuroraConnectionSettings(
        host=_SYNTHETIC_HOST,
        port=5432,
        database="carddemo",
        user=role,
        password=_synthetic_password(role),
        ssl_root_cert=_SYNTHETIC_ANCHOR,
    )


def _deterministic_verifier(password: str) -> str:
    """Return a well-formed verifier that is a pure function of the password.

    Purpose
    -------
    Replace the real PBKDF2 derivation so a run is deterministic and so a case asserting the
    inventory does not also depend on the derivation being correct, which
    :data:`carddemo_migration.credentials.SCRAM_ITERATIONS` rounds of hashing are asserted
    elsewhere.

    Parameters
    ----------
    password : str
        The password that would have been derived from.

    Returns
    -------
    str
        A string matching the structure
        :func:`carddemo_migration.credentials._apply_verifier` requires -- mechanism, iteration
        count, salt, stored key and server key -- so the statement is composed rather than
        refused.

    Raises
    ------
    None
        Nothing is validated here; the module under test refuses a malformed verifier.
    """
    # WHY : Assumptions: the three base64 positions are filled with a constant rather than with
    #   an encoding of the password. The pairing between a role and its secret is asserted
    #   against the resolver tiers, and the double withholds this clause from every recorded
    #   statement, so encoding the password here would put derived credential material into
    #   assertion output for no assertion that reads it.
    del password
    return f"{credentials.SCRAM_MECHANISM}$4096:c2FsdA==$c3RvcmVk:c2VydmVy"


class _RecordingCursor:
    """Cursor double that records statements and answers the step's three queries from state.

    Purpose
    -------
    Be the seam every statement of the credential step passes through, deriving each answer from
    what the statement asked for rather than from a pre-arranged result set, so a case cannot
    pass by arranging the answer it wanted.
    """

    def __init__(self, connection: _RecordingConnection) -> None:
        """Bind a cursor to its connection.

        Parameters
        ----------
        connection : _RecordingConnection
            The connection this cursor executes through.

        Returns
        -------
        None
            Stores the connection and starts with no result set.

        Raises
        ------
        None
            Nothing is validated until a statement is executed.
        """
        self.connection = connection
        self.rows: list[tuple[object, ...]] = []

    def execute(self, statement: Any, params: tuple[object, ...] | None = None) -> _RecordingCursor:
        """Record one statement and load the rows the cluster double would return for it.

        Parameters
        ----------
        statement : Any
            Either SQL text or a ``psycopg.sql.Composed`` object, which is what
            ``_apply_verifier`` passes.
        params : tuple[object, ...] or None, optional
            Bound parameters, or ``None`` for a statement with none.

        Returns
        -------
        _RecordingCursor
            This cursor, matching the driver's chaining behaviour.

        Raises
        ------
        None
            An unrecognised statement yields no rows rather than an error, so a statement this
            double does not model surfaces as a failed assertion naming it and not as a stack
            trace inside the double.
        """
        rendered = statement if isinstance(statement, str) else statement.as_string()
        withheld = _PASSWORD_CLAUSE.sub(f"PASSWORD {_VERIFIER_WITHHELD}", rendered)
        self.connection.cluster.record(withheld)
        self.rows = list(self.connection.answer(rendered, params))
        return self

    def fetchall(self) -> list[tuple[object, ...]]:
        """Return every row of the current result set.

        Returns
        -------
        list[tuple[object, ...]]
            The rows the last :meth:`execute` derived.

        Raises
        ------
        None
            An empty result is a legitimate outcome the step handles.
        """
        return list(self.rows)

    def fetchone(self) -> tuple[object, ...] | None:
        """Return the first row of the current result set, or ``None`` when there is none.

        Returns
        -------
        tuple[object, ...] or None
            The first row, or ``None`` for an empty result, exactly as the driver reports it.

        Raises
        ------
        None
            An empty result is reported as ``None``.
        """
        return self.rows[0] if self.rows else None

    def __enter__(self) -> _RecordingCursor:
        """Enter the cursor's context.

        Returns
        -------
        _RecordingCursor
            This cursor.

        Raises
        ------
        None
            Entering cannot fail.
        """
        return self

    def __exit__(self, *exc_info: object) -> None:
        """Leave the cursor's context without suppressing anything.

        Parameters
        ----------
        *exc_info : object
            Exception triple, ignored.

        Returns
        -------
        None
            Returns ``None`` so an in-flight exception propagates, as the driver's does.

        Raises
        ------
        None
            Leaving cannot fail.
        """
        return None


class _RecordingConnection:
    """Connection double that answers as the identity its connection parameters named.

    Purpose
    -------
    Model the one property the login proof depends on and that a single shared connection cannot
    express: this step opens a NEW connection per role, and ``SELECT CURRENT_USER`` must answer
    with the identity the server accepted for THAT connection.
    """

    def __init__(self, cluster: _FakeCluster, params: dict[str, object]) -> None:
        """Record the parameters this connection was opened with.

        Parameters
        ----------
        cluster : _FakeCluster
            The cluster double this connection belongs to.
        params : dict[str, object]
            The connection parameters, whose ``user`` decides what ``CURRENT_USER`` answers.

        Returns
        -------
        None
            Stores the cluster and the parameters.

        Raises
        ------
        None
            Nothing is validated here; the cluster validates the parameter set.
        """
        self.cluster = cluster
        self.params = params

    def answer(
        self, statement: str, params: tuple[object, ...] | None
    ) -> tuple[tuple[object, ...], ...]:
        """Derive the rows for one statement from the cluster's state.

        Parameters
        ----------
        statement : str
            The rendered statement text.
        params : tuple[object, ...] or None
            Its bound parameters.

        Returns
        -------
        tuple[tuple[object, ...], ...]
            The rows the modelled server would return: the requested roles that exist, the
            requested roles holding no verifier, the catalogue-readability answer, or the
            identity this connection authenticated as.

        Raises
        ------
        None
            A statement this double does not model yields no rows.
        """
        requested = tuple(params[0]) if params and isinstance(params[0], list) else ()
        if "pg_roles" in statement:
            return tuple((role,) for role in requested if role in self.cluster.existing_roles)
        if "has_table_privilege" in statement:
            return ((self.cluster.catalogue_readable,),)
        if "pg_authid" in statement:
            uncredited = self.cluster.roles_without_verifier
            return tuple((role,) for role in requested if role in uncredited)
        if "CURRENT_USER" in statement:
            # WHY : Assumptions: the answer is the ``user`` the connection was OPENED with, not
            #   the role the step expected, so a secret carrying another role's identity is
            #   expressible -- that is the case ``_verify_login``'s own probe exists to catch.
            return ((self.cluster.authenticates_as(str(self.params["user"])),),)
        return ()

    def cursor(self) -> _RecordingCursor:
        """Open a cursor on this connection.

        Returns
        -------
        _RecordingCursor
            A cursor usable directly or as a context manager, as the driver's is.

        Raises
        ------
        None
            Opening cannot fail.
        """
        cursor = _RecordingCursor(self)
        self.cluster.cursors.append(cursor)
        return cursor

    def commit(self) -> None:
        """Record one commit.

        Returns
        -------
        None
            Increments the cluster's commit count.

        Raises
        ------
        None
            Committing cannot fail here; a commit failure is not a case this module covers.
        """
        self.cluster.commits += 1

    def __enter__(self) -> _RecordingConnection:
        """Enter the connection's context.

        Returns
        -------
        _RecordingConnection
            This connection.

        Raises
        ------
        None
            Entering cannot fail.
        """
        return self

    def __exit__(self, *exc_info: object) -> None:
        """Leave the connection's context without suppressing anything.

        Parameters
        ----------
        *exc_info : object
            Exception triple, ignored.

        Returns
        -------
        None
            Returns ``None`` so an in-flight exception propagates.

        Raises
        ------
        None
            Leaving cannot fail.
        """
        return None


class _FakeCluster:
    """In-process cluster double for the credential step's master and per-role connections.

    Purpose
    -------
    Hold the modelled server state the step reads -- which roles exist, which hold a verifier,
    whether the catalogue is readable, and which identity each credential authenticates as --
    and record every statement and every connection so a case can assert on the whole run.
    """

    def __init__(
        self,
        *,
        existing_roles: frozenset[str] | None = None,
        roles_without_verifier: frozenset[str] = frozenset(),
        catalogue_readable: bool = True,
        impostors: dict[str, str] | None = None,
    ) -> None:
        """Arrange the server state this run observes.

        Parameters
        ----------
        existing_roles : frozenset[str] or None, optional
            The roles ``pg_roles`` holds. ``None`` -- the default -- means every login role
            exists, which is the state ``V0__schemas_and_roles.sql`` leaves behind.
        roles_without_verifier : frozenset[str], optional
            The roles the catalogue reports as holding no usable credential after the step ran.
            Empty by default.
        catalogue_readable : bool, optional
            What ``has_table_privilege`` on ``pg_authid`` answers. ``True`` by default.
        impostors : dict[str, str] or None, optional
            Role to the identity its stored credential actually authenticates as, for the case
            where a secret carries another role's payload.

        Returns
        -------
        None
            Stores the arrangement and starts every log empty.

        Raises
        ------
        None
            Construction cannot fail.
        """
        self.existing_roles = (
            frozenset(LOGIN_ROLE_NAMES) if existing_roles is None else existing_roles
        )
        self.roles_without_verifier = roles_without_verifier
        self.catalogue_readable = catalogue_readable
        self._impostors = dict(impostors or {})
        self.statements: list[str] = []
        self.connection_params: list[dict[str, object]] = []
        self.cursors: list[_RecordingCursor] = []
        self.commits = 0

    def authenticates_as(self, user: str) -> str:
        """Return the identity the server accepts for one connection's user.

        Parameters
        ----------
        user : str
            The role the connection was opened as.

        Returns
        -------
        str
            That role, or the impostor identity arranged for it.

        Raises
        ------
        None
            An unarranged role authenticates as itself.
        """
        return self._impostors.get(user, user)

    def record(self, statement: str) -> None:
        """Append one statement to the log, verifier material already withheld.

        Parameters
        ----------
        statement : str
            The rendered statement with any password clause replaced.

        Returns
        -------
        None
            Appends to the log.

        Raises
        ------
        None
            Recording cannot fail.
        """
        self.statements.append(statement)

    def connect(self, **params: object) -> _RecordingConnection:
        """Open one connection, refusing any parameter set that would not verify the server.

        Parameters
        ----------
        **params : object
            The connection parameters
            :meth:`carddemo_migration.config.AuroraConnectionSettings.as_connection_params`
            produced.

        Returns
        -------
        _RecordingConnection
            A connection that answers as the ``user`` named in those parameters.

        Raises
        ------
        AssertionError
            If the transport keywords are absent or weaker than ``verify-full``. The step must
            never open an unverified connection, and a double that accepted one would let that
            regression pass.
        """
        assert params.get("sslmode") == "verify-full", (
            f"the step opened a connection without full certificate verification: {params!r}"
        )
        assert params.get("sslrootcert"), (
            f"the step opened a connection with no trust anchor: {params!r}"
        )
        recorded = dict(params)
        recorded["password"] = _VERIFIER_WITHHELD
        self.connection_params.append(recorded)
        return _RecordingConnection(self, dict(params))


def _install(monkeypatch: pytest.MonkeyPatch, cluster: _FakeCluster) -> None:
    """Substitute the driver and both settings resolvers for one test.

    Purpose
    -------
    Put the double at the two seams the step actually uses -- ``psycopg.connect``, which
    :func:`carddemo_migration.credentials._connect` imports inside itself, and the two ``config``
    entry points the module imported by name -- so nothing is resolved from the environment and
    nothing is opened.

    Parameters
    ----------
    monkeypatch : pytest.MonkeyPatch
        The substitution fixture, so every replacement is undone when the test ends.
    cluster : _FakeCluster
        The cluster double to route every connection to.

    Returns
    -------
    None
        Returns once the substitutions are in place.

    Raises
    ------
    None
        Substitution cannot fail; an absent attribute would be an import error at collection.
    """
    # WHY : Assumptions: ``psycopg.connect`` is patched on the DRIVER module rather than a
    #   ``_connect`` replacement being installed on the module under test. Patching `_connect`
    #   would skip the code that composes the parameter mapping and the code that classifies a
    #   driver error, which is a third of what this step does with a connection.
    import psycopg

    monkeypatch.setattr(psycopg, "connect", cluster.connect)
    monkeypatch.setattr(
        credentials, "resolve_master_settings", lambda: _settings_for_role("carddemo_master")
    )
    monkeypatch.setattr(
        credentials,
        "resolve_aurora_settings",
        lambda schema: _settings_for_role(SCHEMA_ROLES[schema]),
    )
    monkeypatch.setattr(
        credentials,
        "resolve_migration_settings",
        lambda schema: _settings_for_role(MIGRATION_SCHEMA_ROLES[schema]),
    )
    # WHY : Refactoring Rationale: the READ-ONLY verification login is substituted here too, and
    #   it has to be: it is the sixteenth login role, it belongs to no bounded-context schema, and
    #   its credential is resolved through its own entry point rather than through either of the two
    #   above. Without this substitution the run reached the environment for that one role and
    #   failed on an unset variable, which reads as a configuration fault in the test rather than
    #   as the seam it actually is.
    monkeypatch.setattr(
        credentials,
        "resolve_verifier_settings",
        lambda: _settings_for_role(credentials.VERIFIER_ROLE),
    )


def test_the_production_inventory_is_exactly_the_login_roles() -> None:
    """Assert the tier mappings and ``LOGIN_ROLE_NAMES`` describe the same roles, both ways.

    Purpose
    -------
    Pin the property the fix turns on. The step once derived eight roles from ``SCHEMA_ROLES``
    while the inventory the deployment is verified against holds sixteen, and the eight it omitted
    were the seven migration logins and the read-only verification login. Equality is asserted in
    both directions because each direction is a different fault: a role in the inventory and not in
    the mappings is a credential applied to something no tier claims, and a role in the mappings and
    not in the inventory is a credential nothing verifies.

    Returns
    -------
    None
        The test exists for its assertions.

    Raises
    ------
    AssertionError
        If the three tiers do not cover the inventory exactly, or if the module admitted a
        divergence at import.
    """
    # WHY : Refactoring Rationale: this case read a `_credential_targets()` derivation that
    #   produced (schema, role) pairs and asserted the pairs were exactly the inventory. That
    #   derivation is withdrawn: the verification login belongs to no bounded-context schema, so a
    #   schema-driven derivation cannot express it and the work list is now the inventory itself.
    #   The property the derivation protected is unchanged and is asserted here over
    #   `_UNCLAIMED_LOGIN_ROLES`, the import-time guard that carries it now.
    assert credentials._UNCLAIMED_LOGIN_ROLES == frozenset(), (
        "the login inventory and the tier mappings behind it do not describe the same roles:"
        f" {sorted(credentials._UNCLAIMED_LOGIN_ROLES)!r}"
    )
    tiers = (
        frozenset(SCHEMA_ROLES.values())
        | frozenset(MIGRATION_SCHEMA_ROLES.values())
        | {credentials.VERIFIER_ROLE}
    )
    assert tiers == frozenset(LOGIN_ROLE_NAMES)
    assert len(LOGIN_ROLE_NAMES) == 16
    assert len(SCHEMA_ROLES) == 8
    assert len(MIGRATION_SCHEMA_ROLES) == 7

    # WHY : Assumptions: every role is additionally checked to resolve to a SCOPE, not merely to be
    #   present. A role the guard admits but no tier can label would resolve its password from
    #   nowhere and fail at the user-name assertion far from here, naming a secret rather than the
    #   mapping that chose it.
    for role in LOGIN_ROLE_NAMES:
        scope = credentials._scope_for_role(role)
        assert role in (
            SCHEMA_ROLES.get(scope),
            MIGRATION_SCHEMA_ROLES.get(scope),
            credentials.VERIFIER_ROLE,
        ), f"the role {role} resolved to scope {scope}, which does not own it"


def test_a_role_no_tier_claims_is_refused_rather_than_guessed(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Assert a login role belonging to no tier is refused instead of resolved by guesswork.

    Purpose
    -------
    Prove the guard is the thing that keeps the module docstring's claim true rather than a comment
    asserting it. If a role were added to the inventory and to no tier mapping, the step would
    otherwise have to guess a schema for it -- and a guessed schema reads another context's secret,
    which is precisely the shape that let eight-of-sixteen report success.

    Parameters
    ----------
    monkeypatch : pytest.MonkeyPatch
        Unused by the assertion below; declared because the case is registered beside the
        monkeypatching cases and a reader comparing them should see the difference.

    Returns
    -------
    None
        The test exists for its assertions.

    Raises
    ------
    AssertionError
        If a role no tier claims is resolved rather than refused, or if the refusal does not name
        the inventory it was checked against.
    """
    # WHY : Refactoring Rationale: this case monkeypatched the inventory and called a withdrawn
    #   derivation, asserting the derivation refused to narrow. The equivalent guard now runs at
    #   IMPORT, so it cannot be provoked from a test without reimporting the module -- and the
    #   failure it guards against is reachable one step later, at the point a role is resolved to a
    #   scope. That is what is asserted instead, on a role no tier owns.
    del monkeypatch
    with pytest.raises(CredentialApplicationError) as refused:
        credentials._scope_for_role("carddemo_not_a_tier")

    message = str(refused.value)
    assert "carddemo_not_a_tier" in message
    assert "carddemo_auth_migrator" in message


@pytest.mark.parametrize("schema", sorted(MIGRATION_SCHEMA_ROLES))
def test_each_tier_resolves_through_its_own_entry_point(
    schema: str, monkeypatch: pytest.MonkeyPatch
) -> None:
    """Assert a migration role's password comes from the migration secret, not the runtime one.

    Purpose
    -------
    Pin the second half of the fix. Covering fifteen roles is worth nothing if all fifteen
    resolve through ``resolve_aurora_settings``: seven of them would then be credited with the
    runtime role's password, every one would fail that resolver's own user-name assertion, and
    the step would report a secret fault rather than the routing fault that caused it.

    Parameters
    ----------
    schema : str
        One of the seven contexts that has both tiers.
    monkeypatch : pytest.MonkeyPatch
        Used to substitute both resolvers with recorders.

    Returns
    -------
    None
        The test exists for its assertions.

    Raises
    ------
    AssertionError
        If either tier reaches the other's entry point, or if the password read is not the one
        stored for that role.
    """
    calls: list[tuple[str, str]] = []

    def _runtime(name: str) -> AuroraConnectionSettings:
        """Record a runtime-tier resolution and answer with that context's connection role.

        Parameters
        ----------
        name : str
            The schema the module under test asked for.

        Returns
        -------
        AuroraConnectionSettings
            Settings authenticating as the schema's connection role.

        Raises
        ------
        KeyError
            If the schema is not one of the eight, which would itself be the fault.
        """
        calls.append(("runtime", name))
        return _settings_for_role(SCHEMA_ROLES[name])

    def _migration(name: str) -> AuroraConnectionSettings:
        """Record a migration-tier resolution and answer with that context's migrator role.

        Parameters
        ----------
        name : str
            The schema the module under test asked for.

        Returns
        -------
        AuroraConnectionSettings
            Settings authenticating as the schema's ``_migrator`` role.

        Raises
        ------
        KeyError
            If the schema has no migration role, which would itself be the fault.
        """
        calls.append(("migration", name))
        return _settings_for_role(MIGRATION_SCHEMA_ROLES[name])

    monkeypatch.setattr(credentials, "resolve_aurora_settings", _runtime)
    monkeypatch.setattr(credentials, "resolve_migration_settings", _migration)

    runtime_role = SCHEMA_ROLES[schema]
    migration_role = MIGRATION_SCHEMA_ROLES[schema]

    # WHY : Refactoring Rationale: the helper takes the ROLE alone where it took a schema and a
    #   role. The schema was redundant -- the role determines its own tier and therefore its own
    #   scope -- and it could disagree with the role, which is the pairing fault the case above
    #   used to have to check for separately.
    assert credentials._password_for(runtime_role) == _synthetic_password(runtime_role)
    assert credentials._password_for(migration_role) == _synthetic_password(migration_role)
    assert calls == [("runtime", schema), ("migration", schema)]


def test_the_context_without_a_migration_role_resolves_only_through_the_runtime_tier(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Assert ``reporting`` never reaches the migration entry point, which would refuse it.

    Purpose
    -------
    Cover the one context the two mappings disagree about. ``reporting`` ships no Flyway
    migration, so ``resolve_migration_settings`` refuses it by design; a tier decision derived
    from a role-name suffix rather than from ``MIGRATION_SCHEMA_ROLES`` would be indistinguishable
    from the correct one for the other seven contexts and wrong only here.

    Parameters
    ----------
    monkeypatch : pytest.MonkeyPatch
        Used to make any migration-tier resolution a hard failure.

    Returns
    -------
    None
        The test exists for its assertions.

    Raises
    ------
    AssertionError
        If the reporting context resolves through the migration entry point.
    """

    def _refuse(name: str) -> AuroraConnectionSettings:
        """Fail the test if the migration tier is reached at all.

        Parameters
        ----------
        name : str
            The schema the module under test asked for, reported in the failure.

        Returns
        -------
        AuroraConnectionSettings
            Never returns; the annotation matches the resolver it substitutes for.

        Raises
        ------
        AssertionError
            Always, because any call at all is the fault this case exists to catch.
        """
        raise AssertionError(f"the reporting context reached the migration tier for {name}")

    monkeypatch.setattr(
        credentials, "resolve_aurora_settings", lambda name: _settings_for_role(SCHEMA_ROLES[name])
    )
    monkeypatch.setattr(credentials, "resolve_migration_settings", _refuse)

    resolved = credentials._settings_for_role(SCHEMA_ROLES["reporting"])

    assert resolved.user == "carddemo_reporting"


def test_a_run_credits_and_proves_every_login_role(monkeypatch: pytest.MonkeyPatch) -> None:
    """Assert one run alters all fifteen roles in one transaction and logs in as each.

    Purpose
    -------
    Exercise the whole step over the production inventory, which is the only assertion that can
    tell "fifteen roles are derived" from "fifteen roles are credited". The ``ALTER ROLE``
    statements, the single commit and the fifteen login connections are asserted together
    because they are one property: a role is credited only if all three happened for it.

    Parameters
    ----------
    monkeypatch : pytest.MonkeyPatch
        Used to install the cluster double at the driver and resolver seams.

    Returns
    -------
    None
        The test exists for its assertions.

    Raises
    ------
    AssertionError
        If any login role is not altered, if more than one commit is issued, if a role's login is
        not proved, or if any recorded statement carries verifier material.
    """
    cluster = _FakeCluster()
    _install(monkeypatch, cluster)

    applied = apply_service_credentials(verifier_factory=_deterministic_verifier)

    assert frozenset(applied) == frozenset(LOGIN_ROLE_NAMES)
    altered = {
        statement.split('"')[1]
        for statement in cluster.statements
        if statement.startswith("ALTER ROLE")
    }
    assert altered == frozenset(LOGIN_ROLE_NAMES)

    # WHY : Assumptions: exactly one commit, asserted rather than "at least one". The whole set
    #   is applied in one transaction so that a partial failure leaves the cluster as it was, and
    #   a per-role commit would satisfy an at-least-one assertion while producing the
    #   half-credited state the module docstring rejects.
    assert cluster.commits == 1

    logins = [
        params["user"] for params in cluster.connection_params if params["user"] in LOGIN_ROLE_NAMES
    ]
    assert frozenset(logins) == frozenset(LOGIN_ROLE_NAMES)
    assert all(
        _VERIFIER_WITHHELD in statement
        for statement in cluster.statements
        if "PASSWORD" in statement
    )


def test_a_migration_role_holding_no_verifier_is_reported(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Assert the read-back exposes an uncredited migration role instead of reporting success.

    Purpose
    -------
    Cover the failure the previous scoping made unreachable. The read-back is scoped to the roles
    the step selected, so while that selection was the eight runtime roles, a cluster whose seven
    ``_migrator`` roles held no credential at all was reported as fully bootstrapped. The case
    asserts the migration tier specifically, because the runtime tier was always in scope and
    would have passed before the fix.

    Parameters
    ----------
    monkeypatch : pytest.MonkeyPatch
        Used to install the cluster double.

    Returns
    -------
    None
        The test exists for its assertions.

    Raises
    ------
    AssertionError
        If the run succeeds, or if its refusal does not name the uncredited role.
    """
    uncredited = MIGRATION_SCHEMA_ROLES["auth"]
    cluster = _FakeCluster(roles_without_verifier=frozenset({uncredited}))
    _install(monkeypatch, cluster)

    with pytest.raises(CredentialApplicationError) as refused:
        apply_service_credentials(verifier_factory=_deterministic_verifier)

    assert uncredited in str(refused.value)


def test_an_absent_migration_role_stops_the_run_before_any_statement(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Assert a migration role V0 never created is reported as an ordering fault.

    Purpose
    -------
    Prove the pre-flight existence check covers the migration tier too. A ``_migrator`` role that
    does not exist means this step ran before the bootstrap script, and the useful report names
    that ordering rather than a driver error on the first ``ALTER ROLE``.

    Parameters
    ----------
    monkeypatch : pytest.MonkeyPatch
        Used to install the cluster double.

    Returns
    -------
    None
        The test exists for its assertions.

    Raises
    ------
    AssertionError
        If the run proceeds, if the refusal does not name the absent role, or if any credential
        was altered first.
    """
    absent = MIGRATION_SCHEMA_ROLES["ledger"]
    cluster = _FakeCluster(existing_roles=frozenset(LOGIN_ROLE_NAMES) - {absent})
    _install(monkeypatch, cluster)

    with pytest.raises(CredentialApplicationError) as refused:
        apply_service_credentials(verifier_factory=_deterministic_verifier)

    assert absent in str(refused.value)
    assert not [statement for statement in cluster.statements if statement.startswith("ALTER ROLE")]


def test_a_subset_stays_reachable_only_through_the_function(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Assert the subset injection is a function argument and not a command-line option.

    Purpose
    -------
    Pin the boundary the review guidance requires: a deployment must not be able to ask for a
    subset, because a per-role invocation is what leaves a stack half credited, while a test
    needs one to drive a single tier. Both halves are asserted together so neither can be
    satisfied alone.

    Parameters
    ----------
    monkeypatch : pytest.MonkeyPatch
        Used to install the cluster double.

    Returns
    -------
    None
        The test exists for its assertions.

    Raises
    ------
    AssertionError
        If a subset is not honoured, or if the command line accepts a role or schema option.
    """
    cluster = _FakeCluster()
    _install(monkeypatch, cluster)

    applied = apply_service_credentials(
        roles=[MIGRATION_SCHEMA_ROLES["auth"]],
        verifier_factory=_deterministic_verifier,
    )

    assert applied == (MIGRATION_SCHEMA_ROLES["auth"],)
    # WHY : Assumptions: the command line is probed for the two options that would expose the
    #   subset, rather than the parser's option list being read. An option added under a third
    #   spelling would be missed by a list comparison written today, whereas both refusals below
    #   fail the moment any option at all is accepted -- the parser takes none.
    for option in ("--role", "--schema"):
        assert credentials.main([option, "auth"]) == credentials.EXIT_USAGE
