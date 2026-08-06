"""Exercise the packed-decimal and binary codecs on the vectors that decide money parity.

Purpose
-------
Execute :mod:`carddemo_migration.copybook.packed` rather than merely reading it. The module
carries the two ``USAGE COMPUTATIONAL`` regimes every monetary field in the authorization
segments and the export record is stored in, and a defect in either produces a plausible
number rather than an error -- which is exactly the failure mode a static read cannot catch.
Each test below pins one property the reference compiler's own encoding establishes.

Assumptions: the vectors are written as literal bytes rather than produced by the encoder and
handed back to the decoder. A round-trip through one module's own inverse agrees with itself
whatever it does, so it cannot tell a correct nibble order from a reversed one; a literal
six-byte span whose nibbles read 00000019400C can, because that is the span a COBOL compiler
emits for 194.00 in a PIC S9(09)V99 COMP-3 field and nothing about this package chose it.
"""

from __future__ import annotations

from decimal import Decimal

import pytest

from carddemo_migration.copybook import layouts
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

# Assumptions: the sign nibbles are named here from the reference's three admitted forms --
#   signed positive, signed negative and unsigned -- so a test asserting a refusal names the
#   nibble it is refusing instead of hiding it inside a hexadecimal literal.
_SIGNED_POSITIVE = 0x0C
_SIGNED_NEGATIVE = 0x0D
_UNSIGNED = 0x0F

# Assumptions: 0x0A, 0x0B and 0x0E are the three alternate sign nibbles other compilers emit
#   and this codec deliberately does not admit. They are listed so the refusal is proved for
#   every one of them rather than for whichever one a single test happened to pick.
_ALTERNATE_SIGN_NIBBLES = (0x0A, 0x0B, 0x0E)


def test_packed_width_follows_the_reference_formula() -> None:
    """Derive the byte width from the digit count exactly as the compiler does."""
    # WHY : Assumptions: the widths asserted here are the ones the authorization segments
    #   actually declare, so a change to the formula fails against the real corpus and not
    #   against an invented example. S9(11) COMP-3 occupies 6 bytes at CIPAUSMY line 19,
    #   S9(09)V99 occupies 6 at line 23, S9(05) occupies 3 at CIPAUDTY line 20, S9(09)
    #   occupies 5 at line 21, and S9(10)V99 occupies 7 at line 34.
    assert packed_width(11, 0) == 6
    assert packed_width(9, 2) == 6
    assert packed_width(5, 0) == 3
    assert packed_width(9, 0) == 5
    assert packed_width(10, 2) == 7


def test_binary_width_selects_a_halfword_or_a_fullword() -> None:
    """Select two bytes up to four digits and four bytes up to nine, as USAGE COMP does."""
    # WHY : Assumptions: the two counters at CIPAUSMY lines 27-28 are S9(04) COMP and occupy
    #   two bytes each, which is what makes the halfword boundary a corpus fact rather than a
    #   convention. The fullword case is asserted at both ends of its range.
    assert binary_width(4, 0) == 2
    assert binary_width(1, 0) == 2
    assert binary_width(5, 0) == 4
    assert binary_width(9, 0) == 4


def test_packed_decodes_a_positive_money_span_at_declared_scale() -> None:
    """Decode a signed positive span and keep exactly the declared fractional digits."""
    # WHY : Assumptions: 194.00 is the first account's current balance in the shipped seed
    #   data, so this vector is the one value a maintainer can cross-check against the
    #   extract by eye. The scale is asserted through the exponent because a value that
    #   compares equal to 194.00 but carries a different exponent re-encodes to different
    #   bytes, and the golden comparison that decides parity is a byte comparison.
    decoded = decode_packed(b"\x00\x00\x00\x19\x40\x0c", 9, 2, True)
    assert decoded == Decimal("194.00")
    assert decoded.as_tuple().exponent == -2


