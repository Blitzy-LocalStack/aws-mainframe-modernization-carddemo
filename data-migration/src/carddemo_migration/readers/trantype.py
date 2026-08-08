"""Streaming reader for the CardDemo transaction type reference, in both shipped encodings.

Purpose
-------
Turn the transaction type reference extract into decoded records the Aurora loaders and the
verification passes can consume, one record at a time, from either of the two forms the baseline
ships: the line-oriented ASCII seed and the fixed-length EBCDIC dataset. Both entry points yield
the same decoded shape, so a caller can swap corpora and compare the two without adapting to a
second contract.

This module follows the contract ``carddemo_migration.readers.account`` established and differs
from it only in which record descriptor it names. Where the reasoning behind a step is identical
to that module's it is referenced rather than restated, because two copies of one justification
drift apart and then one of them is wrong; where this record differs, the difference is recorded
here at the point it matters.

What this module reads
----------------------
The record is ``TRAN-TYPE-RECORD`` as declared in ``app/cpy/CVTRA03Y.cpy``, and its byte geometry
is resolved exclusively through ``TRANTYPE_LAYOUT`` in ``carddemo_migration.copybook.layouts``.
The two shipped corpora are ``app/data/ASCII/trantype.txt`` and
``app/data/EBCDIC/AWS.M2.CARDDEMO.TRANTYPE.PS``, seven records each. Both are REFERENCE-only
inputs, opened read-only.

This record has no second implementation to be checked against
--------------------------------------------------------------
Assumptions: ``CVTRA03Y`` is one of the THREE base masters the parity oracle's codec does
not register, so unlike most readers in this subpackage this one has no independently
written Python implementation of the same geometry to disagree with it. The oracle's
``tests/helpers/record_codec.py`` transcribes eight copybooks -- ``CVACT01Y``, ``CVTRA06Y``,
``CVTRA02Y``, ``CVACT03Y``, ``CVTRA01Y``, ``CVACT02Y``, ``CVCUS01Y`` and ``CVTRA05Y`` -- and
this record's is not among them; the other two absentees are ``CVTRA04Y`` and ``CSUSR01Y``.
The registry does not leave that to be discovered:
:func:`carddemo_migration.copybook.layouts.has_oracle_round_trip` answers ``False`` for this
record, which is a statement about the ORACLE's coverage and not about this layout's
correctness.

Assumptions: what replaces the missing vector is an exact division. The EBCDIC extract is
420 bytes and the declared record length is 60, so it holds ``420 / 60 = 7`` records with
remainder ZERO -- a remainder of anything else would mean the declared width is wrong, and
the ASCII seed independently agrees at seven rows. That arithmetic is the whole of the
independent corroboration available here, which is why the geometry is imported from the
descriptor rather than restated: a transcription error in this record cannot surface as a
decode failure, only as wrong reference data in a table other rows point at.

Assumptions: do not read the oracle's ELEVEN registry keys as covering the eleven base
masters. They are the eight transcribed copybooks above plus three DERIVED layouts --
the statement view, the reject stream and the interest transaction -- so the two counts
coincide by accident. Equating them is exactly how the three absent masters come to look
covered, and this record is one of the three.

Assumptions: this record is the parent of the transaction-category reference, and the
target preserves that with a foreign key declared ``ON DELETE RESTRICT`` -- the same
semantic the baseline's Db2 constraint asserts. The relationship is not enforced here,
because a reader decodes one record and knows nothing of another table's rows; it is
stated so a loader ordering its inserts reads it before it needs it. What that inheritance
costs THIS module is the two-character key: the child table's integrity rests on it, so a
key decoded from the wrong offset would not fail here but would orphan or misparent rows
one table away.

Ragged line terminators, and why the rule has to be per row
-----------------------------------------------------------
Assumptions: this seed is one of the three whose line terminators are RAGGED. Six of its
seven rows end in a carriage return and a separator and the seventh ends in the separator
alone, which is what its byte count states exactly: ``6 x (60 + 2) + 1 x (60 + 1) = 433``.
That is why no terminator handling appears in this module at all -- the shared text
iterator strips at most one carriage return and one separator PER ROW, testing the pair
before either single character, in one place. A reader trimming for itself would either
leave a carriage return inside the pad on six rows or strip a real character from the
seventh.

Assumptions: no whole-file newline mode is correct across the three ragged seeds, so the
per-row rule is a requirement rather than a defensive habit. The sibling
``trancatg.txt`` is uniformly carriage-return-and-separator INCLUDING its final row, where
this file's final row is bare; a mode chosen to suit either one mis-handles the other.
This module therefore states no terminator policy of its own and inherits the one the
layouts module owns.

Decoded shape
-------------
A decoded record is a ``dict`` keyed by the field name exactly as the copybook spells it, in
declaration order, which is the record's byte order. Every field of this record is a character
field, so every decoded value is its characters at full declared width, untrimmed -- a trailing
blank inside the description is data and is not stripped here. The trailing pad is absent; see
:data:`DROPPED_FIELD_NAMES`.

That shape is deliberately the one ``carddemo_migration.copybook.ebcdic_codec.decode_record``
publishes, minus the pad, which is what makes the two encodings comparable rather than merely
similar.

No money path and no sensitive field
------------------------------------
Both absences are stated outright, because both are things a reader of this module will
otherwise go looking for -- almost every sibling reader has at least one of them.

Assumptions: this record declares NO money field, and no numeric field of any kind. All
three of its declared fields are character fields, which puts it in a set of exactly two
registered layouts -- itself and the security record of ``CSUSR01Y`` -- whose every field
is character data. So this module imports no numeric codec, publishes no
:class:`decimal.Decimal`, and has no money path to get wrong, and there is correspondingly
no binary floating-point value anywhere in it. The package-wide prohibition on ``float`` in
a money path is honoured here trivially rather than carefully, and it is named only so that
its absence reads as the uniform rule holding rather than as this record having been
exempted from it.

Assumptions: NO field of this record is marked sensitive, and that is a deliberate
classification rather than an omission. A two-character type code and its description are
closed-domain reference values shared across every account and every transaction, so
neither designates anybody and neither reveals anything about a particular cardholder. This
is also what separates this record from the other all-character layout, which withholds its
password field entirely: this reader suppresses nothing and publishes no
``SUPPRESSED_FIELD_NAMES``, because every field it decodes has a destination. The rendering
helpers below are still published and still route through the shared masking functions, so
marking a field sensitive in the layout would redact it here with no change to this module.

Design decisions (WHY)
----------------------
Assumptions:
    **Every offset, length, storage regime and record length is imported, never declared.**
    This module states no byte position of its own, and in particular states no key width: the
    2-byte key is read from the descriptor's own ``key_offset`` and ``key_length``. That is
    the Python analogue of compiling every COBOL program against a single ``cobc -I app/cpy``
    include path. The consequence of breaking it is specific and undetectable -- a re-declared
    offset lets this reader and a sibling reading the same bytes drift apart, and a record read
    one byte out of alignment still decodes to plausible characters, so nothing raises and no
    test fails.
Trade-offs:
    **Records are streamed, never materialised.** Both entry points are generators, so memory is
    constant in the record count. The accepted cost is a single forward pass; what it buys is
    that this reader behaves identically on the committed seed and on a production extract many
    orders larger, so the one proven against the seed is the one that runs.
Assumptions:
    **The two-character key stays a character string and is never narrowed to an integer.**
    Its declared picture clause is alphanumeric, so the domain is whatever two characters the
    reference data holds rather than the numbers ``1`` through ``7`` the shipped seed happens
    to spell. Parsing it as an integer would both discard the significant leading zero that
    makes ``'01'`` the key the child table points at and reject a future non-numeric code the
    copybook already permits, and the resulting key would still look like a key at the point a
    loader upserts on it. See also the money note above: this record has no numeric field at
    all, so no value in it is ever converted to a number here.
Trade-offs:
    **Standard library plus ``carddemo_migration.copybook``, and nothing else.** No database
    driver, no AWS SDK and no character-set package is imported here, so this reader imports and
    runs on a bare checkout with no credential configured. Choosing a dataset, opening a
    connection and writing rows belong to a loader.
"""

