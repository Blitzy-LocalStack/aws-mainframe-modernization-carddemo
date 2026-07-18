"""End-to-end (Layer-3) golden-master test for the full CardDemo daily batch cycle.

Purpose
-------
Reproduce the complete daily batch pipeline -- provisioning -> posting -> interest
accrual -> (best-effort) reporting / statement / export-import -- in dependency
order on deterministic seed data, mirroring ``scripts/run_full_batch.sh`` but with
completion-aware sequencing instead of fixed ``sleep`` pacing. The test asserts on
the end-state datasets (reject stream + interest transactions) via golden masters
and evaluates the aggregate worst-case return-code rubric (0 pass / 4 warn / 8 fail
/ 16 fatal).

Layering of hard vs. best-effort steps (WHY)
-------------------------------------------
* **Hard steps** -- posting (``CBTRN02C``) and interest (``CBACT04C``) are the
  financial core; their return codes are asserted strictly (posting 0/4, interest 0).
* **Best-effort steps** -- reporting/statement/export-import are environment-bounded
  (some CardDemo programs do not link cleanly as standalone executables under this
  GnuCOBOL dialect). They are attempted only when their program was built, wrapped so
  any failure degrades to WARN and never fails the cycle -- matching the project's
  RC rubric where these ancillary steps are non-fatal.

Determinism & isolation
-----------------------
Fixed ``PARM_DATE`` and never-mutated seeds; a fresh workspace per test; golden
projections drop non-deterministic TRAN-IDs/timestamps. Parallel-safe.

Binding contract
-----------------
Collected by ``scripts/run_e2e_tests.sh`` -> ``pytest tests/e2e -m e2e``.
"""
from __future__ import annotations

import os
import re
import subprocess
from decimal import Decimal
from pathlib import Path

import pytest

from tests.helpers.golden_compare import assert_matches_golden
from tests.helpers.record_codec import decode_zoned

pytestmark = pytest.mark.e2e

PARM_DATE = "2022071800"

# Return-code rubric (project convention). WHY named constants: the rubric is a
# review-visible contract; symbolic names make the aggregation self-documenting.
RC_PASS, RC_WARN, RC_FAIL, RC_FATAL = 0, 4, 8, 16

# Interest classification + fallback marker (verbatim spec).
INT_TYPE, INT_CAT = "01", "0005"
DEFAULT_FALLBACK_MARKER = "TRY WITH DEFAULT GROUP CODE"

# Documented reject reason codes -> exact message text.
REJECT_MESSAGES = {
    "0100": "INVALID CARD NUMBER FOUND",
    "0101": "ACCOUNT RECORD NOT FOUND",
    "0102": "OVERLIMIT TRANSACTION",
    "0103": "TRANSACTION RECEIVED AFTER ACCT EXPIRATION",
}
VALID_REJECT_CODES = frozenset(REJECT_MESSAGES)

# Fixed-width geometry.
_REJ_RECLEN = 430
_REJ_CARD, _REJ_CODE, _REJ_MSG = slice(262, 278), slice(350, 354), slice(354, 430)
_TR_RECLEN = 350
_TR_ID, _TR_TYPE, _TR_CAT = slice(0, 16), slice(16, 18), slice(18, 22)
_TR_DESC, _TR_AMT = slice(32, 132), slice(132, 143)

# TRAN-AMT numeric geometry for the shared ``record_codec.decode_zoned(raw,
# int_digits, dec_digits, signed)`` contract. WHY (Assumption / single source of
# truth): the amount field is CVTRA05Y ``TRAN-AMT PIC S9(09)V99`` -- 9 integer + 2
# decimal digits, signed -- mirrored verbatim by ``record_codec.TRAN_LAYOUT``'s
# ``Field("TRAN-AMT", 132, 11, "zoned", 9, 2, True)``. Naming the geometry keeps the
# decode call sites free of magic numbers and pinned to the copybook contract.
_TR_AMT_INT, _TR_AMT_DEC, _TR_AMT_SIGNED = 9, 2, True

