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
"""

from __future__ import annotations

import re
from decimal import Decimal
from pathlib import Path
from typing import TYPE_CHECKING

import pytest

from carddemo_migration.verify.checksum import (
    DIGEST_NAME,
    FIELD_SEPARATOR,
    RECORD_SEPARATOR,
    digest_of_record,
    digest_records,
)
from carddemo_migration.verify.money_parity import (
    MONEY_SCALE,
    compare_money_totals,
    total_source_money,
    total_target_money,
)
from carddemo_migration.verify.row_counts import (
    compare_counts,
    count_source_records,
    count_target_rows,
)

if TYPE_CHECKING:
    from conftest import FakeAuroraDatabase

_SQL_ROOT = Path(__file__).resolve().parents[1] / "sql" / "verify"
_DDL_ROOT = Path(__file__).resolve().parents[1] / "sql"
_SERVICES_ROOT = Path(__file__).resolve().parents[2] / "services"

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
    #   produces, and it is the defect this pass is meant to find, so the separator between
    #   fields has to make the boundary part of what is digested.
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


def test_the_two_separators_are_distinct_bytes() -> None:
    """Frame fields and records with different bytes, so the two framings stay independent.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the field and record separators are the same byte, or either is more than one byte.
    """
    # WHY : if the two separators were the same byte, a record boundary and a field boundary
    #   would be indistinguishable in the digested stream, and the two collision tests above
    #   would both be satisfied while a record split differently across the same total bytes
    #   still digested identically. Distinctness is what makes each framing carry its own
    #   meaning. Single bytes, because a multi-byte separator could itself straddle a boundary.
    assert RECORD_SEPARATOR != FIELD_SEPARATOR
    assert len(RECORD_SEPARATOR) == len(FIELD_SEPARATOR) == 1


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
        If the statement omits the coalesce, or the total is not an exact scaled zero.
    """
    fake_aurora.arrange_rows("SUM(", [(0,)])
    connection = fake_aurora.connect(**_CONNECTION_PARAMS)
    total = total_target_money(connection, "reference", "disclosure_groups", "interest_rate")
    statement = fake_aurora.executed_sql()[-1]
    # WHY : COALESCE is asserted in the statement because SUM over zero rows is NULL, not zero.
    #   Comparing a null against an exact zero reports a difference on an empty table that has
    #   nothing wrong with it, which is the failure mode that trains an operator to ignore the
    #   report.
    assert "COALESCE(SUM(" in statement
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
    #   directory, recorded at V0__schemas_and_roles.sql L130-L135.
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