from __future__ import annotations

import pathlib
from collections.abc import Iterable, Iterator, Mapping
from typing import Final

# WHY : Assumptions: these imports are absolute and rooted at the distribution package rather
#   than relative, and the record descriptor named below is the ONLY statement of this record's
#   geometry anywhere in the package. That is the Python analogue of compiling every COBOL
#   program against a single `cobc -I app/cpy` include path, and the reference suite's own guide
#   states the same rule for its COBOL unit tests: never duplicate a layout, keep it
#   single-sourced. A relative import is how the guarantee gets broken quietly -- a module moved
#   between `readers/` and `loaders/` keeps importing successfully but against a different
#   sibling -- and this project's ruff configuration bans relative imports outright for that
#   reason. This record's three fields are the strongest temptation in the subpackage to inline
#   the layout instead, and inlining is precisely how two readers of the same bytes come to
#   disagree.
# WHY : Assumptions: the descriptor is selected by NAME, and a record length could not select it
#   even if this module wanted to. `TRANTYPE_LAYOUT` and its sibling `TRANCAT_LAYOUT` are BOTH 60
#   bytes -- transaction TYPE from `CVTRA03Y` and transaction CATEGORY from `CVTRA04Y` -- so a
#   length-keyed lookup matches both and picks whichever it met first. Picking the wrong one is
#   silent rather than loud: the category record leads with a SIX-byte composite key where this
#   one leads with a two-byte key, so its description begins at offset 6 against this record's 2.
#   Every row would decode to the declared width with its description shifted by four bytes and
#   its key four bytes too long, and no width check anywhere could report it.
from carddemo_migration.copybook.ebcdic_codec import decode_record, iter_ebcdic_records
from carddemo_migration.copybook.layouts import (
    TRANTYPE_LAYOUT,
    FieldSpec,
    Kind,
    LayoutError,
    RecordLengthError,
    iter_ascii_text_records,
    mask_field,
    mask_record,
)

