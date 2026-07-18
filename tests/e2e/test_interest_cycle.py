"""End-to-end (Layer-3) golden-master tests for the CardDemo *interest* cycle.

Purpose
-------
Reproduce the interest-accrual leg of the daily batch chain (the ``INTCALC`` JCL
step that executes ``CBACT04C``) on deterministic seed data and assert, to
financial-enterprise rigor, on every observable output: the process return code,
the emitted interest-transaction records (type/category/id/description), the
exercise of the ``DEFAULT`` disclosure-group fallback, and a golden-master
projection of the generated interest transactions.

Ordering (WHY post -> interest)
------------------------------
The shipped ``tcatbal`` seed carries **zero** category balances, and ``CBACT04C``
computes ``interest = (TRAN-CAT-BAL * rate) / 1200`` -- zero balances would yield
zero interest and make the test vacuous. This cycle therefore first runs the
posting program (``CBTRN02C``) to populate category balances, then runs interest,
exactly mirroring the real daily batch dependency order.

Determinism & isolation
-----------------------
The cycle date is injected via ``PARM_DATE`` (fixed ``2022071800``) so the emitted
TRAN-IDs (which embed the date) are reproducible. Each test uses a fresh workspace
(function-scoped ``tmp_path``); the golden projection deliberately drops the
date-prefixed TRAN-ID and the non-deterministic timestamps, keeping only the
PARM-independent business fields (type | category | amount | description).

Binding contract
-----------------
Collected by ``scripts/run_e2e_tests.sh`` -> ``pytest tests/e2e -m e2e``.
"""
from __future__ import annotations

import os
import subprocess
from decimal import Decimal
from pathlib import Path

import pytest

# §0.6.2 import contract + single-sourced record decoding.
from tests.helpers.golden_compare import assert_matches_golden
from tests.helpers.record_codec import decode_zoned

pytestmark = pytest.mark.e2e

# Deterministic cycle date. WHY fixed: CBACT04C prefixes every generated TRAN-ID
# with this value; pinning it makes the run byte-reproducible for golden compares.
PARM_DATE = "2022071800"

# Expected interest-transaction classification (verbatim spec, never redefined):
# CBACT04C writes type '01', category '0005' for each accrued interest transaction.
INT_TYPE = "01"
INT_CAT = "0005"
# The DEFAULT disclosure-group fallback emits this diagnostic when a group key is
# absent (VSAM status 23); its presence proves the fallback branch was exercised.
DEFAULT_FALLBACK_MARKER = "TRY WITH DEFAULT GROUP CODE"

# Fixed-width geometry of an interest TRAN-RECORD (CVTRA05Y, 350 bytes).
_TR_RECLEN = 350
_TR_ID = slice(0, 16)       # TRAN-ID X(16) -- date-prefixed => excluded from golden
_TR_TYPE = slice(16, 18)    # TRAN-TYPE-CD X(02)
_TR_CAT = slice(18, 22)     # TRAN-CAT-CD 9(04)
_TR_DESC = slice(32, 132)   # TRAN-DESC X(100)
_TR_AMT = slice(132, 143)   # TRAN-AMT S9(09)V99 (11 zoned bytes)

# Zoned-decimal geometry of TRAN-AMT (PIC S9(09)V99): 9 integer digits, 2 decimal
# digits, signed via trailing overpunch. WHY (Assumption): the shared
# tests.helpers.record_codec.decode_zoned takes (raw, int_digits, dec_digits,
# signed) with no defaults, so the field geometry is supplied explicitly here --
# single-sourced from the CVTRA05Y copybook contract and matching how the
# integration layer decodes the same field.
_TR_AMT_INT_DIGITS = 9
_TR_AMT_DEC_DIGITS = 2
_TR_AMT_SIGNED = True

# Loader geometry (reclen, key_length) proven against the seeds.
_ACCT = (300, 11)
_TCAT = (50, 17)
_DISC = (50, 16)   # CVTRA02Y: group+type+cat composite key of 16 bytes

