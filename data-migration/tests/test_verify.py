"""Prove all three mandatory verification passes fail on the corruption each one exists to catch.

Purpose
-------
``carddemo_migration.verify`` answers one question -- did what arrived in Aurora PostgreSQL match
the extract it came from -- and it answers it three independent ways, because no single check can.
This module is the suite for all three, together with the two paired queries under
``data-migration/sql/verify/`` that two of them execute.

The defining property asserted here is SENSITIVITY, not correctness on good input. A verification
pass that only ever passes proves nothing, so every pass carries at least one injected-corruption
case that asserts a FAILURE, and the corruption injected is the kind that pass exists to catch:

``row_counts`` -- pass 1
    Did the right NUMBER of rows arrive. Judged against the eleven measured seed baselines, with
    the one legitimately unbaselined target reported as not comparable rather than as zero.
    Corruption injected: a row removed from the loaded set, which must fail with the correct
    signed delta naming both the dataset and its target table.
``checksum`` -- pass 2
    Is each row the row it should BE. Judged over normalised field values in layout-declared
    order, digested with SHA-256, with a disagreement located by field name and no field content
    reaching the report. Corruption injected: a single mutated byte in a decoded record, which
    must fail naming the affected field -- while a change confined to a field the layout marks as
    a run-clock stamp must NOT fail, which is what proves the normalisation is applied rather than
    merely configured.
``money_parity`` -- pass 3
    Is the MONEY the same money. Judged as exact totals at scale two AND as strictly-negative row
    counts, over nine columns spanning five tables. Corruption injected: a FLIPPED SIGN, which is
    the defect neither other pass can see and the reason this pass exists.

Beyond the three, the subpackage publishes four invariants and each is asserted here: all three
passes are mandatory and run in a fixed order; every pass is read-only and connects as the
SELECT-only ``carddemo_reporting`` role; the outcome is strictly binary; and the rendered report is
deterministic and timestamp-free, so two runs over unchanged data diff to nothing.

The second pass is additionally exercised as a MECHANISM rather than only as a verdict --
``compare_record_digests``, the key pairing, the timestamp contract, the difference localisation
and the report bound -- because it is the one pass that compares values rather than aggregates, so
a defect in its pairing or its canonicalisation would false-pass or false-fail a cutover gate that
looks green either way.

Assumptions:
    Every value class this module exercises is one a real run produces on one of the two sides,
    and the pairs are named rather than generic. ``psycopg`` returns ``int`` for the ``BIGINT`` and
    ``SMALLINT`` columns, ``None`` for a nullable column holding nothing, ``datetime.date`` for
    ``DATE``, ``datetime.datetime`` for ``TIMESTAMP(6)``, ``uuid.UUID`` for the derived subject
    column and ``Decimal`` for ``NUMERIC(p,2)``; the reader and the loader's projections produce
    characters, exact decimals and raw bytes. A canonicalisation admitting only the second set
    could not verify any dataset carrying an identifier, a nullable column or a stamp.

Assumptions:
    Money is exact fixed point at every hop and never a binary float. Every total, every balance
    and every rate this module names is a :class:`decimal.Decimal`, and nothing here converts one
    to ``float`` even to compare it. AAP rule T3 states the requirement across the whole path; this
    module is where a slip would be most damaging, because a ``float`` would make a genuine parity
    failure read as a rounding artefact and be dismissed as one.

Trade-offs:
    The comparison cases are hand-built pairs while the money-parity inputs are read from the
    committed corpus, and the split is deliberate rather than inconsistent. Sensitivity to a single
    perturbation needs pairs constructed to differ in exactly one respect, which a corpus cannot
    supply; cross-language codec agreement needs the same bytes the COBOL programs and the Java
    shared kernel are run against, which only the corpus supplies. Both are used where each
    proves something the other cannot.
"""

from __future__ import annotations

import ast
import datetime
import hashlib
import pathlib
import re
import uuid
from decimal import Decimal
from typing import TYPE_CHECKING

import pytest

from carddemo_migration.copybook import layouts
from carddemo_migration.copybook.zoned import decode_zoned_field, encode_zoned_field
from carddemo_migration.readers import account as account_reader
from carddemo_migration.readers import dalytran as dalytran_reader
from carddemo_migration.readers import discgrp as discgrp_reader
from carddemo_migration.readers import tcatbal as tcatbal_reader
from carddemo_migration.readers import transaction as transaction_reader

# WHY : Assumptions: the gate is reached through the subpackage BOUNDARY rather than through its own
#   module, because `carddemo_migration.verify` publishes `run_verification_gate`, the gate's error
#   type and the `gate` module itself as part of its declared surface, and that surface is the
#   stable spelling. The pass modules are imported directly for the opposite reason -- each
#   publishes far more than the boundary curates, and these cases assert against constants and
#   result types the curated surface deliberately leaves at their owning module.
from carddemo_migration.verify import (
    PASS_MODULES,
    VerificationGateError,
    gate,
    run_verification_gate,
)
from carddemo_migration.verify.checksum import (
    DIGEST_NAME,
    FRAME_DELIMITER,
    REPORTED_DIFFERENCE_LIMIT,
    ChecksumValueError,
    ChecksumVerificationError,
    DifferenceKind,
    TimestampContractError,
    canonical_key,
    compare_record_digests,
    deterministic_field_names,
    digest_of_record,
    digest_records,
    key_field_names,
    normalized_timestamp_field_names,
    validated_timestamp,
)
from carddemo_migration.verify.money_parity import (
    MONEY_SCALE,
    MONEY_TOTAL_COLUMN_COUNT,
    MONEY_TOTAL_COLUMNS,
    MONEY_TOTAL_TABLE_COUNT,
    MoneyParityVerdict,
    MoneyResultSetContractError,
    MoneyTotalRow,
    SourceExtract,
    SourceMoneyTotal,
    declared_money_columns,
    parse_money_total_rows,
    read_source_totals,
    total_source_column,
    verify_money_total_rows,
    verify_money_totals,
)
from carddemo_migration.verify.money_parity import reporting_role as money_reporting_role
from carddemo_migration.verify.row_counts import (
    NO_DATASET_LABEL,
    ROW_COUNT_COLUMNS,
    SEED_DATASET_BASELINES,
    UNSEEDED_LAYOUT_NAME,
    ResultSetContractError,
    RowCountVerdict,
    RowCountVerificationError,
    VerificationQueryError,
    baseline_for,
    parse_row_count_rows,
    read_row_count_query,
    verify_row_count_rows,
    verify_row_counts,
)
from carddemo_migration.verify.row_counts import reporting_role as count_reporting_role

# WHY : Assumptions: ``conftest`` is imported as a TOP-LEVEL absolute module, which is how every
#   sibling module in this suite reaches the shared doubles -- pytest puts the rootdir on the path,
#   so the sibling resolves without a relative import and without touching ``sys.path``. The
#   reference tree under ``tests/`` is never imported from here: its fixtures are read as vectors
#   and its helpers are reference only, so a dependency on them would couple this package to the
#   parity oracle it is validated against.
# WHY : Assumptions: both names are annotation-only, so they stay behind the type-checking guard --
#   ``from __future__ import annotations`` defers every annotation to a string, and taking them at
#   run time would import a module for a name nothing evaluates.
if TYPE_CHECKING:  # pragma: no cover - imported for annotations only
    from collections.abc import Mapping

    from conftest import FakeAuroraDatabase, FixtureCorpus

#: The account layout, used wherever a test needs a real descriptor rather than an invented one.
#
# Assumptions: ACCOUNT is chosen because it carries one of every regime the canonicalisation has to
#   reconcile -- an unsigned display key that loads into BIGINT, signed display money that loads
#   into NUMERIC(12,2), text dates that load into DATE, and plain text -- so one layout exercises
#   the whole rule set without a synthetic descriptor that could drift from any shipped record.
_ACCOUNT = layouts.layout("ACCOUNT")

#: The fields of ACCOUNT a comparison digests in these tests, in target column order.
_ACCOUNT_FIELDS = (
    "ACCT-ID",
    "ACCT-ACTIVE-STATUS",
    "ACCT-CURR-BAL",
    "ACCT-OPEN-DATE",
    "ACCT-GROUP-ID",
)

#: The 26-character processing stamp width every marked field declares.
_STAMP_WIDTH = 26

#: One admitted stamp spelling and the unwritten span, as the shipped extract carries them.
_ADMITTED_STAMP = "2022-06-10 19:27:53.000000"
_DOTTED_STAMP = "2022-06-10-19.27.53.000000"
_UNWRITTEN_STAMP = " " * _STAMP_WIDTH


def _source_account(
    account_id: str = "00000000011",
    status: str = "Y",
    balance: str = "1234.56",
    open_date: str = "2020-01-15",
    group: str = "ZEROAPR",
) -> dict[str, object]:
    """Build one account record in the shapes the SOURCE side holds.

    Purpose
    -------
    Produce the prepared-source half of a pair: an unsigned display key as characters, money as an
    exact decimal at scale two, and a date as the ten characters the fixed-width span carries.

    Parameters
    ----------
    account_id : str
        The eleven-character zero-padded identifier, as the extract carries it.
    status : str
        The one-character active status.
    balance : str
        The current balance, rendered into an exact decimal at its own scale.
    open_date : str
        The open date in ISO order, as ``PIC X(10)`` carries it.
    group : str
        The disclosure group identifier.

    Returns
    -------
    dict[str, object]
        One record keyed by copybook field name.

    Raises
    ------
    None
        Building a mapping cannot fail.
    """
    return {
        "ACCT-ID": account_id,
        "ACCT-ACTIVE-STATUS": status,
        "ACCT-CURR-BAL": Decimal(balance),
        "ACCT-OPEN-DATE": open_date,
        "ACCT-GROUP-ID": group,
    }


def _readback_account(
    account_id: int = 11,
    status: str = "Y",
    balance: str = "1234.56",
    open_date: datetime.date = datetime.date(2020, 1, 15),
    group: str = "ZEROAPR",
) -> dict[str, object]:
    """Build one account row in the shapes a DRIVER read-back holds.

    Purpose
    -------
    Produce the target half of a pair, in the Python types ``psycopg`` really returns for the
    columns the account target loads into: ``int`` for ``BIGINT``, ``Decimal`` for
    ``NUMERIC(12,2)``, ``datetime.date`` for ``DATE`` and ``str`` for the character columns.

    Parameters
    ----------
    account_id : int
        The identifier as the ``BIGINT`` column returns it, without the extract's leading zeros.
    status : str
        The one-character active status.
    balance : str
        The current balance, rendered into an exact decimal at scale two.
    open_date : datetime.date
        The open date as the ``DATE`` column returns it.
    group : str
        The disclosure group identifier.

    Returns
    -------
    dict[str, object]
        One row keyed by the same copybook field names the source side uses.

    Raises
    ------
    None
        Building a mapping cannot fail.
    """
    return {
        "ACCT-ID": account_id,
        "ACCT-ACTIVE-STATUS": status,
        "ACCT-CURR-BAL": Decimal(balance),
        "ACCT-OPEN-DATE": open_date,
        "ACCT-GROUP-ID": group,
    }


def _compare(
    source: list[dict[str, object]],
    target: list[dict[str, object]],
    *,
    keys: tuple[str, ...] = ("ACCT-ID",),
    reported: int = REPORTED_DIFFERENCE_LIMIT,
):
    """Compare two account sides through the pass's own entry point.

    Parameters
    ----------
    source : list[dict[str, object]]
        The prepared source records, already in key order.
    target : list[dict[str, object]]
        The read-back rows, already in the same key order.
    keys : tuple[str, ...]
        The key fields the pairing is verified on; empty selects ordinal pairing.
    reported : int
        How many located differences the result may carry.

    Returns
    -------
    ChecksumComparison
        The outcome, for the caller to assert on.

    Raises
    ------
    ValueError
        If the field selection or the reporting bound is refused.
    """
    return compare_record_digests(
        record_name="ACCOUNT",
        qualified_table="account.accounts",
        source_records=source,
        target_records=target,
        fields=_ACCOUNT_FIELDS,
        layout=_ACCOUNT,
        reported_differences=reported,
        key_fields=keys,
    )


def test_a_prepared_source_record_and_its_driver_readback_verify_as_one_record() -> None:
    """Verify a correct load whose two sides hold four different Python types for one record.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the pass reports a difference between a fixed-width identifier and its BIGINT value, a
        text date and its DATE value, or either character column and itself.
    """
    outcome = _compare([_source_account()], [_readback_account()])
    # WHY : Assumptions: the target side's `int` and `datetime.date` are exactly the classes a
    #   delivered canonicalisation could not express -- both reached a bare `raise TypeError`, so
    #   the mandatory pass could not verify the account master, the first dataset a cutover loads.
    assert outcome.verified
    assert outcome.matched
    assert outcome.differences == ()
    assert outcome.unpaired_records == 0
    assert outcome.compared == 1


@pytest.mark.parametrize(
    ("source_value", "target_value", "field"),
    [
        ("00000000011", 11, "ACCT-ID"),
        (Decimal("11"), 11, "ACCT-ID"),
        (Decimal("1234.56"), Decimal("1234.56"), "ACCT-CURR-BAL"),
        ("2020-01-15", datetime.date(2020, 1, 15), "ACCT-OPEN-DATE"),
        ("ZEROAPR", "ZEROAPR", "ACCT-GROUP-ID"),
    ],
)
def test_each_supported_value_class_pairs_across_the_two_sides(
    source_value: object, target_value: object, field: str
) -> None:
    """Digest one field's source shape and its read-back shape to the same bytes.

    Parameters
    ----------
    source_value : object
        The value as the prepared source record holds it.
    target_value : object
        The value as a driver returns it for the column that field loads into.
    field : str
        The account field being rendered, which supplies the storage regime.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the two shapes of one value digest differently, which would report a correct load as a
        defect on every row carrying that column.
    """
    # WHY : Assumptions: the reconciliation is asserted per CLASS rather than only through a whole
    #   record, so a regression names the class that stopped pairing instead of failing one opaque
    #   record-level assertion that could be any of five columns.
    assert digest_of_record({field: source_value}, (field,), layout=_ACCOUNT) == digest_of_record(
        {field: target_value}, (field,), layout=_ACCOUNT
    )


def test_a_timestamp_and_a_uuid_read_back_pair_with_their_source_characters() -> None:
    """Render a driver timestamp and a driver UUID to the characters their source sides carry.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If a TIMESTAMP(6) or a UUID column's read-back value fails to pair with the same value as
        characters.
    """
    stamp = datetime.datetime(2022, 6, 10, 19, 27, 53)
    fields = ("STAMP",)
    # WHY : Assumptions: the microsecond part is always six digits, which is why `isoformat` is not
    #   used -- it omits the fraction entirely on an exact second, so a correct stamp would render
    #   19 characters against the source's 26 and report a difference.
    assert digest_of_record({"STAMP": stamp}, fields) == digest_of_record(
        {"STAMP": _ADMITTED_STAMP}, fields
    )
    subject = uuid.UUID("6f3d5b0e-9c3a-4b6f-9f2a-1d4e7c8b5a20")
    assert digest_of_record({"STAMP": subject}, fields) == digest_of_record(
        {"STAMP": str(subject)}, fields
    )


def test_an_absent_value_is_not_the_empty_string() -> None:
    """Digest a nullable column holding nothing differently from one holding zero characters.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If ``None`` and ``""`` digest alike, which would pass a load that turned one into the other.
    """
    # WHY : Assumptions: the loader's own TRIMMED_OR_NULL projection makes exactly this distinction
    #   on the way in -- a blank second address line becomes NULL and an empty-but-present value
    #   does not -- so a comparison that could not tell them apart would confirm a load that lost
    #   it.
    fields = ("ADDR",)
    assert digest_of_record({"ADDR": None}, fields) != digest_of_record({"ADDR": ""}, fields)


def test_a_text_value_and_a_numeric_value_do_not_digest_alike() -> None:
    """Digest characters and an integer differently where the field is not a numeric identifier.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If a text field's characters collide with an integer of the same spelling.
    """
    # WHY : Assumptions: the value CLASS is inside the frame, so the reconciliation of a numeric
    #   identifier is granted by the descriptor and never by the spelling. Without the class tag a
    #   text column holding "11" would compare equal to an integer column holding 11, which is the
    #   direction that hides a load writing a value into the wrong column.
    assert digest_of_record({"TEXT": "11"}, ("TEXT",)) != digest_of_record({"TEXT": 11}, ("TEXT",))


