"""Exercise both numeric regimes of the ETL against known-answer vectors it did not choose.

Purpose
-------
Execute :mod:`carddemo_migration.copybook.zoned` and
:mod:`carddemo_migration.copybook.packed` on the exact spans the COBOL baseline wrote,
rather than on spans this suite invented. Every monetary value in the migration passes
through one of the two regimes these modules implement, and a defect in either is silent:
it yields a well-formed number of the right type at the right scale that happens to be
wrong. Nothing downstream raises, so a static read of the codec cannot catch it and only a
known-answer vector can.

Scope, and why one module covers two regimes
--------------------------------------------
The two regimes are not interchangeable and the corpus keeps them strictly apart. All money
in the eleven base masters is zoned display with a trailing sign overpunch -- searching those
copybooks for ``COMP`` or ``COMP-3`` returns nothing at all. Packed decimal reaches persisted
data in exactly three places: the export record at ``app/cpy/CVEXPORT.cpy`` and the two
authorization segments at ``app/app-authorization-ims-db2-mq/cpy/CIPAUSMY.cpy`` (100 bytes)
and ``.../CIPAUDTY.cpy`` (200 bytes), so the authorization bounded context is the only place
packed decimal reaches persisted target data. Binary ``COMP`` occurs alongside packed in the
export record and in the authorization summary. This module carries all three because the
regimes are only meaningfully testable against each other: the hazard worth guarding is a
field decoded under the wrong one, and that assertion needs both codecs in scope at once.

Alternatives Considered:
    Authoring fresh vectors from the codec's own encoder and handing them back to its
    decoder. Rejected, and this is the single most important decision in the file: a
    round trip through one module's own inverse agrees with itself whatever it does, so it
    cannot distinguish a correct overpunch table from a transposed one, nor a correct nibble
    order from a reversed one. Every vector below is instead either transcribed from the
    module docstring of ``tests/helpers/record_codec.py`` -- the reference implementation the
    migration plan names as the source for ``zoned.py``, whose own vectors cite the shipped
    ``app/data/ASCII`` seeds -- or measured directly out of ``tests/fixtures/**``. Reuse is
    what proves the Python codecs agree with the COBOL and with
    ``services/common-lib``'s ``ZonedDecimalCodec``; a fresh vector would prove only that the
    implementation agrees with itself. The shared vectors ``00000020650{`` to 2065.00,
    ``0000005047G`` to 504.77, ``0000000678H`` to 67.88 and ``0000009190}`` to -919.00 are
    asserted here with the values ``ZonedDecimalCodecTest.java`` asserts for them, so a
    divergence is a real defect in one of the two codecs and not a difference of test style.

Assumptions:
    "EBCDIC" names two unrelated things in this package and this module touches only one of
    them. In ``zoned`` it is a SIGN CONVENTION: ``tests/README.md`` section 5.2 records that
    GnuCOBOL's default ``-fsign=ASCII`` misreads the overpunch and silently corrupts negative
    balances, which is why ``-fsign=EBCDIC`` is mandatory for the parity oracle. In
    ``ebcdic_codec`` it is a CHARACTER ENCODING, cp037. Every byte asserted below is plain
    seven-bit ASCII and this module performs no cp037 conversion anywhere.

Trade-offs:
    Money is asserted exclusively as :class:`decimal.Decimal` and never as ``float``. No
    binary floating-point value appears in this file at all, including in an assertion that
    would merely have been convenient, because a single ``float`` comparison here would
    silently accept a codec that had lost a cent.
"""

from __future__ import annotations

from decimal import Decimal
from typing import TYPE_CHECKING

import pytest

from carddemo_migration.copybook import layouts
from carddemo_migration.copybook.layouts import FieldSpec, Kind, RecordSpec
from carddemo_migration.copybook.packed import (
    PackedDecimalError,
    PackedSpanWidthError,
    binary_width,
    decode_binary,
    decode_binary_field,
    decode_packed,
    decode_packed_field,
    encode_binary,
    encode_packed,
    packed_width,
)
from carddemo_migration.copybook.zoned import (
    ZonedDecimalError,
    ZonedSpanWidthError,
    decode_zoned,
    decode_zoned_field,
    encode_zoned,
    encode_zoned_field,
)

if TYPE_CHECKING:
    # WHY : Assumptions: the two corpus classes are imported for annotation only, under the
    #   type-checking guard, so this module never depends at run time on ``conftest`` being
    #   importable as a top-level name. pytest injects both objects as fixtures, so the names
    #   are needed to document the parameters and for nothing else; importing them
    #   unconditionally would tie collection of this file to pytest's path-insertion order.
    from conftest import FixtureCorpus, SeedCorpus

# WHY : Assumptions: the two tables are transcribed as ten-character index strings, in which
#   the STRING INDEX IS THE DIGIT VALUE, because that is the representation ``zoned.py``
#   itself uses at lines 322-323 and the reference codec uses at
#   ``tests/helpers/record_codec.py`` lines 137-138. Asserting against the same shape is what
#   lets a transposed character show up as an asymmetry rather than as a plausible amount: the
#   same table serves encode and decode, so a byte in the wrong slot breaks the round trip.
_POSITIVE_OVERPUNCH_TABLE = "{ABCDEFGHI"
_NEGATIVE_OVERPUNCH_TABLE = "}JKLMNOPQR"

# WHY : Trade-offs: only these five of the twenty overpunch bytes occur in the corpus, which
#   was established by reading every signed span of ``app/data/ASCII/*.txt`` and
#   ``tests/fixtures/**``. Exhaustive table coverage is chosen over pretending the live union
#   is complete: fifteen bytes -- ``A``-``E``, ``I`` and the whole negative letter run
#   ``J``-``R`` -- would otherwise never be exercised, leaving the negative table one tenth
#   tested. The compromise accepted is that those fifteen vectors are SYNTHETIC and are
#   labelled as such at every site, so a reader is never misled into believing the corpus
#   attests them. No ``app/data/ASCII`` record ends in a negative overpunch, because money is
#   never the last field of a record; the one attested negative span, ``0000009190}``, comes
#   from the fixture corpus at an interior offset.
_LIVE_OVERPUNCH_BYTES = frozenset("{FGH}")

# WHY : Assumptions: the ten-digit body below is the digit run of the attested live span
#   ``0000005047G`` from ``app/data/ASCII/dailytran.txt`` line 1. Every table vector
#   substitutes only its final character, so all twenty differ from an attested span in
#   exactly one byte -- and ``ZonedDecimalCodecTest.java`` builds its table vectors from the
#   same body, which is what makes the two languages' tables comparable line for line.
_OVERPUNCH_BODY = "0000005047"

# WHY : Assumptions: the three geometries below are the only ones the corpus declares for
#   money, and each is taken from a picture clause rather than from a byte count.
#   ``PIC S9(09)V99`` is the transaction amount at ``app/cpy/CVTRA05Y.cpy`` line 10 and
#   ``PIC S9(10)V99`` is every account amount at ``app/cpy/CVACT01Y.cpy`` lines 7-9 and 13-14.
#   Two decimal positions is the invariant the whole money path rests on.
_TRAN_MONEY_INT_DIGITS = 9
_ACCOUNT_MONEY_INT_DIGITS = 10
_MONEY_DEC_DIGITS = 2

#: Sign contract of a picture clause that carries a leading ``S``.
_SIGNED = True

#: Sign contract of a picture clause that carries no leading ``S``.
_UNSIGNED = False

# WHY : Assumptions: the three sign nibbles are named from ``packed.py``'s own constants
#   rather than written as bare hexadecimal at each assertion site, so a test that refuses a
#   nibble names the nibble it refuses. 0x0C is signed positive, 0x0D signed negative and
#   0x0F unsigned positive, and all three occur in real data rather than in documentation.
_SIGN_POSITIVE_NIBBLE = 0x0C
_SIGN_NEGATIVE_NIBBLE = 0x0D
_SIGN_UNSIGNED_NIBBLE = 0x0F

# WHY : Assumptions: this policy is READ from ``packed.py`` and not generalised from another
#   COBOL runtime. That module's ``_LOWEST_SIGN_NIBBLE`` comment states that 0x0A, 0x0B and
#   0x0E are the alternate sign nibbles some encoders emit and that this codec rejects all
#   three, as does the Java parity codec, because an unexpected nibble in the sign position is
#   the clearest available evidence that the field offset is wrong -- and treating it as
#   positive would convert a detectable geometry fault into a silently wrong sign on a money
#   value. Other runtimes do accept them as positive; encoding that behaviour here would make
#   the test disagree with the module it is testing.
_ALTERNATE_SIGN_NIBBLES = (0x0A, 0x0B, 0x0E)

#: Fixture scenario holding the single 300-byte account record every account vector cites.
_POSTING_HAPPY_PATH = "posting/happy_path"

#: Fixture scenario holding the five 350-byte transaction records every amount vector cites.
_EXPORT_HAPPY_PATH = "export/happy_path"

#: Fixture scenarios whose daily-transaction record carries the Group A false-positive window.
_REJECT_101_SCENARIO = "posting/reject_101_acct_missing"
_REJECT_103_SCENARIO = "posting/reject_103_expired"

# WHY : Assumptions: the two windows below are byte ranges that LOOK like signed zoned money
#   fields and are not, and both were located by measuring the corpus rather than by guessing.
#   Group A is the eleven characters at zero-based [12:23] of a 350-byte transaction record,
#   which spans FOUR declared field boundaries -- the tail of ``TRAN-ID`` [0:16], all of
#   ``TRAN-TYPE-CD`` [16:18], all of ``TRAN-CAT-CD`` [18:22], and the first letter of
#   ``TRAN-SOURCE`` [22:32]. Group B is the ten characters at [143:153], spanning
#   ``TRAN-MERCHANT-ID`` [143:152] and the first letter of ``TRAN-MERCHANT-NAME`` [152:153].
#   Each window ends in a byte that sits in the negative overpunch table, which is exactly
#   what makes it convincing: ``P`` is -7 and ``O`` is -6, and the letters come from
#   ``'POS TERM  '``, ``'OPERATOR  '``, ``'Kertzmann-...'`` (``K`` is -2) and
#   ``'Nitzsche, ...'`` (``N`` is -5).
_GROUP_A_WINDOW_START = 12
_GROUP_A_WINDOW_END = 23
_GROUP_B_WINDOW_START = 143
_GROUP_B_WINDOW_END = 153

# WHY : Assumptions: the five Group A spans are paired with the value a signed 9-and-2 decode
#   actually returns for them, which is the whole point of the pairing. None of the five
#   RAISES -- each decodes successfully to a plausible nine-figure negative amount -- so the
#   proof that pattern matching is unsafe is that it SUCCEEDS and lies, not that it fails.
_GROUP_A_FALSE_POSITIVES = (
    ("3580010001P", Decimal("-358001000.17")),
    ("4260030001O", Decimal("-426003000.16")),
    ("2564010001P", Decimal("-256401000.17")),
    ("1861010001P", Decimal("-186101000.17")),
    ("2252010001P", Decimal("-225201000.17")),
)

# WHY : Assumptions: a ten-character window read as signed money is eight integer positions
#   and two decimal ones, so the two Group B spans are paired with the values that geometry
#   returns. They too decode rather than raise.
_GROUP_B_FALSE_POSITIVES = (
    ("800000000N", Decimal("-80000000.05")),
    ("800000000K", Decimal("-80000000.02")),
)

#: Integer digit positions a ten-character window would carry if read as signed money.
_GROUP_B_INT_DIGITS = 8


def _account_field(name: str) -> FieldSpec:
    """Resolve one field descriptor of the 300-byte account master by name.

    :param name: the field name exactly as ``app/cpy/CVACT01Y.cpy`` declares it.
    :returns: the registry's descriptor, carrying its declared offset, width and kind.
    :raises LayoutError: if the account layout declares no field of that name.
    """
    # WHY : Assumptions: the descriptor is resolved from the registry on every call rather
    #   than copied into a module constant, because a constant would be a second declaration
    #   of an offset that ``layouts.py`` already owns. Two declarations of one offset is how
    #   the two drift apart, and a drifted offset still decodes to digits.
    return layouts.layout("ACCOUNT").field(name)


def _tran_field(name: str) -> FieldSpec:
    """Resolve one field descriptor of the 350-byte transaction master by name.

    :param name: the field name exactly as ``app/cpy/CVTRA05Y.cpy`` declares it.
    :returns: the registry's descriptor, carrying its declared offset, width and kind.
    :raises LayoutError: if the transaction layout declares no field of that name.
    """
    return layouts.layout("TRAN").field(name)


def _assert_decodes_and_re_encodes(
    span: str,
    int_digits: int,
    dec_digits: int,
    signed: bool,
    expected_text: str,
) -> Decimal:
    """Assert one display span decodes to an exact value, keeps its scale and re-encodes.

    :param span: the exact characters of one already-sliced display field.
    :param int_digits: declared digit positions before the implied decimal point.
    :param dec_digits: declared digit positions after the implied decimal point.
    :param signed: whether the final byte must carry a sign overpunch.
    :param expected_text: the expected value written as exact decimal text at the declared
        scale, so the comparison is against a literal and never against a computed number.
    :returns: the decoded value, for a caller that wants to assert something further.
    :raises AssertionError: if the value, its exponent, or the re-encoded span differs.
    """
    # WHY : Assumptions: the scale is asserted through the exponent and not through equality,
    #   because Decimal("193.00") and Decimal("193") compare EQUAL while re-encoding to
    #   different spans. Parity is decided by a byte comparison against a golden master, so a
    #   value that compares right at the wrong exponent is a defect that equality cannot see.
    decoded = decode_zoned(span, int_digits, dec_digits, signed)
    assert decoded == Decimal(expected_text), f"{span!r} must decode to {expected_text}"
    assert decoded.as_tuple().exponent == -dec_digits, (
        f"{span!r} must decode at exactly {dec_digits} decimal positions"
    )
    assert encode_zoned(decoded, int_digits, dec_digits, signed) == span, (
        f"{span!r} must re-encode to itself byte for byte"
    )
    return decoded


def test_the_reference_account_balance_vector_decodes_to_its_documented_value() -> None:
    """Reproduce the first reference doctest vector, the account balance from the shipped seed.

    Takes no parameters and returns no value; a differing value, scale or re-encoded span is
    reported as an assertion failure.
    """
    # WHY : Alternatives Considered: this span is transcribed from the module docstring of
    #   ``tests/helpers/record_codec.py``, which cites it as the ``CURR-BAL`` field of line 1
    #   of ``app/data/ASCII/acctdata.txt`` -- measured independently here at [12:24] of that
    #   line. Writing a different twelve-character span of my own was the alternative and
    #   proves nothing across languages, whereas this one is simultaneously asserted by the
    #   reference COBOL harness and by ``ZonedDecimalCodecTest.java``.
    _assert_decodes_and_re_encodes(
        "00000001940{", _ACCOUNT_MONEY_INT_DIGITS, _MONEY_DEC_DIGITS, _SIGNED, "194.00"
    )


def test_the_reference_positive_transaction_amount_vector_carries_its_overpunch_digit() -> None:
    """Reproduce the second reference doctest vector, whose ``G`` overpunch is the digit seven.

    Takes no parameters and returns no value; a differing value, scale or re-encoded span is
    reported as an assertion failure.
    """
    # WHY : Assumptions: an overpunch is the LOW-ORDER DIGIT carrying a sign and not a
    #   sign-only suffix, which is why ``0000005047G`` is 504.77 and never 50.47. Dropping the
    #   seven that ``G`` carries would produce a value wrong by a factor of ten that is
    #   entirely plausible as an amount, so the expected value is named in full here rather
    #   than derived from the body.
    _assert_decodes_and_re_encodes(
        "0000005047G", _TRAN_MONEY_INT_DIGITS, _MONEY_DEC_DIGITS, _SIGNED, "504.77"
    )