__all__ = [
    "TRANTYPE_LAYOUT",
    "DROPPED_FIELD_NAMES",
    "LOADED_FIELDS",
    "DecodedTransactionType",
    "decode_ascii_transaction_type",
    "decode_ebcdic_transaction_type",
    "iter_ascii_transaction_types",
    "iter_ebcdic_transaction_types",
    "read_ascii_transaction_types",
    "read_ebcdic_transaction_types",
    "record_key",
    "render_masked_transaction_type_field",
    "render_masked_transaction_type_record",
]

# WHY : Assumptions: the decoded value type is `str` alone because every field this record
#   declares is a character field -- it is the only registered layout with no numeric
#   regime at all -- so no decoded value is ever a number, this module has no money path,
#   and it imports no numeric codec. The projection below still accepts the record
#   decoder's wider union, because that is what the decoder is typed to return.
DecodedTransactionType = dict[str, str]

# WHY : Assumptions: the trailing pad is identified by NAME and not by position, and the
#   convention was measured rather than assumed: across the registered layouts every record
#   declares exactly one pad, named either `FILLER` or, where the copybook qualified it, with
#   that word as its final hyphenated component. THIS record's pad is the unqualified form --
#   `CVTRA03Y` line 7 declares a bare `FILLER PIC X(08)` -- so the suffix arm below never fires
#   here; it is retained because the identical rule is applied by every sibling reader and one of
#   them does face a qualified pad, and a rule that differed per reader is one a maintainer would
#   have to check per reader.
# WHY : Trade-offs: matching on the name rather than on the offset keeps this module free of any
#   byte position of its own, which a positional rule would reintroduce for no gain -- the pad is
#   already the last declared field, so a position test would restate offset 52 here and give the
#   descriptor a second place to drift from.
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


