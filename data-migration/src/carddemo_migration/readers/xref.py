"""Streaming reader for the CardDemo card cross-reference, in both shipped encodings.

Purpose
-------
Turn the card cross-reference extract into decoded records the Aurora loaders and the verification
passes can consume, one record at a time, from either of the two forms the baseline ships: the
line-oriented ASCII seed and the fixed-length EBCDIC dataset. Both entry points yield the same
decoded shape, so a caller can swap corpora and compare the two without adapting to a second
contract.

This module follows the contract ``carddemo_migration.readers.account`` established and differs
from it only in which record descriptor it names. Where the reasoning behind a step is identical
to that module's it is referenced rather than restated, because two copies of one justification
drift apart and then one of them is wrong; where this record differs, the difference is recorded
here at the point it matters.

Parameters
----------
None
    A module takes no argument. Every published callable states its own parameters at its own
    definition, and this module reads no argument, option or environment variable while being
    imported.

Returns
-------
None
    Importing binds names only: :data:`LOADED_FIELDS` and :data:`DROPPED_FIELD_NAMES` are derived
    from the record descriptor, and the data-region width is taken from the last published field.
    No dataset is opened, no database connection is made, no environment variable is read and no
    network is reached at import time, so importing this module is safe on a bare checkout with no
    credential configured.

Raises
------
LayoutError
    At import, from ``carddemo_migration.copybook.layouts``, which this module imports for its
    record descriptor: that module validates every declared layout's geometry and both disclosure
    allowlists as it loads, and refuses to import if any of them disagree. The failure belongs to
    that module and is neither caught nor re-worded here. At call time, from the two masked
    renderings, if ``CARDDEMO_MASK_HMAC_KEY`` is set to material this package refuses -- anything
    that is not canonical base64 of at least thirty-two distinct-valued bytes -- because a
    guessable key returns the redaction tag to the confirmable digest it replaced. Leaving the
    variable unset is supported and is not an error.

What this module reads
---------------------
The record is ``CARD-XREF-RECORD`` as declared in ``app/cpy/CVACT03Y.cpy``, and its byte geometry
is resolved exclusively through ``XREF_LAYOUT`` in ``carddemo_migration.copybook.layouts``. The two
shipped corpora are ``app/data/ASCII/cardxref.txt`` and
``app/data/EBCDIC/AWS.M2.CARDDEMO.CARDXREF.PS``, fifty records each, and they differ in PHYSICAL
width in a way that is load-bearing: the ASCII rows are thirty-six characters on disk because the
seed conversion dropped the trailing pad, while the EBCDIC dataset is 2500 bytes at the full
declared fifty. The shared text iterator right-pads the short rows so both forms decode to the
same fields at the same offsets; reading the ASCII seed at its OBSERVED width instead would put
every field after the first at the wrong offset. Both are REFERENCE-only inputs, opened read-only.

This record is also the ``CXACAIX`` alternate access path: its account identifier at offset 25 is
the alternate key CICS surfaced as a file, and the target replaces that with a non-unique secondary
index on the same column. The descriptor carries the alternate key, so a caller needing the
by-account path reads it from there rather than from a width written here.

The short-row contract, stated once
-----------------------------------
This is the only reader in the subpackage that must accept a short physical row, so its tolerance
is published rather than left as an implementation detail. A row is padded on the right with spaces
to the declared length and decoded when it is short by no more than the trailing pad; it is
REJECTED with a record-length error when it is longer than the declared length, and equally when it
is short enough for the pad to reach a field that carries data. The middle case is the seed and
costs nothing, because the characters added are exactly the pad the projection discards. The two
rejected cases are the ones where padding or trimming would change a published value rather than
restore a discarded one: one would invent an identifier, the other would discard real bytes. The
boundary is derived from the published field tuple -- the end offset of the last field that carries
data -- and is never written here as a number; see :data:`_DATA_REGION_WIDTH`. It is enforced by
the shared text-record iterator, which compares it against the SOURCE line before padding, because
that is the only place the comparison is exact for every record in the corpus.

Decoded shape
-------------
A decoded record is a ``dict`` keyed by the field name exactly as the copybook spells it, in
declaration order, which is the record's byte order. A character field maps to its characters at
full declared width, untrimmed; an unsigned display field maps to its characters too, once they are
proven to be the digits its picture clause requires, so the leading zeroes in the nine-digit
customer identifier and the eleven-digit account identifier survive. The trailing pad is absent;
see :data:`DROPPED_FIELD_NAMES`. That shape is deliberately the one
``carddemo_migration.copybook.ebcdic_codec.decode_record`` publishes, minus the pad, which is what
makes the two encodings comparable rather than merely similar.

Cross-reference exposure control
--------------------------------
All three of this record's data fields are marked sensitive by the descriptor: the primary account
number, the customer identifier and the account identifier. This record exists solely to join those
three together, so there is nothing in it that is not an identifier -- which is why a whole-record
rendering of it discloses nothing but the pad. All three are **emitted** in the decoded mapping,
because the cross-reference table stores exactly those three columns, and all three are **redacted**
in every diagnostic this module produces, with the account number revealing at most its trailing
four digits. Diagnostics never echo record content of any kind.

Design decisions (WHY)
----------------------
Assumptions:
    **Every offset, length, storage regime and record length is imported, never declared.** This
    module states no byte position of its own, and in particular no key width: the 16-byte key is
    read from the descriptor's own ``key_offset`` and ``key_length``. That is the Python analogue of
    compiling every COBOL program against a single ``cobc -I app/cpy`` include path, and the
    consequence of breaking it is undetectable -- a re-declared offset lets this reader and a
    sibling reading the same bytes drift apart, and a record read one byte out of alignment still
    decodes to plausible characters, so nothing raises and no test fails.
Trade-offs:
    **Records are streamed, never materialised.** Both entry points are generators, so memory is
    constant in the record count. The accepted cost is a single forward pass; what it buys is that
    this reader behaves identically on the committed seed and on a production extract many orders
    larger, so the one proven against the seed is the one that runs.
Assumptions:
    **No binary floating-point value anywhere.** This record declares no signed display field, so
    the prohibition costs nothing to honour here, but it is stated rather than left implicit: the
    identifiers this record carries exceed the range a binary float represents exactly, so routing
    one through a float would corrupt the very identifier the target table joins on. Identifiers
    stay character strings end to end.
Trade-offs:
    **Standard library plus ``carddemo_migration.copybook``, and nothing else.** No database driver,
    no AWS SDK and no character-set package is imported here, so this reader imports and runs on a
    bare checkout with no credential configured. Choosing a dataset, opening a connection and
    writing rows belong to a loader.
"""