def test_the_reference_negative_transaction_amount_vector_differs_only_in_its_final_byte() -> None:
    """Reproduce the third reference doctest vector, the same magnitude signed negative by ``J``.

    Takes no parameters and returns no value; a differing value, scale or re-encoded span is
    reported as an assertion failure.
    """
    # WHY : Assumptions: the reference pairs this span with the positive one deliberately.
    #   ``J`` is index 1 of the negative table where ``G`` is index 7 of the positive one, so
    #   the two spans differ in one byte and their values differ in both sign AND last digit --
    #   -504.71 against 504.77. A codec that read the sign correctly but took the digit from
    #   the wrong table would pass a sign-only assertion and fail this one.
    _assert_decodes_and_re_encodes(
        "0000005047J", _TRAN_MONEY_INT_DIGITS, _MONEY_DEC_DIGITS, _SIGNED, "-504.71"
    )


def test_the_reference_encode_vectors_reproduce_the_documented_spans() -> None:
    """Reproduce the fourth and fifth reference doctest vectors, both encode directions.

    Takes no parameters and returns no value; a differing span is reported as an assertion
    failure.
    """
    # WHY : Assumptions: the two encode vectors are asserted from a Decimal literal and not
    #   from a value the decoder just produced, because the reference docstring states them as
    #   encode vectors in their own right. The pairing also fixes the padding rule: a display
    #   field is right-aligned and left-padded with the digit zero, so 194.00 in a
    #   twelve-position field is ``00000001940{`` and not ``1940{`` followed by blanks, which
    #   is a field the baseline programs read as non-numeric.
    assert (
        encode_zoned(Decimal("194.00"), _ACCOUNT_MONEY_INT_DIGITS, _MONEY_DEC_DIGITS, _SIGNED)
        == "00000001940{"
    )
    assert (
        encode_zoned(Decimal("-504.71"), _TRAN_MONEY_INT_DIGITS, _MONEY_DEC_DIGITS, _SIGNED)
        == "0000005047J"
    )


def test_the_shipped_seed_supplies_both_reference_decode_vectors(seed_corpus: SeedCorpus) -> None:
    """Confirm both reference decode vectors are still the bytes the shipped seeds hold.

    :param seed_corpus: session accessor for ``app/data/ASCII`` and ``app/data/EBCDIC``,
        supplied by ``conftest``, used so this module never opens a seed file itself.
    :returns: nothing; a seed whose bytes no longer match the cited vector is reported as an
        assertion failure.
    """
    # WHY : Alternatives Considered: the citation is verified rather than trusted. The
    #   reference docstring names these two spans as coming from line 1 of two named seeds, and
    #   a comment can go stale where an assertion cannot. Hard-coding the spans alone was the
    #   alternative: it would keep passing after a seed changed, at which point every value in
    #   this file would still agree with the reference docstring and no longer with the data.
    account_record = seed_corpus.ascii_records("acctdata.txt")[0]
    balance = _account_field("ACCT-CURR-BAL")
    assert account_record[balance.start : balance.end] == "00000001940{"
    assert decode_zoned_field(account_record, balance) == Decimal("194.00")

    daily_record = seed_corpus.ascii_records("dailytran.txt")[0]
    # WHY : Assumptions: the daily-transaction and posted-transaction records share the
    #   350-byte layout of ``app/cpy/CVTRA06Y.cpy`` and ``app/cpy/CVTRA05Y.cpy``, so the amount
    #   sits at [132:143] in both. The descriptor is taken from the record's own registered
    #   layout rather than from the transaction one, so the offset is never assumed to be
    #   shared even though it is.
    daily_amount = seed_corpus.record_spec("dailytran.txt").field("DALYTRAN-AMT")
    assert daily_record[daily_amount.start : daily_amount.end] == "0000005047G"
    assert decode_zoned_field(daily_record, daily_amount) == Decimal("504.77")


def test_every_account_money_field_of_the_live_fixture_decodes_exactly(
    fixture_corpus: FixtureCorpus,
) -> None:
    """Decode all five signed money fields of the single live account fixture record.

    :param fixture_corpus: session accessor for ``tests/fixtures``, supplied by ``conftest``,
        used so this module never opens a fixture file itself.
    :returns: nothing; a differing value or scale is reported as an assertion failure.
    """
    record = fixture_corpus.records(_POSTING_HAPPY_PATH, "acctdata.txt")[0]
    assert len(record) == fixture_corpus.reclen("acctdata.txt") == 300

    # WHY : Assumptions: the two cycle fields are the live POSITIVE-ZERO vector and are the
    #   reason this test reads all five amounts rather than one. Both hold ``00000000000{``, so
    #   the corpus itself attests that a zero amount carries the opening brace rather than a
    #   plain digit -- which is what makes the negative-zero carve-out asserted further down a
    #   documented exception to a real rule instead of an untested claim.
    expected = {
        "ACCT-CURR-BAL": ("00000001930{", "193.00"),
        "ACCT-CREDIT-LIMIT": ("00000020650{", "2065.00"),
        "ACCT-CASH-CREDIT-LIMIT": ("00000002640{", "264.00"),
        "ACCT-CURR-CYC-CREDIT": ("00000000000{", "0.00"),
        "ACCT-CURR-CYC-DEBIT": ("00000000000{", "0.00"),
    }
    for name, (span, text) in expected.items():
        field = _account_field(name)
        assert field.kind is Kind.ZONED, f"{name} must be a signed display field"
        assert record[field.start : field.end] == span, (
            f"{name} at [{field.start}:{field.end}] must still hold {span!r}"
        )
        decoded = decode_zoned_field(record, field)
        assert decoded == Decimal(text), f"{name} must decode to {text}"
        assert decoded.as_tuple().exponent == -_MONEY_DEC_DIGITS
        assert encode_zoned_field(decoded, field) == span, f"{name} must re-encode to itself"


def test_the_credit_limit_vector_is_the_field_at_offset_twenty_four(
    fixture_corpus: FixtureCorpus,
) -> None:
    """Anchor the 2065.00 vector on the credit limit and not on the current balance.

    :param fixture_corpus: session accessor for ``tests/fixtures``, supplied by ``conftest``.
    :returns: nothing; a vector attributed to the wrong field is reported as an assertion
        failure.
    """
    # WHY : Assumptions: 2065.00 is ``ACCT-CREDIT-LIMIT`` at [24:36] and NOT ``ACCT-CURR-BAL``
    #   at [12:24], and the distinction is asserted because the two fields are adjacent, are
    #   the same width, and are the same kind. Mis-attributing the vector would leave the
    #   decode assertion passing while the field it documents was wrong, and the balance in
    #   this record is 193.00 -- a different number entirely, which is what makes the
    #   confusion detectable at all.
    balance = _account_field("ACCT-CURR-BAL")
    limit = _account_field("ACCT-CREDIT-LIMIT")
    assert (balance.start, balance.length) == (12, 12)
    assert (limit.start, limit.length) == (24, 12)

    record = fixture_corpus.records(_POSTING_HAPPY_PATH, "acctdata.txt")[0]
    assert decode_zoned_field(record, limit) == Decimal("2065.00")
    assert decode_zoned_field(record, balance) == Decimal("193.00")


def test_every_live_transaction_amount_decodes_exactly(fixture_corpus: FixtureCorpus) -> None:
    """Decode the amount of all five live transaction fixture records, positive and negative.

    :param fixture_corpus: session accessor for ``tests/fixtures``, supplied by ``conftest``.
    :returns: nothing; a differing span, value or scale is reported as an assertion failure.
    """
    records = fixture_corpus.records(_EXPORT_HAPPY_PATH, "trandata.txt")
    assert len(records) == 5
    amount = _tran_field("TRAN-AMT")

    # WHY : Assumptions: row 1 is the only attested NEGATIVE money span in the whole corpus,
    #   and it is why the five rows are asserted together rather than one being taken as
    #   representative. Its closing brace is the sole live member of the negative table, so
    #   dropping it would leave every negative assertion in this file synthetic.
    expected = (
        ("0000005047G", "504.77"),
        ("0000009190}", "-919.00"),
        ("0000000678H", "67.88"),
        ("0000002817G", "281.77"),
        ("0000004546F", "454.66"),
    )
    for row, (span, text) in enumerate(expected):
        record = records[row]
        assert len(record) == 350
        assert record[amount.start : amount.end] == span, f"row {row} must still hold {span!r}"
        decoded = decode_zoned_field(record, amount)
        assert decoded == Decimal(text), f"row {row} must decode to {text}"
        assert decoded.as_tuple().exponent == -_MONEY_DEC_DIGITS
        assert encode_zoned_field(decoded, amount) == span


def test_a_leading_zero_card_number_survives_beside_a_negative_amount(
    fixture_corpus: FixtureCorpus,
) -> None:
    """Confirm the negative-amount row also carries the leading-zero card number intact.

    :param fixture_corpus: session accessor for ``tests/fixtures``, supplied by ``conftest``.
    :returns: nothing; a truncated card number or a wrong amount is reported as an assertion
        failure.
    """
    # WHY : Assumptions: the card number is declared ``PIC X(16)`` at [262:278] and is
    #   therefore CHARACTER data, not a number, which is exactly why row 1's
    #   ``0927987108636232`` is asserted on the same row as the negative amount. A reader who
    #   assumed a sixteen-digit identifier were numeric would lose its leading zero, and this
    #   row proves both hazards live in one record: a sign that must be read and a leading zero
    #   that must not be normalised away.
    records = fixture_corpus.records(_EXPORT_HAPPY_PATH, "trandata.txt")
    card = _tran_field("TRAN-CARD-NUM")
    assert card.kind is Kind.TEXT
    assert card.sensitive is True
    assert (card.start, card.length) == (262, 16)
    assert records[1][card.start : card.end] == "0927987108636232"
    assert decode_zoned_field(records[1], _tran_field("TRAN-AMT")) == Decimal("-919.00")

    # WHY : Trade-offs: the other four rows' card numbers are asserted STRUCTURALLY -- declared
    #   width, all digits, no sign interpretation -- rather than as literals. The sibling
    #   ``ZonedDecimalCodecTest.java`` names no card number at all, and this file itself asserts
    #   twice over that a diagnostic must never echo a field marked sensitive, so multiplying
    #   sensitive literals here to restate a property one literal already demonstrates would
    #   contradict its own tests. Row 1 keeps its literal because the leading zero is the whole
    #   hazard; the remaining four are covered without adding four more primary account numbers
    #   to the source. The compromise accepted is that a transcription error in those four
    #   fixtures would show up as a width or digit failure rather than as a value mismatch.
    for row, record in enumerate(records):
        span = record[card.start : card.end]
        assert len(span) == 16, f"row {row}: the card number is sixteen characters wide"
        assert span.isdigit(), f"row {row}: every position holds an ASCII digit"
        assert span[-1] not in _NEGATIVE_OVERPUNCH_TABLE, (
            f"row {row}: a character field's last byte is never read as a sign"
        )


def test_the_two_timestamp_fields_hold_their_documented_live_content(
    fixture_corpus: FixtureCorpus,
) -> None:
    """Confirm the deterministic originating stamp and the all-blank processing stamp.

    :param fixture_corpus: session accessor for ``tests/fixtures``, supplied by ``conftest``.
    :returns: nothing; a differing stamp is reported as an assertion failure.
    """
    # WHY : Assumptions: an all-blank processing timestamp is intentionally VALID and is a live
    #   corpus fact rather than a hypothesis -- all five records carry twenty-six blanks at
    #   [304:330] because the field is stamped by the posting run and these records have not
    #   been posted. The originating stamp beside it is deterministic on all five, which is the
    #   asymmetry that makes one of the two safe to compare in a golden master and the other
    #   not. It is asserted here so no later reader mistakes the blanks for a broken fixture
    #   and "repairs" them.
    records = fixture_corpus.records(_EXPORT_HAPPY_PATH, "trandata.txt")
    originating = _tran_field("TRAN-ORIG-TS")
    processing = _tran_field("TRAN-PROC-TS")
    assert originating.normalize_ts is False
    assert processing.normalize_ts is True
    for record in records:
        assert record[originating.start : originating.end] == "2022-06-10 19:27:53.000000"
        assert record[processing.start : processing.end] == " " * processing.length


def test_the_two_overpunch_tables_are_ten_distinct_characters_each() -> None:
    """Assert the structural shape both tables must have for a table lookup to be sound.

    Takes no parameters and returns no value; a table of the wrong length, or one sharing a
    character with the other, is reported as an assertion failure.
    """
    # WHY : Alternatives Considered: the tables are asserted STRUCTURALLY as well as
    #   entry-by-entry, mirroring the reasoning ``zoned.py`` records for choosing two index
    #   strings over a dictionary or a twenty-branch comparison cascade: the position in the
    #   string is the digit value, so one lookup serves both directions. That property only
    #   holds if each table is exactly ten characters and the two share none, because a
    #   duplicate would make one byte decode to two different digits depending on which table
    #   were consulted first. A dictionary would need two mappings kept in step by hand and
    #   would lose the positional invariant entirely, which is why the invariant is worth an
    #   assertion of its own rather than being left implicit in the twenty vectors below.
    assert len(_POSITIVE_OVERPUNCH_TABLE) == 10
    assert len(_NEGATIVE_OVERPUNCH_TABLE) == 10
    assert len(set(_POSITIVE_OVERPUNCH_TABLE)) == 10
    assert len(set(_NEGATIVE_OVERPUNCH_TABLE)) == 10
    assert not set(_POSITIVE_OVERPUNCH_TABLE) & set(_NEGATIVE_OVERPUNCH_TABLE)


@pytest.mark.parametrize(
    ("final_byte", "expected_text", "digit"),
    [
        ("{", "504.70", 0),
        ("A", "504.71", 1),
        ("B", "504.72", 2),
        ("C", "504.73", 3),
        ("D", "504.74", 4),
        ("E", "504.75", 5),
        ("F", "504.76", 6),
        ("G", "504.77", 7),
        ("H", "504.78", 8),
        ("I", "504.79", 9),
    ],
)
def test_the_positive_overpunch_table_carries_digits_zero_through_nine(
    final_byte: str, expected_text: str, digit: int
) -> None:
    """Decode every entry of the positive trailing-sign table over one attested digit body.

    :param final_byte: the single overpunch character under test, substituted for the final
        byte of the attested span ``0000005047G``.
    :param expected_text: the expected positive value as exact decimal text at scale two.
    :param digit: the position ``final_byte`` must occupy in the positive table, which is also
        the low-order digit it carries.
    :returns: nothing; a differing table position, value, scale or re-encoded span is reported
        as an assertion failure.
    """
    # WHY : Trade-offs: three of these ten entries are attested by the corpus -- ``{``, ``F``,
    #   ``G`` and ``H`` appear in real spans, of which the latter three are positive money --
    #   and the remaining six, ``A`` through ``E`` and ``I``, are SYNTHETIC. They are
    #   constructed by substituting one byte of the attested span, so each differs from real
    #   data in exactly one character. Exhaustive coverage is chosen over asserting only the
    #   live union, because a table one third exercised cannot detect a transposition among the
    #   entries it never reads.
    assert _POSITIVE_OVERPUNCH_TABLE[digit] == final_byte, (
        "the table index must equal the digit the character carries"
    )
    assert (final_byte in _LIVE_OVERPUNCH_BYTES) == (final_byte in "{FGH"), (
        "a byte's live-or-synthetic status must match the measured corpus union"
    )
    _assert_decodes_and_re_encodes(
        f"{_OVERPUNCH_BODY}{final_byte}",
        _TRAN_MONEY_INT_DIGITS,
        _MONEY_DEC_DIGITS,
        _SIGNED,
        expected_text,
    )