# WHY : Assumptions: the pad is DROPPED from every decoded record because its 8 trailing bytes
#   exist to pad the record out to its fixed 60-byte length and carry no data -- the copybook
#   declares them as an unnamed filler and no program reads them. Dropping it is what the
#   migration's copybook-is-normative rule prescribes, and it is a projection decision rather
#   than a decode one: the EBCDIC record decoder deliberately RETURNS the pad, documenting that
#   the drop belongs to the reader mapping a record onto a table. This is that reader and this is
#   where the decision is taken.
# WHY : Trade-offs: both names below are published rather than kept private, which widens this
#   module's surface by two names. What that buys is that the drop is a fact a caller and a
#   verification pass can ASSERT -- a row-count or field-set check can name what it expects to be
#   absent -- instead of a silent omission that would make a decoded record a partial description
#   of the bytes it came from, indistinguishable from a field the reader simply failed to decode.
LOADED_FIELDS: Final[tuple[FieldSpec, ...]] = tuple(
    field for field in TRANTYPE_LAYOUT.fields if not _is_padding_field(field)
)
DROPPED_FIELD_NAMES: Final[frozenset[str]] = frozenset(
    field.name for field in TRANTYPE_LAYOUT.fields if _is_padding_field(field)
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
        The descriptor whose half-open span contains ``offset``, or ``None`` when the offset lies
        beyond the declared record; the fields cover the record contiguously, so ``None`` means
        the offset itself is out of range.

    Raises
    ------
    None
    """
    for field in TRANTYPE_LAYOUT.fields:
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
    #   desynchronises every offset after it -- and because a record read one byte out of
    #   alignment still decodes to plausible characters, nothing later would raise. This check,
    #   and every other validation in this module, is enforced by an explicit `raise` and never by
    #   an `assert`: running the interpreter with `-O` strips assert statements outright, so an
    #   assertion is a validation that disappears in exactly the deployment where a misaligned
    #   record costs something.
    if record.isascii():
        return record

    offset = next(index for index, char in enumerate(record) if not char.isascii())
    field = _field_containing(offset)

    # WHY : Trade-offs: the message names the record number, the zero-based offset and the
    #   containing field's geometry, and it quotes NO part of the record -- not the offending
    #   character and not its code point. That shows exactly WHERE the record failed while
    #   emitting none of its content, so the diagnostic is safe to log wherever its consumer
    #   sends it. The reference codec does echo the offending character; the stricter form is
    #   adopted here because a reader cannot know which fields of a future
    #   record are sensitive, and a diagnostic cannot be un-logged; the uniform rule
    #   across every reader is cheaper to hold than a per-record judgement.
    location = "beyond the declared record" if field is None else f"in field {field.describe()}"
    raise RecordLengthError(
        f"record {number} of {TRANTYPE_LAYOUT.name} holds a character outside the single-byte"
        f" range at zero-based offset {offset}, {location}; a multi-byte character satisfies"
        f" the {TRANTYPE_LAYOUT.reclen}-character width check while occupying more bytes, which"
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
        The field's characters at full declared width, untrimmed, so trailing pad inside a
        character field stays data.

    Raises
    ------
    LayoutError
        If the field declares a storage regime that cannot occur in a character record, which is
        any computational or mixed-regime area.
    """
    if field.kind is Kind.TEXT:
        return record[field.start : field.end]

    # WHY : Assumptions: every regime this record declares is named explicitly above and anything
    #   else raises, rather than the last branch doubling as a default. A computational or
    #   mixed-regime area cannot be read from a character record at all: its bytes are packed
    #   nibbles, machine words or a differently-described overlay, and a character decode of them
    #   succeeds, keeps the declared width and yields plausible text, so the damage would be
    #   invisible. Raising here also means a regime added to this record later cannot fall
    #   silently into whichever branch happens to be last.
    raise LayoutError(
        f"field {field.describe()} of record {TRANTYPE_LAYOUT.name} declares a storage regime"
        " that cannot be decoded from a character record; only the character and display regimes"
        " can, and a computational or mixed-regime area must be read from the byte image through"
        " the EBCDIC record decoder"
    )


def _project_decoded_fields(
    values: Mapping[str, str | bytes],
) -> DecodedTransactionType:
    """Drop the trailing pad from a fully decoded record.

    Purpose
    -------
    Apply the projection that separates the bytes on disk from the columns a row carries, so both
    encodings converge on the same published shape, and fail closed on a value the published
    shape cannot hold.

    Parameters
    ----------
    values : Mapping[str, str | bytes]
        Every declared field of one record, keyed by field name, including the pad. The parameter
        admits the record decoder's own wider value union.

    Returns
    -------
    DecodedTransactionType
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
    projected: DecodedTransactionType = {}
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
                f"field {TRANTYPE_LAYOUT.field(name).describe()} of record"
                f" {TRANTYPE_LAYOUT.name} decoded to raw bytes rather than a published value, so"
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
        If the record is shorter than the declared width, which would make the sliced key short.
    """
    # WHY : Assumptions: the key is sliced by `key_offset` and `key_length` from the descriptor,
    #   never by a literal. Writing the width here would be a second statement of it, and a key
    #   sliced one character short still looks like a key -- it collides with a sibling record
    #   instead of raising, which a loader would resolve as an upsert onto the wrong row.
    if len(record) < TRANTYPE_LAYOUT.reclen:
        raise RecordLengthError(
            f"a {TRANTYPE_LAYOUT.name} record of {len(record)} characters is shorter than the"
            f" declared {TRANTYPE_LAYOUT.reclen}, so its"
            f" {TRANTYPE_LAYOUT.key_length}-character key cannot be sliced"
        )
    start = TRANTYPE_LAYOUT.key_offset
    return record[start : start + TRANTYPE_LAYOUT.key_length]


def decode_ascii_transaction_type(
    record: str,
    *,
    number: int = 1,
) -> DecodedTransactionType:
    """Decode one transaction type record from the ASCII seed form.

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
    DecodedTransactionType
        One entry per data field, keyed by the field name exactly as the copybook spells it, in
        declaration order. The pad is absent.

    Raises
    ------
    RecordLengthError
        If the record is not the declared width, or holds a character outside the single-byte
        range.
    LayoutError
        If a declared field's storage regime cannot be decoded from a character record.
    """
    # WHY : Trade-offs: a record of the WRONG width is rejected here rather than padded or cut to
    #   fit. Truncation would silently discard real data and padding would invent it, and either
    #   way the row would still decode to well-formed characters, so a wrong-width record would
    #   load under a misread key with nothing reporting it. The accepted cost is that a genuinely
    #   malformed source stops the load instead of loading partially.
    if len(record) != TRANTYPE_LAYOUT.reclen:
        raise RecordLengthError(
            f"record {number} of {TRANTYPE_LAYOUT.name} is {len(record)} characters against a"
            f" declared width of {TRANTYPE_LAYOUT.reclen}; a record of the wrong width means the"
            " field offsets have moved, so it is rejected rather than padded or truncated"
        )

    checked = _require_single_byte_record(record, number)

    # WHY : Assumptions: the fields are walked in the descriptor's declaration order, which is the
    #   record's byte order, so the resulting mapping iterates the record left to right. The pad
    #   is excluded by iterating the published field tuple rather than by decoding every field and
    #   filtering afterwards, which also avoids decoding 8 bytes of pad on every record.
    return {field.name: _decode_text_field_value(checked, field) for field in LOADED_FIELDS}


def iter_ascii_transaction_types(
    source: str | Iterable[object],
) -> Iterator[DecodedTransactionType]:
    """Decode the ASCII seed form of the transaction type reference, one record at a time.

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
    Iterator[DecodedTransactionType]
        Each record in order, in the published decoded shape. A source with no records yields
        nothing at all.

    Raises
    ------
    LayoutError
        If the source is a byte object, is neither text nor iterable, or produces an element that
        is not a line, or if a field declares a regime a character record cannot hold.
    RecordLengthError
        If a line is longer than the declared record width, or a record holds a character outside
        the single-byte range.
    """
    # WHY : Assumptions: the record cut is DELEGATED and not written again here. That iterator
    #   owns one statement of the text-mode contract for the whole package: it splits on the
    #   separator, removes at most one trailing carriage return and separator per row so trailing
    #   blanks stay data, drops the single phantom empty piece a text ending in a separator
    #   produces, right-pads a short line, and REJECTS an over-long one. A second implementation
    #   here is exactly the drift this dependency edge exists to prevent, and it would be
    #   invisible: two readers stripping terminators slightly differently both return well-formed
    #   records.
    # WHY : Trade-offs: that iterator's tolerance for a SHORT line -- right-padding it with
    #   blanks -- is inherited deliberately rather than overridden. Measured rather than assumed:
    #   every row of THIS seed is already the full 60 characters once the per-row strip has run,
    #   including the bare-separator seventh row, so the tolerance never engages here at all. It
    #   exists because one shipped seed conversion lost its trailing pad entirely, and padding on
    #   the right cannot move a field that is present. Overriding it for this reader would fork
    #   the text-mode contract for one record and leave the two readers of the ragged seeds
    #   disagreeing about a case neither of them actually hits.
    records = iter_ascii_text_records(source, TRANTYPE_LAYOUT.reclen)

    # WHY : Trade-offs: records are YIELDED one at a time rather than collected, so memory is
    #   constant in the record count. That is plainly unnecessary for a seven-record reference
    #   table, and it is done anyway for two reasons: it makes this reader behave identically on
    #   the committed seed and on a production reference extract of any size, so the one proven
    #   against the seed is the one that runs; and it keeps all twelve readers a single shape, so
    #   a caller can compose them without checking which of them returns a list. The accepted cost
    #   is a single forward pass -- a caller wanting a second reading must re-open the source.
    for number, record in enumerate(records, start=1):
        yield decode_ascii_transaction_type(record, number=number)


def read_ascii_transaction_types(path: pathlib.Path) -> Iterator[DecodedTransactionType]:
    """Stream the transaction type reference from an ASCII seed file at an explicit path.

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
    Iterator[DecodedTransactionType]
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
        If a line is longer than the declared record width, or a record holds a character outside
        the single-byte range.
    """
    # WHY : Assumptions: the caller supplies an EXPLICIT file and this function never globs a
    #   directory to find one. The seed directories make that concrete: they hold a zero-byte
    #   placeholder and, in the EBCDIC tree, names differing by a single character, so a pattern
    #   match would either sweep the placeholder in or load a dataset twice -- and a doubled image
    #   still divides by the record length with remainder zero, so nothing downstream would catch
    #   it and every money total would come out doubled.
    # WHY : Alternatives Considered: the file is decoded through a single-byte code page that is
    #   total over all 256 byte values rather than through a strict ASCII decode. Both reject a
    #   non-conforming file but differ in WHERE: a strict decode fails inside the interpreter's
    #   reader with an untyped encoding error, which would make the single-byte guard above
    #   unreachable, whereas a total page maps each byte to one character so the failure surfaces
    #   as this package's own record-length error naming the offset.
    # WHY : Assumptions: line splitting is pinned to the separator alone, matching the shared
    #   iterator's own whole-text scanner, so streaming this handle line by line and passing the
    #   whole text produce identical records. Leaving the default in place would let the
    #   interpreter translate and split on a carriage return as well, moving terminator policy out
    #   of the module that owns it -- which matters for this corpus specifically, because three of
    #   the nine ASCII seeds carry carriage returns on some rows and not others.
    with path.open("r", encoding="latin-1", newline="\n") as handle:
        yield from iter_ascii_transaction_types(handle)


def decode_ebcdic_transaction_type(
    record: bytes | bytearray | memoryview,
) -> DecodedTransactionType:
    """Decode one transaction type record from the EBCDIC dataset form.

    Purpose
    -------
    Turn a single fixed-length record image into the published decoded shape, delegating the
    per-field character conversion to the EBCDIC record codec.

    Parameters
    ----------
    record : bytes | bytearray | memoryview
        One whole record image of exactly the declared record length, read in binary mode. A
        ``str`` is refused by the codec, because character data has already lost the byte values
        a sign overpunch depends on.

    Returns
    -------
    DecodedTransactionType
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
    """
    # WHY : Assumptions: the conversion is DELEGATED per field and never performed on the record
    #   as a whole. That codec decodes each declared span on its own, dispatching on the
    #   descriptor's regime BEFORE any character set is applied, so no span of packed nibbles or
    #   low-value padding is ever handed to a character decoder. Decoding a whole record through a
    #   code page is the single most likely mistake on this path and the most damaging: it
    #   succeeds, preserves the declared width, and yields a record that looks almost right.
    return _project_decoded_fields(decode_record(record, TRANTYPE_LAYOUT))


def iter_ebcdic_transaction_types(
    source: pathlib.PurePath | bytes | bytearray | memoryview | Iterable[object],
) -> Iterator[DecodedTransactionType]:
    """Decode the EBCDIC dataset form of the transaction type reference, one record at a time.

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
    Iterator[DecodedTransactionType]
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
    """
    # WHY : Assumptions: the dataset is cut on the declared record length ALONE, and no line
    #   terminator is looked for, honoured, stripped or padded on this path. Measured rather than
    #   assumed: the shipped extract contains no separator byte and no carriage return at all, so a
    #   byte that happened to equal either would be field data -- a low-order digit or a pad byte --
    #   and splitting on it would produce pieces of wildly differing lengths, most cut through the
    #   middle of a field. Carrying the text path's terminator logic here would consume a DATA byte
    #   as a separator.
    # WHY : Assumptions: the correctness test for this dataset is that its size divides by the
    #   declared record length with no remainder -- 420 bytes over a 60-byte record is 7 records
    #   remainder ZERO -- and the delegated iterator checks exactly that before it yields the first
    #   record. For this layout that division carries more weight than it does for its siblings: it
    #   is the substitute for the reference Python vector this record does not have, as the module
    #   docstring records, so it is the one independent check standing between a transcription error
    #   and a silently wrong reference table.
    # WHY : Assumptions: the text form's tolerances must never reach here. Right-padding a short
    #   piece or stripping a trailing byte would turn a genuine length failure into a plausible
    #   record, which is why this path reaches a different entry point of the layouts module and
    #   shares no code with the text one.
    for record in iter_ebcdic_records(source, TRANTYPE_LAYOUT):
        # WHY : Trade-offs: this path streams for the same reason the text path does -- constant
        #   memory at the cost of one forward pass -- and the symmetry is what lets a verification
        #   pass walk the two corpora together record by record, comparing them without either
        #   side being held in memory. Materialising one side only would make the comparison the
        #   asymmetric one, which for this record is the whole available correctness argument.
        yield decode_ebcdic_transaction_type(record)


def read_ebcdic_transaction_types(path: pathlib.Path) -> Iterator[DecodedTransactionType]:
    """Stream the transaction type reference from an EBCDIC dataset file at an explicit path.

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
    Iterator[DecodedTransactionType]
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
    """
    # WHY : Assumptions: the caller supplies an EXPLICIT file and this function never globs the
    #   directory to find one, and the hazard is concrete rather than theoretical:
    #   `app/data/EBCDIC/` holds a ZERO-BYTE `.gitkeep` alongside the datasets, and it also holds
    #   two extracts whose names differ from each other by a single character. A pattern match
    #   would sweep the placeholder in, or read a dataset twice -- and a doubled image still
    #   divides by 60 with remainder zero, so the exact-division check that stands in for this
    #   record's missing reference vector would pass on fourteen records where there are seven.
    # WHY : Assumptions: a zero-byte file is a dataset with NO records and is not an error, so
    #   nothing here treats emptiness as a failure. The delegated iterator yields nothing for it
    #   and this generator ends immediately, which is the correct reading of an extract that ran
    #   and selected no rows.
    # WHY : Alternatives Considered: the path is handed to the codec rather than opened here and
    #   passed as a stream. The codec validates the file size against the declared record length
    #   BEFORE yielding a first record, so a truncated dataset fails up front instead of part way
    #   through a load, and it owns the open, the forward-only read and the close. Opening the file
    #   here would duplicate that lifecycle for no gain.
    yield from iter_ebcdic_transaction_types(pathlib.PurePath(path))


def render_masked_transaction_type_record(record: str) -> str:
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
    # WHY : Assumptions: the redaction is delegated to the shared helper rather than applied here,
    #   so marking a field sensitive in the layout remains the ONLY change ever needed to redact
    #   it in this rendering. No field of this record is protected today, so the
    #   rendering it returns is the record verbatim -- stated plainly rather than left to
    #   be discovered, because the same call in the readers that carry identifiers and
    #   amounts redacts them, so a reader inferring a no-op from this module would draw
    #   exactly the wrong conclusion about those.
    return mask_record(record, TRANTYPE_LAYOUT)


def render_masked_transaction_type_field(record: str, field_name: str) -> str:
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
    field = TRANTYPE_LAYOUT.field(field_name)
    return mask_field(field, record[field.start : field.end])