@pytest.mark.parametrize("refused", [1.5, True, ["A"], {"A": "B"}, object()])
def test_an_unsupported_value_class_is_a_verification_error_not_a_type_error(
    refused: object,
) -> None:
    """Refuse a value no column holds as a verification failure the CLI converts to a status.

    Parameters
    ----------
    refused : object
        A value belonging to no supported class: a float, a boolean, two containers and a bare
        object.

    Returns
    -------
    None
        The assertion is the result. The refusal this verifies is :class:`ChecksumValueError`.

    Raises
    ------
    AssertionError
        If the refusal is not a :class:`ChecksumVerificationError`, which is the type ``cli.py``
        catches -- a bare ``TypeError`` terminated the process outside the binary exit contract.
    """
    with pytest.raises(ChecksumValueError):
        digest_of_record({"VALUE": refused}, ("VALUE",))
    # WHY : Assumptions: the subclass relationship is asserted rather than assumed, because it is
    #   what makes the CLI's existing `except (*_DECODE_ERRORS, ChecksumVerificationError)` catch
    #   this refusal. A sibling exception type would have been caught by nothing.
    assert issubclass(ChecksumValueError, ChecksumVerificationError)


def test_a_float_is_refused_with_the_money_reason() -> None:
    """Refuse a float naming exactness rather than type, because the money path forbids one.

    Returns
    -------
    None
        The assertions are the result. The refusal this verifies is :class:`ChecksumValueError`,
        whose message names exactness rather than type.

    Raises
    ------
    AssertionError
        If a float is admitted, or if the refusal does not name the exactness reason.
    """
    with pytest.raises(ChecksumValueError, match="exact monetary value"):
        digest_of_record({"AMT": 10.10}, ("AMT",))


def test_the_refusal_names_the_field_and_quotes_no_value() -> None:
    """Name the offending field in a refusal without disclosing any part of its content.

    Returns
    -------
    None
        The assertions are the result. The refusal this verifies is :class:`ChecksumValueError`.

    Raises
    ------
    AssertionError
        If the message omits the field description or echoes the value.
    """
    # WHY : Assumptions: the sentinel is deliberately NOT digit-shaped, even though the field it
    #   stands in for holds an identifier. A test that wrote a plausible primary account number here
    #   would publish a payment-number-shaped literal into the source of a suite whose whole
    #   disclosure discipline is that such values never appear, and it would trip every credential
    #   scanner for no gain: what is under test is that the refusal echoes NOTHING, and an
    #   unmistakably synthetic token proves that at least as well.
    withheld = "SENTINEL-VALUE-NEVER-ECHOED"
    with pytest.raises(ChecksumValueError) as refused:
        digest_of_record({"ACCT-ID": [withheld]}, ("ACCT-ID",), layout=_ACCOUNT)
    message = str(refused.value)
    assert "ACCT-ID" in message
    # WHY : Trade-offs: a verification report is the artefact most likely to be pasted into an issue
    #   tracker, and these records carry primary account numbers, so a refusal names the field and
    #   the type and never the content. The accepted cost is that a reader learns which field was
    #   refused and not what it held.
    assert withheld not in message


def test_a_differing_field_is_located_by_name_and_interval() -> None:
    """Report the one differing field with its descriptor and no value on either side.

    Returns
    -------
    None
        The assertions are the result.
        The failure this verifies is a comparison whose ``verified`` is ``False``; no
        exception is raised.

    Raises
    ------
    AssertionError
        If the difference is not localised to the differing field, or if either value appears.
    """
    outcome = _compare(
        [_source_account(balance="1234.56")],
        [_readback_account(balance="1234.57")],
    )
    assert not outcome.verified
    assert outcome.differing_records == 1
    assert [difference.kind for difference in outcome.differences] == [DifferenceKind.FIELD]
    located = outcome.differences[0]
    assert located.field_name == "ACCT-CURR-BAL"
    rendered = located.describe()
    assert "ACCT-CURR-BAL" in rendered
    # WHY : Trade-offs: the descriptor's own description carries the half-open byte interval, which
    #   is what lets an operator go to the span in the extract rather than to the value in a log
    #   line. The cost is one more lookup for the reader, paid to keep every value out of the
    #   report.
    assert "12" in rendered
    assert "1234.56" not in rendered
    assert "1234.57" not in rendered


def test_a_row_present_on_one_side_only_is_reported_at_its_position() -> None:
    """Report a load that stopped early and one that ran twice, each at the position it diverged.

    Returns
    -------
    None
        The assertions are the result.
        The failure this verifies is a comparison whose ``verified`` is ``False``; no
        exception is raised.

    Raises
    ------
    AssertionError
        If a one-sided position is not counted and reported with the kind naming which side is
        short.
    """
    short = _compare([_source_account(), _source_account("00000000012")], [_readback_account()])
    assert not short.verified
    assert short.absent_records == 1
    assert short.differences[0].kind is DifferenceKind.ABSENT_FROM_TARGET
    assert short.differences[0].ordinal == 2
    doubled = _compare([_source_account()], [_readback_account(), _readback_account(12)])
    assert not doubled.verified
    assert doubled.absent_records == 1
    assert doubled.differences[0].kind is DifferenceKind.ABSENT_FROM_SOURCE


def test_two_sides_in_different_key_orders_pair_by_key_rather_than_by_position() -> None:
    """Pair by key rather than by position, and report a one-sided key as unpaired.

    Pairs a correctly loaded master whose two sides arrive in different orders, and reports a key
    only one side carries as unpaired rather than as a field difference.

    Returns
    -------
    None
        The assertions are the result.
        The failure this verifies is a comparison whose ``verified`` is ``False``; no
        exception is raised.

    Raises
    ------
    AssertionError
        If a mis-ordered but corresponding pair is reported at all, or if a key disagreement is
        reported as a field difference.
    """
    # WHY : Alternatives Considered: REPORTING a mis-ordered pair as unpaired, on the reasoning that
    #   the source arrives in extract order while the read-back arrives ordered by the server, so a
    #   correctly loaded master whose extract is not sorted that way is compared
    #   record-against-wrong-row. Rejected in favour of the stronger remedy the pass actually
    #   implements: `compare_record_digests` SORTS both sides on the canonical key before it walks
    #   them, so the arrival order of neither side can decide which record is compared against which
    #   row. A mis-ordered pair therefore verifies, which is the correct verdict because the DATA is
    #   correct and only the caller's ordering differed. Reporting it would have sent an operator to
    #   investigate a load that is sound.
    corresponding = _compare(
        [_source_account("00000000011"), _source_account("00000000012")],
        [_readback_account(12), _readback_account(11)],
    )
    assert corresponding.verified
    assert corresponding.unpaired_records == 0
    assert corresponding.differing_records == 0

    # WHY : Assumptions: the guarantee that rejected alternative was really reaching for is asserted
    #   here instead, on the input the sort CANNOT rescue -- a key one side carries and the other
    #   does not. That is reported as an unpaired position at its own key and as zero field
    #   differences, which is the distinction that matters to whoever reads the report: an operator
    #   reads "these two sides do not hold the same records" rather than going looking for a decode
    #   defect that does not exist.
    unpairable = _compare(
        [_source_account("00000000011"), _source_account("00000000013")],
        [_readback_account(11), _readback_account(12)],
    )
    assert not unpairable.verified
    assert unpairable.unpaired_records == 2
    assert unpairable.differing_records == 0
    assert {difference.kind for difference in unpairable.differences} == {
        DifferenceKind.ABSENT_FROM_TARGET,
        DifferenceKind.ABSENT_FROM_SOURCE,
    }
    assert "occupied on one side only" in unpairable.render()


def test_a_key_field_outside_the_compared_fields_is_refused() -> None:
    """Refuse a pairing key the comparison does not digest, rather than silently not checking it.

    Returns
    -------
    None
        The assertion is the result.
        The refusal this verifies is :class:`ValueError`, raised by the call under test.

    Raises
    ------
    AssertionError
        If an unknown key field is accepted, which would leave the pairing unverified while the
        caller believed it was.
    """
    with pytest.raises(ValueError, match="not among the compared fields"):
        _compare([_source_account()], [_readback_account()], keys=("ACCT-EXPIRAION-DATE",))


def test_ordinal_pairing_is_available_for_a_record_with_no_usable_key() -> None:
    """Compare by position when no key field is named, which is the sequential feed's pairing.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If an empty key selection is refused or silently reports an unpaired position.
    """
    outcome = _compare([_source_account()], [_readback_account()], keys=())
    assert outcome.verified
    assert outcome.unpaired_records == 0


def test_the_reported_differences_are_bounded_and_the_withheld_count_is_exact() -> None:
    """Bound the located differences while keeping every count exact.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the report exceeds its bound, or if the counts stop being exact once it is reached.
    """
    identifiers = [f"{index:011d}" for index in range(1, 6)]
    source = [_source_account(identifier, balance="1.00") for identifier in identifiers]
    target = [_readback_account(int(identifier), balance="2.00") for identifier in identifiers]
    outcome = _compare(source, target, reported=2)
    # WHY : Trade-offs: the bound costs the TAIL of a large failure and keeps the counts exact, so a
    #   wholly wrong load reports a diagnostic rather than a file -- and still reports its true
    #   size.
    assert len(outcome.differences) == 2
    assert outcome.differing_records == 5
    assert outcome.withheld_differences == 3
    assert "further difference(s) counted and withheld" in outcome.render()


def test_a_reporting_bound_below_one_is_refused() -> None:
    """Refuse a comparison that could count a difference and name none of them.

    Returns
    -------
    None
        The assertion is the result.
        The refusal this verifies is :class:`ValueError`, raised by the call under test.

    Raises
    ------
    AssertionError
        If a bound below one is clamped rather than refused, which would be a weakened pass reached
        by an argument.
    """
    with pytest.raises(ValueError, match="at least 1 is required"):
        _compare([_source_account()], [_readback_account()], reported=0)


def test_a_comparison_over_no_position_verifies_an_empty_extract_and_an_empty_table() -> None:
    """Report success for the one genuine empty state, rather than a failure nobody can act on.

    Purpose
    -------
    Pin the verdict for a comparison that walked no position, which is the state an empty extract
    loaded into an empty table produces -- the normal state of the transaction master, since no
    ``TRANSACT`` dataset ships in either corpus.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If an empty comparison reports failure.
    """
    # WHY : Alternatives Considered: FAILING a comparison that walked no position, by analogy with
    #   the row-count report, on the ground that "the reader yielded nothing and the table is empty"
    #   cannot be told from an unreadable extract or a load that never ran. The analogy does not
    #   survive measurement, so it is recorded here rather than adopted: a report is built from a
    #   query, where an empty result set genuinely is ambiguous, while this pass is handed both
    #   sides explicitly and every accidental route to an empty one RAISES before a verdict -- an
    #   unreadable extract raises `OSError`, a truncated one `RecordLengthError`, an unknown layout
    #   `LayoutError`, and a load that never ran leaves a non-empty source paired against an empty
    #   table, which reports absent records rather than nothing.
    # WHY : Assumptions: a zero-record dataset is VALID input rather than a defect. Seven of the
    #   committed fixtures are genuinely zero-byte and each models a real batch condition -- a
    #   dataset that exists so the program opens it and reads no rows -- and the transaction master
    #   ships no extract at all, so an empty extract loaded into an empty table is this migration's
    #   normal state for it rather than an error to be reported.
    # WHY : Assumptions: `matched` and `compared` are asserted alongside the verdict, unchanged, so
    #   this case still distinguishes "both digests are equal over nothing" from "a position was
    #   walked" -- the verdict is the only member whose expectation moved.
    outcome = _compare([], [])
    assert outcome.matched
    assert outcome.verified
    assert outcome.compared == 0


def test_a_digest_over_no_field_is_refused() -> None:
    """Refuse a digest over an empty field selection on all three entry points.

    Returns
    -------
    None
        The assertions are the result.
        The refusal this verifies is :class:`ValueError`, raised by the call under test.

    Raises
    ------
    AssertionError
        If any entry point admits an empty selection, which would digest every record alike.
    """
    with pytest.raises(ValueError, match="name the fields to digest"):
        digest_records([_source_account()], ())
    with pytest.raises(ValueError, match="name the fields to digest"):
        canonical_key(_source_account(), ())


@pytest.mark.parametrize(
    ("record_name", "expected"),
    [("ACCOUNT", frozenset()), ("DALYTRAN", frozenset({"DALYTRAN-PROC-TS"}))],
)
def test_the_marked_stamps_are_read_from_the_descriptor(
    record_name: str, expected: frozenset[str]
) -> None:
    """Name a record's run-clock stamps from its own descriptors rather than from a written list.

    Parameters
    ----------
    record_name : str
        A registered layout name.
    expected : frozenset[str]
        The stamp fields that layout marks.

    Returns
    -------
    None
        The assertion is the result.

    Raises
    ------
    AssertionError
        If the published set is not the marked set.
    """
    assert normalized_timestamp_field_names(layouts.layout(record_name)) == expected


def test_the_deterministic_selection_drops_the_processing_stamp_and_keeps_the_originating_one() -> (
    None
):
    """Drop a marked stamp from a candidate selection and keep every other candidate, in order.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If a marked stamp survives, if an unmarked stamp is dropped, or if the order changes.
    """
    candidates = ("DALYTRAN-ID", "DALYTRAN-ORIG-TS", "DALYTRAN-PROC-TS", "COGNITO-SUB")
    kept = deterministic_field_names(layouts.layout("DALYTRAN"), candidates)
    # WHY : Assumptions: the originating stamp is deterministic -- every populated value in the
    #   shipped extract reads the same 26 characters -- so dropping it would discard business data
    #   the comparison exists to verify. A candidate the layout does not declare is KEPT, which is
    #   what lets a derived column such as the subject reference be compared at all.
    assert kept == ("DALYTRAN-ID", "DALYTRAN-ORIG-TS", "COGNITO-SUB")


@pytest.mark.parametrize("admitted", [_ADMITTED_STAMP, _DOTTED_STAMP, _UNWRITTEN_STAMP])
def test_an_admitted_or_unwritten_stamp_is_accepted_unchanged(admitted: str) -> None:
    """Admit both published stamp spellings and a span nobody has written into.

    Parameters
    ----------
    admitted : str
        A 26-character value the contract admits.

    Returns
    -------
    None
        The assertion is the result.

    Raises
    ------
    AssertionError
        If an admitted spelling is refused, or if the value is altered.
    """
    field = _stamp_field()
    assert validated_timestamp(field, admitted) == admitted


@pytest.mark.parametrize("refused", [1.0, b"2022-06-10 19:27:53.000000", ["x"], 0, object()])
def test_a_stamp_of_an_unadmitted_class_is_refused(refused: object) -> None:
    """Refuse any stamp value that is not characters, a naive timestamp or the absent value.

    Parameters
    ----------
    refused : object
        A value of a class the closed admitted set excludes.

    Returns
    -------
    None
        The assertion is the result.
        The refusal this verifies is :class:`TimestampContractError`, raised by the call under test.

    Raises
    ------
    AssertionError
        If an unadmitted class passes, which would make excluding a field from the compared span a
        way of not looking at it.
    """
    # WHY : Assumptions: the closed admitted set is asserted from the OUTSIDE, because the failure
    #   mode it guards is silent. A guard that returned every non-string value unchanged -- on the
    #   premise that only `None` and a driver `datetime` can arrive -- would let an arbitrary object
    #   in a marked stamp pass the one check whose purpose is to catch a mis-sliced or corrupt
    #   record, and the value would then be dropped from the digest with nothing to read.
    with pytest.raises(TimestampContractError):
        validated_timestamp(_stamp_field(), refused)


