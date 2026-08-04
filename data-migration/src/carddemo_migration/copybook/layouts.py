"""Byte geometry of every fixed-width CardDemo record the Python ETL reads.

Purpose
-------
This module is the *single source of offsets* for the whole extract-transform-load
package. Every reader, every loader and every verification pass imports its byte
offsets, field lengths, storage regimes and record boundaries from here and derives
nothing of its own. There is exactly one place to change a layout and exactly one
place to review it, which is the Python analogue of compiling every COBOL program
against a single ``cobc -I app/cpy`` include path -- precisely how the reference
baseline guaranteed the same property.

The module describes *structure only*: names, zero-based offsets, lengths, storage
regimes, digit counts, diagnostic flags, key geometry and record boundaries. It
converts no values. Trailing-sign overpunch decoding belongs to
``carddemo_migration.copybook.zoned``, ``COMP-3`` and ``COMP`` decoding to
``carddemo_migration.copybook.packed``, and cp037 character decoding to
``carddemo_migration.copybook.ebcdic_codec``. Those three modules import this one;
this one imports nothing from the package.

Package layout note: the three sibling module names above are written as plain
literals rather than as cross-references because this module is authored ahead of
them, and a reference to a module with no file yet does not resolve.

What makes this table trustworthy
---------------------------------
Every record length below was derived by SUMMING the declared field widths of its
copybook, and the result was then cross-checked against at least one independent
source. The cross-check is what removes doubt from every offset-dependent decision
downstream, so it is recorded rather than left implicit:

* Summing ``app/cpy/CVTRA05Y.cpy`` places ``TRAN-CARD-NUM`` at zero-based offset 262
  and ``TRAN-PROC-TS`` at zero-based offset 304.
* ``app/jcl/TRANREPT.jcl`` lines 41 and 42 declare the DFSORT symbol names
  ``TRAN-CARD-NUM,263,16,ZD`` and ``TRAN-PROC-DT,305,10,CH``. DFSORT positions are
  ONE-based, so 263 and 305 are 262 and 304 here.
* ``app/jcl/TRANFILE.jcl`` line 84 and ``app/jcl/TRANIDX.jcl`` line 27 both declare
  the alternate index key as ``KEYS(26 304)``. IDCAMS key operands are ZERO-based,
  and that 26-byte key at offset 304 is exactly ``TRAN-PROC-TS``. Note the spelling
  irregularity: those two files separate the operands with a SPACE while
  ``app/jcl/DUSRSECJ.jcl`` line 65 writes the same clause comma-separated as
  ``KEYS(8,0)``. Both spellings occur in the baseline.
* ``app/jcl/PRTCATBL.jcl`` lines 47 to 50 declare four ONE-based symbol names over
  the 50-byte category-balance record, ``TRANCAT-ACCT-ID,1,11,ZD`` through
  ``TRAN-CAT-BAL,18,11,ZD``. Converted those are offsets 0, 11, 13 and 17, which is
  field for field what summing ``app/cpy/CVTRA01Y.cpy`` produces.

The conversion between those two coordinate systems runs in exactly one direction
and is stated here once because getting it backwards is silent. A JCL or DFSORT
position is ONE-based; a :attr:`FieldSpec.start` is ZERO-based; the conversion is
always ``zero_based = one_based - 1``. Applied the other way it shifts every field by
one byte, and a record read one byte out of alignment still decodes to digits, so
nothing raises and the numbers are merely wrong. Two subtleties in that same DFSORT
declaration are worth knowing: ``TRAN-PROC-DT`` covers only the FIRST 10 characters
of the 26-character timestamp, and ``TRAN-CARD-NUM`` is ``PIC X(16)`` in the copybook
yet typed ``ZD`` in the symbol names because it happens to be all-digit text.

Cross-language contract
-----------------------
The Java shared kernel declares the same geometry in
``services/common-lib/src/main/java/com/carddemo/common/codec/CopybookLayout.java``,
and the two descriptors are deliberately shaped alike: the field descriptor carries
the same nine components in the same order and the record descriptor the same five,
so a reader holding both open reads one contract rather than two. A divergence
between them is not a style difference but a silent data defect, because both sides
would keep returning well-formed values. Where this module carries MORE than the
Java does -- alternate-index metadata, the export and authorization layouts, the
record-boundary iterators and the masking helpers -- the additions are separately
named and never disturb those nine and five core components.

Design decisions (WHY)
----------------------
Trade-offs:
    **Standard library only.** This module imports nothing outside the Python
    standard library, and specifically not ``psycopg``, ``boto3``, ``botocore`` or
    ``ebcdic``, nor the sibling ``carddemo_migration.config``. The consequence being
    bought is concrete: the layouts and the codecs built on them import and run on a
    bare checkout with no database driver installed, no AWS SDK present and no
    credential configured, so they stay exercisable against known-answer vectors in
    total isolation. The reference codec at ``tests/helpers/record_codec.py`` states
    the same discipline for the same stated reason. The accepted cost is that a
    convenience such as resolving a dataset location cannot live here and belongs to
    a loader instead.
Assumptions:
    **Every validation raises; none asserts.** ``python -O`` strips ``assert``
    statements outright, so an assertion is not a validation but a check that
    disappears in the deployment where a wrong offset actually costs money. The
    reference codec reaches the same conclusion and records it explicitly at
    ``tests/helpers/record_codec.py`` line 1710. Every geometry guarantee in this
    module is therefore enforced by an explicit ``raise``.
Assumptions:
    **No numeric type beyond the integer is imported at all.** Every quantity this
    module carries is an offset, a length or a digit count, so ``decimal`` is
    deliberately absent from the imports even though the fields being described hold
    money. Turning those bytes into an exact ``decimal.Decimal`` is the numeric
    codecs' work, and importing the type here would both assert a dependency edge
    that does not exist and leave an unused import that ruff's ``F401`` correctly
    rejects. Binary floating point is absent for the stronger reason that it cannot
    represent ten cents exactly; there is no ``float`` anywhere in this package's
    money path and none may be introduced.
Assumptions:
    **Nothing here performs input or output.** No dataset path is opened, no
    environment variable is read, no logging is configured and no work happens at
    import time beyond building and proving the constant tables. The record-boundary
    functions take the bytes or the text a caller already holds.

Sensitive data
--------------
A field marked :attr:`FieldSpec.sensitive` is one whose raw bytes must never reach a
log line or an exception message. The flag MARKS the field and does nothing else.
Masking a primary account number to its last four digits, suppressing a card
verification value from a response and encrypting a national or government identifier
at rest all belong to the domain projection, not here. Stating that boundary matters
because a reader who assumed the flag masked anything would ship an endpoint that
returns a full account number. The one thing this module does with the flag is
diagnostics: :func:`mask_field` and :func:`mask_record` produce redacted renderings so
a failed comparison can show WHERE two records differ without emitting a complete
cardholder identity, and :meth:`FieldSpec.describe` renders a field for an exception
message using no content at all.

No value of any kind is reproduced in this module -- not in a docstring, not in a
comment and not in a doctest. In particular the eight-byte password the baseline
stores in clear is a byte range and nothing more.
"""

from __future__ import annotations

import enum
import hashlib
import re
from collections.abc import Iterator, Mapping, Sequence
from dataclasses import dataclass, replace
from types import MappingProxyType
from typing import Final

# WHY (Assumptions): the public surface is declared explicitly and in sorted order so a
#   consumer's import list can be checked against it mechanically. Sorting matters because
#   this list is long enough that an omission is invisible in an unsorted one, and an
#   omitted name is a symbol a star-import silently stops providing while every other
#   import keeps working.
__all__ = [
    "ACCOUNT_LAYOUT",
    "AlternateKeySpec",
    "BINARY_FULLWORD_MAX_DIGITS",
    "BINARY_HALFWORD_MAX_DIGITS",
    "CARD_LAYOUT",
    "COPYBOOK_OF",
    "CUSTOMER_LAYOUT",
    "DALYTRAN_LAYOUT",
    "DISGROUP_LAYOUT",
    "EXPORT_ACCOUNT_LAYOUT",
    "EXPORT_BRANCH_LENGTH",
    "EXPORT_CARD_LAYOUT",
    "EXPORT_CARD_XREF_LAYOUT",
    "EXPORT_CUSTOMER_LAYOUT",
    "EXPORT_DECLARED_DATASET_KEY_LENGTH",
    "EXPORT_DECLARED_DATASET_KEY_OFFSET",
    "EXPORT_HEADER_LAYOUT",
    "EXPORT_KEY_LENGTH",
    "EXPORT_KEY_OFFSET",
    "EXPORT_PAYLOAD_OFFSET",
    "EXPORT_RECORD_TYPES",
    "EXPORT_TRANSACTION_LAYOUT",
    "EXP_CUST_ADDR_LINES_ELEMENT_LENGTH",
    "EXP_CUST_ADDR_LINES_OCCURS",
    "EXP_CUST_PHONE_NUMS_ELEMENT_LENGTH",
    "EXP_CUST_PHONE_NUMS_OCCURS",
    "FieldSpec",
    "INTTRAN_LAYOUT",
    "Kind",
    "LAYOUTS",
    "LayoutError",
    "MAX_DIGITS",
    "MISSPELLED_FIELDS",
    "PA_ACCOUNT_STATUS_ELEMENT_LENGTH",
    "PA_ACCOUNT_STATUS_OCCURS",
    "PENDING_AUTH_DETAIL_LAYOUT",
    "PENDING_AUTH_SUMMARY_LAYOUT",
    "Provenance",
    "RECORD_NAME_PATTERN",
    "RECORD_TYPE_LENGTH",
    "REJECT_LAYOUT",
    "RecordLengthError",
    "RecordSpec",
    "SECUSER_LAYOUT",
    "TCATBAL_LAYOUT",
    "TRANCAT_LAYOUT",
    "TRANTYPE_LAYOUT",
    "TRAN_LAYOUT",
    "TRNX_LAYOUT",
    "XREF_LAYOUT",
    "base_master_names",
    "binary",
    "binary_width",
    "count_fixed_length_records",
    "derived_names",
    "export_branch",
    "has_oracle_round_trip",
    "iter_ascii_text_records",
    "iter_fixed_length_records",
    "keylen_of",
    "layout",
    "mask_field",
    "mask_record",
    "names",
    "normalized_timestamp",
    "packed",
    "packed_width",
    "provenance_of",
    "reclen_of",
    "sensitive_text",
    "sensitive_uint",
    "signed_zoned",
    "text",
    "uint",
    "width_of",
    "zoned_width",
]


class LayoutError(ValueError):
    """Error raised when a layout or field declaration breaks the geometry contract.

    Purpose
    -------
    Signal that a transcription of a copybook is wrong: a blank name, a negative
    offset, a numeric field whose declared width disagrees with its digit counts, a
    field list that leaves a gap or an overlap, a field-length sum that does not close
    against the declared record length, a key that runs past the end of a record, or a
    lookup of a name that is not declared. This is the most consequential failure this
    package has, which is why it gets a type of its own rather than sharing one.

    Assumptions: it subclasses :class:`ValueError` so a caller that already guards
    layout resolution with ``except ValueError`` keeps catching it, while a caller that
    wants the specific signal can catch :class:`LayoutError` directly. The reference
    codec at ``tests/helpers/record_codec.py`` line 147 makes its own error types
    ``ValueError`` subclasses for the same stated reason.

    Assumptions: for a field marked :attr:`FieldSpec.sensitive` the message names only
    the field name, its offset, its length and its kind, and never echoes content. The
    reference implementation interpolates the offending raw value into its own decode
    failure text, which suits a harness reading committed fixtures; this module
    deliberately does not, because the same message here can reach a production log
    carrying a primary account number. The divergence is documented rather than silent,
    and it is a difference in what is REPORTED, never in what is rejected.

    Parameters
    ----------
    args : tuple
        Standard :class:`ValueError` positional arguments, in practice a single
        human-readable message naming the record or field, the offending value and the
        constraint it breached.

    Returns
    -------
    LayoutError
        A new exception instance.

    Raises
    ------
    None
    """


class RecordLengthError(ValueError):
    """Error raised when a physical record does not match its declared width.

    Purpose
    -------
    Signal that raw data presented for record-boundary iteration does not divide into
    whole records of the declared length: a byte image whose size is not an exact
    multiple of ``reclen``, or a text line that is longer than ``reclen`` once a single
    trailing line terminator is removed. This is the enforcement point for the
    no-silent-repair contract, since a financial record of the wrong width is corrupt
    input and reading it would post a misaligned money value.

    Assumptions: it subclasses :class:`ValueError` for the same reason
    :class:`LayoutError` does, and it is a SEPARATE type from it because the two report
    different classes of fault. A :class:`LayoutError` says the transcription in this
    module is wrong; a :class:`RecordLengthError` says the data handed to it is. Fusing
    them would leave a caller unable to tell a bad table from a bad dataset. The
    reference codec draws the identical distinction at
    ``tests/helpers/record_codec.py`` line 179.

    Parameters
    ----------
    args : tuple
        Standard :class:`ValueError` positional arguments, in practice a single
        human-readable message naming the declared length, the observed length and the
        remainder or excess that failed to close.

    Returns
    -------
    RecordLengthError
        A new exception instance.

    Raises
    ------
    None
    """


class Kind(enum.Enum):
    """Physical storage regime of one field, which is what determines how bytes decode.

    Purpose
    -------
    Record, once and statically, which of five storage regimes a field is stored in, so
    that no decoder has to infer it at run time. A field's regime is a property of its
    declared ``USAGE`` and NOT of its picture clause, as the width rules below
    establish, and the regime is knowable for every field in the corpus.

    Assumptions: this is a deliberate SUPERSET of the three values the reference codec's
    field descriptor admits at ``tests/helpers/record_codec.py`` line 421, which are
    text, unsigned display integer and zoned decimal. For that codec three is complete:
    searching all eleven base-master copybooks for ``COMP``, ``COMP-3`` or ``OCCURS``
    returns nothing at all, so those eleven records are entirely zoned display. This
    module additionally serves the export record at ``app/cpy/CVEXPORT.cpy`` and the two
    authorization segments at ``app/app-authorization-ims-db2-mq/cpy/CIPAUSMY.cpy`` and
    ``.../CIPAUDTY.cpy``, and both families store money packed and identifiers binary.

    Alternatives Considered: narrowing to the reference's three values so the two
    descriptors matched member for member. Rejected because it would leave the export
    record and the entire authorization bounded context unrepresentable -- there would
    be no regime to record for a ``COMP-3`` amount, so its seven bytes could only be
    described as seven characters and every field after it would decode as text. The
    Java parity anchor declares the same five members for the same reason.

    Returns
    -------
    Kind
        One of the five enumerated regimes.

    Raises
    ------
    None
    """

    # WHY (Assumptions): character data, the regime in force when no USAGE clause is
    #   present, exemplified by 05  CARD-NUM  PIC X(16). at app/cpy/CVACT02Y.cpy line 5.
    #   It is the most common regime in the corpus by a wide margin.
    TEXT = "TEXT"

    # WHY (Assumptions): an UNSIGNED display integer, PIC 9(n) with no S and no USAGE,
    #   one printable digit per byte. It is kept distinct from ZONED because it carries
    #   no sign overpunch at all, so its low-order byte is an ordinary digit character
    #   rather than one that has to be folded; folding it would turn the digit into a
    #   letter and the field would stop parsing. app/cpy/CVACT01Y.cpy line 5 declares
    #   ACCT-ID this way, and the reference codec draws the same distinction.
    UINT = "UINT"

    # WHY (Assumptions): a SIGNED display decimal, PIC S9(n)V99 with no USAGE clause,
    #   one printable digit per byte with the sign folded into the low-order digit as an
    #   overpunch. This is the money regime of all eleven base masters, exemplified by
    #   05  ACCT-CURR-BAL  PIC S9(10)V99. at app/cpy/CVACT01Y.cpy line 7. The overpunch
    #   consumes no byte of its own, which is why the zoned width is the digit count
    #   exactly rather than the digit count plus one.
    ZONED = "ZONED"

    # WHY (Assumptions): packed decimal, USAGE COMP-3, two digits per byte with the sign
    #   in the low-order nibble of the last byte. It reaches this module through three
    #   record families and no others: app/cpy/CVEXPORT.cpy declares four such fields at
    #   its lines 41, 50, 52 and 71, CIPAUSMY.cpy declares seven, and CIPAUDTY.cpy uses
    #   it for money and for both halves of its composite key. The authorization context
    #   is consequently the only place packed decimal reaches persisted target data.
    PACKED = "PACKED"

    # WHY (Assumptions): binary, USAGE COMP, whose synonym spelling in the language is
    #   the word this member is named for. Its width is a whole machine unit selected by
    #   the digit count rather than one byte per digit, which is why it needs a rule of
    #   its own rather than sharing the packed rule. app/cpy/CVEXPORT.cpy declares seven
    #   such fields at its lines 16, 25, 57, 72, 87, 95 and 96, and CIPAUSMY.cpy
    #   declares two.
    BINARY = "BINARY"


class Provenance(enum.Enum):
    """Classification of a registered layout by where its geometry comes from.

    Purpose
    -------
    Separate the layouts transcribed directly from a copybook that defines a persistent
    dataset from those built out of one that is. The distinction is modelled rather than
    inferred from a name because inferring would encode the population in a condition,
    and the population is exactly the thing that is easy to miscount.

    Assumptions: the two populations this separates are BOTH eleven strong and are NOT
    the same eleven, and conflating them is how three records go missing with nothing
    reporting the loss. The reference codec registers eleven layouts in its ``LAYOUTS``
    table at ``tests/helpers/record_codec.py`` lines 1349 to 1361. This migration has
    eleven base masters. Only EIGHT names appear in both: the three base masters absent
    from the reference registry are the transaction type at ``app/cpy/CVTRA03Y.cpy``,
    the transaction category at ``app/cpy/CVTRA04Y.cpy`` and the user security record at
    ``app/cpy/CSUSR01Y.cpy``, and the reference makes its count up with three entries
    that are derived rather than transcribed. Both the count of eight and the count of
    eleven are correct at different levels, so this registry carries all fourteen names
    with each one labelled and neither count can be mistaken for the other.

    Returns
    -------
    Provenance
        One of the two enumerated provenances.

    Raises
    ------
    None
    """

    # WHY (Assumptions): a record transcribed directly from a copybook that defines a
    #   persistent dataset. There are exactly eleven, one per base master, and they are
    #   the population the copybook-is-normative rule speaks about. The provenance is
    #   carried as data rather than inferred from a name, because a base master's geometry
    #   has to be proven against its own copybook and dataset definition while a derived
    #   record inherits geometry already proven; a caller that cannot tell the two apart
    #   cannot tell which records a change still obliges it to re-verify.
    BASE_MASTER = "BASE_MASTER"

    # WHY (Assumptions): a record whose geometry is built from a base master rather than
    #   transcribed independently, either by appending fields to it or by altering one
    #   field's flags. There are exactly three. Deriving them is what keeps the shared
    #   prefix single-sourced, so a correction to a base master reaches the derived
    #   record with no second edit.
    DERIVED = "DERIVED"


# WHY (Assumptions): a COBOL data name in this corpus is upper case with hyphens and
#   digits and nothing else, so the shape is checkable. The guard is worth its cost
#   because the failure it catches is invisible: a name transcribed with a stray
#   trailing space or in lower case still constructs a valid-looking descriptor, and
#   every subsequent lookup by the correct name then raises "no such field" while the
#   record itself decodes fine. Verified against every name declared below, including
#   the digit-bearing PA-AUTH-DATE-9C at CIPAUDTY.cpy line 20 and the four-part
#   CUST-DOB-YYYY-MM-DD at CVCUS01Y.cpy line 19.
RECORD_NAME_PATTERN: Final[re.Pattern[str]] = re.compile(r"[A-Z0-9]+(?:-[A-Z0-9]+)*")

# WHY (Assumptions): eighteen is the largest number of digit positions the reference
#   dialect admits in a picture clause, so a declaration beyond it is a transcription
#   error rather than an unusually wide field. The widest declaration actually present in
#   the corpus is twelve, PIC S9(10)V99, which appears zoned, packed AND binary inside
#   app/cpy/CVEXPORT.cpy at its lines 51, 50 and 57 respectively.
MAX_DIGITS: Final[int] = 18

# WHY (Assumptions): the binary width tiers are a step function of the digit count, not
#   an arithmetic expression of it, so the two boundaries are named rather than computed.
#   Four digits or fewer occupy two bytes and five through nine occupy four;
#   app/cpy/CVEXPORT.cpy exercises both at its lines 96 and 25.
# WHY (Assumptions): a binary field's WIDTH is all these two constants govern, and there
#   is deliberately no third constant for its ALIGNMENT, because there is NO SYNCHRONIZED
#   clause anywhere in the reference tree. No alignment padding therefore applies to any
#   binary or packed field, and a field always starts exactly where the previous one
#   ended. This is stated rather than left unsaid because a reader who assumed halfword
#   alignment would insert a phantom pad byte before each COMP field, which shifts every
#   field after it and closes none of the sums proven below.
BINARY_HALFWORD_MAX_DIGITS: Final[int] = 4
BINARY_FULLWORD_MAX_DIGITS: Final[int] = 9


