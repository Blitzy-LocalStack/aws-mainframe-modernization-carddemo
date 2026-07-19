"""Layer-2 integration tests for the CardDemo batch posting program ``CBTRN02C``.

Purpose
-------
``CBTRN02C`` is the highest-risk **monetary** path in the whole suite (AAP
Sections 0.5.1 / 0.5.2 / 0.7): it posts each daily transaction to the account and
category-balance masters, or rejects it with one of four documented reason codes.
This module drives the *compiled, unmodified* program against the nine per-scenario
deterministic fixtures under ``tests/fixtures/posting/`` and asserts, to
financial-enterprise rigor, on **every observable output**:

* the process ``RETURN-CODE`` (``0`` posted / ``4`` at least one reject);
* the ``DALYREJS`` reject stream -- its reason code (100-103) and message text;
* the posted ``TRANSACT`` record; and
* the updated ``ACCTFILE`` / ``TCATBALF`` masters, asserted to **exact fixed-point**
  (``decimal.Decimal``) values that are *derived from the fixtures themselves*, never
  hard-coded.

**Production COBOL is REFERENCE only and is never modified by these tests.**

Test layer & binding contract
------------------------------
Marked ``@pytest.mark.integration``. Each test uses the shared ``cobol_runner``
fixture (``tests/conftest.py``) which binds every GnuCOBOL ``SELECT ... ASSIGN TO
<NAME>`` external name to a file inside a fresh, isolated per-test workspace. The
verified ``CBTRN02C`` file contract is::

    ASSIGN     role          organization         open   reclen  key(len@off)
    DALYTRAN   input          SEQUENTIAL(fixed)   INPUT   350    -- (no key)
    XREFFILE   card lookup    INDEXED             INPUT    50    16 @ 0 (card-num)
    ACCTFILE   master (I-O)   INDEXED             I-O     300    11 @ 0 (acct-id)
    TCATBALF   category bal   INDEXED             I-O      50    17 @ 0 (acct+type+cat)
    TRANFILE   posted out     INDEXED             OUTPUT  350    16 @ 0 (tran-id)
    DALYREJS   reject out     SEQUENTIAL          OUTPUT  430    -- (raw fixed)

The 17-byte ``TCATBALF`` key (``TRANCAT-ACCT-ID 9(11) + TRANCAT-TYPE-CD X(02) +
TRANCAT-CD 9(04)``) is a verified, corrected fact -- it is **not** 16.

Fixture-format laws (empirically proven; violating them abends CBTRN02C)
------------------------------------------------------------------------
1. **DALYTRAN is ``ORGANIZATION IS SEQUENTIAL`` fixed 350, with NO record
   delimiter.** The shipped fixtures carry a single trailing newline (``351`` bytes
   for one ``350``-byte record); a stray ``\\n`` presented to the program is read as
   a spurious partial record (FILE STATUS 04 -> ``ERROR READING DALYTRAN FILE`` ->
   abend / phantom reject). We therefore stage DALYTRAN through
   ``CobolRunner.load_sequential(..., reclen=350)``, which strips exactly one
   trailing terminator per row and writes a headerless ``N*350`` blob.
2. **INDEXED inputs must be loaded with the EXACT key length** (16 / 11 / 17); a
   wrong key length yields FILE STATUS 39 on OPEN -> abend. We load them through
   ``CobolRunner.load_input(...)`` with the single-sourced ``record_codec`` geometry.
3. **DALYREJS output is raw fixed 430-byte records with NO newline.** Each record is
   the verbatim ``350``-byte daily-transaction image followed by an ``80``-byte
   trailer ``{ WS-VALIDATION-FAIL-REASON 9(04) @ [350:354] + DESC X(76) @ [354:430] }``.
   We read the raw bytes and frame them into ``430``-byte records, decoded through
   ``record_codec.REJECT_LAYOUT``.

Validation semantics (transcribed verbatim from app/cbl/CBTRN02C.cbl)
---------------------------------------------------------------------
* reject **100** -- card absent from ``XREFFILE`` (INVALID KEY) -> ``INVALID CARD
  NUMBER FOUND``.
* reject **101** -- resolved account absent from ``ACCTFILE`` (INVALID KEY) ->
  ``ACCOUNT RECORD NOT FOUND``.
* ``WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT - ACCT-CURR-CYC-DEBIT + DALYTRAN-AMT``.
* reject **102** -- when ``ACCT-CREDIT-LIMIT >= WS-TEMP-BAL`` is **FALSE** ->
  ``OVERLIMIT TRANSACTION``. Because the test is ``>=``, a ``WS-TEMP-BAL`` *exactly
  equal* to the credit limit **POSTS** (this module proves that boundary).
* reject **103** -- when ``ACCT-EXPIRAION-DATE >= DALYTRAN-ORIG-TS(1:10)`` is
  **FALSE** -> ``TRANSACTION RECEIVED AFTER ACCT EXPIRATION``. Because the test is
  ``>=``, a transaction date *exactly equal* to the expiration date **POSTS**.
* on post: ``2700-UPDATE-TCATBAL`` **creates** (``2700-A`` WRITE, balance = amount)
  or **updates** (``2700-B`` REWRITE, balance += amount) the category row;
  ``2800-UPDATE-ACCOUNT-REC`` does ``ACCT-CURR-BAL += amount`` and adds the amount to
  ``ACCT-CURR-CYC-CREDIT`` (amount >= 0) or ``ACCT-CURR-CYC-DEBIT`` (amount < 0).
* ``RETURN-CODE = 4`` iff ``WS-REJECT-COUNT > 0``, else ``0``.

Determinism & isolation
------------------------
Each test provisions and reads only its own workspace, so tests are independent and
parallel-safe. Posting decisions are entirely data-driven (the originating date comes
from the fixture's ``DALYTRAN-ORIG-TS``), so assertions never depend on the wall
clock: we assert on balances, reason codes, and the return code -- all fully
deterministic -- and never on the runtime-stamped ``TRAN-PROC-TS``.

Explainability (AAP Section 0.10.1 -- hard review gate)
-------------------------------------------------------
Every function carries a Purpose/Parameters/Returns/Raises docstring, and each
non-obvious decision is annotated with a WHY comment (Assumption / Trade-off /
Alternatives Considered / Refactoring Rationale). All money is handled as
``decimal.Decimal`` -- never ``float`` -- because a binary float cannot represent a
value such as ``0.10`` exactly and would silently corrupt a monetary assertion.

There is **no** ``__init__.py`` anywhere under ``tests/``; the tree resolves as a PEP
420 namespace package via ``PYTHONPATH=<repo_root>`` (the conftest also bootstraps it).
"""

from __future__ import annotations

import re

# WHY (QA Issue 6 / F-D -- observable abend path): the CEE3ABD shim builder below
# needs the standard build primitives -- ``shutil.which`` to locate ``cobc``,
# ``subprocess.run`` to compile the shim, ``tempfile.mkdtemp`` for a private
# scratch dir, and ``os.replace`` for an atomic same-filesystem publish. These are
# imported at module top (not lazily) because the builder is a first-class helper
# of this module, mirroring the committed CEEDAYS-shim pattern in
# ``tests/integration/test_csutldtc_date.py``.
import os
import shutil
import subprocess
import tempfile
from dataclasses import dataclass
from decimal import Decimal
from pathlib import Path

import pytest

# Import ONLY the single-sourced record layouts this module decodes with. Every symbol
# is used (see the assertions below); the layouts carry the copybook geometry so this
# test never transcribes an offset/width itself.
# WHY (Alternatives Considered): we import the layout constants rather than the lower
# level ``decode_zoned`` helper because ``RecordLayout.decode`` already routes every
# money field through ``decode_zoned`` and returns a typed mapping -- decoding through
# the layout keeps the field names (and thus the contract) explicit and single-sourced.
from tests.helpers.record_codec import (
    ACCOUNT_LAYOUT,
    DALYTRAN_LAYOUT,
    REJECT_LAYOUT,
    TCATBAL_LAYOUT,
    TRAN_LAYOUT,
)

# WHY (QA Issue 5 / F-B -- committed goldens must be the authoritative oracle): the
# fixture-derived Decimal assertions below prove the posting math and reject reasons
# independently, but the checkpoint/AAP (0.7.2) also requires the committed
# tests/golden/posting/**/*.expected files to be *consumed* so any whole-record drift
# (the QA Issue 2 filler-byte regression) is caught. assert_matches_golden is the suite's
# AAP-designated byte-exact comparator (tests/helpers/golden_compare.py); a missing golden
# is a hard error, never a silent pass. WHY both oracles coexist (Trade-off): the Decimal
# model localises WHICH balance/reason is wrong, while the golden pins EVERY observable
# output byte-for-byte (all five files: tranfile, acctdat, tcatbal, dalyrejs, return_code).
from tests.helpers.golden_compare import assert_matches_golden

# WHY (QA Issue 6 / F-D -- consistent required-layer gating): the CEE3ABD shim
# builder reuses the suite's single strict-awareness gate so a *missing* toolchain
# (``cobc`` off PATH) is graded identically everywhere -- a clean skip by default,
# a hard failure under ``CARDDEMO_REQUIRE_COBOL`` (the same policy Issue 4 / F-C
# established for provisioning's ``_ensure_cobdatft``). A genuine *compile failure*
# of the shim source (which this module fully controls) is graded separately as a
# hard failure -- see ``_ensure_cee3abd``.
from tests.conftest import _require_or_skip

# WHY (suite contract): the integration marker is registered in tests/pytest.ini with
# --strict-markers, so a bare/mistyped marker is a hard error rather than a silent
# no-op. Marking at module scope tags every test here as an integration test.
pytestmark = pytest.mark.integration

# ---------------------------------------------------------------------------
# Program & domain constants (single source of truth for this module).
# ---------------------------------------------------------------------------
# The compiled program under test and the fixture domain directory it is driven from.
_PROGRAM = "CBTRN02C"
_DOMAIN = "posting"

# The two stdout counters CBTRN02C DISPLAYs at end of run (verified against the source:
# ``DISPLAY 'TRANSACTIONS PROCESSED :' WS-TRANSACTION-COUNT`` and the REJECTED twin).
# They are a robust cross-check that is independent of the on-disk output files.
_COUNTER_PROCESSED = "TRANSACTIONS PROCESSED"
_COUNTER_REJECTED = "TRANSACTIONS REJECTED"

# The exact reject-reason description text each reason code writes to DALYREJS,
# transcribed verbatim from app/cbl/CBTRN02C.cbl (1500-A / 1500-B). These are the
# specification: the tests encode them, they do NOT redefine them.
_REJECT_DESCRIPTIONS = {
    100: "INVALID CARD NUMBER FOUND",
    101: "ACCOUNT RECORD NOT FOUND",
    102: "OVERLIMIT TRANSACTION",
    103: "TRANSACTION RECEIVED AFTER ACCT EXPIRATION",
}


@dataclass(frozen=True)
class PostingExpectation:
    """The expected outcome contract for one posting scenario.

    Purpose
    -------
    Bind a fixture scenario directory name to its financial-grade expected result so
    the scenario table is a single, reviewable declaration of what each fixture must
    prove. Frozen because it is a constant contract -- a test must never mutate a
    shared expectation.

    Parameters
    ----------
    scenario : str
        The fixture sub-directory under ``tests/fixtures/posting/`` (e.g.
        ``"reject_102_overlimit"``).
    return_code : int
        The exact process ``RETURN-CODE`` expected (``0`` posted, ``4`` reject(s)).
    min_rejected : int
        The minimum value of the ``TRANSACTIONS REJECTED`` counter (``0`` for a clean
        post, ``>= 1`` for a reject scenario).
    reason : int or None
        The expected ``WS-VALIDATION-FAIL-REASON`` code (100-103) for a reject
        scenario, or ``None`` when nothing is rejected.
    posts : bool
        ``True`` when the scenario is expected to post at least one transaction (so the
        masters change and ``TRANSACT`` gains a record); ``False`` for a pure reject or
        an empty input.

    Returns
    -------
    PostingExpectation
        A new immutable expectation record.

    Raises
    ------
    None
    """

    scenario: str
    return_code: int
    min_rejected: int
    reason: "int | None"
    posts: bool


# The nine-row scenario -> expectation contract (AAP Section 0.5.2). Keyed by scenario
# name so a test can look up its own expectation and so the table reads as the
# specification it encodes. ``reason`` is cross-checked against ``_REJECT_DESCRIPTIONS``
# for the descriptive text.
SCENARIOS: "dict[str, PostingExpectation]" = {
    "happy_path": PostingExpectation("happy_path", 0, 0, None, True),
    "reject_100_card_missing": PostingExpectation(
        "reject_100_card_missing", 4, 1, 100, False
    ),
    "reject_101_acct_missing": PostingExpectation(
        "reject_101_acct_missing", 4, 1, 101, False
    ),
    "reject_102_overlimit": PostingExpectation(
        "reject_102_overlimit", 4, 1, 102, False
    ),
    "reject_103_expired": PostingExpectation("reject_103_expired", 4, 1, 103, False),
    "boundary_exact_limit": PostingExpectation("boundary_exact_limit", 0, 0, None, True),
    "boundary_expiry_equal": PostingExpectation(
        "boundary_expiry_equal", 0, 0, None, True
    ),
    "empty_input": PostingExpectation("empty_input", 0, 0, None, False),
    "zero_balance": PostingExpectation("zero_balance", 0, 0, None, True),
}


