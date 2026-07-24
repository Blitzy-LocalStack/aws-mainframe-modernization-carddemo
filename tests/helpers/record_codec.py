"""Fixed-width + zoned-decimal record codec for the CardDemo test harness.

Purpose
-------
This module is the *single source of truth* for the on-disk record structure of
the AWS CardDemo batch files inside the Python test layer. It encodes and decodes
the fixed-width COBOL records that the batch programs read and write, mirroring the
``app/cpy/`` copybook layouts **exactly**. Every other Python helper and test builds
on it:

* ``tests/helpers/vsam_loader.py`` reads :func:`reclen_of` / :func:`keylen_of`
  to size and key the GnuCOBOL indexed files it loads.
* ``tests/helpers/golden_compare.py`` reuses :func:`normalize_timestamps` so the
  single *runtime-generated* timestamp field (``PROC-TS``) is masked from exactly
  one place rather than duplicated across the suite. Note the deliberate asymmetry:
  ``ORIG-TS`` is **not** masked because it carries the deterministic originating
  timestamp copied from the input transaction (verified in the seeds, e.g.
  ``2022-06-10 19:27:53.000000``), whereas ``PROC-TS`` is stamped with the wall-clock
  posting time and is therefore the only non-deterministic offset.
* The ``tests/integration`` and ``tests/e2e`` modules decode program output through
  the :data:`LAYOUTS` registry and assert on the decoded fields.

It is imported as ``from tests.helpers.record_codec import ...``. There is **no**
``__init__.py`` anywhere under ``tests/`` -- the package resolves as a PEP 420
namespace package via ``PYTHONPATH=<repo_root>``.

Design decisions (WHY)
----------------------
* **Exact fixed-point money (no float).** Every signed numeric (money / interest
  rate) field decodes to :class:`decimal.Decimal` and encodes from
  ``Decimal`` / ``int`` / ``str`` -- *never* a binary floating-point value. A
  binary ``float`` cannot represent a value such as ``0.10`` (ten cents) exactly,
  so using it would silently corrupt monetary totals. This is a financial-enterprise
  correctness requirement, not a stylistic preference; :func:`encode_zoned`
  therefore rejects ``float`` inputs outright.
* **Single-sourced layouts.** The eight record layouts are transcribed field-for-field
  from the copybooks (``CVACT01Y``, ``CVTRA06Y``, ``CVTRA02Y``, ``CVACT03Y``,
  ``CVTRA01Y``, ``CVACT02Y``, ``CVCUS01Y``, ``CVTRA05Y``). Each :class:`Field`
  carries a comment citing its copybook name and PIC clause. Layouts are *never*
  re-declared independently elsewhere; downstream code imports them from here. The
  XREF layout additionally models its *alternate* index (the account-id key that
  ``CBACT04C`` reads by), because CardDemo's indexed files are not all keyed on their
  leading bytes -- see :class:`AlternateKey`.
* **Strict physical-record validation (no silent repair).** A physical row that is
  not exactly ``reclen`` characters (after stripping a single trailing newline) is
  *rejected* -- never padded, truncated, or dropped. A financial record of the wrong
  width is corrupt input, and silently repairing it could post a misaligned money
  value. The only valid "empty" input is a genuinely zero-byte dataset (zero rows);
  that is expressed by an empty file yielding no rows to decode, not by tolerating a
  blank or short row. See :func:`_validated_record`.
* **Field-aware masking for diagnostics.** PAN / SSN / name / DOB / government-id
  fields are flagged :attr:`Field.sensitive`; :func:`mask_record` and
  :func:`mask_field` produce redacted renderings so that assertion failures and
  golden diffs can show *where* two records differ without ever emitting a complete
  cardholder identity or payment number.
* **Standard library only.** The module imports nothing outside the Python standard
  library so that even a bare checkout (before any test dependency is installed)
  can import and use it.

Zoned-decimal sign overpunch (WHY this exact mapping)
-----------------------------------------------------
CardDemo's ASCII seed data stores signed numbers as USAGE DISPLAY zoned decimals
with a *trailing* sign: the sign "rides" the final digit byte. The canonical
IBM ASCII overpunch mapping used throughout the seeds is::

    positive:  { A B C D E F G H I   ->  +0 +1 +2 ... +9   ('{' == +0)
    negative:  } J K L M N O P Q R   ->  -0 -1 -2 ... -9   ('}' == -0)

Unsigned ``9(n)`` fields (no ``S`` in the PIC) contain plain ASCII digits with no
overpunch. The mapping is expressed below as two 10-character index strings
(:data:`_POS_OVERPUNCH` / :data:`_NEG_OVERPUNCH`) where the string index equals the
represented digit value; a table lookup is used in preference to a chain of ``if``
branches because it is O(1), symmetric for encode/decode, and reads as the mapping
it implements.

Doctest known-answer vectors (taken from the real ``app/data/ASCII`` seeds)::

    >>> from decimal import Decimal
    >>> decode_zoned("00000001940{", 10, 2, True)   # acctdata line 1 CURR-BAL
    Decimal('194.00')
    >>> decode_zoned("0000005047G", 9, 2, True)      # dailytran line 1 AMT ('G' == +7)
    Decimal('504.77')
    >>> decode_zoned("0000005047J", 9, 2, True)      # same magnitude, negative ('J' == -1)
    Decimal('-504.71')
    >>> encode_zoned(Decimal("194.00"), 10, 2, True)
    '00000001940{'
    >>> encode_zoned(Decimal("-504.71"), 9, 2, True)
    '0000005047J'
"""

from __future__ import annotations

import hashlib
import re
from dataclasses import dataclass, field as dc_field, replace as dc_replace
from decimal import Decimal, InvalidOperation, localcontext
from typing import Any

__all__ = [
    "ZonedDecimalError",
    "RecordLengthError",
    "decode_zoned",
    "encode_zoned",
    "AlternateKey",
    "Field",
    "RecordLayout",
    "ACCOUNT_LAYOUT",
    "DALYTRAN_LAYOUT",
    "DISGROUP_LAYOUT",
    "XREF_LAYOUT",
    "TCATBAL_LAYOUT",
    "CARD_LAYOUT",
    "CUSTOMER_LAYOUT",
    "TRAN_LAYOUT",
    "TRNX_LAYOUT",
    "REJECT_LAYOUT",
    "INTTRAN_LAYOUT",
    "LAYOUTS",
    "reclen_of",
    "keylen_of",
    "alternate_keys_of",
    "normalize_timestamps",
    "mask_field",
    "mask_record",
]

# ---------------------------------------------------------------------------
# Zoned-decimal overpunch tables.
#
# WHY (Alternatives Considered): the sign+digit could be resolved with a
# dictionary literal or a cascade of comparisons. Two index strings are used
# instead because the position in the string *is* the digit value, which makes
# the encode direction a direct ``string[digit]`` lookup and the decode
# direction a direct ``string.index(byte)`` lookup -- a symmetric, self-checking
# representation of the canonical IBM ASCII trailing-sign mapping.
# ---------------------------------------------------------------------------
_POS_OVERPUNCH = "{ABCDEFGHI"  # index n -> "+n"  ('{' == +0, 'A' == +1, ... 'I' == +9)
_NEG_OVERPUNCH = "}JKLMNOPQR"  # index n -> "-n"  ('}' == -0, 'J' == -1, ... 'R' == -9)

# Precompiled validator for the plain-digit portion of a zoned field.
# WHY (Trade-off): validating with a compiled regex yields a single, uniform
# error site and message rather than scattering ad-hoc ``str.isdigit`` checks;
# the regex is compiled once at import so the per-call cost is negligible.
_DIGITS_RE = re.compile(r"[0-9]*")


class ZonedDecimalError(ValueError):
    """Error raised for malformed zoned-decimal data or impossible encodings.

    Purpose
    -------
    Signal that a zoned-decimal field could not be decoded or encoded because the
    input violated the fixed-width / overpunch contract -- for example a wrong-length
    field, a non-digit body, an unrecognised overpunch byte, a value that would not
    fit the field width, or an encode request that would silently drop monetary
    precision.

    It subclasses :class:`ValueError` (WHY: Assumption) so that existing callers that
    already guard file parsing with ``except ValueError`` continue to catch these
    problems without needing to import this type, while callers that want the more
    specific signal can still catch :class:`ZonedDecimalError` directly.

    Parameters
    ----------
    args : tuple
        Standard :class:`ValueError` positional arguments (typically a single
        human-readable message string).

    Returns
    -------
    ZonedDecimalError
        A new exception instance.

    Raises
    ------
    None
    """


class RecordLengthError(ValueError):
    """Error raised when a physical record row does not match its declared width.

    Purpose
    -------
    Signal that a raw line presented for decoding, keying, timestamp-normalisation, or
    masking is not exactly the layout's ``reclen`` characters after a single trailing
    newline is removed. This is the enforcement point for the strict "no silent repair"
    contract (CR-03): a wrong-width financial record is corrupt input and must be
    rejected rather than padded, truncated, or dropped.

    It subclasses :class:`ValueError` (WHY: Assumption) so callers that already guard
    fixture parsing with ``except ValueError`` keep catching it, while callers that want
    the specific signal can catch :class:`RecordLengthError` directly.

    Parameters
    ----------
    args : tuple
        Standard :class:`ValueError` positional arguments (typically one message string).

    Returns
    -------
    RecordLengthError
        A new exception instance.

    Raises
    ------
    None
    """