def _require_name(name: str, description: str) -> str:
    """Validate one COBOL data name and return it unchanged.

    Purpose
    -------
    Reject a blank or malformed name at construction so that a name is always usable as
    a lookup key. Every descriptor in this module is addressed by name, so a name that
    does not round-trip is a descriptor no caller can reach.

    Parameters
    ----------
    name : str
        The candidate name, expected upper case with hyphens and digits.
    description : str
        What the name names, interpolated into the failure message so a caller can tell
        a bad field name from a bad record name.

    Returns
    -------
    str
        ``name`` unchanged, once validated.

    Raises
    ------
    LayoutError
        If ``name`` is not a string, is empty or blank, or does not match
        :data:`RECORD_NAME_PATTERN`.
    """
    if not isinstance(name, str) or not name.strip():
        raise LayoutError(
            f"a {description} must be a non-blank string, but was {type(name).__name__}"
        )
    if RECORD_NAME_PATTERN.fullmatch(name) is None:
        raise LayoutError(
            f"a {description} must be upper case with hyphens and digits only, but was {name!r}"
        )
    return name


def _require_digits(int_digits: int, dec_digits: int, kind: Kind) -> int:
    """Validate a digit-position pair and return their sum.

    Purpose
    -------
    Assert the three guarantees every numeric width rule needs, once here rather than
    three times over. A negative count is a transcription error; a pair summing to zero
    describes a numeric field with no digits, which no picture clause can express; and a
    sum beyond :data:`MAX_DIGITS` exceeds what the reference dialect admits, so it too is
    a transcription error rather than an unusually wide field.

    Parameters
    ----------
    int_digits : int
        Number of digit positions before the implied decimal point; zero or more.
    dec_digits : int
        Number of digit positions after the implied decimal point; zero or more.
    kind : Kind
        The regime being validated, named in the failure message so a caller can tell
        which of the three width rules rejected the pair.

    Returns
    -------
    int
        The total number of digit positions, being the sum of the two arguments.

    Raises
    ------
    LayoutError
        If either count is negative, if both are zero, or if their sum exceeds
        :data:`MAX_DIGITS`.
    """
    if int_digits < 0 or dec_digits < 0:
        raise LayoutError(
            f"digit positions of a {kind.name} field must not be negative, but int_digits="
            f"{int_digits} and dec_digits={dec_digits}"
        )
    digits = int_digits + dec_digits
    if digits == 0:
        raise LayoutError(
            f"a {kind.name} field must declare at least one digit position, but int_digits and"
            " dec_digits are both zero"
        )
    if digits > MAX_DIGITS:
        raise LayoutError(
            f"a {kind.name} field must not declare more than {MAX_DIGITS} digit positions, but"
            f" int_digits={int_digits} and dec_digits={dec_digits} sum to {digits}"
        )
    return digits


def zoned_width(int_digits: int, dec_digits: int) -> int:
    """Return the byte width of a zoned display field carrying the given digits.

    Purpose
    -------
    Apply the display width rule from one place: a zoned field stores one printable digit
    per byte and folds its sign into the low-order digit as an overpunch, so the sign
    consumes no byte of its own and the width is the digit count exactly.

    Assumptions: that is why ``PIC S9(09)V99`` is eleven bytes and ``PIC S9(10)V99`` is
    twelve, and it is confirmed independently by ``app/jcl/PRTCATBL.jcl`` line 50, whose
    symbol name ``TRAN-CAT-BAL,18,11,ZD`` declares eleven bytes for the ``PIC S9(09)V99``
    field at ``app/cpy/CVTRA01Y.cpy`` line 9. The implied decimal point occupies zero
    bytes, so counting the ``V`` as a character would add one spurious byte to every
    money field in the corpus.

    Trade-offs: the two digit counts are taken separately rather than pre-added, which is
    marginally more to pass at every call site. It buys a call that reads as the picture
    clause does, so ``signed_zoned("TRAN-AMT", 132, 9, 2)`` is checkable against
    ``PIC S9(09)V99`` at a glance instead of against an eleven whose derivation the
    reader would have to reconstruct.

    Parameters
    ----------
    int_digits : int
        Number of digit positions before the implied decimal point; zero or more.
    dec_digits : int
        Number of digit positions after the implied decimal point; zero or more.

    Returns
    -------
    int
        The physical byte width of the field, being the total digit count.

    Raises
    ------
    LayoutError
        If either count is negative, if both are zero, or if their sum exceeds
        :data:`MAX_DIGITS`.
    """
    return _require_digits(int_digits, dec_digits, Kind.ZONED)


def packed_width(int_digits: int, dec_digits: int) -> int:
    """Return the byte width of a packed decimal field carrying the given digits.

    Purpose
    -------
    Apply the ``COMP-3`` width rule from the one place it is allowed to live: a packed
    field stores two digits per byte and reserves the low-order nibble of its last byte
    for the sign, so its width is the ceiling of one more than the digit count, halved.
    The ladder that produces is 3 digits to 2 bytes, 5 to 3, 9 to 5, 11 to 6 and 12 to 7,
    and every rung is present in the corpus: three and five digits at
    ``app/app-authorization-ims-db2-mq/cpy/CIPAUDTY.cpy`` lines 20 and 21, nine and
    eleven at ``.../CIPAUSMY.cpy``, and twelve at ``app/cpy/CVEXPORT.cpy`` line 50.

    Assumptions: the twelve-digit rung is SEVEN bytes and not six. Twelve digit positions
    plus one sign nibble is thirteen nibbles, and thirteen nibbles occupy the ceiling of
    thirteen halved, which is seven. It is proven twice over from files that had no
    reason to agree. Arithmetically, ``app/cpy/CVEXPORT.cpy`` declares five overlay
    branches over one 460-byte area and its account branch at lines 47 to 60 closes at
    exactly 460 only when each of its two packed amounts is seven bytes; at six each the
    branch would close at 458, which a 460-byte area does not admit. By contradiction,
    ``.../CIPAUDTY.cpy`` sums to exactly 200 bytes at seven and to 198 at six, and 198 is
    not a length that segment can have. Any statement that ``PIC S9(10)V99 COMP-3`` is
    six bytes is defective and must not be propagated.

    Parameters
    ----------
    int_digits : int
        Number of digit positions before the implied decimal point; zero or more.
    dec_digits : int
        Number of digit positions after the implied decimal point; zero or more.

    Returns
    -------
    int
        The physical byte width of the field.

    Raises
    ------
    LayoutError
        If either count is negative, if both are zero, or if their sum exceeds
        :data:`MAX_DIGITS`.
    """
    digits = _require_digits(int_digits, dec_digits, Kind.PACKED)

    # WHY (Alternatives Considered): adding two before an integer halving is the ceiling
    #   of one more than the digit count halved, and it is written this way rather than
    #   with math.ceil because that function returns through a binary floating-point
    #   division, which this package bars from its numeric path entirely. Adding one and
    #   halving would FLOOR instead of ceiling and would return six for twelve digits,
    #   which is exactly the defective figure the docstring above refutes.
    return (digits + 2) // 2


def binary_width(int_digits: int, dec_digits: int) -> int:
    """Return the byte width of a binary field carrying the given digits.

    Purpose
    -------
    Apply the ``COMP`` width rule: a binary field occupies a whole machine unit selected
    by its digit count rather than one byte per digit, so its width is a step function
    with the two boundaries named on :data:`BINARY_HALFWORD_MAX_DIGITS` and
    :data:`BINARY_FULLWORD_MAX_DIGITS` -- two bytes to four digits, four bytes to nine
    and eight bytes to eighteen. The value is stored big-endian.

    Assumptions: all three tiers are exercised inside ``app/cpy/CVEXPORT.cpy``, at its
    lines 96, 25 and 87 respectively, and the eight-byte tier is load-bearing there: its
    cross-reference branch at lines 84 to 88 closes at exactly 460 bytes only because its
    eleven-digit binary field is eight bytes wide. The rule is also provable from real
    bytes rather than from arithmetic alone. ``EXPORT-SEQUENCE-NUM`` is declared
    ``PIC 9(9) COMP`` at line 16 of that copybook, which this rule makes four bytes at
    zero-based offset 27; reading four big-endian bytes there in
    ``app/data/EBCDIC/AWS.M2.CARDDEMO.EXPORT.DATA.PS`` yields clean consecutive integers
    -- record 0 gives 1, record 9 gives 10, record 265 gives 266 and record 499 gives 509
    -- which no other width produces. That reading also explains why the file contains
    five stray newline bytes: the integers 10 and 266 encode a low-order 0x0A.

    Parameters
    ----------
    int_digits : int
        Number of digit positions before the implied decimal point; zero or more.
    dec_digits : int
        Number of digit positions after the implied decimal point; zero or more.

    Returns
    -------
    int
        The physical byte width of the field, being 2, 4 or 8.

    Raises
    ------
    LayoutError
        If either count is negative, if both are zero, or if their sum exceeds
        :data:`MAX_DIGITS`.
    """
    digits = _require_digits(int_digits, dec_digits, Kind.BINARY)
    if digits <= BINARY_HALFWORD_MAX_DIGITS:
        return 2
    if digits <= BINARY_FULLWORD_MAX_DIGITS:
        return 4
    return 8


def width_of(kind: Kind, int_digits: int, dec_digits: int) -> int:
    """Return the byte width a numeric field of the given kind and digits must occupy.

    Purpose
    -------
    Dispatch to exactly one of the three width rules so that field validation applies the
    rule from a single place rather than each declaration site choosing which rule
    applies. A field whose kind and digit counts are known therefore has exactly one
    admissible width, and :class:`FieldSpec` rejects any other.

    Assumptions: the two non-numeric kinds are refused rather than answered. A character
    or unsigned display field takes its width from its declared character count, so a
    width derived from digit counts is a caller error and not a value this function could
    compute. Refusing keeps the numeric-width contract total.

    Parameters
    ----------
    kind : Kind
        The storage regime of the field; must be :attr:`Kind.ZONED`,
        :attr:`Kind.PACKED` or :attr:`Kind.BINARY`.
    int_digits : int
        Number of digit positions before the implied decimal point; zero or more.
    dec_digits : int
        Number of digit positions after the implied decimal point; zero or more.

    Returns
    -------
    int
        The physical byte width the field must occupy for that kind and digit count.

    Raises
    ------
    LayoutError
        If ``kind`` is not a :class:`Kind`, if ``kind`` is :attr:`Kind.TEXT` or
        :attr:`Kind.UINT`, if either digit count is negative, if both are zero, or if
        their sum exceeds :data:`MAX_DIGITS`.
    """
    if not isinstance(kind, Kind):
        raise LayoutError(
            "kind must be a Kind when deriving a numeric field width, but was"
            f" {type(kind).__name__}"
        )
    if kind is Kind.ZONED:
        return zoned_width(int_digits, dec_digits)
    if kind is Kind.PACKED:
        return packed_width(int_digits, dec_digits)
    if kind is Kind.BINARY:
        return binary_width(int_digits, dec_digits)
    raise LayoutError(
        f"the width of a {kind.name} field is its declared character count, not a digit-derived"
        " width; only ZONED, PACKED and BINARY widths are derived from digit counts"
    )


@dataclass(frozen=True, slots=True)
class FieldSpec:
    """Descriptor for one elementary field within a fixed-width record.

    Purpose
    -------
    Capture everything a decoder needs to locate and interpret a single field, and
    nothing it does not: the field's name, where it starts, how wide it is, which storage
    regime holds it, how many digit positions it declares on either side of an implied
    decimal point, whether it carries a sign, whether it holds a run-generated timestamp,
    and whether its bytes must be kept out of diagnostics.

    Assumptions: the nine components below are the nine the reference field descriptor at
    ``tests/helpers/record_codec.py`` line 421 carries and the nine the Java parity anchor
    declares, in the same order in all three, so a reader holding any two open reads one
    contract rather than two.

    Assumptions: instances are frozen because a layout is a constant transcribed from a
    copybook and the registry is a table every consumer shares. A mutable descriptor would
    let one caller alter the geometry every other caller sees, and since a record read
    against altered geometry still decodes to digits, nothing would report the change.

    Assumptions: ``start`` is ZERO-based and :attr:`end` is EXCLUSIVE, so the two bound a
    half-open interval and a field occupies ``raw[start:end]``. That convention is chosen
    because it is the one Python slicing already takes, so no call site adjusts an index
    before slicing. It is the opposite of the ONE-based inclusive convention the reference
    JCL and DFSORT declarations use; the conversion is always ``zero_based = one_based - 1``.

    Parameters
    ----------
    name : str
        The field name exactly as the copybook declares it, upper case with hyphens
        retained and any baseline misspelling preserved verbatim. Never blank.
    start : int
        The ZERO-based byte offset of the field from the start of the record; zero or
        more.
    length : int
        The physical byte width of the field, which for a numeric kind must equal the
        width its digit counts imply under :func:`width_of`; one or more.
    kind : Kind
        The storage regime that determines how the field's bytes decode.
    int_digits : int
        The number of digit positions before the implied decimal point, or zero for a
        non-numeric field.
    dec_digits : int
        The number of digit positions after the implied decimal point introduced by a
        ``V``, or zero for a non-numeric field.
    signed : bool
        Whether the picture clause carries a leading ``S``, and therefore whether the
        field carries a sign at all.
    normalize_ts : bool
        Whether the field holds a run-generated timestamp that a parity comparison blanks
        before comparing. True only for wall-clock stamps.
    sensitive : bool
        Whether the field's raw bytes must be kept out of every log line and exception
        message. The flag MARKS the field and performs no masking itself.

    Returns
    -------
    FieldSpec
        A new immutable, validated field descriptor.

    Raises
    ------
    LayoutError
        If the name is blank or malformed, the kind is not a :class:`Kind`, the offset is
        negative, the length is below one, either digit count is negative, a non-numeric
        field declares digit positions or a sign, or a numeric field's length disagrees
        with the width its kind and digit counts imply.
    """

    name: str
    start: int
    length: int
    kind: Kind
    int_digits: int = 0
    dec_digits: int = 0
    signed: bool = False
    normalize_ts: bool = False
    sensitive: bool = False

    def __post_init__(self) -> None:
        """Validate the nine components and reject any declaration the contract forbids.

        Purpose
        -------
        Refuse a malformed descriptor at construction, so that a descriptor which exists
        is a descriptor that is consistent. The most valuable check here is the last one:
        it makes a numeric field's declared length and its digit counts two statements of
        one fact and forces them to agree, which is the mechanical guard against a
        twelve-digit packed field declared six bytes wide. Such a field would shift every
        field after it by one byte, and a one-byte shift still decodes to digits, so
        nothing downstream would ever raise.

        Parameters
        ----------
        None

        Returns
        -------
        None

        Raises
        ------
        LayoutError
            If the name is blank or malformed, the kind is not a :class:`Kind`, the offset
            is negative, the length is below one, either digit count is negative, a
            non-numeric field declares digit positions or a sign, or a numeric field's
            length disagrees with its implied width.
        """
        _require_name(self.name, "field name")
        if not isinstance(self.kind, Kind):
            raise LayoutError(
                f"field {self.name} must declare a Kind, but kind was {type(self.kind).__name__}"
            )
        if self.start < 0:
            raise LayoutError(
                f"field {self.name} must start at a non-negative offset, but start={self.start}"
            )
        if self.length < 1:
            raise LayoutError(
                f"field {self.name} must occupy at least one byte, but length={self.length}"
            )
        if self.int_digits < 0 or self.dec_digits < 0:
            raise LayoutError(
                f"field {self.name} must not declare negative digit positions, but int_digits="
                f"{self.int_digits} and dec_digits={self.dec_digits}"
            )

        # WHY (Assumptions): a character or unsigned-display field takes its width from
        #   its declared character count and has no implied decimal point, so digit counts
        #   on one would be a second, competing statement of the same width. The unsigned
        #   kind is included in this restriction deliberately: PIC 9(11) does have eleven
        #   digit positions in the copybook, but its byte width is already the length, and
        #   recording the eleven twice is exactly what would let the two disagree. Those
        #   digit counts stay recoverable from the length precisely because the two are
        #   equal.
        if self.kind in (Kind.TEXT, Kind.UINT) and (self.int_digits or self.dec_digits):
            raise LayoutError(
                f"field {self.name} of kind {self.kind.name} must leave both digit counts at zero"
                " because its width is its declared character count, but int_digits="
                f"{self.int_digits} and dec_digits={self.dec_digits}"
            )

        # WHY (Assumptions): neither non-numeric kind can carry a sign, and rejecting the
        #   combination is the mechanical expression of the distinction Kind.UINT exists
        #   to draw. An unsigned display field's low-order byte is an ordinary digit
        #   character; marking it signed would tell the zoned decoder to fold that byte as
        #   an overpunch, which turns a digit into a letter and makes the field
        #   unparseable while every other field still reads correctly.
        if self.kind in (Kind.TEXT, Kind.UINT) and self.signed:
            raise LayoutError(
                f"field {self.name} of kind {self.kind.name} must not be signed because it carries"
                " no sign representation at all"
            )

        # WHY (Assumptions): for the three numeric kinds the declared length and the digit
        #   counts are two statements of the same fact, and this is the check that makes
        #   them agree. It is what refuses a PIC S9(10)V99 COMP-3 declared six bytes wide
        #   at the declaration site rather than letting the 460-byte export branches fail
        #   to close 400 lines further down, where the cause would be far less obvious.
        if self.kind in (Kind.ZONED, Kind.PACKED, Kind.BINARY):
            implied = width_of(self.kind, self.int_digits, self.dec_digits)
            if implied != self.length:
                raise LayoutError(
                    f"field {self.name} of kind {self.kind.name} declares int_digits="
                    f"{self.int_digits} and dec_digits={self.dec_digits}, which imply a width of"
                    f" {implied} bytes, but length={self.length}"
                )

    @property
    def end(self) -> int:
        """Return the EXCLUSIVE zero-based end offset, being ``start + length``."""
        return self.start + self.length

    def with_flags(self, normalize_ts: bool, sensitive: bool) -> FieldSpec:
        """Return a copy of this field with the two diagnostic flags replaced.

        Purpose
        -------
        Provide the per-field half of the derivation mechanism
        :meth:`RecordSpec.with_field_flags` uses.

        Assumptions: only the two flags are replaceable, and deliberately not the name,
        the offset, the length or the kind. Those four are the geometry, and a derived
        record that altered any of them would no longer share a prefix with the record it
        derives from, which is the entire property the derivation exists to preserve.

        Parameters
        ----------
        normalize_ts : bool
            Whether the copy holds a run-generated timestamp a parity comparison blanks.
        sensitive : bool
            Whether the copy's raw bytes must be kept out of diagnostics.

        Returns
        -------
        FieldSpec
            A new field with identical geometry and the two given flags.

        Raises
        ------
        LayoutError
            Never in practice, because the geometry carried over from this instance was
            validated when this instance was constructed. It remains documented because
            the constructor this method invokes raises it.
        """
        return replace(self, normalize_ts=normalize_ts, sensitive=sensitive)

    def relocated_to(self, start: int) -> FieldSpec:
        """Return a copy of this field relocated to a new start offset.

        Purpose
        -------
        Support :meth:`RecordSpec.extend_with`, where an appended field's offset is only
        knowable once the length of the prefix it follows is known.

        Assumptions: everything except the offset is carried over, so a relocated field
        cannot silently change its width or its regime while moving.

        Parameters
        ----------
        start : int
            The ZERO-based byte offset the copy starts at; zero or more.

        Returns
        -------
        FieldSpec
            A new field identical to this one but starting at the given offset.

        Raises
        ------
        LayoutError
            If ``start`` is negative.
        """
        return replace(self, start=start)

    def describe(self) -> str:
        """Return a diagnostic rendering of this field that discloses no content.

        Purpose
        -------
        Name the field, its half-open byte interval and its regime, and nothing else. This
        is what a caller uses when reporting a failure against a field that may be marked
        sensitive, so the report stays actionable without a primary account number
        reaching a log line.

        Trade-offs: the dataclass ``repr`` is unsuitable for that use because it prints
        every component. None of the nine is content today, so the difference looks
        cosmetic; it is not, because the generated form would begin printing content the
        moment a component was added, whereas this rendering names four attributes
        explicitly and therefore cannot.

        Parameters
        ----------
        None

        Returns
        -------
        str
            A short description naming the field, its start, its exclusive end and its
            kind, in the form ``NAME[start,end) KIND``.

        Raises
        ------
        None
        """
        return f"{self.name}[{self.start},{self.end}) {self.kind.name}"


def text(name: str, start: int, length: int) -> FieldSpec:
    """Declare a character field of the given declared width.

    Purpose
    -------
    Build the descriptor for a ``PIC X(n)`` field.

    Trade-offs: the eight factories that follow exist instead of calling the
    nine-argument canonical constructor at every declaration site. They cost eight extra
    module members and they buy two things the constructor cannot. A declaration reads as
    its picture clause does, so ``text("ACCT-OPEN-DATE", 48, 10)`` is checkable against
    ``05  ACCT-OPEN-DATE  PIC X(10).`` at a glance rather than by counting positional
    arguments. And for the three kinds whose width is least obvious the factory DERIVES
    the width from the digit counts instead of accepting it, so the seven-versus-six
    packed correction cannot be got wrong at any call site that uses one.

    Parameters
    ----------
    name : str
        The field name exactly as the copybook declares it.
    start : int
        The ZERO-based byte offset of the field from the start of the record.
    length : int
        The declared character count, which for a character field is its byte width.

    Returns
    -------
    FieldSpec
        A character field descriptor carrying neither timestamp normalisation nor
        sensitivity.

    Raises
    ------
    LayoutError
        If the name is blank or malformed, the offset is negative, or the length is below
        one.
    """
    return FieldSpec(name, start, length, Kind.TEXT)


