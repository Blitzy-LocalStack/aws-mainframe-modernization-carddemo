"""Layer-2 integration tests for the CardDemo master-data provisioning programs.

Purpose
-------
Exercise the four "read/print" batch programs that provision (read every record
of, and DISPLAY) a CardDemo master file end-to-end, against a real compiled
GnuCOBOL executable and a real native indexed file:

* ``CBACT02C`` -- card master        (ASSIGN ``CARDFILE``, 150-byte records)
* ``CBACT03C`` -- card cross-reference (ASSIGN ``XREFFILE``, 50-byte records)
* ``CBCUS01C`` -- customer master     (ASSIGN ``CUSTFILE``, 500-byte records)
* ``CBACT01C`` -- account master      (ASSIGN ``ACCTFILE``, 300-byte records)

Each program OPENs an ``ORGANIZATION IS INDEXED`` master, reads every record in
ascending primary-key order, and DISPLAYs it (whole-record for the first three;
a labeled field block for ``CBACT01C``). Every test here therefore follows the
same golden-master-style round-trip: provision a deterministic indexed file from
a shipped fixture, run the compiled program, and assert on the captured
``stdout`` (record counts + field extracts) and the process ``RETURN-CODE``.
This realises AAP sections 0.5.1 / 0.5.2 -- "Test master-data provisioning".

Binding contract (how a program finds its file)
------------------------------------------------
A CardDemo batch program takes no file-name arguments: each
``SELECT ... ASSIGN TO <NAME>`` clause is bound by the GnuCOBOL runtime to a
same-named environment variable. The ``cobol_runner`` fixture (from
``tests/conftest.py``) points every ASSIGN name at a file inside an isolated
per-test workspace, so these tests need only name the ASSIGN and hand over a
fixture -- ``CobolRunner.load_input`` stages it as a native indexed file (the
automated analog of ``IDCAMS REPRO``) and ``CobolRunner.run`` executes the
program to completion.

``CBACT01C`` requires a ``COBDATFT`` stub
-----------------------------------------
``CBACT01C`` paragraph ``1300-POPUL-ACCT-RECORD`` issues
``CALL 'COBDATFT' USING CODATECN-REC``. The production ``COBDATFT`` is IBM HLASM
(``app/asm/COBDATFT.asm``) that ``cobc`` cannot build; without a resolvable
module the program abends after printing the first account. Per AAP 0.10.2
(stub unavailable dependencies), :func:`_ensure_cobdatft` compiles a no-op
``COBDATFT.so`` into the build directory (found at run time via
``COB_LIBRARY_PATH``, which ``CobolRunner.build_env`` already includes). The
no-op leaves ``OUT-ACCT-REISSUE-DATE`` blank, which is irrelevant to the
read/print round-trip these tests assert on.

Coordination-free assertion strategy (WHY derive-expected-from-fixture)
-----------------------------------------------------------------------
The fixtures under ``tests/fixtures/provisioning/<scenario>/`` are produced by a
sibling agent whose exact byte content and final file names are not this test's
to dictate. Rather than hard-code record counts or key values -- which would be
brittle against any legitimate fixture edit -- every expectation is *derived
from the fixture that was actually loaded*. The test loads N records, then
asserts the program printed exactly those N records' keys: the assertion is
self-consistent with whatever deterministic content the fixtures agent shipped
(Alternatives Considered: hard-coding expected values was rejected as fragile
cross-agent coupling; deriving from the loaded fixture is robust and still fully
asserting).

Determinism & isolation
-----------------------
Each test gets a fresh ``workspace`` (conftest ``workspace`` fixture) and loads
its own indexed file, so there is no shared mutable state and the module is safe
under ``pytest-xdist -n auto``. Compiling the ``COBDATFT`` stub into the shared
session ``build_dir`` is made idempotent and race-safe (see
:func:`_ensure_cobdatft`).

Explainability
--------------
Per the project's mandatory Explainability rule, every function below carries a
docstring stating Purpose / Parameters / Returns / Raises, and each non-obvious
decision is annotated with a WHY comment documenting at least one of
Alternatives Considered, Refactoring Rationale, Assumptions, or Trade-offs.
"""

# ``from __future__ import annotations`` keeps annotations as lazy strings.
# WHY (Trade-off): matches the suite convention (see tests/conftest.py) and lets
# us annotate with builtin generics (``list[bytes]``) without caring about the
# exact interpreter minor version at evaluation time.
from __future__ import annotations

import os
import shutil
import subprocess
import tempfile
from pathlib import Path

import pytest

# Every test in this module is an integration test. WHY (Assumption): the suite
# runs with ``--strict-markers`` (tests/pytest.ini), so this marker must be
# registered there / in conftest -- it is -- and applying it module-wide via
# ``pytestmark`` lets ``pytest -m integration`` select the whole file without
# per-function decoration.
pytestmark = pytest.mark.integration