def decode_zoned(raw: str, int_digits: int, dec_digits: int, signed: bool) -> Decimal:
    """Decode one fixed-width zoned-decimal field into an exact :class:`Decimal`.

    Purpose
    -------
    Convert the raw characters of a single COBOL ``USAGE DISPLAY`` numeric field into
    a precise fixed-point :class:`decimal.Decimal`, honouring the trailing-sign
    overpunch encoding for signed fields and the implied decimal point defined by
    ``dec_digits``.

    Parameters
    ----------
    raw : str
        The exact field bytes, already sliced out of the record. Its length **must**
        equal ``int_digits + dec_digits`` (the field carries no separate sign byte or
        decimal point -- both are implied).
    int_digits : int
        Number of integer digit positions (the ``a`` in ``S9(a)V9(d)``). Must be >= 0.
    dec_digits : int
        Number of fractional digit positions (the ``d`` in ``...V9(d)``). Must be >= 0.
        The implied decimal point sits this many places from the right.
    signed : bool
        ``True`` if the PIC begins with ``S`` (the final byte carries the sign via
        overpunch); ``False`` for an unsigned ``9(n)`` field of plain digits.

    Returns
    -------
    decimal.Decimal
        The decoded value, scaled to exactly ``dec_digits`` fractional places (so the
        Decimal exponent is stable and round-trips reproduce the original bytes).
        Negative zero is preserved (a ``'}'`` overpunch yields ``Decimal('-0.00')``).

    Raises
    ------
    ZonedDecimalError
        If ``raw`` is not ``int_digits + dec_digits`` characters long, if the digit
        body contains a non-digit character, or if the trailing byte of a signed field
        is not a recognised trailing-sign overpunch byte. WHY (Refactoring Rationale):
        a *plain* digit in the final position of a signed field is now rejected rather
        than tolerated as positive -- the CardDemo ASCII seeds always overpunch the sign
        (verified: every signed field in the fixture corpus ends in ``{``-``I`` or
        ``}``-``R``), so a bare digit indicates a mis-encoded record whose sign is
        genuinely unknown, and guessing "positive" could flip the sign of a money value.
    """
    width = int_digits + dec_digits
    if len(raw) != width:
        # WHY (Assumption): callers slice fields by fixed offsets, so a wrong length
        # here means the record layout or the input record is corrupt -- fail loudly
        # rather than silently decoding a misaligned value.
        raise ZonedDecimalError(
            f"zoned field expected {width} chars (int_digits={int_digits}, "
            f"dec_digits={dec_digits}) but got {len(raw)}: {raw!r}"
        )

    if not signed:
        # Unsigned 9(n): the whole field is plain ASCII digits.
        if _DIGITS_RE.fullmatch(raw) is None:
            raise ZonedDecimalError(f"unsigned zoned field has non-digit byte(s): {raw!r}")
        negative = False
        digits = raw
    else:
        body, last = raw[:-1], raw[-1]
        if _DIGITS_RE.fullmatch(body) is None:
            raise ZonedDecimalError(f"signed zoned field body has non-digit byte(s): {raw!r}")
        if last in _POS_OVERPUNCH:
            negative = False
            last_digit = str(_POS_OVERPUNCH.index(last))
        elif last in _NEG_OVERPUNCH:
            negative = True
            last_digit = str(_NEG_OVERPUNCH.index(last))
        else:
            # WHY (Refactoring Rationale): the earlier build tolerated a plain trailing
            # digit as "positive, un-overpunched". That masked the exact class of defect
            # a financial codec must surface -- a signed field whose sign byte was lost
            # in transcription. The CardDemo seeds are uniformly overpunched, so any
            # non-overpunch final byte is corrupt; require documented overpunch and fail
            # loudly rather than inventing a sign.
            raise ZonedDecimalError(
                f"signed zoned field must end in a trailing-sign overpunch byte "
                f"({{-I positive, }}-R negative); got {last!r} in {raw!r}"
            )
        digits = body + last_digit

    # Build the Decimal from an explicit numeric string rather than via arithmetic.
    # WHY (Trade-off): string construction is independent of the active decimal
    # context precision/rounding, so the result is deterministic regardless of the
    # caller's global context -- essential for byte-reproducible golden comparisons.
    int_part = digits[:width - dec_digits] if dec_digits else digits
    frac_part = digits[width - dec_digits:] if dec_digits else ""
    if int_part == "":
        int_part = "0"
    number = int_part + (("." + frac_part) if dec_digits else "")
    return Decimal(("-" + number) if negative else number)


def encode_zoned(value: "Decimal | int | str", int_digits: int, dec_digits: int, signed: bool) -> str:
    """Encode a numeric value into a fixed-width zoned-decimal field (inverse of decode).

    Purpose
    -------
    Produce the exact fixed-width characters for a COBOL ``USAGE DISPLAY`` numeric
    field from an exact numeric value, applying trailing-sign overpunch for signed
    fields. It is the precise inverse of :func:`decode_zoned`, so that
    ``encode_zoned(decode_zoned(raw, a, d, s), a, d, s) == raw`` for every field the
    seeds contain (the round-trip law).

    Parameters
    ----------
    value : decimal.Decimal | int | str
        The numeric quantity to encode, interpreted as the *human* value (e.g. ``194``,
        ``"194.00"`` and ``Decimal("194.00")`` all encode 194 dollars, not 1.94).
        A :class:`float` is **rejected**: WHY -- a binary float cannot represent cents
        exactly, so accepting one would risk silently corrupting monetary data; callers
        must pass a ``Decimal`` / ``int`` / ``str`` instead. ``bool`` is likewise
        rejected because it is an ``int`` subclass and coercing ``True`` -> ``1`` would
        hide a caller mistake.
    int_digits : int
        Number of integer digit positions in the target field. Must be >= 0.
    dec_digits : int
        Number of fractional digit positions in the target field. Must be >= 0.
    signed : bool
        ``True`` to emit a trailing overpunch sign byte; ``False`` for a plain-digit
        unsigned field (which then rejects negative values).

    Returns
    -------
    str
        Exactly ``int_digits + dec_digits`` characters: zero-padded digits with the
        final byte overpunched when ``signed`` is ``True``.

    Raises
    ------
    ZonedDecimalError
        If ``value`` has more fractional precision than ``dec_digits`` (which would
        drop non-zero cents), if its magnitude needs more than ``int_digits`` integer
        positions (field overflow), or if a negative value is given for an unsigned
        field.
    TypeError
        If ``value`` is a ``float`` or ``bool`` (or any type other than
        ``Decimal`` / ``int`` / ``str``).
    """
    width = int_digits + dec_digits

    # Normalise the input type to Decimal WITHOUT ever going through float.
    if isinstance(value, bool):
        raise TypeError("encode_zoned does not accept bool; pass Decimal, int or str")
    if isinstance(value, float):
        raise TypeError(
            "encode_zoned does not accept float; a binary float cannot represent cents "
            "exactly -- pass a Decimal, int, or str instead"
        )
    if isinstance(value, Decimal):
        dec_value = value
    elif isinstance(value, int):
        dec_value = Decimal(value)
    elif isinstance(value, str):
        try:
            dec_value = Decimal(value)
        except InvalidOperation as exc:
            raise ZonedDecimalError(f"cannot parse {value!r} as a Decimal") from exc
    else:  # pragma: no cover - defensive guard for unexpected types
        raise TypeError(
            f"encode_zoned expects Decimal, int or str, not {type(value).__name__}"
        )

    # Quantize to the field scale inside a private context with generous precision.
    # WHY (Trade-off): rather than silently rounding, we quantize and then verify the
    # quantized value is numerically equal to the original. If they differ, the input
    # carried more precision than the field can hold, so we raise instead of dropping
    # cents -- financial correctness favours a loud failure over a quiet rounding.
    quant = Decimal(1).scaleb(-dec_digits)  # e.g. dec_digits=2 -> Decimal('0.01')
    try:
        with localcontext() as ctx:
            ctx.prec = width + 8  # comfortably wider than any field we encode
            quantized = dec_value.quantize(quant)
    except InvalidOperation as exc:
        raise ZonedDecimalError(
            f"value {dec_value} does not fit a {int_digits}.{dec_digits} field"
        ) from exc
    if quantized != dec_value:
        raise ZonedDecimalError(
            f"value {dec_value} has more precision than {dec_digits} decimal places"
        )

    negative = quantized.is_signed()  # preserves negative zero ('}' round-trip)

    # Scale away the implied point to obtain the unsigned integer magnitude string.
    scaled = quantized.scaleb(dec_digits).to_integral_value()
    magnitude = abs(int(scaled))
    digits = str(magnitude).rjust(width, "0")
    if len(digits) > width:
        raise ZonedDecimalError(
            f"value {dec_value} needs {len(digits)} digits but field holds {width}"
        )

    if not signed:
        if negative:
            raise ZonedDecimalError(f"cannot encode negative value {dec_value} in unsigned field")
        return digits

    # Signed: replace the final digit byte with its overpunched form.
    last_digit = int(digits[-1])
    table = _NEG_OVERPUNCH if negative else _POS_OVERPUNCH
    return digits[:-1] + table[last_digit]


# ---------------------------------------------------------------------------
# Field descriptor and record layout.
# ---------------------------------------------------------------------------
@dataclass(frozen=True)
class Field:
    """Immutable descriptor for one field within a fixed-width record.

    Purpose
    -------
    Capture everything the codec needs to slice and (de)serialise a single field:
    its name, its zero-based byte offset, its length, and how to interpret the bytes.
    Instances are frozen (WHY: Assumption) because a layout is a constant contract
    transcribed from a copybook -- making the descriptor immutable prevents a test
    from accidentally mutating a shared layout and corrupting every subsequent decode.

    Parameters
    ----------
    name : str
        The COBOL field name exactly as it appears in the copybook (e.g.
        ``"ACCT-CURR-BAL"``). Misspellings in the source copybook are preserved
        verbatim so the name remains a faithful contract.
    start : int
        Zero-based character offset of the field within the record.
    length : int
        Field length in characters/bytes.
    kind : str
        One of ``"text"`` (returned as-is / space padded), ``"uint"`` (unsigned
        display integer decoded to :class:`int`), or ``"zoned"`` (signed or unsigned
        zoned decimal decoded to :class:`decimal.Decimal`).
    int_digits : int
        For ``"zoned"`` fields, the number of integer digit positions. Ignored for
        other kinds. Defaults to 0.
    dec_digits : int
        For ``"zoned"`` fields, the number of fractional digit positions. Ignored for
        other kinds. Defaults to 0.
    signed : bool
        For ``"zoned"`` fields, whether the PIC carries a leading ``S`` (trailing
        overpunch sign). Defaults to ``False``.
    normalize_ts : bool
        ``True`` only for the *runtime-generated* processing timestamp
        (``PROC-TS``) that must be blanked before golden comparison. Defaults to
        ``False``. WHY (Refactoring Rationale): ``ORIG-TS`` is deliberately left
        ``False`` -- it holds the deterministic originating timestamp copied from the
        input transaction, so masking it would discard business data the comparison
        must verify.
    sensitive : bool
        ``True`` for fields that carry cardholder identity or payment data (PAN, SSN,
        embossed name, date of birth, government id). Such fields are redacted by
        :func:`mask_record` / :func:`mask_field` before appearing in any assertion
        message or golden diff. Defaults to ``False``.

    Returns
    -------
    Field
        A new immutable field descriptor.

    Raises
    ------
    None
    """

    name: str
    start: int  # 0-based offset within the record
    length: int
    kind: str  # "text" | "uint" | "zoned"
    int_digits: int = 0
    dec_digits: int = 0
    signed: bool = False
    normalize_ts: bool = False
    sensitive: bool = False

    @property
    def end(self) -> int:
        """Return the exclusive zero-based end offset (``start + length``).

        Purpose
        -------
        Provide the slice upper bound so callers read ``raw[field.start:field.end]``
        without recomputing the arithmetic.

        Parameters
        ----------
        None

        Returns
        -------
        int
            ``self.start + self.length``.

        Raises
        ------
        None
        """
        return self.start + self.length


