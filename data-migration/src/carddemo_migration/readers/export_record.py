"""Streaming reader for the CardDemo export dataset, in the one encoding it ships in.

Purpose
-------
Turn the export extract into decoded records the Aurora loaders and the verification passes can
consume, one record at a time. Each record is a fixed 500-byte envelope carrying ONE of five
different payload shapes, chosen at run time by the envelope's own first byte, so this reader's
distinguishing work is a dispatch the other eleven readers in this package do not have to make.

This module follows the contract ``carddemo_migration.readers.account`` established -- the same
projection discipline, the same streaming shape, the same refusal to state a byte position of its
own -- and departs from it in exactly three ways, each recorded at the point it matters below: the
decode is two-step rather than one, there is no text form of this record at all, and the decoded
shape is a function of the record's own discriminator rather than a constant.

What this module reads
---------------------
The record is ``EXPORT-RECORD`` as declared in ``app/cpy/CVEXPORT.cpy``, and its byte geometry is
resolved exclusively through ``EXPORT_HEADER_LAYOUT``, ``EXPORT_RECORD_TYPES`` and
``export_branch`` in ``carddemo_migration.copybook.layouts``. The shipped extract is
``app/data/EBCDIC/AWS.M2.CARDDEMO.EXPORT.DATA.PS``: 250,000 bytes, being 500 records of 500.

Assumptions: this record ships in ONE encoding only, and unlike the security file -- the other
single-encoding master -- that is not merely a fact about which files the baseline committed. A
text form of THIS record cannot exist. Its five overlays declare three ``COMP-3`` amounts, seven
``COMP`` binary identifiers and a ``COMP`` card verification value, and the shipped extract was
measured to use all 256 byte values, including 4,153 NUL bytes and five ``0x0A`` bytes sitting
INSIDE binary sequence numbers. Those spans are not characters in any code page, so no character
rendering of the record round-trips, and splitting the file on a newline yields six pieces where
it holds five hundred records. This module therefore publishes byte-mode entry points ONLY, and
the absence of the ``decode_ascii_*`` / ``iter_ascii_*`` / ``read_ascii_*`` trio its siblings
publish is the deliberate, visible signal that no text form is available to read.

Decoded shape
-------------
A decoded record is a ``dict`` keyed by the field name exactly as the copybook spells it, in
declaration order: first the envelope's own data fields, then the fields of the branch that
record's discriminator selected. A character field maps to its characters at full declared width,
untrimmed; every numeric regime -- unsigned display, signed display, packed and binary -- maps to
an exact :class:`decimal.Decimal`, except unsigned display, which stays characters so a leading
zero survives.

Two entries a caller might expect are absent, and both absences are projections this reader makes
deliberately rather than omissions; see :data:`DROPPED_FIELD_NAMES` and
:data:`SUPPRESSED_FIELD_NAMES`.

Assumptions: the two halves are FLATTENED into one mapping here, where the codec that decodes them
deliberately returns a pair. The codec's two stated reasons for keeping them apart are both
answered before the merge rather than argued away. Its first reason is that the envelope's opaque
payload entry would otherwise sit beside the decoded interior it stands for, inviting a reader to
treat 460 raw bytes as a second, disagreeing description of the same fields: that entry is dropped
here, so it is not in the mapping to be mistaken for anything. Its second reason is that a branch
could in principle declare a name the envelope also declares, silently losing one of them: that
is CHECKED at import over all five branches rather than assumed, and a collision raises before any
record is read. What the merge buys is that a loader receives one row-shaped mapping per record
and reads the discriminator out of it as an ordinary field, instead of unpacking a pair whose
second half it must then decide the shape of.

Assumptions: there is no single ``LOADED_FIELDS`` tuple on this reader, and the omission is the
same one the layouts module makes when it declines to give the export record a name-keyed registry
entry: this record has five decoded shapes, so one tuple would have to pick a branch and be wrong
for the other four. :data:`ENVELOPE_LOADED_FIELDS` and :func:`branch_loaded_fields` publish the
two halves a caller can actually rely on, and a caller wanting the full field list for one record
composes them using that record's own :func:`record_type`.

Export-record exposure control
------------------------------
This one record carries more sensitive data than any other in the corpus -- a primary account
number in three of its five branches, a card verification value, a national identifier, a
government-issued identifier, a date of birth, and six account-borne money fields -- so the
disclosure decisions are stated here in full:

* The card verification value is **suppressed**. Its bytes are never handed to a decoder, so no
  representation of it exists at any point in this module: not cleartext, not masked, not
  digested. This mirrors ``carddemo_migration.readers.card`` exactly, and it has to: a value the
  AAP records as never returned by any endpoint cannot be readable through a second door.
* The opaque payload area is **dropped**, which removes the one path by which a caller could hold
  the branch's raw bytes -- primary account number and verification value included -- without
  decoding them.
* Every other sensitive field is **emitted** decoded, because the target tables store what they
  hold, and **redacted** in the masked renderings this module publishes for diagnostics.
* Diagnostics never echo record content. A refusal names a field's declared geometry and nothing
  else, which is enough to locate a defect that is in a layout rather than in the data.

Assumptions: the masked renderings redact this record's card numbers and national identifier as
KEYED TAGS and not as a last-four reveal, where the equivalent base-master fields reveal their
last four characters. That asymmetry is inherited rather than corrected, and it is the
conservative direction: the concession exists so an operator can identify which card a row belongs
to, and the shared helper grants it by field name to the five base-master fields that carry it.
The export branches' fields are differently named, so they fall through to the tag. Nothing is
disclosed by the difference, and cross-record linkage is absent by design in any case -- the tag
folds the field name into its derivation, so one card number under two field names produces two
unrelated tags.

Design decisions (WHY)
----------------------
Assumptions:
    **The branch is chosen from the ENVELOPE and never guessed from the payload.** All five
    overlays are exactly 460 bytes, so decoding a payload against the wrong branch does not raise,
    does not change the record's width and does not invalidate a later offset: it returns a full
    set of well-formed, meaningless values -- a card number read out of a customer's address, a
    money amount read out of a merchant name. The dispatch is delegated to ``export_branch``,
    which refuses an unknown discriminator rather than defaulting to a branch, and that refusal is
    the only thing standing between a corrupt extract and five hundred plausible rows.
Assumptions:
    **Every offset, length, storage regime and record length is imported, never declared.** This
    module states no byte position of its own -- not the payload offset, not the branch length,
    not the key width, and not even the position of the discriminator, which is taken as the
    envelope's first field and then checked against the published discriminator width. That is
    the Python analogue of compiling every COBOL program against a single ``cobc -I app/cpy``
    include path, and the consequence of breaking it is undetectable: a record read one byte out
    of alignment still decodes to plausible values, so nothing raises and no test fails.
Trade-offs:
    **Records are streamed, never materialised.** Both iterating entry points are generators, so
    memory is constant in the record count. The accepted cost is a single forward pass; what it
    buys is that this reader behaves identically on the committed 250 KB extract and on a
    production extract many orders larger, so the one proven against the extract is the one that
    runs.
Assumptions:
    **There is no binary floating-point value anywhere in this module.** Money reaches a caller as
    :class:`decimal.Decimal` at the scale the field declares and identifiers reach it as exact
    integers or as characters, because this record's amounts are the balances and credit limits a
    posting run is reconciled against and its identifiers exceed the range a binary float
    represents exactly. A single ``float`` in this path would corrupt both.
Assumptions:
    **The key is the layout-supported one at zero-based offset 27, not the one the dataset
    definition declares.** ``app/cbl/CBEXPORT.cbl`` line 68 declares ``RECORD KEY IS
    EXPORT-SEQUENCE-NUM``, which the record layout places at offset 27 for four bytes;
    ``app/jcl/CBEXPORT.jcl`` line 32 declares ``KEYS(4 28)``, which straddles the last three bytes
    of that field and the first byte of ``EXPORT-BRANCH-ID``. The layouts module records both, as
    ``EXPORT_KEY_OFFSET`` and ``EXPORT_DECLARED_DATASET_KEY_OFFSET``, and this reader uses the
    descriptor's own key geometry -- so the disagreement is a documented divergence that
    corroborates the known baseline defect in this program pair, and not something this module
    re-decides. Nothing under ``app/`` is touched: this reader reads the extract the baseline
    already shipped.
Trade-offs:
    **Standard library plus ``carddemo_migration.copybook``, and nothing else.** No database
    driver, no AWS SDK and no character-set package is imported here, so this reader imports and
    runs on a bare checkout with no credential configured. Choosing a dataset, opening a
    connection and writing rows belong to a loader.
"""

