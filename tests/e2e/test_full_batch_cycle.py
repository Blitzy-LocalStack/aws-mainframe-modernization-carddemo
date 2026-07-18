"""End-to-end (Layer-3) golden-master test for the full CardDemo daily batch cycle.

Purpose
-------
Reproduce the complete daily batch pipeline -- provisioning -> posting -> interest
accrual -> reporting -> statement -- in dependency order on deterministic seed data,
mirroring ``scripts/run_full_batch.sh`` but with completion-aware sequencing instead
of fixed ``sleep`` pacing. Every stage that runs is asserted against a golden master
or an independently-recomputed invariant, and the aggregate return-code rubric is
scored HONESTLY (0 pass / 4 warn / 8 fail / 16 fatal) with no masking.

Honest severity scoring (WHY -- resolves QA findings F-P4-1 / F-P5-2)
--------------------------------------------------------------------
An earlier revision wrapped the reporting/statement/export-import stages in a
"best-effort" helper that degraded ANY outcome -- a non-zero return code, a missing
build artifact, or an exception -- to WARN, so a crashing or never-built stage still
looked like a passing cycle. That masking is removed. Now:

* **Financial core** -- posting (``CBTRN02C``) and interest (``CBACT04C`` via its
  ``CBACT04D`` driver) are scored strictly: posting may be PASS or WARN (soft
  rejects), interest must be PASS.
* **Reporting** (``CBTRN03C``) and **statement** (``CBSTM03A``+``CBSTM03B``) are
  genuinely RUN and scored with the SAME honest :func:`_severity` map as the core --
  a crash or non-zero RC propagates to the aggregate and FAILS the cycle (proven by
  :func:`test_full_batch_fault_injection_fails_honestly`). Reporting is provisioned
  with real inputs and validated against a committed golden; the statement stage runs
  the platform-compat binary (see :mod:`tests.helpers.statement_compat`) and is
  validated against the committed statement goldens.
* **Export/import** (``CBEXPORT`` / ``CBIMPORT``) do NOT compile against the frozen
  baseline (their FD declares ``RECORD KEY IS EXPORT-SEQUENCE-NUM`` but that field is
  not in the FD record -- a production-source defect that is REFERENCE-only per AAP
  0.8.2). They are NOT silently degraded to WARN: they are excluded from the aggregate
  and documented by :func:`test_full_batch_export_import_not_ready` (xfail-strict with
  the exact compiler evidence), so the gap is visible and would flip to a hard failure
  the moment the baseline is fixed.

Raw-record oracles (WHY -- resolves QA findings F-P4-4 / F-P5-1)
---------------------------------------------------------------
End-state assertions compare the FULL fixed-width records of the reject stream
(``REJECT`` layout) and the interest transactions (``INTTRAN`` layout) against golden
masters -- with only the non-deterministic processing timestamps masked -- rather
than a lossy ``card|code|message`` / ``type|cat|amount|desc`` projection. The posting
set is reconciled by reading TRANFILE and DALYREJS back independently and proving
``posted ids UNION reject ids == the day's inputs`` (disjoint, complete), replacing
the former ``(processed-rejected)+rejected==processed`` tautology.

Determinism, isolation & run-once (WHY -- resolves QA finding F-P4-6)
--------------------------------------------------------------------
Fixed ``PARM_DATE`` and never-mutated seeds; the whole cycle executes exactly ONCE in
a module-scoped fixture (:func:`full_cycle`) whose immutable result is shared by every
end-state/reporting/statement/rubric test, so the expensive pipeline is not re-run per
assertion. The fault-injection test runs its own deliberately-broken cycle in an
isolated workspace.

Binding contract
----------------
Collected by ``scripts/run_e2e_tests.sh`` -> ``pytest tests/e2e -m e2e``.
"""
from __future__ import annotations

import os
import re
import subprocess
from collections import namedtuple
from decimal import Decimal
from pathlib import Path

import pytest

from tests.helpers.cobol_runner import CobolRunner
from tests.helpers.golden_compare import assert_matches_golden
from tests.helpers.e2e_records import (
    frame_records as _frame_records,
    money_field as _money_field,
    text_field as _text_field,
)
from tests.helpers import statement_compat as _stmt

pytestmark = pytest.mark.e2e

PARM_DATE = "2022071800"

# Golden root resolved once at import (the repository layout is fixed). WHY a module
# constant (Refactoring Rationale): several tests need it, and resolving it from
# ``__file__`` keeps the tests independent of the process CWD.
_GOLDEN_ROOT = Path(__file__).resolve().parents[1] / "golden"

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

# Registered record-codec layout names used as raw-record oracles (single-sourced
# geometry + per-layout timestamp masking live in tests/helpers/record_codec.py).
# WHY these three (Assumption): REJECT is the 430-byte daily-reject record (350-byte
# tran image + reason/message trailer, PROC-TS masked); TRAN is the 350-byte posted
# transaction (PROC-TS masked, deterministic ORIG-TS retained); INTTRAN is the 350-byte
# interest transaction whose ORIG-TS *and* PROC-TS are BOTH runtime-stamped by CBACT04C
# and so both masked. ACCOUNT/TCATBAL are read back to prove end-state effects.
_REJECT, _TRAN, _INTTRAN = "REJECT", "TRAN", "INTTRAN"
_ACCOUNT, _TCATBAL = "ACCOUNT", "TCATBAL"

# Record lengths for framing sequential byte images (from the copybooks/FDs).
_REJ_RECLEN = 430
_TR_RECLEN = 350
_TRAN_ID_LEN = 16  # TRAN-ID / DALYTRAN-ID key width (byte 0..16) on every layout.

