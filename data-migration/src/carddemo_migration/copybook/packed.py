"""Computational codec for the CardDemo ``USAGE COMP-3`` and ``USAGE COMP`` numeric fields.

Purpose
-------
This module is the SECOND of the two numeric regimes the extract-transform-load package
needs, and it is the only one that reads bytes rather than characters. Packed decimal
(``USAGE COMP-3``) stores two decimal digits per byte with the sign in the LOW-ORDER
NIBBLE of the field's final byte; binary (``USAGE COMP``, spelled ``BINARY`` in the
language's synonym form) stores a whole machine unit of big-endian two's complement whose
size is chosen by the digit count. :func:`decode_packed` and :func:`decode_binary` turn one
such span into an exact :class:`decimal.Decimal` carried at the field's declared scale;
:func:`encode_packed` and :func:`encode_binary` are their inverses. The four
field-oriented forms -- :func:`decode_packed_field`, :func:`encode_packed_field`,
:func:`decode_binary_field` and :func:`encode_binary_field` -- do the same for a span
located by a field descriptor, which keeps a caller anchored on the declared offset rather
than on anything it found in the data.

Every entry point here takes and returns ``bytes`` and NEVER ``str``. A packed field is
binary: its bytes hold nibbles, not characters, and a span such as ``0x00 0x00 0x00 0x00
0x00 0x00 0x0C`` contains six NUL bytes that no character decoder can carry. Passing such a
span through one maps each uninterpretable byte to a replacement character of the same
width, so the field keeps its declared length, every offset after it still looks valid, and
only the amount is wrong. The parity oracle takes the same position from the other
direction: ``tests/helpers/localstack_setup.py`` uploads its mainframe-character-set
datasets straight from disk because those bytes include NULs and overpunch sign bytes
(around its line 729), records that round-tripping one through a text write would corrupt
it (around its line 741), and reads them back as raw bytes because a text decode mangles
invalid sequences into replacement characters (around its line 1068). Character conversion
is therefore ``carddemo_migration.copybook.ebcdic_codec``'s work and happens one field at a
time; this module receives bytes that were never decoded at all, which is exactly what a
packed field requires.

Nothing else in the package converts these bytes, and this module converts no others.
Zoned display decimal belongs to ``carddemo_migration.copybook.zoned``, cp037 character
decoding to ``carddemo_migration.copybook.ebcdic_codec``, and the byte geometry of every
field to ``carddemo_migration.copybook.layouts``, which this module imports and never
restates.

Why two regimes need two codecs and never one branching module
--------------------------------------------------------------
All eleven CardDemo base-master records are one hundred per cent zoned display: matching
every level-numbered declaration in the eleven copybooks against ``COMP``, ``COMP-3`` and
``OCCURS`` returns nothing at all. ``app/cpy/CVACT01Y.cpy`` line 7 declaring
``ACCT-CURR-BAL PIC S9(10)V99`` is the exemplar, and every amount in those eleven records
looks like it. Those fields belong to the sibling module and never reach this one. (A naive
whole-file grep is not the proof and should not be quoted as one: it returns a single hit in
``app/cpy/CSUSR01Y.cpy``, which is the word "compliance" in that file's Apache licence
header rather than a usage clause. The declaration-level match is the evidence.)

Packed and binary storage reaches this migration through exactly three record families:

* ``app/cpy/CVEXPORT.cpy``, the 500-byte multi-record export layout, which is the ONLY base
  copybook that uses either usage. It declares four packed fields, at its lines 41, 50, 52
  and 71, and seven binary ones, at its lines 16, 25, 57, 72, 87, 95 and 96.
* ``app/app-authorization-ims-db2-mq/cpy/CIPAUSMY.cpy``, the 100-byte IMS authorization
  summary segment, whose data begins at its line 19 at level ``05`` with no ``01`` above it.
  Its thirteen declarations include SEVEN packed fields and TWO binary ones; the two binary
  counters at its lines 27 and 28 are easy to overlook and are handled here as well.
* ``app/app-authorization-ims-db2-mq/cpy/CIPAUDTY.cpy``, the 200-byte IMS authorization
  detail segment, which uses packed decimal for money and for BOTH halves of its composite
  key.

The authorization segments are consequently the only place packed decimal reaches PERSISTED
target data. ``app/cpy/CVEXPORT.cpy`` describes a transport record -- the export and import
round trip -- so its packed bytes are decoded at the edge of the pipeline and the packed
form is never what the target stores.

Two regimes, two codecs, and never one module that inspects bytes to decide which regime it
is in. Such a module would be guessing from data what the copybook already states, and the
copybook is normative.

Why this module carries more explanation than its size suggests
--------------------------------------------------------------
A defect here is SILENT. A mis-decoded packed field does not raise, does not misalign the
record and does not fail a length check: it produces a plausible amount that is wrong, and
it surfaces much later as a balance that does not reconcile. Two specific mistakes are
available and both are easy. Reading a field one nibble early shifts every digit one place
and yields a value TEN TIMES TOO LARGE. Declaring ``PIC S9(10)V99 COMP-3`` as six bytes
rather than seven shifts every field after it by one byte, and a one-byte shift still
decodes to digits. Every decision below is therefore recorded with the specific consequence
of getting it wrong.

``USAGE`` determines the byte width, and the picture clause alone never does
---------------------------------------------------------------------------
Three rules, one per regime, and a field is measured by the rule its ``USAGE`` selects::

    COMP-3 (packed)   bytes = ceil((digits + 1) / 2)   the + 1 is the sign nibble
    COMP   (binary)   bytes = 2 for 1-4 digits, 4 for 5-9, 8 for 10-18, big-endian
    display (zoned)   bytes = the digit count exactly  -- the sibling module's rule

The packed ladder those rules produce is 3 digits to 2 bytes, 5 to 3, 9 to 5, 11 to 6 and 12
to 7, and every rung appears in the corpus. This module does not restate any of the three:
:func:`packed_width` and :func:`binary_width` are re-exported from
``carddemo_migration.copybook.layouts``, which owns them and proves them.

``app/cpy/CVEXPORT.cpy`` settles the point beyond argument by declaring the SAME picture
clause under three different usages inside one 500-byte record::

    line 50   EXP-ACCT-CURR-BAL          PIC S9(10)V99 COMP-3    ->   7 bytes
    line 51   EXP-ACCT-CREDIT-LIMIT      PIC S9(10)V99           ->  12 bytes
    line 57   EXP-ACCT-CURR-CYC-DEBIT    PIC S9(10)V99 COMP      ->   8 bytes

Three physical widths, one picture clause. Line 52 declares
``EXP-ACCT-CASH-CREDIT-LIMIT PIC S9(10)V99 COMP-3``, a second seven-byte packed field. The
codec must therefore be TOLD which usage applies, and it takes that from the ``kind``
component of the field descriptor rather than from any reading of the picture text.

``PIC S9(10)V99 COMP-3`` is SEVEN bytes and not six, proven twice from files that had no
reason to agree. Arithmetically: twelve digit positions plus one sign nibble is thirteen
nibbles, and thirteen nibbles occupy the ceiling of thirteen halved, which is seven; the
five independent 460-byte ``REDEFINES`` branches of ``app/cpy/CVEXPORT.cpy`` -- at its lines
24, 47, 65, 84 and 93 -- each close at exactly 460 against a 500-byte base record only at
that width. By contradiction: the 28 storage-bearing items of ``.../CIPAUDTY.cpy`` sum to
exactly 200 at seven bytes and to 198 at six, and 198 is not a length that segment can have.
Any statement that the field is six bytes is defective and must not be propagated.

The four-byte binary width is provable from REAL BYTES rather than from arithmetic alone,
which makes it the strongest evidence available for any width here.
``EXPORT-SEQUENCE-NUM PIC 9(9) COMP`` is declared at line 16 of ``app/cpy/CVEXPORT.cpy``,
which the rule above makes four bytes at zero-based offset 27. Reading four big-endian bytes
there in ``app/data/EBCDIC/AWS.M2.CARDDEMO.EXPORT.DATA.PS`` yields clean consecutive
integers -- record 0 gives 1, record 9 gives 10, record 265 gives 266 and record 499 gives
509 -- which no other width produces. Nine display bytes would also make the header sum 505
rather than the declared 500. That same reading explains an oddity of the file: it contains
exactly five newline bytes, because the integers 10 and 266 encode a low-order ``0x0A``.

There is NO ``SYNCHRONIZED`` clause anywhere in the reference tree, so no alignment padding
applies to any computational field and a field always begins exactly where the previous one
ended. This is stated because a reader who assumed halfword alignment would insert a phantom
pad byte before each binary field, which shifts every field after it and closes none of the
sums above.

The packed byte layout, and where its unused nibble sits
-------------------------------------------------------
A packed field of ``n`` bytes holds ``2n`` nibbles. The last nibble of the last byte is the
sign; every nibble before it, read high-then-low across each byte from the front, is a
decimal digit. A field therefore uses ``digits + 1`` nibbles out of ``2n``, and the surplus
is at most one::

    PIC S9(05) COMP-3  12345   ->  3 bytes,  6 nibbles,  6 used,  0 spare  ->  1 2 3 4 5 C
    PIC S9(10)V99 COMP-3 158.00 -> 7 bytes, 14 nibbles, 13 used,  1 spare  ->  0 ... C

The spare nibble, when there is one, sits at the FRONT of the field and must be zero. Note
which parity produces it: the surplus exists when the digit count is EVEN, not odd. Twelve
digits occupy seven bytes, which is fourteen nibbles for thirteen used, so one pads; eleven
digits occupy six bytes, which is twelve nibbles for twelve used, so none pads. This module
derives the count from the geometry and never from the parity, for the reason recorded on
:func:`_pad_nibbles`.

The sign nibbles
----------------
The reference compiler lays down exactly three values and nothing else::

    0x0C   signed, non-negative     PIC S9(n) COMP-3 holding zero or more
    0x0D   signed, negative         PIC S9(n) COMP-3 holding less than zero
    0x0F   unsigned                 PIC  9(n) COMP-3, declared with no leading S

All three occur in real data. Reading the export dataset above, all fifty of its customer
records carry ``0x0F`` in ``EXP-CUST-FICO-CREDIT-SCORE PIC 9(03) COMP-3``, and its three
hundred transaction records split 250 carrying ``0x0C`` and 50 carrying ``0x0D`` in
``EXP-TRAN-AMT PIC S9(09)V99 COMP-3``. The unsigned nibble is therefore a real shape here
and not a theoretical one.

The records this module serves
-----------------------------
``.../CIPAUSMY.cpy`` closes at 100 bytes::

    PA-ACCT-ID            PIC S9(11) COMP-3       line 19    6    seven packed fields
    PA-CUST-ID            PIC  9(09)              line 20    9    display, not this module
    PA-AUTH-STATUS        PIC  X(01)              line 21    1
    PA-ACCOUNT-STATUS     PIC  X(02) OCCURS 5     line 22   10    elementary OCCURS
    PA-CREDIT-LIMIT ..    PIC S9(09)V99 COMP-3  lines 23-26  6 each
    PA-APPROVED-AUTH-CNT  PIC S9(04) COMP         line 27    2    two binary fields
    PA-DECLINED-AUTH-CNT  PIC S9(04) COMP         line 28    2
    PA-APPROVED-AUTH-AMT  PIC S9(09)V99 COMP-3    line 29    6
    PA-DECLINED-AUTH-AMT  PIC S9(09)V99 COMP-3    line 30    6
    FILLER                PIC X(34)               line 31   34

The ``OCCURS`` at line 22 is an ELEMENTARY-level one, on the same line as its picture
clause, so the field is ten bytes and not two; reading it as two mis-aligns everything after
it by eight. That segment carries ZERO ``88``-level items. Its sibling
``.../CIPAUDTY.cpy`` carries exactly seven, at its line 31 and at its lines 46 to 49 and 51
to 52, and an ``88``-level item occupies no storage at all, so none of them contributes to
any width above or below.

``.../CIPAUDTY.cpy`` closes at 200 bytes and contributes three packed widths --
``PIC S9(05) COMP-3`` at its line 20 is three bytes, ``PIC S9(09) COMP-3`` at its line 21 is
five, and ``PA-APPROVED-AMT PIC S9(10)V99 COMP-3`` at its line 35 is seven. Its
``PA-AUTHORIZATION-KEY`` at line 19 is an eight-byte GROUP of the first two of those, and
the target decomposes that group into the two integer columns forming its composite primary
key. A packed field is therefore a KEY component here, which is why decode has to be exact
and order-preserving rather than merely close: a value that decodes one part in a thousand
wrong still produces a row, and it produces it under the wrong key.

The target side corroborates the money width independently.
``app/app-authorization-ims-db2-mq/ddl/AUTHFRDS.ddl`` declares ``TRANSACTION_AMT`` and
``APPROVED_AMT`` as ``DECIMAL(12,2)`` at its lines 12 and 13 -- the baseline's own statement
that a twelve-digit packed money field carries two decimal places and therefore ten integer
places, which is exactly the split :func:`decode_packed` is handed. The companion
``.../ddl/XAUTHFRD.ddl`` is a separate unique index on ``(CARD_NUM ASC, AUTH_TS DESC)`` and
says nothing about widths. Both IMS copybooks write ``PIC  9(09)`` with TWO spaces after
``PIC``; this module never parses picture text, but anything that does must tokenise on
whitespace RUNS.

The round-trip law, and its single documented exception
------------------------------------------------------
For every span this module accepts, encoding what it decoded reproduces the original bytes
exactly::

    encode_packed(decode_packed(raw, i, d, s), i, d, s) == raw
    encode_binary(decode_binary(raw, i, d, s), i, d, s) == raw

That law is not a convenience. The parity oracle compares batch output byte for byte after
timestamp normalisation, so a re-encoded record differing in one nibble is a failed
comparison rather than a cosmetic difference.

There is exactly one exception. A packed zero carrying the negative sign nibble decodes to a
value equal to zero and re-encodes carrying the POSITIVE nibble, so ``0x0D`` over an all-zero
magnitude normalises to ``0x0C`` across a round trip. That is the precise analogue of the
sibling module's one exception, where a closing-brace zero -- the zoned negative zero --
normalises to an opening brace. Both exist for the same reason and are recorded in the same
place: the migration's traceability matrix, under the divergence the Java parity codec
registers as ``D-SIGNED-ZERO-PACKED``.

Known-answer vectors
--------------------
Every vector below was read out of real repository data and is exercised in both directions,
so ``python -m doctest`` over this module is a genuine known-answer test rather than a
formatting check. The first two are the sharpest evidence the corpus offers that the two
numeric regimes land the same value: they are the SAME two amounts the sibling module's own
vectors decode from zoned spans, stored packed here::

    >>> from decimal import Decimal
    >>> decode_packed(bytes.fromhex("00000050477c"), 9, 2, True)     # EXP-TRAN-AMT
    Decimal('504.77')
    >>> decode_packed(bytes.fromhex("00000091900d"), 9, 2, True)     # the same field, negative
    Decimal('-919.00')
    >>> decode_packed(bytes.fromhex("0000000102000c"), 10, 2, True)  # EXP-ACCT-CASH-CREDIT-LIMIT
    Decimal('1020.00')
    >>> decode_packed(bytes.fromhex("0000000015800c"), 10, 2, True)  # EXP-ACCT-CURR-BAL
    Decimal('158.00')
    >>> decode_packed(bytes.fromhex("300f"), 3, 0, False)            # EXP-CUST-FICO-CREDIT-SCORE
    Decimal('300')
    >>> encode_packed(Decimal("504.77"), 9, 2, True).hex()
    '00000050477c'
    >>> encode_packed(Decimal("-919.00"), 9, 2, True).hex()
    '00000091900d'
    >>> encode_packed(Decimal("300"), 3, 0, False).hex()
    '300f'
    >>> decode_binary(bytes.fromhex("2faf0800"), 9, 0, False)        # EXP-TRAN-MERCHANT-ID
    Decimal('800000000')
    >>> decode_binary(bytes.fromhex("0000000a"), 9, 0, False)        # EXPORT-SEQUENCE-NUM, record 9
    Decimal('10')
    >>> encode_binary(Decimal("800000000"), 9, 0, False).hex()
    '2faf0800'

The widths are the ladder and the step function, taken from the module that owns them::

    >>> packed_width(3, 0), packed_width(0, 5), packed_width(9, 0), packed_width(9, 2)
    (2, 3, 5, 6)
    >>> packed_width(10, 2)
    7
    >>> binary_width(4, 0), binary_width(9, 0), binary_width(10, 2)
    (2, 4, 8)

A decoded value carries the field's declared fractional width as its scale and is never
normalised::

    >>> decode_packed(bytes.fromhex("0000000015800c"), 10, 2, True).as_tuple().exponent
    -2
    >>> decode_binary(bytes.fromhex("0000000000000000"), 10, 2, True)
    Decimal('0.00')

The negatively-signed zero is the one span that does not return unchanged::

    >>> decode_packed(bytes.fromhex("0000000000000d"), 10, 2, True)
    Decimal('0.00')
    >>> encode_packed(Decimal("0.00"), 10, 2, True).hex()
    '0000000000000c'

Design decisions (WHY)
----------------------
Trade-offs:
    **Standard library only.** This module imports ``decimal`` and ``typing`` and nothing
    else outside the standard library, plus ``layouts`` for the geometry it refuses to
    restate. The consequence bought is concrete: the codec imports and runs against the
    vectors above on a bare checkout with no database driver installed, no cloud SDK present
    and no character-set package configured, which is exactly the isolation a module whose
    defects are silent needs in order to stay testable. The sibling module and the reference
    codec state the same discipline for the same stated reason. The accepted cost is that a
    convenience such as resolving a dataset location cannot live here and belongs to a
    loader instead.
Trade-offs:
    **The binary path lives here rather than in a sixth module.** ``COMP`` is a different
    byte layout from ``COMP-3`` and could have had a file of its own. It does not, because
    the two share the one statement this module exists to make -- that ``USAGE`` determines
    width and the picture clause never does -- and they share the fixed-point landing
    contract below, so splitting them would put one rule in two places and invite the two
    copies to drift. The accepted cost is a module responsibility slightly broader than its
    name suggests; the benefit is a single site where a reader can check both computational
    widths against one another and against the zoned width they are so easily confused with.
Assumptions:
    **Every validation raises; none asserts.** ``python -O`` strips ``assert`` statements
    outright, so an assertion is not a validation but a check that disappears in the
    deployment where a misread amount actually costs money. Every guarantee here is enforced
    by an explicit ``raise``, and the two error types below exist so a caller can tell an
    alignment fault from a content fault.
Assumptions:
    **Exact fixed point at every hop, and no binary floating point anywhere.** A binary
    floating-point number cannot represent ten cents exactly, so a single conversion through
    one would silently corrupt a monetary total. Decoding therefore produces
    :class:`decimal.Decimal` and encoding accepts ``Decimal``, ``int`` or ``str`` and refuses
    the binary form outright. The same discipline carries downstream, as ``NUMERIC(p,2)`` in
    the target schema and as ``NUMERIC`` in every money aggregate the verification queries
    compute, and it is the reason the scaling steps below move a decimal point rather than
    divide by a power of ten: moving the point is exact by construction and involves no
    rounding decision at all, whereas a quotient introduces one.

Parameters
----------
None.
    The module is imported for its codec API and performs no caller-supplied work at import
    time.

Returns
-------
None.
    Importing the module defines constants, errors and functions; it does not produce a
    value.

Raises
------
None.
    Constant definition and function definition do not inspect record data.
"""

