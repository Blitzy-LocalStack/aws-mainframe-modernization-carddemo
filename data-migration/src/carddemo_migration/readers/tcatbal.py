"""Streaming reader for the CardDemo transaction category balance, in both shipped encodings.

Purpose
-------
Turn the transaction-category-balance extract into decoded records the Aurora loaders and the
verification passes can consume, one record at a time, from either of the two forms the
baseline ships: the line-oriented ASCII seed and the fixed-length EBCDIC dataset. Both entry
points yield the same decoded shape, so a caller can swap corpora and compare the two without
adapting to a second contract.

This module follows the contract ``carddemo_migration.readers.account`` established, and
differs from it only in which record descriptor it names. Everything specific to this record is
reached through that one descriptor; everything general -- the pad projection, the
byte-per-character guard, the storage-regime dispatch, the privacy-safe rendering -- is
expressed exactly as the pattern-setter expresses it.

What this module reads
---------------------
The record is ``TRAN-CAT-BAL-RECORD`` as declared in ``app/cpy/CVTRA01Y.cpy``, and its byte
geometry is resolved exclusively through ``TCATBAL_LAYOUT`` in
``carddemo_migration.copybook.layouts``. The two shipped corpora are
``app/data/ASCII/tcatbal.txt`` and ``app/data/EBCDIC/AWS.M2.CARDDEMO.TCATBALF.PS``. Both are
REFERENCE-only inputs: this module opens them read-only and never writes, re-encodes or
normalises either one in place -- including the ASCII seed's line endings, which are
deliberately left exactly as shipped because the reader adapts to the data rather than the
reverse.

Decoded shape
-------------
A decoded record is a ``dict`` keyed by the field name exactly as the copybook spells it, in
declaration order, which is the record's byte order. A character field maps to its characters
at full declared width, untrimmed. An unsigned display field maps to its characters too, once
they are proven to be the digits its picture clause requires, so a significant leading zero
survives in the eleven-digit account identifier. The one signed display field maps to an exact
:class:`decimal.Decimal` at the scale its picture clause declares. The trailing pad is absent;
see :data:`DROPPED_FIELD_NAMES`.

That shape is deliberately the one
``carddemo_migration.copybook.ebcdic_codec.decode_record`` publishes, minus the pad, which is
what makes the two encodings comparable rather than merely similar.

This record carries no sensitive field
--------------------------------------
Every field of this record is non-sensitive: the descriptor marks none of the five with the
sensitivity flag, so no masking policy applies here and there is nothing to redact. That is
stated rather than left to be discovered, because a reader who arrived from the card, customer
or statement readers -- where the same rendering helpers suppress a primary account number, a
card verification value and a national identifier -- would otherwise go looking for a policy
this record does not have. The rendering helpers are still published and still route through
the shared masking functions, so marking a field sensitive in the layout remains the only
change ever needed to redact it here.

Design decisions (WHY)
----------------------
Assumptions:
    **Every offset, length, storage regime and record length is imported, never declared.**
    This module states no byte position of its own, and in particular states no composite key
    width: the seventeen-byte key is read from the descriptor's own ``key_offset`` and
    ``key_length`` rather than written down here. That is the Python analogue of compiling
    every COBOL program against a single ``cobc -I app/cpy`` include path, and the reference
    suite's own guide states the same rule for its COBOL unit tests: never duplicate a layout,
    keep it single-sourced from ``app/cpy/``. The consequence of breaking it is specific and
    undetectable -- a re-declared offset lets this reader and a sibling reading the same bytes
    drift apart, and a record read one byte out of alignment still decodes to digits, so
    nothing raises and no test fails.
Assumptions:
    **The descriptor is selected by RECORD name, and the group name is never matched on.**
    ``app/cpy/CVTRA01Y.cpy`` line 5 and ``app/cpy/CVTRA04Y.cpy`` line 5 both declare a group
    literally named ``TRAN-CAT-KEY``, and the two are unrelated: here it spans seventeen bytes
    over ``TRANCAT-ACCT-ID``, ``TRANCAT-TYPE-CD`` and ``TRANCAT-CD``, while there it spans six
    over the different children ``TRAN-TYPE-CD`` and ``TRAN-CAT-CD``. Resolving a layout by
    that shared group name would silently pick the wrong arity -- and a key read at six bytes
    where seventeen were declared misaligns every field after it while still returning
    well-formed digits. This module therefore names ``TCATBAL_LAYOUT``, whose record is 50
    bytes, and shares no key-handling code with ``carddemo_migration.readers.trancatg``, whose
    record is 60. The matching group name is specifically not a reason to share any.
Trade-offs:
    **Records are streamed, never materialised.** Both entry points are generators and neither
    builds a list of rows, so memory is constant in the record count rather than proportional
    to it. The accepted cost is that a caller gets a single forward pass and must re-open the
    source to read it twice. What that buys is that the 2.5 KB committed seed and a production
    extract many orders larger behave identically here, so the reader that was proven against
    the seed is the one that runs against the extract.
Assumptions:
    **Money is exact fixed point and there is no ``float`` in this module.** An IEEE-754
    binary value cannot represent ten cents exactly, so a total accumulated in one drifts from
    the total the baseline computed -- silently, and by an amount that grows with the row
    count. ``TRAN-CAT-BAL`` therefore leaves this module as a :class:`decimal.Decimal` at its
    declared scale of two, and it is precisely one of the columns the money-total verification
    pass aggregates, so an inexact decode here would surface as a parity failure against the
    COBOL baseline rather than as an obvious error.
Trade-offs:
    **Standard library plus ``carddemo_migration.copybook``, and nothing else.** No database
    driver, no AWS SDK and no character-set package is imported here, so this reader imports
    and runs on a bare checkout with no credential configured and can be exercised against
    known-answer vectors in isolation. Choosing a dataset, opening a connection and writing
    rows belong to a loader. The accepted cost is that a convenience such as resolving a
    dataset location cannot live in this module.
"""