def _validated_record(raw: str, reclen: int, *, context: str = "record") -> str:
    """Strip a single trailing newline and require the row to be exactly ``reclen``.

    Purpose
    -------
    Enforce the strict physical-record contract (CR-03): after removing at most one
    trailing line terminator, the row **must** be exactly ``reclen`` characters. A
    shorter, longer, or empty row is *rejected* -- never padded, truncated, or dropped.
    Every field offset in this codec is fixed, so a wrong-width row means the input or
    the layout is corrupt, and a financial harness must fail loudly rather than decode a
    misaligned money value.

    WHY (Refactoring Rationale): the previous helper silently padded short rows and
    truncated long ones "to keep every offset valid". That is precisely the behaviour a
    data-integrity review flagged as unsafe -- it could accept a truncated account
    record and post a garbage balance. The whole-corpus check performed during
    remediation confirmed every CardDemo fixture row is already full-width, so strict
    validation rejects only genuinely malformed input and breaks no legitimate fixture.

    WHY (Assumption): a *genuinely empty* dataset (a zero-byte file) is represented by
    the caller iterating zero rows, so it never reaches this function. An empty string
    passed here is therefore a malformed/blank row, not "empty input", and is rejected.

    Parameters
    ----------
    raw : str
        The raw record text, possibly ending in ``\\r``, ``\\n``, or ``\\r\\n``.
    reclen : int
        The exact required record length in characters.
    context : str
        A short label (e.g. the layout name) woven into the error message so a rejection
        pinpoints which record type failed. Defaults to ``"record"``.

    Returns
    -------
    str
        The row with one trailing newline removed, guaranteed to be exactly ``reclen``
        characters.

    Raises
    ------
    RecordLengthError
        If the row (after newline stripping) is not exactly ``reclen`` characters, or if
        it contains a non-ASCII (multi-byte) character, which would make the character
        count differ from the byte count and misalign the fixed field offsets (QA finding
        i2).
    """
    # Strip at most one trailing line terminator (``\r\n``, ``\n`` or ``\r``);
    # inner characters are never touched so embedded data is preserved.
    if raw.endswith("\r\n"):
        stripped = raw[:-2]
    elif raw.endswith("\n") or raw.endswith("\r"):
        stripped = raw[:-1]
    else:
        stripped = raw
    if len(stripped) != reclen:
        raise RecordLengthError(
            f"{context}: expected exactly {reclen} characters but got {len(stripped)} "
            f"-- nonconforming physical rows are rejected (no pad/truncate); "
            f"row={stripped[:64]!r}{'...' if len(stripped) > 64 else ''}"
        )
    # WHY (QA finding i2 -- byte-vs-character length guard): every field offset in this
    # codec is byte-oriented and every CardDemo field is fixed-width single-byte
    # (ASCII / EBCDIC-transcoded). ``len(stripped)`` counts CHARACTERS, so a record that
    # contains a multi-byte character (an accented letter, an emoji, a smart quote) can
    # satisfy the reclen *character* check above yet occupy MORE than reclen BYTES, silently
    # desynchronising every downstream offset and corrupting the record when it is written
    # back or compared as bytes -- exactly the kind of misaligned money value a financial
    # harness must never decode. Requiring the record be pure ASCII makes the character
    # count provably equal the byte count, so the width contract holds at the byte level
    # too. (Assumption verified across the corpus during remediation: every shipped fixture
    # and golden -- including the LOW-VALUES/0x00 filler batch programs emit, which IS ASCII
    # -- is already single-byte, so this rejects only genuinely mis-encoded input.)
    if not stripped.isascii():
        bad = next((c for c in stripped if ord(c) > 0x7F), "")
        raise RecordLengthError(
            f"{context}: record contains a non-ASCII character {bad!r} "
            f"(U+{ord(bad):04X}) -- fixed-width records must be single-byte ASCII so the "
            f"character count equals the byte count (QA finding i2); a multi-byte character "
            f"would misalign every downstream field offset."
        )
    return stripped


@dataclass(frozen=True)
class AlternateKey:
    """Descriptor for an alternate index defined on a record layout.

    Purpose
    -------
    Model a VSAM ``ALTERNATE RECORD KEY`` so the flat-to-indexed loader can build the
    same secondary index the production program reads by. CardDemo's cross-reference
    file (``CVACT03Y``) is keyed primarily on the 16-byte card number but is *also* read
    by the 11-byte account id -- ``CBACT04C`` performs ``READ ... KEY IS FD-XREF-ACCT-ID``
    -- so that account-id index must be reproduced or the interest program cannot look up
    its cards. Modelling alternate keys explicitly (rather than assuming all keys sit at
    offset zero) is the core of finding CR-02.

    Parameters
    ----------
    name : str
        The COBOL field name backing the alternate key (e.g. ``"XREF-ACCT-ID"``).
    offset : int
        Zero-based character offset of the alternate key within the record.
    length : int
        Alternate-key length in characters.
    duplicates : bool
        ``True`` when several records may share the same alternate-key value, requiring
        ``WITH DUPLICATES`` on the emitted ``ALTERNATE RECORD KEY``. For the XREF
        account-id index this is ``True`` because one account can own several cards.
        Defaults to ``True`` (WHY: Trade-off -- allowing duplicates is the safe default;
        a unique alternate index that receives a duplicate would fail the load, whereas a
        duplicate-tolerant index still supports the point-and-sequential reads the tests
        need).

    Returns
    -------
    AlternateKey
        A new immutable alternate-key descriptor.

    Raises
    ------
    None
    """

    name: str
    offset: int
    length: int
    duplicates: bool = True


