"""Exercise the per-field EBCDIC decoder on the properties that keep a binary image intact.

Purpose
-------
Execute :mod:`carddemo_migration.copybook.ebcdic_codec` rather than merely reading it. The
module's whole reason for existing is that a fixed-length mainframe image must be cut on byte
offsets and transcoded ONE FIELD AT A TIME, and the two ways of getting that wrong -- decoding
a whole record as text, or treating a byte that happens to look like a line terminator as one
-- both produce output that reads as plausible records. The vectors below are chosen so that
each of those mistakes fails a test instead of passing quietly.

Assumptions: the decisive vectors are drawn from the shipped extracts under
``app/data/EBCDIC`` wherever one exists, and are hand-built only where a fault has to be
INTRODUCED, which no reference dataset contains. Using the real images is what makes a passing
test evidence about this corpus rather than about an invented one.

Assumptions: the closing group of cases pins two ADMISSION rules rather than a decode result --
a code page outside the EBCDIC family is refused, and a timestamp span holding a mixture of the
two pad bytes is refused rather than reported absent. Both matter because the alternative to a
refusal is not an error but WRONG DATA: a span decoded through a Unicode codec yields plausible
characters at displaced offsets, and a half-blank half-low-value span reported as absent turns
partial corruption into a NULL that no later check can tell apart from an unwritten field.

Trade-offs: those closing cases assert the REFUSAL and the diagnostic's discretion, not the
diagnostic's wording. A test that pinned whole sentences would fail on an edit that improved the
prose, which is not a behaviour worth freezing; what must not regress is that the message names
the resolved codec or the two byte counts, and that it never echoes the span's own bytes.

Alternatives Considered: asserting those two conditions through ``decode_record`` over a shipped
extract. Rejected because it would exercise the paths only when a corrupt extract happened to be
on disk, so the two conditions would go unasserted on every ordinary run, and a failure would
name a record rather than the rule that rejected it.

Assumptions: the second group of cases covers the properties that break by returning a
plausible value rather than by raising -- a mixed-regime area transcoded as text, an
arbitrary codec accepted, a buffer whose items are wider than a byte reinterpreted as one,
and a dataset that is not a regular file read as an empty one. Each is asserted against the
shipped extracts where one exists, and against an input built here only where a fault has to
be provoked, because the corpus deliberately contains no malformed record to borrow.

Assumptions: no value read from any extract is written into an assertion message or into a
docstring in this file. Several of the fields exercised carry cardholder identity or payment
data, and a test failure is a diagnostic like any other.
"""

from __future__ import annotations

import os
import pathlib
import signal
from decimal import Decimal
from types import FrameType

import pytest

from carddemo_migration.copybook import layouts
from carddemo_migration.copybook.ebcdic_codec import (
    EBCDIC_CODE_PAGE,
    SUPPORTED_CODE_PAGES,
    EbcdicFieldDecodeError,
    EbcdicRecordLengthError,
    decode_export_record,
    decode_field,
    decode_field_characters,
    decode_record,
    decode_timestamp,
    iter_ebcdic_records,
    trim_trailing_blanks,
)
from carddemo_migration.copybook.layouts import (
    EXPORT_BRANCH_LENGTH,
    EXPORT_CUSTOMER_LAYOUT,
    EXPORT_HEADER_LAYOUT,
    EXPORT_PAYLOAD_OFFSET,
    EXPORT_RECORD_TYPES,
    Kind,
    RecordSpec,
    layout,
)
from carddemo_migration.copybook.packed import encode_binary_field, encode_packed_field
from carddemo_migration.copybook.zoned import ZonedDecimalError

# Assumptions: the two byte values named here are the ones that make the per-field rule
#   load-bearing. 0x15 is the EBCDIC newline and 0x25 is the EBCDIC line feed, and in a binary
#   image both occur as ordinary data -- 0x25 is also the character '.' in ASCII terms only
#   after decoding, which is precisely the confusion a line-oriented reader falls into.
_EBCDIC_NEWLINE = 0x15
_EBCDIC_LINE_FEED = 0x25

# Assumptions: 0x0A is the ASCII line feed. It appears inside the shipped export extract as the
#   low-order byte of a legitimate COMP value, which is the measured reason a text-mode open of
#   these datasets is refused rather than merely discouraged.
_ASCII_LINE_FEED = 0x0A

_REPOSITORY_ROOT = pathlib.Path(__file__).resolve().parents[2]
_EBCDIC_DIRECTORY = _REPOSITORY_ROOT / "app" / "data" / "EBCDIC"
_ACCOUNT_EXTRACT = _EBCDIC_DIRECTORY / "AWS.M2.CARDDEMO.ACCTDATA.PS"
_CARD_EXTRACT = _EBCDIC_DIRECTORY / "AWS.M2.CARDDEMO.CARDDATA.PS"
_USER_EXTRACT = _EBCDIC_DIRECTORY / "AWS.M2.CARDDEMO.USRSEC.PS"
# WHY : Assumptions: the export extract is named once. It is the only shipped file that
#   exercises the opaque area, the five branch overlays and all three computational regimes
#   inside one record, which is why every export assertion below reads it rather than a
#   composed image.
_EXPORT_DATASET: pathlib.Path = _EBCDIC_DIRECTORY / "AWS.M2.CARDDEMO.EXPORT.DATA.PS"

# WHY : Assumptions: the field name is spelled once as a constant for the same reason the
#   codec spells it once -- a mistyped key in a mapping lookup raises KeyError with nothing to
#   say which of several lookups failed.
_PAYLOAD_FIELD: str = "EXPORT-RECORD-DATA"

