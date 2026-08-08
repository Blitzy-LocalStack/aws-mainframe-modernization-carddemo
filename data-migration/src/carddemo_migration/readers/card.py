"""Streaming reader for the CardDemo card master, in both shipped encodings.

Purpose
-------
Turn the card master extract into decoded records the Aurora loaders and the verification
passes can consume, one record at a time, from either of the two forms the baseline ships:
the line-oriented ASCII seed and the fixed-length EBCDIC dataset. Both entry points yield
the same decoded shape, so a caller can swap corpora and compare the two without adapting
to a second contract.

This module is the SECOND per-record reader and follows the pattern ``readers/account.py``
established, so it differs from that module in only two substantive respects: the descriptor
it names, and the exposure control described below. That control is this module's
distinguishing responsibility, because this is the first record in the corpus whose bytes
carry a primary account number, a cardholder name AND a card verification value together.

What this module reads
---------------------
The record is ``CARD-RECORD`` as declared in ``app/cpy/CVACT02Y.cpy``, and its byte geometry
is resolved exclusively through ``CARD_LAYOUT`` in ``carddemo_migration.copybook.layouts``.
The two shipped corpora are ``app/data/ASCII/carddata.txt`` and
``app/data/EBCDIC/AWS.M2.CARDDEMO.CARDDATA.PS``, and ``data-migration/README.md`` records
the pairing of this dataset with that copybook at a 150-byte record length. Both are
REFERENCE-only inputs: this module opens them read-only and never writes, re-encodes or
normalises either one in place.

Decoded shape
-------------
A decoded record is a ``dict`` keyed by the field name exactly as the copybook spells it, in
declaration order, which is the record's byte order. Every field this record declares is
either a character field or an unsigned display field, and both regimes yield their
characters at full declared width, untrimmed, so a significant leading zero in the account
identifier survives. This record declares no signed display, packed or binary field, so no
decoded value is ever a number, this module has no money path, and the value type is
therefore ``str`` rather than the account reader's wider union.

Two spans present in the bytes are absent from the mapping, and each for its own distinct
reason: the trailing pad, which carries no data, and the card verification value, which this
module suppresses outright. See :data:`DROPPED_FIELD_NAMES` and
:data:`SUPPRESSED_FIELD_NAMES`.

Card-data exposure control
--------------------------
Three of this record's six data fields are marked sensitive by the descriptor, and they are
NOT all treated alike, because they are not all needed downstream to the same degree:

* The card verification value is **suppressed**. It is never decoded, never a key in a
  returned mapping, and never named in a diagnostic. Suppression is strictly stronger than
  redaction, and the reason it is warranted is specific rather than precautionary; see the
  design note below.
* The primary account number and the embossed name are **emitted** in the decoded mapping,
  because the card table keys on the one and stores the other, and **masked** in every
  diagnostic rendering this module produces.
* Diagnostics never echo record content of any kind, sensitive or otherwise.

Design decisions (WHY)
----------------------
Alternatives Considered:
    **The card verification value is suppressed rather than masked.** Masking it was
    evaluated first, because the descriptor already marks it sensitive and the shared
    masking helper already redacts it to a same-width tag, so redaction was available at no
    cost. It was rejected: redaction changes only how the value RENDERS and would still
    carry the value itself through this module's return path, where any caller could read
    it from the mapping. The value has no legitimate downstream consumer to justify that.
    The migration plan states that no endpoint ever returns a verification value and that
    its target column is encrypted, so nothing this reader feeds needs the plaintext, and
    the correct handling of a value that must never be reproduced is not to reproduce it.
Trade-offs:
    **What suppression costs is that a verification-value mismatch between the two corpora
    cannot be diagnosed from this reader's output**, because neither the value nor a
    comparable digest of it is emitted. That cost is accepted deliberately: a reader that
    could confirm a verification value is a reader that could disclose one, and for this
    particular field the ability to disclose is the larger risk of the two. A caller needing
    to prove the two corpora agree byte for byte over the whole record can compare the raw
    images without decoding them through this module at all.
Assumptions:
    **Every offset, length, storage regime and record length is imported, never declared.**
    This module states no byte position of its own. That is the Python analogue of compiling
    every COBOL program against a single ``cobc -I app/cpy`` include path, and the reference
    suite's own guide states the same rule for its COBOL unit tests: never duplicate a
    layout, keep it single-sourced from ``app/cpy/``. The consequence of breaking it is
    specific and undetectable -- a re-declared offset lets this reader and a sibling reading
    the same bytes drift apart, and a record read one byte out of alignment still decodes to
    plausible characters, so nothing raises and no test fails.
Trade-offs:
    **Records are streamed, never materialised.** Both entry points are generators and
    neither builds a list of rows, so memory is constant in the record count rather than
    proportional to it. The accepted cost is that a caller gets a single forward pass and
    must re-open the source to read it twice. What that buys is that the committed seed and
    a production extract many orders larger behave identically here, so the reader that was
    proven against the seed is the one that runs against the extract.
Assumptions:
    **There is no binary floating-point value anywhere in this module.** This record happens
    to declare no money field, so the prohibition costs nothing to honour here, but it is
    stated rather than left implicit: an eleven-digit account identifier exceeds the range a
    binary float represents exactly, so routing one through a float would corrupt the very
    identifier the card table joins on. Identifiers stay character strings end to end.
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
from collections.abc import Iterable, Iterator
from typing import Final

# WHY (Assumptions): these imports are the entire reason this module has a dependency on
#   the copybook package, and they are absolute and rooted at the distribution package
#   rather than relative. A relative import is how the single-sourcing guarantee gets
#   broken quietly: a module moved between `readers/` and `loaders/` keeps importing
#   successfully but against a different sibling, and the project's ruff configuration
#   bans relative imports outright for that reason. The record descriptor named below is
#   the ONLY statement of this record's geometry anywhere in the package.
# WHY (Alternatives Considered): the PER-FIELD decoder is imported from the EBCDIC codec
#   rather than its record-oriented sibling, which the account reader uses. The
#   record-oriented form decodes every field the layout declares and returns them all,
#   which would mean decoding the verification value and then discarding it. Both forms
#   route an unsigned display field through the same internal decoder, so they agree value
#   for value on every field this module keeps; they differ only in whether the suppressed
#   field is decoded at all. Per-field is chosen because it is the form in which
#   suppression is a fact about what runs, not a filter applied after the fact.
from carddemo_migration.copybook.ebcdic_codec import decode_field, iter_ebcdic_records
from carddemo_migration.copybook.layouts import (
    CARD_LAYOUT,
    FieldSpec,
    Kind,
    LayoutError,
    RecordLengthError,
    iter_ascii_text_records,
    mask_field,
    mask_record,
)

__all__ = [
    "CARD_LAYOUT",
    "DROPPED_FIELD_NAMES",
    "LOADED_FIELDS",
    "SUPPRESSED_FIELD_NAMES",
    "DecodedCard",
    "decode_ascii_card",
    "decode_ebcdic_card",
    "iter_ascii_cards",
    "iter_ebcdic_cards",
    "read_ascii_cards",
    "read_ebcdic_cards",
    "render_masked_card_field",
    "render_masked_card_record",
]

# WHY (Assumptions): the decoded value type is `str` alone, and not the account reader's
#   `str | Decimal` union, because this record declares only the two display-text regimes.
#   Widening it to match the sibling would oblige every caller to narrow a case this record
#   cannot produce, and narrowing a case that cannot occur is how a caller ends up with an
#   unreachable branch that no test can cover.
DecodedCard = dict[str, str]

# WHY (Assumptions): the trailing pad is identified by NAME and not by position, and the
#   convention was measured rather than assumed: across every registered layout the pad is
#   named either `FILLER` or, where the copybook qualified it, with that word as its final
#   hyphenated component. Testing the name keeps this module free of any byte position of
#   its own, which a position test would reintroduce, and it is why this rule is identical
#   to the account reader's rather than a second variant of it.
_PAD_FIELD_NAME: Final[str] = "FILLER"
_PAD_NAME_SUFFIX: Final[str] = f"-{_PAD_FIELD_NAME}"

# WHY (Assumptions): the suppressed field is identified by a NAME COMPONENT rather than by
#   its full name, its offset or an equality test against one string, and the component was
#   measured across the whole registry rather than guessed. Every field in the corpus that
#   holds a card verification value carries that abbreviation as one hyphen-delimited
#   component of its copybook name -- the card master's own field and the export record's
#   overlay of it are the two that exist, and they agree on the convention while sharing no
#   prefix. Matching a component therefore covers both spellings without also matching an
#   unrelated field that merely contains those letters inside a longer word.
# WHY (Alternatives Considered): the alternative was to key suppression off the
#   descriptor's `sensitive` flag, which is already set on this field. It was rejected
#   because that flag is also set on the primary account number and the embossed name, both
#   of which this reader MUST emit for the loader to populate the card table -- so
#   suppressing everything marked sensitive would empty the record of the columns it exists
#   to carry. Sensitivity selects what gets redacted in a rendering; this predicate selects
#   what is never decoded at all, and the two are deliberately different questions.
_VERIFICATION_VALUE_COMPONENT: Final[str] = "CVV"
_NAME_COMPONENT_SEPARATOR: Final[str] = "-"


def _is_padding_field(field: FieldSpec) -> bool:
    """Report whether a field is the record's trailing pad rather than data.

    Purpose
    -------
    Decide, from the field's declared name alone, whether it exists only to fill the record
    out to its fixed length. This is the single place that judgement is made, so the
    projection and the record of what was dropped cannot disagree about it.

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


