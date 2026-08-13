"""Prove the authority a post-load verification runs under cannot alter what it certifies.

Purpose
-------
Assert the one control that makes a verification result mean anything: the identity performing it
holds no authority to change the data. Before this control, the three per-dataset passes connected
as the bounded context's own service role -- the same credential the load step immediately before
them writes with, holding ``SELECT``, ``INSERT`` and ``UPDATE`` on exactly the rows being certified.
A verification under that identity is unfalsifiable in the worst way: it reports success whether or
not the data is right, because a defect in it could repair the evidence.

The control has three parts and each fails differently, so each is asserted here:

* the ROLE, created by ``data-migration/sql/V0__schemas_and_roles.sql`` with ``SELECT`` on the five
  loaded schemas and no write privilege anywhere -- the part that makes writing impossible;
* the SESSION guard in :mod:`carddemo_migration.verify.session`, which asks the server who it is
  and asks it to confirm the transaction is read-only -- the part that catches a connection this
  package did not open, or a cluster where the role was mis-provisioned;
* the INVENTORY, which is what gets the role a credential provisioned at all.

Alternatives Considered:
    The grant surface is asserted by reading the bootstrap SQL as TEXT rather than by replaying it
    against a live cluster. A live replay would be the stronger check and is unavailable here for
    the reason the sibling suites record: the script needs a role able to create roles, and a test
    that requires one asserts the host rather than the artifact. What the text can establish is
    exactly what matters for least privilege -- which privileges are granted to this role and,
    more importantly, that no write privilege is.

Trade-offs:
    The session assertions use the in-process double, so they pin the STATEMENTS issued and their
    ORDER rather than a server's response to them. That is the right level: the order is the
    control -- an identity check made after a read has protected nothing -- and a real server's
    behaviour on ``SET SESSION CHARACTERISTICS`` is not this package's to prove.
"""

from __future__ import annotations

import re
from dataclasses import replace
from pathlib import Path
from typing import TYPE_CHECKING, Final

import pytest

from carddemo_migration import credentials
from carddemo_migration.config import (
    LOGIN_ROLE_NAMES,
    MIGRATION_SCHEMA_ROLES,
    OWNED_SCHEMA_ROLES,
    REQUIRED_SSL_MODE,
    SCHEMA_ROLES,
    VERIFICATION_SCOPE,
    VERIFIER_ROLE,
)
from carddemo_migration.verify.session import (
    VerificationSessionError,
    open_verification_connection,
    require_verification_session,
    verifier_role,
)

if TYPE_CHECKING:
    from conftest import FakeAuroraDatabase

    from carddemo_migration.config import AuroraConnectionSettings

_BOOTSTRAP_SQL: Final[Path] = (
    Path(__file__).resolve().parents[1] / "sql" / "V0__schemas_and_roles.sql"
)

# Assumptions: these five schemas are the ones the ETL loads into, and they are listed
#   literally rather than derived from the loader's target registry. The registry answers "where
#   does this dataset go"; this list is the PRIVILEGE decision, and stating it here is what makes a
#   sixth schema appearing in a grant a visible edit in this file rather than a silent consequence
#   of a new target.
_READ_SCHEMAS: Final[tuple[str, ...]] = ("auth", "account", "card", "ledger", "reference")

# Assumptions: the three schemas the verifier must NOT reach. batch and authorization hold nothing
#   this package loads, and reporting holds masked presentation views rather than records -- so a
#   grant on any of the three would be authority for which no verification has a use.
_WITHHELD_SCHEMAS: Final[tuple[str, ...]] = ("batch", "authorization", "reporting")

# Assumptions: every privilege that can change data or schema, spelled as the words a GRANT would
#   use. ALL is included because `GRANT ALL` is how an over-broad grant is usually written, and it
#   is the one spelling that confers every other word in this tuple at once.
_WRITE_PRIVILEGES: Final[tuple[str, ...]] = (
    "ALL",
    "INSERT",
    "UPDATE",
    "DELETE",
    "TRUNCATE",
    "REFERENCES",
    "TRIGGER",
    "CREATE",
)

# Assumptions: the only two privileges a verification has any use for. Stating the ALLOWED set as
#   well as the forbidden words is what makes the pair exhaustive: the forbidden list catches the
#   words known today, and this one catches a privilege nobody thought to forbid.
_PERMITTED_PRIVILEGES: Final[frozenset[str]] = frozenset({"USAGE", "SELECT"})

_GRANT_TO_VERIFIER: Final[re.Pattern[str]] = re.compile(
    rf"\bTO\s+{VERIFIER_ROLE}\b",
)
_PRIVILEGE_LIST: Final[re.Pattern[str]] = re.compile(r"GRANT\s+(.*?)\s+ON\b", re.DOTALL)