# WHY : Assumptions: the shipped record and byte counts are stated as constants and then
#   asserted, so a change to an extract is reported as a count mismatch here rather than as a
#   quietly smaller test. Both were measured from the file.
_EXPORT_RECLEN: int = 500
_EXPORT_RECORD_COUNT: int = 500

# WHY : Assumptions: the eleven base masters and their layout names are listed explicitly
#   rather than derived from a directory scan. A scan would silently shrink if a file were
#   renamed, and this table is what proves the whole shipped corpus still decodes -- the one
#   assertion in this file that would notice a regression anywhere in the field dispatch.
#   ACCDATA is byte-identical to ACCTDATA and is listed because it ships separately.
_BASE_MASTER_DATASETS: tuple[tuple[str, str], ...] = (
    ("AWS.M2.CARDDEMO.ACCTDATA.PS", "ACCOUNT"),
    ("AWS.M2.CARDDEMO.ACCDATA.PS", "ACCOUNT"),
    ("AWS.M2.CARDDEMO.CARDDATA.PS", "CARD"),
    ("AWS.M2.CARDDEMO.CARDXREF.PS", "XREF"),
    ("AWS.M2.CARDDEMO.CUSTDATA.PS", "CUSTOMER"),
    ("AWS.M2.CARDDEMO.DALYTRAN.PS", "DALYTRAN"),
    ("AWS.M2.CARDDEMO.DISCGRP.PS", "DISGROUP"),
    ("AWS.M2.CARDDEMO.TCATBALF.PS", "TCATBAL"),
    ("AWS.M2.CARDDEMO.TRANCATG.PS", "TRANCAT"),
    ("AWS.M2.CARDDEMO.TRANTYPE.PS", "TRANTYPE"),
    ("AWS.M2.CARDDEMO.USRSEC.PS", "SECUSER"),
)

# WHY : Assumptions: the total is the sum of the eleven files' record counts -- 50, 50, 50,
#   50, 50, 300, 51, 50, 18, 7 and 10 -- and it is stated so that a decode which silently
#   stopped early still fails. Asserting only that no exception was raised would pass for an
#   iteration that produced nothing at all.
_BASE_MASTER_RECORD_TOTAL: int = 686


def _ebcdic(text: str) -> bytes:
    """Encode ASCII text into the code page the extracts are delivered in.

    Purpose
    -------
    Build a hand-made field span in the same character set the datasets use, so a vector
    that has to carry a specific character does not have to be written as hexadecimal.

    Parameters
    ----------
    text : str
        The characters the span should hold.

    Returns
    -------
    bytes
        ``text`` encoded in :data:`EBCDIC_CODE_PAGE`.

    Raises
    ------
    UnicodeEncodeError
        If a character has no representation in the code page.
    """
    return text.encode(EBCDIC_CODE_PAGE)


def test_the_reference_extracts_are_present() -> None:
    """Fail loudly if the reference extracts this module decodes are missing."""
    # WHY : Assumptions: the presence of the inputs is asserted as its own test rather than
    #   guarded with a skip on each test that reads them. A skip would let the whole file
    #   report green on a checkout where the extracts had moved, and the extracts are the
    #   reason the rest of the file is evidence rather than illustration.
    for extract in (_ACCOUNT_EXTRACT, _CARD_EXTRACT, _USER_EXTRACT):
        assert extract.is_file(), f"reference extract {extract} is missing"


def test_a_text_field_decodes_to_its_declared_width_and_characters() -> None:
    """Decode one display field to exactly its declared width, padding included."""
    field = layouts.text("ACCT-ACTIVE-STATUS", 0, 1)
    assert decode_field(_ebcdic("Y"), field) == "Y"

    padded = layouts.text("ACCT-GROUP-ID", 0, 10)
    decoded = decode_field(_ebcdic("ZEROBAL   "), padded)
    assert decoded == "ZEROBAL   "
    assert len(decoded) == padded.length


def test_a_numeric_display_field_decodes_as_characters_not_as_a_number() -> None:
    """Return an unsigned display field's digits as characters, keeping leading zeros."""
    # WHY : Assumptions: a PIC 9(n) key is decoded as CHARACTERS and not parsed, because its
    #   leading zeros are part of the key. Parsing it to an integer would make account
    #   00000000001 and account 1 the same value, and the cross-reference join is on the
    #   eleven-character form.
    field = layouts.uint("ACCT-ID", 0, 11)
    assert decode_field(_ebcdic("00000000001"), field) == "00000000001"


def test_a_computational_field_is_returned_as_untouched_bytes() -> None:
    """Hand a packed field's raw bytes back without passing them through any code page."""
    # WHY : Assumptions: this is the single most important property in the module. A packed
    #   span's bytes are not characters in any code page, so transcoding them replaces the
    #   ones that have no mapping and silently changes the value. The assertion is byte
    #   identity, which is the only form of it that cannot be satisfied by a lucky mapping.
    field = layouts.packed("PA-CREDIT-LIMIT", 0, 9, 2, signed=True)
    span = b"\x00\x00\x00\x19\x40\x0c"
    assert decode_field(span, field) == span
    assert isinstance(decode_field(span, field), bytes)


