"""Streaming reader for the CardDemo account master, in both shipped encodings.

Purpose
-------
Turn the account master extract into decoded records the Aurora loaders and the
verification passes can consume, one record at a time, from either of the two forms the
baseline ships: the line-oriented ASCII seed and the fixed-length EBCDIC dataset. Both
entry points yield the same decoded shape, so a caller can swap corpora and compare the
two without adapting to a second contract.

This module is the FIRST of the eleven per-record readers and is written to be the
pattern the rest follow. Everything specific to the account master is named through the
one record descriptor it imports; everything general -- padding removal, the
byte-per-character guard, the storage-regime dispatch, the privacy-safe rendering -- is
expressed so that a sibling reader differs from this one only in which descriptor it
names.

What this module reads
---------------------
The record is ``ACCOUNT-RECORD`` as declared in ``app/cpy/CVACT01Y.cpy``, and its byte
geometry is resolved exclusively through ``ACCOUNT_LAYOUT`` in
``carddemo_migration.copybook.layouts``. The two shipped corpora are
``app/data/ASCII/acctdata.txt`` and ``app/data/EBCDIC/AWS.M2.CARDDEMO.ACCTDATA.PS``.
Both are REFERENCE-only inputs: this module opens them read-only and never writes,
re-encodes or normalises either one in place.

Decoded shape
-------------
A decoded record is a ``dict`` keyed by the field name exactly as the copybook spells it,
in declaration order, which is the record's byte order. A character field maps to its
characters at full declared width, untrimmed. An unsigned display field maps to its
characters too, once they are proven to be the digits its picture clause requires, so a
significant leading zero survives. A signed display field maps to an exact
:class:`decimal.Decimal` at the scale its picture clause declares. The trailing pad is
absent; see :data:`DROPPED_FIELD_NAMES`.

That shape is deliberately the one
``carddemo_migration.copybook.ebcdic_codec.decode_record`` publishes, minus the pad,
which is what makes the two encodings comparable rather than merely similar.

The two corpora are not byte-identical, and that is expected
-----------------------------------------------------------
Assumptions: the ASCII and EBCDIC twins of this dataset agree field for field on every
record except ONE, and the difference is a property of the shipped extracts rather than
of this reader. ``data-migration/README.md`` records it in its divergence table: at the
record whose account identifier ends 049, ``ACCT-ADDR-ZIP`` holds different characters in
the two forms. No money field diverges, so a money-total parity check over this dataset
agrees across both forms. That README also fixes the resolution -- the EBCDIC ``.PS``
extracts are the mainframe originals and the ASCII ``.txt`` files are convenience
conversions of them, so EBCDIC is authoritative wherever both exist. Neither file is
edited to reconcile them, because ``app/**`` is REFERENCE-only. A reader who expected the
two to match exactly would otherwise report this reader as broken.

Design decisions (WHY)
----------------------
Assumptions:
    **Every offset, length, storage regime and record length is imported, never
    declared.** This module states no byte position of its own. That is the Python
    analogue of compiling every COBOL program against a single ``cobc -I app/cpy``
    include path, and the reference suite's own guide states the same rule for its COBOL
    unit tests: never duplicate a layout, keep it single-sourced from ``app/cpy/``. The
    consequence of breaking it is specific and undetectable -- a re-declared offset lets
    this reader and a sibling reading the same bytes drift apart, and a record read one
    byte out of alignment still decodes to digits, so nothing raises and no test fails.
Trade-offs:
    **Records are streamed, never materialised.** Both entry points are generators and
    neither builds a list of rows, so memory is constant in the record count rather than
    proportional to it. The accepted cost is that a caller gets a single forward pass and
    must re-open the source to read it twice. What that buys is that the 15 KB committed
    seed and a production extract many orders larger behave identically here, so the
    reader that was proven against the seed is the one that runs against the extract.
Assumptions:
    **Money is exact fixed point and there is no ``float`` in this module.** An
    IEEE-754 binary value cannot represent ten cents exactly, so a total accumulated in
    one drifts from the total the baseline computed -- silently, and by an amount that
    grows with the row count. Every signed display field therefore leaves this module as
    a :class:`decimal.Decimal` at its declared scale, and the five such fields in this
    record are precisely the columns the money-total verification pass aggregates.
Trade-offs:
    **Standard library plus ``carddemo_migration.copybook``, and nothing else.** No
    database driver, no AWS SDK and no character-set package is imported here, so this
    reader imports and runs on a bare checkout with no credential configured and can be
    exercised against known-answer vectors in isolation. Choosing a dataset, opening a
    connection and writing rows belong to a loader. The accepted cost is that a
    convenience such as resolving a dataset location cannot live in this module.
"""