# Deterministic financial expectations at the shipped seed scale. WHY hard-coded
# (Assumption, EMPIRICALLY VERIFIED + de-risked): the seeds never change, so these are
# exact contractual constants, asserted with Decimal (never float) for fixed-point money.
_EXPECTED_DAILY = 300           # daily-transaction inputs
_EXPECTED_POSTED = 262          # posted to TRANFILE
_EXPECTED_REJECTED = 38         # written to DALYREJS (all 0102 OVERLIMIT at seed scale)
_EXPECTED_CONSERVATION = Decimal("77954.70")   # sum of posted TRAN-AMT == sum of posting-induced balance deltas
_EXPECTED_INT_TXNS = 50         # interest transactions emitted
_EXPECTED_INT_TOTAL = Decimal("1279.16")       # sum of interest TRAN-AMT
_EXPECTED_TCAT_FINAL = 100      # category-balance rows after posting (50 created + 50 updated)
_EXPECTED_ACCOUNTS = 50         # master account rows

# Reporting stage (CBTRN03C) expectations.
_REPORT_RECLEN = 133            # FD-REPTFILE-REC PIC X(133)
_EXPECTED_REPORT_RECORDS = 857  # framed report lines at seed scale (de-risked, deterministic)
_EXPECTED_GRAND_TOTAL = "78,557.92"   # report Grand Total (posted debits net of credits)

# Loader geometry for the core master inputs: (reclen, key_length).
_ACCT, _TCAT, _DISC = (300, 11), (50, 17), (50, 16)

# Reporting ancillary-input geometry: (reclen, key_length). WHY these exact sizes
# (Assumption, from the CBTRN03C FDs): CARDXREF is a 50-byte INDEXED file keyed on the
# 16-byte card number; TRANTYPE is 60-byte keyed on the 2-byte type; TRANCATG is 60-byte
# keyed on the 6-byte (type+category) composite. The shipped seeds are 36 bytes (xref,
# pad to 50) and CRLF-terminated 60-byte rows (type/category, strip CR then pad).
_CARDXREF_RECLEN, _CARDXREF_KEY = 50, 16
_TRANTYPE_RECLEN, _TRANTYPE_KEY = 60, 2
_TRANCATG_RECLEN, _TRANCATG_KEY = 60, 6

# Export/import programs that cannot compile against the frozen baseline (documented,
# out-of-scope production defect). WHY listed (transparency): naming them here and in
# test_full_batch_export_import_not_ready makes the excluded-from-aggregate carve-out
# explicit rather than hidden behind a WARN cap.
_UNSUPPORTED_PROGRAMS = ("CBEXPORT", "CBIMPORT")
# The exact, verified compiler diagnostic that proves the non-compile is genuine.
_UNSUPPORTED_SIGNATURE = "EXPORT-SEQUENCE-NUM"

# ---------------------------------------------------------------------------
# Test-only alternate-key XREF provisioner (COBOL). Built once, run per workspace.
# ---------------------------------------------------------------------------
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
        # WHY (F-P4 bounded wait): cap the compile so a wedged ``cobc`` cannot block
        # a CI job indefinitely. 120s matches ``cobol_runner.run_program``'s default
        # and the ``-fsyntax-only`` compile later in this module, keeping every
        # subprocess wait here deadline-bounded. Trade-off: an outer CI-job timeout
        # is the only alternative net and is far coarser than this per-call bound.
        subprocess.run(
            ["cobc", "-x", "-free", "-o", str(exe), str(src)],
            check=True, capture_output=True, timeout=120,
        )
    out = runner.assign_path("XREFFILE")
    env = {**os.environ, "INFLAT": str(seed_cardxref), "OUTIDX": str(out)}
    # WHY (F-P4 bounded wait): mirror the compile bound above so a hung provisioner
    # binary cannot stall the suite; 120s is ample for this tiny deterministic
    # loader yet still finite.
    subprocess.run([str(exe)], env=env, check=True, capture_output=True, timeout=120)
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
    """Map a raw process return code to the project's rubric severity (HONEST).

    Parameters
    ----------
    rc : int
        Process return code (may be negative for a signal, e.g. -11 for SIGSEGV).

    Returns
    -------
    int
        One of ``RC_PASS`` (0), ``RC_WARN`` (4), ``RC_FAIL`` (8), ``RC_FATAL`` (16).

    Notes
    -----
    WHY this exact mapping (resolves F-P4-1 -- no masking): rc==0 is a clean pass;
    rc==4 is the CardDemo soft-reject warning convention; rc>=16 (or a negative
    signal code, which is a hard crash) is fatal/abend; any other non-zero is a hard
    failure. Crucially, a non-zero RC is NEVER capped at WARN -- it propagates to the
    aggregate so a crashing stage fails the cycle.

    Assumptions / Trade-offs: a signal-killed process reports a negative ``rc`` under
    subprocess (e.g. -11); treating any negative value as FATAL is correct because a
    signal death is at least as severe as an abend.
    """
    if rc == 0:
        return RC_PASS
    if rc == 4:
        return RC_WARN
    if rc < 0 or rc >= 16:
        return RC_FATAL
    return RC_FAIL


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


