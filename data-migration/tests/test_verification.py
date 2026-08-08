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
    Every table the row-count query names is checked against the Flyway migrations the owning
    services ship. That is the only assertion available that can catch the verification query
    and the schema drifting apart: both are text, neither imports the other, and a query
    naming a table that does not exist fails at the moment an operator runs it to decide
    whether a migration succeeded -- the worst possible moment to discover a typo.

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
_SERVICES_ROOT = Path(__file__).resolve().parents[2] / "services"

# Assumptions: the twelve tables the row-count query covers are stated literally so that a
#   table dropping out of the query is a visible edit here. Reading them back out of the SQL
#   would make the assertion true of any query, including one covering a single table.
_COUNTED_TABLES = (
    ("account", "accounts"),
    ("account", "customers"),
    ("account", "card_xref"),
    ("card", "cards"),
    ("ledger", "transactions"),
    ("ledger", "daily_transactions"),
    ("ledger", "transaction_category_balances"),
    ("ledger", "transaction_rejects"),
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
    lines = (_SQL_ROOT / name).read_text(encoding="utf-8").splitlines()
    executable = "\n".join(line for line in lines if not line.lstrip().startswith("--"))
    assert executable.strip(), f"{name} holds no executable statement"
    return executable


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
    """Count each of the twelve migrated tables, with an exact aggregate and no estimate.

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
        If the query omits the table, or the table is created by no shipped migration.
    """
    sql = _executable_sql("row_counts.sql")
    assert re.search(rf"\bFROM {schema}\.{table}\b", sql), f"{schema}.{table} is not counted"
    # WHY : the table's existence in a shipped migration is asserted in the SAME test as its
    #   presence in the query, so the two can never be satisfied separately. A query naming a
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
        If the query references an estimate, or counts fewer tables than it declares.
    """
    sql = _executable_sql("row_counts.sql")
    assert "reltuples" not in sql
    assert sql.count("COUNT(*)") == len(_COUNTED_TABLES)
    # WHY : the number of UNION ALL joins is one fewer than the number of counted tables, which
    #   is what proves the branches are joined into a single result set rather than a file of
    #   statements an operator has to run one at a time and could stop halfway through.
    assert sql.count("UNION ALL") == len(_COUNTED_TABLES) - 1


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
        If the column is totalled without a coalesce, or is declared by no migration.
    """
    sql = _executable_sql("money_totals.sql")
    assert f"COALESCE(SUM({column}), 0)" in sql, f"{column} is not totalled with a coalesce"
    assert re.search(rf"\bFROM {re.escape(qualified_table)}\b", sql)
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
        If the query holds a sum that is not coalesced, or a different number of sums than the
        nine columns declared.
    """
    sql = _executable_sql("money_totals.sql")
    total_sums = sql.count("SUM(")
    coalesced = sql.count("COALESCE(SUM(")
    # WHY : the two counts are compared rather than each checked against nine, so a tenth
    #   column added without a coalesce fails here even though every one of the nine named
    #   above is still correct. That is the regression this test exists to catch, and a
    #   per-column assertion alone cannot see it.
    assert total_sums == coalesced == len(_TOTALLED_COLUMNS)


def test_both_verification_queries_stop_on_the_first_error() -> None:
    """Set the psql error switch in both queries, so a failed branch cannot be scrolled past.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If either file omits the switch.
    """
    for name in ("row_counts.sql", "money_totals.sql"):
        sql = _executable_sql(name)
        # WHY : without this switch psql reports the failing statement and carries on, so a
        #   verification run against a schema missing one table prints an error among a screen
        #   of successful counts and exits zero. An operator reading the tail of that output
        #   would conclude the load verified.
        assert "\\set ON_ERROR_STOP on" in sql, f"{name} does not stop on error"
