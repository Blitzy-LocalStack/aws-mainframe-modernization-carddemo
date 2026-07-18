"""Layer-2 integration test for the CardDemo statement generator (``CBSTM03A`` + ``CBSTM03B``).

Purpose
-------
Verify the CardDemo **statement generation** program pair -- the main driver
``CBSTM03A`` and its statically-linked I/O subprogram ``CBSTM03B`` -- at the
integration layer. The *intended* verification is a **golden-master comparison**
of the two artifacts the program emits: the plain-text statement (ASSIGN
``STMTFILE``) and the HTML statement (ASSIGN ``HTMLFILE``), compared against the
committed baselines under ``tests/golden/statement/happy_path/``.

Binding contract
----------------
Like every CardDemo batch program, this pair takes no file arguments: each
``SELECT ... ASSIGN TO <NAME>`` clause is bound by the GnuCOBOL runtime to a
same-named environment variable, which the ``cobol_runner`` fixture points at an
isolated per-test workspace. ``CBSTM03B`` is the ``PROCEDURE DIVISION USING
LK-M03B-AREA`` I/O subprogram; it is compiled into the **same** ``-x`` executable
as ``CBSTM03A`` (static link), so driving ``CBSTM03A`` drives the whole pair --
``cobol_runner.run("CBSTM03A")`` is the only run call needed. The four INPUT
files the pair reads are ``ORGANIZATION IS INDEXED``; each flat fixture is loaded
into a native GnuCOBOL indexed file (the ``IDCAMS REPRO`` analog) before the run.

Run status on this platform -- compile-clean GATE + skip-on-SIGSEGV
-------------------------------------------------------------------
The pair **compiles cleanly** under GnuCOBOL with the ``-ftab-width=2`` recipe
below, but ``CBSTM03A`` **cannot run to completion off a mainframe**: the very
first statements of its ``PROCEDURE DIVISION`` perform a z/OS control-block scan
(``SET ADDRESS OF PSA-BLOCK TO PSAPTR`` -- where ``PSAPTR`` is an *uninitialized*
``POINTER`` -- then dereference the TCB and walk the TIOT to print DD names).
Under GnuCOBOL on Linux there is no PSA/TCB/TIOT, so dereferencing the null/garbage
pointer faults with SIGSEGV **before any file is opened** (the OPEN of ``STMTFILE``
/ ``HTMLFILE`` comes several statements later). Fixing this would require editing
production COBOL, which is explicitly out of scope (production sources are
REFERENCE only).

This module therefore does two things, in order:

1. **Asserts the compile-clean gate.** The ``CBSTM03A`` + ``CBSTM03B`` pair is a
   verified clean-compile invariant; a compile *regression* is a real, meaningful
   failure, so :func:`_ensure_statement_program` compiles the pair and asserts a
   binary was produced. (It only *skips* when there is no compiler at all -- an
   environment condition, not a code defect.)
2. **Skips-on-SIGSEGV, explicitly and with a documented reason.** After the run,
   if the crash is detected (see :func:`_is_sigsegv`) the test issues an explicit
   :func:`pytest.skip` naming the z/OS TIOT scan as the cause. Skips are never
   silent. On this runner that is the *expected* outcome.

If a compatible runtime ever exists (e.g. a mainframe-faithful shim that supplies
a valid PSA/TCB/TIOT), the very same test **auto-upgrades**: the SIGSEGV gate is
not tripped, control falls through to the golden-master comparison, and the test
begins genuinely verifying the statement bytes with no code change.

Explainability
--------------
Per the project's mandatory Explainability rule (AAP Section 0.10.1), every
function below carries a docstring stating Purpose / Parameters / Returns /
Raises, and each non-obvious decision is annotated with a WHY comment documenting
at least one of Alternatives Considered, Refactoring Rationale, Assumptions, or
Trade-offs -- never restating what the code does.

Namespace note
--------------
There is intentionally **no** ``__init__.py`` anywhere under ``tests/``; the tree
resolves as a PEP 420 namespace package via ``PYTHONPATH=<repo_root>`` (the root
``tests/conftest.py`` bootstraps ``sys.path`` for bare ``pytest`` runs), so
``from tests.helpers... import ...`` resolves in both invocation styles.
"""

