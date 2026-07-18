"""Layer-2 pytest **integration** tests for CardDemo ``CBACT04C`` (interest & fees).

Purpose
-------
Drive the compiled, *unmodified* production program ``app/cbl/CBACT04C.cbl`` against
deterministic fixed-width fixtures and the sibling ``tests/mocks/mock_discgrp.txt``
disclosure-group file, then assert -- to exact fixed-point precision -- every
observable output of the interest-calculation batch step:

* the interest formula ``WS-MONTHLY-INT = (TRAN-CAT-BAL * DIS-INT-RATE) / 1200``,
  **truncated** (not rounded) to two decimals, exactly as the COBOL ``COMPUTE`` does;
* the **DEFAULT disclosure-group fallback** taken when a group key is absent
  (VSAM file-status ``23``) -- the mandatory 100%-branch case of AAP section 0.7.1;
* the per-account category-balance roll-up applied to ``ACCTFILE`` (``ACCT-CURR-BAL``);
* the deterministic interest ``TRANSACT`` records (id, type, category, amount);
* the **no-op fee stub** (``1400-COMPUTE-FEES`` is ``EXIT`` -- "To be implemented");
* the process ``RETURN-CODE``.

This module *encodes* the program's current, verified behaviour; it never modifies
production code (the COBOL source and copybooks are REFERENCE only, per AAP 0.8.2).

File / binding contract (verified against the SELECT/ASSIGN clauses)
-------------------------------------------------------------------
``TCATBALF`` INDEXED SEQUENTIAL INPUT  reclen 50  key 17@0  -- drives the main loop.
``XREFFILE`` INDEXED RANDOM   INPUT    reclen 50  primary card 16@0 **+ ALTERNATE
                                                   acct 11@25 WITH DUPLICATES**.
``ACCTFILE`` INDEXED RANDOM   I-O      reclen 300 key 11@0  -- balances updated in place.
``DISCGRP``  INDEXED RANDOM   INPUT    reclen 50  key 16@0.
``TRANSACT`` SEQUENTIAL       OUTPUT   reclen 350 -- interest transactions written here.

Two non-trivial build/runtime facts are solved here and MUST stay embedded:

1. **Module-only build + ``DRV04C`` driver (static link).** ``CBACT04C`` declares
   ``PROCEDURE DIVISION USING EXTERNAL-PARMS`` (the JCL ``PARM=`` date), so it cannot
   be built as a standalone ``-x`` main. The verbatim :data:`_DRV04C_SRC` driver
   supplies the 2-byte ``COMP`` length + 10-char date group, is **static-linked** to
   the unmodified ``CBACT04C``, and injects a *fixed* ``PARMDATE`` so the emitted
   ``TRAN-ID``s are deterministic. WHY (Trade-off): static-linking yields one
   self-contained invokable binary and sidesteps runtime module resolution.

2. **``-fsign=EBCDIC`` is MANDATORY.** The fixtures and :mod:`tests.helpers.record_codec`
   use IBM zoned-decimal *trailing overpunch* signs (``{``=+0..``I``=+9, ``}``=-0..
   ``R``=-9). Without ``-fsign=EBCDIC`` GnuCOBOL misreads those bytes and corrupts every
   monetary computation. WHY (Assumption): this matches ``scripts/build_test_programs.sh``
   (finding MA-02); the flag spelling must use ``=`` (bare ``-fsign-ebcdic`` is ignored).

3. **``XREFFILE`` alternate key + ``LOADX`` loader.** ``CBACT04C`` paragraph ``1110``
   reads the cross-reference by the **alternate** account-id key, which needs an
   alternate index the primary-key indexed load cannot create by itself. The verbatim
   :data:`_LOADX_SRC` loader writes both the primary index and its ``.1`` alternate
   sidecar. WHY (Alternatives Considered): patching the shared helper was rejected to
   keep this an isolated, test-only artifact.

Known-gap encoded, not fixed -- ACCOUNT-BREAK DEFECT
----------------------------------------------------
The main loop's outer ``ELSE PERFORM 1050-UPDATE-ACCOUNT`` is **unreachable**: the
``PERFORM UNTIL END-OF-FILE = 'Y'`` exits at EOF before the body re-runs, so the
**final distinct account** in ``TCATBALF`` order is **never** balance-updated. WHY
(Assumption): every fixture therefore carries >= 2 distinct accounts, and balance
assertions target only *non-final* accounts; :func:`test_final_account_not_updated`
captures the defect explicitly so the gap is a documented test, not a surprise.

Determinism & isolation
------------------------
Each test runs in a fresh function-scoped ``workspace``; a fixed
:data:`_PARM_DATE` makes ``TRAN-ID``s reproducible; the two ``TRANSACT`` timestamps
(``TRAN-ORIG-TS``/``TRAN-PROC-TS``, sourced from ``CURRENT-DATE``) are
non-deterministic and are never asserted on. No shared mutable state -> parallel-safe
under ``pytest-xdist``.
"""

from __future__ import annotations

import os
import shutil
import subprocess
from collections import namedtuple
from decimal import ROUND_DOWN, Decimal
from pathlib import Path

import pytest

# WHY (single-sourcing): decode money and locate every field through the copybook-
# derived layouts in record_codec rather than hand-coded offsets, so the fixtures,
# the programs under test, and this test all agree on one record contract.
from tests.helpers.record_codec import (
    ACCOUNT_LAYOUT,
    DISGROUP_LAYOUT,
    TCATBAL_LAYOUT,
    TRAN_LAYOUT,
    decode_zoned,
)

# WHY (QA Issue 5 / F-A -- committed goldens must be the authoritative oracle): the
# derived-model assertions below prove the interest math independently, but the
# checkpoint/AAP (0.7.2) also requires the committed tests/golden/**/*.expected files to
# be *consumed* so any drift or regression is caught. assert_matches_golden is the suite's
# AAP-designated byte-exact comparator (tests/helpers/golden_compare.py); a missing golden
# is a hard error, never a silent pass. WHY the two oracles coexist (Trade-off): the Decimal
# model localises WHICH field/amount is wrong, while the golden pins the WHOLE record layout
# (id, type, category, description, card-via-alt-key, filler) byte-for-byte.
from tests.helpers.golden_compare import assert_matches_golden

# Every test in this module is an integration-layer test (registered marker; the
# runner selects it with ``-m integration``). --strict-markers makes a typo fatal.
pytestmark = pytest.mark.integration


# ---------------------------------------------------------------------------
# Fixed, deterministic PARM date (JCL PARM analogue). 10 chars == PARM-DATE X(10).
# WHY (Determinism): CBACT04C builds TRAN-ID as PARM-DATE(10) + a 6-digit running
# suffix; pinning the date makes the ids byte-reproducible across runs and workers.
# ---------------------------------------------------------------------------
_PARM_DATE = "2024-01-15"


