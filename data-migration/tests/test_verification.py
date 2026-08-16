"""Prove the three verification passes can each detect the failure the other two cannot.

Purpose
-------
Establish that row counting, record checksums and money-total parity are three independent
checks rather than three spellings of one, and that each is sensitive to the specific defect
it exists to catch: a load that stopped early or ran twice, a corrupted field where the
counts agree, and a sign or decimal-point error where both the counts and the field bytes
agree. The suite also proves none of the three renders a field value, because a verification
report is read by whoever holds log access rather than by whoever is entitled to the records.

Alternatives Considered:
    Two were evaluated. (1) Verifying against a live cluster was rejected for the reason
    ``test_aurora_loader.py`` gives -- the recording double lets the exact statement each
    pass issues be asserted, including that the count is an exact ``COUNT(*)`` rather than a
    planner estimate, which a live connection would answer identically for both and so could
    not distinguish. (2) Asserting the two verification SQL files by rendering them against a
    database was rejected in favour of reading their text: they are applied by an operator
    through ``psql`` and never by this package, so their contract is what they SAY, and the
    two properties that matter -- an exact count and a coalesced sum -- are both readable.

Assumptions:
    Every table the row-count pass covers is checked against the Flyway migrations the owning
    services ship. That is the only assertion available that can catch the verification SQL
    and the schema drifting apart: both are text, neither imports the other, and a query
    naming a table that does not exist fails at the moment an operator runs it to decide
    whether a migration succeeded -- the worst possible moment to discover a typo.

Refactoring Rationale:
    The counting and summing the two verifiers once performed inline now live in
    ``data-migration/sql/V3__verification_surfaces.sql``, as owner-created aggregate-only views
    the verifiers read. The move was forced by privilege: both verifiers are documented to run
    as ``carddemo_reporting``, which holds no ``USAGE`` on the base schemas, so every inline
    ``COUNT(*) FROM account.accounts`` failed with "permission denied for schema account" on
    the first line and an operator following the runbook verified nothing. The tests below
    therefore assert a PAIR of properties per table and per money column: the aggregate is
    present in the SURFACE, and absent from the VERIFIER. Asserting only the first would pass a
    revision that put the counting back into the verifier alongside the surface -- exactly the
    regression that reintroduces the permission failure -- while asserting only the second
    would pass a verifier that counts nothing anywhere.

Trade-offs:
    The digest tests use hand-built records rather than shipped extracts, which is the
    opposite of the choice ``test_readers.py`` makes and is deliberate. What is under test
    here is the digest's SENSITIVITY -- that two inputs differing in one respect produce two
    different digests -- and that needs pairs constructed to differ in exactly one respect,
    which no shipped pair of records does.

Refactoring Rationale:
    The checksum group now covers the READ-BACK side of the comparison as well as the source
    side, for every one of the eleven loadable layouts rather than for the three whose every
    comparable column happens to be text or an exact numeric. The gap was structural rather
    than incidental: the source side is projected through ``aurora.prepare_record``, which
    yields characters, an exact decimal or ``None``, while the read-back side is whatever a
    driver builds from the COLUMN's declared type -- an ``int`` for a ``BIGINT`` identifier, a
    ``date`` for a ``DATE``, a ``datetime`` for a ``TIMESTAMP(6)``, a ``UUID`` for the derived
    subject column. Measured against the shipped migrations, eight of the eleven layouts
    carry at least one such column, and each of the eight raised a type error instead of
    reaching a verdict. ``checksum._canonical`` closes that by rendering BOTH sides by value
    class, reached through ``digest_of_record`` and ``compare_record_digests`` and sharpened per
    field by the ``Canon`` map that ``canonicalisation_of`` derives from the layout; the cases
    below hold it closed by digesting each driver type a column of this migration produces and by
    moving one value by the smallest step its type admits, which is what separates a
    reconciliation from a blanket pass.

    Alternatives Considered:
        Rendering the DRIVER value back into the stored fixed-width form instead -- right-padding
        an integer to the field width, quantising a decimal to the declared fractional digits, and
        refusing a value the declared width cannot hold. It was authored independently and is not
        kept. It reaches comparability by the opposite convention, so the two cannot both govern:
        one normalises both sides to bare canonical digits and the other restores the padding as
        data. The kept design is the one the command line and the comparison actually call, and the
        rejected one arrived with no caller. What its convention would have bought is a refusal
        where a value could not have been stored at all, rather than a reported difference; that is
        a narrower gain than a second canonicalisation contract costs.

Assumptions:
    Each column's driver type is derived from the owning service's own Flyway migration
    rather than from a table written out here. A hand-written table would be true of whatever
    it happened to say, so a column changed from ``CHAR(11)`` to ``BIGINT`` -- exactly the
    change that opens this gap -- would leave the tests green while the pass broke.
"""

from __future__ import annotations

import datetime
import hashlib
import re
import uuid
from collections.abc import Mapping
from dataclasses import dataclass
from decimal import Decimal
from pathlib import Path
from types import MappingProxyType
from typing import TYPE_CHECKING, Final

import pytest

# Assumptions: the double's contract EXCEPTION is imported from ``conftest`` by name, because the
#   fixture hands back an instance and one case below asserts the class the instance raises.
#   pytest's default import mode puts the tests directory on the path, so the sibling import
#   resolves however the suite is invoked, and ``test_doubles.py`` reaches these classes so too.
# Trade-offs: the import sorter groups ``conftest`` with the third-party block because it cannot
#   tell a sibling test module from an installed distribution. That placement is accepted rather
#   than suppressed, since a per-file ignore would switch the rule off for every later import here.
from conftest import FakeClientContractError

from carddemo_migration.copybook import ISO_FORM, layouts
from carddemo_migration.copybook.layouts import ACCOUNT_LAYOUT as _ACCOUNT_LAYOUT
from carddemo_migration.copybook.layouts import DALYTRAN_LAYOUT, INTTRAN_LAYOUT
from carddemo_migration.loaders.aurora import (
    LoadContext,
    TableTarget,
    prepare_record,
    target_for,
)
from carddemo_migration.loaders.protected_columns import CustomerIdentifierCipher, DataKey
from carddemo_migration.readers.factory import RecordReader
from carddemo_migration.verify.checksum import (
    _TAG_FIELD,
    _TAG_RECORD,
    DIGEST_NAME,
    FRAME_DELIMITER,
    NULL_RENDERING,
    REPORTED_DIFFERENCE_LIMIT,
    SEALED_ENVELOPE_MARKERS,
    SEALED_ENVELOPE_MIN_BYTES,
    Canon,
    ChecksumComparison,
    ChecksumVerificationError,
    DifferenceKind,
    SealableValueTally,
    TimestampContractError,
    audit_sealed_columns,
    canonicalisation_of,
    compare_record_digests,
    deterministic_field_names,
    digest_of_record,
    digest_records,
    normalized_timestamp_field_names,
    refined_canonicalisation,
    validated_timestamp,
)
from carddemo_migration.verify.gate import (
    GATE_PASSES,
    CombinedPassResult,
    VerificationGateError,
    run_verification_gate,
)
from carddemo_migration.verify.money_parity import (
    MONEY_SCALE,
    MONEY_TOTAL_COLUMN_COUNT,
    MONEY_TOTAL_COLUMNS,
    MONEY_TOTAL_QUERY_DIGEST,
    MONEY_TOTAL_TABLE_COUNT,
    MoneyParityLine,
    MoneyParityVerdict,
    MoneyParityVerificationError,
    MoneyQueryError,
    MoneyResultSetContractError,
    MoneyTotalReport,
    MoneyTotalRow,
    SourceExtract,
    SourceMoneyTotal,
    _money_total_rows,
    compare_money_totals,
    declared_money_columns,
    money_total_query_path,
    read_money_total_query,
    read_source_totals,
    total_source_column,
    total_source_money,
    total_target_money,
    verify_money_total_rows,
    verify_money_totals,
)
from carddemo_migration.verify.money_parity import (
    reporting_role as money_reporting_role,
)
from carddemo_migration.verify.money_parity import (
    require_reporting_session as require_money_reporting_session,
)
from carddemo_migration.verify.row_counts import (
    NO_DATASET_LABEL,
    ROW_COUNT_QUERY_DIGEST,
    SEED_DATASET_BASELINES,
    UNSEEDED_LAYOUT_NAME,
    RowCountRow,
    RowCountVerdict,
    RowCountVerificationError,
    VerificationQueryError,
    _row_count_rows,
    compare_counts,
    count_source_records,
    count_target_rows,
    read_row_count_query,
    reporting_role,
    require_reporting_session,
    verify_row_count_rows,
)

if TYPE_CHECKING:
    from collections.abc import Callable, Mapping

    from conftest import FakeAuroraDatabase

_SQL_ROOT = Path(__file__).resolve().parents[1] / "sql" / "verify"
_DDL_ROOT = Path(__file__).resolve().parents[1] / "sql"
_SERVICES_ROOT = Path(__file__).resolve().parents[2] / "services"
_MIGRATION_RUNBOOK = Path(__file__).resolve().parents[2] / "docs" / "runbooks" / "data-migration.md"

# Assumptions: the migration that carries the aggregate-only verification surfaces, and the
#   three views it creates. They are named literally for the same reason the table tuple below
#   is: reading them out of the file would make every assertion true of whatever the file
#   happens to contain, including a file that creates nothing.
_SURFACES_DDL = "V3__verification_surfaces.sql"
_ROW_COUNT_SURFACE = "reporting.v_verification_row_counts"
_AUTH_ROW_COUNT_SURFACE = "auth.v_verification_row_counts"
_MONEY_TOTAL_SURFACE = "reporting.v_verification_money_totals"

# WHY : the auth branch is a second view rather than a tenth UNION ALL branch because
#   carddemo_reporting_owner -- the owner whose privileges a non-security_invoker view's base
#   reads are checked against -- holds no USAGE on the auth schema. The two views are therefore
#   counted TOGETHER wherever an assertion is about "every counted table", so the split cannot
#   quietly drop the users count from the pass.
_ROW_COUNT_SURFACES = (_ROW_COUNT_SURFACE, _AUTH_ROW_COUNT_SURFACE)

# Assumptions: the login role the runbook documents for both verifiers. It is spelled here so
#   that the grant assertions below and the runbook cannot drift apart silently; the trailing
#   word boundary in every pattern built from it is load-bearing, because carddemo_reporting is
#   a prefix of carddemo_reporting_owner and the two roles hold deliberately different
#   privileges -- the owner reads the base tables, the login role reads only these views.
_VERIFICATION_ROLE = "carddemo_reporting"

# Assumptions: the eleven tables the row-count query covers are stated literally so that a
#   table dropping out of the query is a visible edit here. Reading them back out of the SQL
#   would make the assertion true of any query, including one covering a single table.
# WHY : Refactoring Rationale: ledger.transaction_rejects was removed from this tuple. The
#   row-count pass compares each target table against the record count of the SEED DATASET it
#   was loaded from, and that table has no seed dataset -- it is written by the posting job at
#   run time. Counting it produced a row whose expected count could only ever be unknown, so
#   the report carried a line that no operator could act on. The three us_* lookup tables are
#   absent from this tuple for the same reason: they are seeded from the condition-name lists
#   in app/cpy/CSLKPCDY.cpy rather than from any dataset under app/data.
_COUNTED_TABLES = (
    ("account", "accounts"),
    ("account", "customers"),
    ("account", "card_xref"),
    ("card", "cards"),
    ("ledger", "transactions"),
    ("ledger", "daily_transactions"),
    ("ledger", "transaction_category_balances"),
    ("reference", "transaction_types"),
    ("reference", "transaction_categories"),
    ("reference", "disclosure_groups"),
    ("auth", "users"),
)

# Assumptions: the nine money columns the totals query covers, each paired with its qualified
#   table. Every one is NUMERIC in its migration; there is no money column in the schema that
#   this query omits, which is the property that makes "money parity passed" mean anything.
_TOTALLED_COLUMNS = (
    ("account.accounts", "curr_bal"),
    ("account.accounts", "credit_limit"),
    ("account.accounts", "cash_credit_limit"),
    ("account.accounts", "curr_cyc_credit"),
    ("account.accounts", "curr_cyc_debit"),
    ("ledger.transactions", "amount"),
    ("ledger.daily_transactions", "amount"),
    ("ledger.transaction_category_balances", "balance"),
    ("reference.disclosure_groups", "interest_rate"),
)

_CONNECTION_PARAMS: dict[str, object] = {
    "host": "aurora.carddemo.invalid",
    "port": 5432,
    "dbname": "carddemo",
    "user": "carddemo_reference",
    "sslmode": "verify-full",
    "sslrootcert": "/nonexistent/synthetic-test-anchor.pem",
}


def _executable_sql(name: str) -> str:
    """Read one verification query with its comment lines removed.

    Purpose
    -------
    Give every assertion below the text psql will actually EXECUTE, so that a rationale
    written in a comment cannot satisfy -- or violate -- an assertion about the query itself.

    Parameters
    ----------
    name : str
        File name beneath ``data-migration/sql/verify``.

    Returns
    -------
    str
        The file's lines with every whole-line ``--`` comment dropped, rejoined.

    Raises
    ------
    AssertionError
        If stripping leaves nothing, which would mean the file was comment-only and every
        assertion below was passing against an empty string.
    """
    # WHY : this exists because the first draft of `test_the_row_count_query_uses_no_planner_
    #   estimate` failed on the query's OWN comment explaining why the planner estimate is not
    #   used. An assertion that a query does not use something must read the executable text,
    #   or it forbids the file from documenting the decision -- which is the opposite of what
    #   Rule 1 asks for.
    return _without_comment_lines((_SQL_ROOT / name).read_text(encoding="utf-8"), name)


def _executable_ddl(name: str) -> str:
    """Read one migration beneath ``data-migration/sql`` with its comment lines removed.

    Purpose
    -------
    Give the surface assertions the text psql will apply, on the same terms
    ``_executable_sql`` gives the verifier assertions. The two roots are separate functions
    rather than one function with a root argument because the two artifacts play different
    parts: one is applied once to CREATE the aggregates, the other is run repeatedly to READ
    them, and a test naming the wrong one should fail to find its file rather than silently
    assert against the other.

    Parameters
    ----------
    name : str
        File name beneath ``data-migration/sql``.

    Returns
    -------
    str
        The file's lines with every whole-line ``--`` comment dropped, rejoined.

    Raises
    ------
    AssertionError
        If stripping leaves nothing, which would mean the migration was comment-only.
    """
    return _without_comment_lines((_DDL_ROOT / name).read_text(encoding="utf-8"), name)


def _without_comment_lines(text: str, label: str) -> str:
    """Drop every whole-line ``--`` comment from one SQL file's text.

    Parameters
    ----------
    text : str
        The file's contents.
    label : str
        File name, used only in the assertion message.

    Returns
    -------
    str
        The remaining lines, rejoined with newlines.

    Raises
    ------
    AssertionError
        If nothing executable remains.
    """
    # WHY : only WHOLE-line comments are dropped, and a trailing comment on a code line is
    #   deliberately left in place. Stripping from the first `--` on any line would corrupt any
    #   statement holding `--` inside a string literal, and this package's SQL does: the owner
    #   assertions in V3 raise messages containing punctuation. Whole-line stripping needs no
    #   lexer to be correct.
    executable = "\n".join(line for line in text.splitlines() if not line.lstrip().startswith("--"))
    assert executable.strip(), f"{label} holds no executable statement"
    return executable


def _surface_statement(ddl: str, view: str) -> str:
    """Return the text of one verification view's ``CREATE`` statement.

    Parameters
    ----------
    ddl : str
        The executable text of the surfaces migration.
    view : str
        Schema-qualified view name whose definition is wanted.

    Returns
    -------
    str
        The substring from the view's ``CREATE OR REPLACE VIEW`` through its terminating
        semicolon.

    Raises
    ------
    AssertionError
        If the view is not created, or its statement is unterminated.
    """
    # WHY : the statement is bounded at the terminating semicolon rather than at the next
    #   CREATE, because the assertions that count aggregates must not be able to see a
    #   neighbouring view's aggregates. The money-totals surface holds eighteen COUNT(*)
    #   occurrences of its own; a boundary that let them leak into the row-count assertion
    #   would make the exact-branch count meaningless.
    start = ddl.find(f"CREATE OR REPLACE VIEW {view}")
    assert start >= 0, f"{view} is not created by {_SURFACES_DDL}"
    end = ddl.find(";", start)
    assert end > start, f"{view} has no terminating semicolon in {_SURFACES_DDL}"
    return ddl[start : end + 1]


def _counting_surfaces() -> str:
    """Return both row-counting view definitions joined, as one searchable body.

    Returns
    -------
    str
        The ``reporting`` and ``auth`` row-count view statements, concatenated.

    Raises
    ------
    AssertionError
        If either view is missing from the surfaces migration.
    """
    ddl = _executable_ddl(_SURFACES_DDL)
    return "\n".join(_surface_statement(ddl, view) for view in _ROW_COUNT_SURFACES)


def _migration_text() -> str:
    """Concatenate every service Flyway migration into one searchable body of DDL.

    Returns
    -------
    str
        Every ``V*.sql`` under ``services/*/src/main/resources/db/migration``, joined.

    Raises
    ------
    AssertionError
        If no migration file is found, which would leave the searches below passing against an
        empty string.
    """
    files = sorted(_SERVICES_ROOT.glob("*/src/main/resources/db/migration/V*.sql"))
    assert files, f"no Flyway migration found beneath {_SERVICES_ROOT}"
    return "\n".join(path.read_text(encoding="utf-8") for path in files)


def test_count_source_records_counts_every_record() -> None:
    """Count each supplied record exactly once, including an empty stream as zero.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the count differs from the number of records supplied.
    """
    assert count_source_records([]) == 0
    assert count_source_records([{"A": "1"}, {"A": "2"}, {"A": "3"}]) == 3


def test_count_target_rows_asks_for_an_exact_count(fake_aurora: FakeAuroraDatabase) -> None:
    """Count target rows with an exact aggregate over quoted identifiers, never an estimate.

    Parameters
    ----------
    fake_aurora : FakeAuroraDatabase
        Recording double, which returns the arranged row and logs the statement.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the statement is not an exact count, or references an unquoted identifier.
    """
    fake_aurora.arrange_rows("COUNT(*)", [(7,)])
    connection = fake_aurora.connect(**_CONNECTION_PARAMS)
    assert count_target_rows(connection, "reference", "transaction_types") == 7
    statement = fake_aurora.executed_sql()[-1]
    # WHY : an exact COUNT is asserted and the planner's own row estimate is asserted absent.
    #   `pg_class.reltuples` answers the same question far more cheaply and is a stale estimate
    #   between analyses, so a verification pass built on it would report parity on a table
    #   whose real count was wrong -- which is precisely the outcome this pass exists to deny.
    assert "COUNT(*)" in statement
    assert "reltuples" not in statement
    assert '"reference"."transaction_types"' in statement


def test_compare_counts_reports_a_short_load_with_a_signed_difference(
    fake_aurora: FakeAuroraDatabase,
) -> None:
    """Report a short load as a mismatch whose difference carries a sign and a direction.

    Parameters
    ----------
    fake_aurora : FakeAuroraDatabase
        Recording double supplying the target count.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the comparison reports a match, or the rendered line omits the signed difference.
    """
    fake_aurora.arrange_rows("COUNT(*)", [(2,)])
    connection = fake_aurora.connect(**_CONNECTION_PARAMS)
    outcome = compare_counts(
        connection,
        "TRANTYPE",
        "reference",
        "transaction_types",
        [{"A": "1"}, {"A": "2"}, {"A": "3"}],
    )
    assert outcome.source_records == 3
    assert outcome.target_rows == 2
    assert not outcome.matched
    rendered = outcome.describe()
    assert rendered.startswith("DIFFER ")
    # WHY : the SIGN is asserted because the two failures it distinguishes need different
    #   responses. A negative difference means the load stopped early and should be re-run; a
    #   positive one means it ran twice and the table must be emptied first. An unsigned
    #   magnitude would leave an operator unable to tell which.
    assert "difference=-1" in rendered


def test_compare_counts_reports_agreement(fake_aurora: FakeAuroraDatabase) -> None:
    """Report agreement when the counts are equal, so the pass can succeed as well as fail.

    Parameters
    ----------
    fake_aurora : FakeAuroraDatabase
        Recording double supplying the target count.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If equal counts are reported as a difference.
    """
    fake_aurora.arrange_rows("COUNT(*)", [(2,)])
    connection = fake_aurora.connect(**_CONNECTION_PARAMS)
    outcome = compare_counts(
        connection, "TRANTYPE", "reference", "transaction_types", [{"A": "1"}, {"A": "2"}]
    )
    # WHY : the agreeing case is asserted alongside the failing one, so neither half of the
    #   comparison can be satisfied alone. A pass that always reported DIFFER would satisfy the
    #   test above and be useless.
    assert outcome.matched
    assert outcome.describe().startswith("MATCH ")


def _reporting_connection(fake_aurora: FakeAuroraDatabase) -> object:
    """Open a double connection whose live session answers as the reporting role.

    Purpose
    -------
    Give the session-guarded entry points a connection they accept, by arranging the one probe
    they make before executing anything.

    Parameters
    ----------
    fake_aurora : FakeAuroraDatabase
        Recording double, on which the ``current_user`` probe is arranged.

    Returns
    -------
    object
        An open connection from the double.

    Raises
    ------
    None
        Arranging a result set and acquiring a connection cannot fail.
    """
    # WHY : Assumptions: the role is read from `reporting_role()` rather than spelled literally,
    #   so this helper follows a rename of the role in the configuration module instead of pinning
    #   a name whose only authority is this file. The name itself is asserted against the bootstrap
    #   SQL by test_config_name_contract, which is where that claim belongs.
    fake_aurora.arrange_rows("current_user", [(reporting_role(),)])
    return fake_aurora.connect(**{**_CONNECTION_PARAMS, "user": reporting_role()})


def test_the_session_guard_admits_a_reporting_session(fake_aurora: FakeAuroraDatabase) -> None:
    """Admit a session that authenticates as the reporting role, and report the role it saw.

    Parameters
    ----------
    fake_aurora : FakeAuroraDatabase
        Recording double answering the ``current_user`` probe.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the guard refuses a conforming session, or probes with something other than
        ``current_user``.
    """
    connection = _reporting_connection(fake_aurora)
    assert require_reporting_session(connection) == reporting_role()
    # WHY : the PROBE is asserted, not just its verdict. `session_user` answers the same question
    #   for an unremarkable session and a different one after SET ROLE, so a guard built on it
    #   would admit a session that authenticated as the reporting role and then assumed a writable
    #   one -- which is the whole failure mode this guard exists to deny.
    statement = fake_aurora.executed_sql()[-1]
    assert "current_user" in statement
    assert "session_user" not in statement


def test_the_session_guard_refuses_a_writable_session(fake_aurora: FakeAuroraDatabase) -> None:
    """Refuse a session on any role but the reporting one, naming both roles and nothing else.

    Parameters
    ----------
    fake_aurora : FakeAuroraDatabase
        Recording double answering the probe with a schema-owner role.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the guard admits the session, or the message discloses a connection detail.
    """
    # WHY : the role arranged is `carddemo_reference`, which is exactly what the command-line
    #   verification path used to connect as: a role holding named DML on the tables being
    #   certified. Arranging an obviously wrong value such as "postgres" would pass this test while
    #   leaving the real regression -- a plausible service role -- undetected.
    fake_aurora.arrange_rows("current_user", [("carddemo_reference",)])
    connection = fake_aurora.connect(**_CONNECTION_PARAMS)
    with pytest.raises(RowCountVerificationError) as refusal:
        require_reporting_session(connection)
    message = str(refusal.value)
    assert reporting_role() in message
    assert "carddemo_reference" in message
    # WHY : Assumptions: the host and the trust anchor's path are asserted ABSENT and the database
    #   name deliberately is not. Both role names in this message begin with the product name, so a
    #   substring check for the database `carddemo` would fail on a correct message -- it would be
    #   measuring the role names it is supposed to carry. The host and the anchor path are the two
    #   values that identify a deployment, and neither has an innocent reason to appear.
    assert str(_CONNECTION_PARAMS["host"]) not in message
    assert str(_CONNECTION_PARAMS["sslrootcert"]) not in message


def test_no_supplied_query_runs_on_a_writable_session(fake_aurora: FakeAuroraDatabase) -> None:
    """Refuse a writable session BEFORE any statement of the committed query reaches the server.

    Parameters
    ----------
    fake_aurora : FakeAuroraDatabase
        Recording double answering the probe with a schema-owner role.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the refusal is raised after a statement reached the server.
    """
    fake_aurora.arrange_rows("current_user", [("carddemo_reference",)])
    connection = fake_aurora.connect(**_CONNECTION_PARAMS)
    # WHY : Refactoring Rationale: the text is driven through the module-private execution seam
    #   because the PUBLISHED fetch no longer accepts query text -- it reads the committed file
    #   itself, which is the point of that change. The seam is what the guard actually protects, so
    #   this test drives it directly rather than through a path that would first have to locate the
    #   shipped file: locating it is a packaging concern and would make an unrelated failure look
    #   like a privilege failure.
    with pytest.raises(RowCountVerificationError):
        _row_count_rows(connection, "select 1 as sentinel_query")
    # WHY : the ABSENCE of the supplied text from the executed log is the assertion that matters.
    #   A guard that refused after running the query would satisfy an exception-only test while
    #   still having executed arbitrary text under an authority that can write.
    assert not any("sentinel_query" in sql for sql in fake_aurora.executed_sql())


def test_the_whole_report_runs_on_the_reporting_session(fake_aurora: FakeAuroraDatabase) -> None:
    """Judge a whole-migration report read over a reporting session, end to end.

    Parameters
    ----------
    fake_aurora : FakeAuroraDatabase
        Recording double answering the probe and then the report.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the report is not reached, or its verdict does not follow from the arranged rows.
    """
    connection = _reporting_connection(fake_aurora)
    # WHY : Assumptions: the report is built from the module's OWN declared pairs rather than from
    #   a hand-written row set, because the pass refuses a report that omits any declared dataset
    #   -- a partial fixture would fail for that reason and prove nothing about the session. One
    #   line is then made short, so the verdict is a MISMATCH: a clean report is also what an empty
    #   result set produces if the plumbing silently returns nothing, and the two would be
    #   indistinguishable.
    # WHY : Assumptions: the pairs are composed from the PUBLISHED declarations --
    #   SEED_DATASET_BASELINES for the ten seeded lines and target_for(UNSEEDED_LAYOUT_NAME) for
    #   the one line that has no seed extract -- rather than from the module's private pair
    #   builder. Reaching into a private name here would make this test the thing that keeps that
    #   name alive, which is the coupling the promotion of config.error_code just removed.
    short_dataset = "acctdata"
    unseeded_target = target_for(UNSEEDED_LAYOUT_NAME)
    # WHY : Assumptions: the baseline-free line carries a count of ZERO so that the ONLY failing
    #   line in this report is the deliberately short one. The pass no longer waves a baseline-free
    #   line through: an unseeded target that holds rows is a failure, so arranging a nonzero count
    #   here would add a second failing line and the assertion below on the failing dataset would
    #   stop distinguishing the shortfall it was written to detect.
    rows = [
        (
            NO_DATASET_LABEL,
            f"{unseeded_target.schema}.{unseeded_target.table}",
            None,
            0,
            None,
            "NO_BASELINE",
        )
    ]
    for baseline in SEED_DATASET_BASELINES.values():
        if baseline.target_table is None:
            continue
        actual = (
            baseline.expected_rows - 1
            if baseline.dataset == short_dataset
            else baseline.expected_rows
        )
        delta = actual - baseline.expected_rows
        rows.append(
            (
                baseline.dataset,
                baseline.target_table,
                baseline.expected_rows,
                actual,
                delta,
                "MISMATCH" if delta else "MATCH",
            )
        )
    fake_aurora.arrange_rows("sentinel_report", rows)
    # WHY : Refactoring Rationale: the report is driven through the module-private execution seam
    #   for the same reason the guard test above is. `verify_row_counts` now reads the committed
    #   query itself and accepts no text, so a caller cannot substitute a statement -- and this test
    #   is about the SESSION the report runs on, not about which file it came from. The public path
    #   over the shipped query is covered by the command-level test in test_cli.py.
    report = verify_row_count_rows(_row_count_rows(connection, "select 1 -- sentinel_report"))
    assert not report.verified
    assert [row.dataset for row in report.mismatches] == [short_dataset]