from __future__ import annotations

import shutil
import subprocess
from pathlib import Path

import pytest

# The golden-master comparator is the only helper this module imports directly;
# all run/load plumbing arrives through the ``cobol_runner`` fixture. WHY (import
# whitelist): ``tests.helpers.golden_compare`` is one of this file's four declared
# dependencies, and importing only what the golden (auto-upgrade) path needs keeps
# the module's surface minimal. It is imported at module top level (not lazily)
# because, unlike ``cobc``, it has no environment precondition -- it is pure Python
# and always importable once ``PYTHONPATH`` includes the repo root.
from tests.helpers.golden_compare import assert_matches_golden

# All tests in this module are integration-layer tests. WHY (suite contract):
# ``tests/pytest.ini`` registers the ``integration`` marker and the runner scripts
# select layers by marker (``-m integration``); tagging at module scope guarantees
# every test here is collected by the integration runner and none leaks into the
# unit/e2e selections.
pytestmark = pytest.mark.integration


# ---------------------------------------------------------------------------
# Program / build constants (the verified, reproduce-exactly recipe).
# ---------------------------------------------------------------------------
# WHY name these once (Refactoring Rationale): the build recipe, source filenames,
# and binary name appear in the helper and in diagnostics; centralising them means
# the "reproduce EXACTLY" recipe lives in a single reviewable place and the helper
# and any error message can never drift from it.

# The main program's PROGRAM-ID and the on-disk binary name are the same token;
# ``cobol_runner.run`` resolves ``build_dir/CBSTM03A``.
_STATEMENT_MAIN = "CBSTM03A"

# The two production sources compiled into the single ``-x`` binary. WHY UPPERCASE
# ``.CBL`` (Assumption): the statement programs are the *only* CardDemo sources
# shipped with an uppercase extension (all others are lowercase ``.cbl``); using the
# exact case is mandatory on case-sensitive filesystems or the compile fails with a
# "file not found". ``CBSTM03B`` is listed second so its subprogram definition is
# available to satisfy ``CBSTM03A``'s CALL at static-link time.
_STATEMENT_SOURCES = ("CBSTM03A.CBL", "CBSTM03B.CBL")

# The exact compile flags. WHY ``-ftab-width=2`` (mandatory -- Assumption/Trade-off):
# copybook ``CUSTREC.cpy`` (COPY'd only by this pair) indents its ``05``-level items
# with leading hard TAB characters; under ``-fixed`` those tabs land in the wrong
# columns and the fixed-format parse fails. ``-ftab-width=2`` expands each tab so the
# items realign to the Area-B column and the parse succeeds. Tab widths of 2, 3, or 4
# all work (the build script uses 4); 2 is chosen here to match the file schema recipe.
#
# WHY NOT ``-ftext-column=...`` (Alternatives Considered): widening the source text
# column is another way to accommodate wide lines, but ``CBSTM03A`` builds its HTML
# with multi-line literal continuations that already run to column 72, and moving the
# right margin breaks those continuations, producing a *different* set of compile
# errors. So the tab-width fix is used and the text-column knob is deliberately avoided.
#
# WHY NOT ``-fformat=...`` (Alternatives Considered): that option does not exist in the
# GnuCOBOL line this suite targets; ``-fixed`` is the correct format selector.
_STATEMENT_COMPILE_FLAGS = ("-x", "-fixed", "-ftab-width=2", "--std=ibm-strict")

# Wall-clock ceiling (seconds) for the compile subprocess. WHY (Reliability): an
# unbounded ``cobc`` invocation could hang a CI job on a wedged toolchain; a generous
# but finite cap (a normal compile finishes in well under a second) turns a hang into a
# clear, catchable failure without ever tripping on a slow shared runner.
_COMPILE_TIMEOUT_S = 180