from __future__ import annotations

from decimal import Decimal, InvalidOperation, localcontext
from typing import Final

from carddemo_migration.copybook.layouts import FieldSpec, Kind, binary_width, packed_width

# WHY (Trade-offs): the import boundary is the standard library plus ``layouts`` and nothing
#   more, which is what lets a packed decode be reproduced on a bare checkout before anything
#   else is installed. Five specific dependencies the package uses elsewhere are deliberately
#   absent here: the PostgreSQL driver, the AWS SDK, that SDK's own core library, the
#   third-party package that registers the cp037 character-set family, and this package's own
#   configuration module. The first four would make the codec unimportable without
#   infrastructure, and the fifth would make a byte-to-value decision depend on a runtime
#   environment having been resolved first. The accepted cost is that record loading,
#   transcoding and persistence stay outside this module; the benefit is that the one decision
#   in the pipeline that fails silently can be tested with nothing else present.

# WHY (Trade-offs): the two width functions are RE-EXPORTED from ``layouts`` rather than
#   defined again here, even though they are part of this module's advertised surface. A
#   second definition would be a second statement of the rule that decides whether
#   ``PIC S9(10)V99 COMP-3`` is six bytes or seven, and two statements of one rule can
#   disagree -- at which point the 460-byte export branches close in one module and not in
#   the other. The accepted cost is that a reader looking for the arithmetic has to follow one
#   import; the benefit is that the arithmetic, and both of its proofs, exist exactly once.