def _is_suppressed_field(field: FieldSpec) -> bool:
    """Report whether a field must never be decoded or published at all.

    Purpose
    -------
    Decide, from the field's declared name alone, whether it holds a card verification
    value. This is the single place that judgement is made, and it is consulted BEFORE any
    byte of the field is read, so a suppressed field is never decoded rather than being
    decoded and then withheld.

    Parameters
    ----------
    field : FieldSpec
        The field descriptor under test. Only its ``name`` is consulted, so the test is
        independent of the field's offset, width and storage regime -- the export record
        declares its verification value as a computational field where this record declares
        a display one, and the same rule has to cover both.

    Returns
    -------
    bool
        ``True`` when the field holds a card verification value and must be excluded from
        every decoded record and every rendering; ``False`` otherwise.

    Raises
    ------
    None
    """
    # WHY (Assumptions): the test is a POSITIVE property of the descriptor, evaluated over
    #   every field the layout declares, rather than the removal of one known key after the
    #   fact. The difference is what happens when the layout gains a field: a predicate
    #   applied to every field covers the new one automatically, whereas a `del` of one
    #   named key keeps passing, keeps looking correct, and silently stops covering anything
    #   it was not written for. For a value that must never be reproduced, the failure mode
    #   of the weaker form is disclosure, so the stronger form is the only acceptable one.
    return _VERIFICATION_VALUE_COMPONENT in field.name.split(_NAME_COMPONENT_SEPARATOR)