from __future__ import annotations

import pathlib
from collections.abc import Iterable, Iterator
from decimal import Decimal
from typing import Final

# WHY : Assumptions: these imports are the entire reason this module has a dependency on
#   the copybook package, and they are absolute and rooted at the distribution package
#   rather than relative. A relative import is how the single-sourcing guarantee gets
#   broken quietly: a module moved between `readers/` and `loaders/` keeps importing
#   successfully but against a different sibling, and the project's ruff configuration
#   bans relative imports outright for that reason. The record descriptor named below is
#   the ONLY statement of this record's geometry anywhere in the package.
from carddemo_migration.copybook.ebcdic_codec import decode_record, iter_ebcdic_records
from carddemo_migration.copybook.layouts import (
    ACCOUNT_LAYOUT,
    FieldSpec,
    Kind,
    LayoutError,
    RecordLengthError,
    iter_ascii_text_records,
    mask_field,
    mask_record,
)
from carddemo_migration.copybook.zoned import decode_zoned_field

__all__ = [
    "ACCOUNT_LAYOUT",
    "DROPPED_FIELD_NAMES",
    "LOADED_FIELDS",
    "DecodedAccount",
    "decode_ascii_account",
    "decode_ebcdic_account",
    "iter_ascii_accounts",
    "iter_ebcdic_accounts",
    "read_ascii_accounts",
    "read_ebcdic_accounts",
    "render_masked_account_field",
    "record_key",
    "render_masked_account_record",
]

# WHY : Assumptions: the decoded value type is a union of exactly two members because
#   this record declares exactly three storage regimes and two of them -- character and
#   unsigned display -- yield characters while the third yields an exact decimal. The
#   union deliberately excludes `bytes`, which the record-oriented EBCDIC decoder can
#   return for a mixed-regime area, because this record declares no such area; admitting
#   it here would oblige every caller to narrow a case that cannot occur.
DecodedAccount = dict[str, str | Decimal]

# WHY : Assumptions: the trailing pad is identified by NAME and not by position, and the
#   convention was measured rather than assumed: across all fourteen registered layouts
#   every record declares exactly one pad, named either `FILLER` or, where the copybook
#   qualified it, with that word as its final hyphenated component. Testing the name keeps
#   this module free of any byte position of its own, which a position test would
#   reintroduce, and it is the reason a sibling reader can reuse the rule unchanged.
_PAD_FIELD_NAME: Final[str] = "FILLER"
_PAD_NAME_SUFFIX: Final[str] = f"-{_PAD_FIELD_NAME}"


def _is_padding_field(field: FieldSpec) -> bool:
    """Report whether a field is the record's trailing pad rather than data.

    Purpose
    -------
    Decide, from the field's declared name alone, whether it exists only to fill the
    record out to its fixed length. This is the single place that judgement is made, so
    the projection and the record of what was dropped cannot disagree about it.

    Parameters
    ----------
    field : FieldSpec
        The field descriptor under test. Only its ``name`` is consulted.

    Returns
    -------
    bool
        ``True`` when the field is a pad and must be dropped from a decoded record;
        ``False`` for every field that carries data.

    Raises
    ------
    None
    """
    return field.name == _PAD_FIELD_NAME or field.name.endswith(_PAD_NAME_SUFFIX)


# WHY : Assumptions: the pad is DROPPED from every decoded record because its 178 trailing
#   bytes pad the record out to its fixed 300-byte length and carry no data -- the copybook
#   declares them as an unnamed filler and no program reads them. The EBCDIC record decoder
#   deliberately returns it, stating that dropping it is a projection decision belonging
#   to the reader that maps a record onto a table, so this is that decision and this is
#   where it is taken. Both names below are published rather than kept private so the drop
#   is a fact a caller and a verification pass can assert, instead of a silent omission
#   that would make a decoded record a partial description of the bytes it came from.
LOADED_FIELDS: Final[tuple[FieldSpec, ...]] = tuple(
    field for field in ACCOUNT_LAYOUT.fields if not _is_padding_field(field)
)
DROPPED_FIELD_NAMES: Final[frozenset[str]] = frozenset(
    field.name for field in ACCOUNT_LAYOUT.fields if _is_padding_field(field)
)