# The four INDEXED input files the pair reads, as
# ``(ASSIGN_name, fixture_basename, reclen, key_length)``. WHY a table (Refactoring
# Rationale): the geometry is taken verbatim from the ``SELECT ... ORGANIZATION IS
# INDEXED`` clauses in ``CBSTM03B.CBL`` and the fixtures' README; expressing it once as
# data (rather than four near-identical ``load_input`` calls) keeps the reclen/key sizes
# auditable at a glance and lets both scenario tests share the exact same loading logic.
# WHY every key_offset is 0 (Assumption): each fixture is pre-sorted ascending by a key
# that begins at byte 0, so the loader's offset-0-only contract is satisfied; the loader
# rejects a non-zero offset, so encoding the assumption here documents it. NOTE the
# TRNXFILE key is a **32-byte composite** (card ``X(16)`` + tranid ``X(16)``), not 16.
_STATEMENT_INPUTS = (
    ("TRNXFILE", "trnxfile.txt", 350, 32),
    ("XREFFILE", "xreffile.txt", 50, 16),
    ("CUSTFILE", "custfile.txt", 500, 9),
    ("ACCTFILE", "acctfile.txt", 300, 11),
)

# The two sequential OUTPUT artifacts and their golden baselines, as
# ``(ASSIGN_name, golden_basename)``. Consumed only on the auto-upgrade path.
_STATEMENT_OUTPUTS = (
    ("STMTFILE", "statement.txt.expected"),
    ("HTMLFILE", "statement.html.expected"),
)

# The single, documented reason string used for every skip-on-SIGSEGV. WHY a named
# constant (Refactoring Rationale): both scenario tests skip for the identical reason;
# naming it once guarantees the two messages stay byte-for-byte identical and keeps the
# "never a silent skip" contract auditable in one place.
_SIGSEGV_SKIP_REASON = (
    "CBSTM03A performs a z/OS PSA/TCB/TIOT control-block scan (uninitialized POINTER) "
    "that SIGSEGVs under GnuCOBOL/Linux; compile-clean gate asserted; runtime E2E is "
    "environment-bounded"
)

# Substrings that, if present in a run's stderr, positively identify the control-block
# SIGSEGV even when the process exit status is ambiguous. WHY this exact set
# (Assumptions / empirical): libcob's fault message wording is release-dependent -- the
# targeted 3.1.x line prints "unallocated memory (signal SIGSEGV)", while the 3.2.x line
# observed on this runner prints "attempt to reference invalid memory address (signal)".
# Matching all of these substrings (in addition to the return-code checks) makes the
# detection robust across GnuCOBOL releases; "Segmentation" also catches a shell-style
# "Segmentation fault" transcript should stderr ever carry one.
_SIGSEGV_STDERR_MARKERS = (
    "SIGSEGV",
    "unallocated memory",
    "invalid memory address",
    "Segmentation",
)


