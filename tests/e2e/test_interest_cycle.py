"""End-to-end (Layer-3) golden-master tests for the CardDemo *interest* cycle.

Purpose
-------
Reproduce the interest-accrual leg of the daily batch chain (the ``INTCALC`` JCL
step that executes ``CBACT04C`` via the ``CBACT04D`` driver) on deterministic seed
data and assert, to financial-enterprise rigor, on **every observable output**:

* the process return codes (posting precondition + clean interest run);
* the **byte-exact emitted interest ledger** -- the ``TRANSACT`` records, compared as
  raw fixed-width records against a committed golden;
* the **effect on the account master** -- read-back of ``ACCTFILE`` before and after the
  interest run proving that, for every correctly flushed account, the balance rose by
  exactly the accrued interest and the billing-cycle credit/debit were cleared;
* an **independent Decimal recomputation** of each account's interest from the category
  principals (``TCATBALF``) and disclosure-group rates (``DISCGRP``) using the documented
  ``(TRAN-CAT-BAL * rate) / 1200`` formula with COBOL truncation (ROUND_DOWN), matching
  the emitted amounts to the cent;
* the ``DEFAULT`` disclosure-group fallback branch, and the absence of any fee record
  (the ``1400-COMPUTE-FEES`` stub is unimplemented).

Known-defect coverage (WHY an xfail)
------------------------------------
``CBACT04C`` fails to flush the **final** account processed: its updated balance and
cleared cycle counters are never written back, so account ``00000000050`` retains its
pre-interest cycle credit/debit even though an interest transaction WAS emitted for it.
This is asserted by a dedicated ``@pytest.mark.xfail(strict=True)`` test that encodes the
*correct* behaviour; it xfails against the current (defective) production source and would
turn any future silent fix into a hard failure (prompting removal of the xfail). Per
AAP §0.8.2 the production COBOL is REFERENCE-only and is NOT modified to satisfy the test.

Oracle design (WHY raw records, not a lossy projection)
------------------------------------------------------
Earlier revisions compared only a lossy ``type|category|amount|description`` text
projection. This module instead compares the whole 350-byte record via
:func:`tests.helpers.golden_compare.assert_matches_golden` in record mode
(``layout="INTTRAN"``): byte-exact except that BOTH non-deterministic timestamps
(``TRAN-ORIG-TS`` and ``TRAN-PROC-TS`` -- ``CBACT04C`` sets both to the runtime
CURRENT-DATE) are blanked on each side by the layout mask. The date-prefixed ``TRAN-ID``
IS retained and asserted, because the injected ``PARM_DATE`` makes it deterministic.

Ordering (WHY post -> interest)
------------------------------
The shipped ``tcatbal`` seed carries **zero** category balances; posting (``CBTRN02C``)
populates ``TCATBALF`` so the subsequent interest computation has non-zero principal,
exactly mirroring the real daily batch dependency order.

Determinism & isolation
-----------------------
The cycle date is injected via ``PARM_DATE`` (fixed ``2022071800``) so the emitted
TRAN-IDs are reproducible. Each test uses a fresh workspace (function-scoped ``tmp_path``);
the only non-deterministic bytes (the two timestamps) are masked by the record layout, so
the golden comparison is otherwise byte-exact. Golden regeneration is the guarded two-step
opt-in (``CARDDEMO_UPDATE_GOLDENS=1`` + ``update=True``, never in CI); a missing golden
fails hard.

Binding contract
-----------------
Collected by ``scripts/run_e2e_tests.sh`` -> ``pytest tests/e2e -m e2e``.
"""
from __future__ import annotations

import os
import re
import subprocess
from collections import namedtuple
from decimal import ROUND_DOWN, Decimal
from pathlib import Path

import pytest

# §0.6.2 import contract.
from tests.helpers.golden_compare import assert_matches_golden

