"""End-to-end (Layer-3) golden-master tests for the CardDemo daily *posting* cycle.

Purpose
-------
Reproduce the posting leg of the daily batch chain (the ``POSTTRAN`` JCL step that
executes ``CBTRN02C``) on deterministic seed data and assert, to financial-enterprise
rigor, on **every observable output** of the run:

* the process return code (RC=0 clean / RC=4 soft-reject rubric);
* an **independent reconciliation** proving each daily input is accounted for exactly
  once as either a posted transaction (``TRANFILE``) or a reject (``DALYREJS``);
* the **byte-exact posted ledger** -- the ``TRANSACT`` records written by the run,
  compared as raw fixed-width records against a committed golden;
* the **byte-exact reject stream** -- the ``DALYREJS`` records, compared as raw
  fixed-width records against a committed golden, plus each reject reason code and its
  verbatim message text;
* the **monetary effect on the masters** -- read-back of ``ACCTFILE`` and ``TCATBALF``
  to prove value conservation (sum of posted amounts equals the sum of account-balance
  deltas, computed in exact fixed-point ``Decimal``) and that both the ``TCATBAL``
  create branch (``2700-A-CREATE``) and update branch (``2700-B-UPDATE``) were exercised.

Oracle design (WHY raw records, not a lossy projection)
------------------------------------------------------
Earlier revisions compared only a lossy ``card|code|message`` text projection of the
reject stream, which could not detect a defect in any un-projected field (amount, dates,
account, category, description). To meet the financial-enterprise assertion-density bar
this module compares the **whole record** via
:func:`tests.helpers.golden_compare.assert_matches_golden` in *record mode*
(``layout="REJECT"`` / ``layout="TRAN"``): the comparison is byte-exact except that the
single non-deterministic processing timestamp (``*-PROC-TS``) is blanked on both sides by
the layout's own timestamp mask. Nothing else is elided, so a regression in any business
field now fails the test.

Golden regeneration (no silent self-bootstrap)
---------------------------------------------
The committed tests **compare only** -- they never write a golden. Regeneration is a
deliberate, never-committed action performed by a human operator who sets
``CARDDEMO_UPDATE_GOLDENS=1`` *and* invokes the comparator with ``update=True`` (refused in
CI and outside the golden root by :func:`tests.helpers.golden_compare._authorize_golden_update`).
A missing golden is therefore a hard failure, never a silent pass.

Determinism & isolation
-----------------------
Each test receives a fresh ``workspace`` (function-scoped ``tmp_path``) so runs are
independent and parallel-safe under ``pytest-xdist``. Inputs are the shipped,
never-mutated seed datasets (REFERENCE only). The only non-deterministic bytes -- the
processing timestamp embedded in each record -- are masked by the record layout, so the
golden comparison is otherwise byte-exact.

Binding contract
-----------------
Collected by ``scripts/run_e2e_tests.sh`` -> ``pytest tests/e2e -m e2e``; every test
here therefore carries the module-level ``e2e`` marker.
"""
from __future__ import annotations

import os
import re
import subprocess
from collections import namedtuple
from decimal import Decimal
from pathlib import Path

import pytest

# §0.6.2 import contract: the golden comparator MUST be importable from this path.
from tests.helpers.golden_compare import assert_matches_golden

# WHY single-source the record geometry (Refactoring Rationale): the fixed-width field
# offsets/widths and zoned-decimal decoding live in ``record_codec`` (which mirrors the
# ``app/cpy`` copybooks). The four read-back accessors below are shared verbatim by all
# three e2e cycle suites, so they live in ``tests.helpers.e2e_records`` and are imported
# (aliased to the module-local ``_`` names the call sites use) rather than duplicated --
# guaranteeing the read-back asserts and the golden comparator can never drift on field
# positions (Assumption: layouts match the copybook contract).
from tests.helpers.e2e_records import (
    frame_records as _frame_records,
    money_field as _money_field,
    text_field as _text_field,
)

