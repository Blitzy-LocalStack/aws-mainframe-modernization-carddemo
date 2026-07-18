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