# Shared fixed-width record accessors (see tests/helpers/e2e_records.py). Reused verbatim
# by the posting and full-cycle e2e suites so the three cannot drift on field geometry.
# WHY these replace the former inline decode_zoned calls: money_field resolves the
# TRAN-AMT int/dec/signed geometry from the single-sourced record layout, so the explicit
# per-field digit constants are no longer needed here.
from tests.helpers.e2e_records import (
    frame_records as _frame_records,
    money_field as _money_field,
    text_field as _text_field,
)

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
# TRAN-AMT (PIC S9(09)V99) is decoded via _money_field(rec, "INTTRAN", "TRAN-AMT"), which
# sources the 9-int/2-dec/signed geometry from the record layout -- no explicit slice needed.

# Loader geometry (reclen, key_length) proven against the seeds.
_ACCT = (300, 11)
_TCAT = (50, 17)
_DISC = (50, 16)   # CVTRA02Y: group+type+cat composite key of 16 bytes

# --- Expected deterministic outcomes for the shipped seed (financial golden values) ---
# WHY assert exact numbers alongside structural invariants (defence-in-depth): the seed is
# REFERENCE-only (immutable per AAP §0.8.2), so its interest outcome is a fixed,
# QA-confirmed fact; a silent change in program behaviour or seed content then fails loudly.
_EXPECTED_INT_TXNS = 50               # one interest transaction per account
_EXPECTED_INT_TOTAL = Decimal("1279.16")  # sum of all emitted interest amounts
_EXPECTED_CLEARED_ACCOUNTS = 49       # accounts whose cycle counters are correctly cleared

# Layout name for interest transactions. WHY INTTRAN (not TRAN): CBACT04C stamps BOTH
# TRAN-ORIG-TS and TRAN-PROC-TS with the runtime CURRENT-DATE, so BOTH must be masked in the
# golden -- the INTTRAN layout marks both, whereas TRAN masks only PROC-TS.
_INTTRAN = "INTTRAN"

# DEFAULT disclosure-group key image (10-wide, space-padded). WHY: all seed accounts carry a
# blank ACCT-GROUP-ID, so CBACT04C's group lookup misses (VSAM status 23) and falls back to
# the DEFAULT group; the independent recompute must read rates from these DEFAULT rows.
_DEFAULT_GROUP_ID = "DEFAULT".ljust(10)

# Interest description carries the target account id: "Int. for a/c <ACCT-ID>". WHY parse it:
# it is the in-record link from an emitted interest transaction back to its account, used to
# aggregate accrued interest per account for the master-effect and recompute assertions.
_INT_DESC_ACCT_RE = re.compile(r"Int\. for a/c\s*(\d+)")

# --- Known production defect (documented, NOT fixed -- production source is REFERENCE) ----
# CBACT04C does not flush the LAST account it processes; acct 00000000050 (highest key, hence
# processed last) keeps its pre-interest cycle counters and its balance is not incremented,
# even though an interest transaction of 18.77 IS emitted for it. These exact values are the
# QA-confirmed defect signature, asserted by the xfail test below.
_UNFLUSHED_ACCT = "00000000050"
_DEFECT_CYC_CREDIT = Decimal("1501.75")
_DEFECT_CYC_DEBIT = Decimal("-47.88")
_DEFECT_ACCRUED = Decimal("18.77")

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


# Immutable result bundle for one interest run. WHY a namedtuple (Trade-off): the five
# interest tests each assert on a different facet (structural invariants, raw golden, master
# effect, the flush defect, and the independent recompute); capturing the full observable
# state once -- both account snapshots, the emitted ledger, the category principals, the
# disclosure rates, and the per-account accrual -- lets each test read exactly what it needs
# from one isolated per-test run without re-deriving geometry.
InterestRun = namedtuple(
    "InterestRun",
    [
        "post",               # RunResult of the CBTRN02C posting precondition
        "interest",           # RunResult of the CBACT04D interest driver
        "raw",                # bytes: raw TRANSACT image emitted by interest
        "records",            # list[str]: framed 350-byte interest records
        "acct_pre",           # dict[str, tuple]: ACCT-ID -> (bal, cyc_cr, cyc_db) pre-interest
        "acct_post",          # dict[str, tuple]: same, post-interest
        "tcat_post",          # list[str]: TCATBAL records (principals) after posting
        "disc_rates",         # dict[(gid,typ,cat)->Decimal]: DISCGRP interest rates
        "accrued_by_acct",    # dict[str, Decimal]: ACCT-ID -> sum of emitted interest amounts
    ],
)


