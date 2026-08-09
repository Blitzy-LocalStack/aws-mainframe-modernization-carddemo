"""Streaming reader for the CardDemo transaction category reference, in both shipped encodings.

Purpose
-------
Turn the transaction category reference extract into decoded records the Aurora loaders and the
verification passes can consume, one record at a time, from either of the two forms the baseline
ships: the line-oriented ASCII seed and the fixed-length EBCDIC dataset. Both entry points yield
the same decoded shape, so a caller can swap corpora and compare the two without adapting to a
second contract.

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
    from the record descriptor, and nothing else is computed. No dataset is opened, no database
    connection is made, no environment variable is read and no network is reached at import time,
    so importing this module is safe on a bare checkout with no credential configured.

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
----------------------
The record is ``TRAN-CAT-RECORD`` as declared in ``app/cpy/CVTRA04Y.cpy``, and its byte geometry is
resolved exclusively through ``TRANCAT_LAYOUT`` in ``carddemo_migration.copybook.layouts``. The two
shipped corpora are ``app/data/ASCII/trancatg.txt`` and
``app/data/EBCDIC/AWS.M2.CARDDEMO.TRANCATG.PS``, eighteen records each. Both are REFERENCE-only
inputs, opened read-only and never rewritten, re-encoded or normalised in place.

The key group name collides with an unrelated record's, at a different width
----------------------------------------------------------------------------
Assumptions: ``app/cpy/CVTRA04Y.cpy`` line 5 and ``app/cpy/CVTRA01Y.cpy`` line 5 both declare a
group literally named ``TRAN-CAT-KEY``, and the two are unrelated. Here it spans SIX bytes over
``TRAN-TYPE-CD`` and ``TRAN-CAT-CD``; in the category-BALANCE record it spans SEVENTEEN over
``TRANCAT-ACCT-ID``, ``TRANCAT-TYPE-CD`` and ``TRANCAT-CD``. Selecting a descriptor by that group
name would therefore pick the wrong geometry from either side of the pair, and the failure is
silent rather than loud: this record's 50-byte description begins at offset 6, so reading it under
the balance record's key would displace it by ELEVEN bytes and still yield a full-width record of
plausible characters. Descriptors are resolved per RECORD name for exactly this reason, and the
key width below is read from the descriptor rather than written here.

Alternatives Considered: a shared composite-key helper, keyed on the group name and used by both
readers, was evaluated and rejected. The name match is a coincidence between two records that
share no field, no width and no purpose, so a helper spanning them would couple two independent
layouts: a change made to suit one would silently mis-decode the other, in the one place where
neither reader's own tests would look. This module therefore shares NO key-handling code with
``carddemo_migration.readers.tcatbal``, which states the reciprocal half of the same finding.

This record has no second implementation to be checked against
--------------------------------------------------------------
Assumptions: ``CVTRA04Y`` is one of the THREE base masters the parity oracle's codec does not
register, so unlike most readers in this subpackage this one has no independently written Python
implementation of the same geometry to disagree with it. The oracle's
``tests/helpers/record_codec.py`` transcribes eight copybooks -- ``CVACT01Y``, ``CVTRA06Y``,
``CVTRA02Y``, ``CVACT03Y``, ``CVTRA01Y``, ``CVACT02Y``, ``CVCUS01Y`` and ``CVTRA05Y`` -- and this
record's is not among them; the other two absentees are ``CVTRA03Y`` and ``CSUSR01Y``. That file
is REFERENCE-only, so the gap is recorded here rather than closed by adding a layout to it.

Assumptions: what replaces the missing vector is an exact division. The EBCDIC extract is 1080
bytes and the declared record length is 60, so it holds ``1080 / 60 = 18`` records with remainder
ZERO -- a remainder of anything else would mean the declared width is wrong -- and the ASCII seed
independently agrees at eighteen rows. That arithmetic is the whole of the independent
corroboration available here, which is why the geometry is imported from the descriptor rather
than restated: a transcription error in this record cannot surface as a decode failure, only as
wrong reference data in the table other rows point at.

Assumptions: do not read the oracle's ELEVEN registry keys as covering the eleven base masters.
They are the eight transcribed copybooks above plus three DERIVED layouts -- the statement view,
the reject stream and the interest transaction -- so the two counts coincide by accident.
Equating them is exactly how the three absent masters come to look covered, and this record is
one of the three.

Uniform line terminators here, ragged in the siblings, so the rule is per row
-----------------------------------------------------------------------------
Assumptions: this seed is the data point that settles why terminator handling cannot be chosen per
FILE. Measured rather than assumed: ALL eighteen of its rows end in a carriage return and a
separator, the final row included, which is what its byte count states exactly --
``18 x (60 + 2) = 1116``. Its two nearest siblings are ragged where this one is uniform:
``tcatbal.txt`` carries the pair on 49 of 50 rows and ``trantype.txt`` on 6 of 7, each ending in a
bare separator (``49 x 52 + 1 x 51 = 2599`` and ``6 x 62 + 1 x 61 = 433``). No single whole-file
newline mode is correct across the three -- one chosen to suit this file would strip a real
character from the last row of the other two, and one chosen to suit them would leave a carriage
return inside this file's pad on every row. The rule must therefore be per ROW: strip at most one
carriage return and one separator, testing the pair BEFORE either single character. That rule is
stated once in the shared text iterator, so no terminator handling appears in this module at all.

Assumptions: this record is the CHILD side of the reference-data foreign key. Its two-character
``TRAN-TYPE-CD`` points at the transaction-type reference, and the target declares that constraint
``ON DELETE RESTRICT`` to preserve the baseline's Db2 ``XTRNTYCAT`` semantic -- which is why
deleting a type that categories still reference surfaces as a refusal to the caller rather than as
a database error. The eighteen rows this reader decodes are the ones the reference schema's seed
migration loads. No constraint is built or enforced here, because a reader decodes one record and
knows nothing of another table's rows; the obligation this inheritance places on THIS module is
narrower and absolute -- decode that two-character code faithfully, because a code taken from the
wrong offset would not fail here and would orphan or misparent rows one table away.

Decoded shape
-------------
A decoded record is a ``dict`` keyed by the field name exactly as the copybook spells it, in
declaration order, which is the record's byte order. A character field maps to its characters at
full declared width, untrimmed -- a trailing blank inside the description is data and is not
stripped here. An unsigned display field maps to its characters too, once they are proven to be
the digits its picture clause requires, so the leading zeroes in the four-digit category code
survive; that matters here specifically, because those zeroes are half of the composite key and
``0001`` and ``1`` are not the same key. The trailing pad is absent; see
:data:`DROPPED_FIELD_NAMES`.

That shape is deliberately the one ``carddemo_migration.copybook.ebcdic_codec.decode_record``
publishes, minus the pad, which is what makes the two encodings comparable rather than merely
similar.

Transaction-category exposure control
-------------------------------------
Assumptions: NO field of this record is marked sensitive, and that is a deliberate
classification rather than an omission. The type code, the category code and the description are
closed-domain reference values shared across every account and every transaction, so none of them
designates a person and none reveals anything about a particular cardholder. This reader therefore
suppresses nothing and publishes no ``SUPPRESSED_FIELD_NAMES``, because every field it decodes has
a destination. The rendering helpers below are still published and still route through the shared
masking functions, so marking a field sensitive in the layout would redact it here with no change
to this module.

Design decisions (WHY)
----------------------
Assumptions:
    **Every offset, length, storage regime and record length is imported, never declared.**
    This module states no byte position of its own, and in particular states no key width: the
    6-byte key is read from the descriptor's own ``key_offset`` and ``key_length``. That is
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
    **This record declares NO money field, and there is no binary floating-point value anywhere in
    this module.** The absence is stated outright because almost every sibling reader has a money
    path and a reader of this one will go looking for it: ``CVTRA04Y`` declares no ``PIC S9(n)V99``
    and no signed display field of any kind, so this module publishes no
    :class:`decimal.Decimal`, imports no signed codec and has no money arithmetic to get wrong.
    The package-wide prohibition on ``float`` in a money path is therefore honoured here trivially
    rather than carefully, and it is named only so that its absence reads as the uniform rule
    holding rather than as this record having been exempted from it. Note the contrast with the
    identically-key-named category-BALANCE record, which does carry a money field: the two are
    easy to conflate, and only one of them has an amount to preserve.
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
#   geometry anywhere in the package. That is the Python analogue of compiling every COBOL program
#   against a single `cobc -I app/cpy` include path, and the reference suite's own guide states the
#   same rule for its COBOL unit tests: never duplicate a layout, keep it single-sourced. A
#   relative import is how the guarantee gets broken quietly: a module moved between `readers/` and
#   `loaders/` keeps importing successfully but against a different sibling, and this project's
#   ruff configuration bans relative imports outright for that reason.
# WHY : Assumptions: the descriptor is selected by NAME, and a record length could not select it
#   even if this module wanted to. `TRANCAT_LAYOUT` and its sibling `TRANTYPE_LAYOUT` are BOTH 60
#   bytes -- transaction CATEGORY from `CVTRA04Y` and transaction TYPE from `CVTRA03Y` -- so a
#   length-keyed lookup matches both and picks whichever it met first. Picking the wrong one is
#   silent rather than loud: this record leads with a SIX-byte composite key where that one leads
#   with a two-byte key, so its description begins at offset 6 against that record's 2. Every row
#   would decode to the declared width with its description shifted by four bytes, and no width
#   check anywhere could report it. `TCATBAL_LAYOUT` is the third confusable name and the one a
#   matching key-group name would reach; it is 50 bytes, so length separates it from this record
#   even though the group name does not.
from carddemo_migration.copybook.ebcdic_codec import decode_record, iter_ebcdic_records
from carddemo_migration.copybook.layouts import (
    TRANCAT_LAYOUT,
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
    "TRANCAT_LAYOUT",
    "DROPPED_FIELD_NAMES",
    "LOADED_FIELDS",
    "DecodedTransactionCategory",
    "decode_ascii_transaction_category",
    "decode_ebcdic_transaction_category",
    "iter_ascii_transaction_categories",
    "iter_ebcdic_transaction_categories",
    "read_ascii_transaction_categories",
    "read_ebcdic_transaction_categories",
    "record_key",
    "render_masked_transaction_category_field",
    "render_masked_transaction_category_record",
]

# WHY : Assumptions: the decoded value type is `str` alone because this record declares only
#   character and unsigned display fields, so no decoded value is ever a number and this
#   module has no money path. The projection below still accepts the record decoder's
#   wider union, because that is what the decoder is typed to return.
DecodedTransactionCategory = dict[str, str]

# WHY : Assumptions: the trailing pad is identified by NAME and not by position, and the
#   convention was measured rather than assumed: across all fourteen registered layouts every
#   record declares exactly one pad, named either `FILLER` or, where the copybook qualified it,
#   with that word as its final hyphenated component. THIS record's pad is the unqualified form --
#   `CVTRA04Y` line 9 declares a bare `FILLER PIC X(04)` -- so the suffix arm below never fires
#   here; it is retained because the identical rule is applied by every sibling reader and one of
#   them does face a qualified pad, and a rule that differed per reader is one a maintainer would
#   have to re-check per reader.
# WHY : Trade-offs: matching on the name rather than on the offset keeps this module free of any
#   byte position of its own, which a positional rule would reintroduce for no gain -- the pad is
#   already the last declared field, so a position test would restate offset 56 here and give the
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


# WHY : Assumptions: the pad is DROPPED from every decoded record because its 4 trailing
#   bytes pad the record out to its fixed 60-byte length and carry no data. The EBCDIC
#   record decoder deliberately returns it, stating that dropping it is a projection decision
#   belonging to the reader that maps a record onto a table, so this is that decision and this is
#   where it is taken. Both names below are published rather than kept private so the drop is a
#   fact a caller and a verification pass can assert, instead of a silent omission that would
#   make a decoded record a partial description of the bytes it came from.
LOADED_FIELDS: Final[tuple[FieldSpec, ...]] = tuple(
    field for field in TRANCAT_LAYOUT.fields if not _is_padding_field(field)
)
DROPPED_FIELD_NAMES: Final[frozenset[str]] = frozenset(
    field.name for field in TRANCAT_LAYOUT.fields if _is_padding_field(field)
)


# WHY : Assumptions: the boundary between a value and the trailing pad is DERIVED from
#   the published field tuple and is never written here as a number. Bytes at or beyond it
#   are the pad a text conversion may legitimately have dropped, so supplying them by
#   padding restores what was discarded and changes no published value; bytes BEFORE it
#   belong to a field this reader publishes, so supplying those would not restore anything
#   -- it would invent a value the source never carried and hand a loader a row to key on.
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
    for field in TRANCAT_LAYOUT.fields:
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
        f"record {number} of {TRANCAT_LAYOUT.name} holds a character outside the single-byte"
        f" range at zero-based offset {offset}, {location}; a multi-byte character satisfies"
        f" the {TRANCAT_LAYOUT.reclen}-character width check while occupying more bytes, which"
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
        character field stays data and the leading zeroes of the category code survive.

    Raises
    ------
    LayoutError
        If the field declares a storage regime that cannot occur in a character record, which is
        any computational or mixed-regime area.
    ZonedSpanWidthError
        If an unsigned display field's declared span is not available, or the descriptor's display
        geometry is internally inconsistent. Raised by the display codec.
    ZonedDecimalError
        If a display span violates its contract: a non-digit in the body of either display regime,
        or a low-order byte that is not a valid sign overpunch in a signed one. Raised by the
        display codec.
    """
    if field.kind is Kind.TEXT:
        return record[field.start : field.end]

    if field.kind is Kind.UINT:
        # WHY : Trade-offs: the decimal this returns is DISCARDED and the characters are returned
        #   instead, which costs one parse whose result is thrown away. What it buys is the content
        #   check the picture clause states -- an unsigned display field holding a letter is
        #   refused rather than passed through -- while keeping the value a digit string of
        #   declared width. An integer would drop a leading zero, and a leading zero is
        #   significant in every identifier this corpus declares as `PIC 9`.
        # WHY : Assumptions: this check runs on BOTH paths of this reader. The EBCDIC path gets it
        #   as a by-product of having to decode a code page at all; performing it here keeps the
        #   two entry points enforcing ONE contract, so the same defective record cannot decode
        #   differently depending on which corpus it arrived in.
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
        f"field {field.describe()} of record {TRANCAT_LAYOUT.name} declares a storage regime"
        " that cannot be decoded from a character record; only the character and display regimes"
        " can, and a computational or mixed-regime area must be read from the byte image through"
        " the EBCDIC record decoder"
    )