# Alternate-key XREF provisioner -- embedded because vsam_loader has no alt-key
# parameter and CBACT04C reads XREF by its ALTERNATE key (ACCT-ID); a primary-only
# load raises VSAM status 35 and abends. (See posting cycle for the full rationale.)
_XREF_PROVISIONER_SRC = """\
       IDENTIFICATION DIVISION.
       PROGRAM-ID. LDXREFA.
       ENVIRONMENT DIVISION.
       INPUT-OUTPUT SECTION.
       FILE-CONTROL.
           SELECT IN-F ASSIGN TO DYNAMIC WS-IN
               ORGANIZATION IS LINE SEQUENTIAL FILE STATUS IS ST-IN.
           SELECT OUT-F ASSIGN TO DYNAMIC WS-OUT
               ORGANIZATION IS INDEXED ACCESS MODE IS DYNAMIC
               RECORD KEY IS OUT-CARD
               ALTERNATE RECORD KEY IS OUT-ACCT WITH DUPLICATES
               FILE STATUS IS ST-OUT.
       DATA DIVISION.
       FILE SECTION.
       FD IN-F.
       01 IN-REC PIC X(80).
       FD OUT-F.
       01 OUT-REC.
          05 OUT-CARD PIC X(16).
          05 OUT-CUST PIC X(09).
          05 OUT-ACCT PIC X(11).
          05 OUT-FILL PIC X(14).
       WORKING-STORAGE SECTION.
       01 WS-IN PIC X(256).
       01 WS-OUT PIC X(256).
       01 ST-IN PIC XX.
       01 ST-OUT PIC XX.
       01 EOFF PIC X VALUE 'N'.
       PROCEDURE DIVISION.
       0000-MAIN.
           ACCEPT WS-IN FROM ENVIRONMENT "INFLAT"
           ACCEPT WS-OUT FROM ENVIRONMENT "OUTIDX"
           OPEN INPUT IN-F OPEN OUTPUT OUT-F
           PERFORM UNTIL EOFF = 'Y'
             READ IN-F AT END MOVE 'Y' TO EOFF NOT AT END
               MOVE SPACES TO OUT-REC
               MOVE IN-REC(1:50) TO OUT-REC(1:50)
               WRITE OUT-REC END-WRITE
             END-READ
           END-PERFORM
           CLOSE IN-F OUT-F GOBACK.
"""


def _seed_dir(repo_root: Path) -> Path:
    """Resolve the directory of deterministic seed inputs for the interest cycle.

    Parameters
    ----------
    repo_root : pathlib.Path
        Repository root supplied by the ``repo_root`` fixture.

    Returns
    -------
    pathlib.Path
        Directory containing the master and transaction seeds.

    Notes
    -----
    Resolution order (WHY): explicit env override for CI, then a dedicated e2e
    fixtures tree if present, else the shipped REFERENCE seeds in ``app/data/ASCII``.
    """
    override = os.environ.get("CARDDEMO_E2E_SEED_DIR")
    if override and (Path(override) / "dailytran.txt").exists():
        return Path(override)
    fixtures = repo_root / "tests" / "fixtures" / "e2e"
    if (fixtures / "dailytran.txt").exists():
        return fixtures
    return repo_root / "app" / "data" / "ASCII"


def _provision_alt_key_xref(runner, seed_cardxref: Path) -> Path:
    """Build the alternate-key XREF indexed file required by ``CBACT04C``.

    Parameters
    ----------
    runner : tests.helpers.cobol_runner.CobolRunner
        Active runner (provides ``build_dir`` and ``assign_path``).
    seed_cardxref : pathlib.Path
        Flat seed cross-reference file (``cardxref.txt``).

    Returns
    -------
    pathlib.Path
        Path of the provisioned ``XREFFILE`` indexed dataset.

    Raises
    ------
    subprocess.CalledProcessError
        If compiling or running the provisioner fails.

    Notes
    -----
    WHY: ``CBACT04C`` opens XREF for random reads on its ALTERNATE key (ACCT-ID);
    without the alternate index the OPEN yields status 35 and the program abends.
    """
    exe = runner.build_dir / "LDXREFA"
    if not exe.exists():
        src = runner.build_dir / "LDXREFA.cbl"
        src.write_text(_XREF_PROVISIONER_SRC)
        subprocess.run(
            ["cobc", "-x", "-free", "-o", str(exe), str(src)],
            check=True, capture_output=True,
        )
    out = runner.assign_path("XREFFILE")
    env = {**os.environ, "INFLAT": str(seed_cardxref), "OUTIDX": str(out)}
    subprocess.run([str(exe)], env=env, check=True, capture_output=True)
    return out