def _bootstrap_sql() -> str:
    """Read the bootstrap script.

    Returns
    -------
    str
        The script's text exactly as committed.

    Raises
    ------
    AssertionError
        If the script is absent, which would make every assertion below vacuously true.
    """
    assert _BOOTSTRAP_SQL.is_file(), f"the bootstrap script is missing at {_BOOTSTRAP_SQL}"
    return _BOOTSTRAP_SQL.read_text(encoding="utf-8")


def _executable_sql() -> str:
    """Read the bootstrap script with its whole-line comments removed.

    Returns
    -------
    str
        The script's executable remainder, with every line whose first non-blank characters are
        ``--`` dropped.

    Raises
    ------
    AssertionError
        If the script is absent.
    """
    # WHY : Assumptions: the comments are STRIPPED before any grant assertion, because this file's
    #   own rationale prose names the privileges it declines to grant -- "no INSERT, UPDATE, DELETE,
    #   TRUNCATE" appears in section 5b as an explanation. A search over the raw text would match
    #   that sentence and report a write grant that does not exist, so the assertions would be
    #   measuring the documentation rather than the statements.
    lines = _bootstrap_sql().splitlines()
    return "\n".join(line for line in lines if not line.lstrip().startswith("--"))


def _verifier_grants() -> tuple[str, ...]:
    """Return every executable statement that grants something to the verification role.

    Returns
    -------
    tuple[str, ...]
        The semicolon-delimited statements, comments removed, whose grantee is the verifier.

    Raises
    ------
    AssertionError
        If no statement grants the verifier anything, which would mean a role that can read
        nothing and would make the privilege assertions below vacuous.
    """
    # WHY : Alternatives Considered: the text is split into STATEMENTS and each is inspected whole,
    #   rather than searching the file for a pattern. A grant's grantee and its object can sit on
    #   different lines -- `ALTER DEFAULT PRIVILEGES ... IN SCHEMA auth` puts the schema before the
    #   GRANT keyword -- so a line-oriented or pattern-oriented search reads one of the two and
    #   misses the other. Splitting on `;` is exact here because a grant contains none.
    statements = tuple(
        statement
        for statement in _executable_sql().split(";")
        if _GRANT_TO_VERIFIER.search(statement)
    )
    assert statements, "the bootstrap grants the verification role nothing at all"
    return statements


def test_the_verifier_is_a_login_role_the_package_provisions_a_credential_for() -> None:
    """Assert the verifier is in the login inventory and composes a secret name.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the role is absent from the inventory, or cannot compose a secret name.
    """
    # WHY : Assumptions: membership in the inventory is what gets the role a credential at all --
    #   infra/modules/secrets creates one secret per element and the bootstrap applies one setting
    #   per element. A read-only role still LOGS IN, so a verifier omitted here would exist with a
    #   null password, the bootstrap would refuse to commit, and the reason would have to be traced
    #   back from a role name in a SQL notice.
    assert VERIFIER_ROLE in LOGIN_ROLE_NAMES
    # WHY : Trade-offs: the SECRET NAME the role resolves to is deliberately not asserted here.
    #   `test_config_name_contract` parametrizes that assertion over every element of
    #   LOGIN_ROLE_NAMES, so membership above already carries it, and repeating it here would need
    #   this module to set the environment variable that names the environment -- coupling a
    #   privilege test to configuration resolution it has no stake in.
    # WHY : the verifier is asserted NOT to be a schema's service role and NOT to be an owner. It
    #   is a fourth tier, and conflating it with either would either give it write authority (a
    #   service role) or make it uncredentialed and unusable (an owner).
    assert VERIFIER_ROLE not in set(SCHEMA_ROLES.values())
    assert VERIFIER_ROLE not in set(OWNED_SCHEMA_ROLES.values())
    # WHY : the SCOPE label is asserted not to collide with a schema name, because it is reported
    #   in failure messages where a reader would otherwise take it for one of the eight contexts.
    assert VERIFICATION_SCOPE not in SCHEMA_ROLES