@dataclass(frozen=True)
class RecordLayout:
    """A named, immutable fixed-width record layout composed of :class:`Field` s.

    Purpose
    -------
    Bind a logical record name, its total length, its key geometry, and its ordered
    list of fields into one object that can decode a raw line into a mapping of field
    values and encode such a mapping back into a byte-identical line. This is the unit
    that downstream helpers and tests import and share.

    Parameters
    ----------
    name : str
        Logical record name (e.g. ``"ACCOUNT"``).
    reclen : int
        Total record length in characters (must equal the sum of field lengths).
    key_length : int
        Length in characters of the record key (the leading key of an indexed file).
    fields : tuple[Field, ...]
        The ordered field descriptors covering the whole record.
    key_offset : int
        Zero-based offset of the primary key within the record. Defaults to 0. WHY
        (Refactoring Rationale): most CardDemo indexed files are keyed on their leading
        bytes, but this is now an explicit per-layout value rather than a global
        assumption, because the XREF file's primary key is the leading card number while
        its *alternate* key (see ``alternate_keys``) sits at offset 25.
    alternate_keys : tuple[AlternateKey, ...]
        Zero or more :class:`AlternateKey` descriptors modelling the record's secondary
        indexes. Empty for records with only a primary key. The XREF layout carries one
        entry for its account-id index so the loader can emit ``ALTERNATE RECORD KEY``
        and the tests can read XREF by account id exactly as ``CBACT04C`` does.

    Returns
    -------
    RecordLayout
        A new immutable layout.

    Raises
    ------
    None
    """

    name: str
    reclen: int
    key_length: int
    fields: tuple[Field, ...] = dc_field(default_factory=tuple)
    key_offset: int = 0
    alternate_keys: tuple[AlternateKey, ...] = dc_field(default_factory=tuple)

    def field(self, name: str) -> Field:
        """Return the :class:`Field` descriptor with the given name.

        Purpose
        -------
        Provide named access to a single field descriptor (e.g. to read its offset for
        a targeted assertion) without callers scanning ``self.fields`` themselves.

        Parameters
        ----------
        name : str
            The COBOL field name to look up (case-sensitive, exactly as declared).

        Returns
        -------
        Field
            The matching field descriptor.

        Raises
        ------
        KeyError
            If no field with ``name`` exists in this layout.
        """
        for fld in self.fields:
            if fld.name == name:
                return fld
        raise KeyError(f"{self.name} layout has no field named {name!r}")

    def key_of(self, raw: str) -> str:
        """Return the record key -- the ``key_length`` bytes at ``key_offset``.

        Purpose
        -------
        Extract the indexed-file key from a raw record so callers (notably the VSAM
        loader) can order and de-duplicate records exactly as the COBOL indexed file
        would key them.

        Parameters
        ----------
        raw : str
            The raw record line (any length; it is normalised to ``reclen`` first).

        Returns
        -------
        str
            The ``key_length`` key characters. Returned verbatim (not stripped) so the
            key matches the on-file bytes exactly.

        Raises
        ------
        RecordLengthError
            If ``raw`` is not exactly ``reclen`` characters (after one trailing newline
            is removed). WHY: keying a wrong-width row would slice a misaligned key and
            silently mis-order the indexed file, so the strict width check applies here
            too.
        """
        validated = _validated_record(raw, self.reclen, context=f"{self.name} key_of")
        return validated[self.key_offset:self.key_offset + self.key_length]

    def alt_key_of(self, raw: str, alt: "AlternateKey") -> str:
        """Return the ``alt.length`` alternate-key bytes at ``alt.offset``.

        Purpose
        -------
        Extract a secondary-index key from a raw record so the loader and alternate-key
        read tests can order/look up records by an alternate key (e.g. XREF by account
        id) exactly as the production indexed file does.

        Parameters
        ----------
        raw : str
            The raw record line (validated to ``reclen`` first).
        alt : AlternateKey
            The alternate-key descriptor whose ``offset``/``length`` are sliced out.

        Returns
        -------
        str
            The ``alt.length`` alternate-key characters, verbatim.

        Raises
        ------
        RecordLengthError
            If ``raw`` is not exactly ``reclen`` characters.
        KeyError
            If ``alt`` is not one of this layout's :attr:`alternate_keys`.
        """
        if alt not in self.alternate_keys:
            raise KeyError(f"{self.name} has no alternate key {alt.name!r}")
        validated = _validated_record(raw, self.reclen, context=f"{self.name} alt_key_of")
        return validated[alt.offset:alt.offset + alt.length]

    def decode(self, raw: str) -> dict[str, Any]:
        """Decode a raw record into a mapping of field name -> typed value.

        Purpose
        -------
        Turn one fixed-width line into a dictionary keyed by copybook field name,
        converting each field according to its :attr:`Field.kind`: ``"text"`` fields
        are returned as their exact bytes (**not** stripped, so callers control any
        trimming), ``"uint"`` fields become :class:`int`, and ``"zoned"`` fields become
        :class:`decimal.Decimal` via :func:`decode_zoned`.

        Parameters
        ----------
        raw : str
            The raw record line. A single trailing newline is removed, after which the
            row **must** be exactly ``reclen`` characters (see :func:`_validated_record`)
            -- short/long rows are rejected, not repaired.

        Returns
        -------
        dict[str, Any]
            Mapping from field name to decoded value (``str`` / ``int`` / ``Decimal``).
            Text values retain trailing spaces; use :meth:`decode_stripped` for trimmed
            text.

        Raises
        ------
        RecordLengthError
            If ``raw`` is not exactly ``reclen`` characters after newline stripping.
        ZonedDecimalError
            Propagated from :func:`decode_zoned` if a zoned field is malformed.
        ValueError
            If a ``"uint"`` field contains characters that are neither digits nor blank.
        """
        padded = _validated_record(raw, self.reclen, context=f"{self.name} decode")
        out: dict[str, Any] = {}
        for fld in self.fields:
            chunk = padded[fld.start:fld.end]
            if fld.kind == "text":
                out[fld.name] = chunk
            elif fld.kind == "uint":
                out[fld.name] = self._decode_uint(fld, chunk)
            elif fld.kind == "zoned":
                out[fld.name] = decode_zoned(chunk, fld.int_digits, fld.dec_digits, fld.signed)
            else:  # pragma: no cover - guarded by layout construction/self-check
                raise ValueError(f"unknown field kind {fld.kind!r} for {fld.name!r}")
        return out

    def decode_stripped(self, raw: str) -> dict[str, Any]:
        """Decode a raw record, additionally right-stripping every ``"text"`` field.

        Purpose
        -------
        Convenience wrapper over :meth:`decode` for the common case where callers want
        trailing COBOL space padding removed from text fields (e.g. comparing a name or
        a date) while numeric fields stay exact.

        Parameters
        ----------
        raw : str
            The raw record line (normalised to ``reclen`` internally).

        Returns
        -------
        dict[str, Any]
            Same mapping as :meth:`decode`, but with ``str`` values ``rstrip``-ed of
            trailing whitespace. ``int`` and ``Decimal`` values are unchanged.

        Raises
        ------
        ZonedDecimalError
            Propagated from :func:`decode_zoned` if a zoned field is malformed.
        ValueError
            If a ``"uint"`` field contains characters that are neither digits nor blank.
        """
        decoded = self.decode(raw)
        # WHY: only text fields carry meaningful trailing padding; stripping the numeric
        # results would either be a no-op (int/Decimal) or lose the fixed scale, so we
        # trim strings exclusively.
        return {k: (v.rstrip() if isinstance(v, str) else v) for k, v in decoded.items()}

    def encode(self, values: dict[str, Any]) -> str:
        """Encode a mapping of field values into a byte-exact fixed-width record.

        Purpose
        -------
        Serialise a field mapping back into a line of exactly ``reclen`` characters,
        the inverse of :meth:`decode`. Feeding :meth:`decode`'s output straight back in
        reproduces the original bytes (the round-trip law), because every field --
        including ``FILLER`` -- is present in that mapping.

        Missing keys are filled with type-appropriate defaults: ``"text"`` fields become
        spaces, ``"uint"`` fields become zero, and ``"zoned"`` fields become ``+0``. This
        lets callers build a record from only the fields they care about.

        Parameters
        ----------
        values : dict[str, Any]
            Mapping from field name to value. ``"text"`` values are coerced with
            :func:`str` and left-justified; ``"uint"`` values must be non-negative
            integers; ``"zoned"`` values are any :func:`encode_zoned`-acceptable type
            (``Decimal`` / ``int`` / ``str``, never ``float``).

        Returns
        -------
        str
            The assembled record, exactly ``reclen`` characters long.

        Raises
        ------
        ZonedDecimalError
            From :func:`encode_zoned` (precision loss / overflow / negative-in-unsigned),
            or if an assembled record does not total ``reclen`` characters.
        ValueError
            If a ``"text"`` field value is longer than the field, or a ``"uint"`` value
            is negative or has too many digits.
        TypeError
            From :func:`encode_zoned` if a ``"zoned"`` value is a ``float``/``bool``.
        """
        parts: list[str] = []
        for fld in self.fields:
            if fld.kind == "text":
                parts.append(self._encode_text(fld, values.get(fld.name, "")))
            elif fld.kind == "uint":
                parts.append(self._encode_uint(fld, values.get(fld.name, 0)))
            elif fld.kind == "zoned":
                raw_val = values.get(fld.name, 0)
                parts.append(encode_zoned(raw_val, fld.int_digits, fld.dec_digits, fld.signed))
            else:  # pragma: no cover - guarded by layout construction/self-check
                raise ValueError(f"unknown field kind {fld.kind!r} for {fld.name!r}")
        record = "".join(parts)
        if len(record) != self.reclen:
            # WHY: a length mismatch means the field table and reclen disagree -- a
            # programming error in the layout, surfaced immediately rather than shipping
            # a malformed record to a COBOL program that would read past its RECLEN.
            raise ZonedDecimalError(
                f"{self.name} encoded to {len(record)} chars but reclen is {self.reclen}"
            )
        return record

    @staticmethod
    def _decode_uint(fld: Field, chunk: str) -> int:
        """Decode one unsigned display-numeric field to an :class:`int`.

        Purpose
        -------
        Interpret a ``9(n)`` field as a base-10 integer, tolerating an all-blank field
        (an uninitialised numeric-display area) as zero.

        Parameters
        ----------
        fld : Field
            The field descriptor (used only for its name in error messages).
        chunk : str
            The exact field bytes.

        Returns
        -------
        int
            The decoded non-negative integer (blank field -> 0).

        Raises
        ------
        ValueError
            If the field contains characters other than digits and surrounding blanks.
        """
        text = chunk.strip()
        if text == "":
            # WHY (Assumption): a space-filled numeric-display field is an uninitialised
            # value; COBOL would read it as spaces, and the test harness represents that
            # as 0 so downstream arithmetic assertions have a concrete number.
            return 0
        if _DIGITS_RE.fullmatch(text) is None:
            raise ValueError(f"uint field {fld.name!r} has non-digit content: {chunk!r}")
        return int(text)

    @staticmethod
    def _encode_text(fld: Field, value: Any) -> str:
        """Encode one text field, left-justified and space-padded to its length.

        Purpose
        -------
        Render a value as COBOL ``PIC X`` text: left-justified within the field and
        padded on the right with spaces, matching how the programs write alphanumeric
        fields.

        Parameters
        ----------
        fld : Field
            The field descriptor providing the target ``length`` and ``name``.
        value : Any
            The value to render; coerced to ``str`` before padding.

        Returns
        -------
        str
            Exactly ``fld.length`` characters.

        Raises
        ------
        ValueError
            If ``str(value)`` is longer than ``fld.length`` (truncation would silently
            drop data, so it is rejected).
        """
        text = value if isinstance(value, str) else str(value)
        if len(text) > fld.length:
            raise ValueError(
                f"text field {fld.name!r} value {text!r} exceeds length {fld.length}"
            )
        return text.ljust(fld.length)

    @staticmethod
    def _encode_uint(fld: Field, value: Any) -> str:
        """Encode one unsigned integer field, zero-padded to its length.

        Purpose
        -------
        Render a non-negative integer as a ``9(n)`` field: right-justified and
        zero-filled on the left, matching COBOL unsigned display numerics.

        Parameters
        ----------
        fld : Field
            The field descriptor providing the target ``length`` and ``name``.
        value : Any
            The value to render; accepts an ``int`` or a decimal string of digits.

        Returns
        -------
        str
            Exactly ``fld.length`` digit characters.

        Raises
        ------
        ValueError
            If the value is negative, non-integer, or needs more than ``fld.length``
            digits.
        """
        if isinstance(value, bool):
            # WHY: bool is an int subclass; silently encoding True->1 would hide a
            # caller error, so reject it explicitly.
            raise ValueError(f"uint field {fld.name!r} does not accept bool")
        if isinstance(value, int):
            ivalue = value
        else:
            text = str(value).strip()
            if _DIGITS_RE.fullmatch(text) is None:
                raise ValueError(f"uint field {fld.name!r} value {value!r} is not a non-negative integer")
            ivalue = int(text) if text else 0
        if ivalue < 0:
            raise ValueError(f"uint field {fld.name!r} cannot hold negative value {ivalue}")
        digits = str(ivalue)
        if len(digits) > fld.length:
            raise ValueError(
                f"uint field {fld.name!r} value {ivalue} needs {len(digits)} digits "
                f"but field holds {fld.length}"
            )
        return digits.rjust(fld.length, "0")