def test_a_naive_timestamp_and_the_absent_value_are_the_two_admitted_non_character_classes() -> (
    None
):
    """Admit the two classes a TIMESTAMP(6) column really returns and nothing else.

    Returns
    -------
    None
        The assertions are the result.
        The refusal this verifies is :class:`TimestampContractError`, raised by the call under test.

    Raises
    ------
    AssertionError
        If either admitted class is refused, or if an offset-carrying timestamp is admitted.
    """
    field = _stamp_field()
    naive = datetime.datetime(2022, 6, 10, 19, 27, 53)
    assert validated_timestamp(field, naive) is naive
    assert validated_timestamp(field, None) is None
    # WHY : Assumptions: the columns are TIMESTAMP(6) WITHOUT time zone and the source span carries
    #   no offset, so admitting an aware value would compare a moment against a wall-clock reading
    #   of it.
    with pytest.raises(TimestampContractError, match="time-zone offset"):
        validated_timestamp(field, naive.replace(tzinfo=datetime.UTC))


def test_a_stamp_of_the_wrong_width_reports_the_slice_rather_than_the_content() -> None:
    """Refuse a stamp whose width is not the declared one, naming the widths and no content.

    Returns
    -------
    None
        The assertions are the result.
        The refusal this verifies is :class:`TimestampContractError`, raised by the call under test.

    Raises
    ------
    AssertionError
        If a short span is admitted, or if the refusal echoes the value.
    """
    with pytest.raises(TimestampContractError, match="not sliced where this layout says"):
        validated_timestamp(_stamp_field(), "2022-06-10")


def test_a_marked_stamp_on_either_side_is_validated_during_the_comparison() -> None:
    """Reach the excluded stamp of both sides, so exclusion never becomes non-inspection.

    Returns
    -------
    None
        The assertions are the result.
        The refusal this verifies is :class:`TimestampContractError`, raised by the call under test.

    Raises
    ------
    AssertionError
        If a corrupt stamp on either side passes a comparison that excludes the field.
    """
    layout = layouts.layout("DALYTRAN")
    fields = ("DALYTRAN-ID",)
    good = {"DALYTRAN-ID": "0000000000000001", "DALYTRAN-PROC-TS": _UNWRITTEN_STAMP}
    corrupt = {"DALYTRAN-ID": "0000000000000001", "DALYTRAN-PROC-TS": "@@@@@@@@@@@@@@@@@@@@@@@@@@"}
    for source, target in ((good, corrupt), (corrupt, good)):
        with pytest.raises(TimestampContractError):
            compare_record_digests(
                record_name="DALYTRAN",
                qualified_table="ledger.daily_transactions",
                source_records=[source],
                target_records=[target],
                fields=fields,
                layout=layout,
            )


@pytest.mark.parametrize(
    ("record_name", "expected"),
    [
        ("ACCOUNT", ("ACCT-ID",)),
        ("DALYTRAN", ("DALYTRAN-ID",)),
        ("SECUSER", ("SEC-USR-ID",)),
    ],
)
def test_the_key_fields_are_read_from_the_descriptor_window(
    record_name: str, expected: tuple[str, ...]
) -> None:
    """Resolve a record's key fields from its own key window rather than from a written list.

    Parameters
    ----------
    record_name : str
        A registered layout name.
    expected : tuple[str, ...]
        The fields that layout's key window covers.

    Returns
    -------
    None
        The assertion is the result.

    Raises
    ------
    AssertionError
        If the resolved fields are not the window's fields, which would pair two sides on the wrong
        identity.
    """
    # WHY : Assumptions: the same window is what `loaders/aurora.py` derives the merge's conflict
    #   target from, so this assertion is also what keeps the pass pairing on the identity the load
    #   conflicts on.
    assert key_field_names(layouts.layout(record_name)) == expected


def test_the_canonical_key_orders_the_two_sides_identically() -> None:
    """Order both sides by one key rendering, so the sort cannot depend on a server collation.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the two shapes of one key render differently, or if the rendering does not order.
    """
    keys = ("ACCT-ID",)
    assert canonical_key(_source_account("00000000011"), keys, layout=_ACCOUNT) == canonical_key(
        _readback_account(11), keys, layout=_ACCOUNT
    )
    ordered = sorted(
        [_source_account("00000000012"), _source_account("00000000009")],
        key=lambda record: canonical_key(record, keys, layout=_ACCOUNT),
    )
    # WHY : Alternatives Considered: ordering both sides on a TEXT rendering of the key, which was
    #   rejected. The identifiers are rendered as INTEGERS for an unsigned display key, so 9 sorts
    #   before 12 -- where a text rendering of the zero-padded spellings would also sort 9 first and
    #   a text rendering of the unpadded driver values would not. One numeric rendering is what
    #   takes the database collation out of the comparison.
    assert [record["ACCT-ID"] for record in ordered] == ["00000000009", "00000000012"]


def test_the_framing_cannot_be_forged_by_a_value_carrying_the_delimiter() -> None:
    """Digest a value that spells a frame header differently from the frame it imitates.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If content carrying the delimiter can reproduce another field split's digested bytes.
    """
    delimiter = FRAME_DELIMITER.decode("ascii")
    fields = ("LEFT", "RIGHT")
    # WHY : Assumptions: the collision vector is spelled out rather than described -- a value that
    #   itself contains `<letter><delimiter><digits><delimiter>` is exactly what a delimiter-only
    #   framing could not distinguish from a real header, and the length prefix is what makes it
    #   distinguishable.
    forged = f"s{delimiter}1{delimiter}A"
    assert digest_of_record({"LEFT": forged, "RIGHT": "B"}, fields) != digest_of_record(
        {"LEFT": "s", "RIGHT": f"1{delimiter}AB"}, fields
    )
    # WHY : Assumptions: the two code points a delimiter-only framing used as separators are
    #   ordinary content here, so a record carrying either must still digest differently from a
    #   different split of the same characters.
    assert digest_of_record({"LEFT": "A\x1fB", "RIGHT": "C"}, fields) != digest_of_record(
        {"LEFT": "A", "RIGHT": "B\x1fC"}, fields
    )
    assert (
        digest_records([{"LEFT": "A\x1eB", "RIGHT": "C"}], fields).digest
        != digest_records([{"LEFT": "A", "RIGHT": "B\x1eC"}], fields).digest
    )


def test_a_record_boundary_cannot_be_forged_by_a_value() -> None:
    """Digest two streams differently when one record's value spells a record boundary.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If a value can terminate a record early, which would make two different datasets digest
        alike.
    """
    fields = ("KEY",)
    one_record = digest_records([{"KEY": "A\x1eB"}], fields)
    two_records = digest_records([{"KEY": "A"}, {"KEY": "B"}], fields)
    assert one_record.digest != two_records.digest
    assert one_record.records == 1
    assert two_records.records == 2


def test_the_report_carries_no_field_value_and_nothing_run_varying() -> None:
    """Render a failing comparison as text two runs over unchanged data diff to nothing.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the rendering echoes a value, or if two renderings of one comparison differ.
    """
    outcome = _compare([_source_account(balance="1234.56")], [_readback_account(balance="9.99")])
    rendered = outcome.render()
    assert rendered == outcome.render()
    assert "1234.56" not in rendered
    assert "9.99" not in rendered
    assert "record checksum verification FAILED" in rendered
    # WHY : Trade-offs: the digests are published because they are the one comparable value an
    #   operator can quote in a ticket, and they disclose nothing about any record. Nothing else
    #   about either side is.
    assert outcome.source.digest in rendered
    assert outcome.target.digest in rendered


def _stamp_field() -> layouts.FieldSpec:
    """Return the daily transaction's marked processing stamp descriptor.

    Purpose
    -------
    Give every timestamp case the SHIPPED descriptor rather than an invented one, so the width the
    refusals compare against is the width the layout declares.

    Returns
    -------
    layouts.FieldSpec
        The ``DALYTRAN-PROC-TS`` descriptor.

    Raises
    ------
    LookupError
        If the layout stops declaring the marked stamp, which would mean the exclusion this pass
        performs no longer has a subject.
    """
    layout = layouts.layout("DALYTRAN")
    for field in layout.fields:
        if field.name == "DALYTRAN-PROC-TS":
            return field
    raise LookupError("DALYTRAN no longer declares the marked processing stamp")


# ---------------------------------------------------------------------------
# Shared anchors for the three passes and their two shipped queries.
# ---------------------------------------------------------------------------

# WHY : Assumptions: the two queries are located relative to THIS file rather than through the
#   pass's own default resolver, because the distribution is installed as a copy into the virtual
#   environment while the `sql` directory ships beside the package rather than inside it. The pass's
#   resolver says so and refuses to guess, so every case below hands it the committed path
#   explicitly -- which is also what makes these assertions about the shipped file rather than about
#   whatever a wheel happened to bundle.
_SQL_VERIFY_ROOT = pathlib.Path(__file__).resolve().parents[1] / "sql" / "verify"
_ROW_COUNT_QUERY_PATH = _SQL_VERIFY_ROOT / "row_counts.sql"
_MONEY_TOTAL_QUERY_PATH = _SQL_VERIFY_ROOT / "money_totals.sql"

# WHY : Assumptions: no credential appears here and none is needed. The two database passes are
#   driven against the recording double, so the parameters only have to be well formed: the host is
#   in the reserved `.invalid` top-level domain and the trust anchor names a path that does not
#   exist, so nothing here could reach a cluster even if a future case handed it to a real driver.
#   The user is filled in per case from the pass's own `reporting_role()`, so the role name is never
#   spelled out in this module.
_CONNECTION_PARAMS: dict[str, object] = {
    "host": "aurora.carddemo.invalid",
    "port": 5432,
    "dbname": "carddemo",
    "sslmode": "verify-full",
    "sslrootcert": "/nonexistent/synthetic-test-anchor.pem",
}

# WHY : Assumptions: these eleven numbers are the MEASURED record counts of the committed seed
#   datasets, restated here so the assertion compares the pass's declaration against an independent
#   copy rather than against itself. A test that read the numbers out of `SEED_DATASET_BASELINES`
#   and compared them to themselves would pass whatever they became, which is the one outcome a
#   baseline check may not have.
# WHY : Assumptions: `discgrp` is 51 where every other master is 50, and the odd number is the
#   correct one -- the extra row is the mandatory 'DEFAULT' disclosure group the interest
#   calculation falls back to when an account's own group key is not found. Writing 50 here would
#   report a correct load as holding one row too many and send an operator hunting a duplicate that
#   does not exist.
# WHY : Assumptions: `export` is listed with 500 even though no table holds it. Its record LENGTH
#   and its record COUNT are both 500 and they are unrelated facts -- 500 bytes a record, 250000
#   bytes of dataset -- so the eleventh baseline exists for the round-trip check and drops out of
#   the report by having no load target.
_MEASURED_SEED_BASELINES: dict[str, int] = {
    "acctdata": 50,
    "carddata": 50,
    "cardxref": 50,
    "custdata": 50,
    "dailytran": 300,
    "discgrp": 51,
    "tcatbal": 50,
    "trancatg": 18,
    "trantype": 7,
    "usrsec": 10,
    "export": 500,
}

# WHY : Alternatives Considered: authoring fresh money vectors in this folder, which would have been
#   simpler to read and is rejected on what each choice PROVES. The fixtures below are the same
#   bytes the COBOL programs are run against and the same bytes the Java shared kernel's codec tests
#   use, so totalling them here demonstrates that the Python codecs agree with the COBOL and with
#   the Java. A vector invented in this folder would demonstrate only that the Python agrees with
#   itself, which is precisely the assurance a cross-language migration cannot use.
# WHY : Assumptions: both trees are REFERENCE and immutable, so every path here is opened for
#   reading only and every injected corruption below is applied to a copy in the per-test workspace.
#   Nothing in this module rewrites, re-encodes or normalises a dataset in place.
_ACCOUNT_FIXTURE = ("posting/happy_path", "acctdata.txt")
_TRANSACTION_FIXTURE = ("export/happy_path", "trandata.txt")
_DALYTRAN_FIXTURE = ("posting/happy_path", "dailytran.txt")
_TCATBAL_FIXTURE = ("posting/happy_path", "tcatbal.txt")
_DISCGRP_FIXTURE = ("interest/default_fallback", "discgrp.txt")

# WHY : Assumptions: these are the five measured `TRAN-AMT` values of the export fixture, in file
#   order. The second is the ONLY negative money value in the whole committed corpus, which is what
#   makes it the anchor of every negative-row-count assertion below: without it each sign comparison
#   would be zero against zero -- true, and no evidence at all that the sign path works.
_TRANSACTION_AMOUNTS = (
    Decimal("504.77"),
    Decimal("-919.00"),
    Decimal("67.88"),
    Decimal("281.77"),
    Decimal("454.66"),
)
_TRANSACTION_AMOUNT_TOTAL = Decimal("390.08")
_TRANSACTION_NEGATIVE_ROWS = 1

# WHY : Assumptions: the one money column whose layout ships no seed extract. The posting job fills
#   `ledger.transactions` from the daily extract, so the ETL leaves it empty and the pass reports it
#   as not comparable rather than as zero.
_UNSEEDED_MONEY_COLUMN = ("ledger.transactions", "amount")

# WHY : Assumptions: a rendered report is scanned for this SHAPE rather than for a clock value, so
#   the determinism check itself reads no clock. The pattern is the calendar-date prefix every
#   timestamp CardDemo writes begins with -- four digits, a separator, two digits, a separator, two
#   digits -- which is what a stamp of any value would show up as in a report that had acquired one.
_TIMESTAMP_SHAPE = re.compile(r"\d{4}[-/]\d{2}[-/]\d{2}")

# WHY : Assumptions: `parametrize` is the ONLY mark this module may carry. The suite registers no
#   markers and runs under `--strict-markers`, and more to the point a corruption assertion wearing
#   an expected-failure or a skip mark is a verification check switched off in place -- which is the
#   one thing this module exists to prevent.
_PERMITTED_MARKS = frozenset({"parametrize"})


def _executable_sql(path: pathlib.Path) -> str:
    """Read one shipped query with its comment lines removed.

    Purpose
    -------
    Let an assertion reason about what a query EXECUTES rather than about what it explains, so a
    keyword mentioned only in the file's own prose cannot satisfy or defeat a check.

    Parameters
    ----------
    path : pathlib.Path
        The committed query file.

    Returns
    -------
    str
        The query text with every whole-line ``--`` comment dropped and the remainder joined by
        newlines.

    Raises
    ------
    OSError
        Propagated if the committed query cannot be read, which would mean the distribution is
        incomplete rather than that a check failed.
    """
    lines = path.read_text(encoding="utf-8").splitlines()
    return "\n".join(line for line in lines if not line.lstrip().startswith("--"))


def _projected_columns(path: pathlib.Path) -> str:
    """Return the text of one shipped query's FINAL projection.

    Purpose
    -------
    Let an ordering assertion look at the columns a query PROJECTS rather than at the whole file.
    Both
    queries declare common table expressions whose names contain their column names as substrings --
    ``money_columns`` contains ``money_column`` -- so a search over the whole text would find the
    declaration before the projection and report a conforming query as mis-ordered.

    Parameters
    ----------
    path : pathlib.Path
        The committed query file.

    Returns
    -------
    str
        The text between the last top-level ``SELECT`` and the ``FROM`` that closes it.

    Raises
    ------
    AssertionError
        If the executable text carries no top-level ``SELECT`` followed by a ``FROM``, which would
        mean the file is not the report query it is named as.
    """
    executable = _executable_sql(path)
    start = executable.rfind("\nSELECT ")
    assert start != -1, f"{path.name} carries no top-level SELECT"
    end = executable.find("\nFROM", start)
    assert end > start, f"{path.name} carries no FROM closing its final SELECT"
    return executable[start:end]