def _project_decoded_fields(
    values: Mapping[str, str | bytes],
) -> DecodedTransactionCategory:
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
    DecodedTransactionCategory
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
    projected: DecodedTransactionCategory = {}
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
                f"field {TRANCAT_LAYOUT.field(name).describe()} of record"
                f" {TRANCAT_LAYOUT.name} decoded to raw bytes rather than a published value, so"
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
    # WHY : Refactoring Rationale: the width test is DELEGATED to the shared guard and is
    #   now EXACT. This function used to accept any record at least the declared width,
    #   which its own docstring and every decoder in this module contradict, and the
    #   over-long case is the more dangerous of the two: the key sliced from it comes from
    #   the right offsets of the WRONG record -- two rows concatenated, most plausibly --
    #   so it looks entirely well formed and a loader upserts on it. Delegating also means
    #   the eight flat readers cannot drift apart on a test they all have to make.
    require_exact_record_width(record, TRANCAT_LAYOUT)
    start = TRANCAT_LAYOUT.key_offset
    return record[start : start + TRANCAT_LAYOUT.key_length]


def decode_ascii_transaction_category(
    record: str,
    *,
    number: int = 1,
) -> DecodedTransactionCategory:
    """Decode one transaction category record from the ASCII seed form.

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
    DecodedTransactionCategory
        One entry per data field, keyed by the field name exactly as the copybook spells it, in
        declaration order. The pad is absent.

    Raises
    ------
    RecordLengthError
        If the record is not the declared width, or holds a character outside the single-byte
        range.
    LayoutError
        If a declared field's storage regime cannot be decoded from a character record.
    ZonedSpanWidthError
        If an unsigned display field's declared span is not available, or the descriptor's display
        geometry is internally inconsistent. Raised by the display codec.
    ZonedDecimalError
        If a display span violates its contract: a non-digit in the body of either display regime,
        or a low-order byte that is not a valid sign overpunch in a signed one. Raised by the
        display codec.
    """
    # WHY : Trade-offs: a record of the WRONG width is rejected here rather than padded or cut to
    #   fit. Truncation would silently discard real data and padding would invent it, and either
    #   way the row would still decode to well-formed characters, so a wrong-width record would
    #   load under a misread key with nothing reporting it. The accepted cost is that a genuinely
    #   malformed source stops the load instead of loading partially, and for THIS record that
    #   trade is settled by the absence noted in the module docstring: with no reference Python
    #   implementation of this layout to compare a decoded row against, failing loudly on a width
    #   that cannot be right is the only signal available at all.
    if len(record) != TRANCAT_LAYOUT.reclen:
        raise RecordLengthError(
            f"record {number} of {TRANCAT_LAYOUT.name} is {len(record)} characters against a"
            f" declared width of {TRANCAT_LAYOUT.reclen}; a record of the wrong width means the"
            " field offsets have moved, so it is rejected rather than padded or truncated"
        )

    checked = _require_single_byte_record(record, number)

    # WHY : Assumptions: the fields are walked in the descriptor's declaration order, which is the
    #   record's byte order, so the resulting mapping iterates the record left to right. The pad
    #   is excluded by iterating the published field tuple rather than by decoding every field and
    #   filtering afterwards, which also avoids decoding 4 bytes of pad on every record.
    return {field.name: _decode_text_field_value(checked, field) for field in LOADED_FIELDS}


