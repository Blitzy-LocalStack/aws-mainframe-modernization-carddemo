"""Trailing-sign overpunch codec for the CardDemo ``USAGE DISPLAY`` numeric fields.

Purpose
-------
This module is the numeric boundary of the extract-transform-load package for one storage
regime: COBOL ``USAGE DISPLAY``, which is how EVERY money field in all eleven CardDemo
base-master records is stored. A display field holds one printable digit per byte, and a
SIGNED one folds its sign into its LOW-ORDER DIGIT byte as a trailing overpunch, so the
sign occupies no byte of its own and the field's width is its digit count exactly.
:func:`decode_zoned` turns one such span into an exact :class:`decimal.Decimal` carried at
the field's declared scale; :func:`encode_zoned` is its exact inverse. The two
field-oriented forms, :func:`decode_zoned_field` and :func:`encode_zoned_field`, do the
same for a span located by a field descriptor, which is what keeps a caller anchored on
the declared offset rather than on anything it found in the data.

Nothing else in the package converts these bytes, and this module converts no others.
``COMP-3`` packed decimal belongs to ``carddemo_migration.copybook.packed``, cp037
character decoding to ``carddemo_migration.copybook.ebcdic_codec``, and the byte geometry
of every field to ``carddemo_migration.copybook.layouts``, which this module imports and
never restates.

Why this module carries more explanation than its size suggests
--------------------------------------------------------------
A defect here is SILENT. A misread overpunch does not raise, does not misalign the record
and does not fail a length check: it produces a plausible amount that is wrong, and it
surfaces much later as a balance that does not reconcile. The single most likely such
defect is to read the trailing byte as a bare sign marker rather than as a digit that also
carries the sign. Under that misreading the span ``0000005047G`` decodes to 50.47 instead
of 504.77 -- out by a factor of ten, still entirely plausible, and accepted by every other
check this module performs. Every decision below is therefore recorded with the specific
consequence of getting it wrong.

The two overpunch tables
------------------------
A signed display field's final byte is one of twenty characters. The POSITION of the
character within its table is the digit value it carries, and the table it appears in is
the sign::

    positive:  {  A  B  C  D  E  F  G  H  I    ->   +0 +1 +2 +3 +4 +5 +6 +7 +8 +9
    negative:  }  J  K  L  M  N  O  P  Q  R    ->   -0 -1 -2 -3 -4 -5 -6 -7 -8 -9

An UNSIGNED display field -- ``PIC 9(n)`` with no leading ``S`` -- carries plain ASCII
digits in every byte including the last, and no sign is extracted from it at any point.

Signed and unsigned are two contracts, and the flag is declared rather than sniffed
------------------------------------------------------------------------------------
``app/cpy/CVACT01Y.cpy`` line 5 declares ``ACCT-ID PIC 9(11)`` and line 6 declares
``ACCT-ACTIVE-STATUS PIC X(01)``. Line 1 of ``app/data/ASCII/acctdata.txt`` opens
``00000000001Y00000001940{00000020200{00000010200{2014-11-20``, and line 7 of the same
dataset opens ``00000000007Y00000001930{00000020650{00000002640{2012-10-12``. In both, the
byte at zero-based offset 11 is the letter ``Y`` -- a character value that is not an
overpunch and must never be read as one -- while the three ``{`` bytes at offsets 12, 24
and 36 are the low-order digits of three signed amounts, each a positive zero. Every entry
point here is therefore TOLD which contract applies, taking the flag from the field
descriptor ``layouts`` declares, so the regime is decided by the copybook and never by the
data.

The false-positive class the corpus proves
------------------------------------------
Inferring the regime from the trailing byte does not merely read badly; it corrupts real
data in this corpus. ``app/data/ASCII/dailytran.txt`` contains the eleven-character spans
``3580010001P``, ``2252010001P``, ``1861010001P``, ``2564010001P`` and ``4260030001O``.
Each ends in a letter that appears in the negative table, so each looks exactly like a
signed ``PIC S9(09)V99`` amount. None of them is a field at all: every one sits at
zero-based offset 12 of its own 350-byte record, straddling the tail of
``DALYTRAN-ID PIC X(16)``, then ``DALYTRAN-TYPE-CD``, then ``DALYTRAN-CAT-CD``, and ending
on the first byte of ``DALYTRAN-SOURCE`` -- the ``P`` of ``POS TERM`` or the ``O`` of
``OPERATOR``. Because ``P`` is the negative seven and ``O`` the negative six, a
trailing-byte sniffer reads the first as -358001000.17 and the last as -426003000.16: a
source-terminal label reported as a nine-figure negative amount. The same records carry
their genuine amounts at offset 132, where line 1 holds ``0000005047G`` and decodes to
504.77, so one record exhibits both the aligned decode and the misaligned hazard.

Decode per field, never per record
----------------------------------
A span reaches this module already sliced to one field. A whole record is never handed to
a character decoder, because a record contains bytes that are not text: overpunch
characters, packed nibbles in the records that use them, and low values inside padding. A
character decoder maps every byte it cannot interpret to a replacement character of the
same width, so the record keeps its declared length and still parses field by field
afterwards -- only the amounts are wrong. The parity oracle takes the same position from
the other direction, treating its mainframe-character-set datasets as opaque binary and
never transcoding them, and its helper comments record that routing those bytes through a
text write mangles them into replacement characters. Transcoding therefore stays upstream
in ``ebcdic_codec``, which performs it one field at a time; this module receives
characters that are already correct.

The EBCDIC sign convention, and the naming trap around it
---------------------------------------------------------
This module implements the EBCDIC sign-overpunch convention explicitly rather than
implicitly. ``tests/README.md`` line 268 records the house compile invocation as
``cobc -fixed -fsign=EBCDIC --std=ibm-strict -I app/cpy``, and its lines 273 and 274 state
why the flag is required: the default ``-fsign=ASCII`` misreads the zoned-decimal sign
overpunch and silently corrupts negative balances. The reference data this module reads
was produced under the EBCDIC convention, so that is the convention decoded here.

Two names collide around this point and both are correct, so the collision is recorded
rather than resolved by dropping one of them. ``tests/helpers/record_codec.py`` line 135
calls this very mapping "the canonical IBM ASCII trailing-sign mapping", while
``tests/README.md`` lines 273 and 274 require ``-fsign=EBCDIC``. The overpunch characters
themselves (``{``, ``A`` to ``I``, ``}``, ``J`` to ``R``) are ASCII-printable, which is
what the first name describes; the CONVENTION those characters implement is the EBCDIC
one, which is what the second name describes.

The sharper trap matters inside this subpackage: here "EBCDIC" names a SIGN CONVENTION and
not a character encoding. It is entirely distinct from the cp037 CHARACTER decode that
``carddemo_migration.copybook.ebcdic_codec`` owns, and the two must not be conflated. This
module never transcodes a character; that module never interprets a sign. A matching note
sits in ``ebcdic_codec`` where the two meet.

The zoned fields this module serves
-----------------------------------
Every money field in all eleven base-master records is in this regime. The widths follow
mechanically from the picture clause, because the implied decimal point occupies no byte::

    declaration                     declared at                     bytes   SQL column
    ACCT-CURR-BAL  PIC S9(10)V99    app/cpy/CVACT01Y.cpy line  7       12    NUMERIC(12,2)
    TRAN-AMT       PIC S9(09)V99    app/cpy/CVTRA05Y.cpy line 10       11    NUMERIC(11,2)
    DALYTRAN-AMT   PIC S9(09)V99    app/cpy/CVTRA06Y.cpy line 10       11    NUMERIC(11,2)
    TRAN-CAT-BAL   PIC S9(09)V99    app/cpy/CVTRA01Y.cpy line  9       11    NUMERIC(11,2)
    DIS-INT-RATE   PIC S9(04)V99    app/cpy/CVTRA02Y.cpy line  9        6    NUMERIC(6,2)
    ACCT-ID        PIC 9(11)        app/cpy/CVACT01Y.cpy line  5       11    BIGINT
    CUST-ID        PIC 9(09)        app/cpy/CVCUS01Y.cpy line  5        9    BIGINT
    CUST-SSN       PIC 9(09)        app/cpy/CVCUS01Y.cpy line 17        9    encrypted, masked

``app/cpy/CVACT01Y.cpy`` declares four further amounts at ``PIC S9(10)V99`` -- the credit
limit and the cash credit limit at lines 8 and 9, and the cycle credit and cycle debit at
lines 13 and 14. Those four plus the current balance are the five ``NUMERIC(12,2)`` columns
the load's money-total parity pass aggregates, which is why the twelve-byte form is both
the commonest in the corpus and the canonical exemplar.

The eleven-byte width is corroborated by an artifact that shares no code with the
copybooks: ``app/jcl/PRTCATBL.jcl`` declares the sort symbol ``TRAN-CAT-BAL,18,11,ZD`` at
line 50, whose ``ZD`` type code names the zoned regime outright and whose width is eleven,
at the one-based position 18 that the preceding fields of ``app/cpy/CVTRA01Y.cpy`` put it
at. A sort position is ONE-based and a field offset here is ZERO-based, so that 18 is the
offset 17 this module is handed.

Two field names in the citations above keep a baseline misspelling that is deliberately not
altered: ``ACCT-EXPIRAION-DATE`` and ``CARD-EXPIRAION-DATE`` are spelled as the copybooks
spell them, and the corrections happen in the target column naming downstream. No other
field is renamed anywhere in this package.

Why ``COMP-3`` is a separate module and not a branch of this one
---------------------------------------------------------------
Searching all eleven base-master copybooks for ``COMP``, ``COMP-3`` or ``OCCURS`` returns
nothing at all: those eleven records are one hundred per cent zoned display, so this module
alone is sufficient for the whole seed corpus. Packed decimal is a genuinely different byte
layout -- two digits per byte with the sign in the low-order nibble of the last byte -- and
it appears only in the export record at ``app/cpy/CVEXPORT.cpy`` and, heavily, in the two
authorization segments at ``app/app-authorization-ims-db2-mq/cpy/CIPAUSMY.cpy`` and
``.../CIPAUDTY.cpy``. Two regimes, two codecs, and never one module branching between them,
because a codec that inspected bytes to decide which regime it was in would be back to
guessing from data what the copybook already states.

The round-trip law, and its single documented exception
-------------------------------------------------------
For every span this module accepts, encoding what it decoded reproduces the original span
byte for byte: ``encode_zoned(decode_zoned(raw, i, d, s), i, d, s) == raw``. That law is
not a convenience. The parity oracle compares batch output byte for byte after timestamp
normalisation, so a re-encoded record differing in one character is a failed comparison
rather than a cosmetic difference.

There is exactly one exception, and it is a property of the target types rather than a
defect on either side. COBOL distinguishes the opening brace, a positive zero, from the
closing brace, a negative zero, as two distinct bytes, so the baseline has a negative zero.
The reference implementation preserves it: ``tests/helpers/record_codec.py`` documents at
its line 241 that a closing-brace zero yields a signed zero, and its line 395 reads the
sign back out of it. The Java parity anchor cannot represent it, because ``BigDecimal`` has
no negative zero, so it normalises. This module follows the Java rather than the reference,
because a value decoded here and the same span decoded there must be the same value: a
closing-brace zero decodes to an unsigned zero, indistinguishable from an opening-brace
zero; encoding any zero always emits the opening brace; and a closing-brace zero therefore
normalises to an opening brace across a round trip. That is the one and only span for which
the law above does not hold. The baseline stores the distinction, this module does not
represent it, and the divergence is documented here, in the Java charter and in the
migration's traceability matrix.

Diagnostics and sensitive fields
--------------------------------
Every message this module raises is built from geometry and from its own constants. Field
content is quoted only when the caller supplies a field descriptor AND that descriptor
declares the field not sensitive, which is an explicit statement that the span may be read.
Supplying no descriptor withholds the content, because sensitivity is a property of the
field and a caller that named no field has stated nothing about it. The unsigned national
identifier at ``app/cpy/CVCUS01Y.cpy`` line 17 is a display field and does reach this
module, so the default has to be the closed one.

Known-answer vectors
--------------------
Every vector below comes from the real seed data and is exercised in both directions, so
``python -m doctest`` over this module is a genuine known-answer test rather than a
formatting check::

    >>> from decimal import Decimal
    >>> decode_zoned("00000001940{", 10, 2, True)   # acctdata line 1 ACCT-CURR-BAL
    Decimal('194.00')
    >>> decode_zoned("00000020650{", 10, 2, True)   # acctdata line 7 ACCT-CREDIT-LIMIT
    Decimal('2065.00')
    >>> decode_zoned("0000005047G", 9, 2, True)     # dailytran line 1 AMT, 'G' is +7
    Decimal('504.77')
    >>> decode_zoned("0000005047J", 9, 2, True)     # same magnitude negative, 'J' is -1
    Decimal('-504.71')
    >>> decode_zoned("0000009190}", 9, 2, True)     # dailytran line 2 AMT, '}' is -0
    Decimal('-919.00')
    >>> encode_zoned(Decimal("194.00"), 10, 2, True)
    '00000001940{'
    >>> encode_zoned(Decimal("2065.00"), 10, 2, True)
    '00000020650{'
    >>> encode_zoned(Decimal("504.77"), 9, 2, True)
    '0000005047G'
    >>> encode_zoned(Decimal("-504.71"), 9, 2, True)
    '0000005047J'
    >>> encode_zoned(Decimal("-919.00"), 9, 2, True)
    '0000009190}'

A decoded value carries the field's declared fractional width as its scale and is never
normalised, and an unsigned span yields no sign at all::

    >>> decode_zoned("00000020650{", 10, 2, True).as_tuple().exponent
    -2
    >>> decode_zoned("00000000007", 11, 0, False)   # acctdata line 7 ACCT-ID, unsigned
    Decimal('7')

The negative zero is the one span that does not return unchanged::

    >>> decode_zoned("00000000000}", 10, 2, True)
    Decimal('0.00')
    >>> encode_zoned(Decimal("0.00"), 10, 2, True)
    '00000000000{'

Design decisions (WHY)
----------------------
Trade-offs:
    **Standard library only.** This module imports ``re``, ``decimal`` and ``typing`` and
    nothing else outside the standard library. The consequence bought is concrete: the
    codec imports and runs against its known-answer vectors on a bare checkout with no
    database driver installed, no cloud SDK present and no credential configured, which is
    exactly the isolation a module whose defects are silent needs in order to stay
    testable. The reference codec states the same discipline for the same stated reason, so
    that even a bare checkout, before any test dependency is installed, can import and use
    it. The accepted cost is that a convenience such as resolving a dataset location cannot
    live here and belongs to a loader instead.
Assumptions:
    **Every validation raises; none asserts.** ``python -O`` strips ``assert`` statements
    outright, so an assertion is not a validation but a check that disappears in the
    deployment where a misread amount actually costs money. Every guarantee in this module
    is enforced by an explicit ``raise``, and the two error types below exist so a caller
    can tell an alignment fault from a content fault.
Assumptions:
    **Exact fixed point at every hop, and no ``float`` anywhere.** A binary
    floating-point number cannot represent ten cents exactly, so a single conversion
    through one would silently corrupt a monetary total. Decoding therefore produces
    :class:`decimal.Decimal` and encoding accepts ``Decimal``, ``int`` or ``str`` and
    refuses ``float`` outright, exactly as the reference codec does. The same discipline
    carries downstream: ``NUMERIC(p,2)`` in the target schema and ``NUMERIC`` in every
    money aggregate the verification queries compute.

Parameters
----------
None.
    The module is imported for its codec API and performs no caller-supplied work at
    import time.

Returns
-------
None.
    Importing the module defines constants, errors and functions; it does not produce a
    value.

Raises
------
None.
    Import-time table construction and function definition do not inspect record data.
"""