def _require_or_skip(reason: str) -> "None":
    """Fail under ``CARDDEMO_REQUIRE_COBOL`` else skip, for a genuinely-absent layer.

    Parameters
    ----------
    reason : str
        Human-readable explanation of the missing capability.

    Returns
    -------
    None
        Never returns normally -- always raises ``pytest.fail`` or ``pytest.skip``.

    Raises
    ------
    Failed
        When ``CARDDEMO_REQUIRE_COBOL`` is truthy (the required layer must not hide
        behind a green-looking skip).
    Skipped
        Otherwise (a documented skip in a default/degraded environment).

    Notes
    -----
    WHY mirror conftest's gate locally (Refactoring Rationale): conftest's helper is
    module-private and not importable; replicating the tiny truthy-read here keeps the
    "absent-required-layer == FAIL, never a silent green" policy consistent for the
    statement stage without cross-importing a test module.
    """
    strict = str(os.environ.get("CARDDEMO_REQUIRE_COBOL", "")).strip().lower() in (
        "1", "true", "yes", "on",
    )
    if strict:
        pytest.fail(reason)
    pytest.skip(reason)


def _acct_bal_by_id(records) -> "dict[str, Decimal]":
    """Index account balances by account id from fixed-width ACCOUNT records.

    Parameters
    ----------
    records : list[str]
        300-byte ACCOUNT records (from a seed frame or an ``unload_output`` readback).

    Returns
    -------
    dict[str, Decimal]
        Mapping ``ACCT-ID`` (stripped) -> ``ACCT-CURR-BAL`` (Decimal, exact).

    Raises
    ------
    KeyError
        If the ACCOUNT layout lacks the referenced fields (single-sourced geometry).

    Notes
    -----
    WHY Decimal, keyed by id (Assumption): balances are fixed-point money that must be
    compared exactly (never float); keying by id lets the caller compute per-account
    deltas between snapshots regardless of record ordering.
    """
    return {
        _text_field(rec, _ACCOUNT, "ACCT-ID"): _money_field(rec, _ACCOUNT, "ACCT-CURR-BAL")
        for rec in records
    }


def _provision_reporting_inputs(runner, seeds: Path, posted_records) -> None:
    """Provision every input CBTRN03C needs, handing off the posted transactions.

    Parameters
    ----------
    runner : tests.helpers.cobol_runner.CobolRunner
        Runner whose workspace already ran posting (so TRANFILE holds the posted
        indexed transactions and the alternate-key XREF exists).
    seeds : pathlib.Path
        Seed directory supplying the reference reday data (cardxref/trantype/trancatg).
    posted_records : list[str]
        The posted 350-byte transactions unloaded from the indexed TRANFILE, to be
        re-materialised as the SEQUENTIAL input CBTRN03C reads.

    Returns
    -------
    None

    Raises
    ------
    tests.helpers.vsam_loader.VsamLoadError
        If an indexed reference input cannot be materialised.

    Notes
    -----
    WHY re-materialise TRANFILE as SEQUENTIAL (Assumption): CBTRN02C WRITES TRANFILE as
    ORGANIZATION INDEXED, but CBTRN03C READS the same ASSIGN name as ORGANIZATION
    SEQUENTIAL. The genuine batch handoff is "unload the posted indexed file to a flat
    sequential dataset, then feed it to the report", so we unlink the indexed sidecars
    and write the unloaded records back as a contiguous fixed-width image.

    WHY a WIDE DATEPARM range "0000-00-00".."9999-99-99" (Trade-off / determinism):
    CBTRN03C filters transactions by ``TRAN-PROC-TS(1:10)`` between the start/end dates.
    The posted PROC-TS is a runtime timestamp, so a narrow range would make the report's
    row set depend on the wall clock. A range that spans all dates includes every posted
    transaction unconditionally, yielding a deterministic report (the row set is exactly
    the 262 posted transactions in TRAN-ID order) whose golden is byte-stable.
    """
    # 1) TRANFILE handoff: replace the indexed image with a flat sequential one.
    tf = runner.assign_path("TRANFILE")
    for sidecar in (tf, Path(str(tf) + ".idx"), Path(str(tf) + ".dat")):
        if sidecar.exists():
            sidecar.unlink()
    tf.write_bytes(
        b"".join(rec.encode("latin-1")[:_TR_RECLEN].ljust(_TR_RECLEN) for rec in posted_records)
    )

    # 2..4) INDEXED reference inputs, each strip-CR + pad to the FD record width.
    # WHY factor into a nested loader (Refactoring Rationale): the three files differ
    # only in name/geometry/seed; one loop keeps the strip-CR + pad + load logic single
    # -sourced and auditable.
    ref = (
        ("CARDXREF", "cardxref.txt", _CARDXREF_RECLEN, _CARDXREF_KEY),
        ("TRANTYPE", "trantype.txt", _TRANTYPE_RECLEN, _TRANTYPE_KEY),
        ("TRANCATG", "trancatg.txt", _TRANCATG_RECLEN, _TRANCATG_KEY),
    )
    for assign_name, basename, reclen, key_len in ref:
        rows = [
            ln.replace("\r", "")
            for ln in (seeds / basename).read_text().splitlines()
            if ln.strip()
        ]
        flat = runner.workspace / f"_report_{assign_name}.txt"
        flat.write_bytes(
            b"".join(row.encode("latin-1")[:reclen].ljust(reclen) + b"\n" for row in rows)
        )
        runner.load_input(assign_name, str(flat), reclen=reclen, key_length=key_len)

    # 5) DATEPARM: WS-START X(10) @0 + FILLER X(1) @10 + WS-END X(10) @11, wide range.
    runner.assign_path("DATEPARM").write_bytes(
        ("0000-00-00" + " " + "9999-99-99").ljust(80).encode("latin-1")
    )