# WHY (Assumptions): the public surface is declared explicitly and in sorted order so a
#   consumer's import list can be checked against it mechanically, and so the two error types
#   are advertised as part of the contract rather than left to be discovered by a caller
#   writing an except clause. The sibling codec and the geometry module declare their surfaces
#   the same way for the same reason.
__all__ = [
    "PackedDecimalError",
    "PackedSpanWidthError",
    "binary_width",
    "decode_binary",
    "decode_binary_field",
    "decode_packed",
    "decode_packed_field",
    "encode_binary",
    "encode_binary_field",
    "encode_packed",
    "encode_packed_field",
    "packed_width",
]

# WHY (Assumptions): the sign occupies the low-order nibble of a packed field's final byte and
#   takes one of exactly three values in this corpus, all three of which are observable in
#   real data rather than taken from documentation. A signed negative value lays down 0x0D, a
#   signed non-negative value 0x0C, and a field declared without the leading S lays down 0x0F
#   -- which is why an unsigned packed field is a real shape here: all fifty customer records
#   of app/data/EBCDIC/AWS.M2.CARDDEMO.EXPORT.DATA.PS carry 0x0F in their
#   PIC 9(03) COMP-3 credit score. The Java parity codec declares the same three constants, so
#   a span decoded in either language yields the same sign.
_SIGN_SIGNED_POSITIVE: Final[int] = 0x0C
_SIGN_SIGNED_NEGATIVE: Final[int] = 0x0D
_SIGN_UNSIGNED: Final[int] = 0x0F

# WHY (Alternatives Considered): 0x0A, 0x0B and 0x0E are the alternate sign nibbles some
#   encoders emit, and this codec rejects all three, as does the Java parity codec. Blanket
#   tolerance of every nibble value was the alternative and was rejected: an unexpected nibble
#   in the sign position is the clearest available evidence that the field offset is wrong, and
#   treating it as positive would convert a detectable geometry fault into silent corruption of
#   a money value's sign. The threshold is named rather than the three values, because the test
#   that actually matters is whether the nibble is a digit or a sign -- anything below 0x0A
#   there is a digit, which means the field is zoned rather than packed, or the read is off by
#   one nibble. The compromise accepted is that a span produced by some other encoder is
#   refused rather than decoded, which is the intended direction of failure because such a span
#   did not come from this baseline.
_LOWEST_SIGN_NIBBLE: Final[int] = 0x0A

# WHY (Assumptions): a nibble above nine in a DIGIT position is not a tolerable variant; it is
#   proof that the read is wrong. Either the offset is misaligned, or the field is not packed at
#   all, or a sign nibble has been reached early. Masking such a nibble down into range instead
#   would manufacture a digit that was never written, and the resulting amount would be
#   plausible and unchallenged.
_MAX_DIGIT_NIBBLE: Final[int] = 0x09

_NIBBLE_MASK: Final[int] = 0x0F
_HIGH_NIBBLE_SHIFT: Final[int] = 4
_NIBBLES_PER_BYTE: Final[int] = 2

# WHY (Assumptions): the hexadecimal digits are indexed out of a constant rather than produced
#   by a format specifier, which keeps a diagnostic independent of the ambient locale. A
#   locale-sensitive case conversion of a formatted hexadecimal digit yields a different
#   character under a locale whose dotless letter maps unexpectedly, and a diagnostic that reads
#   differently per locale cannot be matched against a known message.
_HEX_DIGITS: Final[str] = "0123456789ABCDEF"

# WHY (Assumptions): the ten ASCII digits are named explicitly rather than checked with
#   ``str.isdigit``, which also accepts the decimal digits of other scripts. Such a character
#   subtracted from the ASCII zero yields a digit value in the hundreds, so admitting one would
#   turn a rejected string into an accepted amount off by orders of magnitude.
_DECIMAL_DIGITS: Final[str] = "0123456789"

# WHY (Assumptions): a computational field's magnitude is right-aligned within its declared
#   digit positions and padded on the LEFT with this character, exactly as the display regime
#   pads a zoned field. Padding on the right would multiply every short value by a power of ten.
_PAD_DIGIT: Final[str] = "0"

# WHY (Assumptions): binary fields are stored most significant byte first. This is not a
#   platform property to be probed at run time but a property of the data already written to
#   disk, and it is measurable: reading EXPORT-SEQUENCE-NUM as four big-endian bytes at
#   zero-based offset 27 of the export dataset yields 1, 10, 266 and 509 for records 0, 9, 265
#   and 499, which is a sequence. The little-endian reading of those same four spans yields
#   16777216, 167772160, 167837696 and 4244701184, which is not -- so the byte order is settled
#   by the data rather than chosen, and choosing the other one would silently turn a record
#   counter into a nine-figure identifier that no verification of type or width would question.
_BINARY_BYTE_ORDER: Final[str] = "big"

# WHY (Trade-offs): the encode path quantises inside a private decimal context whose precision
#   is the declared digit count plus this margin. The margin exists so that a value WIDER than
#   the field still quantises successfully and is then refused by the explicit capacity check
#   with a message naming both counts, rather than failing as an inscrutable precision error
#   from the arithmetic itself. Eight is comfortably more than any field in the corpus needs,
#   since the widest declaration anywhere is twelve digit positions.
_QUANTIZE_PRECISION_MARGIN: Final[int] = 8

# WHY (Assumptions): only these two of the five storage regimes are computational regimes, so
#   only these two can reach this module. A zoned or unsigned display field is one printable
#   digit per byte and belongs to ``zoned``; a character field has no numeric reading at all.
#   Naming the admissible set once means a mis-routed field is refused with one message instead
#   of having its bytes read as nibbles and returning a number that looks like an amount.
_COMPUTATIONAL_KINDS: Final[frozenset[Kind]] = frozenset({Kind.PACKED, Kind.BINARY})

# WHY (Assumptions): the three buffer types below are the ones a caller legitimately holds a
#   record in -- an immutable read, a mutable build and a zero-copy slice of either. ``int`` is
#   deliberately NOT among them even though ``bytes(7)`` succeeds, because it succeeds by
#   allocating seven NUL bytes: an accidental integer argument would decode as a zero amount
#   with a digit nibble where the sign belongs rather than raising.
_BUFFER_TYPES: Final[tuple[type, ...]] = (bytes, bytearray, memoryview)


# WHY (Assumptions): every contract violation in this module is enforced with an explicit
#   exception rather than an ``assert``. Optimised Python removes assertions outright, so a
#   width, pad-nibble or sign check written as an assertion would vanish in exactly the
#   deployment where a misread amount costs money. Both public errors remain ValueError
#   subclasses so an existing parse guard still catches them, while the width subtype lets a
#   caller distinguish a misaligned record from malformed numeric content -- two faults with
#   different causes and different remedies. The role is the one the reference codec gives its
#   own zoned error type at tests/helpers/record_codec.py line 147.
class PackedDecimalError(ValueError):
    """Report malformed computational content or an impossible exact encoding.

    Purpose
    -------
    Distinguish violations of the packed-decimal or binary contract from unrelated parsing
    failures while remaining compatible with callers that already catch
    :class:`ValueError`. This is the signal that a record has been read against the wrong
    geometry, which is the most consequential failure this codec has because the damage it
    prevents is silent.

    Parameters
    ----------
    args : tuple[object, ...]
        Standard exception arguments, normally one human-readable diagnostic string.

    Returns
    -------
    PackedDecimalError
        A newly constructed content-or-encoding exception.

    Raises
    ------
    None.
    """


class PackedSpanWidthError(PackedDecimalError):
    """Report a computational span whose byte width differs from its declaration.

    Purpose
    -------
    Give alignment faults a catchable type distinct from malformed content, because a short
    or long slice points at record geometry -- a copybook transcribed with the wrong width,
    or a caller slicing from the wrong offset -- while a bad nibble points at the producer's
    numeric representation. The sibling display codec draws the same distinction for the
    same reason.

    Parameters
    ----------
    args : tuple[object, ...]
        Standard exception arguments, normally one human-readable diagnostic string.

    Returns
    -------
    PackedSpanWidthError
        A newly constructed width exception that is also a :class:`PackedDecimalError`.

    Raises
    ------
    None.
    """


# WHY (Trade-offs): the disclosure policy is graded by how much a rendering reveals, and the
#   gate tightens as the rendering widens. Geometry -- name, offset, length, kind -- is always
#   reported, because it is never content. One offending NIBBLE is reported unless the
#   descriptor marks the field sensitive, because four bits cannot reconstruct a value and it is
#   the only actionable detail in this module's most common diagnostic. A rendering of the whole
#   span or of the whole value is reported to NOBODY, sensitive or not: a packed span has no
#   partial form that is useful without being identifying, so there is nothing here
#   corresponding to the last-four concession the display path can make.
# WHY (Assumptions): the reference implementation's zoned decoder echoes the offending raw value
#   in its error text, which suits a test harness reading committed fixtures; this module
#   deliberately does not, and the divergence is documented rather than silent. It is a
#   difference in what is REPORTED and not in what is rejected -- a sensitive field is validated
#   exactly as strictly as any other. The geometry rendering used below is ``layouts``' own
#   :meth:`FieldSpec.describe`, which that module designates as the sensitive-safe rendering for
#   the byte path precisely because it needs no content whatsoever; its ``mask_field`` is not
#   used here because that helper operates on same-width characters and a packed span has no
#   character reading at all.
def _describe(kind: Kind, width: int, field: FieldSpec | None) -> str:
    """Identify a computational field for a diagnostic without disclosing its content.

    Purpose
    -------
    Build the clause every message in this module carries, naming the field through the
    descriptor when the caller supplied one and through its regime and width when it did not,
    and stating outright when content has been withheld on purpose.

    Parameters
    ----------
    kind : Kind
        The storage regime being reported, used when no descriptor is available.
    width : int
        The field's declared byte width, used when no descriptor is available.
    field : FieldSpec | None
        The descriptor whose name, half-open interval, regime and sensitivity are reported,
        or ``None`` when the caller established no field context.

    Returns
    -------
    str
        An identifying clause that never contains field content.

    Raises
    ------
    None.
    """
    if field is None:
        return f"unnamed {kind.name} field of length {width}"
    described = f"field {field.describe()}"
    if field.sensitive:
        # WHY (Assumptions): a reader who sees a diagnostic with no content cannot otherwise
        #   tell whether the codec had nothing to report or withheld it deliberately, and would
        #   reasonably suspect the message itself was defective. Naming the suppression makes
        #   the omission legible and stops anyone from adding the content back to fill the gap.
        return f"{described} (content withheld: field is marked sensitive)"
    return described