from __future__ import annotations

import pathlib
from collections.abc import Iterable, Iterator, Mapping
from decimal import Decimal
from typing import Final

# WHY : Assumptions: these imports are absolute and rooted at the distribution package rather
#   than relative, and the record descriptor named below is the ONLY statement of this record's
#   geometry anywhere in the package. A relative import is how the single-sourcing guarantee gets
#   broken quietly: a module moved between `readers/` and `loaders/` keeps importing successfully
#   but against a different sibling, and this project's ruff configuration bans relative imports
#   outright for that reason.
from carddemo_migration.copybook.ebcdic_codec import decode_record, iter_ebcdic_records
from carddemo_migration.copybook.layouts import (
    XREF_LAYOUT,
    FieldSpec,
    Kind,
    LayoutError,
    RecordLengthError,
    iter_ascii_text_records,
    mask_field,
    mask_record,
)
from carddemo_migration.copybook.zoned import decode_zoned_field
from carddemo_migration.readers.source import (
    data_region_width,
    iter_seed_lines,
    require_exact_record_width,
)

__all__ = [
    "XREF_LAYOUT",
    "DROPPED_FIELD_NAMES",
    "LOADED_FIELDS",
    "DecodedCardXref",
    "decode_ascii_card_xref",
    "decode_ebcdic_card_xref",
    "iter_ascii_card_xrefs",
    "iter_ebcdic_card_xrefs",
    "read_ascii_card_xrefs",
    "read_ebcdic_card_xrefs",
    "record_key",
    "render_masked_card_xref_field",
    "render_masked_card_xref_record",
]

# WHY : Assumptions: the decoded value type is `str` alone because this record declares only
#   character and unsigned display fields, so no decoded value is ever a number and this
#   module has no money path. The projection below still accepts the record decoder's
#   wider union, because that is what the decoder is typed to return.
DecodedCardXref = dict[str, str]

# WHY : Assumptions: the trailing pad is identified by NAME and not by position, and the
#   convention was measured rather than assumed: across all fourteen registered layouts every
#   record declares exactly one pad, named either `FILLER` or, where the copybook qualified it,
#   with that word as its final hyphenated component -- which is exactly this record's case for
#   the security file and is why a positional rule would have had to special-case it. Testing the
#   name keeps this module free of any byte position of its own.
_PAD_FIELD_NAME: Final[str] = "FILLER"
_PAD_NAME_SUFFIX: Final[str] = f"-{_PAD_FIELD_NAME}"


def _is_padding_field(field: FieldSpec) -> bool:
    """Report whether a field is the record's trailing pad rather than data.

    Purpose
    -------
    Decide, from the field's declared name alone, whether it exists only to fill the record out
    to its fixed length. This is the single place that judgement is made, so the projection and
    the record of what was dropped cannot disagree about it.

    Parameters
    ----------
    field : FieldSpec
        The field descriptor under test. Only its ``name`` is consulted.

    Returns
    -------
    bool
        ``True`` when the field is a pad and must be dropped from a decoded record; ``False``
        for every field that carries data.

    Raises
    ------
    None
    """
    return field.name == _PAD_FIELD_NAME or field.name.endswith(_PAD_NAME_SUFFIX)