def _flatten_daily(src: Path, dst: Path, reclen: int = 350) -> int:
    """Convert an LF-delimited daily-transaction seed into fixed-width records.

    Parameters
    ----------
    src : pathlib.Path
        LF-delimited seed (``dailytran.txt``).
    dst : pathlib.Path
        Destination bound to ``DALYTRAN`` (sequential, newline-free).
    reclen : int, optional
        Record length in bytes (default 350).

    Returns
    -------
    int
        Number of records written.

    Notes
    -----
    WHY: GnuCOBOL reads ``DALYTRAN`` as ORGANIZATION SEQUENTIAL and treats embedded
    LF as data; records must be exact fixed width with no separators.
    """
    lines = Path(src).read_bytes().split(b"\n")
    payload = b"".join((ln + b" " * reclen)[:reclen] for ln in lines if ln.strip())
    Path(dst).write_bytes(payload)
    return len(payload) // reclen


def _run_interest_cycle(cobol_runner, repo_root: Path):
    """Provision inputs, post transactions, then accrue interest deterministically.

    Parameters
    ----------
    cobol_runner : tests.helpers.cobol_runner.CobolRunner
        Runner bound to a fresh, isolated workspace.
    repo_root : pathlib.Path
        Repository root.

    Returns
    -------
    tuple
        ``(post_result, interest_result, raw_transactions)`` -- the two ``RunResult``
        objects and the raw ``TRANSACT`` bytes emitted by the interest program.

    Notes
    -----
    WHY run posting first: the seed category balances are zero; posting populates
    ``TCATBALF`` so that the subsequent interest computation has non-zero principal.
    The same alternate-key XREF and indexed masters serve both programs.
    """
    seeds = _seed_dir(repo_root)
    cobol_runner.load_input("ACCTFILE", seeds / "acctdata.txt", *_ACCT)
    cobol_runner.load_input("TCATBALF", seeds / "tcatbal.txt", *_TCAT)
    cobol_runner.load_input("DISCGRP", seeds / "discgrp.txt", *_DISC)
    _provision_alt_key_xref(cobol_runner, seeds / "cardxref.txt")
    _flatten_daily(seeds / "dailytran.txt", cobol_runner.assign_path("DALYTRAN"))

    post = cobol_runner.run("CBTRN02C")
    # Interest is compiled as a shared module (CBACT04C.so) and invoked through the
    # CBACT04D driver executable that scripts/build_test_programs.sh builds from
    # tests/cobol-unit/CBACT04C_driver.cbl. WHY (Alternatives Considered): CBACT04C
    # declares PROCEDURE DIVISION USING EXTERNAL-PARMS and therefore cannot be linked
    # as a standalone -x main, so a driver that constructs the linkage and CALLs it is
    # the faithful adapter. The driver reads the cycle date from env var
    # CARDDEMO_PARM_DATE and CALLs CBACT04C, which the runtime resolves through
    # COB_LIBRARY_PATH (CobolRunner.build_env points it at build_dir).
    interest = cobol_runner.run(
        "CBACT04D", env_overrides={"CARDDEMO_PARM_DATE": PARM_DATE}
    )

    tr_path = interest.output_path("TRANSACT")
    raw = tr_path.read_bytes() if tr_path.exists() else b""
    return post, interest, raw


def _iter_records(raw: bytes):
    """Yield fixed-width interest TRAN records from the raw ``TRANSACT`` bytes.

    Parameters
    ----------
    raw : bytes
        Raw ``TRANSACT`` output (a multiple of 350 bytes).

    Yields
    ------
    bytes
        One 350-byte record at a time.
    """
    for off in range(0, len(raw) - _TR_RECLEN + 1, _TR_RECLEN):
        yield raw[off:off + _TR_RECLEN]


def _interest_projection(raw: bytes) -> str:
    """Project interest transactions to a PARM-independent, timestamp-free form.

    Parameters
    ----------
    raw : bytes
        Raw ``TRANSACT`` bytes.

    Returns
    -------
    str
        ``type|category|amount|description`` lines, one per record.

    Notes
    -----
    Trade-off: the date-prefixed TRAN-ID and record timestamps are excluded so the
    golden is stable across cycle dates, while the financially meaningful fields
    (classification, monetary amount, target account) are retained. Amounts are
    decoded with Decimal (never float) to preserve exact fixed-point money values.
    """
    lines = []
    for rec in _iter_records(raw):
        t = rec[_TR_TYPE].decode("latin-1")
        c = rec[_TR_CAT].decode("latin-1")
        amt = decode_zoned(
            rec[_TR_AMT].decode("latin-1"),
            _TR_AMT_INT_DIGITS,
            _TR_AMT_DEC_DIGITS,
            _TR_AMT_SIGNED,
        )
        # WHY (Refactoring Rationale): CBACT04C pads TRAN-DESC to X(100) with COBOL
        # LOW-VALUES (0x00), and Python's str.strip() removes whitespace but NOT 0x00.
        # Dropping the NUL fill keeps the golden a clean, byte-diffable *text* artifact
        # (the AAP's audit-grade requirement) instead of a NUL-laden binary blob; the raw
        # fixed-width record is still asserted structurally by test_interest_cycle_invariants.
        desc = rec[_TR_DESC].decode("latin-1").replace("\x00", "").strip()
        lines.append(f"{t}|{c}|{amt}|{desc}")
    return "\n".join(lines)