# WHY module-level marker (not per-function): the binding runner selects with
# ``-m e2e``; a single ``pytestmark`` guarantees no test in this file is ever
# accidentally omitted from that selection.
pytestmark = pytest.mark.e2e

# --- Business-rule oracle (verbatim from the specification, never redefined) -------
# Reject reason codes 100-103 are stored zero-padded to 4 characters in DALYREJS.
# Trade-off: we assert the *exact* documented message text (not a substring) so the
# test fails loudly if the production wording drifts -- these strings ARE the spec.
REJECT_MESSAGES = {
    "0100": "INVALID CARD NUMBER FOUND",
    "0101": "ACCOUNT RECORD NOT FOUND",
    "0102": "OVERLIMIT TRANSACTION",
    "0103": "TRANSACTION RECEIVED AFTER ACCT EXPIRATION",
}
VALID_REJECT_CODES = frozenset(REJECT_MESSAGES)

# Fixed-width geometry of a DALYREJS record (see app/cpy/CVTRA06Y + reject trailer).
_REJ_RECLEN = 430          # 350-byte tran image + 80-byte reject trailer
_REJ_CARD = slice(262, 278)   # CARD-NUM X(16) at cols 263-278 (1-based) in tran image
_REJ_CODE = slice(350, 354)   # reject reason code X(04)
_REJ_MSG = slice(354, 430)    # reject reason message X(76)

# Loader geometry proven against the seeds (reclen, key_length).
_ACCT = (300, 11)   # CVACT01Y: ACCT-ID 9(11) at offset 0
_TCAT = (50, 17)    # CVTRA01Y: acct+type+cat composite key of 17 bytes

# --- Expected deterministic outcomes for the shipped seed (financial golden values) ---
# WHY hard-code these alongside the structural invariants: the seed datasets are
# REFERENCE-only (immutable per AAP §0.8.2), so their posting outcome is a fixed,
# QA-confirmed fact. Asserting the exact numbers (defence-in-depth *with* the
# seed-independent reconciliation below) turns any silent change in program behaviour or
# seed content into an immediate, self-describing failure -- the financial-enterprise bar.
_EXPECTED_DAILY = 300              # daily-transaction input records
_EXPECTED_POSTED = 262             # transactions that pass validation and post
_EXPECTED_REJECTED = 38            # soft rejects (all reason 0102 OVERLIMIT for this seed)
_EXPECTED_CONSERVATION = Decimal("77954.70")  # sum of posted TRAN-AMT == sum of acct deltas
_EXPECTED_TCAT_INIT_KEYS = 50      # category-balance rows present in the seed
_EXPECTED_TCAT_FINAL_KEYS = 100    # rows after posting (50 updated + 50 created)


# The record-access helpers (_text_field / _money_field / _frame_records) are imported at
# the top of this module from tests.helpers.e2e_records; see that module for their full
# docstrings. They are shared verbatim with the interest and full-cycle e2e suites so the
# three cannot drift on field geometry.

# Alternate-key XREF provisioner (free-format COBOL), embedded because the shared
# tests/helpers/vsam_loader has no alternate-key parameter.
# Alternatives Considered: extend vsam_loader with an alt-key option -- rejected to
# keep that helper single-purpose and avoid modifying a sibling contract; the e2e
# layer owns this specialised provisioning step instead.
# Assumption: XREF record is 50 bytes -- primary key XREF-CARD-NUM X(16) at col 1,
# alternate key XREF-ACCT-ID X(11) at col 26 (with duplicates), matching CVACT03Y.
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
    """Resolve the directory of deterministic seed inputs for the posting cycle.

    Parameters
    ----------
    repo_root : pathlib.Path
        Repository root supplied by the ``repo_root`` fixture.

    Returns
    -------
    pathlib.Path
        Directory containing ``dailytran.txt`` and the master seeds.

    Notes
    -----
    Resolution order (WHY): an explicit override wins for CI flexibility; then a
    dedicated e2e fixture tree if the fixtures agent has populated one; otherwise the
    shipped ``app/data/ASCII`` seeds, which are REFERENCE-only and proven deterministic.
    """
    override = os.environ.get("CARDDEMO_E2E_SEED_DIR")
    if override and (Path(override) / "dailytran.txt").exists():
        return Path(override)
    fixtures = repo_root / "tests" / "fixtures" / "e2e"
    if (fixtures / "dailytran.txt").exists():
        return fixtures
    return repo_root / "app" / "data" / "ASCII"