# ---------------------------------------------------------------------------
# Record layouts -- transcribed field-for-field from the copybooks.
#
# WHY (single source of truth): these three layouts are the authoritative Python
# mirror of the copybook record structures. Every field below cites its copybook
# name and PIC clause; downstream helpers/tests import these constants rather than
# re-declaring offsets, so a copybook change is reflected in exactly one place.
# The 0-based ``start`` offsets are cumulative field lengths; the import-time
# :func:`_validate_layouts` self-check proves each layout's field lengths sum to
# its declared reclen.
# ---------------------------------------------------------------------------

# ACCOUNT-RECORD -- app/cpy/CVACT01Y.cpy, RECLN 300, key = ACCT-ID(11) @ offset 0.
ACCOUNT_LAYOUT = RecordLayout(
    name="ACCOUNT",
    reclen=300,
    key_length=11,
    key_offset=0,
    fields=(
        Field("ACCT-ID", 0, 11, "uint"),                                 # CVACT01Y PIC 9(11)
        Field("ACCT-ACTIVE-STATUS", 11, 1, "text"),                      # CVACT01Y PIC X(01)
        Field("ACCT-CURR-BAL", 12, 12, "zoned", 10, 2, True),            # CVACT01Y PIC S9(10)V99
        Field("ACCT-CREDIT-LIMIT", 24, 12, "zoned", 10, 2, True),        # CVACT01Y PIC S9(10)V99
        Field("ACCT-CASH-CREDIT-LIMIT", 36, 12, "zoned", 10, 2, True),   # CVACT01Y PIC S9(10)V99
        Field("ACCT-OPEN-DATE", 48, 10, "text"),                         # CVACT01Y PIC X(10)
        # NOTE: the copybook misspells "EXPIRATION" as "EXPIRAION"; the field name is
        # preserved verbatim (WHY: it is a contract -- programs and fixtures key on the
        # exact spelling, so "correcting" it here would break the single-source match).
        Field("ACCT-EXPIRAION-DATE", 58, 10, "text"),                    # CVACT01Y PIC X(10)
        Field("ACCT-REISSUE-DATE", 68, 10, "text"),                      # CVACT01Y PIC X(10)
        Field("ACCT-CURR-CYC-CREDIT", 78, 12, "zoned", 10, 2, True),     # CVACT01Y PIC S9(10)V99
        Field("ACCT-CURR-CYC-DEBIT", 90, 12, "zoned", 10, 2, True),      # CVACT01Y PIC S9(10)V99
        Field("ACCT-ADDR-ZIP", 102, 10, "text"),                         # CVACT01Y PIC X(10)
        # A blank ACCT-GROUP-ID drives the CBACT04C DEFAULT disclosure-group fallback.
        Field("ACCT-GROUP-ID", 112, 10, "text"),                         # CVACT01Y PIC X(10)
        Field("FILLER", 122, 178, "text"),                               # CVACT01Y PIC X(178)
    ),
)

# DALYTRAN-RECORD -- app/cpy/CVTRA06Y.cpy, RECLN 350, key = DALYTRAN-ID(16) @ offset 0.
DALYTRAN_LAYOUT = RecordLayout(
    name="DALYTRAN",
    reclen=350,
    key_length=16,
    key_offset=0,
    fields=(
        Field("DALYTRAN-ID", 0, 16, "text"),                             # CVTRA06Y PIC X(16)
        Field("DALYTRAN-TYPE-CD", 16, 2, "text"),                        # CVTRA06Y PIC X(02)
        Field("DALYTRAN-CAT-CD", 18, 4, "uint"),                         # CVTRA06Y PIC 9(04)
        Field("DALYTRAN-SOURCE", 22, 10, "text"),                        # CVTRA06Y PIC X(10)
        Field("DALYTRAN-DESC", 32, 100, "text"),                         # CVTRA06Y PIC X(100)
        Field("DALYTRAN-AMT", 132, 11, "zoned", 9, 2, True),             # CVTRA06Y PIC S9(09)V99
        Field("DALYTRAN-MERCHANT-ID", 143, 9, "uint"),                   # CVTRA06Y PIC 9(09)
        Field("DALYTRAN-MERCHANT-NAME", 152, 50, "text"),                # CVTRA06Y PIC X(50)
        Field("DALYTRAN-MERCHANT-CITY", 202, 50, "text"),                # CVTRA06Y PIC X(50)
        Field("DALYTRAN-MERCHANT-ZIP", 252, 10, "text"),                 # CVTRA06Y PIC X(10)
        Field("DALYTRAN-CARD-NUM", 262, 16, "text", sensitive=True),     # CVTRA06Y PIC X(16) PAN
        # ORIG-TS is the deterministic originating timestamp (copied from the source
        # transaction); it is preserved so golden comparison verifies it. Only PROC-TS
        # is the runtime wall-clock stamp, so ONLY it is flagged normalize_ts (WHY:
        # Refactoring Rationale -- blanking ORIG-TS too, as an earlier build did,
        # discarded business data and shortened the effective compared record).
        Field("DALYTRAN-ORIG-TS", 278, 26, "text"),                      # CVTRA06Y PIC X(26)
        Field("DALYTRAN-PROC-TS", 304, 26, "text", normalize_ts=True),   # CVTRA06Y PIC X(26) runtime
        Field("FILLER", 330, 20, "text"),                                # CVTRA06Y PIC X(20)
    ),
)

# DIS-GROUP-RECORD -- app/cpy/CVTRA02Y.cpy, RECLN 50, key = DIS-GROUP-KEY(16) @ offset 0.
# WHY: the copybook groups the first three fields under DIS-GROUP-KEY (16 bytes); they
# are modelled here as three separate leaf fields and the 16-byte composite key is
# exposed through key_length/key_of, keeping both the parts and the whole addressable.
DISGROUP_LAYOUT = RecordLayout(
    name="DISGROUP",
    reclen=50,
    key_length=16,
    key_offset=0,
    fields=(
        Field("DIS-ACCT-GROUP-ID", 0, 10, "text"),                       # CVTRA02Y PIC X(10)
        Field("DIS-TRAN-TYPE-CD", 10, 2, "text"),                        # CVTRA02Y PIC X(02)
        Field("DIS-TRAN-CAT-CD", 12, 4, "uint"),                         # CVTRA02Y PIC 9(04)
        Field("DIS-INT-RATE", 16, 6, "zoned", 4, 2, True),               # CVTRA02Y PIC S9(04)V99
        Field("FILLER", 22, 28, "text"),                                 # CVTRA02Y PIC X(28)
    ),
)

# CARD-XREF-RECORD -- app/cpy/CVACT03Y.cpy, RECLN 50.
# Primary key = XREF-CARD-NUM(16) @ 0; ALTERNATE key = XREF-ACCT-ID(11) @ 25.
# WHY (Contract Fidelity, CR-02): CBACT04C reads this file by account id
# (``READ ... KEY IS FD-XREF-ACCT-ID``), so the alternate index at offset 25 is a hard
# requirement, not an optimisation. One account may own several cards, hence the
# alternate key is declared WITH DUPLICATES (duplicates=True default on AlternateKey).
XREF_LAYOUT = RecordLayout(
    name="XREF",
    reclen=50,
    key_length=16,
    key_offset=0,
    alternate_keys=(AlternateKey("XREF-ACCT-ID", 25, 11, duplicates=True),),
    fields=(
        Field("XREF-CARD-NUM", 0, 16, "text", sensitive=True),           # CVACT03Y PIC X(16) PAN
        Field("XREF-CUST-ID", 16, 9, "uint"),                            # CVACT03Y PIC 9(09)
        Field("XREF-ACCT-ID", 25, 11, "uint"),                           # CVACT03Y PIC 9(11) alt key
        Field("FILLER", 36, 14, "text"),                                 # CVACT03Y PIC X(14)
    ),
)

# TRAN-CAT-BAL-RECORD -- app/cpy/CVTRA01Y.cpy, RECLN 50.
# key = TRAN-CAT-KEY(17) @ 0 = TRANCAT-ACCT-ID(11) + TRANCAT-TYPE-CD(2) + TRANCAT-CD(4).
# WHY: the copybook groups the first three fields under the composite key; they are
# modelled as three leaf fields and the 17-byte composite is exposed via key_length,
# keeping both the parts and the whole addressable (mirrors the DISGROUP approach).
TCATBAL_LAYOUT = RecordLayout(
    name="TCATBAL",
    reclen=50,
    key_length=17,
    key_offset=0,
    fields=(
        Field("TRANCAT-ACCT-ID", 0, 11, "uint"),                         # CVTRA01Y PIC 9(11)
        Field("TRANCAT-TYPE-CD", 11, 2, "text"),                         # CVTRA01Y PIC X(02)
        Field("TRANCAT-CD", 13, 4, "uint"),                              # CVTRA01Y PIC 9(04)
        Field("TRAN-CAT-BAL", 17, 11, "zoned", 9, 2, True),              # CVTRA01Y PIC S9(09)V99
        Field("FILLER", 28, 22, "text"),                                 # CVTRA01Y PIC X(22)
    ),
)