from __future__ import annotations

import pathlib
from collections.abc import Iterable, Iterator, Mapping
from decimal import Decimal
from typing import Final

# WHY : Assumptions: these imports are the entire reason this module has a dependency on the
#   copybook package, and they are absolute and rooted at the distribution package rather than
#   relative. A relative import is how the single-sourcing guarantee gets broken quietly: a
#   module moved between `readers/` and `loaders/` keeps importing successfully but against a
#   different sibling, and the project's ruff configuration bans relative imports outright for
#   that reason. The record descriptor named below is the ONLY statement of this record's
#   geometry anywhere in the package, and it is named `TCATBAL_LAYOUT` rather than the
#   similarly-spelled `TRANCAT_LAYOUT`: the two are different records at different lengths, 50
#   against 60, and transposing them decodes every field of one at the offsets of the other.
from carddemo_migration.copybook.ebcdic_codec import decode_record, iter_ebcdic_records
from carddemo_migration.copybook.layouts import (
    TCATBAL_LAYOUT,
    FieldSpec,
    Kind,
    LayoutError,
    RecordLengthError,
    iter_ascii_text_records,
    mask_field,
    mask_record,
)
from carddemo_migration.copybook.zoned import decode_zoned_field
from carddemo_migration.readers.source import data_region_width, iter_seed_lines

__all__ = [
    "COMPOSITE_KEY_FIELD_NAMES",
    "DROPPED_FIELD_NAMES",
    "LOADED_FIELDS",
    "TCATBAL_LAYOUT",
    "DecodedCategoryBalance",
    "composite_key",
    "decode_ascii_category_balance",
    "decode_ebcdic_category_balance",
    "iter_ascii_category_balances",
    "iter_ebcdic_category_balances",
    "read_ascii_category_balances",
    "read_ebcdic_category_balances",
    "render_masked_category_balance_field",
    "render_masked_category_balance_record",
]

# WHY : Assumptions: the decoded value type is a union of exactly two members because this
#   record declares exactly three storage regimes and two of them -- character and unsigned
#   display -- yield characters while the third yields an exact decimal. The union deliberately
#   excludes `bytes`, which the record-oriented EBCDIC decoder can return for a packed, binary
#   or mixed-regime area, because this record declares no such area: its five fields are two
#   character fields, two unsigned display fields and one signed display field, and nothing
#   else. Admitting `bytes` here would oblige every caller to narrow a case that cannot occur.
DecodedCategoryBalance = dict[str, str | Decimal]

# WHY : Assumptions: the trailing pad is identified by NAME and not by position, matching the
#   pattern-setter, and the convention was measured rather than assumed: across all fourteen
#   registered layouts every record declares exactly one pad, named either `FILLER` or, where
#   the copybook qualified it, with that word as its final hyphenated component. Testing the
#   name keeps this module free of any byte position of its own, which a position test would
#   reintroduce.
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


# WHY : Assumptions: the pad is DROPPED from every decoded record because its 22 trailing bytes
#   pad the record out to its fixed 50-byte length and carry no data -- the copybook declares
#   them as an unnamed filler at its line 10 and no program reads them. The EBCDIC record
#   decoder deliberately returns it, stating that dropping it is a projection decision
#   belonging to the reader that maps a record onto a table, so this is that decision and this
#   is where it is taken. Both names below are published rather than kept private so the drop
#   is a fact a caller and a verification pass can assert, instead of a silent omission that
#   would make a decoded record a partial description of the bytes it came from.
LOADED_FIELDS: Final[tuple[FieldSpec, ...]] = tuple(
    field for field in TCATBAL_LAYOUT.fields if not _is_padding_field(field)
)
DROPPED_FIELD_NAMES: Final[frozenset[str]] = frozenset(
    field.name for field in TCATBAL_LAYOUT.fields if _is_padding_field(field)
)