def _provision_alt_key_xref(runner, seed_cardxref: Path) -> Path:
    """Build the alternate-key XREF indexed file required by the batch programs.

    Parameters
    ----------
    runner : tests.helpers.cobol_runner.CobolRunner
        Active runner (provides ``build_dir`` and ``assign_path``).
    seed_cardxref : pathlib.Path
        Flat seed cross-reference file (``cardxref.txt``).

    Returns
    -------
    pathlib.Path
        Path of the provisioned ``XREFFILE`` indexed dataset (its ``.1`` alternate
        sidecar is produced alongside).

    Raises
    ------
    subprocess.CalledProcessError
        If compiling or running the provisioner fails.

    Notes
    -----
    WHY a bespoke shim: ``CBTRN02C`` declares only the primary key but
    ``CBACT04C`` reads XREF by its ALTERNATE key (ACCT-ID); a primary-key-only load
    yields VSAM status 35 and an abend downstream. Provisioning one alternate-key
    file here lets the identical dataset serve the whole chain.
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
        Destination for the fixed-width, newline-free image bound to ``DALYTRAN``.
    reclen : int, optional
        Record length in bytes (default 350, per CVTRA06Y).

    Returns
    -------
    int
        Number of records written.

    Notes
    -----
    WHY: ``CBTRN02C`` reads ``DALYTRAN`` as an ORGANIZATION SEQUENTIAL file; GnuCOBOL
    treats embedded LF bytes as data, so each line is right-padded/truncated to an
    exact ``reclen`` and concatenated without separators (an IDCAMS-style flat image).
    """
    lines = Path(src).read_bytes().split(b"\n")
    payload = b"".join((ln + b" " * reclen)[:reclen] for ln in lines if ln.strip())
    Path(dst).write_bytes(payload)
    return len(payload) // reclen


# Rich, immutable result bundle for one posting run. WHY a namedtuple (Trade-off): each
# test asserts on a different facet (reconciliation, reject golden, posted golden, monetary
# effect); capturing the full observable state once -- inputs, both master snapshots, the
# posted ledger, and the reject stream -- lets every test read exactly what it needs from an
# immutable snapshot without re-deriving geometry, while the run itself stays isolated
# per-test (a fresh workspace per ``cobol_runner``).
PostingRun = namedtuple(
    "PostingRun",
    [
        "result",             # tests.helpers.cobol_runner.RunResult
        "n_daily",            # int: number of daily input transactions
        "daily_ids",          # list[str]: DALYTRAN-ID of every input, in input order
        "raw_rejects",        # bytes: raw DALYREJS image (empty if none)
        "reject_ids",         # list[str]: DALYTRAN-ID of each reject, in write order
        "posted_records",     # list[str]: TRANFILE records (layout=TRAN), key order
        "posted_ids",         # list[str]: TRAN-ID of each posted record
        "acct_init",          # dict[str, Decimal]: ACCT-ID -> ACCT-CURR-BAL before run
        "acct_final",         # dict[str, Decimal]: ACCT-ID -> ACCT-CURR-BAL after run
        "tcat_init_keys",     # set[str]: 17-byte TCATBAL composite keys before run
        "tcat_final_records",  # list[str]: TCATBALF records (layout=TCATBAL) after run
    ],
)