def _count_row(
    dataset: str,
    *,
    actual: int | None = None,
) -> tuple[object, ...]:
    """Build one conforming row of the row-count report for a declared dataset.

    Purpose
    -------
    Assemble a row in exactly the shape ``row_counts.sql`` projects, deriving the baseline and the
    target table from the pass's own declarations so that a case perturbing the COUNT cannot
    accidentally also perturb the pairing.

    Parameters
    ----------
    dataset : str
        The dataset label, or :data:`NO_DATASET_LABEL` for the one unbaselined target.
    actual : int | None
        The count the target table is to report. ``None`` means "the baseline", which is the
        agreeing case; for the unbaselined line ``None`` means zero, because that is the state the
        ETL leaves it in.

    Returns
    -------
    tuple[object, ...]
        One row, its six values in :data:`ROW_COUNT_COLUMNS` order.

    Raises
    ------
    KeyError
        If ``dataset`` names neither a declared baseline nor the unbaselined label.
    """
    if dataset == NO_DATASET_LABEL:
        # WHY : Assumptions: the unbaselined line carries a NULL baseline and therefore a NULL
        #   delta, because NULL propagates through the query's own subtraction. Coercing either to
        #   zero here would build a row the query cannot emit, and the case that reads it would then
        #   be asserting against a shape no cluster produces.
        rows = 0 if actual is None else actual
        return (
            NO_DATASET_LABEL,
            "ledger.transactions",
            None,
            rows,
            None,
            RowCountVerdict.NO_BASELINE.value,
        )
    baseline = baseline_for(dataset)
    table = baseline.target_table
    assert table is not None, f"{dataset} has no load target, so it has no report line"
    expected = baseline.expected_rows
    rows = expected if actual is None else actual
    status = RowCountVerdict.MATCH if rows == expected else RowCountVerdict.MISMATCH
    return (dataset, table, expected, rows, rows - expected, status.value)


def _count_report(
    *, short: str | None = None, unbaselined_rows: int = 0
) -> list[tuple[object, ...]]:
    """Build a row-count report covering every declared line, optionally one row short.

    Purpose
    -------
    Produce the whole-migration result set in the query's own order, so a case can assert the
    passing shape and the injected-corruption shape from one builder and differ in exactly one
    respect.

    Parameters
    ----------
    short : str | None
        The dataset whose target is to report one row FEWER than its baseline, modelling a load that
        dropped a record. ``None`` builds the agreeing report.
    unbaselined_rows : int
        The count the unbaselined target reports. Zero is the state the ETL leaves it in.

    Returns
    -------
    list[tuple[object, ...]]
        One row per report line, in the order ``row_counts.sql`` returns them.

    Raises
    ------
    KeyError
        If ``short`` names no declared baseline.
    """
    # WHY : Assumptions: the line order is DERIVED from the baseline declaration and the unbaselined
    #   label is appended last, which is the order the query's own `sort_key` fixes. Building the
    #   rows in a different order would make the ordering assertion below true of this helper rather
    #   than of the query.
    rows = [
        _count_row(
            baseline.dataset,
            actual=baseline.expected_rows - 1 if baseline.dataset == short else None,
        )
        for baseline in SEED_DATASET_BASELINES.values()
        if baseline.target_table is not None
    ]
    rows.append(_count_row(NO_DATASET_LABEL, actual=unbaselined_rows))
    return rows


def _money_row(
    key: tuple[str, str],
    *,
    total: object,
    negative_rows: object,
    row_count: object,
) -> tuple[object, ...]:
    """Build one conforming row of the money-total report for a declared money column.

    Purpose
    -------
    Assemble a row in exactly the shape ``money_totals.sql`` projects, deriving the originating
    field, its PICTURE and the SQL type from the layout the loader declares, so that a case
    perturbing a TOTAL cannot accidentally also perturb the column's declaration.

    Parameters
    ----------
    key : tuple[str, str]
        The qualified table and money column the row reports.
    total : object
        The summed value. Typed ``object`` so a case can supply a ``float`` and prove it refused.
    negative_rows : object
        The count of rows holding a strictly negative value. Typed ``object`` for the same reason.
    row_count : object
        The count of rows totalled.

    Returns
    -------
    tuple[object, ...]
        One row, its eight values in :data:`MONEY_TOTAL_COLUMNS` order.

    Raises
    ------
    KeyError
        If ``key`` names no declared money column.
    """
    field = declared_money_columns()[key].field
    return (
        key[0],
        key[1],
        field.name,
        f"PIC S9({field.int_digits:02d})V{'9' * field.dec_digits}",
        f"NUMERIC({field.int_digits + field.dec_digits},{field.dec_digits})",
        row_count,
        total,
        negative_rows,
    )


def _money_report(
    source_totals: Mapping[tuple[str, str], SourceMoneyTotal],
    *,
    overrides: Mapping[tuple[str, str], tuple[Decimal, int]] | None = None,
) -> list[tuple[object, ...]]:
    """Build a money-total report answering with the source measurements, or with an override.

    Purpose
    -------
    Make the database report the money the extracts actually hold, so a passing verdict means the
    pass sliced, decoded and totalled real records and arrived at the same figures through a code
    path that never saw them -- and so a single override is the only difference in a failing case.

    Parameters
    ----------
    source_totals : Mapping[tuple[str, str], SourceMoneyTotal]
        The measurements taken from the extracts, keyed as the report is keyed.
    overrides : Mapping[tuple[str, str], tuple[Decimal, int]] | None
        Per column, a total and a negative-row count to report INSTEAD of the measured pair. This is
        how a corruption is injected into the loaded side.

    Returns
    -------
    list[tuple[object, ...]]
        One row per declared money column, in the order ``money_totals.sql`` returns them.

    Raises
    ------
    KeyError
        If an override names no declared money column.
    """
    replacements = {} if overrides is None else overrides
    rows: list[tuple[object, ...]] = []
    for key in declared_money_columns():
        measured = source_totals.get(key)
        if measured is None:
            # WHY : Assumptions: a column with no source measurement is reported EMPTY rather than
            #   given a plausible figure. Its layout ships no extract, so the ETL leaves the target
            #   empty and the pass fails a line holding unaccounted money -- a fixture carrying a
            #   balance there would fail on the one line that is never the property under test.
            rows.append(_money_row(key, total=Decimal("0.00"), negative_rows=0, row_count=0))
            continue
        total, negative_rows = replacements.get(key, (measured.total, measured.negative_rows))
        rows.append(
            _money_row(key, total=total, negative_rows=negative_rows, row_count=measured.records)
        )
    return rows


def _reporting_connection(
    fake_aurora: FakeAuroraDatabase, relation: str, rows: list[tuple[object, ...]], role: str
) -> object:
    """Arrange a read-only verifier session answering one verification relation.

    Purpose
    -------
    Stand the double up the way both database passes require it: the server is asked who it is
    before any query runs, so the session probe has to be answered as well as the relation.

    Parameters
    ----------
    fake_aurora : FakeAuroraDatabase
        The recording double standing in for the loaded cluster.
    relation : str
        The fragment of the verification view the pass will read, used to route the arranged rows.
    rows : list[tuple[object, ...]]
        The result set the relation is to return.
    role : str
        The role the session is to report, taken from the pass's own ``reporting_role()``.

    Returns
    -------
    object
        A connection on the arranged session, ready to be handed to the pass.

    Raises
    ------
    FakeClientContractError
        Propagated from the double if the connection parameters are malformed, which they are not.
    """
    fake_aurora.arrange_rows("current_user", [(role,)])
    fake_aurora.arrange_rows(relation, rows)
    return fake_aurora.connect(**{**_CONNECTION_PARAMS, "user": role})


def _workspace_copy(
    workspace: pathlib.Path, fixture_corpus: FixtureCorpus, fixture: tuple[str, str], name: str
) -> pathlib.Path:
    """Copy one committed fixture into the per-test workspace, byte for byte.

    Purpose
    -------
    Give a corruption somewhere to be injected that is not the corpus. The copy is the only artefact
    a case may modify.

    Parameters
    ----------
    workspace : pathlib.Path
        The per-test scratch directory, which pytest places outside the checkout.
    fixture_corpus : FixtureCorpus
        The read-only accessor over ``tests/fixtures``.
    fixture : tuple[str, str]
        The ``(scenario, dataset)`` pair naming the fixture to copy.
    name : str
        The file name to write inside the workspace.

    Returns
    -------
    pathlib.Path
        The written copy.

    Raises
    ------
    Failed
        Via :func:`pytest.fail`, propagated from the corpus accessor if the fixture is absent.
    OSError
        Propagated if the workspace cannot be written, which would mean the run has no scratch
        space at all.
    """
    # WHY : Assumptions: `app/**` and `tests/**` are REFERENCE trees that must stay byte-identical:
    #   the first is the specification this migration was transcribed from, the second is the COBOL
    #   parity oracle the migration is validated against -- so a corruption is injected into a COPY
    #   in the workspace and never into the original. Mutating a corpus file in place would silently
    #   invalidate every golden comparison the oracle makes, and the damage would outlive this run.
    target = workspace / name
    target.write_bytes(fixture_corpus.raw_bytes(*fixture))
    return target


# ---------------------------------------------------------------------------
# Pass 1 -- row counts: did the right NUMBER of rows arrive.
# ---------------------------------------------------------------------------


def test_the_row_count_result_set_contract_is_the_six_columns_the_query_projects() -> None:
    """Hold the parsed column contract equal to what the shipped row-count query actually projects.

    Purpose
    -------
    Prove the published column list is the query's own projection, in the query's own order, and
    that the ordering column is excluded from it -- the query orders by a ``sort_key`` it does not
    project, so a seventh name here would report a conforming result set as wrong.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the six names or their order drift from the shipped projection, or if the unprojected
        sort key is treated as a column.
    """
    assert ROW_COUNT_COLUMNS == (
        "dataset",
        "target_table",
        "expected_rows",
        "actual_rows",
        "delta",
        "status",
    )
    # WHY : Assumptions: the projection is read from the COMMENT-STRIPPED final SELECT, so neither a
    #   column named only in the query's own prose nor a common table expression whose name contains
    #   a column name can satisfy this check. Each name must appear in the projection and the
    #   positions must be increasing, which is what makes this an assertion about ORDER rather than
    #   membership.
    projection = _projected_columns(_ROW_COUNT_QUERY_PATH)
    positions = [projection.index(name) for name in ROW_COUNT_COLUMNS]
    assert positions == sorted(positions)
    assert "sort_key" not in ROW_COUNT_COLUMNS
    executable = _executable_sql(_ROW_COUNT_QUERY_PATH)
    # WHY : Assumptions: ordering on an unprojected key is what makes two runs byte-identical
    #   whatever plan the cluster chooses, so its presence in the ORDER BY and its absence from the
    #   projection are asserted together -- either one alone would leave the property unproven.
    assert "ORDER  BY e.sort_key" in executable


def test_the_row_count_report_is_parsed_by_name_in_the_order_the_query_returned() -> None:
    """Parse a whole-migration result set into named lines without reindexing a driver tuple.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the parsed lines lose the query's order, or if a value lands in the wrong attribute.
    """
    parsed = parse_row_count_rows(_count_report())
    assert len(parsed) == len(_count_report())
    assert [line.dataset for line in parsed] == [str(row[0]) for row in _count_report()]
    first = parsed[0]
    assert (first.dataset, first.target_table) == ("acctdata", "account.accounts")
    assert first.expected_rows == _MEASURED_SEED_BASELINES["acctdata"]
    assert first.delta == 0
    assert first.verdict is RowCountVerdict.MATCH


def test_the_eleven_seed_baselines_are_named_single_sourced_constants() -> None:
    """Hold every declared baseline equal to its measured count and to the shipped query's own list.

    Purpose
    -------
    Prove the eleven counts live in ONE place and are reachable by name, and that the two artefacts
    that carry them -- the pass's declaration and the query's ``VALUES`` list -- agree. A number
    duplicated per call site is the drift this check exists to deny.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If a baseline differs from its measured count, if a lookup returns a copy rather than the
        declared object, or if the shipped query carries a different number for any dataset.
    """
    assert {name: baseline.expected_rows for name, baseline in SEED_DATASET_BASELINES.items()} == (
        _MEASURED_SEED_BASELINES
    )
    # WHY : Assumptions: the lookup is asserted to return the DECLARED object rather than an equal
    #   one, which is what makes "single-sourced" a checkable property: a resolver that rebuilt a
    #   baseline per call could answer plausibly from a second copy and this check would not see it.
    for name in _MEASURED_SEED_BASELINES:
        assert baseline_for(name) is SEED_DATASET_BASELINES[name]
    executable = _executable_sql(_ROW_COUNT_QUERY_PATH)
    for name, expected in _MEASURED_SEED_BASELINES.items():
        baseline = SEED_DATASET_BASELINES[name]
        if baseline.target_table is None:
            # WHY : Assumptions: the export record has no load target, so no table holds its 500
            #   records and no line of the SQL report belongs to it. It is skipped here rather than
            #   excluded by a hand-kept list, so a dataset that gains a target is picked up.
            continue
        assert f"'{name}'" in executable
        assert str(expected) in executable
        assert baseline.target_table in executable


def test_the_disclosure_group_baseline_is_fifty_one_for_the_mandatory_default_group() -> None:
    """Hold the disclosure-group baseline at fifty-one rather than at the fifty every master has.

    Purpose
    -------
    Single out the one baseline whose value is easy to normalise to its neighbours' and wrong when
    it is. The extra row is the mandatory ``'DEFAULT'`` disclosure group the interest calculation
    falls back to when an account's own group key is not found.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the baseline reads fifty, or if the shipped query pairs the disclosure-group table with a
        different count.
    """
    baseline = SEED_DATASET_BASELINES["discgrp"]
    assert baseline.expected_rows == 51
    assert baseline.expected_rows != 50
    assert baseline.target_table == "reference.disclosure_groups"
    # WHY : Assumptions: the query's own line is asserted as the PAIR of table and count on one
    #   statement, because the numbers 50 and 51 both appear in that VALUES list many times over --
    #   checking for "51" alone would pass on a file that had lost this row entirely.
    executable = _executable_sql(_ROW_COUNT_QUERY_PATH)
    disclosure_line = next(
        line for line in executable.splitlines() if "reference.disclosure_groups" in line
    )
    assert "51" in disclosure_line
    assert "'discgrp'" in disclosure_line


def test_an_absent_baseline_is_reported_as_not_comparable_rather_than_zero_or_failing() -> None:
    """Report the one unbaselined target as not comparable, neither as zero nor as a failure.

    Purpose
    -------
    Prove the three-state outcome survives the whole path. ``ledger.transactions`` ships no seed
    extract -- ``app/jcl/TRANFILE.jcl`` primes the cluster from a single 350-byte initialiser record
    and the posting pipeline produces its real content -- so its baseline is NULL, and both ways of
    collapsing that to a boolean are wrong.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the absent baseline is coerced to zero, if a delta is derived against it, or if the line
        fails a report that is otherwise sound.
    """
    report = verify_row_count_rows(_count_report())
    absent = next(line for line in report.rows if line.dataset == NO_DATASET_LABEL)
    # WHY : Assumptions: `is None` is asserted rather than falsiness, because zero is falsy too and
    #   collapsing the two is exactly the defect. A baseline of zero would claim the migration
    #   expects an empty table; an absent baseline says no seed dataset exists to compare against.
    assert absent.expected_rows is None
    assert absent.expected_rows != 0
    assert absent.delta is None
    assert absent.verdict is RowCountVerdict.NO_BASELINE
    assert absent.comparable is False
    assert report.not_comparable == (absent,)
    # WHY : Assumptions: a zero-row target is VALID input for this line rather than a defect. The
    #   ETL leaves the transaction master empty by design, so "no rows" is the state a correct
    #   migration produces and the line passes on it -- while a nonzero count is stale data from an
    #   earlier attempt, a load pointed at the wrong table, or a posting run that ran ahead of its
    #   gate, each of which is a real failure this rule now catches.
    assert absent.verified is True
    assert report.verified is True
    assert "not comparable" in absent.describe()
    assert UNSEEDED_LAYOUT_NAME == "TRAN"


def test_an_unbaselined_target_holding_rows_fails_rather_than_being_waved_through() -> None:
    """Fail the unbaselined line when its target holds rows the migration cannot account for.

    Returns
    -------
    None
        The assertions are the result. The failure this verifies is a ``RowCountReport`` whose
        ``verified`` is ``False`` and whose unbaselined line is carried in ``mismatches`` with a
        ``NO_BASELINE`` verdict; no exception is raised.

    Raises
    ------
    AssertionError
        If a nonzero count on the unbaselined line is certified as a pass, which would make "not
        comparable" a way of not checking.
    """
    report = verify_row_count_rows(_count_report(unbaselined_rows=1))
    absent = next(line for line in report.rows if line.dataset == NO_DATASET_LABEL)
    assert absent.verdict is RowCountVerdict.NO_BASELINE
    assert absent.comparable is False
    assert absent.verified is False
    assert report.verified is False
    assert absent in report.mismatches
    assert "VIOLATED" in absent.describe()