def test_decode_field_characters_refuses_a_computational_field() -> None:
    """Refuse to hand a packed field to the character path, which would corrupt it."""
    field = layouts.packed("PA-CREDIT-LIMIT", 0, 9, 2, signed=True)
    with pytest.raises(EbcdicFieldDecodeError):
        decode_field_characters(b"\x00\x00\x00\x19\x40\x0c", field)


def test_a_zoned_field_reaches_the_character_path_with_its_overpunch_intact() -> None:
    """Give a signed display field its characters, closing brace and all."""
    # WHY : Assumptions: the zoned regime is transcodable but NOT numeric-by-bytes, because
    #   its sign lives in the overpunched final character. 0xD0 is the EBCDIC closing brace,
    #   which is how a negative zero is written, and it has to survive the decode as a
    #   character for the sibling display codec to read the sign from it.
    field = layouts.signed_zoned("ACCT-CURR-BAL", 0, 10, 2)
    span = _ebcdic("00000019400")[: field.length - 1] + b"\xd0"
    decoded = decode_field_characters(span, field)
    assert len(decoded) == field.length
    assert decoded.endswith("}")


def test_a_record_too_short_to_reach_a_field_is_refused() -> None:
    """Refuse a record that does not carry the field's declared span in full."""
    # WHY : Assumptions: only the SHORT case is a refusal. A record longer than one field is
    #   the normal case -- every field but the last one is followed by the rest of its record
    #   -- so a longer record is sliced rather than refused, and the test below asserts that
    #   slicing instead of pretending a length ceiling exists.
    field = layouts.text("ACCT-GROUP-ID", 0, 10)
    with pytest.raises(EbcdicFieldDecodeError):
        decode_field(_ebcdic("SHORT"), field)


def test_a_field_is_sliced_from_its_declared_offset_within_a_longer_record() -> None:
    """Read a field from its own offset rather than from the front of the record."""
    # WHY : Assumptions: the vector places DIFFERENT characters before and after the field's
    #   span, so an implementation ignoring the start offset, or one reading to the end of the
    #   record, both produce a different answer. A vector padded with blanks either side would
    #   let both mistakes pass.
    field = layouts.text("ACCT-GROUP-ID", 10, 10)
    record = _ebcdic("BEFOREBEFOZEROBAL   AFTERAFTER")
    assert decode_field(record, field) == "ZEROBAL   "


def test_an_empty_span_is_refused_rather_than_decoded_as_blank() -> None:
    """Refuse an empty span instead of answering an empty string for a field never read."""
    # WHY : Assumptions: an empty span is what an exhausted stream or a truncated record
    #   produces, and answering it with "" would put a blank into a column that has no value
    #   at all -- indistinguishable, downstream, from a legitimately blank field.
    field = layouts.text("ACCT-GROUP-ID", 0, 10)
    with pytest.raises(EbcdicFieldDecodeError):
        decode_field(b"", field)


def test_character_data_is_refused_as_a_record() -> None:
    """Refuse a str, because characters mean somebody already decoded the record."""
    field = layouts.text("ACCT-GROUP-ID", 0, 10)
    with pytest.raises(TypeError):
        decode_field("ZEROBAL   ", field)  # type: ignore[arg-type]


def test_an_unregistered_code_page_is_reported_as_a_decode_failure() -> None:
    """Report an unknown code page as this module's own error, not as a LookupError."""
    field = layouts.text("ACCT-GROUP-ID", 0, 10)
    with pytest.raises(EbcdicFieldDecodeError):
        decode_field(_ebcdic("ZEROBAL   "), field, code_page="cp-does-not-exist")


def test_a_line_terminator_byte_inside_a_field_is_data_and_not_a_boundary() -> None:
    """Decode every declared byte of a field, including ones that look like terminators."""
    # WHY : Assumptions: all three terminator-shaped bytes are placed INSIDE one field's span
    #   and the field is asserted to keep its declared width. A reader that split on any of
    #   them would return a short field, so the width assertion is what makes this test able
    #   to fail; asserting only the characters would pass for a reader that split and re-padded.
    field = layouts.text("CUST-ADDR-LINE-1", 0, 8)
    span = bytes([_EBCDIC_NEWLINE, _EBCDIC_LINE_FEED, _ASCII_LINE_FEED]) + _ebcdic("ABCDE")
    decoded = decode_field(span, field)
    assert len(decoded) == field.length
    assert decoded.endswith("ABCDE")


def test_a_low_value_byte_inside_a_field_survives_the_decode() -> None:
    """Keep a low-value byte as a character rather than truncating the field at it."""
    # WHY : Assumptions: the shipped export extract carries 4153 low values, so this is a
    #   corpus property and not a hypothetical. A decoder treating a low value as a
    #   terminator would silently shorten every field that contains one.
    field = layouts.text("CUST-ADDR-LINE-2", 0, 4)
    decoded = decode_field(b"\x00" + _ebcdic("ABC"), field)
    assert len(decoded) == field.length
    assert decoded == "\x00ABC"


def test_trailing_blanks_are_trimmed_only_when_asked() -> None:
    """Return the full width by default and the trimmed form only through the helper."""
    field = layouts.text("CUST-FIRST-NAME", 0, 10)
    decoded = decode_field(_ebcdic("AARON     "), field)
    assert decoded == "AARON     "
    assert trim_trailing_blanks(decoded) == "AARON"


def test_a_blank_timestamp_is_reported_as_absent_rather_than_refused() -> None:
    """Answer an all-blank timestamp with None, which the baseline considers valid."""
    field = layouts.normalized_timestamp("DALYTRAN-PROC-TS", 0, 26)
    assert decode_timestamp(_ebcdic(" " * 26), field) is None
    written = "2022-07-18 09:12:33.123456"
    assert decode_timestamp(_ebcdic(written), field) == written