def test_the_digest_distinguishes_two_decimals_of_the_same_value() -> None:
    """Digest two numerically equal decimals of different scale to different values.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the two scales digest identically.
    """
    fields = ("AMOUNT",)
    # WHY : `Decimal("1.50")` and `Decimal("1.5")` compare EQUAL, so a digest that rendered
    #   them through anything normalising -- a float, scientific notation, `format(v, "g")` --
    #   would collide. The scale is part of the column contract: a NUMERIC(11,2) holding 1.50 is
    #   not the same stored value as one holding 1.5, and a checksum that could not tell them
    #   apart would confirm a load that had silently dropped a decimal place.
    assert digest_of_record({"AMOUNT": Decimal("1.50")}, fields) != digest_of_record(
        {"AMOUNT": Decimal("1.5")}, fields
    )


def test_the_digest_frames_fields_so_a_shifted_boundary_cannot_collide() -> None:
    """Digest two records whose concatenated fields agree but whose boundaries differ, differently.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If a shifted field boundary produces the same digest.
    """
    fields = ("LEFT", "RIGHT")
    # WHY : both records concatenate to "XYZ", so a digest that simply joined the field values
    #   would report them identical. A shifted field boundary is exactly what a wrong offset
    #   produces, and it is the defect this pass is meant to find, so the framing around each
    #   field has to make the boundary part of what is digested.
    assert digest_of_record({"LEFT": "X", "RIGHT": "YZ"}, fields) != digest_of_record(
        {"LEFT": "XY", "RIGHT": "Z"}, fields
    )


def test_the_digest_is_sensitive_to_record_order() -> None:
    """Digest two streams holding the same records in different orders to different values.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If reordering the records leaves the digest unchanged.
    """
    fields = ("KEY",)
    forward = digest_records([{"KEY": "A"}, {"KEY": "B"}], fields)
    reverse = digest_records([{"KEY": "B"}, {"KEY": "A"}], fields)
    # WHY : Assumptions: order sensitivity is a REQUIREMENT here rather than an accident,
    #   because the read-back side orders by the target's own columns and the source side is in
    #   extract order. Two digests that agreed regardless of order would hide a load that had
    #   written the right rows under the wrong keys.
    assert forward.digest != reverse.digest


def test_the_framing_is_injective_over_a_value_carrying_its_own_delimiters() -> None:
    """Digest two records differing only in where a delimiter-bearing value is split, differently.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If a value carrying the framing delimiter can reproduce another split's digested bytes.
    """
    # WHY : ⚠️ Refactoring Rationale: this case replaces one asserting that the two SEPARATORS
    #   were distinct single bytes. That property was real and the framing it described was not
    #   injective: the separators were U+001F and U+001E, and both the cp037 decode path and the
    #   ASCII seed path admit those code points in a text span, so a field CARRYING one could
    #   terminate a field or a record early. The framing is now type-and-length prefixed, so this
    #   case pins the property that actually matters -- no content can forge a boundary -- and it
    #   does so with the delimiter the current framing uses, which is the printable colon.
    fields = ("LEFT", "RIGHT")
    delimiter = FRAME_DELIMITER.decode("ascii")
    forged = f"X{delimiter}1{delimiter}Y"
    assert digest_of_record({"LEFT": forged, "RIGHT": "Z"}, fields) != digest_of_record(
        {"LEFT": "X", "RIGHT": f"1{delimiter}YZ"}, fields
    )
    # WHY : the old separators are digested as ordinary content now, so a value carrying either
    #   one must still be distinguishable from the same characters split across two fields.
    assert digest_of_record({"LEFT": "A\x1fB", "RIGHT": "C"}, fields) != digest_of_record(
        {"LEFT": "A", "RIGHT": "B\x1fC"}, fields
    )
    assert (
        digest_records([{"LEFT": "A", "RIGHT": "B\x1e"}], fields).digest
        != digest_records([{"LEFT": "A", "RIGHT": "B"}], fields).digest
    )


@pytest.mark.parametrize(
    ("label", "left", "right"),
    [
        (
            "field separator inside a text value",
            {"LEFT": "X\x1fY", "RIGHT": "Z"},
            {"LEFT": "X", "RIGHT": "Y\x1fZ"},
        ),
        (
            "record separator inside a text value",
            {"LEFT": "X\x1eY", "RIGHT": "Z"},
            {"LEFT": "X", "RIGHT": "Y\x1eZ"},
        ),
        (
            "field separator inside a bytes value",
            {"LEFT": b"X\x1fY", "RIGHT": b"Z"},
            {"LEFT": b"X", "RIGHT": b"Y\x1fZ"},
        ),
        (
            "record separator inside a bytes value",
            {"LEFT": b"X\x1eY", "RIGHT": b"Z"},
            {"LEFT": b"X", "RIGHT": b"Y\x1eZ"},
        ),
        (
            "both separators inside one bytes value",
            {"LEFT": b"\x1f\x1e", "RIGHT": b""},
            {"LEFT": b"", "RIGHT": b"\x1f\x1e"},
        ),
    ],
)
def test_a_value_carrying_a_separator_byte_cannot_frame_as_a_boundary(
    label: str,
    left: dict[str, str | bytes],
    right: dict[str, str | bytes],
) -> None:
    """Digest two records whose values carry the framing bytes themselves to different values.

    Parameters
    ----------
    label : str
        Names the adversarial shape under test, so a failure says which one collided.
    left : dict[str, str | bytes]
        One record whose field split puts the separator byte in the first field.
    right : dict[str, str | bytes]
        The other record, whose split puts the same bytes in the second field.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the two records digest identically, which would mean changed data could be certified
        unchanged.
    """
    # WHY : Refactoring Rationale: these cases exist because the framing used to be a bare join on
    #   the two separator bytes, defended by a comment asserting that "the separators are byte
    #   values that cannot occur in a decoded field". They can. `_canonical` accepts `bytes`
    #   verbatim -- which is how an enciphered column reaches the digest -- and places no
    #   restriction on the characters of a text field, so a value holding 0x1f or 0x1e framed
    #   exactly like a boundary and two DIFFERENT field tuples produced one digest. That is a
    #   verification pass certifying changed records as equal, which is the worst failure this
    #   module can have, so the property is asserted adversarially rather than argued in a comment.
    # WHY : Assumptions: both the text and the bytes shape are covered for each separator, because
    #   the two types travel different paths through `_canonical` -- text is encoded, bytes are
    #   returned verbatim -- so a fix that framed only one of them would leave the other
    #   ambiguous and a single-type test would pass over the gap.
    assert digest_of_record(left, ("LEFT", "RIGHT")) != digest_of_record(
        right, ("LEFT", "RIGHT")
    ), label


def test_a_value_carrying_a_record_separator_cannot_frame_as_two_records() -> None:
    """Digest one record holding the record separator differently from two separate records.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If a single record whose value spans the record boundary digests as the two records it
        would be mistaken for.

    """
    fields = ("KEY",)
    # WHY : Assumptions: this is the record-level twin of the field-level cases above, and it is
    #   the one a bare terminator could not survive: with records merely terminated by 0x1e, a
    #   single value of "A\x1eB" produced the same stream as the two records "A" and "B", so a
    #   load that had merged two rows into one digested as though it had not. The record header now
    #   declares its own field count and body length, so the boundary is stated rather than
    #   scanned for.
    assert (
        digest_records([{"KEY": "A\x1eB"}], fields).digest
        != digest_records([{"KEY": "A"}, {"KEY": "B"}], fields).digest
    )


def test_a_text_value_and_an_exact_decimal_of_the_same_characters_digest_differently() -> None:
    """Digest a text field and an exact decimal rendering to the same characters differently.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the type of a value is absent from what is digested.
    """
    # WHY : Assumptions: the two types are genuinely interchangeable in this pass's input rather
    #   than a contrived pair -- `prepare_record` projects some columns to text while leaving
    #   others exact, so the same characters arrive as either type depending on the projection. A
    #   framing that carried only lengths would digest them identically and a projection changed
    #   from exact to text would read as no change at all, which is why the framing carries a type
    #   tag as well as a length.
    assert digest_of_record({"AMOUNT": "1.50"}, ("AMOUNT",)) != digest_of_record(
        {"AMOUNT": Decimal("1.50")}, ("AMOUNT",)
    )


def test_the_digest_covers_only_the_named_fields() -> None:
    """Ignore a field the caller did not name, so the digest matches the columns being loaded.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If an unnamed field changes the digest.
    """
    # WHY : Assumptions: the digest is taken over the TARGET's mapped fields, so a field the
    #   target does not load -- padding, or a suppressed verification value -- must not enter
    #   it. If it did, the source digest would cover data the read-back side could not possibly
    #   reproduce, and the pass would report a difference on every correct load.
    named = digest_of_record({"KEY": "A", "IGNORED": "1"}, ("KEY",))
    assert named == digest_of_record({"KEY": "A", "IGNORED": "2"}, ("KEY",))


def test_the_digest_report_names_the_algorithm_and_no_field_value() -> None:
    """Report the algorithm, the record count and the field count, and no field value.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the rendered line omits the algorithm or carries a digested value.
    """
    report = digest_records([{"PAN": "4111111111111111"}], ("PAN",))
    rendered = report.describe()
    assert rendered.startswith(f"{DIGEST_NAME}=")
    assert "records=1" in rendered
    assert "fields=1" in rendered
    # WHY : the value is asserted absent because a digest is publishable precisely and only
    #   where the records are not. A report that echoed the input alongside the digest would
    #   put a primary account number in a retained log while looking like a privacy-safe
    #   summary.
    assert "4111111111111111" not in rendered


def test_total_source_money_totals_exactly_at_the_money_scale() -> None:
    """Total signed decimals exactly and return the total at the money scale.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the total is inexact, or is not carried at the declared scale.
    """
    records = [{"AMT": Decimal("0.10")}, {"AMT": Decimal("0.20")}, {"AMT": Decimal("-0.05")}]
    total, counted = total_source_money(records, ("AMT",))
    assert counted == 3
    # WHY : the expected value is written as a Decimal literal, never as 0.10 + 0.20 - 0.05 in
    #   binary floating point, which is 0.25000000000000006. The whole reason this pass exists
    #   is that money is exact, so its own test may not be written in the representation it
    #   forbids.
    assert total == Decimal("0.25")
    assert total.as_tuple().exponent == -MONEY_SCALE


def test_total_source_money_refuses_a_field_that_is_not_a_decimal() -> None:
    """Refuse to total a character field, rather than comparing text against a number.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If a character field is totalled instead of refused.
    """
    with pytest.raises(ValueError, match="not an exact decimal"):
        total_source_money([{"CODE": "01"}], ("CODE",))


def test_total_source_money_refuses_an_empty_field_list() -> None:
    """Refuse a total over no field at all, which would report parity between differing loads.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If an empty field list yields a total instead of a refusal.
    """
    # WHY : a total over zero fields is zero for every dataset, so both sides would agree and
    #   the pass would report MATCH having compared nothing. That is worse than an error,
    #   because it looks like evidence.
    with pytest.raises(ValueError, match="name the money fields"):
        total_source_money([{"AMT": Decimal("1.00")}], ())


def test_total_target_money_coalesces_an_empty_table_to_zero(
    fake_aurora: FakeAuroraDatabase,
) -> None:
    """Total an empty column as an exact zero, not as a null.

    Parameters
    ----------
    fake_aurora : FakeAuroraDatabase
        Recording double, arranged to answer as an empty table's coalesced sum would.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the statement omits the coalesce or the scale-carrying cast, or the total is not an exact
        scaled zero.
    """
    # WHY : Refactoring Rationale: the arranged answer is `Decimal("0.00")` where it was the integer
    #   `0`, and the change is a correction of the double rather than an accommodation of the code.
    #   The statement now casts the coalesced sum to NUMERIC at the money scale, so a real driver
    #   answers an empty table with a scale-2 decimal; arranging an int made this test assert that
    #   an inexact representation was accepted, which is the very substitution AAP rule T3 forbids
    #   and which `total_target_money` now refuses.
    fake_aurora.arrange_rows("SUM(", [(Decimal("0.00"),)])
    connection = fake_aurora.connect(**_CONNECTION_PARAMS)
    total = total_target_money(connection, "reference", "disclosure_groups", "interest_rate")
    statement = fake_aurora.executed_sql()[-1]
    # WHY : COALESCE is asserted in the statement because SUM over zero rows is NULL, not zero.
    #   Comparing a null against an exact zero reports a difference on an empty table that has
    #   nothing wrong with it, which is the failure mode that trains an operator to ignore the
    #   report.
    assert "COALESCE(SUM(" in statement
    # WHY : the cast is asserted as well, because it is what makes the empty-table answer carry the
    #   scale this pass requires. Without it the server returns numeric of scale ZERO for the
    #   substituted literal, and the scale assertion below could only be satisfied by rounding --
    #   which is what the pass refuses to do.
    assert f"AS NUMERIC(38,{MONEY_SCALE})" in statement
    assert total == Decimal("0.00")
    assert total.as_tuple().exponent == -MONEY_SCALE


def test_compare_money_totals_detects_a_one_cent_difference(
    fake_aurora: FakeAuroraDatabase,
) -> None:
    """Report a one-cent disagreement between the extract and the loaded column.

    Parameters
    ----------
    fake_aurora : FakeAuroraDatabase
        Recording double supplying the target total.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If a one-cent difference is reported as a match, or the rendered line omits it.
    """
    fake_aurora.arrange_rows("SUM(", [(Decimal("1.00"),)])
    connection = fake_aurora.connect(**_CONNECTION_PARAMS)
    outcome = compare_money_totals(
        connection,
        "DISGROUP",
        "reference",
        "disclosure_groups",
        "interest_rate",
        [{"DIS-INT-RATE": Decimal("1.01")}],
        ("DIS-INT-RATE",),
    )
    # WHY : one cent is the tolerance, and it is zero. A sign overpunch read wrongly moves a
    #   total by twice the value and a decimal point read wrongly moves it by a factor of a
    #   hundred, but a single mis-decoded digit in one record of fifty thousand moves it by
    #   cents -- so any tolerance at all would let that one through.
    assert not outcome.matched
    assert "DIFFER " in outcome.describe()
    assert "difference=" in outcome.describe()


def test_compare_money_totals_reports_agreement(fake_aurora: FakeAuroraDatabase) -> None:
    """Report agreement when the two totals are equal at the money scale.

    Parameters
    ----------
    fake_aurora : FakeAuroraDatabase
        Recording double supplying the target total.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If equal totals are reported as a difference.
    """
    fake_aurora.arrange_rows("SUM(", [(Decimal("1.01"),)])
    connection = fake_aurora.connect(**_CONNECTION_PARAMS)
    outcome = compare_money_totals(
        connection,
        "DISGROUP",
        "reference",
        "disclosure_groups",
        "interest_rate",
        [{"DIS-INT-RATE": Decimal("1.01")}],
        ("DIS-INT-RATE",),
    )
    assert outcome.matched
    assert outcome.describe().startswith("MATCH ")
    assert "records=1" in outcome.describe()


@pytest.mark.parametrize(("schema", "table"), _COUNTED_TABLES)
def test_the_row_count_query_counts_every_migrated_table(schema: str, table: str) -> None:
    """Count each of the eleven seed-loaded tables, with an exact aggregate and no estimate.

    Parameters
    ----------
    schema : str
        Owning schema name.
    table : str
        Table name within that schema.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the surface omits the table, if the verifier reads the table directly, if the
        report loses the table's line, or if the table is created by no shipped migration.
    """
    sql = _executable_sql("row_counts.sql")
    surfaces = _counting_surfaces()
    assert re.search(rf"\bFROM\s+{schema}\.{table}\b", surfaces), (
        f"{schema}.{table} is not counted by any verification surface"
    )
    # WHY : the negative is the half that keeps the pass RUNNABLE. carddemo_reporting holds no
    #   USAGE on the base schemas, so a verifier naming this table as a query source fails with
    #   "permission denied for schema <schema>" before it counts anything -- the exact defect
    #   this arrangement replaced. Asserting the aggregate's presence in the surface alone would
    #   pass a revision that also left the inline count behind.
    assert not re.search(rf"\bFROM\s+{schema}\.{table}\b", sql), (
        f"row_counts.sql must not read {schema}.{table} directly"
    )
    # WHY : the report must still carry a LINE for this table, which is what the baseline
    #   VALUES list supplies as a quoted label. Without this the two assertions above are
    #   satisfied by a verifier that reads the surface and then projects nine of its eleven
    #   rows, and the missing table reads as a passing verification rather than as a gap.
    assert f"'{schema}.{table}'" in sql, f"{schema}.{table} has no baseline row in the report"
    # WHY : the table's existence in a shipped migration is asserted in the SAME test as its
    #   presence in the surface, so the two can never be satisfied separately. A query naming a
    #   table nothing creates fails at the moment an operator runs it to decide whether a
    #   migration succeeded, which is the worst moment available to discover a typo.
    assert re.search(rf"\b{schema}\.{table}\b", _migration_text()), (
        f"{schema}.{table} is created by no shipped migration"
    )


def test_the_row_count_query_uses_no_planner_estimate() -> None:
    """Count with an exact aggregate throughout, never with the planner's stale row estimate.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If either artifact references an estimate, if the surfaces count a different number of
        tables than the pass declares, or if the verifier counts anything itself.
    """
    sql = _executable_sql("row_counts.sql")
    surfaces = _counting_surfaces()
    assert "reltuples" not in sql
    assert "reltuples" not in surfaces
    assert surfaces.count("COUNT(*)") == len(_COUNTED_TABLES)
    # WHY : the number of UNION ALL joins is one fewer than the number of counted tables, which
    #   is what proves the branches are joined into a single result set rather than a file of
    #   statements an operator has to run one at a time and could stop halfway through. The
    #   count spans BOTH row-count views: ten branches in the reporting surface, the eleventh
    #   being its read of the auth surface, which contributes that view's single COUNT(*).
    assert surfaces.count("UNION ALL") == len(_COUNTED_TABLES) - 1
    # WHY : Refactoring Rationale: these two counts were asserted against row_counts.sql until
    #   the aggregates moved into the surfaces migration. The verifier is now asserted to hold
    #   NO aggregate and exactly ONE read of the surface, which is the property that keeps the
    #   pass executable by carddemo_reporting: every relation it touches is one that role is
    #   granted, and there is one of them.
    assert sql.count("COUNT(*)") == 0, "row_counts.sql must count nothing itself"
    assert len(re.findall(rf"\bFROM\s+{_ROW_COUNT_SURFACE}\b", sql)) == 1, (
        f"row_counts.sql must read {_ROW_COUNT_SURFACE} exactly once"
    )


@pytest.mark.parametrize(("qualified_table", "column"), _TOTALLED_COLUMNS)
def test_the_money_totals_query_coalesces_every_column(qualified_table: str, column: str) -> None:
    """Total each money column through a coalesced sum, and only columns a migration declares.

    Parameters
    ----------
    qualified_table : str
        Schema-qualified table holding the column.
    column : str
        Money column name.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the column is totalled without a coalesce, if the verifier sums it directly, if the
        report loses the column's line, or if the column is declared by no migration.
    """
    sql = _executable_sql("money_totals.sql")
    surface = _surface_statement(_executable_ddl(_SURFACES_DDL), _MONEY_TOTAL_SURFACE)
    # WHY : Refactoring Rationale: the zero literal is matched as 0 OR 0.00 rather than as a
    #   bare 0, because money_totals.sql now substitutes a SCALE-CARRYING zero and the earlier
    #   exact-text assertion rejected it. Pinning the unscaled spelling was over-specification:
    #   the property this test exists to protect is that the sum is COALESCED at all, so that a
    #   legitimately empty table -- ledger.transactions, which has no seed dataset -- reports a
    #   number instead of a NULL indistinguishable in a diff from a failed query. Which zero
    #   does that is the query's business, and 0.00 does it better, keeping the empty row's
    #   format identical to the eight populated ones so the report stays line-diffable.
    assert re.search(rf"COALESCE\(SUM\({re.escape(column)}\), 0(?:\.00)?\)", surface), (
        f"{column} is not totalled with a coalesce"
    )
    assert re.search(rf"\bFROM\s+{re.escape(qualified_table)}\b", surface)
    # WHY : the verifier is asserted to sum NOTHING and to read no base table, for the same
    #   reason the row-count pass is: carddemo_reporting cannot reach account, card, ledger or
    #   reference, so an inline sum makes the whole report unobtainable rather than partially
    #   wrong. Both halves are asserted here so a revision cannot satisfy one and drop the
    #   other.
    assert not re.search(rf"\bSUM\({re.escape(column)}\)", sql), (
        f"money_totals.sql must not sum {column} directly"
    )
    assert not re.search(rf"\bFROM\s+{re.escape(qualified_table)}\b", sql), (
        f"money_totals.sql must not read {qualified_table} directly"
    )
    # WHY : the descriptor row keeps the column's line in the report, carrying its COBOL field
    #   name, PICTURE clause and SQL type alongside the total. Losing the row loses the only
    #   place the report states WHICH copybook field a total is being compared against.
    assert f"'{column}'" in sql, f"{column} has no descriptor row in the report"
    assert re.search(rf"\b{column}\b", _migration_text())


def test_the_money_totals_query_totals_every_money_column_and_no_other() -> None:
    """Total exactly the nine money columns, each once, with no bare sum among them.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the surface holds a sum that is not coalesced, a different number of sums than the
        nine columns declared, or if the verifier holds a sum of its own.
    """
    surface = _surface_statement(_executable_ddl(_SURFACES_DDL), _MONEY_TOTAL_SURFACE)
    total_sums = surface.count("SUM(")
    coalesced = surface.count("COALESCE(SUM(")
    # WHY : the two counts are compared rather than each checked against nine, so a tenth
    #   column added without a coalesce fails here even though every one of the nine named
    #   above is still correct. That is the regression this test exists to catch, and a
    #   per-column assertion alone cannot see it.
    assert total_sums == coalesced == len(_TOTALLED_COLUMNS)
    # WHY : the surface is the ONLY place a sum may appear across the pass, so the count above
    #   is exact rather than a lower bound. A sum left in the verifier would make nine correct
    #   coalesced sums in the surface irrelevant: the verifier fails on privilege before
    #   returning a row, and this is the assertion that sees it.
    assert "SUM(" not in _executable_sql("money_totals.sql"), (
        "money_totals.sql must total nothing itself"
    )


@pytest.mark.parametrize(
    "view", (_ROW_COUNT_SURFACE, _AUTH_ROW_COUNT_SURFACE, _MONEY_TOTAL_SURFACE)
)
def test_each_verification_surface_is_an_owner_created_security_barrier(view: str) -> None:
    """Create each surface under the owning role, as a barrier view, inside one transaction.

    Parameters
    ----------
    view : str
        Schema-qualified name of one verification surface.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the view is not a security barrier, if the migration does not assert membership of
        the owner it creates the view under, or if it changes role beyond the transaction.
    """
    ddl = _executable_ddl(_SURFACES_DDL)
    statement = _surface_statement(ddl, view)
    # WHY : the barrier is what makes the view a privilege boundary rather than a convenience.
    #   Without it the planner may push a caller-supplied predicate beneath the view, and a
    #   predicate that raises -- a division by a column value, say -- leaks the base rows it was
    #   evaluated against. These surfaces publish aggregates precisely so no base row is
    #   readable, so a pushed-down predicate would defeat their whole reason for existing.
    assert "WITH (security_barrier = true)" in statement, (
        f"{view} must be created as a security barrier"
    )
    # WHY : the owner is derived from the view's own schema rather than listed per view, because
    #   that is the invariant the migration relies on: each surface is created by the role that
    #   owns the schema it lives in, so it can read what that schema's owner can read and no
    #   more. Deriving it means a surface added in a schema whose owner is not asserted fails
    #   here rather than passing against a hand-maintained table someone forgot to extend.
    owner = f"carddemo_{view.split('.')[0]}_owner"
    # WHY : a non-security_invoker view reads its base tables with the VIEW OWNER's privileges,
    #   so which role runs CREATE decides what the view can see. The migration asserts its own
    #   membership rather than trusting the operator's session, because a view created under the
    #   wrong owner does not fail at creation -- it fails later, at every SELECT, by which point
    #   the operator is debugging the verifier instead of the apply step.
    assert f"pg_has_role(current_user, '{owner}', 'MEMBER')" in ddl, (
        f"{_SURFACES_DDL} must assert membership of {owner} before creating {view}"
    )
    assert f"SET LOCAL ROLE {owner};" in ddl, f"{view} must be created under {owner}"
    # WHY : LOCAL scopes the role change to the transaction, so a failed apply cannot leave the
    #   operator's session running as an owner role it never asked for. A bare SET ROLE would
    #   persist for the rest of the connection, which is how an unrelated later statement in the
    #   same psql session ends up creating an object under the wrong owner.
    assert not re.search(r"^\s*SET\s+ROLE\b", ddl, re.MULTILINE), (
        f"{_SURFACES_DDL} must change role with SET LOCAL ROLE, not SET ROLE"
    )


def test_the_verification_surfaces_grant_the_reporting_role_no_base_relation() -> None:
    """Grant the verification role the two surfaces and nothing else the surfaces are built on.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If either surface is ungranted, if any grant to the login role names a base table, or if
        the login role is granted schema usage that would let it read one.
    """
    ddl = _executable_ddl(_SURFACES_DDL)
    for view in (_ROW_COUNT_SURFACE, _MONEY_TOTAL_SURFACE):
        assert re.search(rf"GRANT\s+SELECT\s+ON\s+{view}\s+TO\s+{_VERIFICATION_ROLE};", ddl), (
            f"{view} must be granted to {_VERIFICATION_ROLE}"
        )
    # WHY : the auth surface is granted to the OWNER of the reporting surface rather than to the
    #   login role, because the login role never reads it -- it reads the reporting surface,
    #   which reads this one. Granting it to the login role as well would be harmless today and
    #   wrong in principle: it publishes a relation the pass does not need, and every published
    #   relation is one more thing a later revision can widen.
    assert f"GRANT SELECT ON {_AUTH_ROW_COUNT_SURFACE} TO {_VERIFICATION_ROLE}_owner;" in ddl, (
        f"{_AUTH_ROW_COUNT_SURFACE} must be granted to the reporting surface's owner"
    )
    # WHY : this is the assertion that keeps the fix a NARROWING rather than a widening. The
    #   rejected alternative to these surfaces was granting the login role SELECT on the eleven
    #   base tables, which would have made both verifiers run while handing back every account,
    #   card, customer and transaction row the reporting boundary exists to withhold. A grant
    #   naming a base table here would reintroduce that exposure silently, since both verifiers
    #   would keep passing either way.
    for schema, table in _COUNTED_TABLES:
        assert not re.search(rf"GRANT\b[^;]*\b{schema}\.{table}\b", ddl), (
            f"{_SURFACES_DDL} must not grant any privilege on {schema}.{table}"
        )
    # WHY : schema usage is checked separately because it is the privilege that makes a table
    #   grant reachable at all. The login role holding USAGE on a base schema is not itself
    #   sufficient to read a table, but it removes the outer barrier that currently makes the
    #   permission failure immediate and obvious, so it is withheld too.
    assert not re.search(
        rf"GRANT\s+USAGE\s+ON\s+SCHEMA\s+\w+\s+TO\s+{_VERIFICATION_ROLE}\b(?!_)", ddl
    ), f"{_VERIFICATION_ROLE} must hold no schema usage granted by {_SURFACES_DDL}"