def _field_containing(offset: int) -> FieldSpec | None:
    """Find the declared field whose byte span covers a record offset.

    Purpose
    -------
    Let a failure report WHICH field a bad byte falls in, using the layout's own spans, so
    a diagnostic can be specific about location without quoting any record content.

    Parameters
    ----------
    offset : int
        A zero-based offset into the record.

    Returns
    -------
    FieldSpec | None
        The descriptor whose half-open span contains ``offset``, or ``None`` when the
        offset lies beyond the declared record; the fields cover the record contiguously,
        so ``None`` means the offset itself is out of range.

    Raises
    ------
    None
    """
    for field in ACCOUNT_LAYOUT.fields:
        if field.start <= offset < field.end:
            return field
    return None


def _require_single_byte_record(record: str, number: int) -> str:
    """Require a record whose character count provably equals its byte count.

    Purpose
    -------
    Close the one width failure the shared text-record iterator cannot see. That iterator
    enforces the declared length in CHARACTERS, which is the right check for text; this
    one additionally proves every character occupies a single byte, so the character
    contract also holds at the byte level the field offsets are expressed in.

    Parameters
    ----------
    record : str
        One whole record, already cut to the declared length by the shared iterator.
    number : int
        The one-based record number within the source, reported so a rejection names the
        row that failed.

    Returns
    -------
    str
        ``record`` unchanged, once every character is proven to be single-byte.

    Raises
    ------
    RecordLengthError
        If any character is outside the single-byte range. The record's declared width
        would then differ from its width in bytes.
    """
    # WHY : Assumptions: a multi-byte character satisfies a CHARACTER-count check while
    #   occupying more than one byte, so it passes the shared iterator's declared-length
    #   test and then desynchronises every offset after it -- and because a record read one
    #   byte out of alignment still decodes to digits, nothing later would raise. The
    #   reference codec reaches this same conclusion for the same reason and rejects a
    #   non-single-byte record outright. Requiring single-byte characters makes the
    #   character count provably equal the byte count, which is what the offsets assume.
    # WHY : Assumptions: this check, and every other validation in this module, is enforced
    #   by an explicit `raise` and never by an `assert`. Running the interpreter with `-O`
    #   strips assert statements outright, so an assertion is not a validation at all: it is
    #   a check that silently disappears in exactly the deployment where a misaligned money
    #   value costs money, and the load would then succeed while writing wrong balances. The
    #   layouts module states the same rule for the same reason, and the reference codec
    #   records it too, so this reader is consistent with both rather than an exception.
    if record.isascii():
        return record

    offset = next(index for index, char in enumerate(record) if not char.isascii())
    field = _field_containing(offset)

    # WHY : Trade-offs: the message names the record number, the zero-based offset and the
    #   containing field's geometry, and it quotes NO part of the record -- not the
    #   offending character and not its code point. That shows exactly WHERE the record
    #   failed while emitting none of its content, so the diagnostic is safe to log
    #   wherever its consumer sends it. The reference codec does echo the offending
    #   character; the Java shared kernel's codecs deliberately do not, and the stricter of
    #   the two forms is adopted here because a reader cannot know which fields of a future
    #   record are sensitive, and a diagnostic cannot be un-logged.
    location = "beyond the declared record" if field is None else f"in field {field.describe()}"
    raise RecordLengthError(
        f"record {number} of {ACCOUNT_LAYOUT.name} holds a character outside the single-byte"
        f" range at zero-based offset {offset}, {location}; a multi-byte character satisfies the"
        f" {ACCOUNT_LAYOUT.reclen}-character width check while occupying more bytes, which moves"
        " every field offset after it, so the record is rejected rather than decoded"
    )