def test_a_whole_record_decodes_field_by_field_into_exact_values() -> None:
    """Decode a real account record and return characters and exact decimals per regime."""
    layout = layouts.layout("ACCOUNT")
    image = next(iter(iter_ebcdic_records(_ACCOUNT_EXTRACT, layout)))
    fields = decode_record(image, layout)

    assert fields["ACCT-ID"] == "00000000001"
    assert fields["ACCT-ACTIVE-STATUS"] == "Y"
    # WHY : Assumptions: the balance is asserted as a Decimal AND at its exponent, because a
    #   value that merely compares equal to 194.00 but carries a different scale re-encodes to
    #   different bytes, and the parity comparison that decides a load is a byte comparison.
    assert fields["ACCT-CURR-BAL"] == Decimal("194.00")
    assert isinstance(fields["ACCT-CURR-BAL"], Decimal)
    assert fields["ACCT-CURR-BAL"].as_tuple().exponent == -2
    assert fields["ACCT-CREDIT-LIMIT"] == Decimal("2020.00")
    assert fields["ACCT-OPEN-DATE"] == "2014-11-20"


def test_every_declared_field_of_a_record_is_returned() -> None:
    """Return one entry per declared field, in declaration order, and nothing else."""
    layout = layouts.layout("CARD")
    image = next(iter(iter_ebcdic_records(_CARD_EXTRACT, layout)))
    fields = decode_record(image, layout)
    assert tuple(fields) == tuple(field.name for field in layout.fields)


def test_a_record_of_the_wrong_length_is_refused() -> None:
    """Refuse a record image that is not the declared record length."""
    layout = layouts.layout("SECUSER")
    with pytest.raises((EbcdicRecordLengthError, EbcdicFieldDecodeError)):
        decode_record(b"\x40" * (layout.reclen - 1), layout)


def test_records_are_cut_on_the_declared_length_alone() -> None:
    """Slice an in-memory image into whole records by width, ignoring any byte's shape."""
    # WHY : Assumptions: the image below is built so that a terminator-splitting reader gets a
    #   DIFFERENT record count, not merely different content: each record carries an embedded
    #   EBCDIC newline, so splitting on it would yield six pieces where the width rule yields
    #   three. Asserting the count is what makes the two behaviours distinguishable.
    layout = layouts.text("PIECE", 0, 4)
    reclen = 4
    body = bytes([_EBCDIC_NEWLINE]) + _ebcdic("ABC")
    spec = layouts.RecordSpec("TERMINATOR-PROBE", reclen, reclen, 0, (layout,))
    records = list(iter_ebcdic_records(body * 3, spec))
    assert len(records) == 3
    assert all(record == body for record in records)


def test_an_image_that_does_not_divide_into_whole_records_is_refused() -> None:
    """Refuse a truncated image before any record reaches a caller."""
    layout = layouts.layout("XREF")
    truncated = b"\x40" * (layout.reclen * 2 + 1)
    with pytest.raises(EbcdicRecordLengthError):
        list(iter_ebcdic_records(truncated, layout))


def test_an_empty_image_yields_no_records_and_is_not_an_error() -> None:
    """Answer an empty image with no records, the only legitimate way to hold none."""
    layout = layouts.layout("XREF")
    assert list(iter_ebcdic_records(b"", layout)) == []


def test_a_truncated_dataset_on_disk_is_refused_before_the_first_record(
    tmp_path: pathlib.Path,
) -> None:
    """Refuse a truncated file up front rather than part-way through a load."""
    # WHY : Assumptions: the file is written from the REAL extract's own bytes and then cut,
    #   so the only difference from a loadable dataset is the truncation itself. Refusing
    #   before the first record is what keeps a partial load from starting at all -- a load
    #   that failed on its last record would leave the target half-populated.
    layout = layouts.layout("XREF")
    source = _EBCDIC_DIRECTORY / "AWS.M2.CARDDEMO.CARDXREF.PS"
    truncated = tmp_path / "truncated.PS"
    truncated.write_bytes(source.read_bytes()[: layout.reclen * 3 + 7])
    with pytest.raises(EbcdicRecordLengthError):
        next(iter(iter_ebcdic_records(truncated, layout)))


def test_a_dataset_path_is_read_in_binary_and_never_reopened_as_text() -> None:
    """Read a whole extract by width and account for every byte of it."""
    # WHY : Assumptions: the record count is checked against the file size by DIVISION rather
    #   than against a literal, so the test states the invariant instead of a fact about one
    #   shipped file that a re-export would change.
    layout = layouts.layout("SECUSER")
    size = _USER_EXTRACT.stat().st_size
    records = list(iter_ebcdic_records(_USER_EXTRACT, layout))
    assert len(records) == size // layout.reclen
    assert all(len(record) == layout.reclen for record in records)
    assert b"".join(records) == _USER_EXTRACT.read_bytes()


def test_a_str_source_is_refused_as_ambiguous() -> None:
    """Refuse a str source, which is ambiguous between a location and decoded data."""
    layout = layouts.layout("XREF")
    with pytest.raises(TypeError):
        list(iter_ebcdic_records("app/data/EBCDIC", layout))  # type: ignore[arg-type]