def sensitive_text(name: str, start: int, length: int) -> FieldSpec:
    """Declare a character field whose raw bytes must stay out of diagnostics.

    Purpose
    -------
    Build the descriptor for a ``PIC X(n)`` field that carries cardholder identity or
    payment data, so that :func:`mask_field` redacts it and an exception message reports
    only its geometry.

    Parameters
    ----------
    name : str
        The field name exactly as the copybook declares it.
    start : int
        The ZERO-based byte offset of the field from the start of the record.
    length : int
        The declared character count, which for a character field is its byte width.

    Returns
    -------
    FieldSpec
        A character field descriptor marked sensitive.

    Raises
    ------
    LayoutError
        If the name is blank or malformed, the offset is negative, or the length is below
        one.
    """
    return FieldSpec(name, start, length, Kind.TEXT, sensitive=True)


def normalized_timestamp(name: str, start: int, length: int) -> FieldSpec:
    """Declare a character timestamp field that a parity comparison blanks.

    Purpose
    -------
    Build the descriptor for a ``PIC X(26)`` stamp that is written from the run clock and
    is therefore the only non-deterministic content in its record.

    Assumptions: exactly one of the two timestamps in a transaction record is normalised,
    and the asymmetry is deliberate. The originating stamp is deterministic business data
    copied from the source transaction, so blanking it would discard data a comparison has
    to verify and would shorten the effectively compared record; the processing stamp is
    read from the clock during the run. The reference codec records the same asymmetry in
    its own module docstring and cites a seed value to show the originating stamp does not
    vary between runs. That single exception -- the interest program, which writes the run
    clock into BOTH stamps -- is the reason a separately named derived layout exists rather
    than a per-call override.

    Parameters
    ----------
    name : str
        The field name exactly as the copybook declares it.
    start : int
        The ZERO-based byte offset of the field from the start of the record.
    length : int
        The declared character count of the timestamp.

    Returns
    -------
    FieldSpec
        A character field descriptor marked for timestamp normalisation.

    Raises
    ------
    LayoutError
        If the name is blank or malformed, the offset is negative, or the length is below
        one.
    """
    return FieldSpec(name, start, length, Kind.TEXT, normalize_ts=True)


def uint(name: str, start: int, digits: int) -> FieldSpec:
    """Declare an unsigned display integer field of the given digit count.

    Purpose
    -------
    Build the descriptor for a ``PIC 9(n)`` field with no ``USAGE`` clause, whose byte
    width is its digit count because it stores one printable digit per byte and carries no
    sign.

    Assumptions: the digit count is recorded as the LENGTH and both digit-count components
    stay at zero, matching the Java parity anchor exactly. Recording the digits twice is
    what would let the two disagree, and they are recoverable from the length precisely
    because they are equal to it.

    Parameters
    ----------
    name : str
        The field name exactly as the copybook declares it.
    start : int
        The ZERO-based byte offset of the field from the start of the record.
    digits : int
        The declared digit count, which for an unsigned display field is its byte width.

    Returns
    -------
    FieldSpec
        An unsigned display integer field descriptor.

    Raises
    ------
    LayoutError
        If the name is blank or malformed, the offset is negative, or the digit count is
        below one.
    """
    return FieldSpec(name, start, digits, Kind.UINT)


def sensitive_uint(name: str, start: int, digits: int) -> FieldSpec:
    """Declare an unsigned display integer whose raw bytes must stay out of diagnostics.

    Purpose
    -------
    Build the descriptor for a ``PIC 9(n)`` field that carries identity or payment data --
    the card verification value and the national identifier are the two in this corpus.

    Parameters
    ----------
    name : str
        The field name exactly as the copybook declares it.
    start : int
        The ZERO-based byte offset of the field from the start of the record.
    digits : int
        The declared digit count, which for an unsigned display field is its byte width.

    Returns
    -------
    FieldSpec
        An unsigned display integer field descriptor marked sensitive.

    Raises
    ------
    LayoutError
        If the name is blank or malformed, the offset is negative, or the digit count is
        below one.
    """
    return FieldSpec(name, start, digits, Kind.UINT, sensitive=True)


def signed_zoned(name: str, start: int, int_digits: int, dec_digits: int) -> FieldSpec:
    """Declare a signed zoned display field, deriving its width from its digit counts.

    Purpose
    -------
    Build the descriptor for a ``PIC S9(i)V(d)`` field with no ``USAGE`` clause. This is
    the money regime of all eleven base masters, so it is the factory the great majority
    of monetary fields in this module are declared through.

    Assumptions: the width is derived by :func:`zoned_width` rather than accepted, so a
    call site cannot state a width that contradicts the picture clause it transcribes.

    Parameters
    ----------
    name : str
        The field name exactly as the copybook declares it.
    start : int
        The ZERO-based byte offset of the field from the start of the record.
    int_digits : int
        Number of digit positions before the implied decimal point.
    dec_digits : int
        Number of digit positions after the implied decimal point.

    Returns
    -------
    FieldSpec
        A signed zoned display field descriptor whose length is the total digit count.

    Raises
    ------
    LayoutError
        If the name is blank or malformed, the offset is negative, either digit count is
        negative, both are zero, or their sum exceeds :data:`MAX_DIGITS`.
    """
    return FieldSpec(
        name,
        start,
        zoned_width(int_digits, dec_digits),
        Kind.ZONED,
        int_digits,
        dec_digits,
        signed=True,
    )


def packed(name: str, start: int, int_digits: int, dec_digits: int, *, signed: bool) -> FieldSpec:
    """Declare a packed decimal field, deriving its width from its digit counts.

    Purpose
    -------
    Build the descriptor for a ``USAGE COMP-3`` field, whose width comes from
    :func:`packed_width` and never from a call site.

    Trade-offs: ``signed`` is keyword-only and has no default, where the Java parity
    anchor takes it positionally. Both halves of that choice are deliberate. It has no
    default because the corpus genuinely contains both -- the credit score at
    ``app/cpy/CVEXPORT.cpy`` line 41 is unsigned packed while every packed amount in the
    authorization segments is signed -- so a default would silently guess the sign nibble
    convention for one of the two. It is keyword-only because a bare trailing ``True`` at
    a declaration site reads as neither the sign nor anything else, and the picture clause
    a reader is checking against spells the ``S`` out.

    Parameters
    ----------
    name : str
        The field name exactly as the copybook declares it.
    start : int
        The ZERO-based byte offset of the field from the start of the record.
    int_digits : int
        Number of digit positions before the implied decimal point.
    dec_digits : int
        Number of digit positions after the implied decimal point.
    signed : bool
        Whether the picture clause carries a leading ``S``.

    Returns
    -------
    FieldSpec
        A packed decimal field descriptor whose length is the ceiling of one more than the
        digit count, halved.

    Raises
    ------
    LayoutError
        If the name is blank or malformed, the offset is negative, either digit count is
        negative, both are zero, or their sum exceeds :data:`MAX_DIGITS`.
    """
    return FieldSpec(
        name,
        start,
        packed_width(int_digits, dec_digits),
        Kind.PACKED,
        int_digits,
        dec_digits,
        signed=signed,
    )


def binary(name: str, start: int, int_digits: int, dec_digits: int, *, signed: bool) -> FieldSpec:
    """Declare a binary field, deriving its width from its digit counts.

    Purpose
    -------
    Build the descriptor for a ``USAGE COMP`` field, whose width comes from
    :func:`binary_width` and never from a call site.

    Trade-offs: ``signed`` is keyword-only with no default for the reasons recorded on
    :func:`packed`. The corpus contains both here as well -- the two authorization counters
    at ``.../CIPAUSMY.cpy`` lines 27 and 28 are signed binary while every binary
    identifier in the export record is unsigned.

    Parameters
    ----------
    name : str
        The field name exactly as the copybook declares it.
    start : int
        The ZERO-based byte offset of the field from the start of the record.
    int_digits : int
        Number of digit positions before the implied decimal point.
    dec_digits : int
        Number of digit positions after the implied decimal point.
    signed : bool
        Whether the picture clause carries a leading ``S``.

    Returns
    -------
    FieldSpec
        A binary field descriptor whose length is 2, 4 or 8 bytes.

    Raises
    ------
    LayoutError
        If the name is blank or malformed, the offset is negative, either digit count is
        negative, both are zero, or their sum exceeds :data:`MAX_DIGITS`.
    """
    return FieldSpec(
        name,
        start,
        binary_width(int_digits, dec_digits),
        Kind.BINARY,
        int_digits,
        dec_digits,
        signed=signed,
    )


@dataclass(frozen=True, slots=True)
class AlternateKeySpec:
    """Descriptor for one alternate index declared over a record layout.

    Purpose
    -------
    Model a secondary access path so a loader can reproduce it as a real database index.
    The migration treats alternate indexes as access paths rather than decoration, because
    the reference programs read through them: the cross-reference file is keyed primarily
    on its sixteen-byte card number but the interest program reads it by account
    identifier, and the report chain reads the transaction file by processing timestamp.
    Three exist in the baseline and all three are declared here -- ``KEYS(11 16)`` at
    ``app/jcl/CARDFILE.jcl`` line 85, ``KEYS(11,25)`` at ``app/jcl/XREFFILE.jcl`` line 74
    and ``KEYS(26 304)`` at ``app/jcl/TRANFILE.jcl`` line 84.

    Trade-offs: this is SUPPLEMENTARY metadata, carried on
    :attr:`RecordSpec.alternate_keys` outside the five core components, and that placement
    is the compromise. The Java parity anchor carries the primary key only and derives the
    secondary path from the fact that the underlying field is a first-class field anyway,
    which keeps its record descriptor to five components exactly. This module carries the
    metadata as well because the ETL must build the index rather than merely read it, and
    the reference codec models the same thing on its own layout at
    ``tests/helpers/record_codec.py`` line 597 for the identical reason. Keeping it in a
    separately named optional attribute is what lets both statements be true: the five
    core components are untouched and identically ordered, so cross-language parity is
    intact, and the access path is still declared where a loader can find it.

    Parameters
    ----------
    name : str
        The field name backing the alternate key, exactly as the copybook declares it.
    offset : int
        The ZERO-based byte offset of the alternate key within the record.
    length : int
        The byte width of the alternate key.
    duplicates : bool
        Whether several records may share one alternate-key value. Defaults to True.

    Returns
    -------
    AlternateKeySpec
        A new immutable, validated alternate-key descriptor.

    Raises
    ------
    LayoutError
        If the name is blank or malformed, the offset is negative, or the length is below
        one.
    """

    name: str
    offset: int
    length: int
    duplicates: bool = True

    def __post_init__(self) -> None:
        """Validate the four components of this alternate-key descriptor.

        Purpose
        -------
        Refuse a malformed alternate key at construction, because an index built from one
        is a malformed index rather than an error: it loads and then answers the wrong
        rows.

        Parameters
        ----------
        None

        Returns
        -------
        None

        Raises
        ------
        LayoutError
            If the name is blank or malformed, the offset is negative, or the length is
            below one.
        """
        _require_name(self.name, "alternate key name")
        if self.offset < 0:
            raise LayoutError(
                f"alternate key {self.name} must start at a non-negative offset, but offset="
                f"{self.offset}"
            )
        if self.length < 1:
            raise LayoutError(
                f"alternate key {self.name} must span at least one byte, but length={self.length}"
            )


@dataclass(frozen=True, slots=True)
class RecordSpec:
    """Descriptor for one whole fixed-width record as an ordered, contiguous field list.

    Purpose
    -------
    Bind a logical record name, its total length, its primary key geometry and its ordered
    field descriptors into the one object every reader, loader and verification pass
    resolves geometry through.

    Assumptions: the five components below are the five the Java parity anchor declares, in
    the same order, and :attr:`alternate_keys` follows them as supplementary metadata that
    never disturbs them.

    Assumptions: ``key_length`` is carried alongside ``reclen`` because both are needed and
    neither implies the other. The reference codec resolves both by name for the same
    reason, offering ``reclen_of`` at ``tests/helpers/record_codec.py`` line 1364 and a key
    length companion immediately after it, and its import-time self-check at line 1684
    validates a table of record-length and key-length PAIRS rather than lengths alone. A
    descriptor carrying only the record length could reproduce neither the dataset
    definitions the reference JCL declares -- ``app/jcl/TCATBALF.jcl`` lines 40 and 41
    state ``KEYS(17 0)`` and ``RECORDSIZE(50 50)`` together -- nor the alternate index key
    ``KEYS(26 304)``, whose entire content is a length and an offset.

    Alternatives Considered: letting one descriptor describe a whole copybook rather than one
    record. Rejected because a copybook is not the unit a record length belongs to:
    ``app/cpy/CVTRA07Y.cpy`` declares SEVEN separate ``01`` groups, at its lines 4, 15, 33,
    48, 50, 56 and 62, being the header, detail, two column-heading and three total lines of
    the 133-column report. Anything reading that file has to enumerate ALL of its ``01``
    levels; stopping at the first silently loses six of the seven, and a report assembled from
    one seventh of its line types is not short by an amount anyone would notice in a byte
    count. One descriptor per record keeps the invariants below meaningful, and a copybook
    declaring several records simply yields several descriptors.

    Assumptions: group items are FLATTENED into ``fields`` and the composite key they
    bracketed survives as ``key_length`` and ``key_offset``. Several of these copybooks
    nest their leading fields inside a group that is the record key: ``TRAN-CAT-KEY`` at
    ``app/cpy/CVTRA01Y.cpy`` line 5 is a 17-byte group of three subordinates,
    ``DIS-GROUP-KEY`` at ``app/cpy/CVTRA02Y.cpy`` line 5 is 16 bytes, ``TRNX-KEY`` at
    ``app/cpy/COSTM01.CPY`` line 21 is 32 bytes and ``PA-AUTHORIZATION-KEY`` at
    ``.../CIPAUDTY.cpy`` line 19 is an eight-byte group of two packed fields. A nested tree
    was considered and rejected: every consumer works in flat byte offsets, so a tree would
    force each of them to re-flatten it, and the re-flattening is precisely the step where
    two consumers can disagree. The reference codec reaches the same conclusion
    independently -- its field list is flat and its composite keys are expressed through a
    key length.

    Parameters
    ----------
    name : str
        The logical record name, which is the registry key. It is a logical name rather
        than a copybook name because two records can share a copybook's geometry without
        being the same record.
    reclen : int
        The total declared record length in bytes, which must equal the sum of the field
        lengths; one or more.
    key_length : int
        The byte width of the primary key, taken from the dataset definition that declares
        it; one or more.
    key_offset : int
        The ZERO-based byte offset at which the primary key begins; zero or more.
    fields : Sequence[FieldSpec]
        The ordered field descriptors covering the record from offset zero with no gap and
        no overlap. Stored as a ``tuple`` so the collection cannot be mutated through a
        reference a caller retained or handed in.
    alternate_keys : Sequence[AlternateKeySpec]
        Zero or more supplementary alternate-index descriptors. Empty for a record with
        only a primary key. Stored as a ``tuple`` for the same reason.

    Returns
    -------
    RecordSpec
        A new immutable record descriptor whose per-component sanity is validated.
        Whole-record geometry is proven separately by :meth:`validate_geometry`.

    Raises
    ------
    LayoutError
        If the name is blank or malformed, the record length is below one, the key length
        is below one, the key offset is negative, the key runs past the end of the record,
        the field list is empty or holds a non-:class:`FieldSpec`, or an alternate key runs
        past the end of the record.
    """

    name: str
    reclen: int
    key_length: int
    key_offset: int
    fields: Sequence[FieldSpec]
    alternate_keys: Sequence[AlternateKeySpec] = ()

    def __post_init__(self) -> None:
        """Validate the components and freeze both collections into tuples.

        Purpose
        -------
        Guarantee the per-component sanity that no amount of later checking could recover
        from, and convert both incoming sequences into tuples so the registry is a constant
        in fact rather than by convention.

        Trade-offs: both collections are copied, which costs one allocation per
        construction. In exchange a table every consumer reads cannot be mutated through a
        list a caller kept a reference to. That is not a theoretical concern for a shared
        constant: one removal from a shared list changes the geometry every subsequent
        decode uses, and a record decoded against a field list missing an entry still
        yields values for the fields that remain.

        Alternatives Considered: performing the full contiguity and sum validation here
        rather than in :meth:`validate_geometry`. Rejected so the derivation helpers can
        build an intermediate record and then prove it, and so the invariant has a named,
        independently callable home a test can aim at directly. Every layout this module
        ships calls :meth:`validate_geometry` at its own declaration, so nothing escapes
        the check. The reference codec makes the same split, validating its assembled
        registry once at import rather than inside its layout constructor.

        Parameters
        ----------
        None

        Returns
        -------
        None

        Raises
        ------
        LayoutError
            If the name is blank or malformed, the record length is below one, the key
            length is below one, the key offset is negative, the key runs past the end of
            the record, the field list is empty or holds a non-:class:`FieldSpec`, or an
            alternate key runs past the end of the record.
        """
        _require_name(self.name, "record name")
        if self.reclen < 1:
            raise LayoutError(
                f"record {self.name} must declare a length of at least one byte, but reclen="
                f"{self.reclen}"
            )
        if self.key_length < 1:
            raise LayoutError(
                f"record {self.name} must declare a key of at least one byte, but key_length="
                f"{self.key_length}"
            )
        if self.key_offset < 0:
            raise LayoutError(
                f"record {self.name} must declare a non-negative key offset, but key_offset="
                f"{self.key_offset}"
            )

        # WHY (Assumptions): a key that runs past the end of the record cannot be extracted
        #   at all, so it is refused here rather than at the first read. The reference codec
        #   performs the identical containment check on the primary and on every alternate
        #   key at tests/helpers/record_codec.py line 1738, for the same reason: a malformed
        #   key builds a malformed index instead of raising, and the index then answers with
        #   whichever bytes happened to fall inside the record.
        if self.key_offset + self.key_length > self.reclen:
            raise LayoutError(
                f"record {self.name} declares a key of {self.key_length} bytes at offset"
                f" {self.key_offset}, which ends at {self.key_offset + self.key_length} and"
                f" exceeds its declared length of {self.reclen}"
            )

        frozen_fields = tuple(self.fields)
        if not frozen_fields:
            raise LayoutError(
                f"record {self.name} must declare at least one field, but declared none"
            )
        for candidate in frozen_fields:
            if not isinstance(candidate, FieldSpec):
                raise LayoutError(
                    f"record {self.name} must declare only FieldSpec entries, but found a"
                    f" {type(candidate).__name__}"
                )
        object.__setattr__(self, "fields", frozen_fields)

        frozen_alternates = tuple(self.alternate_keys)
        for alternate in frozen_alternates:
            if not isinstance(alternate, AlternateKeySpec):
                raise LayoutError(
                    f"record {self.name} must declare only AlternateKeySpec alternate keys, but"
                    f" found a {type(alternate).__name__}"
                )
            if alternate.offset + alternate.length > self.reclen:
                raise LayoutError(
                    f"record {self.name} alternate key {alternate.name} spans"
                    f" {alternate.length} bytes at offset {alternate.offset}, which ends at"
                    f" {alternate.offset + alternate.length} and exceeds its declared length of"
                    f" {self.reclen}"
                )
        object.__setattr__(self, "alternate_keys", frozen_alternates)

    def field(self, name: str) -> FieldSpec:
        """Return the field of the given name, resolved only within this record.

        Purpose
        -------
        Give named access to one descriptor without a caller scanning the field list.

        Alternatives Considered: a registry-wide, field-name-keyed map so any field could
        be resolved from any layout. It is impossible for this corpus and the proof is a
        single name. ``TRAN-CAT-KEY`` exists in two copybooks at two different widths: at
        ``app/cpy/CVTRA01Y.cpy`` line 5 it is 17 bytes, being an eleven-byte account
        identifier plus a two-byte type code plus a four-byte category code; at
        ``app/cpy/CVTRA04Y.cpy`` line 5 it is 6 bytes, being only the type and category
        codes. ``TRAN-TYPE-CD`` and ``TRAN-CAT-CD`` recur again as level-05 items in
        ``app/cpy/CVTRA05Y.cpy`` lines 6 and 7, at a different level. A flat map would have
        to pick one of the two widths and would then be wrong for every reader of the
        other, and being wrong by eleven bytes at offset zero misaligns the entire record.
        The two widths are corroborated independently by the dataset definitions,
        ``KEYS(17 0)`` at ``app/jcl/TCATBALF.jcl`` line 40 and ``KEYS(6 0)`` at
        ``app/jcl/TRANCATG.jcl`` line 40, which is why lookup is scoped per record.

        Parameters
        ----------
        name : str
            The field name to resolve, matched exactly as the copybook declares it.

        Returns
        -------
        FieldSpec
            The matching field descriptor.

        Raises
        ------
        LayoutError
            If this record declares no field of that name. The message names this record
            and the requested field so a caller can see which of the two is wrong.
        """
        for candidate in self.fields:
            if candidate.name == name:
                return candidate
        raise LayoutError(
            f"record {self.name} declares no field named {name!r}; a field name resolves only"
            " within one record, never across the registry"
        )

    def validate_geometry(self) -> RecordSpec:
        """Prove that this record's fields tile it exactly, and raise if they do not.

        Purpose
        -------
        Assert the two invariants that make a declared record length a derived fact rather
        than a claim. Fields must be CONTIGUOUS from offset zero, meaning each field starts
        exactly where the previous one ended, which forbids a gap and an overlap in one
        condition; and their lengths must sum to the declared record length EXACTLY, which
        forbids a record described short or long.

        Alternatives Considered: reading the record-length banner most of these copybooks
        carry instead of summing. Rejected because the banner is written four different
        ways across the corpus and is absent from one file entirely -- ``CVACT01Y.cpy`` and
        ``CVCUS01Y.cpy`` write ``RECLN 300`` and ``RECLN 500`` with NO equals sign,
        ``CVTRA01Y.cpy`` writes ``RECLN = 50`` with one, ``CVEXPORT.cpy`` uses the prose
        ``Total Record Length: 500 bytes``, and ``CSUSR01Y.cpy`` carries no banner at all
        because its first sixteen lines are a licence header. A banner strategy would need
        four dialects and would still fail on the fourth file; summing needs one rule and
        never fails, so the banner is retained only as the corroboration it is fit to be.

        Alternatives Considered: expressing these two invariants as ``assert`` statements.
        Rejected because ``python -O`` strips them, so the invariant would hold during a
        test run and vanish in the deployment where a misaligned record actually costs
        money. The reference codec raises rather than asserting for exactly this reason and
        says so at ``tests/helpers/record_codec.py`` line 1710.

        Assumptions: the FIRST breach is reported rather than every breach collected. One
        wrong length shifts every field after it, so a collected report would name a
        cascade of consequences and leave the reader to find the cause, whereas naming the
        first divergence names the cause.

        Parameters
        ----------
        None

        Returns
        -------
        RecordSpec
            This same record, so a declaration can validate and bind in one expression.

        Raises
        ------
        LayoutError
            If any field starts anywhere other than where the previous field ended, or if
            the field lengths do not sum to the declared record length.
        """
        cursor = 0
        for candidate in self.fields:
            if candidate.start != cursor:
                breach = "a gap" if candidate.start > cursor else "an overlap"
                raise LayoutError(
                    f"record {self.name} field {candidate.describe()} starts at {candidate.start}"
                    f" but the preceding fields end at {cursor}; fields must be contiguous from"
                    f" offset zero, so this is {breach}"
                )
            cursor += candidate.length
        if cursor != self.reclen:
            raise LayoutError(
                f"record {self.name} field lengths sum to {cursor} but its declared length is"
                f" {self.reclen}"
            )
        return self

    def extend_with(
        self, derived_name: str, derived_reclen: int, appended: Sequence[FieldSpec]
    ) -> RecordSpec:
        """Derive a longer record by appending fields to this record's field list.

        Purpose
        -------
        Build a record that reuses this one verbatim as its prefix. Exactly one record in
        the corpus needs this: the posting reject stream writes a rejected transaction as
        its unaltered 350-byte daily-transaction image followed by an 80-byte trailer.

        Alternatives Considered: re-declaring the shared prefix by hand in the derived
        record. Rejected outright, because fourteen fields would then exist twice and two
        hand-maintained copies of a 350-byte layout will drift. The drift would be silent:
        a record read against a prefix that is wrong in one field still decodes to digits
        in every field after it. Appending keeps the prefix single-sourced, so a correction
        to the base master reaches the derived record with no second edit. The reference
        suite states the same house rule for its own layouts -- never duplicate a layout,
        keep it single-sourced from ``app/cpy/`` -- and derives this same record the same
        way by concatenating the base field list at
        ``tests/helpers/record_codec.py`` line 1306.

        Assumptions: appended fields are RELOCATED to follow this record's declared length
        rather than trusting the offsets they arrive with. A caller declaring a trailer
        thinks in offsets relative to the whole derived record, and relocating here means
        the two views cannot disagree. The result is validated before it is returned, so an
        appended list whose lengths do not close against ``derived_reclen`` fails at the
        derivation rather than at first use.

        Parameters
        ----------
        derived_name : str
            The logical name of the derived record.
        derived_reclen : int
            The total declared length of the derived record in bytes, which must equal this
            record's length plus the appended lengths.
        appended : Sequence[FieldSpec]
            The fields to append in order, each relocated to sit after this record's
            fields. Must not be empty.

        Returns
        -------
        RecordSpec
            A validated derived record carrying this record's fields followed by the
            appended ones, this record's key geometry and this record's alternate keys.

        Raises
        ------
        LayoutError
            If ``appended`` is empty, if the derived name is blank or malformed, or if the
            resulting fields are not contiguous or do not sum to ``derived_reclen``.
        """
        relocated: list[FieldSpec] = []
        cursor = self.reclen
        for appendee in tuple(appended):
            relocated.append(appendee.relocated_to(cursor))
            cursor += appendee.length
        if not relocated:
            raise LayoutError(
                f"record {self.name} cannot be extended into {derived_name!r} with an empty field"
                " list, because an extension that appends nothing is the base record under a"
                " second name"
            )
        return RecordSpec(
            derived_name,
            derived_reclen,
            self.key_length,
            self.key_offset,
            tuple(self.fields) + tuple(relocated),
            self.alternate_keys,
        ).validate_geometry()

    def with_field_flags(
        self, derived_name: str, field_name: str, normalize_ts: bool, sensitive: bool
    ) -> RecordSpec:
        """Derive a record identical to this one but for one field's diagnostic flags.

        Purpose
        -------
        Build a record with this one's geometry exactly, changing only the two flags of one
        named field. Exactly one record in the corpus needs this: the interest program
        writes its run-clock value into BOTH timestamps of a transaction record whereas the
        posting program copies the originating stamp from its source transaction, so under
        the base layout the interest record's originating stamp would make every comparison
        non-deterministic.

        Alternatives Considered: two other ways to express that were evaluated. Flipping
        the flag on the base record was rejected because ordinary posted transactions must
        keep asserting their deterministic originating stamp, so the base record cannot
        blank it -- doing so would discard business data and shorten the effectively
        compared record. Passing a per-call override into the comparison was rejected
        because the policy would then live at every call site instead of in the layout, and
        a caller that forgot it would produce a failure unrelated to the change under test.
        A named derived layout keeps the policy declarative and discoverable, and the
        reference codec derives the same variant the same way at
        ``tests/helpers/record_codec.py`` line 1333.

        Assumptions: only the two diagnostic flags are replaceable, never the geometry, for
        the reason recorded on :meth:`FieldSpec.with_flags`. A derived record therefore
        always tiles identically to the record it derives from, which is why the result is
        validated and why that validation cannot fail for a geometry reason.

        Parameters
        ----------
        derived_name : str
            The logical name of the derived record.
        field_name : str
            The field whose flags are replaced, resolved within this record.
        normalize_ts : bool
            Whether the named field holds a run-generated timestamp in the derived record.
        sensitive : bool
            Whether the named field's raw bytes must be kept out of diagnostics in the
            derived record.

        Returns
        -------
        RecordSpec
            A validated derived record with the same geometry and the named field's flags
            replaced.

        Raises
        ------
        LayoutError
            If this record declares no field of that name, or if the derived name is blank
            or malformed.
        """
        # WHY (Assumptions): resolving the name BEFORE rebuilding is what turns a
        #   misspelled field name into an immediate, named failure. Without it a name that
        #   matched nothing would produce a derived record identical to the base, which is a
        #   silently wrong layout rather than an error -- and the one flag the derivation
        #   exists to change would simply not be changed.
        self.field(field_name)

        replaced = tuple(
            candidate.with_flags(normalize_ts, sensitive)
            if candidate.name == field_name
            else candidate
            for candidate in self.fields
        )
        return RecordSpec(
            derived_name,
            self.reclen,
            self.key_length,
            self.key_offset,
            replaced,
            self.alternate_keys,
        ).validate_geometry()