def test_packed_decodes_a_negative_money_span() -> None:
    """Read the signed negative nibble as a sign and not as a digit."""
    decoded = decode_packed(b"\x00\x00\x00\x19\x40\x0d", 9, 2, True)
    assert decoded == Decimal("-194.00")
    assert decoded.as_tuple().exponent == -2


def test_packed_decodes_an_unsigned_span() -> None:
    """Accept the unsigned nibble for a field whose picture carries no leading S."""
    assert decode_packed(b"\x12\x34\x5f", 5, 0, False) == Decimal("12345")


def test_packed_rejects_a_signed_declaration_against_unsigned_data() -> None:
    """Refuse a span whose sign nibble contradicts the descriptor that was passed."""
    # WHY : Assumptions: this cross-check is asserted in both directions because a mismatch
    #   means the descriptor and the bytes describe different fields -- and the value a
    #   permissive codec would return in that case is the right width, the right type and
    #   entirely wrong.
    with pytest.raises(PackedDecimalError, match="different fields"):
        decode_packed(b"\x12\x34\x5f", 5, 0, True)
    with pytest.raises(PackedDecimalError, match="different fields"):
        decode_packed(b"\x12\x34\x5c", 5, 0, False)


@pytest.mark.parametrize("nibble", _ALTERNATE_SIGN_NIBBLES)
def test_packed_rejects_an_alternate_sign_nibble(nibble: int) -> None:
    """Refuse the sign nibbles outside the three forms the reference corpus uses."""
    span = bytes([0x12, 0x34, 0x50 | nibble])
    with pytest.raises(PackedDecimalError, match="alternate sign nibble"):
        decode_packed(span, 5, 0, True)


def test_packed_names_a_digit_in_the_sign_position_as_a_regime_error() -> None:
    """Report a digit in the sign position as a zoned-or-misaligned field, not a bad sign."""
    # WHY : Assumptions: the message is asserted, not just the exception type, because the
    #   two faults this case covers -- the field is zoned display rather than packed, or the
    #   read is off by one nibble -- have entirely different remedies, and a generic
    #   "invalid sign" would send a maintainer looking for neither.
    with pytest.raises(PackedDecimalError, match="zoned display rather than packed"):
        decode_packed(b"\x12\x34\x56", 5, 0, True)


def test_packed_rejects_a_non_digit_nibble_in_a_digit_position() -> None:
    """Refuse a hexadecimal nibble above nine anywhere a digit is declared."""
    with pytest.raises(PackedDecimalError):
        decode_packed(b"\x1a\x34\x5c", 5, 0, True)


def test_packed_rejects_a_non_zero_leading_pad_nibble() -> None:
    """Refuse a value written into the pad nibble the geometry leaves free."""
    # WHY : Assumptions: an even digit count leaves one unused high nibble, and the reference
    #   writes zero into it. A non-zero there means the span carries one more digit than the
    #   descriptor declares, so the value would silently decode a factor of ten small.
    with pytest.raises(PackedDecimalError):
        decode_packed(b"\x91\x23\x45\x6c", 6, 0, True)


def test_packed_rejects_a_span_of_the_wrong_width() -> None:
    """Refuse a span that is not the width the digit counts imply, both short and long."""
    with pytest.raises(PackedSpanWidthError):
        decode_packed(b"\x12\x3c", 5, 0, True)
    with pytest.raises(PackedSpanWidthError):
        decode_packed(b"\x00\x12\x34\x5c", 5, 0, True)


def test_packed_rejects_an_empty_span() -> None:
    """Refuse an empty span rather than answering zero for a field that was never read."""
    # WHY : Assumptions: empty input is called out separately from the width cases because a
    #   zero-length read is what a truncated dataset or an exhausted stream produces, and
    #   answering it with zero would post a missing balance as a settled one.
    with pytest.raises(PackedSpanWidthError):
        decode_packed(b"", 5, 0, True)