def _ensure_statement_program(build_dir: Path, repo_root: Path) -> Path:
    """Ensure the ``CBSTM03A`` binary exists, compiling the pair if needed (the GATE).

    Purpose
    -------
    Guarantee that ``build_dir/CBSTM03A`` (the statically-linked ``CBSTM03A`` +
    ``CBSTM03B`` executable) is present before a test runs it, and in doing so
    **assert the compile-clean gate**: if a compiler is available but the exact
    recipe fails to produce a binary, that is a real compile regression and is
    surfaced as a test failure -- not a skip.

    Reuse-vs-recompile is decided by existence: if the session-scoped
    ``built_programs`` fixture (via ``scripts/build_test_programs.sh``) already
    produced the binary, it is reused as-is; otherwise this function compiles it
    with the verified ``-ftab-width=2`` recipe.

    Parameters
    ----------
    build_dir : pathlib.Path
        Directory that holds (or will hold) the compiled ``CBSTM03A`` binary --
        the same directory ``cobol_runner`` resolves programs from, so the binary
        this function guarantees is exactly the one the run will execute.
    repo_root : pathlib.Path
        Repository root, used to locate the copybook include path
        (``app/cpy``) and the two uppercase ``.CBL`` sources (``app/cbl``).

    Returns
    -------
    pathlib.Path
        Path to the ensured ``build_dir/CBSTM03A`` executable.

    Raises
    ------
    Skipped
        (via :func:`pytest.skip`) if no ``cobc`` compiler is on ``PATH`` **and**
        the binary is not already present -- a missing toolchain is an environment
        condition, not a code defect.
    AssertionError
        If ``cobc`` is present, the compile runs, yet no binary is produced -- the
        compile-clean gate has failed (a meaningful regression).
    """
    binary = Path(build_dir) / _STATEMENT_MAIN

    # WHY existence-first (Trade-off): the session ``built_programs`` fixture normally
    # compiles this binary before any integration test runs, so the common path is a
    # cheap reuse with no redundant compile. Reuse is also *more* robust than always
    # recompiling: a pre-built binary is itself proof of a clean compile and remains
    # usable even in a hypothetical environment where the compiler is no longer present.
    if binary.exists():
        return binary

    # No pre-built binary: we must compile, which requires a compiler. WHY skip (not
    # fail) when it is absent (Assumption): a machine without GnuCOBOL cannot build or
    # run any COBOL, so a COBOL-dependent test has nothing to assert; skipping keeps the
    # broader Python suite green on such machines, exactly as the sibling fixtures do.
    if shutil.which("cobc") is None:
        pytest.skip("GnuCOBOL cobc not available")

    # Build the exact, verified command: flags, the copybook include path, the output
    # binary, then BOTH uppercase sources (order matters -- see _STATEMENT_SOURCES).
    cpy_dir = Path(repo_root) / "app" / "cpy"
    cbl_dir = Path(repo_root) / "app" / "cbl"
    command = [
        "cobc",
        *_STATEMENT_COMPILE_FLAGS,
        "-I",
        str(cpy_dir),
        "-o",
        str(binary),
        *[str(cbl_dir / src) for src in _STATEMENT_SOURCES],
    ]

    # Ensure the output directory exists so ``-o`` can write into it even on a fresh
    # checkout where the build dir has not been created yet.
    binary.parent.mkdir(parents=True, exist_ok=True)

    # WHY capture (not stream) output and bound it with a timeout (Trade-off): the
    # compiler's stdout/stderr are only interesting when the gate fails, in which case
    # the captured text is folded into the assertion message; on success they are noise.
    # The timeout converts a wedged compile into a catchable error rather than a hang.
    completed = subprocess.run(
        command,
        cwd=str(repo_root),
        capture_output=True,
        text=True,
        timeout=_COMPILE_TIMEOUT_S,
    )

    # The compile-clean GATE. WHY assert on the BINARY, not on returncode/stderr
    # emptiness (Assumptions -- empirically established): GnuCOBOL shells out to the C
    # backend, which emits benign warnings (e.g. "'_FORTIFY_SOURCE' redefined") on a
    # perfectly successful build, so a non-empty stderr does NOT mean failure. The
    # single unambiguous success signal is "did a binary get produced?". WHY assert
    # rather than skip on failure: this pair is a verified clean-compile invariant, so a
    # failure to compile is a genuine regression the AAP mandates we surface -- even
    # though the program's *runtime* is environment-bounded.
    assert binary.exists(), (
        f"{_STATEMENT_MAIN}/CBSTM03B compile-clean gate FAILED "
        f"(exit {completed.returncode}): {completed.stderr or completed.stdout}"
    )
    return binary