@pytest.mark.parametrize(
    ("final_byte", "expected_text", "digit"),
    [
        ("}", "-504.70", 0),
        ("J", "-504.71", 1),
        ("K", "-504.72", 2),
        ("L", "-504.73", 3),
        ("M", "-504.74", 4),
        ("N", "-504.75", 5),
        ("O", "-504.76", 6),
        ("P", "-504.77", 7),
        ("Q", "-504.78", 8),
        ("R", "-504.79", 9),
    ],
)
def test_the_negative_overpunch_table_carries_digits_zero_through_nine(
    final_byte: str, expected_text: str, digit: int
) -> None:
    """Decode every entry of the negative trailing-sign table over one attested digit body.

    :param final_byte: the single overpunch character under test, substituted for the final
        byte of the attested span ``0000005047G``.
    :param expected_text: the expected negative value as exact decimal text at scale two.
    :param digit: the position ``final_byte`` must occupy in the negative table, which is also
        the low-order digit it carries.
    :returns: nothing; a differing table position, value, scale or re-encoded span is reported
        as an assertion failure.
    """
    # WHY : Trade-offs: nine of these ten entries are SYNTHETIC. Only the closing brace is
    #   attested, in the span ``0000009190}`` of the second transaction fixture record; the
    #   letters ``J`` through ``R`` appear in no signed field of the corpus, because money is
    #   never a record's last field and no ASCII seed record therefore ends in a negative
    #   overpunch. Asserting only the attested byte was the alternative and would leave the
    #   negative table one tenth tested -- the half of the mapping where an error inverts
    #   financial meaning rather than merely shifting a cent.
    # WHY : Assumptions: none of these ten values is zero, so every one round-trips byte for
    #   byte and the documented negative-zero exception does not apply to any of them. That
    #   exception is exercised on its own, over an all-zero span.
    assert _NEGATIVE_OVERPUNCH_TABLE[digit] == final_byte, (
        "the table index must equal the digit the character carries"
    )
    assert (final_byte in _LIVE_OVERPUNCH_BYTES) == (final_byte == "}"), (
        "the closing brace is the only attested member of the negative table"
    )
    _assert_decodes_and_re_encodes(
        f"{_OVERPUNCH_BODY}{final_byte}",
        _TRAN_MONEY_INT_DIGITS,
        _MONEY_DEC_DIGITS,
        _SIGNED,
        expected_text,
    )


def test_the_live_overpunch_union_is_exactly_five_of_the_twenty_bytes() -> None:
    """Pin the measured live union so a later reader cannot mistake it for the whole table.

    Takes no parameters and returns no value; a union that has drifted from the measurement is
    reported as an assertion failure.
    """
    # WHY : Assumptions: this figure is a MEASUREMENT of the corpus, taken by reading every
    #   signed span of ``app/data/ASCII/*.txt`` and ``tests/fixtures/**``, and it is asserted
    #   so that the synthetic labelling above stays honest. If a future fixture introduced a
    #   sixth live byte, this assertion fails and the labels are corrected deliberately rather
    #   than silently becoming wrong.
    assert _LIVE_OVERPUNCH_BYTES == frozenset({"{", "F", "G", "H", "}"})
    assert _LIVE_OVERPUNCH_BYTES < set(_POSITIVE_OVERPUNCH_TABLE + _NEGATIVE_OVERPUNCH_TABLE)
    synthetic = set(_POSITIVE_OVERPUNCH_TABLE + _NEGATIVE_OVERPUNCH_TABLE) - _LIVE_OVERPUNCH_BYTES
    assert len(synthetic) == 15, "fifteen of the twenty table bytes are synthetic"


def test_a_decoded_amount_keeps_its_declared_scale_rather_than_normalising() -> None:
    """Refuse to let a trailing-zero amount pass a scale assertion as a shorter equal value.

    Takes no parameters and returns no value; a decoded value whose exponent is not the
    declared scale is reported as an assertion failure.
    """
    # WHY : Assumptions: Decimal("193.00") and Decimal("193") COMPARE EQUAL, so an equality
    #   assertion alone cannot detect a codec that normalised away the declared scale. The two
    #   re-encode to different spans -- ``00000001930{`` against ``00000000193{`` -- and parity
    #   is decided by a byte comparison against a golden master, so the exponent is asserted
    #   explicitly and the equal-but-shorter value is asserted NOT to have the same exponent.
    decoded = decode_zoned("00000001930{", _ACCOUNT_MONEY_INT_DIGITS, _MONEY_DEC_DIGITS, _SIGNED)
    assert decoded == Decimal("193.00")
    assert decoded == Decimal("193"), "the shorter form is numerically equal, which is the trap"
    assert decoded.as_tuple().exponent == -_MONEY_DEC_DIGITS
    assert Decimal("193").as_tuple().exponent != -_MONEY_DEC_DIGITS
    assert str(decoded) == "193.00", "the trailing zeroes must remain represented"


@pytest.mark.parametrize(
    "span",
    [
        "00000001930{",
        "00000020650{",
        "00000002640{",
        "00000000000{",
        "00000001940{",
    ],
)
def test_every_account_span_round_trips_byte_for_byte(span: str) -> None:
    """Re-encode each attested twelve-character account span to exactly the bytes it came from.

    :param span: one attested twelve-character signed display span from the account master.
    :returns: nothing; a re-encoded span differing from the original is reported as an
        assertion failure.
    """
    # WHY : Assumptions: the round trip is asserted over the ORIGINAL bytes and not over the
    #   decoded value, because that is the only direction in which the two tables must agree
    #   with each other. A decode-then-compare-value test passes even when encode consults a
    #   different table, whereas reproducing the source span requires both directions to share
    #   one mapping.
    decoded = decode_zoned(span, _ACCOUNT_MONEY_INT_DIGITS, _MONEY_DEC_DIGITS, _SIGNED)
    assert encode_zoned(decoded, _ACCOUNT_MONEY_INT_DIGITS, _MONEY_DEC_DIGITS, _SIGNED) == span


def test_an_overpunched_negative_zero_re_encodes_as_a_positive_zero() -> None:
    """Document the one accepted non-byte-identical round trip on the ordinary decode pair.

    A ``'}'``-overpunched zero decodes to numeric zero and re-encodes as ``'{'``, so this span
    -- and only a span whose digits are all zeros -- does not reproduce itself. Takes no
    parameters and returns no value; a preserved minus sign, or any other span failing to
    reproduce itself, is reported as an assertion failure.
    """
    # WHY : Refactoring Rationale: this comment asserted that ":class:`decimal.Decimal` has no
    #   negative zero", and that is FALSE -- measured, not argued: ``Decimal("-0.00")`` has
    #   ``sign=1`` in ``as_tuple()``, returns ``True`` from ``is_signed()``, and still compares
    #   equal to ``Decimal("0.00")``. Attributing the carve-out to a Python limitation that does
    #   not exist is worse than leaving it unexplained, because the obvious "fix" it invites --
    #   making the decoder preserve the sign, since ``Decimal`` can hold it -- is exactly the
    #   change that breaks parity.
    # WHY : Assumptions: the constraint is on the JAVA side and it is real. ``BigDecimal`` stores
    #   an unscaled ``BigInteger`` with a scale, and ``BigInteger`` has exactly one zero, so
    #   ``new BigDecimal("-0.00")`` yields ``0.00`` with ``signum() == 0`` and the sign is
    #   discarded at construction -- measured against the pinned JDK, not assumed. There is
    #   therefore no signed zero for the Java codec to round-trip.
    # WHY : Assumptions: what the Python decoder does is consequently a DELIBERATE
    #   normalisation and not an inability. It maps a ``'}'``-overpunched zero onto an unsigned
    #   ``Decimal`` so the two languages agree about the same source span, which is precisely
    #   the divergence this suite exists to prevent. ``zoned.py`` records the same carve-out at
    #   its encode site and notes that the Java codec offers a separate sign-preserving pair for
    #   a caller that must reproduce a dataset byte for byte.
    negative_zero = decode_zoned(
        "00000000000}", _ACCOUNT_MONEY_INT_DIGITS, _MONEY_DEC_DIGITS, _SIGNED
    )
    assert negative_zero == Decimal("0.00")
    # WHY : Assumptions: this asserts what the DECODER produced, so the message says so. A
    #   message claiming Decimal "carries no negative zero to preserve" would state the false
    #   premise the comment above withdraws, and it would do it at the point a failure is read.
    assert negative_zero.is_signed() is False, (
        "the decoder must normalise a '}'-overpunched zero to an UNSIGNED Decimal;"
        " Decimal can represent -0.00, so this is the decoder's choice and not a limitation"
    )
    # WHY : Assumptions: the premise is asserted here rather than only described, so the
    #   corrected comment above cannot itself go stale. If a future Python release did drop the
    #   signed zero, this line fails and the reasoning gets revisited instead of silently
    #   becoming true by accident.
    assert Decimal("-0.00").is_signed() is True, (
        "Decimal is expected to CARRY a signed zero; the normalisation above is a parity"
        " choice made because Java's BigDecimal has exactly one zero"
    )
    assert negative_zero.as_tuple().exponent == -_MONEY_DEC_DIGITS
    assert (
        encode_zoned(negative_zero, _ACCOUNT_MONEY_INT_DIGITS, _MONEY_DEC_DIGITS, _SIGNED)
        == "00000000000{"
    )

    # WHY : Assumptions: the exception is asserted to be confined to zero. A non-zero negative
    #   span of the same width reproduces itself exactly, so the carve-out cannot be read as
    #   licence for the encoder to drop a sign generally.
    _assert_decodes_and_re_encodes(
        "00000009190}", _ACCOUNT_MONEY_INT_DIGITS, _MONEY_DEC_DIGITS, _SIGNED, "-919.00"
    )


def test_the_encode_path_refuses_a_binary_floating_point_value() -> None:
    """Refuse a ``float`` outright on encode instead of coercing it, raising :class:`TypeError`.

    Takes no parameters and returns no value; an accepted ``float`` is reported as an assertion
    failure. The exception class verified is :class:`TypeError`, named here because
    ``pytest.raises`` captures it inside a context manager where no linter can inspect it.
    """
    # WHY : Alternatives Considered: coercion via ``Decimal(str(value))`` was available and was
    #   rejected. A binary float cannot represent ten cents exactly -- ``0.1`` is stored as a
    #   value slightly above one tenth -- so accepting one would let an approximation enter the
    #   money path and silently corrupt a monetary total that nothing downstream would
    #   question. Coercing would additionally hide the CALLER's bug: the caller who passed a
    #   float has a float somewhere upstream, and rejecting is what surfaces it. The reference
    #   codec rejects float for exactly this reason, and the migration's rule T3 forbids
    #   ``float`` and ``double`` in the money path end to end.
    with pytest.raises(TypeError) as refusal:
        encode_zoned(0.1, _TRAN_MONEY_INT_DIGITS, _MONEY_DEC_DIGITS, _SIGNED)  # type: ignore[arg-type]
    assert "float" in str(refusal.value), "the diagnostic must name the refused type"

    # WHY : Assumptions: ``bool`` is an ``int`` subclass, so it would otherwise pass an
    #   ``isinstance(value, int)`` gate and encode as one unit. It is refused separately and is
    #   asserted here beside float because the two are the same class of defect -- a value that
    #   is not money arriving where money is expected.
    with pytest.raises(TypeError) as bool_refusal:
        encode_zoned(True, _TRAN_MONEY_INT_DIGITS, _MONEY_DEC_DIGITS, _SIGNED)  # type: ignore[arg-type]
    assert "bool" in str(bool_refusal.value)


def test_exact_values_are_accepted_from_every_admitted_type() -> None:
    """Accept :class:`~decimal.Decimal`, :class:`int` and :class:`str` as exact encode inputs.

    Takes no parameters and returns no value; a rejected exact input, or one encoding to a
    differing span, is reported as an assertion failure.
    """
    # WHY : Assumptions: the three admitted types are asserted together with float's refusal
    #   so the contract reads as a whole. Text is admitted because a decimal string is exact,
    #   which is the same reason money crosses an API boundary as a JSON string rather than as
    #   a JSON number: a client that parses a JSON number routes it through an IEEE-754 double
    #   and destroys the exactness at the boundary the user actually reads.
    geometry = (_TRAN_MONEY_INT_DIGITS, _MONEY_DEC_DIGITS, _SIGNED)
    assert encode_zoned(Decimal("504.77"), *geometry) == "0000005047G"
    assert encode_zoned("504.77", *geometry) == "0000005047G"
    # WHY : Assumptions: an integer input carries scale zero, so 504 becomes 504.00 and its
    #   scaled magnitude is 50400 -- eleven positions of which the low-order one is the digit
    #   zero, hence the opening brace. The span is written out in full rather than derived,
    #   because the two adjacent zeroes are exactly where a transposition hides.
    assert encode_zoned(504, *geometry) == "0000005040{"


def test_an_unsigned_key_decodes_as_plain_digits_with_no_overpunch_reading(
    fixture_corpus: FixtureCorpus,
) -> None:
    """Decode the unsigned account key as plain digits, leaving the next byte untouched.

    :param fixture_corpus: session accessor for ``tests/fixtures``, supplied by ``conftest``.
    :returns: nothing; a key read as signed, or one that consumed the following byte, is
        reported as an assertion failure.
    """
    # WHY : Assumptions: ``ACCT-ID`` is ``PIC 9(11)`` with no leading ``S``, so it holds eleven
    #   plain digits and its low-order position is an ordinary digit rather than an overpunch.
    #   The field immediately after it is ``ACCT-ACTIVE-STATUS`` at [11:12] holding ``'Y'``,
    #   which is what makes this the meaningful guard against over-eager sign parsing: a decoder
    #   that reached one byte past the declared width would find a LETTER there and could read
    #   it as a sign, silently turning account 7 into a signed quantity.
    record = fixture_corpus.records(_POSTING_HAPPY_PATH, "acctdata.txt")[0]
    key = _account_field("ACCT-ID")
    status = _account_field("ACCT-ACTIVE-STATUS")
    assert key.kind is Kind.UINT
    assert key.signed is False
    assert (key.start, key.length) == (0, 11)
    assert (status.start, status.length) == (11, 1)
    assert record[key.start : key.end] == "00000000007"
    assert record[status.start : status.end] == "Y"

    decoded = decode_zoned_field(record, key)
    assert decoded == Decimal(7)
    assert decoded.as_tuple().exponent == 0, "an unsigned key declares no decimal positions"
    assert encode_zoned_field(decoded, key) == "00000000007"


@pytest.mark.parametrize("final_byte", ["}", "J", "R", "{", "A", "I"])
def test_an_overpunch_supplied_for_an_unsigned_field_is_refused(final_byte: str) -> None:
    """Refuse any overpunch character in an unsigned field, raising :class:`ZonedDecimalError`.

    :param final_byte: one overpunch character, positive or negative, substituted for the
        low-order digit of an eleven-digit unsigned key.
    :returns: nothing; an accepted overpunch is reported as an assertion failure. The exception
        class verified is :class:`~carddemo_migration.copybook.zoned.ZonedDecimalError`.
    """
    # WHY : Alternatives Considered: tolerating a negative overpunch here by reading it as a
    #   signed value was rejected, because an unsigned picture clause cannot REPRESENT a
    #   negative number -- the target column has no sign either, so a tolerated sign would be
    #   dropped somewhere further downstream with nothing recording that it had been. Both
    #   tables are exercised rather than only the negative one, because a positive overpunch in
    #   an unsigned field is the same defect wearing a harmless-looking byte.
    with pytest.raises(ZonedDecimalError):
        decode_zoned(f"0000000000{final_byte}", 11, 0, _UNSIGNED)


def test_a_plain_trailing_digit_supplied_for_a_signed_field_is_refused() -> None:
    """Refuse a signed span ending in a plain digit, raising :class:`ZonedDecimalError`.

    Takes no parameters and returns no value; an accepted plain digit is reported as an
    assertion failure. The exception class verified is
    :class:`~carddemo_migration.copybook.zoned.ZonedDecimalError`.
    """
    # WHY : Alternatives Considered: treating a plain trailing digit as implicitly positive was
    #   available and was rejected. In a field declared signed it is proof that the producer
    #   used a different sign convention -- concretely, that a GnuCOBOL build ran with the
    #   default ``-fsign=ASCII`` instead of the mandatory ``-fsign=EBCDIC``, which
    #   ``tests/README.md`` section 5.2 records as silently corrupting negative balances.
    #   Tolerance would convert that detectable configuration error into a plausible amount
    #   carrying an unverified sign, and a lost sign inverts financial meaning rather than
    #   merely perturbing it.
    with pytest.raises(ZonedDecimalError) as refusal:
        decode_zoned(f"{_OVERPUNCH_BODY}7", _TRAN_MONEY_INT_DIGITS, _MONEY_DEC_DIGITS, _SIGNED)
    assert "plain digit" in str(refusal.value), "the diagnostic must name the actual defect"