from __future__ import annotations

import re
from decimal import Decimal, InvalidOperation, localcontext
from typing import Final

from carddemo_migration.copybook.layouts import FieldSpec, Kind, mask_field, zoned_width

# Trade-offs: keeping this import boundary to the standard library plus ``layouts``
#   makes the codec importable in a bare checkout before a database driver, AWS SDK, or
#   character-set package is installed. The accepted cost is that record loading,
#   transcoding, and persistence stay outside this module; the concrete benefit is that a
#   money-field decode can be reproduced without infrastructure that might hide the actual
#   byte-to-value decision.

# Assumptions: the public surface is declared explicitly and in sorted order so a
#   consumer's import list can be checked against it mechanically, and so that the two
#   error types are advertised as part of the contract rather than left to be discovered by
#   a caller writing an except clause. The sibling geometry module declares its surface the
#   same way for the same reason.
__all__ = [
    "ZonedDecimalError",
    "ZonedSpanWidthError",
    "decode_zoned",
    "decode_zoned_field",
    "encode_zoned",
    "encode_zoned_field",
]

# Alternatives Considered: the sign and the digit could be resolved with a dictionary
#   literal mapping each of the twenty characters to a signed digit, or with a cascade of
#   comparisons. Two index strings are used instead because the POSITION in the string IS
#   the digit value, which makes the encode direction a direct ``table[digit]`` lookup and
#   the decode direction a direct ``table.find(byte)`` lookup over the same two constants.
#   That is a symmetric, self-checking representation: a character transcribed into the
#   wrong slot shows up immediately as an asymmetry, because the same table serves both
#   directions and the round-trip vectors then disagree. A dictionary would need two
#   mappings kept in step by hand and would lose the positional invariant entirely; a
#   comparison cascade would need twenty branches and would not express the digit
#   relationship at all.
_POS_OVERPUNCH: Final[str] = "{ABCDEFGHI"
_NEG_OVERPUNCH: Final[str] = "}JKLMNOPQR"