# WHY : Assumptions: the pad is DROPPED from every decoded record because its trailing bytes only
#   fill the record out to its declared length, which is also what makes the two shipped corpora
#   reconcilable: the ASCII seed omits those bytes and loses nothing, because nothing published
#   depended on them. The EBCDIC record decoder returns the pad and leaves the drop to the reader
#   that maps a record onto a table, so this is where that decision is taken. Both names below are
#   published rather than private so a caller and a verification pass can assert the drop instead
#   of inferring it.
LOADED_FIELDS: Final[tuple[FieldSpec, ...]] = tuple(
    field for field in XREF_LAYOUT.fields if not _is_padding_field(field)
)
DROPPED_FIELD_NAMES: Final[frozenset[str]] = frozenset(
    field.name for field in XREF_LAYOUT.fields if _is_padding_field(field)
)

# WHY : Assumptions: this is the boundary the short-row tolerance stops at, and it is DERIVED by
#   the shared helper from the published field tuple rather than written as a number. A literal
#   here would be a second declaration of this record's geometry in the one module whose whole
#   discipline is to declare none, and it would go stale INVISIBLY: a descriptor edit that widened
#   a field would move the real boundary while the literal kept pointing at the old one, so this
#   reader would start accepting genuinely short rows and padding real data into existence with
#   nothing raising. The helper is used rather than the tuple's last element because the two agree
#   only for a record whose published fields are contiguous, as this one's are.
# WHY : Trade-offs: the last DATA field's end is the boundary rather than the record length, and
#   the asymmetry is the entire point. Bytes at or beyond this offset belong to the trailing pad,
#   which is dropped anyway, so supplying them by padding restores exactly what the seed
#   conversion discarded and changes no published value; bytes BEFORE it belong to a published
#   identifier, so supplying those would invent an identifier the source never carried and hand a
#   loader a row to key on.
_DATA_REGION_WIDTH: Final[int] = data_region_width(LOADED_FIELDS)


def _field_containing(offset: int) -> FieldSpec | None:
    """Find the declared field whose byte span covers a record offset.

    Purpose
    -------
    Let a failure report WHICH field a bad byte falls in, using the layout's own spans, so a
    diagnostic can be specific about location without quoting any record content.

    Parameters
    ----------
    offset : int
        A zero-based offset into the record.

    Returns
    -------
    FieldSpec | None
        The descriptor whose half-open span contains ``offset``, or ``None`` when the offset lies
        beyond the declared record; the fields cover the record contiguously, so ``None`` means
        the offset itself is out of range.

    Raises
    ------
    None
    """
    for field in XREF_LAYOUT.fields:
        if field.start <= offset < field.end:
            return field
    return None


def _require_single_byte_record(record: str, number: int) -> str:
    """Require a record whose character count provably equals its byte count.

    Purpose
    -------
    Close the one width failure the shared text-record iterator cannot see. That iterator
    enforces the declared length in CHARACTERS, which is the right check for text; this one
    additionally proves every character occupies a single byte, so the character contract also
    holds at the byte level the field offsets are expressed in.

    Parameters
    ----------
    record : str
        One whole record, already cut to the declared length by the shared iterator.
    number : int
        The one-based record number within the source, reported so a rejection names the row
        that failed.

    Returns
    -------
    str
        ``record`` unchanged, once every character is proven to be single-byte.

    Raises
    ------
    RecordLengthError
        If any character is outside the single-byte range. The record's declared width would
        then differ from its width in bytes.
    """
    # WHY : Assumptions: a multi-byte character satisfies a CHARACTER-count check while occupying
    #   more than one byte, so it passes the shared iterator's declared-length test and then
    #   desynchronises every offset after it -- and a record read one byte out of alignment still
    #   decodes to plausible characters, so nothing later raises. On THIS record it does a second
    #   kind of damage the siblings need not worry about: the inflated character count lets a
    #   genuinely short source line present as full width and slip past the pad bound. Proving the
    #   two counts equal is what keeps that bound meaningful. Like every validation here it is an
    #   explicit `raise` and never an `assert`, because `-O` strips assertions outright.
    if record.isascii():
        return record

    offset = next(index for index, char in enumerate(record) if not char.isascii())
    field = _field_containing(offset)

    # WHY : Trade-offs: the message names the record number, the zero-based offset and the
    #   containing field's geometry, and it quotes NO part of the record -- not the offending
    #   character and not its code point. That shows exactly WHERE the record failed while
    #   emitting none of its content, so the diagnostic is safe to log wherever its consumer
    #   sends it. The reference codec does echo the offending character; the Java shared kernel's
    #   codecs deliberately do not, and the stricter of the two forms is adopted here because
    #   every data byte of this record is an identifier -- a primary account number, a customer
    #   identifier and an account identifier -- so there is no span of it that would be safe to
    #   echo.
    location = "beyond the declared record" if field is None else f"in field {field.describe()}"
    raise RecordLengthError(
        f"record {number} of {XREF_LAYOUT.name} holds a character outside the single-byte"
        f" range at zero-based offset {offset}, {location}; a multi-byte character satisfies"
        f" the {XREF_LAYOUT.reclen}-character width check while occupying more bytes, which"
        " moves every field offset after it, so the record is rejected rather than decoded"
    )