def test_the_bootstrap_creates_the_verifier_and_makes_its_sessions_read_only() -> None:
    """Assert the bootstrap creates the role, credentials it, and sets it read-only by default.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the role is not created, not credentialed, or not made read-only.
    """
    executable = _executable_sql()
    # WHY : the role must appear in BOTH inventories the script drives -- section 1 creates the
    #   LOGIN roles and section 6 applies a credential to each -- and the two are separate arrays.
    #   A role in the first only exists without a password; a role in the second only fails the
    #   script's own existence check. Counting occurrences is what distinguishes the two mistakes
    #   from the correct state.
    assert executable.count(f"'{VERIFIER_ROLE}'") >= 2, (
        "the verifier must appear in both the role-creation inventory and the"
        " credential-application inventory"
    )
    # WHY : the role-level default is the mechanism that makes read-only-ness survive a caller who
    #   forgot to ask for it. The client guard issues its own statement as well, and the two are
    #   kept because they fail differently -- but only this one is a property of the identity.
    assert re.search(
        rf"ALTER\s+ROLE\s+{VERIFIER_ROLE}\s+SET\s+default_transaction_read_only\s*=\s*on",
        executable,
    ), "the bootstrap must set default_transaction_read_only on the verification role"


@pytest.mark.parametrize("schema", _READ_SCHEMAS)
def test_the_bootstrap_grants_the_verifier_read_access_to_each_loaded_schema(
    schema: str,
) -> None:
    """Assert the verifier can read every schema the ETL loads into.

    Parameters
    ----------
    schema : str
        One of the five loaded schemas.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If usage on the schema, table select, or the default privilege for tables created later is
        missing.
    """
    executable = _executable_sql()
    # WHY : Assumptions: the USAGE grant is matched over a COMMA LIST, because the script grants
    #   all five schemas in one statement. A per-schema pattern would fail against correct SQL and
    #   invite someone to split a statement to satisfy a test.
    usage = re.search(
        rf"GRANT\s+USAGE\s+ON\s+SCHEMA\s+([\w\s,\"]+?)\s+TO\s+{VERIFIER_ROLE}\b", executable
    )
    assert usage is not None, f"the verifier holds no USAGE that could include {schema}"
    granted = {name.strip().strip('"') for name in usage.group(1).split(",")}
    assert schema in granted, f"the verifier holds no USAGE on {schema}"
    # WHY : BOTH forms are required and they cover different tables. ON ALL TABLES covers the
    #   tables that exist when the script runs -- none, on a first bootstrap, because the Flyway
    #   migrations create them afterwards -- and the default privilege covers every table created
    #   later. With only the first, the first verification run fails with a permission error
    #   naming a table; with only the second, a re-run against an existing database leaves the
    #   already-created tables unreadable.
    assert re.search(
        rf"GRANT\s+SELECT\s+ON\s+ALL\s+TABLES\s+IN\s+SCHEMA\s+{schema}\s+TO\s+{VERIFIER_ROLE}\b",
        executable,
    ), f"the verifier holds no SELECT on the existing tables of {schema}"
    owner = OWNED_SCHEMA_ROLES[schema]
    assert re.search(
        rf"ALTER\s+DEFAULT\s+PRIVILEGES\s+FOR\s+ROLE\s+{owner}\s+IN\s+SCHEMA\s+{schema}\s*"
        rf"GRANT\s+SELECT\s+ON\s+TABLES\s+TO\s+{VERIFIER_ROLE}\b",
        executable,
    ), f"a table created later in {schema} would not be readable by the verifier"


@pytest.mark.parametrize("privilege", _WRITE_PRIVILEGES)
def test_the_bootstrap_grants_the_verifier_no_privilege_that_could_change_anything(
    privilege: str,
) -> None:
    """Assert no grant to the verifier confers any authority to modify data or schema.

    Parameters
    ----------
    privilege : str
        One privilege word that would let the holder change something.

    Returns
    -------
    None
        The assertion is the result.

    Raises
    ------
    AssertionError
        If any statement grants that privilege to the verification role.
    """
    # WHY : this is the assertion the whole role exists for. It reads the PRIVILEGE LIST of each
    #   grant -- the words between `GRANT` and the first `ON` -- rather than searching the whole
    #   statement, because `GRANT SELECT ON ALL TABLES` contains the word ALL in its object clause
    #   and a whole-statement search for ALL would fail against a correct, minimal grant. The
    #   failure guarded against is a grant added later by someone solving a different problem: a
    #   verification that wanted to write a marker row, or a block copied from the batch section.
    for statement in _verifier_grants():
        listed = _PRIVILEGE_LIST.search(statement)
        assert listed is not None, (
            f"a grant to the verifier names no privilege: {statement.strip()}"
        )
        words = {word.strip().upper() for word in listed.group(1).split(",")}
        assert privilege not in words, (
            f"the verification role is granted {privilege}, so it could alter what it certifies:"
            f" {statement.strip()}"
        )