# ---------------------------------------------------------------------------
# Fixture resolution helpers.
# ---------------------------------------------------------------------------
def _posting_fixture_dir(repo_root: Path, scenario: str) -> Path:
    """Return the fixture directory for a posting scenario, or skip if absent.

    Purpose
    -------
    Locate ``tests/fixtures/posting/<scenario>/`` relative to the repository root and
    skip the requesting test cleanly when the fixtures have not been provisioned (a
    partial checkout), rather than failing.

    Parameters
    ----------
    repo_root : pathlib.Path
        The repository root (from the ``repo_root`` fixture).
    scenario : str
        The scenario sub-directory name (e.g. ``"happy_path"``).

    Returns
    -------
    pathlib.Path
        The existing scenario fixture directory.

    Raises
    ------
    Skipped
        (via :func:`pytest.skip`) if the directory does not exist -- the fixtures are
        produced by a sibling agent, so their absence is an environment condition, not
        a defect in this test.
    """
    path = repo_root / "tests" / "fixtures" / _DOMAIN / scenario
    if not path.is_dir():
        # WHY (Assumption): fixtures are a separate artifact; treat "not yet present"
        # as skip (green collection everywhere) instead of a hard failure.
        pytest.skip(f"posting fixtures not found for scenario {scenario!r}: {path}")
    return path


def _resolve_fixture(scenario_dir: Path, *candidates: str) -> Path:
    """Return the first existing fixture file among candidate names, or skip.

    Purpose
    -------
    Resolve a logical input (daily-transaction / account / xref / category-balance) to
    a concrete file without hard-coding a single filename, because the sibling fixtures
    agent chooses the on-disk names. Candidates are tried in preference order.

    Parameters
    ----------
    scenario_dir : pathlib.Path
        The scenario fixture directory (from :func:`_posting_fixture_dir`).
    *candidates : str
        One or more candidate file names, most-preferred first (e.g.
        ``"dailytran.txt", "DALYTRAN.txt"``).

    Returns
    -------
    pathlib.Path
        The first candidate that exists as a file.

    Raises
    ------
    Skipped
        (via :func:`pytest.skip`) if none of the candidates exists -- a missing input
        fixture means the scenario cannot be driven, which is skipped, not failed.
    """
    # WHY (Trade-off): a resilient, ordered candidate search decouples this test from
    # the fixture agent's exact filenames while still preferring the documented
    # seed-style names; the alternative (a single hard-coded name) would make the whole
    # suite brittle to a cosmetic rename.
    for name in candidates:
        candidate = scenario_dir / name
        if candidate.is_file():
            return candidate
    pretty = ", ".join(candidates)
    pytest.skip(
        f"no fixture found in {scenario_dir} among candidates: {pretty}"
    )


def _read_fixture_records(path: Path, reclen: int) -> "list[str]":
    """Read a flat fixed-width fixture into a list of ``reclen``-character records.

    Purpose
    -------
    Decode the *original* input fixtures (before the run) so expected monetary results
    can be derived from them. Handles both the shipped form (one record + a single
    trailing newline) and a raw ``N*reclen`` blob or a multi-line file.

    Parameters
    ----------
    path : pathlib.Path
        The flat fixture file to read.
    reclen : int
        The fixed record width in characters.

    Returns
    -------
    list[str]
        Zero or more records, each normalised to exactly ``reclen`` characters. A
        genuinely empty (or newline-only) file yields ``[]``.

    Raises
    ------
    OSError
        If the file cannot be read.
    """
    # WHY encoding="latin-1" (Assumption): CardDemo records are fixed-width bytes with
    # zoned-decimal overpunch that are frequently not valid UTF-8; latin-1 is the
    # identity byte<->codepoint map so every byte round-trips and slicing by offset is
    # exact.
    raw = path.read_text(encoding="latin-1").replace("\r\n", "\n").replace("\r", "\n")
    if raw in ("", "\n"):
        return []
    # Drop exactly one trailing EOF newline (the shipped fixtures carry one); a newline
    # anywhere else marks a genuinely multi-line fixture handled below.
    if raw.endswith("\n"):
        raw = raw[:-1]

    if "\n" in raw:
        rows = raw.split("\n")
    elif len(raw) % reclen == 0 and len(raw) != reclen:
        # WHY (Trade-off): a delimiter-free file whose length is an exact multiple of
        # reclen (and longer than one record) is a concatenated N*reclen blob; frame it
        # by width. A file of exactly reclen is the common single-record case (else).
        rows = [raw[i : i + reclen] for i in range(0, len(raw), reclen)]
    else:
        rows = [raw]

    # Normalise each row to exactly reclen. WHY (Trade-off): this reader feeds only the
    # expected-value derivation (never the program input, which is staged byte-exactly
    # by the runner helpers), so right-padding a short final row / truncating an
    # over-long one keeps decoding robust without affecting what the program sees.
    normalised: "list[str]" = []
    for row in rows:
        if len(row) < reclen:
            row = row.ljust(reclen)
        elif len(row) > reclen:
            row = row[:reclen]
        normalised.append(row)
    return normalised


def _parse_counter(stdout: str, label: str) -> int:
    """Extract an integer end-of-run counter that ``CBTRN02C`` prints to stdout.

    Purpose
    -------
    Read one of the program's ``DISPLAY``ed counters (``TRANSACTIONS PROCESSED`` /
    ``TRANSACTIONS REJECTED``) as an ``int``, giving a file-independent cross-check of
    how many transactions were read and how many were rejected.

    Parameters
    ----------
    stdout : str
        The captured standard output of the run.
    label : str
        The counter label WITHOUT its trailing colon (e.g. ``"TRANSACTIONS REJECTED"``).

    Returns
    -------
    int
        The counter value (leading zeros stripped).

    Raises
    ------
    AssertionError
        If the labelled counter is not present in ``stdout`` -- a missing counter means
        the program did not run to normal completion, which the caller must see.
    """
    # WHY (Refactoring Rationale): match ``label : NNNNNNNNN`` tolerantly -- the source
    # prints the label, then a colon, then a zoned 9(09) value; ``\s*`` around the colon
    # and ``0*`` before the capture absorb the alignment spaces and leading zeros so the
    # regex reads the same whether the count is 0 or 999999999.
    match = re.search(re.escape(label) + r"\s*:\s*0*(\d+)", stdout)
    assert match is not None, (
        f"counter {label!r} not found in program stdout; the run may have abended.\n"
        f"stdout was:\n{stdout}"
    )
    return int(match.group(1))


def _read_rejects(result: object) -> "list[str]":
    """Read the ``DALYREJS`` reject stream into a list of 430-character records.

    Purpose
    -------
    Frame the raw, delimiter-free ``DALYREJS`` output into its fixed 430-byte records so
    each can be decoded through :data:`REJECT_LAYOUT`.

    Parameters
    ----------
    result : object
        The :class:`RunResult` returned by ``cobol_runner.run`` (duck-typed to avoid an
        import of the helper's class; only ``output_path`` is used).

    Returns
    -------
    list[str]
        Each element is one 430-character reject record (latin-1 decoded). An
        unproduced or empty file yields ``[]``.

    Raises
    ------
    AssertionError
        If the file's byte length is not a clean multiple of the 430-byte record width
        (after tolerating a single trailing newline) -- a framing mismatch would
        silently mis-decode every reason code, so it must fail loudly.
    """
    reclen = REJECT_LAYOUT.reclen  # 430, single-sourced from the codec
    path = result.output_path("DALYREJS")
    if not path.exists():
        return []
    data = path.read_bytes()
    if not data:
        return []
    # WHY (Assumption): CBTRN02C writes ORGANIZATION SEQUENTIAL fixed records with no
    # delimiter, but be defensive about a single trailing newline an environment might
    # append; strip exactly one if it makes the length a clean multiple.
    if len(data) % reclen != 0 and data.endswith(b"\n"):
        data = data[:-1]
    # WHY (defensive framing): also tolerate per-record newline framing (each 430 + \n);
    # if the total divides evenly by 431, strip the terminator from each record.
    if len(data) % reclen != 0 and len(data) % (reclen + 1) == 0:
        return [
            data[i : i + reclen].decode("latin-1")
            for i in range(0, len(data), reclen + 1)
        ]
    assert len(data) % reclen == 0, (
        f"DALYREJS length {len(data)} is not a multiple of the {reclen}-byte reject "
        f"record width; the reject stream framing is wrong."
    )
    return [
        data[i : i + reclen].decode("latin-1") for i in range(0, len(data), reclen)
    ]


def _decode_reject(record: str) -> "tuple[int, str]":
    """Decode one 430-character ``DALYREJS`` record into ``(reason, description)``.

    Purpose
    -------
    Extract the validation-failure reason code and its message text from a reject
    record via the single-sourced :data:`REJECT_LAYOUT`, so the assertion reads the
    same field names the program writes.

    Parameters
    ----------
    record : str
        A single 430-character reject record from :func:`_read_rejects`.

    Returns
    -------
    tuple[int, str]
        ``(reason_code, description)`` where ``reason_code`` is the ``9(04)``
        ``WS-VALIDATION-FAIL-REASON`` as an ``int`` and ``description`` is the trailing
        ``X(76)`` text right-stripped of its blank padding.

    Raises
    ------
    tests.helpers.record_codec.RecordLengthError
        If ``record`` is not exactly 430 characters (propagated from the layout decode).
    """
    fields = REJECT_LAYOUT.decode(record)
    reason = fields["WS-VALIDATION-FAIL-REASON"]  # decoded as int by the layout
    # WHY (Trade-off): right-strip only the description's blank pad so a substring
    # assertion is stable; we do not touch interior characters, keeping the exact
    # message text intact for the comparison.
    description = fields["WS-VALIDATION-FAIL-REASON-DESC"].rstrip()
    return reason, description


def _category_key(acct_id: int, tran: "dict[str, object]") -> str:
    """Build the 17-character ``TCATBALF`` key for a daily transaction.

    Purpose
    -------
    Reproduce the composite category-balance key CBTRN02C forms in
    ``2700-UPDATE-TCATBAL`` (``TRANCAT-ACCT-ID 9(11) + TRANCAT-TYPE-CD X(02) +
    TRANCAT-CD 9(04)``) so a test can locate the exact category row the transaction
    posts to.

    Parameters
    ----------
    acct_id : int
        The account id the card resolves to (the account whose master is updated).
    tran : dict[str, object]
        A decoded ``DALYTRAN`` record (from ``DALYTRAN_LAYOUT.decode``); its
        ``DALYTRAN-TYPE-CD`` and ``DALYTRAN-CAT-CD`` fields form the trailing key parts.

    Returns
    -------
    str
        The 17-character composite key, zero-padded exactly as the numeric COBOL
        sub-fields are stored on the indexed file.

    Raises
    ------
    KeyError
        If ``tran`` is missing a required field (a programming error in the caller).
    """
    # WHY (Assumption): the numeric key parts are USAGE DISPLAY zoned fields stored as
    # zero-padded ASCII digits (acct-id 11 wide, cat-cd 4 wide), and the 2-char type
    # code is stored verbatim; reproducing that exact padding is what makes the key
    # match the on-file bytes CBTRN02C keyed the record under.
    type_cd = str(tran["DALYTRAN-TYPE-CD"])
    cat_cd = int(tran["DALYTRAN-CAT-CD"])
    return f"{acct_id:011d}{type_cd}{cat_cd:04d}"


def _find_record(records: "list[str]", layout: object, key: str) -> "dict[str, object] | None":
    """Return the decoded record whose primary key matches ``key``, or ``None``.

    Purpose
    -------
    Locate a specific record within a read-back indexed file (a list of fixed-width
    record strings) by its primary key, so a test asserts on exactly the record the
    transaction touched even if the file holds several.

    Parameters
    ----------
    records : list[str]
        Fixed-width record strings (e.g. from ``cobol_runner.unload_output``).
    layout : object
        The :class:`RecordLayout` used to compute each record's key and to decode the
        match (e.g. :data:`ACCOUNT_LAYOUT`).
    key : str
        The primary key to match (exact string comparison against ``layout.key_of``).

    Returns
    -------
    dict[str, object] or None
        The decoded field mapping of the matching record, or ``None`` if no record has
        that key.

    Raises
    ------
    tests.helpers.record_codec.RecordLengthError
        If a record is not the layout's ``reclen`` (propagated from the layout).
    """
    # WHY (Trade-off): key-match rather than assume a single-record file -- the fixtures
    # happen to hold one row today, but matching by key keeps the assertion correct if a
    # scenario ever seeds several accounts/categories, and localises the assertion to
    # the row under test.
    for raw in records:
        if layout.key_of(raw) == key:
            return layout.decode(raw)
    return None