def _decode_text_field_value(record: str, field: FieldSpec) -> str | Decimal:
    """Decode one field of a character record, routing it by its declared storage regime.

    Purpose
    -------
    Produce one field's final value from a record that is already characters. Each of the
    three regimes this record declares is named explicitly and routed to the codec that
    owns it, and anything else is refused.

    Parameters
    ----------
    record : str
        One whole record at its declared character width.
    field : FieldSpec
        The descriptor supplying the offset, the width, the regime, the scale and the sign
        contract. Nothing about the field's position is taken from anywhere else.

    Returns
    -------
    str | Decimal
        The field's characters at full declared width for a character field, and the same
        for an unsigned display field once its digits are proven; an exact
        :class:`decimal.Decimal` at the field's declared scale for a signed display field.

    Raises
    ------
    LayoutError
        If the field declares a storage regime that cannot occur in a character record,
        which is any computational or mixed-regime area.
    ZonedSpanWidthError
        If the record ends before the field's declared span, or the descriptor's display
        geometry is internally inconsistent. Raised by the display codec.
    ZonedDecimalError
        If a display span violates its contract: a non-digit in the body of either display
        regime, or a low-order byte that is not a valid sign overpunch in a signed one.
        Raised by the display codec.
    """
    if field.kind is Kind.TEXT:
        return record[field.start : field.end]

    if field.kind is Kind.UINT:
        # WHY : Trade-offs: the decimal this returns is DISCARDED and the characters are
        #   returned instead, which costs one parse whose result is thrown away. What it buys
        #   is the content check the picture clause states -- an unsigned display field holding
        #   letters is refused rather than passed through -- while keeping the identifier a
        #   digit string of declared width. An integer would drop a leading zero, and a
        #   leading zero is significant in an eleven-digit account identifier.
        decode_zoned_field(record, field)
        return record[field.start : field.end]

    if field.kind is Kind.ZONED:
        # WHY : Alternatives Considered: the FIELD-oriented entry point is called rather than
        #   the span-oriented one, which would have meant slicing here and passing the digit
        #   counts and the sign flag as three separate arguments. The field-oriented form
        #   derives all of them from this descriptor and slices by it too, so this module
        #   restates no part of the geometry -- not the offset, not the width, and not the
        #   unsigned regime's `(length, 0, False)` derivation that the span-oriented call
        #   would have forced into the branch above. Passing the descriptor also lets that
        #   codec name the field in its own diagnostics.
        # WHY : Assumptions: the result is an exact decimal at the scale the picture clause
        #   declares and is never converted to a binary floating-point type. A binary float
        #   cannot represent ten cents exactly, so a total accumulated in one drifts from the
        #   total the baseline computed, by an amount that grows with the row count.
        return decode_zoned_field(record, field)

    # WHY : Assumptions: every regime is named explicitly above and anything else raises,
    #   rather than the last branch doubling as a default. A computational or mixed-regime
    #   area cannot be read from a character record at all: its bytes are packed nibbles,
    #   machine words or a differently-described overlay, and a character decode of them
    #   succeeds, keeps the declared width and yields plausible text, so the damage would be
    #   invisible. Raising here also means a regime added to the enumeration later cannot
    #   fall silently into whichever branch happens to be last.
    raise LayoutError(
        f"field {field.describe()} of record {ACCOUNT_LAYOUT.name} declares a storage regime"
        " that cannot be decoded from a character record; only character and display regimes"
        " can, and a computational or mixed-regime area must be read from the byte image"
        " through the EBCDIC record decoder"
    )


def _project_decoded_fields(values: DecodedAccount) -> DecodedAccount:
    """Drop the trailing pad from a fully decoded record.

    Purpose
    -------
    Apply the one projection that separates the bytes on disk from the columns a row
    carries, so both encodings converge on the same published shape.

    Parameters
    ----------
    values : DecodedAccount
        Every declared field of one record, keyed by field name, including the pad.

    Returns
    -------
    DecodedAccount
        The same mapping without any padding field, in declaration order.

    Raises
    ------
    None
    """
    return {name: value for name, value in values.items() if name not in DROPPED_FIELD_NAMES}


def record_key(record: str) -> str:
    """Return the primary key of one character record, sliced by the descriptor.

    Purpose
    -------
    Expose the key a loader upserts on and a verification pass groups by, taken from the
    descriptor's own key offset and length rather than from a width written here.

    Parameters
    ----------
    record : str
        One whole record at exactly the declared character width.

    Returns
    -------
    str
        The key characters at their full declared width, untrimmed, so a significant leading
        zero survives.

    Raises
    ------
    RecordLengthError
        If the record is shorter than the declared width, which would make the sliced key short.
    """
    # WHY : Refactoring Rationale: this reader published no key accessor while every sibling
    #   reader publishes one, so a loader keying this record had to slice it itself -- which is
    #   the single-sourcing guarantee broken at the one place it matters most, because a key
    #   sliced one character short does not raise. It collides with a sibling record instead, and
    #   a loader resolves that as an upsert onto the wrong row.
    # WHY : Assumptions: the key is sliced by `key_offset` and `key_length` from the descriptor,
    #   never by a literal, so this function states no width of its own.
    if len(record) < ACCOUNT_LAYOUT.reclen:
        raise RecordLengthError(
            f"a {ACCOUNT_LAYOUT.name} record of {len(record)} characters is shorter than the"
            f" declared {ACCOUNT_LAYOUT.reclen}, so its"
            f" {ACCOUNT_LAYOUT.key_length}-character key cannot be sliced"
        )
    start = ACCOUNT_LAYOUT.key_offset
    return record[start : start + ACCOUNT_LAYOUT.key_length]


