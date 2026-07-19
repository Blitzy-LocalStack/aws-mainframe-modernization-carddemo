"""Binary-safety regression tests for the indexed-file loader/unloader helpers.

Purpose
-------
Prove that :mod:`tests.helpers.vsam_loader` frames fixed-length records by their
DECLARED RECORD LENGTH -- never by payload bytes -- and reads them back
byte-for-byte, closing two acceptance-blocker findings:

* **F-VSAM-BINARY-LOAD** -- ``load_indexed`` / ``_validated_blob`` previously read
  the flat fixture as Latin-1 text and split it on newline, so a fixed binary
  record whose key or data legitimately contained ``0x0A`` was split into the
  wrong number of records. The binary framing path (``binary=True``) must treat
  every byte, including ``0x0A``, as record DATA.
* **F-VSAM-BINARY-UNLOAD** -- ``unload_indexed`` previously decoded the read-back
  blob as strict UTF-8, raising ``UnicodeDecodeError`` on any non-UTF-8 byte such
  as ``0x8f``. The byte-exact primitive ``unload_indexed_bytes`` must return the
  record bytes verbatim so genuinely binary records can be verified.

Provenance
----------
Exercises only the in-scope test helper ``tests/helpers/vsam_loader.py``. The
generated COBOL loader/unloader declare the flat side ``ORGANIZATION IS
SEQUENTIAL`` with ``RECORD CONTAINS reclen CHARACTERS`` (record-sequential fixed,
NOT line-sequential), so the COBOL layer was already binary-safe; these tests pin
that the Python framing layer now matches it.

WHY marked ``integration`` (Assumption): the round-trip tests compile and run a
real ``cobc``-built loader/unloader against a real native indexed file, so they
belong to the integration layer selected by ``pytest -m integration``; the pure
``_validated_blob`` framing tests need no compiler and run unconditionally.
"""

from __future__ import annotations

import shutil

import pytest

from tests.helpers.vsam_loader import (
    VsamLoadError,
    _validated_blob,
    load_indexed,
    unload_indexed,
    unload_indexed_bytes,
)

# ``pytest -m integration`` selects this whole module. WHY module-level (Trade-off):
# every test here is integration-tier; a single ``pytestmark`` is clearer than a
# per-test decorator and cannot be forgotten on a newly added case.
pytestmark = pytest.mark.integration

# The round-trip tests need GnuCOBOL. WHY skip (not fail) when absent (Assumption):
# the suite runs in a documented degraded mode without ``cobc`` (mirrors every other
# integration module's guard), so a missing compiler is a skip, not a red result.
_COBC = shutil.which("cobc")
requires_cobc = pytest.mark.skipif(_COBC is None, reason="GnuCOBOL 'cobc' not on PATH")

# Record geometry for the adversarial fixtures. WHY tiny + primary-key-only
# (Trade-off): a 10-byte record with a 4-byte key at offset 0 is the smallest shape
# that still lets a key contain an embedded ``0x0A`` and the data contain a high
# byte, so the framing/decoding contract is exercised without any dependence on a
# real CardDemo layout. WHY no ``0x00`` in keys (Assumption): a NUL byte in an ISAM
# primary key can be treated as low-value padding by some backends, which would test
# the backend rather than our framing; ``0x0A`` is an ordinary data byte to ISAM and
# is exactly the byte the finding calls out.
_RECLEN = 10
_KEY_OFF = 0
_KEY_LEN = 4