# ---------------------------------------------------------------------------
# Embedded COBOL artifacts -- reproduced BYTE-EXACT as module constants (fixed
# format: 7 leading spaces put the A-margin at column 8). Kept verbatim because the
# whole harness has been proven end-to-end against exactly these two programs.
# ---------------------------------------------------------------------------
_DRV04C_SRC = """\
       IDENTIFICATION DIVISION.
       PROGRAM-ID. DRV04C.
       DATA DIVISION.
       WORKING-STORAGE SECTION.
       01  MY-PARMS.
           05 MY-LEN   PIC S9(04) COMP VALUE 10.
           05 MY-DATE  PIC X(10).
       PROCEDURE DIVISION.
           ACCEPT MY-DATE FROM ENVIRONMENT 'PARMDATE'
           CALL 'CBACT04C' USING MY-PARMS
           GOBACK.
"""

_LOADX_SRC = """\
       IDENTIFICATION DIVISION.
       PROGRAM-ID. LOADX.
       ENVIRONMENT DIVISION.
       INPUT-OUTPUT SECTION.
       FILE-CONTROL.
           SELECT F-IN  ASSIGN TO INFLAT
                  ORGANIZATION IS SEQUENTIAL
                  FILE STATUS IS ST-IN.
           SELECT F-OUT ASSIGN TO OUTIDX
                  ORGANIZATION IS INDEXED
                  ACCESS MODE IS SEQUENTIAL
                  RECORD KEY IS IDX-CARD
                  ALTERNATE RECORD KEY IS IDX-ACCT WITH DUPLICATES
                  FILE STATUS IS ST-OUT.
       DATA DIVISION.
       FILE SECTION.
       FD  F-IN.
       01  R-IN            PIC X(50).
       FD  F-OUT.
       01  R-OUT.
           05 IDX-CARD     PIC X(16).
           05 FILLER       PIC X(09).
           05 IDX-ACCT     PIC X(11).
           05 FILLER       PIC X(14).
       WORKING-STORAGE SECTION.
       01  ST-IN           PIC XX.
       01  ST-OUT          PIC XX.
       01  WS-EOF          PIC X VALUE 'N'.
       PROCEDURE DIVISION.
           OPEN INPUT F-IN
           OPEN OUTPUT F-OUT
           PERFORM UNTIL WS-EOF = 'Y'
              READ F-IN INTO R-OUT
                 AT END MOVE 'Y' TO WS-EOF
                 NOT AT END WRITE R-OUT
              END-READ
           END-PERFORM
           CLOSE F-IN F-OUT
           DISPLAY 'LOADX DONE ST-OUT=' ST-OUT
           GOBACK.
"""


# Immutable bundle of everything a scenario run produces, so the test bodies stay
# thin and read like assertions rather than plumbing.
_Outcome = namedtuple(
    "_Outcome",
    # WHY expected_final_bal added (Issue 1 xfail): the account-break defect means the
    # last distinct account is never flushed, so it is excluded from expected_bal. To turn
    # the defect from a green "assert it stays stale" pass into a report-visible xfail, the
    # test needs the value the final account WOULD hold if the loop flushed it
    # (original balance + its accrued interest). Carrying it here keeps the derivation
    # single-sourced in _expected_model rather than recomputed in the test body.
    "res accts disc expected_bal final_acct expected_final_bal tx_amts order",
)


# ===========================================================================
# Build helpers
# ===========================================================================
def _find_cbact04c(repo_root: Path) -> Path:
    """Locate the CBACT04C production source, tolerating case variants.

    Parameters
    ----------
    repo_root : pathlib.Path
        Repository root (the ``repo_root`` fixture).

    Returns
    -------
    pathlib.Path
        Path to ``CBACT04C.cbl`` (or ``.CBL``).

    Raises
    ------
    AssertionError
        If neither spelling exists -- a broken checkout, not a test condition.
    """
    # WHY (Assumption): the repo ships lowercase ``.cbl`` here, but z/OS exports and
    # some mirrors use uppercase; accept both so the test is portable across clones.
    for name in ("CBACT04C.cbl", "CBACT04C.CBL"):
        cand = repo_root / "app" / "cbl" / name
        if cand.exists():
            return cand
    raise AssertionError(
        f"CBACT04C source not found under {repo_root / 'app' / 'cbl'} (.cbl/.CBL)"
    )


def _compile(cmd: list[str], *, what: str) -> None:
    """Run a ``cobc`` command, asserting a clean compile.

    Parameters
    ----------
    cmd : list[str]
        The full ``cobc`` argv.
    what : str
        Human label for the artifact (used in the failure message).

    Returns
    -------
    None

    Raises
    ------
    AssertionError
        If ``cobc`` returns non-zero. WHY: DRV04C/LOADX and the unmodified CBACT04C
        are known-good on this toolchain, so a compile failure is a real regression
        (a missing copybook, a bad flag, a toolchain change) -- never an expected
        skip. A hard assertion surfaces it loudly instead of silently degrading.
    """
    proc = subprocess.run(cmd, capture_output=True, text=True)
    assert proc.returncode == 0, (
        f"{what} compile failed (rc={proc.returncode}); this is a real regression.\n"
        f"CMD: {' '.join(cmd)}\nSTDOUT:\n{proc.stdout}\nSTDERR:\n{proc.stderr}"
    )


def _ensure_driver(build_dir: Path, repo_root: Path) -> Path:
    """Compile the ``DRV04C`` driver (static-linked to CBACT04C) into ``build_dir``.

    Parameters
    ----------
    build_dir : pathlib.Path
        Directory the ``cobol_runner`` fixture searches for executables
        (``<repo>/build``; git-ignored, so the artifact is never committed).
    repo_root : pathlib.Path
        Repository root, used to resolve the CBACT04C source and the ``app/cpy``
        copybook include path.

    Returns
    -------
    pathlib.Path
        Path to the built ``DRV04C`` executable.

    Raises
    ------
    pytest.skip.Exception
        If ``cobc`` is not installed (the suite cannot build COBOL here).
    AssertionError
        If the (known-good) program fails to compile.
    """
    if shutil.which("cobc") is None:
        pytest.skip("GnuCOBOL 'cobc' not on PATH; cannot build DRV04C/CBACT04C")

    driver = build_dir / "DRV04C"
    if driver.exists():
        # WHY (Trade-off): reuse a driver a prior test or build_test_programs.sh
        # already produced -- rebuilding per test would waste seconds and, under
        # xdist, invite writers racing on the same path.
        return driver

    build_dir.mkdir(parents=True, exist_ok=True)
    src = _find_cbact04c(repo_root)
    drv_src = build_dir / f".drv04c_{os.getpid()}.cbl"
    drv_src.write_text(_DRV04C_SRC)
    tmp_out = build_dir / f".drv04c_{os.getpid()}.out"
    # WHY (-fsign=EBCDIC): see the module docstring -- without it every signed money
    # field is mis-decoded. --std=ibm-strict accepts the COMP-3/zoned money PICs that
    # -std=cobol85 would reject; -I app/cpy resolves CVTRA*/CVACT* copybooks.
    cmd = [
        "cobc", "-x", "-fixed", "-fsign=EBCDIC", "--std=ibm-strict",
        "-I", str(repo_root / "app" / "cpy"),
        "-o", str(tmp_out), str(drv_src), str(src),
    ]
    try:
        _compile(cmd, what="DRV04C")
    finally:
        drv_src.unlink(missing_ok=True)
    # WHY (xdist-safety): os.replace is atomic on POSIX, so concurrent workers each
    # publish a complete binary (last writer wins; both are byte-identical).
    os.replace(tmp_out, driver)
    return driver