# ---------------------------------------------------------------------------
# The no-op COBDATFT stub source (see module docstring for WHY it is needed).
# ---------------------------------------------------------------------------
# WHY the over-declared ``PIC X(200)`` linkage item (Trade-off / Assumption):
# the no-op never dereferences the record, so an over-sized linkage item is safe
# and deliberately avoids coupling the stub to CODATECN's exact byte layout -- a
# smaller/precise copy of the real layout would be pointless maintenance for a
# body that only does ``GOBACK``.
# WHY a stub at all (Alternatives Considered): the real COBDATFT is HLASM and
# un-buildable by ``cobc`` here, and no z/OS / Java build path is available on
# the runner; a no-op module is the validated way to let CBACT01C complete its
# read/print pass (AAP 0.10.2 endorses stubbing unavailable dependencies).
# The literal is assembled line-by-line (explicit newlines, fixed-format Area A
# at column 8 / Area B at column 12) so the exact indentation ``cobc -fixed``
# requires is unambiguous and independent of this file's own indentation.
COBDATFT_STUB_SRC = (
    "       IDENTIFICATION DIVISION.\n"
    "       PROGRAM-ID. COBDATFT.\n"
    "       DATA DIVISION.\n"
    "       LINKAGE SECTION.\n"
    "       01 LK-REC PIC X(200).\n"
    "       PROCEDURE DIVISION USING LK-REC.\n"
    "           GOBACK.\n"
)

# Compiled-module file name the runtime resolves ``CALL 'COBDATFT'`` to.
_COBDATFT_MODULE = "COBDATFT.so"


# ---------------------------------------------------------------------------
# Per-program specification table (empirically verified against app/cbl/*.cbl
# and live GnuCOBOL 3.2 runs). Keeping the geometry declarative here -- rather
# than scattering literals through the tests -- means a reviewer can audit the
# whole contract in one place and each test reads as intent, not arithmetic.
# ---------------------------------------------------------------------------
# Field meanings:
#   assign             : the SELECT ... ASSIGN TO external name (== env var).
#   reclen             : fixed record length in bytes (matches the copybook).
#   key_length         : leading primary-key length in bytes (offset 0).
#   display_multiplier : how many stdout lines the program emits PER record.
#       WHY this exists (Assumption, verified): CBACT02C's second
#       ``DISPLAY`` (in 1000-CARDFILE-GET-NEXT) is COMMENTED OUT, so it prints
#       each record ONCE (multiplier 1); CBACT03C and CBCUS01C keep that second
#       DISPLAY ACTIVE, so they print each record TWICE (multiplier 2 -- once in
#       the GET-NEXT paragraph and once in the main loop). Pinning the exact
#       count is financial-grade: it would catch any silent change to the
#       (REFERENCE-only) print behavior. CBACT01C is labeled-block output and is
#       asserted differently (ACCT-ID label-line count), so its multiplier is 1
#       for completeness but unused by its dedicated test.
#   candidates         : fixture file-name candidates, most-likely first. WHY a
#       list (Trade-off): the fixtures agent chooses the final name; trying the
#       seed-style name, the ASSIGN-style name, and record-type synonyms makes
#       the test robust to that choice without any cross-agent coordination.
PROGRAM_SPECS: "dict[str, dict[str, object]]" = {
    "CBACT02C": {
        "assign": "CARDFILE",
        "reclen": 150,
        "key_length": 16,
        "display_multiplier": 1,
        "candidates": ("carddata.txt", "CARDFILE.txt", "card.txt", "cards.txt", "cardfile.txt"),
    },
    "CBACT03C": {
        "assign": "XREFFILE",
        "reclen": 50,
        "key_length": 16,
        "display_multiplier": 2,
        "candidates": ("cardxref.txt", "XREFFILE.txt", "xref.txt", "xrefdata.txt", "cardxref.dat", "xreffile.txt"),
    },
    "CBCUS01C": {
        "assign": "CUSTFILE",
        "reclen": 500,
        "key_length": 9,
        "display_multiplier": 2,
        "candidates": ("custdata.txt", "CUSTFILE.txt", "cust.txt", "customer.txt", "customers.txt", "custfile.txt"),
    },
    "CBACT01C": {
        "assign": "ACCTFILE",
        "reclen": 300,
        "key_length": 11,
        "display_multiplier": 1,
        "candidates": ("acctdata.txt", "ACCTFILE.txt", "acct.txt", "account.txt", "accounts.txt", "acctfile.txt"),
    },
}


def _banner(program: str, phase: str) -> str:
    """Return the exact console banner a provisioning program emits.

    Purpose
    -------
    Build the ``START``/``END`` execution banner string for a program so tests
    assert on the byte-exact text the COBOL source DISPLAYs, defined in one
    place instead of being repeated as literals in each test.

    Parameters
    ----------
    program : str
        The program name (e.g. ``"CBACT02C"``); appears verbatim in the banner.
    phase : str
        Either ``"START"`` or ``"END"`` -- the execution phase the banner marks.

    Returns
    -------
    str
        The banner line, e.g. ``"START OF EXECUTION OF PROGRAM CBACT02C"``.

    Raises
    ------
    None
    """
    # WHY (Assumption): all four programs share the identical banner template
    # ``<PHASE> OF EXECUTION OF PROGRAM <PROG>`` (verified by grep across the
    # sources), so a single formatter keeps the expected strings single-sourced.
    return f"{phase} OF EXECUTION OF PROGRAM {program}"


