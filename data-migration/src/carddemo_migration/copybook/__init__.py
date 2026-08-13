"""Byte geometry and the three record codecs of the CardDemo ETL, behind one package boundary.

Purpose
-------
``carddemo_migration.copybook`` is the foundation subpackage of the extract-transform-load
package. Everything else in the distribution imports from it, and it imports nothing from
anywhere else in the distribution. It answers two questions and no others -- WHERE a field
sits inside a fixed-width record, and WHAT the bytes at that position mean -- and it leaves
choosing a dataset, loading a row and verifying a load to the readers, loaders and
verification passes above it.

This module is the subpackage entry point. It states the contracts the five modules below
are held to, and it re-exports a curated selection of their names so that
``from carddemo_migration.copybook import decode_zoned`` is a stable spelling. It declares
no class, no function and no data of its own, and it converts nothing.

Importing the subpackage reads no environment variable, installs no logging handler,
constructs no client, opens no dataset and reaches no network. The only work done at import
time anywhere below is each module's in-memory self-check of its own declarations, together
with the guarded code-page registration described under *Standard library only*; both are
documented where they happen, and neither touches anything outside the process.

The five modules
----------------
``layouts``
    The single source of offsets. For every record in the migration contract it declares
    each field's zero-based offset, byte length, storage regime, integer and decimal digit
    counts, signedness, timestamp-normalisation flag and sensitivity flag, together with
    the record's total length, its primary key geometry and its alternate-index geometry.
    Its ``Kind`` enumeration names the storage regimes: ``TEXT``, ``UINT``, ``ZONED``,
    ``PACKED``, ``BINARY`` and ``OPAQUE``. Its registry holds fourteen layouts -- the
    ELEVEN base masters (``ACCOUNT``, ``CARD``, ``CUSTOMER``, ``XREF``, ``DALYTRAN``,
    ``TRAN``, ``DISGROUP``, ``TCATBAL``, ``SECUSER``, ``TRANCAT``, ``TRANTYPE``) and the
    THREE derived records the batch chain writes rather than reads (``TRNX``, ``REJECT``,
    ``INTTRAN``). The export and pending-authorization layouts are named separately
    because they are selected by record type rather than resolved from that registry. The
    record-boundary API has two modes, one per physical form the corpus takes:
    :func:`iter_fixed_length_records` slices BYTES, :func:`iter_ascii_text_records` slices
    CHARACTERS, and :func:`count_fixed_length_records` states how many whole records a
    given byte size holds. This module describes structure only and converts no values.
``zoned``
    Trailing-sign overpunch, which is how EVERY money field in all eleven base masters is
    stored: ``USAGE DISPLAY``, one printable digit per byte, and for a signed field the
    sign folded into the low-order digit byte so that it occupies no byte of its own.
    :func:`decode_zoned` returns an exact :class:`decimal.Decimal` at the field's declared
    scale and :func:`encode_zoned` is its inverse.
``packed``
    The two computational regimes, both read as bytes rather than as characters:
    ``USAGE COMP-3`` packed decimal, two digits per byte with the sign in the low-order
    nibble of the final byte, and ``USAGE COMP`` binary, a big-endian two's-complement
    machine unit whose width is chosen by the digit count. :func:`decode_packed` and
    :func:`decode_binary` return exact ``Decimal`` values; :func:`encode_packed` and
    :func:`encode_binary` are their inverses.
``ebcdic_codec``
    The ONE AND ONLY place in this repository where EBCDIC is turned into characters. It
    opens a dataset read-only in binary mode, slices it strictly by the record length its
    layout declares, and decodes EACH FIXED-WIDTH FIELD SEPARATELY through cp037;
    :func:`decode_field` is that per-field entry point. It never decodes a whole record as
    one string, never looks for a line terminator, and never routes a packed or binary
    span through a character decoder at all.
``timestamp``
    The one authority on what the 26 characters of a ``PIC X(26)`` stamp may hold. It
    admits exactly the two spellings CardDemo writes -- ``YYYY-MM-DD HH:MM:SS.ffffff`` from
    the posting program and ``YYYY-MM-DD-HH.MM.SS.NNNNNN`` from a program taking the current
    date -- by PARSING each candidate through the standard library's own calendar and
    requiring it to format back to itself, so an impossible month, an out-of-range hour and
    a short fraction are all refused without this package maintaining a calendar. It also
    recognises the two uniform forms an unwritten stamp takes, and renders a populated stamp
    into the single spelling a ``TIMESTAMP(6)`` column accepts. It raises nothing on the
    validation path: each caller raises its own error naming its own record and field.

Standard library only
---------------------
Every module in this subpackage imports from the Python standard library and from this
subpackage, and from nothing else. ``psycopg``, ``boto3``, ``botocore`` and the sibling
``carddemo_migration.config`` are all excluded, and ``config`` states the same exclusion
from its own side. This is the discipline the reference codec under ``tests/helpers/``
declares for itself, held here for the reason it gives: a bare checkout, before any
dependency is installed, can import these modules and decode a known-answer vector with
them.

There is exactly one narrow exception, and it is a reference rather than a requirement.
``ebcdic_codec`` attempts the pinned ``ebcdic`` distribution behind an ``ImportError``
guard, solely to register the EXTENDED EBCDIC code pages, and records the outcome on its
``EXTENDED_CODE_PAGES_REGISTERED`` flag. Nothing breaks when that distribution is absent:
``cp037`` -- this package's default, and the page the CardDemo extracts actually use -- is
a standard-library codec, so the guard degrades to a narrower supported set and the import
still succeeds.

Single-sourcing contract
------------------------
Record offsets, lengths and storage regimes are declared ONCE, in ``layouts``, and are
imported from there by every reader, loader and verification pass; none of them derives
geometry of its own. There is one place to change a layout and one place to review it, so
two readers cannot drift apart on the same record. This is the Python analogue of
compiling every COBOL program against a single ``cobc -I app/cpy`` include path, and of
the Java shared-kernel module the services import their codecs from. The reference suite
states the same house rule for the COBOL layer in its own guide, at ``tests/README.md``
lines 541 and 542: never duplicate a layout, keep it single-sourced from ``app/cpy/``.

Cross-language parity contract
------------------------------
The descriptor shapes here mirror the Java shared kernel at
``services/common-lib/src/main/java/com/carddemo/common/codec/CopybookLayout.java``, which
the migration plan declares is shared conceptually with this ETL so that offsets are
declared once per record. The parity is exact where it is shared and explicit where it is
not:

* The field descriptor carries the SAME NINE components in the SAME ORDER on both sides:
  name, offset, length, kind, integer digits, decimal digits, signedness,
  timestamp-normalisation flag, sensitivity flag.
* The record descriptor carries the SAME FIVE core components on both sides: name, record
  length, key length, key offset, fields. ``RecordSpec.alternate_keys`` follows those five
  as supplementary metadata and never disturbs them, because this side must BUILD the
  three baseline alternate indexes while the Java side only reads them back.
* ``Kind`` shares FIVE members with the Java enumeration -- ``TEXT``, ``UINT``, ``ZONED``,
  ``PACKED``, ``BINARY`` -- and adds a sixth, ``OPAQUE``, that the Java has no counterpart
  for. That sixth member records something the other five cannot express: that an area's
  interior is described by a DIFFERENT layout selected at run time.

A divergence in the shared nine or the shared five is not a style difference. It is a
silent data defect, because both sides would go on returning well-formed values read from
different bytes. No component name here is available to be tidied.

Two meanings of "EBCDIC"
------------------------
The word names two entirely distinct things in this subpackage, and conflating them is the
error the module split exists to prevent:

* In ``zoned`` it names a SIGN CONVENTION. The reference suite requires the COBOL compiler
  flag ``-fsign=EBCDIC`` at ``tests/README.md`` lines 273 and 274, because the ASCII
  default misreads the overpunch and silently corrupts negative balances. The overpunch
  characters themselves are ASCII-printable, which is why the reference codec calls the
  mapping the canonical IBM ASCII trailing-sign mapping at
  ``tests/helpers/record_codec.py`` line 135 while the convention it implements is the
  EBCDIC one. Both statements are correct.
* In ``ebcdic_codec`` it names a CHARACTER ENCODING: the cp037 code page.

A field may be subject to one, the other, both or neither. The ASCII seed datasets carry
overpunched signs with no cp037 involved anywhere; the mainframe-character-set datasets
carry both at once.

Why a defect in this subpackage is silent
-----------------------------------------
Fixed-point and character-set fidelity is the highest-risk area of the whole migration,
because an error here does not raise. It produces plausible numbers that are wrong, and it
surfaces much later as a balance that does not reconcile. Two traps account for most of
that risk, and both are refused by construction rather than by care:

* **Money never touches ``float``.** Every amount is a :class:`decimal.Decimal` here,
  ``NUMERIC(p,2)`` in PostgreSQL, ``BigDecimal`` in Java and a JSON STRING on the wire. A
  binary float cannot represent ten cents exactly, so a single round trip through a
  ``double`` is enough to make a total disagree with the ledger it was read from.
* **EBCDIC is never decoded per record, and a record is never located by looking for a
  line terminator.** ``app/data/EBCDIC/AWS.M2.CARDDEMO.EXPORT.DATA.PS`` is 250000 bytes
  holding exactly 500 records of 500 bytes, and five of those bytes are ``0x0A`` values
  that are payload: splitting the file on newline yields SIX chunks instead of 500
  records. Two of the five sit at offset 30 of their record, which is the LOW BYTE of
  ``EXPORT-SEQUENCE-NUM``, a four-byte ``USAGE COMP`` field declared at offset 27 -- a
  legitimate binary value, not a delimiter. The remaining three sit inside the 460-byte
  area declared ``OPAQUE``.

Import discipline
-----------------
Every import inside this subpackage, and every import of it, is absolute and written in
full from ``carddemo_migration``. Relative imports and ``sys.path`` manipulation are both
forbidden, and the ban is mechanical rather than agreed: the sibling ``pyproject.toml``
selects ``TID252`` with ``ban-relative-imports = "all"``, so ``from .layouts import ...``
written here would fail the build rather than wait for a reviewer.

Relationship to the baseline
----------------------------
``app/**`` is reference-only input, and that includes ``app/data/**``. This subpackage
reads those bytes and never modifies, re-encodes, normalises or rewrites one of them in
place. The extracts are the specification the decoders are held to, so a decoder that
adjusted its own input would be destroying the evidence it is checked against.
``tests/**``, ``scripts/**`` and ``samples/**`` are reference-only on the same terms; the
first of them is the parity oracle this ETL's output is validated against.

Design decisions (WHY)
----------------------
Trade-offs:
    **Standard-library-only is a constraint imposed on this subpackage, not an observation
    about how it happens to have turned out.** The cost is real and is accepted twice
    over: a caller needing a code page beyond the four supported ones must install a
    distribution, and no module here may reach for a faster third-party decoder however
    attractive one looks. What it buys is the ability to run the codecs where nothing else
    runs. ``python -c "import carddemo_migration.copybook"`` succeeds with no database
    reachable, no AWS credential configured and no ``ebcdic`` distribution installed, so a
    suspect amount can be reproduced from a known-answer vector on a bare checkout instead
    of only inside a provisioned environment -- which matters precisely because a codec
    defect is the kind that has to be reproduced to be believed.
Assumptions:
    **Single-sourced offsets assume that no consumer anywhere declares geometry of its
    own.** The guarantee is not that the offsets are right; it is that there is only one
    copy of them to be wrong. A reader that hard-coded offset 262 for the transaction card
    number would keep decoding successfully after a layout correction landed in
    ``layouts``, and the two would disagree with nothing raising -- which is the drift the
    single copy exists to make impossible. The same assumption is why the record-boundary
    helpers live beside the offsets rather than in each reader.
Assumptions:
    **Cross-language parity assumes the Java descriptors stay held to the same shape, and
    that the shared components are compared rather than trusted.** The nine field
    components and the five core record components are the comparable surface; the two
    Python-only additions, ``Kind.OPAQUE`` and ``RecordSpec.alternate_keys``, are named
    separately for exactly that reason, so that a reader diffing the two files knows which
    differences are expected. Renaming a shared component on one side only would not break
    a build on either side, which is why the shape is recorded here as a contract rather
    than left to be inferred from two files nobody opens together.
Assumptions:
    **The two meanings of "EBCDIC" are assumed to stay separated by module.** A sign
    convention and a character encoding share the word and nothing else, so the split is
    load-bearing: routing a signed display span through the character decoder, or asking
    the overpunch tables to interpret a cp037 byte, produces a value of the right width
    and the wrong content. Width is what every downstream length check verifies, so the
    substitution passes every check and reaches the ledger.
"""