# ---------------------------------------------------------------------------
# The eleven base masters, transcribed field for field from their copybooks.
# ---------------------------------------------------------------------------
# WHY (Trade-offs): FILLER is declared as a real field in every layout below rather than
#   omitted, at the cost of one descriptor per record that no domain object will ever
#   carry. It is what makes both invariants in RecordSpec.validate_geometry expressible at
#   all: fields cannot be proven contiguous, and their lengths cannot be proven to sum to
#   the declared record length, if the padding between and after them is undescribed. It is
#   also what lets a re-encode pad correctly, which matters because the parity oracle
#   compares output byte for byte after timestamp normalisation, so a re-encoded record
#   differing only in its padding is a failed comparison. The reference codec keeps
#   Field("FILLER", 36, 14, "text") in its own cross-reference layout for the same reason.
#   Dropping FILLER is therefore deferred to the domain projection, and because the
#   copybook-is-normative rule requires the drop to be RECORDED per record, this module is
#   where that record lives: the trailing pad of every base master is named below and is
#   present in the field list, so a reader can see exactly which bytes the domain object
#   does not carry.
# WHY (Assumptions): two constructs spelled FILLER are NOT padding, and treating them as
#   padding is how a layout silently gains bytes it does not have. A FILLER REDEFINES is an
#   overlay alias and must not advance the offset -- app/cbl/CBTRN02C.cbl line 160 and
#   app/cbl/CBACT04C.cbl line 151 both declare 01 FILLER REDEFINES DB2-FORMAT-TS. And a
#   FILLER carrying a VALUE is CONTENT rather than padding, because the literal IS the
#   data: app/cpy/CVTRA07Y.cpy declares 22 FILLER items and ALL 22 carry a VALUE clause, so
#   there the proportion is not most of them but every one, and those literals are the
#   column headings and rule lines of the 133-column report.
# WHY (Assumptions): the converse case occurs too, which is why padding is judged by role
#   and never by name. app/cpy/CSUSR01Y.cpy line 23 declares SEC-USR-FILLER PIC X(23),
#   which IS the trailing pad despite not being spelled FILLER; a rule that matched the
#   literal token would miss it and the user record would appear to be 57 bytes long.

# WHY (Assumptions): the eight-byte password at app/cpy/CSUSR01Y.cpy line 21 is described
#   here because a descriptor's job is to describe the bytes the reference dataset actually
#   contains, and the loader has to read those eight bytes in order to skip them. The
#   target does NOT carry the field forward at all -- identity moves to a managed user pool
#   and the target user table keeps only a subject reference. That is a documented
#   behavioural change made one layer out, not a transcription choice, and no value of that
#   field appears anywhere in this module.
# WHY (Assumptions): this copybook's 01 group sits at line 17 rather than line 4 like every
#   other base master, because its first sixteen lines are an Apache 2.0 licence header. It
#   is also the one base master with NO record-length banner, which is why its geometry
#   rests on summing alone: six fields summing to 80, corroborated by app/jcl/DUSRSECJ.jcl
#   lines 65 and 66 declaring KEYS(8,0) and RECORDSIZE(80,80), and by the 800-byte
#   AWS.M2.CARDDEMO.USRSEC.PS dividing into exactly ten records.
SECUSER_LAYOUT: Final[RecordSpec] = RecordSpec(
    "SECUSER",
    80,
    8,
    0,
    (
        text("SEC-USR-ID", 0, 8),  # CSUSR01Y L18 PIC X(08)
        sensitive_text("SEC-USR-FNAME", 8, 20),  # CSUSR01Y L19 PIC X(20)
        sensitive_text("SEC-USR-LNAME", 28, 20),  # CSUSR01Y L20 PIC X(20)
        sensitive_text("SEC-USR-PWD", 48, 8),  # CSUSR01Y L21 PIC X(08)
        text("SEC-USR-TYPE", 56, 1),  # CSUSR01Y L22 PIC X(01) 'A' or 'U'
        text("SEC-USR-FILLER", 57, 23),  # CSUSR01Y L23 PIC X(23) named trailing pad
    ),
).validate_geometry()

# WHY (Assumptions): 300 bytes with an eleven-byte key at offset zero, corroborated by
#   app/jcl/ACCTFILE.jcl lines 40 and 41 declaring KEYS(11 0) and RECORDSIZE(300 300), and
#   by the 15000-byte AWS.M2.CARDDEMO.ACCTDATA.PS dividing into exactly fifty records. Its
#   five PIC S9(10)V99 money fields are the canonical zoned case at twelve bytes each, and
#   they are precisely the five NUMERIC(12,2) columns the money-total verification pass
#   aggregates, so their widths are load-bearing for the parity check and not merely for
#   the decode.
# WHY (Refactoring Rationale): ACCT-EXPIRAION-DATE at app/cpy/CVACT01Y.cpy line 11 is
#   misspelled in the baseline and is transcribed WITH the misspelling, because a
#   descriptor whose names did not match the copybook would no longer be a transcription of
#   it. The target column replaces that name with the correctly spelled expiration_date;
#   the old name was wrong only in its spelling, never in its meaning, so the correction is
#   confined to the target naming layer and the lineage is recorded in MISSPELLED_FIELDS
#   below. Per the copybook-is-normative rule NO OTHER field is renamed anywhere.
ACCOUNT_LAYOUT: Final[RecordSpec] = RecordSpec(
    "ACCOUNT",
    300,
    11,
    0,
    (
        uint("ACCT-ID", 0, 11),  # CVACT01Y L5 PIC 9(11) unsigned
        text("ACCT-ACTIVE-STATUS", 11, 1),  # CVACT01Y L6 PIC X(01)
        signed_zoned("ACCT-CURR-BAL", 12, 10, 2),  # CVACT01Y L7 PIC S9(10)V99
        signed_zoned("ACCT-CREDIT-LIMIT", 24, 10, 2),  # CVACT01Y L8 PIC S9(10)V99
        signed_zoned("ACCT-CASH-CREDIT-LIMIT", 36, 10, 2),  # CVACT01Y L9 PIC S9(10)V99
        text("ACCT-OPEN-DATE", 48, 10),  # CVACT01Y L10 PIC X(10)
        text("ACCT-EXPIRAION-DATE", 58, 10),  # CVACT01Y L11 PIC X(10) misspelt
        text("ACCT-REISSUE-DATE", 68, 10),  # CVACT01Y L12 PIC X(10)
        signed_zoned("ACCT-CURR-CYC-CREDIT", 78, 10, 2),  # CVACT01Y L13 PIC S9(10)V99
        signed_zoned("ACCT-CURR-CYC-DEBIT", 90, 10, 2),  # CVACT01Y L14 PIC S9(10)V99
        text("ACCT-ADDR-ZIP", 102, 10),  # CVACT01Y L15 PIC X(10)
        text("ACCT-GROUP-ID", 112, 10),  # CVACT01Y L16 PIC X(10)
        text("FILLER", 122, 178),  # CVACT01Y L17 PIC X(178) trailing pad
    ),
).validate_geometry()

# WHY (Assumptions): 150 bytes with a sixteen-byte key at offset zero, corroborated by
#   app/jcl/CARDFILE.jcl lines 54 and 55, and by the 7500-byte
#   AWS.M2.CARDDEMO.CARDDATA.PS dividing into exactly fifty records. Line 85 of that same
#   job declares an alternate index KEYS(11 16), which is exactly CARD-ACCT-ID, so the
#   secondary access path is carried below rather than left for a reader to rediscover.
# WHY (Refactoring Rationale): CARD-EXPIRAION-DATE at app/cpy/CVACT02Y.cpy line 9 carries
#   the second of the three baseline misspellings and is transcribed with it, for the reason
#   recorded on the account master; its target column is expiration_date.
CARD_LAYOUT: Final[RecordSpec] = RecordSpec(
    "CARD",
    150,
    16,
    0,
    (
        sensitive_text("CARD-NUM", 0, 16),  # CVACT02Y L5 PIC X(16) primary account number
        uint("CARD-ACCT-ID", 16, 11),  # CVACT02Y L6 PIC 9(11) alternate key
        sensitive_uint("CARD-CVV-CD", 27, 3),  # CVACT02Y L7 PIC 9(03) verification value
        sensitive_text("CARD-EMBOSSED-NAME", 30, 50),  # CVACT02Y L8 PIC X(50)
        text("CARD-EXPIRAION-DATE", 80, 10),  # CVACT02Y L9 PIC X(10) misspelt
        text("CARD-ACTIVE-STATUS", 90, 1),  # CVACT02Y L10 PIC X(01)
        text("FILLER", 91, 59),  # CVACT02Y L11 PIC X(59) trailing pad
    ),
    (AlternateKeySpec("CARD-ACCT-ID", 16, 11),),
).validate_geometry()

# WHY (Assumptions): 500 bytes with a nine-byte key at offset zero, corroborated by
#   app/jcl/CUSTFILE.jcl lines 50 and 51, and by the 25000-byte
#   AWS.M2.CARDDEMO.CUSTDATA.PS dividing into exactly fifty records. Its eighteen named
#   fields sum to 332 and the PIC X(168) pad at line 23 completes the 500.
# WHY (Assumptions): this is the most heavily identifying record in the corpus, so twelve
#   of its eighteen named fields are marked sensitive. Two of them drive target encryption
#   specifically, CUST-SSN at line 17 and CUST-GOVT-ISSUED-ID at line 18; the encryption and
#   the masking both happen in the domain projection and not here, so marking the field is
#   the whole of this module's involvement.
CUSTOMER_LAYOUT: Final[RecordSpec] = RecordSpec(
    "CUSTOMER",
    500,
    9,
    0,
    (
        uint("CUST-ID", 0, 9),  # CVCUS01Y L5 PIC 9(09)
        sensitive_text("CUST-FIRST-NAME", 9, 25),  # CVCUS01Y L6 PIC X(25)
        sensitive_text("CUST-MIDDLE-NAME", 34, 25),  # CVCUS01Y L7 PIC X(25)
        sensitive_text("CUST-LAST-NAME", 59, 25),  # CVCUS01Y L8 PIC X(25)
        sensitive_text("CUST-ADDR-LINE-1", 84, 50),  # CVCUS01Y L9 PIC X(50)
        sensitive_text("CUST-ADDR-LINE-2", 134, 50),  # CVCUS01Y L10 PIC X(50)
        sensitive_text("CUST-ADDR-LINE-3", 184, 50),  # CVCUS01Y L11 PIC X(50)
        text("CUST-ADDR-STATE-CD", 234, 2),  # CVCUS01Y L12 PIC X(02)
        text("CUST-ADDR-COUNTRY-CD", 236, 3),  # CVCUS01Y L13 PIC X(03)
        text("CUST-ADDR-ZIP", 239, 10),  # CVCUS01Y L14 PIC X(10)
        sensitive_text("CUST-PHONE-NUM-1", 249, 15),  # CVCUS01Y L15 PIC X(15)
        sensitive_text("CUST-PHONE-NUM-2", 264, 15),  # CVCUS01Y L16 PIC X(15)
        sensitive_uint("CUST-SSN", 279, 9),  # CVCUS01Y L17 PIC 9(09) encrypted at rest
        sensitive_text("CUST-GOVT-ISSUED-ID", 288, 20),  # CVCUS01Y L18 PIC X(20) encrypted
        sensitive_text("CUST-DOB-YYYY-MM-DD", 308, 10),  # CVCUS01Y L19 PIC X(10)
        sensitive_text("CUST-EFT-ACCOUNT-ID", 318, 10),  # CVCUS01Y L20 PIC X(10)
        text("CUST-PRI-CARD-HOLDER-IND", 328, 1),  # CVCUS01Y L21 PIC X(01)
        uint("CUST-FICO-CREDIT-SCORE", 329, 3),  # CVCUS01Y L22 PIC 9(03)
        text("FILLER", 332, 168),  # CVCUS01Y L23 PIC X(168) trailing pad
    ),
).validate_geometry()

# WHY (Assumptions): 50 bytes with a sixteen-byte key at offset zero, corroborated by
#   app/jcl/XREFFILE.jcl lines 43 and 44, and by the 2500-byte
#   AWS.M2.CARDDEMO.CARDXREF.PS dividing into exactly fifty records. Line 74 of that job
#   declares an alternate index KEYS(11,25) -- comma-separated where the transaction file
#   writes the same clause space-separated -- and 25 with length 11 is exactly
#   XREF-ACCT-ID, so the field and the index corroborate each other byte for byte. The
#   interest program reads this file by account identifier rather than by card number, so
#   that index is a hard requirement and not an optimisation, and one account may own
#   several cards, which is why duplicates are permitted.
# WHY (Assumptions): this copybook indents its 01 group by ONE space where every other base
#   master uses two, and that irregularity is the reason a parser for these files must
#   tokenise on whitespace RUNS rather than on fixed columns. The same file family also
#   varies the indentation of its 05 items, so a fixed-column reader would mis-read the
#   level number itself.
XREF_LAYOUT: Final[RecordSpec] = RecordSpec(
    "XREF",
    50,
    16,
    0,
    (
        sensitive_text("XREF-CARD-NUM", 0, 16),  # CVACT03Y L5 PIC X(16) primary key
        uint("XREF-CUST-ID", 16, 9),  # CVACT03Y L6 PIC 9(09)
        uint("XREF-ACCT-ID", 25, 11),  # CVACT03Y L7 PIC 9(11) alternate key
        text("FILLER", 36, 14),  # CVACT03Y L8 PIC X(14) trailing pad
    ),
    (AlternateKeySpec("XREF-ACCT-ID", 25, 11),),
).validate_geometry()

