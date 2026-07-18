"""Layer-2 integration tests for the CardDemo pre-post cross-reference program ``CBTRN01C``.

Purpose
-------
``CBTRN01C`` is CardDemo's daily-transaction **pre-post cross-reference validation
and display** program (``app/cbl/CBTRN01C.cbl``): it reads the daily-transaction
file sequentially and, for each record, resolves the card through the card
cross-reference (``XREFFILE``) and then reads the corresponding account
(``ACCTFILE``), DISPLAYing a diagnostic for every outcome. It is **read-only** --
it performs no ``WRITE``/``REWRITE``/``DELETE`` and mutates no balance -- so unlike
the monetary ``CBTRN02C``/``CBACT04C`` programs its observable contract is its
**console diagnostics** and its process ``RETURN-CODE``.

This module closes QA finding **F-2 (MAJOR)**: prior to it, ``CBTRN01C`` was the
sole in-scope batch program with *zero* asserting test coverage, violating the
AAP's explicit "at least one asserting test per batch program" mandate
(AAP Sections 0.1.4 and 0.7.1). It drives the *compiled, unmodified* program
against three deterministic per-scenario fixtures under
``tests/fixtures/prepost/`` and asserts, to financial-enterprise rigor, on the
program's every observable output for each of its three cross-reference paths.

**Production COBOL is REFERENCE only and is never modified by these tests**
(AAP Section 0.8.2).

Test layer & binding contract
------------------------------
Marked ``@pytest.mark.integration``. Each test uses the shared ``cobol_runner``
fixture (``tests/conftest.py``), which binds every GnuCOBOL ``SELECT ... ASSIGN TO
<NAME>`` external name to a file inside a fresh, isolated per-test workspace. The
verified ``CBTRN01C`` file contract (transcribed from ``app/cbl/CBTRN01C.cbl``)
is::

    ASSIGN     role                 organization        open    reclen  key(len@off)
    DALYTRAN   daily-tran input     SEQUENTIAL(fixed)   INPUT    350    -- (no key)
    XREFFILE   card lookup          INDEXED             INPUT     50    16 @ 0 (card-num)
    ACCTFILE   account lookup       INDEXED             INPUT    300    11 @ 0 (acct-id)
    CUSTFILE   opened, not read     INDEXED             INPUT    500     9 @ 0 (cust-id)
    CARDFILE   opened, not read     INDEXED             INPUT    150    16 @ 0 (card-num)
    TRANFILE   opened, not read     INDEXED             INPUT    350    16 @ 0 (trans-id)

Only ``DALYTRAN`` (sequential), ``XREFFILE``, and ``ACCTFILE`` are read in the main
loop. ``CUSTFILE``/``CARDFILE``/``TRANFILE`` are ``OPEN INPUT``'d (and abend the
program on a failed OPEN) but never ``READ``, so they must exist as *openable*
valid indexed files -- which is why the tests load **empty** indexed files for them
(see :func:`_stage_empty_master`).

Validation semantics (transcribed verbatim from ``app/cbl/CBTRN01C.cbl``)
-------------------------------------------------------------------------
* ``2000-LOOKUP-XREF`` reads ``XREF-FILE`` by ``DALYTRAN-CARD-NUM``.
  * ``NOT INVALID KEY`` -> DISPLAYs ``SUCCESSFUL READ OF XREF`` plus the resolved
    ``CARD NUMBER:`` / ``ACCOUNT ID :`` / ``CUSTOMER ID:`` lines, and leaves
    ``WS-XREF-READ-STATUS = 0``.
  * ``INVALID KEY`` -> DISPLAYs ``INVALID CARD NUMBER FOR XREF`` and sets
    ``WS-XREF-READ-STATUS = 4``.
* Back in ``MAIN-PARA``: when ``WS-XREF-READ-STATUS = 0`` the program performs
  ``3000-READ-ACCOUNT`` (read ``ACCOUNT-FILE`` by the resolved ``XREF-ACCT-ID``):
  * ``NOT INVALID KEY`` -> ``SUCCESSFUL READ OF ACCOUNT FILE``.
  * ``INVALID KEY`` -> ``INVALID ACCOUNT NUMBER FOUND`` and then, because
    ``WS-ACCT-READ-STATUS NOT = 0``, ``ACCOUNT <acct-id> NOT FOUND``.
  When ``WS-XREF-READ-STATUS NOT = 0`` it instead DISPLAYs ``CARD NUMBER <card>
  COULD NOT BE VERIFIED. SKIPPING TRANSACTION ID-<tran-id>`` and never reads the
  account.
* The three cross-reference outcomes are all *normal* control flow (a missing
  cross-reference is a skip/not-found report, **not** an I/O error), so the run
  completes through the normal ``GOBACK`` with ``RETURN-CODE = 0``. Only a genuine
  file OPEN/read failure drives the ``Z-ABEND-PROGRAM`` path (``CEE3ABD`` code 999).

Loop control-flow characteristic (asserted-around, never "fixed")
-----------------------------------------------------------------
``CBTRN01C``'s main loop issues the cross-reference lookup once *more* on the
end-of-file pass: the ``PERFORM 2000-LOOKUP-XREF`` (and the account read) sit
*outside* the ``IF END-OF-DAILY-TRANS-FILE = 'N'`` guard that gates the
``DISPLAY DALYTRAN-RECORD``. Consequently, for a single input transaction the
``SUCCESSFUL READ OF …`` / skip / not-found diagnostic block is emitted **twice**
(once for the real read, once on the EOF pass over the stale record area). This is
**production behaviour** in a REFERENCE-only program (AAP Section 0.8.2); these
tests therefore assert on the *presence* of the expected markers and the *absence*
of the mutually-exclusive ones, never on an exact repetition count -- an
assertion that is both deterministic and robust to the quirk.

Determinism & isolation
------------------------
Each test provisions and reads only its own workspace, so tests are independent and
parallel-safe under ``pytest-xdist -n auto``. Cross-reference outcomes are entirely
data-driven (they depend only on which keys the fixtures contain), so the
assertions never depend on the wall clock. Every expected card/account/customer
string is **derived from the fixtures themselves** via the single-sourced
``record_codec`` field offsets -- never hard-coded -- so a fixture change surfaces
here instead of silently passing.

Explainability (AAP Section 0.10.1 -- hard review gate)
-------------------------------------------------------
Every function carries a Purpose/Parameters/Returns/Raises docstring, and each
non-obvious decision is annotated with a WHY comment (Assumption / Trade-off /
Alternatives Considered / Refactoring Rationale). No monetary arithmetic occurs in
this module (``CBTRN01C`` is non-monetary), so there is no ``float`` here by
construction.

There is **no** ``__init__.py`` anywhere under ``tests/``; the tree resolves as a
PEP 420 namespace package via ``PYTHONPATH=<repo_root>`` (the conftest bootstraps it).
"""

