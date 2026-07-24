"""Reusable test-only compatibility build + run + verify for the CardDemo statement pair.

Purpose
-------
Factor the platform-compatibility machinery that lets the z/OS-oriented statement
generator (``CBSTM03A`` + its statically-linked I/O subprogram ``CBSTM03B``)
genuinely RUN on this Linux/GnuCOBOL runner into ONE shared module, so BOTH the
Layer-2 integration test (``tests/integration/test_cbstm03a_statement.py``) and the
Layer-3 end-to-end full-batch-cycle test (``tests/e2e/test_full_batch_cycle.py``)
can exercise and assert the statement stage without duplicating the delicate
read-patch-compile-publish logic.

Why a shared helper rather than importing from the integration test (WHY -
Refactoring Rationale + scope): the integration test is a *passing* artifact that
the QA plan (AAP 0.8) forbids modifying, and importing test modules across the
suite is fragile (pytest collection side-effects, marker leakage). Extracting the
mechanism into a helper under ``tests/helpers/`` -- which AAP 0.8.1 lists as
in-scope -- lets the e2e cycle reuse it verbatim while the integration test keeps
its own inline copy untouched. The two copies intentionally share the same
constants and algorithm so they never diverge in behaviour.

What "compat" means (WHY - Assumptions, EMPIRICALLY VERIFIED)
------------------------------------------------------------
* ``CBSTM03A`` opens its run with a z/OS control-block scan
  (``SET ADDRESS OF PSA-BLOCK TO PSAPTR`` ... a chain of uninitialised POINTER
  dereferences) that SIGSEGVs off-mainframe. :func:`neutralize_zos_scan` replaces
  ONLY the statements between that marker and the first business-logic statement
  (``OPEN OUTPUT STMT-FILE``) with a single ``CONTINUE.``; every other byte is
  preserved, so the compiled binary emits the exact same statement bytes minus the
  crashing diagnostic. The production source on disk is NEVER modified -- it is read,
  patched in memory, written to a private scratch ``.CBL``, compiled, and only the
  finished binary is atomically published.
* ``-fsign=EBCDIC`` is mandatory for the compat build: the statement fixtures encode
  signed zoned-decimal money with EBCDIC sign overpunch, and the committed goldens
  were produced under that sign convention, so an ASCII-sign build would render
  negative amounts differently and mismatch the golden.

Determinism & isolation
-----------------------
The compat binary is content-identical for a given production source, is built at
most once per ``build_dir`` (existence-checked), and is published via an atomic
same-filesystem ``os.replace`` so parallel xdist workers cannot observe a
half-built binary.

Binding contract
----------------
Imported by ``tests/e2e/test_full_batch_cycle.py`` (statement stage). Depends only
on the standard library and ``tests.helpers.cobol_runner`` (via the caller's runner
instance); it never imports pytest so it stays a pure, side-effect-free helper.
"""
from __future__ import annotations

import os
import shutil
import subprocess
import tempfile
from pathlib import Path

# ---------------------------------------------------------------------------
# Program identity + build recipe (single-sourced from the CBSTM03A/B contract).
# ---------------------------------------------------------------------------

# The on-disk / PROGRAM-ID name of the REAL pair's main program.
STATEMENT_MAIN = "CBSTM03A"

# The two production sources compiled into the single ``-x`` binary. WHY UPPERCASE
# ``.CBL`` (Assumption): the statement programs are the only CardDemo sources shipped
# with an uppercase extension; the exact case is mandatory on case-sensitive
# filesystems. ``CBSTM03B`` is listed second so its subprogram definition satisfies
# ``CBSTM03A``'s CALL at static-link time.
_STATEMENT_SOURCES = ("CBSTM03A.CBL", "CBSTM03B.CBL")