# Alternatives Considered: this subpackage is a REGULAR package with an explicit
# documented entry point, and the implicit PEP 420 namespace package it could have been
# instead was rejected. The reference suite is the namespace form and is right to be:
# there is no entry-point module anywhere under ``tests/`` -- the count is zero -- and
# that tree resolves through ``PYTHONPATH`` pointing at the repository root, exactly as
# the reference codec's own module docstring states. This tree is the opposite by
# design, an installable distribution discovered through ``where = ["src"]``, and the
# two must not be made to match. Package discovery finds a regular package by this file
# and finds a namespace child only by falling back to namespace discovery, so a later
# ``packages`` or ``exclude`` entry would drop this directory from the built wheel while
# every test in the checkout continued to pass; the failure would then arrive as
# ``ModuleNotFoundError: carddemo_migration.copybook`` inside a container, which is the
# worst place to learn that a package was never shipped. The second reason is this file
# rather than the directory: a package entry point is the first artifact kind the
# project's explainability rule names, so a documented one is required rather than
# preferred, and ruff's ``D104`` enforces it under an ``ignore`` list that is
# deliberately empty and a ``per-file-ignores`` table that deliberately does not exist.

# Trade-offs: the five modules are imported EAGERLY here, which is the opposite of
# what the root ``carddemo_migration`` entry point does, and the two decisions differ
# because their costs differ rather than because one of them is inconsistent. Eager
# import here costs the import of five standard-library-only modules -- no driver, no
# SDK, no configuration read -- and buys the property the folder's own validation gate
# checks, that ``import carddemo_migration.copybook`` is sufficient and a caller never
# has to know which of the five owns the name it wants. The root imports NO subpackage
# at all, because reaching ``loaders`` or ``verify`` from there would pull ``psycopg``
# and ``boto3`` in behind them; a copybook-only import would then stop working on a bare
# checkout and the standard-library-only guarantee would become false at the very point
# the root asserts it. Eager here, lazy there, for one reason stated from two ends.
from carddemo_migration.copybook.ebcdic_codec import (
    EbcdicFieldDecodeError,
    EbcdicRecordLengthError,
    decode_field,
)
from carddemo_migration.copybook.layouts import (
    LAYOUTS,
    AlternateKeySpec,
    FieldSpec,
    Kind,
    LayoutError,
    RecordLengthError,
    RecordSpec,
    count_fixed_length_records,
    iter_ascii_text_records,
    iter_fixed_length_records,
    keylen_of,
    layout,
    reclen_of,
)
from carddemo_migration.copybook.packed import (
    PackedDecimalError,
    PackedSpanWidthError,
    decode_binary,
    decode_packed,
    encode_binary,
    encode_packed,
)
from carddemo_migration.copybook.timestamp import (
    ADMITTED_FORMS,
    # Refactoring Rationale: ``ISO_FORM`` was added to this boundary when
    # ``verify/checksum.py`` began admitting a driver-native ``datetime`` on the read-back
    # side of a comparison. Rendering one back into the spelling the load direction writes
    # needs the directive itself, and ``canonical`` beside it renders only a ``str``, so the
    # alternative was a second copy of ``"%Y-%m-%d %H:%M:%S.%f"`` in the verification pass --
    # a calendar spelling that could drift from the one every row was loaded through. It is
    # ``ADMITTED_FORMS[0]``, and publishing it by NAME rather than leaving a caller to index
    # that tuple is what keeps a future reordering of the two admitted forms from silently
    # changing which spelling a verification renders.
    ISO_FORM,
    canonical,
    is_admitted,
    is_unwritten,
)
from carddemo_migration.copybook.zoned import (
    ZonedDecimalError,
    ZonedSpanWidthError,
    decode_zoned,
    encode_zoned,
)