def test_both_verification_queries_stop_on_the_first_error() -> None:
    """Stop on the first error in both queries, each now by the same single-statement form.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If either query holds a psql meta-command, or is not a single statement.
    """
    # WHY : without an error-stop psql reports the failing statement and carries on, so a
    #   verification run against a schema missing one table prints an error among a screen
    #   of successful counts and exits zero. An operator reading the tail of that output
    #   would conclude the load verified. That hazard is real for a file of MANY statements,
    #   and it is the hazard BOTH files now close by holding exactly one statement: there is
    #   no second statement left for an error to be buried among.
    #
    # WHY : Refactoring Rationale: money_totals.sql is asserted PURE here, where an earlier
    #   revision of this test asserted it carried a `\set ON_ERROR_STOP on` meta-command. That
    #   assertion described a superseded draft. money_totals.sql has since been rewritten as a
    #   SINGLE statement, which makes the switch both redundant and harmful for exactly the
    #   three reasons already recorded below for row_counts.sql -- there is no second statement
    #   for it to skip, docs/runbooks/data-migration.md already passes -v ON_ERROR_STOP=1 on
    #   the command line, and a backslash meta-command is psql-only syntax a driver cursor
    #   rejects outright. Keeping the old assertion would have forced the file to carry syntax
    #   that bars it from the money_parity.py pass it belongs to, so the two files are now held
    #   to ONE convention rather than two. Pure SQL is the settled convention for this
    #   directory, recorded at V0__schemas_and_roles.sql L139-L144.
    #
    # WHY : Assumptions: both files are checked in the same test, and by an identical pair of
    #   assertions, so neither can drift back to psql-only syntax on its own. A per-file test
    #   would let one of the pair regress while the other stayed green.
    for name in ("money_totals.sql", "row_counts.sql"):
        sql = _executable_sql(name)
        assert "\\" not in sql, f"{name} holds a psql meta-command and is not pure SQL"
        # WHY : one terminating semicolon is the property that makes a driver cursor return
        #   the whole report. A cursor's execute() exposes only the LAST result set, so a
        #   second statement here would silently discard every row of the report but one
        #   while the harness reported that it had verified the load.
        assert sql.count(";") == 1, f"{name} is not a single statement"


# ---------------------------------------------------------------------------------------------
# The read-back types, and the pairing the comparison uses.
#
# WHY : Assumptions: these cases exercise the comparison against values shaped the way a database
#   driver returns them -- whole numbers for integral columns, dates for date columns, timestamps
#   for timestamp columns and nulls for absent values -- rather than the way a fixed-width decoder
#   returns them. That distinction is the whole subject: both sides were being rendered by a
#   function that only knew the decoder's three types, so verification either raised on the first
#   integral column it met or reported a correct load as corrupt.
# ---------------------------------------------------------------------------------------------

_MONEY_CANON: Final[Mapping[str, Canon]] = {
    "ID": Canon.INTEGER,
    "AMT": Canon.EXACT,
    "WHEN": Canon.TEXT,
}


def _source_side() -> list[dict[str, object]]:
    """Build a source side shaped the way a fixed-width decoder yields it.

    Returns
    -------
    list[dict[str, object]]
        Three records whose identifier is zero-padded characters, whose amount is an exact decimal
        and whose stamp is characters. Deliberately NOT in identifier order, because physical
        extract order is not key order and that difference is what one of the cases below is about.

    Raises
    ------
    None
        Construction cannot fail.
    """
    return [
        {"ID": "00000000003", "AMT": Decimal("3.00"), "WHEN": "2022-07-18 10:00:00.000000"},
        {"ID": "00000000001", "AMT": Decimal("1.00"), "WHEN": "2022-07-18 10:00:00.000000"},
        {"ID": "00000000002", "AMT": Decimal("2.00"), "WHEN": "2022-07-18 10:00:00.000000"},
    ]


def _target_side() -> list[dict[str, object]]:
    """Build a target side shaped the way the database driver returns it.

    Returns
    -------
    list[dict[str, object]]
        The same three records, with the identifier as a whole number and the stamp as a timestamp,
        in identifier order as a keyed read-back returns them.

    Raises
    ------
    None
        Construction cannot fail.
    """
    stamp = datetime.datetime(2022, 7, 18, 10, 0, 0)
    return [
        {"ID": 1, "AMT": Decimal("1.00"), "WHEN": stamp},
        {"ID": 2, "AMT": Decimal("2.00"), "WHEN": stamp},
        {"ID": 3, "AMT": Decimal("3.00"), "WHEN": stamp},
    ]


def _compare(
    source: list[dict[str, object]],
    target: list[dict[str, object]],
    *,
    identity: tuple[str, ...] = ("ID",),
) -> object:
    """Compare two sides under the declared rules, pairing by the given identity.

    Parameters
    ----------
    source : list[dict[str, object]]
        The source side.
    target : list[dict[str, object]]
        The read-back side.
    identity : tuple[str, ...]
        The key to pair by; pass an empty tuple to pair by position.

    Returns
    -------
    object
        The comparison outcome.

    Raises
    ------
    None
        A disagreement is reported in the outcome rather than raised.
    """
    return compare_record_digests(
        record_name="DALYTRAN",
        qualified_table="ledger.daily_transactions",
        source_records=source,
        target_records=target,
        fields=("ID", "AMT", "WHEN"),
        canon=_MONEY_CANON,
        identity=identity,
    )


def test_a_load_verifies_across_the_types_the_driver_returns() -> None:
    """Assert a correct load verifies though the two sides carry one value in different types.

    Returns
    -------
    None
        Nothing; a disagreement is reported as an assertion failure.

    Raises
    ------
    None
        The comparison reports rather than raises.
    """
    # WHY : Assumptions: this is the case that used to CRASH. The identifier reaches the renderer as
    #   an `int` from the driver and as zero-padded characters from the decoder, and the renderer
    #   accepted neither an integer nor a date nor a null -- so the pass raised TypeError on the
    #   primary key of the first dataset it verified, before reaching any data question at all.
    outcome = _compare(_source_side(), _target_side())

    assert outcome.verified, outcome.render()
    assert outcome.differing_records == 0
    assert outcome.absent_records == 0


def test_extract_order_is_not_treated_as_a_difference() -> None:
    """Assert a correct load verifies when its extract order is not its key order.

    Returns
    -------
    None
        Nothing; a false failure is reported as an assertion failure.

    Raises
    ------
    None
        The comparison reports rather than raises.
    """
    # WHY : Assumptions: the source side here is deliberately out of key order, which is the shape
    #   of a sequential extract, and the two cases below it are what make this assertion mean
    #   something. Pairing by POSITION against a key-ordered read-back reports every record as
    #   differing -- a correct load called wholly corrupt -- and pairing by KEY reports nothing.
    keyed = _compare(_source_side(), _target_side())
    positional = _compare(_source_side(), _target_side(), identity=())

    assert keyed.verified, keyed.render()
    assert not positional.verified
    assert positional.differing_records == len(_source_side())


def test_one_absent_row_does_not_cascade_into_a_tail_of_differences() -> None:
    """Assert a single missing row is reported once, and nothing after it is disturbed.

    Returns
    -------
    None
        Nothing; a cascade is reported as an assertion failure.

    Raises
    ------
    None
        The comparison reports rather than raises.
    """
    # WHY : Assumptions: the row removed is the FIRST by key, which is the worst case for a
    #   positional pairing -- every remaining pair is then shifted by one, so one absent row was
    #   reported as an absent row plus a difference on every record that followed it. An operator
    #   reading that could not tell one missing row from a wholesale corruption.
    target = [row for row in _target_side() if row["ID"] != 1]

    outcome = _compare(_source_side(), target)

    assert not outcome.verified
    assert outcome.absent_records == 1
    assert outcome.differing_records == 0, outcome.render()
    kinds = [difference.kind for difference in outcome.differences]
    assert kinds == [DifferenceKind.ABSENT_FROM_TARGET]


def test_an_absent_row_is_reported_by_its_key() -> None:
    """Assert a reported difference names the record's key, and still names no other value.

    Returns
    -------
    None
        Nothing; a missing or over-disclosing locator is reported as an assertion failure.

    Raises
    ------
    None
        Rendering cannot fail.
    """
    target = [row for row in _target_side() if row["ID"] != 2]

    outcome = _compare(_source_side(), target)
    line = outcome.differences[0].describe()

    # WHY : Assumptions: the key IS named, because a position in a stream names a different record
    #   as soon as the extract is regenerated, and an operator has to be able to look the record up.
    assert "key=2" in line
    # WHY : Assumptions: and nothing ELSE is named. The amount is the value on this record that
    #   would matter if disclosed, so its absence from the line is asserted rather than assumed --
    #   the whole report is written to be safe to paste into an issue tracker.
    assert "2.00" not in line


def test_a_corrupted_field_is_still_located_under_keyed_pairing() -> None:
    """Assert keyed pairing has not made the comparison insensitive to a changed value.

    Returns
    -------
    None
        Nothing; an undetected corruption is reported as an assertion failure.

    Raises
    ------
    None
        The comparison reports rather than raises.
    """
    # WHY : Assumptions: this case exists because every other case here asserts that something is
    #   NOT reported, and a comparison that reported nothing at all would satisfy all of them. One
    #   cent is moved on one record, which is the smallest difference the money regime can carry.
    target = _target_side()
    target[1]["AMT"] = Decimal("2.01")

    outcome = _compare(_source_side(), target)

    assert not outcome.verified
    assert outcome.differing_records == 1
    assert [difference.field_name for difference in outcome.differences] == ["AMT"]
    assert "key=2" in outcome.differences[0].describe()


def test_a_null_is_not_the_same_as_an_empty_value() -> None:
    """Assert an absent value and a value with no content are not reported as agreeing.

    Returns
    -------
    None
        Nothing; a false agreement is reported as an assertion failure.

    Raises
    ------
    None
        The comparison reports rather than raises.
    """
    # WHY : Assumptions: a trimmed text column can hold zero characters, and a null says the load
    #   stored nothing -- two different facts. Rendering both as empty bytes would report a column
    #   the load failed to write as agreeing with one it wrote as blank.
    source = [{"ID": "00000000001", "AMT": Decimal("1.00"), "WHEN": ""}]
    target = [{"ID": 1, "AMT": Decimal("1.00"), "WHEN": None}]

    outcome = _compare(source, target)

    assert not outcome.verified
    assert [difference.field_name for difference in outcome.differences] == ["WHEN"]


def test_the_canonicalisation_rules_come_from_the_declared_regimes() -> None:
    """Assert the rule map is derived from the copybook rather than declared beside it.

    Returns
    -------
    None
        Nothing; a rule that disagrees with the layout is reported as an assertion failure.

    Raises
    ------
    None
        Derivation is total over the declared regimes.
    """
    # WHY : Assumptions: the account layout is used because it carries all three rules that occur
    #   in practice -- an unsigned-display key, zoned money and character fields -- so one layout
    #   proves the mapping rather than three.
    layout = target_for("ACCOUNT")
    rules = canonicalisation_of(_ACCOUNT_LAYOUT)

    assert rules["ACCT-ID"] is Canon.INTEGER
    assert rules["ACCT-CURR-BAL"] is Canon.EXACT
    assert rules["ACCT-ACTIVE-STATUS"] is Canon.TEXT
    # WHY : Assumptions: every field the target compares must have a rule, or the comparison would
    #   silently fall back to rendering that field by type alone -- which is the behaviour that made
    #   the padded identifier mismatch in the first place.
    for field_name in layout.comparable_fields():
        assert field_name in rules, f"{field_name} has no declared canonicalisation rule"


def test_the_null_rendering_cannot_be_produced_by_a_real_value() -> None:
    """Assert the absent-value sentinel is not a rendering any decoded value can produce.

    Returns
    -------
    None
        Nothing; a collision is reported as an assertion failure.

    Raises
    ------
    None
        Rendering cannot fail for these inputs.
    """
    # WHY : Assumptions: a sentinel that a field could legitimately render to would make a null
    #   compare equal to that value. The bracketing NUL pair cannot arise from the fixed-width
    #   character fields in this corpus, and this case pins that rather than trusting it.
    assert NULL_RENDERING not in digest_of_record({"K": "\x00"}, ("K",))
    assert digest_of_record({"K": ""}, ("K",)) != digest_of_record({"K": None}, ("K",))


def test_a_numeric_code_stored_as_characters_is_compared_as_characters() -> None:
    """Assert a field the column stores as characters is not compared as a number.

    Returns
    -------
    None
        Nothing; a masked corruption is reported as an assertion failure.

    Raises
    ------
    None
        Narrowing cannot fail.
    """
    # WHY : Assumptions: the transaction category code is the field this is about. It is declared
    #   PIC 9(04) -- unsigned display, which the regime rule alone classifies as a whole number --
    #   and specification rule T1 stores it as CHAR(4) because a numeric field used as a fixed code
    #   keeps its leading zeros as part of the value. Comparing it as a number would pass a
    #   corruption that changed only the padding, which is a worse failure than the one being fixed.
    declared = {"TRANCAT-CD": Canon.INTEGER}
    narrowed = refined_canonicalisation(declared, [{"TRANCAT-CD": "0001"}], ("TRANCAT-CD",))

    assert declared["TRANCAT-CD"] is Canon.INTEGER, "the declared regime is a whole number"
    assert narrowed["TRANCAT-CD"] is Canon.TEXT, "the column stores characters, so text it is"

    padded = {"TRANCAT-CD": "0001"}
    unpadded = {"TRANCAT-CD": "1   "}
    assert digest_of_record(padded, ("TRANCAT-CD",)) != digest_of_record(unpadded, ("TRANCAT-CD",))


def test_narrowing_leaves_an_identifier_compared_as_a_number() -> None:
    """Assert narrowing does not undo the identifier normalisation it exists beside.

    Returns
    -------
    None
        Nothing; a reintroduced padding mismatch is reported as an assertion failure.

    Raises
    ------
    None
        Narrowing cannot fail.
    """
    # WHY : Assumptions: asserted alongside the case above because the two rules pull in opposite
    #   directions and one narrowing serves both. An account identifier is stored as BIGINT, so the
    #   driver returns a whole number and the padded source characters must normalise onto it; a
    #   category code is stored as CHAR, so they must not.
    narrowed = refined_canonicalisation({"ACCT-ID": Canon.INTEGER}, [{"ACCT-ID": 1}], ("ACCT-ID",))

    assert narrowed["ACCT-ID"] is Canon.INTEGER


def test_an_unobservable_column_keeps_its_declared_rule() -> None:
    """Assert a field no loaded row carries a value for keeps the rule the layout declared.

    Returns
    -------
    None
        Nothing; a lost rule is reported as an assertion failure.

    Raises
    ------
    None
        Narrowing cannot fail.
    """
    # WHY : Assumptions: an empty table and an all-null column are both ordinary, and neither may
    #   leave a field with no rule -- a field rendered by type alone is exactly the state that let
    #   a padded identifier mismatch its column. The declared rule is the floor.
    declared = {"ACCT-ID": Canon.INTEGER, "ACCT-GROUP-ID": Canon.TEXT}

    assert refined_canonicalisation(declared, [], ("ACCT-ID",))["ACCT-ID"] is Canon.INTEGER
    all_null = refined_canonicalisation(
        declared, [{"ACCT-GROUP-ID": None}, {"ACCT-GROUP-ID": None}], ("ACCT-GROUP-ID",)
    )
    assert all_null["ACCT-GROUP-ID"] is Canon.TEXT


def test_the_runbook_runs_the_checksum_pass_for_every_dataset_it_counts() -> None:
    """Assert the runbook does not verify a dataset with fewer than all three passes.

    Purpose
    -------
    The runbook is the operator's instruction, so a pass omitted there is a pass never run,
    whatever this package can do. Its checksum coverage was once narrowed to three of ten datasets
    to work around a renderer that raised on an integral column; that limitation is gone, and this
    case is what stops the narrowing returning quietly.

    Returns
    -------
    None
        Nothing; a dataset counted but not digested is reported as an assertion failure.

    Raises
    ------
    AssertionError
        If any dataset receives the row-count pass and not the checksum pass, or if the runbook
        names no verification at all, which would mean this case had stopped reading it.
    """
    # WHY : Alternatives Considered: asserting the exact dataset LIST, which would also have caught
    #   the narrowing. Rejected because it would then need editing whenever a dataset is added, and
    #   a test that must be edited alongside the thing it checks stops being independent evidence.
    #   Comparing the three passes' coverage to each other needs no list: whatever the set of
    #   datasets is, the runbook has to treat all three passes the same way over it.
    text = _MIGRATION_RUNBOOK.read_text(encoding="utf-8")

    def datasets_of(subcommand: str) -> set[str]:
        """Collect the dataset arguments one subcommand is invoked with in the runbook.

        Parameters
        ----------
        subcommand : str
            The verification subcommand to search invocations of.

        Returns
        -------
        set[str]
            Every literal dataset name the subcommand is invoked with, plus the loop placeholder
            where it is invoked over a loop variable.

        Raises
        ------
        None
            An absent subcommand yields an empty set, which the caller asserts against.
        """
        invocations = re.findall(rf"{re.escape(subcommand)}\s*\\?\s*\n?\s*--dataset\s+(\S+)", text)
        return {name.strip('"') for name in invocations}

    counted = datasets_of("verify-row-counts")
    digested = datasets_of("verify-checksum")
    summed = datasets_of("verify-money-parity")

    assert counted, "the runbook names no row-count invocation, so this case is reading nothing"
    assert digested == counted, (
        "the runbook counts datasets it does not digest, so a mandatory pass is documented as"
        f" skipped for them: {sorted(counted - digested)}"
    )
    assert summed == counted, (
        f"the runbook counts datasets it does not sum money for: {sorted(counted - summed)}"
    )


def test_a_subject_reference_compares_across_its_two_forms() -> None:
    """Assert the security-user subject compares though one side is characters and one an object.

    Returns
    -------
    None
        Nothing; a false difference or a raised failure is reported as an assertion failure.

    Raises
    ------
    None
        Narrowing and rendering cannot fail for these inputs.
    """
    # WHY : Assumptions: this field is the one place in the corpus where the two sides carry a value
    #   in genuinely different TYPES by design -- the published seed-user document holds the subject
    #   as characters and the column is declared UUID, which the driver reads back as an identifier
    #   object. Without a rule for it the pass raised on the security-user dataset, so the runbook's
    #   instruction to digest that dataset would have aborted the cutover verification.
    subject = "227ce75a-1111-4222-8333-444444444444"
    narrowed = refined_canonicalisation({}, [{"cognito_sub": uuid.UUID(subject)}], ("cognito_sub",))

    assert narrowed["cognito_sub"] is Canon.UUID
    source = {"cognito_sub": subject}
    target = {"cognito_sub": uuid.UUID(subject)}
    outcome = compare_record_digests(
        record_name="SECUSER",
        qualified_table="auth.users",
        source_records=[source],
        target_records=[target],
        fields=("cognito_sub",),
        canon=narrowed,
    )
    assert outcome.verified, outcome.render()

    # WHY : Assumptions: a DIFFERENT subject is asserted to be caught in the same case, because a
    #   rule that rendered every identifier to one constant would satisfy the assertion above.
    mismatched = compare_record_digests(
        record_name="SECUSER",
        qualified_table="auth.users",
        source_records=[source],
        target_records=[{"cognito_sub": uuid.UUID("00000000-0000-4000-8000-000000000000")}],
        fields=("cognito_sub",),
        canon=narrowed,
    )
    assert not mismatched.verified


# ---------------------------------------------------------------------------------------------
# The admission rule for an excluded run-clock stamp.
#
# WHY : Assumptions: a field marked ``normalize_ts`` is left out of the compared span, so the
#   admission check is the LAST thing that ever looks at it. These cases pin both halves of that
#   rule -- the three shapes the two sides genuinely produce, and the refusal of everything else --
#   because a value admitted here and then excluded from the digest is a value no later comparison
#   can report on.
# ---------------------------------------------------------------------------------------------


def _run_clock_stamp() -> layouts.FieldSpec:
    """Return the descriptor of a real run-clock stamp, taken from a shipped layout.

    Purpose
    -------
    Give the timestamp-admission cases a field descriptor that the migration actually declares,
    rather than one built here. A hand-built descriptor could carry a width no copybook states, and
    the width is half of what the admission rules check.

    Returns
    -------
    layouts.FieldSpec
        The ``TRAN-PROC-TS`` descriptor of the ``TRAN`` layout, which is marked ``normalize_ts``.

    Raises
    ------
    AssertionError
        If that layout stops marking that field as a run-clock stamp, which would mean these cases
        were no longer exercising the admission path at all.
    """
    spec = layouts.LAYOUTS["TRAN"]
    stamp = next(field for field in spec.fields if field.name == "TRAN-PROC-TS")
    assert stamp.normalize_ts, "TRAN-PROC-TS is no longer a run-clock stamp"
    return stamp


# WHY : ⚠️ Refactoring Rationale: a case here asserted that `validated_timestamp` passed a
#   DECODED value of another type straight through, on the reading that a reader which has already
#   decoded the span satisfies a stronger contract than this guard can express. It is withdrawn,
#   because the admission rule the two cases above pin says the opposite and says it for a reason
#   this file also records: the field is EXCLUDED from the digest, so a value admitted here is
#   never compared against anything, and a wrongly-typed one is therefore reported as agreement.
#   The `None` half of the withdrawn case is kept by
#   `test_an_excluded_stamp_admits_only_none_a_naive_datetime_or_declared_characters`.


def test_an_excluded_stamp_admits_only_none_a_naive_datetime_or_declared_characters() -> None:
    """Assert the admitted set for a field the digest excludes is closed and by type.

    Purpose
    -------
    Pin the POSITIVE half of the admission rule. A field marked ``normalize_ts`` is left out of the
    compared span, so whatever is admitted here is the last thing that ever looks at it; the three
    admitted shapes are the ones the two sides of a comparison genuinely produce -- ``None`` for an
    unwritten nullable column, a naive ``datetime`` for a written one, and characters of the
    declared width for the file side.

    Returns
    -------
    None
        Nothing. The assertions are the test.

    Raises
    ------
    AssertionError
        If an admitted value is refused, which would fail every verification of a valid extract.
    """
    stamp = _run_clock_stamp()

    # WHY : Assumptions: the datetime is NAIVE and its microsecond is zero, which is what
    #   psycopg 3.3.4 returns for a TIMESTAMP(6) column -- measured through its own
    #   TimestampLoader rather than assumed -- and what every populated stamp in
    #   app/data/ASCII/dailytran.txt carries.
    assert validated_timestamp(stamp, None) is None
    written = datetime.datetime(2022, 6, 10, 19, 27, 53)
    assert validated_timestamp(stamp, written) is written
    assert validated_timestamp(stamp, "2022-06-10 19:27:53.000000") == "2022-06-10 19:27:53.000000"
    assert validated_timestamp(stamp, " " * stamp.length) == " " * stamp.length


@pytest.mark.parametrize(
    ("value", "named_type"),
    [
        (b"2022-06-10 19:27:53.000000", "bytes"),
        (Decimal("20220610192753.000000"), "Decimal"),
        (1654889273, "int"),
        (datetime.date(2022, 6, 10), "date"),
        (object(), "object"),
    ],
)
def test_an_excluded_stamp_refuses_every_other_type(value: object, named_type: str) -> None:
    """Assert a wrongly-typed value is refused rather than excluded as if it were a stamp.

    Purpose
    -------
    This is the case the admission rule exists for. The field is excluded from the digest, so a
    value admitted here is never compared against anything -- which means a column of the wrong
    type, or a projection that produced the wrong Python object, was reported as agreement. Each
    parameter is a shape a real defect produces: raw ``bytes`` from a column that is not the type
    the layout declares, a ``Decimal`` from a mis-projected numeric, an ``int`` from an epoch
    rendering, a ``date`` from a column narrowed to a day, and an arbitrary object from a projection
    that returned a row wrapper instead of a value.

    Parameters
    ----------
    value : object
        The wrongly-typed value under test.
    named_type : str
        The type name the refusal is required to name, so that the message localises the fault.

    Returns
    -------
    None
        Nothing. The refusal is the test.

    Raises
    ------
    AssertionError
        If the value is admitted, or if the refusal does not name the type, or if it quotes the
        value -- a harness diagnostic must not publish content it was handed.
    """
    stamp = _run_clock_stamp()

    with pytest.raises(TimestampContractError) as refused:
        validated_timestamp(stamp, value)

    message = str(refused.value)
    assert named_type in message, message
    # WHY : Assumptions: the value is asserted ABSENT from the message as well as the type asserted
    #   present, because a stamp's content can be business data. The bytes case is the one that
    #   makes this worth asserting: rendering the offending value would put a decoded record
    #   fragment into a diagnostic.
    assert str(value) not in message, message


def test_an_excluded_stamp_refuses_a_time_zone_aware_datetime() -> None:
    """Assert an aware timestamp is refused, because the declared column cannot produce one.

    Purpose
    -------
    Separate a value of the right TYPE from a value of the right type read from the wrong COLUMN.
    Every stamp in the migration is declared ``TIMESTAMP(6)`` without time zone, and psycopg returns
    a naive value for that -- measured -- while a ``timestamptz`` column yields an aware one. An
    aware value therefore means the comparison is reading a converted instant rather than the
    written one, which is a schema defect that would otherwise surface as an unexplained difference
    or as none at all.

    Returns
    -------
    None
        Nothing. The refusal is the test.

    Raises
    ------
    AssertionError
        If an aware value is admitted, or if the refusal does not name the declared column type.
    """
    stamp = _run_clock_stamp()

    with pytest.raises(TimestampContractError) as refused:
        validated_timestamp(stamp, datetime.datetime(2022, 6, 10, 19, 27, 53, tzinfo=datetime.UTC))

    assert "TIMESTAMP(6) without time zone" in str(refused.value)


# ---------------------------------------------------------------------------------------------
# The money-total inventory the verification query covers.
#
# WHY : Assumptions: these cases derive the inventory from the module rather than restating it, so a
#   column added to the totals query without being declared -- or declared without being totalled --
#   fails here rather than in a run whose report silently covers one column fewer.
# ---------------------------------------------------------------------------------------------


# Assumptions: two real DALYTRAN field names, used by the record-comparison tests below so the
#   positions they report read as positions in a migrated dataset rather than in a toy pair. The
#   comparison itself takes the field tuple from its caller and validates nothing about the names
#   unless a layout is passed, so these are readable labels rather than a contract -- the tests
#   that DO exercise the layout contract pass DALYTRAN_LAYOUT explicitly.
_CHECKSUM_FIELDS: tuple[str, str] = ("DALYTRAN-ID", "DALYTRAN-AMT")


# Assumptions: the money column every report-shape test perturbs. Its layout ships a committed
#   seed extract, so it is comparable, and it is the NARROWEST money field in the schema at
#   S9(04)V99 -- which makes it the one column where a picture or scale assertion built for the
#   ten-digit account columns would pass by accident if the code read the picture from the wrong
#   place. Perturbing the narrow column therefore probes more than perturbing a wide one.
_MONEY_PROBE_COLUMN: tuple[str, str] = ("reference.disclosure_groups", "interest_rate")


# WHY : ledger.transactions.amount is the one declared money column whose layout ships NO seed
#   extract -- the table is written by the posting job at run time rather than loaded from a
#   dataset under app/data -- so the pass reports it "not comparable" instead of refusing the run
#   for a missing measurement. It is named literally, for the same reason _COUNTED_TABLES omits
#   ledger.transaction_rejects: reading the exclusion back out of the code under test would make
#   the assertion true of whatever that code happens to exclude, including everything.
_UNSEEDED_MONEY_COLUMN: tuple[str, str] = ("ledger.transactions", "amount")


# Assumptions: the committed ASCII disclosure-group extract, and the totals its own bytes hold.
#   They are spelled here rather than recomputed by the assertion, because a test that recomputes
#   the expected value with the code under test proves only that the code agrees with itself.
_SEED_EXTRACT_ROOT = Path(__file__).resolve().parents[2] / "app" / "data" / "ASCII"


_DISCGRP_EXTRACT = "discgrp.txt"


_DISCGRP_EXTRACT_TOTAL = Decimal("375.00")


_DISCGRP_EXTRACT_NEGATIVE_ROWS = 0


_DISCGRP_EXTRACT_RECORDS = 51


def _feed(count: int) -> list[dict[str, str | Decimal]]:
    """Build a run of distinguishable records for the record-comparison tests.

    Purpose
    -------
    Produce records whose every field differs from every other record's, so that a comparison
    reporting position ``n`` can only be reporting position ``n``. Records that shared a field
    value would let a shifted or duplicated position compare equal by luck.

    Parameters
    ----------
    count : int
        How many records to build, numbered from one.

    Returns
    -------
    list[dict[str, str | Decimal]]
        One mapping per record, keyed by :data:`_CHECKSUM_FIELDS`.

    Raises
    ------
    None
        Building mappings cannot fail.
    """
    return [
        {_CHECKSUM_FIELDS[0]: f"{index:016d}", _CHECKSUM_FIELDS[1]: Decimal(f"{index}0.00")}
        for index in range(1, count + 1)
    ]