def test_a_removed_row_fails_the_count_pass_naming_the_dataset_table_and_signed_delta() -> None:
    """Fail pass 1 on an injected missing row, reporting the signed delta and naming both sides.

    Purpose
    -------
    This is pass 1's injected-corruption case, and the corruption is the one it exists to catch: a
    load that stopped early, ran twice or skipped records changes the row COUNT, which is the only
    class of defect a count can see.

    Returns
    -------
    None
        The assertions are the result. The failure this verifies is a ``RowCountReport`` whose
        ``verified`` is ``False``; no exception is raised, because one run must be able to name
        every short table rather than stopping at the first.

    Raises
    ------
    AssertionError
        If a report missing a row verifies, if the delta is unsigned or wrong, or if the failing
        line does not name the dataset and its target table.
    """
    report = verify_row_count_rows(_count_report(short="dailytran"))
    assert report.verified is False
    assert len(report.mismatches) == 1
    failing = report.mismatches[0]
    assert failing.dataset == "dailytran"
    assert failing.target_table == "ledger.daily_transactions"
    assert failing.expected_rows == _MEASURED_SEED_BASELINES["dailytran"]
    assert failing.actual_rows == _MEASURED_SEED_BASELINES["dailytran"] - 1
    assert failing.delta == -1
    assert failing.verdict is RowCountVerdict.MISMATCH
    # WHY : Assumptions: the delta is rendered SIGNED, so a short load and a duplicated load read
    #   differently at a glance. An unsigned magnitude would leave an operator opening the two
    #   counts to work out which direction the difference ran.
    described = failing.describe()
    assert "delta=-1" in described
    assert "dailytran" in described
    assert "ledger.daily_transactions" in described
    rendered = report.render()
    assert "row count verification FAILED" in rendered
    assert "1 failing" in rendered


def test_a_row_count_result_set_of_the_wrong_shape_raises_rather_than_asserting() -> None:
    """Refuse a result set whose arity is not the published one, by raising a named error.

    Returns
    -------
    None
        The assertions are the result. The failure this verifies is
        :class:`ResultSetContractError`, raised by the parser.

    Raises
    ------
    AssertionError
        If a five-value row is parsed, or if the refusal is an assertion rather than an exception.
    """
    truncated = [row[:-1] for row in _count_report()]
    with pytest.raises(ResultSetContractError):
        parse_row_count_rows(truncated)
    # WHY : Assumptions: the refusal must be an EXCEPTION and never a bare `assert`, because `python
    #   -O` strips assertions entirely -- an assertion is a validation that disappears in exactly
    #   the configuration an operator is most likely to run a cutover in. The class is therefore
    #   asserted to descend from `RuntimeError` and not from `AssertionError`.
    assert issubclass(ResultSetContractError, RuntimeError)
    assert not issubclass(ResultSetContractError, AssertionError)


def test_a_row_count_report_that_omits_a_declared_target_raises() -> None:
    """Refuse a report that drops a declared dataset, which would read as a passing verification.

    Returns
    -------
    None
        The assertions are the result. The failure this verifies is
        :class:`ResultSetContractError`, raised by the whole-report judge.

    Raises
    ------
    AssertionError
        If a report missing the disclosure-group line is judged at all.
    """
    # WHY : Assumptions: the OMITTED direction is the dangerous one, which is why it is the case
    #   asserted here. A short report verifies a table nothing looked at, and it does so silently --
    #   every line it does carry agrees, so the verdict reads green.
    partial = [row for row in _count_report() if row[0] != "discgrp"]
    with pytest.raises(ResultSetContractError, match="discgrp"):
        verify_row_count_rows(partial)


def test_a_row_count_pass_that_cannot_read_its_query_raises_a_named_query_error(
    fake_aurora: FakeAuroraDatabase, workspace: pathlib.Path
) -> None:
    """Refuse to reach a verdict when the committed report query cannot be read.

    Parameters
    ----------
    fake_aurora : FakeAuroraDatabase
        Recording double standing in for the loaded cluster.
    workspace : pathlib.Path
        Per-test scratch directory, used only to name a path that holds no query.

    Returns
    -------
    None
        The assertions are the result. The failure this verifies is
        :class:`VerificationQueryError`, raised by the query reader before any statement runs.

    Raises
    ------
    AssertionError
        If an unreadable query yields a verdict rather than an error, or if the error class is an
        assertion type.
    """
    # WHY : Assumptions: the absent query is named in the WORKSPACE, so nothing under `sql/` has to
    #   be moved or renamed to reach this refusal. A case that renamed the committed file would
    #   leave the checkout modified if it failed part way through.
    connection = _reporting_connection(
        fake_aurora, "v_verification_row_counts", _count_report(), count_reporting_role()
    )
    with pytest.raises(VerificationQueryError):
        verify_row_counts(connection, query_path=workspace / "absent_row_counts.sql")
    assert issubclass(VerificationQueryError, RuntimeError)
    assert not issubclass(VerificationQueryError, AssertionError)


def test_a_row_count_pass_refuses_a_session_it_cannot_certify(
    fake_aurora: FakeAuroraDatabase,
) -> None:
    """Refuse to run the report on a session the server does not report as the verifier role.

    Parameters
    ----------
    fake_aurora : FakeAuroraDatabase
        Recording double arranged to report a write-capable role as the live session.

    Returns
    -------
    None
        The assertions are the result. The failure this verifies is
        :class:`RowCountVerificationError`, raised by the session guard.

    Raises
    ------
    AssertionError
        If a session that could write certifies a load, or if the report query runs anyway.
    """
    # WHY : Assumptions: the LIVE role is probed rather than the login role, because a session can
    #   change role after connecting. A verifier able to mutate what it verifies cannot certify
    #   anything, so the refusal has to happen before any supplied query text is executed.
    connection = _reporting_connection(
        fake_aurora, "v_verification_row_counts", _count_report(), "carddemo_ledger"
    )
    with pytest.raises(RowCountVerificationError, match="cannot certify"):
        verify_row_counts(connection, query_path=_ROW_COUNT_QUERY_PATH)
    assert _ROW_COUNT_QUERY_PATH.read_text(encoding="utf-8") not in fake_aurora.executed_sql()
    assert issubclass(RowCountVerificationError, RuntimeError)
    assert not issubclass(RowCountVerificationError, AssertionError)


def test_an_empty_row_count_result_set_does_not_vacuously_verify(
    fake_aurora: FakeAuroraDatabase,
) -> None:
    """Refuse to certify a run whose report returned no line at all.

    Parameters
    ----------
    fake_aurora : FakeAuroraDatabase
        Recording double standing in for the loaded cluster, arranged to return no row.

    Returns
    -------
    None
        The assertions are the result. The failure this verifies is
        :class:`ResultSetContractError`, because an empty result set omits every declared line.

    Raises
    ------
    AssertionError
        If an empty report is treated as a clean bill of health.
    """
    # WHY : Assumptions: an empty result set is the shape a lost connection or a projection that
    #   dropped every line produces, and `all()` over no rows is True -- so without this rule "the
    #   query returned nothing" would report as a verified migration.
    connection = _reporting_connection(
        fake_aurora, "v_verification_row_counts", [], count_reporting_role()
    )
    with pytest.raises(ResultSetContractError):
        verify_row_counts(connection, query_path=_ROW_COUNT_QUERY_PATH)


# ---------------------------------------------------------------------------
# Pass 2 -- record checksums: is each row the row it should BE.
# ---------------------------------------------------------------------------


def test_the_digest_algorithm_is_sha256_resolved_through_hashlib() -> None:
    """Hold the digest at SHA-256, resolved through the standard library rather than named in prose.

    Purpose
    -------
    Prove the declared algorithm is one ``hashlib`` actually provides and that the digest the pass
    produces is that algorithm's own width, so the name and the output cannot drift apart.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the declared name is not a guaranteed ``hashlib`` algorithm, or if the digest width is
        not that algorithm's.
    """
    # WHY : Assumptions: SHA-256 through `hashlib` is the in-repo precedent rather than a fresh
    #   choice -- the loader's protected-column envelopes and the two pinned query digests use it,
    #   so an operator re-measuring any of them reaches for one tool. `algorithms_guaranteed` is
    #   asserted rather than `algorithms_available`, because the latter varies with the linked
    #   OpenSSL build and a digest that resolves on one runner and not another is not a contract.
    assert DIGEST_NAME == "sha256"
    assert DIGEST_NAME in hashlib.algorithms_guaranteed
    width = hashlib.new(DIGEST_NAME).digest_size
    assert width == 32
    first = _source_account("00000000011")
    second = _source_account("00000000012")
    published = digest_records([first, second], _ACCOUNT_FIELDS)
    assert len(published.digest) == width * 2
    # WHY : Assumptions: the published digest is RECOMPUTED here from the canonical frames the pass
    #   itself produces, which is what makes this an assertion about the algorithm rather than about
    #   the constant naming it. A check that only compared `DIGEST_NAME` to a literal would still
    #   pass if the pass hashed with something else entirely.
    recomputed = hashlib.new(
        DIGEST_NAME,
        digest_of_record(first, _ACCOUNT_FIELDS) + digest_of_record(second, _ACCOUNT_FIELDS),
    ).hexdigest()
    assert published.digest == recomputed
    assert published.records == 2
    assert published.fields == _ACCOUNT_FIELDS
    assert DIGEST_NAME in published.describe()


def test_the_digest_covers_named_fields_in_a_fixed_order_rather_than_a_record_slice() -> None:
    """Digest the named fields in the order given, so padding and unnamed bytes cannot reach it.

    Purpose
    -------
    Prove the digest is taken over field VALUES rather than over a record slice. A slice digest
    would
    move with the record's padding and with any fill byte that carries no data, so two loads holding
    the same values would disagree for a reason no operator could act on.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If an unnamed field changes the digest, or if reordering the named fields does not.
    """
    record = _source_account()
    padded = {**record, "FILLER": " " * 178, "ACCT-ADDR-ZIP": "12345"}
    # WHY : Assumptions: the extra members are exactly what a slice digest would pick up -- the
    #   copybook's 178-byte FILLER tail and a field outside the selection -- so their having no
    #   effect is the positive evidence that the digest reads the named values and nothing else.
    assert digest_of_record(padded, _ACCOUNT_FIELDS) == digest_of_record(record, _ACCOUNT_FIELDS)
    reordered = (_ACCOUNT_FIELDS[1], _ACCOUNT_FIELDS[0], *_ACCOUNT_FIELDS[2:])
    assert digest_of_record(record, reordered) != digest_of_record(record, _ACCOUNT_FIELDS)


def test_money_is_digested_as_its_exact_decimal_and_never_as_a_rendered_number() -> None:
    """Distinguish two decimals of equal value but different scale, and refuse a binary float.

    Purpose
    -------
    Prove money reaches the digest as an exact decimal at its own scale. A locale-formatted or
    float-rounded rendering would make ten cents and ten point zero cents digest alike, which is the
    one distinction a money comparison cannot lose.

    Returns
    -------
    None
        The assertions are the result. The refusal this verifies is :class:`ChecksumValueError`.

    Raises
    ------
    AssertionError
        If two scales of one value digest alike, or if a ``float`` is admitted into the money path.
    """
    # WHY : Assumptions: scale is part of the value for a NUMERIC(p,2) column, so 1234.5 and 1234.50
    #   are different values rather than two spellings of one. A digest that normalised them would
    #   certify a load that had lost a decimal place.
    assert digest_of_record({"ACCT-CURR-BAL": Decimal("1234.50")}, ("ACCT-CURR-BAL",)) != (
        digest_of_record({"ACCT-CURR-BAL": Decimal("1234.5")}, ("ACCT-CURR-BAL",))
    )
    # WHY : Assumptions: a binary float cannot represent ten cents exactly, so admitting one would
    #   make a digest depend on a rounding artefact. AAP rule T3 forbids the representation across
    #   the whole money path and this is where the Python half of it is enforced.
    with pytest.raises(ChecksumValueError):
        digest_of_record({"ACCT-CURR-BAL": 1234.56}, ("ACCT-CURR-BAL",))
    assert issubclass(ChecksumValueError, ChecksumVerificationError)


@pytest.mark.parametrize(
    ("record_name", "marked"),
    [
        ("TRAN", frozenset({"TRAN-PROC-TS"})),
        ("DALYTRAN", frozenset({"DALYTRAN-PROC-TS"})),
        ("INTTRAN", frozenset({"TRAN-ORIG-TS", "TRAN-PROC-TS"})),
    ],
)
def test_the_marked_stamp_set_is_read_from_the_layout_and_is_not_universal(
    record_name: str, marked: frozenset[str]
) -> None:
    """Read each record's run-clock stamps from its own descriptors, marked one or marked both.

    Parameters
    ----------
    record_name : str
        A registered layout carrying the two 26-character stamp fields.
    marked : frozenset[str]
        The stamp fields that layout declares as run-clock, which is what the digest must exclude.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the published set is not the layout's own, or if the two stamps have moved off the
        offsets the copybook fixes them at.
    """
    # WHY : Assumptions: the two stamps sit at fixed offsets in the 350-byte transaction record --
    #   ORIG-TS at 278 and PROC-TS at 304, each 26 characters -- and only PROC-TS is wall-clock: it
    #   is stamped with the posting time, while ORIG-TS carries the deterministic originating stamp
    #   copied from the input transaction. Including PROC-TS in a digest or a determinism comparison
    #   therefore produces a test that fails intermittently and reads as a data defect, which is the
    #   most expensive kind of failure to diagnose. The offsets are corroborated two independent
    #   ways: summing the CVTRA05Y field widths puts them at 278 and 304, and app/jcl/TRANREPT.jcl
    #   declares the same fields at one-based 263 and 305 in its own sort control.
    # WHY : Assumptions: the decision is READ from `copybook.layouts` and never re-derived from an
    #   offset, because the asymmetry is layout-declared and NOT universal -- the INTTRAN layout
    #   marks BOTH stamps, since the interest run writes both of them itself. A check hard-coded to
    #   "offset 304 only" would be wrong for that layout while passing for the other two.
    layout = layouts.layout(record_name)
    assert normalized_timestamp_field_names(layout) == marked
    stamps = {field.name: field for field in layout.fields if field.length == _STAMP_WIDTH}
    originating = next(field for name, field in stamps.items() if name.endswith("ORIG-TS"))
    processing = next(field for name, field in stamps.items() if name.endswith("PROC-TS"))
    assert (originating.start, originating.length) == (278, _STAMP_WIDTH)
    assert (processing.start, processing.length) == (304, _STAMP_WIDTH)
    assert processing.normalize_ts is True
    assert processing.name in marked


def test_the_two_transaction_layouts_keep_the_originating_stamp_the_interest_layout_drops() -> None:
    """Contrast the layout that marks one stamp with the layout that marks both, from the flags.

    Purpose
    -------
    Make the non-universality of the asymmetry an assertion rather than a remark, by showing the
    deterministic selection keeps the originating stamp for the transaction layouts and drops it for
    the interest layout that marks it.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the originating stamp is dropped where the layout does not mark it, or kept where it
        does.
    """
    candidates = ("TRAN-ID", "TRAN-ORIG-TS", "TRAN-AMT", "TRAN-PROC-TS")
    assert deterministic_field_names(layouts.layout("TRAN"), candidates) == (
        "TRAN-ID",
        "TRAN-ORIG-TS",
        "TRAN-AMT",
    )
    assert deterministic_field_names(layouts.layout("INTTRAN"), candidates) == (
        "TRAN-ID",
        "TRAN-AMT",
    )


