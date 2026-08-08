"""Streaming reader for the CardDemo security user file, in both shipped encodings.

Purpose
-------
Turn the security user file extract into decoded records the Aurora loaders
and the verification passes can consume, one record at a time, from either of the two forms the
baseline ships: the
line-oriented ASCII seed and the fixed-length EBCDIC dataset. Both entry points yield the same
decoded shape, so a caller can swap corpora and compare the two without adapting to a second
contract.

This module follows the contract ``carddemo_migration.readers.account`` established and differs
from it only in which record descriptor it names. Where the reasoning behind a step is identical
to that module's it is referenced rather than restated, because two copies of one justification
drift apart and then one of them is wrong; where this record differs, the difference is recorded
here at the point it matters.

What this module reads
---------------------
The record is ``SEC-USER-DATA`` as declared in ``app/cpy/CSUSR01Y.cpy``, and its byte geometry is
resolved exclusively through ``SECUSER_LAYOUT`` in ``carddemo_migration.copybook.layouts``.
Assumptions: this record ships in ONE encoding only. ``app/data/EBCDIC`` holds
``AWS.M2.CARDDEMO.USRSEC.PS`` at 800 bytes -- ten records of eighty -- and
``app/data/ASCII`` holds no counterpart at all, which makes it the only base master
with no ASCII twin. The ASCII entry points below are still published rather than
omitted, because a caller legitimately holds converted text -- a fixture, or an extract
somebody transcoded upstream -- and a reader that refused it would push that caller into
writing its own record cut. What is NOT done is inventing a seed: nothing here creates
or converts a file, and ``app/**`` is REFERENCE-only.

Assumptions: the pad on this record is named ``SEC-USR-FILLER`` rather than ``FILLER``,
which is the one place the corpus qualifies the pad name. The pad rule below tests the
final hyphenated component for exactly that reason, so this record needs no special
case -- and a positional rule would have needed one.

Decoded shape
-------------
A decoded record is a ``dict`` keyed by the field name exactly as the copybook spells it, in
declaration order, which is the record's byte order. A character field maps to its characters at
full declared width, untrimmed.
The trailing pad is absent; see :data:`DROPPED_FIELD_NAMES`.

That shape is deliberately the one ``carddemo_migration.copybook.ebcdic_codec.decode_record``
publishes, minus the pad, which is what makes the two encodings comparable rather than merely
similar.

Security-file exposure control
------------------------------
Three of this record's four data fields are marked sensitive by the descriptor, and they
are NOT treated alike:

* The password is **suppressed**. Its bytes are never sliced, so it is never decoded and
  never a key in a returned mapping, and no representation of it -- plaintext, masked or
  digested -- is produced by any entry point here. The field's name and geometry may
  still appear in a diagnostic, because a fault has to be locatable and a refusal has to
  say what it refused; its content never does.
* Both name parts are **emitted** in the decoded mapping, because the target user table
  stores them, and **redacted** in every diagnostic this module produces.
* The user identifier and the user type stay verbatim. The identifier is a sign-on name
  rather than an account identifier -- it is the key an operator uses to locate a record
  -- and the type is a single character from the closed set the baseline defines.
* Diagnostics never echo record content of any kind, sensitive or otherwise.

Design decisions (WHY)
----------------------
Assumptions:
    **Every offset, length, storage regime and record length is imported, never declared.**
    This module states no byte position of its own, and in particular states no key width: the
    8-byte key is read from the descriptor's own ``key_offset`` and ``key_length``. That is
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
    **There is no binary floating-point value anywhere in this module.** This record declares no
    signed display field, so the prohibition costs nothing to honour here, but it is stated rather
    than left implicit: the identifiers this record carries exceed the range a binary float
    represents exactly, so routing one through a float would corrupt the very identifier the
    target table joins on. Identifiers stay character strings end to end.
Trade-offs:
    **Standard library plus ``carddemo_migration.copybook``, and nothing else.** No database
    driver, no AWS SDK and no character-set package is imported here, so this reader imports and
    runs on a bare checkout with no credential configured. Choosing a dataset, opening a
    connection and writing rows belong to a loader.
"""