def _compared(
    source: list[dict[str, str | Decimal]],
    target: list[dict[str, str | Decimal]],
    **options: object,
) -> object:
    """Compare two record runs under the names the migrated dataset actually carries.

    Parameters
    ----------
    source : list[dict[str, str | Decimal]]
        The records read from the extract.
    target : list[dict[str, str | Decimal]]
        The records read back from the loaded table.
    **options : object
        Passed through to :func:`compare_record_digests`, for the ``layout`` and
        ``reported_differences`` keywords.

    Returns
    -------
    object
        The :class:`ChecksumComparison` the verifier produced.

    Raises
    ------
    ChecksumVerificationError
        Propagated when the comparison refuses its inputs.
    """
    return compare_record_digests(
        "DALYTRAN",
        "ledger.daily_transactions",
        source,
        target,
        _CHECKSUM_FIELDS,
        **options,  # type: ignore[arg-type]
    )


def test_the_comparison_verifies_a_faithful_copy() -> None:
    """Verify a load that read back exactly what was sent, and report the positions compared.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If a faithful copy is reported as differing, or the position count is not the record
        count.
    """
    comparison = _compared(_feed(3), _feed(3))
    assert comparison.verified
    assert comparison.matched
    assert comparison.differences == ()
    assert comparison.differing_records == 0
    assert comparison.absent_records == 0
    # WHY : the POSITION COUNT is asserted as well as the verdict. A comparison that verified
    #   without walking anything -- a verifier handed two empty iterables, or one that stopped at
    #   the first record -- would also report `verified` with no differences, and is exactly the
    #   defect that makes a verification pass worthless while it reads as green.
    assert comparison.compared == 3
    assert (
        comparison.render()
        .rstrip()
        .endswith(
            "PASSED: 3 position(s) compared, 0 record(s) differing,"
            " 0 position(s) occupied on one side only"
        )
    )


def test_the_comparison_names_the_position_and_field_of_a_corrupted_value() -> None:
    """Name the position and field of a single corrupted value, and no other position.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the corruption is missed, or is attributed to the wrong position, field or kind.
    """
    target = _feed(3)
    # WHY : the perturbation is one cent in the MIDDLE record, not the first or the last. A
    #   corruption at either end is found by a verifier that compares only the boundary records
    #   -- a real failure mode for a hand-rolled digest walk -- so putting it at position two is
    #   what makes this test able to fail.
    target[1][_CHECKSUM_FIELDS[1]] = Decimal("20.01")
    comparison = _compared(_feed(3), target)
    assert not comparison.verified
    assert not comparison.matched
    assert comparison.compared == 3
    assert comparison.differing_records == 1
    assert comparison.absent_records == 0
    assert len(comparison.differences) == 1
    difference = comparison.differences[0]
    assert difference.kind is DifferenceKind.FIELD
    assert difference.ordinal == 2
    assert difference.field_name == _CHECKSUM_FIELDS[1]
    assert difference.describe() == f"DIFFER  record=2  {_CHECKSUM_FIELDS[1]}"


def test_the_comparison_reports_a_record_the_load_dropped() -> None:
    """Report the position of a source record that was never read back.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If a short load verifies, or the absent position is reported as a field difference.
    """
    comparison = _compared(_feed(3), _feed(2))
    assert not comparison.verified
    assert comparison.absent_records == 1
    # WHY : a dropped record is reported as its OWN kind rather than as a field difference,
    #   because the two call for different operator action -- a short load is re-run, a corrupted
    #   field is investigated. Collapsing them into one kind would leave the report unable to say
    #   which happened, and the row-count pass alone cannot say WHERE the load stopped.
    assert [difference.kind for difference in comparison.differences] == [
        DifferenceKind.ABSENT_FROM_TARGET
    ]
    assert comparison.differences[0].ordinal == 3
    # WHY : no field is attributed to an absent position, because there is no record there to
    #   hold one. A difference that named a field here would invite an operator to go and inspect
    #   a column of a row that was never written.
    assert comparison.differences[0].field is None
    assert comparison.differences[0].field_name == ""
    # WHY : the position count follows the LONGER side, so a load that stopped early still
    #   reports how many positions it should have covered rather than how many it did.
    assert comparison.compared == 3
    assert "1 position(s) occupied on one side only" in comparison.render()


def test_the_comparison_reports_a_record_the_load_invented() -> None:
    """Report the position of a target row with no source record behind it.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If an over-long load verifies, or the extra position is reported as absent from the
        target.
    """
    # WHY : this is the direction a row-count pass catches and a digest of the whole extract does
    #   not: a load re-run over a table that was not truncated first leaves the extra rows PAST
    #   the source's last position, so every position the source occupies still agrees. Only a
    #   positional walk that runs to the longer side sees them.
    comparison = _compared(_feed(2), _feed(3))
    assert not comparison.verified
    assert comparison.absent_records == 1
    assert [difference.kind for difference in comparison.differences] == [
        DifferenceKind.ABSENT_FROM_SOURCE
    ]
    assert comparison.differences[0].ordinal == 3
    assert comparison.compared == 3


def test_the_comparison_reports_a_record_the_load_wrote_twice() -> None:
    """Report every field of a position the load filled with a duplicate of another record.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If a duplicated write verifies, or fewer than all its fields are named.
    """
    target = _feed(3)
    # WHY : a duplicate is the one defect the ROW-COUNT pass provably cannot see -- writing
    #   record one twice and dropping record two leaves the count exactly right -- so it is the
    #   defect that justifies this pass existing alongside the count. Constructing it as
    #   "position two now holds record one" reproduces precisely that: three rows in, three rows
    #   out, and one record lost.
    target[1] = dict(target[0])
    comparison = _compared(_feed(3), target)
    assert not comparison.verified
    assert comparison.compared == 3
    assert comparison.absent_records == 0
    assert comparison.differing_records == 1
    # WHY : BOTH fields are asserted, not just one. A report naming only the first differing
    #   field of a position would let an operator conclude a single column was corrupted, when
    #   what actually happened is that the whole record at that position is the wrong record.
    assert [difference.field_name for difference in comparison.differences] == list(
        _CHECKSUM_FIELDS
    )
    assert {difference.ordinal for difference in comparison.differences} == {2}


def test_the_comparison_counts_every_difference_and_reports_only_the_first_few() -> None:
    """Count all differences while rendering only the requested number, and say how many it held.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the withheld count is wrong, or the report renders more differences than requested.
    """
    source = [
        {_CHECKSUM_FIELDS[0]: f"{index:016d}", _CHECKSUM_FIELDS[1]: Decimal(f"{index}.00")}
        for index in range(1, 31)
    ]
    target = [
        {_CHECKSUM_FIELDS[0]: f"{index:016d}", _CHECKSUM_FIELDS[1]: Decimal(f"{index}.01")}
        for index in range(1, 31)
    ]
    comparison = _compared(source, target, reported_differences=5)
    # WHY : the COUNT and the REPORT are asserted separately because they are different numbers
    #   on purpose. A verifier that truncated the count along with the report would tell an
    #   operator that five records differ when thirty do, which understates a systematic decode
    #   fault as a handful of bad rows -- the opposite of what a cap is for.
    assert comparison.differing_records == 30
    assert len(comparison.differences) == 5
    assert comparison.withheld_differences == 25
    assert (
        "... 25 further difference(s) counted and withheld, the report carrying the first 5"
        in comparison.render()
    )
    assert "30 record(s) differing" in comparison.render()


def test_the_default_difference_limit_bounds_an_unbounded_failure() -> None:
    """Bound the rendered differences by the module default when the caller names no limit.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the default limit is not applied, or the count is bounded along with the report.
    """
    size = REPORTED_DIFFERENCE_LIMIT + 5
    source = [
        {_CHECKSUM_FIELDS[0]: f"{index:016d}", _CHECKSUM_FIELDS[1]: Decimal(f"{index}.00")}
        for index in range(1, size + 1)
    ]
    target = [
        {_CHECKSUM_FIELDS[0]: f"{index:016d}", _CHECKSUM_FIELDS[1]: Decimal(f"{index}.01")}
        for index in range(1, size + 1)
    ]
    comparison = _compared(source, target)
    # WHY : the default is exercised because the failure it bounds is the realistic one -- a
    #   wrong encoding or a mis-set sign convention makes EVERY record differ, and a report with
    #   one line per record of a fifty-thousand-record extract is not a report an operator reads.
    #   A test that always passed a small explicit limit would leave the default unexercised.
    assert len(comparison.differences) == REPORTED_DIFFERENCE_LIMIT
    assert comparison.withheld_differences == 5
    assert comparison.differing_records == size


def test_the_comparison_report_names_both_digests_and_no_field_value() -> None:
    """Render the record name, both digests and every verdict, and no digested field value.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the report omits a digest or the table, or carries a value from either record.
    """
    target = _feed(3)
    target[1][_CHECKSUM_FIELDS[1]] = Decimal("20.01")
    comparison = _compared(_feed(3), target)
    rendered = comparison.render()
    lines = rendered.splitlines()
    assert lines[0] == "record checksum verification: DALYTRAN -> ledger.daily_transactions"
    assert lines[1] == f"  source {comparison.source.describe()}"
    assert lines[2] == f"  target {comparison.target.describe()}"
    assert f"{DIGEST_NAME}=" in lines[1]
    assert f"{DIGEST_NAME}=" in lines[2]
    assert lines[1] != lines[2]
    # WHY : every value from BOTH records is asserted absent, including the account-identifying
    #   one. This report is written to a retained log read by whoever holds log access, while the
    #   records it compares are read only by whoever is entitled to them -- so a report that
    #   echoed the differing value alongside the digest would move a card-level amount and its
    #   record key into a wider audience while looking like a privacy-safe summary. The position
    #   and the field NAME are what an operator needs, and they disclose nothing.
    assert "20.01" not in rendered
    assert "10.00" not in rendered
    assert f"{1:016d}" not in rendered
    assert f"{2:016d}" not in rendered


def _proc_timestamp_field() -> object:
    """Return the DALYTRAN processing-timestamp field, taken from the layout that declares it.

    Purpose
    -------
    Give the timestamp tests the real field spec, so that the width they assert against is the
    width the copybook declares rather than a number spelled in this file.

    Returns
    -------
    object
        The :class:`FieldSpec` for ``DALYTRAN-PROC-TS``.

    Raises
    ------
    StopIteration
        If the layout declares no normalized timestamp, which would itself be the regression.
    """
    return next(field for field in DALYTRAN_LAYOUT.fields if field.normalize_ts)


def test_the_timestamp_guard_admits_a_written_stamp_unchanged() -> None:
    """Admit a conforming processing timestamp and return it byte-for-byte unchanged.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If a conforming stamp is refused, or is rewritten on the way through.
    """
    field = _proc_timestamp_field()
    written = "2022-06-10 19:27:53.000000"
    # WHY : the value is asserted IDENTICAL rather than merely admitted. This guard runs on the
    #   path to a digest, so a guard that canonicalised what it admitted -- trimming, re-rendering
    #   or zero-filling the microseconds -- would make the source and target digests agree on
    #   records whose stored bytes differ, which is the one thing the digest exists to detect.
    assert validated_timestamp(field, written) == written


def test_the_timestamp_guard_admits_an_unwritten_stamp() -> None:
    """Admit an all-blank stamp, which is what an unposted record carries.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the blank form is refused as malformed.
    """
    field = _proc_timestamp_field()
    unwritten = " " * field.length
    # WHY : blank is not a malformed timestamp, it is the ABSENCE of one -- CBTRN02C writes this
    #   field when it posts, so every record in an unposted daily feed carries spaces here. A
    #   guard that refused blanks would refuse every extract of DALYTRAN.PS taken before posting,
    #   which is exactly the extract the migration loads.
    assert validated_timestamp(field, unwritten) == unwritten


def test_the_timestamp_guard_refuses_a_stamp_of_the_wrong_width() -> None:
    """Refuse a stamp shorter than its declared width, naming both widths.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If a short value is admitted, or the refusal names neither width.
    """
    field = _proc_timestamp_field()
    with pytest.raises(TimestampContractError) as refusal:
        validated_timestamp(field, "2022-10-10")
    message = str(refusal.value)
    # WHY : a value of the wrong WIDTH in a fixed-width record means the record was sliced at the
    #   wrong offsets, so every field after this one is wrong too. Refusing here rather than
    #   digesting a plausible-looking short string is what turns a silent whole-record
    #   misalignment into one legible failure that names where the slicing went wrong.
    assert "holds 10 characters" in message
    assert f"declared width is {field.length}" in message
    assert field.name in message


def test_the_timestamp_guard_refuses_an_impossible_calendar_date() -> None:
    """Refuse a stamp of the right width whose date could not exist.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If a thirteenth month is admitted because the width happens to be right.
    """
    field = _proc_timestamp_field()
    # WHY : this value is the right LENGTH and the right SHAPE, and only the month is impossible.
    #   A guard that checked width and punctuation alone would admit it, and the digest would then
    #   certify a record whose timestamp no database could store -- the load would fail later, at
    #   INSERT time, with nothing pointing back at which record was malformed.
    with pytest.raises(TimestampContractError) as refusal:
        validated_timestamp(field, "2022-13-10 19:27:53.000000")
    message = str(refusal.value)
    assert f"holds {field.length} characters" in message
    assert "neither an admitted timestamp" in message


@pytest.mark.parametrize(
    ("layout", "expected"),
    [
        (DALYTRAN_LAYOUT, ("DALYTRAN-PROC-TS",)),
        (INTTRAN_LAYOUT, ("TRAN-ORIG-TS", "TRAN-PROC-TS")),
    ],
    ids=("dalytran", "inttran"),
)
def test_the_normalized_stamp_names_come_from_the_layout(
    layout: object, expected: tuple[str, ...]
) -> None:
    """Name every field a layout marks for timestamp normalization, and no other field.

    Parameters
    ----------
    layout : object
        The record layout to interrogate.
    expected : tuple[str, ...]
        The field names that layout marks, spelled literally.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the set of marked names drifts from what the layout declares.
    """
    # WHY : the INTTRAN pair is the case that matters. The interest job stamps BOTH an originating
    #   and a processing timestamp on every transaction it generates, so a comparison that
    #   excluded only one of them would report every generated row as differing -- a whole-pass
    #   false failure. Asserting the names literally is what keeps a layout edit that drops one
    #   from passing silently.
    assert sorted(normalized_timestamp_field_names(layout)) == sorted(expected)


def test_the_deterministic_field_names_drop_the_stamp_and_keep_the_order() -> None:
    """Drop exactly the normalized timestamps from a field list and preserve the rest in order.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If a field other than the stamp is dropped, or the surviving order changes.
    """
    declared = tuple(field.name for field in DALYTRAN_LAYOUT.fields)
    kept = deterministic_field_names(DALYTRAN_LAYOUT, declared)
    assert set(declared) - set(kept) == {"DALYTRAN-PROC-TS"}
    # WHY : ORDER is asserted, not just membership. The digest frames its fields in the order it
    #   is given them, so a filter that returned the right set in a different order would produce
    #   a different digest for identical records -- and the two sides of a comparison would only
    #   agree while both happened to be filtered by the same run.
    assert kept == tuple(name for name in declared if name != "DALYTRAN-PROC-TS")


def test_the_deterministic_field_names_keep_a_name_the_layout_does_not_declare() -> None:
    """Keep a column the layout knows nothing about rather than silently dropping it.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If an undeclared name is filtered out.
    """
    # WHY : the target table carries columns the copybook has no field for -- surrogate keys and
    #   the columns the anti-corruption layer derives. The filter's job is to remove the
    #   non-deterministic stamps, so anything it cannot recognise must survive: dropping unknown
    #   names instead would quietly shrink the compared field set until a comparison over zero
    #   fields passed. That is the failure mode this direction of the test exists to deny.
    assert deterministic_field_names(DALYTRAN_LAYOUT, ("DERIVED-COL",)) == ("DERIVED-COL",)


def test_the_comparison_refuses_a_record_whose_stamp_breaches_its_layout() -> None:
    """Refuse the whole comparison when a layout is supplied and a stamp does not conform.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If a malformed stamp is digested rather than refused.
    """
    names = tuple(field.name for field in DALYTRAN_LAYOUT.fields)
    # WHY : Assumptions: the rules are read from `canonicalisation_of` rather than inferred from a
    #   field's kind here, because that function IS what the comparison consults -- a second reading
    #   of the layout could disagree with it and the fixture would then violate a rule this case
    #   believed it satisfied.
    _DALYTRAN_RULES = canonicalisation_of(DALYTRAN_LAYOUT)

    def _conforming_value(field: object) -> object:
        """Supply a value each field's own declaration admits.

        Parameters
        ----------
        field : object
            One descriptor of the daily-transaction layout.

        Returns
        -------
        object
            The unwritten stamp for the marked timestamp, digits for an integral rule, an exact
            decimal for a monetary rule and a single character otherwise.

        Raises
        ------
        None
            Reading a declaration cannot fail.
        """
        # WHY : ⚠️ Refactoring Rationale: every field carried the literal "x" here, and the fixture
        #   now respects each field's DECLARATION. The comparison derives its per-field
        #   canonicalisation rules from the layout it is given, so a numeric field carrying "x" is
        #   refused for not being digits -- and it is refused BEFORE the stamp is examined, so this
        #   case failed on the wrong ground and stopped asserting the stamp contract at all. A
        #   fixture that violates two declarations cannot isolate one of them.
        if field.name == "DALYTRAN-PROC-TS":
            return " " * 26
        rule = _DALYTRAN_RULES.get(field.name)
        if rule is Canon.INTEGER:
            width = field.length
            return "0" * (width - 1) + "1" if width > 1 else "1"
        if rule is Canon.EXACT:
            return Decimal("1.00")
        return "x"

    conforming = [{field.name: _conforming_value(field) for field in DALYTRAN_LAYOUT.fields}]
    malformed = [{**conforming[0], "DALYTRAN-PROC-TS": "2022-13-10 19:27:53.000000"}]
    # WHY : the clean pair is compared FIRST, so this test proves the layout keyword does not
    #   simply refuse everything. Without it, a guard that raised unconditionally would satisfy
    #   the refusal assertion below and no test would notice that the pass had stopped working.
    assert compare_record_digests(
        "DALYTRAN",
        "ledger.daily_transactions",
        conforming,
        conforming,
        names,
        layout=DALYTRAN_LAYOUT,
    ).verified
    with pytest.raises(TimestampContractError):
        compare_record_digests(
            "DALYTRAN",
            "ledger.daily_transactions",
            conforming,
            malformed,
            names,
            layout=DALYTRAN_LAYOUT,
        )


def _money_total_row(
    key: tuple[str, str],
    *,
    total: object = Decimal("100.00"),
    negative_rows: object = 1,
    row_count: object = 3,
    cobol_field: str | None = None,
) -> tuple[object, ...]:
    """Build one conforming row of the money-total report, for the named declared column.

    Purpose
    -------
    Assemble a row in exactly the shape ``money_totals.sql`` projects, deriving the picture and
    the SQL type from the layout the loader declares rather than spelling them per test, so that
    a test perturbing the TOTAL cannot accidentally also perturb the picture.

    Parameters
    ----------
    key : tuple[str, str]
        The qualified table and money column the row reports.
    total : object
        The summed value. Typed ``object`` so a test can supply a ``float`` and prove it refused.
    negative_rows : object
        The count of rows holding a negative value. Typed ``object`` for the same reason.
    row_count : object
        The count of rows totalled.
    cobol_field : str | None
        Overrides the originating COBOL field name, for the field-drift test. ``None`` uses the
        name the loader declares.

    Returns
    -------
    tuple[object, ...]
        One row, its values in :data:`MONEY_TOTAL_COLUMNS` order.

    Raises
    ------
    KeyError
        If ``key`` names no declared money column.
    """
    field = declared_money_columns()[key].field
    return (
        key[0],
        key[1],
        cobol_field if cobol_field is not None else field.name,
        f"S9({field.int_digits:02d})V{'9' * field.dec_digits}",
        f"NUMERIC({field.int_digits + field.dec_digits},{field.dec_digits})",
        row_count,
        total,
        negative_rows,
    )


def _conforming_money_report() -> list[tuple[object, ...]]:
    """Build a report covering every declared money column, each agreeing with its source.

    Returns
    -------
    list[tuple[object, ...]]
        One row per declared money column.

    Raises
    ------
    None
        Building rows from the declared inventory cannot fail.
    """
    # WHY : Refactoring Rationale: the baseline-free column is reported EMPTY -- zero rows and a
    #   zero total -- where this fixture once gave it the same hundred as every other column. The
    #   pass no longer waves such a line through as merely "not comparable": a money column whose
    #   layout ships no seed extract must leave its target empty after the migration, so the pass
    #   states which expectation was applied and FAILS a line holding unaccounted money. A hundred
    #   pounds in a table nothing seeds is that failure, so a fixture carrying it made every caller
    #   of this helper fail on the one line that was never the property under test.
    return [
        _money_total_row(key, total=Decimal("0.00"), negative_rows=0, row_count=0)
        if key == _UNSEEDED_MONEY_COLUMN
        else _money_total_row(key)
        for key in declared_money_columns()
    ]


def _matching_source_totals() -> dict[tuple[str, str], SourceMoneyTotal]:
    """Build source measurements that agree with :func:`_conforming_money_report` exactly.

    Purpose
    -------
    Cover every declared money column whose layout ships a seed extract, and no other, so that a
    baseline run verifies and every perturbation below has exactly one cause.

    Returns
    -------
    dict[tuple[str, str], SourceMoneyTotal]
        One measurement per comparable column, keyed as the report is keyed.

    Raises
    ------
    None
        Building measurements from the declared inventory cannot fail.
    """
    return {
        key: SourceMoneyTotal(column.layout_name, column.field.name, Decimal("100.00"), 1, 3)
        for key, column in declared_money_columns().items()
        if key != _UNSEEDED_MONEY_COLUMN
    }


def test_the_declared_money_inventory_is_every_money_column_and_no_other() -> None:
    """Declare nine money columns over five tables, matching the tuple this suite pins.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the derived inventory drifts from the nine columns the totals query covers.
    """
    declared = declared_money_columns()
    assert len(declared) == MONEY_TOTAL_COLUMN_COUNT
    assert len({table for table, _ in declared}) == MONEY_TOTAL_TABLE_COUNT
    # WHY : the inventory is DERIVED from the copybook layouts at import time, while
    #   _TOTALLED_COLUMNS is spelled literally from the SQL. Asserting the two against each other
    #   is the only check that can catch them drifting apart: a money field added to a copybook
    #   without a matching line in the query would otherwise leave a column of real money
    #   unverified, while "money parity passed" still printed.
    assert sorted(declared) == sorted(_TOTALLED_COLUMNS)
    assert len(MONEY_TOTAL_COLUMNS) == len(_conforming_money_report()[0])


def test_the_money_report_verifies_a_faithful_load() -> None:
    """Verify a load whose every column agrees, and report the one column it cannot compare.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If a faithful load fails, or the unseeded column is not reported as not comparable.
    """
    report = verify_money_total_rows(_conforming_money_report(), _matching_source_totals())
    assert report.verified
    assert report.mismatches == ()
    assert report.sign_discrepancies == ()
    assert len(report.lines) == MONEY_TOTAL_COLUMN_COUNT
    # WHY : the not-comparable line is asserted PRESENT rather than tolerated. ledger.transactions
    #   holds real posted money, so a report that silently omitted it would shrink from nine
    #   columns to eight and still print PASSED -- and the column carrying the largest balances in
    #   the system would be the one nobody was checking.
    assert [line.row.qualified_column for line in report.not_comparable] == [
        f"{_UNSEEDED_MONEY_COLUMN[0]}.{_UNSEEDED_MONEY_COLUMN[1]}"
    ]
    assert report.not_comparable[0].verdict is MoneyParityVerdict.NO_SOURCE
    # WHY : Refactoring Rationale: the summary word is `failing` where it was `mismatched`. The two
    #   stopped being the same set when the expected-empty rule arrived: an unmeasurable column can
    #   now fail with no source total to have disagreed with, and labelling that count "mismatched"
    #   would name a comparison that never happened. The spelling matches the row-count report's, so
    #   both reports an operator reads in one run speak one vocabulary.
    assert report.render().splitlines()[-1] == (
        "money total verification PASSED: 8 matched, 0 failing, 1 not comparable,"
        " 0 sign discrepancies"
    )


def test_the_money_report_detects_a_one_cent_difference_in_one_column() -> None:
    """Fail a load whose one column differs by a single cent, and name only that column.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If one cent passes, or more than the perturbed column is reported.
    """
    # WHY : Refactoring Rationale: the baseline-free column is reported EMPTY here too, for the
    #   reason recorded on `_conforming_money_report`: a money column whose layout ships no seed
    #   extract must leave its target empty, so a hundred pounds in it is a SECOND failing line and
    #   this case asserts there is exactly one. Deriving the row list here rather than calling the
    #   shared helper is deliberate -- the probe column's perturbation is the subject -- so the same
    #   correction has to be made in both places.
    rows = [
        _money_total_row(_MONEY_PROBE_COLUMN, total=Decimal("100.01"))
        if key == _MONEY_PROBE_COLUMN
        else _money_total_row(key, total=Decimal("0.00"), negative_rows=0, row_count=0)
        if key == _UNSEEDED_MONEY_COLUMN
        else _money_total_row(key)
        for key in declared_money_columns()
    ]
    report = verify_money_total_rows(rows, _matching_source_totals())
    assert not report.verified
    assert len(report.mismatches) == 1
    line = report.mismatches[0]
    assert line.row.qualified_column == f"{_MONEY_PROBE_COLUMN[0]}.{_MONEY_PROBE_COLUMN[1]}"
    # WHY : one cent is the tolerance, and it is zero, for the reason the single-column test above
    #   already records: a sign misread moves a total by twice a value and a decimal misread by a
    #   hundredfold, but ONE mis-decoded digit in one record of fifty thousand moves it by cents.
    #   Any tolerance at all is a tolerance for that record.
    assert line.total_difference == Decimal("0.01")
    assert not line.total_matched
    assert line.negative_rows_matched
    assert report.render().splitlines()[-1].startswith("money total verification FAILED:")
    # WHY : Refactoring Rationale: `failing` where it was `mismatched`, for the reason recorded on
    #   the faithful-load case. The per-line MISMATCH verdict asserted above is unchanged, so this
    #   case still distinguishes a genuine disagreement from an unmeasurable line.
    assert "1 failing" in report.render()


def test_the_money_report_detects_a_sign_count_the_totals_agree_on() -> None:
    """Fail a load whose totals agree exactly while the count of negative rows does not.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If a sign-count difference passes because the totals matched.
    """
    rows = [
        _money_total_row(_MONEY_PROBE_COLUMN, negative_rows=2)
        if key == _MONEY_PROBE_COLUMN
        else _money_total_row(key)
        for key in declared_money_columns()
    ]
    report = verify_money_total_rows(rows, _matching_source_totals())
    # WHY : this is the ONE defect neither of the other two passes can see and neither can the
    #   money total alone. A sign overpunch read wrongly on a pair of records that offset each
    #   other leaves the row count right, the record digests right if the bytes were digested
    #   before decoding, and the TOTAL right to the cent -- and the money has still landed on the
    #   wrong side of the ledger. Comparing the negative-row count alongside the total is the only
    #   assertion in the suite that catches it, which is why the report counts sign discrepancies
    #   as their own figure rather than folding them into the mismatch count.
    assert not report.verified
    assert len(report.sign_discrepancies) == 1
    line = report.sign_discrepancies[0]
    assert line.total_matched
    assert line.total_difference == Decimal("0.00")
    assert not line.negative_rows_matched
    assert line.negative_rows_difference == 1
    assert line.verdict is MoneyParityVerdict.MISMATCH
    assert "1 sign discrepancies" in report.render().splitlines()[-1]


def test_the_money_report_refuses_a_report_that_omits_a_column() -> None:
    """Refuse a report short of one declared column, naming the column it dropped.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If a short report is verified, or the refusal does not name the omission.
    """
    rows = _conforming_money_report()
    dropped = rows.pop()
    # WHY : refusing rather than reporting is the right response, and the message says why: a
    #   report missing a column would otherwise print PASSED over the columns it did carry, and
    #   "money parity passed" would mean "the money I chose to total, totalled". A verification
    #   pass whose coverage is set by its own output cannot be relied on.
    with pytest.raises(MoneyResultSetContractError) as refusal:
        verify_money_total_rows(rows, _matching_source_totals())
    message = str(refusal.value)
    assert f"'{dropped[1]}'" in message
    assert "omits" in message