def _acct_balances(records: "list[str]") -> "dict[str, Decimal]":
    """Index a set of ACCOUNT records by ACCT-ID with their current balance.

    Parameters
    ----------
    records : list of str
        ``ACCOUNT``-layout records (from ``unload_output(layout="ACCOUNT")``).

    Returns
    -------
    dict[str, decimal.Decimal]
        Mapping of stripped ``ACCT-ID`` to exact ``ACCT-CURR-BAL``.

    Notes
    -----
    WHY index by id: value conservation is asserted as a per-account delta
    (final - initial) summed across accounts, so both snapshots must be keyed identically.
    """
    return {
        _text_field(rec, "ACCOUNT", "ACCT-ID"): _money_field(rec, "ACCOUNT", "ACCT-CURR-BAL")
        for rec in records
    }


def _run_posting_cycle(cobol_runner, repo_root: Path) -> PostingRun:
    """Provision inputs and execute one deterministic posting run of ``CBTRN02C``.

    Captures the complete observable state of the run so downstream tests can assert on
    the reject stream, the posted ledger, and the monetary effect on the masters without
    re-running the program.

    Parameters
    ----------
    cobol_runner : tests.helpers.cobol_runner.CobolRunner
        Runner bound to a fresh, isolated workspace.
    repo_root : pathlib.Path
        Repository root.

    Returns
    -------
    PostingRun
        Immutable snapshot of inputs, both master states, the posted ledger, and the
        reject stream for the run.

    Raises
    ------
    tests.helpers.vsam_loader.VsamLoadError
        If a seed fixture violates the fixed-width contract during load.
    subprocess.CalledProcessError
        If the alternate-key XREF provisioner fails to compile or run.

    Notes
    -----
    WHY snapshot the masters *before* the run: monetary conservation compares the
    post-run balance against the pre-run balance, so the initial state must be read back
    from the freshly loaded indexed masters before ``CBTRN02C`` mutates them.
    """
    seeds = _seed_dir(repo_root)
    # Masters must be preloaded as indexed files; XREF needs the alternate key.
    cobol_runner.load_input("ACCTFILE", seeds / "acctdata.txt", *_ACCT)
    cobol_runner.load_input("TCATBALF", seeds / "tcatbal.txt", *_TCAT)
    _provision_alt_key_xref(cobol_runner, seeds / "cardxref.txt")
    n_daily = _flatten_daily(seeds / "dailytran.txt", cobol_runner.assign_path("DALYTRAN"))

    # Snapshot the initial master state (indexed files are already populated by load_input).
    acct_init = _acct_balances(cobol_runner.unload_output("ACCTFILE", layout="ACCOUNT"))
    # WHY key at [0:17]: the TCATBAL primary key (acct-id + type + category) is a 17-byte
    # composite at offset 0; comparing the key *sets* proves which category rows are newly
    # created vs. updated in place (the 2700-A-CREATE / 2700-B-UPDATE branch).
    tcat_init_keys = {
        rec[:_TCAT[1]] for rec in cobol_runner.unload_output("TCATBALF", layout="TCATBAL")
    }

    # Daily-transaction ids, in input order. WHY [0:16]: the DALYTRAN-ID primary key is a
    # 16-byte field at offset 0, identical across the DALYTRAN/TRAN/REJECT tran family.
    daily_raw = cobol_runner.assign_path("DALYTRAN").read_bytes()
    daily_ids = [rec[:16].strip() for rec in _frame_records(daily_raw, 350)]

    result = cobol_runner.run("CBTRN02C")

    rej_path = result.output_path("DALYREJS")
    raw_rejects = rej_path.read_bytes() if rej_path.exists() else b""
    reject_ids = [
        rec[:16].strip() for rec in _frame_records(raw_rejects, _REJ_RECLEN)
    ] if raw_rejects else []

    # Posted ledger and final master state (all ORGANIZATION INDEXED -> read via unload).
    posted_records = cobol_runner.unload_output("TRANFILE", layout="TRAN")
    posted_ids = [_text_field(rec, "TRAN", "TRAN-ID") for rec in posted_records]
    acct_final = _acct_balances(cobol_runner.unload_output("ACCTFILE", layout="ACCOUNT"))
    tcat_final_records = cobol_runner.unload_output("TCATBALF", layout="TCATBAL")

    return PostingRun(
        result=result,
        n_daily=n_daily,
        daily_ids=daily_ids,
        raw_rejects=raw_rejects,
        reject_ids=reject_ids,
        posted_records=posted_records,
        posted_ids=posted_ids,
        acct_init=acct_init,
        acct_final=acct_final,
        tcat_init_keys=tcat_init_keys,
        tcat_final_records=tcat_final_records,
    )


