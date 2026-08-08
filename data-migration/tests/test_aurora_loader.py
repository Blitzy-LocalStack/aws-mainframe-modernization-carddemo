"""Prove the Aurora bulk loader loads exactly what it declares, atomically, and privately.

Purpose
-------
Establish the four properties a bulk load into a live cluster depends on and which nothing
else in this suite covers: that every declared target names columns the owning service's
migration really creates, that the COPY statement names its columns explicitly rather than
relying on declared table order, that a mapping failure rolls the transaction back before it
propagates, and that no diagnostic on the failure path carries a field VALUE. A loader
missing any one of them fails in a way that is either silent or unsafe -- values in the
wrong columns, a half-loaded table, or a national identifier in a retained log.

Alternatives Considered:
    Two were evaluated. (1) Running against the container database the setup log describes
    was rejected: it makes the suite depend on a running service and a credential, where
    the recording double the shared ``conftest`` already publishes lets every statement the
    loader issues be asserted exactly -- including the statements it must NOT issue, which a
    real connection can only refuse rather than record. (2) Asserting the COPY statement as
    one literal string per target was rejected in favour of asserting its SHAPE plus the
    column list read from the target, because a literal would have to be rewritten whenever
    a column was added and the rewrite is where an implicit column order slips back in.

Assumptions:
    The column names each target declares are checked against the Flyway migrations the
    owning services ship, read from disk. That cross-tree assertion is the only thing that
    can catch the loader and the schema drifting apart, since both are text and neither
    imports the other. A load that named a column no table had would otherwise be
    discovered by the first real deployment.

Trade-offs:
    Atomicity is asserted through the double's rollback and commit counters rather than by
    observing a partially populated table. The counters cannot prove the ORDER of the
    rollback against the raise directly; what they do prove is that a rollback happened at
    all on a path that ended in an exception, which is the property that distinguishes a
    loader leaving a clean transaction from one leaving an open one for the connection pool.
"""

from __future__ import annotations

import re
from collections.abc import Iterator
from decimal import Decimal
from pathlib import Path
from typing import TYPE_CHECKING

import pytest

from carddemo_migration.config import quote_identifier
from carddemo_migration.copybook import layouts
from carddemo_migration.loaders.aurora import (
    AuroraLoadError,
    TableTarget,
    load_records,
    target_for,
    target_names,
)

if TYPE_CHECKING:
    from conftest import FakeAuroraDatabase

# Assumptions: the five loadable records are stated literally, in declaration order, so a
#   target appearing or disappearing is a visible edit here rather than a silent change in
#   what a migration run covers. Reading the mapping back would make this assertion true of
#   any mapping at all, including an empty one.
_LOADABLE_RECORDS = ("XREF", "TRANTYPE", "TRANCAT", "DISGROUP", "ACCOUNT")

# Assumptions: these two records are registered, have readers, and deliberately have NO load
#   target in this module. Their tables declare ciphertext columns as NOT NULL -- the customer
#   table's national and government identifiers, the card table's verification value -- and the
#   key that produces that ciphertext belongs to the owning service, not to this package. They
#   are asserted as an explicit refusal because a loader that inserted plaintext into a column
#   named `*_encrypted` would succeed and be wrong in the worst available way.
_CIPHER_BOUND_RECORDS = ("CUSTOMER", "CARD")

# Assumptions: the migrations are located relative to this file rather than through an
#   installed distribution, because they are resources of the SERVICE modules and are not
#   packaged with this one. The suite therefore reads the same text a reviewer edits.
_SERVICES_ROOT = Path(__file__).resolve().parents[2] / "services"

_COPY_SHAPE = re.compile(
    r'\ACOPY "(?P<schema>[^"]+)"\."(?P<table>[^"]+)" \((?P<columns>[^)]*)\) FROM STDIN\Z'
)


def _migration_text() -> str:
    """Concatenate every service Flyway migration into one searchable body of DDL.

    Purpose
    -------
    Give the column-existence assertion one text to search, so a target's column is accepted
    when ANY owning service declares it and no assertion has to know which service owns which
    schema.

    Returns
    -------
    str
        Every ``V*.sql`` under ``services/*/src/main/resources/db/migration``, joined.

    Raises
    ------
    AssertionError
        If no migration file is found at all, which would mean the path had moved and the
        assertion below was searching an empty string -- passing while proving nothing.
    """
    files = sorted(_SERVICES_ROOT.glob("*/src/main/resources/db/migration/V*.sql"))
    assert files, f"no Flyway migration found beneath {_SERVICES_ROOT}"
    return "\n".join(path.read_text(encoding="utf-8") for path in files)


def test_the_declared_targets_are_exactly_the_five_loadable_records() -> None:
    """Declare a load target for exactly the five records this module can load.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the declared target set differs from the stated one in either direction.
    """
    assert target_names() == _LOADABLE_RECORDS