def _failure(
    reason: str,
    kind: Kind,
    width: int,
    field: FieldSpec | None,
    detail: str = "",
) -> str:
    """Assemble one sensitive-safe diagnostic from a reason, field context and detail.

    Purpose
    -------
    Give every exception in this module the same ordering and the same disclosure policy:
    the content-free reason first, then the identifying clause, then whatever narrow detail
    :func:`_nibble_detail` has already cleared for release.

    Parameters
    ----------
    reason : str
        Content-free explanation of the violated contract.
    kind : Kind
        The storage regime being reported.
    width : int
        The field's declared byte width.
    field : FieldSpec | None
        Optional descriptor used for the identifying clause.
    detail : str
        An already-cleared trailing clause, or the empty string when there is none.

    Returns
    -------
    str
        A complete diagnostic that is safe for the supplied field context.

    Raises
    ------
    None.
    """
    return f"{reason}; {_describe(kind, width, field)}{detail}"


def _nibble_detail(nibble: int, field: FieldSpec | None) -> str:
    """Render one offending nibble for a diagnostic, or nothing for a sensitive field.

    Purpose
    -------
    Name the nibble that failed validation in hexadecimal, which is what turns "this field is
    misaligned" into a message a reader can act on, while honouring the suppression a
    sensitive descriptor demands.

    Parameters
    ----------
    nibble : int
        The nibble value that failed validation, between zero and fifteen.
    field : FieldSpec | None
        Descriptor whose sensitivity governs disclosure, or ``None`` when the caller
        established no field context.

    Returns
    -------
    str
        A clause naming the nibble in hexadecimal, or the empty string when the field is
        marked sensitive.

    Raises
    ------
    None.
    """
    if field is not None and field.sensitive:
        return ""
    return f"; found 0x{_HEX_DIGITS[nibble & _NIBBLE_MASK]}"


def _first_non_digit(text: str) -> int | None:
    """Locate the first character of a string that is not an ASCII digit.

    Purpose
    -------
    Turn a magnitude rendering that is not a plain digit run into the exact zero-based index
    of the offender, without echoing the run itself.

    Parameters
    ----------
    text : str
        Candidate run expected to contain only the characters ``0`` through ``9``.

    Returns
    -------
    int | None
        The zero-based index of the first non-digit, or ``None`` when every character is an
        ASCII digit.

    Raises
    ------
    None.
    """
    # WHY (Alternatives Considered): the sibling display codec compiles a regular expression
    #   for this check because it validates a caller-supplied span on every single decode. Here
    #   the only strings scanned are ones this module produced itself from a Decimal, on the
    #   encode path alone, so a plain scan does the same work without importing ``re`` at all --
    #   which keeps the import list to two standard-library modules and makes the
    #   standard-library-only claim above checkable at a glance.
    for index, character in enumerate(text):
        if character not in _DECIMAL_DIGITS:
            return index
    return None


def _require_geometry(
    int_digits: int,
    dec_digits: int,
    signed: bool,
    kind: Kind,
    field: FieldSpec | None,
) -> tuple[int, int]:
    """Validate computational geometry and return its digit count and byte width.

    Purpose
    -------
    Guard the shared public parameters once, delegate the width rule to the module that owns
    it, and ensure any supplied descriptor describes the same span the digit counts do.

    Parameters
    ----------
    int_digits : int
        Number of declared digit positions before the implied decimal point.
    dec_digits : int
        Number of declared digit positions after the implied decimal point.
    signed : bool
        Whether the picture clause carries a leading ``S``.
    kind : Kind
        The regime whose width rule applies, either :attr:`Kind.PACKED` or
        :attr:`Kind.BINARY`.
    field : FieldSpec | None
        Optional descriptor whose declared length must equal the derived width.

    Returns
    -------
    tuple[int, int]
        The total digit count and the field's physical byte width, in that order.

    Raises
    ------
    TypeError
        If either digit count is not an integer, if ``signed`` is not a boolean, or if
        ``field`` is neither a :class:`FieldSpec` nor ``None``.
    PackedDecimalError
        If the digit counts do not describe an admissible computational field, or if ``kind``
        is not a computational regime.
    PackedSpanWidthError
        If the digit counts disagree with the supplied descriptor's declared length.
    """
    if field is not None and not isinstance(field, FieldSpec):
        raise TypeError(f"field must be FieldSpec or None, not {type(field).__name__}")
    if kind not in _COMPUTATIONAL_KINDS:
        raise PackedDecimalError(
            f"the computational codec handles {Kind.PACKED.name} and {Kind.BINARY.name} only,"
            f" not {kind.name}"
        )
    for name, count in (("int_digits", int_digits), ("dec_digits", dec_digits)):
        # WHY (Assumptions): ``bool`` is an ``int`` subclass, so a digit count of ``True`` would
        #   otherwise pass as one and produce a one-digit field with no complaint. Rejecting it
        #   here keeps the width rule from being handed a value that means a flag.
        if isinstance(count, bool) or not isinstance(count, int):
            raise TypeError(f"{name} must be an int digit count, not {type(count).__name__}")
    if not isinstance(signed, bool):
        raise TypeError(f"signed must be bool, not {type(signed).__name__}")

    # WHY (Assumptions): the width comes from ``layouts`` and its digit-count validation comes
    #   with it, so the admissible range of one to eighteen positions is enforced in one place
    #   for all three regimes. Its LayoutError is translated rather than propagated because a
    #   caller of this codec guards against this module's error type, and a geometry fault
    #   reaching it under a different name would escape that guard.
    widths = {Kind.PACKED: packed_width, Kind.BINARY: binary_width}
    try:
        width = widths[kind](int_digits, dec_digits)
    except ValueError as exc:
        raise PackedDecimalError(
            f"{kind.name} geometry must declare between one and eighteen digit positions with no"
            f" negative count; int_digits={int_digits}, dec_digits={dec_digits}"
        ) from exc

    if field is not None and width != field.length:
        raise PackedSpanWidthError(
            _failure(
                f"{kind.name} geometry implies {width} bytes but the descriptor declares"
                f" {field.length}",
                kind,
                width,
                field,
            )
        )
    return int_digits + dec_digits, width


def _require_buffer(raw: object, kind: Kind, width: int, field: FieldSpec | None) -> bytes:
    """Require raw bytes at the computational codec boundary and normalise the buffer type.

    Purpose
    -------
    Reject character input before any nibble is read, so that cp037 conversion stays
    exclusively in ``carddemo_migration.copybook.ebcdic_codec`` and a packed span can never
    arrive having already been through a text decoder.

    Parameters
    ----------
    raw : object
        Candidate field or record buffer.
    kind : Kind
        The storage regime being decoded, named in a failure message.
    width : int
        The field's declared byte width, named in a failure message.
    field : FieldSpec | None
        Optional descriptor included in a sensitive-safe failure message.

    Returns
    -------
    bytes
        An immutable copy of the supplied buffer, so that every nibble read downstream
        indexes one uniform type.

    Raises
    ------
    TypeError
        If ``raw`` is a :class:`str`, or is not one of :class:`bytes`, :class:`bytearray` or
        :class:`memoryview`.
    """
    # WHY (Assumptions): ``str`` is refused by name and with its own message rather than
    #   falling through to the generic one, because it is the single most likely wrong argument
    #   and the reason it is wrong is not obvious. A packed span is nibbles: the seven bytes of
    #   a PIC S9(10)V99 COMP-3 zero are six NULs and a 0x0C, and a character decoder maps every
    #   byte it cannot interpret to a replacement character of the same width -- so the field
    #   keeps its length, every later offset still looks valid, and only the amount is wrong.
    #   The parity oracle's own helper records the same failure mode from the opposite
    #   direction, at tests/helpers/localstack_setup.py around line 1068.
    if isinstance(raw, str):
        raise TypeError(
            _failure(
                "computational decoding requires raw bytes read in binary mode, not str;"
                " a character decoder replaces the NUL and sign nibbles a packed span contains",
                kind,
                width,
                field,
            )
        )
    if not isinstance(raw, _BUFFER_TYPES):
        raise TypeError(
            _failure(
                "computational decoding requires bytes, bytearray or memoryview, not"
                f" {type(raw).__name__}",
                kind,
                width,
                field,
            )
        )
    return bytes(raw)


def _require_span(raw: bytes, width: int, kind: Kind, field: FieldSpec | None) -> None:
    """Require a span to be exactly the byte width its declaration implies.

    Purpose
    -------
    Refuse a short or long slice before any nibble is interpreted, which is the check that
    separates a geometry fault from a content fault and gives the former its own error type.

    Parameters
    ----------
    raw : bytes
        The span presented for decoding.
    width : int
        The byte width the field's digit counts and regime imply.
    kind : Kind
        The storage regime being decoded, named in a failure message.
    field : FieldSpec | None
        Optional descriptor included in a sensitive-safe failure message.

    Returns
    -------
    None
        The function returns nothing and is called for its validation alone.

    Raises
    ------
    PackedSpanWidthError
        If ``raw`` is not exactly ``width`` bytes long.
    """
    if len(raw) != width:
        raise PackedSpanWidthError(
            _failure(
                f"{kind.name} span expected {width} bytes but received {len(raw)}",
                kind,
                width,
                field,
            )
        )