# WHY : Assumptions: the boundary between a value and the trailing pad is DERIVED from
#   the published field tuple and is never written here as a number. Bytes at or beyond it
#   are the pad a text conversion may legitimately have dropped, so supplying them by
#   padding restores what was discarded and changes no published value; bytes BEFORE it
#   belong to a field this reader publishes, so supplying those would not restore anything
#   -- it would invent a value the source never carried and hand a loader a row to key on.
_DATA_REGION_WIDTH: Final[int] = data_region_width(LOADED_FIELDS)

# WHY : Assumptions: the three components of the composite key are derived by asking which
#   declared fields fall inside the descriptor's own key span, rather than by listing their
#   names here. Listing them would be a second statement of the key's composition that could go
#   stale against the descriptor without anything detecting it, and it is exactly the statement
#   the sibling category-reference record would contradict -- its identically-named key group
#   has two components where this one has three. Deriving the membership means the key's arity
#   is always whatever the layout says it is.
COMPOSITE_KEY_FIELD_NAMES: Final[tuple[str, ...]] = tuple(
    field.name
    for field in TCATBAL_LAYOUT.fields
    if field.start >= TCATBAL_LAYOUT.key_offset
    and field.end <= TCATBAL_LAYOUT.key_offset + TCATBAL_LAYOUT.key_length
)


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
        The descriptor whose half-open span contains ``offset``, or ``None`` when the offset
        lies beyond the declared record; the fields cover the record contiguously, so ``None``
        means the offset itself is out of range.

    Raises
    ------
    None
    """
    for field in TCATBAL_LAYOUT.fields:
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
    # WHY : Assumptions: a multi-byte character satisfies a CHARACTER-count check while
    #   occupying more than one byte, so it passes the shared iterator's declared-length test
    #   and then desynchronises every offset after it -- and because a record read one byte out
    #   of alignment still decodes to digits, nothing later would raise. The reference codec
    #   reaches this same conclusion for the same reason and rejects a non-single-byte record
    #   outright. Requiring single-byte characters makes the character count provably equal the
    #   byte count, which is what the offsets assume.
    # WHY : Assumptions: this check, and every other validation in this module, is enforced by
    #   an explicit `raise` and never by an `assert`. Running the interpreter with `-O` strips
    #   assert statements outright, so an assertion is not a validation at all: it is a check
    #   that silently disappears in exactly the deployment where a misaligned money value costs
    #   money, and the load would then succeed while writing wrong balances. The layouts module
    #   states the same rule for the same reason, and the reference codec records it too, so
    #   this reader is consistent with both rather than an exception.
    if record.isascii():
        return record

    offset = next(index for index, char in enumerate(record) if not char.isascii())
    field = _field_containing(offset)

    # WHY : Trade-offs: the message names the record number, the zero-based offset and the
    #   containing field's geometry, and it quotes NO part of the record -- not the offending
    #   character and not its code point. That shows exactly WHERE the record failed while
    #   emitting none of its content, so the diagnostic is safe to log wherever its consumer
    #   sends it. The reference codec does echo the offending character; the Java shared
    #   kernel's codecs deliberately do not, and the stricter of the two forms is adopted here
    #   because a reader cannot know which fields of a future record are sensitive, and a
    #   diagnostic cannot be un-logged.
    location = "beyond the declared record" if field is None else f"in field {field.describe()}"
    raise RecordLengthError(
        f"record {number} of {TCATBAL_LAYOUT.name} holds a character outside the single-byte"
        f" range at zero-based offset {offset}, {location}; a multi-byte character satisfies the"
        f" {TCATBAL_LAYOUT.reclen}-character width check while occupying more bytes, which moves"
        " every field offset after it, so the record is rejected rather than decoded"
    )


def _decode_text_field_value(record: str, field: FieldSpec) -> str | Decimal:
    """Decode one field of a character record, routing it by its declared storage regime.

    Purpose
    -------
    Produce one field's final value from a record that is already characters. Each of the three
    regimes this record declares is named explicitly and routed to the codec that owns it, and
    anything else is refused.

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
        The field's characters at full declared width for a character field, and the same for
        an unsigned display field once its digits are proven; an exact
        :class:`decimal.Decimal` at the field's declared scale for a signed display field.

    Raises
    ------
    LayoutError
        If the field declares a storage regime that cannot occur in a character record, which
        is any computational or mixed-regime area.
    ZonedSpanWidthError
        If the record ends before the field's declared span, or the descriptor's display
        geometry is internally inconsistent. Raised by the display codec.
    ZonedDecimalError
        If a display span violates its contract: a non-digit in the body of either display
        regime, or a low-order byte that is not a valid sign overpunch in a signed one. Raised
        by the display codec.
    """
    if field.kind is Kind.TEXT:
        return record[field.start : field.end]

    if field.kind is Kind.UINT:
        # WHY : Trade-offs: the decimal this returns is DISCARDED and the characters are
        #   returned instead, which costs one parse whose result is thrown away. What it buys is
        #   the content check the picture clause states -- an unsigned display field holding
        #   letters is refused rather than passed through -- while keeping the identifier a
        #   digit string of declared width. An integer would drop a leading zero, and a leading
        #   zero is significant in both of this record's unsigned fields: the eleven-digit
        #   account identifier and the four-digit category code are keys, and `0001` and `1`
        #   are not the same key.
        decode_zoned_field(record, field)
        return record[field.start : field.end]

    if field.kind is Kind.ZONED:
        # WHY : Alternatives Considered: the FIELD-oriented entry point is called rather than
        #   the span-oriented one, which would have meant slicing here and passing the digit
        #   counts and the sign flag as three separate arguments. The field-oriented form
        #   derives all of them from this descriptor and slices by it too, so this module
        #   restates no part of the geometry -- not the offset, not the width, and not the
        #   unsigned regime's `(length, 0, False)` derivation that the span-oriented call would
        #   have forced into the branch above. Passing the descriptor also lets that codec name
        #   the field in its own diagnostics.
        # WHY : Assumptions: the result is an exact decimal at the scale the picture clause
        #   declares and is never converted to a binary floating-point type. A binary float
        #   cannot represent ten cents exactly, so a total accumulated in one drifts from the
        #   total the baseline computed, by an amount that grows with the row count. This is the
        #   record's only money field and it is one of the columns the money-total verification
        #   pass aggregates, so an inexact decode would surface as a parity failure.
        return decode_zoned_field(record, field)

    # WHY : Assumptions: every regime is named explicitly above and anything else raises,
    #   rather than the last branch doubling as a default. A computational or mixed-regime area
    #   cannot be read from a character record at all: its bytes are packed nibbles, machine
    #   words or a differently-described overlay, and a character decode of them succeeds, keeps
    #   the declared width and yields plausible text, so the damage would be invisible. Raising
    #   here also means a regime added to the enumeration later cannot fall silently into
    #   whichever branch happens to be last.
    raise LayoutError(
        f"field {field.describe()} of record {TCATBAL_LAYOUT.name} declares a storage regime"
        " that cannot be decoded from a character record; only character and display regimes"
        " can, and a computational or mixed-regime area must be read from the byte image"
        " through the EBCDIC record decoder"
    )


def _project_decoded_fields(
    values: Mapping[str, str | Decimal | bytes],
) -> DecodedCategoryBalance:
    """Drop the trailing pad from a fully decoded record.

    Purpose
    -------
    Apply the one projection that separates the bytes on disk from the columns a row carries, so
    both encodings converge on the same published shape.

    Parameters
    ----------
    values : Mapping[str, str | Decimal | bytes]
        Every declared field of one record, keyed by field name, including the pad. The
        parameter admits the record decoder's own wider value union; see the note below for why
        the ``bytes`` member cannot occur for this record.

    Returns
    -------
    DecodedCategoryBalance
        The same mapping without any padding field, in declaration order.

    Raises
    ------
    LayoutError
        If a retained field decoded to raw bytes, which means the descriptor has acquired a
        computational or mixed-regime area this reader publishes no representation for.
    """
    # WHY : Refactoring Rationale: an unexpected `bytes` value is now REJECTED where it used to
    #   be filtered out of the result. The filter's justification was that the case is
    #   unreachable -- this record's five fields are two character, two unsigned display and one
    #   signed display, so the record decoder cannot produce bytes for any of them -- and that
    #   was and remains true of today's descriptor. It was still the wrong construction, because
    #   of what happens the moment it stops being true. A descriptor edit that changed one of
    #   these fields to a computational regime, or a decoder change that widened what it returns,
    #   would make the filter silently OMIT a real field, and the reader would hand a loader a
    #   plausible partial row: four keys instead of five, every one of them well-formed. That row
    #   would insert with a NULL where an account identifier or a balance belongs and nothing
    #   would report it. Failing closed converts an invisible data-integrity fault into a named
    #   one, which is the same choice the sibling card reader's narrowing helper makes.
    # WHY : Trade-offs: the pad is dropped BEFORE the type is judged, so a pad that decoded to
    #   bytes cannot raise. That ordering is deliberate: the pad is not published, so its regime
    #   is not this reader's contract to enforce, and raising over a value nobody receives would
    #   turn a harmless descriptor detail into a load failure.
    projected: DecodedCategoryBalance = {}
    for name, value in values.items():
        if name in DROPPED_FIELD_NAMES:
            continue
        if isinstance(value, bytes):
            # WHY : Trade-offs: the rejection names the field's GEOMETRY through the descriptor
            #   and never renders the bytes, not even as a length-bounded excerpt. A
            #   computational span on this record could only be the account identifier or the
            #   balance, both of which the disclosure policy protects, so a diagnostic that
            #   dumped an unexpected span would disclose exactly what the masking helpers exist
            #   to withhold. Naming the field is enough to locate the defect, which is in the
            #   descriptor rather than in the data.
            field = TCATBAL_LAYOUT.field(name)
            raise LayoutError(
                f"field {field.describe()} of record {TCATBAL_LAYOUT.name} decoded to raw bytes"
                " rather than characters or an exact decimal, so it declares a computational or"
                " mixed-regime area; this reader publishes only the character and display"
                " regimes, and such an area must be decoded by the codec that owns its regime"
                " rather than dropped from the row"
            )
        projected[name] = value
    return projected


def composite_key(record: str) -> str:
    """Return the composite primary key of one character record, sliced by the descriptor.

    Purpose
    -------
    Give a caller the record's whole primary key as one string, for the cases that key a row by
    its key rather than by its parts -- an idempotency check across a reload, or a comparison of
    the two corpora keyed on the same value. The three components remain separately available as
    ordinary decoded fields, so this is an addition to the decoded shape and not a replacement
    for it.

    Parameters
    ----------
    record : str
        One whole record at exactly the declared character width, with no line terminator.

    Returns
    -------
    str
        The key's characters at exactly the declared key width, taken verbatim from the record
        so that a leading zero and a trailing blank both survive.

    Raises
    ------
    RecordLengthError
        If the record is not the declared width, in which case the key span would be cut short
        or would read into a neighbouring field.
    """
    # WHY : Trade-offs: the width is re-checked here even though every caller reaching this
    #   through an iterator has already been checked, because this function is published and a
    #   caller may hand it a record it assembled itself. A short record would otherwise yield a
    #   silently truncated key, and a truncated key still looks like a key.
    if len(record) != TCATBAL_LAYOUT.reclen:
        raise RecordLengthError(
            f"record of {TCATBAL_LAYOUT.name} is {len(record)} characters against a declared"
            f" width of {TCATBAL_LAYOUT.reclen}, so its {TCATBAL_LAYOUT.key_length}-byte"
            " composite key cannot be sliced; the record is rejected rather than keyed on a"
            " short span"
        )

    # WHY : Assumptions: the key's offset and width come from the descriptor's `key_offset` and
    #   `key_length` and are never written as literals here. The reader owns no geometry, and a
    #   literal width would be a SECOND declaration of this key that could go stale against the
    #   descriptor with nothing detecting it. The hazard is concrete rather than theoretical:
    #   the sibling category-reference record's identically-named `TRAN-CAT-KEY` group is six
    #   bytes where this one is seventeen, so a literal copied between the two readers decodes
    #   a key that is well-formed and wrong. The external source for this record's key width and
    #   offset is the `KEYS` operand pair in the dataset definition at `app/jcl/TCATBALF.jcl`
    #   lines 40 and 41, and the descriptor is where this package records that pair once.
    start = TCATBAL_LAYOUT.key_offset
    return record[start : start + TCATBAL_LAYOUT.key_length]


def decode_ascii_category_balance(record: str, *, number: int = 1) -> DecodedCategoryBalance:
    """Decode one transaction-category-balance record from the ASCII seed form.

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
    DecodedCategoryBalance
        One entry per data field, keyed by the field name exactly as the copybook spells it, in
        declaration order. Character and unsigned display fields map to characters at full
        declared width; the signed display field maps to an exact :class:`decimal.Decimal` at
        scale two. The pad is absent.

    Raises
    ------
    RecordLengthError
        If the record is not the declared width, or holds a character outside the single-byte
        range.
    LayoutError
        If a declared field's storage regime cannot be decoded from a character record.
    ZonedSpanWidthError
        If a display field's declared span is not available, or its geometry is internally
        inconsistent. Raised by the display codec.
    ZonedDecimalError
        If a display field's content violates its contract. Raised by the display codec.
    """
    # WHY : Trade-offs: a record of the WRONG width is rejected here rather than padded or cut
    #   to fit. Truncation would silently discard real data and padding would invent it, and
    #   either way the row would still decode to well-formed digits, so a wrong-width financial
    #   record would post a misaligned money value with nothing reporting it. The accepted cost
    #   is that a genuinely malformed source stops the load instead of loading partially, which
    #   for a balance master is the outcome to prefer. The asymmetry with the shared iterator's
    #   tolerance for a SHORT line is deliberate: padding on the right cannot move a field that
    #   is present, whereas truncating an over-long row moves every field after the cut.
    if len(record) != TCATBAL_LAYOUT.reclen:
        raise RecordLengthError(
            f"record {number} of {TCATBAL_LAYOUT.name} is {len(record)} characters against a"
            f" declared width of {TCATBAL_LAYOUT.reclen}; a record of the wrong width means the"
            " field offsets have moved, so it is rejected rather than padded or truncated"
        )

    checked = _require_single_byte_record(record, number)

    # WHY : Assumptions: the fields are walked in the descriptor's declaration order, which is
    #   the record's byte order, so the resulting mapping iterates the record left to right. The
    #   pad is excluded by iterating the published data fields rather than by decoding all five
    #   and filtering afterwards, which also avoids decoding 22 bytes of padding on every record.
    return {field.name: _decode_text_field_value(checked, field) for field in LOADED_FIELDS}


def iter_ascii_category_balances(
    source: str | Iterable[object],
) -> Iterator[DecodedCategoryBalance]:
    """Decode the ASCII seed form of the category-balance master, one record at a time.

    Purpose
    -------
    Stream decoded records from character data the caller already holds or is already iterating,
    delegating every record boundary decision to the shared text-record iterator.

    Parameters
    ----------
    source : str | Iterable[object]
        The seed data as either the whole text, or an iterable whose every element is one LINE,
        with or without its terminator. An open text stream is such an iterable. A byte object
        is refused, because no character encoding is guessed here.

    Returns
    -------
    Iterator[DecodedCategoryBalance]
        Each record in order, in the published decoded shape. A source with no records yields
        nothing at all.

    Raises
    ------
    LayoutError
        If the source is a byte object, is neither text nor iterable, or produces an element
        that is not a line, or if a field declares a regime a character record cannot hold.
    RecordLengthError
        If a line is longer than the declared record width, or a record holds a character
        outside the single-byte range.
    ZonedSpanWidthError
        If a display field's declared span is not available. Raised by the display codec.
    ZonedDecimalError
        If a display field's content violates its contract. Raised by the display codec.
    """
    # WHY : Assumptions: the record cut is DELEGATED and not written again here, and this
    #   dataset is the sharpest evidence in the package for why the delegated rule is the one it
    #   is. That iterator strips AT MOST ONE trailing terminator per row, testing the two-byte
    #   sequence before either single byte, and this seed is MIXED: measured at 2599 bytes over
    #   50 rows of 50 data bytes each, 49 rows end in carriage-return-newline and the FINAL row
    #   ends in a bare newline -- 49 x 52 + 1 x 51 = 2599 exactly. A whole-file newline mode
    #   mishandles that last row: splitting the text on the two-byte sequence leaves the final
    #   row carrying its newline, which makes it 51 characters against a declared 50, and the
    #   over-long rule then refuses it, so the entire dataset fails to load rather than loading
    #   wrongly. Stripping only the newline and not the carriage return fails the other 49 rows
    #   the same way. The sibling seeds disagree with each other too -- `trantype.txt` shows the
    #   same six-of-seven pattern while `trancatg.txt` is uniformly two-byte-terminated
    #   INCLUDING its last row -- so no single per-file mode is correct across all three, and
    #   only the per-row rule is. A second implementation here is exactly the drift this
    #   dependency edge exists to prevent, and it would be invisible: two readers stripping
    #   terminators slightly differently both return well-formed records.
    # WHY : Trade-offs: that iterator's tolerance for a SHORT line -- padding it on the right
    #   with blanks -- is inherited deliberately rather than overridden. It exists because one
    #   shipped seed conversion lost its trailing pad entirely, and padding on the right cannot
    #   move a field that is present. Every row of this dataset's seed is already full width, so
    #   the tolerance never engages here, and overriding it would fork the contract for one
    #   reader.
    # WHY : Refactoring Rationale: the record cut is bounded by `_DATA_REGION_WIDTH`, and the bound
    #   closes a data-integrity defect rather than tightening a nicety. The shared iterator
    #   right-pads a short line -- which is what lets a seed whose trailing pad the conversion
    #   dropped be read at all -- and it padded a line of ANY length, so a line that stopped
    #   part-way through a field this reader PUBLISHES was completed with manufactured blanks and
    #   returned as a well-formed record. Nothing raised: the invented characters are
    #   indistinguishable from real ones. The bound is the end of the last published field, derived
    #   from `LOADED_FIELDS` rather than written here, and it is compared against the SOURCE line
    #   before any padding, which is the only place the comparison is exact.
    # WHY : Assumptions: the descriptor is passed for DIAGNOSTICS only, so a refusal can name the
    #   record and the field the line stopped inside. It cannot change which lines are accepted.
    records = iter_ascii_text_records(
        source, TCATBAL_LAYOUT.reclen, min_data_width=_DATA_REGION_WIDTH, layout=TCATBAL_LAYOUT
    )

    # WHY : Trade-offs: records are YIELDED one at a time rather than collected, so memory is
    #   constant in the record count. The cost is a single forward pass -- a caller wanting a
    #   second reading must re-open the source -- and what it buys is that this reader behaves
    #   identically on the small committed seed and on a production extract many orders of
    #   magnitude larger, so the one proven against the seed is the one that runs.
    for number, record in enumerate(records, start=1):
        yield decode_ascii_category_balance(record, number=number)


def read_ascii_category_balances(path: pathlib.Path) -> Iterator[DecodedCategoryBalance]:
    """Stream the category-balance master from an ASCII seed file at an explicit path.

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
    Iterator[DecodedCategoryBalance]
        Each record in order, in the published decoded shape. A zero-byte file yields nothing
        and is not an error.

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
    # WHY : Assumptions: the caller supplies an EXPLICIT file, and this function never globs a
    #   directory to find one. The seed directories make that concrete: the EBCDIC directory
    #   holds a zero-byte placeholder alongside the datasets, and a pattern match over a
    #   directory would sweep it up and, on the sibling text path, would just as readily match a
    #   dataset whose name differs from the intended one by a character. A zero-byte file is
    #   legitimately a dataset with no records and is not an error -- one committed fixture of
    #   this very dataset is exactly that.
    # WHY : Refactoring Rationale: the open is DELEGATED to `readers.source.iter_seed_lines` and
    #   is no longer a `Path.open` here, matching every sibling reader. Two faults closed.
    #   `Path.open` is a BLOCKING open, so a named pipe or a character device named where a
    #   seed file was expected did not fail -- it waited, indefinitely and with no diagnostic,
    #   in a step an operator is watching for a load to finish. And iterating a text handle
    #   reads to the next separator with NO bound at all, so a file whose first separator lies
    #   far past the record length was materialised in full before any width check could refuse
    #   it. The shared reader opens with O_NONBLOCK, proves the descriptor is a regular file
    #   with fstat before a byte is read, and bounds each line by the declared width plus its
    #   terminators.
    # WHY : Trade-offs: the code page and the terminator policy move WITH the open, so this module
    #   no longer names either, and the reasoning it used to state here is now stated once in
    #   `readers.source`: the decode is a single-byte page total over all 256 byte values rather
    #   than a strict one, so the character count the shared iterator checks provably equals the
    #   byte count and a non-conforming file surfaces as this package's own typed record-length
    #   error rather than as an untyped encoding error from inside the interpreter's reader; and
    #   line splitting is pinned to the separator alone so the carriage return stays ATTACHED to
    #   each row and the delegated per-row rule is the code that removes it. Eleven modules each
    #   naming those two decisions was eleven chances to disagree, invisibly -- a reader that
    #   validated a file slightly differently from its siblings still returns well-formed records
    #   for every ordinary input.
    yield from iter_ascii_category_balances(iter_seed_lines(path, TCATBAL_LAYOUT.reclen))