# Assumptions: a 26-character span is used throughout because that is the width the
#   corpus declares for a timestamp -- 'YYYY-MM-DD HH:MM:SS.mmmmmm' -- so the mixed-pad
#   case below is expressed at the width it actually occurs at rather than a convenient
#   short one.
_STAMP = layouts.text("STAMP", 0, 26)

# The two pad bytes an unwritten fixed-width span can legitimately hold: the EBCDIC
# blank and the low value. Declared as bytes rather than as characters because the
# distinction only exists before decoding.
_BLANK_BYTE = b"\x40"
_LOW_VALUE_BYTE = b"\x00"

_WRITTEN_STAMP = "2022-07-18 12:00:00.000000"


def _stamp_record(text_value: str) -> bytes:
    """Encode a written timestamp into the code page the corpus is stored in.

    :param text_value: the 26-character timestamp to encode; its length is not checked
        here because a wrong length is exactly what the field descriptor should reject.
    :returns: the encoded span, ready to hand to a decode entry point.
    """
    return text_value.encode(EBCDIC_CODE_PAGE)


def test_a_written_stamp_decodes_through_the_default_code_page() -> None:
    """Confirm the positive path, so the refusals below are not vacuously satisfied.

    :returns: nothing; the assertion is the test.
    """
    # WHY : Assumptions: this case is not decoration. Every other case here asserts that
    #   something is refused, and a module that refused EVERYTHING would satisfy all of
    #   them; one passing decode is what makes the refusals meaningful.
    assert decode_timestamp(_stamp_record(_WRITTEN_STAMP), _STAMP) == _WRITTEN_STAMP
    assert decode_field(_stamp_record(_WRITTEN_STAMP), _STAMP) == _WRITTEN_STAMP


@pytest.mark.parametrize("pad_byte", [_BLANK_BYTE, _LOW_VALUE_BYTE])
def test_a_uniformly_padded_stamp_is_reported_absent(pad_byte: bytes) -> None:
    """Accept the two documented unwritten forms as an absence rather than a failure.

    :param pad_byte: the single pad byte the whole span is filled with.
    :returns: nothing; the assertion is the test.
    """
    # WHY : Assumptions: both forms occur in the corpus for the same reason -- a record
    #   written before its timestamp was set -- and neither is corruption, so both answer
    #   None. Parametrising rather than writing two cases keeps them provably symmetric.
    assert decode_timestamp(pad_byte * _STAMP.length, _STAMP) is None


def test_a_stamp_mixing_the_two_pad_bytes_is_refused() -> None:
    """Refuse a span that is part blank and part low value instead of calling it absent.

    :returns: nothing; the assertion is the test.
    """
    # Trade-offs: the span below is thirteen blanks followed by thirteen low values, which is
    #   neither documented pad form. An earlier revision stripped both pad bytes together, so ANY
    #   mixture collapsed to an empty result and was reported as an absent timestamp.
    #   That converted partial corruption into a NULL indistinguishable from an unwritten
    #   field, which is the one outcome a load cannot detect afterwards. Refusing costs a
    #   failed load on a corrupt extract and buys the ability to know it was corrupt.
    mixed = _BLANK_BYTE * 13 + _LOW_VALUE_BYTE * 13

    with pytest.raises(EbcdicFieldDecodeError) as refusal:
        decode_timestamp(mixed, _STAMP)

    message = str(refusal.value)
    assert "13" in message, "the diagnostic states how many positions each pad byte held"
    assert _STAMP.name in message, "the diagnostic names the field that failed"


@pytest.mark.parametrize("code_page", ["utf-8", "ascii", "latin-1", "utf-16"])
def test_a_code_page_outside_the_ebcdic_family_is_refused(code_page: str) -> None:
    """Refuse any code page that is not a member of the admitted EBCDIC family.

    :param code_page: a registered codec that is not an EBCDIC page.
    :returns: nothing; the assertion is the test.
    """
    # WHY : Assumptions: each of these four resolves successfully in the codec registry,
    #   so registry resolution alone cannot reject them -- which is precisely why
    #   admission is a SECOND check. utf-16 is included deliberately: it is the case
    #   where one character does not occupy one byte, so accepting it would displace
    #   every field after this one rather than merely producing wrong characters.
    with pytest.raises(EbcdicFieldDecodeError) as refusal:
        decode_field(_stamp_record(_WRITTEN_STAMP), _STAMP, code_page=code_page)

    message = str(refusal.value)
    assert "EBCDIC" in message, "the diagnostic says which family the page had to belong to"
    assert _WRITTEN_STAMP not in message, "a field-safe diagnostic never echoes the span"


def test_an_alias_of_the_default_page_is_admitted_by_its_canonical_name() -> None:
    """Admit an alias of the default page, because admission is by canonical name.

    :returns: nothing; the assertion is the test.
    """
    # WHY : Alternatives Considered: comparing the caller's spelling against a hand-written
    #   list of admitted names. Rejected because the registry has many aliases for one
    #   page -- 'EBCDIC-CP-BE' and 'IBM037' both resolve to cp037 -- so a spelling list
    #   would refuse a legitimate caller for using a different name for the same page.
    #   Normalising through the resolved codec's canonical name is what makes the
    #   membership test about the page rather than about the spelling.
    assert decode_field(_stamp_record(_WRITTEN_STAMP), _STAMP, code_page="EBCDIC-CP-BE") == (
        _WRITTEN_STAMP
    )