def test_interest_cycle_invariants(cobol_runner, repo_root):
    """Assert interest-accrual invariants and the DEFAULT-group fallback branch.

    Parameters
    ----------
    cobol_runner : tests.helpers.cobol_runner.CobolRunner
        Runner fixture (fresh workspace).
    repo_root : pathlib.Path
        Repository root fixture.

    Returns
    -------
    None

    Notes
    -----
    Assertion density: checks the posting precondition, the interest return code,
    the count and fixed-width alignment of emitted transactions, each record's
    classification (type/category), TRAN-ID shape, description prefix, the DEFAULT
    fallback marker, and that total accrued interest is strictly positive.
    """
    post, interest, raw = _run_interest_cycle(cobol_runner, repo_root)

    # Precondition: posting must not hard-fail (0 clean / 4 soft rejects allowed).
    assert post.returncode in (0, 4), (
        f"posting precondition RC={post.returncode}\nSTDERR:\n{post.stderr}"
    )
    # Interest is a clean batch step; any non-zero RC indicates an abend/error.
    assert interest.returncode == 0, (
        f"interest RC={interest.returncode}\nSTDERR:\n{interest.stderr}"
    )

    # The interest run must emit whole 350-byte transaction records, at least one.
    assert len(raw) % _TR_RECLEN == 0, "TRANSACT not a whole number of records"
    n_int = len(raw) // _TR_RECLEN
    assert n_int > 0, "interest produced no transactions"

    total = Decimal("0")
    for rec in _iter_records(raw):
        assert rec[_TR_TYPE].decode("latin-1") == INT_TYPE
        assert rec[_TR_CAT].decode("latin-1") == INT_CAT
        tran_id = rec[_TR_ID].decode("latin-1")
        assert len(tran_id) == 16 and tran_id.isdigit(), f"bad TRAN-ID {tran_id!r}"
        # TRAN-ID embeds the injected cycle date as its prefix (determinism proof).
        assert tran_id.startswith(PARM_DATE)
        # Strip the COBOL LOW-VALUES (0x00) TRAN-DESC padding (see _interest_projection)
        # so the prefix check and the diagnostic operate on clean text, consistent with
        # the golden projection.
        desc = rec[_TR_DESC].decode("latin-1").replace("\x00", "").strip()
        assert desc.startswith("Int. for a/c"), f"unexpected desc {desc!r}"
        total += decode_zoned(
            rec[_TR_AMT].decode("latin-1"),
            _TR_AMT_INT_DIGITS,
            _TR_AMT_DEC_DIGITS,
            _TR_AMT_SIGNED,
        )

    # Business-rule branch: the DEFAULT disclosure-group fallback must be exercised
    # (seed accounts carry a blank GROUP-ID -> VSAM status 23 -> DEFAULT re-read).
    assert DEFAULT_FALLBACK_MARKER in interest.stdout, "DEFAULT fallback not exercised"

    # Total accrued interest must be strictly positive (non-zero balances accrued).
    assert total > 0, f"expected positive total interest, got {total}"


def test_interest_transactions_match_golden(cobol_runner, repo_root):
    """Compare the interest-transaction projection against the golden master.

    Parameters
    ----------
    cobol_runner : tests.helpers.cobol_runner.CobolRunner
        Runner fixture (fresh workspace).
    repo_root : pathlib.Path
        Repository root fixture.

    Returns
    -------
    None

    Notes
    -----
    WHY separate: regression protection on the exact accrued amounts/descriptions is
    kept distinct from the structural invariants so a golden drift is unambiguous.
    The comparator compares against the committed baseline; regenerate locally with
    ``CARDDEMO_UPDATE_GOLDENS=1`` and ``update=True`` (never in CI) to re-bless it.
    """
    _post, _interest, raw = _run_interest_cycle(cobol_runner, repo_root)
    if not raw:
        pytest.skip("interest produced no transactions; nothing to golden-compare")

    projection = _interest_projection(raw)
    golden = repo_root / "tests" / "golden" / "interest" / "e2e_interest_cycle_transactions.expected"
    assert_matches_golden(projection, golden)