def decode_ebcdic_category_balance(
    record: bytes | bytearray | memoryview,
) -> DecodedCategoryBalance:
    """Decode one transaction-category-balance record from the EBCDIC dataset form.

    Purpose
    -------
    Turn a single fixed-length record image into the published decoded shape, delegating the
    per-field character conversion and the numeric decode to the EBCDIC record codec.

    Parameters
    ----------
    record : bytes | bytearray | memoryview
        One whole record image of exactly the declared record length, read in binary mode. A
        ``str`` is refused by the codec, because character data has already lost the byte values
        a sign overpunch depends on.

    Returns
    -------
    DecodedCategoryBalance
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
    ZonedDecimalError
        If a display span violates its contract. Raised by the display codec.
    """
    # WHY : Assumptions: the conversion is DELEGATED per field and never performed on the record
    #   as a whole. That codec decodes each declared span on its own, so this record's signed
    #   display field has its sign overpunch converted as one field while the numeric decode
    #   stays a separate step. Decoding a whole record through a code page is the single most
    #   likely mistake on this path and the most damaging: it succeeds, preserves the declared
    #   width, and yields a record that looks almost right.
    return _project_decoded_fields(decode_record(record, TCATBAL_LAYOUT))


def iter_ebcdic_category_balances(
    source: pathlib.PurePath | bytes | bytearray | memoryview | Iterable[object],
) -> Iterator[DecodedCategoryBalance]:
    """Decode the EBCDIC dataset form of the category-balance master, one record at a time.

    Purpose
    -------
    Stream decoded records from a fixed-length dataset, cut strictly on the record length the
    layout declares.

    Parameters
    ----------
    source : pathlib.PurePath | bytes | bytearray | memoryview | Iterable[object]
        The dataset, in one of four shapes: a path, which the codec opens read-only in binary
        mode, streams and closes; a whole byte image the caller already holds; an open binary
        stream, read strictly forward and neither seeked nor closed; or an iterable of byte
        pieces whose boundaries may fall anywhere. A ``str`` is refused.

    Returns
    -------
    Iterator[DecodedCategoryBalance]
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
    ZonedDecimalError
        If a display span violates its contract. Raised by the display codec.
    LayoutError
        If the source is neither a byte image nor readable nor iterable, or produces a piece
        that is not a byte object.
    """
    # WHY : Assumptions: the dataset is cut on the declared record length ALONE, and no line
    #   terminator is looked for, honoured, stripped or padded on this path. The EBCDIC form of
    #   this dataset has NO terminators at all -- measured at 2500 bytes containing not one
    #   occurrence of any newline or carriage-return byte in either the ASCII or the EBCDIC
    #   encoding -- so its correctness check is that 2500 divides by the declared 50 with
    #   remainder zero, giving exactly 50 records, and the delegated iterator performs that
    #   division before it yields the first record. Carrying the text path's terminator logic
    #   onto this path would consume a DATA byte as a separator: a low-order digit or a pad byte
    #   that happens to equal a newline would split the image into pieces of wildly differing
    #   lengths, most of them cut through the middle of a field.
    # WHY : Assumptions: the text form's tolerances must never reach here either. Right-padding
    #   a short piece or stripping a trailing byte would turn a genuine length failure into a
    #   plausible record, which is why this path reaches a different entry point of the layouts
    #   module and shares no code with the text one.
    for record in iter_ebcdic_records(source, TCATBAL_LAYOUT):
        # WHY : Trade-offs: records are yielded one at a time for the same reason the text path
        #   streams -- constant memory in the record count, at the cost of one forward pass -- so
        #   a caller can compare the two corpora record by record without either side holding a
        #   dataset in memory.
        yield decode_ebcdic_category_balance(record)