from __future__ import annotations

from pathlib import Path

import pytest

# Import ONLY the single-sourced record layouts this module needs. DALYTRAN/XREF are
# decoded to DERIVE the expected DISPLAY strings from the fixtures (so no magic value
# is transcribed); ACCOUNT/CUSTOMER/CARD/TRAN supply the exact indexed geometry the
# loader must use so a wrong key width can never cause a spurious FILE STATUS 39 OPEN.
# WHY import the layout constants rather than raw offsets (Alternatives Considered):
# the layouts carry the copybook geometry verbatim, so the test never transcribes an
# offset/width itself and can never drift from app/cpy/.
from tests.helpers.record_codec import (
    ACCOUNT_LAYOUT,
    CARD_LAYOUT,
    CUSTOMER_LAYOUT,
    DALYTRAN_LAYOUT,
    TRAN_LAYOUT,
    XREF_LAYOUT,
)

# WHY (suite contract): the integration marker is registered in tests/pytest.ini with
# --strict-markers, so a bare/mistyped marker is a hard error rather than a silent
# no-op. Marking at module scope tags every test here as an integration test.
pytestmark = pytest.mark.integration

# ---------------------------------------------------------------------------
# Program & domain constants (single source of truth for this module).
# ---------------------------------------------------------------------------
# The compiled program under test and the fixture domain directory it is driven from.
_PROGRAM = "CBTRN01C"
_DOMAIN = "prepost"