from __future__ import annotations

import pathlib
from collections.abc import Iterable, Iterator, Mapping
from decimal import Decimal
from types import MappingProxyType
from typing import Final

# WHY : Assumptions: these imports are absolute and rooted at the distribution package rather
#   than relative, and the descriptors named below are the ONLY statement of this record's
#   geometry anywhere in the package. A relative import is how the single-sourcing guarantee gets
#   broken quietly: a module moved between `readers/` and `loaders/` keeps importing successfully
#   but against a different sibling, and this project's ruff configuration bans relative imports
#   outright for that reason.
from carddemo_migration.copybook.ebcdic_codec import (
    decode_export_record,
    decode_field,
    iter_ebcdic_records,
)
from carddemo_migration.copybook.layouts import (
    EXPORT_HEADER_LAYOUT,
    EXPORT_RECORD_TYPES,
    RECORD_TYPE_LENGTH,
    FieldSpec,
    Kind,
    LayoutError,
    RecordLengthError,
    RecordSpec,
    export_branch,
    mask_rendered_value,
)

__all__ = [
    "EXPORT_HEADER_LAYOUT",
    "DROPPED_FIELD_NAMES",
    "ENVELOPE_LOADED_FIELDS",
    "DecodedExportRecord",
    "SUPPRESSED_FIELD_NAMES",
    "branch_loaded_fields",
    "decode_ebcdic_export_record",
    "iter_ebcdic_export_records",
    "read_ebcdic_export_records",
    "record_key",
    "record_type",
    "render_masked_export_field",
    "render_masked_export_record",
]

# WHY : Assumptions: the decoded value type is `str | Decimal` because this record spans every
#   regime the copybooks use: character and unsigned-display fields decode to characters, and
#   signed-display, packed and binary fields decode to an exact Decimal. `float` appears nowhere
#   in the union, which is the type-level half of the prohibition the module docstring states.
DecodedExportRecord = dict[str, str | Decimal]