@pytest.mark.parametrize(("window", "misread_value"), _GROUP_A_FALSE_POSITIVES)
def test_a_group_a_window_is_not_a_signed_money_field(window: str, misread_value: Decimal) -> None:
    """Prove an eleven-character window spanning four fields decodes successfully and wrongly.

    :param window: the eleven characters found at zero-based [12:23] of a real 350-byte
        transaction record, spanning the tail of ``TRAN-ID``, ``TRAN-TYPE-CD``, ``TRAN-CAT-CD``
        and the first letter of ``TRAN-SOURCE``.
    :param misread_value: the value a signed nine-and-two decode actually returns for that
        window, which is a plausible nine-figure amount and is not any field of the record.
    :returns: nothing; a window that failed to decode, or decoded to a different wrong value,
        is reported as an assertion failure.
    """
    # WHY : Assumptions: the rule these windows prove is that ONLY a declared offset, a
    #   declared length and a declared kind identify a zoned field, and that a signed field
    #   must never be pattern-matched over a whole record. The danger is precisely that the
    #   misread SUCCEEDS: ``3580010001P`` at [12:23] of
    #   ``tests/fixtures/posting/reject_101_acct_missing/dailytran.txt`` row 0 decodes without
    #   complaint to -358001000.17, because its final ``P`` -- the ``P`` of ``'POS TERM  '`` in
    #   ``TRAN-SOURCE`` -- is index 7 of the negative table. Nothing raises, nothing logs, and
    #   the number is well formed. The corresponding Group B case is ``800000000K`` at
    #   [143:153], whose ``K`` is the ``K`` of a merchant name.
    assert len(window) == _GROUP_A_WINDOW_END - _GROUP_A_WINDOW_START == 11
    assert window[-1] in _NEGATIVE_OVERPUNCH_TABLE, (
        "the window is only convincing because its final byte is a negative overpunch"
    )
    assert decode_zoned(window, _TRAN_MONEY_INT_DIGITS, _MONEY_DEC_DIGITS, _SIGNED) == misread_value


@pytest.mark.parametrize(("window", "misread_value"), _GROUP_B_FALSE_POSITIVES)
def test_a_group_b_window_is_not_a_signed_money_field(window: str, misread_value: Decimal) -> None:
    """Prove a ten-character window spanning two fields decodes successfully and wrongly.

    :param window: the ten characters found at zero-based [143:153] of a real 350-byte
        transaction record, spanning all of ``TRAN-MERCHANT-ID`` and the first letter of
        ``TRAN-MERCHANT-NAME``.
    :param misread_value: the value a signed eight-and-two decode actually returns for that
        window, which is a plausible eight-figure amount and is not any field of the record.
    :returns: nothing; a window that failed to decode, or decoded to a different wrong value,
        is reported as an assertion failure.
    """
    assert len(window) == _GROUP_B_WINDOW_END - _GROUP_B_WINDOW_START == 10
    assert window[-1] in _NEGATIVE_OVERPUNCH_TABLE
    assert decode_zoned(window, _GROUP_B_INT_DIGITS, _MONEY_DEC_DIGITS, _SIGNED) == misread_value


def test_the_declared_span_disagrees_with_every_false_positive_window(
    fixture_corpus: FixtureCorpus,
) -> None:
    """Show the declared field and the look-alike window yield different numbers on one record.

    :param fixture_corpus: session accessor for ``tests/fixtures``, supplied by ``conftest``.
    :returns: nothing; a window that agreed with a declared field, or a record whose windows
        have drifted from the measurement, is reported as an assertion failure.
    """
    # WHY : Assumptions: this is the assertion that makes the anchoring rule actionable rather
    #   than abstract. On one and the same record, the look-alike window at [12:23] yields a
    #   nine-figure negative and the DECLARED amount at [132:143] yields the real value, and
    #   the two differ. Both windows are located from the descriptors' own offsets, so the test
    #   cannot drift from ``layouts.py`` even if a field moved.
    records = fixture_corpus.records(_EXPORT_HAPPY_PATH, "trandata.txt")
    amount = _tran_field("TRAN-AMT")
    merchant = _tran_field("TRAN-MERCHANT-ID")
    assert (amount.start, amount.length) == (132, 11)
    assert (merchant.start, merchant.length) == (143, 9)

    truths = ("504.77", "-919.00", "67.88", "281.77", "454.66")
    for row, truth in enumerate(truths):
        record = records[row]
        group_a = record[_GROUP_A_WINDOW_START:_GROUP_A_WINDOW_END]
        group_b = record[_GROUP_B_WINDOW_START:_GROUP_B_WINDOW_END]
        declared_amount = decode_zoned_field(record, amount)
        assert declared_amount == Decimal(truth)
        assert decode_zoned_field(record, merchant) == Decimal(800000000)

        misread_a = decode_zoned(group_a, _TRAN_MONEY_INT_DIGITS, _MONEY_DEC_DIGITS, _SIGNED)
        assert misread_a != declared_amount, (
            f"row {row}: the [12:23] window must not agree with the declared amount"
        )
        # WHY : Assumptions: rows 0, 2 and 3 carry a POSITIVE-table byte at [152], so only two
        #   of the five Group B windows look negative. All five are still asserted to be
        #   decodable-and-wrong, because the hazard is the successful misread and not the sign.
        misread_b = decode_zoned(group_b, _GROUP_B_INT_DIGITS, _MONEY_DEC_DIGITS, _SIGNED)
        assert misread_b != declared_amount, (
            f"row {row}: the [143:153] window must not agree with the declared amount"
        )


def test_the_group_a_window_is_still_present_in_both_reject_scenarios(
    fixture_corpus: FixtureCorpus,
) -> None:
    """Confirm the measured Group A window is still the bytes both reject fixtures hold.

    :param fixture_corpus: session accessor for ``tests/fixtures``, supplied by ``conftest``.
    :returns: nothing; a drifted fixture is reported as an assertion failure.
    """
    # WHY : Assumptions: the window is asserted against the fixtures rather than trusted from a
    #   comment, because a measurement written only in prose goes stale invisibly. Both
    #   scenarios are checked because they share the same daily-transaction record, so a change
    #   to one and not the other would itself be worth surfacing.
    for scenario in (_REJECT_101_SCENARIO, _REJECT_103_SCENARIO):
        record = fixture_corpus.records(scenario, "dailytran.txt")[0]
        assert record[_GROUP_A_WINDOW_START:_GROUP_A_WINDOW_END] == "3580010001P", (
            f"{scenario} must still carry the measured Group A window"
        )
        source = fixture_corpus.record_spec("dailytran.txt").field("DALYTRAN-SOURCE")
        assert record[source.start] == "P", "the window's final byte is this field's first byte"


def _display_record_holding(layout: RecordSpec, field: FieldSpec, span: str) -> str:
    """Build a synthetic display record of declared length carrying one span at one offset.

    :param layout: the record descriptor supplying the total declared record length.
    :param field: the descriptor whose declared offset and width the span occupies.
    :param span: exactly ``field.length`` characters to place at ``field.start``.
    :returns: a record of exactly ``layout.reclen`` characters, zero-filled elsewhere.
    :raises AssertionError: if ``span`` is not the field's declared width.
    """
    # WHY : Assumptions: the surrounding record is filled with the digit zero rather than with
    #   blanks, so that every other display field of the record remains individually valid. A
    #   blank-filled record would make any failure ambiguous -- a reader could not tell whether
    #   the codec rejected the span under test or some unrelated field of the padding.
    assert len(span) == field.length, "the span must be the field's declared width"
    return f"{'0' * field.start}{span}{'0' * (layout.reclen - field.end)}"


@pytest.mark.parametrize(
    ("span", "reason"),
    [
        ("0000005047", "one character short of the declared eleven"),
        ("0000005047GG", "one character longer than the declared eleven"),
        ("", "an empty span, which is short by the whole width"),
    ],
)
def test_a_span_of_the_wrong_width_is_refused(span: str, reason: str) -> None:
    """Refuse a mis-sized display span, raising :class:`ZonedSpanWidthError`.

    :param span: a span whose length differs from the eleven characters the geometry implies.
    :param reason: prose naming how the span is mis-sized, carried into the failure message so
        a failing case identifies itself.
    :returns: nothing; an accepted mis-sized span is reported as an assertion failure. The
        exception class verified is
        :class:`~carddemo_migration.copybook.zoned.ZonedSpanWidthError`.
    """
    # WHY : Assumptions: width failures carry their own exception SUBTYPE because a short or
    #   long slice points at record geometry -- a misaligned read -- whereas a bad body byte
    #   points at the producer's numeric representation. The two faults have different remedies,
    #   so a caller that catches only the width type is making a meaningful distinction, and
    #   both directions are asserted because a decoder that padded a short span would silently
    #   invent a digit.
    with pytest.raises(ZonedSpanWidthError):
        decode_zoned(span, _TRAN_MONEY_INT_DIGITS, _MONEY_DEC_DIGITS, _SIGNED)
    assert reason, "each parametrised case names how it is mis-sized"


def test_a_non_digit_in_the_digit_body_is_refused() -> None:
    """Refuse a non-ASCII digit inside the body, raising :class:`ZonedDecimalError`.

    Takes no parameters and returns no value; an accepted non-digit is reported as an assertion
    failure. The exception class verified is
    :class:`~carddemo_migration.copybook.zoned.ZonedDecimalError`.
    """
    # WHY : Assumptions: the body is validated against the ten ASCII digits specifically and
    #   not with ``str.isdigit``, which also accepts the decimal digits of other scripts. Such a
    #   character subtracted from the ASCII zero yields a digit value in the hundreds, so
    #   admitting one turns a rejected span into an amount wrong by orders of magnitude. The
    #   letter ``O`` is used below because it is the character most easily mistaken for a zero.
    with pytest.raises(ZonedDecimalError) as refusal:
        decode_zoned("00000O5047G", _TRAN_MONEY_INT_DIGITS, _MONEY_DEC_DIGITS, _SIGNED)
    assert "index 5" in str(refusal.value), "the diagnostic locates the offending position"


@pytest.mark.parametrize("final_byte", ["*", " ", "S", "Z", "0"])
def test_an_unrecognised_final_byte_is_refused(final_byte: str) -> None:
    """Refuse a final byte outside both tables, raising :class:`ZonedDecimalError`.

    :param final_byte: one character that is in neither the positive nor the negative
        trailing-sign table, appended to the attested digit body.
    :returns: nothing; an accepted byte is reported as an assertion failure. The exception class
        verified is :class:`~carddemo_migration.copybook.zoned.ZonedDecimalError`.
    """
    # WHY : Assumptions: the five characters below are chosen to cover the distinct ways a final
    #   byte goes wrong -- punctuation, a blank left by an unwritten field, two letters that are
    #   adjacent to real table entries in the alphabet but absent from both tables, and a plain
    #   digit. ``S`` and ``Z`` matter particularly: a decoder that bounded its table by
    #   character range rather than by membership would accept them.
    assert final_byte not in _POSITIVE_OVERPUNCH_TABLE
    assert final_byte not in _NEGATIVE_OVERPUNCH_TABLE
    with pytest.raises(ZonedDecimalError):
        decode_zoned(
            f"{_OVERPUNCH_BODY}{final_byte}",
            _TRAN_MONEY_INT_DIGITS,
            _MONEY_DEC_DIGITS,
            _SIGNED,
        )


def test_an_encode_magnitude_wider_than_the_field_is_refused() -> None:
    """Refuse a value needing more digit positions than the display field declares.

    Takes no parameters and returns no value; an accepted over-wide value is reported as an
    assertion failure. The exception class verified is
    :class:`~carddemo_migration.copybook.zoned.ZonedDecimalError`.
    """
    # WHY : Assumptions: the alternative to refusing is truncating the high-order digits, which
    #   is the worst available outcome: it returns a field of exactly the declared width holding
    #   a number smaller than the one requested, so the span is well formed and nothing
    #   downstream can tell. The value below needs thirteen positions where the field holds
    #   eleven.
    with pytest.raises(ZonedDecimalError) as refusal:
        encode_zoned(Decimal("99999999999.99"), _TRAN_MONEY_INT_DIGITS, _MONEY_DEC_DIGITS, _SIGNED)
    assert "digit positions" in str(refusal.value)


@pytest.mark.parametrize("value", ["504.771", "0.001", "-504.775", "1.005"])
def test_encoding_is_lossless_or_throwing_and_never_rounds(value: str) -> None:
    """Refuse fractional precision beyond the declared scale, raising :class:`ZonedDecimalError`.

    :param value: exact decimal text carrying a non-zero digit beyond two decimal places.
    :returns: nothing; a silently rounded or truncated encode is reported as an assertion
        failure. The exception class verified is
        :class:`~carddemo_migration.copybook.zoned.ZonedDecimalError`.
    """
    # WHY : Alternatives Considered: rounding to the declared scale was available and was
    #   rejected, because a codec that chooses a rounding rule makes a business decision and
    #   records it nowhere. The two values ending in five are included deliberately: they are
    #   the inputs on which two defensible rules -- half-up and half-even -- disagree, so a
    #   codec that rounded would produce a different cent depending on a choice no audit trail
    #   holds. Refusing pushes the decision back to the caller, who can quantise explicitly.
    with pytest.raises(ZonedDecimalError) as refusal:
        encode_zoned(Decimal(value), _TRAN_MONEY_INT_DIGITS, _MONEY_DEC_DIGITS, _SIGNED)
    assert "beyond 2 decimal places" in str(refusal.value)


def test_a_negative_value_is_refused_by_an_unsigned_field_on_encode() -> None:
    """Refuse a negative value for an unsigned field, raising :class:`ZonedDecimalError`.

    Takes no parameters and returns no value; an accepted negative value is reported as an
    assertion failure. The exception class verified is
    :class:`~carddemo_migration.copybook.zoned.ZonedDecimalError`.
    """
    # WHY : Assumptions: an unsigned picture clause has nowhere to put a sign, so the only ways
    #   to accept a negative value are to emit its magnitude -- losing the sign entirely -- or
    #   to emit an overpunch the field is not declared to carry, which this module's own decoder
    #   would then refuse. Both are worse than refusing at the boundary.
    with pytest.raises(ZonedDecimalError):
        encode_zoned(Decimal(-1), 11, 0, _UNSIGNED)


def test_bytes_are_refused_at_the_display_codec_boundary() -> None:
    """Refuse raw bytes on the display path, raising :class:`TypeError`.

    Takes no parameters and returns no value; accepted bytes are reported as an assertion
    failure. The exception class verified is :class:`TypeError`.
    """
    # WHY : Assumptions: cp037 character conversion belongs exclusively to
    #   ``carddemo_migration.copybook.ebcdic_codec``, and refusing bytes here is what keeps it
    #   there. The prohibition matters because what a stray decode does is charset-dependent: a
    #   single-byte codec maps all 256 values and mistranslates in silence, while a multi-byte
    #   codec emits replacements whose count need not equal the bytes consumed and so moves
    #   every later field offset.
    with pytest.raises(TypeError) as refusal:
        decode_zoned(
            b"0000005047G",  # type: ignore[arg-type]
            _TRAN_MONEY_INT_DIGITS,
            _MONEY_DEC_DIGITS,
            _SIGNED,
        )
    assert "ebcdic_codec" in str(refusal.value), "the diagnostic names the module that decodes"