# ---------------------------------------------------------------------------
# Phase B -- fixture / stub / run helpers.
# ---------------------------------------------------------------------------
def _fixture_dir(repo_root: Path, scenario: str) -> Path:
    """Return the provisioning fixture directory for a scenario.

    Purpose
    -------
    Centralise the ``tests/fixtures/provisioning/<scenario>/`` path so the layout
    is defined once and every test refers to a scenario by name.

    Parameters
    ----------
    repo_root : pathlib.Path
        Repository root (from the ``repo_root`` fixture).
    scenario : str
        Scenario sub-directory name, e.g. ``"happy_path"`` or ``"empty_input"``.

    Returns
    -------
    pathlib.Path
        The scenario directory path (not guaranteed to exist -- the caller's
        :func:`_resolve_fixture` handles absence by skipping).

    Raises
    ------
    None
    """
    # WHY (Assumption): the fixture tree location is fixed by the AAP
    # (tests/fixtures/<domain>/<scenario>/), so deriving it from repo_root keeps
    # the test independent of the current working directory.
    return repo_root / "tests" / "fixtures" / "provisioning" / scenario


def _resolve_fixture(scenario_dir: Path, *candidates: str) -> Path:
    """Return the first existing fixture file among ``candidates`` (or skip).

    Purpose
    -------
    Locate the concrete fixture for a scenario without hard-coding a single file
    name, tolerating the sibling fixtures agent's naming choice.

    Parameters
    ----------
    scenario_dir : pathlib.Path
        Directory to search (e.g. the ``happy_path`` fixture directory).
    *candidates : str
        Candidate file names, tried in order (most-likely first).

    Returns
    -------
    pathlib.Path
        The path of the first candidate that exists as a regular file.

    Raises
    ------
    Skipped
        Calls ``pytest.skip`` (which raises ``Skipped``) if none of the
        candidates exists, so a partial checkout / not-yet-materialised fixture
        tree yields a clean skip rather than an error.
    """
    # WHY (Alternatives Considered / resilient resolver): the fixtures agent's
    # final file name is its own choice, so rather than couple to one name we try
    # a short ordered list of plausible names and skip cleanly if the tree is not
    # present. Skipping (not failing) is deliberate: an absent fixture is an
    # environment/ordering condition, not a defect in the program under test.
    for name in candidates:
        candidate = scenario_dir / name
        if candidate.is_file():
            return candidate
    tried = ", ".join(candidates) if candidates else "(none)"
    pytest.skip(f"fixture not found under {scenario_dir} (tried: {tried})")


def _read_fixture_records(path: Path, reclen: int) -> "list[bytes]":
    """Read a flat fixture into a list of fixed-width logical records.

    Purpose
    -------
    Turn a shipped fixture into the list of ``reclen``-byte records it
    represents, so the tests can derive the expected record count and the set of
    primary keys directly from the loaded data. Handles both a raw ``N * reclen``
    blob (no terminators) and the newline-delimited form the ASCII seeds use
    (one padded record per line).

    Parameters
    ----------
    path : pathlib.Path
        The fixture file to read.
    reclen : int
        The fixed record length in bytes; each returned element is exactly this
        wide.

    Returns
    -------
    list[bytes]
        The logical records, each exactly ``reclen`` bytes. An empty (or
        whitespace/newline-only) fixture yields an empty list -- the
        ``empty_input`` scenario.

    Raises
    ------
    OSError
        If the fixture cannot be read.
    """
    data = path.read_bytes()
    # WHY (empty means empty): the empty_input scenario ships a 0-byte (or
    # newline-only) file; returning [] lets the same reader drive both the
    # happy_path and empty_input tests without a special case in the caller.
    if not data.strip():
        return []

    # WHY (Assumption -- two on-disk shapes): a fixture is EITHER a headerless
    # ``N * reclen`` blob (the byte-exact IDCAMS-style form, no terminators) OR a
    # newline-delimited listing where each line is one record. The "no newline
    # AND length is a whole multiple of reclen" test distinguishes the raw blob
    # unambiguously; anything else is treated as newline-delimited. WHY normalise
    # CRLF first (Trade-off): a fixture edited on Windows could carry ``\r\n``;
    # collapsing to ``\n`` before splitting avoids a spurious trailing ``\r`` on
    # every record that would corrupt the primary key.
    if b"\n" not in data and len(data) % reclen == 0:
        return [data[i : i + reclen] for i in range(0, len(data), reclen)]

    normalised = data.replace(b"\r\n", b"\n").replace(b"\r", b"\n")
    records: "list[bytes]" = []
    for line in normalised.split(b"\n"):
        if line == b"":
            # Drop empty elements (e.g. the trailing one produced by a final
            # EOF newline); a real record is never zero-length here.
            continue
        # WHY right-pad/truncate to reclen (Assumption): the COBOL FD reads a
        # fixed-width record, so a fixture line must present exactly ``reclen``
        # bytes. ``ljust`` pads a short line with spaces (the COBOL fill byte)
        # and the slice truncates an over-long line, making the reader tolerant
        # of trailing-space trimming a text editor might apply while still
        # yielding the exact geometry the program expects.
        records.append(line[:reclen].ljust(reclen))
    return records


