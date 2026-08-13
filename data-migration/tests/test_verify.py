"""Prove the record-checksum pass can pair, canonicalise and localise what a real load produces.

Purpose
-------
``verify/checksum.py`` is the mandatory second pass of the three the migration plan requires, and
it is the only one that compares VALUES rather than aggregates. Its correctness therefore rests on
three properties no aggregate check can stand in for: that a value reaching it from a fixed-width
extract and the same value reaching it back from a database driver canonicalise identically, that
the two sides are put in correspondence before they are walked, and that a disagreement is located
by name without any field content reaching the report. This module holds all three, plus the
framing property that makes the digest a digest rather than a coincidence.

Why this module exists beside ``test_verification.py``
-----------------------------------------------------
Refactoring Rationale: the sibling module proves the three passes are INDEPENDENT of one another --
that each detects the defect the other two cannot -- and it exercises the digest entry points to do
so. What it does not reach is the comparison: ``compare_record_digests``, the pairing, the
timestamp contract, the difference localisation and the report bound were delivered untested, so a
pass that could false-pass, false-fail, or terminate outside its binary exit contract was the
mandatory gate of a cutover. Splitting rather than extending keeps each module's purpose readable:
one is about the three passes as a set, this one is about the second pass as a mechanism.

Assumptions:
    Every value class this module exercises is one a real run produces on one of the two sides,
    and the pairs are named rather than generic. ``psycopg`` returns ``int`` for the ``BIGINT`` and
    ``SMALLINT`` columns, ``None`` for a nullable column holding nothing, ``datetime.date`` for
    ``DATE``, ``datetime.datetime`` for ``TIMESTAMP(6)``, ``uuid.UUID`` for the derived subject
    column and ``Decimal`` for ``NUMERIC(p,2)``; the reader and the loader's projections produce
    characters, exact decimals and raw bytes. A canonicalisation that admitted only the second set
    -- which is what shipped -- could not verify any dataset carrying an identifier, a nullable
    column or a stamp.

Trade-offs:
    The records here are hand-built rather than read from the shipped corpus, which is the opposite
    of the choice ``test_readers.py`` makes and is deliberate for the same reason the sibling
    module gives: what is under test is SENSITIVITY, and that needs pairs constructed to differ in
    exactly one respect. The one place a shipped artefact is used is the layout registry, because
    the descriptors are the authority on which stamp is non-deterministic and on where a key
    window falls, and restating either here would create a second authority free to disagree.
"""

from __future__ import annotations

import datetime
import uuid
from decimal import Decimal

import pytest