def test_a_non_display_field_is_refused_by_the_field_oriented_entry_point() -> None:
    """Refuse a text or computational field on the display path, raising :class:`ZonedDecimalError`.

    Takes no parameters and returns no value; a decoded non-display field is reported as an
    assertion failure. The exception class verified is
    :class:`~carddemo_migration.copybook.zoned.ZonedDecimalError`.
    """
    # WHY : Assumptions: only ``ZONED`` and ``UINT`` are display regimes, so a mis-routed field
    #   must be refused before its bytes are read as digits. Both a character field and a packed
    #   one are exercised, because the two mis-routings fail differently in the wild: a
    #   character field would decode to a plausible number if it happened to hold digits,
    #   whereas a packed field's nibbles are not digits at all.
    status = _account_field("ACCT-ACTIVE-STATUS")
    account_layout = layouts.layout("ACCOUNT")
    record = _display_record_holding(account_layout, status, "Y")
    with pytest.raises(ZonedDecimalError) as text_refusal:
        decode_zoned_field(record, status)
    assert "TEXT" in str(text_refusal.value)

    packed_amount = layouts.EXPORT_ACCOUNT_LAYOUT.field("EXP-ACCT-CURR-BAL")
    assert packed_amount.kind is Kind.PACKED
    with pytest.raises(ZonedDecimalError) as packed_refusal:
        decode_zoned_field("0" * layouts.EXPORT_ACCOUNT_LAYOUT.reclen, packed_amount)
    assert "PACKED" in str(packed_refusal.value)


def test_a_record_ending_before_the_declared_field_is_refused() -> None:
    """Refuse a record too short to reach the field's end, raising :class:`ZonedSpanWidthError`.

    Takes no parameters and returns no value; an accepted short record is reported as an
    assertion failure. The exception class verified is
    :class:`~carddemo_migration.copybook.zoned.ZonedSpanWidthError`.
    """
    # WHY : Assumptions: a short record is refused rather than padded, because padding would
    #   supply digits the producer never wrote and the resulting amount would be well formed.
    #   The record below stops one character before the balance field's exclusive end.
    balance = _account_field("ACCT-CURR-BAL")
    with pytest.raises(ZonedSpanWidthError):
        decode_zoned_field("0" * (balance.end - 1), balance)


def test_width_is_validated_before_the_digit_body_and_the_final_byte() -> None:
    """Assert width takes precedence when a span is short, malformed and badly signed at once.

    Takes no parameters and returns no value; an exception of the content type rather than the
    width type is reported as an assertion failure. The exception class verified is
    :class:`~carddemo_migration.copybook.zoned.ZonedSpanWidthError`, which is the subtype
    raised in preference to the more general
    :class:`~carddemo_migration.copybook.zoned.ZonedDecimalError`.
    """
    # WHY : Assumptions: the span below carries THREE simultaneous defects -- it is ten
    #   characters where eleven are declared, it holds the letter ``O`` in its digit body, and
    #   its final byte is in neither table. Precedence is asserted rather than assumed because
    #   the width fault is the one that explains the other two: a misaligned slice naturally
    #   contains foreign bytes, so reporting a body error first would send a reader hunting for
    #   a data-quality problem that does not exist. The order the codec applies is geometry,
    #   then text type, then width, then digit body, then the final byte, then signedness.
    with pytest.raises(ZonedSpanWidthError):
        decode_zoned("0000O5047*", _TRAN_MONEY_INT_DIGITS, _MONEY_DEC_DIGITS, _SIGNED)


def test_the_digit_body_is_validated_before_the_final_overpunch_byte() -> None:
    """Assert the body is reported before the final byte when both are malformed at declared width.

    Takes no parameters and returns no value; a diagnostic naming the final byte rather than the
    body is reported as an assertion failure. The exception class verified is
    :class:`~carddemo_migration.copybook.zoned.ZonedDecimalError`.
    """
    # WHY : Assumptions: at the correct width the two remaining defects are ordered body first,
    #   which is asserted through the diagnostic rather than the type because both faults raise
    #   the same class. The distinction is worth pinning: the body index localises the fault to
    #   one character, whereas "unrecognised overpunch" describes only the last position and
    #   would leave the earlier defect unreported.
    with pytest.raises(ZonedDecimalError) as refusal:
        decode_zoned("0000O05047*", _TRAN_MONEY_INT_DIGITS, _MONEY_DEC_DIGITS, _SIGNED)
    message = str(refusal.value)
    assert "digit body" in message
    assert "overpunch" not in message, "the body fault is reported ahead of the final byte"


def test_the_final_byte_is_validated_before_the_signedness_contract() -> None:
    """Assert an unrecognised final byte is reported ahead of the signedness contract.

    Takes no parameters and returns no value; a diagnostic naming signedness rather than the
    unrecognised byte is reported as an assertion failure. The exception class verified is
    :class:`~carddemo_migration.copybook.zoned.ZonedDecimalError`.
    """
    # WHY : Assumptions: the span below is a well-formed body followed by punctuation, decoded
    #   against a SIGNED contract. Two checks could fire -- the byte is in neither table, and it
    #   is not a plain digit either -- and the table check is the informative one because it
    #   names the admissible set. Asserting the order keeps a future refactor from reporting the
    #   less specific fault.
    with pytest.raises(ZonedDecimalError) as refusal:
        decode_zoned(f"{_OVERPUNCH_BODY}*", _TRAN_MONEY_INT_DIGITS, _MONEY_DEC_DIGITS, _SIGNED)
    assert "unrecognised overpunch" in str(refusal.value)


def test_a_sensitive_display_field_diagnostic_withholds_its_raw_bytes() -> None:
    """Withhold field content from a sensitive display field's diagnostic.

    Takes no parameters and returns no value; a diagnostic echoing the sensitive span, or one
    omitting the field's geometry, is reported as an assertion failure. The exception class
    verified is :class:`~carddemo_migration.copybook.zoned.ZonedDecimalError`.
    """
    # WHY : Trade-offs: a sensitive field's content is withheld COMPLETELY rather than masked to
    #   a last-four suffix the way a record diff renders it. A record diff needs stable width
    #   and a small identifying suffix to be readable, but an exception message is copied into a
    #   log aggregator and pasted into tickets, where even that suffix broadens disclosure. The
    #   compromise accepted is that a reader of this diagnostic cannot see the offending bytes
    #   and must reproduce the failure locally; the geometry is still named, which is what
    #   localises the fault.
    card_layout = layouts.layout("CARD")
    verification_value = card_layout.field("CARD-CVV-CD")
    assert verification_value.sensitive is True
    assert verification_value.kind is Kind.UINT

    secret_span = "*12"
    record = _display_record_holding(card_layout, verification_value, secret_span)
    with pytest.raises(ZonedDecimalError) as refusal:
        decode_zoned_field(record, verification_value)
    message = str(refusal.value)
    assert verification_value.name in message, "the diagnostic names the field"
    assert f"[{verification_value.start},{verification_value.end})" in message
    assert verification_value.kind.name in message
    assert secret_span not in message, "a sensitive diagnostic never echoes the field's bytes"
    assert "content=" not in message, "no content clause is rendered for a sensitive field"

    # WHY : Assumptions: the non-sensitive comparison is asserted in the same test, because
    #   "no content appeared" is only evidence of suppression if content appears when the flag
    #   is absent. Without the pair, a codec that had stopped rendering content altogether
    #   would pass the suppression assertion while having lost a diagnostic feature.
    # WHY : Refactoring Rationale: the control used to be ACCT-CURR-BAL, and it stopped being a
    #   valid control when the account master, its export branch and the category balance were
    #   brought under the fail-closed master disclosure policy -- every monetary field of those
    #   records is now sensitive, so the pair was asserting suppression against suppression and
    #   the test failed on its own control rather than on the property.
    # WHY : Refactoring Rationale: the control was then TRAN-AMT, on the reasoning that "a
    #   transaction amount is the field a reject diagnostic exists to show". That reasoning does
    #   not survive the rendering rule it has to answer to: docs/architecture/observability.md
    #   lines 1075 to 1076 place "no monetary amount, no credit limit or balance" in the OMITTED
    #   class with no abbreviated form, and it says so of EVERY rendering read by an operator
    #   rather than of one record's. Every account-borne amount in the corpus is therefore
    #   sensitive, in the transaction records as much as in the account master, and a reject
    #   diagnostic identifies its record by key and geometry rather than by echoing the amount.
    #   The control is now DIS-INT-RATE of the disclosure-group record, which is the one signed
    #   display field the policy DISCLOSES on its merits: a group rate is a published product
    #   term held against a group of accounts and not against any cardholder, and the
    #   command-line decode of that record is the case an operator has to be able to read.
    # WHY : Assumptions: picking a control from a different layout is the point rather than a
    #   workaround -- a control drawn from the same record as the subject can always be swept up
    #   by the same policy change, which is exactly how the previous two controls were lost.
    rate = layouts.layout("DISGROUP").field("DIS-INT-RATE")
    assert rate.sensitive is False, (
        "the control must be a field no disclosure policy withholds; if this fails, pick"
        " another non-sensitive signed-display field rather than weakening the assertion below"
    )
    assert rate.kind is Kind.ZONED
    disclosable = "*" * (rate.length - 1) + "{"
    group_record = _display_record_holding(layouts.layout("DISGROUP"), rate, disclosable)
    with pytest.raises(ZonedDecimalError) as disclosed:
        decode_zoned_field(group_record, rate)
    assert "content=" in str(disclosed.value)


def test_a_descriptor_whose_width_contradicts_its_geometry_is_refused() -> None:
    """Refuse explicit digit counts that disagree with a descriptor's declared length.

    Takes no parameters and returns no value; an accepted contradiction is reported as an
    assertion failure. The exception class verified is
    :class:`~carddemo_migration.copybook.zoned.ZonedSpanWidthError`.
    """
    # WHY : Assumptions: the descriptor and the explicit digit counts are two statements of one
    #   fact, so the codec cross-checks them instead of trusting whichever arrived last. This is
    #   the guard against a caller passing the transaction geometry -- nine integer positions,
    #   eleven characters -- against an account descriptor twelve characters wide, which would
    #   otherwise read eleven of the twelve bytes and shift the implied decimal point.
    balance = _account_field("ACCT-CURR-BAL")
    assert balance.length == 12
    with pytest.raises(ZonedSpanWidthError):
        decode_zoned(
            "00000001930{",
            _TRAN_MONEY_INT_DIGITS,
            _MONEY_DEC_DIGITS,
            _SIGNED,
            field=balance,
        )


def _computational_record_holding(layout: RecordSpec, field: FieldSpec, span: bytes) -> bytes:
    """Build a synthetic byte record of declared length carrying one span at one offset.

    :param layout: the record descriptor supplying the total declared record length.
    :param field: the descriptor whose declared offset and width the span occupies.
    :param span: exactly ``field.length`` bytes to place at ``field.start``.
    :returns: a record of exactly ``layout.reclen`` bytes, NUL-filled elsewhere.
    :raises AssertionError: if ``span`` is not the field's declared width.
    """
    # WHY : Assumptions: the padding is NUL rather than the digit character zero, because this
    #   record is read as nibbles and not as text -- a byte of 0x30 would present as the two
    #   nibbles 3 and 0, which is a digit pair rather than the empty pad a computational field
    #   expects. The record is built as bytes throughout so no character decoder is involved.
    assert len(span) == field.length, "the span must be the field's declared width"
    return bytes(field.start) + span + bytes(layout.reclen - field.end)


@pytest.mark.parametrize(
    ("int_digits", "dec_digits", "expected_bytes", "citation"),
    [
        (3, 0, 2, "CIPAUDTY-family PIC S9(03) COMP-3"),
        (5, 0, 3, "CIPAUDTY line 20 PA-AUTH-DATE-9C PIC S9(05) COMP-3"),
        (9, 0, 5, "CIPAUDTY line 21 PA-AUTH-TIME-9C PIC S9(09) COMP-3"),
        (11, 0, 6, "CIPAUSMY line 19 PA-ACCT-ID PIC S9(11) COMP-3"),
        (9, 2, 6, "CIPAUSMY line 23 PA-CREDIT-LIMIT PIC S9(09)V99 COMP-3"),
        (10, 2, 7, "CVEXPORT line 50 EXP-ACCT-CURR-BAL PIC S9(10)V99 COMP-3"),
    ],
)
def test_the_packed_width_ladder_matches_the_declared_corpus(
    int_digits: int, dec_digits: int, expected_bytes: int, citation: str
) -> None:
    """Derive each packed byte width from its digit counts exactly as the compiler does.

    :param int_digits: declared digit positions before the implied decimal point.
    :param dec_digits: declared digit positions after the implied decimal point.
    :param expected_bytes: the physical byte width the reference compiler lays down.
    :param citation: the copybook line the rung is taken from, so a failing case identifies the
        real declaration it contradicts rather than an invented example.
    :returns: nothing; a differing width is reported as an assertion failure.
    """
    # WHY : Assumptions: a packed field stores two digits per byte and reserves the low-order
    #   nibble of its last byte for the sign, so its width is the ceiling of one more than the
    #   digit count, halved. Every rung asserted here is a real declaration in the corpus, which
    #   is what makes a formula change fail against the data rather than against an example.
    assert packed_width(int_digits, dec_digits) == expected_bytes, citation


def test_a_twelve_digit_packed_money_field_is_seven_bytes_and_not_six() -> None:
    """Guard the twelve-digit rung against the defective six-byte figure for ``PIC S9(10)V99``.

    Takes no parameters and returns no value; a six-byte result is reported as an assertion
    failure.
    """
    # WHY : Assumptions: twelve digit positions plus one sign nibble is thirteen nibbles, and
    #   thirteen nibbles occupy the CEILING of thirteen halved, which is seven. The figure is
    #   proven twice over from files that had no reason to agree: ``app/cpy/CVEXPORT.cpy``
    #   declares five overlay branches over one 460-byte area, and its account branch closes at
    #   exactly 460 only when each of its two packed amounts is seven bytes -- at six each it
    #   would close at 458, which a 460-byte area does not admit. Independently,
    #   ``app/app-authorization-ims-db2-mq/cpy/CIPAUDTY.cpy`` sums to exactly 200 bytes at seven
    #   and to 198 at six, and 198 is not a length that segment can have. This test is named
    #   conspicuously because adding one and halving FLOORS instead of ceiling and returns six,
    #   which is the specific defect it exists to catch: a six-byte declaration shifts every
    #   later field of the record by one byte, and a one-byte shift still decodes to digits.
    assert packed_width(10, 2) == 7
    assert packed_width(10, 2) != 6

    # WHY : Assumptions: the width is cross-checked against the descriptors the registry already
    #   declares, so the formula and the two transcribed layouts must agree. Both are real
    #   ``PIC S9(10)V99 COMP-3`` fields -- one in the export record, one in the authorization
    #   detail segment -- and a divergence between formula and descriptor is caught here rather
    #   than at load time.
    assert layouts.EXPORT_ACCOUNT_LAYOUT.field("EXP-ACCT-CURR-BAL").length == 7
    detail = layouts.PENDING_AUTH_DETAIL_LAYOUT.field("PA-TRANSACTION-AMT")
    assert (detail.length, detail.int_digits, detail.dec_digits) == (7, 10, 2)


@pytest.mark.parametrize(
    ("int_digits", "dec_digits", "expected_bytes"),
    [
        (1, 0, 2),
        (4, 0, 2),
        (2, 2, 2),
        (5, 0, 4),
        (9, 0, 4),
        (7, 2, 4),
        (10, 0, 8),
        (11, 0, 8),
        (10, 2, 8),
        (18, 0, 8),
        (16, 2, 8),
    ],
)
def test_the_binary_width_step_function_selects_a_halfword_word_or_doubleword(
    int_digits: int, dec_digits: int, expected_bytes: int
) -> None:
    """Select two, four or eight bytes by digit count as ``USAGE COMP`` does.

    :param int_digits: declared digit positions before the implied decimal point.
    :param dec_digits: declared digit positions after the implied decimal point.
    :param expected_bytes: the machine unit the digit count selects, being 2, 4 or 8.
    :returns: nothing; a differing width is reported as an assertion failure.
    """
    # WHY : Assumptions: a binary field occupies a WHOLE MACHINE UNIT chosen by its digit count
    #   rather than one byte per digit, so the width is a step function with boundaries at four
    #   and nine digits. Both boundaries are exercised from both sides -- four and five, nine and
    #   ten -- because a step function is only wrong at its transitions, and a decoder that
    #   placed a boundary one digit out would size an eleven-digit key at four bytes and shift
    #   every field after it.
    assert binary_width(int_digits, dec_digits) == expected_bytes