def _ensure_loadx(build_dir: Path) -> Path:
    """Compile the ``LOADX`` alternate-key XREF loader into ``build_dir``.

    Parameters
    ----------
    build_dir : pathlib.Path
        Directory to build into (git-ignored ``<repo>/build``).

    Returns
    -------
    pathlib.Path
        Path to the built ``LOADX`` executable.

    Raises
    ------
    pytest.skip.Exception
        If ``cobc`` is not installed.
    AssertionError
        If LOADX fails to compile.
    """
    if shutil.which("cobc") is None:
        pytest.skip("GnuCOBOL 'cobc' not on PATH; cannot build LOADX")

    binp = build_dir / "LOADX"
    if binp.exists():
        return binp

    build_dir.mkdir(parents=True, exist_ok=True)
    src = build_dir / f".loadx_{os.getpid()}.cbl"
    src.write_text(_LOADX_SRC)
    tmp_out = build_dir / f".loadx_{os.getpid()}.out"
    # WHY: LOADX is all-alphanumeric (PIC X) fields, so -fsign is irrelevant here;
    # the flags otherwise mirror the driver build for consistency.
    cmd = ["cobc", "-x", "-fixed", "--std=ibm-strict", "-o", str(tmp_out), str(src)]
    try:
        _compile(cmd, what="LOADX")
    finally:
        src.unlink(missing_ok=True)
    os.replace(tmp_out, binp)
    return binp


def _load_xref_altkey(build_dir: Path, xref_target: Path, records: list[bytes]) -> None:
    """Load XREF flat records into an indexed file **with** the alternate acct key.

    Parameters
    ----------
    build_dir : pathlib.Path
        Directory holding (or receiving) the LOADX executable.
    xref_target : pathlib.Path
        Destination indexed file, i.e. ``cobol_runner.assign_path("XREFFILE")``.
        LOADX creates this file and its ``<name>.1`` alternate-index sidecar.
    records : list[bytes]
        Raw 50-byte XREF records (unsorted is fine; sorted here).

    Returns
    -------
    None

    Raises
    ------
    AssertionError
        If LOADX exits non-zero, reports a file status other than ``00``, or does
        not produce both index files.
    """
    loadx = _ensure_loadx(build_dir)
    xref_target.parent.mkdir(parents=True, exist_ok=True)

    # WHY (Assumption): a SEQUENTIAL-access indexed WRITE demands ascending primary
    # keys, so the flat input must be pre-sorted by the 16-byte card number @0.
    ordered = sorted(records, key=lambda r: r[0:16])
    flat = xref_target.parent / f".xref_flat_{os.getpid()}.dat"
    # WHY: LOADX reads record-sequential fixed 50-byte records, so the flat MUST be
    # raw N*50 bytes with NO newline separators (a newline would shift every field).
    flat.write_bytes(b"".join(ordered))

    # Remove any stale index + sidecar so OPEN OUTPUT starts from a clean slate.
    sidecar = Path(str(xref_target) + ".1")
    for stale in (xref_target, sidecar):
        if stale.exists():
            stale.unlink()

    env = dict(os.environ)
    env["INFLAT"] = str(flat)      # GnuCOBOL binds ASSIGN TO INFLAT -> this env var.
    env["OUTIDX"] = str(xref_target)
    try:
        proc = subprocess.run(
            [str(loadx)], cwd=str(xref_target.parent),
            env=env, capture_output=True, text=True, timeout=120,
        )
    finally:
        flat.unlink(missing_ok=True)

    assert proc.returncode == 0, (
        f"LOADX exited rc={proc.returncode}\nSTDOUT:{proc.stdout}\nSTDERR:{proc.stderr}"
    )
    # LOADX echoes the final FILE STATUS; '00' is the only success value.
    assert "LOADX DONE ST-OUT=00" in proc.stdout, (
        f"LOADX did not report status 00: {proc.stdout!r}"
    )
    assert xref_target.exists(), "LOADX did not create the XREFFILE primary index"
    assert sidecar.exists(), "LOADX did not create the XREFFILE.1 alternate sidecar"


# ===========================================================================
# Fixture / record helpers
# ===========================================================================
def _fixture_dir(repo_root: Path, scenario: str) -> Path:
    """Return the fixture directory for one interest scenario.

    Parameters
    ----------
    repo_root : pathlib.Path
        Repository root.
    scenario : str
        Scenario folder name (e.g. ``"happy_path"``).

    Returns
    -------
    pathlib.Path
        ``<repo>/tests/fixtures/interest/<scenario>``.

    Raises
    ------
    None
    """
    return repo_root / "tests" / "fixtures" / "interest" / scenario


def _resolve_fixture(scenario_dir: Path, *candidates: str) -> "Path | None":
    """Return the first existing candidate file in ``scenario_dir``, else ``None``.

    Parameters
    ----------
    scenario_dir : pathlib.Path
        Directory to search.
    *candidates : str
        Candidate file names, tried in order.

    Returns
    -------
    pathlib.Path | None
        The first match, or ``None`` when none exist (caller decides to skip).

    Raises
    ------
    None
    """
    # WHY (Refactoring Rationale): tolerating a few name spellings keeps the test
    # resilient to a fixture-generator that emits e.g. ``TCATBALF.txt`` vs
    # ``tcatbal.txt`` without turning a rename into a false failure.
    for name in candidates:
        cand = scenario_dir / name
        if cand.exists():
            return cand
    return None


def _read_records(path: Path, reclen: int) -> list[bytes]:
    """Read a flat file into a list of fixed-width ``reclen``-byte records.

    Parameters
    ----------
    path : pathlib.Path
        File to read.
    reclen : int
        Exact record length in bytes.

    Returns
    -------
    list[bytes]
        The records, each exactly ``reclen`` bytes.

    Raises
    ------
    None
    """
    data = path.read_bytes()
    # WHY (Trade-off): fixtures are authored newline-delimited (one record per line)
    # while GnuCOBOL record-sequential output (TRANSACT) is raw N*reclen with no
    # separators. Prefer newline splitting only when every piece is exactly reclen;
    # otherwise fall back to fixed-width chunking. Assumption: these records are all
    # DISPLAY text, so no legitimate 0x0A byte hides inside a record.
    if b"\n" in data:
        parts = [ln for ln in data.split(b"\n") if ln]
        if parts and all(len(p) == reclen for p in parts):
            return parts
    raw = data.replace(b"\n", b"")
    return [raw[i:i + reclen] for i in range(0, len(raw), reclen) if raw[i:i + reclen]]


def _slice(layout, name: str, rec: str) -> str:
    """Slice one field's raw characters out of a record using its layout descriptor.

    Parameters
    ----------
    layout : tests.helpers.record_codec.RecordLayout
        The record layout owning the field.
    name : str
        COBOL field name (exact copybook spelling).
    rec : str
        The full record as a ``latin-1`` string.

    Returns
    -------
    str
        The field's raw characters (not stripped).

    Raises
    ------
    KeyError
        If ``name`` is not a field of ``layout``.
    """
    fld = layout.field(name)
    return rec[fld.start:fld.end]