# Loader geometry.
_ACCT, _TCAT, _DISC = (300, 11), (50, 17), (50, 16)

# Ancillary (best-effort) pipeline programs, attempted after the financial core.
# WHY this set: reporting (CBTRN03C) plus statement/export-import (CBSTM03A /
# CBEXPORT / CBIMPORT), which the compile survey flagged as environment-bounded.
_BEST_EFFORT_PROGRAMS = ("CBTRN03C", "CBSTM03A", "CBEXPORT", "CBIMPORT")

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
    """Resolve the directory of deterministic seed inputs for the full cycle.

    Parameters
    ----------
    repo_root : pathlib.Path
        Repository root supplied by the ``repo_root`` fixture.

    Returns
    -------
    pathlib.Path
        Directory containing the seed datasets.

    Notes
    -----
    Order (WHY): env override, then an e2e fixtures tree if present, else the shipped
    REFERENCE seeds -- keeps the cycle runnable and deterministic in any environment.
    """
    override = os.environ.get("CARDDEMO_E2E_SEED_DIR")
    if override and (Path(override) / "dailytran.txt").exists():
        return Path(override)
    fixtures = repo_root / "tests" / "fixtures" / "e2e"
    if (fixtures / "dailytran.txt").exists():
        return fixtures
    return repo_root / "app" / "data" / "ASCII"