def decode_ascii_account(record: str, *, number: int = 1) -> DecodedAccount:
    """Decode one account record from the ASCII seed form.

    Purpose
    -------
    Turn a single full-width character record into the published decoded shape: every data
    field keyed by its copybook name, with the trailing pad dropped.

    Parameters
    ----------
    record : str
        One whole record at exactly the declared record width, with no line terminator.
        Records at the declared width are what the shared text-record iterator yields.
    number : int
        The one-based record number within the source, used only so a rejection names the
        row that failed. Defaults to 1 for a caller decoding a record in isolation.

    Returns
    -------
    DecodedAccount
        One entry per data field, keyed by the field name exactly as the copybook spells
        it, in declaration order. Character and unsigned display fields map to characters
        at full declared width; signed display fields map to an exact
        :class:`decimal.Decimal` at scale two. The pad is absent.

    Raises
    ------
    RecordLengthError
        If the record is not the declared width, or holds a character outside the
        single-byte range.
    LayoutError
        If a declared field's storage regime cannot be decoded from a character record.
    ZonedSpanWidthError
        If a display field's declared span is not available, or its geometry is internally
        inconsistent. Raised by the display codec.
    ZonedDecimalError
        If a display field's content violates its contract. Raised by the display codec.
    """
    # WHY : Trade-offs: a record of the WRONG width is rejected here rather than padded or
    #   cut to fit. Truncation would silently discard real data and padding would invent it,
    #   and either way the row would still decode to well-formed digits, so a wrong-width
    #   financial record would post a misaligned money value with nothing reporting it. The
    #   accepted cost is that a genuinely malformed source stops the load instead of loading
    #   partially, which for an account master is the outcome to prefer.
    if len(record) != ACCOUNT_LAYOUT.reclen:
        raise RecordLengthError(
            f"record {number} of {ACCOUNT_LAYOUT.name} is {len(record)} characters against a"
            f" declared width of {ACCOUNT_LAYOUT.reclen}; a record of the wrong width means the"
            " field offsets have moved, so it is rejected rather than padded or truncated"
        )

    checked = _require_single_byte_record(record, number)

    # WHY : Assumptions: the fields are walked in the descriptor's declaration order, which
    #   is the record's byte order, so the resulting mapping iterates the record left to
    #   right. The pad is excluded by iterating the published data fields rather than by
    #   decoding all thirteen and filtering afterwards, which also avoids decoding 178 bytes
    #   of blanks on every single record.
    return {field.name: _decode_text_field_value(checked, field) for field in LOADED_FIELDS}


def iter_ascii_accounts(source: str | Iterable[object]) -> Iterator[DecodedAccount]:
    """Decode the ASCII seed form of the account master, one record at a time.

    Purpose
    -------
    Stream decoded records from character data the caller already holds or is already
    iterating, delegating every record boundary decision to the shared text-record
    iterator.

    Parameters
    ----------
    source : str | Iterable[object]
        The seed data as either the whole text, or an iterable whose every element is one
        LINE, with or without its terminator. An open text stream is such an iterable. A
        byte object is refused, because no character encoding is guessed here.

    Returns
    -------
    Iterator[DecodedAccount]
        Each record in order, in the published decoded shape. A source with no records
        yields nothing at all.

    Raises
    ------
    LayoutError
        If the source is a byte object, is neither text nor iterable, or produces an
        element that is not a line, or if a field declares a regime a character record
        cannot hold.
    RecordLengthError
        If a line is longer than the declared record width, or a record holds a character
        outside the single-byte range.
    ZonedSpanWidthError
        If a display field's declared span is not available. Raised by the display codec.
    ZonedDecimalError
        If a display field's content violates its contract. Raised by the display codec.
    """
    # WHY : Assumptions: the record cut is DELEGATED and not written again here. That
    #   iterator owns one statement of the text-mode contract for the whole package: it
    #   splits on the separator, removes at most one trailing carriage return and separator
    #   so that trailing blanks stay data, drops the single phantom empty piece a text ending
    #   in a separator produces, right-pads a short line, and REJECTS an over-long one. A
    #   second implementation here is exactly the drift this dependency edge exists to
    #   prevent, and it would be invisible: two readers stripping terminators slightly
    #   differently both return well-formed records.
    # WHY : Trade-offs: that iterator's tolerance for a SHORT line -- padding it on the
    #   right with blanks -- is inherited deliberately rather than overridden. It exists
    #   because one shipped seed conversion lost its trailing pad entirely, and padding on the
    #   right cannot move a field that is present; the characters added are precisely the pad
    #   the text form omitted. Every row of this dataset's seed is already full width, so the
    #   tolerance never engages here, and overriding it would fork the contract for one reader.
    records = iter_ascii_text_records(source, ACCOUNT_LAYOUT.reclen)

    # WHY : Trade-offs: records are YIELDED one at a time rather than collected, so memory
    #   is constant in the record count. The cost is a single forward pass -- a caller wanting
    #   a second reading must re-open the source -- and what it buys is that this reader
    #   behaves identically on the small committed seed and on a production extract many
    #   orders of magnitude larger, so the one proven against the seed is the one that runs.
    for number, record in enumerate(records, start=1):
        yield decode_ascii_account(record, number=number)