# Trade-offs: the plain-digit portion of a span is validated with one precompiled
#   pattern rather than with ad-hoc ``str.isdigit`` calls at each site. Two things are
#   bought. The check accepts exactly the ten ASCII digits, where ``str.isdigit`` also
#   accepts the decimal digits of other scripts -- characters a fixed-width record produced
#   on the reference platform cannot contain, and which would then be subtracted from the
#   ASCII zero to yield a digit value in the hundreds. And every failure comes from one
#   site with one message shape, so a caller reading a log does not have to recognise four
#   different phrasings of the same fault. The pattern is compiled once at import, so the
#   per-call cost is a match against a compiled object; the reference codec adopts the same
#   approach for the same reason.
_DIGITS_RE: Final[re.Pattern[str]] = re.compile(r"[0-9]*")

# Assumptions: a display field is right-aligned within its declared width and padded
#   on the LEFT with this character, which the corpus shows directly -- the credit limit at
#   line 7 of ``app/data/ASCII/acctdata.txt`` holds ``00000020650{`` and not ``20650{``
#   followed by spaces. Padding on the right, or with spaces on either side, produces a
#   field the baseline programs read as non-numeric.
_PAD_DIGIT: Final[str] = "0"

# Assumptions: the encode path quantises inside a private decimal context whose
#   precision is the field width plus this margin. The margin exists so that a value WIDER
#   than the field still quantises successfully and is then refused by the explicit width
#   check below with a message naming both widths, rather than failing as an inscrutable
#   precision error from the arithmetic. Eight is comfortably more than any base-master
#   field needs, since the widest declaration in the corpus is twelve digit positions.
_QUANTIZE_PRECISION_MARGIN: Final[int] = 8