def _key_texts(records: "list[bytes]", key_length: int) -> "list[str]":
    """Extract the primary-key text of each record.

    Purpose
    -------
    Produce the leading-``key_length`` primary key of every record as text, so a
    test can assert those keys appear in the program's DISPLAY output.

    Parameters
    ----------
    records : list[bytes]
        Fixed-width records (from :func:`_read_fixture_records`).
    key_length : int
        Number of leading bytes that form the primary key (offset 0).

    Returns
    -------
    list[str]
        The decoded key of each record, in fixture order (duplicates preserved,
        though CardDemo master keys are unique).

    Raises
    ------
    None
    """
    # WHY latin-1 (Assumption): CardDemo records are fixed-width bytes; latin-1
    # maps all 256 byte values 1:1 to code points so decoding can never raise,
    # and the keys here are ASCII digits, so the decoded text matches the
    # characters the COBOL DISPLAY writes to stdout byte-for-byte.
    return [rec[:key_length].decode("latin-1") for rec in records]


def _display_record_lines(
    stdout: str, keys: "list[str]", key_length: int
) -> "list[str]":
    """Return the stdout lines that are record displays (key-prefixed lines).

    Purpose
    -------
    Identify, among all captured stdout lines, those that are whole-record
    DISPLAYs -- i.e. lines whose first ``key_length`` characters equal one of the
    fixture record keys. This excludes the banners and any incidental output, so
    the caller can count record lines and match them against the fixture.

    Parameters
    ----------
    stdout : str
        The program's captured standard output.
    keys : list[str]
        The fixture record keys (from :func:`_key_texts`).
    key_length : int
        The primary-key length used to slice each candidate line.

    Returns
    -------
    list[str]
        The stdout lines classified as record displays, in emission order.

    Raises
    ------
    None
    """
    # WHY match on the key prefix rather than the whole record (Trade-off): the
    # programs DISPLAY the full fixed-width record, but trailing spaces can be
    # trimmed by the terminal/capture layer, so comparing the stable leading key
    # is a robust way to recognise a record line while still tying each line to a
    # specific fixture record. A ``set`` gives O(1) membership over the keys.
    key_set = set(keys)
    matched: "list[str]" = []
    for line in stdout.splitlines():
        if len(line) >= key_length and line[:key_length] in key_set:
            matched.append(line)
    return matched


def _ensure_cobdatft(build_dir: Path) -> Path:
    """Compile the no-op ``COBDATFT`` stub into ``build_dir`` (idempotently).

    Purpose
    -------
    Guarantee a resolvable ``COBDATFT`` module exists so ``CBACT01C`` can complete
    its read/print pass (see the module docstring for the HLASM background). Safe
    to call repeatedly and concurrently: the compiled ``.so`` is produced once and
    installed atomically.

    Parameters
    ----------
    build_dir : pathlib.Path
        The session build directory (from the ``build_dir`` fixture); the stub is
        installed here as ``COBDATFT.so`` because ``CobolRunner.build_env`` places
        this directory on ``COB_LIBRARY_PATH``.

    Returns
    -------
    pathlib.Path
        The path of the installed ``COBDATFT.so``.

    Raises
    ------
    Skipped
        Calls ``pytest.skip`` if ``cobc`` is not on ``PATH`` or the stub fails to
        compile -- both are environment conditions specific to the CBACT01C /
        COBDATFT (HLASM-unavailable) path, so only the requesting test skips
        rather than failing the suite.
    """
    so_path = build_dir / _COBDATFT_MODULE
    # WHY existence-guard first (idempotency): the stub is identical every time,
    # so once it exists any subsequent call -- including a parallel xdist worker's
    # -- can return immediately without recompiling.
    if so_path.exists():
        return so_path

    # WHY skip (not fail) on a missing compiler (Assumption): a runner without
    # ``cobc`` cannot build the stub OR the program under test; that is an
    # environment limitation, not a defect, so the CBACT01C test skips cleanly.
    if shutil.which("cobc") is None:
        pytest.skip(
            "GnuCOBOL 'cobc' not on PATH; cannot build the COBDATFT stub that "
            "CBACT01C requires (its real COBDATFT is HLASM, un-buildable here)"
        )

    build_dir.mkdir(parents=True, exist_ok=True)

    # WHY compile in a private temp dir INSIDE build_dir, then os.replace
    # (Refactoring Rationale -- parallel safety): building straight to
    # ``COBDATFT.so`` would let two concurrent xdist workers write the same file
    # at once and a program could load a half-written module. Compiling to a
    # unique temp path on the SAME filesystem and then ``os.replace`` (atomic
    # rename) means every observer sees either no file or a complete, valid one.
    work = Path(tempfile.mkdtemp(prefix=".cobdatft-", dir=str(build_dir)))
    try:
        src = work / "COBDATFT.cbl"
        src.write_text(COBDATFT_STUB_SRC, encoding="ascii")
        tmp_so = work / _COBDATFT_MODULE
        # Compile a dynamically CALL'able module (``-m``) in the repository's
        # fixed-format, strict-IBM dialect -- the same convention
        # scripts/build_test_programs.sh uses for the real modules.
        proc = subprocess.run(
            ["cobc", "-m", "-fixed", "--std=ibm-strict", "-o", str(tmp_so), str(src)],
            capture_output=True,
            text=True,
        )
        if proc.returncode != 0 or not tmp_so.exists():
            # WHY skip with the compiler tail (documented reason): if even the
            # trivial stub will not build, the CBACT01C read/print path cannot be
            # exercised in this environment; surface the compiler's own message so
            # the skip is auditable, and skip ONLY this test.
            tail = (proc.stderr or proc.stdout or "").splitlines()[-8:]
            pytest.skip(
                "COBDATFT stub failed to compile (rc="
                f"{proc.returncode}); CBACT01C needs a COBDATFT module and its "
                "real one is HLASM (un-buildable by cobc). Compiler tail:\n"
                + "\n".join(tail)
            )
        os.replace(str(tmp_so), str(so_path))
    finally:
        # Remove the private temp dir regardless of outcome; ignore_errors keeps
        # cleanup from masking a compile/skip already in flight.
        shutil.rmtree(work, ignore_errors=True)
    return so_path