# The production compile recipe. WHY ``-ftab-width=2`` (mandatory): copybook
# ``CUSTREC.cpy`` (COPY'd only by this pair) indents its 05-level items with leading
# hard TABs; under ``-fixed`` those tabs must be expanded to realign to Area B or the
# fixed-format parse fails. WHY ``--std=ibm-strict`` (convention): the repository's
# documented dialect, which (unlike ``-std=cobol85``) accepts the COMP-3 money fields.
_STATEMENT_COMPILE_FLAGS = ("-x", "-fixed", "-ftab-width=2", "--std=ibm-strict")

# The compat-build flags: the production recipe PLUS ``-fsign=EBCDIC`` (see module
# docstring for WHY this is mandatory for a golden-matching run).
_STATEMENT_COMPAT_COMPILE_FLAGS = _STATEMENT_COMPILE_FLAGS + ("-fsign=EBCDIC",)

# Wall-clock ceiling (seconds) for the compile subprocess. WHY (Reliability): an
# unbounded ``cobc`` could hang CI on a wedged toolchain; a finite cap turns a hang
# into a clear failure without tripping on a slow shared runner.
_COMPILE_TIMEOUT_S = 180

# The distinct on-disk name of the compat binary. WHY a distinct name (safety): it
# can never be mistaken for, or overwrite, the compile-clean-gate ``CBSTM03A`` binary
# the session build produces from the unmodified sources.
COMPAT_MAIN = "CBSTM03A_LINUXCOMPAT"

# The two stable source anchors that bracket the z/OS control-block scan. WHY marker
# matching over line numbers (robustness): pinning line numbers would silently break
# if the production source shifted; these two unique landmarks localise the patch and
# fail loudly if either ever disappears.
_ZOS_SCAN_START_MARKER = "SET ADDRESS OF PSA-BLOCK"
_ZOS_SCAN_END_MARKER = "OPEN OUTPUT STMT-FILE"

# The four INDEXED inputs the pair reads, as
# ``(ASSIGN_name, fixture_basename, reclen, key_length)``; geometry taken verbatim
# from the ``SELECT ... ORGANIZATION IS INDEXED`` clauses in ``CBSTM03B.CBL``. NOTE
# the TRNXFILE key is a 32-byte composite (card X(16) + tranid X(16)).
STATEMENT_INPUTS = (
    ("TRNXFILE", "trnxfile.txt", 350, 32),
    ("XREFFILE", "xreffile.txt", 50, 16),
    ("CUSTFILE", "custfile.txt", 500, 9),
    ("ACCTFILE", "acctfile.txt", 300, 11),
)

# The two sequential OUTPUT artifacts and their committed golden baselines, as
# ``(ASSIGN_name, golden_basename)``.
STATEMENT_OUTPUTS = (
    ("STMTFILE", "statement.txt.expected"),
    ("HTMLFILE", "statement.html.expected"),
)

# Fixed-width record geometry of the two sequential OUTPUT artifacts (from the FDs in
# CBSTM03A.CBL): STMT-FILE writes 80-byte print records, HTML-FILE writes 100-byte
# records. GnuCOBOL writes SEQUENTIAL files as contiguous fixed records with NO
# delimiter, so the raw bytes must be re-framed by these widths before comparison.
STATEMENT_OUTPUT_WIDTHS = {"STMTFILE": 80, "HTMLFILE": 100}

# stderr substrings that positively identify the control-block SIGSEGV across GnuCOBOL
# releases (wording is release-dependent). Used by :func:`is_sigsegv`.
_SIGSEGV_STDERR_MARKERS = (
    "SIGSEGV",
    "unallocated memory",
    "invalid memory address",
    "Segmentation",
)