def test_an_unwritten_processing_stamp_is_valid_across_the_whole_committed_extract(
    fixture_corpus: FixtureCorpus,
) -> None:
    """Admit a 26-space processing stamp as valid, and prove the committed extract carries it.

    Parameters
    ----------
    fixture_corpus : FixtureCorpus
        Read-only accessor over ``tests/fixtures``, supplying the export scenario's transactions.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If an unwritten stamp is refused, if it registers as a difference, or if the corpus no
        longer
        carries the unwritten span this rule exists for.
    """
    # WHY : Assumptions: an unwritten stamp is a VALID value rather than a defect, and the corpus is
    #   the evidence: COBOL leaves a field it has not moved a value into holding what the record was
    #   initialised to, and the posting run is what writes the processing stamp. Refusing 26 blanks
    #   would fail every verification of a pre-posting extract.
    extract = fixture_corpus.path(*_TRANSACTION_FIXTURE)
    records = list(transaction_reader.read_ascii_transactions(extract))
    assert len(records) == len(_TRANSACTION_AMOUNTS)
    assert {record["TRAN-PROC-TS"] for record in records} == {_UNWRITTEN_STAMP}
    assert {record["TRAN-ORIG-TS"] for record in records} == {_ADMITTED_STAMP}
    layout = layouts.layout("TRAN")
    processing = layout.field("TRAN-PROC-TS")
    for record in records:
        assert validated_timestamp(processing, record["TRAN-PROC-TS"]) == _UNWRITTEN_STAMP
    fields = deterministic_field_names(layout, ("TRAN-ID", "TRAN-ORIG-TS", "TRAN-AMT"))
    outcome = compare_record_digests(
        record_name="TRAN",
        qualified_table="ledger.transactions",
        source_records=records,
        target_records=records,
        fields=fields,
        layout=layout,
    )
    assert outcome.verified
    assert outcome.differing_records == 0


def test_a_malformed_processing_stamp_raises_rather_than_being_silently_blanked() -> None:
    """Refuse a 26-character stamp that is neither unwritten nor a well-formed calendar timestamp.

    Purpose
    -------
    Prove the exclusion is granted to a value the pass has LOOKED at. A field excluded from the
    compared span is never inspected again, so admitting an arbitrary 26 characters would discard a
    mis-decoded record or a wrong offset along with the stamp.

    Returns
    -------
    None
        The assertions are the result. The failure this verifies is
        :class:`TimestampContractError`, raised for each malformed spelling.

    Raises
    ------
    AssertionError
        If a malformed span of the declared width is admitted, or if the refusal echoes the content.
    """
    field = _stamp_field()
    for malformed in ("@" * _STAMP_WIDTH, "2022-13-45 99:99:99.000000"):
        assert len(malformed) == _STAMP_WIDTH
        with pytest.raises(TimestampContractError) as refusal:
            validated_timestamp(field, malformed)
        # WHY : Assumptions: the refusal names the FIELD and never the content, because a stamp can
        #   be business data and a verification diagnostic is the artefact most likely to be pasted
        #   whole into an issue tracker.
        assert malformed not in str(refusal.value)
        assert field.name in str(refusal.value)


def test_two_digests_of_one_input_agree_and_the_report_is_line_diffable(
    fixture_corpus: FixtureCorpus,
) -> None:
    """Digest one input twice to the same value, and render one comparison twice to the same text.

    Parameters
    ----------
    fixture_corpus : FixtureCorpus
        Read-only accessor over ``tests/fixtures``, supplying the export scenario's transactions.

    Returns
    -------
    None
        The assertions are the result.
        The failure this verifies is a comparison whose ``verified`` is ``False``; no
        exception is raised.

    Raises
    ------
    AssertionError
        If a digest is not a function of its input, or if a rendering carries anything run-varying.
    """
    extract = fixture_corpus.path(*_TRANSACTION_FIXTURE)
    records = list(transaction_reader.read_ascii_transactions(extract))
    layout = layouts.layout("TRAN")
    fields = deterministic_field_names(layout, ("TRAN-ID", "TRAN-ORIG-TS", "TRAN-AMT"))
    # WHY : Assumptions: the originating stamp is INCLUDED in this determinism check and the
    #   processing stamp is excluded by the selection above. The originating stamp reads the same 26
    #   characters on every row of the committed extract, so including it costs nothing and proves
    #   business data is being digested; including the processing stamp would make this the flaky
    #   test the asymmetry exists to prevent.
    assert "TRAN-ORIG-TS" in fields
    assert "TRAN-PROC-TS" not in fields
    first = digest_records(records, fields)
    second = digest_records(records, fields)
    assert first.digest == second.digest
    assert first.records == second.records == len(_TRANSACTION_AMOUNTS)
    outcome = compare_record_digests(
        record_name="TRAN",
        qualified_table="ledger.transactions",
        source_records=records,
        target_records=records[:-1],
        fields=fields,
        layout=layout,
    )
    assert outcome.verified is False
    assert outcome.render() == outcome.render()
    for amount in _TRANSACTION_AMOUNTS:
        assert str(amount) not in outcome.render()


def _mutated_record(record: str, field: layouts.FieldSpec, offset: int, replacement: str) -> str:
    """Return one record with a single byte inside one field replaced.

    Purpose
    -------
    Build the smallest possible corruption -- one character, inside a named field, leaving every
    boundary and the record length untouched -- because that is the defect class only a value
    comparison can see.

    Parameters
    ----------
    record : str
        The whole fixed-width record, at its declared width.
    field : layouts.FieldSpec
        The descriptor of the field to corrupt, supplying the span.
    offset : int
        The zero-based position WITHIN the field to overwrite.
    replacement : str
        The single character to write there.

    Returns
    -------
    str
        A record of the same width with exactly one character changed.

    Raises
    ------
    ValueError
        If the replacement is not a single character, or if the offset falls outside the field, both
        of which would make the corruption something other than the one-byte change claimed.
    """
    if len(replacement) != 1:
        raise ValueError(f"a one-byte mutation needs one character, not {len(replacement)}")
    if not 0 <= offset < field.length:
        raise ValueError(f"offset {offset} is outside {field.name}, which is {field.length} wide")
    position = field.start + offset
    return record[:position] + replacement + record[position + 1 :]


def test_a_mutated_byte_fails_the_checksum_pass_and_names_the_affected_field(
    fixture_corpus: FixtureCorpus, workspace: pathlib.Path
) -> None:
    """Fail pass 2 on one corrupted byte in a decoded record, locating it by field name.

    Purpose
    -------
    This is pass 2's injected-corruption case, and the corruption is the one it exists to catch: a
    single field wrong in place. Every count still agrees and every boundary still holds, so pass 1
    is blind to it and only a value comparison can see it.

    Parameters
    ----------
    fixture_corpus : FixtureCorpus
        Read-only accessor over ``tests/fixtures``, supplying the account record to corrupt a copy
        of.
    workspace : pathlib.Path
        Per-test scratch directory, which is where the corrupted copy is written.

    Returns
    -------
    None
        The assertions are the result. The failure this verifies is a ``ChecksumComparison`` whose
        ``verified`` is ``False`` carrying a ``DifferenceKind.FIELD`` difference; no exception is
        raised, because one run must be able to name every corrupted record.

    Raises
    ------
    AssertionError
        If a one-byte change verifies, if the difference does not name the corrupted field, or if
        the
        report echoes either value.
    """
    # WHY : Assumptions: the corruption is applied to a COPY in the workspace and never to the
    #   corpus, because `app/**` and `tests/**` are REFERENCE trees the migration is forbidden to
    #   modify -- the second is the COBOL parity oracle, so a dataset rewritten in place would
    #   silently invalidate every golden comparison it makes, for every later run as well as this
    #   one.
    original = fixture_corpus.records(*_ACCOUNT_FIXTURE)[0]
    layout = fixture_corpus.record_spec(_ACCOUNT_FIXTURE[1])
    balance = layout.field("ACCT-CURR-BAL")
    # WHY : Assumptions: the mutated byte is a DIGIT inside the money span rather than the sign
    #   overpunch, so the corrupted record still decodes cleanly. A corruption that made the record
    #   undecodable would be caught by the reader before the comparison ran, which would prove the
    #   reader's validation rather than the checksum pass's sensitivity.
    corrupted = _mutated_record(original, balance, balance.length - 2, "5")
    assert corrupted != original
    copy = workspace / "acctdata_mutated.txt"
    copy.write_text(corrupted + "\n", encoding="ascii")
    source = list(account_reader.read_ascii_accounts(fixture_corpus.path(*_ACCOUNT_FIXTURE)))
    target = list(account_reader.read_ascii_accounts(copy))
    assert source[0]["ACCT-CURR-BAL"] != target[0]["ACCT-CURR-BAL"]
    fields = tuple(field.name for field in account_reader.LOADED_FIELDS)
    outcome = compare_record_digests(
        record_name="ACCOUNT",
        qualified_table="account.accounts",
        source_records=source,
        target_records=target,
        fields=fields,
        layout=layout,
    )
    assert outcome.verified is False
    assert outcome.differing_records == 1
    assert outcome.absent_records == 0
    located = [difference for difference in outcome.differences if difference.field is not None]
    assert [difference.field.name for difference in located] == ["ACCT-CURR-BAL"]
    assert located[0].kind is DifferenceKind.FIELD
    described = located[0].describe()
    assert "ACCT-CURR-BAL" in described
    # WHY : Assumptions: neither value reaches the report even though both are known to it. A
    #   verification report is the artefact most likely to be pasted whole into an issue tracker and
    #   these records carry primary account numbers and national identifiers, so the field name and
    #   its byte interval are published and the content is not. The masking helpers are the
    #   supported way to look at a value, and they mask this field because the layout marks it
    #   sensitive.
    assert str(source[0]["ACCT-CURR-BAL"]) not in described
    assert str(target[0]["ACCT-CURR-BAL"]) not in described
    assert balance.sensitive is True
    masked = layouts.mask_field(balance, original[balance.start : balance.end])
    assert masked != original[balance.start : balance.end]
    assert masked not in outcome.render()


def test_a_change_confined_to_a_marked_stamp_does_not_fail_the_checksum_pass(
    fixture_corpus: FixtureCorpus, workspace: pathlib.Path
) -> None:
    """Verify a record whose only difference is the field the layout marks as a run-clock stamp.

    Purpose
    -------
    Prove the normalisation is APPLIED rather than merely configured. The processing stamp is
    written
    by the posting run, so two sides that differ only there hold the same business data, and a pass
    that reported it would fail every load of a posted extract.

    Parameters
    ----------
    fixture_corpus : FixtureCorpus
        Read-only accessor over ``tests/fixtures``, supplying the transaction records.
    workspace : pathlib.Path
        Per-test scratch directory, which is where the restamped copy is written.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If a difference confined to the marked stamp fails the comparison, or if the two sides do
        not
        actually differ there, which would make the check vacuous.
    """
    layout = layouts.layout("TRAN")
    processing = layout.field("TRAN-PROC-TS")
    records = fixture_corpus.records(*_TRANSACTION_FIXTURE)
    restamped = [
        record[: processing.start] + _ADMITTED_STAMP + record[processing.end :]
        for record in records
    ]
    copy = workspace / "trandata_restamped.txt"
    copy.write_text("\n".join(restamped) + "\n", encoding="ascii")
    extract = fixture_corpus.path(*_TRANSACTION_FIXTURE)
    source = list(transaction_reader.read_ascii_transactions(extract))
    target = list(transaction_reader.read_ascii_transactions(copy))
    # WHY : Assumptions: the two sides are asserted to REALLY differ at the marked stamp before the
    #   comparison is run. Without that assertion a normalisation that had been switched off would
    #   still pass this case, because the inputs would be identical and there would be nothing to
    #   normalise.
    assert source[0]["TRAN-PROC-TS"] == _UNWRITTEN_STAMP
    assert target[0]["TRAN-PROC-TS"] == _ADMITTED_STAMP
    assert source[0]["TRAN-PROC-TS"] != target[0]["TRAN-PROC-TS"]
    fields = deterministic_field_names(
        layout, tuple(field.name for field in transaction_reader.LOADED_FIELDS)
    )
    assert "TRAN-PROC-TS" not in fields
    outcome = compare_record_digests(
        record_name="TRAN",
        qualified_table="ledger.transactions",
        source_records=source,
        target_records=target,
        fields=fields,
        layout=layout,
    )
    assert outcome.verified is True
    assert outcome.differences == ()
    assert outcome.compared == len(_TRANSACTION_AMOUNTS)


# ---------------------------------------------------------------------------
# Pass 3 -- money parity: is the MONEY the same money.
# ---------------------------------------------------------------------------


def _measured_source_totals(
    fixture_corpus: FixtureCorpus,
) -> Mapping[tuple[str, str], SourceMoneyTotal]:
    """Measure every seeded money column from the committed fixtures, through the real readers.

    Purpose
    -------
    Recompute the source side of pass 3 from bytes rather than from a stored figure, using the five
    readers that own the five money-bearing layouts.

    Parameters
    ----------
    fixture_corpus : FixtureCorpus
        Read-only accessor over ``tests/fixtures``.

    Returns
    -------
    Mapping[tuple[str, str], SourceMoneyTotal]
        One measurement per money column whose layout ships a fixture, keyed as the report is keyed.

    Raises
    ------
    Failed
        Via :func:`pytest.fail`, propagated from the corpus accessor if a fixture is absent.
    MoneyParityVerificationError
        Propagated if a decoded record does not carry a declared money field.
    """
    # WHY : Alternatives Considered: comparing against a single stored expected total, which would
    #   have been one line instead of five extracts. Rejected because a stored figure is verified
    #   against itself: nothing would notice when the corpus changed, so the pass would keep
    #   certifying against a yardstick that had moved with the data it is meant to judge.
    #   Recomputing from the extract bytes each run is what makes the comparison independent, and it
    #   is also what exercises the fixed-width slicing and the sign overpunch on the way through.
    extracts = [
        SourceExtract("ACCOUNT", fixture_corpus.path(*_ACCOUNT_FIXTURE), "ascii"),
        SourceExtract("DALYTRAN", fixture_corpus.path(*_DALYTRAN_FIXTURE), "ascii"),
        SourceExtract("TCATBAL", fixture_corpus.path(*_TCATBAL_FIXTURE), "ascii"),
        SourceExtract("DISGROUP", fixture_corpus.path(*_DISCGRP_FIXTURE), "ascii"),
    ]
    return read_source_totals(extracts)


def test_the_money_result_set_contract_is_the_eight_columns_the_query_projects() -> None:
    """Hold the parsed column contract equal to the shipped money-total query's own projection.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the eight names or their order drift from the shipped projection, or if the unprojected
        sort key is treated as a column.
    """
    assert MONEY_TOTAL_COLUMNS == (
        "target_table",
        "money_column",
        "cobol_field",
        "cobol_picture",
        "sql_type",
        "row_count",
        "total",
        "negative_rows",
    )
    projection = _projected_columns(_MONEY_TOTAL_QUERY_PATH)
    positions = [projection.index(name) for name in MONEY_TOTAL_COLUMNS]
    assert positions == sorted(positions)
    assert "sort_key" not in MONEY_TOTAL_COLUMNS
    assert "ORDER  BY d.sort_key" in _executable_sql(_MONEY_TOTAL_QUERY_PATH)


def test_the_money_report_is_exactly_nine_columns_over_five_tables(
    fixture_corpus: FixtureCorpus,
) -> None:
    """Hold the report's geometry at nine money columns over five tables, from three authorities.

    Purpose
    -------
    Assert the SHAPE as well as the values, because the two fail differently: a report of nine rows
    over four tables means one table's rows were duplicated and another's lost, which no value
    comparison would notice. The geometry is checked against the declared inventory, the shipped
    query's own ``VALUES`` list, and the parsed result set.

    Parameters
    ----------
    fixture_corpus : FixtureCorpus
        Read-only accessor over ``tests/fixtures``, supplying the measurements the rows report.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If any of the three authorities reports other than nine columns over five tables.
    """
    declared = declared_money_columns()
    assert MONEY_TOTAL_COLUMN_COUNT == 9
    assert MONEY_TOTAL_TABLE_COUNT == 5
    assert len(declared) == MONEY_TOTAL_COLUMN_COUNT
    assert len({table for table, _ in declared}) == MONEY_TOTAL_TABLE_COUNT
    executable = _executable_sql(_MONEY_TOTAL_QUERY_PATH)
    for table, column in declared:
        assert f"'{column}'" in executable
        assert table in executable
    parsed = parse_money_total_rows(_money_report(_measured_source_totals(fixture_corpus)))
    assert len(parsed) == MONEY_TOTAL_COLUMN_COUNT
    assert len({row.target_table for row in parsed}) == MONEY_TOTAL_TABLE_COUNT
    # WHY : Assumptions: the parsed keys are asserted to BE the declared inventory rather than
    #   merely to number the same, because nine rows naming eight declared columns and one
    #   undeclared one would satisfy a count check while leaving a real column unmeasured.
    assert {row.key for row in parsed} == set(declared)