# WHY (Assumptions): 350 bytes with a sixteen-byte key at offset zero, and the 105000-byte
#   AWS.M2.CARDDEMO.DALYTRAN.PS divides into exactly 300 records. Its geometry is field for
#   field the same as the posted transaction record's with a different name prefix on every
#   field, which is exactly why the two are separate registry entries rather than one
#   aliased entry: a decoder resolving DALYTRAN-AMT against the posted layout would find no
#   such field.
# WHY (Assumptions): exactly one of its two timestamps is normalised. The originating stamp
#   at offset 278 is deterministic business data copied from the source transaction and is
#   compared; the processing stamp at offset 304 is read from the clock during the run and is
#   blanked before comparison. Blanking both would discard business data and shorten the
#   effectively compared record by 26 bytes.
DALYTRAN_LAYOUT: Final[RecordSpec] = RecordSpec(
    "DALYTRAN",
    350,
    16,
    0,
    (
        text("DALYTRAN-ID", 0, 16),  # CVTRA06Y L5 PIC X(16)
        text("DALYTRAN-TYPE-CD", 16, 2),  # CVTRA06Y L6 PIC X(02)
        uint("DALYTRAN-CAT-CD", 18, 4),  # CVTRA06Y L7 PIC 9(04)
        text("DALYTRAN-SOURCE", 22, 10),  # CVTRA06Y L8 PIC X(10)
        text("DALYTRAN-DESC", 32, 100),  # CVTRA06Y L9 PIC X(100)
        signed_zoned("DALYTRAN-AMT", 132, 9, 2),  # CVTRA06Y L10 PIC S9(09)V99
        uint("DALYTRAN-MERCHANT-ID", 143, 9),  # CVTRA06Y L11 PIC 9(09)
        text("DALYTRAN-MERCHANT-NAME", 152, 50),  # CVTRA06Y L12 PIC X(50)
        text("DALYTRAN-MERCHANT-CITY", 202, 50),  # CVTRA06Y L13 PIC X(50)
        text("DALYTRAN-MERCHANT-ZIP", 252, 10),  # CVTRA06Y L14 PIC X(10)
        sensitive_text("DALYTRAN-CARD-NUM", 262, 16),  # CVTRA06Y L15 PIC X(16)
        text("DALYTRAN-ORIG-TS", 278, 26),  # CVTRA06Y L16 PIC X(26) deterministic
        normalized_timestamp("DALYTRAN-PROC-TS", 304, 26),  # CVTRA06Y L17 PIC X(26) wall clock
        text("FILLER", 330, 20),  # CVTRA06Y L18 PIC X(20) trailing pad
    ),
).validate_geometry()

# WHY (Assumptions): 350 bytes with a sixteen-byte key at offset zero, corroborated by
#   app/jcl/TRANFILE.jcl lines 53 and 54. This is the record whose offsets three
#   independent sources confirm, as the module docstring sets out: its card number at 262
#   and its processing timestamp at 304 match the ONE-based DFSORT positions 263 and 305 at
#   app/jcl/TRANREPT.jcl lines 41 and 42, and the ZERO-based alternate index KEYS(26 304)
#   at line 84 of the same dataset definition. Those two offsets are the most load-bearing
#   pair in the registry because the report sort and the secondary index both read them.
# WHY (Assumptions): the alternate index over the processing timestamp is a BATCH access
#   path rather than an online one, which is why it permits duplicates: many transactions
#   share one processing instant, and a unique index would reject the second of them at
#   load time.
TRAN_LAYOUT: Final[RecordSpec] = RecordSpec(
    "TRAN",
    350,
    16,
    0,
    (
        text("TRAN-ID", 0, 16),  # CVTRA05Y L5 PIC X(16)
        text("TRAN-TYPE-CD", 16, 2),  # CVTRA05Y L6 PIC X(02)
        uint("TRAN-CAT-CD", 18, 4),  # CVTRA05Y L7 PIC 9(04)
        text("TRAN-SOURCE", 22, 10),  # CVTRA05Y L8 PIC X(10)
        text("TRAN-DESC", 32, 100),  # CVTRA05Y L9 PIC X(100)
        signed_zoned("TRAN-AMT", 132, 9, 2),  # CVTRA05Y L10 PIC S9(09)V99
        uint("TRAN-MERCHANT-ID", 143, 9),  # CVTRA05Y L11 PIC 9(09)
        text("TRAN-MERCHANT-NAME", 152, 50),  # CVTRA05Y L12 PIC X(50)
        text("TRAN-MERCHANT-CITY", 202, 50),  # CVTRA05Y L13 PIC X(50)
        text("TRAN-MERCHANT-ZIP", 252, 10),  # CVTRA05Y L14 PIC X(10)
        sensitive_text("TRAN-CARD-NUM", 262, 16),  # CVTRA05Y L15 PIC X(16) at 262
        text("TRAN-ORIG-TS", 278, 26),  # CVTRA05Y L16 PIC X(26) deterministic
        normalized_timestamp("TRAN-PROC-TS", 304, 26),  # CVTRA05Y L17 PIC X(26) at 304
        text("FILLER", 330, 20),  # CVTRA05Y L18 PIC X(20) trailing pad
    ),
    (AlternateKeySpec("TRAN-PROC-TS", 304, 26),),
).validate_geometry()

# WHY (Assumptions): 50 bytes with a sixteen-byte COMPOSITE key at offset zero, corroborated
#   by app/jcl/DISCGRP.jcl lines 40 and 41, and by the 2550-byte
#   AWS.M2.CARDDEMO.DISCGRP.PS dividing into exactly 51 records -- one more than the other
#   fifty-record masters, because this dataset additionally carries the DEFAULT group row
#   the interest calculation falls back to. Line 5 of the copybook brackets the first three
#   fields under DIS-GROUP-KEY; those three are flattened here and the sixteen-byte
#   composite survives as the key length, which matches KEYS(16 0) exactly.
# WHY (Assumptions): the interest rate at line 9 is PIC S9(04)V99, so it is six bytes. It is
#   the only zoned field in the corpus with four integer digits, and its width follows from
#   the same rule as every other zoned field rather than from an exception -- which matters
#   because a reader who assumed every money field were twelve bytes would overrun this
#   50-byte record by six.
DISGROUP_LAYOUT: Final[RecordSpec] = RecordSpec(
    "DISGROUP",
    50,
    16,
    0,
    (
        text("DIS-ACCT-GROUP-ID", 0, 10),  # CVTRA02Y L6 PIC X(10), in the L5 key group
        text("DIS-TRAN-TYPE-CD", 10, 2),  # CVTRA02Y L7 PIC X(02), in the L5 key group
        uint("DIS-TRAN-CAT-CD", 12, 4),  # CVTRA02Y L8 PIC 9(04), in the L5 key group
        signed_zoned("DIS-INT-RATE", 16, 4, 2),  # CVTRA02Y L9 PIC S9(04)V99
        text("FILLER", 22, 28),  # CVTRA02Y L10 PIC X(28) trailing pad
    ),
).validate_geometry()

# WHY (Assumptions): 60 bytes with a SIX-byte composite key at offset zero, corroborated by
#   app/jcl/TRANCATG.jcl lines 40 and 41, and by the 1080-byte
#   AWS.M2.CARDDEMO.TRANCATG.PS dividing into exactly eighteen records. This is one of the
#   two records whose key group is named identically to another record's while being a
#   different width -- here TRAN-CAT-KEY is two plus four bytes; in the category-balance
#   record it is eleven plus two plus four -- and that pair is the reason field names
#   resolve per layout rather than globally.
# WHY (Assumptions): this is the second of the three base masters the parity oracle's codec
#   does not register, so its geometry rests on the copybook, the dataset definition and the
#   byte-size division alone, with no second Python implementation to be compared against.
TRANCAT_LAYOUT: Final[RecordSpec] = RecordSpec(
    "TRANCAT",
    60,
    6,
    0,
    (
        text("TRAN-TYPE-CD", 0, 2),  # CVTRA04Y L6 PIC X(02), in the L5 key group
        uint("TRAN-CAT-CD", 2, 4),  # CVTRA04Y L7 PIC 9(04), in the L5 key group
        text("TRAN-CAT-TYPE-DESC", 6, 50),  # CVTRA04Y L8 PIC X(50)
        text("FILLER", 56, 4),  # CVTRA04Y L9 PIC X(04) trailing pad
    ),
).validate_geometry()

# WHY (Assumptions): 60 bytes with a two-byte key at offset zero, corroborated by
#   app/jcl/TRANTYPE.jcl lines 40 and 41, and by the 420-byte
#   AWS.M2.CARDDEMO.TRANTYPE.PS dividing into exactly seven records. It is the smallest key
#   in the registry and it is the parent of the category reference above, which the target
#   expresses as a restricting foreign key so deleting a type that categories still
#   reference is refused rather than cascaded.
# WHY (Assumptions): this is the third of the three base masters the parity oracle's codec
#   does not register, so its geometry rests on the copybook, the dataset definition and the
#   byte-size division alone. No independently written Python implementation of this layout
#   exists to disagree with it, so a transcription error here would surface only as wrong
#   reference data, which is why the absent counterpart is recorded as data below rather
#   than left to be inferred from the registry.
TRANTYPE_LAYOUT: Final[RecordSpec] = RecordSpec(
    "TRANTYPE",
    60,
    2,
    0,
    (
        text("TRAN-TYPE", 0, 2),  # CVTRA03Y L5 PIC X(02)
        text("TRAN-TYPE-DESC", 2, 50),  # CVTRA03Y L6 PIC X(50)
        text("FILLER", 52, 8),  # CVTRA03Y L7 PIC X(08) trailing pad
    ),
).validate_geometry()

# WHY (Assumptions): 50 bytes with a SEVENTEEN-byte composite key at offset zero,
#   corroborated by app/jcl/TCATBALF.jcl lines 40 and 41, and by the 2500-byte
#   AWS.M2.CARDDEMO.TCATBALF.PS dividing into exactly fifty records. Line 5 of the copybook
#   brackets its first three fields under a group whose name collides with the category
#   reference record's at a different width; that collision is why lookup is scoped per
#   record.
# WHY (Assumptions): all four of this record's offsets are confirmed by an independent
#   source. app/jcl/PRTCATBL.jcl lines 47 to 50 declare the ONE-based DFSORT positions 1,
#   12, 14 and 18, which convert to the offsets 0, 11, 13 and 17 declared below, and its
#   TRAN-CAT-BAL,18,11,ZD confirms both that the balance is zoned and that eleven bytes is
#   the width of a PIC S9(09)V99.
TCATBAL_LAYOUT: Final[RecordSpec] = RecordSpec(
    "TCATBAL",
    50,
    17,
    0,
    (
        uint("TRANCAT-ACCT-ID", 0, 11),  # CVTRA01Y L6 PIC 9(11), in the L5 key group
        text("TRANCAT-TYPE-CD", 11, 2),  # CVTRA01Y L7 PIC X(02), in the L5 key group
        uint("TRANCAT-CD", 13, 4),  # CVTRA01Y L8 PIC 9(04), in the L5 key group
        signed_zoned("TRAN-CAT-BAL", 17, 9, 2),  # CVTRA01Y L9 PIC S9(09)V99
        text("FILLER", 28, 22),  # CVTRA01Y L10 PIC X(22) trailing pad
    ),
).validate_geometry()

# ---------------------------------------------------------------------------
# The three derived layouts.
# ---------------------------------------------------------------------------
# WHY (Assumptions): the statement view is NOT an alias of the posted transaction record
#   and must never be treated as one. It is 350 bytes like that record, but it leads with a
#   THIRTY-TWO byte composite key -- the card number followed by the transaction identifier
#   -- where the posted record leads with the identifier alone, so every field after the key
#   is displaced: its amount sits at offset 148 where the posted record's sits at 132. The
#   reference codec records the same finding in the same terms at
#   tests/helpers/record_codec.py line 1344, calling the two deliberately separate record
#   types with different geometry rather than aliases, and noting that an earlier aliasing
#   of the two mis-decoded every statement amount.
# WHY (Assumptions): app/cpy/COSTM01.CPY line 21 brackets the key as one group and line 24
#   brackets the remainder as another, so this copybook nests TWO group items rather than
#   one. Both are flattened and the thirty-two byte composite survives as the key length.
TRNX_LAYOUT: Final[RecordSpec] = RecordSpec(
    "TRNX",
    350,
    32,
    0,
    (
        sensitive_text("TRNX-CARD-NUM", 0, 16),  # COSTM01 L22 PIC X(16), in the L21 key group
        text("TRNX-ID", 16, 16),  # COSTM01 L23 PIC X(16), in the L21 key group
        text("TRNX-TYPE-CD", 32, 2),  # COSTM01 L25 PIC X(02)
        uint("TRNX-CAT-CD", 34, 4),  # COSTM01 L26 PIC 9(04)
        text("TRNX-SOURCE", 38, 10),  # COSTM01 L27 PIC X(10)
        text("TRNX-DESC", 48, 100),  # COSTM01 L28 PIC X(100)
        signed_zoned("TRNX-AMT", 148, 9, 2),  # COSTM01 L29 PIC S9(09)V99 at 148, not 132
        uint("TRNX-MERCHANT-ID", 159, 9),  # COSTM01 L30 PIC 9(09)
        text("TRNX-MERCHANT-NAME", 168, 50),  # COSTM01 L31 PIC X(50)
        text("TRNX-MERCHANT-CITY", 218, 50),  # COSTM01 L32 PIC X(50)
        text("TRNX-MERCHANT-ZIP", 268, 10),  # COSTM01 L33 PIC X(10)
        text("TRNX-ORIG-TS", 278, 26),  # COSTM01 L34 PIC X(26) deterministic
        normalized_timestamp("TRNX-PROC-TS", 304, 26),  # COSTM01 L35 PIC X(26) wall clock
        text("FILLER", 330, 20),  # COSTM01 L36 PIC X(20) trailing pad
    ),
).validate_geometry()

# WHY (Assumptions): 430 bytes, and the arithmetic is exact. The posting program writes a
#   rejected transaction as its verbatim 350-byte daily-transaction image followed by an
#   80-byte trailer, and app/cbl/CBTRN02C.cbl declares both halves: lines 82 to 84 declare
#   the file record as a PIC X(350) data area plus a PIC X(80) trailer, and lines 180 to 182
#   declare that trailer as a four-digit reason code followed by a 76-character description.
#   350 plus 4 plus 76 is 430.
# WHY (Assumptions): the four documented reason codes that populate the first trailer field
#   are 100, 101, 102 and 103, moved into it at lines 385, 397, 410 and 417 of that program.
#   They are recorded here because a field width of four digits is only meaningful alongside
#   the domain it carries.
REJECT_LAYOUT: Final[RecordSpec] = DALYTRAN_LAYOUT.extend_with(
    "REJECT",
    430,
    (
        uint("WS-VALIDATION-FAIL-REASON", 350, 4),  # CBTRN02C L181 PIC 9(04)
        text("WS-VALIDATION-FAIL-REASON-DESC", 354, 76),  # CBTRN02C L182 PIC X(76)
    ),
)

# WHY (Assumptions): 350 bytes with the posted record's geometry exactly, differing in one
#   flag only, and the reason is visible in four lines of the reference baseline. The
#   interest program moves its run-clock value into BOTH timestamps, whereas the posting
#   program copies the originating stamp from the source transaction and moves the run clock
#   only into the processing stamp. Under the base layout the interest record's
#   run-generated originating stamp would therefore make every interest comparison
#   non-deterministic. This layout flips that one flag and changes nothing else, which is
#   why it is derived rather than transcribed.
INTTRAN_LAYOUT: Final[RecordSpec] = TRAN_LAYOUT.with_field_flags(
    "INTTRAN", "TRAN-ORIG-TS", True, False
)


# ---------------------------------------------------------------------------
# The registry.
# ---------------------------------------------------------------------------
@dataclass(frozen=True, slots=True)
class _Registration:
    """Binding of one registered layout to the two facts known about it beyond geometry.

    Purpose
    -------
    Hold a layout together with its provenance and with whether the parity oracle's own
    codec registers a layout for the same record, so the registry answers all three
    questions from one lookup.

    Alternatives Considered: carrying these two facts as components of :class:`RecordSpec`
    itself, or holding them in two maps parallel to the layout map. Putting them on the
    record was rejected because they are not geometry -- a derived record and the base
    master it derives from can have identical geometry and different provenance -- and
    parallel maps were rejected because three maps keyed by the same names can disagree
    about which names exist, which is exactly the miscount :class:`Provenance` exists to
    prevent.

    Parameters
    ----------
    spec : RecordSpec
        The layout, already proven by a :meth:`RecordSpec.validate_geometry` call at its
        own declaration.
    provenance : Provenance
        Whether the layout is transcribed from a copybook or derived from one that is.
    has_oracle_round_trip : bool
        Whether the parity oracle's codec at ``tests/helpers/record_codec.py`` registers a
        layout for the same record.

    Returns
    -------
    _Registration
        A new immutable registration.

    Raises
    ------
    None
    """

    spec: RecordSpec
    provenance: Provenance
    has_oracle_round_trip: bool


def _build_registry() -> Mapping[str, _Registration]:
    """Assemble the registry and prove that every name it holds is unique.

    Purpose
    -------
    Build the one table every consumer resolves geometry through, in a deliberate order,
    and reject a duplicate name rather than overwriting one. Silently replacing an entry
    would leave the registry holding one of two layouts with no indication the other was
    ever declared, and since the two would differ in geometry every decode after the
    replacement would use the wrong one.

    Assumptions: insertion order is preserved and is meaningful. The eleven base masters
    are inserted first and the three derived records follow, which makes :func:`names`
    deterministic -- a failure message that lists the known names would otherwise list them
    differently on different runs and defeat comparison against a committed expectation.

    Assumptions: the provenance and the oracle-support flag are supplied per entry rather
    than inferred from the name. Inferring would encode the population in a condition
    somewhere, and the population is exactly the thing that has been miscounted before.

    Parameters
    ----------
    None

    Returns
    -------
    Mapping[str, _Registration]
        A read-only, insertion-ordered mapping from logical record name to registration.

    Raises
    ------
    LayoutError
        If two layouts are registered under one name.
    """
    entries: list[tuple[RecordSpec, Provenance, bool]] = [
        # WHY (Assumptions): these eight base masters are the ones the parity oracle's codec
        #   also registers, so a decode here can be checked against an independently written
        #   implementation of the same geometry. That is what the trailing True records, and
        #   it is why the flag is carried as data rather than left as a comment: a comment
        #   cannot be queried, so a verification pass would have no way to select the subset
        #   it can cross-check and would either skip the check entirely or run it against
        #   the three layouts that have no counterpart and fail for the wrong reason.
        (ACCOUNT_LAYOUT, Provenance.BASE_MASTER, True),
        (CARD_LAYOUT, Provenance.BASE_MASTER, True),
        (CUSTOMER_LAYOUT, Provenance.BASE_MASTER, True),
        (XREF_LAYOUT, Provenance.BASE_MASTER, True),
        (DALYTRAN_LAYOUT, Provenance.BASE_MASTER, True),
        (TRAN_LAYOUT, Provenance.BASE_MASTER, True),
        (DISGROUP_LAYOUT, Provenance.BASE_MASTER, True),
        (TCATBAL_LAYOUT, Provenance.BASE_MASTER, True),
        # WHY (Assumptions): these three base masters have NO counterpart in the parity
        #   oracle's codec, which is the whole reason this module distinguishes the two
        #   populations. The oracle registers eleven layouts and the migration has eleven
        #   base masters, but only eight names appear in both, so treating the two elevens
        #   as one population would drop exactly these three from the registry and nothing
        #   would report the loss. Their geometry rests on the copybook, the dataset
        #   definition and the byte-size division alone.
        (SECUSER_LAYOUT, Provenance.BASE_MASTER, False),
        (TRANCAT_LAYOUT, Provenance.BASE_MASTER, False),
        (TRANTYPE_LAYOUT, Provenance.BASE_MASTER, False),
        # WHY (Assumptions): these three are the oracle's other three entries and are NOT
        #   base masters. The statement view is a separate copybook over the same dataset
        #   with a wider leading key; the reject stream and the interest transaction are both
        #   built from a base master by this module. Labelling them derived is what lets a
        #   reader reconcile the count of eight with the count of eleven instead of having to
        #   choose between them.
        (TRNX_LAYOUT, Provenance.DERIVED, True),
        (REJECT_LAYOUT, Provenance.DERIVED, True),
        (INTTRAN_LAYOUT, Provenance.DERIVED, True),
    ]
    registry: dict[str, _Registration] = {}
    for spec, provenance, has_round_trip in entries:
        if spec.name in registry:
            raise LayoutError(
                f"a layout is already registered under the name {spec.name}; every registry name"
                " must be unique because a name is how every consumer resolves geometry"
            )
        registry[spec.name] = _Registration(spec, provenance, has_round_trip)
    return MappingProxyType(registry)


_REGISTRY: Final[Mapping[str, _Registration]] = _build_registry()

# WHY (Trade-offs): the layout mapping is published as a read-only proxy as well as through
#   the accessor below, because the reference codec publishes a plain LAYOUTS dict at
#   tests/helpers/record_codec.py line 1349 and a reader coming from that file looks for the
#   same name. It is a proxy rather than a dict so the parity in NAME does not import the
#   reference's mutability: a caller that popped an entry from a plain shared dict would
#   change the geometry every later decode uses, and a record decoded against a registry
#   missing an entry raises a name error far from the pop that caused it.
LAYOUTS: Final[Mapping[str, RecordSpec]] = MappingProxyType(
    {name: registration.spec for name, registration in _REGISTRY.items()}
)