def test_every_privilege_the_verifier_holds_is_one_a_read_needs() -> None:
    """Assert every grant to the verifier confers only usage or select.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If any grant confers a privilege outside the permitted pair.
    """
    # WHY : Trade-offs: this allow-list duplicates part of the parametrized deny-list above, and the
    #   duplication is deliberate. The deny-list names the privileges known to be dangerous today;
    #   this one catches one that is not on that list -- a future PostgreSQL privilege word, or
    #   `MAINTAIN` -- by refusing anything a read does not need. Neither check subsumes the other.
    for statement in _verifier_grants():
        listed = _PRIVILEGE_LIST.search(statement)
        assert listed is not None, (
            f"a grant to the verifier names no privilege: {statement.strip()}"
        )
        words = {word.strip().upper() for word in listed.group(1).split(",")}
        unexpected = words - _PERMITTED_PRIVILEGES
        assert not unexpected, (
            f"the verification role is granted {sorted(unexpected)}, which a read does not need:"
            f" {statement.strip()}"
        )


@pytest.mark.parametrize("schema", _WITHHELD_SCHEMAS)
def test_the_bootstrap_grants_the_verifier_nothing_on_a_schema_it_never_reads(
    schema: str,
) -> None:
    """Assert the verifier holds no privilege on a schema outside its remit.

    Parameters
    ----------
    schema : str
        One of the three schemas the verification never reads.

    Returns
    -------
    None
        The assertion is the result.

    Raises
    ------
    AssertionError
        If any grant to the verifier names that schema.
    """
    # WHY : Assumptions: the withheld set is asserted per schema, because the interesting failure is
    #   a specific one -- `authorization` or `reporting` swept into the five-schema USAGE list by
    #   someone adding a sixth loaded context. Reporting in particular publishes the MASKED views,
    #   so a verifier able to read through them would make the masking boundary look optional to
    #   the next reader.
    for statement in _verifier_grants():
        assert not re.search(rf"\b{schema}\b", statement), (
            f"the verifier is granted something on {schema}, which it never reads:"
            f" {statement.strip()}"
        )


def test_the_bootstrap_gives_the_verifier_no_sequence_privilege() -> None:
    """Assert the verifier cannot advance a sequence, which is a write that does not roll back.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If a sequence privilege is granted, or if the explicit revoke is missing.
    """
    executable = _executable_sql()
    # WHY : `nextval()` is a WRITE that is not transactional -- it advances shared state and is not
    #   undone by a rollback -- so a read-only identity holding sequence usage could consume
    #   identifiers a later load then skips. That is a data defect produced BY the verification,
    #   which is the one class of failure a verifier must be incapable of.
    for statement in _verifier_grants():
        assert "SEQUENCE" not in statement.upper(), (
            f"the verifier is granted a sequence privilege: {statement.strip()}"
        )
    assert re.search(
        rf"REVOKE\s+ALL\s+ON\s+ALL\s+SEQUENCES[^;]*\bFROM\s+{VERIFIER_ROLE}\b", executable
    ), "the bootstrap does not explicitly withhold sequence privileges from the verifier"


def test_the_verifier_owns_no_schema() -> None:
    """Assert no schema is created under, or reassigned to, the verification role.

    Returns
    -------
    None
        The assertion is the result.

    Raises
    ------
    AssertionError
        If the verifier is named as an owner anywhere.
    """
    executable = _executable_sql()
    # WHY : an owner may ALTER, DROP and TRUNCATE every object in its schema regardless of any
    #   grant, so ownership would silently confer everything the write-privilege assertions above
    #   look for. Both spellings that confer it are checked.
    assert f"AUTHORIZATION {VERIFIER_ROLE}" not in executable
    assert not re.search(rf"OWNER\s+TO\s+{VERIFIER_ROLE}\b", executable)


def _verifier_settings(settings: AuroraConnectionSettings) -> AuroraConnectionSettings:
    """Stamp synthetic settings with the verification role's own user name.

    Parameters
    ----------
    settings : AuroraConnectionSettings
        The synthetic descriptor the shared fixture supplies.

    Returns
    -------
    AuroraConnectionSettings
        The same descriptor with its user set to the verification role.

    Raises
    ------
    None
        Re-stamping a frozen descriptor cannot fail.
    """
    return replace(settings, user=VERIFIER_ROLE)


def _certified(fake_aurora: FakeAuroraDatabase, settings: AuroraConnectionSettings) -> object:
    """Open a double connection whose session answers the guard's three statements conformingly.

    Parameters
    ----------
    fake_aurora : FakeAuroraDatabase
        Recording double the probes are arranged on.
    settings : AuroraConnectionSettings
        Synthetic settings, re-stamped with the verification role.

    Returns
    -------
    object
        An open connection from the double.

    Raises
    ------
    None
        Arranging result sets and acquiring a connection cannot fail.
    """
    fake_aurora.arrange_rows("current_user", [(VERIFIER_ROLE,)])
    fake_aurora.arrange_rows("transaction_read_only", [("on",)])
    return fake_aurora.connect(**_verifier_settings(settings).as_connection_params())