def _acct_state(records: "list[str]") -> "dict[str, tuple]":
    """Index ACCOUNT records by ACCT-ID with balance and billing-cycle counters.

    Parameters
    ----------
    records : list of str
        ``ACCOUNT``-layout records (from ``unload_output(layout="ACCOUNT")``).

    Returns
    -------
    dict[str, tuple]
        Mapping ``ACCT-ID -> (ACCT-CURR-BAL, ACCT-CURR-CYC-CREDIT, ACCT-CURR-CYC-DEBIT)``,
        every value an exact ``Decimal``.

    Notes
    -----
    WHY these three fields: the interest run both increments the balance by the accrued
    interest AND clears the two cycle counters; asserting the master effect requires all
    three, compared between the pre- and post-interest snapshots.
    """
    return {
        _text_field(rec, "ACCOUNT", "ACCT-ID"): (
            _money_field(rec, "ACCOUNT", "ACCT-CURR-BAL"),
            _money_field(rec, "ACCOUNT", "ACCT-CURR-CYC-CREDIT"),
            _money_field(rec, "ACCOUNT", "ACCT-CURR-CYC-DEBIT"),
        )
        for rec in records
    }


def _accrued_by_account(records: "list[str]") -> "dict[str, Decimal]":
    """Aggregate emitted interest amounts per target account.

    Parameters
    ----------
    records : list of str
        Framed interest ``TRANSACT`` records (``layout="INTTRAN"`` geometry).

    Returns
    -------
    dict[str, decimal.Decimal]
        ACCT-ID (parsed from the ``Int. for a/c <id>`` description) -> summed interest.

    Raises
    ------
    AssertionError
        If a record's description does not carry a parseable account id (a contract
        regression -- every interest transaction must name its account).

    Notes
    -----
    WHY parse the description (Assumption): the account id is embedded in ``TRAN-DESC`` as
    ``Int. for a/c <ACCT-ID>`` (see CBACT04C 1300-B-WRITE-TX); this is the in-record link
    from an interest transaction back to its account for the per-account assertions.
    """
    accrued: "dict[str, Decimal]" = {}
    for rec in records:
        # TRAN-DESC is padded with COBOL LOW-VALUES (0x00); drop them before matching.
        desc = rec[_TR_DESC].replace("\x00", "").strip()
        match = _INT_DESC_ACCT_RE.search(desc)
        assert match, f"interest description missing account id: {desc!r}"
        acct = match.group(1)
        accrued[acct] = accrued.get(acct, Decimal("0.00")) + _money_field(
            rec, _INTTRAN, "TRAN-AMT"
        )
    return accrued