def _decode_num(layout, name: str, rec: str) -> Decimal:
    """Decode one zoned-decimal field to an exact :class:`~decimal.Decimal`.

    Parameters
    ----------
    layout : tests.helpers.record_codec.RecordLayout
        The record layout owning the field.
    name : str
        COBOL field name.
    rec : str
        The full record as a ``latin-1`` string.

    Returns
    -------
    decimal.Decimal
        The decoded fixed-point value (honouring trailing-overpunch sign).

    Raises
    ------
    tests.helpers.record_codec.ZonedDecimalError
        If the field bytes are not a valid zoned decimal.
    """
    fld = layout.field(name)
    # WHY (financial precision): decode straight to Decimal -- money is NEVER float.
    return decode_zoned(rec[fld.start:fld.end], fld.int_digits, fld.dec_digits, fld.signed)


def _read_accounts(path: Path) -> "dict[str, tuple[str, Decimal]]":
    """Read the ACCOUNT fixture into ``acct_id -> (group_id, original_balance)``.

    Parameters
    ----------
    path : pathlib.Path
        Path to the 300-byte ACCOUNT fixture.

    Returns
    -------
    dict[str, tuple[str, decimal.Decimal]]
        Keyed by the 11-char zero-padded account id; value is the rstripped group
        id and the original ``ACCT-CURR-BAL``.

    Raises
    ------
    tests.helpers.record_codec.ZonedDecimalError
        If a balance field is malformed.
    """
    accts: "dict[str, tuple[str, Decimal]]" = {}
    for rec in _read_records(path, ACCOUNT_LAYOUT.reclen):
        s = rec.decode("latin-1")
        acct_id = _slice(ACCOUNT_LAYOUT, "ACCT-ID", s)
        group = _slice(ACCOUNT_LAYOUT, "ACCT-GROUP-ID", s).rstrip()
        bal = _decode_num(ACCOUNT_LAYOUT, "ACCT-CURR-BAL", s)
        accts[acct_id] = (group, bal)
    return accts


def _parse_disc(path: Path) -> "dict[tuple[str, str, int], Decimal]":
    """Parse the disclosure-group file into ``(group, type, cat) -> interest_rate``.

    Parameters
    ----------
    path : pathlib.Path
        Path to the 50-byte disclosure-group file (``tests/mocks/mock_discgrp.txt``).

    Returns
    -------
    dict[tuple[str, str, int], decimal.Decimal]
        Group id (rstripped, e.g. ``"DEFAULT"``), 2-char transaction type, integer
        category code -> the ``DIS-INT-RATE`` percentage.

    Raises
    ------
    tests.helpers.record_codec.ZonedDecimalError
        If a rate field is malformed.
    """
    disc: "dict[tuple[str, str, int], Decimal]" = {}
    for rec in _read_records(path, DISGROUP_LAYOUT.reclen):
        s = rec.decode("latin-1")
        group = _slice(DISGROUP_LAYOUT, "DIS-ACCT-GROUP-ID", s).rstrip()
        typ = _slice(DISGROUP_LAYOUT, "DIS-TRAN-TYPE-CD", s)
        cat = int(_slice(DISGROUP_LAYOUT, "DIS-TRAN-CAT-CD", s))
        rate = _decode_num(DISGROUP_LAYOUT, "DIS-INT-RATE", s)
        disc[(group, typ, cat)] = rate
    return disc


def _read_transact(path: Path) -> "list[tuple[str, str, int, Decimal]]":
    """Read the interest ``TRANSACT`` output into ``(id, type, cat, amount)`` tuples.

    Parameters
    ----------
    path : pathlib.Path
        Path to the 350-byte sequential ``TRANSACT`` output file.

    Returns
    -------
    list[tuple[str, str, int, decimal.Decimal]]
        In physical (write) order: ``TRAN-ID``, ``TRAN-TYPE-CD``, integer
        ``TRAN-CAT-CD``, and the exact ``TRAN-AMT``.

    Raises
    ------
    tests.helpers.record_codec.ZonedDecimalError
        If an amount field is malformed.
    """
    if not path.exists():
        # WHY: a scenario that writes no interest transactions yields no file; an
        # empty list lets the caller assert count == 0 rather than error.
        return []
    out: "list[tuple[str, str, int, Decimal]]" = []
    for rec in _read_records(path, TRAN_LAYOUT.reclen):
        s = rec.decode("latin-1")
        tran_id = _slice(TRAN_LAYOUT, "TRAN-ID", s)
        typ = _slice(TRAN_LAYOUT, "TRAN-TYPE-CD", s)
        cat = int(_slice(TRAN_LAYOUT, "TRAN-CAT-CD", s))
        amt = _decode_num(TRAN_LAYOUT, "TRAN-AMT", s)
        # WHY (non-determinism Trade-off): TRAN-ORIG-TS/TRAN-PROC-TS come from
        # CURRENT-DATE and change every run, so they are deliberately NOT read here.
        out.append((tran_id, typ, cat, amt))
    return out