# CARD-RECORD -- app/cpy/CVACT02Y.cpy, RECLN 150, key = CARD-NUM(16) @ 0.
# WHY (Privacy, MA-13): the card number, CVV, and embossed name are cardholder
# payment/identity data and are flagged sensitive so diagnostics redact them.
CARD_LAYOUT = RecordLayout(
    name="CARD",
    reclen=150,
    key_length=16,
    key_offset=0,
    fields=(
        Field("CARD-NUM", 0, 16, "text", sensitive=True),                # CVACT02Y PIC X(16) PAN
        Field("CARD-ACCT-ID", 16, 11, "uint"),                           # CVACT02Y PIC 9(11)
        Field("CARD-CVV-CD", 27, 3, "uint", sensitive=True),             # CVACT02Y PIC 9(03) CVV
        Field("CARD-EMBOSSED-NAME", 30, 50, "text", sensitive=True),     # CVACT02Y PIC X(50) name
        Field("CARD-EXPIRAION-DATE", 80, 10, "text"),                    # CVACT02Y PIC X(10) [sic]
        Field("CARD-ACTIVE-STATUS", 90, 1, "text"),                      # CVACT02Y PIC X(01)
        Field("FILLER", 91, 59, "text"),                                 # CVACT02Y PIC X(59)
    ),
)

# CUSTOMER-RECORD -- app/cpy/CVCUS01Y.cpy, RECLN 500, key = CUST-ID(9) @ 0.
# WHY (Privacy, MA-13): names, address, phones, SSN, government id, and date of birth
# are personally identifying and flagged sensitive so no complete customer identity
# can appear in an assertion message or golden diff.
CUSTOMER_LAYOUT = RecordLayout(
    name="CUSTOMER",
    reclen=500,
    key_length=9,
    key_offset=0,
    fields=(
        Field("CUST-ID", 0, 9, "uint"),                                  # CVCUS01Y PIC 9(09)
        Field("CUST-FIRST-NAME", 9, 25, "text", sensitive=True),         # CVCUS01Y PIC X(25)
        Field("CUST-MIDDLE-NAME", 34, 25, "text", sensitive=True),       # CVCUS01Y PIC X(25)
        Field("CUST-LAST-NAME", 59, 25, "text", sensitive=True),         # CVCUS01Y PIC X(25)
        Field("CUST-ADDR-LINE-1", 84, 50, "text", sensitive=True),       # CVCUS01Y PIC X(50)
        Field("CUST-ADDR-LINE-2", 134, 50, "text", sensitive=True),      # CVCUS01Y PIC X(50)
        Field("CUST-ADDR-LINE-3", 184, 50, "text", sensitive=True),      # CVCUS01Y PIC X(50)
        Field("CUST-ADDR-STATE-CD", 234, 2, "text"),                     # CVCUS01Y PIC X(02)
        Field("CUST-ADDR-COUNTRY-CD", 236, 3, "text"),                   # CVCUS01Y PIC X(03)
        Field("CUST-ADDR-ZIP", 239, 10, "text"),                         # CVCUS01Y PIC X(10)
        Field("CUST-PHONE-NUM-1", 249, 15, "text", sensitive=True),      # CVCUS01Y PIC X(15)
        Field("CUST-PHONE-NUM-2", 264, 15, "text", sensitive=True),      # CVCUS01Y PIC X(15)
        Field("CUST-SSN", 279, 9, "uint", sensitive=True),               # CVCUS01Y PIC 9(09) SSN
        Field("CUST-GOVT-ISSUED-ID", 288, 20, "text", sensitive=True),   # CVCUS01Y PIC X(20)
        Field("CUST-DOB-YYYY-MM-DD", 308, 10, "text", sensitive=True),   # CVCUS01Y PIC X(10) DOB
        Field("CUST-EFT-ACCOUNT-ID", 318, 10, "text", sensitive=True),   # CVCUS01Y PIC X(10)
        Field("CUST-PRI-CARD-HOLDER-IND", 328, 1, "text"),               # CVCUS01Y PIC X(01)
        Field("CUST-FICO-CREDIT-SCORE", 329, 3, "uint"),                 # CVCUS01Y PIC 9(03)
        Field("FILLER", 332, 168, "text"),                               # CVCUS01Y PIC X(168)
    ),
)

# TRAN-RECORD -- app/cpy/CVTRA05Y.cpy, RECLN 350, key = TRAN-ID(16) @ 0.
# This is the persisted TRANSACT (a.k.a. "TRNX") record the posting/statement programs
# read and write. Its geometry matches DALYTRAN but with distinct field names.
# WHY (CR-04): ORIG-TS is the deterministic originating timestamp (preserved); only the
# runtime PROC-TS is flagged normalize_ts. TRAN-CARD-NUM is a PAN (sensitive).
TRAN_LAYOUT = RecordLayout(
    name="TRAN",
    reclen=350,
    key_length=16,
    key_offset=0,
    fields=(
        Field("TRAN-ID", 0, 16, "text"),                                 # CVTRA05Y PIC X(16)
        Field("TRAN-TYPE-CD", 16, 2, "text"),                            # CVTRA05Y PIC X(02)
        Field("TRAN-CAT-CD", 18, 4, "uint"),                             # CVTRA05Y PIC 9(04)
        Field("TRAN-SOURCE", 22, 10, "text"),                            # CVTRA05Y PIC X(10)
        Field("TRAN-DESC", 32, 100, "text"),                             # CVTRA05Y PIC X(100)
        Field("TRAN-AMT", 132, 11, "zoned", 9, 2, True),                 # CVTRA05Y PIC S9(09)V99
        Field("TRAN-MERCHANT-ID", 143, 9, "uint"),                       # CVTRA05Y PIC 9(09)
        Field("TRAN-MERCHANT-NAME", 152, 50, "text"),                    # CVTRA05Y PIC X(50)
        Field("TRAN-MERCHANT-CITY", 202, 50, "text"),                    # CVTRA05Y PIC X(50)
        Field("TRAN-MERCHANT-ZIP", 252, 10, "text"),                     # CVTRA05Y PIC X(10)
        Field("TRAN-CARD-NUM", 262, 16, "text", sensitive=True),         # CVTRA05Y PIC X(16) PAN
        Field("TRAN-ORIG-TS", 278, 26, "text"),                          # CVTRA05Y PIC X(26) deterministic
        Field("TRAN-PROC-TS", 304, 26, "text", normalize_ts=True),       # CVTRA05Y PIC X(26) runtime
        Field("FILLER", 330, 20, "text"),                                # CVTRA05Y PIC X(20)
    ),
)

# TRNX-RECORD -- app/cpy/COSTM01.CPY, RECLN 350, key = TRNX-KEY(32) @ 0.
# WHY (Contract Fidelity, MA-11): the *statement* program (CBSTM03A via the CBSTM03B
# I/O subprogram) reads the transaction file through this copybook, which is a DISTINCT
# layout from CVTRA05Y/TRAN -- it leads with a 32-byte composite key (TRNX-CARD-NUM +
# TRNX-ID) and therefore places TRNX-AMT at offset 148, not 132. The statement fixtures
# (``trnxfile.txt``) are encoded in this layout, so "TRNX" must be its own record type,
# NOT an alias of TRAN (an earlier build's alias mis-decoded every statement amount).
TRNX_LAYOUT = RecordLayout(
    name="TRNX",
    reclen=350,
    key_length=32,   # TRNX-KEY = TRNX-CARD-NUM(16) + TRNX-ID(16)
    key_offset=0,
    fields=(
        Field("TRNX-CARD-NUM", 0, 16, "text", sensitive=True),           # COSTM01 PIC X(16) PAN
        Field("TRNX-ID", 16, 16, "text"),                                # COSTM01 PIC X(16)
        Field("TRNX-TYPE-CD", 32, 2, "text"),                            # COSTM01 PIC X(02)
        Field("TRNX-CAT-CD", 34, 4, "uint"),                             # COSTM01 PIC 9(04)
        Field("TRNX-SOURCE", 38, 10, "text"),                            # COSTM01 PIC X(10)
        Field("TRNX-DESC", 48, 100, "text"),                             # COSTM01 PIC X(100)
        Field("TRNX-AMT", 148, 11, "zoned", 9, 2, True),                 # COSTM01 PIC S9(09)V99
        Field("TRNX-MERCHANT-ID", 159, 9, "uint"),                       # COSTM01 PIC 9(09)
        Field("TRNX-MERCHANT-NAME", 168, 50, "text"),                    # COSTM01 PIC X(50)
        Field("TRNX-MERCHANT-CITY", 218, 50, "text"),                    # COSTM01 PIC X(50)
        Field("TRNX-MERCHANT-ZIP", 268, 10, "text"),                     # COSTM01 PIC X(10)
        Field("TRNX-ORIG-TS", 278, 26, "text"),                          # COSTM01 PIC X(26) deterministic
        Field("TRNX-PROC-TS", 304, 26, "text", normalize_ts=True),       # COSTM01 PIC X(26) runtime
        Field("FILLER", 330, 20, "text"),                                # COSTM01 PIC X(20)
    ),
)

# REJECT-RECORD -- CBTRN02C DALYREJS output, RECLN 430, key = DALYTRAN-ID(16) @ 0.
# WHY (Contract Fidelity, MA-11 / QA finding M1+M3): CBTRN02C writes a rejected daily
# transaction as its verbatim 350-byte DALYTRAN image (REJECT-TRAN-DATA PIC X(350))
# immediately followed by an 80-byte VALIDATION-TRAILER = WS-VALIDATION-FAIL-REASON
# PIC 9(04) + WS-VALIDATION-FAIL-REASON-DESC PIC X(76). Modelling this as a first-class
# 430-byte record type lets golden_compare run the reject stream in *record mode*
# (byte-exact, width-enforced) instead of the lenient text mode that a 430-byte line
# was forced into before -- text mode rstrips trailing spaces and scrubs any
# space-format timestamp anywhere on the line, so a wrong reason code padded with the
# right number of blanks, a same-format-but-wrong ORIG-TS, or a width drift could all
# slip through undetected.
#
# The 350-byte prefix reuses DALYTRAN_LAYOUT.fields verbatim (single source of truth for
# the CVTRA06Y geometry): this inherits ORIG-TS@278 normalize_ts=False (the deterministic
# originating stamp copied from the input transaction is PRESERVED and therefore
# asserted) and PROC-TS@304 normalize_ts=True (the runtime wall-clock stamp is blanked
# before comparison). In the committed reject goldens PROC-TS is unset (26 blanks), which
# the normaliser accepts as a legitimate empty value.
#
# WHY key_length=16 (Assumption): the reject record leads with DALYTRAN-ID, so its key
# geometry matches DALYTRAN even though DALYREJS is a *sequential* (not indexed) output;
# key_length is carried for layout self-consistency and is harmless for sequential use.
REJECT_LAYOUT = RecordLayout(
    name="REJECT",
    reclen=430,
    key_length=16,
    key_offset=0,
    fields=DALYTRAN_LAYOUT.fields + (
        # VALIDATION-TRAILER (CBTRN02C working storage, appended to the 350-byte image).
        Field("WS-VALIDATION-FAIL-REASON", 350, 4, "uint"),              # PIC 9(04) reason 100-103
        Field("WS-VALIDATION-FAIL-REASON-DESC", 354, 76, "text"),        # PIC X(76) reason text
    ),
)