# The exact DISPLAY marker strings CBTRN01C emits, transcribed verbatim from
# app/cbl/CBTRN01C.cbl. These are the specification: the tests encode them, they do
# NOT redefine them. Grouped by the branch each marker proves.
_MARK_START = "START OF EXECUTION OF PROGRAM CBTRN01C"
_MARK_END = "END OF EXECUTION OF PROGRAM CBTRN01C"
_MARK_XREF_OK = "SUCCESSFUL READ OF XREF"
_MARK_ACCT_OK = "SUCCESSFUL READ OF ACCOUNT FILE"
_MARK_XREF_BAD = "INVALID CARD NUMBER FOR XREF"
_MARK_ACCT_BAD = "INVALID ACCOUNT NUMBER FOUND"
_MARK_SKIP = "COULD NOT BE VERIFIED"


# ---------------------------------------------------------------------------
# Fixture resolution helpers.
# ---------------------------------------------------------------------------
def _prepost_fixture_dir(repo_root: Path, scenario: str) -> Path:
    """Return the fixture directory for a prepost scenario, or skip if absent.

    Purpose
    -------
    Locate ``tests/fixtures/prepost/<scenario>/`` relative to the repository root and
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
        (via :func:`pytest.skip`) if the directory does not exist -- treated as an
        environment condition (fixtures not present), not a test defect.
    """
    path = repo_root / "tests" / "fixtures" / _DOMAIN / scenario
    if not path.is_dir():
        # WHY (Assumption): fixtures are a separate committed artifact; treat "not yet
        # present" as a clean skip (green collection everywhere) rather than a failure.
        pytest.skip(f"prepost fixtures not found for scenario {scenario!r}: {path}")
    return path