# Assumptions: only these two of the five storage regimes are display regimes, so
#   only these two can reach this module. Packed and binary fields are a different byte
#   layout altogether and belong to ``packed``; a character field has no numeric reading at
#   all. Naming the admissible set once means the field-oriented entry points reject a
#   mis-routed field with one message instead of decoding its bytes as digits and returning
#   a number that looks like an amount.
_DISPLAY_KINDS: Final[frozenset[Kind]] = frozenset({Kind.ZONED, Kind.UINT})


# Assumptions: every contract violation below is enforced with an explicit exception
#   rather than an ``assert``. Optimised Python removes assertions under ``python -O``;
#   losing a width or sign check in that mode would turn malformed financial input into a
#   plausible value instead of a visible failure. Both public errors remain ValueError
#   subclasses so existing parse guards still catch them, while the width subtype lets a
#   caller distinguish misalignment from malformed numeric content.
class ZonedDecimalError(ValueError):
    """Report malformed display-numeric content or an impossible exact encoding.

    Purpose
    -------
    Distinguish violations of the zoned or unsigned display contract from unrelated
    parsing failures while remaining compatible with callers that already catch
    :class:`ValueError`.

    Parameters
    ----------
    args : tuple[object, ...]
        Standard exception arguments, normally one human-readable diagnostic string.

    Returns
    -------
    ZonedDecimalError
        A newly constructed content-or-encoding exception.

    Raises
    ------
    None.
    """


class ZonedSpanWidthError(ZonedDecimalError):
    """Report a display-numeric span whose width differs from its declaration.

    Purpose
    -------
    Give alignment faults a catchable type distinct from malformed content, because a
    short or long slice points to record geometry while a bad body byte points to the
    producer's numeric representation.

    Parameters
    ----------
    args : tuple[object, ...]
        Standard exception arguments, normally one human-readable diagnostic string.

    Returns
    -------
    ZonedSpanWidthError
        A newly constructed width exception that is also a
        :class:`ZonedDecimalError`.

    Raises
    ------
    None.
    """