def _frame_report(report_bytes: bytes) -> str:
    """Frame the 133-byte record-sequential report into right-trimmed golden lines.

    Parameters
    ----------
    report_bytes : bytes
        Raw ``TRANREPT`` output (contiguous fixed 133-byte records, no delimiter).

    Returns
    -------
    str
        One right-stripped, newline-terminated line per report record (empty input
        yields ``""``).

    Raises
    ------
    None

    Notes
    -----
    WHY frame + rstrip (Trade-off, mirrors the statement helper): GnuCOBOL writes a
    SEQUENTIAL file as contiguous fixed records with no delimiter, so the raw stream is
    sliced by the FD width and each line right-trimmed to produce the git-diffable,
    line-oriented form the golden stores. The report embeds NO runtime timestamp (the
    date range is the deterministic wide range), so the framed text is byte-stable.
    """
    if not report_bytes:
        return ""
    text = report_bytes.decode("latin-1")
    records = [text[i:i + _REPORT_RECLEN] for i in range(0, len(text), _REPORT_RECLEN)]
    return "".join(rec.rstrip() + "\n" for rec in records)


# Immutable capture of one statement-stage execution.
StatementStage = namedtuple("StatementStage", ["severity", "note", "outputs"])

# Immutable capture of one full-cycle execution (shared across tests, run once).
FullCycle = namedtuple(
    "FullCycle",
    [
        "post", "interest", "report",
        "n_daily", "daily_ids",
        "raw_rej", "reject_records", "reject_ids",
        "raw_int", "interest_records",
        "posted_records", "posted_ids",
        "acct_init", "acct_post", "acct_final",
        "tcat_final_records",
        "report_bytes", "report_framed",
        "statement", "steps",
    ],
)


def _run_core_cycle(runner, repo_root: Path, *, omit_cardxref: bool = False) -> dict:
    """Execute provisioning -> posting -> interest -> reporting in one workspace.

    Parameters
    ----------
    runner : tests.helpers.cobol_runner.CobolRunner
        Runner bound to a fresh, isolated workspace.
    repo_root : pathlib.Path
        Repository root (locates the seeds).
    omit_cardxref : bool, optional
        Fault-injection switch. When ``True``, the reporting stage's required CARDXREF
        input is deliberately NOT provisioned, so CBTRN03C fails at OPEN -- used to
        prove the honest severity map propagates a real failure (F-P4-5). Default
        ``False``.

    Returns
    -------
    dict
        Core cycle capture (see :class:`FullCycle` fields, minus the statement stage
        and the ``steps`` rollup which the fixture assembles).

    Raises
    ------
    subprocess.CalledProcessError
        If the alternate-key XREF provisioner fails to build/run.

    Notes
    -----
    Sequencing mirrors ``scripts/run_full_batch.sh``. Snapshots are taken at three
    points -- initial (seed), post-posting, and post-interest -- so posting
    conservation and interest effects can each be asserted against the correct baseline
    (posting and interest BOTH mutate ACCTFILE, so a single final snapshot could not
    separate their contributions).
    """
    seeds = _seed_dir(repo_root)
    runner.load_input("ACCTFILE", seeds / "acctdata.txt", *_ACCT)
    runner.load_input("TCATBALF", seeds / "tcatbal.txt", *_TCAT)
    runner.load_input("DISCGRP", seeds / "discgrp.txt", *_DISC)
    _provision_alt_key_xref(runner, seeds / "cardxref.txt")
    n_daily = _flatten_daily(seeds / "dailytran.txt", runner.assign_path("DALYTRAN"))
    daily_raw = runner.assign_path("DALYTRAN").read_bytes()
    daily_ids = [rec[:_TRAN_ID_LEN].strip() for rec in _frame_records(daily_raw, _TR_RECLEN)]

    # Initial account snapshot from the seed (before any mutation).
    acct_init = _acct_bal_by_id(
        [ln for ln in (seeds / "acctdata.txt").read_text().splitlines() if ln.strip()]
    )

    # --- Posting (hard step) --------------------------------------------------
    post = runner.run("CBTRN02C", check=False)
    posted_records = runner.unload_output("TRANFILE", layout=_TRAN)
    posted_ids = [_text_field(rec, _TRAN, "TRAN-ID") for rec in posted_records]
    acct_post = _acct_bal_by_id(runner.unload_output("ACCTFILE", layout=_ACCOUNT))
    rej_path = post.output_path("DALYREJS")
    raw_rej = rej_path.read_bytes() if rej_path.exists() else b""
    reject_records = _frame_records(raw_rej, _REJ_RECLEN) if raw_rej else []
    reject_ids = [rec[:_TRAN_ID_LEN].strip() for rec in reject_records]

    # --- Interest (hard step) -------------------------------------------------
    # WHY the CBACT04D driver (adapter): CBACT04C declares PROCEDURE DIVISION USING
    # EXTERNAL-PARMS and is built as a shared MODULE, so it cannot launch as a
    # standalone -x executable; CBACT04D reconstructs the linkage, CALLs CBACT04C, and
    # propagates its RETURN-CODE and DISPLAY output. The processing date is injected via
    # the driver's CARDDEMO_PARM_DATE env hook (CBACT04C reads it from the linkage
    # record, not argv, so an env-driven driver is the faithful, deterministic adapter).
    interest = runner.run(
        "CBACT04D", env_overrides={"CARDDEMO_PARM_DATE": PARM_DATE}, check=False
    )
    int_path = interest.output_path("TRANSACT")
    raw_int = int_path.read_bytes() if int_path.exists() else b""
    interest_records = _frame_records(raw_int, _TR_RECLEN) if raw_int else []
    acct_final = _acct_bal_by_id(runner.unload_output("ACCTFILE", layout=_ACCOUNT))
    tcat_final_records = runner.unload_output("TCATBALF", layout=_TCATBAL)

    # --- Reporting (hard step; honest severity) -------------------------------
    seeds_for_report = seeds
    if omit_cardxref:
        # Fault injection: provision everything EXCEPT CARDXREF so CBTRN03C fails to
        # OPEN a required file. We inline a reduced provisioning that skips the xref.
        tf = runner.assign_path("TRANFILE")
        for sidecar in (tf, Path(str(tf) + ".idx"), Path(str(tf) + ".dat")):
            if sidecar.exists():
                sidecar.unlink()
        tf.write_bytes(
            b"".join(r.encode("latin-1")[:_TR_RECLEN].ljust(_TR_RECLEN) for r in posted_records)
        )
        for assign_name, basename, reclen, key_len in (
            ("TRANTYPE", "trantype.txt", _TRANTYPE_RECLEN, _TRANTYPE_KEY),
            ("TRANCATG", "trancatg.txt", _TRANCATG_RECLEN, _TRANCATG_KEY),
        ):
            rows = [
                ln.replace("\r", "")
                for ln in (seeds_for_report / basename).read_text().splitlines()
                if ln.strip()
            ]
            flat = runner.workspace / f"_report_{assign_name}.txt"
            flat.write_bytes(
                b"".join(row.encode("latin-1")[:reclen].ljust(reclen) + b"\n" for row in rows)
            )
            runner.load_input(assign_name, str(flat), reclen=reclen, key_length=key_len)
        runner.assign_path("DATEPARM").write_bytes(
            ("0000-00-00" + " " + "9999-99-99").ljust(80).encode("latin-1")
        )
    else:
        _provision_reporting_inputs(runner, seeds_for_report, posted_records)
    report = runner.run("CBTRN03C", check=False)
    rep_path = runner.assign_path("TRANREPT")
    report_bytes = rep_path.read_bytes() if rep_path.exists() else b""

    return {
        "post": post,
        "interest": interest,
        "report": report,
        "n_daily": n_daily,
        "daily_ids": daily_ids,
        "raw_rej": raw_rej,
        "reject_records": reject_records,
        "reject_ids": reject_ids,
        "raw_int": raw_int,
        "interest_records": interest_records,
        "posted_records": posted_records,
        "posted_ids": posted_ids,
        "acct_init": acct_init,
        "acct_post": acct_post,
        "acct_final": acct_final,
        "tcat_final_records": tcat_final_records,
        "report_bytes": report_bytes,
        "report_framed": _frame_report(report_bytes),
    }