def _expected_model(
    tcat: list[bytes],
    accts: "dict[str, tuple[str, Decimal]]",
    disc: "dict[tuple[str, str, int], Decimal]",
) -> "tuple[dict[str, Decimal], str | None, Decimal | None, list[Decimal], list[str]]":
    """Reproduce CBACT04C's interest math in Python to derive expected outputs.

    Mirrors the COBOL exactly: read ``TCATBALF`` in order; look up the rate by
    ``(group, type, cat)`` and fall back to the ``DEFAULT`` group when the direct key
    is absent (VSAM status 23); when the rate is non-zero, compute
    ``(bal * rate) / 1200`` **truncated** to two decimals and accumulate it per
    account. The final distinct account is excluded from ``expected_bal`` because of
    the account-break defect (see the module docstring), but its *would-be-correct*
    balance is returned separately so the xfail test can assert the fixed behaviour.

    Parameters
    ----------
    tcat : list[bytes]
        The ``TCATBALF`` fixture records, in file order.
    accts : dict[str, tuple[str, decimal.Decimal]]
        ``acct_id -> (group_id, original_balance)`` from :func:`_read_accounts`.
    disc : dict[tuple[str, str, int], decimal.Decimal]
        Disclosure-group rate map from :func:`_parse_disc`.

    Returns
    -------
    tuple
        ``(expected_bal, final_acct, expected_final_bal, tx_amts, order)`` where
        ``expected_bal`` maps each *non-final* account id to its expected post-run
        balance, ``final_acct`` is the last distinct account (never updated by the
        buggy loop), ``expected_final_bal`` is the balance that final account WOULD
        hold if ``1050-UPDATE-ACCOUNT`` flushed it (``original + accrued interest``) or
        ``None`` when there is no account, ``tx_amts`` is the ordered list of expected
        interest ``TRAN-AMT`` values, and ``order`` is the first-seen account order.

    Raises
    ------
    tests.helpers.record_codec.ZonedDecimalError
        If a balance field is malformed.
    """
    order: list[str] = []
    total: "dict[str, Decimal]" = {}
    tx_amts: list[Decimal] = []

    for rec in tcat:
        s = rec.decode("latin-1")
        acct = _slice(TCATBAL_LAYOUT, "TRANCAT-ACCT-ID", s)
        typ = _slice(TCATBAL_LAYOUT, "TRANCAT-TYPE-CD", s)
        cat = int(_slice(TCATBAL_LAYOUT, "TRANCAT-CD", s))
        bal = _decode_num(TCATBAL_LAYOUT, "TRAN-CAT-BAL", s)

        if acct not in total:
            order.append(acct)
            total[acct] = Decimal("0.00")

        group = accts.get(acct, ("", Decimal("0.00")))[0]
        rate = disc.get((group, typ, cat))
        if rate is None:
            # DEFAULT fallback -- the mandatory VSAM-status-23 branch.
            rate = disc.get(("DEFAULT", typ, cat))
        if rate is None:
            rate = Decimal("0")

        if rate != 0:
            # WHY (truncation, not rounding): WS-MONTHLY-INT is S9(09)V99 and the
            # COBOL COMPUTE has no ROUNDED clause, so the result is truncated toward
            # zero at two decimals -- ROUND_DOWN reproduces that byte-for-byte.
            monthly = (bal * rate / Decimal(1200)).quantize(
                Decimal("0.01"), rounding=ROUND_DOWN
            )
            total[acct] += monthly
            tx_amts.append(monthly)

    final_acct = order[-1] if order else None
    expected_bal = {
        acct: accts[acct][1] + total[acct]
        for acct in order
        if acct != final_acct and acct in accts
    }
    # WHY (Issue 1 -- what the final account SHOULD be): the account-break defect
    # leaves the last distinct account un-flushed, so it is deliberately absent from
    # expected_bal above. The xfail test needs the value the loop WOULD have written
    # if 1050-UPDATE-ACCOUNT ran at EOF: the original balance plus the interest that
    # was accrued for it (total[final_acct]). Deriving it here -- next to the identical
    # non-final formula -- keeps the "correct balance" contract single-sourced.
    expected_final_bal: "Decimal | None" = None
    if final_acct is not None and final_acct in accts:
        expected_final_bal = accts[final_acct][1] + total[final_acct]
    return expected_bal, final_acct, expected_final_bal, tx_amts, order


def _updated_accounts(cobol_runner) -> "dict[str, Decimal]":
    """Read the post-run ``ACCTFILE`` back into ``acct_id -> ACCT-CURR-BAL``.

    Parameters
    ----------
    cobol_runner : tests.helpers.cobol_runner.CobolRunner
        The active runner (its workspace holds the rewritten indexed ACCTFILE).

    Returns
    -------
    dict[str, decimal.Decimal]
        Current balance per account after CBACT04C ran.

    Raises
    ------
    tests.helpers.record_codec.ZonedDecimalError
        If a balance field is malformed.
    """
    # unload_output walks the indexed file in primary-key order and returns flat
    # 300-char records; we decode only the two fields we assert on (balance/group)
    # to avoid a brittle whole-record decode of fields the program may leave unset.
    updated: "dict[str, Decimal]" = {}
    for rec in cobol_runner.unload_output(
        "ACCTFILE", ACCOUNT_LAYOUT.reclen, ACCOUNT_LAYOUT.key_length
    ):
        acct_id = _slice(ACCOUNT_LAYOUT, "ACCT-ID", rec)
        updated[acct_id] = _decode_num(ACCOUNT_LAYOUT, "ACCT-CURR-BAL", rec)
    return updated


def _drive_scenario(cobol_runner, build_dir: Path, repo_root: Path, scenario: str) -> _Outcome:
    """Compile, provision, and run CBACT04C for one scenario; return an outcome bundle.

    Parameters
    ----------
    cobol_runner : tests.helpers.cobol_runner.CobolRunner
        Function-scoped runner (fresh, isolated workspace).
    build_dir : pathlib.Path
        Executable build directory (``<repo>/build``).
    repo_root : pathlib.Path
        Repository root.
    scenario : str
        Fixture scenario name under ``tests/fixtures/interest/``.

    Returns
    -------
    _Outcome
        Bundle of the run result plus the Python-derived expectations.

    Raises
    ------
    pytest.skip.Exception
        If ``cobc`` or any required fixture / mock is missing.
    AssertionError
        If a build or the alternate-key load fails.
    """
    fixture_dir = _fixture_dir(repo_root, scenario)
    tcat_p = _resolve_fixture(fixture_dir, "tcatbal.txt", "TCATBAL.txt", "tcatbalf.txt")
    acct_p = _resolve_fixture(fixture_dir, "acctdata.txt", "ACCTDATA.txt", "acctfile.txt")
    xref_p = _resolve_fixture(fixture_dir, "cardxref.txt", "CARDXREF.txt", "xreffile.txt")
    disc_p = repo_root / "tests" / "mocks" / "mock_discgrp.txt"

    missing = [
        label for label, path in (
            ("tcatbal", tcat_p), ("acctdata", acct_p), ("cardxref", xref_p),
            ("tests/mocks/mock_discgrp.txt", disc_p if disc_p.exists() else None),
        ) if path is None
    ]
    if missing:
        pytest.skip("interest fixtures/mock not present: " + ", ".join(missing))

    # Build the driver first so a toolchain problem fails fast, before we provision.
    _ensure_driver(build_dir, repo_root)

    # Primary-key indexed inputs via the shared helper (geometry single-sourced from
    # record_codec so key widths never drift from the copybooks).
    cobol_runner.load_input(
        "TCATBALF", str(tcat_p), TCATBAL_LAYOUT.reclen, TCATBAL_LAYOUT.key_length
    )
    cobol_runner.load_input(
        "ACCTFILE", str(acct_p), ACCOUNT_LAYOUT.reclen, ACCOUNT_LAYOUT.key_length
    )
    cobol_runner.load_input(
        "DISCGRP", str(disc_p), DISGROUP_LAYOUT.reclen, DISGROUP_LAYOUT.key_length
    )
    # XREF needs the alternate acct key -> dedicated LOADX loader (see its docstring).
    _load_xref_altkey(build_dir, cobol_runner.assign_path("XREFFILE"), _read_records(xref_p, 50))

    # env_overrides feeds DRV04C's ACCEPT ... FROM ENVIRONMENT 'PARMDATE'.
    res = cobol_runner.run("DRV04C", env_overrides={"PARMDATE": _PARM_DATE})

    accts = _read_accounts(acct_p)
    disc = _parse_disc(disc_p)
    tcat = _read_records(tcat_p, TCATBAL_LAYOUT.reclen)
    # WHY unpack expected_final_bal here (Issue 1): _expected_model now also derives the
    # would-be-correct balance of the un-flushed final account; carry it through the
    # _Outcome bundle so the xfail test asserts the fixed value without recomputing it.
    expected_bal, final_acct, expected_final_bal, tx_amts, order = _expected_model(
        tcat, accts, disc
    )
    return _Outcome(
        res=res, accts=accts, disc=disc, expected_bal=expected_bal,
        final_acct=final_acct, expected_final_bal=expected_final_bal,
        tx_amts=tx_amts, order=order,
    )