def test_the_session_guard_admits_a_read_only_verifier_session(
    fake_aurora: FakeAuroraDatabase, aurora_settings: AuroraConnectionSettings
) -> None:
    """Admit a conforming session and issue the three statements in the load-bearing order.

    Parameters
    ----------
    fake_aurora : FakeAuroraDatabase
        Recording double answering the guard's statements.
    aurora_settings : AuroraConnectionSettings
        Synthetic settings naming an unreachable host.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the guard refuses a conforming session, or issues its statements out of order.
    """
    connection = _certified(fake_aurora, aurora_settings)

    assert require_verification_session(connection) == verifier_role()

    executed = fake_aurora.executed_sql()
    # WHY : the ORDER is the control, so it is what is asserted. The identity must be established
    #   FIRST -- a session that is not the verifier must be refused before this package changes
    #   anything about it -- the enforcement second, and the read-back last, because only a
    #   statement issued after the enforcement can prove the enforcement took effect. All three
    #   present in any other order would satisfy a presence-only assertion while proving nothing.
    assert "current_user" in executed[0]
    assert "READ ONLY" in executed[1]
    assert "transaction_read_only" in executed[2]


def test_the_session_guard_refuses_a_write_capable_session_before_changing_it(
    fake_aurora: FakeAuroraDatabase, aurora_settings: AuroraConnectionSettings
) -> None:
    """Refuse a service-role session, and refuse it before issuing any other statement.

    Parameters
    ----------
    fake_aurora : FakeAuroraDatabase
        Recording double answering the probe with a write-capable role.
    aurora_settings : AuroraConnectionSettings
        Synthetic settings naming an unreachable host.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the guard admits the session, or acts on it before refusing.
    """
    # WHY : Assumptions: the role arranged is `carddemo_ledger`, which is exactly what the
    #   verification path used to connect as -- a role holding named DML on the tables being
    #   certified. Arranging an obviously wrong value such as "postgres" would pass this test while
    #   leaving the real regression, a plausible service role, undetected.
    writable = SCHEMA_ROLES["ledger"]
    fake_aurora.arrange_rows("current_user", [(writable,)])
    connection = fake_aurora.connect(**_verifier_settings(aurora_settings).as_connection_params())

    with pytest.raises(VerificationSessionError) as refusal:
        require_verification_session(connection)

    message = str(refusal.value)
    assert VERIFIER_ROLE in message
    assert writable in message
    # WHY : the refusal is asserted to be TOTAL: nothing but the probe ran. A guard that set the
    #   session read-only and then refused would have acted on a session it was about to reject,
    #   and on a pooled connection that change outlives the refusal.
    executed = fake_aurora.executed_sql()
    assert len(executed) == 1
    assert "current_user" in executed[0]
    # WHY : the host and the trust anchor are asserted ABSENT from the message. The database name
    #   deliberately is not: both role names begin with the product name, so a substring check for
    #   `carddemo` would fail on a correct message.
    assert str(aurora_settings.host) not in message
    assert str(aurora_settings.ssl_root_cert) not in message


def test_the_session_guard_refuses_a_session_the_server_reports_as_writable(
    fake_aurora: FakeAuroraDatabase, aurora_settings: AuroraConnectionSettings
) -> None:
    """Refuse a verifier session whose transaction is not read-only after being set read-only.

    Parameters
    ----------
    fake_aurora : FakeAuroraDatabase
        Recording double reporting the session as writable.
    aurora_settings : AuroraConnectionSettings
        Synthetic settings naming an unreachable host.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If a session the server reports as writable is admitted.
    """
    fake_aurora.arrange_rows("current_user", [(VERIFIER_ROLE,)])
    fake_aurora.arrange_rows("transaction_read_only", [("off",)])
    connection = fake_aurora.connect(**_verifier_settings(aurora_settings).as_connection_params())

    # WHY : this is the case the role's own default is supposed to make impossible, and it is
    #   asserted precisely because "should be impossible" is where a control stops being checked.
    #   The reachable arrangements are real: a cluster bootstrapped by an older revision of the
    #   script, a role recreated by hand, or a pooler that resets session state after the
    #   enforcement statement. In each of them the session really can write.
    with pytest.raises(VerificationSessionError, match="transaction_read_only"):
        require_verification_session(connection)