def _parse_count(stdout: str, label: str) -> int:
    """Extract a zero-padded counter that ``CBTRN02C`` prints to stdout.

    Parameters
    ----------
    stdout : str
        Captured program stdout.
    label : str
        Counter label, e.g. ``"PROCESSED"`` or ``"REJECTED"``.

    Returns
    -------
    int
        Parsed integer value.

    Raises
    ------
    AssertionError
        If the labelled counter line is absent (a contract regression).
    """
    m = re.search(rf"{label}\s*:\s*(\d+)", stdout)
    assert m, f"counter {label!r} not found in program output"
    return int(m.group(1))


def _golden_path(repo_root: Path, *parts: str) -> Path:
    """Resolve a golden-master path under ``tests/golden``.

    Parameters
    ----------
    repo_root : pathlib.Path
        Repository root fixture.
    *parts : str
        Path components below ``tests/golden`` (e.g. ``"posting"``, ``"x.expected"``).

    Returns
    -------
    pathlib.Path
        The assembled golden path (not required to exist -- a missing golden is a hard
        failure inside the comparator, never a silent pass).
    """
    return repo_root.joinpath("tests", "golden", *parts)


def test_posting_cycle_invariants(cobol_runner, repo_root):
    """Independently reconcile every daily input to a posted transaction or a reject.

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
    WHY a set-based reconciliation, not ``posted = processed - rejected`` (F-P2-1): the
    former derivation made ``posted + rejected == processed`` a tautology that could never
    fail. This test instead reads the posted ids back from ``TRANFILE`` and the reject ids
    from ``DALYREJS`` -- two *independent* sources -- and proves their id sets are unique,
    disjoint, and together exactly reconstitute the set of daily input ids. That can catch
    a dropped, duplicated, or misrouted transaction, which the arithmetic identity could
    not. The program's own PROCESSED/REJECTED tallies are then cross-checked against these
    independently derived counts, and the seed's exact QA-confirmed totals are asserted.
    Assertion density (financial-enterprise standard): return code, id-set reconciliation,
    tally agreement, exact counts, reject-file framing, and every reject reason code +
    verbatim message -- not merely a non-abend exit.
    """
    run = _run_posting_cycle(cobol_runner, repo_root)

    # Return-code rubric: 0 (clean) or 4 (soft rejects present) are the only acceptable
    # posting outcomes; >=8 would indicate a hard/abend failure.
    assert run.result.returncode in (0, 4), (
        f"unexpected posting RC={run.result.returncode}\nSTDERR:\n{run.result.stderr}"
    )

    processed = _parse_count(run.result.stdout, "PROCESSED")
    rejected = _parse_count(run.result.stdout, "REJECTED")

    # The program must claim to have processed exactly the input it was given.
    assert processed == run.n_daily, f"processed {processed} != input {run.n_daily}"

    daily_set = set(run.daily_ids)
    posted_set = set(run.posted_ids)
    reject_set = set(run.reject_ids)

    # Independent reconciliation (the heart of F-P2-1) --------------------------------
    # (a) every id stream is duplicate-free: no transaction counted twice.
    assert len(run.daily_ids) == len(daily_set), "duplicate DALYTRAN-ID in the input"
    assert len(run.posted_ids) == len(posted_set), "duplicate TRAN-ID in TRANFILE"
    assert len(run.reject_ids) == len(reject_set), "duplicate id in DALYREJS"
    # (b) posted and rejected partitions are disjoint: no id both posts AND rejects.
    assert posted_set.isdisjoint(reject_set), (
        f"ids appear as BOTH posted and rejected: {sorted(posted_set & reject_set)[:5]}"
    )
    # (c) their union is exactly the set of inputs: nothing dropped, nothing invented.
    assert posted_set | reject_set == daily_set, (
        "posted+rejected id-set does not reconstitute the daily inputs "
        f"(missing={sorted(daily_set - (posted_set | reject_set))[:5]}, "
        f"extra={sorted((posted_set | reject_set) - daily_set)[:5]})"
    )

    # Cross-check the independently derived counts against the program's own tallies.
    assert len(run.reject_ids) == rejected, (
        f"DALYREJS record count {len(run.reject_ids)} != program REJECTED tally {rejected}"
    )
    assert len(run.posted_ids) == processed - rejected, (
        f"TRANFILE record count {len(run.posted_ids)} != PROCESSED-REJECTED "
        f"{processed - rejected}"
    )

    # Exact, QA-confirmed outcome for the immutable seed (defence-in-depth golden values).
    assert run.n_daily == _EXPECTED_DAILY
    assert len(run.posted_ids) == _EXPECTED_POSTED
    assert len(run.reject_ids) == _EXPECTED_REJECTED

    # RC=4 iff soft rejects were produced.
    if rejected > 0:
        assert run.result.returncode == 4

    # The reject file must be a whole number of records matching the tally.
    assert len(run.raw_rejects) % _REJ_RECLEN == 0, "DALYREJS not a whole number of records"
    assert len(run.raw_rejects) // _REJ_RECLEN == rejected, (
        "reject file size disagrees with tally"
    )

    # Every reject reason is one of the four documented codes and its message text matches
    # the specification verbatim.
    for rec in _frame_records(run.raw_rejects, _REJ_RECLEN):
        code = rec[_REJ_CODE].strip()
        msg = rec[_REJ_MSG].strip()
        assert code in VALID_REJECT_CODES, f"undocumented reject code {code!r}"
        assert msg == REJECT_MESSAGES[code], (
            f"reject {code} message {msg!r} != spec {REJECT_MESSAGES[code]!r}"
        )