def _run_interest_cycle(cobol_runner, repo_root: Path) -> InterestRun:
    """Provision inputs, post transactions, then accrue interest deterministically.

    Captures the complete observable state so downstream tests can assert on the ledger,
    the master effect, the flush defect, and the independent recompute without re-running.

    Parameters
    ----------
    cobol_runner : tests.helpers.cobol_runner.CobolRunner
        Runner bound to a fresh, isolated workspace.
    repo_root : pathlib.Path
        Repository root.

    Returns
    -------
    InterestRun
        Immutable snapshot of both account states, the emitted ledger, the category
        principals, the disclosure rates, and the per-account accrual.

    Raises
    ------
    tests.helpers.vsam_loader.VsamLoadError
        If a seed fixture violates the fixed-width contract during load.
    subprocess.CalledProcessError
        If the alternate-key XREF provisioner fails.

    Notes
    -----
    WHY run posting first: the seed category balances are zero; posting populates
    ``TCATBALF`` so the subsequent interest computation has non-zero principal. WHY snapshot
    the account master and category principals *before* the interest run: the master effect
    is a pre/post delta, and the independent recompute needs the post-posting principals
    (which the interest run does not modify) paired with the disclosure rates.
    """
    seeds = _seed_dir(repo_root)
    cobol_runner.load_input("ACCTFILE", seeds / "acctdata.txt", *_ACCT)
    cobol_runner.load_input("TCATBALF", seeds / "tcatbal.txt", *_TCAT)
    cobol_runner.load_input("DISCGRP", seeds / "discgrp.txt", *_DISC)
    _provision_alt_key_xref(cobol_runner, seeds / "cardxref.txt")
    _flatten_daily(seeds / "dailytran.txt", cobol_runner.assign_path("DALYTRAN"))

    post = cobol_runner.run("CBTRN02C")

    # Snapshot masters/principals/rates AFTER posting, BEFORE interest.
    acct_pre = _acct_state(cobol_runner.unload_output("ACCTFILE", layout="ACCOUNT"))
    tcat_post = cobol_runner.unload_output("TCATBALF", layout="TCATBAL")
    disc_rates = {}
    for rec in cobol_runner.unload_output("DISCGRP", layout="DISGROUP"):
        # DISCGRP composite key: group-id X(10) @0, type X(02) @10, category X(04) @12.
        key = (rec[0:10], rec[10:12], rec[12:16])
        disc_rates[key] = _money_field(rec, "DISGROUP", "DIS-INT-RATE")

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
    records = _frame_records(raw, _TR_RECLEN) if raw else []
    acct_post = _acct_state(cobol_runner.unload_output("ACCTFILE", layout="ACCOUNT"))
    accrued_by_acct = _accrued_by_account(records)

    return InterestRun(
        post=post,
        interest=interest,
        raw=raw,
        records=records,
        acct_pre=acct_pre,
        acct_post=acct_post,
        tcat_post=tcat_post,
        disc_rates=disc_rates,
        accrued_by_acct=accrued_by_acct,
    )


def _recompute_interest_by_account(run: InterestRun) -> "dict[str, Decimal]":
    """Independently recompute per-account interest from principals, rates, and the formula.

    Parameters
    ----------
    run : InterestRun
        A completed interest run (supplies ``tcat_post`` principals and ``disc_rates``).

    Returns
    -------
    dict[str, decimal.Decimal]
        ACCT-ID -> recomputed total interest, summed over the account's category rows.

    Notes
    -----
    WHY recompute independently (F-P3-3): rather than trust the program's emitted amounts,
    this reapplies the documented rule ``interest = (TRAN-CAT-BAL * rate) / 1200`` to each
    category principal and compares. The rate is read from the DEFAULT disclosure group
    because every seed account uses the fallback (blank group-id). Trade-off: the quantize
    uses ``ROUND_DOWN`` (truncation) per category because ``CBACT04C`` stores the result in a
    ``PIC S9(09)V99`` field via ``COMPUTE`` *without* ``ROUNDED``, which truncates -- a
    ROUND_HALF_UP recompute mismatches the emitted cents.
    """
    recomputed: "dict[str, Decimal]" = {}
    for rec in run.tcat_post:
        acct = _text_field(rec, "TCATBAL", "TRANCAT-ACCT-ID")
        # TCATBAL composite key exposes type @11:13 and category @13:17.
        typ = rec[11:13]
        cat = rec[13:17]
        principal = _money_field(rec, "TCATBAL", "TRAN-CAT-BAL")
        rate = run.disc_rates.get((_DEFAULT_GROUP_ID, typ, cat))
        if rate is None or rate == 0:
            # No disclosure rate for this category -> CBACT04C emits no interest for it.
            continue
        per_cat = (principal * rate / Decimal(1200)).quantize(
            Decimal("0.01"), rounding=ROUND_DOWN
        )
        recomputed[acct] = recomputed.get(acct, Decimal("0.00")) + per_cat
    return recomputed


def _golden_path(repo_root: Path, *parts: str) -> Path:
    """Resolve a golden-master path under ``tests/golden``.

    Parameters
    ----------
    repo_root : pathlib.Path
        Repository root fixture.
    *parts : str
        Path components below ``tests/golden`` (e.g. ``"interest"``, ``"x.expected"``).

    Returns
    -------
    pathlib.Path
        The assembled golden path (a missing golden is a hard failure inside the
        comparator, never a silent pass).
    """
    return repo_root.joinpath("tests", "golden", *parts)