def iter_ascii_transaction_categories(
    source: str | Iterable[object],
) -> Iterator[DecodedTransactionCategory]:
    """Decode the ASCII seed form of the transaction category reference, one record at a time.

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
    Iterator[DecodedTransactionCategory]
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
    ZonedSpanWidthError
        If an unsigned display field's declared span is not available, or the descriptor's display
        geometry is internally inconsistent. Raised by the display codec.
    ZonedDecimalError
        If a display span violates its contract: a non-digit in the body of either display regime,
        or a low-order byte that is not a valid sign overpunch in a signed one. Raised by the
        display codec.
    """
    # WHY : Assumptions: the record cut is DELEGATED and not written again here. That iterator
    #   owns one statement of the text-mode contract for the whole package: it splits on the
    #   separator, removes at most one trailing carriage return and separator per row so trailing
    #   blanks stay data, drops the single phantom empty piece a text ending in a separator
    #   produces, right-pads a short line, and REJECTS an over-long one. A second implementation
    #   here is exactly the drift this dependency edge exists to prevent, and it would be
    #   invisible: two readers stripping terminators slightly differently both return well-formed
    #   records.
    # WHY : Assumptions: the per-ROW terminator rule that iterator applies is a requirement here
    #   rather than a defensive habit, and this seed is the measurement that proves it. All eighteen
    #   of its rows carry a carriage return and a separator, the last row included -- `18 x 62 =
    #   1116` bytes exactly -- whereas `tcatbal.txt` carries the pair on 49 of 50 rows and
    #   `trantype.txt` on 6 of 7, each ending bare. A whole-file mode chosen to suit this corpus
    #   would strip a real character from the final row of those two, and one chosen to suit them
    #   would leave a carriage return inside this record's pad on every row. Testing the pair before
    #   either single character is what makes both cases come out right from one implementation.
    # WHY : Trade-offs: that iterator's tolerance for a SHORT line -- right-padding it with blanks
    #   -- is inherited deliberately rather than overridden. Measured rather than assumed: every row
    #   of THIS seed already reaches the full 60 characters once the per-row strip has run, so the
    #   tolerance never engages here at all. It exists because one shipped seed conversion lost its
    #   trailing pad entirely, and padding on the right cannot move a field that is present.
    #   Overriding it for this reader would fork the text-mode contract for one record.
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
        source, TRANCAT_LAYOUT.reclen, min_data_width=_DATA_REGION_WIDTH, layout=TRANCAT_LAYOUT
    )

    for number, record in enumerate(records, start=1):
        yield decode_ascii_transaction_category(record, number=number)