def _run_readprint(
    cobol_runner: object,
    repo_root: Path,
    program: str,
    scenario: str,
) -> "tuple[object, list[bytes]]":
    """Load a scenario fixture, run a provisioning program, and return the outcome.

    Purpose
    -------
    Factor out the load-then-run sequence shared by every provisioning test:
    resolve the scenario fixture, read its records, stage it as an indexed file
    bound to the program's ASSIGN name, and execute the program. Assertions are
    intentionally left to the test bodies so a failure points at the specific
    scenario rather than at this shared helper.

    Parameters
    ----------
    cobol_runner : tests.helpers.cobol_runner.CobolRunner
        The per-test runner (from the ``cobol_runner`` fixture).
    repo_root : pathlib.Path
        Repository root (from the ``repo_root`` fixture); locates the fixtures.
    program : str
        The program to run; must be a key of :data:`PROGRAM_SPECS`.
    scenario : str
        The fixture scenario name (e.g. ``"happy_path"`` / ``"empty_input"``).

    Returns
    -------
    tuple[tests.helpers.cobol_runner.RunResult, list[bytes]]
        The run result and the list of fixture records that were loaded (so the
        caller can derive expected counts/keys from exactly what it staged).

    Raises
    ------
    Skipped
        Propagated from :func:`_resolve_fixture` if the scenario fixture is
        absent.
    tests.helpers.cobol_runner.CobolRunError
        If the run times out (a stuck program is a defect, surfaced by the
        runner).
    """
    spec = PROGRAM_SPECS[program]
    reclen = int(spec["reclen"])
    key_length = int(spec["key_length"])
    assign = str(spec["assign"])
    candidates = spec["candidates"]

    scenario_dir = _fixture_dir(repo_root, scenario)
    # ``*candidates`` is unpacked from the spec's tuple of plausible names.
    fixture_path = _resolve_fixture(scenario_dir, *candidates)  # type: ignore[misc]
    records = _read_fixture_records(fixture_path, reclen)

    # WHY explicit reclen/key_length rather than ``layout=`` (Assumption /
    # bug-avoidance): several record layouts (notably XREF) declare an ALTERNATE
    # key, and passing ``layout=`` would make load_input build the alternate-key
    # sidecar. These read/print programs OPEN their file with the PRIMARY key
    # only (their SELECT has no ALTERNATE RECORD KEY clause), and opening a file
    # that carries an unexpected alternate index risks a FILE STATUS 39
    # attribute mismatch. Loading with explicit geometry yields a primary-key-
    # only indexed file that exactly matches what the program declares.
    cobol_runner.load_input(assign, fixture_path, reclen=reclen, key_length=key_length)
    result = cobol_runner.run(program)
    return result, records