def test_the_money_report_refuses_a_row_for_an_undeclared_column() -> None:
    """Refuse a report carrying a column the loader declares no money mapping for.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If an unrecognised column is compared, or the refusal does not name it.
    """
    rows = _conforming_money_report()
    rows.append(
        ("x.y", "z", "F-X", "S9(04)V99", "NUMERIC(6,2)", 1, Decimal("0.00"), 0),
    )
    # WHY : an unrecognised column means the query and the loader disagree about what money is,
    #   and the pass cannot know which is right. Comparing it against nothing would report it as
    #   not comparable -- indistinguishable in the output from ledger.transactions, which is not
    #   comparable for a legitimate and documented reason.
    with pytest.raises(MoneyResultSetContractError) as refusal:
        verify_money_total_rows(rows, _matching_source_totals())
    assert "('x.y', 'z')" in str(refusal.value)
    assert "declares no money column" in str(refusal.value)


def test_the_money_report_refuses_a_column_reported_twice() -> None:
    """Refuse a report holding two rows for one column, even when both rows agree.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If a duplicated column is accepted because its two rows are identical.
    """
    rows = _conforming_money_report()
    # WHY : the duplicate appended is IDENTICAL to the row it duplicates, so nothing about the
    #   comparison itself would fail. The refusal has to come from the row COUNT against the
    #   distinct-pair count, because the query joins two closed sets on that pair: a duplicate
    #   means the query was not the query, and every other row of that result set is then suspect
    #   too. Duplicating a differing row instead would let a mismatch assertion pass this test
    #   while the contract check was broken.
    rows.append(rows[0])
    with pytest.raises(MoneyResultSetContractError) as refusal:
        verify_money_total_rows(rows, _matching_source_totals())
    message = str(refusal.value)
    assert f"{MONEY_TOTAL_COLUMN_COUNT + 1} rows" in message
    assert f"{MONEY_TOTAL_COLUMN_COUNT} distinct" in message


def test_the_money_report_refuses_a_row_of_the_wrong_width() -> None:
    """Refuse a row projecting fewer values than the query's documented column list.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If a short row is unpacked anyway, or the refusal does not name both widths.
    """
    rows = _conforming_money_report()
    rows[0] = rows[0][:-1]
    # WHY : a row of the wrong width means the query was edited without the parser, so every
    #   value after the missing one is being read into the wrong field -- a total read as a row
    #   count would compare as a wildly wrong number, or worse, as a plausible one. Naming both
    #   widths and the projection puts the operator straight at the edit.
    with pytest.raises(MoneyResultSetContractError) as refusal:
        verify_money_total_rows(rows, _matching_source_totals())
    message = str(refusal.value)
    assert f"carries {len(MONEY_TOTAL_COLUMNS) - 1} values" in message
    assert f"the query projects {len(MONEY_TOTAL_COLUMNS)}" in message


def test_the_money_report_refuses_a_label_column_that_is_not_text() -> None:
    """Refuse a row whose label columns are not all text, naming the types it found.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If a non-text label is coerced rather than refused.
    """
    rows = _conforming_money_report()
    # WHY : the five label columns are what every comparison is KEYED on. Coercing a non-text
    #   label with str() would build a key that matches nothing, and the row would then be
    #   reported as a column the loader does not declare -- a refusal naming the wrong cause and
    #   sending the operator to the loader instead of to the query.
    rows[0] = (1, *rows[0][1:])
    with pytest.raises(MoneyResultSetContractError) as refusal:
        verify_money_total_rows(rows, _matching_source_totals())
    assert "'int'" in str(refusal.value)
    assert "five label columns" in str(refusal.value)


def test_the_money_report_refuses_a_total_that_is_not_exact() -> None:
    """Refuse a total arriving as a binary float rather than an exact decimal.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If a float total is compared, or the refusal does not name the type.
    """
    rows = [
        _money_total_row(_MONEY_PROBE_COLUMN, total=100.0)
        if key == _MONEY_PROBE_COLUMN
        else _money_total_row(key)
        for key in declared_money_columns()
    ]
    # WHY : this refusal is the whole money path's guarantee, checked at the last place money
    #   enters the process. The query projects NUMERIC end to end, so a float here can only mean a
    #   driver or an adapter converted it -- and a converted total compares equal to the source on
    #   the values a test would pick and unequal on a cent that no test picked. Refusing the TYPE
    #   is the only check that does not depend on which values happen to survive the conversion.
    with pytest.raises(MoneyResultSetContractError) as refusal:
        verify_money_total_rows(rows, _matching_source_totals())
    message = str(refusal.value)
    assert "is float and not an exact decimal" in message
    assert f"{_MONEY_PROBE_COLUMN[0]}.{_MONEY_PROBE_COLUMN[1]}" in message


def test_the_money_report_refuses_a_count_that_cannot_be_a_count() -> None:
    """Refuse a negative row or sign count, which no COUNT expression can produce.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If a negative count is accepted and compared.
    """
    rows = [
        _money_total_row(_MONEY_PROBE_COLUMN, negative_rows=-1)
        if key == _MONEY_PROBE_COLUMN
        else _money_total_row(key)
        for key in declared_money_columns()
    ]
    # WHY : a negative count is impossible from the query, so it means the values were read into
    #   the wrong fields -- most plausibly a total landing where a count belongs. Comparing it
    #   would report a sign-count mismatch and send the operator looking for an overpunch fault
    #   that does not exist.
    with pytest.raises(MoneyResultSetContractError) as refusal:
        verify_money_total_rows(rows, _matching_source_totals())
    assert "negative_rows=-1" in str(refusal.value)
    assert "cannot be negative" in str(refusal.value)


def test_the_money_report_refuses_a_row_naming_the_wrong_originating_field() -> None:
    """Refuse a row attributing a column to a COBOL field the loader does not map it from.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the field name is accepted as decoration, or the refusal names only one of the pair.
    """
    rows = [
        _money_total_row(_MONEY_PROBE_COLUMN, cobol_field="WRONG-FIELD")
        if key == _MONEY_PROBE_COLUMN
        else _money_total_row(key)
        for key in declared_money_columns()
    ]
    declared_field = declared_money_columns()[_MONEY_PROBE_COLUMN].field.name
    # WHY : the field name is the ONE column of the report that is a claim about the migration
    #   rather than a measurement of it, and the pass holds it to the loader's own mapping. If the
    #   two transcriptions of "which copybook field became this column" disagree, then one side of
    #   the comparison is totalling a different field from the other, and every total it reports
    #   is a comparison of two unrelated quantities that happens to be numeric.
    with pytest.raises(MoneyResultSetContractError) as refusal:
        verify_money_total_rows(rows, _matching_source_totals())
    message = str(refusal.value)
    assert "WRONG-FIELD" in message
    assert declared_field in message


def test_the_money_report_refuses_a_source_total_it_cannot_place() -> None:
    """Refuse a source measurement for a column the report does not carry.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If an unplaceable measurement is discarded silently.
    """
    totals = _matching_source_totals()
    totals[("q", "r")] = SourceMoneyTotal("DISGROUP", "F-X", Decimal("0.00"), 0, 0)
    # WHY : the caller measured something and the pass has nowhere to compare it. Discarding it
    #   would mean the operator read "money parity passed" over a set of columns SMALLER than the
    #   set they asked to have verified, with nothing in the output saying so.
    with pytest.raises(MoneyParityVerificationError) as refusal:
        verify_money_total_rows(_conforming_money_report(), totals)
    assert "('q', 'r')" in str(refusal.value)
    assert "does not carry" in str(refusal.value)


def test_the_money_report_refuses_to_leave_a_seeded_column_unmeasured() -> None:
    """Refuse a run that supplies no source total for a column whose layout ships an extract.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If a comparable column is quietly reported as not comparable instead.
    """
    totals = _matching_source_totals()
    del totals[_MONEY_PROBE_COLUMN]
    # WHY : this is the failure mode that makes "not comparable" dangerous. Without this refusal,
    #   forgetting an extract turns a comparable column into a NO_SOURCE line, and the report
    #   still says PASSED -- so the way to make money parity pass would be to stop measuring the
    #   column that disagrees. Refusal is what makes coverage the caller's declared intent rather
    #   than a by-product of what they happened to pass in.
    with pytest.raises(MoneyParityVerificationError) as refusal:
        verify_money_total_rows(_conforming_money_report(), totals)
    message = str(refusal.value)
    assert f"{_MONEY_PROBE_COLUMN[0]}.{_MONEY_PROBE_COLUMN[1]}" in message
    assert "ships a committed seed extract" in message
    assert declared_money_columns()[_MONEY_PROBE_COLUMN].layout_name in message


def test_the_money_report_renders_one_verdict_per_column_and_no_connection_detail() -> None:
    """Render a verdict line for every declared column and disclose no deployment detail.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If a column renders no line, or the report names a host or a trust anchor.
    """
    report = verify_money_total_rows(_conforming_money_report(), _matching_source_totals())
    rendered = report.render()
    lines = rendered.splitlines()
    assert lines[0] == f"money total verification: {MONEY_TOTAL_COLUMN_COUNT} line(s)"
    # WHY : one line per column is asserted by COUNT as well as by content, because the figure in
    #   the header is what an operator reads to decide whether the pass covered the schema. A
    #   header claiming nine over a body of eight is worse than no header.
    assert len(lines) == MONEY_TOTAL_COLUMN_COUNT + 2
    for key in declared_money_columns():
        assert f"{key[0]}.{key[1]}" in rendered
    # WHY : unlike the checksum report, this one PUBLISHES its money -- an aggregate total over a
    #   whole table is the measurement itself and cannot be withheld without withholding the
    #   verdict. What must still be absent is anything identifying the deployment the figures came
    #   from, so the host and the trust-anchor path are asserted absent for the same reason the
    #   session-guard refusal asserts them absent.
    assert str(_CONNECTION_PARAMS["host"]) not in rendered
    assert str(_CONNECTION_PARAMS["sslrootcert"]) not in rendered


def test_total_source_column_totals_the_signed_rows_and_counts_them() -> None:
    """Total a column's decimals exactly and count the rows holding a negative value.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the total is inexact, or a zero is counted as negative.
    """
    column = declared_money_columns()[_MONEY_PROBE_COLUMN]
    measured = total_source_column(
        [
            {column.field.name: Decimal("15.00")},
            {column.field.name: Decimal("-2.50")},
            {column.field.name: Decimal("0.00")},
        ],
        column,
    )
    assert measured.total == Decimal("12.50")
    assert measured.total.as_tuple().exponent == -MONEY_SCALE
    # WHY : the zero row is present precisely to prove it is not counted. A count written as
    #   `not positive` rather than `negative` would report two here, and the sign comparison would
    #   then fail on every table holding a zero balance -- which the committed category-balance
    #   extract is entirely made of.
    assert measured.negative_rows == 1
    assert measured.records == 3
    assert measured.layout_name == column.layout_name
    assert measured.field_name == column.field.name


def test_read_source_totals_measures_a_committed_extract_from_its_own_bytes() -> None:
    """Measure the shipped disclosure-group extract and report the totals its bytes hold.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the totals drift from the committed extract's own contents.
    """
    extract = SourceExtract("DISGROUP", _SEED_EXTRACT_ROOT / _DISCGRP_EXTRACT, "ascii")
    totals = read_source_totals([extract])
    # WHY : this is the only money test that runs over REAL committed bytes rather than
    #   hand-built decimals, and it is here because the hand-built ones cannot catch a decoding
    #   fault: they never pass through the zoned-decimal reader at all. The expected figures are
    #   spelled from a measured run rather than recomputed by the code under test, because a test
    #   that recomputes its expectation proves only that the code agrees with itself. app/data is
    #   reference-only and immutable, so the figures cannot go stale under us.
    assert set(totals) == {_MONEY_PROBE_COLUMN}
    measured = totals[_MONEY_PROBE_COLUMN]
    assert measured.total == _DISCGRP_EXTRACT_TOTAL
    assert measured.negative_rows == _DISCGRP_EXTRACT_NEGATIVE_ROWS
    assert measured.records == _DISCGRP_EXTRACT_RECORDS


def test_both_verification_passes_run_as_the_same_reporting_role() -> None:
    """Report the same read-only login role from both the row-count and money-parity passes.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the two passes name different roles.
    """
    # WHY : the two passes read the role from the same configuration, and this assertion is what
    #   keeps that true. If they diverged, one runbook command would work and the other would fail
    #   on a privilege error, and the operator's reasonable conclusion -- that the verification
    #   tooling is broken -- would be indistinguishable from a genuinely failed migration.
    assert money_reporting_role() == reporting_role() == _VERIFICATION_ROLE


def test_the_money_session_guard_refuses_a_writable_session(
    fake_aurora: FakeAuroraDatabase,
) -> None:
    """Refuse a money-parity session on any role but the reporting one, disclosing no detail.

    Parameters
    ----------
    fake_aurora : FakeAuroraDatabase
        Recording double answering the ``current_user`` probe with a writable role.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the guard admits a writable session, or its message names a deployment detail.
    """
    # WHY : `carddemo_batch` is arranged rather than an obviously wrong name, because it is the
    #   role that plausibly HAS a connection open at the moment someone runs verification -- it
    #   holds the cross-schema write grants the posting job needs. Certifying a load from the
    #   session that wrote it is the confusion this guard exists to prevent.
    fake_aurora.arrange_rows("current_user", [("carddemo_batch",)])
    connection = fake_aurora.connect(**_CONNECTION_PARAMS)
    with pytest.raises(MoneyParityVerificationError) as refusal:
        require_money_reporting_session(connection)
    message = str(refusal.value)
    assert money_reporting_role() in message
    assert "carddemo_batch" in message
    assert str(_CONNECTION_PARAMS["host"]) not in message
    assert str(_CONNECTION_PARAMS["sslrootcert"]) not in message


def test_the_money_session_guard_probes_the_live_role_not_the_login_role(
    fake_aurora: FakeAuroraDatabase,
) -> None:
    """Admit a reporting session, having asked the server which role it is actually running as.

    Parameters
    ----------
    fake_aurora : FakeAuroraDatabase
        Recording double answering the probe as the reporting role.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the guard refuses a conforming session, or probes with ``session_user``.
    """
    fake_aurora.arrange_rows("current_user", [(money_reporting_role(),)])
    connection = fake_aurora.connect(
        **{**_CONNECTION_PARAMS, "user": money_reporting_role()},
    )
    assert require_money_reporting_session(connection) == money_reporting_role()
    statement = fake_aurora.executed_sql()[-1]
    # WHY : `session_user` reports the role that authenticated and never changes; `current_user`
    #   reports the role in effect and follows SET ROLE. A guard built on the former would admit a
    #   session that logged in as the reporting role and then assumed a writable one, which is the
    #   whole failure mode being denied here.
    assert "current_user" in statement
    assert "session_user" not in statement


def test_no_money_query_runs_on_a_session_that_was_not_checked(
    fake_aurora: FakeAuroraDatabase,
) -> None:
    """Refuse to execute the money-total query before the live session has been confirmed.

    Parameters
    ----------
    fake_aurora : FakeAuroraDatabase
        Recording double answering the probe with a writable role.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the query reaches the server on an unchecked session.
    """
    fake_aurora.arrange_rows("current_user", [("carddemo_batch",)])
    connection = fake_aurora.connect(**_CONNECTION_PARAMS)
    # WHY : Refactoring Rationale: the text is driven through the module-private execution seam
    #   because the PUBLISHED fetch no longer accepts query text -- it reads the committed file
    #   itself and pins it by digest, which is the point of that change. The seam is what the guard
    #   actually protects, so this test drives it directly rather than through a path that would
    #   first have to locate the shipped file: locating it is a packaging concern and would make an
    #   unrelated failure look like a privilege failure.
    with pytest.raises(MoneyParityVerificationError):
        _money_total_rows(connection, "SELECT 1")
    # WHY : the ORDER is what is asserted, not just the refusal. A guard that ran the query first
    #   and checked the role afterwards would raise this same error while the query had already
    #   executed -- and on a writable session, the query is the one thing that must not run
    #   before the check, because the point of the check is that it might not be safe to.
    assert "SELECT 1" not in " ".join(fake_aurora.executed_sql())


def test_the_money_query_refuses_to_guess_where_it_lives() -> None:
    """Refuse to locate the query relative to the installed package, and say where to look.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If a path is guessed rather than refused when no root is supplied.
    """
    # WHY : the sql/ directory ships BESIDE the package rather than inside the wheel, so a
    #   package-relative guess resolves to a path that does not exist -- and it resolves
    #   differently again from a source checkout than from an installed distribution. Refusing
    #   with the path it tried is what turns "verification found no query" into one legible
    #   message instead of a FileNotFoundError an operator has to reverse-engineer.
    with pytest.raises(MoneyQueryError) as refusal:
        money_total_query_path()
    assert "money-total query is not at" in str(refusal.value)


def test_the_money_query_is_read_from_the_distribution_it_ships_in() -> None:
    """Locate and read the committed money-total query when given the distribution root.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the located path is not the committed file, or its text is not the file's text.
    """
    root = _DDL_ROOT.parent
    located = money_total_query_path(root)
    assert located == _SQL_ROOT / "money_totals.sql"
    assert located.exists()
    # WHY : the text is asserted IDENTICAL to the file rather than merely non-empty, because this
    #   is the one place the pass could quietly diverge from the SQL an operator reads and runs
    #   through psql. Every assertion this suite makes about the query's contract is an assertion
    #   about that file, and they are only assertions about the pass if the pass executes it
    #   verbatim.
    assert read_money_total_query(located) == located.read_text(encoding="utf-8")


def test_the_whole_money_pass_runs_the_committed_query_over_committed_extracts(
    fake_aurora: FakeAuroraDatabase,
) -> None:
    """Verify the migration end to end: committed query, committed extracts, real totals.

    Parameters
    ----------
    fake_aurora : FakeAuroraDatabase
        Recording double standing in for the loaded database.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the pass does not execute the committed query, or a faithful load fails to verify.
    """
    # WHY : every other money test here builds its source measurements by hand, which leaves the
    #   READER path -- fixed-width slicing, zoned-decimal sign overpunch, the money scale -- out
    #   of the pass entirely. This case runs the real four extracts through it, so the eight
    #   comparable columns are totalled from the same bytes an operator would migrate.
    extracts = [
        SourceExtract("ACCOUNT", _SEED_EXTRACT_ROOT / "acctdata.txt", "ascii"),
        SourceExtract("DALYTRAN", _SEED_EXTRACT_ROOT / "dailytran.txt", "ascii"),
        SourceExtract("TCATBAL", _SEED_EXTRACT_ROOT / "tcatbal.txt", "ascii"),
        SourceExtract("DISGROUP", _SEED_EXTRACT_ROOT / _DISCGRP_EXTRACT, "ascii"),
    ]
    source_totals = read_source_totals(extracts)
    assert len(source_totals) == MONEY_TOTAL_COLUMN_COUNT - 1
    # WHY : the arranged target rows are built FROM those measurements, so the database is made to
    #   answer with the money the extracts actually hold. That is what makes a PASSED verdict here
    #   meaningful: the pass had to slice, decode and total 451 real records and arrive at the same
    #   nine figures the arrangement was built from, through a code path that never sees them.
    rows = [
        _money_total_row(
            key,
            total=source_totals[key].total,
            negative_rows=source_totals[key].negative_rows,
            row_count=source_totals[key].records,
        )
        if key in source_totals
        else _money_total_row(key, total=Decimal("0.00"), negative_rows=0, row_count=0)
        for key in declared_money_columns()
    ]
    fake_aurora.arrange_rows("current_user", [(money_reporting_role(),)])
    fake_aurora.arrange_rows("v_verification_money_totals", rows)
    connection = fake_aurora.connect(
        **{**_CONNECTION_PARAMS, "user": money_reporting_role()},
    )
    report = verify_money_totals(connection, extracts, query_path=_SQL_ROOT / "money_totals.sql")
    assert report.verified
    assert len(report.lines) == MONEY_TOTAL_COLUMN_COUNT
    assert report.mismatches == ()
    assert report.sign_discrepancies == ()
    # WHY : the executed statement is asserted to BE the committed file, verbatim. A pass that
    #   wrapped, paginated or re-ordered the query would still produce a verdict from these
    #   arranged rows, and the file every runbook and every other test in this module reasons
    #   about would no longer be the file the pass runs.
    committed = (_SQL_ROOT / "money_totals.sql").read_text(encoding="utf-8")
    assert committed in fake_aurora.executed_sql()
    # WHY : the daily-transaction extract is the only committed one carrying negative money, so
    #   its sign count is asserted non-zero. Without it, every sign comparison in this end-to-end
    #   case would be zero against zero -- true, and no evidence at all that the sign path works.
    assert source_totals[("ledger.daily_transactions", "amount")].negative_rows > 0


# WHY : ⚠️ Refactoring Rationale: `test_the_comparison_fails_a_pass_that_walked_no_position` stood
#   here and is WITHDRAWN, because the rule it asserted has been withdrawn from the pass. It held
#   that a comparison walking no position must FAIL, by analogy with the row-count report, on the
#   ground that "the reader yielded nothing and the table is empty" is indistinguishable from an
#   unreadable extract or a load that never ran. The analogy does not survive measurement: a report
#   is built from a query, where an empty result set genuinely is ambiguous, while this pass is
#   handed both sides explicitly and every accidental route to an empty one RAISES before a verdict
#   -- an unreadable extract raises OSError, a truncated one RecordLengthError, an unknown layout
#   LayoutError, and a load that never ran leaves a non-empty source paired against an empty table,
#   which reports absent records rather than nothing. What the rule actually failed was the one
#   genuine state left: an empty extract loaded into an empty table, which is the normal state of
#   the transaction master because no TRANSACT dataset ships in either corpus. The superseding
#   property is asserted by `test_an_empty_load_of_an_empty_extract_verifies`, and the rule the
#   analogy came from still holds where it belongs -- the row-count report's own empty-result rule
#   is unchanged.


def test_the_comparison_names_a_field_a_record_does_not_carry() -> None:
    """Report a missing field as a keyed lookup failure naming the field and no value.

    Purpose
    -------
    A record short of a compared field is a projection defect, and the two ways of tolerating it --
    rendering the absence as a null, or skipping the field -- would each report a load with a
    missing column as faithful. The lookup is left to fail, and the failure names the FIELD, which
    is the one part of it that is not data.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If a record missing a compared field is silently tolerated.
    """
    complete = {"ID": "00000000001", "AMT": Decimal("1.00"), "WHEN": "2022-07-18 10:00:00.000000"}
    partial = {"ID": 1, "AMT": Decimal("1.00")}

    with pytest.raises(KeyError, match="WHEN"):
        _compare([complete], [partial])


def test_the_field_and_record_tags_are_distinct_so_the_two_framings_stay_independent() -> None:
    """Tag fields and records differently, so a field boundary cannot be read as a record boundary.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the field and record container tags are the same byte, or the delimiter is not one byte.
    """
    # WHY : Refactoring Rationale: this case was authored against two dedicated separator bytes and
    #   asserted they were distinct single bytes. That mechanism is withdrawn -- the framing is
    #   now a
    #   one-byte class or container TAG, a decimal length and one delimiter -- so the assertion is
    #   restated over what carries the same guarantee. If the field and record tags were the same
    #   byte, a record boundary and a field boundary would be indistinguishable in the digested
    #   stream and the collision cases above could both pass while a record split differently across
    #   the same total bytes still digested identically. Distinct tags are what make each framing
    #   carry its own meaning; the delimiter is a single byte so that no delimiter can straddle a
    #   boundary.
    assert _TAG_FIELD != _TAG_RECORD
    assert len(_TAG_FIELD) == len(_TAG_RECORD) == 1
    assert len(FRAME_DELIMITER) == 1


class _OneValueConnection:
    """Minimal connection stub that answers any statement with one arranged value.

    Purpose
    -------
    Drive the money-total refusals the shared :class:`FakeAuroraDatabase` cannot reach. That double
    refuses to record a ``float`` at all -- deliberately, because money is exact fixed point end to
    end -- so the one representation most likely to be substituted by a driver mapping cannot be
    arranged on it. This stub is the narrowest object that can present one, and it presents nothing
    else: no transaction, no recording, no credential check.

    Parameters
    ----------
    value : object
        The single value every statement's one row projects.

    Returns
    -------
    _OneValueConnection
        A stub whose ``cursor()`` answers with that value.

    Raises
    ------
    None
        Construction cannot fail.
    """

    def __init__(self, value: object) -> None:
        """Hold the value to answer with.

        Parameters
        ----------
        value : object
            The value every statement's one row projects.

        Returns
        -------
        None
            Stores the value.

        Raises
        ------
        None
            Assignment cannot fail.
        """
        self._value = value

    def cursor(self) -> _OneValueConnection:
        """Answer as its own cursor.

        Parameters
        ----------
        None
            Reads nothing.

        Returns
        -------
        _OneValueConnection
            This object, so that ``execute``/``fetchone`` are reached without a second class. It is
            deliberately NOT a context manager, which is the shape the production helpers already
            tolerate for the in-process doubles.

        Raises
        ------
        None
            Returning self cannot fail.
        """
        return self

    def execute(self, statement: str) -> None:
        """Accept a statement and record nothing.

        Parameters
        ----------
        statement : str
            The statement, ignored: this stub exists to answer, not to observe.

        Returns
        -------
        None
            Nothing is recorded.

        Raises
        ------
        None
            Accepting a string cannot fail.
        """

    def fetchone(self) -> tuple[object, ...]:
        """Answer with one row carrying the arranged value.

        Parameters
        ----------
        None
            Reads the held value.

        Returns
        -------
        tuple[object, ...]
            A single-column row.

        Raises
        ------
        None
            Building a tuple cannot fail.
        """
        return (self._value,)


def test_total_target_money_refuses_a_binary_float() -> None:
    """Refuse a target total that arrives as a binary floating-point value.

    Parameters
    ----------
    None
        The stub supplies the value.

    Returns
    -------
    None
        The assertion is the result.

    Raises
    ------
    AssertionError
        If a float is accepted, which would let a sum computed over an inexact representation be
        certified as exact money.
    """
    # WHY : `Decimal(0.10)` is 0.1000000000000000055511151231257827, so the old coercion-then-
    #   quantize path turned a float into a tidy `0.10` and certified it. This is the one branch the
    #   shared double cannot drive, because it refuses to carry a float at all, which is why the
    #   stub above exists.
    with pytest.raises(MoneyResultSetContractError) as refusal:
        total_target_money(
            _OneValueConnection(0.10), "reference", "disclosure_groups", "interest_rate"
        )
    assert "float" in str(refusal.value)


@pytest.mark.parametrize(
    "inexact",
    [
        pytest.param(0, id="int"),
        pytest.param("0.10", id="str"),
        pytest.param(True, id="bool"),
        pytest.param(Decimal("0.100"), id="wrong-scale"),
        pytest.param(Decimal("NaN"), id="not-a-number"),
    ],
)
def test_total_target_money_refuses_an_inexact_representation(
    fake_aurora: FakeAuroraDatabase,
    inexact: object,
) -> None:
    """Refuse every target total that is not an exact decimal at the money scale.

    Parameters
    ----------
    fake_aurora : FakeAuroraDatabase
        Recording double, arranged to answer with a representation that cannot hold cents.
    inexact : object
        The value the driver is arranged to return.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If any of the six representations is accepted, or if the refusal quotes the value.
    """
    # WHY : each parameter is a way an inexact total can arrive and be silently rounded into
    #   plausible cents. The float is the dangerous one: `Decimal(0.10)` is
    #   0.1000000000000000055511151231257827, and quantizing it yields a tidy `0.10` -- so a column
    #   whose sum had been computed over a binary floating-point representation would be certified
    #   as exact. The wrong-scale decimal is the subtle one: it is exact, and it is not the quantity
    #   this pass compares, so accepting it would compare thousandths against hundredths.
    fake_aurora.arrange_rows("SUM(", [(inexact,)])
    connection = fake_aurora.connect(**_CONNECTION_PARAMS)
    with pytest.raises(MoneyResultSetContractError) as refusal:
        total_target_money(connection, "reference", "disclosure_groups", "interest_rate")
    message = str(refusal.value)
    assert "reference.disclosure_groups.interest_rate" in message
    # WHY : the value itself must not reach the message. These records carry balances and the
    #   refusal is the text an operator pastes into a ticket, so the type and the column are the
    #   whole of what is disclosed.
    assert str(inexact) not in message or isinstance(inexact, int | bool)