def neutralize_zos_scan(source_text: str) -> str:
    """Return ``CBSTM03A`` source with ONLY its z/OS control-block scan removed.

    Purpose
    -------
    Produce the text of the test-only compat variant: identical to the production
    ``CBSTM03A.CBL`` except that the statements between the
    ``SET ADDRESS OF PSA-BLOCK`` marker and the ``OPEN OUTPUT STMT-FILE`` marker (the
    uninitialised-POINTER PSA/TCB/TIOT scan that SIGSEGVs off-mainframe) are replaced
    by a single ``CONTINUE.``. Every other byte is preserved verbatim, so the compiled
    binary computes the exact same statement bytes the real program would, minus only
    the crashing diagnostic DISPLAY.

    Parameters
    ----------
    source_text : str
        Full text of the production ``CBSTM03A.CBL`` (read, never written).

    Returns
    -------
    str
        The patched source text for the compat compile.

    Raises
    ------
    ValueError
        If either marker is missing, or they appear out of order -- a signal that the
        production source changed shape and the patch can no longer be localised
        safely (fail loudly rather than emit a subtly-wrong binary).
    """
    lines = source_text.splitlines(keepends=True)
    start_idx = end_idx = None
    for idx, line in enumerate(lines):
        if start_idx is None and _ZOS_SCAN_START_MARKER in line:
            start_idx = idx
        # Only accept the END marker at or after START so an unrelated earlier mention
        # could never mis-bracket the region.
        if start_idx is not None and _ZOS_SCAN_END_MARKER in line:
            end_idx = idx
            break
    # WHY fail loudly on a missing/mis-ordered marker (Trade-off): silently returning
    # the source unpatched would recreate the SIGSEGV; silently guessing an offset
    # could delete real logic. A ValueError surfaces the exact cause so a maintainer
    # updates the markers deliberately after a production-source change.
    if start_idx is None or end_idx is None or end_idx <= start_idx:
        raise ValueError(
            "could not locate the z/OS control-block scan in CBSTM03A.CBL "
            f"(start marker {_ZOS_SCAN_START_MARKER!r} at {start_idx}, "
            f"end marker {_ZOS_SCAN_END_MARKER!r} at {end_idx}); the production "
            "source shape changed -- update the markers before rebuilding the compat "
            "binary."
        )
    # Replace [start_idx, end_idx) with one Area-B ``CONTINUE.``; the OPEN statement at
    # end_idx (start of business logic) is preserved as the next line. WHY CONTINUE.
    # (Assumption): the scan sits in CBSTM03A's implicit first paragraph; a no-op keeps
    # that paragraph non-empty and control flow intact after the DISPLAY block is
    # excised.
    patched = lines[:start_idx] + ["           CONTINUE.\n"] + lines[end_idx:]
    return "".join(patched)