# Alternatives Considered: the surface below is CURATED and sorted, and a star
# re-export was rejected. ``layouts`` alone exports seventy-eight names, so re-exporting
# the union would put every per-record layout constant, every field-descriptor
# constructor helper and every diagnostic helper at this level, and it would promote
# ``layouts``' internal vocabulary into a second public spelling for names that already
# have one. Two concrete consequences decided it. A star surface makes every subsequent
# internal rename a breaking change to this package's public contract, because a name
# that leaked once cannot be withdrawn without warning. And the two overpunch tables in
# ``zoned`` are private for a reason -- they are an implementation of the mapping, not
# the mapping's interface -- so a surface assembled by wildcard would advertise them the
# moment either one stopped being underscore-prefixed. Every name here is instead
# declared, which is also what keeps this list and the import block above verifiable
# against each other by the linter rather than by reading.
# The inclusion criterion is correspondingly narrow: the smallest set with which a reader
# or a loader can locate a record boundary, resolve a field's declared geometry, decode
# each of the three storage regimes and catch what any of them raises, all without
# reaching into a submodule. Everything else -- the per-record layout constants, the
# field-descriptor constructors, the masking helpers, the field-oriented codec forms --
# stays reachable at its owning module, which is the spelling every existing consumer
# already uses, so curating withdraws nothing that was available before. The sorted order
# is the order each of the five modules declares its own surface in, so all six files in
# this directory read the same way.
__all__ = [
    "ADMITTED_FORMS",
    "AlternateKeySpec",
    "EbcdicFieldDecodeError",
    "EbcdicRecordLengthError",
    "FieldSpec",
    "ISO_FORM",
    "Kind",
    "LAYOUTS",
    "LayoutError",
    "PackedDecimalError",
    "PackedSpanWidthError",
    "RecordLengthError",
    "RecordSpec",
    "ZonedDecimalError",
    "ZonedSpanWidthError",
    "canonical",
    "count_fixed_length_records",
    "decode_binary",
    "decode_field",
    "decode_packed",
    "decode_zoned",
    "encode_binary",
    "encode_packed",
    "encode_zoned",
    "is_admitted",
    "is_unwritten",
    "iter_ascii_text_records",
    "iter_fixed_length_records",
    "keylen_of",
    "layout",
    "reclen_of",
]