@pytest.mark.parametrize("record", _LOADABLE_RECORDS)
def test_every_target_maps_only_fields_its_reader_publishes(record: str) -> None:
    """Map only fields the record's own layout declares and its reader loads.

    Parameters
    ----------
    record : str
        One loadable record name.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If a mapped field is not declared by the layout, or is one the reader drops as padding.
    """
    target = target_for(record)
    layout = layouts.LAYOUTS[record]
    declared = {field.name for field in layout.fields}
    for field_name in target.columns:
        assert field_name in declared, f"{record} target maps undeclared field {field_name}"
        # WHY : a mapped field is asserted NOT to be padding. Padding is dropped by every
        #   reader, so a target naming it would raise the "missing mapped field" error on the
        #   very first record -- a load that could never succeed, declared as if it could.
        assert not layout.field(field_name).name.endswith("FILLER")


@pytest.mark.parametrize("record", _LOADABLE_RECORDS)
def test_every_target_column_exists_in_a_shipped_migration(record: str) -> None:
    """Name only columns the owning service's Flyway migration really creates.

    Parameters
    ----------
    record : str
        One loadable record name.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If a mapped column name appears in no migration, or the target's table does not.
    """
    ddl = _migration_text()
    target = target_for(record)
    assert re.search(rf"\b{target.schema}\.{target.table}\b", ddl), (
        f"{target.schema}.{target.table} is created by no shipped migration"
    )
    for column in target.columns.values():
        # WHY : Trade-offs: the search is for the column NAME as a word anywhere in the
        #   concatenated DDL, not for its declaration within the right CREATE TABLE. Parsing
        #   the DDL properly would need a SQL parser this suite has no reason to carry, and the
        #   weaker assertion still catches the failure that matters -- a column this loader
        #   names that no migration creates anywhere, which is a load that aborts on its first
        #   COPY. The accepted cost is that a column belonging to a different table would
        #   satisfy it.
        assert re.search(rf"\b{column}\b", ddl), f"column {column} is declared by no migration"


@pytest.mark.parametrize("record", _CIPHER_BOUND_RECORDS)
def test_a_cipher_bound_record_is_refused_with_its_reason(record: str) -> None:
    """Refuse a load target for a record whose table holds ciphertext this package cannot make.

    Parameters
    ----------
    record : str
        One record registered and readable here but deliberately not loadable.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the record has a target, or is refused without naming the ciphertext reason.
    """
    assert record not in target_names()
    with pytest.raises(AuroraLoadError) as refused:
        target_for(record)
    message = str(refused.value)
    # WHY : the REASON is asserted, not merely the refusal. "No target" and "no target because
    #   the columns hold ciphertext produced by a key this package does not hold" are read very
    #   differently by whoever next tries to load the record, and only the second stops them
    #   adding a mapping that would insert plaintext into an encrypted column.
    assert "ciphertext" in message
    assert record in message


def test_an_unregistered_record_has_no_target() -> None:
    """Refuse a target for a name the layout registry does not hold.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If an unknown record name yields a target instead of a refusal.
    """
    with pytest.raises(AuroraLoadError):
        target_for("NO-SUCH-RECORD")


@pytest.mark.parametrize("record", _LOADABLE_RECORDS)
def test_the_copy_statement_names_every_column_explicitly(record: str) -> None:
    """Emit a COPY that lists its columns, quoted, in the target's own declared order.

    Parameters
    ----------
    record : str
        One loadable record name.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the statement omits the column list, quotes nothing, or lists the columns in an
        order other than the target's.
    """
    target = target_for(record)
    matched = _COPY_SHAPE.match(target.copy_statement())
    assert matched, f"{record} COPY does not match the expected shape: {target.copy_statement()}"
    assert matched.group("schema") == target.schema
    assert matched.group("table") == target.table
    # WHY : the ORDER is asserted, not just the membership. A COPY whose column list is a
    #   permutation of the right names loads every value into the wrong column of the right
    #   table, and where the adjacent types agree -- two NUMERIC(11,2) money columns, say -- it
    #   commits successfully. That is the failure an explicit column list exists to prevent, so
    #   the order is the assertion.
    expected = ", ".join(quote_identifier(column) for column in target.columns.values())
    assert matched.group("columns") == expected