def _run_statement_stage(build_dir: Path, repo_root: Path, workspace: Path,
                         reports_dir: Path) -> StatementStage:
    """Run the statement generator against its canonical fixtures; capture the result.

    Parameters
    ----------
    build_dir : pathlib.Path
        Session build directory (holds/receives the compat binary).
    repo_root : pathlib.Path
        Repository root (locates sources, fixtures, goldens).
    workspace : pathlib.Path
        Isolated workspace for this stage's indexed inputs and outputs.
    reports_dir : pathlib.Path
        Suite reports directory (passed through to the runner).

    Returns
    -------
    StatementStage
        ``severity`` -- honest :func:`_severity` of the run, or ``None`` if the compat
        binary genuinely cannot be built in this environment; ``note`` -- a status
        string; ``outputs`` -- ``{ASSIGN_name: framed_text}`` for each statement output
        (empty when the stage could not run).

    Raises
    ------
    None
        A build/run failure is captured as ``severity`` (or ``None``), never raised, so
        the fixture can record it and the dedicated tests apply the strict-gate policy.

    Notes
    -----
    WHY a dedicated workspace + canonical fixtures (Trade-off): the statement generator
    reads a purpose-built TRNXFILE (a 32-byte-keyed transaction index) that the daily
    posting stage does not produce, and the committed statement goldens correspond to
    the statement fixtures' specific accounts. Running the stage against its canonical
    inputs in its own workspace proves the statement program EXECUTES cleanly and emits
    byte-correct output as part of the cycle, without entangling it with the posting
    workspace's different master data.
    """
    compat = _stmt.ensure_compat_binary(build_dir, repo_root)
    if compat is None:
        return StatementStage(
            severity=None,
            note="CBSTM03A_LINUXCOMPAT could not be built (no usable cobc / compat compile failed)",
            outputs={},
        )
    runner = CobolRunner(build_dir, workspace, reports_dir)
    scenario_dir = repo_root / "tests" / "fixtures" / "statement" / "happy_path"
    _stmt.load_statement_inputs(runner, scenario_dir)
    result = runner.run(_stmt.COMPAT_MAIN, check=False)
    if _stmt.is_sigsegv(result):
        return StatementStage(
            severity=None,
            note="CBSTM03A_LINUXCOMPAT still crashed at runtime after neutralising the z/OS scan",
            outputs={},
        )
    outputs = {
        assign_name: _stmt.frame_fixed_width_records(
            result.read_output(assign_name), _stmt.STATEMENT_OUTPUT_WIDTHS[assign_name]
        )
        for assign_name, _golden in _stmt.STATEMENT_OUTPUTS
    }
    return StatementStage(
        severity=_severity(result.returncode),
        note=f"{_stmt.COMPAT_MAIN} rc={result.returncode}",
        outputs=outputs,
    )