def read_ascii_transaction_categories(path: pathlib.Path) -> Iterator[DecodedTransactionCategory]:
    """Stream the transaction category reference from an ASCII seed file at an explicit path.

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
    Iterator[DecodedTransactionCategory]
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
    ZonedSpanWidthError
        If an unsigned display field's declared span is not available, or the descriptor's display
        geometry is internally inconsistent. Raised by the display codec.
    ZonedDecimalError
        If a display span violates its contract: a non-digit in the body of either display regime,
        or a low-order byte that is not a valid sign overpunch in a signed one. Raised by the
        display codec.
    """
    # WHY : Assumptions: the caller supplies an EXPLICIT file and this function never globs a
    #   directory to find one. The seed directories make that concrete: they hold a zero-byte
    #   placeholder and, in the EBCDIC tree, names differing by a single character, so a pattern
    #   match would either sweep the placeholder in or load a dataset twice -- and a doubled image
    #   still divides by the record length with remainder zero, so nothing downstream would catch
    #   it and every money total would come out doubled.
    # WHY : Refactoring Rationale: the open is DELEGATED to `readers.source.iter_seed_lines` and
    #   is no longer a `Path.open` here, which closes two faults this reader shared with its seven
    #   siblings. `Path.open` is a BLOCKING open, so a named pipe or a character device named where
    #   a seed file was expected did not fail -- it waited, indefinitely and with no diagnostic, in
    #   a step an operator is watching for a load to finish. And iterating a text handle reads to
    #   the next separator with NO bound at all, so a file whose first separator lies far past the
    #   record length was materialised in full before any width check could refuse it: the check
    #   that would have rejected it ran after the allocation that made it a problem. The shared
    #   reader opens with O_NONBLOCK, proves the descriptor is a regular file with fstat before a
    #   byte is read, and bounds each line by the declared width plus its terminators.
    # WHY : Trade-offs: the code page and the terminator policy move WITH the open, so this module
    #   no longer names either. That is the point -- eight modules each naming them is eight
    #   chances to disagree, and a disagreement would be invisible, because a reader that validated
    #   a file slightly differently from its siblings still returns well-formed records for every
    #   ordinary input. The accepted cost is one more module to read to see how a file is opened;
    #   `readers.source` records the full reasoning for both decisions in one place.
    yield from iter_ascii_transaction_categories(iter_seed_lines(path, TRANCAT_LAYOUT.reclen))