def _registration(name: str) -> _Registration:
    """Resolve one registry entry by name, or report the name as unknown.

    Purpose
    -------
    Centralise the unknown-name failure so every accessor reports it identically and lists
    the names that do exist.

    Assumptions: an unknown name is refused rather than answered with a default, because a
    caller asking for a layout has no fallback available -- it is about to slice a record,
    and slicing it against guessed geometry produces plausible digits rather than an error.
    Listing the registered names is what turns a misspelling into a one-line diagnosis.

    Parameters
    ----------
    name : str
        The logical record name to resolve, matched exactly.

    Returns
    -------
    _Registration
        The registration held under that name.

    Raises
    ------
    LayoutError
        If no layout is registered under that name. The message lists every registered
        name.
    """
    found = _REGISTRY.get(name)
    if found is None:
        raise LayoutError(
            f"no record layout is registered under the name {name!r}; the registered names are"
            f" {tuple(_REGISTRY)}"
        )
    return found


def layout(name: str) -> RecordSpec:
    """Return the layout registered under the given logical record name.

    Purpose
    -------
    Give every reader, loader and verification pass one name-based way to reach the record
    geometry it needs without importing a layout constant directly.

    Parameters
    ----------
    name : str
        The logical record name to resolve, matched exactly.

    Returns
    -------
    RecordSpec
        The validated layout descriptor registered under that name.

    Raises
    ------
    LayoutError
        If no layout is registered under that name.
    """
    return _registration(name).spec


def reclen_of(name: str) -> int:
    """Return the record length of the named layout.

    Purpose
    -------
    Give a caller sizing a fixed-width dataset a name-based way to obtain its record length,
    which is the argument :func:`iter_fixed_length_records` and
    :func:`count_fixed_length_records` both take. The reference codec offers the same
    accessor under the same name at ``tests/helpers/record_codec.py`` line 1364.

    Parameters
    ----------
    name : str
        The logical record name to resolve, matched exactly.

    Returns
    -------
    int
        The declared record length in bytes.

    Raises
    ------
    LayoutError
        If no layout is registered under that name.
    """
    return _registration(name).spec.reclen


def keylen_of(name: str) -> int:
    """Return the primary key length of the named layout.

    Purpose
    -------
    Give a caller building an index a name-based way to obtain the key width without
    duplicating the geometry. The reference codec offers the same accessor under the same
    name immediately after its record-length accessor.

    Parameters
    ----------
    name : str
        The logical record name to resolve, matched exactly.

    Returns
    -------
    int
        The declared primary key length in bytes.

    Raises
    ------
    LayoutError
        If no layout is registered under that name.
    """
    return _registration(name).spec.key_length


def provenance_of(name: str) -> Provenance:
    """Return whether the named layout is transcribed from a copybook or derived from one.

    Purpose
    -------
    Let a caller distinguish the eleven base masters from the three derived records without
    hard-coding either list.

    Parameters
    ----------
    name : str
        The logical record name to resolve, matched exactly.

    Returns
    -------
    Provenance
        The provenance recorded for that layout.

    Raises
    ------
    LayoutError
        If no layout is registered under that name.
    """
    return _registration(name).provenance


def has_oracle_round_trip(name: str) -> bool:
    """Return whether the parity oracle's codec also registers a layout for this record.

    Purpose
    -------
    Tell a reviewer where an independent cross-check on a decode is available and where it
    is not.

    Assumptions: a False answer is a statement about the oracle and not about this layout's
    correctness. It means a decode of that record has no second, independently written
    implementation of the same geometry to compare against, so its geometry rests on the
    copybook, the dataset definition and the byte-size division alone. Three of the eleven
    base masters answer False, and knowing which three is the point.

    Parameters
    ----------
    name : str
        The logical record name to resolve, matched exactly.

    Returns
    -------
    bool
        True when the parity oracle's codec registers a layout for the same record.

    Raises
    ------
    LayoutError
        If no layout is registered under that name.
    """
    return _registration(name).has_oracle_round_trip


def names() -> tuple[str, ...]:
    """Return every registered layout name, base masters first and derived records after.

    Purpose
    -------
    Expose the registry's deterministic ordering so a caller can iterate every layout, and
    so a diagnostic listing the known names is stable across runs.

    Parameters
    ----------
    None

    Returns
    -------
    tuple[str, ...]
        The registered names in registration order.

    Raises
    ------
    None
    """
    return tuple(_REGISTRY)


def _names_with_provenance(provenance: Provenance) -> tuple[str, ...]:
    """Return the registered names carrying the given provenance, in registration order.

    Purpose
    -------
    Filter the one registry rather than maintaining two lists.

    Assumptions: filtering guarantees the two populations partition the registry exactly.
    Two maintained lists could omit a name from both, and the omission would be invisible
    because neither list would look short -- which is precisely the failure the base-master
    versus derived distinction exists to make impossible.

    Parameters
    ----------
    provenance : Provenance
        The provenance to select.

    Returns
    -------
    tuple[str, ...]
        The matching names in registration order.

    Raises
    ------
    None
    """
    return tuple(
        name for name, registration in _REGISTRY.items() if registration.provenance is provenance
    )


def base_master_names() -> tuple[str, ...]:
    """Return the names of the layouts transcribed directly from a copybook.

    Purpose
    -------
    Name the eleven records the copybook-is-normative rule speaks about, so a caller
    verifying the load does not have to restate the list.

    Parameters
    ----------
    None

    Returns
    -------
    tuple[str, ...]
        The base-master names in registration order.

    Raises
    ------
    None
    """
    return _names_with_provenance(Provenance.BASE_MASTER)


def derived_names() -> tuple[str, ...]:
    """Return the names of the layouts built from a base master rather than transcribed.

    Purpose
    -------
    Name the three derived records, so a caller can tell them from the eleven transcribed
    ones without inspecting how each was built.

    Parameters
    ----------
    None

    Returns
    -------
    tuple[str, ...]
        The derived names in registration order.

    Raises
    ------
    None
    """
    return _names_with_provenance(Provenance.DERIVED)


# WHY (Assumptions): the copybook each registered layout was transcribed from is recorded as
#   data rather than only in the comment beside the layout, because two consumers need it
#   for reasons a comment cannot serve: the traceability document is generated from the
#   registry, and a reader diagnosing an offset has to reach the copybook without grepping.
#   The three entries whose value is a program or a second copybook are the honest cases --
#   the reject trailer is declared in app/cbl/CBTRN02C.cbl and not in any copybook at all,
#   and the interest variant reuses the posted transaction copybook.
COPYBOOK_OF: Final[Mapping[str, str]] = MappingProxyType(
    {
        "ACCOUNT": "app/cpy/CVACT01Y.cpy",
        "CARD": "app/cpy/CVACT02Y.cpy",
        "CUSTOMER": "app/cpy/CVCUS01Y.cpy",
        "XREF": "app/cpy/CVACT03Y.cpy",
        "DALYTRAN": "app/cpy/CVTRA06Y.cpy",
        "TRAN": "app/cpy/CVTRA05Y.cpy",
        "DISGROUP": "app/cpy/CVTRA02Y.cpy",
        "TCATBAL": "app/cpy/CVTRA01Y.cpy",
        "SECUSER": "app/cpy/CSUSR01Y.cpy",
        "TRANCAT": "app/cpy/CVTRA04Y.cpy",
        "TRANTYPE": "app/cpy/CVTRA03Y.cpy",
        "TRNX": "app/cpy/COSTM01.CPY",
        "REJECT": "app/cbl/CBTRN02C.cbl",
        "INTTRAN": "app/cpy/CVTRA05Y.cpy",
    }
)

# WHY (Refactoring Rationale): three field names in the reference baseline are misspelled,
#   and this table is the lineage record for all three. The COBOL names stay misspelled in
#   every descriptor above and below, because a descriptor whose names did not match the
#   copybook would no longer be a transcription of it; only the TARGET column and attribute
#   names replace them, one layer further out. Each old name was wrong purely in its
#   spelling and never in its meaning, which is what makes the replacement safe and what
#   confines it to the naming layer. Recording the correspondence as data is what keeps the
#   lineage unambiguous: a reader who finds either spelling finds the other. Per the
#   copybook-is-normative rule NO OTHER field is renamed anywhere in the migration.
MISSPELLED_FIELDS: Final[Mapping[str, str]] = MappingProxyType(
    {
        # app/cpy/CVACT01Y.cpy line 11, and again at app/cpy/CVEXPORT.cpy line 54.
        "ACCT-EXPIRAION-DATE": "expiration_date",
        # app/cpy/CVACT02Y.cpy line 9, and again at app/cpy/CVEXPORT.cpy line 98.
        "CARD-EXPIRAION-DATE": "expiration_date",
        # app/app-authorization-ims-db2-mq/cpy/CIPAUDTY.cpy line 36. A second spelling with
        # an -RQ- infix, PA-RQ-MERCHANT-CATAGORY-CODE, appears in that tree's CCPAURQY.cpy
        # line 28; both carry the same misspelling and the same target name.
        "PA-MERCHANT-CATAGORY-CODE": "merchant_category_code",
    }
)


# ---------------------------------------------------------------------------
# The multi-record export layout, app/cpy/CVEXPORT.cpy.
# ---------------------------------------------------------------------------
# WHY (Assumptions): the export layouts are declared here but deliberately NOT registered
#   alongside the fourteen above, matching the Java parity anchor, which registers the same
#   fourteen and no more. The registry answers "which record is this dataset" for the eleven
#   base masters and the three records derived from them; the export record is a single
#   500-byte envelope carrying five DIFFERENT record shapes discriminated at run time by its
#   first byte, so a name-keyed registry entry would have to pick one of the five and would
#   be wrong for the other four. They are reached through EXPORT_HEADER_LAYOUT and
#   export_branch instead, which makes the two-step nature of the decode explicit.
# WHY (Assumptions): REDEFINES aliases storage and must NOT advance the offset, and this
#   copybook is where ignoring that rule is most expensive. Its five branch overlays each
#   redefine the same 460-byte payload area, so a naive width-summing parser would count
#   that payload five extra times and make the record 2800 bytes. The same hazard is
#   arithmetically self-proving in app/cpy/CVCRD01Y.cpy, which redefines three character
#   fields as numeric at its lines 36, 39 and 42; counted as new storage those three add
#   exactly 36 spurious bytes, being 11 plus 16 plus 9.
# WHY (Assumptions): the header's own EXPORT-TIMESTAMP-R at line 12 is a fourth REDEFINES,
#   overlaying the 26-byte timestamp as a 10-byte date, a 1-byte separator and a 15-byte
#   time. It is therefore absent from the field list below and its three subordinates are
#   recorded in this note instead, because emitting them would double-count 26 bytes and
#   the header would stop closing at 500.
# WHY (Assumptions): the header sums to exactly 500 only if the USAGE width rule holds --
#   1 plus 26 plus 4 plus 4 plus 5 plus 460. Treating EXPORT-SEQUENCE-NUM's PIC 9(9) COMP as
#   nine display bytes instead of four gives 505, which the dataset's own
#   RECORDSIZE(500 500) at app/jcl/CBEXPORT.jcl line 33 refuses. The width rule is therefore
#   load-bearing here rather than cosmetic, and it is independently proven from real bytes in
#   the note on binary_width.

# WHY (Assumptions): the discriminator occupies the first byte of every export record and is
#   named as a constant because both the header layout and every branch dispatch depend on
#   the same one byte. Were the two to state that width separately and disagree, the
#   dispatch would read a different span than the layout describes and would classify every
#   record by the wrong byte, which silently routes all 500 records to the wrong branch.
RECORD_TYPE_LENGTH: Final[int] = 1

# WHY (Assumptions): the payload area begins at zero-based offset 40 and runs for 460 bytes.
#   Both numbers are derived by summing the six header fields rather than read from the
#   copybook's prose banner, and every one of the five branch layouts below is declared in
#   coordinates RELATIVE to that offset -- a branch field's start is its offset within the
#   payload, not within the whole record -- so a caller slices the payload out with these two
#   constants and then decodes it against the branch. Mixing the two coordinate systems is
#   the one mistake available here, and it shifts every branch field by exactly 40 bytes.
EXPORT_PAYLOAD_OFFSET: Final[int] = 40
EXPORT_BRANCH_LENGTH: Final[int] = 460

# WHY (Assumptions): app/cbl/CBEXPORT.cbl line 68 declares RECORD KEY IS
#   EXPORT-SEQUENCE-NUM, and that field sits at zero-based offset 27 for four bytes, so 27
#   and 4 are the key geometry the RECORD LAYOUT supports and are what this module records.
#   The dataset definition disagrees: app/jcl/CBEXPORT.jcl line 32 declares KEYS(4 28),
#   spanning bytes 28 to 31, which straddles the last three bytes of the sequence number and
#   the first byte of EXPORT-BRANCH-ID. Both figures are recorded, the layout-supported one
#   as the key this module uses and the declared one beside it, because the disagreement is a
#   DOCUMENTED DIVERGENCE and not a transcription slip: it independently corroborates the
#   known baseline defect in which this program pair declares a record key that is not part
#   of its file record at all. The baseline declares KEYS(4 28); this module records the key
#   at offset 27 length 4 and exempts the export record from the key-alignment cross-check
#   the other ten datasets satisfy; the divergence is documented and nothing under app/ is
#   touched.
EXPORT_KEY_OFFSET: Final[int] = 27
EXPORT_KEY_LENGTH: Final[int] = 4
EXPORT_DECLARED_DATASET_KEY_OFFSET: Final[int] = 28
EXPORT_DECLARED_DATASET_KEY_LENGTH: Final[int] = 4

EXPORT_HEADER_LAYOUT: Final[RecordSpec] = RecordSpec(
    "EXPORT",
    500,
    EXPORT_KEY_LENGTH,
    EXPORT_KEY_OFFSET,
    (
        text("EXPORT-REC-TYPE", 0, RECORD_TYPE_LENGTH),  # CVEXPORT L10 PIC X(1)
        normalized_timestamp("EXPORT-TIMESTAMP", 1, 26),  # CVEXPORT L11 PIC X(26) run clock
        binary("EXPORT-SEQUENCE-NUM", 27, 9, 0, signed=False),  # CVEXPORT L16 PIC 9(9) COMP
        text("EXPORT-BRANCH-ID", 31, 4),  # CVEXPORT L17 PIC X(4)
        text("EXPORT-REGION-CODE", 35, 5),  # CVEXPORT L18 PIC X(5)
        text("EXPORT-RECORD-DATA", 40, EXPORT_BRANCH_LENGTH),  # CVEXPORT L19 PIC X(460)
    ),
).validate_geometry()

# WHY (Assumptions): a group-level OCCURS multiplies the whole group, and this copybook
#   carries two of them -- EXP-CUST-ADDR-LINES at lines 29 and 30 is three occurrences of a
#   50-byte line, and EXP-CUST-PHONE-NUMS at lines 34 and 35 is two occurrences of a 15-byte
#   number. Ignoring the two clauses would count 50 and 15 instead of 150 and 30 and
#   mis-align the branch by 115 bytes, so the customer branch would close at 345 rather than
#   460. The group name is what the copybook declares at that level, so it is the name
#   carried below, and the occurrence count and element width are published as constants so
#   a caller addressing one slot derives its offset from here rather than re-deriving it.
EXP_CUST_ADDR_LINES_OCCURS: Final[int] = 3
EXP_CUST_ADDR_LINES_ELEMENT_LENGTH: Final[int] = 50
EXP_CUST_PHONE_NUMS_OCCURS: Final[int] = 2
EXP_CUST_PHONE_NUMS_ELEMENT_LENGTH: Final[int] = 15

# WHY (Assumptions): each branch overlay has no dataset of its own, so it has no key of its
#   own either; the key declared on each one below is the leading identifier of the base
#   master the branch projects, expressed in payload-relative coordinates. That keeps the key
#   a real field interval inside the branch and makes the branch's identity addressable,
#   which is what a loader needs in order to join a decoded branch row back to the record it
#   came from. Note the customer branch's identifier is FOUR bytes and not nine, because the
#   export record stores it PIC 9(09) COMP where the customer master stores it as display.
EXPORT_CUSTOMER_LAYOUT: Final[RecordSpec] = RecordSpec(
    "EXPORT-CUSTOMER-DATA",
    EXPORT_BRANCH_LENGTH,
    4,
    0,
    (
        binary("EXP-CUST-ID", 0, 9, 0, signed=False),  # CVEXPORT L25 PIC 9(09) COMP
        sensitive_text("EXP-CUST-FIRST-NAME", 4, 25),  # CVEXPORT L26 PIC X(25)
        sensitive_text("EXP-CUST-MIDDLE-NAME", 29, 25),  # CVEXPORT L27 PIC X(25)
        sensitive_text("EXP-CUST-LAST-NAME", 54, 25),  # CVEXPORT L28 PIC X(25)
        sensitive_text("EXP-CUST-ADDR-LINES", 79, 150),  # CVEXPORT L29 OCCURS 3 x X(50)
        text("EXP-CUST-ADDR-STATE-CD", 229, 2),  # CVEXPORT L31 PIC X(02)
        text("EXP-CUST-ADDR-COUNTRY-CD", 231, 3),  # CVEXPORT L32 PIC X(03)
        text("EXP-CUST-ADDR-ZIP", 234, 10),  # CVEXPORT L33 PIC X(10)
        sensitive_text("EXP-CUST-PHONE-NUMS", 244, 30),  # CVEXPORT L34 OCCURS 2 x X(15)
        sensitive_uint("EXP-CUST-SSN", 274, 9),  # CVEXPORT L36 PIC 9(09) display
        sensitive_text("EXP-CUST-GOVT-ISSUED-ID", 283, 20),  # CVEXPORT L37 PIC X(20)
        sensitive_text("EXP-CUST-DOB-YYYY-MM-DD", 303, 10),  # CVEXPORT L38 PIC X(10)
        sensitive_text("EXP-CUST-EFT-ACCOUNT-ID", 313, 10),  # CVEXPORT L39 PIC X(10)
        text("EXP-CUST-PRI-CARD-HOLDER-IND", 323, 1),  # CVEXPORT L40 PIC X(01)
        packed("EXP-CUST-FICO-CREDIT-SCORE", 324, 3, 0, signed=False),  # L41 9(03) COMP-3
        text("FILLER", 326, 134),  # CVEXPORT L42 PIC X(134) trailing pad
    ),
).validate_geometry()

# WHY (Assumptions): this branch is the clearest proof in the corpus that a picture clause
#   does not determine a byte width, because it declares the SAME PIC S9(10)V99 at THREE
#   different widths -- seven bytes at line 50 under COMP-3, twelve at line 51 with no usage
#   clause, and eight at line 57 under COMP. Its 108 bytes of named fields plus the 352-byte
#   pad close at exactly 460 only when all three widths are honoured; at six bytes for each
#   of the two packed amounts the named fields would sum to 106 and the branch would close
#   at 458, which a 460-byte area does not admit.
EXPORT_ACCOUNT_LAYOUT: Final[RecordSpec] = RecordSpec(
    "EXPORT-ACCOUNT-DATA",
    EXPORT_BRANCH_LENGTH,
    11,
    0,
    (
        uint("EXP-ACCT-ID", 0, 11),  # CVEXPORT L48 PIC 9(11) display
        text("EXP-ACCT-ACTIVE-STATUS", 11, 1),  # CVEXPORT L49 PIC X(01)
        packed("EXP-ACCT-CURR-BAL", 12, 10, 2, signed=True),  # L50 S9(10)V99 COMP-3 = 7
        signed_zoned("EXP-ACCT-CREDIT-LIMIT", 19, 10, 2),  # L51 S9(10)V99 display = 12
        packed("EXP-ACCT-CASH-CREDIT-LIMIT", 31, 10, 2, signed=True),  # L52 COMP-3 = 7
        text("EXP-ACCT-OPEN-DATE", 38, 10),  # CVEXPORT L53 PIC X(10)
        text("EXP-ACCT-EXPIRAION-DATE", 48, 10),  # CVEXPORT L54 PIC X(10) misspelt
        text("EXP-ACCT-REISSUE-DATE", 58, 10),  # CVEXPORT L55 PIC X(10)
        signed_zoned("EXP-ACCT-CURR-CYC-CREDIT", 68, 10, 2),  # L56 S9(10)V99 display = 12
        binary("EXP-ACCT-CURR-CYC-DEBIT", 80, 10, 2, signed=True),  # L57 COMP = 8
        text("EXP-ACCT-ADDR-ZIP", 88, 10),  # CVEXPORT L58 PIC X(10)
        text("EXP-ACCT-GROUP-ID", 98, 10),  # CVEXPORT L59 PIC X(10)
        text("FILLER", 108, 352),  # CVEXPORT L60 PIC X(352) trailing pad
    ),
).validate_geometry()