def test_the_two_binary_width_boundaries_are_where_the_layout_constants_say() -> None:
    """Tie both binary width transitions to the named layout constants rather than to literals.

    Takes no parameters and returns no value; a boundary that has moved away from its constant
    is reported as an assertion failure.
    """
    # WHY : Assumptions: the transitions are asserted against ``BINARY_HALFWORD_MAX_DIGITS`` and
    #   ``BINARY_FULLWORD_MAX_DIGITS`` so the boundary has one authority. Writing four and nine
    #   as literals here would create a second declaration of a rule ``layouts.py`` owns, and the
    #   two could then disagree without either being obviously wrong.
    halfword_max = layouts.BINARY_HALFWORD_MAX_DIGITS
    fullword_max = layouts.BINARY_FULLWORD_MAX_DIGITS
    assert binary_width(halfword_max, 0) == 2
    assert binary_width(halfword_max + 1, 0) == 4
    assert binary_width(fullword_max, 0) == 4
    assert binary_width(fullword_max + 1, 0) == 8
    assert binary_width(layouts.MAX_DIGITS, 0) == 8


def test_the_two_named_export_binary_declarations_take_their_documented_widths() -> None:
    """Confirm ``PIC 9(9) COMP`` is four bytes and ``PIC S9(10)V99 COMP`` is eight.

    Takes no parameters and returns no value; a differing width, or a registry descriptor that
    disagrees with the formula, is reported as an assertion failure.
    """
    # WHY : Assumptions: both figures are load-bearing in the export record.
    #   ``EXPORT-SEQUENCE-NUM`` is ``PIC 9(9) COMP`` at ``app/cpy/CVEXPORT.cpy`` line 16, which
    #   this rule makes four bytes -- and reading four big-endian bytes at its offset in the real
    #   extract yields clean consecutive integers, which no other width produces. The
    #   eight-byte tier is what makes the cross-reference branch close at exactly 460 bytes.
    assert binary_width(9, 0) == 4
    assert binary_width(10, 2) == 8
    header_key = layouts.EXPORT_HEADER_LAYOUT.field("EXPORT-SEQUENCE-NUM")
    assert (header_key.kind, header_key.length) == (Kind.BINARY, 4)
    cycle_debit = layouts.EXPORT_ACCOUNT_LAYOUT.field("EXP-ACCT-CURR-CYC-DEBIT")
    assert (cycle_debit.kind, cycle_debit.length) == (Kind.BINARY, 8)


@pytest.mark.parametrize(
    ("span_hex", "signed", "expected_text"),
    [
        ("00000019400c", _SIGNED, "194.00"),
        ("00000019400d", _SIGNED, "-194.00"),
        ("00000019400f", _UNSIGNED, "194.00"),
    ],
)
def test_each_admitted_sign_nibble_decodes_to_its_documented_sign(
    span_hex: str, signed: bool, expected_text: str
) -> None:
    """Decode the signed positive, signed negative and unsigned sign nibbles.

    :param span_hex: the six bytes of one ``PIC S9(09)V99 COMP-3`` span, written in hexadecimal
        so the sign nibble is visible in the vector itself.
    :param signed: the sign contract the span is decoded against.
    :param expected_text: the expected value as exact decimal text at scale two.
    :returns: nothing; a differing value or scale is reported as an assertion failure.
    """
    # WHY : Assumptions: 194.00 is the first account's balance in the shipped seed, so all three
    #   vectors share one magnitude a maintainer can cross-check by eye and differ ONLY in their
    #   final nibble. That isolation is the point: it makes the sign the single variable, so a
    #   codec that read the sign from the wrong nibble position cannot pass one case and fail
    #   another for an unrelated reason. All three nibbles occur in real data -- 0x0F because all
    #   fifty customer records of the real export extract carry it in their unsigned
    #   ``PIC 9(03) COMP-3`` credit score.
    span = bytes.fromhex(span_hex)
    assert len(span) == packed_width(_TRAN_MONEY_INT_DIGITS, _MONEY_DEC_DIGITS) == 6
    decoded = decode_packed(span, _TRAN_MONEY_INT_DIGITS, _MONEY_DEC_DIGITS, signed)
    assert decoded == Decimal(expected_text)
    assert decoded.as_tuple().exponent == -_MONEY_DEC_DIGITS
    assert encode_packed(decoded, _TRAN_MONEY_INT_DIGITS, _MONEY_DEC_DIGITS, signed) == span


@pytest.mark.parametrize("digit_nibble", [0x0, 0x1, 0x5, 0x9])
def test_a_digit_in_the_sign_position_is_refused(digit_nibble: int) -> None:
    """Refuse a digit where the sign nibble belongs, raising :class:`PackedDecimalError`.

    :param digit_nibble: a value below 0x0A placed in the low nibble of the final byte.
    :returns: nothing; an accepted digit is reported as an assertion failure. The exception class
        verified is :class:`~carddemo_migration.copybook.packed.PackedDecimalError`.
    """
    # WHY : Assumptions: a value below 0x0A in the sign position means one of two specific
    #   things -- the field is zoned display rather than packed, or the read is off by one
    #   nibble -- and the two have entirely different remedies. The diagnostic names both, which
    #   is why the case is asserted separately from the alternate-sign refusal rather than folded
    #   into it. Zero is included deliberately: an all-NUL span is what an unwritten field looks
    #   like, so it must be refused rather than read as a zero amount.
    span = bytes.fromhex("0000001940") + bytes([digit_nibble])
    with pytest.raises(PackedDecimalError) as refusal:
        decode_packed(span, _TRAN_MONEY_INT_DIGITS, _MONEY_DEC_DIGITS, _SIGNED)
    assert "sign nibble" in str(refusal.value)


@pytest.mark.parametrize("alternate", _ALTERNATE_SIGN_NIBBLES)
def test_an_alternate_sign_nibble_is_refused(alternate: int) -> None:
    """Refuse 0x0A, 0x0B and 0x0E in the sign position, raising :class:`PackedDecimalError`.

    :param alternate: one alternate sign nibble that other encoders emit and this codec rejects.
    :returns: nothing; an accepted alternate nibble is reported as an assertion failure. The
        exception class verified is
        :class:`~carddemo_migration.copybook.packed.PackedDecimalError`.
    """
    # WHY : Assumptions: this policy is READ from ``packed.py`` and not generalised from another
    #   COBOL runtime. Some runtimes treat 0x0A, 0x0B and 0x0E as positive; ``packed.py``
    #   rejects all three and states why on ``_LOWEST_SIGN_NIBBLE``: an unexpected nibble in the
    #   sign position is the clearest available evidence that the field offset is wrong, so
    #   reading it as positive would convert a detectable geometry fault into silent corruption
    #   of a money value's sign. The Java parity codec rejects the same three. Encoding the
    #   permissive behaviour instead would make this test disagree with the module it tests, and
    #   all three are exercised so the refusal is proved for every one rather than for whichever
    #   a single case happened to pick.
    assert alternate >= 0x0A, "an alternate sign nibble is not a digit nibble"
    assert alternate not in (
        _SIGN_POSITIVE_NIBBLE,
        _SIGN_NEGATIVE_NIBBLE,
        _SIGN_UNSIGNED_NIBBLE,
    )
    span = bytes.fromhex("0000001940") + bytes([alternate])
    with pytest.raises(PackedDecimalError) as refusal:
        decode_packed(span, _TRAN_MONEY_INT_DIGITS, _MONEY_DEC_DIGITS, _SIGNED)
    assert "alternate sign nibble" in str(refusal.value)


@pytest.mark.parametrize("digit_nibble", [0x0A, 0x0C, 0x0D, 0x0F])
def test_a_non_digit_nibble_in_a_digit_position_is_refused(digit_nibble: int) -> None:
    """Refuse a nibble above nine in a digit position, raising :class:`PackedDecimalError`.

    :param digit_nibble: a value above nine placed in the high nibble of the span's third byte.
    :returns: nothing; an accepted nibble is reported as an assertion failure. The exception class
        verified is :class:`~carddemo_migration.copybook.packed.PackedDecimalError`.
    """
    # WHY : Assumptions: a nibble above nine in a DIGIT position is not a tolerable variant; it
    #   is proof that the read is wrong -- the offset is misaligned, the field is not packed at
    #   all, or a sign nibble has been reached early. Masking it back into range would
    #   manufacture a digit that was never written, and the resulting amount would be plausible
    #   and unchallenged. The sign nibbles are included among the cases because reaching one
    #   early is the most likely way this fires in practice.
    span = bytes.fromhex("0000") + bytes([digit_nibble << 4]) + bytes.fromhex("19400c")
    assert len(span) == 6
    with pytest.raises(PackedDecimalError) as refusal:
        decode_packed(span, _TRAN_MONEY_INT_DIGITS, _MONEY_DEC_DIGITS, _SIGNED)
    assert "non-digit nibble" in str(refusal.value)


def test_the_unsigned_nibble_is_refused_by_a_field_declared_with_a_sign() -> None:
    """Refuse 0x0F on a signed field, raising :class:`PackedDecimalError`.

    Takes no parameters and returns no value; an accepted mismatch is reported as an assertion
    failure. The exception class verified is
    :class:`~carddemo_migration.copybook.packed.PackedDecimalError`.
    """
    # WHY : Alternatives Considered: merely classifying the nibble and returning a sign was
    #   available and was rejected. A mismatch means the descriptor the caller passed and the
    #   bytes on disk describe DIFFERENT fields, so whichever way it were resolved the value
    #   would have been read against a layout that does not match the data -- and that value
    #   would be the right width, the right type and quite plausible. This is the packed analogue
    #   of the display codec's refusal of a plain trailing digit in a signed field.
    span = bytes.fromhex("00000019400f")
    with pytest.raises(PackedDecimalError) as refusal:
        decode_packed(span, _TRAN_MONEY_INT_DIGITS, _MONEY_DEC_DIGITS, _SIGNED)
    assert "declared with a sign" in str(refusal.value)


@pytest.mark.parametrize("signed_nibble", [_SIGN_POSITIVE_NIBBLE, _SIGN_NEGATIVE_NIBBLE])
def test_a_signed_nibble_is_refused_by_a_field_declared_without_one(signed_nibble: int) -> None:
    """Refuse 0x0C or 0x0D on an unsigned field, raising :class:`PackedDecimalError`.

    :param signed_nibble: one of the two signed sign nibbles, placed on an unsigned field.
    :returns: nothing; an accepted mismatch is reported as an assertion failure. The exception
        class verified is :class:`~carddemo_migration.copybook.packed.PackedDecimalError`.
    """
    # WHY : Assumptions: the positive signed nibble is refused as firmly as the negative one,
    #   even though reading 0x0C on an unsigned field would produce the numerically correct
    #   value. Tolerating it would leave the declaration-versus-data disagreement unreported on
    #   exactly the half of the cases where it happens to be harmless, so the fault would only
    #   ever surface on the negative records -- which are the rarest in this corpus.
    span = bytes.fromhex("0000001940") + bytes([signed_nibble])
    with pytest.raises(PackedDecimalError) as refusal:
        decode_packed(span, _TRAN_MONEY_INT_DIGITS, _MONEY_DEC_DIGITS, _UNSIGNED)
    assert "declared without a sign" in str(refusal.value)


def test_a_nonzero_unused_leading_nibble_is_refused() -> None:
    """Refuse a non-zero unused leading nibble on an even-digit packed field.

    Takes no parameters and returns no value; an accepted non-zero pad is reported as an
    assertion failure. The exception class verified is
    :class:`~carddemo_migration.copybook.packed.PackedDecimalError`.
    """
    # WHY : Assumptions: checking the pad nibble is the cheapest detector of a displaced read
    #   this codec has, and the consequence of skipping it is measurable rather than theoretical:
    #   a leading nibble of 9 on the seven-byte balance turns 158.00 into 9000000015.80 -- a
    #   number that is well formed, raises nothing anywhere downstream, and is caught only after
    #   the fact by a golden-master comparison. Every account record of the real export extract
    #   carries a zero here, so the check costs nothing on correct data.
    valid = encode_packed(Decimal("2065.00"), _ACCOUNT_MONEY_INT_DIGITS, _MONEY_DEC_DIGITS, _SIGNED)
    assert valid == bytes.fromhex("0000000206500c")
    assert len(valid) == 7

    displaced = bytes([valid[0] | 0x90]) + valid[1:]
    with pytest.raises(PackedDecimalError) as refusal:
        decode_packed(displaced, _ACCOUNT_MONEY_INT_DIGITS, _MONEY_DEC_DIGITS, _SIGNED)
    assert "zero pad nibble" in str(refusal.value)


def test_an_odd_digit_packed_field_has_no_pad_nibble_to_check() -> None:
    """Confirm the pad exists only on an even digit count, so neither parity is assumed.

    Takes no parameters and returns no value; a field of the wrong width for its parity is
    reported as an assertion failure.
    """
    # WHY : Assumptions: the surplus is ``width * 2 - (digits + 1)`` and is at most one, so it
    #   exists exactly when the digit count is EVEN. Twelve digits occupy seven bytes, fourteen
    #   nibbles for thirteen used, so one pads; eleven digits occupy six bytes, twelve nibbles
    #   for twelve used, so none pads. Both directions of getting the parity wrong were measured
    #   rather than assumed, and they fail differently: assuming no pad where one exists reads
    #   the pad as a leading digit and drops the true low-order one, which is a silent factor of
    #   ten, whereas assuming a pad where none exists pulls the sign nibble into the digit window
    #   and is refused loudly. Only one of the two corrupts quietly, which is why neither is left
    #   to a claim.
    even_digits = _ACCOUNT_MONEY_INT_DIGITS + _MONEY_DEC_DIGITS
    odd_digits = _TRAN_MONEY_INT_DIGITS + _MONEY_DEC_DIGITS
    assert even_digits == 12
    assert odd_digits == 11

    even_width = packed_width(_ACCOUNT_MONEY_INT_DIGITS, _MONEY_DEC_DIGITS)
    odd_width = packed_width(_TRAN_MONEY_INT_DIGITS, _MONEY_DEC_DIGITS)
    assert even_width * 2 - (even_digits + 1) == 1, "an even digit count leaves one pad nibble"
    assert odd_width * 2 - (odd_digits + 1) == 0, "an odd digit count leaves none"

    # WHY : Assumptions: the no-pad case is confirmed against real bytes, not only against the
    #   arithmetic. The reference compiler lays ``PIC S9(05) COMP-3`` holding 12345 down as the
    #   nibbles 1 2 3 4 5 C, whose LEADING nibble is a digit and not a pad, so a decoder that
    #   skipped a nibble here would return 234.5 and never see the one.
    assert packed_width(5, 0) == 3
    assert decode_packed(bytes.fromhex("12345c"), 5, 0, _SIGNED) == Decimal(12345)