def _read_posted(cobol_runner: object) -> "list[dict[str, object]]":
    """Read the posted transactions back from ``TRANFILE``, tolerating an absent file.

    Purpose
    -------
    Return the decoded records CBTRN02C wrote to the ``TRANSACT`` output so a test can
    assert what posted (or that nothing did). ``TRANFILE`` is opened ``OUTPUT`` and
    auto-created by the program, but a reject-only or empty-input run writes no records
    to it; whether the ISAM backend then leaves an empty physical file on disk is
    implementation-defined, so a missing file is treated as "nothing posted".

    Parameters
    ----------
    cobol_runner : object
        The ``CobolRunner`` from the ``cobol_runner`` fixture (only ``assign_path`` and
        ``unload_output`` are used).

    Returns
    -------
    list[dict[str, object]]
        The decoded ``TRAN`` records in primary-key order; ``[]`` when the file is
        absent or empty.

    Raises
    ------
    tests.helpers.vsam_loader.VsamLoadError
        If the file exists but cannot be unloaded (bad geometry / corrupt file) --
        propagated so a genuine read failure is not silently masked.
    """
    # WHY guard on existence (Trade-off vs. a blanket try/except): unload_output raises
    # VsamLoadError for a *missing* file, but we only want to swallow the "never created"
    # case -- a present-but-corrupt file must still raise. Checking the path first draws
    # that line precisely, so a real unload failure is never hidden.
    if not cobol_runner.assign_path("TRANFILE").exists():
        return []
    raw_records = cobol_runner.unload_output("TRANFILE", layout="TRAN")
    return [TRAN_LAYOUT.decode(rec) for rec in raw_records]


@dataclass(frozen=True)
class StagedRun:
    """The result of staging a scenario and running ``CBTRN02C``, plus its inputs.

    Purpose
    -------
    Bundle the :class:`RunResult` with the concrete paths of the four input fixtures so
    a caller can both assert on the run outcome and re-read the *original* input records
    to derive expected monetary results -- without resolving the fixtures a second time.

    Parameters
    ----------
    result : object
        The :class:`RunResult` from ``cobol_runner.run``.
    dailytran : pathlib.Path
        The resolved ``DALYTRAN`` daily-transaction fixture.
    cardxref : pathlib.Path
        The resolved ``XREFFILE`` card cross-reference fixture.
    acctdata : pathlib.Path
        The resolved ``ACCTFILE`` account-master fixture.
    tcatbal : pathlib.Path
        The resolved ``TCATBALF`` category-balance fixture.

    Returns
    -------
    StagedRun
        A new immutable staged-run record.

    Raises
    ------
    None
    """

    result: object
    dailytran: Path
    cardxref: Path
    acctdata: Path
    tcatbal: Path


def _stage_inputs_and_run(cobol_runner: object, repo_root: Path, scenario: str) -> StagedRun:
    """Stage the four posting inputs into the workspace and run ``CBTRN02C``.

    Purpose
    -------
    Perform the common per-scenario setup exactly once: resolve the four fixtures, load
    the three INDEXED inputs with their exact key geometry, stage the SEQUENTIAL
    ``DALYTRAN`` input byte-exactly (no trailing newline), execute the compiled program,
    and return the result together with the resolved input paths.

    Parameters
    ----------
    cobol_runner : object
        The ``CobolRunner`` from the ``cobol_runner`` fixture.
    repo_root : pathlib.Path
        The repository root (from the ``repo_root`` fixture).
    scenario : str
        The scenario name whose fixtures drive the run.

    Returns
    -------
    StagedRun
        The run result and the resolved fixture paths.

    Raises
    ------
    Skipped
        (via :func:`pytest.skip`) if the scenario directory or any required fixture is
        missing (propagated from the resolver helpers).
    tests.helpers.cobol_runner.CobolRunError
        If the program times out (propagated from ``cobol_runner.run``).
    tests.helpers.vsam_loader.VsamLoadError
        If an input fixture cannot be loaded into an indexed file.
    """
    scenario_dir = _posting_fixture_dir(repo_root, scenario)
    dailytran = _resolve_fixture(
        scenario_dir, "dailytran.txt", "DALYTRAN.txt", "DALYTRAN", "dailytran"
    )
    cardxref = _resolve_fixture(
        scenario_dir, "cardxref.txt", "XREFFILE.txt", "cardxref", "xref.txt"
    )
    acctdata = _resolve_fixture(
        scenario_dir, "acctdata.txt", "ACCTFILE.txt", "acctdat.txt", "acctdata"
    )
    tcatbal = _resolve_fixture(
        scenario_dir, "tcatbal.txt", "TCATBALF.txt", "tcatbalf.txt", "tcatbal"
    )

    # INDEXED inputs. WHY (fixture-format law #2): each must be loaded with its exact key
    # length or GnuCOBOL raises FILE STATUS 39 on OPEN. We pass layout= so the geometry
    # is single-sourced from record_codec (ACCOUNT 300/11, TCATBAL 50/17).
    # WHY alternate_keys=() on XREFFILE (Assumption): CBTRN02C's SELECT for XREF-FILE
    # declares only the primary card-number key -- no ALTERNATE. The XREF layout DOES
    # model an account-id alternate index (CBACT04C needs it), so we explicitly force a
    # primary-key-only file here; building the alternate index would make the physical
    # key set disagree with this program's SELECT and risk FILE STATUS 39 on OPEN.
    cobol_runner.load_input("XREFFILE", cardxref, layout="XREF", alternate_keys=())
    cobol_runner.load_input("ACCTFILE", acctdata, layout="ACCOUNT")
    cobol_runner.load_input("TCATBALF", tcatbal, layout="TCATBAL")

    # SEQUENTIAL input. WHY (fixture-format law #1): load_sequential strips exactly one
    # trailing terminator per row and writes a headerless N*350 blob, so a shipped
    # fixture's trailing newline can never be read as a spurious partial record.
    cobol_runner.load_sequential("DALYTRAN", dailytran, reclen=DALYTRAN_LAYOUT.reclen)

    # Completion-aware execution (subprocess.run blocks to exit); check=False so a soft
    # reject (RC=4) is returned for inspection rather than raised.
    result = cobol_runner.run(_PROGRAM)
    return StagedRun(result, dailytran, cardxref, acctdata, tcatbal)


# ---------------------------------------------------------------------------
# Outcome helpers: derive expected results from the fixtures and verify.
# ---------------------------------------------------------------------------
@dataclass(frozen=True)
class PostOutcome:
    """Everything needed to assert a *posting* scenario, derived from the fixtures.

    Purpose
    -------
    Capture the fully-computed expected-vs-actual state of a scenario that posts, so a
    test body can make each assertion explicitly (and localise a failure) without
    re-deriving values. Every monetary attribute is a :class:`decimal.Decimal`.

    Parameters
    ----------
    result : object
        The :class:`RunResult` of the run.
    processed : int
        The ``TRANSACTIONS PROCESSED`` counter parsed from stdout.
    rejected : int
        The ``TRANSACTIONS REJECTED`` counter parsed from stdout.
    rejects : list[str]
        The framed ``DALYREJS`` records (expected empty for a clean post).
    tran : dict[str, object]
        The decoded originating ``DALYTRAN`` record.
    amount : decimal.Decimal
        ``DALYTRAN-AMT`` -- the exact signed amount being posted.
    orig_account : dict[str, object]
        The decoded original account master record (pre-run).
    new_account : dict[str, object] or None
        The decoded updated account master record read back after the run (``None`` if
        the account key was not found in the output).
    expected_balance : decimal.Decimal
        ``orig ACCT-CURR-BAL + amount`` -- the exact expected post-run balance.
    orig_cat_balance : decimal.Decimal or None
        The original ``TRAN-CAT-BAL`` for the transaction's category key, or ``None``
        when no such category row pre-existed (the create branch).
    new_cat : dict[str, object] or None
        The decoded updated/created category-balance record read back (``None`` if not
        found).
    expected_cat_balance : decimal.Decimal
        The exact expected category balance: ``amount`` on the create branch, else
        ``orig_cat_balance + amount``.
    posted : list[dict[str, object]]
        The decoded records read back from ``TRANSACT`` (the posted transactions).

    Returns
    -------
    PostOutcome
        A new immutable outcome record.

    Raises
    ------
    None
    """

    result: object
    processed: int
    rejected: int
    rejects: "list[str]"
    tran: "dict[str, object]"
    amount: Decimal
    orig_account: "dict[str, object]"
    new_account: "dict[str, object] | None"
    expected_balance: Decimal
    orig_cat_balance: "Decimal | None"
    new_cat: "dict[str, object] | None"
    expected_cat_balance: Decimal
    posted: "list[dict[str, object]]"


def _run_post(cobol_runner: object, repo_root: Path, scenario: str) -> PostOutcome:
    """Stage, run, and compute the full expected-vs-actual state of a posting scenario.

    Purpose
    -------
    Centralise the mechanics shared by every scenario that posts (happy path, both
    boundaries, zero-balance): derive the expected account and category balances from
    the *input* fixtures, then read the masters and the posted transaction file back and
    package everything into a :class:`PostOutcome` for explicit per-test assertions.

    Parameters
    ----------
    cobol_runner : object
        The ``CobolRunner`` from the ``cobol_runner`` fixture.
    repo_root : pathlib.Path
        The repository root (from the ``repo_root`` fixture).
    scenario : str
        The posting scenario name.

    Returns
    -------
    PostOutcome
        The derived expectations and the actual read-back state.

    Raises
    ------
    Skipped
        (propagated) if fixtures are missing.
    AssertionError
        If the account fixture does not contain exactly one account record (these
        posting fixtures seed a single target account; anything else is a fixture bug).
    """
    staged = _stage_inputs_and_run(cobol_runner, repo_root, scenario)
    result = staged.result

    # ---- Derive expected values from the ORIGINAL input fixtures (Decimal) ----
    # WHY (Trade-off: derive-from-fixture vs. hard-coding): computing the expectation
    # from the same bytes the program reads makes the test coordination-free and
    # enterprise-exact -- it cannot drift from a hand-transcribed constant, and it stays
    # correct if the fixtures are re-seeded with different (but self-consistent) amounts.
    acct_records = _read_fixture_records(staged.acctdata, ACCOUNT_LAYOUT.reclen)
    assert len(acct_records) == 1, (
        f"{scenario}: expected exactly one seeded account record, "
        f"got {len(acct_records)}"
    )
    orig_account = ACCOUNT_LAYOUT.decode(acct_records[0])
    # WHY (Assumption): a posting fixture seeds the single account the card resolves to,
    # so that account's key is the one whose master CBTRN02C updates.
    account_key = ACCOUNT_LAYOUT.key_of(acct_records[0])
    acct_id = int(orig_account["ACCT-ID"])

    tran_records = _read_fixture_records(staged.dailytran, DALYTRAN_LAYOUT.reclen)
    assert len(tran_records) == 1, (
        f"{scenario}: expected exactly one daily transaction, got {len(tran_records)}"
    )
    tran = DALYTRAN_LAYOUT.decode(tran_records[0])
    amount = tran["DALYTRAN-AMT"]  # Decimal, never float

    expected_balance = orig_account["ACCT-CURR-BAL"] + amount

    # Category balance: locate the transaction's 17-byte category key in the ORIGINAL
    # TCATBALF fixture. Present -> update branch (orig + amount); absent -> create branch
    # (amount only). This is exactly the 2700-A/2700-B branch condition.
    cat_key = _category_key(acct_id, tran)
    orig_cat_records = _read_fixture_records(staged.tcatbal, TCATBAL_LAYOUT.reclen)
    orig_cat_record = _find_record(orig_cat_records, TCATBAL_LAYOUT, cat_key)
    orig_cat_balance = (
        orig_cat_record["TRAN-CAT-BAL"] if orig_cat_record is not None else None
    )
    # WHY Decimal("0.00") base on the create branch (not int 0): keeps the operand at the
    # money scale so the sum is unambiguously a 2-place fixed-point value.
    base_cat = orig_cat_balance if orig_cat_balance is not None else Decimal("0.00")
    expected_cat_balance = base_cat + amount

    # ---- Read the ACTUAL post-run state back out of the indexed masters ----
    # WHY layout= (single-sourced geometry): unload_output resolves reclen/key length
    # from record_codec, mirroring how the inputs were loaded so the round-trip is
    # provably symmetric. TRANSACT holds CVTRA05Y records (TRAN layout, 350/16).
    new_acct_records = cobol_runner.unload_output("ACCTFILE", layout="ACCOUNT")
    new_account = _find_record(new_acct_records, ACCOUNT_LAYOUT, account_key)

    new_cat_records = cobol_runner.unload_output("TCATBALF", layout="TCATBAL")
    new_cat = _find_record(new_cat_records, TCATBAL_LAYOUT, cat_key)

    posted = _read_posted(cobol_runner)

    processed = _parse_counter(result.stdout, _COUNTER_PROCESSED)
    rejected = _parse_counter(result.stdout, _COUNTER_REJECTED)
    rejects = _read_rejects(result)

    return PostOutcome(
        result=result,
        processed=processed,
        rejected=rejected,
        rejects=rejects,
        tran=tran,
        amount=amount,
        orig_account=orig_account,
        new_account=new_account,
        expected_balance=expected_balance,
        orig_cat_balance=orig_cat_balance,
        new_cat=new_cat,
        expected_cat_balance=expected_cat_balance,
        posted=posted,
    )