def _pad_nibbles(digits: int, width: int) -> int:
    """Return how many leading nibbles of a packed field are unused padding.

    Purpose
    -------
    Derive the surplus from the field's geometry, which is the only formulation of this that
    cannot be stated the wrong way round.

    Parameters
    ----------
    digits : int
        The total number of declared digit positions.
    width : int
        The field's physical byte width.

    Returns
    -------
    int
        The number of unused nibbles at the FRONT of the field, which is zero or one.

    Raises
    ------
    None.
    """
    # WHY (Assumptions): the surplus is computed from the geometry rather than from the parity
    #   of the digit count, because the geometry is arithmetic while the parity is a claim that
    #   is easy to state backwards -- and stating it backwards is not a harmless slip. A field
    #   occupies ``width * 2`` nibbles and uses ``digits + 1`` of them, so the surplus is
    #   whatever is left, and it is at most one. For the record the surplus exists when the digit
    #   count is EVEN: twelve digits occupy seven bytes, which is fourteen nibbles for thirteen
    #   used, so one pads; whereas eleven digits occupy six bytes, which is twelve nibbles for
    #   twelve used, so none pads. The odd case is settled by observation as well as by
    #   arithmetic, because the reference compiler lays PIC S9(05) COMP-3 holding 12345 down as
    #   the nibbles 1 2 3 4 5 C, whose LEADING nibble is a digit and not a pad. Deriving the
    #   count is correct for either parity, and both ways of getting the parity wrong were
    #   measured rather than assumed. A decoder that assumed no pad on a field that pads reads
    #   the pad as a leading digit and drops the true low-order one, so the seven bytes
    #   0x00000015800C -- the balance of account 2 in the real export dataset -- come back as
    #   15.80 instead of 158.00, wrong by a FACTOR OF TEN and entirely plausible. A decoder that
    #   assumed a pad on a field that does not pad pulls the sign nibble into the digit window
    #   instead, which the digit-range check below refuses outright. So one direction of the
    #   mistake corrupts silently and the other fails loudly, which is precisely why neither is
    #   left to a parity claim. The Java parity codec derives the count the same way, for the
    #   same reason.
    return width * _NIBBLES_PER_BYTE - (digits + 1)


