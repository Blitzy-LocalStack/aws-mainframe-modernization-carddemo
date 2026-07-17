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
  non-deterministic ``ORIG-TS`` / ``PROC-TS`` offsets are defined in exactly one
  place rather than duplicated across the suite.
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
* **Single-sourced layouts.** The three record layouts are transcribed field-for-field
  from the copybooks (``CVACT01Y``, ``CVTRA06Y``, ``CVTRA02Y``). Each :class:`Field`
  carries a comment citing its copybook name and PIC clause. Layouts are *never*
  re-declared independently elsewhere; downstream code imports them from here.
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

import re
from dataclasses import dataclass, field as dc_field
from decimal import Decimal, InvalidOperation, localcontext
from typing import Any

__all__ = [
    "ZonedDecimalError",
    "decode_zoned",
    "encode_zoned",
    "Field",
    "RecordLayout",
    "ACCOUNT_LAYOUT",
    "DALYTRAN_LAYOUT",
    "DISGROUP_LAYOUT",
    "LAYOUTS",
    "reclen_of",
    "keylen_of",
    "normalize_timestamps",
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
        is not a recognised overpunch / plain digit.
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
        elif last.isdigit():
            # WHY (Assumption / robustness): a signed field whose final byte is a plain
            # digit (no overpunch applied) is treated as positive. Some upstream
            # encoders emit an un-overpunched positive value; tolerating it lets the
            # harness decode such records without masking a truly corrupt byte, which
            # would instead land in the ``else`` branch below.
            negative = False
            last_digit = last
        else:
            raise ZonedDecimalError(f"unrecognised overpunch sign byte {last!r} in {raw!r}")
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
        ``True`` for the non-deterministic timestamp fields (``DALYTRAN-ORIG-TS`` /
        ``DALYTRAN-PROC-TS``) that must be blanked before golden comparison. Defaults
        to ``False``.

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


def _normalize_length(raw: str, reclen: int) -> str:
    """Coerce a raw line to exactly ``reclen`` characters for slicing.

    Purpose
    -------
    Seed and fixture lines are frequently shorter than the declared record length --
    for example ``cardxref`` lines are 36 bytes though the layout is 50 bytes with the
    trailing ``FILLER`` omitted -- and may carry a trailing newline. This helper strips
    a single trailing CR/LF and then pads (or truncates) the line to exactly ``reclen``
    characters so every field offset is valid.

    WHY (Assumption / Trade-off): omitted trailing ``FILLER`` is assumed to be spaces,
    matching the copybook contract in which ``FILLER PIC X(n)`` initialises to blanks.
    We pad with spaces rather than rejecting short lines because doing so lets the
    harness consume real-world seed files that legitimately drop trailing filler, at
    the cost of masking a genuinely truncated record -- an acceptable trade for test
    fixtures whose field values live well before the filler.

    Parameters
    ----------
    raw : str
        The raw record text, possibly shorter or longer than ``reclen`` and possibly
        ending in ``\\r`` and/or ``\\n``.
    reclen : int
        The exact target record length in characters.

    Returns
    -------
    str
        A string of length exactly ``reclen``: the input with one trailing newline
        removed, right-padded with spaces if short, truncated if long.

    Raises
    ------
    None
    """
    # Strip at most one trailing line terminator (``\r\n``, ``\n`` or ``\r``);
    # inner characters are never touched so embedded data is preserved.
    if raw.endswith("\r\n"):
        raw = raw[:-2]
    elif raw.endswith("\n") or raw.endswith("\r"):
        raw = raw[:-1]
    return raw.ljust(reclen)[:reclen]


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
        Zero-based offset of the key within the record. Defaults to 0 (CardDemo's
        indexed files are all keyed on their leading bytes).

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
        None
        """
        padded = _normalize_length(raw, self.reclen)
        return padded[self.key_offset:self.key_offset + self.key_length]

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
            The raw record line; normalised to exactly ``reclen`` characters first
            (see :func:`_normalize_length`), so short/long/newline-terminated input is
            accepted.

        Returns
        -------
        dict[str, Any]
            Mapping from field name to decoded value (``str`` / ``int`` / ``Decimal``).
            Text values retain trailing spaces; use :meth:`decode_stripped` for trimmed
            text.

        Raises
        ------
        ZonedDecimalError
            Propagated from :func:`decode_zoned` if a zoned field is malformed.
        ValueError
            If a ``"uint"`` field contains characters that are neither digits nor blank.
        """
        padded = _normalize_length(raw, self.reclen)
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
        Field("DALYTRAN-CARD-NUM", 262, 16, "text"),                     # CVTRA06Y PIC X(16)
        # ORIG-TS / PROC-TS are wall-clock timestamps and are therefore
        # non-deterministic across runs; flagged normalize_ts so golden comparison can
        # blank them (WHY: Trade-off -- normalising two known offsets is simpler and
        # safer than teaching every golden file to ignore timestamps).
        Field("DALYTRAN-ORIG-TS", 278, 26, "text", normalize_ts=True),   # CVTRA06Y PIC X(26)
        Field("DALYTRAN-PROC-TS", 304, 26, "text", normalize_ts=True),   # CVTRA06Y PIC X(26)
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

# Registry keyed by logical record name. WHY: a single dict lets helpers such as
# vsam_loader resolve reclen/keylen by name (e.g. reclen_of("ACCOUNT")) instead of
# importing each layout constant individually.
LAYOUTS: dict[str, RecordLayout] = {
    "ACCOUNT": ACCOUNT_LAYOUT,
    "DALYTRAN": DALYTRAN_LAYOUT,
    "DISGROUP": DISGROUP_LAYOUT,
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


def normalize_timestamps(raw: str, layout: RecordLayout, sentinel: str = " ") -> str:
    """Blank the non-deterministic timestamp fields of a record for golden comparison.

    Purpose
    -------
    Replace every field flagged :attr:`Field.normalize_ts` (the ``DALYTRAN-ORIG-TS`` /
    ``DALYTRAN-PROC-TS`` wall-clock stamps) with a fixed sentinel so that two runs that
    differ only in processing time compare byte-identical. It lives here, beside the
    layout that defines those offsets, so ``golden_compare.py`` reuses the same offsets
    rather than duplicating them (WHY: Trade-off -- single-sourcing the offsets beats a
    marginally more convenient home in the comparator).

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
        the record length and desynchronise every downstream offset).
    """
    if len(sentinel) != 1:
        raise ValueError(f"sentinel must be exactly one character, got {sentinel!r}")
    chars = list(_normalize_length(raw, layout.reclen))
    for fld in layout.fields:
        if fld.normalize_ts:
            chars[fld.start:fld.end] = sentinel * fld.length
    return "".join(chars)


def _validate_layouts() -> None:
    """Self-check that every registered layout is internally consistent.

    Purpose
    -------
    Prove, at import time, that each layout's fields are contiguous from offset 0, that
    their lengths sum to the declared ``reclen``, and that the three known layouts carry
    their expected reclen/key-length constants (300/350/50 and 11/16/16). This turns a
    silent transcription error in the tables above into an immediate, loud import
    failure -- the codec is the single source of truth, so a wrong offset must never
    reach the tests that depend on it.

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
    expected = {"ACCOUNT": (300, 11), "DALYTRAN": (350, 16), "DISGROUP": (50, 16)}
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