def _first_export_record() -> bytes:
    """Return the first record of the shipped export extract.

    Purpose
    -------
    Give the export assertions one vector without each of them re-reading a 250 000-byte
    file, and skip the whole group cleanly when the extract is not present.
        Exactly the first 500 bytes of the export extract.

    Raises
    ------
    None. A missing extract is reported through :func:`pytest.skip`, not as an error,
    because the extract is reference input this package never creates.
    """
    if not _EXPORT_DATASET.is_file():
        pytest.skip(f"reference extract {_EXPORT_DATASET.name} is not present")
    with _EXPORT_DATASET.open("rb") as stream:
        return stream.read(_EXPORT_RECLEN)


def _zeroed_branch_image(spec: RecordSpec) -> bytearray:
    """Build one branch-sized image whose every field holds a decodable zero.

    Purpose
    -------
    Provide a valid canvas on which exactly one field can be corrupted, so a refusal is
    attributable to that field alone.

    Assumptions: the display and text areas are filled with the cp037 blank and the
    computational areas with an encoded zero, rather than the whole image being filled with
    one byte. Filling uniformly with the blank was tried first and is wrong: the blank's
    byte value read as a four-byte big-endian integer is a ten-digit number, so a nine-digit
    ``COMP`` identifier refuses it and the refusal comes from the wrong field.

    Parameters
    ----------
    spec : RecordSpec
        The branch layout whose declared length and fields the image must satisfy.

    Returns
    -------
    bytearray
        A mutable image of exactly ``spec.reclen`` bytes that decodes without error.

    Raises
    ------
    None.
    """
    image = bytearray(EBCDIC_BLANK * spec.reclen)
    for field in spec.fields:
        if field.kind is Kind.PACKED:
            image[field.start : field.end] = encode_packed_field(0, field)
        elif field.kind is Kind.BINARY:
            image[field.start : field.end] = encode_binary_field(0, field)
        elif field.kind is Kind.ZONED:
            image[field.start : field.end] = ("0" * field.length).encode(EBCDIC_CODE_PAGE)
        elif field.kind is Kind.UINT:
            image[field.start : field.end] = ("0" * field.length).encode(EBCDIC_CODE_PAGE)
    return image


# WHY : Assumptions: the cp037 blank is byte 0x40 and it is named rather than written as a
#   literal at each fill site, because 0x40 is also the ASCII commercial-at and a reader
#   meeting the bare number would have no way to tell which of the two was meant.
EBCDIC_BLANK: bytes = b"\x40"


def test_export_payload_is_declared_opaque_and_sensitive() -> None:
    """Confirm the export payload area is a raw, sensitive container and not text.

    Purpose
    -------
    Guard the declaration itself. The copybook writes ``PIC X(460)`` for this area, so the
    literal reading is character data, and that reading routed a primary account number, a
    card verification value, a national identifier, three packed amounts and seven binary
    identifiers through a code page. Asserting the regime here means a future edit that
    restores the literal reading fails a test rather than shipping.
    """
    field = EXPORT_HEADER_LAYOUT.field(_PAYLOAD_FIELD)

    assert field.kind is Kind.OPAQUE
    assert field.sensitive is True
    assert (field.start, field.length) == (EXPORT_PAYLOAD_OFFSET, EXPORT_BRANCH_LENGTH)


def test_record_decode_returns_the_export_payload_untouched() -> None:
    """Confirm the generic record decoder hands the payload back as its own bytes.

    Purpose
    -------
    Prove the property that the regime exists to provide, on the shipped bytes: the area is
    returned, byte for byte, with no code page applied. A character decode of it succeeds
    and returns a 460-character string, so an assertion on the TYPE is what distinguishes
    the two outcomes -- a length assertion alone would pass for either.
    """
    record = _first_export_record()

    decoded = decode_record(record, EXPORT_HEADER_LAYOUT)
    payload = decoded[_PAYLOAD_FIELD]

    assert isinstance(payload, bytes)
    assert payload == record[EXPORT_PAYLOAD_OFFSET : EXPORT_PAYLOAD_OFFSET + EXPORT_BRANCH_LENGTH]


def test_character_entry_point_refuses_the_export_payload() -> None:
    """Confirm the character boundary refuses the opaque area outright.

    Purpose
    -------
    Close the second route to the same corruption. Classifying the regime keeps the record
    decoder away from the area, and this assertion keeps a caller that reaches for the
    character entry point directly away from it too.
    """
    record = _first_export_record()
    field = EXPORT_HEADER_LAYOUT.field(_PAYLOAD_FIELD)

    with pytest.raises(EbcdicFieldDecodeError):
        decode_field_characters(record, field)


def test_export_two_step_decode_reads_the_branch_the_discriminator_selects() -> None:
    """Confirm the envelope routes to a branch and the branch's own regimes decode.

    Purpose
    -------
    Prove the replacement path works and not merely that the wrong one is blocked. The
    first shipped record is a customer branch, so its identifier is a four-byte binary and
    its credit score a three-digit packed field: both must arrive as exact decimals, which
    is what shows the payload was decoded against the branch layout rather than as text.
    """
    envelope, branch = decode_export_record(_first_export_record())

    assert envelope["EXPORT-REC-TYPE"] == "C"
    assert isinstance(envelope[_PAYLOAD_FIELD], bytes)
    assert isinstance(branch["EXP-CUST-ID"], Decimal)
    assert isinstance(branch["EXP-CUST-FICO-CREDIT-SCORE"], Decimal)