def read_ascii_accounts(path: pathlib.Path) -> Iterator[DecodedAccount]:
    """Stream the account master from an ASCII seed file at an explicit path.

    Purpose
    -------
    Open one named seed file, stream its records through the shared text-record contract,
    and close it when the caller stops reading.

    Parameters
    ----------
    path : pathlib.Path
        The exact seed file to read. The caller names the file; this function never
        searches a directory for it.

    Returns
    -------
    Iterator[DecodedAccount]
        Each record in order, in the published decoded shape. A zero-byte file yields
        nothing and is not an error.

    Raises
    ------
    OSError
        If the path cannot be opened or read.
    LayoutError
        If the file produces an element that is not a line, or a field declares a regime a
        character record cannot hold.
    RecordLengthError
        If a line is longer than the declared record width, or a record holds a character
        outside the single-byte range.
    ZonedSpanWidthError
        If a display field's declared span is not available. Raised by the display codec.
    ZonedDecimalError
        If a display field's content violates its contract. Raised by the display codec.
    """
    # WHY : Assumptions: the caller supplies an EXPLICIT file, and this function never
    #   globs a directory to find one. The seed directories make that concrete: the EBCDIC
    #   directory holds a zero-byte placeholder and a second dataset whose name differs from
    #   the account master's by one character, so a pattern match over the directory would
    #   sweep up both and load the wrong dataset while reporting success. A zero-byte file is
    #   legitimately a dataset with no records and is not an error.
    # WHY : Alternatives Considered: the file is decoded through a single-byte code page
    #   that is total over all 256 byte values, rather than through a strict ASCII decode.
    #   Both reject a non-conforming file, but they differ in WHERE and HOW. A strict decode
    #   would fail inside the interpreter's reader with an encoding error, which is untyped
    #   with respect to this package and would make the explicit single-byte guard above
    #   unreachable. A total single-byte page instead maps each byte to exactly one character,
    #   so the character count the shared iterator checks provably equals the byte count, and
    #   the failure surfaces as this package's own record-length error naming the offset. Both
    #   non-conforming shapes then land on that one typed error: a multi-byte sequence widens
    #   the row past the declared width and the iterator rejects it, while a single high byte
    #   leaves the width intact and the guard rejects it.
    # WHY : Assumptions: line splitting is pinned to the separator alone, matching the
    #   shared iterator's own whole-text scanner exactly, so streaming this handle line by
    #   line and passing the whole text produce identical records. Leaving the default in
    #   place would let the interpreter translate and split on a carriage return as well,
    #   which would move terminator policy out of the module that owns it.
    with path.open("r", encoding="latin-1", newline="\n") as handle:
        yield from iter_ascii_accounts(handle)