@dataclass(frozen=True)
class RejectOutcome:
    """Everything needed to assert a *reject* scenario.

    Purpose
    -------
    Capture the run outcome and the decoded first reject record so a reject test body
    can assert the reason code, message text, and "nothing posted" explicitly.

    Parameters
    ----------
    result : object
        The :class:`RunResult` of the run.
    processed : int
        The ``TRANSACTIONS PROCESSED`` counter.
    rejected : int
        The ``TRANSACTIONS REJECTED`` counter.
    rejects : list[str]
        The framed ``DALYREJS`` records.
    first_reason : int or None
        The decoded reason code of the first reject record (``None`` if none written).
    first_desc : str
        The decoded, right-stripped description of the first reject record (``""`` if
        none written).
    posted : list[dict[str, object]]
        The decoded ``TRANSACT`` records (expected empty for a pure reject).

    Returns
    -------
    RejectOutcome
        A new immutable reject-outcome record.

    Raises
    ------
    None
    """

    result: object
    processed: int
    rejected: int
    rejects: "list[str]"
    first_reason: "int | None"
    first_desc: str
    posted: "list[dict[str, object]]"


def _run_reject(cobol_runner: object, repo_root: Path, scenario: str) -> RejectOutcome:
    """Stage, run, and decode the first reject of a reject scenario.

    Purpose
    -------
    Centralise the mechanics shared by the four reject tests: run the scenario, parse
    the counters, frame and decode the first ``DALYREJS`` record, and read ``TRANSACT``
    back so the caller can prove nothing posted.

    Parameters
    ----------
    cobol_runner : object
        The ``CobolRunner`` from the ``cobol_runner`` fixture.
    repo_root : pathlib.Path
        The repository root (from the ``repo_root`` fixture).
    scenario : str
        The reject scenario name.

    Returns
    -------
    RejectOutcome
        The run outcome and the decoded first reject record.

    Raises
    ------
    Skipped
        (propagated) if fixtures are missing.
    """
    staged = _stage_inputs_and_run(cobol_runner, repo_root, scenario)
    result = staged.result
    processed = _parse_counter(result.stdout, _COUNTER_PROCESSED)
    rejected = _parse_counter(result.stdout, _COUNTER_REJECTED)
    rejects = _read_rejects(result)

    if rejects:
        first_reason, first_desc = _decode_reject(rejects[0])
    else:
        # WHY (Assumption): an empty reject stream is itself an assertion failure the
        # caller will surface; we return neutral values rather than raising here so the
        # test's own assert produces the clearer diagnostic.
        first_reason, first_desc = None, ""

    posted = _read_posted(cobol_runner)

    return RejectOutcome(
        result=result,
        processed=processed,
        rejected=rejected,
        rejects=rejects,
        first_reason=first_reason,
        first_desc=first_desc,
        posted=posted,
    )


# The five observable outputs CBTRN02C produces per scenario, paired with the golden
# file name, the ASSIGN external name, the record layout, and how the file is read back.
# WHY a single declarative table (single source of truth): every scenario asserts the
# SAME five outputs, so listing them once -- rather than five open-coded calls per test --
# keeps the golden contract reviewable in one place and impossible to get out of step
# between the nine scenario tests. ``kind`` distinguishes the read-back mechanism:
#   * "rc"      -> the run's integer RETURN-CODE (text mode, no layout);
#   * "indexed" -> an ORGANIZATION INDEXED output read via unload_output (TRANFILE is
#                  auto-created OUTPUT and may be absent on a reject/empty run);
#   * "seq"     -> an ORGANIZATION SEQUENTIAL output read as raw bytes (DALYREJS is
#                  empty on a clean post).
_GOLDEN_OUTPUTS = (
    ("return_code", "", None, "rc"),
    ("tranfile", "TRANFILE", "TRAN", "indexed"),
    ("acctdat", "ACCTFILE", "ACCOUNT", "indexed"),
    ("tcatbal", "TCATBALF", "TCATBAL", "indexed"),
    ("dalyrejs", "DALYREJS", "REJECT", "seq"),
)