def test_opening_a_verification_connection_refuses_settings_for_another_role(
    monkeypatch: pytest.MonkeyPatch,
    fake_aurora: FakeAuroraDatabase,
    aurora_settings: AuroraConnectionSettings,
) -> None:
    """Refuse settings naming another role before any connection is opened.

    Parameters
    ----------
    monkeypatch : pytest.MonkeyPatch
        Used to bind the connect seam to the double.
    fake_aurora : FakeAuroraDatabase
        Recording double, which must record no connection.
    aurora_settings : AuroraConnectionSettings
        Synthetic settings naming a schema's service role rather than the verifier.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If a connection is opened on settings for another role.
    """
    from carddemo_migration.verify import session

    opened: list[object] = []

    def _connect(resolved: AuroraConnectionSettings, *, expected_role: str | None = None) -> object:
        """Record an attempt to connect, which this test asserts never happens.

        Parameters
        ----------
        resolved : AuroraConnectionSettings
            The settings the caller resolved.
        expected_role : str | None
            The role the caller expects.

        Returns
        -------
        object
            A recording connection.

        Raises
        ------
        None
            Recording cannot fail.
        """
        opened.append(expected_role)
        return fake_aurora.connect(**resolved.as_connection_params())

    monkeypatch.setattr(session, "connect", _connect)

    # WHY : the settings check exists so that the cheap failure happens first -- naming the wrong
    #   role is reported as such, rather than as an authentication failure an operator has to
    #   interpret. Asserting that NOTHING was opened is what distinguishes the two.
    with pytest.raises(VerificationSessionError, match=VERIFIER_ROLE):
        open_verification_connection(aurora_settings)

    assert opened == []


def test_a_verification_connection_that_cannot_be_certified_is_closed(
    monkeypatch: pytest.MonkeyPatch,
    fake_aurora: FakeAuroraDatabase,
    aurora_settings: AuroraConnectionSettings,
) -> None:
    """Close the connection when the live session fails certification.

    Parameters
    ----------
    monkeypatch : pytest.MonkeyPatch
        Used to bind the connect seam to the double.
    fake_aurora : FakeAuroraDatabase
        Recording double answering the probe with a write-capable role.
    aurora_settings : AuroraConnectionSettings
        Synthetic settings naming an unreachable host.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If a connection that failed certification is left open.
    """
    from carddemo_migration.verify import session

    connections: list[object] = []

    def _connect(resolved: AuroraConnectionSettings, *, expected_role: str | None = None) -> object:
        """Open a recording connection and keep it for inspection.

        Parameters
        ----------
        resolved : AuroraConnectionSettings
            The settings the caller resolved.
        expected_role : str | None
            The role the caller expects, unused by the double beyond being accepted.

        Returns
        -------
        object
            A recording connection.

        Raises
        ------
        None
            Recording cannot fail.
        """
        connection = fake_aurora.connect(**resolved.as_connection_params())
        connections.append(connection)
        return connection

    monkeypatch.setattr(session, "connect", _connect)
    fake_aurora.arrange_rows("current_user", [(SCHEMA_ROLES["card"],)])

    with pytest.raises(VerificationSessionError):
        open_verification_connection(_verifier_settings(aurora_settings))

    # WHY : a leaked session is not a leak an operator sees -- it is one the cluster's connection
    #   limit sees on the next run. The caller cannot close what it was never handed, so the only
    #   place the close can happen is here, on the failure path.
    assert connections and all(getattr(each, "closed", False) for each in connections)


def test_the_scope_a_role_s_credential_is_read_from_is_that_role_s_own_tier() -> None:
    """Assert each login tier maps to the scope whose secret carries its credential.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If a role resolves to another tier's scope, or an unknown role is silently accepted.
    """
    assert credentials._scope_for_role(SCHEMA_ROLES["ledger"]) == "ledger"
    assert credentials._scope_for_role(MIGRATION_SCHEMA_ROLES["ledger"]) == "ledger"
    assert credentials._scope_for_role(VERIFIER_ROLE) == VERIFICATION_SCOPE
    # WHY : an unrecognised role is REFUSED rather than defaulted to a schema, because a defaulted
    #   scope reads some other role's secret: the step would apply that role's password to this
    #   one, report success, and the failure would surface later as an authentication error against
    #   a credential that reads as correct in the store.
    with pytest.raises(credentials.CredentialApplicationError, match="not a login role"):
        credentials._scope_for_role("carddemo_not_a_role")