# Trade-offs: a sensitive field's content is withheld completely rather than using
#   ``mask_field``'s same-width last-four concession. A record diff needs stable width and
#   a small identifying suffix; an exception can be copied into a log aggregator, where
#   even that suffix broadens disclosure. Non-sensitive content is routed through the
#   layouts-owned helper when its width matches, so this module never duplicates the
#   policy decision attached to ``FieldSpec.sensitive``.
def _renderable(content: str | None, field: FieldSpec | None) -> str | None:
    """Return content only when a field descriptor explicitly permits disclosure.

    Purpose
    -------
    Centralise the fail-closed gate used by every diagnostic so an absent descriptor, a
    sensitive descriptor, or a wrong-width span can never leak field content.

    Parameters
    ----------
    content : str | None
        Candidate field content, or ``None`` when the failure has no content to render.
    field : FieldSpec | None
        Descriptor whose sensitivity and width govern disclosure, or ``None`` when the
        caller has not established a field context.

    Returns
    -------
    str | None
        A layouts-approved rendering for a non-sensitive, same-width field; otherwise
        ``None``.

    Raises
    ------
    None.
    """
    if content is None or field is None or field.sensitive:
        return None
    if len(content) != field.length:
        return None
    return mask_field(field, content)


def _failure(
    reason: str,
    *,
    field: FieldSpec | None = None,
    content: str | None = None,
) -> str:
    """Build one sensitive-safe diagnostic from a reason and optional field context.

    Purpose
    -------
    Give every exception the same ordering and disclosure policy: reason first, then
    safe field geometry, then content only when :func:`_renderable` permits it.

    Parameters
    ----------
    reason : str
        Content-free explanation of the violated contract.
    field : FieldSpec | None
        Optional descriptor used for geometry and sensitivity decisions.
    content : str | None
        Optional raw span considered for fail-closed rendering.

    Returns
    -------
    str
        A complete diagnostic that is safe for the supplied field context.

    Raises
    ------
    None.
    """
    message = reason
    if field is not None:
        message = f"{message}; field={field.describe()}"
    rendered = _renderable(content, field)
    if rendered is not None:
        message = f"{message}; content={rendered!r}"
    return message


def _require_text(raw: object, *, field: FieldSpec | None = None) -> str:
    """Require already-decoded text at the display codec boundary.

    Purpose
    -------
    Reject byte input before any numeric parsing so cp037 character conversion remains
    exclusively in ``carddemo_migration.copybook.ebcdic_codec`` and this module cannot
    accidentally apply an ASCII decode to mainframe bytes.

    Parameters
    ----------
    raw : object
        Candidate field or record text.
    field : FieldSpec | None
        Optional descriptor included in a sensitive-safe failure message.

    Returns
    -------
    str
        ``raw`` unchanged after its type is validated.

    Raises
    ------
    TypeError
        If ``raw`` is not a :class:`str`.
    """
    if isinstance(raw, str):
        return raw
    raise TypeError(
        _failure(
            "display decoding requires str content already converted at the character"
            f" boundary, not {type(raw).__name__}; use ebcdic_codec for cp037 bytes",
            field=field,
        )
    )


def _require_geometry(
    int_digits: int,
    dec_digits: int,
    signed: bool,
    *,
    field: FieldSpec | None = None,
) -> int:
    """Validate display geometry and return its exact character width.

    Purpose
    -------
    Guard the shared public parameters once, delegate the picture-width rule to
    :func:`zoned_width`, and ensure optional field context describes the same span.

    Parameters
    ----------
    int_digits : int
        Number of positions before the implied decimal point.
    dec_digits : int
        Number of positions after the implied decimal point.
    signed : bool
        Whether the final position must carry an overpunched sign.
    field : FieldSpec | None
        Optional descriptor whose declared length must equal the derived width.

    Returns
    -------
    int
        ``int_digits + dec_digits`` after all geometry checks.

    Raises
    ------
    TypeError
        If either digit count is not an integer, if ``signed`` is not a boolean, or if
        ``field`` is neither :class:`FieldSpec` nor ``None``.
    ZonedDecimalError
        If the digit counts do not describe an admissible display field.
    ZonedSpanWidthError
        If the digit counts disagree with the supplied field's declared length.
    """
    if field is not None and not isinstance(field, FieldSpec):
        raise TypeError(f"field must be FieldSpec or None, not {type(field).__name__}")
    for name, count in (("int_digits", int_digits), ("dec_digits", dec_digits)):
        if isinstance(count, bool) or not isinstance(count, int):
            raise TypeError(
                _failure(
                    f"{name} must be an int digit count, not {type(count).__name__}",
                    field=field,
                )
            )
    if not isinstance(signed, bool):
        raise TypeError(_failure(f"signed must be bool, not {type(signed).__name__}", field=field))
    try:
        width = zoned_width(int_digits, dec_digits)
    except ValueError as exc:
        raise ZonedDecimalError(
            _failure(
                "display geometry must declare between one and eighteen digit positions"
                f" with no negative count; int_digits={int_digits}, dec_digits={dec_digits}",
                field=field,
            )
        ) from exc
    if field is not None and width != field.length:
        raise ZonedSpanWidthError(
            _failure(
                f"display geometry implies {width} characters but the descriptor declares"
                f" {field.length}",
                field=field,
            )
        )
    return width


def _first_non_digit(text: str) -> int | None:
    """Locate the first character outside the ASCII digit range.

    Purpose
    -------
    Use the module's single compiled validator to turn a malformed digit run into the
    exact zero-based failure index without echoing the run itself.

    Parameters
    ----------
    text : str
        Candidate run expected to contain only ``0`` through ``9``.

    Returns
    -------
    int | None
        The zero-based index of the first non-digit, or ``None`` when every character
        is an ASCII digit.

    Raises
    ------
    None.
    """
    match = _DIGITS_RE.match(text)
    end = match.end()
    return None if end == len(text) else end


