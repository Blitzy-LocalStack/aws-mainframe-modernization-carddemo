"""Shared fixed-width record-access helpers for the Layer-3 (e2e) golden-master tests.

Purpose
-------
The three end-to-end cycle suites (``tests/e2e/test_posting_cycle.py``,
``test_interest_cycle.py``, ``test_full_batch_cycle.py``) all need to read the batch
programs' fixed-width outputs back into logical fields to assert on business values --
framing a sequential byte image into records, locating a named field by its copybook
offset, extracting a text field, and decoding a signed zoned-decimal money field to an
exact :class:`decimal.Decimal`. This module hosts those four operations in one place so
the suites cannot drift apart on field geometry or decoding rules.

WHY a dedicated helper module (Refactoring Rationale)
----------------------------------------------------
Earlier revisions inlined these accessors in each e2e file. That duplicated the
offset/decoding logic three times -- a maintenance hazard the Explainability standard
(AAP §0.10.1) and financial-correctness bar both disfavour: a copybook change would have
to be mirrored in three places or the goldens would silently diverge. Centralising here
means all three suites resolve a field's ``start``/``length``/``int_digits``/``dec_digits``
/``signed`` from the single ``record_codec`` layout registry (which mirrors ``app/cpy``),
so the read-back asserts and the golden comparator can never disagree on a field position.

Assumptions
-----------
* The fixed-width offsets/widths in ``record_codec.LAYOUTS`` match the ``app/cpy``
  copybook contract (the same assumption the golden comparator relies on).
* ``latin-1`` is the byte-preserving codec used across the helper layer, so a raw record
  round-trips 1 byte -> 1 code point without mangling zoned-decimal sign overpunch bytes.
"""
from __future__ import annotations

from decimal import Decimal

from tests.helpers import record_codec


def field_geometry(layout_name: str, field_name: str):
    """Look up a named field in a ``record_codec`` layout (single-sourced offsets).

    Parameters
    ----------
    layout_name : str
        Registered record-layout name (e.g. ``"TRAN"``, ``"ACCOUNT"``, ``"TCATBAL"``,
        ``"INTTRAN"``).
    field_name : str
        COBOL field name as defined on the layout (e.g. ``"TRAN-AMT"``).

    Returns
    -------
    tests.helpers.record_codec.Field
        The matching field descriptor (carrying ``start``, ``length``, ``int_digits``,
        ``dec_digits``, ``signed``).

    Raises
    ------
    KeyError
        If ``layout_name`` is not registered or ``field_name`` is absent from it -- a
        contract error surfaced loudly rather than mis-slicing a record.
    """
    layout = record_codec.LAYOUTS[layout_name]
    for field in layout.fields:
        if field.name == field_name:
            return field
    raise KeyError(f"field {field_name!r} not found in layout {layout_name!r}")


def text_field(record: str, layout_name: str, field_name: str) -> str:
    """Extract and strip a text field from a fixed-width logical record.

    Parameters
    ----------
    record : str
        One fixed-width record string (as returned by ``unload_output``/:func:`frame_records`).
    layout_name : str
        Registered record-layout name.
    field_name : str
        Field to extract.

    Returns
    -------
    str
        The field's whitespace-stripped text value.

    Raises
    ------
    KeyError
        If ``layout_name`` is not registered or ``field_name`` is absent from it
        (propagated from :func:`field_geometry`).

    Notes
    -----
    WHY strip (Assumption): identifiers such as ``TRAN-ID`` are right-padded to their fixed
    width; comparing/reconciling by logical value requires trimming that padding.
    """
    field = field_geometry(layout_name, field_name)
    return record[field.start:field.start + field.length].strip()


def money_field(record: str, layout_name: str, field_name: str) -> Decimal:
    """Decode a signed zoned-decimal money field to an exact ``Decimal``.

    Parameters
    ----------
    record : str
        One fixed-width record string.
    layout_name : str
        Registered record-layout name.
    field_name : str
        Zoned-decimal field to decode (e.g. ``"TRAN-AMT"``, ``"ACCT-CURR-BAL"``).

    Returns
    -------
    decimal.Decimal
        The exact fixed-point value (never a binary float).

    Raises
    ------
    tests.helpers.record_codec.ZonedDecimalError
        If the slice is not a valid zoned-decimal image (propagated from
        :func:`record_codec.decode_zoned`).

    Notes
    -----
    WHY ``Decimal`` (Trade-off): monetary assertions must use exact fixed-point arithmetic;
    a float tolerance would violate the financial-precision mandate. The int/dec/signed
    metadata comes from the layout so the decode matches the copybook PIC clause exactly.
    """
    field = field_geometry(layout_name, field_name)
    raw = record[field.start:field.start + field.length]
    return record_codec.decode_zoned(raw, field.int_digits, field.dec_digits, field.signed)


def frame_records(raw: bytes, reclen: int) -> "list[str]":
    """Frame a raw sequential-file byte image into fixed-width logical record strings.

    Parameters
    ----------
    raw : bytes
        Contiguous byte image of a sequential output file (e.g. ``DALYREJS``,
        ``TRANSACT``), whose length must be a whole multiple of ``reclen``.
    reclen : int
        Fixed record length in bytes/characters.

    Returns
    -------
    list of str
        The records as ``latin-1``-decoded strings, in physical (write) order.

    Raises
    ------
    AssertionError
        If ``raw`` is not a whole number of ``reclen``-sized records (a truncation/partial
        write that must fail loudly, not be silently rounded).

    Notes
    -----
    WHY ``latin-1`` (Assumption): the decode must be byte-preserving (1 byte -> 1 code
    point) so zoned-decimal sign-overpunch bytes survive intact for the record-mode golden
    comparison. Sequential outputs (``ORGANIZATION SEQUENTIAL``) are framed here directly
    from their byte image; INDEXED outputs are read via ``CobolRunner.unload_output``.
    """
    assert len(raw) % reclen == 0, (
        f"raw image of {len(raw)} bytes is not a whole number of {reclen}-byte records"
    )
    return [raw[off:off + reclen].decode("latin-1") for off in range(0, len(raw), reclen)]