def test_each_tier_s_credential_is_read_through_its_own_resolver(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Assert a role's settings come from the resolver its own consumer uses.

    Parameters
    ----------
    monkeypatch : pytest.MonkeyPatch
        Used to record which resolver each role reaches.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If any role is resolved through another tier's resolver.
    """
    reached: list[tuple[str, str]] = []

    def _record(name: str) -> object:
        """Build a recorder that notes which resolver was called and with what.

        Parameters
        ----------
        name : str
            The resolver's own name.

        Returns
        -------
        object
            A callable accepting an optional scope.

        Raises
        ------
        None
            Building a recorder cannot fail.
        """

        def _resolver(scope: str = "") -> str:
            """Record one resolution and stand in for a settings object.

            Parameters
            ----------
            scope : str
                The scope the caller passed, empty for the verifier's no-argument resolver.

            Returns
            -------
            str
                A marker in place of settings; no caller in this test reads a field from it.

            Raises
            ------
            None
                Recording cannot fail.
            """
            reached.append((name, scope))
            return name

        return _resolver

    monkeypatch.setattr(credentials, "resolve_aurora_settings", _record("runtime"))
    monkeypatch.setattr(credentials, "resolve_migration_settings", _record("migration"))
    monkeypatch.setattr(credentials, "resolve_verifier_settings", _record("verification"))

    credentials._settings_for_role(SCHEMA_ROLES["card"])
    credentials._settings_for_role(MIGRATION_SCHEMA_ROLES["card"])
    credentials._settings_for_role(VERIFIER_ROLE)

    # WHY : the three resolvers assert three DIFFERENT user names against three different secrets,
    #   so routing every tier through the runtime resolver would hand a migration role the runtime
    #   role's password. The pairing of resolver to scope is therefore the assertion, not merely
    #   that some resolver ran.
    assert reached == [("runtime", "card"), ("migration", "card"), ("verification", "")]


def _password_secrets(monkeypatch: pytest.MonkeyPatch) -> None:
    """Bind every credential resolver to a synthetic per-role password.

    Parameters
    ----------
    monkeypatch : pytest.MonkeyPatch
        Used to replace the three resolvers and the master resolver.

    Returns
    -------
    None
        The bindings are the result.

    Raises
    ------
    None
        Binding cannot fail.
    """

    def _settings(role: str) -> object:
        """Return a minimal stand-in carrying one role's synthetic password.

        Parameters
        ----------
        role : str
            The role whose descriptor is wanted.

        Returns
        -------
        object
            An object exposing ``password`` and ``as_connection_params``.

        Raises
        ------
        None
            Construction cannot fail.
        """

        class _Descriptor:
            """A settings stand-in exposing only what this step reads.

            Returns
            -------
            None
                Instances carry a password and can render connection parameters.

            Raises
            ------
            None
                Construction cannot fail.
            """

            password = f"synthetic-{role}"

            @staticmethod
            def as_connection_params() -> dict[str, object]:
                """Render the connection keywords the double validates.

                Returns
                -------
                dict[str, object]
                    Keywords including the TLS pair the double requires.

                Raises
                ------
                None
                    Rendering cannot fail.
                """
                return {
                    "host": "aurora.carddemo.invalid",
                    "port": 5432,
                    "dbname": "carddemo",
                    "user": role,
                    "password": _Descriptor.password,
                    "sslmode": REQUIRED_SSL_MODE,
                    "sslrootcert": "/nonexistent/synthetic-test-anchor.pem",
                }

        return _Descriptor()

    monkeypatch.setattr(
        credentials, "resolve_aurora_settings", lambda scope: _settings(SCHEMA_ROLES[scope])
    )
    monkeypatch.setattr(
        credentials,
        "resolve_migration_settings",
        lambda scope: _settings(MIGRATION_SCHEMA_ROLES[scope]),
    )
    monkeypatch.setattr(credentials, "resolve_verifier_settings", lambda: _settings(VERIFIER_ROLE))
    monkeypatch.setattr(credentials, "resolve_master_settings", lambda: _settings("carddemo_admin"))


def test_the_credential_step_applies_and_proves_every_login_role(
    monkeypatch: pytest.MonkeyPatch, fake_aurora: FakeAuroraDatabase
) -> None:
    """Assert the step covers every login role, the verification role among them.

    Parameters
    ----------
    monkeypatch : pytest.MonkeyPatch
        Used to bind the resolvers, the connection and the login proof.
    fake_aurora : FakeAuroraDatabase
        Recording double the master connection is taken from.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the step omits any login role from either the application or the proof.
    """
    _password_secrets(monkeypatch)
    monkeypatch.setattr(
        credentials,
        "_connect",
        lambda settings: fake_aurora.connect(**settings.as_connection_params()),
    )
    # WHY : Assumptions: the catalogue probes are arranged on fragments that are DISJOINT between
    #   the two statements. The privilege probe names `pg_authid` inside a function call and the
    #   credential-presence query names it in a FROM clause, so keying the second on `pg_authid`
    #   would also match the first -- and the double lets the most recent arrangement win, so the
    #   privilege probe would return the empty set and the step would refuse. `rolpassword` appears
    #   in the second statement only.
    fake_aurora.arrange_rows("pg_roles", [(role,) for role in LOGIN_ROLE_NAMES])
    fake_aurora.arrange_rows("has_table_privilege", [(True,)])
    fake_aurora.arrange_rows("rolpassword", [])

    proved: list[str] = []
    # WHY : Trade-offs: the login proof is REPLACED by a recorder rather than driven through the
    #   double. `_verify_login` reads `CURRENT_USER` back and compares it to the role, and the
    #   double keys its answers on statement text -- so one arrangement would answer every role's
    #   probe with one name and fifteen of the sixteen proofs would fail for a reason that is an
    #   artefact of the double. Which roles were proved is exactly what this test is about, and the
    #   proof's own comparison is asserted separately below on a single role.
    monkeypatch.setattr(credentials, "_verify_login", proved.append)

    stored: list[tuple[str, str]] = []

    def _record_application(cursor: object, role: str, verifier: str) -> None:
        """Record which role each derived verifier was applied to.

        Parameters
        ----------
        cursor : object
            The master cursor, unused by the recorder.
        role : str
            The role being altered.
        verifier : str
            The SCRAM verifier derived for it.

        Returns
        -------
        None
            Recording is the effect.

        Raises
        ------
        None
            Recording cannot fail.
        """
        del cursor
        stored.append((role, verifier))

    # WHY : Trade-offs: the application statement is recorded rather than executed on the double.
    #   `_apply_verifier` composes the statement through the driver's own `sql.SQL(...).format(...)`
    #   -- deliberately, so no value is spliced by hand -- and that yields a `Composed` object the
    #   in-process double cannot record, because the double keys arrangements on statement TEXT. The
    #   pair actually worth asserting is which role each verifier reached, which this captures
    #   directly, and the composition itself is the driver's contract rather than this step's.
    monkeypatch.setattr(credentials, "_apply_verifier", _record_application)

    applied = credentials.apply_service_credentials()

    assert applied == tuple(LOGIN_ROLE_NAMES)
    assert VERIFIER_ROLE in applied
    assert proved == list(LOGIN_ROLE_NAMES)
    # WHY : the credential for the verification role is asserted specifically, because the inventory
    #   equality above would also hold if the application loop had skipped it. A role present in the
    #   report and absent from the catalogue is the exact state that makes a bootstrap log
    #   misleading.
    assert [role for role, _ in stored] == list(LOGIN_ROLE_NAMES)
    applied_to_verifier = [value for role, value in stored if role == VERIFIER_ROLE]
    assert len(applied_to_verifier) == 1
    # WHY : the stored value is asserted to be a WELL-FORMED verifier, not merely present. A
    #   malformed one is rejected by the server rather than by this step, so a deployment would
    #   fail at the statement with the role named and no indication that the derivation was at
    #   fault.
    assert credentials._VERIFIER_PATTERN.fullmatch(applied_to_verifier[0])
    # WHY : the derivations are asserted to be DISTINCT across roles. Each secret carries its own
    #   password and each derivation salts independently, so a repeated value would mean one
    #   password had been read for every role -- which is exactly the defect the per-tier resolver
    #   routing above exists to prevent, seen from the other side.
    assert len({value for _, value in stored}) == len(LOGIN_ROLE_NAMES)


def test_the_login_proof_refuses_a_secret_holding_another_role_s_identity(
    monkeypatch: pytest.MonkeyPatch, fake_aurora: FakeAuroraDatabase
) -> None:
    """Assert the verification role's proof compares the identity the server accepted.

    Parameters
    ----------
    monkeypatch : pytest.MonkeyPatch
        Used to bind the resolvers and the connection.
    fake_aurora : FakeAuroraDatabase
        Recording double answering the identity probe.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If a login as the wrong identity is accepted, or a correct one refused.
    """
    _password_secrets(monkeypatch)
    monkeypatch.setattr(
        credentials,
        "_connect",
        lambda settings: fake_aurora.connect(**settings.as_connection_params()),
    )

    fake_aurora.arrange_rows("current_user", [(VERIFIER_ROLE,)])
    credentials._verify_login(VERIFIER_ROLE)

    # WHY : the case guarded against is a secret populated with another role's payload, which
    #   AUTHENTICATES successfully -- so a proof that only checked "the connection opened" would
    #   pass. Arranging a service role here is the realistic mistake: the two secrets differ by one
    #   path component.
    fake_aurora.arrange_rows("current_user", [(SCHEMA_ROLES["ledger"],)])
    with pytest.raises(credentials.CredentialApplicationError, match=VERIFICATION_SCOPE):
        credentials._verify_login(VERIFIER_ROLE)