def _field_geometry(field: FieldSpec) -> tuple[int, int, bool]:
    """Derive core codec parameters from one display-field descriptor.

    Purpose
    -------
    Convert the two supported layout regimes to the common ``int_digits``,
    ``dec_digits`` and ``signed`` triple while refusing every non-display regime before
    any bytes are inspected.

    Parameters
    ----------
    field : FieldSpec
        Descriptor for a :attr:`Kind.ZONED` or :attr:`Kind.UINT` field.

    Returns
    -------
    tuple[int, int, bool]
        Integer positions, fractional positions and sign contract for the field.

    Raises
    ------
    TypeError
        If ``field`` is not a :class:`FieldSpec`.
    ZonedDecimalError
        If ``field`` is text, packed decimal or binary.
    ZonedSpanWidthError
        If the descriptor's length disagrees with its derived display width.
    """
    if not isinstance(field, FieldSpec):
        raise TypeError(f"field must be FieldSpec, not {type(field).__name__}")
    if field.kind not in _DISPLAY_KINDS:
        # Assumptions: all eleven base masters contain zero ``COMP`` or
        #   ``COMP-3`` declarations, so their numerics are display fields. Packed and
        #   binary storage occurs in other record families and has different digit and
        #   sign placement; accepting either here would make one codec guess between
        #   incompatible byte layouts instead of obeying the descriptor.
        raise ZonedDecimalError(
            _failure(
                f"display codec cannot process {field.kind.name} storage; expected ZONED or UINT",
                field=field,
            )
        )
    if field.kind is Kind.UINT:
        # Assumptions: unsigned ``PIC 9(n)`` fields carry an ordinary digit in the
        #   low-order position, so layouts records their digit count once as ``length``
        #   and deliberately leaves both digit-count attributes at zero. Deriving
        #   ``(length, 0, False)`` preserves keys such as ACCT-ID and CUST-ID; treating
        #   either as signed would demand a letter overpunch and corrupt the identifier.
        geometry = (field.length, 0, False)
    else:
        geometry = (field.int_digits, field.dec_digits, field.signed)
    _require_geometry(*geometry, field=field)
    return geometry


def decode_zoned(
    raw: str,
    int_digits: int,
    dec_digits: int,
    signed: bool,
    *,
    field: FieldSpec | None = None,
) -> Decimal:
    """Decode one display-numeric span to an exact, scale-stable decimal.

    Purpose
    -------
    Interpret one already-sliced COBOL ``USAGE DISPLAY`` field, extracting a trailing
    sign overpunch only when the declared contract is signed and placing the implied
    decimal point exactly ``dec_digits`` positions from the right.

    Parameters
    ----------
    raw : str
        Exact characters of one field, already converted at the character boundary.
    int_digits : int
        Number of declared digit positions before the implied decimal point.
    dec_digits : int
        Number of declared digit positions after the implied decimal point.
    signed : bool
        ``True`` when the final byte must be an EBCDIC-convention sign overpunch;
        ``False`` when every position must be a plain ASCII digit.
    field : FieldSpec | None
        Optional descriptor used only to cross-check width and render safe diagnostics.

    Returns
    -------
    decimal.Decimal
        Exact decoded value whose exponent is ``-dec_digits``; overpunched negative zero
        is deliberately normalised to unsigned zero for Java parity.

    Raises
    ------
    ZonedSpanWidthError
        If ``raw`` is not exactly ``int_digits + dec_digits`` characters, or if those
        counts disagree with ``field.length``.
    ZonedDecimalError
        If the geometry is inadmissible, the body contains a non-digit, the final byte
        violates the signed or unsigned contract, or an unsigned result is negative.
    TypeError
        If ``raw`` is not a string, the geometry arguments have the wrong types, or
        ``field`` is neither :class:`FieldSpec` nor ``None``.
    """
    width = _require_geometry(int_digits, dec_digits, signed, field=field)
    text = _require_text(raw, field=field)

    if len(text) != width:
        raise ZonedSpanWidthError(
            _failure(
                f"display span expected {width} characters from int_digits={int_digits}"
                f" and dec_digits={dec_digits}, but received {len(text)}",
                field=field,
                content=text,
            )
        )

    body = text[:-1]
    last = text[-1]
    invalid_index = _first_non_digit(body)
    if invalid_index is not None:
        raise ZonedDecimalError(
            _failure(
                f"display digit body has a non-ASCII digit at zero-based index {invalid_index}",
                field=field,
                content=text,
            )
        )

    negative = False
    if signed:
        # Assumptions: signed mode means the EBCDIC SIGN CONVENTION explicitly.
        #   ``tests/README.md`` lines 273-274 warn that the ASCII sign default misreads
        #   these bytes and silently corrupts negative balances, so a caller cannot
        #   switch modes by presenting a different-looking final character.
        positive_digit = _POS_OVERPUNCH.find(last)
        negative_digit = _NEG_OVERPUNCH.find(last)

        # Assumptions: the printable characters are the IBM ASCII trailing-sign
        #   MAPPING while the contract is named ``-fsign=EBCDIC`` by the COBOL compiler.
        #   Both names are retained because one describes the characters and the other
        #   describes their sign convention; neither invokes cp037 character decoding.
        if positive_digit >= 0:
            last_digit = str(positive_digit)
        elif negative_digit >= 0:
            last_digit = str(negative_digit)
            negative = True
        elif _first_non_digit(last) is None:
            # Alternatives Considered: silently treating a plain trailing digit as
            #   positive was rejected. In a signed field it proves the producer used a
            #   different sign convention, so tolerance would convert the detectable
            #   ``-fsign=ASCII`` configuration error into a plausible amount with an
            #   unverified sign.
            raise ZonedDecimalError(
                _failure(
                    f"signed display span ends in a plain digit at zero-based index"
                    f" {width - 1}; an overpunched low-order digit is required",
                    field=field,
                    content=text,
                )
            )
        else:
            raise ZonedDecimalError(
                _failure(
                    f"signed display span has an unrecognised overpunch at zero-based"
                    f" index {width - 1}; expected one of {{ through I or }} through R",
                    field=field,
                    content=text,
                )
            )
    else:
        if _first_non_digit(last) is not None:
            raise ZonedDecimalError(
                _failure(
                    f"unsigned display span has a non-ASCII digit at zero-based index {width - 1}",
                    field=field,
                    content=text,
                )
            )
        last_digit = last

    if not signed and negative:
        raise ZonedDecimalError(
            _failure(
                "unsigned display span resolved to a negative value, which its picture"
                " clause cannot represent",
                field=field,
                content=text,
            )
        )

    # Assumptions: an overpunch is the LOW-ORDER DIGIT carrying a sign, not a
    #   sign-only suffix. Appending its table index makes ``0000005047G`` become the
    #   digits ``00000050477`` and therefore 504.77; dropping that seven would produce
    #   50.47, a plausible value wrong by a factor of ten.
    digits = body + last_digit
    integer_boundary = width - dec_digits
    integer_part = digits[:integer_boundary] or _PAD_DIGIT

    # Trade-offs: constructing Decimal from one explicit numeric string costs one
    #   allocation, but it is independent of the caller's decimal precision and cannot
    #   overflow an integer intermediate. That keeps every twelve-digit money field
    #   exact under any ambient decimal context.
    if dec_digits:
        number = f"{integer_part}.{digits[integer_boundary:]}"
    else:
        number = integer_part

    # Assumptions: preserving exactly ``dec_digits`` characters after the point is
    #   what preserves the declared scale. Calling ``normalize()`` would make 2065.00
    #   and 2065 compare equal while discarding the exponent needed by golden-master
    #   checks and downstream NUMERIC(p,2) parity.

    # Trade-offs: a closing-brace zero is normalised to an opening-brace zero on
    #   re-encode because the Java parity codec has no signed-zero representation. This
    #   is the one accepted non-byte-identical round trip; retaining a minus here would
    #   make Python and Java disagree on the same source span.
    if negative and any(digit != _PAD_DIGIT for digit in digits):
        number = f"-{number}"
    return Decimal(number)