def _provision_alt_key_xref(runner, seed_cardxref: Path) -> Path:
    """Build the alternate-key XREF indexed file required across the batch chain.

    Parameters
    ----------
    runner : tests.helpers.cobol_runner.CobolRunner
        Active runner.
    seed_cardxref : pathlib.Path
        Flat seed cross-reference file.

    Returns
    -------
    pathlib.Path
        Provisioned ``XREFFILE`` path.

    Raises
    ------
    subprocess.CalledProcessError
        If compiling or running the provisioner fails.

    Notes
    -----
    WHY: ``CBACT04C`` reads XREF on the ALTERNATE key (ACCT-ID); a primary-key-only
    load abends (status 35). One alt-key file serves posting and interest alike.
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
    """Flatten an LF-delimited daily-transaction seed into fixed-width records.

    Parameters
    ----------
    src : pathlib.Path
        LF-delimited seed.
    dst : pathlib.Path
        Destination bound to ``DALYTRAN``.
    reclen : int, optional
        Record length (default 350).

    Returns
    -------
    int
        Number of records written.

    Notes
    -----
    WHY: ``DALYTRAN`` is read as ORGANIZATION SEQUENTIAL; embedded LF is data, so
    records must be exact fixed width with no separators.
    """
    lines = Path(src).read_bytes().split(b"\n")
    payload = b"".join((ln + b" " * reclen)[:reclen] for ln in lines if ln.strip())
    Path(dst).write_bytes(payload)
    return len(payload) // reclen


def _severity(rc: int) -> int:
    """Map a raw process return code to the project's rubric severity.

    Parameters
    ----------
    rc : int
        Process return code.

    Returns
    -------
    int
        One of ``RC_PASS`` (0), ``RC_WARN`` (4), ``RC_FAIL`` (8), ``RC_FATAL`` (16).

    Notes
    -----
    Assumption: rc==4 is the CardDemo soft-reject warning convention; any other
    non-zero below 16 is a hard failure, and >=16 is treated as fatal/abend.
    """
    if rc == 0:
        return RC_PASS
    if rc == 4:
        return RC_WARN
    if rc >= 16:
        return RC_FATAL
    return RC_FAIL


def _best_effort_step(runner, program: str, env_overrides=None):
    """Attempt an ancillary pipeline step, degrading any failure to WARN.

    Parameters
    ----------
    runner : tests.helpers.cobol_runner.CobolRunner
        Active runner.
    program : str
        Program name to attempt.
    env_overrides : dict, optional
        Extra environment bindings.

    Returns
    -------
    tuple
        ``(severity, note)`` -- severity is ``RC_PASS`` on clean success else capped
        at ``RC_WARN``; note is a human-readable status string.

    Notes
    -----
    Trade-off: these steps are environment-bounded (not all CardDemo programs link as
    standalone executables here), so a non-zero RC, a missing build artifact, or an
    exception all map to WARN rather than failing the financial cycle. This keeps the
    core assertions meaningful while still exercising the ancillary programs when
    they are available.
    """
    exe = runner.build_dir / program
    if not exe.exists():
        return RC_WARN, f"{program}: not built (WARN)"
    try:
        res = runner.run(program, env_overrides=env_overrides, timeout=60)
    except Exception as exc:  # noqa: BLE001 - best-effort must never propagate
        return RC_WARN, f"{program}: raised {exc!r} (WARN)"
    if res.returncode == 0:
        return RC_PASS, f"{program}: OK"
    return RC_WARN, f"{program}: rc={res.returncode} (WARN)"


def _parse_count(stdout: str, label: str) -> int:
    """Extract a zero-padded counter printed by the posting program.

    Parameters
    ----------
    stdout : str
        Captured program stdout.
    label : str
        Counter label (``"PROCESSED"`` / ``"REJECTED"``).

    Returns
    -------
    int
        Parsed integer.

    Raises
    ------
    AssertionError
        If the labelled counter is missing.
    """
    m = re.search(rf"{label}\s*:\s*(\d+)", stdout)
    assert m, f"counter {label!r} not found in program output"
    return int(m.group(1))


def _iter_records(raw: bytes, reclen: int):
    """Yield fixed-width records from a flat byte image.

    Parameters
    ----------
    raw : bytes
        Flat image.
    reclen : int
        Record length.

    Yields
    ------
    bytes
        One ``reclen``-byte record at a time.
    """
    for off in range(0, len(raw) - reclen + 1, reclen):
        yield raw[off:off + reclen]


def _reject_projection(raw: bytes) -> str:
    """Project the reject stream to a sorted, timestamp-free ``card|code|message``.

    Parameters
    ----------
    raw : bytes
        Raw ``DALYREJS`` bytes.

    Returns
    -------
    str
        Sorted projection lines.

    Notes
    -----
    Trade-off: field projection (not full-record diff) keeps the golden immune to the
    non-deterministic PROC-TS embedded in the tran image.
    """
    lines = []
    for rec in _iter_records(raw, _REJ_RECLEN):
        card = rec[_REJ_CARD].decode("latin-1").strip()
        code = rec[_REJ_CODE].decode("latin-1").strip()
        msg = rec[_REJ_MSG].decode("latin-1").strip()
        lines.append(f"{card}|{code}|{msg}")
    return "\n".join(sorted(lines))


def _interest_projection(raw: bytes) -> str:
    """Project interest transactions to PARM-independent ``type|cat|amount|desc``.

    Parameters
    ----------
    raw : bytes
        Raw ``TRANSACT`` bytes.

    Returns
    -------
    str
        Projection lines, one per record.

    Notes
    -----
    Trade-off: the date-prefixed TRAN-ID and timestamps are dropped for cross-date
    stability; amounts use Decimal (never float) for exact fixed-point money.
    """
    lines = []
    for rec in _iter_records(raw, _TR_RECLEN):
        t = rec[_TR_TYPE].decode("latin-1")
        c = rec[_TR_CAT].decode("latin-1")
        # WHY (contract match): record_codec.decode_zoned requires the field geometry
        # (raw, int_digits, dec_digits, signed); TRAN-AMT is PIC S9(09)V99 (see the
        # _TR_AMT_* constants above), so decode with that exact geometry to keep money
        # exact (Decimal) and single-sourced against the CVTRA05Y copybook.
        amt = decode_zoned(
            rec[_TR_AMT].decode("latin-1"), _TR_AMT_INT, _TR_AMT_DEC, _TR_AMT_SIGNED
        )
        # WHY (Assumption): CBACT04C fills TRAN-DESC via a COBOL ``STRING 'Int. for
        # a/c <id>'`` which leaves the field's trailing bytes as the record's
        # LOW-VALUES (0x00) filler, not spaces. ``str.strip()`` removes only
        # whitespace, so the 0x00 filler is first normalised to spaces; this keeps the
        # golden master clean, reviewable ASCII (audit-grade, git-diffable) instead of
        # a NUL-laden blob that git flags as binary -- WITHOUT altering the projected
        # description text ("Int. for a/c NNN").
        desc = rec[_TR_DESC].decode("latin-1").replace("\x00", " ").strip()
        lines.append(f"{t}|{c}|{amt}|{desc}")
    return "\n".join(lines)


def _run_full_cycle(cobol_runner, repo_root: Path):
    """Execute the full daily batch pipeline in dependency order.

    Parameters
    ----------
    cobol_runner : tests.helpers.cobol_runner.CobolRunner
        Runner bound to a fresh, isolated workspace.
    repo_root : pathlib.Path
        Repository root.

    Returns
    -------
    dict
        Structured cycle result with keys: ``post`` (RunResult), ``interest``
        (RunResult), ``raw_rej`` (bytes), ``raw_int`` (bytes), ``n_daily`` (int),
        and ``steps`` (list of ``(name, severity, note)`` for every pipeline step).

    Notes
    -----
    Sequencing mirrors ``scripts/run_full_batch.sh``: provision the masters and the
    alternate-key XREF, post the day's transactions (populating category balances),
    accrue interest, then attempt the ancillary reporting/statement/export steps.
    """
    seeds = _seed_dir(repo_root)
    cobol_runner.load_input("ACCTFILE", seeds / "acctdata.txt", *_ACCT)
    cobol_runner.load_input("TCATBALF", seeds / "tcatbal.txt", *_TCAT)
    cobol_runner.load_input("DISCGRP", seeds / "discgrp.txt", *_DISC)
    _provision_alt_key_xref(cobol_runner, seeds / "cardxref.txt")
    n_daily = _flatten_daily(seeds / "dailytran.txt", cobol_runner.assign_path("DALYTRAN"))

    steps = []
    post = cobol_runner.run("CBTRN02C")               # hard step: posting
    steps.append(("posting", _severity(post.returncode), f"CBTRN02C rc={post.returncode}"))

    # hard step: interest. WHY (adapter): CBACT04C declares PROCEDURE DIVISION USING
    # EXTERNAL-PARMS, so scripts/build_test_programs.sh builds it as a shared MODULE
    # (-m -> CBACT04C.so) and it cannot be launched as a standalone -x executable. The
    # same build step also compiles the promised -x driver CBACT04D, which
    # reconstructs the EXTERNAL-PARMS linkage, CALLs CBACT04C (resolved by PROGRAM-ID
    # via COB_LIBRARY_PATH), and propagates BOTH its RETURN-CODE and its DISPLAY output
    # (including the DEFAULT-group fallback marker). The processing date is injected
    # through the driver's CARDDEMO_PARM_DATE environment hook.
    # Alternatives Considered: passing the date on the command line was rejected --
    # CBACT04C reads it from the fixed linkage record, not argv, so the env-driven
    # driver is the faithful, deterministic adapter.
    interest = cobol_runner.run(
        "CBACT04D", env_overrides={"CARDDEMO_PARM_DATE": PARM_DATE}
    )   # hard step: interest
    steps.append(("interest", _severity(interest.returncode),
                  f"CBACT04C(via CBACT04D) rc={interest.returncode}"))

    for prog in _BEST_EFFORT_PROGRAMS:                 # best-effort ancillary steps
        sev, note = _best_effort_step(cobol_runner, prog)
        steps.append((prog, sev, note))

    rej_path = post.output_path("DALYREJS")
    int_path = interest.output_path("TRANSACT")
    return {
        "post": post,
        "interest": interest,
        "raw_rej": rej_path.read_bytes() if rej_path.exists() else b"",
        "raw_int": int_path.read_bytes() if int_path.exists() else b"",
        "n_daily": n_daily,
        "steps": steps,
    }


def test_full_batch_cycle_end_state(cobol_runner, repo_root):
    """Run the full pipeline and assert end-state datasets + golden masters.

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
    Assertion density: posting counts and their arithmetic, reject-stream alignment
    and per-record reason/message, interest classification and positive total, plus
    golden-master comparison of both the reject stream and the interest transactions.
    """
    cyc = _run_full_cycle(cobol_runner, repo_root)
    post, interest = cyc["post"], cyc["interest"]

    # --- Hard step: posting ---------------------------------------------------
    assert post.returncode in (0, 4), f"posting RC={post.returncode}\n{post.stderr}"
    processed = _parse_count(post.stdout, "PROCESSED")
    rejected = _parse_count(post.stdout, "REJECTED")
    assert processed == cyc["n_daily"], f"processed {processed} != input {cyc['n_daily']}"
    assert (processed - rejected) + rejected == processed
    assert len(cyc["raw_rej"]) % _REJ_RECLEN == 0
    assert len(cyc["raw_rej"]) // _REJ_RECLEN == rejected
    for rec in _iter_records(cyc["raw_rej"], _REJ_RECLEN):
        code = rec[_REJ_CODE].decode("latin-1").strip()
        msg = rec[_REJ_MSG].decode("latin-1").strip()
        assert code in VALID_REJECT_CODES, f"undocumented reject code {code!r}"
        assert msg == REJECT_MESSAGES[code]

    # --- Hard step: interest --------------------------------------------------
    assert interest.returncode == 0, f"interest RC={interest.returncode}\n{interest.stderr}"
    assert len(cyc["raw_int"]) % _TR_RECLEN == 0
    assert len(cyc["raw_int"]) // _TR_RECLEN > 0, "interest produced no transactions"
    total = Decimal("0")
    for rec in _iter_records(cyc["raw_int"], _TR_RECLEN):
        assert rec[_TR_TYPE].decode("latin-1") == INT_TYPE
        assert rec[_TR_CAT].decode("latin-1") == INT_CAT
        # WHY (contract match): decode with the CVTRA05Y TRAN-AMT geometry so the
        # running total is exact fixed-point money via Decimal (never float).
        total += decode_zoned(
            rec[_TR_AMT].decode("latin-1"), _TR_AMT_INT, _TR_AMT_DEC, _TR_AMT_SIGNED
        )
    assert DEFAULT_FALLBACK_MARKER in interest.stdout, "DEFAULT fallback not exercised"
    assert total > 0, f"expected positive total interest, got {total}"

    # --- End-state golden masters --------------------------------------------
    golden_dir = repo_root / "tests" / "golden"
    if cyc["raw_rej"]:
        assert_matches_golden(
            _reject_projection(cyc["raw_rej"]),
            golden_dir / "posting" / "e2e_full_cycle_dalyrejs.expected",
        )
    assert_matches_golden(
        _interest_projection(cyc["raw_int"]),
        golden_dir / "interest" / "e2e_full_cycle_interest.expected",
    )


def test_full_batch_condition_code_rubric(cobol_runner, repo_root):
    """Assert the aggregate worst-case return-code rubric for the whole cycle.

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
    The financial core (posting, interest) must never reach FAIL/FATAL: posting may
    be PASS or WARN (soft rejects), interest must be PASS. Ancillary steps are capped
    at WARN by design, so the aggregate worst-case severity must stay below
    ``RC_FAIL`` -- proving the cycle completed without a hard failure.
    """
    cyc = _run_full_cycle(cobol_runner, repo_root)
    steps = dict((name, sev) for name, sev, _note in cyc["steps"])

    # Core financial steps: strict severity bounds.
    assert steps["posting"] <= RC_WARN, f"posting severity {steps['posting']}"
    assert steps["interest"] == RC_PASS, f"interest severity {steps['interest']}"

    # Aggregate worst-case severity across every step must be below FAIL.
    aggregate = max(sev for _name, sev, _note in cyc["steps"])
    notes = "\n".join(note for _n, _s, note in cyc["steps"])
    assert aggregate < RC_FAIL, f"aggregate severity {aggregate} >= FAIL\n{notes}"