def read_ebcdic_category_balances(path: pathlib.Path) -> Iterator[DecodedCategoryBalance]:
    """Stream the category-balance master from an EBCDIC dataset file at an explicit path.

    Purpose
    -------
    Read one named fixed-length dataset and stream its decoded records, with the size validated
    against the declared record length before the first record is produced.

    Parameters
    ----------
    path : pathlib.Path
        The exact dataset file to read. The caller names the file; this function never searches
        a directory for it.

    Returns
    -------
    Iterator[DecodedCategoryBalance]
        Each record in order, in the published decoded shape. A zero-byte file yields nothing
        and is not an error.

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
    #   concrete rather than theoretical: the EBCDIC directory holds a zero-byte `.gitkeep`
    #   placeholder beside the thirteen datasets, and it also holds a pair of names differing by
    #   a single character. A directory pattern would sweep the placeholder in, and a
    #   near-name match would load a dataset twice -- which nothing downstream would catch,
    #   because a doubled image still divides by this record length with remainder zero, so the
    #   exact-division check passes, every record decodes cleanly, and the only symptom is that
    #   every money total the verification pass aggregates comes out doubled.
    # WHY : Alternatives Considered: the path is handed to the codec rather than opened here and
    #   passed as a stream. The codec validates the file size against the declared record length
    #   BEFORE yielding a first record, so a truncated dataset fails up front instead of part
    #   way through a load, and it owns the open, the forward-only read and the close. Opening
    #   the file here would duplicate that lifecycle for no gain.
    yield from iter_ebcdic_category_balances(pathlib.PurePath(path))


def render_masked_category_balance_record(record: str) -> str:
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
        same-width redaction and every other field left verbatim, so offsets can still be
        counted across it.

    Raises
    ------
    LayoutError
        If the record is not the declared width.
    """
    # WHY : Refactoring Rationale: this comment used to record that the record declared NO
    #   sensitive field, so that the rendering was the record verbatim. That described a defect
    #   rather than justifying one: a function documented as privacy-safe returned the
    #   eleven-digit account identifier and the S9(09)V99 running balance in clear. The fields
    #   are now marked at the layout by _close_master_disclosure, which is the fix this comment
    #   already prescribed.
    # WHY : Assumptions: the account identifier and the running balance are withheld as
    #   same-width keyed redaction tags; the transaction type code and the transaction category
    #   code are left verbatim because the rendering rule admits a type or category code by
    #   name and both are drawn from seeded reference tables, and they are what identify WHICH
    #   category row a diagnostic is describing. Width is unchanged at 50 characters.
    return mask_record(record, TCATBAL_LAYOUT)


def render_masked_category_balance_field(record: str, field_name: str) -> str:
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
        The field name exactly as the copybook spells it. This record's own spellings carry no
        baseline misspelling, but the descriptor preserves the copybook's spelling either way.

    Returns
    -------
    str
        A rendering of exactly that field's declared width: the characters verbatim when the
        field is not sensitive, otherwise a same-width redaction.

    Raises
    ------
    LayoutError
        If no field of that name is declared, or the sliced span is not the field's declared
        width, which happens when the record is short.
    """
    # WHY : Assumptions: the field is resolved through the layout by name and then sliced by its
    #   own declared span, so this module still states no offset of its own and an unknown name
    #   fails loudly here rather than silently rendering the wrong bytes. Resolution is scoped to
    #   THIS record's descriptor rather than to a package-wide field table, which is what keeps
    #   the name `TRAN-CAT-KEY` -- declared over different children at a different width in the
    #   sibling category-reference copybook -- from resolving to the wrong geometry.
    field = TCATBAL_LAYOUT.field(field_name)
    return mask_field(field, record[field.start : field.end])