# WHY : Assumptions: the gate section below drives `run_verification_gate` with DOUBLES rather than
#   with the three real passes. What is under test is the gate's own four rules -- the coverage
#   refusal, the fixed order, the short-circuit and the binary verdict -- and every one of them is a
#   property of the orchestration, not of any measurement. Driving it with real passes would need a
#   cluster and two extracts to assert a rule neither of them participates in, and a rule that only
#   holds when a cluster is reachable is a rule that goes unasserted.
class _StubPass:
    """A pass result of a fixed verdict, recording whether the gate actually ran it.

    Purpose
    -------
    Stand in for a :class:`~carddemo_migration.verify.gate.PassResult` so the gate's short-circuit
    is observable: a pass the gate must not reach is one whose ``ran`` stays false.

    Parameters
    ----------
    name : str
        What the stub renders as, so a gate rendering can be attributed to the right step.
    verdict : bool
        The verdict the stub reports.

    Returns
    -------
    None
        Constructing binds the two attributes.

    Raises
    ------
    None
        Construction cannot fail.
    """

    def __init__(self, name: str, verdict: bool) -> None:
        """Bind the stub's name and verdict, and mark it as not yet run.

        Parameters
        ----------
        name : str
            What the stub renders as.
        verdict : bool
            The verdict the stub reports.

        Returns
        -------
        None
            Binds attributes.

        Raises
        ------
        None
            Binding cannot fail.
        """
        self.name = name
        self._verdict = verdict
        self.ran = False

    def __call__(self) -> _StubPass:
        """Act as the zero-argument callable the gate invokes, recording the invocation.

        Returns
        -------
        _StubPass
            The stub itself, which is also its own result.

        Raises
        ------
        None
            Recording an invocation cannot fail.
        """
        self.ran = True
        return self

    @property
    def verified(self) -> bool:
        """Report the arranged verdict.

        Returns
        -------
        bool
            The verdict this stub was constructed with.

        Raises
        ------
        None
            Reading an attribute cannot fail.
        """
        return self._verdict

    def render(self) -> str:
        """Render a single deterministic line naming this stub.

        Returns
        -------
        str
            A one-line rendering, so a gate rendering can be attributed.

        Raises
        ------
        None
            Rendering cannot fail.
        """
        return f"  {self.name} stub"


def _gate_steps(*verdicts: bool) -> tuple[dict[str, _StubPass], tuple[_StubPass, ...]]:
    """Build a conforming step mapping over three stubs of the given verdicts.

    Parameters
    ----------
    *verdicts : bool
        One verdict per declared pass, in declared order.

    Returns
    -------
    tuple[dict[str, _StubPass], tuple[_StubPass, ...]]
        The mapping to hand the gate, and the three stubs so their ``ran`` marks can be read.

    Raises
    ------
    AssertionError
        If a verdict is not supplied for every declared pass, which would make the mapping itself
        the thing under test rather than the gate.
    """
    assert len(verdicts) == len(GATE_PASSES)
    stubs = tuple(
        _StubPass(name, verdict) for name, verdict in zip(GATE_PASSES, verdicts, strict=True)
    )
    return {stub.name: stub for stub in stubs}, stubs


def test_the_gate_runs_all_three_passes_in_the_declared_order() -> None:
    """Run every declared pass, in declared order, when each one verifies.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If a pass is skipped, or the outcomes do not follow the declared order.
    """
    steps, stubs = _gate_steps(True, True, True)

    verification = run_verification_gate(steps)

    assert verification.verified
    assert all(stub.ran for stub in stubs)
    # WHY : the ORDER of the outcomes is asserted, not merely that three exist. Order is
    #   load-bearing: a count mismatch explains a checksum mismatch, so a gate that ran the money
    #   pass first would report a money difference whose cause the count pass would have named in
    #   one line -- and every verdict here would still be identical.
    assert tuple(outcome.pass_name for outcome in verification.outcomes) == GATE_PASSES
    assert tuple(outcome.position for outcome in verification.outcomes) == (1, 2, 3)
    assert verification.failure is None
    assert verification.not_run == ()


def test_the_gate_stops_at_the_first_failing_pass() -> None:
    """Stop after the first pass that fails, leaving the later passes unrun.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If a pass after the failure runs, or the report does not name what was not run.
    """
    steps, stubs = _gate_steps(True, False, True)

    verification = run_verification_gate(steps)

    assert not verification.verified
    # WHY : the third stub's `ran` mark is the assertion that matters. A gate that ran all three
    #   and merely reported the first failure would satisfy an exit-status test while re-reading
    #   every extract to produce two further failures that share one cause.
    assert (stubs[0].ran, stubs[1].ran, stubs[2].ran) == (True, True, False)
    assert verification.failure is not None
    assert verification.failure.pass_name == GATE_PASSES[1]
    assert verification.not_run == (GATE_PASSES[2],)
    # WHY : the rendering is asserted to NAME the unrun pass, because a gate that stopped after two
    #   passes otherwise renders exactly like a gate configured with two. The reader could then not
    #   tell "this evidence is missing" from "this evidence was not required", which is the whole
    #   distinction the three-pass mandate rests on.
    rendered = verification.render()
    assert f"{GATE_PASSES[2]}: NOT RUN" in rendered
    assert "migration verification FAILED" in rendered


def test_a_gate_missing_a_pass_refuses_before_running_anything() -> None:
    """Refuse a partial gate, and refuse it before any pass has been invoked.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If a partial gate produces a verdict, or runs a pass on the way to refusing.
    """
    steps, stubs = _gate_steps(True, True, True)
    del steps[GATE_PASSES[2]]

    with pytest.raises(VerificationGateError, match="in that order"):
        run_verification_gate(steps)

    # WHY : Assumptions: the refusal is asserted to be TOTAL -- not one pass ran. A gate that
    #   verified the two passes it was given and then refused would have done real work under a
    #   configuration it was about to reject, and on a verification path that work is a read of
    #   every extract. The refusal is the cheap outcome, so it has to come first.
    assert not any(stub.ran for stub in stubs)


def test_a_reordered_gate_is_refused() -> None:
    """Refuse a gate whose passes are supplied out of declared order.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If a reordered gate is accepted.
    """
    steps, _ = _gate_steps(True, True, True)
    reordered = {name: steps[name] for name in reversed(GATE_PASSES)}

    # WHY : Alternatives Considered: the gate compares its keys as an ORDERED tuple rather than as
    #   a set, and this is the case that distinguishes the two. A set comparison would accept this
    #   mapping, whose contents are complete and whose order inverts the dependency the passes were
    #   ordered by -- and every pass would then still report a verdict, so nothing downstream would
    #   notice.
    with pytest.raises(VerificationGateError, match="in that order"):
        run_verification_gate(reordered)


def test_a_pass_that_cannot_judge_is_not_reported_as_a_failed_pass() -> None:
    """Let a pass's own refusal propagate, rather than folding it into a failed verdict.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the gate swallows a pass's refusal.
    """
    steps, _ = _gate_steps(True, True, True)

    def _refuse() -> _StubPass:
        """Refuse to judge, as a pass does when its session or its result set is unusable.

        Returns
        -------
        _StubPass
            Never returns.

        Raises
        ------
        RowCountVerificationError
            Always.
        """
        raise RowCountVerificationError("the session handed to this pass can write")

    steps[GATE_PASSES[0]] = _refuse  # type: ignore[assignment]

    # WHY : Trade-offs: the distinction being preserved is between "this pass compared the data and
    #   the data differs" and "this pass compared nothing". Folding a refusal into a failed outcome
    #   would report a data disagreement where the truth is that a session was writable or an
    #   extract was unreadable, and an operator would go looking for a load defect that is not
    #   there. The caller's own error mapping owns the exit status for a refusal.
    with pytest.raises(RowCountVerificationError):
        run_verification_gate(steps)


def test_a_pass_that_judged_nothing_does_not_verify() -> None:
    """Refuse to call an empty per-dataset aggregate verified.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If an aggregate over no result verifies.
    """
    empty = CombinedPassResult(label="record checksums", parts=())
    populated = CombinedPassResult(label="record checksums", parts=(_StubPass("acctdata", True),))

    # WHY : the failure this guards against is silent rather than loud: a dataset vocabulary that
    #   resolved to nothing, or a generator already consumed by an earlier loop, produces an
    #   aggregate over zero comparisons. `all(())` is True, so an aggregate that only reduced its
    #   parts would certify a load that nothing had read -- which is the one outcome a verification
    #   pass must never produce.
    assert not empty.verified
    assert "0 result(s)" in empty.render()
    assert populated.verified


def test_an_aggregate_fails_when_any_of_its_parts_fails() -> None:
    """Fail the whole pass when one dataset of many disagrees.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If one failing part does not fail the aggregate, or is left out of the rendering.
    """
    aggregate = CombinedPassResult(
        label="record checksums",
        parts=(
            _StubPass("acctdata", True),
            _StubPass("carddata", False),
            _StubPass("custdata", True),
        ),
    )

    assert not aggregate.verified
    # WHY : every part is asserted PRESENT in the rendering, including the two that passed. A
    #   rendering that showed only the failure would leave an operator unable to tell which
    #   datasets were actually compared, which is the difference between "one dataset is wrong" and
    #   "one dataset was checked and it is wrong".
    rendered = aggregate.render()
    assert "3 result(s)" in rendered
    for part in ("acctdata", "carddata", "custdata"):
        assert part in rendered


# WHY : Assumptions: the canonical-form section below drives the digest with the value types a
#   DRIVER returns, not only the three a reader produces. That is the asymmetry the pass lives on:
#   the source side is decoded text, exact decimals, raw bytes and nulls, while the read-back side
#   of the same eleven records carries integers for `PIC 9(n)` keys, `datetime.date` for a DATE
#   column, `datetime.datetime` for a TIMESTAMP(6), a `uuid.UUID` for the one derived subject column
#   and a memoryview for BYTEA. A canonicaliser that refused any of those made the pass unusable on
#   eight of the eleven loadable records, which is why the widened domain is asserted per type.
# WHY : Assumptions: `_ACCOUNT_LAYOUT` is the import alias bound at the head of this module, not a
#   second lookup. `layouts.ACCOUNT_LAYOUT is layouts.layout("ACCOUNT")` holds, so a second binding
#   here would restate one object under one name and leave a reader checking which was in scope.


def test_the_digest_pairs_a_key_integer_with_the_digits_the_extract_carries() -> None:
    """Digest a read-back integer key and the extract's zero-padded digits to one value.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the two sides of a `PIC 9(n)` key digest differently.
    """
    fields = ("ACCT-ID",)
    # WHY : this is the pairing that decides whether the pass is usable at all. `ACCT-ID` is
    #   `PIC 9(11)`, so the extract carries "00000000001" and the BIGINT column reads back as 1.
    #   Without the declared display width the two digest differently on EVERY row of every table
    #   keyed by a `PIC 9(n)` -- eight of the eleven loadable records -- and a reader taught that
    #   the pass always fails is a reader who stops running it.
    from_extract = digest_of_record({"ACCT-ID": "00000000001"}, fields, layout=_ACCOUNT_LAYOUT)
    from_database = digest_of_record({"ACCT-ID": 1}, fields, layout=_ACCOUNT_LAYOUT)

    assert from_extract == from_database
    # WHY : the pairing must not flatten DIFFERENT keys together. Zero-padding to a common width is
    #   only safe if it is injective, so a second key is asserted to digest differently.
    assert digest_of_record({"ACCT-ID": 2}, fields, layout=_ACCOUNT_LAYOUT) != from_database


def test_the_digest_renders_a_date_as_the_characters_the_copybook_holds() -> None:
    """Digest a read-back date and the extract's ``YYYY-MM-DD`` characters to one value.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If a DATE column and its source characters digest differently.
    """
    import datetime

    fields = ("ACCT-OPEN-DATE",)
    # WHY : `PIC X(10)` holding an ISO-ordered date is exactly why AAP 0.4.1.3 maps that picture to
    #   DATE, and the mapping is only lossless if the digest renders the date back to the same ten
    #   characters. `isoformat()` is asserted rather than assumed because a rendering through
    #   `str(datetime.date)` is identical here while `strftime` under a locale-sensitive form would
    #   not be.
    assert digest_of_record(
        {"ACCT-OPEN-DATE": "1996-11-02"}, fields, layout=_ACCOUNT_LAYOUT
    ) == digest_of_record(
        {"ACCT-OPEN-DATE": datetime.date(1996, 11, 2)}, fields, layout=_ACCOUNT_LAYOUT
    )


def test_the_digest_renders_a_timestamp_through_the_load_projection_form() -> None:
    """Digest a read-back instant and the extract's 26-character stamp to one value.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If a TIMESTAMP(6) column and its source characters digest differently.
    """
    import datetime

    fields = ("ORIG-TS",)
    stamp = "2022-06-10 19:27:53.000000"
    # WHY : the form is read from `copybook.timestamp.ISO_FORM`, the same constant the load
    #   projection renders a 26-character span through, rather than spelled again here.
    #   `isoformat()` would write a `T` where the copybook holds a space and would DROP a zero
    #   microsecond field entirely, so it would make every timestamp column differ on a correct
    #   load -- and `ORIG-TS` is exactly the deterministic stamp this pass is allowed to compare.
    assert digest_of_record({"ORIG-TS": stamp}, fields) == digest_of_record(
        {"ORIG-TS": datetime.datetime(2022, 6, 10, 19, 27, 53)}, fields
    )


def test_the_digest_renders_a_subject_identifier_as_its_canonical_text() -> None:
    """Digest a read-back UUID and its canonical text to one value.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If a UUID column and its source text digest differently.
    """
    import uuid

    fields = ("SEC-USR-SUBJECT",)
    text = "7c9e6679-7425-40de-944b-e07fc1f90ae7"
    # WHY : `auth.users.cognito_sub` is the one derived column in the corpus, published as text and
    #   stored as UUID. Without this rendering the pass could not compare the users record at all,
    #   and excluding a column to make a pass green is how a pass stops proving anything.
    assert digest_of_record({"SEC-USR-SUBJECT": text}, fields) == digest_of_record(
        {"SEC-USR-SUBJECT": uuid.UUID(text)}, fields
    )


def test_the_digest_accepts_a_driver_buffer_for_a_binary_column() -> None:
    """Digest a memoryview, a bytearray and the bytes they view to one value.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the three spellings of the same bytes digest differently.
    """
    fields = ("CARD-CVV-CD",)
    payload = b"\x01\x02\xff"
    # WHY : a driver hands a BYTEA column back as a MEMORYVIEW on its own wire buffer, which is
    #   valid only while the cursor's buffer lives. The canonicaliser copies it, so a digest may
    #   outlive the cursor; digesting the view itself would either raise once the buffer was
    #   released or, worse, read reused memory.
    baseline = digest_of_record({"CARD-CVV-CD": payload}, fields)

    assert digest_of_record({"CARD-CVV-CD": memoryview(payload)}, fields) == baseline
    assert digest_of_record({"CARD-CVV-CD": bytearray(payload)}, fields) == baseline


def test_the_digest_keeps_raw_bytes_apart_from_the_text_of_the_same_characters() -> None:
    """Digest bytes and the text they spell to different values.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If a type-tagged rendering collides across the value domains.
    """
    fields = ("VALUE",)
    # WHY : this is what the TYPE TAG is for, and it is not hypothetical: an enciphered column and
    #   a text column can hold the same characters, and a rendering that joined values without
    #   naming their type would report a load that had written the ciphertext into the plaintext
    #   column as verified. Text "1" and integer 1 are separated for the same reason -- one is a
    #   code and the other a count.
    assert digest_of_record({"VALUE": b"AB"}, fields) != digest_of_record({"VALUE": "AB"}, fields)
    assert digest_of_record({"VALUE": "1"}, fields) != digest_of_record({"VALUE": 1}, fields)


def test_the_digest_keeps_an_absent_field_apart_from_an_empty_one() -> None:
    """Digest a null and an empty string to different values.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If a null and an empty value collide.
    """
    fields = ("VALUE",)
    # WHY : a nullable column read back as None and a `CHAR(n)` read back as blanks are DIFFERENT
    #   load outcomes, and only one of them is right for a given column. Rendering both as nothing
    #   would make a load that wrote nulls where the extract carried blanks -- the exact failure a
    #   wrong projection produces -- verify cleanly.
    assert digest_of_record({"VALUE": None}, fields) != digest_of_record({"VALUE": ""}, fields)
    assert digest_of_record({"VALUE": None}, fields) != digest_of_record({"VALUE": "   "}, fields)


def test_the_digest_refuses_a_value_type_no_column_of_this_migration_holds() -> None:
    """Refuse a boolean and a binary float rather than rendering either.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If either type is digested instead of refused.
    """
    fields = ("VALUE",)
    # WHY : `bool` is refused BEFORE `int` even though it is one, because `True` would otherwise
    #   render as the digit 1 and compare equal to a count of one. No column of this migration is
    #   boolean, so a boolean here is a substituted value rather than data.
    with pytest.raises(TypeError, match="boolean"):
        digest_of_record({"VALUE": True}, fields)
    # WHY : a binary float cannot represent ten cents exactly, so digesting one would make the
    #   result depend on representation error: two loads of the same cents could digest differently
    #   and two different amounts could digest identically. AAP rule T3 forbids float in the money
    #   path, and refusing it here is what makes that rule mechanical in this pass.
    with pytest.raises(TypeError, match="cannot be digested"):
        digest_of_record({"VALUE": 1.5}, fields)


def test_an_empty_load_of_an_empty_extract_verifies() -> None:
    """Verify a comparison of no records against no rows, rather than failing it.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If two empty sides do not verify, or if the comparison reports having compared anything.
    """
    comparison = compare_record_digests(
        "ACCOUNT", "account.accounts", [], [], ("ACCT-ID",), layout=_ACCOUNT_LAYOUT
    )

    # WHY : Refactoring Rationale: an empty comparison used to be unverified on the reasoning that
    #   a run which judged nothing has proved nothing. That rule is right for a REPORT built from a
    #   query, where an empty result set is indistinguishable from broken plumbing, and wrong here:
    #   this pass is handed both sides explicitly, and the accidental-empty routes all RAISE before
    #   reaching a verdict -- an unreadable extract raises OSError, a truncated one raises
    #   RecordLengthError, an unknown layout raises LayoutError. What remained was a genuine state,
    #   an empty extract loaded into an empty table, reported as a failure an operator could do
    #   nothing about.
    assert comparison.verified
    assert comparison.compared == 0
    assert comparison.matched


def _money_row(total: Decimal, *, row_count: int = 0, negative_rows: int = 0) -> MoneyTotalRow:
    """Build one money-total line for a column the migration declares.

    Parameters
    ----------
    total : Decimal
        The target-side total the line reports.
    row_count : int
        How many rows the target table holds.
    negative_rows : int
        How many of those rows carry a negative amount.

    Returns
    -------
    MoneyTotalRow
        A conforming line, so the verdict rather than the parsing is what a test exercises.

    Raises
    ------
    None
        Building a validated line cannot fail.
    """
    # WHY : Assumptions: the column named is a REAL declared one -- the transaction master's amount,
    #   whose extract ships no seed file -- because the expected-empty rule below is only meaningful
    #   for a column that genuinely has no source measurement. A made-up column would exercise the
    #   same branch over a state the migration never reaches.
    return MoneyTotalRow(
        target_table="ledger.transactions",
        money_column="amount",
        cobol_field="TRAN-AMT",
        cobol_picture="S9(09)V99",
        # WHY : Assumptions: the declared type is spelled in the UPPER-CASE form the shipped query
        #   projects, because the line refuses any other spelling. That refusal is deliberate -- a
        #   money column is NUMERIC at scale two and nothing else -- so a fixture that arranged a
        #   lower-case spelling would be testing the parser's tolerance rather than the verdict.
        sql_type="NUMERIC(11,2)",
        row_count=row_count,
        total=total,
        negative_rows=negative_rows,
    )


def test_a_money_column_with_no_source_passes_only_while_it_is_empty() -> None:
    """Pass an unmeasurable money column that is empty, and fail one that holds money.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If an unmeasurable column holding money is certified.
    """
    empty = MoneyParityLine(row=_money_row(Decimal("0.00")), source=None)
    holding_money = MoneyParityLine(row=_money_row(Decimal("1234.56"), row_count=7), source=None)
    holding_rows = MoneyParityLine(row=_money_row(Decimal("0.00"), row_count=7), source=None)

    # WHY : Refactoring Rationale: NO_SOURCE used to be unconditionally passing, which made the
    #   money pass certify any total at all for a column whose extract ships no seed file. The
    #   transaction master is exactly that column, and it is the one the nightly posting run writes
    #   -- so the pass would have blessed money left behind by an abandoned load, a load aimed at
    #   the wrong table, or a posting run that overtook its own verification gate. Nothing in this
    #   package can empty that table, so the rows would have stayed and every later run would have
    #   kept passing.
    assert empty.verified
    assert not holding_money.verified
    # WHY : the ROW COUNT is checked as well as the total, because a table holding equal and
    #   opposite amounts sums to zero. Checking the total alone would pass a table full of rows
    #   whose net happened to cancel, which is precisely the shape a duplicated debit-and-credit
    #   load produces.
    assert not holding_rows.verified
    assert not holding_money.comparable
    # WHY : the rendering is asserted to state the expectation AND the verdict, because before this
    #   rule a correctly-empty column and one holding unaccounted money rendered identically.
    assert "VIOLATED" in holding_money.describe()
    assert "held" in empty.describe()


def test_a_money_report_fails_on_an_unmeasurable_column_holding_money() -> None:
    """Fail the whole money report when its one unmeasurable line holds money.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the report's binary verdict does not follow from the line's.
    """
    report = MoneyTotalReport(
        lines=(MoneyParityLine(row=_money_row(Decimal("0.01"), row_count=1), source=None),)
    )

    # WHY : the REPORT is asserted as well as the line, because the report's verdict is what the
    #   command's exit status and the batch chain's gate are taken from. A line-level rule that the
    #   aggregate did not honour would fail nothing an operator could see.
    assert not report.verified
    assert report.mismatches == report.lines
    assert "money total verification FAILED" in report.render()


def test_a_baseline_free_count_line_passes_only_while_its_table_is_empty() -> None:
    """Pass a baseline-free row-count line whose table is empty, and fail one holding rows.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If a baseline-free line holding rows is certified.
    """
    unseeded = target_for(UNSEEDED_LAYOUT_NAME)
    qualified = f"{unseeded.schema}.{unseeded.table}"
    empty = RowCountRow(
        dataset=NO_DATASET_LABEL,
        target_table=qualified,
        expected_rows=None,
        actual_rows=0,
        delta=None,
        status=RowCountVerdict.NO_BASELINE.value,
    )
    populated = RowCountRow(
        dataset=NO_DATASET_LABEL,
        target_table=qualified,
        expected_rows=None,
        actual_rows=7,
        delta=None,
        status=RowCountVerdict.NO_BASELINE.value,
    )

    # WHY : Refactoring Rationale: NO_BASELINE used to pass unconditionally, so the shipped query's
    #   own words -- the NULL baseline is "reported for completeness" -- described a line that
    #   reported nothing. The state is determinable rather than unknown: no seed extract ships for
    #   the transaction master, so a correct migration leaves that table empty, and rows in it are
    #   evidence of exactly what a verification exists to catch.
    assert empty.verified
    assert not populated.verified
    # WHY : the pair is asserted to remain NOT COMPARABLE either way. The expected-empty rule is a
    #   verdict about the table, not a substitute baseline, and collapsing the two would make the
    #   report claim a comparison it never made.
    assert not empty.comparable
    assert not populated.comparable
    assert "VIOLATED" in populated.describe()
    assert "held" in empty.describe()


@pytest.mark.parametrize(
    "value",
    [
        pytest.param(Decimal("1.5"), id="fractional-decimal"),
        pytest.param("7", id="text"),
        pytest.param(7.0, id="binary-float"),
        pytest.param(True, id="boolean"),
        pytest.param(None, id="null"),
    ],
)
def test_a_count_that_is_not_an_exact_whole_number_is_refused(value: object) -> None:
    """Refuse a count value that is not an exact whole number, rather than coercing it.

    Parameters
    ----------
    value : object
        A value no ``COUNT(*)`` produces.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If an inexact count is coerced into an integer instead of refused.
    """
    # WHY : Refactoring Rationale: the count was parsed with `int(row[0])`, which TRUNCATES a
    #   Decimal and parses text, so a query rewritten to return an average, a ratio or a text
    #   aggregate would have been silently floored into a plausible count. A row count is the one
    #   number in this pass that is exact by construction, so anything inexact arriving here means
    #   the query is not the query -- and quietly rounding it is how a verification comes to certify
    #   a load it never counted.
    # WHY : Assumptions: the narrow stub is used rather than the shared recording double, because
    #   that double REFUSES to record a float at all -- money is exact fixed point end to end -- so
    #   the one representation a driver mapping is most likely to substitute cannot be arranged on
    #   it. The stub is the narrowest object that can present one.
    connection = _OneValueConnection(value)

    with pytest.raises(RowCountVerificationError):
        count_target_rows(connection, "account", "accounts")


# WHY : Assumptions: the query-identity section drives BOTH passes through one parametrisation, so
#   neither can regress alone. The two modules deliberately reproduce each other's guards rather
#   than sharing them -- so that a fault in one pass's baseline table cannot take the other pass
#   down -- and the accepted cost of that duplication is exactly this: a rule corrected in one file
#   and forgotten in the other. A single parametrised test is what makes the cost bounded.
_QUERY_GUARDS = (
    pytest.param(
        read_row_count_query,
        VerificationQueryError,
        "row_counts.sql",
        id="row-counts",
    ),
    pytest.param(
        read_money_total_query,
        MoneyQueryError,
        "money_totals.sql",
        id="money-totals",
    ),
)


@pytest.mark.parametrize(("reader", "error", "name"), _QUERY_GUARDS)
def test_the_committed_query_passes_its_own_identity_guard(
    reader: object, error: type[Exception], name: str
) -> None:
    """Read each shipped query through its own guard, so the guard cannot reject the real file.

    Parameters
    ----------
    reader : object
        The pass's query reader.
    error : type[Exception]
        The refusal the reader raises, unused here and named so the parametrisation is one set.
    name : str
        The shipped file's name, asserted to be the file actually read.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If a guard refuses the query it was written to admit.
    """
    # WHY : a guard that rejected the committed artifact would be worse than no guard: every
    #   verification would fail for a reason unrelated to the load, and the fix an operator would
    #   reach for is to stop running the pass. This assertion is what keeps the allow-list honest as
    #   either query gains a common table expression.
    text = reader(_SQL_ROOT / name)  # type: ignore[operator]

    assert text.strip()
    assert error is not None


@pytest.mark.parametrize(("reader", "error", "name"), _QUERY_GUARDS)
def test_a_query_that_does_not_open_as_a_read_is_refused(
    reader: object, error: type[Exception], name: str, tmp_path: Path
) -> None:
    """Refuse a substituted query that opens with anything other than a read.

    Parameters
    ----------
    reader : object
        The pass's query reader.
    error : type[Exception]
        The refusal the reader must raise.
    name : str
        The file name to write the substituted text as, so the reader resolves it.
    tmp_path : Path
        Per-test directory the substituted file is written into.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If a text that writes is admitted.
    """
    # WHY : Assumptions: the allow-list is on the OPENING keyword rather than a deny-list of write
    #   verbs, and this is the case that shows why. Enumerating write verbs leaves the set
    #   open-ended -- a statement type nobody listed would pass a deny-list -- whereas a single
    #   statement that opens with SELECT or WITH cannot modify data unless one of its own
    #   expressions does, which the relation check catches.
    substituted = tmp_path / name
    substituted.write_text("UPDATE account.accounts SET current_balance = 0.00;", encoding="utf-8")

    with pytest.raises(error, match="rather than SELECT or WITH"):
        reader(substituted)  # type: ignore[operator]