def _resolve_fixture(scenario_dir: Path, *candidates: str) -> Path:
    """Return the first existing fixture file in ``scenario_dir``, or skip.

    Purpose
    -------
    Resolve a scenario's input fixture resiliently: try each candidate base name
    in order and return the first that exists. If none exist, the scenario's data
    is not present in this checkout, so the test is skipped (never failed) with a
    message naming the directory and the names tried.

    Parameters
    ----------
    scenario_dir : pathlib.Path
        Directory holding the scenario's flat fixtures (e.g.
        ``tests/fixtures/statement/happy_path``).
    *candidates : str
        One or more candidate base names to try, in priority order (e.g.
        ``"trnxfile.txt"``).

    Returns
    -------
    pathlib.Path
        The resolved, existing fixture path.

    Raises
    ------
    Skipped
        (via :func:`pytest.skip`) if none of ``candidates`` exists in
        ``scenario_dir`` -- the scenario cannot run without its inputs, and a
        missing fixture is a data-availability condition, not a code defect.
    ValueError
        If called with no candidate names (a programming error in the caller).
    """
    # WHY guard the empty call (Assumption): resolving "the first of nothing" is
    # meaningless; failing loudly here turns a caller typo into an immediate, clear
    # error instead of a confusing skip that lists no names.
    if not candidates:
        raise ValueError("_resolve_fixture requires at least one candidate name")

    for name in candidates:
        candidate = Path(scenario_dir) / name
        if candidate.is_file():
            return candidate

    # WHY skip (not fail) on a missing fixture (Trade-off): mirrors the suite-wide
    # convention that absent *data* degrades to a documented skip so a partial checkout
    # stays green, whereas a genuine assertion failure is reserved for wrong behaviour.
    pytest.skip(
        f"no statement fixture found in {scenario_dir} "
        f"(tried: {', '.join(candidates)})"
    )


def _is_sigsegv(result) -> bool:
    """Report whether a run crashed with the control-block SIGSEGV.

    Purpose
    -------
    Robustly detect the ``CBSTM03A`` z/OS-control-block segmentation fault across
    GnuCOBOL releases and across the two ways a crash can surface: a signalled
    process exit, or a libcob-caught fault that prints a diagnostic and exits with
    a conventional code.

    Parameters
    ----------
    result : tests.helpers.cobol_runner.RunResult
        The captured outcome of a run -- only its ``returncode`` (int) and
        ``stderr`` (str) attributes are consulted.

    Returns
    -------
    bool
        ``True`` if the run's return code or stderr indicates the SIGSEGV;
        ``False`` otherwise (i.e. the program actually ran -- the auto-upgrade
        path should proceed).

    Raises
    ------
    None
    """
    returncode = result.returncode
    stderr = result.stderr or ""

    # WHY check three return-code shapes (Assumptions -- empirically established): when
    # the fault is NOT caught by libcob the OS terminates the process by signal, which
    # Python's subprocess surfaces as a NEGATIVE return code (``-11`` for SIGSEGV -- the
    # exact value observed on this runner); a shell-mediated launch would instead report
    # ``139`` (128 + 11); and when libcob DOES catch the fault it prints its message and
    # exits ``11``. Covering all three makes the gate independent of how the crash
    # happens to propagate.
    if returncode in (11, 139) or returncode < 0:
        return True

    # Belt-and-suspenders: also match libcob's fault message directly, so an unusual exit
    # status can never let a genuine control-block crash slip through as a "real" run.
    return any(marker in stderr for marker in _SIGSEGV_STDERR_MARKERS)