def _nibble_at(raw: bytes, index: int) -> int:
    """Read one nibble of a span, counting from the high nibble of its first byte.

    Purpose
    -------
    Give the decode loop a single addressing convention for a storage form whose unit is half
    a byte, so that the loop expresses digit positions rather than byte-and-shift arithmetic.

    Parameters
    ----------
    raw : bytes
        The span being decoded.
    index : int
        The zero-based nibble position within the span, where zero is the HIGH nibble of the
        span's first byte.

    Returns
    -------
    int
        The nibble value, between zero and fifteen.

    Raises
    ------
    IndexError
        If ``index`` addresses a nibble beyond the end of ``raw``. Callers reach this function
        only after :func:`_require_span` has fixed the span's length, so the condition
        indicates a defect in this module rather than in its input.
    """
    value = raw[index // _NIBBLES_PER_BYTE]
    if index % _NIBBLES_PER_BYTE == 0:
        return value >> _HIGH_NIBBLE_SHIFT
    return value & _NIBBLE_MASK


def _set_nibble(target: bytearray, index: int, nibble: int) -> None:
    """Write one nibble of a span, counting from the high nibble of its first byte.

    Purpose
    -------
    Provide the exact inverse of :func:`_nibble_at`, so the encode loop and the decode loop
    address the same positions and the round-trip law can hold by construction rather than by
    coincidence.

    Parameters
    ----------
    target : bytearray
        The mutable span being built, already sized to the field's declared width.
    index : int
        The zero-based nibble position within the span.
    nibble : int
        The nibble value to store, between zero and fifteen.

    Returns
    -------
    None
        The function mutates ``target`` in place and returns nothing.

    Raises
    ------
    IndexError
        If ``index`` addresses a nibble beyond the end of ``target``. Callers size the buffer
        from the width rule before calling, so the condition indicates a defect in this module
        rather than in its input.
    """
    byte_index = index // _NIBBLES_PER_BYTE
    existing = target[byte_index]
    if index % _NIBBLES_PER_BYTE == 0:
        target[byte_index] = (nibble << _HIGH_NIBBLE_SHIFT) | (existing & _NIBBLE_MASK)
    else:
        target[byte_index] = (existing & (_NIBBLE_MASK << _HIGH_NIBBLE_SHIFT)) | nibble


def _assemble(digit_text: str, negative: bool, dec_digits: int) -> Decimal:
    """Build the decoded value from its digit run, its sign and its declared decimal places.

    Purpose
    -------
    Place the implied decimal point exactly ``dec_digits`` positions from the right and attach
    the sign, producing a value whose scale is the field's declared fractional width.

    Parameters
    ----------
    digit_text : str
        The field's digits as ASCII characters, most significant first, with no sign and no
        physical decimal point.
    negative : bool
        Whether the field's sign representation indicated a negative value.
    dec_digits : int
        Number of declared digit positions after the implied decimal point.

    Returns
    -------
    decimal.Decimal
        The exact value, whose exponent is ``-dec_digits``.

    Raises
    ------
    None.
    """
    integer_boundary = len(digit_text) - dec_digits
    integer_part = digit_text[:integer_boundary] or _PAD_DIGIT

    # WHY (Trade-offs): the value is built from one explicitly assembled decimal string rather
    #   than by integer arithmetic on the accumulated digits followed by a scaling operation. The
    #   string costs one allocation per field and buys two properties arithmetic does not. It is
    #   exact for the full twelve digits of the widest packed money field with no intermediate
    #   that could overflow, and it is independent of the ambient decimal context, so a process
    #   running under a reduced precision cannot make the same span decode to a different value
    #   than another process does. ``Decimal.scaleb`` would have been shorter and does consult
    #   that context.
    number = f"{integer_part}.{digit_text[integer_boundary:]}" if dec_digits else integer_part

    # WHY (Assumptions): keeping exactly ``dec_digits`` characters after the point is what
    #   preserves the declared scale, and the result is deliberately never passed through
    #   ``normalize()``. Normalising would make 1020.00 and 1020 compare equal while discarding
    #   the exponent -- two values that encode to different bytes would then look identical, and
    #   the golden-master comparison that decides parity is a byte comparison.
    # WHY (Assumptions): the sign is applied only when at least one digit is non-zero, which is
    #   what keeps a negatively-signed zero out of the result. Python's Decimal, unlike the
    #   BigDecimal the Java parity codec uses, CAN carry a negative zero, so this normalisation
    #   has to be performed rather than inherited: without it the same span would decode to
    #   Decimal('-0.00') here and to zero there, the two languages would disagree on one record,
    #   and a re-encode taking its sign from the value's own flag would emit 0x0D where Java
    #   emits 0x0C. The sibling display codec normalises its closing-brace zero identically.
    if negative and any(digit != _PAD_DIGIT for digit in digit_text):
        number = f"-{number}"
    return Decimal(number)


def _require_capacity(
    magnitude: str,
    digits: int,
    kind: Kind,
    width: int,
    field: FieldSpec | None,
) -> str:
    """Require a magnitude to fit the declared digit positions and left-pad it to them.

    Purpose
    -------
    Enforce the overflow half of the lossless-or-raise contract and return the magnitude in
    the fixed-width form both encode paths and the binary decode path need.

    Parameters
    ----------
    magnitude : str
        The absolute value's digits as ASCII characters, most significant first.
    digits : int
        The total number of declared digit positions the field provides.
    kind : Kind
        The storage regime being reported in a failure message.
    width : int
        The field's declared byte width, reported in a failure message.
    field : FieldSpec | None
        Optional descriptor included in a sensitive-safe failure message.

    Returns
    -------
    str
        The magnitude left-padded with zeros to exactly ``digits`` characters.

    Raises
    ------
    PackedDecimalError
        If ``magnitude`` is not a plain ASCII digit run, or if it needs more digit positions
        than the field declares.
    """
    # WHY (Assumptions): the run is revalidated here rather than trusted, even though every
    #   caller produces it from a Decimal that has already been scaled to an integer. An
    #   unexpected exponent form -- a value rendered as 5E+2 rather than as 500 -- must raise
    #   visibly; coercing it through ``int()`` instead would succeed and would silently drop a
    #   declared decimal position while still returning a field of exactly the right width.
    invalid_index = _first_non_digit(magnitude)
    if invalid_index is not None:
        raise PackedDecimalError(
            _failure(
                "exact decimal scaling did not produce a plain digit run; refusing an unchecked"
                f" exponent form at zero-based index {invalid_index}",
                kind,
                width,
                field,
            )
        )
    if len(magnitude) > digits:
        raise PackedDecimalError(
            _failure(
                f"value needs {len(magnitude)} digit positions but the field declares {digits}",
                kind,
                width,
                field,
            )
        )
    return magnitude.rjust(digits, _PAD_DIGIT)


def _require_encodable(
    value: Decimal | int | str,
    int_digits: int,
    dec_digits: int,
    signed: bool,
    kind: Kind,
    width: int,
    field: FieldSpec | None,
) -> tuple[str, bool]:
    """Validate a value for exact encoding and return its padded digits and its sign.

    Purpose
    -------
    Perform every check both encode paths share -- admissible input type, finiteness, sign
    against the declared contract, scale within the declared decimal places and magnitude
    within the declared digit positions -- and hand back the value in the one form the packed
    and binary writers both consume.

    Parameters
    ----------
    value : decimal.Decimal | int | str
        Exact human value to encode; binary floating-point values and booleans are refused.
    int_digits : int
        Number of declared digit positions before the implied decimal point.
    dec_digits : int
        Number of declared digit positions after the implied decimal point.
    signed : bool
        Whether the picture clause carries a leading ``S``.
    kind : Kind
        The storage regime being encoded, reported in a failure message.
    width : int
        The field's declared byte width, reported in a failure message.
    field : FieldSpec | None
        Optional descriptor included in a sensitive-safe failure message.

    Returns
    -------
    tuple[str, bool]
        The magnitude's digits left-padded to ``int_digits + dec_digits`` characters, and
        whether the value is strictly negative, in that order.

    Raises
    ------
    TypeError
        If ``value`` is a boolean, a binary floating-point number, or any type other than
        :class:`Decimal`, :class:`int` or :class:`str`.
    PackedDecimalError
        If a text value is not a decimal number, the value is not finite, a negative value is
        supplied for an unsigned field, the value carries a scale beyond ``dec_digits``, or
        the magnitude exceeds the declared digit positions.
    """
    digits = int_digits + dec_digits

    # WHY (Assumptions): a binary floating-point value cannot represent ten cents exactly, and
    #   ``bool`` is an ``int`` subclass despite not being an amount at all. Refusing both before
    #   any conversion stops 0.10 from carrying a hidden approximation into a money column and
    #   stops ``True`` from silently becoming one unit of currency.
    if isinstance(value, bool):
        raise TypeError(
            _failure(
                "computational encoding does not accept bool; pass Decimal, int or str",
                kind,
                width,
                field,
            )
        )
    if isinstance(value, float):
        raise TypeError(
            _failure(
                "computational encoding does not accept binary floating point because it cannot"
                " represent decimal cents exactly; pass Decimal, int or str",
                kind,
                width,
                field,
            )
        )
    if isinstance(value, Decimal):
        decimal_value = value
    elif isinstance(value, int):
        decimal_value = Decimal(value)
    elif isinstance(value, str):
        try:
            decimal_value = Decimal(value)
        except InvalidOperation as exc:
            raise PackedDecimalError(
                _failure(
                    "computational encoding received text that is not a decimal number",
                    kind,
                    width,
                    field,
                )
            ) from exc
    else:
        raise TypeError(
            _failure(
                f"computational encoding expects Decimal, int or str, not {type(value).__name__}",
                kind,
                width,
                field,
            )
        )

    if not decimal_value.is_finite():
        raise PackedDecimalError(
            _failure(
                "computational encoding requires a finite decimal value",
                kind,
                width,
                field,
            )
        )

    # WHY (Assumptions): the quantum is constructed from Decimal's exact tuple form rather than
    #   through arithmetic in the caller's active context, so a low ambient precision cannot
    #   change the target exponent and make the same amount encode differently in two batch
    #   processes.
    quantum = Decimal((0, (1,), -dec_digits))

    # WHY (Trade-offs): encoding is lossless or it raises. Silent truncation and silent rounding
    #   were both rejected, because a codec that picks a rounding rule takes a business decision
    #   that never reaches the audit trail -- and the scale and mode for money are declared in
    #   the target's own money type, where the decision is visible. Quantising privately and then
    #   comparing the result against the input accepts a redundant trailing zero, which changes
    #   no value, and refuses any non-zero fraction the declared scale cannot store.
    try:
        with localcontext() as context:
            context.prec = digits + _QUANTIZE_PRECISION_MARGIN
            quantized = decimal_value.quantize(quantum)
            scaled = quantized.copy_abs().scaleb(dec_digits)
    except InvalidOperation as exc:
        raise PackedDecimalError(
            _failure(
                f"value cannot be represented exactly with int_digits={int_digits} and"
                f" dec_digits={dec_digits}",
                kind,
                width,
                field,
            )
        ) from exc

    if quantized != decimal_value:
        raise PackedDecimalError(
            _failure(
                f"value carries non-zero precision beyond the {dec_digits} decimal positions the"
                " field declares, and reducing it here would round silently",
                kind,
                width,
                field,
            )
        )

    # WHY (Trade-offs): the sign is taken from an ordering comparison and not from Decimal's own
    #   sign flag, which deliberately makes a negative zero non-negative. A value of
    #   Decimal('-0.00') therefore encodes with the positive sign nibble, which is what makes the
    #   documented round-trip exception a single well-defined normalisation rather than a
    #   behaviour that depends on how the caller happened to construct its zero -- and what keeps
    #   this codec emitting the same bytes as the Java parity codec, whose decimal type has no
    #   negative zero to consult.
    negative = quantized < 0
    if not signed and negative:
        raise PackedDecimalError(
            _failure(
                "cannot encode a negative value in a field whose picture clause declares no sign",
                kind,
                width,
                field,
            )
        )

    return _require_capacity(str(scaled), digits, kind, width, field), negative


def _decode_sign_nibble(
    raw: bytes,
    width: int,
    signed: bool,
    field: FieldSpec | None,
) -> bool:
    """Read and validate the sign nibble in the low nibble of a packed field's final byte.

    Purpose
    -------
    Classify the sign, refuse every value the reference compiler does not emit, and
    cross-check the value found against the sign contract the caller declared.

    Parameters
    ----------
    raw : bytes
        The packed span being decoded, already confirmed to be ``width`` bytes long.
    width : int
        The field's declared byte width.
    signed : bool
        Whether the picture clause carries a leading ``S``.
    field : FieldSpec | None
        Optional descriptor included in a sensitive-safe failure message.

    Returns
    -------
    bool
        ``True`` when the sign nibble is the signed negative one, ``False`` otherwise.

    Raises
    ------
    PackedDecimalError
        If the sign position holds a digit, if it holds an alternate sign nibble this codec
        does not admit, or if the nibble contradicts ``signed``.
    """
    sign = raw[width - 1] & _NIBBLE_MASK

    # WHY (Assumptions): a value below 0x0A in the sign position is a digit, and a digit here
    #   means one of two specific things -- the field is zoned display rather than packed, or the
    #   read is off by one nibble. Naming that case separately is worth the extra branch because
    #   it points at the actual defect instead of reporting an unrecognised sign, and those two
    #   defects have entirely different remedies.
    if sign < _LOWEST_SIGN_NIBBLE:
        raise PackedDecimalError(
            _failure(
                "packed span holds a digit where its sign nibble must be, so the field is either"
                " zoned display rather than packed or misaligned by one nibble",
                Kind.PACKED,
                width,
                field,
                _nibble_detail(sign, field),
            )
        )
    if sign not in (_SIGN_SIGNED_POSITIVE, _SIGN_SIGNED_NEGATIVE, _SIGN_UNSIGNED):
        raise PackedDecimalError(
            _failure(
                "packed span holds an alternate sign nibble this codec does not admit; the"
                " reference corpus uses only the signed positive, signed negative and unsigned"
                " forms",
                Kind.PACKED,
                width,
                field,
                _nibble_detail(sign, field),
            )
        )

    # WHY (Alternatives Considered): the nibble is cross-checked against the caller's declared
    #   sign contract rather than merely classified, and this is the packed analogue of the
    #   sibling codec's refusal of a plain trailing digit in a signed display field. Both exist
    #   for one reason: a mismatch means the descriptor the caller passed and the bytes on disk
    #   describe DIFFERENT fields, so decoding either one produces a value read against a layout
    #   that does not match the data. Classifying without cross-checking was the alternative and
    #   would accept both cases and report neither -- and the value it returned would be the
    #   right width, the right type and quite plausible.
    if signed and sign == _SIGN_UNSIGNED:
        raise PackedDecimalError(
            _failure(
                "packed span is declared with a sign but holds the unsigned sign nibble, so the"
                " declaration and the data describe different fields",
                Kind.PACKED,
                width,
                field,
            )
        )
    if not signed and sign != _SIGN_UNSIGNED:
        raise PackedDecimalError(
            _failure(
                "packed span is declared without a sign but holds a signed sign nibble, so the"
                " declaration and the data describe different fields",
                Kind.PACKED,
                width,
                field,
                _nibble_detail(sign, field),
            )
        )
    return sign == _SIGN_SIGNED_NEGATIVE


def _sign_nibble_for(signed: bool, negative: bool) -> int:
    """Return the sign nibble a value takes when written into a packed field.

    Purpose
    -------
    Select one of the three nibbles the reference compiler emits, from the field's declared
    sign contract and the sign the encode path has already established.

    Parameters
    ----------
    signed : bool
        Whether the picture clause carries a leading ``S``.
    negative : bool
        Whether the value is strictly negative, as decided by an ordering comparison.

    Returns
    -------
    int
        The signed negative nibble for a negative value, the signed positive nibble for any
        other value in a signed field, and the unsigned nibble in an unsigned field.

    Raises
    ------
    None.
    """
    # WHY (Assumptions): a field declared without a sign takes the unsigned nibble regardless of
    #   the value, which is why the sign contract is consulted before the sign. Emitting 0x0C for
    #   a non-negative value in an unsigned field would produce a span this module's own decoder
    #   then refuses as a declaration-versus-data mismatch, so the encode and decode contracts
    #   would disagree about the very bytes one of them wrote.
    if not signed:
        return _SIGN_UNSIGNED
    return _SIGN_SIGNED_NEGATIVE if negative else _SIGN_SIGNED_POSITIVE


def _field_geometry(field: FieldSpec, kind: Kind) -> tuple[int, int, bool]:
    """Derive the core codec parameters from one computational field descriptor.

    Purpose
    -------
    Convert a descriptor to the ``int_digits``, ``dec_digits`` and ``signed`` triple the
    span-oriented entry points take, refusing every regime that is not the expected one before
    any byte is inspected.

    Parameters
    ----------
    field : FieldSpec
        Descriptor for a :attr:`Kind.PACKED` or :attr:`Kind.BINARY` field.
    kind : Kind
        The regime the calling entry point handles, which ``field`` must declare.

    Returns
    -------
    tuple[int, int, bool]
        Integer positions, fractional positions and sign contract for the field.

    Raises
    ------
    TypeError
        If ``field`` is not a :class:`FieldSpec`.
    PackedDecimalError
        If ``field`` declares a regime other than ``kind``.
    PackedSpanWidthError
        If the descriptor's declared length disagrees with its derived computational width.
    """
    if not isinstance(field, FieldSpec):
        raise TypeError(f"field must be FieldSpec, not {type(field).__name__}")
    if field.kind is not kind:
        # WHY (Assumptions): the regime is matched exactly and not merely checked for membership
        #   in the computational set, because packed and binary are different byte layouts of the
        #   same picture clause. app/cpy/CVEXPORT.cpy declares PIC S9(10)V99 at line 50 as seven
        #   packed bytes and at line 57 as eight binary bytes, so reading one entry point's span
        #   with the other's rule reads the wrong number of bytes and then misinterprets them.
        raise PackedDecimalError(
            _failure(
                f"the {kind.name} entry points cannot process {field.kind.name} storage",
                field.kind,
                field.length,
                field,
            )
        )
    geometry = (field.int_digits, field.dec_digits, field.signed)
    _require_geometry(*geometry, kind, field)
    return geometry


def _require_record_reach(
    record: bytes,
    field: FieldSpec,
    kind: Kind,
) -> bytes:
    """Slice one field's declared span out of a record, refusing a record that ends too soon.

    Purpose
    -------
    Anchor a field-oriented read on the descriptor's declared half-open interval and report a
    truncated record as the geometry fault it is, rather than returning a short slice for the
    span check to reject with less information.

    Parameters
    ----------
    record : bytes
        The full fixed-width record containing the declared field span.
    field : FieldSpec
        The descriptor supplying the offset and the width.
    kind : Kind
        The storage regime being decoded, reported in a failure message.

    Returns
    -------
    bytes
        Exactly ``record[field.start:field.end]``.

    Raises
    ------
    PackedSpanWidthError
        If ``record`` ends before ``field.end``.
    """
    if len(record) < field.end:
        available = max(len(record) - field.start, 0)
        raise PackedSpanWidthError(
            _failure(
                f"record ends at offset {len(record)}, before the field's exclusive end"
                f" {field.end}; only {available} of {field.length} bytes are available",
                kind,
                field.length,
                field,
            )
        )

    # WHY (Assumptions): the read is anchored on the declared offset and never on a scan for
    #   something that looks like a packed field. A scan cannot work here at all: unlike a display
    #   field, a packed span has no printable form to recognise, and any seven bytes whose last
    #   low nibble happens to be 0x0C decode as a valid amount. The declared offset is the only
    #   evidence of where a field begins, which is why layouts owns it and this module takes it.
    return record[field.start : field.end]


def decode_packed(
    raw: bytes | bytearray | memoryview,
    int_digits: int,
    dec_digits: int,
    signed: bool,
    *,
    field: FieldSpec | None = None,
) -> Decimal:
    """Decode one packed-decimal span to an exact, scale-stable decimal.

    Purpose
    -------
    Interpret one already-sliced COBOL ``USAGE COMP-3`` field: validate that the span is the
    width its digit counts imply, that its leading pad nibble is zero where the geometry
    leaves one, and that every digit nibble is a digit; read the sign from the low nibble of
    the final byte and cross-check it against the declared contract; and place the implied
    decimal point exactly ``dec_digits`` positions from the right.

    Parameters
    ----------
    raw : bytes | bytearray | memoryview
        The exact bytes of one field, read in BINARY mode and never passed through a
        character decoder.
    int_digits : int
        Number of declared digit positions before the implied decimal point.
    dec_digits : int
        Number of declared digit positions after the implied decimal point.
    signed : bool
        ``True`` when the picture clause carries a leading ``S``, in which case the sign
        nibble must be ``0x0C`` or ``0x0D``; ``False`` when it does not, in which case the
        sign nibble must be ``0x0F``.
    field : FieldSpec | None
        Optional descriptor used only to cross-check the width and to render safe
        diagnostics.

    Returns
    -------
    decimal.Decimal
        The exact decoded value, whose exponent is ``-dec_digits``. A negatively-signed zero
        is deliberately normalised to an unsigned zero, for parity with the Java codec.

    Raises
    ------
    PackedSpanWidthError
        If ``raw`` is not exactly the byte width ``int_digits`` and ``dec_digits`` imply, or
        if those counts disagree with ``field.length``.
    PackedDecimalError
        If the geometry is inadmissible, the leading pad nibble is non-zero, a digit position
        holds a nibble above nine, the sign position holds a digit or an alternate sign
        nibble, or the sign nibble contradicts ``signed``.
    TypeError
        If ``raw`` is a :class:`str` or any type other than a bytes-like buffer, if the
        geometry arguments have the wrong types, or if ``field`` is neither a
        :class:`FieldSpec` nor ``None``.
    """
    digits, width = _require_geometry(int_digits, dec_digits, signed, Kind.PACKED, field)
    span = _require_buffer(raw, Kind.PACKED, width, field)
    _require_span(span, width, Kind.PACKED, field)

    pad_nibbles = _pad_nibbles(digits, width)
    if pad_nibbles:
        # WHY (Assumptions): the pad nibble is validated rather than skipped, and checking that
        #   it is zero is the cheapest detector of a displaced read this codec has. A real digit
        #   where the pad belongs means the span did not begin where the caller thinks it did,
        #   and accepting it shifts every true digit one place while prepending a foreign one:
        #   measured on the account-2 balance from the real export dataset, a leading nibble of 9
        #   turns 158.00 into 9000000015.80 -- a number that is well formed, that raises nothing
        #   anywhere downstream, and that the golden-master comparison catches only after the
        #   fact. Every one of the fifty account records in that dataset carries a zero here in
        #   its seven-byte PIC S9(10)V99 COMP-3 balance, so the check costs nothing on correct
        #   data and is the only thing standing between a one-nibble slip and a corrupt ledger.
        pad = _nibble_at(span, 0)
        if pad:
            raise PackedDecimalError(
                _failure(
                    f"packed span must begin with a zero pad nibble, because its {digits} digit"
                    " positions leave one nibble unused at the FRONT of the field",
                    Kind.PACKED,
                    width,
                    field,
                    _nibble_detail(pad, field),
                )
            )

    # WHY (Assumptions): the digits are read high nibble then low nibble across each byte from
    #   the pad offset forward, which is the order the storage form uses. Reading them from the
    #   back instead would be correct only for a field with no pad, so it would reverse nothing
    #   and shift everything: PIC S9(10)V99 COMP-3, the widest money field in the authorization
    #   segments, is exactly a field that pads.
    digit_characters = []
    for nibble_index in range(pad_nibbles, pad_nibbles + digits):
        nibble = _nibble_at(span, nibble_index)
        if nibble > _MAX_DIGIT_NIBBLE:
            raise PackedDecimalError(
                _failure(
                    "packed span holds a non-digit nibble at digit position"
                    f" {nibble_index - pad_nibbles + 1} of {digits}, so the field is either"
                    " misaligned or not packed decimal at all",
                    Kind.PACKED,
                    width,
                    field,
                    _nibble_detail(nibble, field),
                )
            )
        digit_characters.append(_DECIMAL_DIGITS[nibble])

    negative = _decode_sign_nibble(span, width, signed, field)
    return _assemble("".join(digit_characters), negative, dec_digits)


def encode_packed(
    value: Decimal | int | str,
    int_digits: int,
    dec_digits: int,
    signed: bool,
    *,
    field: FieldSpec | None = None,
) -> bytes:
    """Encode an exact value as one fixed-width packed-decimal span.

    Purpose
    -------
    Quantise without rounding, lay the digits down two per byte from the front into the nibble
    positions the geometry leaves free, and write the sign nibble into the low nibble of the
    final byte. This is the exact inverse of :func:`decode_packed`, with the single documented
    exception that a zero always takes the positive sign nibble.

    Parameters
    ----------
    value : decimal.Decimal | int | str
        Exact human value to encode; binary floating-point values and booleans are refused.
    int_digits : int
        Number of declared digit positions before the implied decimal point.
    dec_digits : int
        Number of declared digit positions after the implied decimal point.
    signed : bool
        ``True`` to emit ``0x0C`` or ``0x0D`` according to the value's sign, or ``False`` to
        emit ``0x0F`` and reject a negative value.
    field : FieldSpec | None
        Optional descriptor used only to cross-check the width and to render safe
        diagnostics.

    Returns
    -------
    bytes
        Exactly the byte width ``int_digits`` and ``dec_digits`` imply, holding two digits per
        byte with the sign in the low nibble of the last byte.

    Raises
    ------
    PackedSpanWidthError
        If the digit counts disagree with ``field.length``.
    PackedDecimalError
        If a text value is not a decimal number, the value is not finite, the geometry is
        inadmissible, the value carries a scale beyond ``dec_digits``, its magnitude exceeds
        the declared digit positions, or it is negative in a field declared without a sign.
    TypeError
        If ``value`` is a boolean, a binary floating-point number, or any type other than
        :class:`Decimal`, :class:`int` or :class:`str`; also if the geometry arguments or
        ``field`` have the wrong types.
    """
    digits, width = _require_geometry(int_digits, dec_digits, signed, Kind.PACKED, field)
    digit_text, negative = _require_encodable(
        value, int_digits, dec_digits, signed, Kind.PACKED, width, field
    )

    pad_nibbles = _pad_nibbles(digits, width)
    target = bytearray(width)

    # WHY (Assumptions): the digits are laid down from the LEFT into the nibble positions the pad
    #   leaves free, which mirrors the decode loop exactly and is what makes the round trip
    #   reproduce the original bytes rather than merely the original value. Writing them from the
    #   right instead would be correct only when nothing pads, so it would silently shift every
    #   even-digit-count field by one nibble and leave a stray digit where the pad belongs -- and
    #   this module's own decoder would then refuse the span it had just written.
    for digit_index, character in enumerate(digit_text):
        _set_nibble(target, pad_nibbles + digit_index, int(character))

    _set_nibble(
        target,
        width * _NIBBLES_PER_BYTE - 1,
        _sign_nibble_for(signed, negative),
    )

    # WHY (Assumptions): an immutable copy is returned rather than the working buffer, so a
    #   caller cannot mutate a span this codec has already validated and then present it back for
    #   a round-trip comparison that would silently pass against altered bytes.
    return bytes(target)


def decode_binary(
    raw: bytes | bytearray | memoryview,
    int_digits: int,
    dec_digits: int,
    signed: bool,
    *,
    field: FieldSpec | None = None,
) -> Decimal:
    """Decode one binary span to an exact, scale-stable decimal.

    Purpose
    -------
    Interpret one already-sliced COBOL ``USAGE COMP`` field as a big-endian two's complement
    whole number of the width its digit count selects, then move the implied decimal point
    ``dec_digits`` positions to the left without performing a division.

    Parameters
    ----------
    raw : bytes | bytearray | memoryview
        The exact bytes of one field, read in BINARY mode and never passed through a
        character decoder.
    int_digits : int
        Number of declared digit positions before the implied decimal point.
    dec_digits : int
        Number of declared digit positions after the implied decimal point.
    signed : bool
        ``True`` when the picture clause carries a leading ``S``, in which case the leading
        bit is a sign bit; ``False`` when it does not, in which case every bit is magnitude.
    field : FieldSpec | None
        Optional descriptor used only to cross-check the width and to render safe
        diagnostics.

    Returns
    -------
    decimal.Decimal
        The exact decoded value, whose exponent is ``-dec_digits``.

    Raises
    ------
    PackedSpanWidthError
        If ``raw`` is not exactly 2, 4 or 8 bytes as the digit count dictates, or if the digit
        counts disagree with ``field.length``.
    PackedDecimalError
        If the geometry is inadmissible, or if the stored value needs more digit positions
        than the field declares.
    TypeError
        If ``raw`` is a :class:`str` or any type other than a bytes-like buffer, if the
        geometry arguments have the wrong types, or if ``field`` is neither a
        :class:`FieldSpec` nor ``None``.
    """
    digits, width = _require_geometry(int_digits, dec_digits, signed, Kind.BINARY, field)
    span = _require_buffer(raw, Kind.BINARY, width, field)
    _require_span(span, width, Kind.BINARY, field)

    # WHY (Assumptions): ``COMP`` and ``BINARY`` are the SAME usage under two spellings, and both
    #   occur in the language, so one decode path serves both and neither spelling gets a rule of
    #   its own. Handling only one of them would leave every field declared with the other sized
    #   by the display rule instead -- eleven digit positions read as eleven bytes rather than
    #   eight, which is a three-byte shift of everything after it.
    stored = int.from_bytes(span, _BINARY_BYTE_ORDER, signed=signed)
    negative = stored < 0

    # WHY (Assumptions): the magnitude's digit count is checked against the field's declared
    #   positions, which is what catches a span whose bytes hold more than the picture clause can
    #   describe. This subsumes the overflow guard the Java parity codec needs explicitly: that
    #   codec accumulates into a fixed sixty-four-bit integer and so has to refuse an unsigned
    #   eight-byte value with its leading bit set, whereas a Python integer simply grows -- and
    #   such a value is at least nineteen digits, which no field here declares.
    magnitude = _require_capacity(
        str(-stored if negative else stored), digits, Kind.BINARY, width, field
    )
    return _assemble(magnitude, negative, dec_digits)


def encode_binary(
    value: Decimal | int | str,
    int_digits: int,
    dec_digits: int,
    signed: bool,
    *,
    field: FieldSpec | None = None,
) -> bytes:
    """Encode an exact value as one fixed-width binary span.

    Purpose
    -------
    Quantise without rounding, rebuild the whole number the field stores from the validated
    digit text, and write it big-endian in two's complement at the width the digit count
    selects. This is the exact inverse of :func:`decode_binary`.

    Parameters
    ----------
    value : decimal.Decimal | int | str
        Exact human value to encode; binary floating-point values and booleans are refused.
    int_digits : int
        Number of declared digit positions before the implied decimal point.
    dec_digits : int
        Number of declared digit positions after the implied decimal point.
    signed : bool
        ``True`` to allow a negative value and store it in two's complement, or ``False`` to
        reject one.
    field : FieldSpec | None
        Optional descriptor used only to cross-check the width and to render safe
        diagnostics.

    Returns
    -------
    bytes
        Exactly 2, 4 or 8 bytes as the digit count dictates, most significant byte first.

    Raises
    ------
    PackedSpanWidthError
        If the digit counts disagree with ``field.length``.
    PackedDecimalError
        If a text value is not a decimal number, the value is not finite, the geometry is
        inadmissible, the value carries a scale beyond ``dec_digits``, its magnitude exceeds
        the declared digit positions, it is negative in a field declared without a sign, or
        the whole number it stores does not fit the field's byte width.
    TypeError
        If ``value`` is a boolean, a binary floating-point number, or any type other than
        :class:`Decimal`, :class:`int` or :class:`str`; also if the geometry arguments or
        ``field`` have the wrong types.
    """
    _, width = _require_geometry(int_digits, dec_digits, signed, Kind.BINARY, field)
    digit_text, negative = _require_encodable(
        value, int_digits, dec_digits, signed, Kind.BINARY, width, field
    )

    # WHY (Assumptions): the whole number is rebuilt from the validated digit text rather than
    #   read out of the value's own unscaled form, because the digit text has already been padded
    #   to exactly the declared decimal places. Reading the unscaled form directly would encode
    #   two units as though they were two hundredths: a scale of zero and a scale of two hold the
    #   same digits and mean different amounts.
    magnitude = int(digit_text)
    stored = -magnitude if negative else magnitude

    try:
        return stored.to_bytes(width, _BINARY_BYTE_ORDER, signed=signed)
    except OverflowError as exc:
        # WHY (Trade-offs): this guard is unreachable while the width rule and the digit-position
        #   check agree, because the widest admissible magnitude at every tier fits its tier --
        #   eighteen digits need at most sixty bits and the eight-byte tier provides
        #   sixty-three. It is kept, and the platform error is translated rather than propagated,
        #   because a caller of this codec guards on this module's error type: were the two rules
        #   ever to disagree, an untranslated OverflowError would slip straight through that
        #   guard and abort a load instead of rejecting one field.
        raise PackedDecimalError(
            _failure(
                f"the stored whole number does not fit the field's {width} bytes",
                Kind.BINARY,
                width,
                field,
            )
        ) from exc


def decode_packed_field(record: bytes | bytearray | memoryview, field: FieldSpec) -> Decimal:
    """Decode the packed-decimal field at its descriptor's declared record position.

    Purpose
    -------
    Slice exactly ``record[field.start:field.end]``, take the geometry from the descriptor
    rather than from the caller, and delegate the numeric interpretation to
    :func:`decode_packed`. This is the form every reader uses, because it makes the copybook
    the only source of a field's offset, width, scale and sign.

    Parameters
    ----------
    record : bytes | bytearray | memoryview
        The full fixed-width record containing the declared field span, read in BINARY mode.
    field : FieldSpec
        A :attr:`Kind.PACKED` descriptor supplying the offset, width, scale, sign contract and
        sensitivity.

    Returns
    -------
    decimal.Decimal
        The exact value at the field's declared scale.

    Raises
    ------
    PackedSpanWidthError
        If ``record`` ends before ``field.end``, or if the descriptor's computational geometry
        is internally inconsistent.
    PackedDecimalError
        If ``field`` does not declare :attr:`Kind.PACKED`, or if the sliced span violates the
        packed contract.
    TypeError
        If ``record`` is a :class:`str` or any type other than a bytes-like buffer, or if
        ``field`` is not a :class:`FieldSpec`.
    """
    geometry = _field_geometry(field, Kind.PACKED)
    record_bytes = _require_buffer(record, Kind.PACKED, field.length, field)
    span = _require_record_reach(record_bytes, field, Kind.PACKED)
    return decode_packed(span, *geometry, field=field)


def encode_packed_field(value: Decimal | int | str, field: FieldSpec) -> bytes:
    """Encode a value for the exact packed geometry one field descriptor declares.

    Purpose
    -------
    Derive the codec parameters from ``field`` so a caller cannot independently supply a
    width, a scale or a sign flag that disagrees with the copybook, and delegate the encoding
    to :func:`encode_packed`.

    Parameters
    ----------
    value : decimal.Decimal | int | str
        Exact human value to encode.
    field : FieldSpec
        A :attr:`Kind.PACKED` descriptor supplying the width, scale, sign contract and
        sensitivity.

    Returns
    -------
    bytes
        Exactly ``field.length`` bytes, ready for insertion at ``field.start``.

    Raises
    ------
    PackedSpanWidthError
        If the descriptor's computational geometry is internally inconsistent.
    PackedDecimalError
        If ``field`` does not declare :attr:`Kind.PACKED`, or if ``value`` cannot be
        represented exactly under the field's contract.
    TypeError
        If ``value`` has a refused type, or if ``field`` is not a :class:`FieldSpec`.
    """
    geometry = _field_geometry(field, Kind.PACKED)
    return encode_packed(value, *geometry, field=field)


def decode_binary_field(record: bytes | bytearray | memoryview, field: FieldSpec) -> Decimal:
    """Decode the binary field at its descriptor's declared record position.

    Purpose
    -------
    Slice exactly ``record[field.start:field.end]``, take the geometry from the descriptor
    rather than from the caller, and delegate the numeric interpretation to
    :func:`decode_binary`.

    Parameters
    ----------
    record : bytes | bytearray | memoryview
        The full fixed-width record containing the declared field span, read in BINARY mode.
    field : FieldSpec
        A :attr:`Kind.BINARY` descriptor supplying the offset, width, scale, sign contract and
        sensitivity.

    Returns
    -------
    decimal.Decimal
        The exact value at the field's declared scale.

    Raises
    ------
    PackedSpanWidthError
        If ``record`` ends before ``field.end``, or if the descriptor's computational geometry
        is internally inconsistent.
    PackedDecimalError
        If ``field`` does not declare :attr:`Kind.BINARY`, or if the sliced span holds a value
        wider than the field declares.
    TypeError
        If ``record`` is a :class:`str` or any type other than a bytes-like buffer, or if
        ``field`` is not a :class:`FieldSpec`.
    """
    geometry = _field_geometry(field, Kind.BINARY)
    record_bytes = _require_buffer(record, Kind.BINARY, field.length, field)
    span = _require_record_reach(record_bytes, field, Kind.BINARY)
    return decode_binary(span, *geometry, field=field)


def encode_binary_field(value: Decimal | int | str, field: FieldSpec) -> bytes:
    """Encode a value for the exact binary geometry one field descriptor declares.

    Purpose
    -------
    Derive the codec parameters from ``field`` so a caller cannot independently supply a
    width, a scale or a sign flag that disagrees with the copybook, and delegate the encoding
    to :func:`encode_binary`.

    Parameters
    ----------
    value : decimal.Decimal | int | str
        Exact human value to encode.
    field : FieldSpec
        A :attr:`Kind.BINARY` descriptor supplying the width, scale, sign contract and
        sensitivity.

    Returns
    -------
    bytes
        Exactly ``field.length`` bytes, ready for insertion at ``field.start``.

    Raises
    ------
    PackedSpanWidthError
        If the descriptor's computational geometry is internally inconsistent.
    PackedDecimalError
        If ``field`` does not declare :attr:`Kind.BINARY`, or if ``value`` cannot be
        represented exactly under the field's contract.
    TypeError
        If ``value`` has a refused type, or if ``field`` is not a :class:`FieldSpec`.
    """
    geometry = _field_geometry(field, Kind.BINARY)
    return encode_binary(value, *geometry, field=field)