def test_every_shipped_export_record_decodes_through_its_branch() -> None:
    """Confirm all five branch types occur in the extract and all of them decode.

    Purpose
    -------
    Exercise the two-step path over the whole shipped file rather than one record, because
    a single record proves only one of the five overlays. The count is asserted as well as
    the type set: an iteration that produced nothing would satisfy a set assertion built
    from what it happened to see.
    """
    if not _EXPORT_DATASET.is_file():
        pytest.skip(f"reference extract {_EXPORT_DATASET.name} is not present")

    counts: dict[str, int] = {}
    for record in iter_ebcdic_records(_EXPORT_DATASET, EXPORT_HEADER_LAYOUT):
        envelope, branch = decode_export_record(record)
        record_type = envelope["EXPORT-REC-TYPE"]
        assert isinstance(record_type, str)
        assert len(branch) == len(EXPORT_RECORD_TYPES[record_type].fields)
        counts[record_type] = counts.get(record_type, 0) + 1

    assert sum(counts.values()) == _EXPORT_RECORD_COUNT
    assert set(counts) == set(EXPORT_RECORD_TYPES)


@pytest.mark.parametrize("code_page", ["latin-1", "utf-8", "ascii", "utf-16-le", "idna"])
def test_a_non_ebcdic_code_page_is_refused(code_page: str) -> None:
    """Confirm a codec outside the supported set cannot be selected.

    Purpose
    -------
    Guard the failure mode that does not announce itself. ``latin-1`` decodes every byte of
    this corpus, consumes the whole span and returns the field's full declared width, so the
    only thing wrong with the result is its content -- and nothing downstream can tell.
    ``ascii`` and ``utf-8`` are included because they would raise on this corpus by luck
    rather than by rule, and a guard that relied on that luck would not hold for the next
    extract.

    Parameters
    ----------
    code_page : str
        The unsupported codec name under test.
    """
    record = _first_export_record()
    field = EXPORT_HEADER_LAYOUT.field("EXPORT-BRANCH-ID")

    with pytest.raises(EbcdicFieldDecodeError):
        decode_field(record, field, code_page=code_page)


def test_the_corpus_page_and_a_sibling_ebcdic_page_are_both_supported() -> None:
    """Confirm the allow-list is a closed set that still permits the intended pages.

    Purpose
    -------
    Assert both halves of the restriction. A set that refused everything would pass the
    refusal test above and break the capability the optional distribution is pinned for,
    which is that an extract in a sibling page decodes through the identical per-field path
    by configuration alone.
    """
    record = _first_export_record()
    field = EXPORT_HEADER_LAYOUT.field("EXPORT-BRANCH-ID")

    assert EBCDIC_CODE_PAGE in SUPPORTED_CODE_PAGES
    assert "latin-1" not in SUPPORTED_CODE_PAGES
    assert isinstance(decode_field(record, field, code_page="cp500"), str)


def test_a_wider_memoryview_is_refused_at_every_conversion_site() -> None:
    """Confirm a two-byte-item view reaches neither field nor record decoder.

    Purpose
    -------
    Enforce the contract both docstrings already stated. Converting such a view succeeds
    and flattens it to the host's byte order, so a caller that assembled its buffer from
    integers would read one set of amounts on one machine and a different set on another,
    with nothing raising on either.
    """
    record = _first_export_record()
    field = EXPORT_HEADER_LAYOUT.field("EXPORT-BRANCH-ID")
    wide = memoryview(record).cast("H")

    with pytest.raises(TypeError):
        decode_field(wide, field)
    with pytest.raises(TypeError):
        decode_record(wide, EXPORT_HEADER_LAYOUT)


def test_single_byte_buffers_are_still_accepted() -> None:
    """Confirm the new guard did not narrow the three buffer shapes a caller may hold.

    Purpose
    -------
    A guard on view shape is easy to write too tightly. This asserts the accepted set is
    still bytes, bytearray and a one-byte-item view, so the refusal above is attributable
    to the item width and not to the view type.
    """
    record = _first_export_record()
    field = EXPORT_HEADER_LAYOUT.field("EXPORT-BRANCH-ID")

    assert isinstance(decode_field(record, field), str)
    assert isinstance(decode_field(bytearray(record), field), str)
    assert isinstance(decode_field(memoryview(record), field), str)


def test_an_unsigned_display_field_holding_letters_is_refused() -> None:
    """Confirm a ``PIC 9(n)`` field is checked against its picture clause.

    Purpose
    -------
    An unsigned display field used to be decoded through the same branch as plain text and
    returned unexamined, so a nine-position identifier holding letters came back as those
    letters while the Java parity anchor refused the same span. The two implementations
    disagreed in the direction that keeps bad data moving.
    """
    field = EXPORT_CUSTOMER_LAYOUT.field("EXP-CUST-SSN")
    image = _zeroed_branch_image(EXPORT_CUSTOMER_LAYOUT)
    image[field.start : field.end] = ("A" * field.length).encode(EBCDIC_CODE_PAGE)

    with pytest.raises(ZonedDecimalError):
        decode_field(bytes(image), field)


def test_a_valid_unsigned_display_field_returns_its_digits_unchanged() -> None:
    """Confirm the added check did not change what a valid field returns.

    Purpose
    -------
    The regime has ONE representation across the three entry points, and it is the digit
    string rather than a number, because a leading zero is significant in every identifier
    in this corpus and an integer would discard it. Asserting the field and record decoders
    agree is what keeps the two from drifting into different representations.
    """
    field = EXPORT_CUSTOMER_LAYOUT.field("EXP-CUST-SSN")
    digits = "012345678"
    image = _zeroed_branch_image(EXPORT_CUSTOMER_LAYOUT)
    image[field.start : field.end] = digits.encode(EBCDIC_CODE_PAGE)
    frozen = bytes(image)

    assert decode_field(frozen, field) == digits
    assert decode_record(frozen, EXPORT_CUSTOMER_LAYOUT)["EXP-CUST-SSN"] == digits