@pytest.mark.parametrize(("reader", "error", "name"), _QUERY_GUARDS)
def test_a_query_reading_an_unexpected_relation_is_refused(
    reader: object, error: type[Exception], name: str, tmp_path: Path
) -> None:
    """Refuse a substituted query that reads a relation the pass is not entitled to read.

    Parameters
    ----------
    reader : object
        The pass's query reader.
    error : type[Exception]
        The refusal the reader must raise.
    name : str
        The file name to write the substituted text as.
    tmp_path : Path
        Per-test directory the substituted file is written into.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If a read of an unauthorised relation is admitted.
    """
    # WHY : Refactoring Rationale: this is the substitution the earlier shape checks admitted. A
    #   single pure-SQL SELECT with no placeholder satisfied all three of them while reading the
    #   base table the verification boundary exists to withhold, and it would have run on the
    #   session the pass had just certified as the sole authority entitled to judge the load --
    #   the CWE-89 shape, arriving through a path argument rather than a string concatenation.
    substituted = tmp_path / name
    substituted.write_text(
        "SELECT card_number, current_balance FROM account.accounts;", encoding="utf-8"
    )

    with pytest.raises(error, match="account.accounts"):
        reader(substituted)  # type: ignore[operator]


def test_the_row_count_report_runs_read_only_and_under_a_timeout(
    fake_aurora: FakeAuroraDatabase,
) -> None:
    """Open the transaction read-only, then probe the role, then bound and run the query.

    Parameters
    ----------
    fake_aurora : FakeAuroraDatabase
        Recording double answering the session probe and then the report.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If any of the three statements is absent, or arrives out of the server-valid order.
    """
    connection = _reporting_connection(fake_aurora)
    fake_aurora.arrange_rows("sentinel_report", [])

    _row_count_rows(connection, "select 1 -- sentinel_report")

    executed = fake_aurora.executed_sql()
    # WHY : the ORDER is asserted, not merely the presence, and this is the SERVER-VALID order:
    #   `SET TRANSACTION READ ONLY` states a property OF the transaction, so it belongs at its
    #   start, before anything -- the identity probe included -- has run in it. The probe follows
    #   inside that same transaction, then the timeout, then the report.
    # WHY : Refactoring Rationale: this assertion previously required `current_user` FIRST and the
    #   read-only setting SECOND, while its own comment said the setting had to be first. The
    #   implementation matched the assertion, so the probe ran in a still-writable transaction, and
    #   the double accepted any order so neither half could contradict the other. Both are corrected
    #   together: `FakeAuroraConnection.note_transaction_statement` now refuses a late
    #   `SET TRANSACTION`, which means this test cannot be satisfied by restoring the old order.
    assert "READ ONLY" in executed[0]
    assert "current_user" in executed[1]
    assert "statement_timeout" in executed[2]
    assert "sentinel_report" in executed[3]
    # WHY : the two guards fail differently and both are kept: the ROLE is what makes writing
    #   impossible, and the transaction setting is what makes an attempt to write fail loudly on a
    #   cluster where the role was mis-provisioned. Asserting the setting is asserting the second.
    assert not any("sentinel_report" in sql for sql in executed[:3])


def test_the_money_total_report_runs_read_only_and_under_a_timeout(
    fake_aurora: FakeAuroraDatabase,
) -> None:
    """Hold the money pass to the same four-statement order as the row-count pass.

    Parameters
    ----------
    fake_aurora : FakeAuroraDatabase
        Recording double answering the session probe and then the report.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If any of the three statements is absent, or arrives out of the server-valid order.
    """
    # WHY : Refactoring Rationale: the money pass had no order assertion at all, only the row-count
    #   pass did -- which is how the same ordering defect came to exist in both modules and be
    #   reported once. Both passes execute the identical discipline, so both are asserted, and a
    #   correction applied to one of them can no longer leave the other behind.
    connection = _reporting_connection(fake_aurora)
    fake_aurora.arrange_rows("sentinel_money", [])

    _money_total_rows(connection, "select 1 -- sentinel_money")

    executed = fake_aurora.executed_sql()
    assert "READ ONLY" in executed[0]
    assert "current_user" in executed[1]
    assert "statement_timeout" in executed[2]
    assert "sentinel_money" in executed[3]
    assert not any("sentinel_money" in sql for sql in executed[:3])


def test_the_double_refuses_a_read_only_setting_issued_after_a_query(
    fake_aurora: FakeAuroraDatabase,
) -> None:
    """Assert the double models transaction order rather than merely logging statements.

    Parameters
    ----------
    fake_aurora : FakeAuroraDatabase
        Recording double standing in for the connection boundary.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the double admits a late ``SET TRANSACTION``, or refuses one that opens a fresh
        transaction after a commit.
    """
    # WHY : Assumptions: this is the NEGATIVE probe for the two order assertions above. They can
    #   only be trusted while the double they run against is capable of refusing the wrong order,
    #   and it was not: it recorded statements and had no notion of a transaction, so the previous
    #   order passed and read as verified. Proving the refusal here is what makes the two
    #   assertions above evidence rather than restatement.
    connection = _reporting_connection(fake_aurora)

    with connection.cursor() as cursor:  # type: ignore[attr-defined]
        cursor.execute("select current_user")
        cursor.fetchone()
        with pytest.raises(FakeClientContractError, match="first statement"):
            cursor.execute("SET TRANSACTION READ ONLY")

    # WHY : Assumptions: a commit ends the transaction, so the same connection may legitimately
    #   open a new one and set it read-only. Asserting that too keeps the rule from being a blanket
    #   ban that a reused connection would trip over.
    connection.commit()  # type: ignore[attr-defined]
    with connection.cursor() as cursor:  # type: ignore[attr-defined]
        cursor.execute("SET TRANSACTION READ ONLY")
        # WHY : Assumptions: re-stating a mode the transaction already holds is admitted, and it is
        #   asserted here because the verification gate depends on it: three passes share one
        #   connection without committing between them, so passes two and three each re-assert
        #   `READ ONLY` on the transaction pass one opened. Only a FIRST establishment arriving late
        #   is a defect.
        cursor.execute("select current_user")
        cursor.fetchone()
        cursor.execute("SET TRANSACTION READ ONLY")


@pytest.mark.parametrize(
    ("name", "pinned"),
    [
        pytest.param("row_counts.sql", ROW_COUNT_QUERY_DIGEST, id="row-counts"),
        pytest.param("money_totals.sql", MONEY_TOTAL_QUERY_DIGEST, id="money-totals"),
    ],
)
def test_the_pinned_query_digest_is_the_shipped_file_s(name: str, pinned: str) -> None:
    """Assert each pinned digest is the digest of the file that ships beside it.

    Parameters
    ----------
    name : str
        The shipped query's file name.
    pinned : str
        The digest the pass pins.

    Returns
    -------
    None
        The assertion is the result.

    Raises
    ------
    AssertionError
        If the pin and the file disagree, reporting the measured digest to paste.
    """
    # WHY : Assumptions: this test is what makes the pin AFFORDABLE. Pinning a digest without it
    #   would move the cost of an edit to an operator's next verification run, where the failure
    #   reads as a privilege or packaging problem; here it fails in CI, at the moment of the edit,
    #   with the value to paste. The failure message therefore carries the measured digest -- the
    #   one piece of information the person editing the query needs.
    measured = hashlib.sha256(
        (_SQL_ROOT / name).read_text(encoding="utf-8").encode("utf-8")
    ).hexdigest()

    assert measured == pinned, (
        f"{name} has digest {measured}; update the pin in the owning verification pass to that"
        " value, having reviewed the query change itself"
    )


@pytest.mark.parametrize(("reader", "error", "name"), _QUERY_GUARDS)
def test_a_substituted_but_well_formed_query_is_refused(
    reader: object, error: type[Exception], name: str, tmp_path: Path
) -> None:
    """Refuse a harmless, allow-listed query that is nevertheless not the committed one.

    Parameters
    ----------
    reader : object
        The pass's query reader.
    error : type[Exception]
        The refusal the reader must raise.
    name : str
        The file name to write the substituted text as.
    tmp_path : Path
        Per-test directory the substituted file is written into.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If a substituted query that passes every shape check is admitted.
    """
    # WHY : this is the residue the shape checks leave, and it is the reason the digest pin exists.
    #   A text that opens with SELECT and reads only the allow-listed view passes every one of them
    #   while projecting whichever columns, in whichever order, its author chose -- and the pass
    #   would then judge a load against a report nobody committed, on the session it had just
    #   certified as the sole authority entitled to judge it.
    substituted = tmp_path / name
    relation = (
        "reporting.v_verification_row_counts"
        if name == "row_counts.sql"
        else "reporting.v_verification_money_totals"
    )
    substituted.write_text(f"SELECT 1 FROM {relation};", encoding="utf-8")

    with pytest.raises(error, match="not the committed"):
        reader(substituted)  # type: ignore[operator]


def test_the_digest_cannot_be_collided_by_a_value_holding_the_delimiter_byte() -> None:
    """Digest two records apart even when one field's own bytes spell the frame delimiter.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If a value containing the delimiter byte can be made to imitate a field or record boundary.
    """
    fields = ("LEFT", "RIGHT")
    # WHY : Refactoring Rationale: this is the collision a bare separator allowed, and it is
    #   structural rather than theoretical. Joining values with a byte that a value may itself
    #   contain means two DIFFERENT records can produce one identical byte string before SHA-256
    #   ever runs. These datasets carry `bytea` columns whose contents are arbitrary, so a value
    #   holding the delimiter is not a contrivance. Length-prefixing every field is what makes the
    #   framing injective: the length is read before the payload, so no payload can imitate a
    #   boundary. The vector is restated over FRAME_DELIMITER because the separator bytes it was
    #   authored against are withdrawn.
    collidable = digest_of_record({"LEFT": FRAME_DELIMITER + b"B", "RIGHT": b""}, fields)
    plain = digest_of_record({"LEFT": b"", "RIGHT": FRAME_DELIMITER + b"B"}, fields)

    assert collidable != plain
    # WHY : the RECORD boundary is asserted the same way, because a delimiter inside a value would
    #   let one record imitate two. `digest_records` frames each record with its own length, so a
    #   stream of one record holding the delimiter cannot equal a stream of two.
    one_record = digest_records([{"LEFT": FRAME_DELIMITER, "RIGHT": b"X"}], fields)
    two_records = digest_records(
        [{"LEFT": b"", "RIGHT": b""}, {"LEFT": b"", "RIGHT": b"X"}], fields
    )

    assert one_record.digest != two_records.digest


#: The sealed columns a two-column audit is exercised over, mapping source field to stored column.
#:
#: Assumptions: the pair is spelled here rather than read from the customer target, because these
#: tests are about the audit's ARITHMETIC and its refusals. Reading the real declaration would make
#: them fail for a schema change that has nothing to do with either.
_TWO_SEALED_COLUMNS: Final[Mapping[str, str]] = MappingProxyType(
    {"CUST-SSN": "ssn_encrypted", "CUST-GOVT-ISSUED-ID": "govt_issued_id_encrypted"}
)


def _envelope(marker: bytes = b"CDCI", *, length: int = SEALED_ENVELOPE_MIN_BYTES) -> bytes:
    """Build a stored value with a chosen marker and total length.

    Parameters
    ----------
    marker : bytes, optional
        The four leading bytes. Defaults to a marker the audit accepts.
    length : int, optional
        The total byte length. Defaults to the published minimum.

    Returns
    -------
    bytes
        A value of exactly ``length`` bytes beginning with ``marker``.

    Raises
    ------
    None
    """
    return (marker + bytes(max(length - len(marker), 0)))[:length]


def test_the_accepted_envelope_markers_are_the_two_the_writer_declares() -> None:
    """Pin the marker set the audit accepts, so a silent widening is visible.

    Parameters
    ----------
    None
        Reads the published constant.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the accepted set changes, or a marker is not four bytes.
    """
    # WHY : Assumptions: the set is asserted against LITERALS deliberately, even though
    #   `loaders.protected_columns` also declares these markers. Importing the writer's constants
    #   here is precisely what would make the audit agree with the writer by construction -- a
    #   marker changed on one side and mirrored on the other would leave every stored envelope
    #   written before the change unreadable and this test still green.
    assert SEALED_ENVELOPE_MARKERS == frozenset({b"CDCI", b"CDCV"})
    for marker in SEALED_ENVELOPE_MARKERS:
        assert len(marker) == 4
    # WHY : Assumptions: the minimum is a LOWER bound and is asserted as such, because the wrapped
    #   data key's length belongs to the key-management service. An exact expected length would make
    #   this suite fail the day a key specification changed, over a value that was never wrong.
    assert SEALED_ENVELOPE_MIN_BYTES == 4 + 1 + 2 + 1 + 12 + 16


def test_a_sealed_column_audit_passes_when_every_sealable_value_stored_one_envelope() -> None:
    """Verify an audit whose stored count matches its expectation with no malformed value."""
    tally = SealableValueTally(_TWO_SEALED_COLUMNS)
    records = [
        {"CUST-SSN": "123456789", "CUST-GOVT-ISSUED-ID": "A1"},
        {"CUST-SSN": "987654321", "CUST-GOVT-ISSUED-ID": "B2"},
    ]
    assert list(tally.tap(records)) == records
    audit = audit_sealed_columns(
        record_name="CUSTOMER",
        qualified_table='"account"."customers"',
        columns=_TWO_SEALED_COLUMNS,
        expected=tally.counts,
        stored_envelopes={
            "ssn_encrypted": [_envelope(), _envelope()],
            "govt_issued_id_encrypted": [_envelope(b"CDCV"), _envelope(b"CDCV")],
        },
    )
    assert audit.verified
    assert audit.expected == {"ssn_encrypted": 2, "govt_issued_id_encrypted": 2}
    assert audit.stored == {"ssn_encrypted": 2, "govt_issued_id_encrypted": 2}
    assert audit.malformed == {"ssn_encrypted": 0, "govt_issued_id_encrypted": 0}


@pytest.mark.parametrize(
    ("value", "counted"),
    [("123456789", 1), ("   ", 0), ("", 0), (None, 0), (0, 1)],
    ids=["present", "blank", "empty", "absent", "zero"],
)
def test_the_tally_counts_only_a_field_that_carried_something_to_seal(
    value: object, counted: int
) -> None:
    """Treat blank, empty and absent as nothing to seal, and a present value as one.

    Parameters
    ----------
    value : object
        The source field value.
    counted : int
        How many sealable values that represents.

    Returns
    -------
    None
        The assertion is the result.

    Raises
    ------
    AssertionError
        If a blank pad is counted, or a real value is not.
    """
    # WHY : Assumptions: a BLANK is nothing to seal, and that is a fact about the corpus rather than
    #   a convenience. Fixed-width records pad every unused text field with spaces, so a blank
    #   government identifier means the record supplied none -- counting it would expect an envelope
    #   the loader correctly did not write, and the audit would report every such record as a loss.
    # WHY : Assumptions: a numeric ZERO is counted, because it is a value that was supplied. Reusing
    #   a truthiness test would silently drop it, which is the classic way a "missing" check comes
    #   to mean "falsy".
    tally = SealableValueTally({"CUST-SSN": "ssn_encrypted"})
    assert list(tally.tap([{"CUST-SSN": value}]))
    assert tally.counts == {"ssn_encrypted": counted}


def test_the_tally_refuses_to_report_counts_before_its_stream_ended() -> None:
    """Refuse a partial measurement rather than answering a prefix of the extract."""
    # WHY : Assumptions: this is the guard on an otherwise INVISIBLE coupling. The counts are only
    #   complete because `compare_record_digests` drains the tapped stream -- it walks both sides
    #   with `zip_longest` to exhaustion and its reporting limit caps only what it lists. A consumer
    #   that later stopped early would under-count every sealed column and turn a correct load into
    #   a reported violation, with nothing pointing at the cause. Refusing names it at the mistake.
    tally = SealableValueTally({"CUST-SSN": "ssn_encrypted"})
    with pytest.raises(ChecksumVerificationError, match="before its stream ended"):
        _ = tally.counts

    stream = tally.tap([{"CUST-SSN": "1"}, {"CUST-SSN": "2"}])
    assert next(stream)
    with pytest.raises(ChecksumVerificationError, match="before its stream ended"):
        _ = tally.counts

    assert list(stream)
    assert tally.counts == {"ssn_encrypted": 2}


def test_the_tally_refuses_a_second_traversal() -> None:
    """Refuse to tap twice, because two streams would add their counts together."""
    # WHY : Assumptions: a second tap would DOUBLE every count rather than replace them, so the
    #   audit would expect twice as many envelopes as the extract holds and report a correct load
    #   as a total loss. Refusing the second call is cheaper to diagnose than a verdict wrong by a
    #   factor of two.
    tally = SealableValueTally({"CUST-SSN": "ssn_encrypted"})
    assert list(tally.tap([{"CUST-SSN": "1"}])) == [{"CUST-SSN": "1"}]
    with pytest.raises(ChecksumVerificationError, match="already been tapped"):
        list(tally.tap([{"CUST-SSN": "2"}]))


def test_a_sealed_column_audit_fails_a_column_that_stored_fewer_envelopes_than_expected() -> None:
    """Report the column, and fail, when a sealable value produced no stored envelope."""
    # WHY : Assumptions: this is the exact defect the finding names. The digest necessarily excludes
    #   a sealed column -- its initialisation vector is drawn per value, so its bytes are not a
    #   function of its source -- which left a load that wrote a null for every identifier reporting
    #   a clean comparison over the columns it did compare.
    tally = SealableValueTally({"CUST-SSN": "ssn_encrypted"})
    assert list(tally.tap([{"CUST-SSN": "1"}, {"CUST-SSN": "2"}]))
    audit = audit_sealed_columns(
        record_name="CUSTOMER",
        qualified_table='"account"."customers"',
        columns={"CUST-SSN": "ssn_encrypted"},
        expected=tally.counts,
        stored_envelopes={"ssn_encrypted": [_envelope(), None]},
    )
    assert not audit.verified
    assert "ssn_encrypted: expected 2, stored 1, malformed 0 [VIOLATED]" in audit.render()


@pytest.mark.parametrize(
    "stored",
    [_envelope(b"XXXX"), _envelope(length=SEALED_ENVELOPE_MIN_BYTES - 1), b"", "CDCI-not-bytes"],
    ids=["foreign-marker", "too-short", "empty", "not-bytes"],
)
def test_a_sealed_column_audit_fails_a_stored_value_that_is_not_a_known_envelope(
    stored: object,
) -> None:
    """Count a value of the wrong framing as malformed, whichever way its framing is wrong.

    Parameters
    ----------
    stored : object
        The stored value to audit.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If a malformed value is accepted, or is not counted as stored.
    """
    # WHY : Assumptions: a malformed value is counted as STORED as well as malformed, so the two
    #   readings stay independent. A value that is present but unusable is a different fault from
    #   one that is missing, and collapsing them would report a corrupted envelope as an absent
    #   one -- which points an operator at the loader instead of at the key material.
    tally = SealableValueTally({"CUST-SSN": "ssn_encrypted"})
    assert list(tally.tap([{"CUST-SSN": "1"}]))
    audit = audit_sealed_columns(
        record_name="CUSTOMER",
        qualified_table='"account"."customers"',
        columns={"CUST-SSN": "ssn_encrypted"},
        expected=tally.counts,
        stored_envelopes={"ssn_encrypted": [stored]},
    )
    assert not audit.verified
    assert audit.stored == {"ssn_encrypted": 1}
    assert audit.malformed == {"ssn_encrypted": 1}


@pytest.mark.parametrize("omitted", ["expected", "stored_envelopes"], ids=["expected", "stored"])
def test_a_sealed_column_audit_refuses_an_absent_measurement(omitted: str) -> None:
    """Refuse a missing measurement on either side rather than counting it as nothing.

    Parameters
    ----------
    omitted : str
        Which argument omits the audited column.

    Returns
    -------
    None
        The assertion is the result.

    Raises
    ------
    AssertionError
        If an omission is silently read as zero.
    """
    # WHY : Assumptions: BOTH sides are checked because an omission fails in opposite directions and
    #   each reading is spectacular. No expectation against fifty stored reads as fifty spurious
    #   envelopes; no stored measurement against fifty expected reads as a load that dropped every
    #   identifier. Either would consume a cutover window; naming the omission costs nothing.
    arguments: dict[str, object] = {
        "record_name": "CUSTOMER",
        "qualified_table": '"account"."customers"',
        "columns": {"CUST-SSN": "ssn_encrypted"},
        "expected": {"ssn_encrypted": 1},
        "stored_envelopes": {"ssn_encrypted": [_envelope()]},
    }
    arguments[omitted] = {}
    with pytest.raises(ChecksumVerificationError, match="no complete measurement"):
        audit_sealed_columns(**arguments)  # type: ignore[arg-type]


def test_a_record_that_seals_nothing_is_audited_as_verified_and_says_so() -> None:
    """Verify a seal-free record vacuously, and render the fact rather than nothing."""
    # WHY : Assumptions: an empty audit verifies TRUE, unlike the empty AGGREGATE elsewhere in this
    #   package which verifies False. The two emptinesses are different claims: an empty aggregate
    #   means a pass judged nothing it was meant to judge, while nine of the eleven loadable records
    #   genuinely declare no sealing projection at all -- reporting those as unverified would make
    #   the combined gate fail on a correct load.
    tally = SealableValueTally({})
    assert list(tally.tap([{"TRAN-CAT-CD": "1"}]))
    audit = audit_sealed_columns(
        record_name="TCATBAL",
        qualified_table='"ledger"."transaction_category_balances"',
        columns={},
        expected=tally.counts,
        stored_envelopes={},
    )
    assert audit.verified
    assert "0 column(s)" in audit.render()


def test_a_sealed_column_audit_renders_counts_and_never_a_stored_value() -> None:
    """Keep every stored byte, marker and per-row length out of the rendered audit."""
    # WHY : Assumptions: the rendering is asserted to disclose NOTHING, because the whole premise of
    #   auditing a sealed column without deciphering it is that the audit reveals nothing. Even an
    #   individual length is a weak disclosure -- a social security number and a government-issued
    #   identifier differ in width, so a per-row length leaks which kind a customer supplied.
    marked = _envelope(b"CDCI", length=SEALED_ENVELOPE_MIN_BYTES + 7)
    tally = SealableValueTally({"CUST-SSN": "ssn_encrypted"})
    assert list(tally.tap([{"CUST-SSN": "123456789"}]))
    rendered = audit_sealed_columns(
        record_name="CUSTOMER",
        qualified_table='"account"."customers"',
        columns={"CUST-SSN": "ssn_encrypted"},
        expected=tally.counts,
        stored_envelopes={"ssn_encrypted": [marked]},
    ).render()
    assert "123456789" not in rendered
    assert "CDCI" not in rendered
    assert str(len(marked)) not in rendered
    assert rendered == (
        'sealed columns CUSTOMER -> "account"."customers": 1 column(s)\n'
        "  ssn_encrypted: expected 1, stored 1, malformed 0 [SEALED]"
    )


def test_a_sealed_column_audit_is_a_snapshot_that_a_later_count_cannot_change() -> None:
    """Freeze the counts a verdict was computed from, so the artifact stays true."""
    # WHY : Assumptions: the audit is the artifact a cutover decision is made from, so the numbers
    #   behind its verdict must not be able to move afterwards. A caller is free to hand this
    #   function a dictionary it still holds, which is exactly how a rendered report and the verdict
    #   printed above it come to disagree.
    expected = {"ssn_encrypted": 1}
    audit = audit_sealed_columns(
        record_name="CUSTOMER",
        qualified_table='"account"."customers"',
        columns={"CUST-SSN": "ssn_encrypted"},
        expected=expected,
        stored_envelopes={"ssn_encrypted": [_envelope()]},
    )
    expected["ssn_encrypted"] = 99
    assert audit.expected == {"ssn_encrypted": 1}
    assert audit.verified


# ---------------------------------------------------------------------------------------------
# Record-level reconciliation -- the same domain, over real records instead of vectors
#
# Assumptions: the cases above pair one hand-built value against one driver-native value, which
#   is what isolates a rendering. The cases below do the opposite on purpose -- they pair a real
#   prepared record against the values a driver returns for the columns that record loads into, so
#   the two REPRESENTATIONS of one row are what is under test rather than the digest's arithmetic.
#   Both layers are kept: a vector case names which rendering broke, and a record case is the only
#   one that can catch a rendering that is right in isolation and wrong for the field it is applied
#   to.
# Assumptions: the four shapes exercised cover every cross-representation pair the delivered
#   targets produce -- account gives BIGINT and three DATE columns, customer gives SMALLINT with
#   two nullable text columns, transaction gives TIMESTAMP(6) beside a NUMERIC and a CHAR(4) fed by
#   an unsigned display field, and the user record gives the one UUID. A fifth shape would add no
#   pair that is not already here.
# ---------------------------------------------------------------------------------------------

# Assumptions: the four shapes exercised are the ones the finding names plus the identity record,
#   and they are chosen because between them they cover EVERY cross-representation pair the
#   delivered targets produce: account gives BIGINT and three DATE columns, customer gives
#   SMALLINT with two nullable text columns, transaction gives TIMESTAMP(6) beside a NUMERIC and a
#   CHAR(4) fed by an unsigned display field, and the user record gives the one UUID. A fifth shape
#   would add no pair that is not already here.
_ACCOUNT_EXTRACT = Path(__file__).resolve().parents[2] / "app" / "data" / "ASCII" / "acctdata.txt"


_CUSTOMER_EXTRACT = Path(__file__).resolve().parents[2] / "app" / "data" / "ASCII" / "custdata.txt"


# Assumptions: the subject is an all-zero version-4 identifier, matching the spelling
#   ``config.resolve_seed_user_subjects`` validates. A subject NAMES a user rather than
#   authenticating one, so nothing is disclosed by writing it, and a fixed value is what makes the
#   UUID reconciliation below exact.
_SUBJECT = "00000000-0000-4000-8000-000000000001"


def _unchanged(value: object) -> object:
    """Return a value as it stands, for a column whose driver type is the prepared type.

    Parameters
    ----------
    value : object
        The prepared value.

    Returns
    -------
    object
        The same value.

    Raises
    ------
    None
        Returning an argument cannot fail.
    """
    # WHY : Assumptions: this exists as a NAMED default rather than an inline lambda, so the
    #   read-back builder reads as "no cast for this column" -- which is a statement about the
    #   column: every CHAR, VARCHAR and NUMERIC column really does come back as the type sent.
    return value


def _as_integer(value: object) -> int:
    """Cast a display-digit span the way a numeric column and its driver do.

    Parameters
    ----------
    value : object
        The prepared value, which for an unsigned display field is its digits as characters.

    Returns
    -------
    int
        The integer PostgreSQL stores and the driver returns.

    Raises
    ------
    ValueError
        Propagated if the span is not digits, which would mean the projection had not produced
        something the column could accept.
    """
    return int(str(value))


def _as_date(value: object) -> datetime.date:
    """Cast an ISO-ordered ten-character span the way a DATE column and its driver do.

    Parameters
    ----------
    value : object
        The prepared value, which for a date field is ``YYYY-MM-DD`` as characters.

    Returns
    -------
    datetime.date
        The calendar day the driver returns.

    Raises
    ------
    ValueError
        Propagated if the span is not an ISO date.
    """
    return datetime.date.fromisoformat(str(value))


def _as_timestamp(value: object) -> datetime.datetime:
    """Cast a 26-character stamp the way a TIMESTAMP(6) column and its driver do.

    Parameters
    ----------
    value : object
        The prepared value, which the load projection has already rendered into the one spelling
        the column accepts.

    Returns
    -------
    datetime.datetime
        The naive instant the driver returns for a column with no time zone.

    Raises
    ------
    ValueError
        Propagated if the span is not in that spelling, which would mean the projection and the
        column disagreed.
    """
    # WHY : Assumptions: parsed through the SAME directive the boundary publishes, rather than
    #   through `fromisoformat`. The two accept different sets -- `fromisoformat` would also accept
    #   spellings a `TIMESTAMP(6)` column does not -- and using the published directive is what
    #   makes this cast the inverse of the projection rather than a looser reading of it.
    return datetime.datetime.strptime(str(value), ISO_FORM)  # noqa: DTZ007


def _as_uuid(value: object) -> uuid.UUID:
    """Cast a canonical RFC-4122 string the way a UUID column and its driver do.

    Parameters
    ----------
    value : object
        The prepared value, which is the subject the identity provider published.

    Returns
    -------
    uuid.UUID
        The identifier the driver returns.

    Raises
    ------
    ValueError
        Propagated if the string is not a UUID.
    """
    return uuid.UUID(str(value))