def decode_ebcdic_account(record: bytes | bytearray | memoryview) -> DecodedAccount:
    """Decode one account record from the EBCDIC dataset form.

    Purpose
    -------
    Turn a single fixed-length record image into the published decoded shape, delegating
    the per-field character conversion and the numeric decode to the EBCDIC record codec.

    Parameters
    ----------
    record : bytes | bytearray | memoryview
        One whole record image of exactly the declared record length, read in binary mode.
        A ``str`` is refused by the codec, because character data has already lost the byte
        values a sign overpunch depends on.

    Returns
    -------
    DecodedAccount
        One entry per data field, keyed by the field name exactly as the copybook spells
        it, in declaration order, with the pad dropped. The shape is identical to the ASCII
        path's, which is what makes the two corpora comparable.

    Raises
    ------
    TypeError
        If the record is a ``str`` or is not a one-dimensional image of single bytes.
    EbcdicRecordLengthError
        If the record is not exactly the declared record length. This is a record-length
        error, so a caller guarding either encoding may catch the shared base type.
    EbcdicFieldDecodeError
        If a span does not decode to exactly one character per byte and re-encode to those
        same bytes.
    ZonedDecimalError
        If a display span violates its contract. Raised by the display codec.
    """
    # WHY : Assumptions: the conversion is DELEGATED per field and never performed on the
    #   record as a whole. That codec decodes each declared span on its own, so a signed
    #   display field's sign overpunch is converted as one field while the numeric decode
    #   stays a separate step -- and no span of packed nibbles or low-value padding is ever
    #   handed to a character decoder. Decoding a whole record through a code page is the
    #   single most likely mistake on this path and the most damaging: it succeeds, preserves
    #   the declared width, and yields a record that looks almost right.
    return _project_decoded_fields(decode_record(record, ACCOUNT_LAYOUT))


def iter_ebcdic_accounts(
    source: pathlib.PurePath | bytes | bytearray | memoryview | Iterable[object],
) -> Iterator[DecodedAccount]:
    """Decode the EBCDIC dataset form of the account master, one record at a time.

    Purpose
    -------
    Stream decoded records from a fixed-length dataset, cut strictly on the record length
    the layout declares.

    Parameters
    ----------
    source : pathlib.PurePath | bytes | bytearray | memoryview | Iterable[object]
        The dataset, in one of four shapes: a path, which the codec opens read-only in
        binary mode, streams and closes; a whole byte image the caller already holds; an
        open binary stream, read strictly forward and neither seeked nor closed; or an
        iterable of byte pieces whose boundaries may fall anywhere. A ``str`` is refused.

    Returns
    -------
    Iterator[DecodedAccount]
        Each record in order, in the published decoded shape. A dataset with no bytes
        yields nothing at all.

    Raises
    ------
    TypeError
        If the source is a ``str``, which is ambiguous between a dataset location and
        character data somebody has already decoded.
    EbcdicRecordLengthError
        If the dataset does not divide into whole records of the declared length.
    EbcdicFieldDecodeError
        If a span does not decode to exactly one character per byte.
    ZonedDecimalError
        If a display span violates its contract. Raised by the display codec.
    LayoutError
        If the source is neither a byte image nor readable nor iterable, or produces a
        piece that is not a byte object.
    """
    # WHY : Assumptions: the dataset is cut on the declared record length ALONE, and no
    #   line terminator is looked for, honoured, stripped or padded on this path. A
    #   fixed-length blocked dataset carries no terminators, so a byte that happens to equal
    #   a newline is field data -- a low-order digit, a packed nibble pair or a pad byte --
    #   and splitting on it would produce a handful of pieces of wildly differing lengths, most
    #   of them cut through the middle of a field. The correctness test for this dataset is
    #   that its size divides by the declared record length with no remainder, which the
    #   delegated iterator checks before it yields the first record.
    # WHY : Assumptions: the text form's tolerances must never reach here. Right-padding a
    #   short piece or stripping a trailing byte would turn a genuine length failure into a
    #   plausible record, which is why this path reaches a different entry point of the
    #   layouts module and shares no code with the text one.
    for record in iter_ebcdic_records(source, ACCOUNT_LAYOUT):
        # WHY : Trade-offs: records are yielded one at a time for the same reason the text
        #   path streams -- constant memory in the record count, at the cost of one forward
        #   pass -- so a caller can compare the two corpora record by record without either
        #   side holding a dataset in memory.
        yield decode_ebcdic_account(record)