def _assert_posting_goldens(cobol_runner, result, repo_root, scenario, *, update=None):
    """Compare (or, under the guarded protocol, regenerate) a scenario's posting goldens.

    Purpose
    -------
    QA Issue 5 (MAJOR) found this module proved its math/reject reasons with fixture-
    derived Decimal assertions but never *consumed* the committed
    ``tests/golden/posting/<scenario>/*.expected`` files, so whole-record drift -- the QA
    Issue 2 ``zero_balance`` filler regression (20 trailing bytes that are NUL at runtime
    but spaces in the stale golden) -- went uncaught. This helper is the single place that
    wires the AAP-designated byte-exact comparator over ALL five observable outputs of one
    scenario, driven by :data:`_GOLDEN_OUTPUTS`.

    WHY one helper drives BOTH compare and regenerate (single source of truth): the
    regeneration path (``update=True``) and the test's compare path (``update=None``) MUST
    frame exactly the same bytes, or a regenerated golden would not round-trip. The
    committed test calls always use the ``update=None`` default (pure compare); only a
    deliberate, never-committed regeneration run passes ``update=True`` -- and even then
    :func:`assert_matches_golden` still enforces the full MA-12 two-step guard.

    WHY record mode (``layout=``) with ``encoding="latin-1"`` (Assumptions/Trade-off):
    record mode frames fixed-width output by width and blanks only the layout's
    ``normalize_ts`` field (TRAN/REJECT ``*-PROC-TS`` stamped from CURRENT-DATE), so the
    non-deterministic timestamp never causes a flake while every other byte -- including
    the LOW-VALUES ``0x00`` FILLER that distinguishes Issue 2 -- is compared verbatim.
    latin-1 is the identity byte<->codepoint map, so goldens carrying NUL/overpunch bytes
    are read and (re)written byte-for-byte with no re-encoding.

    Parameters
    ----------
    cobol_runner : tests.helpers.cobol_runner.CobolRunner
        The active runner (its workspace holds the indexed TRANFILE/ACCTFILE/TCATBALF).
    result : tests.helpers.cobol_runner.RunResult
        The completed CBTRN02C run (source of the return code and the DALYREJS path).
    repo_root : pathlib.Path
        Repository root, used to locate the golden directory.
    scenario : str
        The posting scenario name (selects ``tests/golden/posting/<scenario>/``).
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
    golden_dir = Path(repo_root) / "tests" / "golden" / _DOMAIN / scenario

    for gname, assign, layout, kind in _GOLDEN_OUTPUTS:
        golden_path = golden_dir / f"{gname}.expected"
        if kind == "rc":
            # RETURN-CODE (text mode): the AAP RC-rubric signal (0 post / 4 reject).
            actual = str(result.returncode)
            assert_matches_golden(actual, golden_path, update=update)
        elif kind == "indexed":
            # WHY guard on existence (Trade-off, mirrors _read_posted): TRANFILE is opened
            # OUTPUT and auto-created, but a reject-only or empty-input run writes no
            # records; whether the ISAM backend then leaves an empty physical file is
            # implementation-defined, so a missing file means "no records" (matches the
            # 0-byte golden) rather than an unload error.
            if cobol_runner.assign_path(assign).exists():
                records = cobol_runner.unload_output(assign, layout=layout)
            else:
                records = []
            assert_matches_golden(
                "\n".join(records), golden_path,
                layout=layout, encoding="latin-1", update=update,
            )
        else:  # "seq"
            # DALYREJS is SEQUENTIAL: read the raw bytes (empty string when the clean-post
            # run wrote no reject), letting record mode frame it by width on both sides.
            rej_path = result.output_path(assign)
            raw = rej_path.read_text(encoding="latin-1") if rej_path.exists() else ""
            assert_matches_golden(
                raw, golden_path,
                layout=layout, encoding="latin-1", update=update,
            )


# ===========================================================================
# CEE3ABD abend shim (QA Issue 6 / F-D) -- make the terminal abend observable.
# ===========================================================================
# The abend code CardDemo uses. WHY 999 (Assumption, verified repo-wide): EVERY
# ``CALL 'CEE3ABD'`` site in app/cbl (CBTRN02C 9999-ABEND-PROGRAM and its peers)
# executes ``MOVE 999 TO ABCODE`` immediately before the call, so 999 is the single
# canonical CardDemo user-abend code -- the shim can hard-code it faithfully rather
# than reflect the (always-999) argument.
_CEE3ABD_ABEND_CODE = 999

# The exact stdout marker the shim DISPLAYs. WHY a named constant coupled to the
# COBOL literal below (Trade-off): the marker is emitted by the COBOL ``DISPLAY``
# in ``_CEE3ABD_SHIM_SRC`` and asserted by the Python test; declaring the expected
# rendering once here keeps the two in lock-step. The trailing ``0999`` is how the
# ``PIC 9(4)`` field ``WS-ABEND-CODE`` (value 999) renders -- a 4-digit zoned value.
_CEE3ABD_MARKER = "CEE3ABD TEST SHIM - ABEND CODE 0999"

# The CEE3ABD shim source: a minimal stand-in for the absent LE ``CEE3ABD`` abend
# service. WHY this exists at all (Alternatives Considered): CardDemo's fail-fast
# handler ``9999-ABEND-PROGRAM`` ends with ``CALL 'CEE3ABD' USING ABCODE, TIMING``;
# GnuCOBOL ships no Language Environment, so at run time libcob emits
# ``module 'CEE3ABD' not found`` and the terminal abend is never actually executed
# (exactly the coverage gap QA Issue 6 flagged). A tiny resolvable ``CEE3ABD.so`` on
# COB_LIBRARY_PATH closes the gap so the abend path is observable end-to-end.
#
# WHY NO ``PROCEDURE DIVISION USING`` (Trade-off, robustness across call sites):
# CBTRN02C calls with two arguments (``ABCODE, TIMING``) but CBEXPORT / CBSTM03A call
# ``CEE3ABD`` with NONE. A shim that declared ``USING LK-ABCODE LK-TIMING`` and then
# dereferenced ``LK-ABCODE`` would read an unbound linkage item (undefined behaviour)
# when invoked by the no-argument callers. GnuCOBOL lets a program with no USING
# clause be CALLed WITH arguments (the extras are simply ignored), so omitting USING
# makes ONE shim safe for every CEE3ABD caller in the codebase. The abend code is not
# taken from the argument for the same reason -- and it need not be, since every site
# passes 999 (see ``_CEE3ABD_ABEND_CODE``).
#
# WHY it terminates via ``MOVE 999 TO RETURN-CODE`` + ``STOP RUN`` and never GOBACKs
# (Assumption): the real ``CEE3ABD`` is a *terminal* abend -- control never returns to
# the caller. GnuCOBOL has no true abend/dump, so the faithful emulation is abnormal
# process termination with a non-zero code; RETURN-CODE 999 surfaces as process exit
# 231 (999 & 0xFF), which the suite's RC rubric reads as a hard failure (RC != 0).
_CEE3ABD_SHIM_SRC = (
    "       IDENTIFICATION DIVISION.\n"
    "       PROGRAM-ID. CEE3ABD.\n"
    "       DATA DIVISION.\n"
    "       WORKING-STORAGE SECTION.\n"
    "       01 WS-ABEND-CODE PIC 9(4) VALUE 999.\n"
    "       PROCEDURE DIVISION.\n"
    "           DISPLAY 'CEE3ABD TEST SHIM - ABEND CODE ' WS-ABEND-CODE\n"
    "           MOVE 999 TO RETURN-CODE\n"
    "           STOP RUN.\n"
)


def _ensure_cee3abd(build_dir: Path) -> None:
    """Compile the ``CEE3ABD`` abend shim into ``build_dir`` on demand.

    Purpose
    -------
    Guarantee that ``build_dir/CEE3ABD.so`` exists before the abend-path test runs
    the compiled ``CBTRN02C``. ``build_dir`` is the directory ``cobol_runner`` places
    on ``COB_LIBRARY_PATH``, so the dynamically-``CALL``'d ``CEE3ABD`` resolves to
    this shim at run time and the terminal abend becomes observable (QA Issue 6).
    If the shim already exists the call returns immediately, so the cost is paid at
    most once per session.

    Parameters
    ----------
    build_dir : pathlib.Path
        Directory the compiled ``CEE3ABD.so`` is published into. Must be the same
        directory ``cobol_runner`` exposes on ``COB_LIBRARY_PATH`` (the ``build_dir``
        fixture guarantees this).

    Returns
    -------
    None

    Raises
    ------
    Skipped
        Via :func:`_require_or_skip` when ``cobc`` is not on ``PATH`` and
        ``CARDDEMO_REQUIRE_COBOL`` is unset. WHY skip (not fail) here: an absent
        toolchain is an environment-capability condition, not a defect -- graded
        exactly like the rest of the suite (Issue 4 / F-C policy).
    Failed
        Via :func:`pytest.fail` when ``cobc`` IS present but the shim source (which
        this module fully controls and which is verified to compile under
        ``--std=ibm-strict``) fails to compile. WHY hard-fail (not skip): a compile
        failure of a controlled test artifact is a real defect and must never hide
        behind a silent skip -- the same reasoning Issue 4 / F-C applied to
        provisioning's ``_ensure_cobdatft`` stub build.
    """
    shim_so = build_dir / "CEE3ABD.so"
    # WHY the existence guard (Trade-off, idempotence + xdist safety): a cheap check
    # makes this near-free to call from the test body -- the first invocation compiles,
    # any later one short-circuits -- and, paired with the atomic publish below, keeps
    # concurrent pytest-xdist workers from doing redundant or racing work.
    if shim_so.exists():
        return

    cobc = shutil.which("cobc")
    if cobc is None:
        # WHY route through the shared gate (Refactoring Rationale): in practice this
        # branch is unreachable in the abend test because ``cobol_runner`` transitively
        # depends on ``built_programs``, which already compiled CBTRN02C with ``cobc``.
        # It is kept as a defensive, consistent guard for the edge case of a pre-built
        # ``CARDDEMO_BUILD_DIR`` where the build step was short-circuited by its stamp.
        _require_or_skip("GnuCOBOL 'cobc' not on PATH; cannot build the CEE3ABD abend shim")

    build_dir.mkdir(parents=True, exist_ok=True)

    # WHY compile in a private temp dir INSIDE build_dir, then atomically publish
    # (Refactoring Rationale + parallel safety): all scratch work (writing the ``.cbl``
    # and compiling) happens in a throwaway directory; only the finished ``.so`` is
    # ``os.replace``-d into ``build_dir``. This (a) leaves no stray ``.cbl`` in
    # ``build_dir`` (honouring the suite's "no stray sources" rule) and (b) makes
    # publication atomic and same-filesystem, so a concurrent xdist worker can never
    # observe or write a half-built shim. The temp dir lives INSIDE ``build_dir`` so the
    # rename is same-device (``os.replace`` is only atomic within one filesystem; a
    # system ``/tmp`` on another mount could otherwise raise EXDEV).
    scratch = Path(tempfile.mkdtemp(prefix=".abend_build_", dir=str(build_dir)))
    try:
        shim_src = scratch / "CEE3ABD.cbl"
        shim_src.write_text(_CEE3ABD_SHIM_SRC, encoding="ascii")
        tmp_so = scratch / "CEE3ABD.so"

        # Compile the shim as a dynamically-loadable module (``-m``) under the repo's
        # production compile recipe.
        # WHY ``-fixed --std=ibm-strict`` (Trade-off vs. the CEEDAYS shim): unlike the
        # CEEDAYS shim -- which had to drop to ``-free -fintrinsics=ALL`` because it uses
        # an intrinsic the strict dialect rejects -- this shim uses only plain COBOL, so
        # it stays on the EXACT production dialect (``cobc -m -fixed --std=ibm-strict``,
        # per scripts/build_test_programs.sh). Keeping the test shim on the production
        # dialect is strictly preferable: it proves the shim is dialect-clean.
        built = subprocess.run(
            [cobc, "-m", "-fixed", "--std=ibm-strict", "-o", str(tmp_so), str(shim_src)],
            cwd=str(scratch),
            capture_output=True,
            text=True,
        )
        # WHY check returncode AND the artifact (Assumption): cobc emits a benign
        # ``_FORTIFY_SOURCE redefined`` warning from the gcc backend (the environment's
        # default CFLAGS collide with cobc's own) that does NOT set a non-zero exit; a
        # clean build is rc == 0 with the ``.so`` present, so we gate on both and treat
        # the warning as noise, never as failure.
        if built.returncode != 0 or not tmp_so.exists():
            pytest.fail(
                "CEE3ABD abend shim build failed (cobc present, so this is a defect in "
                "the shim source, not an environment gap): "
                + (built.stderr or built.stdout or "(no compiler output)").strip(),
                pytrace=False,
            )

        # Publish atomically. WHY ``os.replace`` (Assumption): a POSIX rename within one
        # filesystem is atomic, so a reader sees either no shim or the fully-written one
        # -- never a truncated file -- which is what makes the concurrent-worker race
        # benign.
        os.replace(str(tmp_so), str(shim_so))
    finally:
        # Always remove the scratch dir (best-effort). The finished ``.so`` was moved out
        # by ``os.replace``, so only the ``.cbl`` and any cobc intermediates remain;
        # deleting them keeps ``build_dir`` clean.
        shutil.rmtree(scratch, ignore_errors=True)


# ===========================================================================
# Tests -- happy path (post + exact fixed-point balance updates).
# ===========================================================================
def test_happy_path_posts_and_updates_balances(cobol_runner, repo_root):
    """A fully valid transaction posts and updates both masters by the exact amount.

    Purpose
    -------
    Exercise the CBTRN02C happy path end to end: a card present in ``XREFFILE`` whose
    account exists, is within its credit limit, and is unexpired must post -- writing the
    transaction to ``TRANFILE``, incrementing the category balance (the 2700-B REWRITE
    branch, since the fixture seeds a matching category row), and incrementing the
    account master (2800) by the exact ``DALYTRAN-AMT`` -- with ``RETURN-CODE`` 0 and an
    empty reject stream.

    Parameters
    ----------
    cobol_runner : tests.helpers.cobol_runner.CobolRunner
        Per-test runner (``cobol_runner`` fixture) bound to a fresh isolated workspace.
    repo_root : pathlib.Path
        Repository root (``repo_root`` fixture) used to locate the scenario fixtures.

    Returns
    -------
    None

    Raises
    ------
    AssertionError
        If the return code, counters, reject stream, posted transaction, or either exact
        monetary balance diverges from the fixture-derived expectation.
    Skipped
        If the scenario fixtures or the compiled program are unavailable.
    """
    exp = SCENARIOS["happy_path"]
    out = _run_post(cobol_runner, repo_root, exp.scenario)

    # RETURN-CODE and stdout counters (a cross-check independent of the on-disk files).
    assert out.result.returncode == exp.return_code  # 0: nothing rejected
    assert out.rejected == exp.min_rejected  # 0
    assert out.processed == 1  # the single seeded transaction was read
    # A clean post writes NO reject record; prove the stream is empty.
    assert out.rejects == []

    # Exact fixed-point account-balance update (Decimal, never float).
    # WHY assert the fixture-derived expected (orig + amount): this encodes the 2800 rule
    # (ADD DALYTRAN-AMT TO ACCT-CURR-BAL) computed from the very bytes the program read,
    # so the assertion cannot drift from a hand-transcribed constant.
    assert out.new_account is not None, "updated ACCTFILE record not found on read-back"
    assert out.new_account["ACCT-CURR-BAL"] == out.expected_balance

    # Exact category-balance update (2700-B REWRITE branch: orig + amount).
    assert out.new_cat is not None, "TCATBALF category row not found on read-back"
    assert out.new_cat["TRAN-CAT-BAL"] == out.expected_cat_balance

    # The transaction posted to TRANFILE with its id and amount preserved.
    # WHY assert id + amount only (never TRAN-PROC-TS): 2000-POST moves DALYTRAN-ID and
    # DALYTRAN-AMT verbatim, but TRAN-PROC-TS is stamped from CURRENT-DATE and is
    # non-deterministic -- asserting it would make the test flaky, so we assert only the
    # deterministic posted fields (Trade-off: coverage of the timestamp vs. determinism).
    tran_id = out.tran["DALYTRAN-ID"]
    matches = [rec for rec in out.posted if rec["TRAN-ID"] == tran_id]
    assert len(matches) == 1, f"expected exactly one posted tran with id {tran_id!r}"
    assert matches[0]["TRAN-AMT"] == out.amount

    # Golden oracle (QA Issue 5): after proving the math field-by-field, also assert all
    # five observable outputs byte-for-byte against the committed goldens (whole-record
    # layout the value-only checks above do not inspect).
    _assert_posting_goldens(cobol_runner, out.result, repo_root, exp.scenario)


# ===========================================================================
# Tests -- the four reject reasons (100-103). One explicit test each so a
# failure names the exact business rule that regressed.
# ===========================================================================
def test_reject_100_card_missing(cobol_runner, repo_root):
    """Reject reason 100 is raised when the card is absent from ``XREFFILE``.

    Purpose
    -------
    Prove that a daily transaction whose card number is not a primary key in
    ``XREFFILE`` fails ``1500-A-LOOKUP-XREF`` on INVALID KEY, is written to ``DALYREJS``
    with reason ``0100`` and description ``INVALID CARD NUMBER FOUND``, sets
    ``RETURN-CODE`` 4, and posts nothing.

    Parameters
    ----------
    cobol_runner : tests.helpers.cobol_runner.CobolRunner
        Per-test runner bound to a fresh isolated workspace.
    repo_root : pathlib.Path
        Repository root used to locate the scenario fixtures.

    Returns
    -------
    None

    Raises
    ------
    AssertionError
        If the return code, reject count, reason code, description text, or the
        "nothing posted" invariant is not satisfied.
    Skipped
        If the scenario fixtures or the compiled program are unavailable.
    """
    exp = SCENARIOS["reject_100_card_missing"]
    out = _run_reject(cobol_runner, repo_root, exp.scenario)

    # WHY this fixture yields 100: its transaction's card number is not present as a
    # primary key in the XREF fixture, so the XREF READ takes the INVALID KEY branch.
    assert out.result.returncode == exp.return_code  # 4
    assert out.rejected >= exp.min_rejected  # >= 1
    assert out.rejects, "expected at least one DALYREJS record"
    assert out.first_reason == exp.reason  # 100
    assert _REJECT_DESCRIPTIONS[exp.reason] in out.first_desc
    # Nothing posts on a reject: TRANFILE has no records and processed == rejected.
    assert out.posted == []
    assert out.processed - out.rejected == 0

    # Golden oracle (QA Issue 5): assert the reject stream (DALYREJS reason 0100 record),
    # the empty TRANFILE, the untouched masters, and RETURN-CODE 4 byte-for-byte.
    _assert_posting_goldens(cobol_runner, out.result, repo_root, exp.scenario)


def test_reject_101_acct_missing(cobol_runner, repo_root):
    """Reject reason 101 is raised when the resolved account is absent from ``ACCTFILE``.

    Purpose
    -------
    Prove that when the card resolves to an ``XREF-ACCT-ID`` that has no record in
    ``ACCTFILE``, ``1500-B-LOOKUP-ACCT`` fails on INVALID KEY, writing ``DALYREJS`` with
    reason ``0101`` / ``ACCOUNT RECORD NOT FOUND``, ``RETURN-CODE`` 4, and no post.

    Parameters
    ----------
    cobol_runner : tests.helpers.cobol_runner.CobolRunner
        Per-test runner bound to a fresh isolated workspace.
    repo_root : pathlib.Path
        Repository root used to locate the scenario fixtures.

    Returns
    -------
    None

    Raises
    ------
    AssertionError
        If the return code, reject count, reason code, description text, or the
        "nothing posted" invariant is not satisfied.
    Skipped
        If the scenario fixtures or the compiled program are unavailable.
    """
    exp = SCENARIOS["reject_101_acct_missing"]
    out = _run_reject(cobol_runner, repo_root, exp.scenario)

    # WHY this fixture yields 101: the card IS in XREF (so 100 is passed) but the account
    # it points to is not seeded in ACCTFILE, so the ACCOUNT READ hits INVALID KEY.
    assert out.result.returncode == exp.return_code  # 4
    assert out.rejected >= exp.min_rejected  # >= 1
    assert out.rejects, "expected at least one DALYREJS record"
    assert out.first_reason == exp.reason  # 101
    assert _REJECT_DESCRIPTIONS[exp.reason] in out.first_desc
    assert out.posted == []
    assert out.processed - out.rejected == 0

    # Golden oracle (QA Issue 5): assert DALYREJS reason 0101, empty TRANFILE, untouched
    # masters, and RETURN-CODE 4 byte-for-byte against the committed goldens.
    _assert_posting_goldens(cobol_runner, out.result, repo_root, exp.scenario)


def test_reject_102_overlimit(cobol_runner, repo_root):
    """Reject reason 102 is raised when the posting would exceed the credit limit.

    Purpose
    -------
    Prove that when ``WS-TEMP-BAL`` (``CYC-CREDIT - CYC-DEBIT + amount``) exceeds
    ``ACCT-CREDIT-LIMIT`` -- i.e. ``ACCT-CREDIT-LIMIT >= WS-TEMP-BAL`` is FALSE -- the
    transaction is rejected with reason ``0102`` / ``OVERLIMIT TRANSACTION``,
    ``RETURN-CODE`` 4, and posts nothing.

    Parameters
    ----------
    cobol_runner : tests.helpers.cobol_runner.CobolRunner
        Per-test runner bound to a fresh isolated workspace.
    repo_root : pathlib.Path
        Repository root used to locate the scenario fixtures.

    Returns
    -------
    None

    Raises
    ------
    AssertionError
        If the return code, reject count, reason code, description text, or the
        "nothing posted" invariant is not satisfied.
    Skipped
        If the scenario fixtures or the compiled program are unavailable.
    """
    exp = SCENARIOS["reject_102_overlimit"]
    out = _run_reject(cobol_runner, repo_root, exp.scenario)

    # WHY this fixture yields 102: the amount pushes WS-TEMP-BAL one cent past the credit
    # limit, so the `>=` guard is FALSE. This is the strict complement of the
    # boundary_exact_limit case (which sits exactly ON the limit and POSTS).
    assert out.result.returncode == exp.return_code  # 4
    assert out.rejected >= exp.min_rejected  # >= 1
    assert out.rejects, "expected at least one DALYREJS record"
    assert out.first_reason == exp.reason  # 102
    assert _REJECT_DESCRIPTIONS[exp.reason] in out.first_desc
    assert out.posted == []
    assert out.processed - out.rejected == 0

    # Golden oracle (QA Issue 5): assert DALYREJS reason 0102, empty TRANFILE, untouched
    # masters, and RETURN-CODE 4 byte-for-byte against the committed goldens.
    _assert_posting_goldens(cobol_runner, out.result, repo_root, exp.scenario)


def test_reject_103_expired(cobol_runner, repo_root):
    """Reject reason 103 is raised when the transaction date is past account expiration.

    Purpose
    -------
    Prove that when the transaction date (``DALYTRAN-ORIG-TS`` first 10 chars) is later
    than ``ACCT-EXPIRAION-DATE`` -- i.e. ``ACCT-EXPIRAION-DATE >= date`` is FALSE -- the
    transaction is rejected with reason ``0103`` /
    ``TRANSACTION RECEIVED AFTER ACCT EXPIRATION``, ``RETURN-CODE`` 4, and no post.

    Parameters
    ----------
    cobol_runner : tests.helpers.cobol_runner.CobolRunner
        Per-test runner bound to a fresh isolated workspace.
    repo_root : pathlib.Path
        Repository root used to locate the scenario fixtures.

    Returns
    -------
    None

    Raises
    ------
    AssertionError
        If the return code, reject count, reason code, description text, or the
        "nothing posted" invariant is not satisfied.
    Skipped
        If the scenario fixtures or the compiled program are unavailable.
    """
    exp = SCENARIOS["reject_103_expired"]
    out = _run_reject(cobol_runner, repo_root, exp.scenario)

    # WHY this fixture yields 103: its transaction date is one day after the account's
    # expiration date, so the `>=` guard is FALSE. Strict complement of the
    # boundary_expiry_equal case (date exactly ON expiration, which POSTS).
    assert out.result.returncode == exp.return_code  # 4
    assert out.rejected >= exp.min_rejected  # >= 1
    assert out.rejects, "expected at least one DALYREJS record"
    assert out.first_reason == exp.reason  # 103
    assert _REJECT_DESCRIPTIONS[exp.reason] in out.first_desc
    assert out.posted == []
    assert out.processed - out.rejected == 0

    # Golden oracle (QA Issue 5): assert DALYREJS reason 0103, empty TRANFILE, untouched
    # masters, and RETURN-CODE 4 byte-for-byte against the committed goldens.
    _assert_posting_goldens(cobol_runner, out.result, repo_root, exp.scenario)


# ===========================================================================
# Tests -- mandatory boundary cases (AAP 0.7.1): the `>=` guards POST at equality.
# ===========================================================================
def test_boundary_exact_limit_posts(cobol_runner, repo_root):
    """A transaction landing EXACTLY on the credit limit POSTS (the ``>=`` boundary).

    Purpose
    -------
    Cover the mandatory over-limit boundary (AAP 0.7.1). ``1500-B`` guards posting with
    ``ACCT-CREDIT-LIMIT >= WS-TEMP-BAL``; at exact equality the condition is TRUE, so
    reason 102 is NOT raised and the transaction posts. The test additionally proves the
    fixture truly sits on the boundary (``WS-TEMP-BAL == ACCT-CREDIT-LIMIT``), so it is a
    genuine equality case and not merely a below-limit post.

    Parameters
    ----------
    cobol_runner : tests.helpers.cobol_runner.CobolRunner
        Per-test runner bound to a fresh isolated workspace.
    repo_root : pathlib.Path
        Repository root used to locate the scenario fixtures.

    Returns
    -------
    None

    Raises
    ------
    AssertionError
        If the boundary transaction does not post, the balance update is not exact, or
        the fixture does not sit exactly on the credit limit.
    Skipped
        If the scenario fixtures or the compiled program are unavailable.
    """
    exp = SCENARIOS["boundary_exact_limit"]
    out = _run_post(cobol_runner, repo_root, exp.scenario)

    assert out.result.returncode == exp.return_code  # 0: posts at equality
    assert out.rejected == 0
    assert out.rejects == []
    assert out.new_account is not None
    assert out.new_account["ACCT-CURR-BAL"] == out.expected_balance

    # Cross-check the boundary actually held: recompute WS-TEMP-BAL from the ORIGINAL
    # account (all Decimal) and assert it equals the credit limit exactly.
    # WHY recompute here rather than trust the fixture name: it converts "the fixture is
    # named boundary" into a proven arithmetic fact, guarding against a mis-seeded fixture
    # that would otherwise let a below-limit post masquerade as a boundary test.
    ws_temp_bal = (
        out.orig_account["ACCT-CURR-CYC-CREDIT"]
        - out.orig_account["ACCT-CURR-CYC-DEBIT"]
        + out.amount
    )
    assert ws_temp_bal == out.orig_account["ACCT-CREDIT-LIMIT"]

    # Golden oracle (QA Issue 5): the at-limit post must produce the same five outputs as
    # the committed goldens (proves the `>=` boundary posts, not just the derived balance).
    _assert_posting_goldens(cobol_runner, out.result, repo_root, exp.scenario)


def test_boundary_expiry_equal_posts(cobol_runner, repo_root):
    """A transaction dated EXACTLY on the expiration date POSTS (the ``>=`` boundary).

    Purpose
    -------
    Cover the mandatory expiration boundary (AAP 0.7.1). ``1500-B`` guards posting with
    ``ACCT-EXPIRAION-DATE >= DALYTRAN-ORIG-TS(1:10)``; at equal dates the condition is
    TRUE, so reason 103 is NOT raised and the transaction posts. The test additionally
    proves the transaction date equals the account expiration date, so it is a genuine
    equality case.

    Parameters
    ----------
    cobol_runner : tests.helpers.cobol_runner.CobolRunner
        Per-test runner bound to a fresh isolated workspace.
    repo_root : pathlib.Path
        Repository root used to locate the scenario fixtures.

    Returns
    -------
    None

    Raises
    ------
    AssertionError
        If the boundary transaction does not post, the balance update is not exact, or
        the transaction date does not equal the account expiration date.
    Skipped
        If the scenario fixtures or the compiled program are unavailable.
    """
    exp = SCENARIOS["boundary_expiry_equal"]
    out = _run_post(cobol_runner, repo_root, exp.scenario)

    assert out.result.returncode == exp.return_code  # 0: posts at equality
    assert out.rejected == 0
    assert out.rejects == []
    assert out.new_account is not None
    assert out.new_account["ACCT-CURR-BAL"] == out.expected_balance

    # Cross-check the boundary: the transaction's date equals the account expiration date.
    # WHY compare the first 10 chars: DALYTRAN-ORIG-TS is a 26-char timestamp, but the
    # program compares only its date portion (1:10) against ACCT-EXPIRAION-DATE, so the
    # test mirrors exactly the sub-field the rule inspects.
    tran_date = out.tran["DALYTRAN-ORIG-TS"][:10]
    assert tran_date == out.orig_account["ACCT-EXPIRAION-DATE"]

    # Golden oracle (QA Issue 5): the equal-to-expiry post must produce the same five
    # outputs as the committed goldens (proves the date `>=` boundary posts).
    _assert_posting_goldens(cobol_runner, out.result, repo_root, exp.scenario)


# ===========================================================================
# Tests -- empty input and zero-balance edge cases.
# ===========================================================================
def test_empty_input(cobol_runner, repo_root):
    """An empty ``DALYTRAN`` yields a clean no-op run (RC 0, nothing read/rejected/posted).

    Purpose
    -------
    Prove the empty-input edge case: when the daily-transaction file is zero-length, the
    first READ hits end-of-file immediately, so ``TRANSACTIONS PROCESSED`` is 0,
    ``TRANSACTIONS REJECTED`` is 0, ``RETURN-CODE`` is 0, no reject record is written,
    and nothing is posted.

    Parameters
    ----------
    cobol_runner : tests.helpers.cobol_runner.CobolRunner
        Per-test runner bound to a fresh isolated workspace.
    repo_root : pathlib.Path
        Repository root used to locate the scenario fixtures.

    Returns
    -------
    None

    Raises
    ------
    AssertionError
        If any counter, the return code, the reject stream, or the posted set is
        non-empty.
    Skipped
        If the scenario fixtures or the compiled program are unavailable.
    """
    exp = SCENARIOS["empty_input"]
    # WHY not _run_post: there is no transaction to derive a balance from; the contract is
    # simply "read zero, reject zero, post zero" -- so we stage/run directly and assert
    # the counters and empty output streams.
    staged = _stage_inputs_and_run(cobol_runner, repo_root, exp.scenario)
    result = staged.result

    processed = _parse_counter(result.stdout, _COUNTER_PROCESSED)
    rejected = _parse_counter(result.stdout, _COUNTER_REJECTED)
    rejects = _read_rejects(result)
    posted = _read_posted(cobol_runner)

    assert result.returncode == exp.return_code  # 0
    assert processed == 0
    assert rejected == exp.min_rejected  # 0
    assert rejects == []
    assert posted == []

    # Golden oracle (QA Issue 5): empty input must yield empty TRANFILE and DALYREJS, the
    # untouched seeded master, and RETURN-CODE 0 -- all five byte-for-byte vs the goldens.
    _assert_posting_goldens(cobol_runner, result, repo_root, exp.scenario)


def test_zero_balance(cobol_runner, repo_root):
    """A zero-amount transaction on a zero-balance account posts cleanly (CREATE branch).

    Purpose
    -------
    Prove the zero-balance edge case AND the 2700-A CREATE branch: the fixture seeds no
    pre-existing category row, so the category READ hits INVALID KEY and a new row is
    WRITTEN at the transaction amount, while the account master is REWRITTEN by the exact
    (zero) amount -- all with ``RETURN-CODE`` 0 and no reject.

    Parameters
    ----------
    cobol_runner : tests.helpers.cobol_runner.CobolRunner
        Per-test runner bound to a fresh isolated workspace.
    repo_root : pathlib.Path
        Repository root used to locate the scenario fixtures.

    Returns
    -------
    None

    Raises
    ------
    AssertionError
        If the run rejects, if the account balance is not exactly ``orig + amount``, or
        if the category row was not created at the exact amount.
    Skipped
        If the scenario fixtures or the compiled program are unavailable.
    """
    exp = SCENARIOS["zero_balance"]
    out = _run_post(cobol_runner, repo_root, exp.scenario)

    assert out.result.returncode == exp.return_code  # 0
    assert out.rejected == 0
    assert out.rejects == []

    # Exact balance: orig + amount. The amount is zero here, so the balance is unchanged,
    # but the REWRITE path still runs and must leave an exact fixed-point value (not, say,
    # a re-scaled or sign-flipped zero).
    assert out.new_account is not None
    assert out.new_account["ACCT-CURR-BAL"] == out.expected_balance

    # 2700-A CREATE branch: no category row pre-existed -> a new row is written at amount.
    # WHY assert orig_cat_balance is None: it is the observable precondition that forces
    # the INVALID KEY (create) path rather than the REWRITE (update) path.
    assert out.orig_cat_balance is None, "zero_balance fixture must seed no category row"
    assert out.new_cat is not None, "the CREATE branch must write a new category row"
    assert out.new_cat["TRAN-CAT-BAL"] == out.expected_cat_balance

    # Golden oracle (QA Issue 5 + Issue 2): zero_balance is the scenario whose committed
    # tranfile golden carried the stale space-filler; wiring it here makes the regenerated
    # NUL-filler golden authoritative so the drift can never silently reappear.
    _assert_posting_goldens(cobol_runner, out.result, repo_root, exp.scenario)


# ===========================================================================
# Test -- mandatory TCATBAL create-vs-update branch coverage (AAP 0.7.1).
# ===========================================================================
def test_tcatbal_create_vs_update(cobol_runner, repo_root):
    """Both TCATBALF branches are covered: 2700-A CREATE and 2700-B UPDATE.

    Purpose
    -------
    Make the mandatory category-balance branch coverage (AAP 0.7.1) explicit in one
    place using two scenarios that differ only in whether a matching category row
    pre-exists: ``zero_balance`` seeds none (INVALID KEY -> 2700-A WRITE, balance ==
    amount) while ``happy_path`` seeds one (2700-B REWRITE, balance == orig + amount).

    Parameters
    ----------
    cobol_runner : tests.helpers.cobol_runner.CobolRunner
        Per-test runner bound to a fresh isolated workspace.
    repo_root : pathlib.Path
        Repository root used to locate the scenario fixtures.

    Returns
    -------
    None

    Raises
    ------
    AssertionError
        If either branch's category balance does not match its fixture-derived
        expectation, or if the presumed create/update preconditions do not hold.
    Skipped
        If either scenario's fixtures or the compiled program are unavailable.
    """
    # ---- 2700-A CREATE: no pre-existing category row -> written at the amount. ----
    create = _run_post(cobol_runner, repo_root, "zero_balance")
    assert create.orig_cat_balance is None, "CREATE precondition: no seeded category row"
    assert create.new_cat is not None
    assert create.new_cat["TRAN-CAT-BAL"] == create.expected_cat_balance

    # ---- 2700-B UPDATE: a matching category row pre-existed -> incremented. ----
    # WHY a second run in the SAME test is safe (isolation holds within the test too):
    # load_input performs the IDCAMS DELETE analog (removing the prior indexed file and
    # any sidecars) before rebuilding each input, and CBTRN02C opens TRANFILE/DALYREJS
    # OUTPUT (truncating) each run -- so the second scenario cannot observe the first's
    # inputs or outputs. This keeps both branches asserted in one localizable test.
    update = _run_post(cobol_runner, repo_root, "happy_path")
    assert update.orig_cat_balance is not None, "UPDATE precondition: seeded category row"
    assert update.new_cat is not None
    assert update.new_cat["TRAN-CAT-BAL"] == update.expected_cat_balance
    # Prove it was a true increment (orig + amount), i.e. the REWRITE, not a fresh WRITE.
    assert update.expected_cat_balance == update.orig_cat_balance + update.amount



# ===========================================================================
# Tests -- fail-fast abend path (QA Issue 6 / F-D): the terminal CEE3ABD call
# is made OBSERVABLE by resolving a test-only CEE3ABD shim on COB_LIBRARY_PATH.
# ===========================================================================
def test_bad_input_open_triggers_observable_cee3abd_abend(cobol_runner, build_dir):
    """A missing required input drives CBTRN02C's fail-fast abend through the CEE3ABD shim.

    Purpose
    -------
    Close the coverage gap QA Issue 6 flagged: CardDemo's hard-error handler
    ``9999-ABEND-PROGRAM`` ends with ``CALL 'CEE3ABD' USING ABCODE, TIMING`` (LE
    user-abend code 999), but with no Language Environment on the runner libcob
    could only report ``module 'CEE3ABD' not found`` -- so the *terminal abend call
    itself was never executed in-suite*. This test installs a resolvable, test-only
    ``CEE3ABD.so`` shim and then deliberately triggers the fail-fast path by running
    ``CBTRN02C`` with **no inputs staged**. ``CBTRN02C``'s first file operation is
    ``0000-DALYTRAN-OPEN`` (``OPEN INPUT`` on ``DALYTRAN``); an absent file yields
    FILE STATUS 35, which routes through ``9910-DISPLAY-IO-STATUS`` into
    ``9999-ABEND-PROGRAM`` and calls the shim. The whole abend path -- diagnostic,
    abend banner, resolved terminal call, and abnormal exit -- is then asserted.

    Parameters
    ----------
    cobol_runner : tests.helpers.cobol_runner.CobolRunner
        Per-test runner (``cobol_runner`` fixture) bound to a fresh, isolated,
        EMPTY workspace. Because the test stages nothing, every ``ASSIGN`` name --
        ``DALYTRAN`` first -- resolves to a non-existent file, which is exactly the
        deterministic bad-``OPEN`` condition the fail-fast path handles.
    build_dir : pathlib.Path
        The session build directory (``build_dir`` fixture) -- the same directory
        ``cobol_runner`` places on ``COB_LIBRARY_PATH`` -- into which the CEE3ABD
        shim is compiled so the ``CALL 'CEE3ABD'`` resolves at run time.

    Returns
    -------
    None

    Raises
    ------
    AssertionError
        If the CEE3ABD module is still unresolved (the QA symptom persists), if the
        fail-fast diagnostic / abend banner / shim marker is absent from stdout, or
        if the process does not terminate abnormally (non-zero return code).
    Skipped
        Via :func:`_require_or_skip` (from ``_ensure_cee3abd``) if ``cobc`` is not on
        ``PATH`` and ``CARDDEMO_REQUIRE_COBOL`` is unset.
    Failed
        Via :func:`pytest.fail` (from ``_ensure_cee3abd``) if the shim source fails
        to compile while ``cobc`` is present.
    tests.helpers.cobol_runner.CobolRunError
        If the program times out (propagated from ``cobol_runner.run``).
    """
    # Install the resolvable CEE3ABD shim BEFORE running the program. WHY here and not
    # in a fixture (Trade-off): the shim is specific to this single abend test, so
    # building it inline keeps the dependency visible and localized; the builder's own
    # existence-guard + atomic publish already make it idempotent and xdist-safe.
    _ensure_cee3abd(build_dir)

    # WHY stage NOTHING (Alternatives Considered): the cleanest, most deterministic way
    # to force the fail-fast path is to make the very FIRST OPEN fail. CBTRN02C opens
    # DALYTRAN (INPUT) first; an absent file gives FILE STATUS 35 unconditionally. An
    # alternative -- staging a valid DALYTRAN then a *corrupt* INDEXED XREFFILE (FILE
    # STATUS 39) -- also abends, but requires more setup and couples the test to indexed
    # file internals; the empty-workspace trigger is simpler and equally faithful to the
    # "bad OPEN / I/O -> abend" rule (AAP 0.4.1). The runner does NOT auto-create input
    # files, so an unstaged DALYTRAN is genuinely absent (verified empirically).
    result = cobol_runner.run(_PROGRAM)

    # (1) THE Issue-6 fix: the shim RESOLVED. Before the shim, stderr carried
    # ``libcob: error: module 'CEE3ABD' not found``; its absence proves the terminal
    # abend call was actually dispatched to a resolvable module. WHY assert on the
    # stable substring ``not found`` (Assumption): it is the invariant part of libcob's
    # message across quoting styles/versions, so the assertion is robust.
    assert "not found" not in result.stderr, (
        "CEE3ABD is still unresolved -- the abend shim did not load. stderr:\n"
        + result.stderr
    )

    # (2) The fail-fast DIAGNOSTIC path executed: CBTRN02C reports the failing file and
    # its FILE STATUS before abending. WHY assert the exact ``NNNN0035`` rendering
    # (Assumption): ``9910-DISPLAY-IO-STATUS`` DISPLAYs the literal label ``FILE STATUS
    # IS: NNNN`` immediately followed by the 4-digit status; ``0035`` (file-not-found)
    # is the deterministic status for OPEN INPUT of an absent file under the pinned
    # GnuCOBOL 3.2.0 toolchain (verified empirically, stable across reruns).
    assert "ERROR OPENING DALYTRAN" in result.stdout, result.stdout
    assert "FILE STATUS IS: NNNN0035" in result.stdout, result.stdout
    assert "ABENDING PROGRAM" in result.stdout, result.stdout

    # (3) The shim itself EXECUTED (not merely resolved): its auditable marker proves the
    # CALL reached the shim body. WHY an explicit marker (Trade-off, auditability): a
    # financial-enterprise abend path should be observable in the run log, so the shim
    # emits a self-identifying line carrying the simulated abend code (0999) rather than
    # terminating silently -- distinguishing "shim ran" from "program exited early".
    assert _CEE3ABD_MARKER in result.stdout, (
        f"expected the CEE3ABD shim marker {_CEE3ABD_MARKER!r} in stdout:\n"
        + result.stdout
    )

    # (4) ABNORMAL termination: the abend must NOT look like a clean run. The shim sets
    # RETURN-CODE to the abend code (999), which surfaces as a non-zero process exit
    # (999 & 0xFF == 231). WHY assert ``!= 0`` rather than ``== 231`` (Trade-off,
    # portability): the RC rubric only distinguishes zero (success) from non-zero
    # (failure); pinning the exact 8-bit-wrapped value would couple the test to the
    # OS's exit-code truncation without adding financial meaning.
    assert result.returncode != 0, (
        f"expected abnormal termination (non-zero RC), got {result.returncode}"
    )


# ===========================================================================
# Test -- multi-transaction run (main read-loop iteration coverage).
# WHY this test exists (finding F-COVERAGE-FINANCIAL-TARGETS): every other
# posting scenario seeds EXACTLY ONE daily transaction, so CBTRN02C's main
# driver loop (1000-DALYTRAN-GET-NEXT -> validate -> post -> loop back) is only
# ever entered once and its "read a SECOND record, it is not EOF, process it,
# then hit EOF" continuation branch is never exercised on the build-dir binary.
# The gcov numbers the COBOL coverage report measures come solely from these
# integration runs (the GCBLUnit *_test programs COPY the source into a SEPARATE
# compilation unit whose data lands in <NAME>_test.gcda, and the CBACT04C/abend
# drivers are separately linked), so adding a run that posts TWO transactions is
# the single highest-value FEASIBLE increment to CBTRN02C's build-dir line/branch
# coverage. The two TCATBAL branches (2700-A create / 2700-B update) already have
# a dedicated test; here the NEW coverage is the loop continuation plus a second
# 2000/2700-B/2800/2900 posting cycle within one process.
# ===========================================================================
def test_multiple_transactions_post_in_one_run(cobol_runner, repo_root):
    """Two valid transactions in one DALYTRAN both post, exercising the read loop twice.

    Purpose
    -------
    Drive CBTRN02C with a DALYTRAN holding TWO valid transactions for the same card,
    account, and category (the second is the ``happy_path`` transaction with a distinct
    ``DALYTRAN-ID`` so it does not collide on the ``TRANFILE`` primary key). This forces
    the main driver loop to iterate a second time -- the "read next record, not EOF,
    process, then detect EOF" continuation branch that no single-transaction scenario
    reaches -- and applies two full 2000/2700-B/2800/2900 posting cycles in one process.
    Both amounts are proven to remain within the account's credit limit (193.00 + 2 x
    504.77 = 1202.54 <= 2065.00), so both must POST rather than the second rejecting.

    Parameters
    ----------
    cobol_runner : tests.helpers.cobol_runner.CobolRunner
        Per-test runner (``cobol_runner`` fixture) bound to a fresh isolated workspace.
    repo_root : pathlib.Path
        Repository root (``repo_root`` fixture) used to locate the ``happy_path`` fixtures.

    Returns
    -------
    None

    Raises
    ------
    AssertionError
        If either transaction fails to post, if the processed/rejected counters or
        RETURN-CODE diverge from "2 processed, 0 rejected, RC=0", or if the exact
        fixed-point account/category balances (original + TWICE the amount) or the two
        posted ``TRANFILE`` records are not observed.
    Skipped
        (via :func:`pytest.skip`) if the ``happy_path`` fixtures or the compiled program
        are unavailable.
    """
    scenario_dir = _posting_fixture_dir(repo_root, "happy_path")
    dailytran = _resolve_fixture(
        scenario_dir, "dailytran.txt", "DALYTRAN.txt", "DALYTRAN", "dailytran"
    )
    cardxref = _resolve_fixture(
        scenario_dir, "cardxref.txt", "XREFFILE.txt", "cardxref", "xref.txt"
    )
    acctdata = _resolve_fixture(
        scenario_dir, "acctdata.txt", "ACCTFILE.txt", "acctdat.txt", "acctdata"
    )
    tcatbal = _resolve_fixture(
        scenario_dir, "tcatbal.txt", "TCATBALF.txt", "tcatbalf.txt", "tcatbal"
    )

    # ---- Derive the single seeded transaction + build a distinct-ID duplicate. ----
    tran_records = _read_fixture_records(dailytran, DALYTRAN_LAYOUT.reclen)
    assert len(tran_records) == 1, (
        f"happy_path is expected to seed exactly one transaction, got {len(tran_records)}"
    )
    rec1 = tran_records[0]
    # WHY mutate ONLY the 16-char DALYTRAN-ID prefix (Assumption + Trade-off): CVTRA06Y
    # puts DALYTRAN-ID at offset 0, width 16 (record_codec: "key = DALYTRAN-ID(16) @
    # offset 0"). 2000-POST-TRANSACTION moves DALYTRAN-ID verbatim into the indexed
    # TRANFILE's TRAN-ID key, so a DUPLICATE id would make the second WRITE collide
    # (a different, non-posting path). Giving the copy a distinct id (…3580 -> …3581)
    # while leaving every other byte identical keeps it a fully valid, same-account,
    # same-category transaction -- so the ONLY behavioural difference under test is the
    # loop iterating twice, not any change in validation/posting logic.
    orig_id = rec1[:16]
    dup_id = orig_id[:-1] + ("1" if orig_id[-1] != "1" else "2")
    rec2 = dup_id + rec1[16:]
    assert len(rec2) == DALYTRAN_LAYOUT.reclen, "duplicate record must stay 350 bytes"

    # Stage the 2-record DALYTRAN through a throwaway temp file (load_sequential COPIES
    # the content into the workspace, so the source can be removed immediately after).
    # WHY one terminator per row: load_sequential strips exactly one trailing terminator
    # per row (fixture-format law #1), so "rec1\nrec2\n" stages as a clean 2 x 350 blob.
    tmp = tempfile.NamedTemporaryFile(
        mode="w", suffix=".dailytran", encoding="latin-1", delete=False
    )
    try:
        tmp.write(rec1 + "\n" + rec2 + "\n")
        tmp.close()
        # INDEXED inputs (same geometry rationale as _stage_inputs_and_run).
        cobol_runner.load_input("XREFFILE", cardxref, layout="XREF", alternate_keys=())
        cobol_runner.load_input("ACCTFILE", acctdata, layout="ACCOUNT")
        cobol_runner.load_input("TCATBALF", tcatbal, layout="TCATBAL")
        cobol_runner.load_sequential("DALYTRAN", Path(tmp.name), reclen=DALYTRAN_LAYOUT.reclen)
        result = cobol_runner.run(_PROGRAM)
    finally:
        # WHY unlink in finally (Assumption): the workspace copy is authoritative once
        # staged, so the source temp file is pure scratch; removing it unconditionally
        # avoids leaking a file per test run without affecting the staged input.
        os.unlink(tmp.name)

    # ---- Both transactions must POST (RC=0, 2 processed, 0 rejected). ----
    assert result.returncode == 0, (
        f"expected RC=0 (both post), got {result.returncode}\nstdout:\n{result.stdout}"
    )
    assert _parse_counter(result.stdout, _COUNTER_PROCESSED) == 2, (
        "the driver loop must read and process BOTH transactions\n" + result.stdout
    )
    assert _parse_counter(result.stdout, _COUNTER_REJECTED) == 0, (
        "neither transaction is over-limit/expired, so none may reject\n" + result.stdout
    )

    # ---- Exact fixed-point balances: original + TWICE the amount (Decimal). ----
    acct_records = _read_fixture_records(acctdata, ACCOUNT_LAYOUT.reclen)
    orig_account = ACCOUNT_LAYOUT.decode(acct_records[0])
    account_key = ACCOUNT_LAYOUT.key_of(acct_records[0])
    acct_id = int(orig_account["ACCT-ID"])
    tran = DALYTRAN_LAYOUT.decode(rec1)
    amount = tran["DALYTRAN-AMT"]  # Decimal, never float
    # WHY 2 * amount (Refactoring Rationale): the two staged transactions are byte-identical
    # apart from the id, so each applies the SAME DALYTRAN-AMT; posting both must move the
    # account master (2800) and the category balance (2700-B) by exactly twice the amount.
    expected_balance = orig_account["ACCT-CURR-BAL"] + amount + amount

    new_acct_records = cobol_runner.unload_output("ACCTFILE", layout="ACCOUNT")
    new_account = _find_record(new_acct_records, ACCOUNT_LAYOUT, account_key)
    assert new_account is not None, "updated ACCTFILE record not found on read-back"
    assert new_account["ACCT-CURR-BAL"] == expected_balance, (
        f"account balance must be orig + 2 x amount ({expected_balance}), "
        f"got {new_account['ACCT-CURR-BAL']}"
    )

    cat_key = _category_key(acct_id, tran)
    orig_cat_records = _read_fixture_records(tcatbal, TCATBAL_LAYOUT.reclen)
    orig_cat_record = _find_record(orig_cat_records, TCATBAL_LAYOUT, cat_key)
    base_cat = (
        orig_cat_record["TRAN-CAT-BAL"] if orig_cat_record is not None else Decimal("0.00")
    )
    expected_cat_balance = base_cat + amount + amount
    new_cat_records = cobol_runner.unload_output("TCATBALF", layout="TCATBAL")
    new_cat = _find_record(new_cat_records, TCATBAL_LAYOUT, cat_key)
    assert new_cat is not None, "TCATBALF category row not found on read-back"
    assert new_cat["TRAN-CAT-BAL"] == expected_cat_balance, (
        f"category balance must be orig + 2 x amount ({expected_cat_balance}), "
        f"got {new_cat['TRAN-CAT-BAL']}"
    )

    # ---- Both transactions posted to TRANFILE under their distinct ids. ----
    posted = _read_posted(cobol_runner)
    posted_ids = {rec["TRAN-ID"] for rec in posted}
    assert orig_id in posted_ids and dup_id in posted_ids, (
        f"expected both ids {orig_id!r} and {dup_id!r} in TRANFILE, got {sorted(posted_ids)}"
    )
    for rec in posted:
        if rec["TRAN-ID"] in (orig_id, dup_id):
            assert rec["TRAN-AMT"] == amount, (
                f"posted tran {rec['TRAN-ID']!r} amount {rec['TRAN-AMT']} != {amount}"
            )


def test_reject_then_post_continues_processing(cobol_runner, repo_root):
    """A rejected transaction does NOT halt the run: a later valid one still posts.

    Purpose
    -------
    Prove the mainframe-batch invariant that CBTRN02C's driver loop keeps processing
    after a soft reject -- it writes the bad record to ``DALYREJS``, sets
    ``RETURN-CODE`` 4, and *continues* to the next transaction rather than aborting.
    The DALYTRAN is staged with the REJECT first (an over-limit copy of the
    ``happy_path`` transaction, amount 5000.00 > the 2065.00 credit limit) followed by
    the original valid transaction, so the assertion that the valid one still posts can
    only hold if the loop advanced *past* the reject. This exercises the
    reject-then-continue branch that every single-transaction reject scenario (reject
    immediately followed by EOF) leaves uncovered, and is the strict counterpart to
    :func:`test_multiple_transactions_post_in_one_run` (which proves continue-after-post).

    Parameters
    ----------
    cobol_runner : tests.helpers.cobol_runner.CobolRunner
        Per-test runner (``cobol_runner`` fixture) bound to a fresh isolated workspace.
    repo_root : pathlib.Path
        Repository root (``repo_root`` fixture) used to locate the ``happy_path`` fixtures.

    Returns
    -------
    None

    Raises
    ------
    AssertionError
        If the run does not report exactly "2 processed, 1 rejected, RC=4", if the
        reject is not reason ``0102`` / ``OVERLIMIT TRANSACTION``, if the valid
        transaction fails to post (or the over-limit copy posts), or if the account /
        category balances are not moved by EXACTLY the one posted amount (proving the
        reject neither posted nor corrupted the masters).
    Skipped
        (via :func:`pytest.skip`) if the ``happy_path`` fixtures or the compiled program
        are unavailable.
    """
    scenario_dir = _posting_fixture_dir(repo_root, "happy_path")
    dailytran = _resolve_fixture(
        scenario_dir, "dailytran.txt", "DALYTRAN.txt", "DALYTRAN", "dailytran"
    )
    cardxref = _resolve_fixture(
        scenario_dir, "cardxref.txt", "XREFFILE.txt", "cardxref", "xref.txt"
    )
    acctdata = _resolve_fixture(
        scenario_dir, "acctdata.txt", "ACCTFILE.txt", "acctdat.txt", "acctdata"
    )
    tcatbal = _resolve_fixture(
        scenario_dir, "tcatbal.txt", "TCATBALF.txt", "tcatbalf.txt", "tcatbal"
    )

    # ---- Derive the valid transaction + build an over-limit copy that must reject. ----
    tran_records = _read_fixture_records(dailytran, DALYTRAN_LAYOUT.reclen)
    assert len(tran_records) == 1, (
        f"happy_path is expected to seed exactly one transaction, got {len(tran_records)}"
    )
    rec_post = tran_records[0]
    # WHY reject via amount, not a bad card/date (Alternatives Considered): reason 102 is
    # the only reject that leaves the record otherwise fully valid (real card in XREF,
    # real account, not expired), so it isolates the over-limit branch WITHOUT also
    # tripping 100/101/103. WHY 5000.00 (Assumption): the happy_path account has
    # ACCT-CURR-CYC-CREDIT = ACCT-CURR-CYC-DEBIT = 0.00, so 1500-B computes
    # WS-TEMP-BAL = 0 - 0 + amount = amount; 5000.00 > ACCT-CREDIT-LIMIT (2065.00) makes
    # the `ACCT-CREDIT-LIMIT >= WS-TEMP-BAL` guard FALSE with a wide, robust margin that
    # survives any cycle-field bookkeeping change. Mutating ONLY the id + amount (via the
    # single-sourced record_codec, proven byte-faithful) keeps the copy a genuine
    # over-limit transaction rather than a malformed record.
    decoded = dict(DALYTRAN_LAYOUT.decode(rec_post))
    orig_id = rec_post[:16]
    rej_id = orig_id[:-1] + ("1" if orig_id[-1] != "1" else "2")
    decoded["DALYTRAN-ID"] = rej_id
    decoded["DALYTRAN-AMT"] = Decimal("5000.00")
    rec_reject = DALYTRAN_LAYOUT.encode(decoded)
    assert len(rec_reject) == DALYTRAN_LAYOUT.reclen, "reject record must stay 350 bytes"
    assert rej_id != orig_id, "reject copy must carry a distinct DALYTRAN-ID"

    # Stage DALYTRAN with the REJECT FIRST so a subsequent post proves loop continuation.
    tmp = tempfile.NamedTemporaryFile(
        mode="w", suffix=".dailytran", encoding="latin-1", delete=False
    )
    try:
        # WHY reject-first ordering (Refactoring Rationale): placing the reject at
        # iteration 1 and the valid post at iteration 2 means "post observed" is a direct
        # witness that the driver did NOT stop at the reject -- the ordering IS the test.
        tmp.write(rec_reject + "\n" + rec_post + "\n")
        tmp.close()
        cobol_runner.load_input("XREFFILE", cardxref, layout="XREF", alternate_keys=())
        cobol_runner.load_input("ACCTFILE", acctdata, layout="ACCOUNT")
        cobol_runner.load_input("TCATBALF", tcatbal, layout="TCATBAL")
        cobol_runner.load_sequential(
            "DALYTRAN", Path(tmp.name), reclen=DALYTRAN_LAYOUT.reclen
        )
        result = cobol_runner.run(_PROGRAM)
    finally:
        # WHY unlink in finally (Assumption): load_sequential copies content into the
        # workspace, so the source temp file is pure scratch and is removed unconditionally
        # to avoid leaking one file per run without affecting the staged input.
        os.unlink(tmp.name)

    # ---- Both records are read (processed=2); exactly one rejects; RC=4 (soft reject). ----
    assert result.returncode == 4, (
        f"a soft reject must set RETURN-CODE=4, got {result.returncode}\n"
        f"stdout:\n{result.stdout}"
    )
    assert _parse_counter(result.stdout, _COUNTER_PROCESSED) == 2, (
        "the driver must READ both records even though the first rejects\n" + result.stdout
    )
    assert _parse_counter(result.stdout, _COUNTER_REJECTED) == 1, (
        "exactly the over-limit record must reject; the valid one must not\n"
        + result.stdout
    )

    # ---- The reject is reason 102 / OVERLIMIT and nothing else. ----
    rejects = _read_rejects(result)
    assert len(rejects) == 1, f"expected exactly one DALYREJS record, got {len(rejects)}"
    reason, desc = _decode_reject(rejects[0])
    assert reason == 102, f"expected reject reason 102, got {reason}"
    assert _REJECT_DESCRIPTIONS[102] in desc, (
        f"reject description {desc!r} must contain {_REJECT_DESCRIPTIONS[102]!r}"
    )

    # ---- Only the VALID transaction posted (the over-limit copy did not). ----
    posted = _read_posted(cobol_runner)
    posted_ids = {rec["TRAN-ID"] for rec in posted}
    assert orig_id in posted_ids, (
        f"the valid transaction {orig_id!r} must post after the earlier reject, "
        f"got {sorted(posted_ids)}"
    )
    assert rej_id not in posted_ids, (
        f"the over-limit transaction {rej_id!r} must NOT post, got {sorted(posted_ids)}"
    )

    # ---- Masters moved by EXACTLY the one posted amount (reject neither posts nor rolls
    #      back the good post). ----
    acct_records = _read_fixture_records(acctdata, ACCOUNT_LAYOUT.reclen)
    orig_account = ACCOUNT_LAYOUT.decode(acct_records[0])
    account_key = ACCOUNT_LAYOUT.key_of(acct_records[0])
    acct_id = int(orig_account["ACCT-ID"])
    tran = DALYTRAN_LAYOUT.decode(rec_post)
    amount = tran["DALYTRAN-AMT"]  # Decimal, never float
    # WHY orig + amount (Refactoring Rationale): only ONE transaction posted, so the
    # account master (2800) and category balance (2700) must each move by exactly one
    # amount -- proving the reject contributed nothing to the balances.
    expected_balance = orig_account["ACCT-CURR-BAL"] + amount

    new_acct_records = cobol_runner.unload_output("ACCTFILE", layout="ACCOUNT")
    new_account = _find_record(new_acct_records, ACCOUNT_LAYOUT, account_key)
    assert new_account is not None, "updated ACCTFILE record not found on read-back"
    assert new_account["ACCT-CURR-BAL"] == expected_balance, (
        f"account balance must be orig + ONE amount ({expected_balance}), "
        f"got {new_account['ACCT-CURR-BAL']}"
    )

    cat_key = _category_key(acct_id, tran)
    orig_cat_records = _read_fixture_records(tcatbal, TCATBAL_LAYOUT.reclen)
    orig_cat_record = _find_record(orig_cat_records, TCATBAL_LAYOUT, cat_key)
    base_cat = (
        orig_cat_record["TRAN-CAT-BAL"] if orig_cat_record is not None else Decimal("0.00")
    )
    expected_cat_balance = base_cat + amount
    new_cat_records = cobol_runner.unload_output("TCATBALF", layout="TCATBAL")
    new_cat = _find_record(new_cat_records, TCATBAL_LAYOUT, cat_key)
    assert new_cat is not None, "TCATBALF category row not found on read-back"
    assert new_cat["TRAN-CAT-BAL"] == expected_cat_balance, (
        f"category balance must be orig + ONE amount ({expected_cat_balance}), "
        f"got {new_cat['TRAN-CAT-BAL']}"
    )