def test_a_named_pipe_is_refused_instead_of_blocking(tmp_path: pathlib.Path) -> None:
    """Confirm a first-in-first-out file is refused promptly rather than waited on.

    Purpose
    -------
    Opening a pipe for reading normally waits for a writer with no bound, so a caller that
    passed one by mistake received no error at all -- the process simply stopped. The
    descriptor is now opened non-blocking and refused on its kind.

    Assumptions: an alarm bounds the call so that a REGRESSION fails this test instead of
    hanging the whole run. A test that can hang the suite is worse than the defect it
    guards, because a hung run reports nothing at all.

    Parameters
    ----------
    tmp_path : pathlib.Path
        The per-test temporary directory pytest provides, used so nothing is created inside
        the repository tree.
    """
    if not hasattr(os, "mkfifo"):
        pytest.skip("this platform has no first-in-first-out file to test against")

    fifo = tmp_path / "pipe.ps"
    os.mkfifo(fifo)
    spec = layout("XREF")

    def _bail(_signum: int, _frame: FrameType | None) -> None:
        """Convert the alarm into an exception so a regression fails rather than hangs."""
        raise TimeoutError("iter_ebcdic_records blocked on a named pipe")

    previous = signal.signal(signal.SIGALRM, _bail)
    signal.alarm(5)
    try:
        with pytest.raises(EbcdicRecordLengthError):
            list(iter_ebcdic_records(fifo, spec))
    finally:
        signal.alarm(0)
        signal.signal(signal.SIGALRM, previous)


def test_a_character_device_is_refused_rather_than_read_as_empty() -> None:
    """Confirm an endless device is refused instead of yielding without bound.

    Purpose
    -------
    A device reports zero bytes, and zero divides by every record length with no remainder,
    so the geometry check accepted it as a well-formed EMPTY dataset and the iteration then
    produced records for as long as it was asked to -- measured at 200 001 before the probe
    gave up. The kind check now fires before the geometry check for exactly this reason.
    """
    device = pathlib.Path("/dev/zero")
    if not device.exists():
        pytest.skip("this platform has no /dev/zero to test against")

    with pytest.raises(EbcdicRecordLengthError):
        list(iter_ebcdic_records(device, layout("XREF")))


def test_a_regular_file_and_a_symlink_to_one_are_both_read(tmp_path: pathlib.Path) -> None:
    """Confirm the kind check did not exclude a legitimate dataset or a link to one.

    Purpose
    -------
    The refusal above is only correct if it is narrow. Resolving a symbolic link to a
    regular file is ordinary operational practice -- a staged extract is frequently a link
    -- and the descriptor, not the link, is what the check inspects.

    Parameters
    ----------
    tmp_path : pathlib.Path
        The per-test temporary directory pytest provides.
    """
    spec = layout("XREF")
    dataset = tmp_path / "xref.ps"
    dataset.write_bytes(b"A" * (spec.reclen * 2))
    link = tmp_path / "xref-link.ps"
    link.symlink_to(dataset)

    assert list(iter_ebcdic_records(dataset, spec)) == [b"A" * spec.reclen] * 2
    assert list(iter_ebcdic_records(link, spec)) == [b"A" * spec.reclen] * 2


def test_a_truncated_dataset_is_still_refused(tmp_path: pathlib.Path) -> None:
    """Confirm the original geometry refusal survived the rewrite of the path reader.

    Purpose
    -------
    The size is now taken from the open descriptor rather than from the path, and this
    asserts the check it feeds still fires. A rewrite that moved the source of a value and
    lost the validation using it would pass every other test in this file.

    Parameters
    ----------
    tmp_path : pathlib.Path
        The per-test temporary directory pytest provides.
    """
    spec = layout("XREF")
    dataset = tmp_path / "short.ps"
    dataset.write_bytes(b"A" * (spec.reclen - 13))

    with pytest.raises(EbcdicRecordLengthError):
        list(iter_ebcdic_records(dataset, spec))


def test_every_shipped_base_master_record_still_decodes() -> None:
    """Decode all eleven shipped base-master extracts, field by field, end to end.

    Purpose
    -------
    This is the regression assertion for the whole field dispatch. Each of the five
    interpreted regimes occurs across these eleven files, so a change that broke any one of
    them -- the code-page allow-list, the one-character-per-byte requirement, the unsigned
    display check, the buffer guard or the record iteration -- fails here rather than in
    production. The record total is asserted so that an iteration which silently produced
    nothing cannot pass.
    """
    if not _EBCDIC_DIRECTORY.is_dir():
        pytest.skip(f"reference extract directory {_EBCDIC_DIRECTORY} is not present")

    decoded_records = 0
    for file_name, layout_name in _BASE_MASTER_DATASETS:
        dataset = _EBCDIC_DIRECTORY / file_name
        if not dataset.is_file():
            pytest.skip(f"reference extract {file_name} is not present")
        spec = layout(layout_name)
        for record in iter_ebcdic_records(dataset, spec):
            values = decode_record(record, spec)
            assert len(values) == len(spec.fields)
            decoded_records += 1

    assert decoded_records == _BASE_MASTER_RECORD_TOTAL