def test_every_money_total_stays_an_exact_decimal_and_no_binary_type_appears(
    fixture_corpus: FixtureCorpus,
) -> None:
    """Hold every total at exact decimal scale two and refuse a binary floating-point value.

    Purpose
    -------
    Prove the money path is exact end to end. AAP rule T3 requires ``NUMERIC(p,2)`` in SQL and
    ``Decimal`` in Python; the Java services hold the same rule with an architecture test and SQL
    has
    no such linter, so the report's own declared type is checked here as well as its values.

    Parameters
    ----------
    fixture_corpus : FixtureCorpus
        Read-only accessor over ``tests/fixtures``, supplying the measurements the rows report.

    Returns
    -------
    None
        The assertions are the result. The refusal this verifies is
        :class:`MoneyResultSetContractError`, raised when a row carries a ``float`` total.

    Raises
    ------
    AssertionError
        If a total is not an exact decimal, if the declared scale is not two, or if a float total is
        admitted.
    """
    assert MONEY_SCALE == 2
    parsed = parse_money_total_rows(_money_report(_measured_source_totals(fixture_corpus)))
    for row in parsed:
        assert isinstance(row.total, Decimal)
        assert not isinstance(row.total, float)
        assert row.sql_type.startswith("NUMERIC(")
        assert row.sql_type.endswith(",2)")
        assert isinstance(row.row_count, int)
        assert isinstance(row.negative_rows, int)
    # WHY : Assumptions: the executable query text is asserted to name no binary floating-point type
    #   at all. Such a type cannot represent ten cents exactly, so its SUM would depend on the order
    #   the executor read the rows in -- two runs over byte-identical data could then differ in the
    #   last place, and this pass would report a difference that does not exist or hide one that
    #   does.
    executable = _executable_sql(_MONEY_TOTAL_QUERY_PATH).casefold()
    for forbidden in ("double precision", "float", "real"):
        assert forbidden not in executable
    # WHY : Assumptions: the refusal is reached through the PARSER rather than through the row
    #   constructor, because the boundary between a driver's tuples and this pass's own types is
    #   where a value's class is still knowable. A row built in Python has already been typed by
    #   whoever built it; a row read from a cursor has not.
    key = next(iter(declared_money_columns()))
    with pytest.raises(MoneyResultSetContractError, match="not an exact decimal"):
        parse_money_total_rows([_money_row(key, total=1.0, negative_rows=0, row_count=1)])


def test_the_source_totals_are_recomputed_from_the_extracts_by_the_five_money_readers(
    fixture_corpus: FixtureCorpus,
) -> None:
    """Recompute each seeded money total from its own extract, equal to the measured value.

    Purpose
    -------
    Prove the source side is a measurement rather than a declaration, taken through the same five
    readers an operator's migration runs, over the same bytes the COBOL programs are run against.

    Parameters
    ----------
    fixture_corpus : FixtureCorpus
        Read-only accessor over ``tests/fixtures``, supplying the five money-bearing extracts.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If a recomputed total differs from the measured one, or if the readers no longer own the
        five
        money-bearing layouts.
    """
    declared = declared_money_columns()
    measured = _measured_source_totals(fixture_corpus)
    # WHY : Assumptions: the unseeded column is absent from the measurement rather than present as
    #   zero, because its layout ships no extract at all. Reporting it as zero would claim a check
    #   that never ran.
    assert set(measured) == set(declared) - {_UNSEEDED_MONEY_COLUMN}
    reader_for = {
        "ACCOUNT": (account_reader.read_ascii_accounts, _ACCOUNT_FIXTURE),
        "DALYTRAN": (dalytran_reader.read_ascii_daily_transactions, _DALYTRAN_FIXTURE),
        "TCATBAL": (tcatbal_reader.read_ascii_category_balances, _TCATBAL_FIXTURE),
        "DISGROUP": (discgrp_reader.read_ascii_disclosure_groups, _DISCGRP_FIXTURE),
        "TRAN": (transaction_reader.read_ascii_transactions, _TRANSACTION_FIXTURE),
    }
    assert {column.layout_name for column in declared.values()} == set(reader_for)
    assert len(reader_for) == MONEY_TOTAL_TABLE_COUNT
    for key, column in declared.items():
        reader, fixture = reader_for[column.layout_name]
        records = list(reader(fixture_corpus.path(*fixture)))
        recomputed = total_source_column(records, column)
        assert isinstance(recomputed.total, Decimal)
        assert recomputed.records == len(records)
        if key in measured:
            assert recomputed.total == measured[key].total
            assert recomputed.negative_rows == measured[key].negative_rows
            assert recomputed.records == measured[key].records


def test_the_transaction_amounts_carry_the_only_negative_money_in_the_corpus(
    fixture_corpus: FixtureCorpus,
) -> None:
    """Hold the five measured transaction amounts, their exact total and the one negative row.

    Purpose
    -------
    Anchor every sign assertion on a real vector. The second of the export scenario's five amounts
    is
    the only negative money value in the whole committed corpus, so without it each negative-row
    comparison would be zero against zero -- true, and no evidence that the sign path works at all.

    Parameters
    ----------
    fixture_corpus : FixtureCorpus
        Read-only accessor over ``tests/fixtures``, supplying the export scenario's transactions.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If a measured amount has moved, if the exact total has changed, or if the negative row is no
        longer negative.
    """
    extract = fixture_corpus.path(*_TRANSACTION_FIXTURE)
    records = list(transaction_reader.read_ascii_transactions(extract))
    amounts = tuple(record["TRAN-AMT"] for record in records)
    assert amounts == _TRANSACTION_AMOUNTS
    assert all(isinstance(amount, Decimal) for amount in amounts)
    assert sum(amounts, Decimal("0.00")) == _TRANSACTION_AMOUNT_TOTAL
    assert sum(1 for amount in amounts if amount < 0) == _TRANSACTION_NEGATIVE_ROWS
    assert _TRANSACTION_AMOUNTS[1] == Decimal("-919.00")
    column = declared_money_columns()[_UNSEEDED_MONEY_COLUMN]
    measured = total_source_column(records, column)
    assert measured.total == _TRANSACTION_AMOUNT_TOTAL
    assert measured.negative_rows == _TRANSACTION_NEGATIVE_ROWS
    assert measured.records == len(_TRANSACTION_AMOUNTS)


def test_a_wholesale_flipped_sign_fails_the_money_pass_on_the_negative_row_count(
    fixture_corpus: FixtureCorpus,
) -> None:
    """Fail pass 3 on a sign flip whose arithmetic total is unchanged, by comparing negative rows.

    Purpose
    -------
    This is pass 3's injected-corruption case and the assertion that matters most in this module. A
    zoned-decimal sign overpunch decoded with the wrong convention turns every negative amount
    positive: the record keeps its declared length, every field keeps its boundaries and the row
    count is unchanged, so pass 1 reports a match on every line and pass 2 sees it only if the
    digest
    happens to cover that field. Pass 3 is the pass designed for it.

    Parameters
    ----------
    fixture_corpus : FixtureCorpus
        Read-only accessor over ``tests/fixtures``, supplying the measurements to perturb.

    Returns
    -------
    None
        The assertions are the result. The failure this verifies is a ``MoneyTotalReport`` whose
        ``verified`` is ``False`` with the perturbed column among its ``sign_discrepancies``; no
        exception is raised, because one run must name every column that disagrees.

    Raises
    ------
    AssertionError
        If a sign flip that preserves the total verifies, if the negative-row count is reported
        without being compared, or if the failing line does not name the column and its COBOL field.
    """
    # WHY : Assumptions: a WHOLESALE sign flip can leave the arithmetic total untouched, which is
    #   the whole reason the negative-row count is COMPARED and not merely reported. A symmetric set
    #   of values sums to the same magnitude with every sign inverted, and a compensating pair of
    #   mis-decodes does so exactly, so the total alone can agree while every row is wrong. The
    #   COUNT of rows below zero moves whatever the total does, which is what makes the defect
    #   unambiguous.
    measured = _measured_source_totals(fixture_corpus)
    key = ("ledger.daily_transactions", "amount")
    source = measured[key]
    perturbed = source.negative_rows + 1
    rows = _money_report(measured, overrides={key: (source.total, perturbed)})
    report = verify_money_total_rows(rows, measured)
    assert report.verified is False
    failing = next(line for line in report.lines if line.row.key == key)
    assert failing.verdict is MoneyParityVerdict.MISMATCH
    # WHY : Assumptions: the total is asserted to AGREE on the failing line, which is what proves
    #   the verdict came from the sign comparison. Had the perturbation moved the total as well,
    #   this case would pass while a total-only pass would also have caught it, and the property
    #   under test would be unproven.
    assert failing.total_matched is True
    assert failing.total_difference == Decimal("0.00")
    assert failing.negative_rows_matched is False
    assert failing.negative_rows_difference == 1
    assert failing in report.sign_discrepancies
    assert failing in report.mismatches
    described = failing.describe()
    assert "amount" in described
    assert failing.row.cobol_field in described
    rendered = report.render()
    assert "money total verification FAILED" in rendered
    assert rendered == report.render()


def test_a_flipped_sign_written_into_a_workspace_copy_is_caught_by_the_money_pass(
    fixture_corpus: FixtureCorpus, workspace: pathlib.Path
) -> None:
    """Fail pass 3 on an extract whose sign overpunches were inverted byte by byte.

    Purpose
    -------
    Drive the same defect through the bytes rather than through a perturbed measurement, so the sign
    overpunch itself is exercised: the corrupted extract is re-encoded with every amount's sign
    inverted, measured through the real reader, and compared against the loaded figures.

    Parameters
    ----------
    fixture_corpus : FixtureCorpus
        Read-only accessor over ``tests/fixtures``, supplying the transactions to invert a copy of.
    workspace : pathlib.Path
        Per-test scratch directory, which is where the inverted copy is written.

    Returns
    -------
    None
        The assertions are the result. The failure this verifies is a ``MoneyTotalReport`` whose
        ``verified`` is ``False``, reached because both the total and the negative-row count moved.

    Raises
    ------
    AssertionError
        If inverting every sign leaves the measurement unchanged, or if the pass certifies the
        inverted extract against the loaded figures.
    """
    # WHY : Assumptions: the inversion is written into a COPY in the workspace, for the same reason
    #   every other corruption in this module is -- `app/**` and `tests/**` are REFERENCE trees, so
    #   re-encoding a committed extract in place would corrupt the parity oracle's own input.
    layout = layouts.layout("TRAN")
    amount = layout.field("TRAN-AMT")
    inverted: list[str] = []
    for record in fixture_corpus.records(*_TRANSACTION_FIXTURE):
        value = decode_zoned_field(record, amount)
        encoded = encode_zoned_field(-value, amount)
        inverted.append(record[: amount.start] + encoded + record[amount.end :])
    copy = workspace / "trandata_sign_inverted.txt"
    copy.write_text("\n".join(inverted) + "\n", encoding="ascii")
    records = list(transaction_reader.read_ascii_transactions(copy))
    column = declared_money_columns()[_UNSEEDED_MONEY_COLUMN]
    corrupted = total_source_column(records, column)
    # WHY : Assumptions: this extract is NOT symmetric, so inverting it moves the total as well as
    #   the count -- which is the ordinary case. The symmetric case, where only the count moves, is
    #   asserted separately, because it is the one a total-only comparison would pass.
    assert corrupted.total == -_TRANSACTION_AMOUNT_TOTAL
    assert corrupted.negative_rows == len(_TRANSACTION_AMOUNTS) - _TRANSACTION_NEGATIVE_ROWS
    faithful = total_source_column(
        list(
            transaction_reader.read_ascii_transactions(fixture_corpus.path(*_TRANSACTION_FIXTURE))
        ),
        column,
    )
    measured = {**_measured_source_totals(fixture_corpus), _UNSEEDED_MONEY_COLUMN: faithful}
    rows = _money_report(
        measured, overrides={_UNSEEDED_MONEY_COLUMN: (corrupted.total, corrupted.negative_rows)}
    )
    report = verify_money_total_rows(rows, measured)
    assert report.verified is False
    failing = next(line for line in report.lines if line.row.key == _UNSEEDED_MONEY_COLUMN)
    assert failing.verdict is MoneyParityVerdict.MISMATCH
    assert failing.total_matched is False
    assert failing.negative_rows_matched is False
    assert failing in report.sign_discrepancies


def test_a_sign_overpunched_negative_zero_is_counted_by_neither_side(
    fixture_corpus: FixtureCorpus,
) -> None:
    """Exclude an overpunched negative zero from the total and from the strictly-negative count.

    Purpose
    -------
    Pin the documented carve-out on both sides of the comparison. The zoned regime can spell a
    negative zero -- the closing brace is the ``-0`` overpunch -- and neither side treats it as a
    negative value: Python's ``Decimal`` has no negative zero, and the aggregate view counts rows
    strictly below zero.

    Parameters
    ----------
    fixture_corpus : FixtureCorpus
        Read-only accessor over ``tests/fixtures``, supplying an account record to restate.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If a negative zero decodes as negative, if it reaches a negative-row count, or if the
        shipped
        query admits a non-strict comparison against zero.
    """
    layout = fixture_corpus.record_spec(_ACCOUNT_FIXTURE[1])
    cycle_credit = layout.field("ACCT-CURR-CYC-CREDIT")
    record = fixture_corpus.records(*_ACCOUNT_FIXTURE)[0]
    negative_zero = record[: cycle_credit.start] + "0" * (cycle_credit.length - 1) + "}"
    negative_zero += record[cycle_credit.end :]
    decoded = decode_zoned_field(negative_zero, cycle_credit)
    # WHY : Assumptions: the carve-out is a property of the two representations rather than a policy
    #   this module chose. `Decimal` has no negative zero, so the brace decodes to an unsigned zero
    #   and re-encodes as the `+0` overpunch; and a `< 0` predicate excludes zero whatever its sign
    #   was written as. Both sides therefore agree without either being told about the other.
    assert decoded == Decimal("0.00")
    assert not decoded < 0
    assert encode_zoned_field(decoded, cycle_credit).endswith("{")
    column = declared_money_columns()[("account.accounts", "curr_cyc_credit")]
    restated = list(account_reader.iter_ascii_accounts(negative_zero))
    measured = total_source_column(restated, column)
    assert measured.total == Decimal("0.00")
    assert measured.negative_rows == 0
    assert measured.records == 1
    # WHY : Assumptions: the SQL side is asserted from the shipped query's executable text, which
    #   delegates the predicate to the aggregate view and therefore must carry no comparison against
    #   zero of its own. A non-strict spelling anywhere in this path would count every zero row as
    #   negative and make the two sides disagree on data that is correct.
    executable = _executable_sql(_MONEY_TOTAL_QUERY_PATH)
    assert "<= 0" not in executable
    assert "negative_rows" in executable
    line = MoneyTotalRow(
        *_money_row(
            ("account.accounts", "curr_cyc_credit"),
            total=Decimal("0.00"),
            negative_rows=0,
            row_count=1,
        )
    )
    assert line.negative_rows == 0
    assert line.total == Decimal("0.00")