def ensure_compat_binary(build_dir: "str | os.PathLike[str]",
                         repo_root: "str | os.PathLike[str]") -> "Path | None":
    """Build (once) the test-only ``CBSTM03A_LINUXCOMPAT`` binary; return its path.

    Purpose
    -------
    Guarantee that ``build_dir/CBSTM03A_LINUXCOMPAT`` -- the statically-linked
    ``CBSTM03A`` (z/OS scan neutralised) + unmodified ``CBSTM03B`` executable --
    exists so a caller can genuinely RUN the statement generator on this runner and
    assert its output against the goldens. Reuse-vs-rebuild is decided by existence,
    so the compat compile happens at most once per ``build_dir``.

    The production ``CBSTM03A.CBL`` is READ ONLY: patched in memory, written to a
    private scratch ``.CBL`` inside ``build_dir``, compiled, and only the finished
    binary is atomically published (``os.replace``) -- the on-disk production source
    is never modified and no stray ``.CBL`` is left behind.

    Parameters
    ----------
    build_dir : str | os.PathLike[str]
        Directory that holds (or will hold) the compat binary -- the same directory
        ``cobol_runner`` resolves programs from, so the returned binary is exactly the
        one a subsequent ``run(COMPAT_MAIN)`` executes.
    repo_root : str | os.PathLike[str]
        Repository root, used to locate the copybook include path (``app/cpy``) and
        the two uppercase ``.CBL`` sources (``app/cbl``).

    Returns
    -------
    pathlib.Path | None
        Path to the ensured compat executable, or ``None`` if the environment cannot
        build it (no compiler / missing sources / compat compile failed). A ``None``
        return is the caller's cue to apply its own strict-gate policy (skip vs fail).

    Raises
    ------
    ValueError
        Propagated from :func:`neutralize_zos_scan` if the scan markers cannot be
        located (a genuine, must-surface production-source-shape change).
    """
    build_dir = Path(build_dir)
    repo_root = Path(repo_root)
    binary = build_dir / COMPAT_MAIN
    # WHY existence-first (idempotence): cheap reuse keeps the compat compile to at most
    # once per session and lets every statement consumer share the artifact.
    if binary.exists():
        return binary
    # No compiler -> the environment genuinely cannot build the compat binary. Return
    # None so the caller applies the strict gate rather than deciding policy here.
    if shutil.which("cobc") is None:
        return None
    source = repo_root / "app" / "cbl" / _STATEMENT_SOURCES[0]
    subprogram = repo_root / "app" / "cbl" / _STATEMENT_SOURCES[1]
    cpy_dir = repo_root / "app" / "cpy"
    if not source.is_file() or not subprogram.is_file():
        # A partial checkout missing the sources is an environment/data condition, not
        # a code defect; signal "cannot build" and let the strict gate decide.
        return None
    binary.parent.mkdir(parents=True, exist_ok=True)
    patched_text = neutralize_zos_scan(source.read_text(encoding="latin-1"))
    # WHY compile in a private scratch dir INSIDE build_dir, then atomically publish
    # (parallel safety): doing the patch-write and compile in a throwaway directory and
    # ``os.replace``-ing only the finished binary (a) leaves no stray ``.CBL`` behind
    # and (b) makes publication atomic and same-filesystem, so a concurrent xdist
    # worker can never observe or race a half-built binary.
    scratch = Path(tempfile.mkdtemp(prefix=".stmt_compat_", dir=str(build_dir)))
    try:
        patched_src = scratch / f"{COMPAT_MAIN}.CBL"
        patched_src.write_text(patched_text, encoding="latin-1")
        tmp_binary = scratch / COMPAT_MAIN
        command = [
            "cobc",
            *_STATEMENT_COMPAT_COMPILE_FLAGS,
            "-I", str(cpy_dir),
            "-o", str(tmp_binary),
            str(patched_src),
            str(subprogram),
        ]
        subprocess.run(
            command,
            cwd=str(repo_root),
            capture_output=True,
            text=True,
            timeout=_COMPILE_TIMEOUT_S,
        )
        # WHY treat a missing binary as "cannot build" -> None (Trade-off): a compat
        # compile failure is not a production regression (the REAL pair's compile is
        # gated separately by the session build); it is an environment/toolchain
        # limitation on running the program here, so the strict gate is the right
        # policy for the caller to apply.
        if not tmp_binary.exists():
            return None
        os.replace(str(tmp_binary), str(binary))
    finally:
        shutil.rmtree(scratch, ignore_errors=True)
    return binary


def frame_fixed_width_records(data: str, width: int) -> str:
    """Frame a record-sequential output stream into newline-delimited records.

    Purpose
    -------
    Convert the raw bytes ``CBSTM03A`` writes to ``STMTFILE`` / ``HTMLFILE`` into the
    newline-delimited, right-trimmed form the committed goldens use, so
    :func:`tests.helpers.golden_compare.assert_matches_golden` (text mode) compares
    like-for-like. GnuCOBOL writes a SEQUENTIAL file as contiguous fixed-``width``
    records with NO delimiter, so the stream must be sliced by width before comparison.

    Parameters
    ----------
    data : str
        Raw output content (decoded ``latin-1`` so every byte maps 1:1), either
        contiguous fixed-width records or (defensively) already newline-delimited.
    width : int
        Fixed logical record width used to slice contiguous data (80 for ``STMTFILE``,
        100 for ``HTMLFILE`` -- see :data:`STATEMENT_OUTPUT_WIDTHS`).

    Returns
    -------
    str
        Framed text: each record right-stripped of trailing blanks and terminated by a
        single ``"\\n"``. An empty input yields ``""``.

    Raises
    ------
    None
    """
    if not data:
        return ""
    # WHY tolerate BOTH framings (Trade-off): the record-sequential case (no delimiter)
    # is what GnuCOBOL emits here and is handled by width-slicing; the newline branch is
    # a defensive fallback for a future runtime that writes line-delimited output.
    # Trying the delimiter first would be WRONG -- a stray 0x0A inside a fixed record
    # would mis-split -- so width-slicing is preferred whenever a delimiter is absent.
    if "\n" in data:
        records = data.split("\n")
        if records and records[-1] == "":
            records.pop()
    else:
        records = [data[i:i + width] for i in range(0, len(data), width)]
    # WHY rstrip each record then re-join with "\n" (Assumption): the goldens store each
    # line right-trimmed and newline-terminated (fixed-width blank padding is not
    # meaningful output), so trimming makes the produced bytes directly comparable to
    # the committed baseline without widening the comparator's tolerance.
    return "".join(record.rstrip() + "\n" for record in records)