def encode_zoned(
    value: Decimal | int | str,
    int_digits: int,
    dec_digits: int,
    signed: bool,
    *,
    field: FieldSpec | None = None,
) -> str:
    """Encode an exact value as one fixed-width display-numeric span.

    Purpose
    -------
    Quantise without rounding, remove the implied decimal point, left-pad the magnitude,
    and replace the low-order digit with its sign overpunch when ``signed`` is true.

    Parameters
    ----------
    value : decimal.Decimal | int | str
        Exact human value to encode; binary floating-point values and booleans are not
        accepted.
    int_digits : int
        Number of declared digit positions before the implied decimal point.
    dec_digits : int
        Number of declared digit positions after the implied decimal point.
    signed : bool
        ``True`` to emit a trailing overpunch, or ``False`` to emit plain digits and
        reject negative values.
    field : FieldSpec | None
        Optional descriptor used only to cross-check width and render safe diagnostics.

    Returns
    -------
    str
        Exactly ``int_digits + dec_digits`` characters with no physical decimal point.

    Raises
    ------
    ZonedSpanWidthError
        If the digit counts disagree with ``field.length``.
    ZonedDecimalError
        If the text value is not numeric, the value is non-finite, precision would be
        lost, the magnitude exceeds the field width, the geometry is inadmissible, or a
        negative value is supplied for an unsigned field.
    TypeError
        If ``value`` is a boolean, a binary floating-point number, or any type other than
        :class:`Decimal`, :class:`int` or :class:`str`; also if geometry types or
        ``field`` are invalid.
    """
    width = _require_geometry(int_digits, dec_digits, signed, field=field)

    # Assumptions: a binary floating-point value cannot represent ten cents
    #   exactly, and ``bool`` is an ``int`` subclass despite not being a monetary value.
    #   Rejecting both before Decimal conversion prevents ``0.10`` from carrying a hidden
    #   approximation and prevents ``True`` from silently becoming one unit.
    if isinstance(value, bool):
        raise TypeError(
            _failure(
                "encode_zoned does not accept bool; pass Decimal, int, or str",
                field=field,
            )
        )
    if isinstance(value, float):
        raise TypeError(
            _failure(
                "encode_zoned does not accept float because binary floating point cannot"
                " represent decimal cents exactly; pass Decimal, int, or str",
                field=field,
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
            raise ZonedDecimalError(
                _failure("encode_zoned received text that is not a decimal number", field=field)
            ) from exc
    else:
        raise TypeError(
            _failure(
                f"encode_zoned expects Decimal, int, or str, not {type(value).__name__}",
                field=field,
            )
        )

    if not decimal_value.is_finite():
        raise ZonedDecimalError(
            _failure("encode_zoned requires a finite decimal value", field=field)
        )

    # Assumptions: the quantum is constructed from Decimal's exact tuple form rather
    #   than through arithmetic in the caller's active context. A low ambient precision
    #   must not change the target exponent or make the same field encode differently in
    #   two batch processes.
    quantum = Decimal((0, (1,), -dec_digits))

    # Trade-offs: encoding is lossless-or-throw. Silent truncation and silent
    #   rounding were both rejected because a codec that chooses a rounding rule hides a
    #   business decision from the audit trail. Quantising privately and comparing the
    #   result to the input accepts redundant trailing zeroes but refuses any non-zero
    #   fraction the declared scale cannot store.
    try:
        with localcontext() as context:
            context.prec = width + _QUANTIZE_PRECISION_MARGIN
            quantized = decimal_value.quantize(quantum)
            scaled = quantized.copy_abs().scaleb(dec_digits)
    except InvalidOperation as exc:
        raise ZonedDecimalError(
            _failure(
                f"value cannot be represented exactly in a display field with"
                f" int_digits={int_digits} and dec_digits={dec_digits}",
                field=field,
            )
        ) from exc

    if quantized != decimal_value:
        raise ZonedDecimalError(
            _failure(
                f"value has non-zero precision beyond {dec_digits} decimal places",
                field=field,
            )
        )

    # Trade-offs: signum comparison deliberately makes negative zero non-negative.
    #   A decoded ``}`` zero therefore emits ``{`` on re-encode, matching the Java codec
    #   and documenting the sole byte-level exception to the otherwise exact round-trip
    #   law instead of allowing language-specific signed-zero behaviour to diverge.
    negative = quantized < 0
    if not signed and negative:
        raise ZonedDecimalError(
            _failure("cannot encode a negative value in an unsigned display field", field=field)
        )

    # Assumptions: quantisation should make the scaled magnitude a plain digit run,
    #   but that invariant is revalidated rather than asserted or fed through ``int()``.
    #   An unexpected exponent form must raise visibly; coercing it could truncate a
    #   decimal position while still returning a field of the declared width.
    magnitude = str(scaled)
    invalid_index = _first_non_digit(magnitude)
    if invalid_index is not None:
        raise ZonedDecimalError(
            _failure(
                "exact decimal scaling did not produce a plain digit run; refusing an"
                f" unchecked exponent form at zero-based index {invalid_index}",
                field=field,
            )
        )
    if len(magnitude) > width:
        raise ZonedDecimalError(
            _failure(
                f"value needs {len(magnitude)} digit positions but the field holds {width}",
                field=field,
            )
        )

    digits = magnitude.rjust(width, _PAD_DIGIT)
    if not signed:
        return digits

    last_digit = int(digits[-1])
    table = _NEG_OVERPUNCH if negative else _POS_OVERPUNCH
    return f"{digits[:-1]}{table[last_digit]}"


def decode_zoned_field(record: str, field: FieldSpec) -> Decimal:
    """Decode the display field at its descriptor's declared record position.

    Purpose
    -------
    Slice exactly ``record[field.start:field.end]``, derive the signed or unsigned
    geometry from ``field``, and delegate the numeric interpretation to
    :func:`decode_zoned`.

    Parameters
    ----------
    record : str
        Full fixed-width record containing the declared field span.
    field : FieldSpec
        Display-field descriptor supplying offset, width, kind, scale and sensitivity.

    Returns
    -------
    decimal.Decimal
        Exact value at the field's declared scale.

    Raises
    ------
    ZonedSpanWidthError
        If ``record`` ends before ``field.end`` or the descriptor's display geometry is
        internally inconsistent.
    ZonedDecimalError
        If the field is not :attr:`Kind.ZONED` or :attr:`Kind.UINT`, or if its sliced
        content violates the applicable display contract.
    TypeError
        If ``record`` is not a string or ``field`` is not a :class:`FieldSpec`.
    """
    geometry = _field_geometry(field)
    text = _require_text(record, field=field)
    if len(text) < field.end:
        available = max(len(text) - field.start, 0)
        raise ZonedSpanWidthError(
            _failure(
                f"record ends at offset {len(text)}, before the field's exclusive end"
                f" {field.end}; only {available} of {field.length} characters are available",
                field=field,
            )
        )

    # Assumptions: decoding is anchored on one declared field and never on a
    #   pattern scan over a record. ``3580010001P`` occurs at zero-based offset 12 in
    #   ``dailytran.txt`` only because it straddles DALYTRAN-ID and three following code
    #   fields; scanning would report it as a nine-figure negative amount, while slicing
    #   the declared AMT at offset 132 yields the actual 504.77.
    span = text[field.start : field.end]

    # Assumptions: a whole record may contain sign bytes, packed nibbles, padding
    #   low values, and ordinary text. Passing only this fixed-width span prevents a
    #   character decoder from replacing one non-text byte while preserving record
    #   length, a failure mode that leaves every later offset apparently valid.
    return decode_zoned(span, *geometry, field=field)


def encode_zoned_field(value: Decimal | int | str, field: FieldSpec) -> str:
    """Encode a value for the exact display geometry declared by a field.

    Purpose
    -------
    Derive the core codec parameters from ``field`` so callers cannot independently
    supply a width, scale or sign flag that disagrees with the copybook descriptor.

    Parameters
    ----------
    value : decimal.Decimal | int | str
        Exact human value to encode.
    field : FieldSpec
        Display-field descriptor supplying width, kind, scale and sensitivity.

    Returns
    -------
    str
        Exactly ``field.length`` display characters ready for insertion at
        ``field.start``.

    Raises
    ------
    ZonedSpanWidthError
        If the descriptor's display geometry is internally inconsistent.
    ZonedDecimalError
        If the field is not :attr:`Kind.ZONED` or :attr:`Kind.UINT`, or if ``value``
        cannot be represented exactly under its contract.
    TypeError
        If ``value`` has a forbidden type or ``field`` is not a :class:`FieldSpec`.
    """
    geometry = _field_geometry(field)
    return encode_zoned(value, *geometry, field=field)