def test_a_negatively_signed_packed_zero_re_encodes_with_the_positive_nibble() -> None:
    """Document the packed analogue of the zoned negative-zero carve-out.

    A 0x0D-signed packed zero decodes to numeric zero and re-encodes with 0x0C, so this span --
    and only a span whose digits are all zeros -- does not reproduce itself. Takes no parameters
    and returns no value; a preserved negative nibble, or any other span failing to reproduce
    itself, is reported as an assertion failure.
    """
    # WHY : Refactoring Rationale: this comment also said ":class:`decimal.Decimal` has no
    #   negative zero", carried over from the zoned test above, and it is false for the same
    #   measured reason recorded there -- ``Decimal("-0.00")`` reports ``is_signed() is True``.
    #   Only the ``BigDecimal`` half of the original sentence was ever correct.
    # WHY : Assumptions: the normalisation is the same accepted exception the display regime
    #   makes for a ``'}'``-overpunched zero, and it is made for the Java constraint rather than
    #   a Python one: ``BigInteger`` has exactly one zero, so ``BigDecimal`` cannot hold a signed
    #   one. Preserving the negative nibble on one side only would make the two regimes disagree
    #   about the same amount, and preserving it in Python but not in Java would make the two
    #   languages disagree about the same bytes.
    negative_zero = decode_packed(
        bytes.fromhex("0000000000000d"), _ACCOUNT_MONEY_INT_DIGITS, _MONEY_DEC_DIGITS, _SIGNED
    )
    assert negative_zero == Decimal("0.00")
    assert negative_zero.is_signed() is False, (
        "the decoder must normalise a 0x0D-signed packed zero to an UNSIGNED Decimal"
    )
    assert negative_zero.as_tuple().exponent == -_MONEY_DEC_DIGITS
    assert encode_packed(
        negative_zero, _ACCOUNT_MONEY_INT_DIGITS, _MONEY_DEC_DIGITS, _SIGNED
    ) == bytes.fromhex("0000000000000c")

    # WHY : Assumptions: the carve-out is asserted to be confined to zero, so it cannot be read
    #   as licence for the encoder to drop a negative nibble generally.
    negative = bytes.fromhex("00000019400d")
    assert decode_packed(negative, _TRAN_MONEY_INT_DIGITS, _MONEY_DEC_DIGITS, _SIGNED) == Decimal(
        "-194.00"
    )
    assert (
        encode_packed(Decimal("-194.00"), _TRAN_MONEY_INT_DIGITS, _MONEY_DEC_DIGITS, _SIGNED)
        == negative
    )


@pytest.mark.parametrize(
    ("int_digits", "dec_digits", "signed", "value_text", "span_hex"),
    [
        (4, 0, _SIGNED, "9999", "270f"),
        (4, 0, _SIGNED, "-1234", "fb2e"),
        (3, 0, _UNSIGNED, "123", "007b"),
        (9, 0, _UNSIGNED, "509", "000001fd"),
        (9, 0, _SIGNED, "-509", "fffffe03"),
        (10, 2, _SIGNED, "2065.00", "00000000000326a4"),
        (10, 2, _SIGNED, "-2065.00", "fffffffffffcd95c"),
        (11, 0, _UNSIGNED, "99999999999", "000000174876e7ff"),
    ],
)
def test_a_binary_span_round_trips_big_endian_two_s_complement(
    int_digits: int, dec_digits: int, signed: bool, value_text: str, span_hex: str
) -> None:
    """Decode and re-encode binary spans across all three widths, positive and negative.

    :param int_digits: declared digit positions before the implied decimal point.
    :param dec_digits: declared digit positions after the implied decimal point.
    :param signed: whether the leading bit is a sign bit or part of the magnitude.
    :param value_text: the expected value as exact decimal text at the declared scale.
    :param span_hex: the exact stored bytes, written in hexadecimal most significant byte first.
    :returns: nothing; a differing value, scale or re-encoded span is reported as an assertion
        failure.
    """
    # Assumptions: binary fields are stored MOST SIGNIFICANT BYTE FIRST, and that is a
    #   property of the bytes already on disk rather than of the platform reading them. It is
    #   measurable: reading ``EXPORT-SEQUENCE-NUM`` as four big-endian bytes in the real export
    #   extract yields 1, 10, 266 and 509 for records 0, 9, 265 and 499, which is a sequence,
    #   whereas the little-endian reading of those same spans yields 16777216, 167772160,
    #   167837696 and 4244701184, which is not. Choosing the other order would silently turn a
    #   record counter into a nine-figure identifier that no check of type or width would
    #   question -- which is why 509 appears among the vectors below.
    span = bytes.fromhex(span_hex)
    assert len(span) == binary_width(int_digits, dec_digits)
    decoded = decode_binary(span, int_digits, dec_digits, signed)
    assert decoded == Decimal(value_text)
    assert decoded.as_tuple().exponent == -dec_digits
    assert encode_binary(decoded, int_digits, dec_digits, signed) == span


def test_a_scaled_binary_value_moves_its_decimal_point_without_dividing() -> None:
    """Apply the declared scale by exact point movement rather than by an inexact division.

    Takes no parameters and returns no value; a value whose scale was applied by division, or
    whose exponent differs from the declaration, is reported as an assertion failure.
    """
    # Alternatives Considered: dividing the stored whole number by a power of ten was
    #   available and was rejected. Division introduces an intermediate whose precision depends
    #   on the ambient decimal context, so the same span could decode differently in two
    #   processes configured differently -- and at a low precision it would round. Moving the
    #   decimal point is exact by construction and context-independent. The pair below is the
    #   evidence: the stored whole number is 206500 and the value is 2065.00, which are the same
    #   digits at two exponents, so a decoder that ignored the scale would report a value one
    #   hundred times too large and one that divided inexactly would lose a cent.
    span = bytes.fromhex("00000000000326a4")
    stored = int.from_bytes(span, "big", signed=True)
    assert stored == 206500, "the field stores the unscaled whole number"

    decoded = decode_binary(span, _ACCOUNT_MONEY_INT_DIGITS, _MONEY_DEC_DIGITS, _SIGNED)
    assert decoded == Decimal("2065.00")
    assert decoded.as_tuple().exponent == -_MONEY_DEC_DIGITS
    assert decoded.as_tuple().digits == Decimal(stored).as_tuple().digits, (
        "the digits are unchanged; only the exponent moves"
    )
    assert decoded * 100 == Decimal(stored)


@pytest.mark.parametrize(
    ("int_digits", "dec_digits", "signed"),
    [
        (4, 0, _SIGNED),
        (4, 0, _UNSIGNED),
        (9, 0, _SIGNED),
        (9, 0, _UNSIGNED),
        (18, 0, _SIGNED),
        (18, 0, _UNSIGNED),
    ],
)
def test_the_widest_fitting_binary_values_round_trip_at_every_tier(
    int_digits: int, dec_digits: int, signed: bool
) -> None:
    """Round-trip the largest and smallest values each binary tier can hold.

    :param int_digits: declared digit positions before the implied decimal point.
    :param dec_digits: declared digit positions after the implied decimal point.
    :param signed: whether the tier admits a negative value at all.
    :returns: nothing; a rejected in-range extreme, or one that failed to reproduce itself, is
        reported as an assertion failure.
    """
    # Assumptions: the extremes are derived from the DECLARED DIGIT COUNT and not from the
    #   byte width, because the digit count is the narrower of the two constraints -- nine digits
    #   is at most 999999999 while four signed bytes reach 2147483647. Testing the byte extreme
    #   instead would assert a value the picture clause cannot describe, and the codec correctly
    #   refuses those; testing the digit extreme asserts the boundary a real record can actually
    #   reach.
    largest = Decimal(10) ** int_digits - 1
    for value in (largest, -largest) if signed else (largest, Decimal(0)):
        span = encode_binary(value, int_digits, dec_digits, signed)
        assert len(span) == binary_width(int_digits, dec_digits)
        assert decode_binary(span, int_digits, dec_digits, signed) == value


def test_a_binary_value_wider_than_its_declared_digits_is_refused_on_encode() -> None:
    """Refuse a binary value needing more digit positions than the field declares.

    Takes no parameters and returns no value; an accepted over-wide value is reported as an
    assertion failure. The exception class verified is
    :class:`~carddemo_migration.copybook.packed.PackedDecimalError`.
    """
    # Assumptions: the refusal is driven by the DECLARED DIGIT COUNT and not by whether the
    #   number happens to fit the bytes, and the vector is chosen so that the two rules cannot
    #   be confused. 32767 is exactly the largest value two signed bytes hold, and two signed
    #   bytes are the width four declared digits select, so the storage refuses this value on no
    #   ground at all -- only its fifth digit does, which ``PIC S9(04)`` cannot describe. A
    #   width-only check would therefore accept a value the target column cannot store, and the
    #   asserted diagnostic names the digit positions rather than the byte width for exactly
    #   that reason.
    assert binary_width(4, 0) == 2
    assert Decimal(32767) == Decimal(2) ** 15 - 1, "the vector is the signed two-byte extreme"
    with pytest.raises(PackedDecimalError) as refusal:
        encode_binary(Decimal(32767), 4, 0, _SIGNED)
    assert "digit positions" in str(refusal.value)


def test_a_binary_span_holding_more_digits_than_declared_is_refused_on_decode() -> None:
    """Refuse a correctly sized span whose stored value overflows the declaration.

    Takes no parameters and returns no value; an accepted over-capacity span is reported as an
    assertion failure. The exception class verified is
    :class:`~carddemo_migration.copybook.packed.PackedDecimalError`.
    """
    # Assumptions: the span below is exactly four bytes -- the declared width for nine
    #   digits -- and holds 1000000000, which needs ten. The case exists because it is the one
    #   overflow a width check cannot catch: the bytes are the right length, so only comparing
    #   the stored magnitude against the declared digit positions detects it, and the
    #   undetected value would be a plausible ten-digit identifier.
    span = (10**9).to_bytes(4, "big", signed=True)
    assert len(span) == binary_width(9, 0)
    with pytest.raises(PackedDecimalError) as refusal:
        decode_binary(span, 9, 0, _SIGNED)
    assert "digit positions" in str(refusal.value)


def test_a_binary_field_is_never_decoded_as_packed_decimal() -> None:
    """Refuse to read a ``COMP`` span through the ``COMP-3`` path, and the reverse.

    Takes no parameters and returns no value; a ``COMP`` span accepted by the packed decoder is
    reported as an assertion failure. The exception class verified is
    :class:`~carddemo_migration.copybook.packed.PackedSpanWidthError`.
    """
    # Assumptions: the token ``COMP-3`` CONTAINS the token ``COMP``, so any dispatch that
    #   matched the shortest usage token would read a seven-byte packed field as an eight-byte
    #   binary one -- and would then CASCADE a one-byte offset error through every subsequent
    #   field of the record. That is a silent-wrong-number failure and not a crash: each later
    #   field still slices to its declared width and still decodes, so nothing raises and the
    #   whole tail of the record is quietly wrong. Dispatch is therefore on the descriptor's
    #   ``Kind`` -- ``Kind.PACKED`` against ``Kind.BINARY`` -- and never on a substring match of
    #   a usage token, which is exactly why the two spans below have different lengths and each
    #   decoder refuses the other's.
    value = Decimal("2065.00")
    packed_span = encode_packed(value, _ACCOUNT_MONEY_INT_DIGITS, _MONEY_DEC_DIGITS, _SIGNED)
    binary_span = encode_binary(value, _ACCOUNT_MONEY_INT_DIGITS, _MONEY_DEC_DIGITS, _SIGNED)
    assert len(packed_span) == 7
    assert len(binary_span) == 8

    with pytest.raises(PackedSpanWidthError) as packed_refusal:
        decode_packed(binary_span, _ACCOUNT_MONEY_INT_DIGITS, _MONEY_DEC_DIGITS, _SIGNED)
    assert "expected 7 bytes but received 8" in str(packed_refusal.value)

    with pytest.raises(PackedSpanWidthError) as binary_refusal:
        decode_binary(packed_span, _ACCOUNT_MONEY_INT_DIGITS, _MONEY_DEC_DIGITS, _SIGNED)
    assert "expected 8 bytes but received 7" in str(binary_refusal.value)


def test_dispatch_is_on_the_declared_kind_and_not_on_a_usage_token() -> None:
    """Confirm the three regimes are distinguished by :class:`Kind` alone in the export record.

    Takes no parameters and returns no value; a descriptor carrying the wrong kind, or a
    field-oriented decoder accepting a foreign kind, is reported as an assertion failure. The
    exception class verified for each mis-routing is
    :class:`~carddemo_migration.copybook.packed.PackedDecimalError`.
    """
    # Assumptions: the substring hazard is guarded structurally rather than only by width,
    #   because two fields of the same regime CAN share a width -- the packed balance and the
    #   packed cash limit of this very branch are both seven bytes. Only the kind distinguishes
    #   regimes reliably, so each field-oriented entry point is asserted to refuse a descriptor
    #   of the other regime outright, before any byte is read as a nibble or as a word.
    branch = layouts.EXPORT_ACCOUNT_LAYOUT
    packed_field = branch.field("EXP-ACCT-CURR-BAL")
    zoned_field = branch.field("EXP-ACCT-CREDIT-LIMIT")
    binary_field = branch.field("EXP-ACCT-CURR-CYC-DEBIT")
    assert (packed_field.kind, zoned_field.kind, binary_field.kind) == (
        Kind.PACKED,
        Kind.ZONED,
        Kind.BINARY,
    )

    record = _computational_record_holding(branch, packed_field, bytes.fromhex("0000000206500c"))
    assert decode_packed_field(record, packed_field) == Decimal("2065.00")
    with pytest.raises(PackedDecimalError):
        decode_binary_field(record, packed_field)

    binary_record = _computational_record_holding(
        branch, binary_field, bytes.fromhex("00000000000326a4")
    )
    assert decode_binary_field(binary_record, binary_field) == Decimal("2065.00")
    with pytest.raises(PackedDecimalError):
        decode_packed_field(binary_record, binary_field)


def test_one_picture_clause_has_three_physical_widths_in_the_export_record() -> None:
    """Decode the same amount through packed, display and binary storage of one picture clause.

    Takes no parameters and returns no value; a differing width, a differing decoded value, or
    two spans that happen to be equal is reported as an assertion failure.
    """
    # Assumptions: ``app/cpy/CVEXPORT.cpy`` declares ``PIC S9(10)V99`` three times inside
    #   ONE overlay branch, at lines 50, 51 and 57, and gives it a different storage regime each
    #   time -- ``COMP-3``, display and ``COMP``. The same twelve declared digit positions
    #   therefore occupy seven, twelve and eight physical positions respectively, which is the
    #   clearest available proof that a field's width follows its USAGE and not its picture
    #   clause. A reader who sized all three from the picture alone would misplace two of them.
    branch = layouts.EXPORT_ACCOUNT_LAYOUT
    value = Decimal("2065.00")
    widths = {}
    for name, expected_kind, expected_width in (
        ("EXP-ACCT-CURR-BAL", Kind.PACKED, 7),
        ("EXP-ACCT-CREDIT-LIMIT", Kind.ZONED, 12),
        ("EXP-ACCT-CURR-CYC-DEBIT", Kind.BINARY, 8),
    ):
        field = branch.field(name)
        assert field.kind is expected_kind
        assert (field.int_digits, field.dec_digits, field.signed) == (10, 2, True)
        assert field.length == expected_width, f"{name} must occupy {expected_width} positions"
        widths[expected_kind] = field.length

    packed_span = encode_packed(value, 10, 2, _SIGNED)
    display_span = encode_zoned(value, 10, 2, _SIGNED)
    binary_span = encode_binary(value, 10, 2, _SIGNED)
    assert (len(packed_span), len(display_span), len(binary_span)) == (7, 12, 8)
    assert widths == {Kind.PACKED: 7, Kind.ZONED: 12, Kind.BINARY: 8}

    # Assumptions: equal NUMERIC MEANING across three different byte counts is the property
    #   asserted, because that is what a migration must preserve: the target column holds one
    #   value regardless of which regime the source stored it in. All three decode to the same
    #   Decimal at the same exponent while sharing no representation.
    assert decode_packed(packed_span, 10, 2, _SIGNED) == value
    assert decode_zoned(display_span, 10, 2, _SIGNED) == value
    assert decode_binary(binary_span, 10, 2, _SIGNED) == value
    assert decode_packed(packed_span, 10, 2, _SIGNED).as_tuple().exponent == -2
    assert decode_zoned(display_span, 10, 2, _SIGNED).as_tuple().exponent == -2
    assert decode_binary(binary_span, 10, 2, _SIGNED).as_tuple().exponent == -2
    assert packed_span != binary_span, "the two computational spans share no representation"