def test_the_whole_money_pass_runs_the_committed_query_over_committed_extracts(
    fake_aurora: FakeAuroraDatabase, fixture_corpus: FixtureCorpus
) -> None:
    """Verify a faithful load end to end: committed query, committed extracts, recomputed totals.

    Parameters
    ----------
    fake_aurora : FakeAuroraDatabase
        Recording double standing in for the loaded cluster.
    fixture_corpus : FixtureCorpus
        Read-only accessor over ``tests/fixtures``, supplying the five money-bearing extracts.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the pass does not execute the committed query verbatim, or if a faithful load fails to
        verify.
    """
    extracts = [
        SourceExtract("ACCOUNT", fixture_corpus.path(*_ACCOUNT_FIXTURE), "ascii"),
        SourceExtract("DALYTRAN", fixture_corpus.path(*_DALYTRAN_FIXTURE), "ascii"),
        SourceExtract("TCATBAL", fixture_corpus.path(*_TCATBAL_FIXTURE), "ascii"),
        SourceExtract("DISGROUP", fixture_corpus.path(*_DISCGRP_FIXTURE), "ascii"),
    ]
    measured = read_source_totals(extracts)
    connection = _reporting_connection(
        fake_aurora,
        "v_verification_money_totals",
        _money_report(measured),
        money_reporting_role(),
    )
    report = verify_money_totals(connection, extracts, query_path=_MONEY_TOTAL_QUERY_PATH)
    assert report.verified is True
    assert len(report.lines) == MONEY_TOTAL_COLUMN_COUNT
    assert report.mismatches == ()
    assert report.sign_discrepancies == ()
    # WHY : Assumptions: the executed statement is asserted to BE the committed file, verbatim. A
    #   pass that wrapped, paginated or re-ordered the query would still produce a verdict from
    #   these arranged rows, and the file every runbook reasons about would no longer be the file it
    #   runs.
    committed = _MONEY_TOTAL_QUERY_PATH.read_text(encoding="utf-8")
    assert committed in fake_aurora.executed_sql()


# ---------------------------------------------------------------------------
# The four invariants the verify subpackage publishes over all three passes.
# ---------------------------------------------------------------------------

# WHY : Assumptions: these are the statement shapes a verification pass must never issue, and the
#   list is a DENY-list here rather than an allow-list because the passes legitimately execute two
#   session statements and one whole committed query, whose text no allow-list could enumerate. The
#   shared double's own forbidden-statement helper covers the loader contract, which admits INSERT
#   and UPDATE because a loader has to write; a verifier may not, so the two write verbs are added.
_WRITE_STATEMENT_FRAGMENTS = (
    "insert ",
    "update ",
    "delete from",
    "truncate",
    "merge ",
    "grant ",
    "revoke ",
    "create ",
    "alter ",
    "drop ",
)


def _write_statements(fake_aurora: FakeAuroraDatabase) -> tuple[str, ...]:
    """Return every recorded statement that could change what a pass is verifying.

    Purpose
    -------
    Make the read-only claim observable from the statement LOG rather than from the role's
    privileges
    alone. The two fail differently: the role is what makes writing impossible on a correctly
    provisioned cluster, and this is what catches a pass that tried on any cluster at all.

    Parameters
    ----------
    fake_aurora : FakeAuroraDatabase
        The recording double whose log is to be scanned.

    Returns
    -------
    tuple[str, ...]
        The offending statements, in execution order. Empty when the contract holds.

    Raises
    ------
    None
        Reporting a breach is not itself an error; the caller asserts the tuple is empty so the
        failure message carries the offending SQL.
    """
    # WHY : Assumptions: each statement is stripped of its own comment lines before being scanned,
    #   because the two shipped queries are executed VERBATIM including their explanatory headers --
    #   and those headers name the migrations that CREATE the views and GRANT on them. Scanning the
    #   raw text would report a passing verification as a privilege breach on the strength of the
    #   query's own prose.
    # WHY : Assumptions: the scan folds case and looks for the verb followed by a space, so the
    #   substring cannot match inside an identifier -- `updated_at` does not read as an UPDATE, and
    #   `SET TRANSACTION READ ONLY` does not read as a write.
    executed = [
        "\n".join(
            line for line in statement.splitlines() if not line.lstrip().startswith("--")
        ).casefold()
        for statement in fake_aurora.executed_sql()
    ]
    return tuple(
        statement
        for statement in executed
        if any(fragment in statement for fragment in _WRITE_STATEMENT_FRAGMENTS)
    )


def test_all_three_passes_are_mandatory_and_run_in_the_order_the_subpackage_declares() -> None:
    """Run the gate over exactly the three declared passes and refuse anything else.

    Purpose
    -------
    Prove the mandate is mechanical rather than procedural. A row count cannot detect a sign-
    overpunch
    decode defect at all, and only a checksum can localise a field corrupted in place, so a caller
    able to ask for a partial verdict could certify a load on evidence that does not reach the
    defect.

    Returns
    -------
    None
        The assertions are the result. The refusals this verifies are
        :class:`VerificationGateError`, raised for a gate handed too few passes and for a gate
        handed
        them out of order.

    Raises
    ------
    AssertionError
        If the two declared orders disagree, if a partial gate is allowed to run, or if a reordered
        gate reaches a verdict.
    """
    assert PASS_MODULES == ("row_counts", "checksum", "money_parity")
    assert gate.GATE_PASSES == PASS_MODULES
    ran: list[str] = []

    def _step(name: str) -> object:
        """Return a passing pass result that records the order it was invoked in.

        Parameters
        ----------
        name : str
            The pass name to record when the step runs.

        Returns
        -------
        object
            A minimal result satisfying the gate's pass protocol: verified, and renderable.

        Raises
        ------
        None
            Building a result cannot fail.
        """

        class _Result:
            """A verified pass result carrying only what the gate reads from one."""

            verified = True

            def render(self) -> str:
                """Return the one line this result renders as.

                Returns
                -------
                str
                    A deterministic, timestamp-free line naming the pass.

                Raises
                ------
                None
                    Rendering a constant cannot fail.
                """
                return f"{name} PASSED"

        ran.append(name)
        return _Result()

    steps = {name: (lambda name=name: _step(name)) for name in gate.GATE_PASSES}
    verification = run_verification_gate(steps)
    assert verification.verified is True
    assert ran == list(gate.GATE_PASSES)
    assert [outcome.pass_name for outcome in verification.outcomes] == list(gate.GATE_PASSES)
    # WHY : Assumptions: the refusals are asserted to happen BEFORE anything runs, which is what the
    #   recorded order proves -- a gate that measured first and complained afterwards would already
    #   have reported a partial verdict by the time it raised.
    ran.clear()
    partial = {name: steps[name] for name in gate.GATE_PASSES[:2]}
    with pytest.raises(VerificationGateError):
        run_verification_gate(partial)
    assert ran == []
    reordered = {name: steps[name] for name in reversed(gate.GATE_PASSES)}
    with pytest.raises(VerificationGateError):
        run_verification_gate(reordered)
    assert ran == []
    assert issubclass(VerificationGateError, RuntimeError)
    assert not issubclass(VerificationGateError, AssertionError)


def test_neither_database_pass_issues_a_statement_that_could_change_what_it_verifies(
    fake_aurora: FakeAuroraDatabase, fixture_corpus: FixtureCorpus
) -> None:
    """Run both database passes and prove from the statement log that neither wrote anything.

    Parameters
    ----------
    fake_aurora : FakeAuroraDatabase
        Recording double whose statement log is the evidence.
    fixture_corpus : FixtureCorpus
        Read-only accessor over ``tests/fixtures``, supplying the money pass's extracts.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If either pass issues a write, a grant or any data-definition statement, or if either runs
        as
        a role other than the SELECT-only verifier.
    """
    role = count_reporting_role()
    # WHY : Assumptions: both passes are asserted to resolve the SAME role name, and the name is
    #   read from each pass rather than written out here, so the role lives in exactly one place --
    #   the configuration module's schema-to-role map -- and this case cannot drift from it.
    assert role == money_reporting_role()
    counts = _reporting_connection(fake_aurora, "v_verification_row_counts", _count_report(), role)
    count_report = verify_row_counts(counts, query_path=_ROW_COUNT_QUERY_PATH)
    assert count_report.verified is True
    assert read_row_count_query(_ROW_COUNT_QUERY_PATH) in fake_aurora.executed_sql()
    extracts = [
        SourceExtract("ACCOUNT", fixture_corpus.path(*_ACCOUNT_FIXTURE), "ascii"),
        SourceExtract("DALYTRAN", fixture_corpus.path(*_DALYTRAN_FIXTURE), "ascii"),
        SourceExtract("TCATBAL", fixture_corpus.path(*_TCATBAL_FIXTURE), "ascii"),
        SourceExtract("DISGROUP", fixture_corpus.path(*_DISCGRP_FIXTURE), "ascii"),
    ]
    measured = read_source_totals(extracts)
    fake_aurora.arrange_rows("v_verification_money_totals", _money_report(measured))
    money_report = verify_money_totals(counts, extracts, query_path=_MONEY_TOTAL_QUERY_PATH)
    assert money_report.verified is True
    assert _write_statements(fake_aurora) == ()
    assert fake_aurora.forbidden_statements() == ()
    # WHY : Assumptions: the read-only transaction setting is asserted PRESENT as well as the writes
    #   absent, because the two guard different failures. The role is what makes a write impossible
    #   on a correctly provisioned cluster; the transaction setting is what makes an attempt fail
    #   loudly on a cluster where the role was mis-provisioned.
    executed = [statement.casefold() for statement in fake_aurora.executed_sql()]
    assert any("set transaction read only" in statement for statement in executed)
    assert sum(1 for statement in executed if "current_user" in statement) >= 2


def test_the_outcome_of_every_pass_is_binary_with_no_graded_tier(
    fixture_corpus: FixtureCorpus,
) -> None:
    """Hold every verdict to a boolean and every three-state token to a name rather than a code.

    Purpose
    -------
    Prove the exit semantics are strictly binary. A verification either verifies or it does not, and
    any difference at all is a failure -- including a money total out by one cent.

    Parameters
    ----------
    fixture_corpus : FixtureCorpus
        Read-only accessor over ``tests/fixtures``, supplying the money pass's measurements.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If a verdict is anything but a boolean, if a verdict token carries a numeric tier, or if a
        one-cent difference is tolerated.
    """
    # WHY : Alternatives Considered: adopting the graded aggregate return-code rubric the COBOL
    #   parity oracle under `tests/**` uses -- 0 pass, 2 usage, 4 warn, 8 fail, 16 fatal, worst code
    #   wins. Rejected, and the reason is specific rather than stylistic: in that rubric a
    #   warn-level 4 is the documented GREEN state, because two baseline programs carry an unfixable
    #   defect in immutable source. Borrowing it here would make a non-zero verification result
    #   ambiguous between "warned" and "failed", so the one signal an operator needs from a
    #   verification -- whether the load can be trusted -- would stop being readable from the exit
    #   status alone. Nothing in this module configures a tolerance, and the absence is asserted
    #   below rather than left to a reader's inspection.
    counts = verify_row_count_rows(_count_report())
    assert isinstance(counts.verified, bool)
    measured = _measured_source_totals(fixture_corpus)
    money = verify_money_total_rows(_money_report(measured), measured)
    assert isinstance(money.verified, bool)
    comparison = _compare([_source_account()], [_readback_account()])
    assert isinstance(comparison.verified, bool)
    for verdict in (*RowCountVerdict, *MoneyParityVerdict):
        assert isinstance(verdict.value, str)
        assert not verdict.value.isdigit()
    # WHY : Assumptions: one cent is the tolerance asserted, because it is the smallest difference a
    #   NUMERIC(p,2) column can carry and therefore the whole of the question. A pass that tolerated
    #   it would tolerate any systematic rounding defect that stayed below its threshold.
    key = ("account.accounts", "curr_bal")
    off_by_one_cent = measured[key].total + Decimal("0.01")
    perturbed = verify_money_total_rows(
        _money_report(measured, overrides={key: (off_by_one_cent, measured[key].negative_rows)}),
        measured,
    )
    assert perturbed.verified is False
    assert perturbed.mismatches[0].total_difference == Decimal("0.01")


def test_no_corruption_assertion_in_this_module_wears_a_tolerance_mark() -> None:
    """Refuse any mark on this module's own tests other than parametrisation.

    Purpose
    -------
    Keep the corruption assertions unskippable. An expected-failure or a skip mark on one of them is
    a
    verification check switched off in place, and it would leave the suite green while proving
    nothing -- which is exactly the outcome this module exists to deny.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If any test in this module carries a mark outside :data:`_PERMITTED_MARKS`.
    """
    # WHY : Assumptions: the module's own source is parsed rather than searched as text, so a mark
    #   inside a docstring or a comment cannot trigger this check and a mark spelled across two
    #   lines cannot evade it. `pytest.mark.<name>` is matched on the attribute chain, which is the
    #   only form a mark can take.
    tree = ast.parse(pathlib.Path(__file__).read_text(encoding="utf-8"))
    found: list[str] = []
    for node in ast.walk(tree):
        if not isinstance(node, ast.FunctionDef):
            continue
        for decorator in node.decorator_list:
            expression = decorator.func if isinstance(decorator, ast.Call) else decorator
            attributes: list[str] = []
            while isinstance(expression, ast.Attribute):
                attributes.append(expression.attr)
                expression = expression.value
            if isinstance(expression, ast.Name) and expression.id == "pytest" and attributes:
                found.append(attributes[0])
    assert found, "this module carries no marks at all, so the check has lost its subject"
    assert set(found) <= _PERMITTED_MARKS


def test_two_runs_of_each_pass_render_byte_identical_reports(
    fixture_corpus: FixtureCorpus,
) -> None:
    """Render each pass's report twice to the same bytes, with nothing run-varying in the text.

    Purpose
    -------
    Prove the output is line-diffable. Both shipped queries fix their row order on an unprojected
    sort
    key precisely so that two runs over unchanged data diff to nothing, and a single clock reading
    in
    the rendering would defeat that at the last step.

    Parameters
    ----------
    fixture_corpus : FixtureCorpus
        Read-only accessor over ``tests/fixtures``, supplying the money pass's measurements.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If any report renders differently twice, or if a rendering carries a timestamp.
    """
    measured = _measured_source_totals(fixture_corpus)
    renderings = (
        verify_row_count_rows(_count_report(short="tcatbal")).render(),
        verify_money_total_rows(_money_report(measured), measured).render(),
        _compare([_source_account(balance="1.00")], [_readback_account(balance="2.00")]).render(),
    )
    repeated = (
        verify_row_count_rows(_count_report(short="tcatbal")).render(),
        verify_money_total_rows(_money_report(measured), measured).render(),
        _compare([_source_account(balance="1.00")], [_readback_account(balance="2.00")]).render(),
    )
    assert renderings == repeated
    # WHY : Assumptions: the timestamp SHAPE is matched rather than a clock value, and the pattern
    #   is a literal rather than a reading of today's date. A check built from the current date
    #   would itself depend on the wall clock, which is the property this case exists to deny, and
    #   it would stop detecting a stamp written on any other day.
    for rendered in renderings:
        assert _ADMITTED_STAMP not in rendered
        assert not _TIMESTAMP_SHAPE.search(rendered)


def test_every_typed_verification_error_is_raised_rather_than_asserted() -> None:
    """Hold every typed failure to an exception class that survives an optimised interpreter.

    Purpose
    -------
    Prove the refusals cannot be optimised away. ``python -O`` strips ``assert`` statements
    entirely,
    so a validation expressed as a bare assertion is a validation that disappears in exactly the
    configuration an operator is most likely to run a cutover in.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If any typed error descends from :class:`AssertionError`, or if one is not a
        :class:`RuntimeError` the caller can catch by category.
    """
    typed = (
        ResultSetContractError,
        RowCountVerificationError,
        VerificationQueryError,
        ChecksumVerificationError,
        ChecksumValueError,
        TimestampContractError,
        MoneyResultSetContractError,
        VerificationGateError,
    )
    for error in typed:
        assert issubclass(error, RuntimeError)
        assert not issubclass(error, AssertionError)
    # WHY : Assumptions: the checksum value error is additionally a TypeError, which is deliberate
    #   and is asserted so the dual inheritance is not "corrected" later: a caller passing a value
    #   of the wrong class expects a TypeError, and a caller handling verification failures expects
    #   the subpackage's own base, so the class answers to both without either caller special-casing
    #   it.
    assert issubclass(ChecksumValueError, TypeError)
    assert issubclass(TimestampContractError, ChecksumVerificationError)
    assert issubclass(ResultSetContractError, RowCountVerificationError)
    assert issubclass(VerificationQueryError, RowCountVerificationError)