from carddemo_migration.copybook import layouts
from carddemo_migration.verify.checksum import (
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
    # WHY : this is the case the delivered canonicalisation could not express at all: the target
    #   side's `int` and `datetime.date` both reached a bare `raise TypeError`, so the mandatory
    #   pass could not verify the account master -- the first dataset a cutover loads.
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
    # WHY : the microsecond part is always six digits, which is why `isoformat` is not used: it
    #   omits the fraction entirely on an exact second, so a correct stamp would render 19
    #   characters against the source's 26 and report a difference.
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
    # WHY : the loader's own TRIMMED_OR_NULL projection makes exactly this distinction on the way
    #   in -- a blank second address line becomes NULL and an empty-but-present value does not --
    #   so a comparison that could not tell them apart would confirm a load that lost it.
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
    # WHY : the value CLASS is inside the frame, so the reconciliation of a numeric identifier is
    #   granted by the descriptor and never by the spelling. Without the class tag a text column
    #   holding "11" would compare equal to an integer column holding 11, which is the direction
    #   that hides a load writing a value into the wrong column.
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
        The assertion is the result.

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
        The assertions are the result.

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
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the message omits the field description or echoes the value.
    """
    secret = "4111111111111111"
    with pytest.raises(ChecksumValueError) as refused:
        digest_of_record({"ACCT-ID": [secret]}, ("ACCT-ID",), layout=_ACCOUNT)
    message = str(refused.value)
    assert "ACCT-ID" in message
    # WHY : a verification report is the artefact most likely to be pasted into an issue tracker,
    #   and these records carry primary account numbers, so a refusal names the field and the type
    #   and never the content.
    assert secret not in message


def test_a_differing_field_is_located_by_name_and_interval() -> None:
    """Report the one differing field with its descriptor and no value on either side.

    Returns
    -------
    None
        The assertions are the result.

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
    # WHY : the descriptor's own description carries the half-open byte interval, which is what
    #   lets an operator go to the span in the extract rather than to the value in a log line.
    assert "12" in rendered
    assert "1234.56" not in rendered
    assert "1234.57" not in rendered


def test_a_row_present_on_one_side_only_is_reported_at_its_position() -> None:
    """Report a load that stopped early and one that ran twice, each at the position it diverged.

    Returns
    -------
    None
        The assertions are the result.

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

    Raises
    ------
    AssertionError
        If a mis-ordered but corresponding pair is reported at all, or if a key disagreement is
        reported as a field difference.
    """
    # WHY : ⚠️ Refactoring Rationale: this case asserted that two sides in different key orders were
    #   REPORTED as unpaired, on the reasoning that the source arrives in extract order while the
    #   read-back arrives ordered by the server, so a correctly loaded master whose extract is not
    #   sorted that way was compared record-against-wrong-row. The defect was real and its remedy is
    #   now stronger than a report: `compare_record_digests` SORTS both sides on the canonical key
    #   before it walks them, so the arrival order of neither side can decide which record is
    #   compared against which row. A mis-ordered pair therefore verifies -- which is the correct
    #   verdict, because the DATA is correct and only the caller's ordering differed. Reporting it
    #   would have sent an operator to investigate a load that is sound.
    corresponding = _compare(
        [_source_account("00000000011"), _source_account("00000000012")],
        [_readback_account(12), _readback_account(11)],
    )
    assert corresponding.verified
    assert corresponding.unpaired_records == 0
    assert corresponding.differing_records == 0

    # WHY : ⚠️ Assumptions: the guarantee the withdrawn assertion was really reaching for is
    #   asserted here instead, on the input the sort CANNOT rescue -- a key one side carries and the
    #   other does not. That is reported as an unpaired position at its own key and as zero field
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
    # WHY : the bound costs the TAIL of a large failure and keeps the counts exact, so a wholly
    #   wrong load reports a diagnostic rather than a file -- and still reports its true size.
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
    # WHY : ⚠️ Refactoring Rationale: this case ASSERTED THE OPPOSITE and is restated rather than
    #   deleted, so the rule it held and the reason it was withdrawn both stay on the record.
    #   It held that a comparison walking no position must fail, by analogy with the row-count
    #   report, on the
    #   ground that "the reader yielded nothing and the table is empty" is indistinguishable from an
    #   unreadable extract or a load that never ran. The analogy does not survive measurement: a
    #   report is built from a query, where an empty result set genuinely is ambiguous, while this
    #   pass is handed both sides explicitly and every accidental route to an empty one RAISES
    #   before
    #   a verdict -- an unreadable extract raises `OSError`, a truncated one `RecordLengthError`, an
    #   unknown layout `LayoutError`, and a load that never ran leaves a non-empty source paired
    #   against an empty table, which reports absent records rather than nothing.
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
    # WHY : the originating stamp is deterministic -- every populated value in the shipped extract
    #   reads the same 26 characters -- so dropping it would discard business data the comparison
    #   exists to verify. A candidate the layout does not declare is KEPT, which is what lets a
    #   derived column such as the subject reference be compared at all.
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

    Raises
    ------
    AssertionError
        If an unadmitted class passes, which would make excluding a field from the compared span a
        way of not looking at it.
    """
    # WHY : ⚠️ this is the case the delivered guard admitted: it returned every non-string value
    #   unchanged on the stated premise that only `None` and a driver `datetime` could arrive, so
    #   an arbitrary object in a marked stamp passed the one check whose purpose is to catch a
    #   mis-sliced or corrupt record.
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

    Raises
    ------
    AssertionError
        If either admitted class is refused, or if an offset-carrying timestamp is admitted.
    """
    field = _stamp_field()
    naive = datetime.datetime(2022, 6, 10, 19, 27, 53)
    assert validated_timestamp(field, naive) is naive
    assert validated_timestamp(field, None) is None
    # WHY : the columns are TIMESTAMP(6) WITHOUT time zone and the source span carries no offset,
    #   so admitting an aware value would compare a moment against a wall-clock reading of it.
    with pytest.raises(TimestampContractError, match="time-zone offset"):
        validated_timestamp(field, naive.replace(tzinfo=datetime.UTC))


def test_a_stamp_of_the_wrong_width_reports_the_slice_rather_than_the_content() -> None:
    """Refuse a stamp whose width is not the declared one, naming the widths and no content.

    Returns
    -------
    None
        The assertions are the result.

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
    # WHY : the same window is what `loaders/aurora.py` derives the merge's conflict target from,
    #   so this assertion is also what keeps the pass pairing on the identity the load conflicts on.
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
    # WHY : the identifiers are rendered as INTEGERS for an unsigned display key, so 9 sorts before
    #   12 -- where a text rendering of the zero-padded spellings would also sort 9 first and a text
    #   rendering of the unpadded driver values would not. Ordering both sides on this one rendering
    #   is what takes the database collation out of the comparison.
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
    # WHY : the collision vector is spelled out rather than described: a value that itself contains
    #   `<letter><delimiter><digits><delimiter>` is exactly what a delimiter-only framing could not
    #   distinguish from a real header, and the length prefix is what makes it distinguishable.
    forged = f"s{delimiter}1{delimiter}A"
    assert digest_of_record({"LEFT": forged, "RIGHT": "B"}, fields) != digest_of_record(
        {"LEFT": "s", "RIGHT": f"1{delimiter}AB"}, fields
    )
    # WHY : and the two code points the previous framing used as separators are ordinary content
    #   now, so a record carrying either must still digest differently from a different split of
    #   the same characters.
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
    # WHY : the digests are published because they are the one comparable value an operator can
    #   quote in a ticket, and they disclose nothing about any record.
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