def test_packed_negative_zero_decodes_as_unsigned_zero() -> None:
    """Normalise a negatively-signed all-zero span to plain zero, as the Java codec does."""
    # WHY : Assumptions: this is asserted through the SIGN FLAG and not only through equality,
    #   because Decimal('-0.00') == Decimal('0.00') is true in Python, so an equality check
    #   alone would pass for the very value this normalisation exists to prevent. The
    #   divergence is registered as D-SIGNED-ZERO-PACKED: BigDecimal cannot carry a negative
    #   zero, so without this the two languages would disagree on one record and a re-encode
    #   would emit the negative nibble where the reference emits the positive one.
    decoded = decode_packed(b"\x00\x00\x00\x00\x00\x0d", 9, 2, True)
    assert decoded == Decimal("0.00")
    assert decoded.as_tuple().sign == 0
    assert encode_packed(decoded, 9, 2, True)[-1] & 0x0F == _SIGNED_POSITIVE


def test_packed_encode_writes_the_sign_nibble_the_contract_selects() -> None:
    """Write the signed nibbles for a signed field and the unsigned nibble otherwise."""
    assert encode_packed(Decimal("194.00"), 9, 2, True) == b"\x00\x00\x00\x19\x40\x0c"
    assert encode_packed(Decimal("-194.00"), 9, 2, True)[-1] & 0x0F == _SIGNED_NEGATIVE
    assert encode_packed(Decimal("12345"), 5, 0, False)[-1] & 0x0F == _UNSIGNED


def test_packed_round_trips_every_admitted_sign_form() -> None:
    """Re-encode each decoded vector to the exact bytes it was decoded from."""
    for span, signed in (
        (b"\x00\x00\x00\x19\x40\x0c", True),
        (b"\x00\x00\x00\x19\x40\x0d", True),
        (b"\x00\x00\x00\x19\x40\x0f", False),
    ):
        assert encode_packed(decode_packed(span, 9, 2, signed), 9, 2, signed) == span


def test_binary_decodes_big_endian_and_not_little_endian() -> None:
    """Read a COMP span most significant byte first."""
    # WHY : Assumptions: 0x0100 is the decisive vector for byte order, because it decodes to
    #   256 big-endian and to 1 little-endian -- two values a test using a palindromic span
    #   such as 0x0101 could not tell apart. Mainframe COMP is big-endian, so 256 is correct.
    assert decode_binary(b"\x01\x00", 4, 0, True) == Decimal("256")
    assert decode_binary(b"\x00\x01", 4, 0, True) == Decimal("1")


def test_binary_decodes_a_negative_value_in_twos_complement() -> None:
    """Read a signed COMP span as two's complement rather than sign-and-magnitude."""
    # WHY : Assumptions: 0xFFFF is the decisive vector for the representation, because it
    #   decodes to -1 in two's complement and to -32767 in sign-and-magnitude. The lower bound
    #   asserted below is the field's DECLARED bound of -9999 rather than the halfword's
    #   representable -32768, for the reason the capacity test states.
    assert decode_binary(b"\xff\xff", 4, 0, True) == Decimal("-1")
    assert decode_binary(b"\xd8\xf1", 4, 0, True) == Decimal("-9999")


def test_binary_decodes_an_unsigned_span_without_a_sign_bit() -> None:
    """Read the high bit of an unsigned COMP span as a magnitude bit."""
    # WHY : Assumptions: 0x270F is 9999, the largest value a four-digit field declares, and
    #   it is asserted here rather than 0xFFFF because the capacity contract refuses the
    #   latter -- the test below asserts that refusal directly.
    assert decode_binary(b"\x27\x0f", 4, 0, False) == Decimal("9999")