# ---------------------------------------------------------------------------
# _validated_blob framing (no compiler required)
# ---------------------------------------------------------------------------
def test_validated_blob_binary_preserves_embedded_lf(tmp_path) -> None:
    """Binary framing keeps an embedded ``0x0A`` as data, not a record boundary.

    Parameters
    ----------
    tmp_path : pathlib.Path
        pytest-provided per-test temporary directory (isolation).

    Returns
    -------
    None

    Raises
    ------
    AssertionError
        If binary framing does not return the fixture bytes verbatim, or if the
        legacy textual framing fails to reject the same misframed content.
    """
    # Two 10-byte records, concatenated with NO separators; record 0's key holds a
    # 0x0A at offset 1 and its data holds a high byte 0x8f. A newline split would
    # (wrongly) see three fragments; fixed framing must see exactly two records.
    rec0 = b"K\x0aAA" + b"\x8f\x8fxyz!"   # 4-byte key + 6-byte data == 10 bytes
    rec1 = b"MMMM" + b"data!!"            # 4-byte key + 6-byte data == 10 bytes
    fixture = tmp_path / "binary.fix"
    fixture.write_bytes(rec0 + rec1)

    blob = _validated_blob(fixture, _RECLEN, binary=True)
    # Byte-for-byte identity: the two records survive intact and in order.
    assert blob == rec0 + rec1
    assert len(blob) == 2 * _RECLEN

    # The legacy textual path MUST reject this content: splitting on the embedded
    # 0x0A yields rows whose widths are not exactly reclen. WHY assert the raise
    # (Refactoring Rationale): it proves the binary flag is load-bearing -- the same
    # bytes are unusable under the default framing, so binary=True is not cosmetic.
    with pytest.raises(VsamLoadError):
        _validated_blob(fixture, _RECLEN, binary=False)


def test_validated_blob_binary_rejects_non_multiple(tmp_path) -> None:
    """Binary framing rejects a total length that is not a whole multiple of reclen.

    Parameters
    ----------
    tmp_path : pathlib.Path
        pytest per-test temporary directory.

    Returns
    -------
    None

    Raises
    ------
    AssertionError
        If a 21-byte fixture (reclen 10) does not raise ``VsamLoadError``.
    """
    fixture = tmp_path / "short.fix"
    fixture.write_bytes(b"\x00" * (2 * _RECLEN + 1))  # 21 bytes, not a multiple of 10
    # WHY VsamLoadError (Assumption): a non-multiple length means the fixture geometry
    # disagrees with the declared reclen; the loader must surface this loudly rather
    # than silently truncate the trailing partial record.
    with pytest.raises(VsamLoadError):
        _validated_blob(fixture, _RECLEN, binary=True)


def test_validated_blob_binary_empty(tmp_path) -> None:
    """An empty binary fixture yields an empty blob (the legitimate zero-records case).

    Parameters
    ----------
    tmp_path : pathlib.Path
        pytest per-test temporary directory.

    Returns
    -------
    None

    Raises
    ------
    AssertionError
        If an empty file does not map to ``b""``.
    """
    fixture = tmp_path / "empty.fix"
    fixture.write_bytes(b"")
    assert _validated_blob(fixture, _RECLEN, binary=True) == b""


# ---------------------------------------------------------------------------
# Full load -> unload round trips (require cobc)
# ---------------------------------------------------------------------------
@requires_cobc
def test_load_indexed_binary_frames_by_reclen_not_newline(tmp_path) -> None:
    """load_indexed(binary=True) frames a 0x0A-bearing key by reclen, not newline.

    Reproduces F-VSAM-BINARY-LOAD end-to-end: a fixed binary record whose 4-byte key
    contains ``0x0A`` must load as ONE record (not be split), and must read back
    byte-for-byte.

    Parameters
    ----------
    tmp_path : pathlib.Path
        pytest per-test temporary directory; holds the fixture, the indexed output,
        and the isolated compile cache.

    Returns
    -------
    None

    Raises
    ------
    AssertionError
        If the read-back record set is not exactly the two input records.
    tests.helpers.vsam_loader.VsamLoadError
        Propagated if the loader/unloader fails to compile or run.
    """
    rec0 = b"K\x0aAA" + b"payld0"   # key contains 0x0A at offset 1
    rec1 = b"MMMM" + b"payld1"
    fixture = tmp_path / "keys_with_lf.fix"
    fixture.write_bytes(rec0 + rec1)
    indexed = tmp_path / "keys_with_lf.idx"

    load_indexed(
        fixture, indexed, _RECLEN, _KEY_LEN, _KEY_OFF,
        binary=True, cache_dir=tmp_path,
    )
    # Byte-exact read-back, ascending primary-key order (0x4b.. < 0x4d..).
    got = unload_indexed_bytes(indexed, _RECLEN, _KEY_LEN, _KEY_OFF, cache_dir=tmp_path)
    assert got == [rec0, rec1]