def _decode_text_field_value(record: str, field: FieldSpec) -> str:
    """Decode one field of a character record, routing it by its declared storage regime.

    Purpose
    -------
    Produce one field's final value from a record that is already characters. Each regime this
    record declares is named explicitly and routed to the codec that owns it, and anything else
    is refused.

    Parameters
    ----------
    record : str
        One whole record at its declared character width.
    field : FieldSpec
        The descriptor supplying the offset, the width and the regime. Nothing about the field's
        position is taken from anywhere else.

    Returns
    -------
    str
        The field's characters at full declared width, untrimmed, so a leading zero inside an
        identifier survives.

    Raises
    ------
    LayoutError
        If the field declares a storage regime that cannot occur in a character record, which is
        any computational or mixed-regime area.
    ZonedSpanWidthError
        From the display codec, if an unsigned display field's declared span is not available or the
        descriptor's display geometry is internally inconsistent.
    ZonedDecimalError
        From the display codec, if a display span holds a non-digit in its body or, in a signed
        regime, a low-order byte that is not a valid sign overpunch.
    """
    if field.kind is Kind.TEXT:
        return record[field.start : field.end]

    if field.kind is Kind.UINT:
        # WHY : Trade-offs: the decimal this returns is DISCARDED and the characters are returned
        #   instead, at the cost of one parse whose result is thrown away. What it buys is the
        #   content check the picture clause states -- an unsigned display field holding a letter is
        #   refused rather than passed through -- while keeping the value a digit string of declared
        #   width, where an integer would drop a leading zero that is significant in every
        #   identifier this corpus declares as `PIC 9`. It runs on BOTH paths of this reader, so the
        #   same defective record cannot decode differently depending on which corpus it arrived
        #   in.
        decode_zoned_field(record, field)
        return record[field.start : field.end]

    # WHY : Assumptions: every regime this record declares is named explicitly above and anything
    #   else raises, rather than the last branch doubling as a default. A computational or
    #   mixed-regime area cannot be read from a character record at all: its bytes are packed
    #   nibbles, machine words or a differently-described overlay, and a character decode of them
    #   succeeds, keeps the declared width and yields plausible text, so the damage would be
    #   invisible. Raising here also means a regime added to this record later cannot fall
    #   silently into whichever branch happens to be last.
    raise LayoutError(
        f"field {field.describe()} of record {XREF_LAYOUT.name} declares a storage regime"
        " that cannot be decoded from a character record; only the character and display regimes"
        " can, and a computational or mixed-regime area must be read from the byte image through"
        " the EBCDIC record decoder"
    )