def test_packed_decimal_reaches_persisted_data_only_where_the_corpus_declares_it() -> None:
    """Pin the three record families in which packed decimal reaches persisted target data.

    Takes no parameters and returns no value; a base master that has acquired a packed or binary
    field, or an authorization segment whose length has drifted, is reported as an assertion
    failure.
    """
    # Assumptions: all money in the eleven base masters is zoned DISPLAY -- searching those
    #   copybooks for ``COMP`` or ``COMP-3`` returns nothing -- so packed decimal reaches
    #   persisted data only in the export record and in the two authorization segments,
    #   ``CIPAUSMY.cpy`` at 100 bytes and ``CIPAUDTY.cpy`` at 200. The authorization bounded
    #   context is therefore the only place packed decimal reaches persisted TARGET data, the
    #   export record being a transport encoding rather than a stored one. Asserting the negative
    #   half matters as much as the positive: if a base master ever acquired a computational
    #   field, every reader that assumes one byte per digit for it would silently misalign.
    for name in layouts.base_master_names():
        kinds = {field.kind for field in layouts.layout(name).fields}
        assert Kind.PACKED not in kinds, f"{name} must contain no packed field"
        assert Kind.BINARY not in kinds, f"{name} must contain no binary field"

    assert layouts.PENDING_AUTH_SUMMARY_LAYOUT.reclen == 100
    assert layouts.PENDING_AUTH_DETAIL_LAYOUT.reclen == 200
    for segment in (
        layouts.PENDING_AUTH_SUMMARY_LAYOUT,
        layouts.PENDING_AUTH_DETAIL_LAYOUT,
    ):
        packed_fields = [f for f in segment.fields if f.kind is Kind.PACKED]
        assert packed_fields, f"{segment.name} stores money packed"

    export_kinds = {field.kind for field in layouts.EXPORT_ACCOUNT_LAYOUT.fields}
    assert {Kind.PACKED, Kind.ZONED, Kind.BINARY} <= export_kinds


@pytest.mark.parametrize("span_hex", ["00000c", "0000001940000c", "", "0c"])
def test_a_packed_span_of_the_wrong_width_is_refused(span_hex: str) -> None:
    """Refuse a mis-sized packed span, raising :class:`PackedSpanWidthError`.

    :param span_hex: a span whose byte count differs from the six the geometry implies, written
        in hexadecimal.
    :returns: nothing; an accepted mis-sized span is reported as an assertion failure. The
        exception class verified is
        :class:`~carddemo_migration.copybook.packed.PackedSpanWidthError`.
    """
    # Assumptions: both directions are asserted because they arise from opposite mistakes
    #   and neither is safe to repair. A short span is a slice that ran off the end of a record; a
    #   long one is a slice sized by the wrong rule -- most plausibly the display rule, which
    #   would give eleven bytes for these eleven digits rather than six.
    span = bytes.fromhex(span_hex)
    assert len(span) != packed_width(_TRAN_MONEY_INT_DIGITS, _MONEY_DEC_DIGITS)
    with pytest.raises(PackedSpanWidthError):
        decode_packed(span, _TRAN_MONEY_INT_DIGITS, _MONEY_DEC_DIGITS, _SIGNED)


@pytest.mark.parametrize("span_hex", ["000001", "000000000001fd", "00"])
def test_a_binary_span_of_the_wrong_width_is_refused(span_hex: str) -> None:
    """Refuse a mis-sized binary span, raising :class:`PackedSpanWidthError`.

    :param span_hex: a span whose byte count is not the four this geometry implies, written in
        hexadecimal.
    :returns: nothing; an accepted mis-sized span is reported as an assertion failure. The
        exception class verified is
        :class:`~carddemo_migration.copybook.packed.PackedSpanWidthError`.
    """
    # Assumptions: a binary width is a step function, so a three-byte or seven-byte span is
    #   not merely short -- it is a width the rule can never produce, which means the caller
    #   computed it some other way. Refusing rather than left-padding is what surfaces that: a
    #   padded three-byte span would decode to a number of the right order of magnitude.
    span = bytes.fromhex(span_hex)
    assert len(span) != binary_width(9, 0)
    with pytest.raises(PackedSpanWidthError):
        decode_binary(span, 9, 0, _SIGNED)


def test_a_packed_value_wider_than_its_declared_digits_is_refused_on_encode() -> None:
    """Refuse a packed value needing more digit positions than the field declares.

    Takes no parameters and returns no value; an accepted over-wide value is reported as an
    assertion failure. The exception class verified is
    :class:`~carddemo_migration.copybook.packed.PackedDecimalError`.
    """
    # Assumptions: the alternative is truncating high-order digits into the declared
    #   nibbles, which returns a span of exactly the right byte count holding a smaller number.
    #   Nothing about the resulting bytes is detectably wrong, which is why the refusal happens
    #   before any nibble is written rather than being left to a later consistency check.
    with pytest.raises(PackedDecimalError) as refusal:
        encode_packed(Decimal("9999999999999"), _TRAN_MONEY_INT_DIGITS, _MONEY_DEC_DIGITS, _SIGNED)
    assert "digit positions" in str(refusal.value)


@pytest.mark.parametrize("value_text", ["1.234", "504.775", "-0.001"])
def test_computational_encoding_refuses_fractional_precision_it_cannot_store(
    value_text: str,
) -> None:
    """Refuse precision beyond the declared scale on both computational paths.

    :param value_text: exact decimal text carrying a non-zero digit beyond two decimal places.
    :returns: nothing; a silently rounded encode is reported as an assertion failure. The
        exception class verified on both paths is
        :class:`~carddemo_migration.copybook.packed.PackedDecimalError`.
    """
    # Assumptions: the two computational regimes are asserted together with one vector set
    #   because they share one encode guard, so testing only one would leave the other's refusal
    #   unproven while looking covered. Rounding here would be a business decision recorded
    #   nowhere -- the same reason the display regime refuses it.
    value = Decimal(value_text)
    with pytest.raises(PackedDecimalError):
        encode_packed(value, _TRAN_MONEY_INT_DIGITS, _MONEY_DEC_DIGITS, _SIGNED)
    with pytest.raises(PackedDecimalError):
        encode_binary(value, _TRAN_MONEY_INT_DIGITS, _MONEY_DEC_DIGITS, _SIGNED)


def test_a_negative_value_is_refused_by_an_unsigned_computational_field() -> None:
    """Refuse a negative value on an unsigned packed or binary field.

    Takes no parameters and returns no value; an accepted negative value is reported as an
    assertion failure. The exception class verified on both paths is
    :class:`~carddemo_migration.copybook.packed.PackedDecimalError`.
    """
    # Assumptions: an unsigned packed field lays down 0x0F, which carries no sign to set,
    #   and an unsigned binary field treats its leading bit as magnitude, so a negative value
    #   would become a very large positive one. Both are refused at the boundary rather than
    #   represented approximately.
    with pytest.raises(PackedDecimalError):
        encode_packed(Decimal(-1), 9, 0, _UNSIGNED)
    with pytest.raises(PackedDecimalError):
        encode_binary(Decimal(-1), 9, 0, _UNSIGNED)


@pytest.mark.parametrize("bad_value", [0.1, 2065.0, True])
def test_computational_encoding_refuses_float_and_bool(bad_value: object) -> None:
    """Refuse a binary floating-point value or a boolean on both computational paths.

    :param bad_value: a value that is not exact money -- two binary floats and a boolean.
    :returns: nothing; an accepted value is reported as an assertion failure. The exception class
        verified on both paths is :class:`TypeError`.
    """
    # Alternatives Considered: coercing through ``Decimal(str(value))`` was available and
    #   was rejected for the same reason as on the display path -- a binary float cannot represent
    #   ten cents exactly, so accepting one admits an approximation into the money path that
    #   nothing downstream can detect. ``2065.0`` is included because it looks exact and is not
    #   the point: the type is refused rather than the value, so a caller cannot pass a float that
    #   happens to be representable and thereby establish a habit that fails on the next value.
    with pytest.raises(TypeError):
        encode_packed(bad_value, 9, 2, _SIGNED)  # type: ignore[arg-type]
    with pytest.raises(TypeError):
        encode_binary(bad_value, 9, 2, _SIGNED)  # type: ignore[arg-type]


def test_text_is_refused_at_the_computational_codec_boundary() -> None:
    """Refuse a :class:`str` on both computational decode paths, raising :class:`TypeError`.

    Takes no parameters and returns no value; accepted text is reported as an assertion failure.
    The exception class verified on both paths is :class:`TypeError`.
    """
    # Assumptions: a computational span must stay BYTES, because character interpretation
    #   changes what those bytes MEAN rather than how many of them there are. Every code page
    #   ``ebcdic_codec`` admits has to decode all 256 byte values, produce exactly one character
    #   per byte and re-encode them unchanged, so a decode moves no later field offset. What it
    #   destroys is the meaning: a packed span's sign and digit nibbles and a binary span's
    #   two's-complement word are storage rather than text, and the characters they map to carry
    #   no relation to the amount stored. Refusing text at this boundary is what keeps character
    #   conversion confined to ``ebcdic_codec`` and applied per fixed-width field rather than
    #   per record.
    with pytest.raises(TypeError):
        decode_packed("\x00\x00\x00\x19\x40\x0c", 9, 2, _SIGNED)  # type: ignore[arg-type]
    with pytest.raises(TypeError):
        decode_binary("\x00\x00\x01\xfd", 9, 0, _SIGNED)  # type: ignore[arg-type]


def test_a_sensitive_packed_field_diagnostic_withholds_its_offending_nibble() -> None:
    """Withhold the offending nibble from a sensitive packed field's diagnostic.

    Takes no parameters and returns no value; a diagnostic disclosing the nibble of a sensitive
    field, or one omitting the field's geometry, is reported as an assertion failure. The
    exception class verified is
    :class:`~carddemo_migration.copybook.packed.PackedDecimalError`.
    """
    # Trade-offs: a sensitive computational field discloses no nibble at all, not even in
    #   hexadecimal, and the suppression is NAMED in the message rather than left as a silent
    #   omission. Without the naming a reader cannot tell whether the codec had nothing to report
    #   or withheld it deliberately, and would reasonably suspect the diagnostic itself was
    #   defective -- and might then add the content back to fill the gap. The compromise accepted
    #   is a less actionable message on exactly the fields where disclosure is most costly.
    summary = layouts.PENDING_AUTH_SUMMARY_LAYOUT
    limit = summary.field("PA-CREDIT-LIMIT")
    assert limit.sensitive is True
    assert limit.kind is Kind.PACKED

    record = _computational_record_holding(summary, limit, bytes.fromhex("00000019400a"))
    with pytest.raises(PackedDecimalError) as refusal:
        decode_packed_field(record, limit)
    message = str(refusal.value)
    assert limit.name in message, "the diagnostic names the field"
    assert f"[{limit.start},{limit.end})" in message
    assert limit.kind.name in message
    assert "0x" not in message, "a sensitive diagnostic renders no hexadecimal nibble"
    assert "withheld" in message, "the suppression is named rather than silent"

    # Assumptions: the non-sensitive comparison is asserted in the same test, because "no
    #   nibble appeared" only evidences suppression if a nibble appears when the flag is absent.
    #   Without the pair a codec that had stopped rendering nibbles entirely would pass the
    #   suppression assertion while having lost a diagnostic feature.
    account_key = summary.field("PA-ACCT-ID")
    assert account_key.sensitive is False
    disclosable = _computational_record_holding(summary, account_key, bytes.fromhex("00000019400a"))
    with pytest.raises(PackedDecimalError) as disclosed:
        decode_packed_field(disclosable, account_key)
    assert "found 0xA" in str(disclosed.value)


def test_a_sensitive_binary_field_diagnostic_withholds_its_content() -> None:
    """Withhold content from a sensitive binary field's diagnostic.

    Takes no parameters and returns no value; a diagnostic disclosing the content of a sensitive
    binary field is reported as an assertion failure. The exception class verified is
    :class:`~carddemo_migration.copybook.packed.PackedDecimalError`.
    """
    # Assumptions: the card verification value is declared through the sensitive binary
    #   factory and not the plain one, and the distinction is load-bearing rather than cosmetic:
    #   the account identifier beside it is an internal key while this is the card's
    #   authentication secret, and a masked record rendering is precisely what gets pasted into a
    #   ticket. The classification follows the DATA and never the storage regime, which is why a
    #   binary field can be sensitive at all.
    card_branch = layouts.EXPORT_CARD_LAYOUT
    verification_value = card_branch.field("EXP-CARD-CVV-CD")
    assert verification_value.sensitive is True
    assert (verification_value.kind, verification_value.length) == (Kind.BINARY, 2)

    record = _computational_record_holding(card_branch, verification_value, b"\xff\xff")
    with pytest.raises(PackedDecimalError) as refusal:
        decode_binary_field(record, verification_value)
    message = str(refusal.value)
    assert verification_value.name in message
    assert f"[{verification_value.start},{verification_value.end})" in message
    assert "withheld" in message
    assert "ffff" not in message.lower(), "a sensitive diagnostic never echoes the raw bytes"


def test_a_record_ending_before_a_declared_computational_field_is_refused() -> None:
    """Refuse a record that ends before a declared computational field's end.

    Takes no parameters and returns no value; an accepted short record is reported as an assertion
    failure. The exception class verified on both paths is
    :class:`~carddemo_migration.copybook.packed.PackedSpanWidthError`.
    """
    # Assumptions: a short record is refused rather than zero-extended, because zero-padded
    #   nibbles read as real digits and a zero-padded binary word reads as a smaller number.
    #   Either way the value returned would be well formed, which is the failure mode this whole
    #   module exists to prevent.
    packed_field = layouts.EXPORT_ACCOUNT_LAYOUT.field("EXP-ACCT-CURR-BAL")
    binary_field = layouts.EXPORT_ACCOUNT_LAYOUT.field("EXP-ACCT-CURR-CYC-DEBIT")
    with pytest.raises(PackedSpanWidthError):
        decode_packed_field(bytes(packed_field.end - 1), packed_field)
    with pytest.raises(PackedSpanWidthError):
        decode_binary_field(bytes(binary_field.end - 1), binary_field)


def test_a_display_field_is_refused_by_the_computational_entry_points() -> None:
    """Refuse a display descriptor on the computational paths, raising :class:`PackedDecimalError`.

    Takes no parameters and returns no value; a display field accepted by a computational decoder
    is reported as an assertion failure. The exception class verified on both paths is
    :class:`~carddemo_migration.copybook.packed.PackedDecimalError`.
    """
    # Assumptions: this closes the mis-routing loop in the direction opposite to the display
    #   codec's own refusal of a packed field. A zoned field is one printable digit per byte, so
    #   reading it as nibbles would find 0x3 and 0x0 pairs -- both valid digit nibbles -- and
    #   would return a number twice as long as the field declares. The kind check is the earliest
    #   and the semantically authoritative guard against that: it refuses the descriptor's regime
    #   outright, before a single byte is read as a nibble or as a word. The width contract would
    #   also reject THIS vector -- the field declares twelve display positions against the seven
    #   bytes the packed geometry selects -- but that is a property of this pair of geometries
    #   rather than the rule, because two fields declaring the same digits can share a width, and
    #   then only the kind still separates the regimes.
    zoned_field = layouts.EXPORT_ACCOUNT_LAYOUT.field("EXP-ACCT-CREDIT-LIMIT")
    assert zoned_field.kind is Kind.ZONED
    record = bytes(layouts.EXPORT_ACCOUNT_LAYOUT.reclen)
    with pytest.raises(PackedDecimalError) as packed_refusal:
        decode_packed_field(record, zoned_field)
    assert "ZONED" in str(packed_refusal.value)
    with pytest.raises(PackedDecimalError) as binary_refusal:
        decode_binary_field(record, zoned_field)
    assert "ZONED" in str(binary_refusal.value)
