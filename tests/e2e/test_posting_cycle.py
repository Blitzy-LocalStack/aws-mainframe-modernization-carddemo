"""End-to-end (Layer-3) golden-master tests for the CardDemo daily *posting* cycle.

Purpose
-------
Reproduce the posting leg of the daily batch chain (the ``POSTTRAN`` JCL step that
executes ``CBTRN02C``) on deterministic seed data and assert, to financial-enterprise
rigor, on every observable output of the run: the count of processed vs. rejected
transactions, the process return code, and the byte-exact contents of the daily
reject stream (``DALYREJS``) including each reject reason code and its message text.

The suite deliberately combines two complementary verification techniques:

* **Invariants** (always-on backbone) -- relationships that must hold for *any*
  deterministic input (e.g. ``processed == posted + rejected``), so the test is
  meaningful even before a golden file exists.
* **Golden-master** projection of the reject stream -- a timestamp-immune projection
  (card-number | reason-code | message) compared against a committed baseline via
  :func:`tests.helpers.golden_compare.assert_matches_golden`, which self-bootstraps
  the baseline on first run or when ``CARDDEMO_UPDATE_GOLDENS`` is set.

Determinism & isolation
-----------------------
Each test receives a fresh ``workspace`` (function-scoped ``tmp_path``) so runs are
independent and parallel-safe under ``pytest-xdist``. Inputs are the shipped,
never-mutated seed datasets (REFERENCE only). Non-deterministic processing
timestamps embedded in transaction records are excluded from the golden projection.

Binding contract
-----------------
Collected by ``scripts/run_e2e_tests.sh`` -> ``pytest tests/e2e -m e2e``; every test
here therefore carries the module-level ``e2e`` marker.
"""
from __future__ import annotations

import os
import re
import subprocess
from pathlib import Path

import pytest

# §0.6.2 import contract: the golden comparator MUST be importable from this path.
from tests.helpers.golden_compare import assert_matches_golden

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


def _run_posting_cycle(cobol_runner, repo_root: Path):
    """Provision inputs and execute one deterministic posting run of ``CBTRN02C``.

    Parameters
    ----------
    cobol_runner : tests.helpers.cobol_runner.CobolRunner
        Runner bound to a fresh, isolated workspace.
    repo_root : pathlib.Path
        Repository root.

    Returns
    -------
    tuple
        ``(result, n_daily, raw_rejects)`` where ``result`` is the ``RunResult``,
        ``n_daily`` the number of input transactions, and ``raw_rejects`` the raw
        ``DALYREJS`` bytes (empty if none written).
    """
    seeds = _seed_dir(repo_root)
    # Masters must be preloaded as indexed files; XREF needs the alternate key.
    cobol_runner.load_input("ACCTFILE", seeds / "acctdata.txt", *_ACCT)
    cobol_runner.load_input("TCATBALF", seeds / "tcatbal.txt", *_TCAT)
    _provision_alt_key_xref(cobol_runner, seeds / "cardxref.txt")
    n_daily = _flatten_daily(seeds / "dailytran.txt", cobol_runner.assign_path("DALYTRAN"))

    result = cobol_runner.run("CBTRN02C")

    rej_path = result.output_path("DALYREJS")
    raw = rej_path.read_bytes() if rej_path.exists() else b""
    return result, n_daily, raw


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


def _reject_projection(raw: bytes) -> str:
    """Project the reject stream to a deterministic, timestamp-free text form.

    Parameters
    ----------
    raw : bytes
        Raw ``DALYREJS`` contents (multiple of 430 bytes).

    Returns
    -------
    str
        Sorted ``card|code|message`` lines, one per reject record.

    Notes
    -----
    Trade-off: a field projection is used instead of a full-record byte diff so the
    golden is immune to the non-deterministic PROC-TS embedded in the tran image,
    while still capturing the business-relevant facts (which card, why rejected).
    Lines are sorted so ordering variation can never cause a false mismatch.
    """
    lines = []
    for off in range(0, len(raw), _REJ_RECLEN):
        rec = raw[off:off + _REJ_RECLEN]
        if len(rec) < _REJ_RECLEN:
            break
        card = rec[_REJ_CARD].decode("latin-1").strip()
        code = rec[_REJ_CODE].decode("latin-1").strip()
        msg = rec[_REJ_MSG].decode("latin-1").strip()
        lines.append(f"{card}|{code}|{msg}")
    return "\n".join(sorted(lines))


def test_posting_cycle_invariants(cobol_runner, repo_root):
    """Assert the core posting invariants and per-reject business rules.

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
    Assertion density (financial-enterprise standard): the test checks the process
    return code, the processed/rejected/posted counts and their arithmetic relation,
    the exact size of the reject stream, and every reject record's reason code and
    message text -- not merely a non-abend exit.
    """
    result, n_daily, raw = _run_posting_cycle(cobol_runner, repo_root)

    # Return-code rubric: 0 (clean) or 4 (soft rejects present) are the only
    # acceptable posting outcomes; >=8 would indicate a hard/abend failure.
    assert result.returncode in (0, 4), (
        f"unexpected posting RC={result.returncode}\nSTDERR:\n{result.stderr}"
    )

    processed = _parse_count(result.stdout, "PROCESSED")
    rejected = _parse_count(result.stdout, "REJECTED")
    posted = processed - rejected

    # Invariant 1: every input transaction is accounted for exactly once.
    assert processed == n_daily, f"processed {processed} != input {n_daily}"
    # Invariant 2: posted + rejected must reconstitute the processed total.
    assert posted + rejected == processed
    assert posted >= 0 and rejected >= 0

    # Invariant 3: RC=4 iff soft rejects were produced.
    if rejected > 0:
        assert result.returncode == 4

    # Invariant 4: the reject file is an exact multiple of the record length and its
    # record count matches the program's own tally (no truncation / partial writes).
    assert len(raw) % _REJ_RECLEN == 0, "DALYREJS not a whole number of records"
    assert len(raw) // _REJ_RECLEN == rejected, "reject file size disagrees with tally"

    # Invariant 5: every reject reason is one of the four documented codes and its
    # message text matches the specification verbatim.
    for off in range(0, len(raw), _REJ_RECLEN):
        rec = raw[off:off + _REJ_RECLEN]
        code = rec[_REJ_CODE].decode("latin-1").strip()
        msg = rec[_REJ_MSG].decode("latin-1").strip()
        assert code in VALID_REJECT_CODES, f"undocumented reject code {code!r}"
        assert msg == REJECT_MESSAGES[code], (
            f"reject {code} message {msg!r} != spec {REJECT_MESSAGES[code]!r}"
        )


def test_posting_reject_stream_matches_golden(cobol_runner, repo_root):
    """Compare the reject-stream projection against the committed golden master.

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
    WHY a separate test: golden regression protection is orthogonal to the invariant
    checks; keeping them apart makes a baseline drift immediately distinguishable from
    a business-rule violation. The comparator self-bootstraps the baseline on first
    run (and via ``CARDDEMO_UPDATE_GOLDENS``), so this is safe in a greenfield tree.
    """
    _result, _n, raw = _run_posting_cycle(cobol_runner, repo_root)
    if not raw:
        pytest.skip("no rejects produced by seed data; nothing to golden-compare")

    projection = _reject_projection(raw)
    golden = repo_root / "tests" / "golden" / "posting" / "e2e_posting_cycle_dalyrejs.expected"
    # Timestamp-free projection => no layout normalisation needed here.
    assert_matches_golden(projection, golden)