def test_row_of_orders_values_by_the_declared_column_order() -> None:
    """Project a decoded record into the tuple order the COPY column list declares.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the projected tuple is not in the target's declared field order.
    """
    target = TableTarget(
        schema="reference",
        table="transaction_types",
        columns={"TRAN-TYPE": "type_cd", "TRAN-TYPE-DESC": "description"},
    )
    # WHY : Assumptions: the record is given with its keys in the OPPOSITE order to the
    #   target's, so a projection that happened to iterate the record instead of the mapping
    #   would produce a reversed tuple and fail. Supplying them in matching order would let
    #   both implementations pass.
    record = {"TRAN-TYPE-DESC": "SYNTHETIC DESCRIPTION", "TRAN-TYPE": "99"}
    assert target.row_of(record) == ("99", "SYNTHETIC DESCRIPTION")


def test_row_of_refuses_a_missing_field_and_renders_no_value() -> None:
    """Refuse a record lacking a mapped field, naming the field and none of the record's values.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the refusal omits the missing field name, or repeats any value the record carried.
    """
    target = TableTarget(
        schema="account",
        table="card_xref",
        columns={
            "XREF-CARD-NUM": "card_num",
            "XREF-CUST-ID": "customer_id",
            "XREF-ACCT-ID": "account_id",
        },
    )
    present = {"XREF-CARD-NUM": "4111111111111111", "XREF-CUST-ID": "000000001"}
    with pytest.raises(AuroraLoadError) as refused:
        target.row_of(present)
    message = str(refused.value)
    assert "XREF-ACCT-ID" in message
    # WHY : the values are asserted ABSENT from the diagnostic. A decoded cross-reference
    #   record carries a primary account number, and this message is written to a log that
    #   outlives the load and is readable by everyone holding log access -- so the field names
    #   are the diagnostic and the values are not part of it.
    for value in present.values():
        assert value not in message, f"{value!r} reached the failure diagnostic"


def test_load_records_copies_every_row_and_commits(fake_aurora: FakeAuroraDatabase) -> None:
    """Copy every supplied record through one COPY and commit once.

    Parameters
    ----------
    fake_aurora : FakeAuroraDatabase
        Recording double for the driver.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the row count returned differs from the records supplied, if the rows are not the
        projected tuples, or if the transaction did not commit exactly once.
    """
    target = target_for("TRANTYPE")
    records = [
        {"TRAN-TYPE": "01", "TRAN-TYPE-DESC": "SYNTHETIC ONE"},
        {"TRAN-TYPE": "02", "TRAN-TYPE-DESC": "SYNTHETIC TWO"},
    ]
    connection = fake_aurora.connect(**_connection_params())
    written = load_records(connection, target, records)
    assert written == len(records)
    assert fake_aurora.copy_statements == [target.copy_statement()]
    # WHY : Assumptions: the double records each copied row PAIRED with the statement it was
    #   written under, so the statement is projected away here rather than the pair being
    #   compared against a bare tuple. Keeping the pair is what lets the assertion below prove
    #   the rows went through the one COPY this target declares and not some other.
    assert [statement for statement, _ in fake_aurora.copied_rows] == [
        target.copy_statement()
    ] * len(records)
    assert [row for _, row in fake_aurora.copied_rows] == [
        ("01", "SYNTHETIC ONE"),
        ("02", "SYNTHETIC TWO"),
    ]
    assert fake_aurora.commits == 1
    assert fake_aurora.rollbacks == 0


def test_load_records_preserves_an_exact_decimal_unrounded(
    fake_aurora: FakeAuroraDatabase,
) -> None:
    """Hand the driver the exact decimal the reader decoded, at its own scale.

    Parameters
    ----------
    fake_aurora : FakeAuroraDatabase
        Recording double for the driver.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the copied value is not the identical decimal, or its scale was normalised away.
    """
    target = target_for("DISGROUP")
    rate = Decimal("-12.30")
    connection = fake_aurora.connect(**_connection_params())
    load_records(
        connection,
        target,
        [
            {
                "DIS-ACCT-GROUP-ID": "SYNTHGRP01",
                "DIS-TRAN-TYPE-CD": "01",
                "DIS-TRAN-CAT-CD": "0005",
                "DIS-INT-RATE": rate,
            }
        ],
    )
    copied = fake_aurora.copied_rows[0][1][-1]
    # WHY : the SCALE is asserted as well as the value, because `Decimal("-12.30")` and
    #   `Decimal("-12.3")` compare equal and are different stored values in a NUMERIC(6,2). A
    #   value-only assertion would pass against a loader that had normalised the scale away,
    #   and the money-parity check would then compare two totals that agreed while the column
    #   held something the extract did not.
    assert copied == rate
    assert copied.as_tuple().exponent == rate.as_tuple().exponent


