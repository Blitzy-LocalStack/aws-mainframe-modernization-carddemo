"""Trailing-sign overpunch codec for the CardDemo ``USAGE DISPLAY`` numeric fields.

Purpose
-------
This module is the numeric boundary of the extract-transform-load package for one storage
regime: COBOL ``USAGE DISPLAY``, which is how EVERY money field in all eleven CardDemo
base-master records is stored. A display field holds one printable digit per byte, and a
SIGNED one folds its sign into its LOW-ORDER DIGIT byte as a trailing overpunch, so the
sign occupies no byte of its own and the field's width is its digit count exactly.
:func:`decode_zoned` turns one such span into an exact :class:`decimal.Decimal` carried at
the field's declared scale and :func:`encode_zoned` is its exact inverse;
:func:`decode_zoned_field` and :func:`encode_zoned_field` do the same for a span located by
a field descriptor, which is what keeps a caller anchored on the declared offset rather
than on anything it found in the data. Nothing else in the package converts these bytes and
this module converts no others: packed decimal belongs to
``carddemo_migration.copybook.packed``, cp037 character decoding to
``carddemo_migration.copybook.ebcdic_codec``, and the byte geometry of every field to
``carddemo_migration.copybook.layouts``, which this module imports and never restates.

Why a defect here is silent
---------------------------
A misread overpunch does not raise, does not misalign the record and does not fail a length
check: it produces a plausible amount that is wrong, and it surfaces much later as a
balance that does not reconcile. The most likely such defect is to read the trailing byte
as a bare sign marker rather than as a digit that also carries the sign -- under that
misreading the span ``0000005047G`` decodes to 50.47 instead of 504.77, out by a factor of
ten, still entirely plausible, and accepted by every other check this module performs. Each
decision below is therefore recorded with the consequence of getting it wrong.

The two overpunch tables
------------------------
A signed display field's final byte is one of twenty characters. The POSITION of the
character within its table is the digit value it carries, and the table it appears in is
the sign::

    positive:  {  A  B  C  D  E  F  G  H  I    ->   +0 +1 +2 +3 +4 +5 +6 +7 +8 +9
    negative:  }  J  K  L  M  N  O  P  Q  R    ->   -0 -1 -2 -3 -4 -5 -6 -7 -8 -9

An UNSIGNED display field -- ``PIC 9(n)`` with no leading ``S`` -- carries plain ASCII
digits in every byte including the last, and no sign is extracted from it at any point.

The sign flag is declared, never sniffed
----------------------------------------
Every entry point here is TOLD which of those two contracts applies, taking the flag from
the field descriptor ``layouts`` declares, so the regime is decided by the copybook and
never by the data. Inferring it from the trailing byte does not merely read badly; it
corrupts real data in this corpus, and two demonstrations from ``app/data/ASCII`` fix why:

* ``acctdata.txt`` line 1 opens ``00000000001Y00000001940{``. The ``Y`` at zero-based
  offset 11 is ``ACCT-ACTIVE-STATUS PIC X(01)`` and must never be read as an overpunch,
  while the ``{`` at offset 12 is the low-order digit of a signed amount and is a positive
  zero.
* ``dailytran.txt`` holds eleven-character spans such as ``3580010001P`` and
  ``4260030001O``, each ending in a letter from the negative table and so looking exactly
  like a signed ``PIC S9(09)V99`` amount. Neither is a field at all: each sits at
  zero-based offset 12 of its own 350-byte record, straddling ``DALYTRAN-ID``,
  ``DALYTRAN-TYPE-CD`` and ``DALYTRAN-CAT-CD`` and ending on the first byte of
  ``DALYTRAN-SOURCE`` -- the ``P`` of ``POS TERM`` or the ``O`` of ``OPERATOR``. Because
  ``P`` is the negative seven and ``O`` the negative six, a trailing-byte sniffer reads a
  source-terminal label as -358001000.17 and -426003000.16, while the genuine amounts in
  those records sit at offset 132.

Decode per field, never per record
----------------------------------
A span reaches this module already sliced to one field. A whole record is never handed to a
character decoder, because a record contains bytes that are not text -- overpunch
characters, packed nibbles in the records that use them, and low values inside padding --
and a character decoder maps every byte it cannot interpret to a replacement character of
the same width. The record therefore keeps its declared length and still parses field by
field afterwards, and only the amounts are wrong. The parity oracle takes the same position
from the other direction, treating its mainframe-character-set datasets as opaque binary
and never transcoding them. Transcoding stays upstream in ``ebcdic_codec``, which performs
it one field at a time; this module receives characters that are already correct.

"EBCDIC" here names a sign convention, not a character encoding
---------------------------------------------------------------
The house compile invocation is ``cobc -fixed -fsign=EBCDIC --std=ibm-strict -I app/cpy``,
and ``tests/README.md`` states why the flag is required: the default ``-fsign=ASCII``
misreads the sign overpunch and silently corrupts negative balances. The reference data
this module reads was produced under the EBCDIC convention, so that is the convention
decoded here. The overpunch characters themselves are ASCII-printable, which is why the
reference codec calls the same mapping a canonical IBM ASCII trailing-sign mapping; the
CONVENTION they implement is the EBCDIC one. Both names are correct, and neither of them is
the cp037 CHARACTER decode ``carddemo_migration.copybook.ebcdic_codec`` owns: this module
never transcodes a character, and that module never interprets a sign.

The fields served, and where their widths are declared
-----------------------------------------------------
Every money field in all eleven base-master records is in this regime, and each width
follows mechanically from the picture clause because the implied decimal point occupies no
byte: ``PIC S9(10)V99`` is twelve bytes against a ``NUMERIC(12,2)`` column,
``PIC S9(09)V99`` eleven against ``NUMERIC(11,2)``, ``PIC S9(04)V99`` six against
``NUMERIC(6,2)``, and the unsigned identifiers ``PIC 9(11)`` and ``PIC 9(09)`` are eleven
and nine bytes against ``BIGINT``. Which field is declared where, at what offset and
against which column is held once in ``carddemo_migration.copybook.layouts`` and tabulated
in ``docs/architecture/data-model-and-schema-mapping.md``, so a correction has one home.
Searching those eleven copybooks for ``COMP``, ``COMP-3`` or ``OCCURS`` returns nothing at
all: they are one hundred per cent zoned display, so this module alone serves the whole seed
corpus, and packed decimal is a separate module rather than a branch of this one because a
codec that inspected bytes to decide which regime it was in would be back to guessing what
the copybook already states.

The round-trip law, and its single documented exception
-------------------------------------------------------
For every span this module accepts, encoding what it decoded reproduces the original span
byte for byte: ``encode_zoned(decode_zoned(raw, i, d, s), i, d, s) == raw``. That law is
not a convenience: the parity oracle compares batch output byte for byte after timestamp
normalisation, so a re-encoded record differing in one character is a failed comparison
rather than a cosmetic difference.

The one exception is the negative zero, and it is a deliberate cross-language parity policy
rather than a limitation of this language. COBOL distinguishes the opening brace, a positive
zero, from the closing brace, a negative zero, and the reference codec preserves that
distinction; Python could preserve it too, since ``Decimal("-0").is_signed()`` is true, so
nothing here forces the normalisation. What forces it is the Java parity anchor:
``BigDecimal`` has no signed zero at all, so a value it decodes from a closing-brace span is
indistinguishable from one decoded from an opening brace. A value decoded here and the same
span decoded there have to be the same value, and preserving the sign here would make the
two implementations disagree on one input while agreeing on every other -- the hardest kind
of disagreement to notice. So a closing-brace zero decodes to an unsigned zero, encoding any
zero always emits the opening brace, and a closing-brace zero normalises to an opening brace
across a round trip. That is the one and only span for which the law above does not hold,
and the divergence is recorded here, in the Java charter and in the migration's
traceability matrix.

Diagnostics and sensitive fields
--------------------------------
Every message this module raises is built from geometry and from its own constants. Field
content is quoted only when the caller supplies a field descriptor AND that descriptor
declares the field not sensitive, which is an explicit statement that the span may be read.
Supplying no descriptor withholds the content, because sensitivity is a property of the
field and a caller that named no field has stated nothing about it. The unsigned national
identifier ``CUST-SSN PIC 9(09)`` is a display field and does reach this module, so the
default has to be the closed one.


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
    testable. The accepted cost is that a convenience such as resolving a dataset location
    cannot live here and belongs to a loader instead.
Assumptions:
    **Every validation raises; none asserts.** ``python -O`` strips ``assert`` statements
    outright, so an assertion is not a validation but a check that disappears in the
    deployment where a misread amount actually costs money. Every guarantee in this module
    is enforced by an explicit ``raise``, and the two error types below exist so a caller
    can tell an alignment fault from a content fault.
Assumptions:
    **Exact fixed point at every hop, and no ``float`` anywhere.** A binary floating-point
    number cannot represent ten cents exactly, so a single conversion through one would
    silently corrupt a monetary total. Decoding therefore produces
    :class:`decimal.Decimal`, encoding accepts ``Decimal``, ``int`` or ``str`` and refuses
    ``float`` outright, and the same discipline carries downstream into the target schema's
    ``NUMERIC(p,2)`` columns and the ``NUMERIC`` aggregates the verification queries
    compute.
"""