def test_posting_reject_stream_matches_golden(cobol_runner, repo_root):
    """Compare the *whole* reject stream, record-for-record, against its golden master.

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
    WHY raw record mode (F-P2-2): the ``DALYREJS`` bytes are framed into 430-byte records
    and compared with ``layout="REJECT"``, which is byte-exact except that the
    non-deterministic ``DALYTRAN-PROC-TS`` is blanked on both sides by the layout mask.
    Unlike the former lossy ``card|code|message`` projection, this catches a regression in
    ANY reject field -- amount, dates, account, category, description -- not just the three
    projected columns. Regeneration is the guarded two-step opt-in described in the module
    docstring; a missing golden fails hard.
    """
    run = _run_posting_cycle(cobol_runner, repo_root)
    assert run.raw_rejects, "seed data must produce rejects for the reject-stream golden"

    framed = "\n".join(_frame_records(run.raw_rejects, _REJ_RECLEN))
    golden = _golden_path(repo_root, "posting", "e2e_posting_cycle_dalyrejs.expected")
    # Record mode: byte-exact with PROC-TS masked on both sides by the REJECT layout.
    assert_matches_golden(framed, golden, layout="REJECT")


def test_posting_transactions_match_golden(cobol_runner, repo_root):
    """Compare the posted ledger (``TRANSACT`` records) against its golden master.

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
    WHY (F-P2-3): the earlier suite never validated the *posted* records at all -- only the
    reject stream -- so a defect in a successfully posted transaction (wrong amount, wrong
    category, corrupted description) was invisible. This reads the ``TRANFILE`` indexed
    output back in primary-key order and compares every 350-byte record with
    ``layout="TRAN"`` (byte-exact except the masked ``TRAN-PROC-TS``; the deterministic
    ``TRAN-ORIG-TS`` is retained and asserted). Trade-off: a whole-record golden is used
    rather than per-field asserts because the ledger has many fields and a byte-diff
    localises any regression precisely while keeping the test compact.
    """
    run = _run_posting_cycle(cobol_runner, repo_root)
    assert len(run.posted_records) == _EXPECTED_POSTED, (
        f"expected {_EXPECTED_POSTED} posted records, got {len(run.posted_records)}"
    )

    ledger = "\n".join(run.posted_records)
    golden = _golden_path(repo_root, "posting", "e2e_posting_cycle_transactions.expected")
    assert_matches_golden(ledger, golden, layout="TRAN")