def test_interest_cycle_invariants(cobol_runner, repo_root):
    """Assert structural interest-accrual invariants and the exact accrued total.

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
    Assertion density: posting precondition + clean interest RC, exact emitted-record
    count, each record's classification (type/category) and date-prefixed TRAN-ID shape,
    the description prefix, and the exact QA-confirmed accrued total (not merely ``> 0``,
    which the earlier revision settled for -- F-P3-3's companion). Per-account correctness
    is proven separately by :func:`test_interest_account_effects` and
    :func:`test_interest_amounts_match_independent_recompute`.
    """
    run = _run_interest_cycle(cobol_runner, repo_root)

    # Precondition: posting must not hard-fail (0 clean / 4 soft rejects allowed).
    assert run.post.returncode in (0, 4), (
        f"posting precondition RC={run.post.returncode}\nSTDERR:\n{run.post.stderr}"
    )
    # Interest is a clean batch step; any non-zero RC indicates an abend/error.
    assert run.interest.returncode == 0, (
        f"interest RC={run.interest.returncode}\nSTDERR:\n{run.interest.stderr}"
    )

    # Whole 350-byte records, exact count (one interest transaction per account).
    assert len(run.raw) % _TR_RECLEN == 0, "TRANSACT not a whole number of records"
    assert len(run.records) == _EXPECTED_INT_TXNS, (
        f"expected {_EXPECTED_INT_TXNS} interest transactions, got {len(run.records)}"
    )

    total = Decimal("0.00")
    for rec in run.records:
        # Records are latin-1 strings (framed), so field slices need no decode.
        assert rec[_TR_TYPE] == INT_TYPE, f"bad TRAN-TYPE {rec[_TR_TYPE]!r}"
        assert rec[_TR_CAT] == INT_CAT, f"bad TRAN-CAT {rec[_TR_CAT]!r}"
        tran_id = rec[_TR_ID]
        assert len(tran_id) == 16 and tran_id.isdigit(), f"bad TRAN-ID {tran_id!r}"
        # TRAN-ID embeds the injected cycle date as its prefix (determinism proof).
        assert tran_id.startswith(PARM_DATE)
        # TRAN-DESC is padded with COBOL LOW-VALUES (0x00); strip them for the text check.
        desc = rec[_TR_DESC].replace("\x00", "").strip()
        assert desc.startswith("Int. for a/c"), f"unexpected desc {desc!r}"
        total += _money_field(rec, _INTTRAN, "TRAN-AMT")

    # Exact accrued total for the immutable seed (QA-confirmed golden value).
    assert total == _EXPECTED_INT_TOTAL, (
        f"total accrued interest {total} != expected {_EXPECTED_INT_TOTAL}"
    )


def test_interest_transactions_match_golden(cobol_runner, repo_root):
    """Compare the *whole* emitted interest ledger against its golden master.

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
    WHY raw record mode (F-P3-2): every 350-byte interest record is compared with
    ``layout="INTTRAN"`` -- byte-exact except that BOTH timestamps are masked (CBACT04C sets
    ``TRAN-ORIG-TS`` and ``TRAN-PROC-TS`` to the runtime CURRENT-DATE). Unlike the former
    lossy ``type|category|amount|description`` projection, this also protects the
    date-prefixed ``TRAN-ID`` (deterministic via ``PARM_DATE``), the sequence suffix, the
    transaction source, and the card number. Regeneration is the guarded two-step opt-in;
    a missing golden fails hard.
    """
    run = _run_interest_cycle(cobol_runner, repo_root)
    assert run.records, "interest produced no transactions to golden-compare"

    ledger = "\n".join(run.records)
    golden = _golden_path(repo_root, "interest", "e2e_interest_cycle_transactions.expected")
    assert_matches_golden(ledger, golden, layout=_INTTRAN)