def _assert_interest_goldens(cobol_runner, res, repo_root, scenario, *, update=None):
    """Compare (or, under the guarded protocol, regenerate) one interest scenario's goldens.

    Purpose
    -------
    QA Issue 5 / MA-04 (MAJOR, Oracle Integrity) found the interest module proved its math
    with a derived Decimal model but never *consumed* the committed
    ``tests/golden/interest/**/*.expected`` files, so drift in the whole-record layout
    (ids, type/category, description, filler) went uncaught -- and while ``happy_path`` was
    later wired in, the ``default_fallback`` and ``zero_balance`` oracle families stayed
    committed-but-dead. This helper is now the single, ``scenario``-parameterized place that
    wires the AAP-designated byte-exact comparator
    (:func:`tests.helpers.golden_compare.assert_matches_golden`) over every observable output
    of the named ``scenario``: the condition code, the rewritten ``ACCTFILE`` master, and the
    ``TRANSACT`` interest stream. Keying the golden directory on ``scenario`` is exactly what
    lets ALL three interest oracle families be consumed by one reviewed implementation,
    closing MA-04 (no oracle is left un-diffed).

    WHY one helper drives BOTH compare and regenerate (single source of truth): the
    regeneration path (``update=True``) and the test's compare path (``update=None``) MUST
    extract and frame exactly the same bytes, or a regenerated golden would not round-trip.
    Factoring the three comparisons here guarantees that. The committed test call always
    uses the ``update=None`` default (pure compare); only a deliberate, never-committed
    regeneration run passes ``update=True`` -- and even then :func:`assert_matches_golden`
    still enforces the full MA-12 two-step guard (``CARDDEMO_UPDATE_GOLDENS=1`` and not-CI).

    Parameters
    ----------
    cobol_runner : tests.helpers.cobol_runner.CobolRunner
        The active runner (its workspace holds the rewritten indexed ``ACCTFILE``).
    res : tests.helpers.cobol_runner.RunResult
        The completed ``DRV04C`` run result (source of the return code and ``TRANSACT``).
    repo_root : pathlib.Path
        Repository root, used to locate the golden directory.
    scenario : str
        Interest scenario folder name under ``tests/golden/interest/`` -- one of
        ``"happy_path"``, ``"default_fallback"``, or ``"zero_balance"``. Selects which
        committed oracle family (the ``return_code``/``acctdat``/``transact`` ``.expected``
        trilogy in that folder) the three comparisons below are diffed against.
    update : bool or None, optional
        Forwarded verbatim to :func:`assert_matches_golden`. ``None`` (default) compares;
        an explicit ``True`` requests a guarded regeneration. Defaults to ``None``.

    Returns
    -------
    None

    Raises
    ------
    tests.helpers.golden_compare.GoldenMismatchError
        If any golden is missing (compare mode) or a normalized output differs from it.
    tests.helpers.golden_compare.GoldenUpdateError
        If ``update=True`` but the MA-12 safe-update guard blocks the write.
    """
    # WHY scenario-keyed (Refactoring Rationale, MA-04): the three sibling oracle files
    # (return_code/acctdat/transact .expected) live in one directory per scenario, so keying
    # golden_dir on the caller-supplied `scenario` lets this single helper consume EVERY
    # committed interest oracle family instead of only happy_path -- which is the concrete
    # fix for the "committed but never consumed" dead-oracle finding. Alternatives Considered:
    # three near-identical per-scenario helpers -- rejected, because duplicating the compare
    # /regenerate byte-framing invites it to drift apart between scenarios (the exact class of
    # divergence MA-02/MA-08 flagged elsewhere), whereas one parameterized helper cannot.
    golden_dir = Path(repo_root) / "tests" / "golden" / "interest" / scenario

    # RETURN-CODE (text mode): the top-level pass/warn/fail signal per the AAP RC rubric.
    # WHY text mode (no layout): the code is a bare integer, not a fixed-width record; text
    # normalization canonicalises the trailing newline so "0" and "0\n" compare equal.
    assert_matches_golden(
        str(res.returncode), golden_dir / "return_code.expected", update=update,
    )

    # ACCTFILE (record mode, ACCOUNT 300B): the whole rewritten master in primary-key
    # order. WHY join with "\n" (Assumption): record mode frames by fixed WIDTH, so the
    # newline-joined records compare equal to the width-framed golden regardless of joiner.
    # WHY encoding="latin-1": account records are fixed-width bytes (zoned decimal + possible
    # LOW-VALUES 0x00 filler); latin-1 is the identity byte<->codepoint map so the golden is
    # read/written byte-for-byte with no re-encoding, keeping the comparison byte-exact.
    acct_records = cobol_runner.unload_output("ACCTFILE", layout="ACCOUNT")
    assert_matches_golden(
        "\n".join(acct_records),
        golden_dir / "acctdat.expected",
        layout="ACCOUNT",
        encoding="latin-1",
        update=update,
    )

    # TRANSACT (record mode, INTTRAN 350B): the interest transaction stream, a raw
    # SEQUENTIAL blob of N*350 concatenated bytes with no delimiters. WHY layout="INTTRAN"
    # (not "TRAN"): CBACT04C sets BOTH TRAN-ORIG-TS and TRAN-PROC-TS from CURRENT-DATE, so
    # both are non-deterministic; INTTRAN is record_codec's purpose-built clone of TRAN that
    # marks BOTH timestamps normalize_ts, so record-mode blanks them on each side and the
    # comparison is deterministic across runs. WHY encoding="latin-1": same byte-identity
    # rationale as ACCTFILE -- the read_output str is latin-1 and the golden is read/written
    # latin-1, so any 0x00 FILLER byte round-trips exactly.
    transact = res.read_output("TRANSACT")
    assert_matches_golden(
        transact,
        golden_dir / "transact.expected",
        layout="INTTRAN",
        encoding="latin-1",
        update=update,
    )