def _one_higher(value: object) -> int:
    """Move an integer to the next one, the smallest change that type admits.

    Parameters
    ----------
    value : object
        A read-back integer.

    Returns
    -------
    int
        The next integer.

    Raises
    ------
    TypeError
        Propagated if the value is not an integer, which would mean the cast under test had not
        produced one.
    """
    return int(value) + 1  # type: ignore[call-overload]


def _one_cent_higher(value: object) -> Decimal:
    """Move an exact money value by one cent, the smallest change its column admits.

    Parameters
    ----------
    value : object
        A read-back exact decimal.

    Returns
    -------
    Decimal
        The same amount plus one cent, at the same scale.

    Raises
    ------
    TypeError
        Propagated if the value is not a decimal.
    """
    assert isinstance(value, Decimal)
    return value + Decimal("0.01")


def _one_day_later(value: object) -> datetime.date:
    """Move a calendar day to the next one, the smallest change a DATE column admits.

    Parameters
    ----------
    value : object
        A read-back calendar day.

    Returns
    -------
    datetime.date
        The following day.

    Raises
    ------
    TypeError
        Propagated if the value is not a date.
    """
    assert isinstance(value, datetime.date)
    return value + datetime.timedelta(days=1)


def _one_microsecond_later(value: object) -> datetime.datetime:
    """Move an instant by one microsecond, the smallest change a TIMESTAMP(6) column admits.

    Parameters
    ----------
    value : object
        A read-back instant.

    Returns
    -------
    datetime.datetime
        The instant one microsecond later.

    Raises
    ------
    TypeError
        Propagated if the value is not an instant.
    """
    assert isinstance(value, datetime.datetime)
    return value + datetime.timedelta(microseconds=1)


# Assumptions: the one optional span exercised for absence is the middle name, because it is the
#   plainest case the nullable projection exists for -- ``V1__account.sql`` declares the column
#   nullable and ``TRIMMED_OR_NULL`` is declared against exactly this field -- and naming one field
#   rather than looping over the four keeps the case readable as "a customer with no middle name".
_OPTIONAL_NAME_FIELD = "CUST-MIDDLE-NAME"


# Assumptions: the customer casts are declared ONCE and shared by both customer cases, so the two
#   cannot come to disagree about which of that record's columns a driver returns natively. Three
#   columns do: the identifier and the score are integer columns and the date of birth is a DATE.
_CUSTOMER_CASTS = MappingProxyType(
    {
        "CUST-ID": _as_integer,
        "CUST-FICO-CREDIT-SCORE": _as_integer,
        "CUST-DOB-YYYY-MM-DD": _as_date,
    }
)


@dataclass(frozen=True)
class _PreparedPair:
    """One prepared record with the descriptors a comparison of it needs.

    Purpose
    -------
    Carry the four things every case below passes to the comparator together, so a case reads as
    the comparison it is making rather than as four lookups.

    Parameters
    ----------
    layout : layouts.RecordSpec
        The record descriptor, which supplies each field's declared regime and width.
    target : TableTarget
        The load target, which supplies the qualified table and the comparable field set.
    fields : tuple[str, ...]
        The compared field order, with every run-clock stamp already excluded.
    prepared : Mapping[str, object]
        The source side: one record with every projection applied.

    Raises
    ------
    None
        Construction validates nothing; every component was produced by the machinery that owns it.
    """

    layout: layouts.RecordSpec
    target: TableTarget
    fields: tuple[str, ...]
    prepared: Mapping[str, object]


class _FixedDataKeys:
    """Answer every data-key request with one fixed pair, so a sealed target can be prepared.

    Purpose
    -------
    Let the customer record be projected without a key-management service, since two of its
    columns declare a sealing projection and ``prepare_record`` builds every mapped column -- not
    only the comparable ones -- so it refuses outright without a cipher. The pair is synthetic, it
    protects nothing, and nothing enciphered with it is ever compared: both sealed columns are
    excluded from the compared span by the target's own ``comparable_fields``.
    """

    def data_key(self, *, key_id: str, encryption_context: Mapping[str, str]) -> DataKey:
        """Return the one fixed key pair, ignoring the key and the context.

        Parameters
        ----------
        key_id : str
            The customer-managed key the caller would have used. Accepted and ignored.
        encryption_context : Mapping[str, str]
            The binding the caller would have authenticated. Accepted and ignored.

        Returns
        -------
        DataKey
            A fixed plaintext/wrapped pair.

        Raises
        ------
        None
            Returning a constant cannot fail.
        """
        # WHY : Assumptions: fixed bytes rather than random ones, because a random key would make
        #   the envelope differ between two runs for a reason unrelated to anything under test.
        #   The envelope still differs between two seals of the same value -- the initialisation
        #   vector is drawn per value by the cipher -- which is exactly why the comparisons below
        #   are taken over the comparable field set and never over the whole row.
        del key_id, encryption_context
        return DataKey(plaintext=bytes(range(32)), wrapped=b"synthetic-wrapped-key")


def _transaction_record() -> dict[str, object]:
    """Build one transaction record at the widths its layout declares.

    Purpose
    -------
    Supply a source record for the ONE loadable master that ships no extract.
    ``readers/transaction`` declares itself unseeded -- there is no
    ``app/data/ASCII/transact.txt`` and no EBCDIC twin, because ``app/jcl/TRANFILE.jcl`` primes that
    cluster from a single initializer record -- so this shape is constructed rather than read, and
    every span is at its declared width so the
    projections it drives are the ones a real record would drive.

    Returns
    -------
    dict[str, object]
        One record keyed by copybook field name, with money as an exact decimal and both stamps in
        the space-separated 26-character spelling.

    Raises
    ------
    None
        Building a literal cannot fail.
    """
    # WHY : Assumptions: the descriptive spans are padded to their declared widths and the two
    #   trimmed columns therefore hold trailing blanks, which is what makes the `TRIMMED` projection
    #   actually do something in this case. A record built with pre-trimmed values would compare
    #   equal whether the projection ran or not.
    return {
        "TRAN-ID": "0000000000000001",
        "TRAN-TYPE-CD": "01",
        "TRAN-CAT-CD": "0001",
        "TRAN-SOURCE": "POS TERM  ",
        "TRAN-DESC": "Purchase at Abshire-Lowe".ljust(100),
        "TRAN-AMT": Decimal("504.77"),
        "TRAN-MERCHANT-ID": "000000123",
        "TRAN-MERCHANT-NAME": "Abshire-Lowe".ljust(50),
        "TRAN-MERCHANT-CITY": "Port Hilpert".ljust(50),
        "TRAN-MERCHANT-ZIP": "0000012345",
        "TRAN-CARD-NUM": "4111111111111111",
        "TRAN-ORIG-TS": "2022-06-10 19:27:53.000000",
        "TRAN-PROC-TS": "2022-06-11 03:11:07.123456",
    }


def _user_record() -> dict[str, object]:
    """Build one security record at the widths its layout declares.

    Purpose
    -------
    Supply a source record for the identity master, whose committed extract exists only in the
    mainframe character set and whose one interesting compared column -- the subject -- is not in
    the record at all but resolved from the published document.

    Returns
    -------
    dict[str, object]
        One record keyed by copybook field name, with both names blank-padded to 20 characters.

    Raises
    ------
    None
        Building a literal cannot fail.
    """
    # WHY : Assumptions: the cleartext password span is deliberately ABSENT from this record rather
    #   than present and ignored. `auth.users` declares no column for it and the target maps none,
    #   so a record carrying one would suggest the migration had somewhere to put it.
    return {
        "SEC-USR-ID": "ADMIN001",
        "SEC-USR-FNAME": "Admin".ljust(20),
        "SEC-USR-LNAME": "User".ljust(20),
        "SEC-USR-TYPE": "A",
    }


def _first_prepared_from_record(
    layout_name: str, record: Mapping[str, object], context: LoadContext | None
) -> _PreparedPair:
    """Project one constructed record for its target.

    Purpose
    -------
    Serve the two records that have no shipped extract to read, through the same projection path
    :func:`_first_prepared` uses for the ones that do, so a case over either kind asserts the same
    thing.

    Parameters
    ----------
    layout_name : str
        The record layout, which is also the target name.
    record : Mapping[str, object]
        The constructed record, keyed by copybook field name.
    context : LoadContext | None
        The collaborators the target's projections need, or ``None`` when it declares none.

    Returns
    -------
    _PreparedPair
        The layout descriptor, the target, the compared field order and the prepared record.

    Raises
    ------
    AuroraLoadError
        Propagated if a projection cannot be applied to the constructed record, which would mean the
        record is not shaped as the layout declares.
    """
    layout = layouts.layout(layout_name)
    target = target_for(layout_name)
    return _PreparedPair(
        layout=layout,
        target=target,
        fields=deterministic_field_names(layout, target.comparable_fields()),
        prepared=prepare_record(target, record, context),
    )


def _customer_context() -> LoadContext:
    """Build the one collaborator the customer target's projections need.

    Purpose
    -------
    Supply the identifier cipher, without which ``prepare_record`` refuses the customer record
    outright -- it builds every mapped column, and two of them declare a sealing projection. Both
    are excluded from the compared span, so nothing this cipher produces is ever compared.

    Returns
    -------
    LoadContext
        A context carrying the identifier cipher over the fixed key source and nothing else, because
        the customer target declares no other non-mechanical projection.

    Raises
    ------
    None
        Constructing the bundle cannot fail.
    """
    # WHY : Assumptions: the verification-value cipher is deliberately NOT supplied. It belongs to
    #   the card target, and a context carrying collaborators a target does not declare would leave
    #   a reader unable to tell which of them this record actually needs.
    return LoadContext(
        identifier_cipher=CustomerIdentifierCipher(key_id="synthetic-key", keys=_FixedDataKeys())
    )


def _first_prepared(layout_name: str, extract: Path, context: LoadContext | None) -> _PreparedPair:
    """Decode the first record of one shipped extract and project it for its target.

    Purpose
    -------
    Produce the SOURCE side of a comparison the way the load path produces it -- the shipped
    extract, the real reader, the real projections -- so that a case asserts what a load would
    actually have sent rather than what this file believes it would.

    Parameters
    ----------
    layout_name : str
        The record layout, which is also the target name.
    extract : Path
        The shipped extract to read the first record of.
    context : LoadContext | None
        The collaborators the target's projections need, or ``None`` when it declares none.

    Returns
    -------
    _PreparedPair
        The layout descriptor, the target, the compared field order and the prepared record.

    Raises
    ------
    AuroraLoadError
        Propagated if a projection cannot be applied.
    OSError
        Propagated if the extract cannot be read.
    """
    layout = layouts.layout(layout_name)
    record = next(iter(RecordReader(layout).read_ascii(extract)))
    target = target_for(layout_name)
    return _PreparedPair(
        layout=layout,
        target=target,
        fields=deterministic_field_names(layout, target.comparable_fields()),
        prepared=prepare_record(target, record, context),
    )


def _compared_pair(pair: _PreparedPair, row: Mapping[str, object]) -> ChecksumComparison:
    """Compare one prepared record against one simulated read-back row.

    Parameters
    ----------
    pair : _PreparedPair
        The source side and the descriptors, as :func:`_first_prepared` returns them.
    row : Mapping[str, object]
        The read-back side, keyed by the same field names and holding the types the columns
        declare.

    Returns
    -------
    ChecksumComparison
        The outcome of comparing the two, over the compared field order.

    Raises
    ------
    ChecksumValueError
        Propagated if either side holds a value outside the admitted domain.
    """
    # WHY : Assumptions: the layout is supplied, because it is what reconciles a read-back integer
    #   against the fixed-width display field that fed it. A case that omitted it would be
    #   asserting a property of a comparison no shipped caller makes -- `cli.py` always supplies
    #   one -- and every integer column would then differ.
    return compare_record_digests(
        record_name=pair.target.record,
        qualified_table=pair.target.qualified_name,
        source_records=[pair.prepared],
        target_records=[row],
        fields=pair.fields,
        layout=pair.layout,
    )


def _read_back_of(
    pair: _PreparedPair, casts: Mapping[str, Callable[[object], object]]
) -> dict[str, object]:
    """Build the row a driver would return for one prepared record.

    Purpose
    -------
    Apply, per field, the cast PostgreSQL performs on the way in and the driver performs on the way
    out -- ``BIGINT`` to ``int``, ``DATE`` to ``date``, ``TIMESTAMP(6)`` to ``datetime``, ``UUID``
    to ``UUID`` -- so a case compares two representations of ONE row rather than a row against
    itself.

    Parameters
    ----------
    pair : _PreparedPair
        The prepared record and the compared field order.
    casts : Mapping[str, Callable[[object], object]]
        The cast to apply per field name. A field with no entry is returned unchanged, which is
        correct for every ``CHAR``, ``VARCHAR`` and ``NUMERIC`` column.

    Returns
    -------
    dict[str, object]
        The simulated read-back row, carrying exactly the compared field names.

    Raises
    ------
    ValueError
        Propagated from a cast handed a value it cannot parse, which would mean the prepared record
        is not in the form the column accepts -- a defect in the projection rather than in the cast.
    """
    return {name: casts.get(name, _unchanged)(pair.prepared[name]) for name in pair.fields}


def test_an_account_record_verifies_against_its_native_integer_and_date_columns() -> None:
    """Verify a correct account load whose target declares a BIGINT and three DATE columns.

    Purpose
    -------
    Assert the property the comparator was delivered without: that the source's fixed-width
    characters and the driver's native values are recognised as the SAME row. ``ACCT-ID`` is
    ``PIC 9(11)`` holding ``00000000001`` and ``account_id`` is ``BIGINT`` holding 1; the three date
    fields are ``PIC X(10)`` and their columns are ``DATE``. Before the tagged encoding this
    comparison could not be made at all -- it refused at the first read-back value.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the comparison does not verify, or reports any difference.
    """
    pair = _first_prepared("ACCOUNT", _ACCOUNT_EXTRACT, None)
    row = _read_back_of(
        pair,
        {
            "ACCT-ID": _as_integer,
            "ACCT-OPEN-DATE": _as_date,
            "ACCT-EXPIRAION-DATE": _as_date,
            "ACCT-REISSUE-DATE": _as_date,
        },
    )

    comparison = _compared_pair(pair, row)

    assert comparison.verified
    assert comparison.differing_records == 0
    assert comparison.differences == ()
    # WHY : both digests are asserted equal as well as the verdict, because the verdict is computed
    #   from the located differences and the digests from the same per-field renderings. Two
    #   readings of one computation agreeing is what makes the report and the verdict consistent;
    #   asserting only one of them would leave the other free to disagree.
    assert comparison.source.digest == comparison.target.digest


@pytest.mark.parametrize(
    ("field", "corruption"),
    [
        ("ACCT-ID", _one_higher),
        ("ACCT-CURR-BAL", _one_cent_higher),
        ("ACCT-OPEN-DATE", _one_day_later),
    ],
    ids=["integer-column", "money-column", "date-column"],
)
def test_a_corrupted_account_column_is_still_localised_after_reconciliation(
    field: str, corruption: Callable[[object], object]
) -> None:
    """Localise a one-value corruption in each of the three reconciled representations.

    Purpose
    -------
    Assert the reconciliation is a translation and NOT a blanket pass. Rendering a driver's integer
    to the display field's width, and its date to the field's characters, is what makes a correct
    load verify -- and the danger of any such normalisation is that it also makes an incorrect one
    verify. Each case here moves exactly one value by the smallest amount its type allows and
    requires the comparison to name that field.

    Parameters
    ----------
    field : str
        The compared field to corrupt on the read-back side.
    corruption : Callable[[object], object]
        The smallest change that type admits: the next integer, one more cent, the next day.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the comparison verifies, or does not name the corrupted field.
    """
    pair = _first_prepared("ACCOUNT", _ACCOUNT_EXTRACT, None)
    row = _read_back_of(
        pair,
        {
            "ACCT-ID": _as_integer,
            "ACCT-OPEN-DATE": _as_date,
            "ACCT-EXPIRAION-DATE": _as_date,
            "ACCT-REISSUE-DATE": _as_date,
        },
    )
    row[field] = corruption(row[field])

    comparison = _compared_pair(pair, row)

    assert not comparison.verified
    assert comparison.differing_records == 1
    assert [difference.field_name for difference in comparison.differences] == [field]
    assert comparison.source.digest != comparison.target.digest


def test_a_customer_record_verifies_with_its_integer_credit_score() -> None:
    """Verify a correct customer load carrying the SMALLINT score the finding names.

    Purpose
    -------
    Assert the value the finding cites as the one that already broke the comparator. The credit
    score is ``PIC 9(03)`` loading into ``fico_credit_score SMALLINT``, so a driver returns an
    ``int`` where the source holds three characters -- and this record additionally carries two
    sealed columns, which must stay outside the compared span because their envelopes draw a fresh
    initialisation vector per value.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the comparison does not verify, or if a sealed column is inside the compared span.
    """
    pair = _first_prepared("CUSTOMER", _CUSTOMER_EXTRACT, _customer_context())
    row = _read_back_of(pair, _CUSTOMER_CASTS)

    comparison = _compared_pair(pair, row)

    assert comparison.verified
    assert comparison.differences == ()
    # WHY : the score is asserted to have been an INTEGER on the read-back side and characters on
    #   the source side, so this case cannot pass by both sides happening to hold the same type. It
    #   is the pairing that is under test, not the verdict.
    assert isinstance(row["CUST-FICO-CREDIT-SCORE"], int)
    assert isinstance(pair.prepared["CUST-FICO-CREDIT-SCORE"], str)
    # WHY : the sealed columns are asserted ABSENT from the compared span. Including one would make
    #   this comparison fail on every run against a load that was correct -- and a case that had to
    #   exclude them by hand would be asserting its own bookkeeping rather than the target's
    #   declaration.
    assert "CUST-SSN" not in pair.fields
    assert "CUST-GOVT-ISSUED-ID" not in pair.fields


def test_a_customer_with_no_middle_name_verifies_with_the_column_absent_on_both_sides() -> None:
    """Verify a customer whose optional span is blank, and localise an empty string in its place.

    Purpose
    -------
    Exercise the absent domain through the COMPARATOR rather than through a digest of a hand-built
    mapping. ``middle_name`` is nullable and its field declares ``TRIMMED_OR_NULL`` precisely so
    that "no middle name" and "a middle name of zero characters" are not one stored value, and the
    second half of this case is what proves the encoding keeps them apart where it matters: a
    read-back holding an empty string against a source holding absence must be reported.

    Refactoring Rationale: the blank span is imposed on a shipped record rather than found in
    one, because it cannot be found -- measured across all 50 records of
    ``app/data/ASCII/custdata.txt``, every one populates every nullable span, so no shipped record
    reaches the projection's null arm at all. The record still travels the real reader and the real
    projection; only the one span is blanked, to its declared width.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the blank span is not projected to absence, if the comparison does not verify, or if an
        empty string in its place is not localised.
    """
    layout = layouts.layout("CUSTOMER")
    record = dict(next(iter(RecordReader(layout).read_ascii(_CUSTOMER_EXTRACT))))
    blank_field = next(field for field in layout.fields if field.name == _OPTIONAL_NAME_FIELD)
    record[_OPTIONAL_NAME_FIELD] = " " * blank_field.length
    pair = _first_prepared_from_record("CUSTOMER", record, _customer_context())
    row = _read_back_of(pair, _CUSTOMER_CASTS)

    assert pair.prepared[_OPTIONAL_NAME_FIELD] is None
    assert row[_OPTIONAL_NAME_FIELD] is None
    assert _compared_pair(pair, row).verified

    row[_OPTIONAL_NAME_FIELD] = ""
    empty_instead = _compared_pair(pair, row)

    assert not empty_instead.verified
    assert [difference.field_name for difference in empty_instead.differences] == [
        _OPTIONAL_NAME_FIELD
    ]


def test_a_transaction_record_verifies_with_its_native_stamp_beside_a_padded_code() -> None:
    """Verify a correct transaction load carrying a TIMESTAMP(6) and a CHAR(4) coded field.

    Purpose
    -------
    Assert the two remaining representation pairs at once, and in the one record where they
    conflict. ``TRAN-ORIG-TS`` is ``PIC X(26)`` loading into ``TIMESTAMP(6)``, so the driver
    returns a ``datetime`` that has to render back to the 26 characters that were sent. And
    ``TRAN-CAT-CD`` is
    an unsigned display field loading into ``CHAR(4)``, so its read-back is the PADDED characters --
    which is why a read-back integer is padded to the field's width rather than the source's zeros
    being stripped. Stripping would have verified this record's ``TRAN-MERCHANT-ID`` and broken its
    category code; padding verifies both.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the comparison does not verify, or if the category code is not the padded form.
    """
    pair = _first_prepared_from_record("TRAN", _transaction_record(), None)
    row = _read_back_of(
        pair,
        {
            "TRAN-MERCHANT-ID": _as_integer,
            "TRAN-ORIG-TS": _as_timestamp,
        },
    )

    comparison = _compared_pair(pair, row)

    assert comparison.verified
    assert comparison.differences == ()
    # WHY : the padded spelling is asserted on BOTH sides, because it is the whole reason the
    #   integer reconciliation pads rather than strips. A comparison that verified while the two
    #   sides held `1` and `0001` would mean the encoding had normalised the difference away.
    assert pair.prepared["TRAN-CAT-CD"] == "0001"
    assert row["TRAN-CAT-CD"] == "0001"
    # WHY : the excluded stamp is asserted excluded. `TRAN-PROC-TS` is marked as a run-clock stamp,
    #   so it must not be inside the compared span -- and it must still be present on the read-back
    #   row, because the validate-before-blank check reaches it there.
    assert "TRAN-PROC-TS" not in pair.fields


def test_a_transaction_stamp_one_microsecond_out_is_localised() -> None:
    """Localise a stamp that differs by the smallest interval its column can hold.

    Purpose
    -------
    Assert the timestamp rendering does not discard precision. A rendering that dropped the
    fractional part -- or rendered through a shorter directive -- would make two instants a
    microsecond apart digest identically, and a load that truncated every stamp would verify.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the comparison verifies, or does not name the stamp.
    """
    pair = _first_prepared_from_record("TRAN", _transaction_record(), None)
    row = _read_back_of(pair, {"TRAN-MERCHANT-ID": _as_integer, "TRAN-ORIG-TS": _as_timestamp})
    row["TRAN-ORIG-TS"] = _one_microsecond_later(row["TRAN-ORIG-TS"])

    comparison = _compared_pair(pair, row)

    assert not comparison.verified
    assert [difference.field_name for difference in comparison.differences] == ["TRAN-ORIG-TS"]


def test_a_user_record_verifies_its_subject_against_the_driver_s_uuid() -> None:
    """Verify a correct user load whose one derived column is a UUID.

    Purpose
    -------
    Assert the last cross-representation pair. ``auth.users.cognito_sub`` is ``UUID``, so a driver
    returns ``uuid.UUID`` where the source side holds the canonical string the identity provider
    published -- and that field has no copybook descriptor at all, being derived, so the comparison
    has to render it with no field to consult.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the comparison does not verify, or if the subject was not compared.
    """
    pair = _first_prepared_from_record(
        "SECUSER", _user_record(), LoadContext(subjects={"ADMIN001": _SUBJECT})
    )
    row = _read_back_of(pair, {"cognito_sub": _as_uuid})

    comparison = _compared_pair(pair, row)

    assert comparison.verified
    assert comparison.differences == ()
    assert "cognito_sub" in pair.fields
    assert isinstance(row["cognito_sub"], uuid.UUID)


def test_a_subject_belonging_to_another_user_is_localised() -> None:
    """Localise a subject that does not match the one published for the user.

    Purpose
    -------
    Assert the UUID rendering compares the whole identifier rather than its presence. The column is
    ``NOT NULL UNIQUE``, so a row carrying the wrong subject is a user bound to another identity --
    the one defect in this record that no row count and no total can see.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the comparison verifies, or does not name the subject column.
    """
    pair = _first_prepared_from_record(
        "SECUSER", _user_record(), LoadContext(subjects={"ADMIN001": _SUBJECT})
    )
    row = _read_back_of(pair, {"cognito_sub": _as_uuid})
    row["cognito_sub"] = uuid.UUID("00000000-0000-4000-8000-000000000002")

    comparison = _compared_pair(pair, row)

    assert not comparison.verified
    assert [difference.field_name for difference in comparison.differences] == ["cognito_sub"]


@pytest.mark.parametrize(
    ("left", "right"),
    [
        (Decimal("5"), "5"),
        (Decimal("5"), b"5"),
        ("5", b"5"),
        (None, b""),
    ],
    ids=[
        "decimal-vs-text",
        "decimal-vs-bytes",
        "text-vs-bytes",
        "absent-vs-empty-bytes",
    ],
)
def test_values_from_different_domains_never_digest_alike(left: object, right: object) -> None:
    """Refuse to render two values from different domains to the same bytes.

    Purpose
    -------
    Assert the reason the encoding carries a domain tag at all. Untagged, an exact five, the
    character five and the byte five all rendered to ``b"5"``, so a load that stored a money
    column's value as text -- or a text column's value as a number -- digested identically to a
    correct one.

    Parameters
    ----------
    left : object
        One value.
    right : object
        A value from a different domain that would previously have rendered to the same bytes.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the two digest alike.
    """
    # WHY : an integer and its display spelling are deliberately NOT in this list. They are one
    #   domain by design -- the case below states why -- so including them here would assert the
    #   opposite of the reconciliation the comparator depends on.
    assert digest_of_record({"VALUE": left}, ("VALUE",)) != digest_of_record(
        {"VALUE": right}, ("VALUE",)
    )


def test_money_parity_descriptors() -> None:
    """Derive the sensitivity census of the totalled money columns from the descriptors themselves.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If a totalled money column other than the published disclosure-group rate is not marked
        sensitive, or if that rate is.
    """
    # WHY : Refactoring Rationale: this case exists because the module's own documentation carried a
    #   hand-maintained fraction -- "five of the nine money fields are themselves marked
    #   sensitive" -- twice, and both statements were wrong: EIGHT of the nine descriptors mark
    #   their field sensitive. A wrong privacy census is not a cosmetic defect, because it is the
    #   stated justification for withholding every value from this module's diagnostics, and a
    #   reader who checked the number and found it false has no reason to trust the rule either.
    #   The prose now states the RULE rather than a count, and this case is what holds the
    #   descriptors to it -- `money_parity.py` cites this case BY NAME for exactly that purpose, so
    #   deleting it would leave that citation naming nothing.
    # WHY : Assumptions: the exclusion is named rather than counted. DIS-INT-RATE is a published
    #   product term held against a group of accounts and not against any one cardholder, which is
    #   the same reasoning `test_every_money_and_identifier_field_is_classified_sensitive` records
    #   at the layouts module; asserting the NAME means adding a money column cannot quietly widen
    #   the exclusion the way a count could.
    # WHY : Assumptions: the census is read through `declared_money_columns()` rather than through a
    #   module-level constant. An earlier draft read a `MONEY_COLUMNS` constant built at
    #   IMPORT time; that constant was withdrawn so that a defect in the derivation cannot fail at
    #   import inside `cli.py`, which imports the passes merely to register its subcommands. The
    #   accessor is the memoised equivalent and is the only spelling that still exists.
    published_rate = "DIS-INT-RATE"
    descriptors = declared_money_columns()
    unmarked = sorted(
        f"{table}.{column}"
        for (table, column), descriptor in descriptors.items()
        if not descriptor.field.sensitive and descriptor.field.name != published_rate
    )
    assert not unmarked, (
        "every money column this pass totals must be marked sensitive by its own descriptor,"
        f" because that classification is what the withheld diagnostics rest on; {unmarked} is not"
    )

    rates = [
        f"{table}.{column}"
        for (table, column), descriptor in descriptors.items()
        if descriptor.field.name == published_rate
    ]
    assert len(rates) == 1, (
        "exactly one totalled column is expected to carry the published disclosure-group rate,"
        f" and the descriptors name {rates}"
    )
    assert not descriptors[("reference.disclosure_groups", "interest_rate")].field.sensitive, (
        "the disclosure-group rate is the one totalled column deliberately NOT marked sensitive;"
        " marking it would make the module's stated rule false in the other direction"
    )