def test_interest_account_effects(cobol_runner, repo_root):
    """Assert the interest run's effect on the account master (F-P3-1, correct accounts).

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
    WHY read the master back (F-P3-1): the earlier suite never verified that interest was
    actually applied to any account. This reads ``ACCTFILE`` before and after the interest
    run and asserts, for every **correctly flushed** account, that (a) both billing-cycle
    counters were cleared to zero and (b) the balance rose by exactly the accrued interest
    (exact ``Decimal``). It also pins the known flush defect: exactly one account -- the
    last processed, ``00000000050`` -- is NOT cleared and retains its QA-confirmed defect
    values, even though an interest transaction WAS emitted for it. The *correct* behaviour
    for that account is asserted (and xfails) in :func:`test_final_account_flush_defect`;
    here we only document the current state so any change in the defect's shape is noticed.
    """
    run = _run_interest_cycle(cobol_runner, repo_root)

    # Interest changes balances/cycles, not the roster of accounts.
    assert set(run.acct_pre) == set(run.acct_post), "interest changed the account id set"

    cleared, not_cleared = [], []
    for acct in run.acct_pre:
        bal_pre, _cr_pre, _db_pre = run.acct_pre[acct]
        bal_post, cyc_cr, cyc_db = run.acct_post[acct]
        if cyc_cr == 0 and cyc_db == 0:
            cleared.append(acct)
            # A correctly flushed account's balance rose by exactly its accrued interest.
            accrued = run.accrued_by_acct.get(acct, Decimal("0.00"))
            assert bal_post - bal_pre == accrued, (
                f"account {acct}: balance delta {bal_post - bal_pre} != accrued {accrued}"
            )
        else:
            not_cleared.append(acct)

    # Exactly the QA-confirmed set is cleared; exactly the known-defect account is not.
    assert len(cleared) == _EXPECTED_CLEARED_ACCOUNTS, (
        f"expected {_EXPECTED_CLEARED_ACCOUNTS} cleared accounts, got {len(cleared)}"
    )
    assert not_cleared == [_UNFLUSHED_ACCT], (
        f"expected only {_UNFLUSHED_ACCT} unflushed, got {not_cleared}"
    )

    # Document the current (defective) state of the unflushed account: its cycle counters
    # retain the pre-interest values and no interest was added to its balance, yet an
    # interest transaction of the expected amount WAS emitted for it.
    bal_pre_50, _c, _d = run.acct_pre[_UNFLUSHED_ACCT]
    bal_post_50, cyc_cr_50, cyc_db_50 = run.acct_post[_UNFLUSHED_ACCT]
    assert (cyc_cr_50, cyc_db_50) == (_DEFECT_CYC_CREDIT, _DEFECT_CYC_DEBIT)
    assert bal_post_50 == bal_pre_50, "unflushed account balance unexpectedly changed"
    assert run.accrued_by_acct.get(_UNFLUSHED_ACCT) == _DEFECT_ACCRUED


@pytest.mark.xfail(
    strict=True,
    reason=(
        "Known CBACT04C defect: the final account processed (00000000050) is not flushed "
        "after interest -- its cycle counters are not cleared and its balance is not "
        "incremented, though an interest transaction IS emitted for it. Production COBOL is "
        "REFERENCE-only (AAP §0.8.2) and is NOT modified; this xfail(strict) encodes the "
        "correct behaviour so a future fix turns the XPASS into a hard failure prompting "
        "removal of this marker."
    ),
)
def test_final_account_flush_defect(cobol_runner, repo_root):
    """Encode the CORRECT flush behaviour for the final account (currently xfails).

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
    WHY xfail(strict) (Alternatives Considered): the alternative -- asserting the buggy
    values as "expected" -- would bake the defect into the suite as if correct. Instead we
    assert what SHOULD happen (cycle counters cleared AND balance incremented by the accrued
    interest, exactly like every other account); this fails against the current defective
    source (hence ``xfail``), and if the source is ever corrected the assertions pass,
    producing an XPASS that ``strict=True`` escalates to a hard failure -- a deliberate
    reminder to delete this marker. Mirrors the integration-layer
    ``test_final_account_not_updated`` pattern.
    """
    run = _run_interest_cycle(cobol_runner, repo_root)
    bal_pre, _cr_pre, _db_pre = run.acct_pre[_UNFLUSHED_ACCT]
    bal_post, cyc_cr, cyc_db = run.acct_post[_UNFLUSHED_ACCT]
    accrued = run.accrued_by_acct.get(_UNFLUSHED_ACCT, Decimal("0.00"))

    # CORRECT behaviour (fails against the current defective source -> xfail):
    assert (cyc_cr, cyc_db) == (Decimal("0.00"), Decimal("0.00")), (
        f"final account {_UNFLUSHED_ACCT} cycle counters not cleared: cr={cyc_cr} db={cyc_db}"
    )
    assert bal_post - bal_pre == accrued, (
        f"final account {_UNFLUSHED_ACCT} balance not incremented by accrued interest {accrued}"
    )