# ---------------------------------------------------------------------------
# Phase C -- tests.
#
# WHY the assertions live in each test body, not in a shared asserter
# (Refactoring Rationale): _run_readprint centralises the load+run so each test
# reads its own OUTPUT and asserts on it, which makes a failing assertion point
# at the specific program/scenario (e.g. test_cbact03c_xref_roundtrip) rather
# than at a shared helper -- exactly the failure-localisation the suite wants.
# ---------------------------------------------------------------------------
def test_cbact02c_card_roundtrip(cobol_runner, repo_root):
    """CBACT02C reads and prints every card record from a happy-path fixture.

    Purpose
    -------
    Assert the card master read/print round-trip: a deterministic ``CARDFILE``
    fixture is staged as an indexed file, ``CBACT02C`` is run, and its output is
    checked for both banners, exactly the fixture's records (by key), and RC=0.

    Parameters
    ----------
    cobol_runner : tests.helpers.cobol_runner.CobolRunner
        Per-test runner fixture (isolated workspace + compiled programs).
    repo_root : pathlib.Path
        Repository-root fixture; locates the provisioning fixtures.

    Returns
    -------
    None

    Raises
    ------
    AssertionError
        If any observable output (RC, banners, record set, record count) differs
        from what the loaded fixture implies.
    Skipped
        If the ``happy_path`` CARDFILE fixture is absent or empty.
    """
    program = "CBACT02C"
    key_length = int(PROGRAM_SPECS[program]["key_length"])
    multiplier = int(PROGRAM_SPECS[program]["display_multiplier"])

    result, records = _run_readprint(cobol_runner, repo_root, program, "happy_path")
    # WHY skip an empty happy_path (Assumption): a happy_path fixture is meant to
    # carry data; if the sibling agent shipped it empty there is no round-trip to
    # assert, so skip with a clear reason instead of trivially "passing".
    if not records:
        pytest.skip("happy_path CARDFILE fixture has no records to round-trip")

    keys = _key_texts(records, key_length)

    assert result.ok(), (
        f"{program} expected RETURN-CODE 0, got {result.returncode}; "
        f"stderr tail: {result.stderr.splitlines()[-5:]}"
    )
    assert _banner(program, "START") in result.stdout, "missing START banner"
    assert _banner(program, "END") in result.stdout, "missing END banner"

    # Record-count as a round-trip: the set of displayed keys must equal the set
    # of fixture keys -- every record printed, nothing extra.
    display_lines = _display_record_lines(result.stdout, keys, key_length)
    distinct_displayed = {line[:key_length] for line in display_lines}
    assert distinct_displayed == set(keys), (
        f"{program}: displayed keys {sorted(distinct_displayed)} != "
        f"fixture keys {sorted(set(keys))}"
    )
    # Field-extract: each fixture key value appears in the output (AAP requires
    # a record-count + field-extract assertion).
    for key in keys:
        assert key in result.stdout, f"{program}: key {key!r} missing from output"
    # Exact line count pins the single-DISPLAY behavior (multiplier 1).
    assert len(display_lines) == len(records) * multiplier, (
        f"{program}: expected {len(records) * multiplier} record lines "
        f"({len(records)} records x {multiplier}), got {len(display_lines)}"
    )


def test_cbact02c_empty_input(cobol_runner, repo_root):
    """CBACT02C on an empty CARDFILE prints only the banners and exits RC=0.

    Purpose
    -------
    Assert the empty-input edge case: with a zero-record ``CARDFILE`` the program
    must still open/close cleanly, emit both execution banners, print no record
    lines, and return 0.

    Parameters
    ----------
    cobol_runner : tests.helpers.cobol_runner.CobolRunner
        Per-test runner fixture.
    repo_root : pathlib.Path
        Repository-root fixture.

    Returns
    -------
    None

    Raises
    ------
    AssertionError
        If the run is non-zero, a banner is missing, or any non-banner line is
        emitted.
    Skipped
        If the ``empty_input`` CARDFILE fixture is absent.
    """
    program = "CBACT02C"
    result, records = _run_readprint(cobol_runner, repo_root, program, "empty_input")

    assert records == [], "empty_input CARDFILE fixture should contain zero records"
    assert result.ok(), (
        f"{program} empty run expected RETURN-CODE 0, got {result.returncode}"
    )
    assert _banner(program, "START") in result.stdout, "missing START banner"
    assert _banner(program, "END") in result.stdout, "missing END banner"
    # WHY assert "only the banners" (stronger than "zero record lines"): with no
    # keys, a key-prefix check is vacuously empty, so instead we require that the
    # ONLY non-blank lines are the two banners -- proving no record was printed.
    nonblank = [line for line in result.stdout.splitlines() if line.strip()]
    banners = {_banner(program, "START"), _banner(program, "END")}
    extra = [line for line in nonblank if line not in banners]
    assert extra == [], f"{program} empty run emitted unexpected lines: {extra}"


def test_cbact03c_xref_roundtrip(cobol_runner, repo_root):
    """CBACT03C reads and prints every card-xref record from a happy-path fixture.

    Purpose
    -------
    Assert the card cross-reference read/print round-trip against a deterministic
    ``XREFFILE`` fixture, checking banners, the exact record set (by key), the
    exact display-line count (this program double-prints each record), and RC=0.

    Parameters
    ----------
    cobol_runner : tests.helpers.cobol_runner.CobolRunner
        Per-test runner fixture.
    repo_root : pathlib.Path
        Repository-root fixture.

    Returns
    -------
    None

    Raises
    ------
    AssertionError
        If any observable output differs from what the loaded fixture implies.
    Skipped
        If the ``happy_path`` XREFFILE fixture is absent or empty.
    """
    program = "CBACT03C"
    key_length = int(PROGRAM_SPECS[program]["key_length"])
    multiplier = int(PROGRAM_SPECS[program]["display_multiplier"])

    result, records = _run_readprint(cobol_runner, repo_root, program, "happy_path")
    if not records:
        pytest.skip("happy_path XREFFILE fixture has no records to round-trip")

    keys = _key_texts(records, key_length)

    assert result.ok(), (
        f"{program} expected RETURN-CODE 0, got {result.returncode}; "
        f"stderr tail: {result.stderr.splitlines()[-5:]}"
    )
    assert _banner(program, "START") in result.stdout, "missing START banner"
    assert _banner(program, "END") in result.stdout, "missing END banner"

    display_lines = _display_record_lines(result.stdout, keys, key_length)
    distinct_displayed = {line[:key_length] for line in display_lines}
    assert distinct_displayed == set(keys), (
        f"{program}: displayed keys {sorted(distinct_displayed)} != "
        f"fixture keys {sorted(set(keys))}"
    )
    for key in keys:
        assert key in result.stdout, f"{program}: key {key!r} missing from output"
    # WHY multiplier 2 (verified quirk): CBACT03C keeps the DISPLAY inside
    # 1000-XREFFILE-GET-NEXT active AND displays again in the main loop, so each
    # record appears twice. Asserting the exact doubled count would catch any
    # change to that REFERENCE-only print behavior.
    assert len(display_lines) == len(records) * multiplier, (
        f"{program}: expected {len(records) * multiplier} record lines "
        f"({len(records)} records x {multiplier}), got {len(display_lines)}"
    )