@pytest.fixture(scope="module")
def full_cycle(built_programs, build_dir, repo_root, reports_dir, tmp_path_factory):
    """Run the whole daily batch cycle EXACTLY ONCE and share the immutable result.

    Parameters
    ----------
    built_programs : pathlib.Path
        Session build directory (compiled once); the runner's ``build_dir`` argument.
    build_dir : pathlib.Path
        Same session build directory (used for the statement compat build).
    repo_root : pathlib.Path
        Repository root fixture.
    reports_dir : pathlib.Path
        Suite reports directory fixture.
    tmp_path_factory : _pytest.tmpdir.TempPathFactory
        Builtin factory used to mint module-scoped workspaces.

    Yields
    ------
    FullCycle
        The immutable capture consumed by every end-state/reporting/statement/rubric
        test in this module.

    Raises
    ------
    subprocess.CalledProcessError
        If provisioning the alternate-key XREF fails.

    Notes
    -----
    WHY module scope + run-once (resolves F-P4-6): the previous design called the full
    pipeline in BOTH the end-state and rubric tests, executing the expensive cycle
    twice. Running it once here and sharing an immutable namedtuple keeps every
    assertion consistent (they all see the same bytes) and roughly halves the module's
    runtime. A module-scoped fixture may depend on the session-scoped build fixtures and
    the builtin ``tmp_path_factory``; the workspaces it mints are isolated from every
    function-scoped test's workspace.
    """
    core_ws = tmp_path_factory.mktemp("full_cycle_core")
    runner = CobolRunner(built_programs, core_ws, reports_dir)
    core = _run_core_cycle(runner, repo_root)

    stmt_ws = tmp_path_factory.mktemp("full_cycle_stmt")
    statement = _run_statement_stage(build_dir, repo_root, stmt_ws, reports_dir)

    # Honest severity rollup for the RUNNING stages only. Export/import are excluded by
    # design (documented, out-of-scope non-compile) and covered by a dedicated xfail
    # test -- NOT degraded to WARN here.
    steps = [
        ("posting", _severity(core["post"].returncode), f"CBTRN02C rc={core['post'].returncode}"),
        ("interest", _severity(core["interest"].returncode),
         f"CBACT04C(via CBACT04D) rc={core['interest'].returncode}"),
        ("reporting", _severity(core["report"].returncode), f"CBTRN03C rc={core['report'].returncode}"),
    ]
    if statement.severity is not None:
        steps.append(("statement", statement.severity, statement.note))

    yield FullCycle(statement=statement, steps=steps, **core)


def test_full_batch_cycle_end_state(full_cycle):
    """Assert end-state datasets with RAW-record oracles + independent read-backs.

    Parameters
    ----------
    full_cycle : FullCycle
        The run-once cycle capture.

    Returns
    -------
    None

    Notes
    -----
    Resolves F-P4-4 / F-P5-1: replaces the former lossy projections and the
    ``(processed-rejected)+rejected==processed`` tautology with (1) full fixed-width
    golden comparison of the reject stream (REJECT) and interest transactions
    (INTTRAN), (2) set reconciliation of the posted vs rejected ids against the day's
    inputs read back independently, and (3) exact Decimal monetary conservation of the
    posting stage measured against the post-posting account snapshot.
    """
    fc = full_cycle

    # --- Posting: exact counts + independent set reconciliation (no tautology) ----
    assert fc.post.returncode in (RC_PASS, RC_WARN), (
        f"posting RC={fc.post.returncode}\n{fc.post.stderr}"
    )
    processed = _parse_count(fc.post.stdout, "PROCESSED")
    rejected = _parse_count(fc.post.stdout, "REJECTED")
    assert processed == fc.n_daily == _EXPECTED_DAILY, (
        f"processed {processed} / n_daily {fc.n_daily} != {_EXPECTED_DAILY}"
    )
    posted_set, reject_set, daily_set = set(fc.posted_ids), set(fc.reject_ids), set(fc.daily_ids)
    # Independent read-backs, not counters, decide the reconciliation.
    assert len(fc.posted_ids) == len(posted_set) == _EXPECTED_POSTED, "posted ids not unique / wrong count"
    assert len(fc.reject_ids) == len(reject_set) == _EXPECTED_REJECTED, "reject ids not unique / wrong count"
    assert rejected == _EXPECTED_REJECTED, f"REJECTED counter {rejected} != {_EXPECTED_REJECTED}"
    assert posted_set.isdisjoint(reject_set), "a transaction was BOTH posted and rejected"
    assert posted_set | reject_set == daily_set, "posted UNION rejected != the day's inputs"
    assert len(daily_set) == _EXPECTED_DAILY, "daily inputs not unique"

    # Every reject carries a documented reason code + exact message text.
    for rec in fc.reject_records:
        code = rec[350:354].strip()
        msg = rec[354:430].strip()
        assert code in VALID_REJECT_CODES, f"undocumented reject code {code!r}"
        assert msg == REJECT_MESSAGES[code]

    # --- Independent master read-backs ---------------------------------------
    assert len(fc.acct_final) == _EXPECTED_ACCOUNTS, "unexpected ACCTFILE row count"
    tcat_keys = [_text_field(r, _TCATBAL, "TRANCAT-ACCT-ID") for r in fc.tcat_final_records]
    assert len(fc.tcat_final_records) == _EXPECTED_TCAT_FINAL, "unexpected TCATBAL row count"
    assert len(fc.tcat_final_records) == len(set(
        r[:17] for r in fc.tcat_final_records
    )), "TCATBAL composite keys not unique"
    assert len(tcat_keys) == _EXPECTED_TCAT_FINAL

    # --- Posting monetary conservation (Decimal, exact) ----------------------
    # WHY measure against the POST-POSTING snapshot (Assumption): interest also mutates
    # balances, so conservation of the posting stage must be read before interest ran.
    posting_delta = sum(
        (fc.acct_post[aid] - fc.acct_init[aid] for aid in fc.acct_post),
        Decimal("0"),
    )
    posted_amounts = sum(
        (_money_field(rec, _TRAN, "TRAN-AMT") for rec in fc.posted_records),
        Decimal("0"),
    )
    assert posted_amounts == _EXPECTED_CONSERVATION, (
        f"sum posted TRAN-AMT {posted_amounts} != {_EXPECTED_CONSERVATION}"
    )
    assert posting_delta == _EXPECTED_CONSERVATION, (
        f"sum posting balance delta {posting_delta} != {_EXPECTED_CONSERVATION}"
    )

    # --- Interest: classification + exact total ------------------------------
    assert fc.interest.returncode == RC_PASS, f"interest RC={fc.interest.returncode}\n{fc.interest.stderr}"
    assert len(fc.interest_records) == _EXPECTED_INT_TXNS, "unexpected interest transaction count"
    total_interest = Decimal("0")
    for rec in fc.interest_records:
        assert _text_field(rec, _INTTRAN, "TRAN-TYPE-CD") == INT_TYPE
        assert _text_field(rec, _INTTRAN, "TRAN-CAT-CD") == INT_CAT
        total_interest += _money_field(rec, _INTTRAN, "TRAN-AMT")
    assert DEFAULT_FALLBACK_MARKER in fc.interest.stdout, "DEFAULT fallback not exercised"
    assert total_interest == _EXPECTED_INT_TOTAL, (
        f"sum interest TRAN-AMT {total_interest} != {_EXPECTED_INT_TOTAL}"
    )

    # --- End-state golden masters (RAW records, PROC-TS masked) --------------
    golden_dir = _GOLDEN_ROOT
    assert_matches_golden(
        "\n".join(fc.reject_records),
        golden_dir / "posting" / "e2e_full_cycle_dalyrejs.expected",
        layout=_REJECT,
    )
    assert_matches_golden(
        "\n".join(fc.interest_records),
        golden_dir / "interest" / "e2e_full_cycle_interest.expected",
        layout=_INTTRAN,
    )