# WHY (Assumptions): this branch closes at 460 only because its amount is six bytes under
#   COMP-3 -- eleven digit positions plus a sign nibble is twelve nibbles, which is six bytes
#   exactly -- and its merchant identifier is four bytes under COMP. The two widths differ
#   from the eleven and nine display bytes the posted transaction record uses for the same
#   two fields, which is the whole point of the overlay: the export record is a re-encoding
#   of the same data in a denser representation.
EXPORT_TRANSACTION_LAYOUT: Final[RecordSpec] = RecordSpec(
    "EXPORT-TRANSACTION-DATA",
    EXPORT_BRANCH_LENGTH,
    16,
    0,
    (
        text("EXP-TRAN-ID", 0, 16),  # CVEXPORT L66 PIC X(16)
        text("EXP-TRAN-TYPE-CD", 16, 2),  # CVEXPORT L67 PIC X(02)
        uint("EXP-TRAN-CAT-CD", 18, 4),  # CVEXPORT L68 PIC 9(04)
        text("EXP-TRAN-SOURCE", 22, 10),  # CVEXPORT L69 PIC X(10)
        text("EXP-TRAN-DESC", 32, 100),  # CVEXPORT L70 PIC X(100)
        packed("EXP-TRAN-AMT", 132, 9, 2, signed=True),  # L71 S9(09)V99 COMP-3 = 6
        binary("EXP-TRAN-MERCHANT-ID", 138, 9, 0, signed=False),  # L72 9(09) COMP = 4
        text("EXP-TRAN-MERCHANT-NAME", 142, 50),  # CVEXPORT L73 PIC X(50)
        text("EXP-TRAN-MERCHANT-CITY", 192, 50),  # CVEXPORT L74 PIC X(50)
        text("EXP-TRAN-MERCHANT-ZIP", 242, 10),  # CVEXPORT L75 PIC X(10)
        sensitive_text("EXP-TRAN-CARD-NUM", 252, 16),  # CVEXPORT L76 PIC X(16)
        text("EXP-TRAN-ORIG-TS", 268, 26),  # CVEXPORT L77 PIC X(26) deterministic
        normalized_timestamp("EXP-TRAN-PROC-TS", 294, 26),  # CVEXPORT L78 PIC X(26) clock
        text("FILLER", 320, 140),  # CVEXPORT L79 PIC X(140) trailing pad
    ),
).validate_geometry()

# WHY (Assumptions): this branch closes at 460 only because its eleven-digit account
#   identifier is EIGHT bytes under COMP, which is the highest of the three binary tiers.
#   Sixteen plus nine plus eight plus 427 is 460; at four bytes it would close at 456. This
#   is the tier that would be easiest to get wrong, because the neighbouring nine-digit
#   identifier on the line above it is four.
EXPORT_CARD_XREF_LAYOUT: Final[RecordSpec] = RecordSpec(
    "EXPORT-CARD-XREF-DATA",
    EXPORT_BRANCH_LENGTH,
    16,
    0,
    (
        sensitive_text("EXP-XREF-CARD-NUM", 0, 16),  # CVEXPORT L85 PIC X(16)
        uint("EXP-XREF-CUST-ID", 16, 9),  # CVEXPORT L86 PIC 9(09) display
        binary("EXP-XREF-ACCT-ID", 25, 11, 0, signed=False),  # L87 9(11) COMP = 8
        text("FILLER", 33, 427),  # CVEXPORT L88 PIC X(427) trailing pad
    ),
).validate_geometry()

# WHY (Assumptions): this branch exercises two binary tiers in adjacent fields -- eight bytes
#   for the eleven-digit account identifier at line 95 and two bytes for the three-digit
#   verification value at line 96 -- and closes at 460 only with both. It is also where the
#   second baseline misspelling recurs, at line 98.
EXPORT_CARD_LAYOUT: Final[RecordSpec] = RecordSpec(
    "EXPORT-CARD-DATA",
    EXPORT_BRANCH_LENGTH,
    16,
    0,
    (
        sensitive_text("EXP-CARD-NUM", 0, 16),  # CVEXPORT L94 PIC X(16)
        binary("EXP-CARD-ACCT-ID", 16, 11, 0, signed=False),  # L95 9(11) COMP = 8
        binary("EXP-CARD-CVV-CD", 24, 3, 0, signed=False),  # L96 9(03) COMP = 2
        sensitive_text("EXP-CARD-EMBOSSED-NAME", 26, 50),  # CVEXPORT L97 PIC X(50)
        text("EXP-CARD-EXPIRAION-DATE", 76, 10),  # CVEXPORT L98 PIC X(10) misspelt
        text("EXP-CARD-ACTIVE-STATUS", 86, 1),  # CVEXPORT L99 PIC X(01)
        text("FILLER", 87, 373),  # CVEXPORT L100 PIC X(373) trailing pad
    ),
).validate_geometry()

# WHY (Assumptions): app/cpy/CVEXPORT.cpy declares NO 88-level condition names at all, so the
#   five discriminator values are not in the copybook and cannot be transcribed from it. They
#   are in the program: app/cbl/CBEXPORT.cbl moves 'C' at line 274, 'A' at line 343, 'X' at
#   line 407, 'T' at line 462 and 'D' at line 527 into EXPORT-REC-TYPE, each immediately
#   before writing the corresponding branch. The mapping below is taken from those five lines
#   and from nowhere else, and it is corroborated by the real 500-record dataset, whose first
#   bytes decode to exactly fifty 'C', fifty 'A', fifty 'X', three hundred 'T' and fifty 'D'
#   -- which matches the seed populations of the customer, account, cross-reference, daily
#   transaction and card datasets respectively.
EXPORT_RECORD_TYPES: Final[Mapping[str, RecordSpec]] = MappingProxyType(
    {
        "C": EXPORT_CUSTOMER_LAYOUT,
        "A": EXPORT_ACCOUNT_LAYOUT,
        "X": EXPORT_CARD_XREF_LAYOUT,
        "T": EXPORT_TRANSACTION_LAYOUT,
        "D": EXPORT_CARD_LAYOUT,
    }
)


def export_branch(record_type: str) -> RecordSpec:
    """Return the export payload layout selected by a record-type discriminator.

    Purpose
    -------
    Resolve the second half of the export record's two-step decode: the caller slices the
    460-byte payload out of the envelope using :data:`EXPORT_PAYLOAD_OFFSET` and
    :data:`EXPORT_BRANCH_LENGTH`, reads the one-byte discriminator at offset zero, and asks
    this function which of the five overlay layouts describes what it just sliced.

    Assumptions: an unrecognised discriminator is refused rather than defaulted to any
    branch. All five branches are 460 bytes, so a wrong branch decodes without raising and
    returns a full set of well-formed but meaningless values -- a card number read out of a
    customer's address, for instance. Refusing is the only way that failure becomes visible.

    Parameters
    ----------
    record_type : str
        The one-character discriminator taken from ``EXPORT-REC-TYPE``, being one of the
        five keys of :data:`EXPORT_RECORD_TYPES`.

    Returns
    -------
    RecordSpec
        The 460-byte payload layout for that record type, in payload-relative coordinates.

    Raises
    ------
    LayoutError
        If ``record_type`` is not exactly one of the five declared discriminators. The
        message lists the five so a caller can see what it should have been.
    """
    branch = EXPORT_RECORD_TYPES.get(record_type)
    if branch is None:
        raise LayoutError(
            f"export record type {record_type!r} is not one of the five the export program"
            f" writes; the declared types are {tuple(EXPORT_RECORD_TYPES)}"
        )
    return branch


# ---------------------------------------------------------------------------
# The two authorization IMS segments.
# ---------------------------------------------------------------------------
# WHY (Assumptions): both authorization copybooks are FRAGMENTS. Each begins at level 05 with
#   no 01 group of its own, because a program copies the fragment into a segment-io area it
#   declares itself, so there is no root name to transcribe and the two names below are
#   supplied EXTERNALLY. That is possible because RecordSpec always takes its name from the
#   caller and never parses one out of a copybook -- a design point worth stating, since a
#   layout API that assumed an 01 existed could not describe either of these segments, nor
#   the two message payload copybooks in the same tree that share the property.
# WHY (Assumptions): both copybooks write PIC with TWO spaces before the picture string, for
#   instance PIC  9(09) at CIPAUSMY.cpy line 20, and both indent their level numbers
#   differently from the base masters. Any parser over these files must therefore tokenise on
#   whitespace RUNS and never on fixed columns; splitting on a single space would leave an
#   empty token and the picture string would be read as the field name.
# WHY (Assumptions): 88-level condition names carry NO storage and must be skipped when
#   summing. CIPAUSMY.cpy declares ZERO of them; CIPAUDTY.cpy declares exactly seven -- one
#   for the response code at line 31, four for the match status at lines 46 to 49 and two for
#   the fraud flag at lines 51 and 52. Counting any of the seven as a field would add bytes
#   the segment does not have, and the 200-byte sum would not close.

# WHY (Assumptions): PA-ACCOUNT-STATUS at CIPAUSMY.cpy line 22 is an ELEMENTARY-level OCCURS,
#   declared on the same line as its own picture clause -- PIC  X(02) OCCURS 5 TIMES -- which
#   is a different shape from the two group-level OCCURS clauses in the export copybook.
#   Reading only the picture clause and ignoring the OCCURS gives 2 bytes instead of 10 and
#   mis-aligns the remaining eight bytes of the segment, so the 100-byte sum would not close.
#   The table is carried below as ONE ten-byte interval because the nine-component field
#   descriptor has no occurrence count to record and gaining one would break cross-language
#   parity; the occurrence count and element width are published here instead, so a caller
#   addressing slot n derives its offset as the field start plus (n - 1) times the element
#   length. The target projects the table into five discrete two-byte columns.
PA_ACCOUNT_STATUS_OCCURS: Final[int] = 5
PA_ACCOUNT_STATUS_ELEMENT_LENGTH: Final[int] = 2

# WHY (Assumptions): 100 bytes, and the sum closes only under the packed width rule: an
#   eleven-digit key at six bytes, six eleven-digit amounts at six bytes each, two four-digit
#   binary counters at two bytes each, a nine-byte display identifier, two single characters
#   of status, the ten-byte status table and a 34-byte pad. Seven of its thirteen fields are
#   packed, which makes this segment and its detail sibling the only place packed decimal
#   reaches persisted target data.
# WHY (Assumptions): this segment's key is PA-ACCT-ID, six packed bytes at offset zero, and it
#   decomposes downstream into a single account-identifier column. The target collapses these
#   IMS segments and the neighbouring Db2 tables into one schema, which is what eliminates the
#   baseline's two-phase commit rather than emulating it.
PENDING_AUTH_SUMMARY_LAYOUT: Final[RecordSpec] = RecordSpec(
    "PENDING-AUTH-SUMMARY",
    100,
    6,
    0,
    (
        packed("PA-ACCT-ID", 0, 11, 0, signed=True),  # CIPAUSMY L19 S9(11) COMP-3 = 6
        uint("PA-CUST-ID", 6, 9),  # CIPAUSMY L20 PIC  9(09) display
        text("PA-AUTH-STATUS", 15, 1),  # CIPAUSMY L21 PIC  X(01)
        text("PA-ACCOUNT-STATUS", 16, 10),  # CIPAUSMY L22 X(02) OCCURS 5 TIMES = 10
        packed("PA-CREDIT-LIMIT", 26, 9, 2, signed=True),  # CIPAUSMY L23 COMP-3 = 6
        packed("PA-CASH-LIMIT", 32, 9, 2, signed=True),  # CIPAUSMY L24 COMP-3 = 6
        packed("PA-CREDIT-BALANCE", 38, 9, 2, signed=True),  # CIPAUSMY L25 COMP-3 = 6
        packed("PA-CASH-BALANCE", 44, 9, 2, signed=True),  # CIPAUSMY L26 COMP-3 = 6
        binary("PA-APPROVED-AUTH-CNT", 50, 4, 0, signed=True),  # CIPAUSMY L27 COMP = 2
        binary("PA-DECLINED-AUTH-CNT", 52, 4, 0, signed=True),  # CIPAUSMY L28 COMP = 2
        packed("PA-APPROVED-AUTH-AMT", 54, 9, 2, signed=True),  # CIPAUSMY L29 COMP-3 = 6
        packed("PA-DECLINED-AUTH-AMT", 60, 9, 2, signed=True),  # CIPAUSMY L30 COMP-3 = 6
        text("FILLER", 66, 34),  # CIPAUSMY L31 PIC X(34) trailing pad
    ),
).validate_geometry()

# WHY (Assumptions): 200 bytes, and this segment is the proof by contradiction for the
#   packed width rule. It carries two PIC S9(10)V99 COMP-3 amounts, at lines 34 and 35, and
#   its 27 named elementary fields plus its 17-byte pad sum to exactly 200 when each of those
#   is seven bytes. At six bytes each the sum is 198, which is not a length this segment can
#   have -- so six is arithmetically impossible here and not merely unlikely.
# WHY (Assumptions): PA-AUTHORIZATION-KEY at line 19 is an eight-byte GROUP of two packed
#   fields, a three-byte five-digit date and a five-byte nine-digit time. The group is
#   flattened into its two leaves below and the eight-byte composite survives as the key
#   length, which is what the target decomposes into the two integer columns forming its
#   composite primary key.
# WHY (Refactoring Rationale): PA-MERCHANT-CATAGORY-CODE at line 36 is the third of the three
#   baseline misspellings and is transcribed with it; its target name is
#   merchant_category_code, recorded in MISSPELLED_FIELDS above.
PENDING_AUTH_DETAIL_LAYOUT: Final[RecordSpec] = RecordSpec(
    "PENDING-AUTH-DETAIL",
    200,
    8,
    0,
    (
        packed("PA-AUTH-DATE-9C", 0, 5, 0, signed=True),  # CIPAUDTY L20 S9(05) COMP-3 = 3
        packed("PA-AUTH-TIME-9C", 3, 9, 0, signed=True),  # CIPAUDTY L21 S9(09) COMP-3 = 5
        text("PA-AUTH-ORIG-DATE", 8, 6),  # CIPAUDTY L22 PIC  X(06)
        text("PA-AUTH-ORIG-TIME", 14, 6),  # CIPAUDTY L23 PIC  X(06)
        sensitive_text("PA-CARD-NUM", 20, 16),  # CIPAUDTY L24 PIC  X(16)
        text("PA-AUTH-TYPE", 36, 4),  # CIPAUDTY L25 PIC  X(04)
        text("PA-CARD-EXPIRY-DATE", 40, 4),  # CIPAUDTY L26 PIC  X(04)
        text("PA-MESSAGE-TYPE", 44, 6),  # CIPAUDTY L27 PIC  X(06)
        text("PA-MESSAGE-SOURCE", 50, 6),  # CIPAUDTY L28 PIC  X(06)
        text("PA-AUTH-ID-CODE", 56, 6),  # CIPAUDTY L29 PIC  X(06)
        text("PA-AUTH-RESP-CODE", 62, 2),  # CIPAUDTY L30 PIC  X(02), 88 at L31
        text("PA-AUTH-RESP-REASON", 64, 4),  # CIPAUDTY L32 PIC  X(04)
        uint("PA-PROCESSING-CODE", 68, 6),  # CIPAUDTY L33 PIC  9(06) display
        packed("PA-TRANSACTION-AMT", 74, 10, 2, signed=True),  # L34 S9(10)V99 COMP-3 = 7
        packed("PA-APPROVED-AMT", 81, 10, 2, signed=True),  # L35 S9(10)V99 COMP-3 = 7
        text("PA-MERCHANT-CATAGORY-CODE", 88, 4),  # CIPAUDTY L36 PIC X(04) misspelt
        text("PA-ACQR-COUNTRY-CODE", 92, 3),  # CIPAUDTY L37 PIC  X(03)
        uint("PA-POS-ENTRY-MODE", 95, 2),  # CIPAUDTY L38 PIC  9(02) display
        text("PA-MERCHANT-ID", 97, 15),  # CIPAUDTY L39 PIC  X(15)
        text("PA-MERCHANT-NAME", 112, 22),  # CIPAUDTY L40 PIC  X(22)
        text("PA-MERCHANT-CITY", 134, 13),  # CIPAUDTY L41 PIC  X(13)
        text("PA-MERCHANT-STATE", 147, 2),  # CIPAUDTY L42 PIC  X(02)
        text("PA-MERCHANT-ZIP", 149, 9),  # CIPAUDTY L43 PIC  X(09)
        text("PA-TRANSACTION-ID", 158, 15),  # CIPAUDTY L44 PIC  X(15)
        text("PA-MATCH-STATUS", 173, 1),  # CIPAUDTY L45 X(01), 88s at L46-L49
        text("PA-AUTH-FRAUD", 174, 1),  # CIPAUDTY L50 X(01), 88s at L51-L52
        text("PA-FRAUD-RPT-DATE", 175, 8),  # CIPAUDTY L53 PIC  X(08)
        text("FILLER", 183, 17),  # CIPAUDTY L54 PIC  X(17) trailing pad
    ),
).validate_geometry()


# ---------------------------------------------------------------------------
# Sensitive-field diagnostics.
# ---------------------------------------------------------------------------
# WHY (Trade-offs): the five names below reveal their last four characters where every other
#   sensitive field is replaced wholesale, and the compromise is deliberate. A primary account
#   number or a national identifier is routinely quoted by its last four digits in operational
#   practice, so revealing exactly four keeps a diagnostic actionable -- a reader can tell
#   WHICH card a mismatch concerns -- while disclosing far too little to reconstruct the value.
#   Every other sensitive field reveals nothing at all, because a name or a date of birth has
#   no equivalent partial form that is useful without being identifying. The same five names
#   carry the same concession in the reference codec at tests/helpers/record_codec.py line
#   1589.
_LAST4_REVEAL: Final[frozenset[str]] = frozenset(
    {
        "CARD-NUM",
        "XREF-CARD-NUM",
        "TRAN-CARD-NUM",
        "DALYTRAN-CARD-NUM",
        "CUST-SSN",
    }
)

# WHY (Assumptions): eight hexadecimal characters of a digest is enough for a diagnostic to
#   tell two differing values apart while being far too little to invert, and the tag is
#   wrapped in a fixed literal so a masked rendering is unmistakably a mask rather than
#   something that could be read back as data. A shorter tag would let two different values
#   render identically and a diff would then report no difference where one exists, and an
#   unwrapped tag would be indistinguishable from field content, so a masked rendering could
#   be mistaken for a real value and loaded.
_REDACTION_DIGEST_CHARS: Final[int] = 8


def mask_field(field: FieldSpec, chunk: str) -> str:
    """Return a redacted, same-width rendering of one field's characters.

    Purpose
    -------
    Produce a privacy-safe stand-in for a single field so a diagnostic can show a record's
    shape and WHERE two records differ without emitting a complete cardholder identity or
    payment number. A field not marked sensitive is returned unchanged.

    Trade-offs: the mask is DETERMINISTIC -- equal input characters always yield equal masked
    characters -- because that is what lets a masked comparison still reveal which field
    changed and whether two masked records are equal. A random mask would destroy that, and a
    plaintext value would over-disclose. The accepted cost is that an attacker holding a
    candidate value could confirm it by hashing, which is acceptable for a diagnostic
    rendering that never leaves an operator's log.

    Trade-offs: the returned rendering is exactly ``field.length`` characters, so a masked
    record stays byte-aligned and a reader can still count offsets across it. An over-long
    redaction tag is therefore truncated and a short one is space-padded.

    Assumptions: this operates on ``str`` rather than ``bytes`` because it is the counterpart
    of the text-line ingest path, where a record is already characters. For the byte path --
    where a record has not been through a character decoder at all -- the sensitive-safe
    rendering is :meth:`FieldSpec.describe`, which needs no content whatsoever.

    Parameters
    ----------
    field : FieldSpec
        The field descriptor. Its ``sensitive`` flag decides whether masking applies, its
        ``name`` selects the last-four-reveal concession, and its ``length`` fixes the
        returned width.
    chunk : str
        The exact field characters sliced from the record, which must be ``field.length``
        characters long.

    Returns
    -------
    str
        Exactly ``field.length`` characters: ``chunk`` verbatim when the field is not
        sensitive, otherwise a redaction revealing at most the trailing four characters.

    Raises
    ------
    LayoutError
        If ``chunk`` is not ``field.length`` characters. The message names the field through
        :meth:`FieldSpec.describe` and reports the observed length, never the content, so it
        stays safe for a sensitive field.
    """
    if len(chunk) != field.length:
        raise LayoutError(
            f"field {field.describe()} was given {len(chunk)} characters to mask but declares"
            f" {field.length}"
        )
    if not field.sensitive:
        return chunk
    stripped = chunk.rstrip()
    if field.name in _LAST4_REVEAL and len(stripped) >= 4:
        masked = "*" * (len(stripped) - 4) + stripped[-4:]
    else:
        digest = hashlib.sha256(chunk.encode("utf-8", "replace")).hexdigest()
        masked = f"<redacted:{digest[:_REDACTION_DIGEST_CHARS]}>"
    return masked[: field.length].ljust(field.length)


def mask_record(raw: str, layout: RecordSpec) -> str:
    """Return a full-width copy of a record with every sensitive field redacted.

    Purpose
    -------
    Render a whole record for a diagnostic, replacing each sensitive field in place through
    :func:`mask_field` and leaving every other byte exactly as it was, so a reader can diff
    two masked records and see which field differs.

    Trade-offs: the argument order puts the data first and the layout second, which is the
    opposite of :func:`mask_field`. The asymmetry is inherited deliberately rather than
    tidied, because both signatures then match the reference codec's at
    ``tests/helpers/record_codec.py`` lines 1594 and 1649, and a reader moving between the two
    files does not have to remember which of them reordered its arguments.

    Parameters
    ----------
    raw : str
        The record characters, which must be exactly ``layout.reclen`` characters long.
    layout : RecordSpec
        The layout describing the record, whose sensitive fields select what is redacted.

    Returns
    -------
    str
        A copy of ``raw`` of the same length with every sensitive field redacted.

    Raises
    ------
    RecordLengthError
        If ``raw`` is not exactly ``layout.reclen`` characters.
    LayoutError
        If a field slice comes out the wrong width, which would mean the layout does not tile
        the record; :meth:`RecordSpec.validate_geometry` makes that unreachable for every
        layout this module declares.
    """
    if len(raw) != layout.reclen:
        raise RecordLengthError(
            f"record {layout.name} must be exactly {layout.reclen} characters to mask, but was"
            f" {len(raw)}"
        )
    characters = list(raw)
    for field in layout.fields:
        if field.sensitive:
            characters[field.start : field.end] = mask_field(field, raw[field.start : field.end])
    return "".join(characters)