# ===========================================================================
# Tests
# ===========================================================================
def test_happy_path_interest_and_balances(cobol_runner, build_dir, repo_root):
    """Direct-hit interest posts exact balances and deterministic transactions.

    Purpose
    -------
    With every account mapped to a disclosure group that has a direct rate match,
    assert the whole observable contract: RETURN-CODE 0, each *non-final* account's
    ``ACCT-CURR-BAL`` equals ``original + accumulated interest`` (exact Decimal), and
    the ``TRANSACT`` stream holds one interest record per non-zero-rate category with
    the expected amount, deterministic ``TRAN-ID``, type ``01`` and category ``5``.

    Parameters
    ----------
    cobol_runner : tests.helpers.cobol_runner.CobolRunner
        Fresh isolated runner fixture.
    build_dir : pathlib.Path
        Executable build directory fixture.
    repo_root : pathlib.Path
        Repository root fixture.

    Returns
    -------
    None

    Raises
    ------
    AssertionError
        On any output mismatch.
    """
    outcome = _drive_scenario(cobol_runner, build_dir, repo_root, "happy_path")

    assert outcome.res.returncode == 0, (
        f"CBACT04C returned {outcome.res.returncode}\nSTDERR:\n{outcome.res.stderr}"
    )

    # Balances -- compare against values DERIVED from the fixtures, not hard-coded,
    # so the assertion stays correct if a fixture is re-tuned.
    updated = _updated_accounts(cobol_runner)
    assert outcome.expected_bal, "fixture must contain >= 2 accounts (see defect note)"
    for acct, expected in outcome.expected_bal.items():
        assert updated[acct] == expected, (
            f"account {acct} balance {updated[acct]} != expected {expected}"
        )

    # Interest transactions: exact amounts, count, and deterministic identifiers.
    txs = _read_transact(outcome.res.output_path("TRANSACT"))
    assert [amt for (_, _, _, amt) in txs] == outcome.tx_amts, (
        f"TRANSACT amounts {[a for (*_, a) in txs]} != expected {outcome.tx_amts}"
    )
    for index, (tran_id, typ, cat, _amt) in enumerate(txs, start=1):
        # WHY: TRAN-ID == PARM-DATE(10) + a 6-digit running suffix that starts at 1
        # and increments once per written transaction across the whole run.
        expected_id = f"{_PARM_DATE}{index:06d}"
        assert tran_id == expected_id, f"TRAN-ID {tran_id!r} != {expected_id!r}"
        assert typ == "01", f"interest TRAN-TYPE-CD should be '01', got {typ!r}"
        assert cat == 5, f"interest TRAN-CAT-CD should be 5 (0005), got {cat}"

    # Golden oracle (QA Issue 5): after proving the math field-by-field above, also assert
    # the WHOLE observable output byte-for-byte against the committed goldens. WHY both
    # oracles (Trade-off): the Decimal model localises WHICH amount/balance is wrong, while
    # the golden pins the ENTIRE record layout (id, type, category, description, alt-key
    # card number, filler) that the field checks do not inspect -- catching layout drift a
    # value-only assertion would miss.
    _assert_interest_goldens(cobol_runner, outcome.res, repo_root, "happy_path")


def test_default_group_fallback(cobol_runner, build_dir, repo_root):
    """A missing disclosure-group key falls back to the DEFAULT group (status 23).

    Purpose
    -------
    The accounts carry a blank ``ACCT-GROUP-ID``, so the direct ``DISCGRP`` read
    misses (VSAM file-status 23) and CBACT04C retries under the literal ``DEFAULT``
    group. Assert the two diagnostic stdout lines are emitted AND that interest was
    actually applied at the DEFAULT rate (the non-final balance rose to the derived
    expectation and is strictly greater than the original). This is the mandatory
    100%-branch case of AAP section 0.7.1.

    Parameters
    ----------
    cobol_runner : tests.helpers.cobol_runner.CobolRunner
        Fresh isolated runner fixture.
    build_dir : pathlib.Path
        Executable build directory fixture.
    repo_root : pathlib.Path
        Repository root fixture.

    Returns
    -------
    None

    Raises
    ------
    AssertionError
        If the fallback path is not taken or the fallback rate is not applied.
    """
    outcome = _drive_scenario(cobol_runner, build_dir, repo_root, "default_fallback")

    assert outcome.res.returncode == 0, (
        f"CBACT04C returned {outcome.res.returncode}\nSTDERR:\n{outcome.res.stderr}"
    )
    # WHY: 1200-GET-INTEREST-RATE DISPLAYs these exact strings on the INVALID KEY /
    # status-23 branch; their presence proves the fallback branch executed.
    assert "DISCLOSURE GROUP RECORD MISSING" in outcome.res.stdout, outcome.res.stdout
    assert "TRY WITH DEFAULT GROUP CODE" in outcome.res.stdout, outcome.res.stdout

    updated = _updated_accounts(cobol_runner)
    assert outcome.expected_bal, "fixture must contain >= 2 accounts (see defect note)"
    for acct, expected in outcome.expected_bal.items():
        assert updated[acct] == expected, (
            f"account {acct} balance {updated[acct]} != expected {expected}"
        )
    # Prove the DEFAULT rate was genuinely applied (non-zero interest), not skipped.
    nonfinal = outcome.order[0]
    original = outcome.accts[nonfinal][1]
    assert updated[nonfinal] > original, (
        "DEFAULT-group interest should have increased the balance; "
        f"{updated[nonfinal]} !> {original}"
    )

    # Golden oracle (MA-04): consume the committed default_fallback oracle family so the
    # WHOLE observable output (RETURN-CODE, rewritten ACCTFILE, TRANSACT stream) is pinned
    # byte-for-byte -- not merely the derived balances and the two diagnostic stdout lines
    # asserted above. WHY both oracles (Trade-off): the Decimal/stdout checks localise WHICH
    # balance is wrong or WHETHER the status-23 fallback branch fired, while the golden pins
    # the ENTIRE record layout (TRAN-ID, type/category, description, alt-key card number,
    # 0x00 filler, and the 206.50 accrued balance) that the value/branch assertions never
    # inspect. Before MA-04 these .expected files were committed but never diffed (a dead
    # oracle), so fallback-path layout drift could regress silently.
    _assert_interest_goldens(cobol_runner, outcome.res, repo_root, "default_fallback")


def test_zero_balance_yields_zero_interest(cobol_runner, build_dir, repo_root):
    """A zero category balance yields zero interest and an unchanged account balance.

    Purpose
    -------
    With ``TRAN-CAT-BAL = 0`` but a non-zero rate, CBACT04C still enters
    1300-COMPUTE-INTEREST (rate != 0) and writes an interest transaction, but the
    computed monthly interest is ``0.00`` -- so the non-final account balance is
    unchanged and every emitted ``TRAN-AMT`` is ``0.00``.

    Parameters
    ----------
    cobol_runner : tests.helpers.cobol_runner.CobolRunner
        Fresh isolated runner fixture.
    build_dir : pathlib.Path
        Executable build directory fixture.
    repo_root : pathlib.Path
        Repository root fixture.

    Returns
    -------
    None

    Raises
    ------
    AssertionError
        If any interest is applied or any transaction amount is non-zero.
    """
    outcome = _drive_scenario(cobol_runner, build_dir, repo_root, "zero_balance")

    assert outcome.res.returncode == 0, (
        f"CBACT04C returned {outcome.res.returncode}\nSTDERR:\n{outcome.res.stderr}"
    )

    updated = _updated_accounts(cobol_runner)
    nonfinal = outcome.order[0]
    original = outcome.accts[nonfinal][1]
    # Balance unchanged: model expectation and the observed value must both equal orig.
    assert updated[nonfinal] == original, (
        f"zero-balance account {nonfinal} changed: {updated[nonfinal]} != {original}"
    )
    assert outcome.expected_bal[nonfinal] == original, (
        "derive-model disagrees: expected zero interest for a zero balance"
    )

    txs = _read_transact(outcome.res.output_path("TRANSACT"))
    assert all(amt == Decimal("0.00") for (_, _, _, amt) in txs), (
        f"all interest amounts should be 0.00, got {[a for (*_, a) in txs]}"
    )
    # Model must agree that every computed monthly amount is zero.
    assert outcome.tx_amts == [Decimal("0.00")] * len(outcome.tx_amts)

    # Golden oracle (MA-04): consume the committed zero_balance oracle family. WHY it matters
    # HERE specifically (Assumption made explicit): zero interest means the ACCTFILE is
    # rewritten with BYTE-IDENTICAL content to the input fixture, and each TRANSACT record
    # carries TRAN-AMT = +0.00 encoded as the zoned-decimal overpunch `0000000000{` (ten
    # zeros then `{`), NOT eleven plain zeros -- a distinction the value-only Decimal
    # assertions above are blind to (they see 0.00 either way). The byte-exact golden is the
    # only oracle that proves the +0.00 sign overpunch and that all 300-byte account records
    # round-trip unchanged. Before MA-04 these .expected files were committed but never
    # diffed (a dead oracle).
    _assert_interest_goldens(cobol_runner, outcome.res, repo_root, "zero_balance")