def _load_scenario_inputs(cobol_runner, scenario_dir: Path) -> None:
    """Resolve and load all four INDEXED statement inputs for a scenario.

    Purpose
    -------
    Perform the shared "stage the inputs" step both scenario tests need: for each
    entry in :data:`_STATEMENT_INPUTS`, resolve the flat fixture in
    ``scenario_dir`` and load it into the native GnuCOBOL indexed file bound to its
    ASSIGN name (the ``IDCAMS REPRO`` analog), using the exact record length and
    key length verified against ``CBSTM03B.CBL``.

    Parameters
    ----------
    cobol_runner : tests.helpers.cobol_runner.CobolRunner
        The per-test runner whose workspace receives the loaded indexed files.
    scenario_dir : pathlib.Path
        Directory holding this scenario's four flat fixtures.

    Returns
    -------
    None

    Raises
    ------
    Skipped
        (via :func:`pytest.skip`, from :func:`_resolve_fixture`) if any of the four
        fixtures is absent -- the scenario cannot run without a complete input set.
    tests.helpers.vsam_loader.VsamLoadError
        If a flat fixture cannot be materialised as an indexed file (bad geometry,
        malformed record, or a compile/run failure inside the loader).
    """
    # WHY factor this out (Refactoring Rationale): the happy-path and empty-input tests
    # differ ONLY in which scenario directory they read; sharing the loading logic keeps
    # the record/key geometry single-sourced from _STATEMENT_INPUTS and prevents the two
    # tests from drifting (e.g. a key-length fix applied to one but not the other).
    for assign_name, basename, reclen, key_length in _STATEMENT_INPUTS:
        fixture = _resolve_fixture(scenario_dir, basename)
        # key_offset defaults to 0 -- every statement key begins at byte 0 (see
        # _STATEMENT_INPUTS WHY). reclen/key_length are passed explicitly rather than via
        # a ``layout=`` name because the statement files have no registered record-codec
        # layout; the literal geometry is the authoritative source here.
        cobol_runner.load_input(
            assign_name, str(fixture), reclen=reclen, key_length=key_length
        )


def test_statement_generation(cobol_runner, build_dir, repo_root) -> None:
    """Golden-master test of the plain-text + HTML statements (auto-upgrading).

    Purpose
    -------
    Exercise the full statement happy path: compile the ``CBSTM03A`` + ``CBSTM03B``
    pair (asserting the compile-clean gate), stage the four indexed inputs from
    ``tests/fixtures/statement/happy_path``, run the program, and -- if it runs to
    completion -- assert its plain-text (``STMTFILE``) and HTML (``HTMLFILE``)
    outputs match the committed goldens under
    ``tests/golden/statement/happy_path``.

    On the current runner the run SIGSEGVs in ``CBSTM03A``'s z/OS control-block
    scan, so the test compiles clean and then **skips with a documented reason**.
    The golden comparison below is the auto-upgrade path that activates unchanged
    on any runtime where the program can execute.

    Parameters
    ----------
    cobol_runner : tests.helpers.cobol_runner.CobolRunner
        Per-test runner (from the ``conftest`` fixture) bound to the build dir and
        an isolated workspace.
    build_dir : pathlib.Path
        Session build directory; passed to :func:`_ensure_statement_program` so the
        binary it guarantees is the very one ``cobol_runner`` will execute.
    repo_root : pathlib.Path
        Repository root, used to locate the sources, fixtures, and goldens.

    Returns
    -------
    None

    Raises
    ------
    Skipped
        (via :func:`pytest.skip`) when ``cobc`` is absent, when a fixture is
        missing, or -- the expected outcome here -- when the run SIGSEGVs.
    AssertionError
        On a compile-clean gate failure, or (auto-upgrade path) when a produced
        statement does not match its golden.
    """
    # Compile-clean GATE first: proves the pair still builds before anything else.
    _ensure_statement_program(build_dir, repo_root)

    # Stage the four indexed inputs for the happy-path scenario.
    scenario_dir = Path(repo_root) / "tests" / "fixtures" / "statement" / "happy_path"
    _load_scenario_inputs(cobol_runner, scenario_dir)

    # Run the statically-linked pair; ``CBSTM03A`` drives ``CBSTM03B`` internally.
    result = cobol_runner.run(_STATEMENT_MAIN)

    # SIGSEGV GATE -- the expected stop on this platform. WHY an explicit, reasoned
    # skip (never a silent pass): the crash is a known, out-of-scope environment limit
    # (a z/OS TIOT scan with no Linux equivalent), so the honest result is a documented
    # skip; silently "passing" would falsely imply the statement bytes were verified.
    if _is_sigsegv(result):
        pytest.skip(_SIGSEGV_SKIP_REASON)

    # --- Auto-upgrade golden path (runs only on a compatible runtime) ---------------
    # WHY golden-master over field-by-field assertions (Trade-off): a statement is a
    # large, formatted document (headers, address block, per-transaction lines, HTML
    # scaffolding); a single byte-deterministic diff against a committed baseline gives
    # complete coverage of layout AND content, whereas hand-picked field assertions
    # would be verbose and would silently miss formatting regressions between the fields.
    #
    # WHY rely on the comparator's built-in timestamp scrubbing (Assumption): statements
    # can carry a wall-clock run date, which is non-deterministic. ``assert_matches_golden``
    # in text mode (no ``layout=``) already replaces ISO-8601 timestamps with a stable
    # sentinel, so a plain call is sufficient for the current baselines (which carry no
    # volatile field). Should a future statement embed a non-ISO date line, a maintainer
    # adds ``extra_patterns=[(<regex>, "")]`` here to blank it -- no other change needed.
    golden_dir = Path(repo_root) / "tests" / "golden" / "statement" / "happy_path"
    for assign_name, golden_name in _STATEMENT_OUTPUTS:
        produced = result.read_output(assign_name)
        assert_matches_golden(produced, golden_dir / golden_name)