from __future__ import annotations

import re
from decimal import Decimal, InvalidOperation, localcontext
from typing import Final

from carddemo_migration.copybook.layouts import FieldSpec, Kind, mask_field, zoned_width

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

# Assumptions: only these two of the six storage regimes are display regimes, so
#   only these two can reach this module. Packed and binary fields are a different byte
#   layout altogether and belong to ``packed``; a character field has no numeric reading at
#   all; and an opaque area holds several regimes at once, so it has none either. Naming the
#   admissible set once means the field-oriented entry points reject a mis-routed field with
#   one message instead of decoding its bytes as digits and returning a number that looks
#   like an amount.
_DISPLAY_KINDS: Final[frozenset[Kind]] = frozenset({Kind.ZONED, Kind.UINT})


# Assumptions: both public errors remain ``ValueError`` subclasses so an existing parse
#   guard still catches them, and the width subtype exists so a caller can distinguish
#   misalignment -- a record-geometry fault -- from malformed numeric content.
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

    Notes
    -----
    Raised by every content and exact-encoding check in this module.
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

    Notes
    -----
    Raised only for a width or alignment fault, and catchable as a
    :class:`ZonedDecimalError`.
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
        # Assumptions: packed and binary storage place their digits and sign
        #   differently, so accepting either here would make one codec guess between
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
        # Assumptions: signed mode means the EBCDIC SIGN CONVENTION explicitly, so the
        #   final byte is looked up in the two overpunch tables and a caller cannot switch
        #   conventions by presenting a different-looking final character. The ASCII sign
        #   default misreads these bytes and silently corrupts negative balances.
        positive_digit = _POS_OVERPUNCH.find(last)
        negative_digit = _NEG_OVERPUNCH.find(last)

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
    #   overflow an integer intermediate, which keeps every twelve-digit money field exact
    #   under any ambient decimal context. Keeping exactly ``dec_digits`` characters after
    #   the point is also what preserves the declared scale: ``normalize()`` would make
    #   2065.00 and 2065 compare equal while discarding the exponent the golden-master
    #   comparison and the downstream ``NUMERIC(p,2)`` column both depend on.
    if dec_digits:
        number = f"{integer_part}.{digits[integer_boundary:]}"
    else:
        number = integer_part

    # Trade-offs: a closing-brace zero yields an unsigned zero, the one accepted
    #   non-byte-identical round trip, for the cross-language reason the module docstring
    #   records. The Java codec exposes a sign-preserving pair for a caller that must
    #   reproduce a dataset byte for byte; a Python equivalent belongs with the loader that
    #   needs it rather than on this ordinary decode path, which every reader calls and
    #   none of which re-emits the span it read.
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

    # Assumptions: ``bool`` is refused as well as ``float``, because it is an ``int``
    #   subclass and would otherwise make ``True`` silently encode as one unit.
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
    #   A decoded ``}`` zero therefore emits ``{`` on re-encode, matching the Java codec's
    #   ordinary ``encode`` entry point and documenting the sole byte-level exception to
    #   the otherwise exact round-trip law instead of letting language-specific
    #   signed-zero behaviour diverge. The Java codec's sign-preserving pair,
    #   ``decodePreservingSign`` with ``encodePreservingSign``, has no such exception
    #   because it carries the overpunch class beside the value; this function takes a
    #   bare ``Decimal`` and so has nothing to carry.
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

    # Assumptions: decoding is anchored on one declared field and never on a pattern scan
    #   over a record, and only the sliced span travels onward. Scanning would report
    #   ``3580010001P`` -- which occurs at offset 12 of a daily-transaction record only
    #   because it straddles four fields -- as a nine-figure negative amount, where slicing
    #   the declared amount at offset 132 yields the actual 504.77.
    span = text[field.start : field.end]
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