def test_no_fee_applied(cobol_runner, build_dir, repo_root):
    """The balance delta equals interest ONLY -- the fee stub adds nothing.

    Purpose
    -------
    ``1400-COMPUTE-FEES`` is an unimplemented ``EXIT`` stub ("To be implemented"),
    so no fee is ever posted. Assert the non-final account's balance change equals
    exactly the accumulated interest, documenting that current no-fee behaviour (and
    flagging the gap without modifying production code).

    Parameters
    ----------
    cobol_runner : tests.helpers.cobol_runner.CobolRunner
        Fresh isolated runner fixture.
    build_dir : pathlib.Path
        Executable build directory fixture.
    repo_root : pathlib.Path
        Repository root fixture.

    Returns
    -------
    None

    Raises
    ------
    AssertionError
        If the balance delta differs from the interest (i.e. a fee crept in).
    """
    outcome = _drive_scenario(cobol_runner, build_dir, repo_root, "happy_path")

    assert outcome.res.returncode == 0, (
        f"CBACT04C returned {outcome.res.returncode}\nSTDERR:\n{outcome.res.stderr}"
    )

    updated = _updated_accounts(cobol_runner)
    nonfinal = outcome.order[0]
    original = outcome.accts[nonfinal][1]
    interest_only = outcome.expected_bal[nonfinal] - original
    # WHY: expected_bal already reflects interest with NO fee term; requiring the
    # observed delta to match it proves 1400-COMPUTE-FEES contributed nothing.
    assert updated[nonfinal] - original == interest_only, (
        f"balance delta {updated[nonfinal] - original} != interest-only "
        f"{interest_only}; a fee may have been applied"
    )
    assert interest_only > Decimal("0.00"), (
        "happy_path must accrue positive interest for this test to be meaningful"
    )


@pytest.mark.xfail(
    strict=True,
    reason=(
        "KNOWN PRODUCTION DEFECT (CBACT04C account-break): the last distinct account "
        "in TCATBALF order is never flushed because the main loop's outer "
        "'ELSE PERFORM 1050-UPDATE-ACCOUNT' is unreachable at end-of-file, so its "
        "accrued interest is silently dropped. This test asserts the CORRECT behaviour "
        "(final account rewritten to original + accrued interest); it xfails until the "
        "production loop is fixed in app/cbl/CBACT04C.cbl. app/cbl is REFERENCE-only per "
        "AAP 0.8.2, so the fix is out of this suite's scope -- when it lands, xfail_strict "
        "(pytest.ini) turns the resulting xpass into a hard failure, forcing this marker "
        "to be removed."
    ),
)
def test_final_account_not_updated(cobol_runner, build_dir, repo_root):
    """Assert the final distinct account WOULD be updated once the account-break defect is fixed.

    Purpose
    -------
    The main loop's outer ``ELSE PERFORM 1050-UPDATE-ACCOUNT`` is unreachable at EOF,
    so the last distinct account in ``TCATBALF`` order keeps its original balance even
    though its category rows accrued interest. QA Issue 1 (CRITICAL) flagged that the
    prior version of this test merely *asserted the buggy state* (balance unchanged),
    which passed green and hid a real monetary defect from every report.

    This rewrite instead asserts the *correct* post-run balance
    (``original + accrued interest``) and is marked ``xfail(strict=True)``. WHY xfail
    rather than a plain failing assert (Alternatives Considered): a bare failing assert
    would turn the suite red and block CI on a defect this test-only suite is forbidden
    to fix (``app/cbl`` is REFERENCE-only, AAP 0.8.2). ``xfail`` records the defect as a
    first-class, report-visible expected failure while keeping the suite green; ``strict``
    guarantees that if the production loop is ever repaired the resulting *xpass* becomes
    a hard failure (``xfail_strict = True`` in ``pytest.ini``), which is the signal to
    delete this marker and convert the assertion to a normal pass.

    Parameters
    ----------
    cobol_runner : tests.helpers.cobol_runner.CobolRunner
        Fresh isolated runner fixture.
    build_dir : pathlib.Path
        Executable build directory fixture.
    repo_root : pathlib.Path
        Repository root fixture.

    Returns
    -------
    None

    Raises
    ------
    AssertionError
        Expected (xfail) while the defect is present: the final account's observed
        balance still equals its original balance instead of the interest-adjusted value.
    """
    outcome = _drive_scenario(cobol_runner, build_dir, repo_root, "happy_path")

    assert outcome.res.returncode == 0, (
        f"CBACT04C returned {outcome.res.returncode}\nSTDERR:\n{outcome.res.stderr}"
    )
    assert outcome.final_acct is not None, "scenario must have at least one account"
    assert outcome.expected_final_bal is not None, (
        "scenario must accrue interest for the final account for this test to be meaningful"
    )

    updated = _updated_accounts(cobol_runner)
    original_final = outcome.accts[outcome.final_acct][1]
    # WHY assert the accrual actually moves the balance (guards a vacuous xfail): if the
    # final account happened to accrue zero interest, expected_final_bal would equal the
    # original and the correctness assertion below could pass for the wrong reason. This
    # scenario is chosen so the final account DOES accrue, so a strict difference must hold.
    assert outcome.expected_final_bal != original_final, (
        "happy_path must accrue non-zero interest for the final account so the "
        "correctness assertion is not vacuously satisfied by the buggy no-op"
    )
    # WHY this is the CORRECTNESS assertion (Issue 1): with the defect present the final
    # account is never rewritten, so updated[...] still equals original_final and this
    # equality FAILS -> the test xfails (documented, report-visible). Once the loop flushes
    # the final account, updated[...] becomes expected_final_bal, this passes, and strict
    # xfail escalates the xpass to a failure prompting marker removal.
    assert updated[outcome.final_acct] == outcome.expected_final_bal, (
        f"final account {outcome.final_acct} balance {updated[outcome.final_acct]} "
        f"!= interest-adjusted {outcome.expected_final_bal} "
        f"(original {original_final}); the account-break defect drops its accrued interest"
    )