def _resolve_fixture(scenario_dir: Path, *candidates: str) -> Path:
    """Return the first existing fixture file among candidate names, or skip.

    Purpose
    -------
    Resolve a logical input (daily-transaction / xref / account) to a concrete file
    without hard-coding a single filename, tolerating cosmetic naming differences.

    Parameters
    ----------
    scenario_dir : pathlib.Path
        The scenario fixture directory (from :func:`_prepost_fixture_dir`).
    *candidates : str
        One or more candidate file names, most-preferred first.

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
    # WHY (Trade-off): an ordered candidate search decouples this test from the exact
    # on-disk filenames while still preferring the documented names; a single
    # hard-coded name would make the suite brittle to a cosmetic rename.
    for name in candidates:
        candidate = scenario_dir / name
        if candidate.is_file():
            return candidate
    pretty = ", ".join(candidates)
    pytest.skip(f"no fixture found in {scenario_dir} among candidates: {pretty}")


def _read_single_record(path: Path, reclen: int) -> str:
    """Read a flat fixed-width fixture and return its first ``reclen``-char record.

    Purpose
    -------
    Decode a scenario's single-record input fixture so the *expected* DISPLAY
    strings (card number, resolved account/customer ids) can be derived from the
    fixture bytes rather than hard-coded. The prepost fixtures each carry exactly one
    logical record (plus at most one trailing EOF newline).

    Parameters
    ----------
    path : pathlib.Path
        The flat fixture file to read.
    reclen : int
        The fixed record width in characters.

    Returns
    -------
    str
        The first record normalised to exactly ``reclen`` characters (right-padded
        with spaces if the stored row is short, truncated if long).

    Raises
    ------
    OSError
        If the file cannot be read.
    AssertionError
        If the fixture is empty -- a scenario used for value-derivation must contain a
        record, so an empty file is a fixture defect the caller must see.
    """
    # WHY encoding="latin-1" (Assumption): CardDemo records are fixed-width bytes with
    # zoned-decimal overpunch that are frequently not valid UTF-8; latin-1 is the
    # identity byte<->codepoint map so every byte round-trips and slicing by offset is
    # exact. This reader feeds only the expected-value derivation (never the program
    # input, which the runner stages byte-exactly), so right-padding a short row is a
    # safe normalisation that never changes what the program sees.
    raw = path.read_text(encoding="latin-1").replace("\r\n", "\n").replace("\r", "\n")
    if raw.endswith("\n"):
        raw = raw[:-1]  # drop exactly one trailing EOF newline (the shipped form)
    assert raw, f"fixture {path} is empty; a value-derivation fixture must have a record"
    first = raw.split("\n", 1)[0]
    if len(first) < reclen:
        first = first.ljust(reclen)
    return first[:reclen]


def _field(layout, record: str, name: str) -> str:
    """Return the raw (undecoded) bytes of a named field from a record.

    Purpose
    -------
    Extract a field's *exact on-disk characters* -- e.g. the zero-padded
    ``XREF-ACCT-ID`` ``00000000007`` -- using the single-sourced ``record_codec``
    field offsets, so an expected DISPLAY string is derived from the fixture without
    transcribing any offset here.

    WHY raw slice rather than ``layout.decode`` (Refactoring Rationale): ``decode``
    turns a numeric ``9(n)`` field into an ``int``, which would drop the leading
    zeros that ``CBTRN01C`` DISPLAYs (it prints the raw ``PIC 9(11)`` field
    ``00000000007``, not ``7``). Slicing the raw record preserves the exact bytes the
    program echoes to the console, which is what the assertion must match.

    Parameters
    ----------
    layout : tests.helpers.record_codec.RecordLayout
        The record layout that defines the field's offset and length.
    record : str
        The ``reclen``-character record to slice.
    name : str
        The copybook field name (e.g. ``"XREF-ACCT-ID"``).

    Returns
    -------
    str
        The field's raw characters (``record[field.start:field.end]``).

    Raises
    ------
    KeyError
        If ``name`` is not a field of ``layout`` (propagated from ``layout.field``).
    """
    field = layout.field(name)
    return record[field.start:field.end]


def _stage_empty_master(cobol_runner: object, assign_name: str, layout: object) -> None:
    """Stage an empty indexed file for an ASSIGN name that is opened but never read.

    Purpose
    -------
    ``CBTRN01C`` ``OPEN INPUT``s the customer, card, and transaction masters and
    abends if any OPEN fails (FILE STATUS != ``00`` -> ``Z-ABEND-PROGRAM``), yet its
    main loop never ``READ``s them. They therefore need to exist only as *openable*,
    correctly-keyed indexed files. This helper materialises an **empty** indexed file
    (zero records) at the workspace path bound to ``assign_name``, using the layout's
    exact record length and primary-key length so GnuCOBOL's OPEN INPUT sees a
    matching file attribute set (a wrong key width would raise FILE STATUS 39).

    WHY empty rather than a populated fixture (Trade-off): shipping three more static
    fixtures whose bytes the program never reads would add maintenance surface and
    imply (falsely) that their contents matter. An empty index is the minimal,
    semantically-honest input for an opened-not-read file, and the suite's loader
    explicitly supports a zero-record index (QA edge case: "genuinely empty 0-byte
    index file -> accepted, 0 records").

    Parameters
    ----------
    cobol_runner : object
        The ``CobolRunner`` from the ``cobol_runner`` fixture (its ``workspace`` hosts
        the scratch empty flat file and its ``load_input`` performs the flat->indexed
        load).
    assign_name : str
        The ASSIGN external name to bind (``"CUSTFILE"``/``"CARDFILE"``/``"TRANFILE"``).
    layout : tests.helpers.record_codec.RecordLayout
        The record layout whose ``reclen``/``key_length`` geometry the empty index is
        built with (must match ``CBTRN01C``'s SELECT for the file).

    Returns
    -------
    None

    Raises
    ------
    tests.helpers.vsam_loader.VsamLoadError
        If the (empty) load fails, propagated unchanged from ``load_input``.
    """
    # Create a zero-byte flat source inside this test's isolated workspace. WHY here
    # (Assumption): the workspace is per-test and torn down on teardown, so the scratch
    # file never leaks and never collides with a parallel worker. A single empty source
    # is reused for all three masters because an empty index carries no records to key.
    empty_flat = Path(cobol_runner.workspace) / f"_empty_{assign_name}.flat"
    empty_flat.write_bytes(b"")
    # alternate_keys=() : force a primary-key-only index. CBTRN01C's SELECTs for these
    # three files declare only a primary RECORD KEY (no ALTERNATE), so building any
    # alternate index would make the physical key set disagree with the program's SELECT
    # and risk FILE STATUS 39 on OPEN.
    cobol_runner.load_input(
        assign_name,
        str(empty_flat),
        reclen=layout.reclen,
        key_length=layout.key_length,
        alternate_keys=(),
    )


# ---------------------------------------------------------------------------
# Staging + run.
# ---------------------------------------------------------------------------
def _stage_inputs_and_run(cobol_runner: object, repo_root: Path, scenario: str) -> object:
    """Stage the six ``CBTRN01C`` inputs into the workspace and run the program.

    Purpose
    -------
    Perform the common per-scenario setup exactly once: resolve the three meaningful
    fixtures, load the read INDEXED inputs (``XREFFILE``/``ACCTFILE``) with their
    exact key geometry, stage empty indexed files for the opened-not-read masters
    (``CUSTFILE``/``CARDFILE``/``TRANFILE``), stage the SEQUENTIAL ``DALYTRAN`` input
    byte-exactly (no trailing newline), execute the compiled program, and return the
    :class:`RunResult`.

    Parameters
    ----------
    cobol_runner : object
        The ``CobolRunner`` from the ``cobol_runner`` fixture.
    repo_root : pathlib.Path
        The repository root (from the ``repo_root`` fixture).
    scenario : str
        The scenario name whose fixtures drive the run (``"happy_path"``,
        ``"unmatched_card"``, or ``"unmatched_account"``).

    Returns
    -------
    tests.helpers.cobol_runner.RunResult
        The captured run result (return code + stdout/stderr + ASSIGN path map).

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
    scenario_dir = _prepost_fixture_dir(repo_root, scenario)
    dailytran = _resolve_fixture(
        scenario_dir, "dailytran.txt", "DALYTRAN.txt", "DALYTRAN", "dailytran"
    )
    cardxref = _resolve_fixture(
        scenario_dir, "cardxref.txt", "XREFFILE.txt", "cardxref", "xref.txt"
    )
    acctdata = _resolve_fixture(
        scenario_dir, "acctdata.txt", "ACCTFILE.txt", "acctdat.txt", "acctdata"
    )

    # Read INDEXED inputs. WHY layout= (single-sourced geometry): the key widths come
    # from record_codec (XREF 50/16, ACCOUNT 300/11) so they can never drift from the
    # copybooks. WHY alternate_keys=() on XREFFILE (Assumption): CBTRN01C's SELECT for
    # XREF-FILE declares only the primary card-number key -- no ALTERNATE -- even though
    # the XREF layout models an account-id alternate index (CBACT04C needs it). Forcing
    # a primary-key-only file here keeps the physical key set in step with THIS program's
    # SELECT and avoids a FILE STATUS 39 on OPEN.
    cobol_runner.load_input("XREFFILE", str(cardxref), layout="XREF", alternate_keys=())
    cobol_runner.load_input("ACCTFILE", str(acctdata), layout="ACCOUNT")

    # Opened-not-read masters: empty, correctly-keyed indexed files (see helper).
    _stage_empty_master(cobol_runner, "CUSTFILE", CUSTOMER_LAYOUT)
    _stage_empty_master(cobol_runner, "CARDFILE", CARD_LAYOUT)
    _stage_empty_master(cobol_runner, "TRANFILE", TRAN_LAYOUT)

    # SEQUENTIAL input. WHY load_sequential(reclen=350) (fixture-format law): it strips
    # exactly one trailing terminator per row and writes a headerless N*350 blob, so a
    # shipped fixture's trailing newline can never be read as a spurious partial record
    # (which would abend CBTRN01C via its 'ERROR READING DAILY TRANSACTION FILE' path).
    cobol_runner.load_sequential("DALYTRAN", str(dailytran), reclen=DALYTRAN_LAYOUT.reclen)

    # Completion-aware execution (subprocess.run blocks to exit); check=False so the
    # return code is returned for explicit inspection rather than raised.
    return cobol_runner.run(_PROGRAM)


def _expected_ids(repo_root: Path, scenario: str) -> "dict[str, str]":
    """Derive the card / resolved-account / resolved-customer ids from the fixtures.

    Purpose
    -------
    Compute the exact DISPLAY-echoed identifier strings for a scenario straight from
    its fixture bytes, so assertions compare against fixture-derived values rather than
    hard-coded magic constants. The daily-transaction card is read from ``DALYTRAN``;
    the resolved account/customer are read from the ``XREF`` row keyed by that card
    (present only when the card is in the cross-reference).

    Parameters
    ----------
    repo_root : pathlib.Path
        The repository root (from the ``repo_root`` fixture).
    scenario : str
        The scenario whose fixtures to decode.

    Returns
    -------
    dict[str, str]
        ``{"card": <16-char card>, "tran_id": <16-char daily-tran id>,
        "acct": <11-char resolved account or "">, "cust": <9-char resolved customer or "">}``.
        ``acct``/``cust`` are empty strings when the transaction's card is absent from
        the cross-reference (the unmatched-card scenario).

    Raises
    ------
    Skipped
        (via the resolver helpers) if a fixture is missing.
    """
    scenario_dir = _prepost_fixture_dir(repo_root, scenario)
    dailytran = _resolve_fixture(
        scenario_dir, "dailytran.txt", "DALYTRAN.txt", "DALYTRAN", "dailytran"
    )
    cardxref = _resolve_fixture(
        scenario_dir, "cardxref.txt", "XREFFILE.txt", "cardxref", "xref.txt"
    )
    dt = _read_single_record(dailytran, DALYTRAN_LAYOUT.reclen)
    card = _field(DALYTRAN_LAYOUT, dt, "DALYTRAN-CARD-NUM")
    tran_id = _field(DALYTRAN_LAYOUT, dt, "DALYTRAN-ID")

    # Build the card->(cust, acct) map from the XREF fixture, then look up THIS card.
    # WHY read every XREF row (Assumption): a scenario may carry an unrelated xref row
    # (the unmatched-card case), so we must key by the transaction's own card rather
    # than assuming the first row matches.
    acct = cust = ""
    raw = cardxref.read_text(encoding="latin-1").replace("\r\n", "\n").replace("\r", "\n")
    if raw.endswith("\n"):
        raw = raw[:-1]
    for row in raw.split("\n"):
        if not row:
            continue
        row = row.ljust(XREF_LAYOUT.reclen)[: XREF_LAYOUT.reclen]
        if _field(XREF_LAYOUT, row, "XREF-CARD-NUM") == card:
            acct = _field(XREF_LAYOUT, row, "XREF-ACCT-ID")
            cust = _field(XREF_LAYOUT, row, "XREF-CUST-ID")
            break
    return {"card": card, "tran_id": tran_id, "acct": acct, "cust": cust}


# ===========================================================================
# Tests -- one per cross-reference outcome (all three branches of CBTRN01C).
# ===========================================================================
def test_happy_path_card_and_account_resolve(cobol_runner, repo_root) -> None:
    """Happy path: the card resolves through XREF and the account is found.

    Purpose
    -------
    Drive ``CBTRN01C`` against a daily transaction whose card is present in the
    cross-reference and whose resolved account exists, and assert the program takes
    the fully-matched path: it DISPLAYs ``SUCCESSFUL READ OF XREF`` (with the resolved
    card/account/customer ids echoed) and ``SUCCESSFUL READ OF ACCOUNT FILE``, emits
    neither ``INVALID`` diagnostic, and exits ``RETURN-CODE = 0``. This is the
    "clean compile + asserting happy-path test" the AAP requires for every batch
    program (Sections 0.1.4 / 0.7.1), the core of QA finding F-2's remediation.

    Parameters
    ----------
    cobol_runner : tests.helpers.cobol_runner.CobolRunner
        Fresh isolated per-test runner fixture.
    repo_root : pathlib.Path
        Repository-root fixture (locates the fixtures).

    Returns
    -------
    None

    Raises
    ------
    AssertionError
        If the fully-matched path is not taken (any expected marker/id absent, any
        mutually-exclusive marker present, or a non-zero return code).
    """
    result = _stage_inputs_and_run(cobol_runner, repo_root, "happy_path")
    ids = _expected_ids(repo_root, "happy_path")
    out = result.stdout

    # RETURN-CODE: a normal completion (all lookups succeed) exits 0 per the rubric.
    assert result.returncode == 0, (
        f"{_PROGRAM} returned {result.returncode} (expected 0).\n"
        f"stdout:\n{out}\nstderr tail:\n{(result.stderr or '')[-1000:]}"
    )
    # Program bookends prove it ran to normal completion (not a truncated abend).
    assert _MARK_START in out, f"missing start banner.\nstdout:\n{out}"
    assert _MARK_END in out, f"missing end banner (did the program abend?).\nstdout:\n{out}"
    # Positive markers: both cross-reference reads succeeded.
    assert _MARK_XREF_OK in out, f"expected {_MARK_XREF_OK!r}.\nstdout:\n{out}"
    assert _MARK_ACCT_OK in out, f"expected {_MARK_ACCT_OK!r}.\nstdout:\n{out}"
    # The resolved ids are echoed verbatim (fixture-derived, not hard-coded). WHY the
    # exact 'CARD NUMBER: '/'ACCOUNT ID : '/'CUSTOMER ID: ' prefixes (Assumption):
    # CBTRN01C DISPLAYs each on the NOT-INVALID-KEY branch of 2000-LOOKUP-XREF, so their
    # presence pins that the SUCCESS branch echoed the correct resolved identifiers.
    assert f"CARD NUMBER: {ids['card']}" in out, f"resolved card id not echoed.\nstdout:\n{out}"
    assert f"ACCOUNT ID : {ids['acct']}" in out, f"resolved account id not echoed.\nstdout:\n{out}"
    assert f"CUSTOMER ID: {ids['cust']}" in out, f"resolved customer id not echoed.\nstdout:\n{out}"
    # Negative markers: none of the failure/skip diagnostics may appear on the happy path.
    for absent in (_MARK_XREF_BAD, _MARK_ACCT_BAD, _MARK_SKIP):
        assert absent not in out, (
            f"unexpected failure marker {absent!r} on the happy path.\nstdout:\n{out}"
        )


def test_unmatched_card_is_skipped(cobol_runner, repo_root) -> None:
    """Unmatched card: an XREF miss reports the transaction as unverifiable and skips it.

    Purpose
    -------
    Drive ``CBTRN01C`` against a daily transaction whose card is **absent** from the
    cross-reference, and assert the program takes the card-not-verified path: it
    DISPLAYs ``INVALID CARD NUMBER FOR XREF`` and ``CARD NUMBER <card> COULD NOT BE
    VERIFIED. SKIPPING TRANSACTION ID-<tran-id>``, never reads the account (so
    ``SUCCESSFUL READ OF ACCOUNT FILE`` is absent), and still exits ``RETURN-CODE = 0``
    (a missing cross-reference is a normal skip, not an abend). This is the
    unmatched-cross-reference case F-2 explicitly calls for.

    Parameters
    ----------
    cobol_runner : tests.helpers.cobol_runner.CobolRunner
        Fresh isolated per-test runner fixture.
    repo_root : pathlib.Path
        Repository-root fixture.

    Returns
    -------
    None

    Raises
    ------
    AssertionError
        If the skip path is not taken (expected markers absent, the account marker
        present, the wrong card/tran-id echoed, or a non-zero return code).
    """
    result = _stage_inputs_and_run(cobol_runner, repo_root, "unmatched_card")
    ids = _expected_ids(repo_root, "unmatched_card")
    out = result.stdout

    assert result.returncode == 0, (
        f"{_PROGRAM} returned {result.returncode} (expected 0; a missing cross-reference "
        f"is a skip, not an abend).\nstdout:\n{out}\nstderr tail:\n{(result.stderr or '')[-1000:]}"
    )
    assert _MARK_START in out and _MARK_END in out, f"missing banners.\nstdout:\n{out}"
    # The XREF read missed -> the invalid-card diagnostic and the skip report appear,
    # naming THIS transaction's card and id (fixture-derived).
    assert _MARK_XREF_BAD in out, f"expected {_MARK_XREF_BAD!r}.\nstdout:\n{out}"
    assert (
        f"CARD NUMBER {ids['card']} COULD NOT BE VERIFIED. SKIPPING TRANSACTION ID-{ids['tran_id']}"
        in out
    ), f"expected the skip line naming card {ids['card']} / tran {ids['tran_id']}.\nstdout:\n{out}"
    # The account is never read on this path, so its success marker must be absent. WHY
    # this is the load-bearing negative assertion (Assumption): it proves the skip
    # short-circuited BEFORE 3000-READ-ACCOUNT -- the whole point of the card-miss branch.
    assert _MARK_ACCT_OK not in out, (
        f"account should NOT be read after an XREF miss, but {_MARK_ACCT_OK!r} appeared."
        f"\nstdout:\n{out}"
    )
    assert _MARK_XREF_OK not in out, (
        f"XREF read should have MISSED, but {_MARK_XREF_OK!r} appeared.\nstdout:\n{out}"
    )


def test_unmatched_account_reports_not_found(cobol_runner, repo_root) -> None:
    """Unmatched account: the card resolves but the account is absent -> NOT FOUND.

    Purpose
    -------
    Drive ``CBTRN01C`` against a daily transaction whose card **is** in the
    cross-reference but whose resolved account is **absent** from the account master,
    and assert the program takes the account-not-found path: it DISPLAYs
    ``SUCCESSFUL READ OF XREF`` (the card resolved) followed by ``INVALID ACCOUNT
    NUMBER FOUND`` and ``ACCOUNT <acct-id> NOT FOUND``, does NOT DISPLAY
    ``SUCCESSFUL READ OF ACCOUNT FILE``, and still exits ``RETURN-CODE = 0``. This
    exercises ``CBTRN01C``'s third and final cross-reference branch, completing the
    program's observable-behaviour coverage.

    Parameters
    ----------
    cobol_runner : tests.helpers.cobol_runner.CobolRunner
        Fresh isolated per-test runner fixture.
    repo_root : pathlib.Path
        Repository-root fixture.

    Returns
    -------
    None

    Raises
    ------
    AssertionError
        If the not-found path is not taken (expected markers absent, the account
        success marker present, the wrong account id echoed, or a non-zero return code).
    """
    result = _stage_inputs_and_run(cobol_runner, repo_root, "unmatched_account")
    ids = _expected_ids(repo_root, "unmatched_account")
    out = result.stdout

    assert result.returncode == 0, (
        f"{_PROGRAM} returned {result.returncode} (expected 0; an unmatched account is a "
        f"not-found report, not an abend).\nstdout:\n{out}\nstderr tail:\n{(result.stderr or '')[-1000:]}"
    )
    assert _MARK_START in out and _MARK_END in out, f"missing banners.\nstdout:\n{out}"
    # The card resolved (XREF hit) but the account read missed.
    assert _MARK_XREF_OK in out, f"expected {_MARK_XREF_OK!r} (the card DOES resolve here).\nstdout:\n{out}"
    assert _MARK_ACCT_BAD in out, f"expected {_MARK_ACCT_BAD!r}.\nstdout:\n{out}"
    # The main loop names the missing account (fixture-derived resolved acct id). WHY the
    # exact 'ACCOUNT <id> NOT FOUND' text (Assumption): MAIN-PARA DISPLAYs it when
    # WS-ACCT-READ-STATUS != 0, so its presence pins that the account-miss branch ran for
    # the RIGHT (resolved) account id, not some unrelated value.
    assert f"ACCOUNT {ids['acct']} NOT FOUND" in out, (
        f"expected the not-found line naming account {ids['acct']}.\nstdout:\n{out}"
    )
    # The account read missed, so its success marker must be absent.
    assert _MARK_ACCT_OK not in out, (
        f"account read should have MISSED, but {_MARK_ACCT_OK!r} appeared.\nstdout:\n{out}"
    )
    # The card DID resolve, so the card-miss/skip diagnostics must NOT appear.
    for absent in (_MARK_XREF_BAD, _MARK_SKIP):
        assert absent not in out, (
            f"unexpected card-miss marker {absent!r} (the card resolves here).\nstdout:\n{out}"
        )