def read_ebcdic_accounts(path: pathlib.Path) -> Iterator[DecodedAccount]:
    """Stream the account master from an EBCDIC dataset file at an explicit path.

    Purpose
    -------
    Read one named fixed-length dataset and stream its decoded records, with the size
    validated against the declared record length before the first record is produced.

    Parameters
    ----------
    path : pathlib.Path
        The exact dataset file to read. The caller names the file; this function never
        searches a directory for it.

    Returns
    -------
    Iterator[DecodedAccount]
        Each record in order, in the published decoded shape. A zero-byte file yields
        nothing and is not an error.

    Raises
    ------
    OSError
        If the path cannot be inspected or opened.
    EbcdicRecordLengthError
        If the file size does not divide into whole records of the declared length.
    EbcdicFieldDecodeError
        If a span does not decode to exactly one character per byte.
    ZonedDecimalError
        If a display span violates its contract. Raised by the display codec.
    """
    # WHY : Assumptions: the caller supplies an EXPLICIT file here too, and the hazard is
    #   sharper on this path than on the text one. Beside the account master the EBCDIC
    #   directory holds a zero-byte placeholder and a second dataset whose name differs by a
    #   single character -- and that second file is a BYTE-IDENTICAL duplicate of the master,
    #   verified by digest. So a directory pattern matching both would not load the wrong
    #   dataset; it would load the right one TWICE, yielding a hundred records where there
    #   are fifty and doubling every money total the verification pass aggregates. Nothing
    #   would catch it: the combined image still divides by this record length with no
    #   remainder, so the exact-division check passes and every record decodes cleanly.
    # WHY : Alternatives Considered: the path is handed to the codec rather than opened
    #   here and passed as a stream. The codec validates the file size against the declared
    #   record length BEFORE yielding a first record, so a truncated dataset fails up front
    #   instead of part way through a load, and it owns the open, the forward-only read and
    #   the close. Opening the file here would duplicate that lifecycle for no gain.
    yield from iter_ebcdic_accounts(pathlib.PurePath(path))


def render_masked_account_record(record: str) -> str:
    """Render one character record with every sensitive field redacted.

    Purpose
    -------
    Give an operator a privacy-safe, byte-aligned rendering of a whole record, so a failed
    comparison can show a record's shape and which field differs without emitting a
    complete cardholder identity.

    Parameters
    ----------
    record : str
        One whole record at exactly the declared character width.

    Returns
    -------
    str
        A rendering of exactly the declared width, with each sensitive field replaced by a
        same-width redaction and every other field left verbatim, so offsets can still be
        counted across it.

    Raises
    ------
    LayoutError
        If the record is not the declared width.
    """
    # WHY : Refactoring Rationale: this comment used to record that the record declared NO
    #   sensitive field, so that "the rendering it returns today is the record verbatim". That
    #   was an accurate description of a defect rather than a justification for one: a function
    #   named render_masked_account_record, and documented as privacy-safe, returned the
    #   eleven-digit account identifier and all five S9(10)V99 monetary fields in clear. The
    #   fix is the one this comment already prescribed -- marking the fields sensitive in the
    #   layout -- and it is applied by _close_master_disclosure in the copybook module.
    # WHY : Assumptions: what this now returns is the record with the account identifier, the
    #   current balance, both credit limits, both cycle accumulators and the postal code
    #   replaced by same-width keyed redaction tags, and with the active-status flag, the three
    #   dates, the disclosure-group code and the trailing pad left verbatim. The disclosed set
    #   is what lets a failed comparison still say WHICH field differs; the withheld set is
    #   what the rendering rule of docs/architecture/observability.md places in its omitted
    #   class. Width is unchanged at 300 characters, so offsets are still countable across it.
    return mask_record(record, ACCOUNT_LAYOUT)


def render_masked_account_field(record: str, field_name: str) -> str:
    """Render one named field of a character record with redaction applied.

    Purpose
    -------
    Produce a privacy-safe rendering of a single field, for a diagnostic that needs to show
    one field rather than a whole record.

    Parameters
    ----------
    record : str
        One whole record at exactly the declared character width.
    field_name : str
        The field name exactly as the copybook spells it, including any baseline
        misspelling, since the descriptor preserves the copybook's own spelling.

    Returns
    -------
    str
        A rendering of exactly that field's declared width: the characters verbatim when
        the field is not sensitive, otherwise a same-width redaction.

    Raises
    ------
    LayoutError
        If no field of that name is declared, or the sliced span is not the field's
        declared width, which happens when the record is short.
    """
    # WHY : Assumptions: the field is resolved through the layout by name and then sliced
    #   by its own declared span, so this module still states no offset of its own and an
    #   unknown name fails loudly here rather than silently rendering the wrong bytes.
    field = ACCOUNT_LAYOUT.field(field_name)
    return mask_field(field, record[field.start : field.end])