def test_load_records_rolls_back_before_reraising(fake_aurora: FakeAuroraDatabase) -> None:
    """Roll the transaction back, and never commit, when a record fails partway through.

    Parameters
    ----------
    fake_aurora : FakeAuroraDatabase
        Recording double for the driver.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the failure commits, does not roll back, or copies the failing record.
    """
    target = target_for("TRANTYPE")
    records = [
        {"TRAN-TYPE": "01", "TRAN-TYPE-DESC": "SYNTHETIC ONE"},
        {"TRAN-TYPE": "02"},
    ]
    connection = fake_aurora.connect(**_connection_params())
    with pytest.raises(AuroraLoadError):
        load_records(connection, target, records)
    # WHY : Assumptions: the failure is placed on the SECOND record deliberately, so the
    #   loader has already written one row when it fails. A first-record failure would roll
    #   back an empty transaction, which every implementation gets right; only a partway
    #   failure distinguishes a loader that rolls back from one that leaves a row behind.
    assert [row for _, row in fake_aurora.copied_rows] == [("01", "SYNTHETIC ONE")]
    assert fake_aurora.rollbacks == 1
    assert fake_aurora.commits == 0


def test_load_records_rolls_back_a_decode_failure_and_wraps_it(
    fake_aurora: FakeAuroraDatabase,
) -> None:
    """Roll back and wrap a failure that is not already a load error, such as a decode failure.

    Parameters
    ----------
    fake_aurora : FakeAuroraDatabase
        Recording double for the driver.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the failure commits, does not roll back, or escapes unwrapped.
    """

    def failing_stream() -> Iterator[dict[str, str]]:
        """Yield one good record, then fail the way a truncated extract fails.

        Yields
        ------
        dict[str, str]
            One valid transaction-type record.

        Raises
        ------
        RecordLengthError
            Always, after the first record, standing for the width failure a reader raises on
            a truncated extract.
        """
        yield {"TRAN-TYPE": "01", "TRAN-TYPE-DESC": "SYNTHETIC ONE"}
        raise layouts.RecordLengthError("record 2 of TRANTYPE is 59 characters against 60")

    target = target_for("TRANTYPE")
    connection = fake_aurora.connect(**_connection_params())
    # WHY : this exercises the SECOND of the loader's two failure arms, and it was added
    #   because a mutation that made that arm commit instead of roll back survived a suite
    #   covering only the first. The two arms differ in kind: a missing mapped field is already
    #   an AuroraLoadError and is re-raised, whereas anything a lazy reader raises mid-stream --
    #   a width failure, a decode failure, a driver error -- arrives as a foreign exception and
    #   must be both rolled back AND wrapped, so a caller branching on the load's own error type
    #   still sees it.
    with pytest.raises(AuroraLoadError) as refused:
        load_records(connection, target, failing_stream())
    message = str(refused.value)
    assert "rolled back" in message
    assert "1 row(s)" in message
    assert fake_aurora.rollbacks == 1
    assert fake_aurora.commits == 0


def test_the_loader_issues_no_privileged_statement(fake_aurora: FakeAuroraDatabase) -> None:
    """Issue no schema, role, grant, delete, truncate or index statement while loading.

    Parameters
    ----------
    fake_aurora : FakeAuroraDatabase
        Recording double, which classifies every statement it is given.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the loader issued any statement the double classifies as privileged.
    """
    target = target_for("XREF")
    connection = fake_aurora.connect(**_connection_params())
    load_records(
        connection,
        target,
        [
            {
                "XREF-CARD-NUM": "4111111111111111",
                "XREF-CUST-ID": "000000001",
                "XREF-ACCT-ID": "00000000011",
            }
        ],
    )
    # WHY : this is asserted rather than assumed from the connection role's grants, because the
    #   grants are applied by a bootstrap this suite does not run. The loader's own statement
    #   log is the only evidence available here that it stays inside the DML the role holds --
    #   in particular that it never clears the table before loading it, which would turn a
    #   re-run into silent data loss rather than the duplicate-key failure it should be.
    assert fake_aurora.forbidden_statements() == ()


def _connection_params() -> dict[str, object]:
    """Build the connection parameters the recording double accepts.

    Purpose
    -------
    Keep the verified-TLS requirement in one place, since the double refuses any connection
    that does not request it -- which is the behaviour a real cluster has too.

    Returns
    -------
    dict[str, object]
        Parameters naming a synthetic, unreachable host and requesting full verification.

    Raises
    ------
    None
    """
    # WHY : Assumptions: the host is in the reserved `.invalid` top-level domain so nothing
    #   here can reach a real cluster even if a future edit handed these parameters to the real
    #   driver. `sslmode` is the value `config.REQUIRED_SSL_MODE` fixes, which is why the double
    #   rejects anything weaker rather than accepting a downgrade silently.
    return {
        "host": "aurora.carddemo.invalid",
        "port": 5432,
        "dbname": "carddemo",
        "user": "carddemo_reference",
        "sslmode": "verify-full",
        "sslrootcert": "/nonexistent/synthetic-test-anchor.pem",
    }