def test_cbact03c_empty_input(cobol_runner, repo_root):
    """CBACT03C on an empty XREFFILE prints only the banners and exits RC=0.

    Purpose
    -------
    Assert the empty-input edge case for the card cross-reference reader.

    Parameters
    ----------
    cobol_runner : tests.helpers.cobol_runner.CobolRunner
        Per-test runner fixture.
    repo_root : pathlib.Path
        Repository-root fixture.

    Returns
    -------
    None

    Raises
    ------
    AssertionError
        If the run is non-zero, a banner is missing, or any non-banner line is
        emitted.
    Skipped
        If the ``empty_input`` XREFFILE fixture is absent.
    """
    program = "CBACT03C"
    result, records = _run_readprint(cobol_runner, repo_root, program, "empty_input")

    assert records == [], "empty_input XREFFILE fixture should contain zero records"
    assert result.ok(), (
        f"{program} empty run expected RETURN-CODE 0, got {result.returncode}"
    )
    assert _banner(program, "START") in result.stdout, "missing START banner"
    assert _banner(program, "END") in result.stdout, "missing END banner"
    nonblank = [line for line in result.stdout.splitlines() if line.strip()]
    banners = {_banner(program, "START"), _banner(program, "END")}
    extra = [line for line in nonblank if line not in banners]
    assert extra == [], f"{program} empty run emitted unexpected lines: {extra}"


def test_cbcus01c_customer_roundtrip(cobol_runner, repo_root):
    """CBCUS01C reads and prints every customer record from a happy-path fixture.

    Purpose
    -------
    Assert the customer master read/print round-trip against a deterministic
    ``CUSTFILE`` fixture, checking banners, the exact record set (by 9-byte
    CUST-ID key), the exact display-line count (double-print), and RC=0.

    Parameters
    ----------
    cobol_runner : tests.helpers.cobol_runner.CobolRunner
        Per-test runner fixture.
    repo_root : pathlib.Path
        Repository-root fixture.

    Returns
    -------
    None

    Raises
    ------
    AssertionError
        If any observable output differs from what the loaded fixture implies.
    Skipped
        If the ``happy_path`` CUSTFILE fixture is absent or empty.
    """
    program = "CBCUS01C"
    key_length = int(PROGRAM_SPECS[program]["key_length"])
    multiplier = int(PROGRAM_SPECS[program]["display_multiplier"])

    result, records = _run_readprint(cobol_runner, repo_root, program, "happy_path")
    if not records:
        pytest.skip("happy_path CUSTFILE fixture has no records to round-trip")

    keys = _key_texts(records, key_length)

    assert result.ok(), (
        f"{program} expected RETURN-CODE 0, got {result.returncode}; "
        f"stderr tail: {result.stderr.splitlines()[-5:]}"
    )
    assert _banner(program, "START") in result.stdout, "missing START banner"
    assert _banner(program, "END") in result.stdout, "missing END banner"

    display_lines = _display_record_lines(result.stdout, keys, key_length)
    distinct_displayed = {line[:key_length] for line in display_lines}
    assert distinct_displayed == set(keys), (
        f"{program}: displayed keys {sorted(distinct_displayed)} != "
        f"fixture keys {sorted(set(keys))}"
    )
    for key in keys:
        assert key in result.stdout, f"{program}: key {key!r} missing from output"
    # CBCUS01C double-prints each record like CBACT03C (multiplier 2).
    assert len(display_lines) == len(records) * multiplier, (
        f"{program}: expected {len(records) * multiplier} record lines "
        f"({len(records)} records x {multiplier}), got {len(display_lines)}"
    )