from __future__ import annotations

import pathlib
from collections.abc import Iterable, Iterator
from typing import Final

# WHY : Assumptions: these imports are absolute and rooted at the distribution package rather
#   than relative, and the record descriptor named below is the ONLY statement of this record's
#   geometry anywhere in the package. A relative import is how the single-sourcing guarantee gets
#   broken quietly: a module moved between `readers/` and `loaders/` keeps importing successfully
#   but against a different sibling, and this project's ruff configuration bans relative imports
#   outright for that reason.
from carddemo_migration.copybook.ebcdic_codec import decode_field, iter_ebcdic_records
from carddemo_migration.copybook.layouts import (
    SECUSER_LAYOUT,
    FieldSpec,
    Kind,
    LayoutError,
    RecordLengthError,
    iter_ascii_text_records,
    mask_field,
    mask_record,
)

__all__ = [
    "SECUSER_LAYOUT",
    "DROPPED_FIELD_NAMES",
    "LOADED_FIELDS",
    "DecodedSecurityUser",
    "SUPPRESSED_FIELD_NAMES",
    "decode_ascii_security_user",
    "decode_ebcdic_security_user",
    "iter_ascii_security_users",
    "iter_ebcdic_security_users",
    "read_ascii_security_users",
    "read_ebcdic_security_users",
    "record_key",
    "render_masked_security_user_field",
    "render_masked_security_user_record",
]

# WHY : Assumptions: the decoded value type is `str` alone because every field this record
#   declares is a character field, so no decoded value is ever a number and this module
#   has no money path. The type is exact rather than widened to the record decoder's
#   `str | bytes` union because neither entry point here calls the whole-record decoder; see
#   the note on per-field decoding below.
# WHY : Trade-offs: both entry points build the row by decoding `LOADED_FIELDS` ONE FIELD AT A
#   TIME, rather than decoding the whole record and then projecting it as the nine sibling flat
#   readers do. That difference is deliberate and it is specific to this record: the suppressed
#   field is the baseline's PLAINTEXT PASSWORD, and decoding the record whole would materialise
#   that value in memory before dropping it, leaving it recoverable for as long as the
#   intermediate mapping lived. Decoding only the published fields means the password is never
#   decoded at all, which is strictly stronger than decoding it and discarding it. The cost is
#   that this reader cannot share the siblings' project-after-decode helper, which is why it does
#   not have one.
DecodedSecurityUser = dict[str, str]

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


# WHY : Alternatives Considered: the eight-character password is SUPPRESSED rather than masked,
#   and suppression is chosen for the same reason the card reader suppresses a verification value.
#   Masking was available at no cost -- the descriptor already marks the field sensitive and the
#   shared helper already redacts it to a same-width tag -- and it was rejected because redaction
#   changes only how a value RENDERS while still carrying the value itself through this module's
#   return path, where any caller could read it out of the mapping. This particular value has no
#   legitimate downstream consumer at all: the migration replaces sign-on with a managed identity
#   provider, the target user table carries only a subject reference, and `app/cpy/CSUSR01Y.cpy`
#   line 21 stores the password in CLEAR, so what this field holds is every user's actual
#   credential. The correct handling of a value that must never be reproduced is not to reproduce
#   it, and the AAP records the field as deliberately not carried forward.
# WHY : Trade-offs: what suppression costs is that a password mismatch between two extracts cannot
#   be diagnosed from this reader's output, because neither the value nor a comparable digest of it
#   is emitted. That cost is accepted: a reader that could confirm a credential is a reader that
#   could disclose one. A caller needing to prove two images agree byte for byte can compare the
#   raw images without decoding them through this module at all.
SUPPRESSED_FIELD_NAMES: Final[frozenset[str]] = frozenset({"SEC-USR-PWD"})