@requires_cobc
def test_unload_indexed_bytes_preserves_high_byte(tmp_path) -> None:
    """unload_indexed_bytes returns a 0x8f high byte verbatim; the str layer raises.

    Reproduces F-VSAM-BINARY-UNLOAD end-to-end: a record containing a non-UTF-8 byte
    (``0x8f``) must round-trip through the byte primitive unchanged, while the legacy
    text layer (:func:`unload_indexed`, UTF-8) intentionally raises on it.

    Parameters
    ----------
    tmp_path : pathlib.Path
        pytest per-test temporary directory (fixture, indexed file, compile cache).

    Returns
    -------
    None

    Raises
    ------
    AssertionError
        If the high byte is not preserved, or the text layer does not raise.
    tests.helpers.vsam_loader.VsamLoadError
        Propagated if the loader/unloader fails to compile or run.
    """
    rec = b"AAAA" + b"\x8f\x8fxyz!"   # 4-byte key + 6-byte data, high bytes in data
    fixture = tmp_path / "high_byte.fix"
    fixture.write_bytes(rec)
    indexed = tmp_path / "high_byte.idx"

    load_indexed(
        fixture, indexed, _RECLEN, _KEY_LEN, _KEY_OFF,
        binary=True, cache_dir=tmp_path,
    )

    got = unload_indexed_bytes(indexed, _RECLEN, _KEY_LEN, _KEY_OFF, cache_dir=tmp_path)
    assert got == [rec]
    assert b"\x8f" in got[0]  # the high byte survived the full round trip

    # The default text layer must still raise on the same file. WHY assert this
    # (Trade-off): it documents that decoding is now an explicit, opt-in step --
    # binary callers use the bytes primitive, and the str layer's strict-UTF-8
    # behaviour is preserved for the ASCII records the golden comparator consumes.
    with pytest.raises(UnicodeDecodeError):
        unload_indexed(indexed, _RECLEN, _KEY_LEN, _KEY_OFF, cache_dir=tmp_path)


@requires_cobc
def test_textual_roundtrip_backward_compatible(tmp_path) -> None:
    """A normal ASCII fixture still round-trips via the default (textual) path.

    Guards backward compatibility: the default ``binary=False`` load and the
    ``unload_indexed`` str layer behave exactly as before for the newline-delimited
    ASCII fixtures every existing test uses.

    Parameters
    ----------
    tmp_path : pathlib.Path
        pytest per-test temporary directory (fixture, indexed file, compile cache).

    Returns
    -------
    None

    Raises
    ------
    AssertionError
        If the decoded read-back does not equal the input records.
    tests.helpers.vsam_loader.VsamLoadError
        Propagated if the loader/unloader fails to compile or run.
    """
    # Newline-delimited, each line exactly reclen wide (the shape of every committed
    # CardDemo *.txt fixture). WHY distinct 4-byte prefixes (Assumption): the primary
    # key is bytes 0..3, so each record needs a unique key prefix or the loader rejects
    # the duplicate with FILE STATUS 22 (primary keys are unique by contract).
    lines = ["AC01aaaaaa", "AC02bbbbbb", "AC03cccccc"]
    fixture = tmp_path / "ascii.fix"
    fixture.write_text("\n".join(lines) + "\n", encoding="ascii")
    indexed = tmp_path / "ascii.idx"

    load_indexed(fixture, indexed, _RECLEN, _KEY_LEN, _KEY_OFF, cache_dir=tmp_path)
    got = unload_indexed(indexed, _RECLEN, _KEY_LEN, _KEY_OFF, cache_dir=tmp_path)
    assert got == lines
    assert all(isinstance(r, str) for r in got)