# WHY (Assumptions): the pad is DROPPED from every decoded record because its 59 trailing
#   bytes pad the record out to its fixed 150-byte length and carry no data -- the copybook
#   declares them as an unnamed filler and no program reads them. The EBCDIC record decoder
#   deliberately returns it, stating that dropping it is a projection decision belonging to
#   the reader that maps a record onto a table, so this is that decision and this is where
#   it is taken.
# WHY (Trade-offs): all three names below are PUBLISHED rather than kept private, so that
#   what a decoded record omits is a fact a caller and a verification pass can assert
#   instead of a silent omission that would make a decoded record a partial description of
#   the bytes it came from. Publishing the suppressed set names the field WITHOUT emitting
#   any instance of its value, which is the distinction that makes it safe to publish: a
#   test can prove the suppression holds without ever handling a verification value.
LOADED_FIELDS: Final[tuple[FieldSpec, ...]] = tuple(
    field
    for field in CARD_LAYOUT.fields
    if not _is_padding_field(field) and not _is_suppressed_field(field)
)
DROPPED_FIELD_NAMES: Final[frozenset[str]] = frozenset(
    field.name for field in CARD_LAYOUT.fields if _is_padding_field(field)
)
SUPPRESSED_FIELD_NAMES: Final[frozenset[str]] = frozenset(
    field.name for field in CARD_LAYOUT.fields if _is_suppressed_field(field)
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
        lies beyond the declared record; the fields cover the record contiguously, so
        ``None`` means the offset itself is out of range.

    Raises
    ------
    None
    """
    # WHY (Trade-offs): the search walks EVERY declared field, including the suppressed one
    #   and the pad, rather than only the published ones. Locating a fault is a different
    #   question from publishing a value: a bad byte inside the verification value's span
    #   still has to be reported as being in that span, or the offset would be attributed to
    #   whichever published field happens to sit next to it and the report would be wrong.
    #   Only the field's NAME and GEOMETRY are ever rendered from this, never its content,
    #   so naming the suppressed span discloses nothing about the value stored in it.
    for field in CARD_LAYOUT.fields:
        if field.start <= offset < field.end:
            return field
    return None


def _require_single_byte_record(record: str, number: int) -> str:
    """Require a record whose character count provably equals its byte count.

    Purpose
    -------
    Close the one width failure the shared text-record iterator cannot see. That iterator
    enforces the declared length in CHARACTERS, which is the right check for text; this one
    additionally proves every character occupies a single byte, so the character contract
    also holds at the byte level the field offsets are expressed in.

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
    # WHY (Assumptions): a multi-byte character satisfies a CHARACTER-count check while
    #   occupying more than one byte, so it passes the shared iterator's declared-length test
    #   and then desynchronises every offset after it -- and because a record read one byte
    #   out of alignment still decodes to plausible characters, nothing later would raise. On
    #   this record the specific damage is that the sixteen characters read as the primary
    #   account number would be the wrong sixteen, so the card table would key on a number
    #   that appears in no corpus. Requiring single-byte characters makes the character count
    #   provably equal the byte count, which is what the offsets assume.
    # WHY (Assumptions): this check, and every other validation in this module, is enforced
    #   by an explicit `raise` and never by an `assert`. Running the interpreter with `-O`
    #   strips assert statements outright, so an assertion is not a validation at all: it is a
    #   check that silently disappears in exactly the deployment where a misaligned record
    #   loads a wrong card number, and the load would then succeed while writing it. The
    #   layouts module states the same rule for the same reason, so this reader is consistent
    #   with it rather than an exception to it.
    if record.isascii():
        return record

    offset = next(index for index, char in enumerate(record) if not char.isascii())
    field = _field_containing(offset)

    # WHY (Trade-offs): the message names the record number, the zero-based offset and the
    #   containing field's geometry, and it quotes NO part of the record -- not the offending
    #   character and not its code point. That shows exactly WHERE the record failed while
    #   emitting none of its content, so the diagnostic is safe to log wherever its consumer
    #   sends it. The reference codec does echo the offending character; the Java shared
    #   kernel's codecs deliberately do not, and the stricter of the two forms is adopted
    #   here because this record's 150 bytes hold a primary account number, a cardholder name
    #   and a verification value, so echoing raw content would leak all three at once, and a
    #   diagnostic cannot be un-logged once it has been written.
    location = "beyond the declared record" if field is None else f"in field {field.describe()}"
    raise RecordLengthError(
        f"record {number} of {CARD_LAYOUT.name} holds a character outside the single-byte"
        f" range at zero-based offset {offset}, {location}; a multi-byte character satisfies the"
        f" {CARD_LAYOUT.reclen}-character width check while occupying more bytes, which moves"
        " every field offset after it, so the record is rejected rather than decoded"
    )


def _decode_text_field_value(record: str, field: FieldSpec) -> str:
    """Decode one field of a character record, routing it by its declared storage regime.

    Purpose
    -------
    Produce one field's final value from a record that is already characters. Both regimes
    this record declares are named explicitly and yield the field's characters at its full
    declared width, and anything else is refused.

    Parameters
    ----------
    record : str
        One whole record at its declared character width.
    field : FieldSpec
        The descriptor supplying the offset and the width. Nothing about the field's position
        is taken from anywhere else.

    Returns
    -------
    str
        The field's characters at full declared width, untrimmed, so trailing pad inside a
        character field stays data and a leading zero inside an identifier survives.

    Raises
    ------
    LayoutError
        If the field declares a storage regime that cannot occur in a character record, which
        is any signed display, computational or mixed-regime area.
    """
    # WHY (Assumptions): both display regimes are sliced identically, by the descriptor's own
    #   span, because both yield characters at full declared width -- the copybook's `PIC 9`
    #   fields on this record are identifiers rather than quantities, and an integer
    #   conversion would drop the leading zeros that are significant in an eleven-digit
    #   account identifier. The two regimes are still named separately rather than merged
    #   into one test, so that the trailing raise keeps its meaning.
    # WHY (Trade-offs): unlike the EBCDIC path, this path does not additionally PROVE that an
    #   unsigned display field holds only digits. The check belongs to the display codec,
    #   which is deliberately outside this reader's dependency set because this record
    #   declares no signed value for that codec to decode, and reaching for it here to
    #   validate content would pull a money path into a module that has none. The EBCDIC path
    #   gets the same check for free, as a by-product of having to decode a code page at all,
    #   and the project's README fixes the EBCDIC extracts as authoritative wherever both
    #   forms exist -- so the stricter check runs on the form whose verdict governs. What is
    #   accepted is that a corrupt ASCII seed row could carry a non-digit through this path;
    #   what is preserved is that the field's width, offset and content are otherwise
    #   untouched, so the two forms still decode equal for every conforming record.
    if field.kind is Kind.TEXT or field.kind is Kind.UINT:
        return record[field.start : field.end]

    # WHY (Assumptions): every regime this record declares is named explicitly above and
    #   anything else raises, rather than the last branch doubling as a default. A signed
    #   display, computational or mixed-regime area cannot be read from a character record at
    #   all: its bytes are sign overpunches, packed nibbles, machine words or a differently
    #   described overlay, and a character decode of them succeeds, keeps the declared width
    #   and yields plausible text, so the damage would be invisible. Raising here also means a
    #   regime added to this record later cannot fall silently into whichever branch happens
    #   to be last -- it stops the load and names itself instead.
    raise LayoutError(
        f"field {field.describe()} of record {CARD_LAYOUT.name} declares a storage regime that"
        " cannot be decoded from a character record; only the two display-text regimes can,"
        " and a signed display, computational or mixed-regime area must be read from the byte"
        " image through the EBCDIC codec"
    )


def decode_ascii_card(record: str, *, number: int = 1) -> DecodedCard:
    """Decode one card record from the ASCII seed form.

    Purpose
    -------
    Turn a single full-width character record into the published decoded shape: every emitted
    data field keyed by its copybook name, with the trailing pad dropped and the card
    verification value suppressed.

    Parameters
    ----------
    record : str
        One whole record at exactly the declared record width, with no line terminator.
        Records at the declared width are what the shared text-record iterator yields.
    number : int
        The one-based record number within the source, used only so a rejection names the row
        that failed. Defaults to 1 for a caller decoding a record in isolation.

    Returns
    -------
    DecodedCard
        One entry per emitted data field, keyed by the field name exactly as the copybook
        spells it -- including the baseline's own misspelling of the expiration date, which
        the descriptor preserves -- in declaration order. Every value is the field's
        characters at full declared width. The pad and the verification value are absent.

    Raises
    ------
    RecordLengthError
        If the record is not the declared width, or holds a character outside the single-byte
        range.
    LayoutError
        If a declared field's storage regime cannot be decoded from a character record.
    """
    # WHY (Trade-offs): a record of the WRONG width is rejected here rather than padded or
    #   cut to fit. Truncation would silently discard real data and padding would invent it,
    #   and either way the row would still decode to well-formed characters, so a wrong-width
    #   record would load a card under a misread number with nothing reporting it. The
    #   accepted cost is that a genuinely malformed source stops the load instead of loading
    #   partially, which for the master that keys every card is the outcome to prefer.
    if len(record) != CARD_LAYOUT.reclen:
        raise RecordLengthError(
            f"record {number} of {CARD_LAYOUT.name} is {len(record)} characters against a"
            f" declared width of {CARD_LAYOUT.reclen}; a record of the wrong width means the"
            " field offsets have moved, so it is rejected rather than padded or truncated"
        )

    checked = _require_single_byte_record(record, number)

    # WHY (Assumptions): the fields are walked in the descriptor's declaration order, which
    #   is the record's byte order, so the resulting mapping iterates the record left to
    #   right. Both the pad and the verification value are excluded by iterating the published
    #   field tuple rather than by decoding all seven fields and filtering afterwards, which
    #   is what makes the suppression structural: the verification value's three bytes are
    #   never sliced, so there is no point in this function at which that value exists.
    return {field.name: _decode_text_field_value(checked, field) for field in LOADED_FIELDS}


def iter_ascii_cards(source: str | Iterable[object]) -> Iterator[DecodedCard]:
    """Decode the ASCII seed form of the card master, one record at a time.

    Purpose
    -------
    Stream decoded records from character data the caller already holds or is already
    iterating, delegating every record boundary decision to the shared text-record iterator.

    Parameters
    ----------
    source : str | Iterable[object]
        The seed data as either the whole text, or an iterable whose every element is one
        LINE, with or without its terminator. An open text stream is such an iterable. A byte
        object is refused, because no character encoding is guessed here.

    Returns
    -------
    Iterator[DecodedCard]
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
    """
    # WHY (Assumptions): the record cut is DELEGATED and not written again here. That iterator
    #   owns one statement of the text-mode contract for the whole package: it splits on the
    #   separator, removes AT MOST ONE trailing carriage return and separator per row so that
    #   trailing blanks stay data, drops the single phantom empty piece a text ending in a
    #   separator produces, right-pads a short line, and REJECTS an over-long one. The
    #   at-most-one rule per ROW is what makes a single behaviour correct across all the seeds
    #   rather than just this one: this dataset is uniformly bare-newline, but two sibling
    #   seeds carry a carriage return on every line EXCEPT a final bare-newline line, so a
    #   whole-file newline mode would mis-handle that last row. A second implementation here
    #   is exactly the drift this dependency edge exists to prevent, and it would be
    #   invisible: two readers stripping terminators slightly differently both return
    #   well-formed records.
    # WHY (Trade-offs): that iterator's tolerance for a SHORT line -- padding it on the right
    #   with blanks -- is inherited deliberately rather than overridden. It exists because one
    #   shipped seed conversion lost its trailing pad entirely, and padding on the right cannot
    #   move a field that is present; the characters added are precisely the pad the text form
    #   omitted. Every row of this dataset's seed is already full width, so the tolerance never
    #   engages here, and overriding it would fork the contract for one reader.
    records = iter_ascii_text_records(source, CARD_LAYOUT.reclen)

    # WHY (Trade-offs): records are YIELDED one at a time rather than collected, so memory is
    #   constant in the record count. The cost is a single forward pass -- a caller wanting a
    #   second reading must re-open the source -- and what it buys is that this reader behaves
    #   identically on the small committed seed and on a production extract many orders of
    #   magnitude larger, so the one proven against the seed is the one that runs.
    for number, record in enumerate(records, start=1):
        yield decode_ascii_card(record, number=number)


def read_ascii_cards(path: pathlib.Path) -> Iterator[DecodedCard]:
    """Stream the card master from an ASCII seed file at an explicit path.

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
    Iterator[DecodedCard]
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
    """
    # WHY (Assumptions): the caller supplies an EXPLICIT file, and this function never globs a
    #   directory to find one. The seed directories make that concrete: the EBCDIC directory
    #   holds a zero-byte placeholder beside the datasets, so a pattern match over the
    #   directory would sweep it up along with whatever else matched. A zero-byte file is
    #   legitimately a dataset with no records and is not an error -- the committed fixtures
    #   include exactly that case for this dataset -- so it must yield nothing rather than
    #   raise, and only an explicit path makes the difference between "empty" and "wrong file"
    #   the caller's to state.
    # WHY (Alternatives Considered): the file is decoded through a single-byte code page that
    #   is total over all 256 byte values, rather than through a strict ASCII decode. Both
    #   reject a non-conforming file, but they differ in WHERE and HOW. A strict decode would
    #   fail inside the interpreter's reader with an encoding error, which is untyped with
    #   respect to this package and would make the explicit single-byte guard unreachable. A
    #   total single-byte page instead maps each byte to exactly one character, so the
    #   character count the shared iterator checks provably equals the byte count, and the
    #   failure surfaces as this package's own record-length error naming the offset. Both
    #   non-conforming shapes then land on that one typed error: a multi-byte sequence widens
    #   the row past the declared width and the iterator rejects it, while a single high byte
    #   leaves the width intact and the guard rejects it.
    # WHY (Assumptions): line splitting is pinned to the separator alone, matching the shared
    #   iterator's own whole-text scanner exactly, so streaming this handle line by line and
    #   passing the whole text produce identical records. Leaving the default in place would
    #   let the interpreter translate and split on a carriage return as well, which would move
    #   terminator policy out of the module that owns it.
    with path.open("r", encoding="latin-1", newline="\n") as handle:
        yield from iter_ascii_cards(handle)


def _require_full_record_image(record: bytes | bytearray | memoryview) -> bytes:
    """Require a byte image of exactly the declared record length.

    Purpose
    -------
    Prove a record image spans the WHOLE declared record before any field is decoded from it.
    The per-field decoder checks only that each field it is asked for is reachable, and the
    fields this module publishes all end well before the record does, so without this check a
    record truncated anywhere inside its trailing pad would decode cleanly and silently.

    Parameters
    ----------
    record : bytes | bytearray | memoryview
        One whole record image, read in binary mode. A ``str`` is refused, because character
        data has already lost the byte values a fixed-length dataset is defined in terms of.

    Returns
    -------
    bytes
        The same image as ``bytes``, once its length is proven to equal the declared record
        length.

    Raises
    ------
    TypeError
        If the record is a ``str`` or is not convertible to a one-dimensional byte image.
    RecordLengthError
        If the image is not exactly the declared record length.
    """
    # WHY (Alternatives Considered): the whole-record length check is performed HERE rather
    #   than inherited from the record-oriented EBCDIC decoder, which is where the account
    #   reader gets it. Using that decoder would have supplied this check for free, but only
    #   by decoding every declared field -- including the verification value -- which is
    #   precisely what this module refuses to do. Re-stating the check is the smaller cost:
    #   it compares against the layout's own declared length and so still introduces no byte
    #   position of this module's own.
    # WHY (Trade-offs): the image is normalised to `bytes` rather than being passed on as
    #   whatever view arrived. A memoryview reports its length in ITEMS, not bytes, so a view
    #   with a wider element format would satisfy a naive length test while spanning a
    #   different number of bytes; converting first makes the length compared here the length
    #   in bytes. The accepted cost is one copy per record, which is bounded by the record
    #   length and so does not grow with the dataset.
    image = bytes(record)

    if len(image) != CARD_LAYOUT.reclen:
        raise RecordLengthError(
            f"a {CARD_LAYOUT.name} record image is {len(image)} bytes against a declared record"
            f" length of {CARD_LAYOUT.reclen}; the published fields all end before the record"
            " does, so an image of the wrong length is rejected here rather than decoded from"
            " partially"
        )
    return image


def _require_decoded_characters(value: str | bytes, field: FieldSpec) -> str:
    """Require that a per-field decode produced characters rather than raw bytes.

    Purpose
    -------
    Narrow the per-field decoder's two-part result to the characters this record's regimes
    always yield, and refuse the byte-valued outcome explicitly instead of letting it reach a
    caller typed to receive text.

    Parameters
    ----------
    value : str | bytes
        One field's decoded result. The per-field decoder returns characters for the two
        display-text regimes and the untouched span for every computational regime.
    field : FieldSpec
        The descriptor the value was decoded from, used only to name the field and its
        geometry in a rejection.

    Returns
    -------
    str
        ``value`` unchanged, once it is proven to be characters.

    Raises
    ------
    LayoutError
        If the decode returned raw bytes, which means the field declares a computational
        regime that this reader publishes no representation for.
    """
    if isinstance(value, str):
        return value

    # WHY (Trade-offs): the rejection names the field's geometry and never renders the bytes
    #   themselves, not even as a length-bounded excerpt. A computational span is exactly where
    #   an amount or an identifier would sit, and this record's neighbouring spans hold a
    #   primary account number and a verification value, so a diagnostic that dumped an
    #   unexpected span could disclose either. Naming the field is enough to locate the defect,
    #   which is in the layout rather than in the data.
    raise LayoutError(
        f"field {field.describe()} of record {CARD_LAYOUT.name} decoded to raw bytes rather than"
        " characters, so it declares a computational regime; this reader publishes only the two"
        " display-text regimes, and a computational field must be decoded by the codec that"
        " owns its regime rather than published as text"
    )


def decode_ebcdic_card(record: bytes | bytearray | memoryview) -> DecodedCard:
    """Decode one card record from the EBCDIC dataset form.

    Purpose
    -------
    Turn a single fixed-length record image into the published decoded shape, delegating each
    field's character conversion to the EBCDIC codec's per-field entry point so that only the
    published fields are ever converted.

    Parameters
    ----------
    record : bytes | bytearray | memoryview
        One whole record image of exactly the declared record length, read in binary mode. A
        ``str`` is refused, because character data has already lost the byte values a
        fixed-length dataset is defined in terms of.

    Returns
    -------
    DecodedCard
        One entry per emitted data field, keyed by the field name exactly as the copybook
        spells it, in declaration order, with the pad dropped and the verification value
        suppressed. The shape is identical to the ASCII path's, which is what makes the two
        corpora comparable.

    Raises
    ------
    TypeError
        If the record is a ``str`` or is not convertible to a one-dimensional byte image.
    RecordLengthError
        If the image is not exactly the declared record length.
    EbcdicFieldDecodeError
        If a span does not decode to exactly one character per byte and re-encode to those
        same bytes. Raised by the EBCDIC codec.
    ZonedDecimalError
        If an unsigned display span is not the digits its picture clause requires. Raised by
        the display codec through the EBCDIC codec.
    LayoutError
        If a published field decodes to raw bytes, which means it declares a computational
        regime this reader publishes no representation for.
    """
    image = _require_full_record_image(record)

    # WHY (Assumptions): the conversion is DELEGATED per field and never performed on the
    #   record as a whole. That codec decodes each declared span on its own, dispatching on the
    #   descriptor's regime BEFORE any character set is applied, so no span of packed nibbles
    #   or low-value padding is ever handed to a character decoder. Decoding a whole record
    #   through a code page is the single most likely mistake on this path and the most
    #   damaging: it succeeds, preserves the declared width, and yields a record that looks
    #   almost right.
    # WHY (Assumptions): iterating the published field tuple is what makes suppression hold on
    #   this path too. The verification value's three bytes are inside `image` and are never
    #   passed to the decoder, so no decoded form of that value exists at any point in this
    #   function -- which is a stronger guarantee than decoding the record and dropping the
    #   key afterwards, where the value would exist for as long as the mapping did.
    return {
        field.name: _require_decoded_characters(decode_field(image, field), field)
        for field in LOADED_FIELDS
    }


def iter_ebcdic_cards(
    source: pathlib.PurePath | bytes | bytearray | memoryview | Iterable[object],
) -> Iterator[DecodedCard]:
    """Decode the EBCDIC dataset form of the card master, one record at a time.

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
    Iterator[DecodedCard]
        Each record in order, in the published decoded shape. A dataset with no bytes yields
        nothing at all.

    Raises
    ------
    TypeError
        If the source is a ``str``, which is ambiguous between a dataset location and
        character data somebody has already decoded.
    EbcdicRecordLengthError
        If the dataset does not divide into whole records of the declared length. This is a
        record-length error, so a caller guarding either encoding may catch the shared base
        type.
    EbcdicFieldDecodeError
        If a span does not decode to exactly one character per byte. Raised by the EBCDIC
        codec.
    ZonedDecimalError
        If an unsigned display span is not the digits its picture clause requires. Raised by
        the display codec through the EBCDIC codec.
    LayoutError
        If the source is neither a byte image nor readable nor iterable, or produces a piece
        that is not a byte object, or a published field decodes to raw bytes.
    """
    # WHY (Assumptions): the dataset is cut on the declared record length ALONE, and no line
    #   terminator is looked for, honoured, stripped or padded on this path. A fixed-length
    #   blocked dataset carries no terminators, so a byte that happens to equal a newline is
    #   field data -- a low-order digit of an identifier or a pad byte -- and splitting on it
    #   would produce a handful of pieces of wildly differing lengths, most of them cut through
    #   the middle of a field. The correctness test for this dataset is that its size divides by
    #   the declared record length with no remainder, which the delegated iterator checks before
    #   it yields the first record, and which the shipped extract satisfies exactly.
    # WHY (Assumptions): the text form's tolerances must never reach here. Right-padding a short
    #   piece or stripping a trailing byte would turn a genuine length failure into a plausible
    #   record, which is why this path reaches a different entry point of the copybook package
    #   and shares no code with the text one.
    for record in iter_ebcdic_records(source, CARD_LAYOUT):
        # WHY (Trade-offs): records are yielded one at a time for the same reason the text path
        #   streams -- constant memory in the record count, at the cost of one forward pass --
        #   so a caller can compare the two corpora record by record without either side
        #   holding a dataset in memory.
        yield decode_ebcdic_card(record)


def read_ebcdic_cards(path: pathlib.Path) -> Iterator[DecodedCard]:
    """Stream the card master from an EBCDIC dataset file at an explicit path.

    Purpose
    -------
    Read one named fixed-length dataset and stream its decoded records, with the size
    validated against the declared record length before the first record is produced.

    Parameters
    ----------
    path : pathlib.Path
        The exact dataset file to read. The caller names the file; this function never searches
        a directory for it.

    Returns
    -------
    Iterator[DecodedCard]
        Each record in order, in the published decoded shape. A zero-byte file yields nothing
        and is not an error.

    Raises
    ------
    OSError
        If the path cannot be inspected or opened.
    EbcdicRecordLengthError
        If the file size does not divide into whole records of the declared length.
    EbcdicFieldDecodeError
        If a span does not decode to exactly one character per byte. Raised by the EBCDIC
        codec.
    ZonedDecimalError
        If an unsigned display span is not the digits its picture clause requires. Raised by
        the display codec through the EBCDIC codec.
    LayoutError
        If a published field decodes to raw bytes.
    """
    # WHY (Assumptions): the caller supplies an EXPLICIT file here too, and the hazard is
    #   concrete rather than theoretical: the EBCDIC seed directory holds a zero-byte
    #   placeholder alongside more than a dozen datasets, so a directory pattern would match it
    #   and whatever else the pattern happened to catch. A zero-byte file is legitimately a
    #   dataset with no records, so it must yield nothing rather than raise -- and because that
    #   is indistinguishable from a placeholder once globbed, naming the file is what keeps
    #   "this dataset is empty" from being confused with "the pattern found the wrong thing".
    # WHY (Alternatives Considered): the path is handed to the codec rather than opened here and
    #   passed as a stream. The codec validates the file size against the declared record length
    #   BEFORE yielding a first record, so a truncated dataset fails up front instead of part
    #   way through a load, and it owns the open, the forward-only read and the close. Opening
    #   the file here would duplicate that lifecycle for no gain.
    yield from iter_ebcdic_cards(pathlib.PurePath(path))


def render_masked_card_record(record: str) -> str:
    """Render one character record with every sensitive field redacted.

    Purpose
    -------
    Give an operator a privacy-safe, byte-aligned rendering of a whole record, so a failed
    comparison can show a record's shape and which field differs without emitting a complete
    payment number or cardholder identity.

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
    RecordLengthError
        If the record is not the declared width. Raised by the shared masking helper, whose
        width check is the reason this rendering can be relied on to stay byte-aligned.
    """
    # WHY (Trade-offs): the primary account number and the embossed name are MASKED here
    #   rather than emitted, even though this same module emits both in full from its decode
    #   entry points. The two calls answer different questions: a decoded record feeds a loader
    #   that must write the real values, while this rendering feeds a human or a log. Masking
    #   shows WHERE two records differ without emitting a complete payment number or cardholder
    #   identity, and because the shared helper's mask is same-width and deterministic -- equal
    #   stored characters always render equally -- a masked diff still reveals which field
    #   changed and whether two masked records are equal. A random mask would destroy that
    #   property and a plaintext rendering would over-disclose, so the deterministic mask is
    #   the only one of the three that is both safe and diagnostically useful.
    # WHY (Assumptions): the redaction is delegated to the shared helper rather than applied
    #   here, so marking a field sensitive in the layout remains the ONLY change ever needed to
    #   redact it in this rendering. The helper reveals at most the trailing four characters of
    #   a payment number and otherwise substitutes a keyed tag that contains no part of the
    #   value, and it has no path that returns the plaintext of a sensitive field -- so a
    #   masking failure cannot degrade into disclosure, it can only raise.
    # WHY (Assumptions): the suppressed field's span is present in this rendering as a
    #   redaction rather than removed from it, and that is deliberate: the descriptor marks the
    #   field sensitive, so the helper replaces it with a tag holding none of its value, while
    #   keeping the rendering the declared width. Excising the span instead would shorten the
    #   record and move every offset after it, which would defeat the one thing a whole-record
    #   rendering is for. No part of the verification value appears in the result.
    return mask_record(record, CARD_LAYOUT)


def render_masked_card_field(record: str, field_name: str) -> str:
    """Render one named field of a character record with redaction applied.

    Purpose
    -------
    Produce a privacy-safe rendering of a single field, for a diagnostic that needs to show
    one field rather than a whole record. A field this reader suppresses cannot be rendered at
    all.

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
        A rendering of exactly that field's declared width: the characters verbatim when the
        field is not sensitive, otherwise a same-width redaction that reveals at most the
        trailing four characters of a payment number.

    Raises
    ------
    LayoutError
        If the named field is one this reader suppresses, if no field of that name is declared,
        or if the sliced span is not the field's declared width, which happens when the record
        is short.
    """
    # WHY (Alternatives Considered): a suppressed field is REFUSED here rather than rendered as
    #   a redaction, even though the shared helper would redact it safely and the whole-record
    #   rendering above does exactly that. The alternative was to allow it for consistency with
    #   that rendering, and it was rejected because the two cases differ in what the caller is
    #   asking for: the whole-record form needs the span present to keep later offsets
    #   countable, whereas a caller naming this field is asking for that field's value, and this
    #   reader publishes no representation of it. Refusing keeps the suppression contract
    #   uniform across every entry point, so the field cannot be reached through one door after
    #   being excluded from another -- and the caller learns the field is suppressed instead of
    #   receiving a tag it might mistake for a value it could compare.
    if field_name in SUPPRESSED_FIELD_NAMES:
        raise LayoutError(
            f"field {field_name!r} of record {CARD_LAYOUT.name} is suppressed by this reader and"
            " has no rendering; it is excluded from every decoded record, so there is no value"
            " here to redact and none is produced"
        )

    # WHY (Assumptions): the field is resolved through the layout by name and then sliced by its
    #   own declared span, so this module still states no offset of its own and an unknown name
    #   fails loudly here rather than silently rendering the wrong bytes.
    field = CARD_LAYOUT.field(field_name)
    return mask_field(field, record[field.start : field.end])