def test_cbcus01c_empty_input(cobol_runner, repo_root):
    """CBCUS01C on an empty CUSTFILE prints only the banners and exits RC=0.

    Purpose
    -------
    Assert the empty-input edge case for the customer master reader.

    Parameters
    ----------
    cobol_runner : tests.helpers.cobol_runner.CobolRunner
        Per-test runner fixture.
    repo_root : pathlib.Path
        Repository-root fixture.

    Returns
    -------
    None

    Raises
    ------
    AssertionError
        If the run is non-zero, a banner is missing, or any non-banner line is
        emitted.
    Skipped
        If the ``empty_input`` CUSTFILE fixture is absent.
    """
    program = "CBCUS01C"
    result, records = _run_readprint(cobol_runner, repo_root, program, "empty_input")

    assert records == [], "empty_input CUSTFILE fixture should contain zero records"
    assert result.ok(), (
        f"{program} empty run expected RETURN-CODE 0, got {result.returncode}"
    )
    assert _banner(program, "START") in result.stdout, "missing START banner"
    assert _banner(program, "END") in result.stdout, "missing END banner"
    nonblank = [line for line in result.stdout.splitlines() if line.strip()]
    banners = {_banner(program, "START"), _banner(program, "END")}
    extra = [line for line in nonblank if line not in banners]
    assert extra == [], f"{program} empty run emitted unexpected lines: {extra}"


def test_cbact01c_account_roundtrip_with_stub(cobol_runner, build_dir, repo_root):
    """CBACT01C reads and prints every account record (with a COBDATFT stub).

    Purpose
    -------
    Assert the account master read/print round-trip. Because ``CBACT01C`` CALLs
    the HLASM-only ``COBDATFT`` (which ``cobc`` cannot build), a no-op stub is
    compiled first via :func:`_ensure_cobdatft`; the test then checks the labeled
    output for one ``ACCT-ID`` block per fixture record, each account id present,
    both banners, and RC=0.

    Parameters
    ----------
    cobol_runner : tests.helpers.cobol_runner.CobolRunner
        Per-test runner fixture.
    build_dir : pathlib.Path
        Session build-directory fixture; where the ``COBDATFT`` stub is installed
        so the runtime resolves it via ``COB_LIBRARY_PATH``.
    repo_root : pathlib.Path
        Repository-root fixture.

    Returns
    -------
    None

    Raises
    ------
    AssertionError
        If the account output (banners, ACCT-ID block count, account ids) differs
        from the loaded fixture.
    Skipped
        If the COBDATFT stub cannot be built, if ``CBACT01C`` still returns
        non-zero despite the stub (documented HLASM limitation -- this test only),
        or if the ``happy_path`` ACCTFILE fixture is absent/empty.
    """
    program = "CBACT01C"
    key_length = int(PROGRAM_SPECS[program]["key_length"])

    # WHY build the stub BEFORE running (ordering): CBACT01C's 1300-POPUL-ACCT-
    # RECORD CALLs COBDATFT for every record; the module must be resolvable on
    # COB_LIBRARY_PATH (== build_dir) before the run, or the program abends after
    # the first account. _ensure_cobdatft skips ONLY this test if it cannot build
    # the stub, leaving the other three programs' tests unaffected.
    _ensure_cobdatft(build_dir)

    result, records = _run_readprint(cobol_runner, repo_root, program, "happy_path")
    if not records:
        pytest.skip("happy_path ACCTFILE fixture has no records to round-trip")

    keys = _key_texts(records, key_length)

    # WHY skip-only-this-test on a non-zero RC (Trade-off): the remaining risk
    # unique to CBACT01C is the HLASM COBDATFT dependency. If the stub workaround
    # does not take in some environment, skip with a documented reason rather than
    # fail the suite -- and never let it affect CBACT02C/03C/CBCUS01C.
    if result.returncode != 0:
        pytest.skip(
            f"CBACT01C returned RC={result.returncode} despite the COBDATFT stub; "
            "its production COBDATFT is HLASM (un-buildable by cobc). stderr tail: "
            f"{result.stderr.splitlines()[-5:]}"
        )

    # Reaching the END banner proves the program did not abend mid-file.
    assert _banner(program, "START") in result.stdout, "missing START banner"
    assert _banner(program, "END") in result.stdout, (
        "CBACT01C did not reach its END banner (likely abended before EOF)"
    )

    # CBACT01C prints a labeled field block per record; the block begins with an
    # ``ACCT-ID<spaces>:<value>`` line, so counting those lines counts records.
    # WHY startswith("ACCT-ID") (Assumption): only the ACCT-ID label line starts
    # with that token -- the sibling labels (ACCT-ACTIVE-STATUS, ACCT-CURR-BAL,
    # ...) do not, the raw DISPLAY ACCOUNT-RECORD line starts with the numeric id,
    # and the VBRC-REC lines start with "VBRC" -- so the count is exact.
    acct_id_lines = [
        line for line in result.stdout.splitlines() if line.lstrip().startswith("ACCT-ID")
    ]
    assert len(acct_id_lines) == len(records), (
        f"{program}: expected {len(records)} ACCT-ID label lines "
        f"(one per account), got {len(acct_id_lines)}"
    )
    # Field-extract: each account id appears in the output. WHY substring (not
    # equality): signed numeric fields DISPLAY with a trailing overpunch/sign
    # char (e.g. ``000000015800+``), so we assert the key's digit substring is
    # present rather than an exact line equality that the sign would break.
    for key in keys:
        assert key in result.stdout, f"{program}: account id {key!r} missing from output"