def test_full_batch_reporting_matches_golden(full_cycle):
    """Genuinely run CBTRN03C and validate its report against a committed golden.

    Parameters
    ----------
    full_cycle : FullCycle
        The run-once cycle capture (its reporting stage already executed).

    Returns
    -------
    None

    Notes
    -----
    Resolves F-P4-2 (reporting half): the report is provisioned with real inputs (the
    posted transactions handed off as a sequential dataset plus the reference
    xref/type/category files) and compared -- framed, byte-for-byte -- against
    ``tests/golden/reporting/e2e_full_cycle_report.expected``. The Grand Total is also
    asserted directly as a human-auditable financial cross-check.
    """
    fc = full_cycle
    assert fc.report.returncode == RC_PASS, (
        f"reporting CBTRN03C RC={fc.report.returncode}\n{fc.report.stderr}"
    )
    assert fc.report_bytes, "reporting produced no output"
    assert len(fc.report_bytes) % _REPORT_RECLEN == 0, "report not a whole number of 133-byte records"
    assert len(fc.report_bytes) // _REPORT_RECLEN == _EXPECTED_REPORT_RECORDS, "unexpected report record count"
    # Human-auditable financial cross-check independent of the golden bytes.
    assert any(
        "Grand Total" in line and _EXPECTED_GRAND_TOTAL in line
        for line in fc.report_framed.splitlines()
    ), f"report Grand Total {_EXPECTED_GRAND_TOTAL!r} not found"
    assert_matches_golden(
        fc.report_framed,
        _GOLDEN_ROOT / "reporting" / "e2e_full_cycle_report.expected",
    )


def test_full_batch_statement_matches_golden(full_cycle):
    """Genuinely run the statement generator and validate against committed goldens.

    Parameters
    ----------
    full_cycle : FullCycle
        The run-once cycle capture (its statement stage already executed).

    Returns
    -------
    None

    Notes
    -----
    Resolves F-P4-2 (statement half): the statement stage runs the platform-compat
    ``CBSTM03A_LINUXCOMPAT`` binary (z/OS control-block scan neutralised in a scratch
    copy; production source untouched -- see :mod:`tests.helpers.statement_compat`) and
    its plain-text + HTML outputs are compared against the committed statement goldens.
    A genuinely-degraded environment that cannot build the compat binary routes through
    the strict gate (fail under ``CARDDEMO_REQUIRE_COBOL``, else skip) -- never a silent
    green.
    """
    stmt = full_cycle.statement
    if stmt.severity is None or not stmt.outputs:
        _require_or_skip(f"statement stage could not run: {stmt.note}")
    assert stmt.severity == RC_PASS, f"statement stage severity {stmt.severity} ({stmt.note})"
    golden_dir = _GOLDEN_ROOT / "statement" / "happy_path"
    for assign_name, golden_name in _stmt.STATEMENT_OUTPUTS:
        assert_matches_golden(stmt.outputs[assign_name], golden_dir / golden_name)