# ---------------------------------------------------------------------------
# Record boundaries: two modes, and they are structurally unmixable.
# ---------------------------------------------------------------------------
# WHY (Assumptions): the reference extracts arrive in two genuinely different physical shapes,
#   which was MEASURED rather than assumed, so one boundary rule cannot serve both. The
#   thirteen datasets under app/data/EBCDIC are pure fixed-length byte images with no record
#   terminator at all: every one of them divides by its record length with remainder zero, and
#   AWS.M2.CARDDEMO.EXPORT.DATA.PS additionally contains five 0x0A bytes INSIDE its binary
#   sequence numbers, so splitting it on a newline yields six pieces where it holds five
#   hundred records. The nine seeds under app/data/ASCII are newline-delimited text, and three
#   of them are not uniform even in that: tcatbal.txt carries 49 carriage returns across 50
#   lines, trancatg.txt carries 18 across 18 and trantype.txt carries 6 across 7, so within one
#   file some lines end CR-LF and others end LF. One of them is not even full width --
#   cardxref.txt has 50 lines of 36 characters against a declared record length of 50, because
#   its trailing FILLER X(14) is simply absent from the text form.
# WHY (Trade-offs): the two modes are therefore SEPARATE FUNCTIONS taking DIFFERENT TYPES --
#   the byte mode takes bytes and the text mode takes str -- rather than one function with a
#   mode flag. That is what makes the text tolerance structurally unreachable from the byte
#   path: this module performs no character decoding at all, so an EBCDIC byte image cannot be
#   passed to the text function without an explicit decode step the caller would have to write
#   itself, and no default value of any parameter can lead it there by accident. A flag with a
#   default would eventually be left at its default over an EBCDIC dataset, and the padding
#   arm would then silently accept a truncated final record.
# WHY (Assumptions): the padding tolerance in the text mode is a DELIBERATE, NAMED DIVERGENCE
#   from the reference codec, scoped to ASCII seed ingest alone. The reference's
#   _validated_record at tests/helpers/record_codec.py lines 513 to 596 REJECTS any row that
#   is not exactly reclen characters after one trailing newline is stripped, on the stated
#   ground that a financial record of the wrong width is corrupt input and silently repairing
#   it could post a misaligned money value. That reasoning is correct and is not weakened
#   here: the byte mode rejects outright, and the text mode still refuses a row that is too
#   LONG, because an over-long row means the offsets have moved. It pads a SHORT row only, and
#   only because the measured evidence above shows the committed seeds require it -- 36-byte
#   cardxref.txt lines and mixed line endings in three other files -- and because padding on
#   the right with spaces cannot move any field: every field that exists still starts where it
#   started, and the bytes added are exactly the trailing FILLER the text form omitted. The
#   divergence is therefore in what is tolerated at ONE ingest boundary, never in record
#   validation generally and never on the EBCDIC path.

# WHY (Assumptions): a text line terminates with a newline, optionally preceded by a carriage
#   return, and at most ONE of each is removed. Stripping greedily with rstrip would eat
#   trailing spaces too, and trailing spaces are DATA in a fixed-width record -- they are how
#   a short value fills its field -- so a greedy strip would shorten a legitimately
#   space-padded final field and then pad it back with a different count.
_ASCII_LINE_SEPARATOR: Final[str] = "\n"
_ASCII_CARRIAGE_RETURN: Final[str] = "\r"


def count_fixed_length_records(size_bytes: int, reclen: int) -> int:
    """Return how many whole fixed-length records a byte count contains.

    Purpose
    -------
    Derive a record count from a dataset size, and in doing so assert the property that makes
    the derivation meaningful: the size must be an exact multiple of the record length. This is
    the correctness check for every fixed-length dataset in the corpus -- the DIVISION is what
    proves a dataset is well formed, never the presence or absence of a newline. All thirteen
    EBCDIC datasets satisfy it, which is why the record counts they yield can be trusted
    without reading a byte of their content.

    Parameters
    ----------
    size_bytes : int
        The total size of the dataset in bytes; zero or more.
    reclen : int
        The declared record length in bytes; one or more, normally obtained from
        :func:`reclen_of`.

    Returns
    -------
    int
        The number of whole records, being ``size_bytes`` divided by ``reclen``. Zero for an
        empty dataset, which is the only legitimate way for a dataset to hold no records.

    Raises
    ------
    LayoutError
        If ``reclen`` is below one or ``size_bytes`` is negative, both of which are caller
        errors rather than data faults.
    RecordLengthError
        If ``size_bytes`` is not an exact multiple of ``reclen``. The message reports the
        remainder, because the remainder is the number of bytes by which the dataset is
        malformed and is the first thing an operator needs.
    """
    if reclen < 1:
        raise LayoutError(f"a record length must be at least one byte, but reclen={reclen}")
    if size_bytes < 0:
        raise LayoutError(f"a dataset size must not be negative, but size_bytes={size_bytes}")
    remainder = size_bytes % reclen
    if remainder:
        raise RecordLengthError(
            f"a dataset of {size_bytes} bytes does not divide into whole records of {reclen}"
            f" bytes; {remainder} trailing bytes remain, so the dataset is truncated or the"
            " record length is wrong"
        )
    return size_bytes // reclen


def iter_fixed_length_records(data: bytes, reclen: int) -> Iterator[bytes]:
    """Iterate a fixed-length byte image as whole records, with no newline semantics at all.

    Purpose
    -------
    Slice a dataset strictly by record length. This is the mode every EBCDIC dataset and every
    packed or binary record must be read through, because their content is arbitrary bytes: a
    0x0A inside a binary sequence number, a packed nibble pair or a low-value pad byte is data
    and not a boundary.

    Assumptions: no line terminator is looked for, honoured or stripped, which is the entire
    point. ``AWS.M2.CARDDEMO.EXPORT.DATA.PS`` is the concrete case -- it holds exactly five
    hundred 500-byte records and five 0x0A bytes, so a newline-aware reader would report six
    pieces of wildly differing lengths, and each of the five would be a record split through
    the middle of a field.

    Parameters
    ----------
    data : bytes
        The whole dataset image, whose length must be an exact multiple of ``reclen``.
    reclen : int
        The declared record length in bytes; one or more, normally obtained from
        :func:`reclen_of`.

    Returns
    -------
    Iterator[bytes]
        Each whole record in order, every one exactly ``reclen`` bytes long.

    Raises
    ------
    LayoutError
        If ``data`` is not a ``bytes`` or ``bytearray``, or if ``reclen`` is below one. A
        ``str`` is refused explicitly rather than encoded, because guessing a character
        encoding for an EBCDIC image is exactly the mistake this module must not make.
    RecordLengthError
        If the length of ``data`` is not an exact multiple of ``reclen``.
    """
    if not isinstance(data, (bytes, bytearray)):
        raise LayoutError(
            "fixed-length record iteration requires a byte image, but was given a"
            f" {type(data).__name__}; character data belongs to iter_ascii_text_records, and no"
            " encoding is guessed here"
        )
    total = count_fixed_length_records(len(data), reclen)
    for index in range(total):
        start = index * reclen
        yield bytes(data[start : start + reclen])


def iter_ascii_text_records(text: str, reclen: int) -> Iterator[str]:
    """Iterate newline-delimited ASCII seed text as whole records, padding a short line.

    Purpose
    -------
    Read the nine committed seed files under ``app/data/ASCII``, which are line-oriented text
    rather than fixed-length images. Each line is separated on the newline, at most one
    trailing carriage return and newline are removed, and the result is right-padded with
    spaces to the declared record length.

    Assumptions: this padding tolerance is the named divergence recorded in full in the
    comment block above this function, and its scope is exactly this function. A line LONGER
    than the declared length is still refused, because an over-long line means the field
    offsets have moved and no amount of trimming could put them back. Only a SHORT line is
    padded, and padding on the right with spaces cannot move a field that exists: the bytes
    added are precisely the trailing pad the text form omitted, which is why ``cardxref.txt``
    can be read at all -- its 50 lines are 36 characters each against a declared 50, the
    missing 14 being exactly its trailing ``FILLER PIC X(14)``.

    Assumptions: a single trailing empty element is dropped, because a file whose final record
    ends with a newline produces one. Dropping more than one would hide a genuinely blank line,
    which is corrupt input rather than formatting -- a blank line pads to a record of spaces
    and would load as a row of empty keys.

    Parameters
    ----------
    text : str
        The whole seed file decoded as text.
    reclen : int
        The declared record length in characters; one or more, normally obtained from
        :func:`reclen_of`.

    Returns
    -------
    Iterator[str]
        Each record in order, every one exactly ``reclen`` characters long.

    Raises
    ------
    LayoutError
        If ``text`` is not a ``str``, or if ``reclen`` is below one.
    RecordLengthError
        If any line is longer than ``reclen`` characters once one trailing carriage return and
        newline are removed. The message reports the line number, its observed length and the
        declared length.
    """
    if not isinstance(text, str):
        raise LayoutError(
            "ASCII text record iteration requires character data, but was given a"
            f" {type(text).__name__}; a byte image belongs to iter_fixed_length_records, whose"
            " strict division this function deliberately relaxes"
        )
    if reclen < 1:
        raise LayoutError(f"a record length must be at least one character, but reclen={reclen}")

    lines = text.split(_ASCII_LINE_SEPARATOR)
    if lines and lines[-1] == "":
        lines = lines[:-1]
    for number, line in enumerate(lines, start=1):
        stripped = line[:-1] if line.endswith(_ASCII_CARRIAGE_RETURN) else line
        if len(stripped) > reclen:
            raise RecordLengthError(
                f"ASCII seed line {number} is {len(stripped)} characters, which exceeds the"
                f" declared record length of {reclen}; an over-long line means the field offsets"
                " have moved, so it is rejected rather than truncated"
            )
        yield stripped.ljust(reclen)


# ---------------------------------------------------------------------------
# Import-time self-check.
# ---------------------------------------------------------------------------
# WHY (Assumptions): the expected record-length and key-length PAIRS are hard-coded here,
#   deliberately duplicating what each layout already declares, because a check that read its
#   expectation from the thing being checked would prove nothing. Every pair below is the
#   IDCAMS KEYS(len off) and RECORDSIZE from the dataset definition that creates the file, so
#   this table is an independent second source rather than a restatement: KEYS(8,0) and
#   RECORDSIZE(80,80) at app/jcl/DUSRSECJ.jcl lines 65 and 66, KEYS(11 0) and
#   RECORDSIZE(300 300) at app/jcl/ACCTFILE.jcl lines 40 and 41, and so on through
#   CARDFILE, CUSTFILE, XREFFILE, TRANFILE, DISCGRP, TRANCATG, TRANTYPE and TCATBALF. The
#   three entries with no dataset definition of their own are the daily transaction input,
#   which shares the transaction file's geometry, and the three derived records.
_EXPECTED_GEOMETRY: Final[Mapping[str, tuple[int, int]]] = MappingProxyType(
    {
        "SECUSER": (80, 8),
        "ACCOUNT": (300, 11),
        "CARD": (150, 16),
        "CUSTOMER": (500, 9),
        "XREF": (50, 16),
        "DALYTRAN": (350, 16),
        "TRAN": (350, 16),
        "DISGROUP": (50, 16),
        "TRANCAT": (60, 6),
        "TRANTYPE": (60, 2),
        "TCATBAL": (50, 17),
        "TRNX": (350, 32),
        "REJECT": (430, 16),
        "INTTRAN": (350, 16),
    }
)

# WHY (Assumptions): the three alternate indexes are checked against the fields that back
#   them, and this table is the independent source for that check. Each entry is the layout
#   name paired with the IDCAMS clause that declares the index: KEYS(11 16) at
#   app/jcl/CARDFILE.jcl line 85, KEYS(11,25) at app/jcl/XREFFILE.jcl line 74 -- written
#   comma-separated where the other two are space-separated -- and KEYS(26 304) at
#   app/jcl/TRANFILE.jcl line 84, restated at app/jcl/TRANIDX.jcl line 27. Alternate indexes
#   are real access paths and not decoration, so an index whose declared offset drifted from
#   the field it indexes would build cleanly and then answer with the wrong bytes.
_EXPECTED_ALTERNATE_KEYS: Final[Mapping[str, tuple[tuple[str, int, int], ...]]] = MappingProxyType(
    {
        "CARD": (("CARD-ACCT-ID", 16, 11),),
        "XREF": (("XREF-ACCT-ID", 25, 11),),
        "TRAN": (("TRAN-PROC-TS", 304, 26),),
    }
)


def _validate_declarations() -> None:
    """Prove every table this module declares, and raise if any of them disagrees.

    Purpose
    -------
    Turn a silent transcription error into an immediate, loud import failure. This module is
    the single source of offsets, so a wrong offset must never reach the code that depends on
    it, and importing the module is the earliest moment such an error can be detected. The
    reference codec places its equivalent self-check at import time for the same stated reason
    and calls it from module scope at ``tests/helpers/record_codec.py`` line 1770.

    The check proves eight things, each of which would otherwise be silent: that every
    registered layout still tiles itself exactly; that every registered layout's record length
    and key length match the dataset definition that creates the file; that every alternate
    index sits exactly over the field that backs it; that the export envelope's payload field
    agrees with the two published payload constants; that all five export branches are the
    declared payload length; that the two authorization segments close at their published
    lengths; that each collapsed ``OCCURS`` interval is the occurrence count times the element
    width; and that every name in the copybook and misspelling tables resolves to something
    that actually exists.

    Assumptions: this raises :class:`LayoutError` rather than using ``assert``, and the
    distinction is not stylistic. ``python -O`` strips ``assert`` statements, so an
    assertion-based self-check would hold during a test run and then vanish in the deployment
    where a wrong offset actually costs money -- which is the one place it is needed. The
    reference codec records the identical reasoning at ``tests/helpers/record_codec.py``
    line 1710.

    Parameters
    ----------
    None

    Returns
    -------
    None

    Raises
    ------
    LayoutError
        If any registered layout fails its geometry invariant, if a record length or key
        length disagrees with the dataset definition, if an alternate index does not sit over
        its backing field, if an export or authorization layout does not close at its declared
        length, if a collapsed ``OCCURS`` interval does not equal its occurrence count times
        its element width, or if a metadata table names something that does not exist.
    """
    for name, spec in LAYOUTS.items():
        spec.validate_geometry()
        expected = _EXPECTED_GEOMETRY.get(name)
        if expected is None:
            raise LayoutError(
                f"record {name} is registered but has no expected geometry recorded; the"
                " independent dataset-definition cross-check must cover every registered name,"
                " or a layout could be added without one"
            )
        if (spec.reclen, spec.key_length) != expected:
            raise LayoutError(
                f"record {name} declares a record length and key length of"
                f" {(spec.reclen, spec.key_length)} but its dataset definition states"
                f" {expected}"
            )
    missing = tuple(name for name in _EXPECTED_GEOMETRY if name not in LAYOUTS)
    if missing:
        raise LayoutError(
            f"the expected geometry table names {missing}, which the registry does not hold;"
            " every base master and derived record must be registered, because a table entry"
            " with no layout is a record the ETL cannot read at all"
        )

    for name, expected_alternates in _EXPECTED_ALTERNATE_KEYS.items():
        declared = LAYOUTS[name].alternate_keys
        observed = tuple((alt.name, alt.offset, alt.length) for alt in declared)
        if observed != expected_alternates:
            raise LayoutError(
                f"record {name} declares alternate keys {observed} but its dataset definition"
                f" states {expected_alternates}"
            )
        for alternate in declared:
            backing = LAYOUTS[name].field(alternate.name)
            if (backing.start, backing.length) != (alternate.offset, alternate.length):
                raise LayoutError(
                    f"record {name} alternate key {alternate.name} spans"
                    f" {alternate.length} bytes at offset {alternate.offset} but its backing"
                    f" field is {backing.describe()}"
                )

    payload = EXPORT_HEADER_LAYOUT.field("EXPORT-RECORD-DATA")
    if (payload.start, payload.length) != (EXPORT_PAYLOAD_OFFSET, EXPORT_BRANCH_LENGTH):
        raise LayoutError(
            f"the export payload field is {payload.describe()} but the published payload"
            f" constants state offset {EXPORT_PAYLOAD_OFFSET} and length"
            f" {EXPORT_BRANCH_LENGTH}; a caller slicing with the constants would then decode"
            " the wrong bytes against every branch"
        )
    export_key = EXPORT_HEADER_LAYOUT.field("EXPORT-SEQUENCE-NUM")
    if (export_key.start, export_key.length) != (EXPORT_KEY_OFFSET, EXPORT_KEY_LENGTH):
        raise LayoutError(
            f"the export record key field is {export_key.describe()} but the published key"
            f" constants state offset {EXPORT_KEY_OFFSET} and length {EXPORT_KEY_LENGTH}"
        )

    # WHY (Assumptions): the export record is EXEMPT from the key-alignment cross-check the
    #   other ten datasets satisfy, and the exemption is asserted here rather than left
    #   unstated. Its program declares the key on a field at offset 27 while its dataset
    #   definition declares KEYS(4 28), so the two genuinely disagree by one byte. Requiring
    #   them to agree would force this module either to adopt an offset the record layout does
    #   not support or to alter the baseline, and neither is permissible; recording the
    #   disagreement as an expected inequality is what keeps the check meaningful for the other
    #   ten while documenting the one that cannot pass it.
    if EXPORT_DECLARED_DATASET_KEY_OFFSET == EXPORT_KEY_OFFSET:
        raise LayoutError(
            "the export dataset definition and the export record layout are recorded as"
            " agreeing on the key offset, but the documented baseline divergence is that they"
            " do not; if the baseline has genuinely changed, the divergence note above must"
            " change with it rather than the constants alone"
        )

    for record_type, branch in EXPORT_RECORD_TYPES.items():
        branch.validate_geometry()
        if branch.reclen != EXPORT_BRANCH_LENGTH:
            raise LayoutError(
                f"export branch {record_type!r} ({branch.name}) declares {branch.reclen} bytes"
                f" but every branch overlays the same {EXPORT_BRANCH_LENGTH}-byte payload area"
            )

    for segment, expected_length in (
        (PENDING_AUTH_SUMMARY_LAYOUT, 100),
        (PENDING_AUTH_DETAIL_LAYOUT, 200),
    ):
        segment.validate_geometry()
        if segment.reclen != expected_length:
            raise LayoutError(
                f"authorization segment {segment.name} declares {segment.reclen} bytes but sums"
                f" to {expected_length} under the packed and binary width rules"
            )

    for owner, field_name, occurs, element_length in (
        (
            PENDING_AUTH_SUMMARY_LAYOUT,
            "PA-ACCOUNT-STATUS",
            PA_ACCOUNT_STATUS_OCCURS,
            PA_ACCOUNT_STATUS_ELEMENT_LENGTH,
        ),
        (
            EXPORT_CUSTOMER_LAYOUT,
            "EXP-CUST-ADDR-LINES",
            EXP_CUST_ADDR_LINES_OCCURS,
            EXP_CUST_ADDR_LINES_ELEMENT_LENGTH,
        ),
        (
            EXPORT_CUSTOMER_LAYOUT,
            "EXP-CUST-PHONE-NUMS",
            EXP_CUST_PHONE_NUMS_OCCURS,
            EXP_CUST_PHONE_NUMS_ELEMENT_LENGTH,
        ),
    ):
        table = owner.field(field_name)
        if table.length != occurs * element_length:
            raise LayoutError(
                f"record {owner.name} field {table.describe()} collapses an OCCURS table, so its"
                f" length must equal {occurs} occurrences of {element_length} bytes, which is"
                f" {occurs * element_length}"
            )

    if tuple(sorted(COPYBOOK_OF)) != tuple(sorted(LAYOUTS)):
        raise LayoutError(
            "the copybook provenance table and the registry must name exactly the same records,"
            f" but the table names {tuple(sorted(COPYBOOK_OF))} and the registry holds"
            f" {tuple(sorted(LAYOUTS))}"
        )

    # WHY (Assumptions): each misspelled name is required to resolve in at least one declared
    #   layout, which is what stops the lineage table from outliving the fields it documents. A
    #   stale entry would be worse than no entry: a reader searching for the baseline spelling
    #   would find a mapping to a target name that nothing produces, and would conclude the
    #   rename had been applied when it had not.
    all_declared = (
        tuple(LAYOUTS.values())
        + (EXPORT_HEADER_LAYOUT, PENDING_AUTH_SUMMARY_LAYOUT, PENDING_AUTH_DETAIL_LAYOUT)
        + tuple(EXPORT_RECORD_TYPES.values())
    )
    declared_names = {field.name for spec in all_declared for field in spec.fields}
    for misspelled in MISSPELLED_FIELDS:
        if misspelled not in declared_names:
            raise LayoutError(
                f"the misspelling lineage table names {misspelled}, which no declared layout"
                " carries; a lineage entry with no field is a rename a reader would believe had"
                " been applied"
            )


_validate_declarations()