# WHY : Assumptions: the trailing pad is identified by NAME and not by position, exactly as in
#   every sibling reader, and the convention was measured rather than assumed: each of the five
#   branch overlays declares exactly one pad, named `FILLER`, running from the end of its data to
#   the 460-byte branch length. Testing the name keeps this module free of any byte position of
#   its own; the hyphen-suffixed form is admitted too so this rule stays identical across the
#   package, where one record qualifies the pad name.
_PAD_FIELD_NAME: Final[str] = "FILLER"
_PAD_NAME_SUFFIX: Final[str] = f"-{_PAD_FIELD_NAME}"


def _is_padding_field(field: FieldSpec) -> bool:
    """Report whether a field is a record's trailing pad rather than data.

    Purpose
    -------
    Decide, from the field's declared name alone, whether it exists only to fill a record or a
    branch overlay out to its fixed length. This is the single place that judgement is made, so
    the projection and the record of what was dropped cannot disagree about it.

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


def _sole_opaque_field(layout: RecordSpec) -> FieldSpec:
    """Return the one field of a layout declared in the opaque regime.

    Purpose
    -------
    Identify the envelope's payload area by the REGIME it declares rather than by its name, and
    prove while doing so that the envelope declares exactly one such area.

    Parameters
    ----------
    layout : RecordSpec
        The layout to search. Only each field's ``kind`` is consulted.

    Returns
    -------
    FieldSpec
        The single opaque field.

    Raises
    ------
    LayoutError
        If the layout declares no opaque field, or declares more than one, in which case this
        module cannot know which area the branch overlays redefine.
    """
    # WHY : Alternatives Considered: the payload area is found by its declared regime and not by
    #   the string "EXPORT-RECORD-DATA". Matching on the name was the shorter option and was
    #   rejected because it restates in this module a fact the layout already carries, and the two
    #   then drift: a renamed area would leave this module silently publishing 460 raw bytes --
    #   the primary account number and verification value among them -- as an ordinary decoded
    #   field. The regime cannot drift, because it is what makes the two-step decode necessary in
    #   the first place.
    opaque = tuple(field for field in layout.fields if field.kind is Kind.OPAQUE)
    if len(opaque) != 1:
        raise LayoutError(
            f"record {layout.name} declares {len(opaque)} fields in the {Kind.OPAQUE.name}"
            " regime but this reader requires exactly one, because that single area is what the"
            " branch overlays redefine and what the two-step decode replaces with a decoded"
            " branch"
        )
    return opaque[0]


def _validated_discriminator_field(layout: RecordSpec) -> FieldSpec:
    """Return the envelope's record-type discriminator, checked against its published width.

    Purpose
    -------
    Resolve the field the branch dispatch reads, taking its position from the layout rather than
    from a literal, and refuse a layout in which that field is not the single leading character
    byte the copybook declares.

    Parameters
    ----------
    layout : RecordSpec
        The envelope layout whose first declared field is the discriminator.

    Returns
    -------
    FieldSpec
        The discriminator's descriptor.

    Raises
    ------
    LayoutError
        If the layout declares no fields, or its first field does not begin at offset zero, is not
        the published discriminator width, or is not a character field -- any of which would mean
        the dispatch below would read a span the layout does not describe.
    """
    # WHY : Assumptions: the discriminator is taken as the envelope's FIRST field and then checked
    #   against `RECORD_TYPE_LENGTH`, rather than looked up by name. The layouts module documents
    #   the discriminator as occupying the first byte of every export record and publishes that
    #   width as a constant precisely so the layout and every dispatch agree on it; checking the
    #   two against each other here turns that agreement into something this module proves at
    #   import instead of assuming per record.
    if not layout.fields:
        raise LayoutError(
            f"record {layout.name} declares no fields, so it has no record-type discriminator to"
            " dispatch a payload branch on"
        )
    field = layout.fields[0]
    if field.start != 0 or field.length != RECORD_TYPE_LENGTH or field.kind is not Kind.TEXT:
        raise LayoutError(
            f"the leading field {field.describe()} of record {layout.name} is not the"
            f" record-type discriminator this reader dispatches on, which the layouts module"
            f" publishes as {RECORD_TYPE_LENGTH} character byte(s) at offset 0; a dispatch"
            " reading any other span would classify every record by the wrong byte"
        )
    return field


_PAYLOAD_FIELD: Final[FieldSpec] = _sole_opaque_field(EXPORT_HEADER_LAYOUT)
_DISCRIMINATOR_FIELD: Final[FieldSpec] = _validated_discriminator_field(EXPORT_HEADER_LAYOUT)

# WHY : Alternatives Considered: the card verification value is SUPPRESSED rather than masked,
#   for the same reason `carddemo_migration.readers.card` suppresses the base master's copy of it.
#   Masking was available at no cost -- the descriptor already marks the field sensitive and the
#   shared helper already redacts it -- and was rejected because redaction changes only how a
#   value RENDERS while still carrying the value itself through this module's return path, where
#   any caller could read it out of the mapping. The AAP records the verification value as never
#   returned by any endpoint, and a value that must never be reproduced is not reproduced.
# WHY : Assumptions: suppressing it HERE as well as in the card reader is what makes that
#   contract hold rather than merely appear to. The export extract's card branch carries the same
#   three digits as the card master, so a verification value excluded from one reader and emitted
#   by another is not excluded at all -- it is reachable through the second door, from a dataset
#   whose whole purpose is to leave the system.
# WHY : Trade-offs: what suppression costs is that a verification-value mismatch between the card
#   master and the export extract cannot be diagnosed from this reader's output, because neither
#   the value nor a comparable digest of it is emitted. That cost is accepted: a reader that could
#   confirm a verification value is a reader that could disclose one, and a caller needing to
#   prove two images agree can compare the raw images without decoding them through this module.
SUPPRESSED_FIELD_NAMES: Final[frozenset[str]] = frozenset({"EXP-CARD-CVV-CD"})

# WHY : Assumptions: the envelope's data fields are published minus the opaque payload area,
#   because that area is not data at this level: it is the 460 bytes the selected branch decodes,
#   and the decoded branch is what this reader returns in its place. Publishing both would put a
#   raw and a decoded description of the same bytes in one mapping, and the raw one would carry
#   every sensitive value the branch declares in a form no masking helper can see into.
ENVELOPE_LOADED_FIELDS: Final[tuple[FieldSpec, ...]] = tuple(
    field
    for field in EXPORT_HEADER_LAYOUT.fields
    if field is not _PAYLOAD_FIELD
    if not _is_padding_field(field)
)


def _branch_loaded_fields(branch: RecordSpec) -> tuple[FieldSpec, ...]:
    """Return one branch overlay's data fields, with the pad and the suppressed field removed.

    Purpose
    -------
    Apply this reader's projection to a single branch overlay, so the decode path and the field
    inventory a caller reads are produced by one function rather than by two that could diverge.

    Parameters
    ----------
    branch : RecordSpec
        One of the five payload overlays.

    Returns
    -------
    tuple[FieldSpec, ...]
        The branch's fields in declaration order, without its trailing pad and without any field
        this reader suppresses.

    Raises
    ------
    None
    """
    return tuple(
        field
        for field in branch.fields
        if not _is_padding_field(field)
        if field.name not in SUPPRESSED_FIELD_NAMES
    )


def _projected_branch_fields() -> Mapping[str, tuple[FieldSpec, ...]]:
    """Build the per-branch field inventories, proving no branch name collides with the envelope.

    Purpose
    -------
    Compute once, at import, the projected field tuple for each of the five overlays, and verify
    while doing so that flattening a branch into the envelope's mapping cannot lose a field.

    Returns
    -------
    Mapping[str, tuple[FieldSpec, ...]]
        A read-only mapping from each branch layout's name to its projected fields.

    Raises
    ------
    LayoutError
        If any branch declares a field name the envelope also declares, which would make the
        flattened decoded mapping silently drop one of the two.
    """
    # WHY : Assumptions: the collision check is the reason this runs at IMPORT rather than per
    #   record. Whether a branch name shadows an envelope name is a property of the layouts and
    #   not of any record's bytes, so proving it once means no record can be decoded against a
    #   colliding pair -- where a per-record check would raise on the first record of a corrupt
    #   deployment and pass on none of them, which is the same outcome reached far later.
    # WHY : Alternatives Considered: raising at import was weighed against silently prefixing one
    #   half's keys to make a collision impossible. Prefixing was rejected because it would change
    #   the published decoded shape -- keys are the copybook's own spelling, which is what lets a
    #   loader map a field to a column and a verification pass compare two corpora -- and it would
    #   hide a genuine layout defect behind a rename.
    envelope_names = frozenset(field.name for field in ENVELOPE_LOADED_FIELDS)
    projected: dict[str, tuple[FieldSpec, ...]] = {}
    for branch in EXPORT_RECORD_TYPES.values():
        fields = _branch_loaded_fields(branch)
        collisions = sorted(field.name for field in fields if field.name in envelope_names)
        if collisions:
            raise LayoutError(
                f"branch {branch.name} declares field name(s) {', '.join(collisions)} that record"
                f" {EXPORT_HEADER_LAYOUT.name} also declares; this reader returns one flattened"
                " mapping per record, so a shared name would silently drop one of the two fields"
            )
        projected[branch.name] = fields
    return MappingProxyType(projected)


_BRANCH_LOADED_FIELDS: Final[Mapping[str, tuple[FieldSpec, ...]]] = _projected_branch_fields()

# WHY : Assumptions: the dropped names are published rather than kept private so the drop is a
#   fact a caller and a verification pass can assert, instead of a silent omission that would make
#   a decoded record a partial description of the bytes it came from. Two names appear: the
#   envelope's opaque payload area, replaced by the decoded branch, and the pad every branch
#   declares to fill its overlay out to the 460-byte branch length.
DROPPED_FIELD_NAMES: Final[frozenset[str]] = frozenset(
    {_PAYLOAD_FIELD.name}
    | {field.name for field in EXPORT_HEADER_LAYOUT.fields if _is_padding_field(field)}
    | {
        field.name
        for branch in EXPORT_RECORD_TYPES.values()
        for field in branch.fields
        if _is_padding_field(field)
    }
)


def _require_full_record_image(record: bytes | bytearray | memoryview) -> bytes:
    """Require one whole export record image of exactly the declared record length.

    Purpose
    -------
    Reject a short, long or partial image before any span is read out of it, so a caller reading
    the discriminator or the key from a truncated record learns that rather than receiving bytes
    from the wrong field.

    Parameters
    ----------
    record : bytes | bytearray | memoryview
        One whole record image, read in binary mode.

    Returns
    -------
    bytes
        The image as ``bytes``, once its length is proven.

    Raises
    ------
    TypeError
        If the argument is a ``str``, or is not a byte image at all. A ``str`` is refused
        explicitly because character data has already lost the byte values this record's packed
        and binary spans are made of.
    RecordLengthError
        If the image is not exactly the declared record length.
    """
    # WHY : Assumptions: a `str` is refused with a distinct message rather than falling through to
    #   the generic type error, because passing decoded text here is the specific mistake this
    #   record punishes hardest: cp037 maps the packed nibbles and the binary identifiers to
    #   characters that no longer carry their byte values, so a length check alone would pass and
    #   every numeric field would decode to a plausible wrong number.
    if isinstance(record, str):
        raise TypeError(
            f"a {EXPORT_HEADER_LAYOUT.name} record image must be bytes read in binary mode, not"
            " characters; this record carries packed and binary spans whose byte values a"
            " character decoder has already destroyed"
        )
    if not isinstance(record, bytes | bytearray | memoryview):
        raise TypeError(
            f"a {EXPORT_HEADER_LAYOUT.name} record image must be a byte image, but"
            f" {type(record).__name__} was given"
        )
    image = bytes(record)
    if len(image) != EXPORT_HEADER_LAYOUT.reclen:
        raise RecordLengthError(
            f"a {EXPORT_HEADER_LAYOUT.name} record image is {len(image)} bytes against a declared"
            f" record length of {EXPORT_HEADER_LAYOUT.reclen}; the published fields all end before"
            " the record does, so an image of the wrong length is rejected here rather than"
            " decoded from partially"
        )
    return image


def _require_discriminator_characters(value: str | Decimal | bytes) -> str:
    """Narrow a decoded discriminator to the characters a branch dispatch needs.

    Purpose
    -------
    Refuse a discriminator that did not decode to characters, rather than letting a non-character
    value reach the dispatch, where it could only fail with a less specific message.

    Parameters
    ----------
    value : str | Decimal | bytes
        The discriminator field's decoded result.

    Returns
    -------
    str
        ``value`` unchanged, once it is proven to be characters.

    Raises
    ------
    LayoutError
        If the discriminator decoded to anything but characters, which means the envelope layout
        no longer declares it as character data.
    """
    if isinstance(value, str):
        return value
    raise LayoutError(
        f"field {_DISCRIMINATOR_FIELD.describe()} of record {EXPORT_HEADER_LAYOUT.name} decoded to"
        f" {type(value).__name__} rather than characters, so it cannot select a payload branch;"
        " the envelope layout must declare the discriminator as character data"
    )


def _require_scalar_value(
    value: str | Decimal | bytes,
    field: FieldSpec,
    layout_name: str,
) -> str | Decimal:
    """Narrow one decoded field value to the published union, refusing raw bytes.

    Purpose
    -------
    Keep this reader's decoded shape honest: every published field decodes either to characters or
    to an exact number, and a byte-valued result means a descriptor has acquired a regime this
    reader publishes no representation for.

    Parameters
    ----------
    value : str | Decimal | bytes
        One field's decoded result, as the record decoder returns it.
    field : FieldSpec
        The descriptor the value was decoded from, used only to name the field and its geometry in
        a rejection.
    layout_name : str
        The name of the layout the field belongs to -- the envelope or one branch -- so a rejection
        says which of the two halves is at fault.

    Returns
    -------
    str | Decimal
        ``value`` unchanged, once it is proven to be a published scalar.

    Raises
    ------
    LayoutError
        If the decode returned raw bytes.
    """
    if isinstance(value, str | Decimal):
        return value

    # WHY : Trade-offs: the rejection names the field's geometry and never renders the bytes
    #   themselves, not even as a length-bounded excerpt. An unexpected opaque span on this record
    #   would sit among a primary account number, a verification value and a national identifier,
    #   so a diagnostic that dumped it could disclose any of them. Naming the field is enough to
    #   locate the defect, which is in the layout rather than in the data.
    raise LayoutError(
        f"field {field.describe()} of record {layout_name} decoded to raw bytes rather than to"
        " characters or an exact number, so it declares a regime this reader publishes no"
        " representation for; the only such area this record has is the payload the branch"
        " overlays redefine, and that area is decoded as a branch rather than published raw"
    )


def branch_loaded_fields(record_type_value: str) -> tuple[FieldSpec, ...]:
    """Return the projected payload fields for one record-type discriminator.

    Purpose
    -------
    Publish the second half of this record's decoded shape: the fields the named branch
    contributes, after this reader's pad drop and field suppression, in declaration order.

    Parameters
    ----------
    record_type_value : str
        The one-character discriminator, exactly as :func:`record_type` returns it.

    Returns
    -------
    tuple[FieldSpec, ...]
        The branch's projected fields in declaration order. The pad and the suppressed
        verification value are absent.

    Raises
    ------
    LayoutError
        If the discriminator is not one of the five the export program writes. Raised by
        ``export_branch`` and left to propagate, because its message already lists the five so a
        caller can see what the byte should have been.
    """
    # WHY : Assumptions: the dispatch is delegated to `export_branch` and the projection is then
    #   looked up by the BRANCH's own name rather than by the discriminator. Keying the cache on
    #   the branch makes a second, independent type-to-branch table impossible here: there is
    #   exactly one place a discriminator is resolved, so this function cannot disagree with the
    #   decode path about which overlay a byte selects.
    return _BRANCH_LOADED_FIELDS[export_branch(record_type_value).name]


def record_type(record: bytes | bytearray | memoryview) -> str:
    """Return the record-type discriminator of one export record image.

    Purpose
    -------
    Let a caller partition an extract by record type -- or route a record to the loader that owns
    its target table -- without decoding the payload, reading the discriminator through the same
    per-field path the full decode uses.

    Parameters
    ----------
    record : bytes | bytearray | memoryview
        One whole record image of exactly the declared record length, read in binary mode.

    Returns
    -------
    str
        The one-character discriminator. It is NOT validated against the five known types here;
        :func:`branch_loaded_fields` and the decode path do that, so a caller may inspect an
        unknown byte before deciding what to do about it.

    Raises
    ------
    TypeError
        If the record is a ``str`` or is not a byte image.
    RecordLengthError
        If the record is not exactly the declared record length.
    EbcdicFieldDecodeError
        If the discriminator span does not decode to exactly one character and re-encode to that
        same byte.
    LayoutError
        If the discriminator decoded to something other than characters.
    """
    # WHY : Alternatives Considered: the byte is decoded through the per-field codec rather than
    #   sliced and decoded here. Slicing was cheaper by one function call and was rejected because
    #   it would be a second statement of where the discriminator lives and how it is decoded, and
    #   the two would then be able to disagree -- at which point a record would be classified one
    #   way by this function and another way by the decode path, for the same bytes.
    image = _require_full_record_image(record)
    return _require_discriminator_characters(decode_field(image, _DISCRIMINATOR_FIELD))


def record_key(record: bytes | bytearray | memoryview) -> bytes:
    """Return the raw key span of one export record image.

    Purpose
    -------
    Give a caller the record's sequence-number key as stored, for grouping, ordering or
    duplicate detection over a whole extract without decoding every record.

    Parameters
    ----------
    record : bytes | bytearray | memoryview
        One whole record image of exactly the declared record length, read in binary mode.

    Returns
    -------
    bytes
        The key's declared span exactly as stored. Ordering these spans lexically orders the
        records by sequence number, because the field is an unsigned big-endian binary integer of
        fixed width; the decoded number itself is available as ``EXPORT-SEQUENCE-NUM`` in a
        decoded record.

    Raises
    ------
    TypeError
        If the record is a ``str`` or is not a byte image.
    RecordLengthError
        If the record is not exactly the declared record length.
    """
    # WHY : Assumptions: the key is returned as RAW BYTES rather than as the decoded number, and
    #   the property that makes that useful is stated rather than assumed: an unsigned big-endian
    #   integer of fixed width compares lexically in the same order as it compares numerically, so
    #   a caller can sort or group images without paying for a decode. A signed or
    #   little-endian key would NOT have that property, which is why this reasoning is recorded
    #   here rather than treated as a general fact about keys.
    # WHY : Assumptions: the geometry comes from the descriptor's own `key_offset` and
    #   `key_length`, so this module states no key position. The layouts module records that the
    #   dataset definition declares a DIFFERENT key -- `KEYS(4 28)`, straddling this field and the
    #   next -- as `EXPORT_DECLARED_DATASET_KEY_OFFSET`, and deliberately does not use it: that
    #   disagreement is a documented divergence corroborating the known baseline defect in this
    #   program pair, and honouring it here would return three bytes of the sequence number and
    #   one byte of the branch identifier as a key.
    image = _require_full_record_image(record)
    start = EXPORT_HEADER_LAYOUT.key_offset
    return image[start : start + EXPORT_HEADER_LAYOUT.key_length]


def decode_ebcdic_export_record(record: bytes | bytearray | memoryview) -> DecodedExportRecord:
    """Decode one export record image into the published flattened shape.

    Purpose
    -------
    Turn a single fixed-length record image into one row-shaped mapping: the envelope's own data
    fields followed by the fields of the branch its discriminator selects, with the opaque payload
    area, the branch pad and the suppressed verification value all absent.

    Parameters
    ----------
    record : bytes | bytearray | memoryview
        One whole record image of exactly the declared record length, read in binary mode. A
        ``str`` is refused, because character data has already lost the byte values this record's
        packed and binary spans are made of.

    Returns
    -------
    DecodedExportRecord
        One entry per published field, keyed by the field name exactly as the copybook spells it,
        in declaration order: envelope fields first, then branch fields.

    Raises
    ------
    TypeError
        If the record is a ``str`` or is not a one-dimensional image of single bytes.
    EbcdicRecordLengthError
        If the record is not exactly the declared record length, or if the sliced payload is not
        the selected branch's declared length. This is a record-length error, so a caller guarding
        either encoding may catch the shared base type.
    EbcdicFieldDecodeError
        If a character span does not decode to exactly one character per byte and re-encode to
        those same bytes.
    LayoutError
        If the discriminator is not one of the five the export program writes, if it decoded to
        something other than characters, or if a published field decoded to raw bytes.
    ZonedDecimalError
        If a display span in either half violates its contract.
    PackedDecimalError
        If a packed or binary span in either half violates its contract.
    """
    # WHY : Assumptions: the two-step decode is DELEGATED in full rather than reimplemented here.
    #   That codec reads the discriminator from the decoded envelope, resolves the branch through
    #   the layouts module, slices the payload with the published payload offset and the branch's
    #   own declared length, and checks the two agree -- so this reader states none of those four
    #   things and cannot state any of them differently. What remains here is the projection,
    #   which is a reader decision and belongs nowhere else.
    envelope, payload = decode_export_record(record)
    discriminator = _require_discriminator_characters(envelope[_DISCRIMINATOR_FIELD.name])

    # WHY : Assumptions: the mapping is built by iterating the PUBLISHED field tuples rather than
    #   by copying the decoded halves and deleting keys, and that is what makes the suppression a
    #   real one on this path. The verification value's bytes are inside the payload the codec
    #   decoded, so its decoded form exists for as long as `payload` does -- but it is never
    #   copied into the returned mapping, and the returned mapping is the only thing that outlives
    #   this call. Deleting a key afterwards would leave the value reachable through every
    #   intermediate a caller might hold.
    decoded: DecodedExportRecord = {
        field.name: _require_scalar_value(envelope[field.name], field, EXPORT_HEADER_LAYOUT.name)
        for field in ENVELOPE_LOADED_FIELDS
    }
    branch = export_branch(discriminator)
    for field in _BRANCH_LOADED_FIELDS[branch.name]:
        decoded[field.name] = _require_scalar_value(payload[field.name], field, branch.name)
    return decoded


def iter_ebcdic_export_records(
    source: pathlib.PurePath | bytes | bytearray | memoryview | Iterable[object],
) -> Iterator[DecodedExportRecord]:
    """Decode the export dataset one record at a time.

    Purpose
    -------
    Stream decoded records from a fixed-length dataset, cut strictly on the record length the
    envelope layout declares.

    Parameters
    ----------
    source : pathlib.PurePath | bytes | bytearray | memoryview | Iterable[object]
        The dataset, in one of four shapes: a path, which the codec opens read-only in binary
        mode, streams and closes; a whole byte image the caller already holds; an open binary
        stream, read strictly forward and neither seeked nor closed; or an iterable of byte pieces
        whose boundaries may fall anywhere. A ``str`` is refused.

    Returns
    -------
    Iterator[DecodedExportRecord]
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
        If a character span does not decode to exactly one character per byte.
    LayoutError
        If the source is neither a byte image nor readable nor iterable, produces a piece that is
        not a byte object, carries an unknown discriminator, or has a published field that decoded
        to raw bytes.
    ZonedDecimalError
        If a display span violates its contract.
    PackedDecimalError
        If a packed or binary span violates its contract.
    """
    # WHY : Assumptions: the dataset is cut on the declared record length ALONE, and no line
    #   terminator is looked for, honoured, stripped or padded on this path. This extract is the
    #   sharpest illustration in the corpus of why that matters: it contains five 0x0A bytes
    #   INSIDE its binary sequence numbers, so splitting it on a newline yields six pieces where
    #   it holds five hundred records, and it contains 4,153 NUL bytes, so a text-mode read would
    #   not survive either. The correctness test for this dataset is that its size divides by the
    #   declared record length with no remainder, which the delegated iterator checks before it
    #   yields the first record.
    for image in iter_ebcdic_records(source, EXPORT_HEADER_LAYOUT):
        yield decode_ebcdic_export_record(image)


def read_ebcdic_export_records(path: pathlib.Path) -> Iterator[DecodedExportRecord]:
    """Stream the export dataset from a file at an explicit path.

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
    Iterator[DecodedExportRecord]
        Each record in order, in the published decoded shape. A zero-byte file yields nothing and
        is not an error.

    Raises
    ------
    OSError
        If the path cannot be inspected or opened.
    EbcdicRecordLengthError
        If the file size does not divide into whole records of the declared length.
    EbcdicFieldDecodeError
        If a character span does not decode to exactly one character per byte.
    LayoutError
        If a record carries an unknown discriminator, or a published field decoded to raw bytes.
    ZonedDecimalError
        If a display span violates its contract.
    PackedDecimalError
        If a packed or binary span violates its contract.
    """
    # WHY : Alternatives Considered: the path is handed to the codec rather than opened here and
    #   passed as a stream. The codec validates the file size against the declared record length
    #   BEFORE yielding a first record, so a truncated dataset fails up front instead of part way
    #   through a load, and it owns the open, the forward-only read and the close. Opening the file
    #   here would duplicate that lifecycle for no gain.
    yield from iter_ebcdic_export_records(pathlib.PurePath(path))


def _masked_field_index(discriminator: str) -> Mapping[str, FieldSpec]:
    """Build the published field index for one record type, envelope first then branch.

    Purpose
    -------
    Resolve a field name to its descriptor over exactly the fields this reader publishes for a
    given record type, so a masked rendering and a name lookup agree about what exists.

    Parameters
    ----------
    discriminator : str
        The one-character record-type discriminator.

    Returns
    -------
    Mapping[str, FieldSpec]
        A read-only mapping from published field name to descriptor. Dropped and suppressed fields
        are absent.

    Raises
    ------
    LayoutError
        If the discriminator is not one of the five the export program writes.
    """
    # WHY : Assumptions: the index is composed from the same two tuples the decode path iterates,
    #   so a field is present here exactly when it is present in a decoded record. Building it
    #   from the layouts directly would readmit the pad, the opaque payload area and the
    #   suppressed verification value, and a caller could then ask for a rendering of a field that
    #   no decoded record contains.
    index: dict[str, FieldSpec] = {field.name: field for field in ENVELOPE_LOADED_FIELDS}
    index.update(
        {field.name: field for field in _BRANCH_LOADED_FIELDS[export_branch(discriminator).name]}
    )
    return MappingProxyType(index)


def render_masked_export_record(record: bytes | bytearray | memoryview) -> dict[str, str]:
    """Render one export record as text with every sensitive field redacted.

    Purpose
    -------
    Give an operator a privacy-safe rendering of a whole record, so a failed comparison can show
    which field differs without emitting content whose sensitivity the reader cannot judge.

    Parameters
    ----------
    record : bytes | bytearray | memoryview
        One whole record image of exactly the declared record length, read in binary mode.

    Returns
    -------
    dict[str, str]
        One entry per published field, in the same order and under the same keys as a decoded
        record, with each value rendered as text: verbatim when the field is not sensitive, and
        otherwise a keyed redaction holding no part of the value.

    Raises
    ------
    TypeError
        If the record is a ``str`` or is not a byte image.
    EbcdicRecordLengthError
        If the record is not exactly the declared record length.
    EbcdicFieldDecodeError
        If a character span does not decode to exactly one character per byte.
    LayoutError
        If the discriminator is unknown, or a published field decoded to raw bytes.
    ZonedDecimalError
        If a display span violates its contract.
    PackedDecimalError
        If a packed or binary span violates its contract.
    """
    # WHY : Alternatives Considered: this rendering is a mapping of DECODED values rather than a
    #   full-width copy of the record with sensitive spans overwritten, which is what every
    #   sibling reader publishes. The whole-record form was preferred there because a caller can
    #   count offsets across it; it is unavailable here, because two thirds of this record is not
    #   characters at all -- rendering it byte-for-byte would mean putting packed nibbles and
    #   big-endian integers through a character decoder, which is precisely the corruption the
    #   opaque payload declaration exists to prevent. Rendering the decoded values keeps every
    #   field readable and keeps the raw bytes out of the diagnostic entirely.
    # WHY : Assumptions: redaction is delegated to the shared rendered-value helper rather than
    #   applied here, so marking a field sensitive in the layout remains the ONLY change ever
    #   needed to redact it in this rendering. That helper is the one that accepts a value of any
    #   length -- a decoded amount is not the width of its stored form -- and bounds the tag by
    #   the field's declared width so the tag cannot leak a value's magnitude through its length.
    decoded = decode_ebcdic_export_record(record)
    index = _masked_field_index(
        _require_discriminator_characters(decoded[_DISCRIMINATOR_FIELD.name])
    )
    return {name: mask_rendered_value(index[name], str(value)) for name, value in decoded.items()}


def render_masked_export_field(record: bytes | bytearray | memoryview, field_name: str) -> str:
    """Render one named field of an export record with redaction applied.

    Purpose
    -------
    Produce a privacy-safe rendering of a single field, for a diagnostic that needs to show one
    field rather than a whole record.

    Parameters
    ----------
    record : bytes | bytearray | memoryview
        One whole record image of exactly the declared record length, read in binary mode.
    field_name : str
        The field name exactly as the copybook spells it, including any baseline misspelling,
        since the descriptors preserve the copybook's own spelling.

    Returns
    -------
    str
        The field's decoded value rendered as text: verbatim when the field is not sensitive,
        otherwise a keyed redaction holding no part of the value.

    Raises
    ------
    TypeError
        If the record is a ``str`` or is not a byte image.
    EbcdicRecordLengthError
        If the record is not exactly the declared record length.
    EbcdicFieldDecodeError
        If a character span does not decode to exactly one character per byte.
    LayoutError
        If the named field is the one this reader suppresses, is one of the names this reader
        drops, or is not published for this record's type -- and also if the discriminator is
        unknown or a published field decoded to raw bytes.
    ZonedDecimalError
        If a display span violates its contract.
    PackedDecimalError
        If a packed or binary span violates its contract.
    """
    # WHY : Alternatives Considered: a suppressed field is REFUSED here rather than rendered as a
    #   redaction, and a dropped name is refused rather than rendered from the raw bytes. A caller
    #   naming a field is asking for that field's value, and this reader publishes no
    #   representation of either kind: refusing keeps the contract uniform across every entry
    #   point, so neither can be reached through one door after being excluded from another -- and
    #   the caller learns which of the two rules applied instead of receiving a tag it might
    #   mistake for a value it could compare.
    if field_name in SUPPRESSED_FIELD_NAMES:
        raise LayoutError(
            f"field {field_name!r} of record {EXPORT_HEADER_LAYOUT.name} is suppressed by this"
            " reader and has no rendering; it is excluded from every decoded record, so there is"
            " no value here to redact and none is produced"
        )
    if field_name in DROPPED_FIELD_NAMES:
        raise LayoutError(
            f"field {field_name!r} of record {EXPORT_HEADER_LAYOUT.name} is dropped by this"
            " reader and has no rendering; the payload area is published as the decoded branch"
            " its discriminator selects, and a pad carries no data"
        )

    decoded = decode_ebcdic_export_record(record)
    discriminator = _require_discriminator_characters(decoded[_DISCRIMINATOR_FIELD.name])
    index = _masked_field_index(discriminator)
    field = index.get(field_name)
    if field is None:
        # WHY : Trade-offs: the refusal names the branch this record selected and does NOT list
        #   the fields it publishes. Naming the branch is what makes the common mistake -- asking
        #   a customer record for an account field, since all five branches share one envelope --
        #   immediately diagnosable, while listing every field would put a hundred copybook names
        #   into a log line for no additional information.
        branch = export_branch(discriminator)
        raise LayoutError(
            f"record {EXPORT_HEADER_LAYOUT.name} does not publish a field named {field_name!r}"
            f" for record type {discriminator!r}, whose payload is branch {branch.name}; the"
            " envelope's own fields and that branch's fields are the whole published set"
        )
    return mask_rendered_value(field, str(decoded[field_name]))