def load_statement_inputs(runner, scenario_dir: "str | os.PathLike[str]") -> None:
    """Load all four INDEXED statement inputs from a scenario directory into a runner.

    Purpose
    -------
    Stage the statement inputs the pair reads: for each entry in
    :data:`STATEMENT_INPUTS`, resolve the flat fixture in ``scenario_dir`` and load it
    into the native GnuCOBOL indexed file bound to its ASSIGN name (the ``IDCAMS
    REPRO`` analog), using the exact record length and key length verified against
    ``CBSTM03B.CBL``.

    Parameters
    ----------
    runner : tests.helpers.cobol_runner.CobolRunner
        Runner whose isolated workspace receives the loaded indexed files.
    scenario_dir : str | os.PathLike[str]
        Directory holding this scenario's four flat fixtures.

    Returns
    -------
    None

    Raises
    ------
    FileNotFoundError
        If a required fixture is absent from ``scenario_dir`` (the statement stage
        cannot run without a complete input set).
    tests.helpers.vsam_loader.VsamLoadError
        If a flat fixture cannot be materialised as an indexed file (bad geometry,
        malformed record, or a loader compile/run failure).
    """
    scenario_dir = Path(scenario_dir)
    # WHY key_offset defaults to 0 (Assumption): every statement key begins at byte 0
    # (the fixtures are pre-sorted ascending by that key); the loader rejects a non-zero
    # offset, so this assumption is enforced by the loader itself. reclen/key_length are
    # passed literally because the statement files have no registered record-codec
    # layout -- the geometry table STATEMENT_INPUTS is the authoritative source.
    for assign_name, basename, reclen, key_length in STATEMENT_INPUTS:
        fixture = scenario_dir / basename
        if not fixture.is_file():
            raise FileNotFoundError(
                f"statement fixture {basename!r} not found in {scenario_dir}"
            )
        runner.load_input(
            assign_name, str(fixture), reclen=reclen, key_length=key_length
        )


def is_sigsegv(result) -> bool:
    """Report whether a run result looks like the z/OS control-block SIGSEGV.

    Purpose
    -------
    Give callers a robust, release-independent residual-crash guard: even after the
    scan is neutralised, an honest outcome (skip/fail per the caller's policy) is
    better than a confusing assertion on empty output should some other fault surface
    as a SIGSEGV.

    Parameters
    ----------
    result : tests.helpers.cobol_runner.RunResult
        The completed run to inspect (its ``returncode`` and ``stderr``).

    Returns
    -------
    bool
        ``True`` if the return code is the POSIX SIGSEGV signal (``-11``) or the stderr
        carries any known libcob fault marker; ``False`` otherwise.

    Raises
    ------
    None
    """
    # WHY check BOTH the signal code and stderr wording (Assumption): a process killed
    # by SIGSEGV reports returncode ``-11`` under subprocess, but libcob sometimes traps
    # the fault and exits non-negatively while printing a diagnostic, so matching the
    # wording as well makes detection robust across GnuCOBOL releases.
    if getattr(result, "returncode", 0) == -11:
        return True
    stderr = getattr(result, "stderr", "") or ""
    return any(marker in stderr for marker in _SIGSEGV_STDERR_MARKERS)