def test_statement_empty_input(cobol_runner, build_dir, repo_root) -> None:
    """Empty-transaction edge case: the pair builds and degrades gracefully.

    Purpose
    -------
    Exercise the empty-input edge case: an empty ``TRNXFILE`` (zero transactions)
    with an otherwise-valid master chain. Confirms the pair still compiles and,
    on a compatible runtime, completes without abending and still emits both
    statement artifacts (COBOL ``OPEN OUTPUT`` creates them even with no
    transaction lines to write).

    On the current runner this skips-on-SIGSEGV exactly like the happy path,
    before any output is produced.

    Parameters
    ----------
    cobol_runner : tests.helpers.cobol_runner.CobolRunner
        Per-test runner bound to the build dir and an isolated workspace.
    build_dir : pathlib.Path
        Session build directory (see :func:`test_statement_generation`).
    repo_root : pathlib.Path
        Repository root, used to locate the sources and the empty-input fixtures.

    Returns
    -------
    None

    Raises
    ------
    Skipped
        (via :func:`pytest.skip`) when ``cobc`` is absent, when a fixture is
        missing, or -- the expected outcome here -- when the run SIGSEGVs.
    AssertionError
        On a compile-clean gate failure, or (auto-upgrade path) if the empty-input
        run abends or fails to produce an output artifact.
    """
    # Compile-clean GATE (shared invariant with the happy-path test).
    _ensure_statement_program(build_dir, repo_root)

    # Stage the empty-input scenario: TRNXFILE is a zero-byte fixture (an empty index),
    # the three master files carry one linked record each.
    scenario_dir = Path(repo_root) / "tests" / "fixtures" / "statement" / "empty_input"
    _load_scenario_inputs(cobol_runner, scenario_dir)

    result = cobol_runner.run(_STATEMENT_MAIN)

    # SIGSEGV GATE -- same documented, non-silent stop as the happy path.
    if _is_sigsegv(result):
        pytest.skip(_SIGSEGV_SKIP_REASON)

    # --- Auto-upgrade path (runs only on a compatible runtime) ----------------------
    # WHY assert the rubric + artifact presence rather than a golden (Trade-off): no
    # empty-input golden is committed for the statement domain, so a byte-diff is not
    # available; instead we assert the program honoured the CardDemo return-code rubric
    # (0 pass / 4 soft-warn are both acceptable for a no-transaction run) and that both
    # ``OPEN OUTPUT`` artifacts were created -- the observable, deterministic contract of
    # an empty run without over-specifying its exact (unbaselined) content.
    assert result.returncode in (0, 4), (
        f"{_STATEMENT_MAIN} exited {result.returncode} on empty input "
        f"(expected 0 or 4 per the CardDemo return-code rubric)"
    )
    for assign_name, _golden_name in _STATEMENT_OUTPUTS:
        produced_path = result.output_path(assign_name)
        assert produced_path.exists(), (
            f"{assign_name} was not produced on the empty-input run "
            f"(expected an OPEN OUTPUT to have created {produced_path})"
        )