# INT-TRAN-RECORD -- CBACT04C interest-transaction output, CVTRA05Y geometry (350B/key16).
# WHY (QA finding C3 / determinism): CBACT04C writes each accrued-interest transaction to
# the TRANSACT file using the CVTRA05Y layout, but -- UNLIKE an ordinary posted transaction
# whose TRAN-ORIG-TS is the deterministic originating stamp copied from the source
# transaction -- CBACT04C sets BOTH TRAN-ORIG-TS *and* TRAN-PROC-TS to the runtime
# CURRENT-DATE (DB2-FORMAT-TS, dash/dot: YYYY-MM-DD-HH.MM.SS.NNNNNN; see CBACT04C moving
# DB2-FORMAT-TS to both TRAN-ORIG-TS and TRAN-PROC-TS). Under the base TRAN layout only
# PROC-TS is normalised, so the run-generated ORIG-TS would make every interest golden
# non-deterministic. This variant therefore flags BOTH timestamps normalize_ts=True so
# golden comparison blanks them together.
#
# WHY a SEPARATE layout, not a change to TRAN (Alternatives Considered): ordinary posted
# transactions MUST keep asserting their deterministic ORIG-TS, so TRAN cannot blank it.
# A per-call normalize_ts override was also considered but rejected -- a named layout
# integrates transparently with golden_compare's record mode (which looks up
# LAYOUTS[name]) and keeps the policy declarative and discoverable.
# WHY dc_replace (Refactoring Rationale): the field list is cloned from TRAN_LAYOUT with
# only TRAN-ORIG-TS's normalize_ts flipped, so the 350-byte CVTRA05Y geometry stays
# single-sourced from TRAN and cannot drift.
INTTRAN_LAYOUT = RecordLayout(
    name="INTTRAN",
    reclen=350,
    key_length=16,
    key_offset=0,
    fields=tuple(
        dc_replace(fld, normalize_ts=True) if fld.name == "TRAN-ORIG-TS" else fld
        for fld in TRAN_LAYOUT.fields
    ),
)

# Registry keyed by logical record name. WHY: a single dict lets helpers such as
# vsam_loader resolve reclen/keylen by name (e.g. reclen_of("ACCOUNT")) instead of
# importing each layout constant individually. TRAN (CVTRA05Y, posting output) and TRNX
# (COSTM01, statement input) are deliberately SEPARATE record types with different
# geometry, not aliases.
LAYOUTS: dict[str, RecordLayout] = {
    "ACCOUNT": ACCOUNT_LAYOUT,
    "DALYTRAN": DALYTRAN_LAYOUT,
    "DISGROUP": DISGROUP_LAYOUT,
    "XREF": XREF_LAYOUT,
    "TCATBAL": TCATBAL_LAYOUT,
    "CARD": CARD_LAYOUT,
    "CUSTOMER": CUSTOMER_LAYOUT,
    "TRAN": TRAN_LAYOUT,
    "TRNX": TRNX_LAYOUT,
    "REJECT": REJECT_LAYOUT,
    "INTTRAN": INTTRAN_LAYOUT,
}


def reclen_of(name: str) -> int:
    """Return the record length for a registered logical record name.

    Purpose
    -------
    Give the VSAM loader and other helpers a name-based way to size a fixed-width file
    (its ``RECLEN``) without importing the layout constant directly.

    Parameters
    ----------
    name : str
        A key of :data:`LAYOUTS` (e.g. ``"ACCOUNT"``, ``"DALYTRAN"``, ``"DISGROUP"``).

    Returns
    -------
    int
        The record length in characters.

    Raises
    ------
    KeyError
        If ``name`` is not a registered layout.
    """
    try:
        return LAYOUTS[name].reclen
    except KeyError:
        raise KeyError(f"unknown record layout {name!r}; known: {sorted(LAYOUTS)}") from None


def keylen_of(name: str) -> int:
    """Return the key length for a registered logical record name.

    Purpose
    -------
    Give the VSAM loader a name-based way to obtain the indexed-file key length so it
    can build the ``ORGANIZATION IS INDEXED`` key without duplicating the geometry.

    Parameters
    ----------
    name : str
        A key of :data:`LAYOUTS` (e.g. ``"ACCOUNT"``, ``"DALYTRAN"``, ``"DISGROUP"``).

    Returns
    -------
    int
        The key length in characters.

    Raises
    ------
    KeyError
        If ``name`` is not a registered layout.
    """
    try:
        return LAYOUTS[name].key_length
    except KeyError:
        raise KeyError(f"unknown record layout {name!r}; known: {sorted(LAYOUTS)}") from None


def alternate_keys_of(name: str) -> tuple[AlternateKey, ...]:
    """Return the alternate-key descriptors for a registered logical record name.

    Purpose
    -------
    Give the VSAM loader a name-based way to discover a layout's secondary indexes so it
    can emit ``ALTERNATE RECORD KEY`` clauses (notably the XREF account-id index that
    ``CBACT04C`` reads by) without importing the layout constant directly.

    Parameters
    ----------
    name : str
        A key of :data:`LAYOUTS` (e.g. ``"XREF"``).

    Returns
    -------
    tuple[AlternateKey, ...]
        The layout's alternate keys, empty for records that have only a primary key.

    Raises
    ------
    KeyError
        If ``name`` is not a registered layout.
    """
    try:
        return LAYOUTS[name].alternate_keys
    except KeyError:
        raise KeyError(f"unknown record layout {name!r}; known: {sorted(LAYOUTS)}") from None


# Offsets within a 26-char timestamp where a SEPARATOR (not a digit) is expected. Both
# dialects CardDemo emits share this shape, differing only in which separator sits where:
#   space format : YYYY-MM-DD HH:MM:SS.ffffff   (seeds / CBTRN02C -- '-','-',' ',':',':','.')
#   DB2   format : YYYY-MM-DD-HH.MM.SS.NNNNNN   (CBACT04C CURRENT-DATE -- '-','-','-','.','.','.')
# The other twenty offsets are always digits. WHY (Assumption): accepting the union of
# separators {'-','.',':' ,' '} at these six offsets validates BOTH dialects with one
# rule, so neither the posting nor the interest path needs a dialect-specific matcher.
_TS_SEP_POSITIONS = frozenset({4, 7, 10, 13, 16, 19})
_TS_SEP_CHARS = frozenset("-.: ")


def _is_normalizable_ts_field(value: str) -> bool:
    """Return whether a 26-char field value is a legitimate timestamp (or all-blank).

    Purpose
    -------
    Guard :func:`normalize_timestamps` (QA finding m1): before a ``normalize_ts`` field is
    blanked, confirm it actually holds a 26-byte timestamp -- either the unset/all-blank
    value COBOL leaves for a stamp that was never written, or a well-formed timestamp in
    one of the two dialects CardDemo emits. A value that is neither is corrupt (a
    mis-decoded record or a wrong offset), and blanking it would silently hide the defect,
    so the caller raises instead of masking.

    Parameters
    ----------
    value : str
        The 26-character field slice to check.

    Returns
    -------
    bool
        ``True`` if ``value`` is 26 spaces (legitimate unset) OR matches the shared
        timestamp shape (a digit at each of the twenty digit offsets and one of
        ``-``/``.``/``:``/space at each of the six separator offsets); ``False`` otherwise.

    Raises
    ------
    None
    """
    # All-blank is an intentionally VALID value: COBOL leaves an unwritten PROC-TS as
    # spaces, and the committed reject goldens carry exactly this (26 blanks). WHY
    # (Assumption): treating blank as valid is essential -- rejecting it would break every
    # reject-stream comparison whose PROC-TS was never set.
    if value == " " * 26:
        return True
    if len(value) != 26:
        return False
    for i, ch in enumerate(value):
        if i in _TS_SEP_POSITIONS:
            if ch not in _TS_SEP_CHARS:
                return False
        elif not ch.isdigit():
            return False
    return True