def _project_decoded_fields(
    values: Mapping[str, str | Decimal | bytes],
) -> DecodedCardXref:
    """Drop the trailing pad from a fully decoded record.

    Purpose
    -------
    Apply the projection that separates the bytes on disk from the columns a row carries, so both
    encodings converge on the same published shape, and fail closed on a value the published
    shape cannot hold.

    Parameters
    ----------
    values : Mapping[str, str | Decimal | bytes]
        Every declared field of one record, keyed by field name, including the pad. The parameter
        admits the record decoder's own wider value union.

    Returns
    -------
    DecodedCardXref
        The same mapping without any padding field, in
        declaration order.

    Raises
    ------
    LayoutError
        If a retained field decoded to raw bytes, which means the descriptor has acquired a
        computational or mixed-regime area this reader publishes no representation for.
    """
    # WHY : Trade-offs: an unexpected `bytes` value is REJECTED rather than filtered out. The
    #   filter would be justified by this record declaring no computational area today, and that
    #   is true -- but the moment a descriptor edit or a decoder change made it false, a filter
    #   would silently OMIT a real field and hand a loader a plausible partial row whose missing
    #   column inserts as NULL with nothing reporting it. Failing closed converts an invisible
    #   data-integrity fault into a named one.
    # WHY : Trade-offs: the pad is dropped BEFORE the type is judged, so a pad that decoded to
    #   bytes cannot raise. The pad is not published, so its regime is not this reader's contract
    #   to enforce, and raising over a value nobody receives would turn a harmless descriptor
    #   detail into a load failure.
    projected: DecodedCardXref = {}
    for name, value in values.items():
        if name in DROPPED_FIELD_NAMES:
            continue
        if isinstance(value, bytes):
            # WHY : Trade-offs: the rejection names the field's GEOMETRY through the descriptor
            #   and never renders the bytes, not even as a length-bounded excerpt, because an
            #   unexpected computational span could sit anywhere in this record including over a
            #   field the disclosure policy protects. Naming the field is enough to locate the
            #   defect, which is in the descriptor rather than in the data.
            raise LayoutError(
                f"field {XREF_LAYOUT.field(name).describe()} of record"
                f" {XREF_LAYOUT.name} decoded to raw bytes rather than a published value, so"
                " it declares a computational or mixed-regime area; such an area must be decoded"
                " by the codec that owns its regime rather than dropped from the row"
            )
        projected[name] = value
    return projected


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
        If the record is not exactly the declared width. Raised by the shared width guard: a short
        record would yield a short key that collides with a sibling row, and an over-long one means
        the source was cut on the wrong boundary, so neither is accepted.
    """
    # WHY : Assumptions: the key is sliced by `key_offset` and `key_length` from the descriptor,
    #   never by a literal. Writing the width here would be a second statement of it, and a key
    #   sliced one character short still looks like a key -- it collides with a sibling record
    #   instead of raising, which a loader would resolve as an upsert onto the wrong row.
    # WHY : Trade-offs: the width test is DELEGATED to the shared guard and is EXACT in both
    #   directions. An over-long record is the more dangerous case: the key sliced from it comes
    #   from the right offsets of the WRONG record -- two rows concatenated, most plausibly -- so it
    #   looks entirely well formed and a loader upserts on it. Delegating also keeps the eight flat
    #   readers from drifting apart on a test they all have to make.
    require_exact_record_width(record, XREF_LAYOUT)
    start = XREF_LAYOUT.key_offset
    return record[start : start + XREF_LAYOUT.key_length]


def decode_ascii_card_xref(
    record: str,
    *,
    number: int = 1,
) -> DecodedCardXref:
    """Decode one card cross-reference record from the ASCII seed form.

    Purpose
    -------
    Turn a single full-width character record into the published decoded shape: every data field
    keyed by its copybook name, with the trailing pad dropped.

    Parameters
    ----------
    record : str
        One whole record at exactly the declared record width, with no line terminator. Records
        at the declared width are what the shared text-record iterator yields.
    number : int
        The one-based record number within the source, used only so a rejection names the row
        that failed. Defaults to 1 for a caller decoding a record in isolation.

    Returns
    -------
    DecodedCardXref
        One entry per data field, keyed by the field name exactly as the copybook spells it, in
        declaration order. The pad is absent.

    Raises
    ------
    RecordLengthError
        If the record is not the declared width, holds a character outside the single-byte
        range, or stops inside a field that carries data.
    LayoutError
        If a declared field's storage regime cannot be decoded from a character record.
    ZonedSpanWidthError
        From the display codec, if an unsigned display field's declared span is not available or the
        descriptor's display geometry is internally inconsistent.
    ZonedDecimalError
        From the display codec, if a display span holds a non-digit in its body or, in a signed
        regime, a low-order byte that is not a valid sign overpunch.
    """
    # WHY : Trade-offs: a record of the WRONG width is rejected here rather than padded or cut to
    #   fit. Truncation would silently discard real data and padding would invent it, and either
    #   way the row would still decode to well-formed characters, so a wrong-width record would
    #   load under a misread key with nothing reporting it. The accepted cost is that a genuinely
    #   malformed source stops the load instead of loading partially.
    if len(record) != XREF_LAYOUT.reclen:
        raise RecordLengthError(
            f"record {number} of {XREF_LAYOUT.name} is {len(record)} characters against a"
            f" declared width of {XREF_LAYOUT.reclen}; a record of the wrong width means the"
            " field offsets have moved, so it is rejected rather than padded or truncated"
        )

    checked = _require_single_byte_record(record, number)

    # WHY : Assumptions: the data-region check belongs to `iter_ascii_text_records`, through
    #   `min_data_width`, and not here, because only there is the SOURCE line's length still
    #   visible. Inspecting the PADDED row for a trailing space would be exact for THIS record,
    #   whose two trailing data fields are display fields that cannot hold a blank, and would not
    #   generalise -- the daily transaction's last published field is a processing timestamp that is
    #   legitimately blank on all 300 shipped records, so the same rule would refuse every one of
    #   them.
    # WHY : Trade-offs: a record handed DIRECTLY to this single-record entry point is checked only
    #   against its declared length, because a caller passing one record has already decided where
    #   it starts and ends -- there is no source line to measure. A fabricated blank in a trailing
    #   field is still caught, by the display codec's digit check on the field itself; what is
    #   given up is the clearer length-flavoured message, and only on the path that reads no file.

    # WHY : Assumptions: the fields are walked in the descriptor's declaration order, which is the
    #   record's byte order, so the resulting mapping iterates the record left to right. The pad
    #   is excluded by iterating the published field tuple rather than by decoding every field and
    #   filtering afterwards, which also avoids decoding a span of blanks on every record.
    return {field.name: _decode_text_field_value(checked, field) for field in LOADED_FIELDS}


def iter_ascii_card_xrefs(
    source: str | Iterable[object],
) -> Iterator[DecodedCardXref]:
    """Decode the ASCII seed form of the card cross-reference, one record at a time.

    Purpose
    -------
    Stream decoded records from character data the caller already holds or is already iterating,
    delegating every record boundary decision to the shared text-record iterator.

    Parameters
    ----------
    source : str | Iterable[object]
        The seed data as either the whole text, or an iterable whose every element is one LINE,
        with or without its terminator. An open text stream is such an iterable. A byte object is
        refused, because no character encoding is guessed here.

    Returns
    -------
    Iterator[DecodedCardXref]
        Each record in order, in the published decoded shape. A source with no records yields
        nothing at all.

    Raises
    ------
    LayoutError
        If the source is a byte object, is neither text nor iterable, or produces an element that
        is not a line, or if a field declares a regime a character record cannot hold.
    RecordLengthError
        If a line is longer than the declared record width, if a record holds a character outside
        the single-byte range, or if a line stops inside a field that carries data. The last case
        is the one a short line reaches: a line shorter than the declared width is admitted only
        while the bytes it omits are pad, so a truncation that removes part of the cross-reference
        key or of either identifier is rejected rather than decoded from a padded copy.
    ZonedSpanWidthError
        From the display codec, if an unsigned display field's declared span is not available or the
        descriptor's display geometry is internally inconsistent.
    ZonedDecimalError
        From the display codec, if a display span holds a non-digit in its body or, in a signed
        regime, a low-order byte that is not a valid sign overpunch.
    """
    # WHY : Assumptions: the record cut is DELEGATED and not written again here. That iterator owns
    #   one statement of the text-mode contract for the whole package -- split on the separator,
    #   remove at most one carriage return and separator PER ROW so trailing blanks stay data, drop
    #   the phantom empty piece a text ending in a separator produces, right-pad a short line and
    #   reject an over-long one -- and a second implementation here is exactly the drift this
    #   dependency edge exists to prevent, because two readers stripping terminators slightly
    #   differently both return well-formed records.
    # WHY : Trade-offs: the short-line tolerance is not merely inherited but REQUIRED, while an
    #   over-long line is rejected rather than truncated, and the asymmetry is deliberate. Every
    #   ASCII row of this dataset is thirty-six characters against a declared fifty, so every row
    #   engages the tolerance and the characters it adds are exactly the pad the conversion dropped;
    #   a long line instead carries bytes the layout does not account for, so trimming it would
    #   discard real data and misalign every field after the cut while still decoding to plausible
    #   characters. One direction is recoverable and one is a defect. `min_data_width` bounds the
    #   tolerance at the last published field's end, compared against the SOURCE line before any
    #   padding, so a row short enough for the pad to reach an identifier is still refused.
    # WHY : Alternatives Considered: strict rejection of every short row, which is what the
    #   reference suite's record helper does, would make the seed this repository ships unloadable
    #   -- not a defensible outcome for a migration whose job is to load it. Declaring a second,
    #   narrower layout matching the seed's observed width was also rejected: it would put a rival
    #   set of offsets in this module, free to drift, at which point the ASCII and EBCDIC paths
    #   would decode the same record differently while both looked correct.
    records = iter_ascii_text_records(
        source, XREF_LAYOUT.reclen, min_data_width=_DATA_REGION_WIDTH, layout=XREF_LAYOUT
    )

    for number, record in enumerate(records, start=1):
        yield decode_ascii_card_xref(record, number=number)


def read_ascii_card_xrefs(path: pathlib.Path) -> Iterator[DecodedCardXref]:
    """Stream the card cross-reference from an ASCII seed file at an explicit path.

    Purpose
    -------
    Open one named seed file, stream its records through the shared text-record contract, and
    close it when the caller stops reading.

    Parameters
    ----------
    path : pathlib.Path
        The exact seed file to read. The caller names the file; this function never searches a
        directory for it.

    Returns
    -------
    Iterator[DecodedCardXref]
        Each record in order, in the published decoded shape. A zero-byte file yields nothing and
        is not an error.

    Raises
    ------
    OSError
        If the path cannot be opened or read.
    LayoutError
        If the file produces an element that is not a line, or a field declares a regime a
        character record cannot hold.
    RecordLengthError
        If a line is longer than the declared record width, if a record holds a character outside
        the single-byte range, or if a line stops inside a field that carries data. The last case
        is the one a short line reaches: a line shorter than the declared width is admitted only
        while the bytes it omits are pad, so a truncation that removes part of the cross-reference
        key or of either identifier is rejected rather than decoded from a padded copy.
    ZonedSpanWidthError
        From the display codec, if an unsigned display field's declared span is not available or the
        descriptor's display geometry is internally inconsistent.
    ZonedDecimalError
        From the display codec, if a display span holds a non-digit in its body or, in a signed
        regime, a low-order byte that is not a valid sign overpunch.
    """
    # WHY : Assumptions: the caller supplies an EXPLICIT file and this function never globs a
    #   directory to find one. The seed directories make that concrete: they hold a zero-byte
    #   placeholder and, in the EBCDIC tree, names differing by a single character, so a pattern
    #   match would either sweep the placeholder in or load a dataset twice -- and a doubled image
    #   still divides by the record length with remainder zero, so nothing downstream would catch
    #   it and every money total would come out doubled.
    # WHY : Trade-offs: the open is DELEGATED to `readers.source.iter_seed_lines`, which opens with
    #   O_NONBLOCK, proves the descriptor is a regular file with fstat before a byte is read, and
    #   bounds each line by the declared width plus its terminators. A plain `Path.open` blocks, so
    #   a named pipe named where a seed file was expected would wait indefinitely and with no
    #   diagnostic in a step an operator is watching; and iterating a text handle reads to the next
    #   separator unbounded, so a file whose first separator lies far past the record length is
    #   materialised in full before any width check can refuse it. The code page and the terminator
    #   policy move with the open, so this module names neither -- eight modules each naming them
    #   is eight chances to disagree invisibly. The accepted cost is one more module to read to see
    #   how a file is opened.
    yield from iter_ascii_card_xrefs(iter_seed_lines(path, XREF_LAYOUT.reclen))


def decode_ebcdic_card_xref(
    record: bytes | bytearray | memoryview,
) -> DecodedCardXref:
    """Decode one card cross-reference record from the EBCDIC dataset form.

    Purpose
    -------
    Turn a single fixed-length record image into the published decoded shape, delegating the
    per-field character conversion and the digit proof to the EBCDIC record codec.

    Parameters
    ----------
    record : bytes | bytearray | memoryview
        One whole record image of exactly the declared record length, read in binary mode. A
        ``str`` is refused by the codec, because character data has already lost the byte values
        a sign overpunch depends on.

    Returns
    -------
    DecodedCardXref
        One entry per data field, keyed by the field name exactly as the copybook spells it, in
        declaration order, with the pad dropped. The shape is identical to the ASCII path's,
        which is what makes the two corpora comparable.

    Raises
    ------
    TypeError
        If the record is a ``str`` or is not a one-dimensional image of single bytes.
    EbcdicRecordLengthError
        If the record is not exactly the declared record length. This is a record-length error,
        so a caller guarding either encoding may catch the shared base type.
    EbcdicFieldDecodeError
        If a span does not decode to exactly one character per byte and re-encode to those same
        bytes.
    LayoutError
        If a published field decoded to raw bytes, which means the descriptor has acquired a
        computational or mixed-regime area.
    ZonedSpanWidthError
        From the display codec, if an unsigned display field's declared span is not available or the
        descriptor's display geometry is internally inconsistent.
    ZonedDecimalError
        From the display codec, if a display span holds a non-digit in its body or, in a signed
        regime, a low-order byte that is not a valid sign overpunch.
    """
    # WHY : Assumptions: the conversion is DELEGATED per field and never performed on the record
    #   as a whole. That codec decodes each declared span on its own, dispatching on the
    #   descriptor's regime BEFORE any character set is applied, so no span of packed nibbles or
    #   low-value padding is ever handed to a character decoder. Decoding a whole record through a
    #   code page is the single most likely mistake on this path and the most damaging: it
    #   succeeds, preserves the declared width, and yields a record that looks almost right.
    return _project_decoded_fields(decode_record(record, XREF_LAYOUT))


def iter_ebcdic_card_xrefs(
    source: pathlib.PurePath | bytes | bytearray | memoryview | Iterable[object],
) -> Iterator[DecodedCardXref]:
    """Decode the EBCDIC dataset form of the card cross-reference, one record at a time.

    Purpose
    -------
    Stream decoded records from a fixed-length dataset, cut strictly on the record length the
    layout declares.

    Parameters
    ----------
    source : pathlib.PurePath | bytes | bytearray | memoryview | Iterable[object]
        The dataset, in one of four shapes: a path, which the codec opens read-only in binary
        mode, streams and closes; a whole byte image the caller already holds; an open binary
        stream, read strictly forward and neither seeked nor closed; or an iterable of byte pieces
        whose boundaries may fall anywhere. A ``str`` is refused.

    Returns
    -------
    Iterator[DecodedCardXref]
        Each record in order, in the published decoded shape. A dataset with no bytes yields
        nothing at all.

    Raises
    ------
    TypeError
        If the source is a ``str``, which is ambiguous between a dataset location and character
        data somebody has already decoded.
    EbcdicRecordLengthError
        If the dataset does not divide into whole records of the declared length.
    EbcdicFieldDecodeError
        If a span does not decode to exactly one character per byte.
    LayoutError
        If the source is neither a byte image nor readable nor iterable, produces a piece that is
        not a byte object, or a published field decoded to raw bytes.
    ZonedSpanWidthError
        From the display codec, if an unsigned display field's declared span is not available or the
        descriptor's display geometry is internally inconsistent.
    ZonedDecimalError
        From the display codec, if a display span holds a non-digit in its body or, in a signed
        regime, a low-order byte that is not a valid sign overpunch.
    """
    # WHY : Assumptions: the dataset is cut on the declared record length ALONE, and no line
    #   terminator is looked for, honoured, stripped or padded on this path. A fixed-length
    #   blocked dataset carries no terminators, so a byte that happens to equal a newline is field
    #   data -- a low-order digit, a packed nibble pair or a pad byte -- and splitting on it would
    #   produce pieces of wildly differing lengths, most cut through the middle of a field. The
    #   correctness test for this dataset is that its size divides by the declared record length
    #   with no remainder, which the delegated iterator checks before it yields the first record.
    #   For the committed extract that division is exact -- its 2500 bytes are fifty records of
    #   the declared fifty -- so this corpus needs no tolerance of any kind.
    # WHY : Assumptions: the text form's tolerances must never reach here -- the pad bound is
    #   deliberately an ASCII-path concern only. The EBCDIC extract already carries the trailing
    #   pad, so there is nothing to restore, and right-padding a short piece or stripping a
    #   trailing byte would turn a genuine length failure into a plausible record. That is why this
    #   path reaches a different entry point of the layouts module and shares no code with the text
    #   one.
    for record in iter_ebcdic_records(source, XREF_LAYOUT):
        yield decode_ebcdic_card_xref(record)


def read_ebcdic_card_xrefs(path: pathlib.Path) -> Iterator[DecodedCardXref]:
    """Stream the card cross-reference from an EBCDIC dataset file at an explicit path.

    Purpose
    -------
    Read one named fixed-length dataset and stream its decoded records, with the size validated
    against the declared record length before the first record is produced.

    Parameters
    ----------
    path : pathlib.Path
        The exact dataset file to read. The caller names the file; this function never searches a
        directory for it.

    Returns
    -------
    Iterator[DecodedCardXref]
        Each record in order, in the published decoded shape. A zero-byte file yields nothing and
        is not an error.

    Raises
    ------
    OSError
        If the path cannot be inspected or opened.
    EbcdicRecordLengthError
        If the file size does not divide into whole records of the declared length.
    EbcdicFieldDecodeError
        If a span does not decode to exactly one character per byte.
    LayoutError
        If a published field decoded to raw bytes.
    ZonedSpanWidthError
        From the display codec, if an unsigned display field's declared span is not available or the
        descriptor's display geometry is internally inconsistent.
    ZonedDecimalError
        From the display codec, if a display span holds a non-digit in its body or, in a signed
        regime, a low-order byte that is not a valid sign overpunch.
    """
    # WHY : Alternatives Considered: the path is handed to the codec rather than opened here and
    #   passed as a stream. The codec validates the file size against the declared record length
    #   BEFORE yielding a first record, so a truncated dataset fails up front instead of part way
    #   through a load, and it owns the open, the forward-only read and the close. Opening the file
    #   here would duplicate that lifecycle for no gain.
    yield from iter_ebcdic_card_xrefs(pathlib.PurePath(path))


def render_masked_card_xref_record(record: str) -> str:
    """Render one character record with every sensitive field redacted.

    Purpose
    -------
    Give an operator a privacy-safe, byte-aligned rendering of a whole record, so a failed
    comparison can show a record's shape and which field differs without emitting content whose
    sensitivity the reader cannot judge.

    Parameters
    ----------
    record : str
        One whole record at exactly the declared character width.

    Returns
    -------
    str
        A rendering of exactly the declared width, with each sensitive field replaced by a
        same-width redaction and every other field left verbatim, so offsets can still be counted
        across it.

    Raises
    ------
    RecordLengthError
        If the record is not the declared width. Raised by the shared masking helper, whose width
        check is the reason this rendering can be relied on to stay byte-aligned.
    LayoutError
        If a field slice comes out the wrong width, which the descriptor's own geometry
        validation makes unreachable.
    """
    # WHY : Assumptions: the redaction is delegated to the shared helper, so marking a field
    #   sensitive in the layout remains the ONLY change ever needed to redact it here. Every data
    #   field of this record is protected, so the rendering is entirely tags plus the pad -- which
    #   still answers the one question a cross-reference diff asks, namely which of the three joins
    #   differs.
    # WHY : Trade-offs: the mask is SAME-WIDTH and DETERMINISTIC, and the account number keeps its
    #   trailing four digits -- enough to locate a record in a diff, not enough to reconstruct a
    #   payment instrument. Those two properties are what keep the rendering diagnostically useful:
    #   offsets stay countable and equal stored characters always render equally, so a masked diff
    #   still shows WHICH field changed. A random mask would satisfy the privacy requirement and
    #   destroy both. The accepted cost is that identical values are identifiable as identical.
    return mask_record(record, XREF_LAYOUT)


def render_masked_card_xref_field(record: str, field_name: str) -> str:
    """Render one named field of a character record with redaction applied.

    Purpose
    -------
    Produce a privacy-safe rendering of a single field, for a diagnostic that needs to show one
    field rather than a whole record.

    Parameters
    ----------
    record : str
        One whole record at exactly the declared character width.
    field_name : str
        The field name exactly as the copybook spells it, including any baseline misspelling,
        since the descriptor preserves the copybook's own spelling.

    Returns
    -------
    str
        A rendering of exactly that field's declared width: the characters verbatim when the field
        is not sensitive, otherwise a same-width redaction.

    Raises
    ------
    LayoutError
        If no field of that name is declared, or the sliced span is not the field's declared
        width, which happens when the record is short.
    """
    # WHY : Assumptions: the field is resolved through the layout by name and then sliced by its
    #   own declared span, so this module still states no offset of its own and an unknown name
    #   fails loudly here rather than silently rendering the wrong bytes. Resolution is scoped to
    #   THIS record's descriptor rather than to a package-wide field table, which is what keeps a
    #   name declared over different children in a sibling copybook from resolving to the wrong
    #   geometry.
    field = XREF_LAYOUT.field(field_name)
    return mask_field(field, record[field.start : field.end])