def test_interest_amounts_match_independent_recompute(cobol_runner, repo_root):
    """Match each emitted interest amount to an independent Decimal recomputation (F-P3-3).

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
    WHY an independent recompute (F-P3-3): the earlier suite only checked ``total > 0``. This
    recomputes each account's interest from first principles -- the category principals in
    ``TCATBALF`` and the DEFAULT disclosure-group rates in ``DISCGRP`` via the documented
    ``(TRAN-CAT-BAL * rate) / 1200`` formula with COBOL truncation -- and asserts it equals
    the program's emitted amount per account (zero mismatches) and in total. This catches a
    wrong rate, wrong principal, or wrong rounding that a total-only check would miss.
    """
    run = _run_interest_cycle(cobol_runner, repo_root)
    recomputed = _recompute_interest_by_account(run)

    # The recompute must cover exactly the accounts that received an interest transaction.
    assert set(recomputed) == set(run.accrued_by_acct), (
        "independent recompute covers a different account set than the emitted ledger"
    )
    mismatches = {
        acct: (recomputed[acct], run.accrued_by_acct[acct])
        for acct in run.accrued_by_acct
        if recomputed.get(acct) != run.accrued_by_acct[acct]
    }
    assert not mismatches, (
        f"interest recompute disagrees with emitted amounts: {list(mismatches.items())[:5]}"
    )
    assert sum(recomputed.values(), Decimal("0.00")) == _EXPECTED_INT_TOTAL, (
        f"recomputed total {sum(recomputed.values(), Decimal('0.00'))} != {_EXPECTED_INT_TOTAL}"
    )


def test_no_fee_records_and_default_fallback(cobol_runner, repo_root):
    """Assert the no-fee stub behaviour and the DEFAULT disclosure-group fallback (F-P3-4).

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
    WHY (F-P3-4): ``CBACT04C``'s ``1400-COMPUTE-FEES`` is an unimplemented stub (``EXIT``
    only), so no fee transaction is ever written -- every emitted record must be an interest
    transaction (TYPE=01/CAT=0005); any other classification would mean a fee (or spurious)
    record leaked in. The DEFAULT-group fallback branch (VSAM status 23 -> re-read the
    DEFAULT group) is exercised by every seed account because all carry a blank GROUP-ID.
    Assumption/coverage note: the direct-rate path (a populated, matching group-id) IS
    present in ``DISCGRP`` but is UNEXERCISED at seed scale because no seed account
    references a populated group; exercising it would require a bespoke fixture and is a
    documented gap, not a defect.
    """
    run = _run_interest_cycle(cobol_runner, repo_root)
    assert run.records, "interest produced no transactions"

    # No fee (or other) records: every emitted transaction is an interest transaction.
    non_interest = [
        rec[_TR_ID] for rec in run.records
        if not (rec[_TR_TYPE] == INT_TYPE and rec[_TR_CAT] == INT_CAT)
    ]
    assert not non_interest, f"unexpected non-interest (e.g. fee) records: {non_interest[:5]}"

    # DEFAULT disclosure-group fallback branch must be exercised.
    assert DEFAULT_FALLBACK_MARKER in run.interest.stdout, "DEFAULT fallback not exercised"

    # A non-DEFAULT (direct-rate) group is present in DISCGRP but unexercised at seed scale;
    # assert its presence so this documented coverage gap is explicit and monitored.
    has_direct_group = any(
        gid.strip() and gid.strip() != "DEFAULT"
        for (gid, _typ, _cat) in run.disc_rates
    )
    assert has_direct_group, "expected a non-DEFAULT disclosure group present in DISCGRP"