def decode_ebcdic_transaction_category(
    record: bytes | bytearray | memoryview,
) -> DecodedTransactionCategory:
    """Decode one transaction category record from the EBCDIC dataset form.

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
    DecodedTransactionCategory
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
        If an unsigned display field's declared span is not available, or the descriptor's display
        geometry is internally inconsistent. Raised by the display codec.
    ZonedDecimalError
        If a display span violates its contract: a non-digit in the body of either display regime,
        or a low-order byte that is not a valid sign overpunch in a signed one. Raised by the
        display codec.
    """
    # WHY : Assumptions: the conversion is DELEGATED per field and never performed on the record
    #   as a whole. That codec decodes each declared span on its own, dispatching on the
    #   descriptor's regime BEFORE any character set is applied, so no span of packed nibbles or
    #   low-value padding is ever handed to a character decoder. Decoding a whole record through a
    #   code page is the single most likely mistake on this path and the most damaging: it
    #   succeeds, preserves the declared width, and yields a record that looks almost right.
    return _project_decoded_fields(decode_record(record, TRANCAT_LAYOUT))


def iter_ebcdic_transaction_categories(
    source: pathlib.PurePath | bytes | bytearray | memoryview | Iterable[object],
) -> Iterator[DecodedTransactionCategory]:
    """Decode the EBCDIC dataset form of the transaction category reference, one record at a time.

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
    Iterator[DecodedTransactionCategory]
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
        If an unsigned display field's declared span is not available, or the descriptor's display
        geometry is internally inconsistent. Raised by the display codec.
    ZonedDecimalError
        If a display span violates its contract: a non-digit in the body of either display regime,
        or a low-order byte that is not a valid sign overpunch in a signed one. Raised by the
        display codec.
    """
    # WHY : Assumptions: the dataset is cut on the declared record length ALONE, and no line
    #   terminator is looked for, honoured, stripped or padded on this path. Measured rather than
    #   assumed: the shipped extract contains no separator byte and no carriage return at all, so a
    #   byte that happened to equal either would be field data -- a low-order digit or a pad byte --
    #   and splitting on it would produce pieces of wildly differing lengths, most cut through the
    #   middle of a field. Carrying the text path's terminator logic here would consume a DATA byte
    #   as a separator.
    # WHY : Assumptions: the correctness test for this dataset is that its size divides by the
    #   declared record length with no remainder -- 1080 bytes over a 60-byte record is 18 records
    #   remainder ZERO -- and the delegated iterator checks exactly that before it yields the first
    #   record. For this layout that division carries more weight than it does for most siblings: it
    #   is the substitute for the reference Python vector this record does not have, as the module
    #   docstring records, so it is the one independent check standing between a transcription error
    #   and a silently wrong reference table that every posted transaction's category points at.
    # WHY : Assumptions: the text form's tolerances must never reach here. Right-padding a short
    #   piece or stripping a trailing byte would turn a genuine length failure into a plausible
    #   record, which is why this path reaches a different entry point of the layouts module and
    #   shares no code with the text one.
    for record in iter_ebcdic_records(source, TRANCAT_LAYOUT):
        yield decode_ebcdic_transaction_category(record)