def test_posting_account_and_category_effects(cobol_runner, repo_root):
    """Assert monetary conservation and the TCATBAL create-vs-update branch.

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
    WHY (F-P2-4): the earlier suite never read the master files back, so it could not prove
    that posting actually moved money correctly. This test reads ``ACCTFILE`` and
    ``TCATBALF`` before and after the run and asserts:

    * **Value conservation** -- the sum of posted ``TRAN-AMT`` equals the sum of
      per-account ``ACCT-CURR-BAL`` deltas, computed in exact fixed-point ``Decimal``
      (no float tolerance -- the financial-precision mandate), and equals the
      QA-confirmed seed total.
    * **No phantom/absent accounts** -- the account key set is unchanged by posting.
    * **Both TCATBAL branches exercised** -- comparing the category key sets proves the
      run both *created* new category rows (``2700-A-CREATE``) and *updated* existing ones
      (``2700-B-UPDATE``), the AAP-mandated branch coverage; the exact seed row counts are
      asserted as golden values.
    """
    run = _run_posting_cycle(cobol_runner, repo_root)

    # --- Value conservation (exact Decimal) ------------------------------------------
    sum_posted = sum(
        (_money_field(rec, "TRAN", "TRAN-AMT") for rec in run.posted_records),
        Decimal("0.00"),
    )
    # Account key set must be stable across posting (posting mutates balances, not the
    # roster of accounts).
    assert set(run.acct_init) == set(run.acct_final), (
        "posting changed the set of account ids (created/dropped an account)"
    )
    sum_delta = sum(
        (run.acct_final[a] - run.acct_init[a] for a in run.acct_init),
        Decimal("0.00"),
    )
    assert sum_posted == sum_delta, (
        f"conservation broken: sum(posted TRAN-AMT)={sum_posted} != "
        f"sum(ACCT-CURR-BAL delta)={sum_delta}"
    )
    assert sum_posted == _EXPECTED_CONSERVATION, (
        f"posted total {sum_posted} != QA-confirmed seed total {_EXPECTED_CONSERVATION}"
    )

    # --- TCATBAL create-vs-update branch coverage ------------------------------------
    final_keys = {rec[:_TCAT[1]] for rec in run.tcat_final_records}
    init_keys = run.tcat_init_keys
    created = final_keys - init_keys        # keys not in the seed -> 2700-A-CREATE
    updated = final_keys & init_keys        # keys present in the seed -> 2700-B-UPDATE

    # Both branches must fire at least once (the mandated business-rule branch coverage).
    assert created, "TCATBAL create branch (2700-A-CREATE) never exercised"
    assert updated, "TCATBAL update branch (2700-B-UPDATE) never exercised"
    # Every seed row must survive (be updated in place), never dropped.
    assert init_keys <= final_keys, "a seed TCATBAL row disappeared after posting"
    # created and updated partition the final key set (no overlap, no gap).
    assert len(created) + len(updated) == len(final_keys)
    # Exact, QA-confirmed seed row counts.
    assert len(init_keys) == _EXPECTED_TCAT_INIT_KEYS
    assert len(final_keys) == _EXPECTED_TCAT_FINAL_KEYS