def normalize_timestamps(raw: str, layout: RecordLayout, sentinel: str = " ") -> str:
    """Blank the non-deterministic timestamp fields of a record for golden comparison.

    Purpose
    -------
    Replace every field flagged :attr:`Field.normalize_ts` -- the runtime-generated
    timestamp(s) -- with a fixed sentinel so that two runs that differ only in wall-clock
    time compare byte-identical. It lives here, beside the layout that defines those
    offsets, so ``golden_compare.py`` reuses the same offsets rather than duplicating them
    (WHY: Trade-off -- single-sourcing the offsets beats a marginally more convenient home
    in the comparator).

    Which fields are blanked is LAYOUT-DEFINED: for most layouts it is exactly the single
    processing timestamp (``PROC-TS``), while the deterministic originating stamp
    (``ORIG-TS``) is left intact as business data the golden must verify. The ``INTTRAN``
    layout is the deliberate exception -- CBACT04C sets BOTH stamps from the runtime clock,
    so that layout flags both (see ``INTTRAN_LAYOUT``).

    WHY validate-before-blank (QA finding m1): each ``normalize_ts`` field is first checked
    with :func:`_is_normalizable_ts_field`; only a genuine 26-byte timestamp (either
    dialect) or an all-blank unset field may be masked. Blanking by position without this
    check would silently paper over a corrupt record or a wrong offset -- exactly the class
    of defect a financial harness must surface, not hide. The mask is written *in place*
    so the record keeps its exact width.

    Parameters
    ----------
    raw : str
        The raw record line; normalised to ``layout.reclen`` characters first.
    layout : RecordLayout
        The layout describing ``raw`` (its timestamp fields drive what gets blanked).
    sentinel : str
        The single character used to overwrite each timestamp byte. Defaults to a space,
        matching COBOL's blank-fill and the seeds' unset ``PROC-TS``.

    Returns
    -------
    str
        A record of length ``layout.reclen`` with each ``normalize_ts`` field overwritten
        by ``sentinel`` repeated to the field length. Records with no such fields are
        returned unchanged (aside from length normalisation).

    Raises
    ------
    ValueError
        If ``sentinel`` is not exactly one character (a multi-char sentinel would change
        the record length and desynchronise every downstream offset), or if a
        ``normalize_ts`` field of length 26 holds neither a valid timestamp nor all
        blanks (QA finding m1 -- a corrupt record must fail loudly, not be masked).
    RecordLengthError
        If ``raw`` is not exactly ``layout.reclen`` characters after newline stripping.
    """
    if len(sentinel) != 1:
        raise ValueError(f"sentinel must be exactly one character, got {sentinel!r}")
    chars = list(_validated_record(raw, layout.reclen, context=f"{layout.name} normalize_timestamps"))
    for fld in layout.fields:
        if fld.normalize_ts:
            # WHY (m1): validate the field actually holds a timestamp (or is unset/blank)
            # before overwriting it. Only 26-byte fields carry the timestamp contract, so
            # the shape check is scoped to that length; any other normalize_ts width (none
            # exist today) is blanked without a shape assertion. A failure here means the
            # record is corrupt or an offset is wrong -- surfacing it beats silently hiding
            # a defect behind a blank field.
            if fld.length == 26:
                chunk = "".join(chars[fld.start:fld.end])
                if not _is_normalizable_ts_field(chunk):
                    raise ValueError(
                        f"{layout.name}.{fld.name}: expected a 26-byte timestamp or all "
                        f"blanks before normalisation but got {chunk!r} "
                        f"(a corrupt record or a wrong field offset)"
                    )
            chars[fld.start:fld.end] = sentinel * fld.length
    return "".join(chars)


# Sensitive fields for which revealing the final four characters is the accepted
# industry practice (PAN "last four", SSN "last four") -- it aids identification in a
# diagnostic without disclosing the full number. WHY (Trade-off): everything else
# (CVV, names, DOB, government id, address, phone) is masked in full, because there is
# no defensible partial-disclosure convention for those and full masking is the safer
# default for a financial harness.
_LAST4_REVEAL = frozenset({
    "CARD-NUM", "XREF-CARD-NUM", "TRAN-CARD-NUM", "DALYTRAN-CARD-NUM", "CUST-SSN",
})


def mask_field(field: "Field", chunk: str) -> str:
    """Return a redacted, same-width rendering of one field's bytes for diagnostics.

    Purpose
    -------
    Produce a privacy-safe stand-in for a single field so assertion messages and golden
    diffs can show a record's shape and *where* two records differ without ever emitting
    a complete PAN, SSN, name, date of birth, or government id (finding MA-13).
    Non-sensitive fields are returned unchanged.

    The mask is *deterministic*: equal input bytes always yield equal masked bytes
    (WHY: Trade-off -- a stable per-value hash lets a masked diff still reveal which
    field changed and whether two masked records are equal, which a random mask would
    destroy, while a plaintext value would over-disclose).

    Parameters
    ----------
    field : Field
        The field descriptor. Its :attr:`Field.sensitive` flag decides whether masking
        applies, its :attr:`Field.name` selects the last-four-reveal convention, and its
        :attr:`Field.length` fixes the returned width.
    chunk : str
        The exact field bytes sliced from the record.

    Returns
    -------
    str
        Exactly ``field.length`` characters: ``chunk`` verbatim when the field is not
        sensitive; otherwise a redaction that reveals at most the trailing four
        characters (for PAN/SSN) and hides the remainder.

    Raises
    ------
    ValueError
        If ``chunk`` is not ``field.length`` characters (a slice/width bug).
    """
    if len(chunk) != field.length:
        raise ValueError(
            f"mask_field: {field.name!r} chunk is {len(chunk)} chars, expected {field.length}"
        )
    if not field.sensitive:
        return chunk
    stripped = chunk.rstrip()
    if field.name in _LAST4_REVEAL and len(stripped) >= 4:
        revealed = stripped[-4:]
        masked = "*" * (len(stripped) - 4) + revealed
    else:
        # Deterministic short digest keeps equal values equal without disclosure.
        digest = hashlib.sha256(chunk.encode("utf-8", "replace")).hexdigest()[:8]
        masked = f"<redacted:{digest}>"
    # Preserve the exact field width so a masked record stays byte-aligned; truncate an
    # over-long redaction tag and pad a short one with spaces.
    return masked[: field.length].ljust(field.length)


def mask_record(raw: str, layout: RecordLayout) -> str:
    """Return a full-width copy of ``raw`` with every sensitive field redacted.

    Purpose
    -------
    Build a privacy-safe rendering of a whole record for logs, assertion failures, and
    golden diffs. Non-sensitive fields (ids, amounts, dates, status flags, filler) are
    preserved so the record remains diagnostically useful, while PAN/SSN/name/DOB/
    government-id fields are replaced by :func:`mask_field` (finding MA-13).

    Parameters
    ----------
    raw : str
        The raw record line (validated to ``layout.reclen`` first).
    layout : RecordLayout
        The layout describing ``raw``; its :attr:`Field.sensitive` flags drive masking.

    Returns
    -------
    str
        A record of length ``layout.reclen`` with sensitive fields redacted in place.

    Raises
    ------
    RecordLengthError
        If ``raw`` is not exactly ``layout.reclen`` characters after newline stripping.
    """
    validated = _validated_record(raw, layout.reclen, context=f"{layout.name} mask_record")
    chars = list(validated)
    for fld in layout.fields:
        if fld.sensitive:
            chars[fld.start:fld.end] = mask_field(fld, validated[fld.start:fld.end])
    return "".join(chars)


def _validate_layouts() -> None:
    """Self-check that every registered layout is internally consistent.

    Purpose
    -------
    Prove, at import time, that each layout's fields are contiguous from offset 0, that
    their lengths sum to the declared ``reclen``, that every primary and alternate key
    lies within the record, and that each known layout carries its expected
    reclen/key-length constants. This turns a silent transcription error in the tables
    above into an immediate, loud import failure -- the codec is the single source of
    truth, so a wrong offset must never reach the tests that depend on it.

    Parameters
    ----------
    None

    Returns
    -------
    None

    Raises
    ------
    RuntimeError
        If any layout's fields are non-contiguous, its field lengths do not sum to its
        ``reclen``, or a known layout's reclen/key-length differs from the copybook value.
        A ``RuntimeError`` (rather than ``assert``) is used deliberately (WHY:
        Refactoring Rationale) so the check still fires under ``python -O``, which strips
        ``assert`` statements.
    """
    # Expected (reclen, key_length) per copybook. WHY: hard-coding the published
    # constants makes a mistyped offset in the tables above fail loudly at import rather
    # than surfacing as a subtly wrong decode deep inside a test. "TRNX" is the TRAN
    # alias used by the statement fixtures and shares TRAN's geometry.
    expected = {
        "ACCOUNT": (300, 11), "DALYTRAN": (350, 16), "DISGROUP": (50, 16),
        "XREF": (50, 16), "TCATBAL": (50, 17), "CARD": (150, 16),
        "CUSTOMER": (500, 9), "TRAN": (350, 16), "TRNX": (350, 32),
        # REJECT = 350-byte DALYTRAN image + 80-byte VALIDATION-TRAILER (CBTRN02C DALYREJS).
        "REJECT": (430, 16),
        # INTTRAN = CVTRA05Y interest transaction (CBACT04C); shares TRAN's geometry.
        "INTTRAN": (350, 16),
    }
    for name, layout in LAYOUTS.items():
        cursor = 0
        for fld in layout.fields:
            if fld.start != cursor:
                raise RuntimeError(
                    f"{name}: field {fld.name!r} starts at {fld.start}, expected {cursor} "
                    f"(fields must be contiguous from offset 0)"
                )
            cursor += fld.length
        if cursor != layout.reclen:
            raise RuntimeError(
                f"{name}: field lengths sum to {cursor} but reclen is {layout.reclen}"
            )
        if layout.key_offset + layout.key_length > layout.reclen:
            raise RuntimeError(
                f"{name}: key (offset {layout.key_offset}, len {layout.key_length}) "
                f"exceeds reclen {layout.reclen}"
            )
        for alt in layout.alternate_keys:
            # WHY: an alternate key that runs off the end of the record would build a
            # malformed indexed file; verify containment at import just like the primary.
            if alt.offset + alt.length > layout.reclen:
                raise RuntimeError(
                    f"{name}: alternate key {alt.name!r} (offset {alt.offset}, "
                    f"len {alt.length}) exceeds reclen {layout.reclen}"
                )
        if name in expected:
            exp_reclen, exp_keylen = expected[name]
            if (layout.reclen, layout.key_length) != (exp_reclen, exp_keylen):
                raise RuntimeError(
                    f"{name}: expected reclen/keylen {(exp_reclen, exp_keylen)} but got "
                    f"{(layout.reclen, layout.key_length)}"
                )


# Run the self-check as an import-time guard. WHY: importing this module is the earliest
# possible moment to detect a corrupt layout, and every other helper imports it, so the
# whole suite fails fast and unambiguously rather than producing subtly wrong decodes.
_validate_layouts()


if __name__ == "__main__":  # pragma: no cover - manual convenience entry point
    # WHY: running the module directly executes its doctest known-answer vectors, giving
    # a zero-dependency smoke test (``python3 tests/helpers/record_codec.py``) without a
    # test runner. Kept out of coverage because it is an operator convenience, not suite
    # logic.
    import doctest

    failures, _tests = doctest.testmod(verbose=False)
    if failures:
        raise SystemExit(1)
    print("record_codec: all doctests passed")