def read_ebcdic_transaction_categories(path: pathlib.Path) -> Iterator[DecodedTransactionCategory]:
    """Stream the transaction category reference from an EBCDIC dataset file at an explicit path.

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
    Iterator[DecodedTransactionCategory]
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
        If an unsigned display field's declared span is not available, or the descriptor's display
        geometry is internally inconsistent. Raised by the display codec.
    ZonedDecimalError
        If a display span violates its contract: a non-digit in the body of either display regime,
        or a low-order byte that is not a valid sign overpunch in a signed one. Raised by the
        display codec.
    """
    # WHY : Assumptions: the caller supplies an EXPLICIT file and this function never globs the
    #   directory to find one, and the hazard is concrete rather than theoretical:
    #   `app/data/EBCDIC/` holds a ZERO-BYTE `.gitkeep` alongside the datasets, and it also holds
    #   extracts whose names differ from one another by a single character. A pattern match would
    #   sweep the placeholder in, or read a dataset twice -- and a doubled image still divides by 60
    #   with remainder zero, so the exact-division check that stands in for this record's missing
    #   reference vector would pass on thirty-six records where there are eighteen.
    # WHY : Assumptions: a zero-byte file is a dataset with NO records and is not an error, so
    #   nothing here treats emptiness as a failure. The delegated iterator yields nothing for it and
    #   this generator ends immediately, which is the correct reading of an extract that ran and
    #   selected no rows -- and it is also what keeps the `.gitkeep` above from being reported as a
    #   corrupt dataset if a caller ever does name it.
    # WHY : Alternatives Considered: the path is handed to the codec rather than opened here and
    #   passed as a stream. The codec validates the file size against the declared record length
    #   BEFORE yielding a first record, so a truncated dataset fails up front instead of part way
    #   through a load, and it owns the open, the forward-only read and the close. Opening the file
    #   here would duplicate that lifecycle for no gain.
    yield from iter_ebcdic_transaction_categories(pathlib.PurePath(path))


def render_masked_transaction_category_record(record: str) -> str:
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
    #   amounts redacts them.
    return mask_record(record, TRANCAT_LAYOUT)


def render_masked_transaction_category_field(record: str, field_name: str) -> str:
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
    field = TRANCAT_LAYOUT.field(field_name)
    return mask_field(field, record[field.start : field.end])