# WHY : Assumptions: the pad is DROPPED from every decoded record because its 23 trailing
#   bytes pad the record out to its fixed 80-byte length and carry no data. The EBCDIC
#   record decoder deliberately returns it, stating that dropping it is a projection decision
#   belonging to the reader that maps a record onto a table, so this is that decision and this is
#   where it is taken. Both names below are published rather than kept private so the drop is a
#   fact a caller and a verification pass can assert, instead of a silent omission that would
#   make a decoded record a partial description of the bytes it came from.
LOADED_FIELDS: Final[tuple[FieldSpec, ...]] = tuple(
    field
    for field in SECUSER_LAYOUT.fields
    if not _is_padding_field(field)
    if field.name not in SUPPRESSED_FIELD_NAMES
)
DROPPED_FIELD_NAMES: Final[frozenset[str]] = frozenset(
    field.name for field in SECUSER_LAYOUT.fields if _is_padding_field(field)
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
    for field in SECUSER_LAYOUT.fields:
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
    #   adopted here because this record's fifty-seven data bytes include
    #   an eight-character password the baseline stores in CLEAR, so echoing raw content
    #   would emit a live credential.
    location = "beyond the declared record" if field is None else f"in field {field.describe()}"
    raise RecordLengthError(
        f"record {number} of {SECUSER_LAYOUT.name} holds a character outside the single-byte"
        f" range at zero-based offset {offset}, {location}; a multi-byte character satisfies"
        f" the {SECUSER_LAYOUT.reclen}-character width check while occupying more bytes, which"
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
        f"field {field.describe()} of record {SECUSER_LAYOUT.name} declares a storage regime"
        " that cannot be decoded from a character record; only the character and display regimes"
        " can, and a computational or mixed-regime area must be read from the byte image through"
        " the EBCDIC record decoder"
    )


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
    if len(record) < SECUSER_LAYOUT.reclen:
        raise RecordLengthError(
            f"a {SECUSER_LAYOUT.name} record of {len(record)} characters is shorter than the"
            f" declared {SECUSER_LAYOUT.reclen}, so its"
            f" {SECUSER_LAYOUT.key_length}-character key cannot be sliced"
        )
    start = SECUSER_LAYOUT.key_offset
    return record[start : start + SECUSER_LAYOUT.key_length]


def decode_ascii_security_user(
    record: str,
    *,
    number: int = 1,
) -> DecodedSecurityUser:
    """Decode one security user record from the ASCII seed form.

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
    DecodedSecurityUser
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
    if len(record) != SECUSER_LAYOUT.reclen:
        raise RecordLengthError(
            f"record {number} of {SECUSER_LAYOUT.name} is {len(record)} characters against a"
            f" declared width of {SECUSER_LAYOUT.reclen}; a record of the wrong width means the"
            " field offsets have moved, so it is rejected rather than padded or truncated"
        )

    checked = _require_single_byte_record(record, number)

    # WHY : Assumptions: the fields are walked in the descriptor's declaration order, which is the
    #   record's byte order, so the resulting mapping iterates the record left to right. The pad
    #   is excluded by iterating the published field tuple rather than by decoding every field and
    #   filtering afterwards, which also avoids decoding 23 bytes of pad on every record.
    return {field.name: _decode_text_field_value(checked, field) for field in LOADED_FIELDS}


def iter_ascii_security_users(
    source: str | Iterable[object],
) -> Iterator[DecodedSecurityUser]:
    """Decode the ASCII seed form of the security user file, one record at a time.

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
    Iterator[DecodedSecurityUser]
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
    # WHY : Trade-offs: that iterator's tolerance for a SHORT line -- right-padding it
    #   with blanks -- is inherited deliberately. No ASCII seed ships for this record, so
    #   the tolerance engages only for whatever text a caller supplies, and padding on the
    #   right cannot move a field that is present.
    records = iter_ascii_text_records(source, SECUSER_LAYOUT.reclen)

    for number, record in enumerate(records, start=1):
        yield decode_ascii_security_user(record, number=number)


def read_ascii_security_users(path: pathlib.Path) -> Iterator[DecodedSecurityUser]:
    """Stream the security user file from an ASCII seed file at an explicit path.

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
    Iterator[DecodedSecurityUser]
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
        yield from iter_ascii_security_users(handle)


def _require_full_record_image(record: bytes | bytearray | memoryview) -> bytes:
    """Require a byte image of exactly the declared record length.

    Purpose
    -------
    Check the image's length BEFORE any field is sliced out of it, so that the per-field path
    reaches the same length verdict the record-oriented decoder would have reached.

    Parameters
    ----------
    record : bytes | bytearray | memoryview
        One whole record image.

    Returns
    -------
    bytes
        The same bytes, normalised to ``bytes`` and proven to be the declared length.

    Raises
    ------
    RecordLengthError
        If the image is not exactly the declared record length.
    TypeError
        If the object is not a one-dimensional image of single bytes.
    """
    # WHY : Assumptions: the length is checked HERE and not delegated, because the per-field
    #   decoder validates only the span it is given: an image one byte short would satisfy every
    #   field whose span ended before the truncation and fail only on the last one, so the fault
    #   would be reported as a field error rather than as the record-length error it is.
    # WHY : Trade-offs: the image is normalised to `bytes` rather than passed on as whatever view
    #   arrived. A memoryview reports its length in ITEMS, not bytes, so a view with a wider
    #   element format would satisfy a naive length test while spanning a different number of
    #   bytes; converting first makes the length compared here the length in bytes. The accepted
    #   cost is one copy per record, bounded by the record length.
    image = bytes(record)
    if len(image) != SECUSER_LAYOUT.reclen:
        raise RecordLengthError(
            f"a {SECUSER_LAYOUT.name} record image is {len(image)} bytes against a declared record"
            f" length of {SECUSER_LAYOUT.reclen}; the published fields all end before the record"
            " does, so an image of the wrong length is rejected here rather than decoded from"
            " partially"
        )
    return image


def _require_decoded_characters(value: str | bytes, field: FieldSpec) -> str:
    """Require that a per-field decode produced characters rather than raw bytes.

    Purpose
    -------
    Narrow the per-field decoder's two-part result to the characters this record's one regime
    always yields, and refuse the byte-valued outcome explicitly instead of letting it reach a
    caller typed to receive text.

    Parameters
    ----------
    value : str | bytes
        One field's decoded result. The per-field decoder returns characters for a character
        field and the untouched span for every computational regime.
    field : FieldSpec
        The descriptor the value was decoded from, used only to name the field and its geometry
        in a rejection.

    Returns
    -------
    str
        ``value`` unchanged, once it is proven to be characters.

    Raises
    ------
    LayoutError
        If the decode returned raw bytes, which means the field declares a computational regime
        this reader publishes no representation for.
    """
    if isinstance(value, str):
        return value

    # WHY : Trade-offs: the rejection names the field's geometry and never renders the bytes
    #   themselves, not even as a length-bounded excerpt. A computational span on this record
    #   would sit among a cleartext password and two name parts, so a diagnostic that dumped an
    #   unexpected span could disclose any of them. Naming the field is enough to locate the
    #   defect, which is in the layout rather than in the data.
    raise LayoutError(
        f"field {field.describe()} of record {SECUSER_LAYOUT.name} decoded to raw bytes rather"
        " than characters, so it declares a computational regime; this reader publishes only the"
        " character regime, and a computational field must be decoded by the codec that owns its"
        " regime rather than published as text"
    )


def decode_ebcdic_security_user(
    record: bytes | bytearray | memoryview,
) -> DecodedSecurityUser:
    """Decode one security user record from the EBCDIC dataset form.

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
    DecodedSecurityUser
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
    # WHY : Assumptions: iterating the published field tuple is what makes suppression hold on
    #   this path too. The password's eight bytes are inside `image` and are never passed to the
    #   decoder, so no decoded form of that value exists at any point in this function -- which is
    #   a stronger guarantee than decoding the record and dropping the key afterwards, where the
    #   value would exist for as long as the mapping did.
    image = _require_full_record_image(record)
    return {
        field.name: _require_decoded_characters(decode_field(image, field), field)
        for field in LOADED_FIELDS
    }


def iter_ebcdic_security_users(
    source: pathlib.PurePath | bytes | bytearray | memoryview | Iterable[object],
) -> Iterator[DecodedSecurityUser]:
    """Decode the EBCDIC dataset form of the security user file, one record at a time.

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
    Iterator[DecodedSecurityUser]
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
    #   terminator is looked for, honoured, stripped or padded on this path. A fixed-length
    #   blocked dataset carries no terminators, so a byte that happens to equal a newline is field
    #   data -- a low-order digit, a packed nibble pair or a pad byte -- and splitting on it would
    #   produce pieces of wildly differing lengths, most cut through the middle of a field. The
    #   correctness test for this dataset is that its size divides by the declared record length
    #   with no remainder, which the delegated iterator checks before it yields the first record.
    # WHY : Assumptions: the text form's tolerances must never reach here. Right-padding a short
    #   piece or stripping a trailing byte would turn a genuine length failure into a plausible
    #   record, which is why this path reaches a different entry point of the layouts module and
    #   shares no code with the text one.
    for record in iter_ebcdic_records(source, SECUSER_LAYOUT):
        yield decode_ebcdic_security_user(record)


def read_ebcdic_security_users(path: pathlib.Path) -> Iterator[DecodedSecurityUser]:
    """Stream the security user file from an EBCDIC dataset file at an explicit path.

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
    Iterator[DecodedSecurityUser]
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
    # WHY : Alternatives Considered: the path is handed to the codec rather than opened here and
    #   passed as a stream. The codec validates the file size against the declared record length
    #   BEFORE yielding a first record, so a truncated dataset fails up front instead of part way
    #   through a load, and it owns the open, the forward-only read and the close. Opening the file
    #   here would duplicate that lifecycle for no gain.
    yield from iter_ebcdic_security_users(pathlib.PurePath(path))


def render_masked_security_user_record(record: str) -> str:
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
    #   it in this rendering. The suppressed field's span is present in this
    #   rendering as a redaction rather than removed from it: the descriptor marks it
    #   sensitive so the helper replaces it with a tag holding none of its value, while
    #   keeping the rendering the declared width. Excising the span instead would shorten
    #   the record and move every offset after it, defeating the one thing a whole-record
    #   rendering is for. No part of the password appears in the result.
    return mask_record(record, SECUSER_LAYOUT)


def render_masked_security_user_field(record: str, field_name: str) -> str:
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
    LayoutError
        If the named field is the one this reader suppresses, which has no rendering at
        all.
    """
    # WHY : Alternatives Considered: a suppressed field is REFUSED here rather than
    #   rendered as a redaction, even though the shared helper would redact it safely and
    #   the whole-record rendering above does exactly that. The two cases differ in what
    #   the caller is asking for: the whole-record form needs the span present to keep
    #   later offsets countable, whereas a caller naming this field is asking for that
    #   field's value, and this reader publishes no representation of it. Refusing keeps
    #   the suppression contract uniform across every entry point, so the field cannot be
    #   reached through one door after being excluded from another -- and the caller learns
    #   the field is suppressed instead of receiving a tag it might mistake for a value it
    #   could compare.
    if field_name in SUPPRESSED_FIELD_NAMES:
        raise LayoutError(
            f"field {field_name!r} of record {SECUSER_LAYOUT.name} is suppressed by this"
            " reader and has no rendering; it is excluded from every decoded record, so"
            " there is no value here to redact and none is produced"
        )

    # WHY : Assumptions: the field is resolved through the layout by name and then sliced by its
    #   own declared span, so this module still states no offset of its own and an unknown name
    #   fails loudly here rather than silently rendering the wrong bytes. Resolution is scoped to
    #   THIS record's descriptor rather than to a package-wide field table, which is what keeps a
    #   name declared over different children in a sibling copybook from resolving to the wrong
    #   geometry.
    field = SECUSER_LAYOUT.field(field_name)
    return mask_field(field, record[field.start : field.end])