@pytest.mark.xfail(strict=True, reason=(
    "CBEXPORT/CBIMPORT do not compile against the frozen baseline: their FD declares "
    "RECORD KEY IS EXPORT-SEQUENCE-NUM but that field is not in the FD record "
    "(app/cbl/CBEXPORT.cbl:68 / CBIMPORT.cbl:40). Production source is REFERENCE-only "
    "(AAP 0.8.2), so this is documented, not fixed; the xfail flips to a hard failure "
    "if the baseline is ever corrected, prompting removal of this marker."
))
def test_full_batch_export_import_not_ready(built_programs, repo_root):
    """Document the export/import non-compile with exact compiler evidence (xfail).

    Parameters
    ----------
    built_programs : pathlib.Path
        Session build directory (unused for building here; present so the session build
        gate has run and the environment is the same one the cycle uses).
    repo_root : pathlib.Path
        Repository root (locates the export source).

    Returns
    -------
    None

    Notes
    -----
    Resolves F-P4-2 / F-P4-1 (export-import half): rather than silently degrading the
    unbuildable export/import programs to WARN inside the cycle, this test ATTEMPTS the
    real compile and asserts it SUCCEEDS. It does not (the FD-key defect), so the body
    fails and the strict xfail records the documented gap. WHY assert success (so the
    xfail is meaningful): an xfail must fail for the RIGHT reason -- if the baseline is
    fixed and the compile starts succeeding, this test XPASSes and, under
    ``xfail_strict``, becomes a hard failure that forces this marker's removal.
    """
    source = repo_root / "app" / "cbl" / "CBEXPORT.cbl"
    assert source.is_file(), f"missing export source {source}"
    completed = subprocess.run(
        ["cobc", "-x", "-fixed", "-I", str(repo_root / "app" / "cpy"),
         "--std=ibm-strict", "-fsyntax-only", str(source)],
        cwd=str(repo_root), capture_output=True, text=True, timeout=120,
    )
    # WHY surface the exact signature on failure (auditability): if this ever XPASSes we
    # want the transcript to show the defect signature is gone, confirming a real fix.
    assert completed.returncode == 0, (
        f"CBEXPORT failed to compile (expected once the baseline is fixed); "
        f"signature {_UNSUPPORTED_SIGNATURE!r} present in stderr: "
        f"{_UNSUPPORTED_SIGNATURE in completed.stderr}\n{completed.stderr[-500:]}"
    )


def test_full_batch_condition_code_rubric(full_cycle):
    """Assert the HONEST aggregate return-code rubric for the whole cycle.

    Parameters
    ----------
    full_cycle : FullCycle
        The run-once cycle capture.

    Returns
    -------
    None

    Notes
    -----
    Resolves F-P4-1 / F-P5-2: every running stage is scored with the honest
    :func:`_severity` map -- NO stage is capped at WARN. The financial core (posting,
    interest) has strict bounds; reporting and statement genuinely ran to RC=0 and so
    contribute PASS. The aggregate worst-case severity must therefore be exactly WARN
    (from posting's soft rejects) and stay below FAIL -- proving the cycle completed
    with no hard failure while a real crash in any RUNNING stage WOULD fail it (see
    :func:`test_full_batch_fault_injection_fails_honestly`). Export/import are excluded
    by design and tracked by :func:`test_full_batch_export_import_not_ready`.
    """
    steps = dict((name, sev) for name, sev, _note in full_cycle.steps)

    # Core financial steps: strict severity bounds.
    assert steps["posting"] <= RC_WARN, f"posting severity {steps['posting']}"
    assert steps["interest"] == RC_PASS, f"interest severity {steps['interest']}"
    # Ancillary steps that RAN are scored honestly (not capped): they must be PASS here.
    assert steps["reporting"] == RC_PASS, f"reporting severity {steps['reporting']}"
    assert steps.get("statement") == RC_PASS, f"statement severity {steps.get('statement')}"

    aggregate = max(sev for _name, sev, _note in full_cycle.steps)
    notes = "\n".join(note for _n, _s, note in full_cycle.steps)
    assert aggregate == RC_WARN, f"expected aggregate WARN (posting soft rejects), got {aggregate}\n{notes}"
    assert aggregate < RC_FAIL, f"aggregate severity {aggregate} >= FAIL\n{notes}"


def test_full_batch_fault_injection_fails_honestly(cobol_runner, repo_root):
    """Inject a mid-chain fault and prove the honest rubric FAILS the cycle.

    Parameters
    ----------
    cobol_runner : tests.helpers.cobol_runner.CobolRunner
        Function-scoped runner with its own isolated workspace (a deliberately-broken
        run, kept separate from the shared happy-path ``full_cycle``).
    repo_root : pathlib.Path
        Repository root fixture.

    Returns
    -------
    None

    Notes
    -----
    Resolves F-P4-5: posting and interest succeed, then the reporting stage is starved
    of its required CARDXREF input so CBTRN03C fails at OPEN. The test asserts (1) the
    financial core still succeeded, (2) the honest :func:`_severity` of the reporting
    failure is at least FAIL, (3) the aggregate worst-case severity is >= FAIL (i.e. the
    masking that once hid this is gone), and (4) the failed stage produced NO misleading
    report output. This is the guard that proves the rubric in
    :func:`test_full_batch_condition_code_rubric` passes for the RIGHT reason.
    """
    core = _run_core_cycle(cobol_runner, repo_root, omit_cardxref=True)

    # The financial core is unaffected by the reporting fault.
    assert core["post"].returncode in (RC_PASS, RC_WARN), "posting should be unaffected by the reporting fault"
    assert core["interest"].returncode == RC_PASS, "interest should be unaffected by the reporting fault"

    report_severity = _severity(core["report"].returncode)
    assert report_severity >= RC_FAIL, (
        f"injected reporting fault must score >= FAIL, got {report_severity} "
        f"(rc={core['report'].returncode})"
    )
    # The honest aggregate over the running stages preserves the worst severity.
    aggregate = max(
        _severity(core["post"].returncode),
        _severity(core["interest"].returncode),
        report_severity,
    )
    assert aggregate >= RC_FAIL, f"aggregate must reflect the injected fault, got {aggregate}"
    # A failed stage must not leave misleading downstream output.
    assert not core["report_bytes"], "failed reporting stage unexpectedly produced report output"