def test_binary_refuses_a_span_holding_more_digits_than_the_field_declares() -> None:
    """Refuse a span whose value needs more digit positions than the picture declares."""
    # WHY : Assumptions: a halfword can REPRESENT -32768 to 65535, but PIC S9(04) COMP
    #   DECLARES only four digit positions, and the two are not the same contract. The codec
    #   enforces the declaration, which is correct: a value outside it means the descriptor and
    #   the data describe different fields, and accepting it would let a five-digit quantity
    #   flow into a column sized for four. Both interpretations are asserted so neither branch
    #   can quietly widen.
    with pytest.raises(PackedDecimalError, match="digit positions"):
        decode_binary(b"\x80\x00", 4, 0, True)
    with pytest.raises(PackedDecimalError, match="digit positions"):
        decode_binary(b"\xff\xff", 4, 0, False)


def test_binary_places_the_implied_point_without_dividing() -> None:
    """Move the implied decimal point left and keep the declared scale exactly."""
    decoded = decode_binary(b"\x00\x00\x4c\x4b", 7, 2, True)
    assert decoded == Decimal("195.31")
    assert decoded.as_tuple().exponent == -2


def test_binary_rejects_a_span_of_the_wrong_width() -> None:
    """Refuse a COMP span that is neither the halfword nor the fullword the digits select."""
    with pytest.raises(PackedSpanWidthError):
        decode_binary(b"\x01", 4, 0, True)
    with pytest.raises(PackedSpanWidthError):
        decode_binary(b"", 4, 0, True)


def test_binary_round_trips_each_boundary_value() -> None:
    """Re-encode the extremes of a signed halfword to the bytes they were decoded from."""
    for span in (b"\xd8\xf1", b"\xff\xff", b"\x00\x00", b"\x27\x0f"):
        assert encode_binary(decode_binary(span, 4, 0, True), 4, 0, True) == span


def test_field_entry_points_slice_the_declared_span_from_a_whole_record() -> None:
    """Read a field's own bytes out of a record image rather than requiring a pre-slice."""
    # WHY : Assumptions: the field entry points are exercised through a REAL registry
    #   descriptor built by layouts.packed/layouts.binary, not through a hand-made stub,
    #   because the offset arithmetic they perform is the whole reason they exist and a stub
    #   would let a wrong offset pass. The record below places each field after a byte of
    #   leading padding, so an implementation ignoring the start offset fails.
    money = layouts.packed("PA-TRANSACTION-AMT", 1, 9, 2, signed=True)
    counter = layouts.binary("PA-APPROVED-AUTH-CNT", 7, 4, 0, signed=True)
    record = b"\x99" + b"\x00\x00\x00\x19\x40\x0c" + b"\x01\x00"
    assert decode_packed_field(record, money) == Decimal("194.00")
    assert decode_binary_field(record, counter) == Decimal("256")


def test_field_entry_points_refuse_a_record_that_does_not_reach_the_field() -> None:
    """Refuse a truncated record instead of decoding a short final span as a whole one."""
    money = layouts.packed("PA-TRANSACTION-AMT", 1, 9, 2, signed=True)
    with pytest.raises(PackedDecimalError):
        decode_packed_field(b"\x99\x00\x00\x00\x01", money)


def test_field_entry_points_refuse_character_data() -> None:
    """Refuse a str, because characters mean the record already went through a decoder."""
    money = layouts.packed("PA-TRANSACTION-AMT", 0, 9, 2, signed=True)
    with pytest.raises(TypeError):
        decode_packed_field("00000019400c", money)  # type: ignore[arg-type]


def test_packed_decodes_the_widest_money_field_exactly() -> None:
    """Carry the full digit count of the widest packed money field with no loss."""
    # WHY : Assumptions: the widest packed money field in the corpus is S9(10)V99 at CIPAUDTY
    #   line 34, twelve digits in seven bytes. It is asserted at its maximum because that is
    #   the value an implementation using a binary float or a 32-bit accumulator would round.
    span = b"\x09\x99\x99\x99\x99\x99\x9c"
    widest = Decimal("9999999999.99")
    assert decode_packed(span, 10, 2, True) == widest
    assert encode_packed(widest, 10, 2, True) == span
