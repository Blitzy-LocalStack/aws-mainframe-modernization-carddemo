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

Run status on this platform -- compile-clean GATE + test-only compat build
--------------------------------------------------------------------------
The pair **compiles cleanly** under GnuCOBOL with the ``-ftab-width=2`` recipe
below, but the *unmodified* ``CBSTM03A`` **cannot run to completion off a
mainframe**: the very first statements of its ``PROCEDURE DIVISION`` perform a
z/OS control-block scan (``SET ADDRESS OF PSA-BLOCK TO PSAPTR`` -- where
``PSAPTR`` is an *uninitialized* ``POINTER`` -- then dereference the TCB and walk
the TIOT to print DD names). Under GnuCOBOL on Linux there is no PSA/TCB/TIOT, so
dereferencing the null/garbage pointer faults with SIGSEGV **before any file is
opened** (the OPEN of ``STMTFILE`` / ``HTMLFILE`` comes several statements later).

This diagnostic scan is a pure operator-facing DISPLAY of the JCL/DD context; it
computes **nothing** the statement output depends on. That lets the test cross the
platform gap without ever touching production COBOL: it builds a **test-only
platform-compatibility binary** in which *only* that z/OS scan is neutralised, and
runs THAT to genuinely produce and verify the statement bytes on this runner.

This module therefore does three things, in order (see the QA finding F-STMT-SKIP
this design resolves -- the feature must be genuinely exercised, not skipped):

1. **Asserts the compile-clean gate on the REAL, unmodified pair.** The
   ``CBSTM03A`` + ``CBSTM03B`` pair is a verified clean-compile invariant; a
   compile *regression* on the production sources is a real, meaningful failure,
   so :func:`_ensure_statement_program` compiles the *unmodified* pair and asserts
   a binary was produced. This invariant is preserved exactly as before.
2. **Builds a test-only compat binary** (:func:`_ensure_statement_compat_program`).
   It reads the production ``CBSTM03A.CBL`` at run time, replaces ONLY the z/OS
   PSA/TCB/TIOT scan (the lines between the ``SET ADDRESS OF PSA-BLOCK`` and
   ``OPEN OUTPUT STMT-FILE`` markers) with a single ``CONTINUE.`` in a private
   scratch copy, and compiles that copy + the unmodified ``CBSTM03B`` into a
   distinctly-named ``CBSTM03A_LINUXCOMPAT`` executable. **The production file on
   disk is never modified** -- it is only read; the patch lives solely in scratch
   and only the finished binary is published (the same read-patch-compile-publish
   pattern the sibling ``CSUTLDTC`` date test uses for its shim/driver).
3. **Runs the compat binary and asserts the goldens.** The plain-text
   (``STMTFILE``) and HTML (``HTMLFILE``) artifacts are framed into their
   fixed-width records and compared byte-for-byte against the committed baselines
   under ``tests/golden/statement/happy_path/``. On this runner that comparison
   runs for real and PASSES.

Genuine-degradation fallback (never a silent skip). If -- and only if -- the
environment cannot build the compat binary (no ``cobc`` on PATH, or the compat
compile fails), or the compat binary still cannot run, the test routes through the
local strict gate (:func:`_require_or_skip`, mirroring ``conftest``): a clean,
documented skip in default mode, and a HARD failure under
``CARDDEMO_REQUIRE_COBOL`` so a required-COBOL CI can never go green by skipping.

Why the goldens encode two documented product defects (out of scope to fix)
---------------------------------------------------------------------------
The committed goldens are a faithful characterisation of what the *current*
production program emits, INCLUDING two behaviours the QA review flagged as
product defects that are out of scope to fix here (production COBOL is REFERENCE
only, AAP Section 0.8.2). The goldens therefore encode them, and matching the
goldens is the honest, auditable record of current behaviour -- not an
endorsement:

* **F-STMT-HTML-TOTAL** -- the HTML statement omits the "Total EXP" line that the
  plain-text statement includes; the HTML golden reflects that omission.
* **F-STMT-CITY** -- a merchant/customer city is rendered truncated (e.g.
  ``New Aricchester`` appears as ``New``); the text golden reflects the truncation.

A third product defect is deliberately NOT exercised here:

* **F-STMT-OVERFLOW** -- ``CBSTM03A`` stores transactions in an ``OCCURS 51`` table
  with no bound check, so an account with more than ~51 transactions overruns it
  and SIGSEGVs. The happy-path fixture holds only 3 transactions, well under the
  bound, so this test never triggers it. Exercising the overflow would require a
  production fix (bounds checking) that is out of scope; it is recorded here so a
  future maintainer does not enlarge the fixture past the limit unaware.

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

import os
import shutil
import subprocess
import tempfile
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

# ---------------------------------------------------------------------------
# Test-only platform-compatibility build (resolves F-STMT-SKIP).
# ---------------------------------------------------------------------------

# The PROGRAM-ID / on-disk name of the compat binary. WHY a distinct name from the
# real ``CBSTM03A`` (Refactoring Rationale + safety): keeping the compat executable
# under its own name means it can NEVER be mistaken for, or overwrite, the
# compile-clean-gate binary the session ``built_programs`` fixture produces from the
# unmodified sources -- the two coexist in ``build_dir`` and the gate binary stays a
# pristine proof that the real pair compiles.
_STATEMENT_COMPAT_MAIN = "CBSTM03A_LINUXCOMPAT"

# The compat-build flags: the production recipe PLUS ``-fsign=EBCDIC``. WHY add
# ``-fsign=EBCDIC`` (mandatory -- Assumption, EMPIRICALLY VERIFIED): the statement
# fixtures encode signed zoned-decimal money fields with EBCDIC sign OVERPUNCH (e.g. a
# trailing ``}``/letter overpunch), and the committed goldens were generated under that
# sign convention. Without ``-fsign=EBCDIC`` GnuCOBOL decodes/emits the sign in the
# ASCII convention, so a negative amount renders differently (``47.88-`` vs the
# overpunched form) and the golden comparison MISMATCHES. It is intentionally NOT part
# of the compile-clean-gate flags above, which must stay the documented *production*
# recipe (the gate only proves the real pair compiles; it never runs it).
_STATEMENT_COMPAT_COMPILE_FLAGS = _STATEMENT_COMPILE_FLAGS + ("-fsign=EBCDIC",)

# The two source markers that bracket the z/OS control-block scan to neutralise. WHY
# marker-matching rather than hard-coded line numbers (Refactoring Rationale +
# robustness): pinning line numbers would silently break if the production source
# shifted by even one line. These two string anchors are stable, unique landmarks --
# the first statement of the scan and the first statement of the real business logic --
# so the patch localises correctly regardless of surrounding edits, and fails loudly
# (see :func:`_neutralize_zos_scan`) if either landmark ever disappears.
_ZOS_SCAN_START_MARKER = "SET ADDRESS OF PSA-BLOCK"
_ZOS_SCAN_END_MARKER = "OPEN OUTPUT STMT-FILE"

# The fixed-width record geometry of the two sequential OUTPUT artifacts, as
# ``ASSIGN_name -> record_width``. WHY these exact widths (Assumption -- from the FDs in
# CBSTM03A.CBL, EMPIRICALLY VERIFIED): ``STMT-FILE`` writes ``FD``-declared 80-byte
# print records and ``HTML-FILE`` writes 100-byte records. GnuCOBOL writes an
# ``ORGANIZATION IS SEQUENTIAL`` file as contiguous fixed records with NO record
# delimiter, so the raw bytes must be re-framed by these widths before comparison (see
# :func:`_frame_fixed_width_records`). 1760 bytes / 80 == 22 STMT records and 9700 / 100
# == 97 HTML records were observed for the happy-path fixture.
_STATEMENT_OUTPUT_WIDTHS = {
    "STMTFILE": 80,
    "HTMLFILE": 100,
}


def _is_truthy(value: "str | None") -> bool:
    """Interpret an environment-variable string as a boolean flag.

    Purpose
    -------
    Provide the same case-insensitive reading of the strict-mode flag that the
    root ``tests/conftest.py`` uses, so this module's local strict gate agrees
    exactly with the suite-wide policy.

    Parameters
    ----------
    value : str | None
        The raw environment value (``os.environ.get(...)`` result), possibly
        ``None`` when the variable is unset.

    Returns
    -------
    bool
        ``True`` iff ``value`` -- lower-cased and stripped -- is one of
        ``{"1", "true", "yes", "on"}``; ``False`` otherwise (including ``None``).

    Raises
    ------
    None
    """
    # WHY duplicate conftest's tiny predicate here rather than import it
    # (Trade-off): ``conftest`` is shared infrastructure that other, out-of-scope
    # checkpoints depend on, and its helpers are private (underscore-prefixed) and
    # not part of a stable import surface. Re-stating this three-line contract
    # locally keeps the fix confined to this in-scope file and cannot perturb the
    # shared module, at the cost of a small, deliberate, documented duplication.
    if value is None:
        return False
    return value.strip().lower() in {"1", "true", "yes", "on"}


def _strict_cobol_required() -> bool:
    """Return whether an absent/unusable COBOL layer must HARD-FAIL, not skip.

    Purpose
    -------
    Centralise this module's read of ``CARDDEMO_REQUIRE_COBOL`` so the required-layer
    policy (conftest's QA finding M1 contract) is evaluated identically here.

    Parameters
    ----------
    None

    Returns
    -------
    bool
        ``True`` when the operator/CI demands the COBOL toolchain be present and
        usable (missing/unusable -> failure); ``False`` for the default
        developer-friendly mode (missing/unusable -> clean skip).

    Raises
    ------
    None
    """
    # WHY the identical env-var name/semantics as conftest (Assumption): a CI run that
    # exports CARDDEMO_REQUIRE_COBOL=1 to force the whole COBOL layer to be required must
    # see the SAME hard-fail behaviour from this module's local gate as from conftest's
    # session gate -- otherwise a required-COBOL run could still go green here by skipping.
    return _is_truthy(os.environ.get("CARDDEMO_REQUIRE_COBOL"))


def _require_or_skip(reason: str) -> "NoReturn":  # type: ignore[valid-type]  # noqa: F821
    """Fail (strict mode) or skip (default) when a required dependency is absent.

    Purpose
    -------
    Implement the conftest M1 contract locally: a genuinely-absent or unusable COBOL
    dependency (no compiler, a failed compat compile, or a compat binary that still
    cannot run) becomes a HARD, report-visible FAILURE when
    ``CARDDEMO_REQUIRE_COBOL`` is set, and a clean, documented skip otherwise. This is
    the honest replacement for the OLD unconditional ``pytest.skip`` (QA finding
    F-STMT-SKIP) that let a required-COBOL CI exit green while never running the feature.

    Parameters
    ----------
    reason : str
        Human-readable explanation of what is missing/unusable; surfaced verbatim in
        the pytest failure/skip message.

    Returns
    -------
    NoReturn
        Never returns normally -- always raises ``Failed`` (strict) or ``Skipped``
        (default) via pytest.

    Raises
    ------
    Failed
        (via :func:`pytest.fail`) when strict mode is active.
    Skipped
        (via :func:`pytest.skip`) when strict mode is inactive.
    """
    if _strict_cobol_required():
        # WHY pytrace=False (Trade-off): the actionable signal is the missing-dependency
        # message, not a traceback into this helper; suppressing the trace keeps the
        # JUnit failure readable while still recording it as a hard, non-green result.
        pytest.fail(
            f"{reason} (required because CARDDEMO_REQUIRE_COBOL is set).",
            pytrace=False,
        )
    pytest.skip(f"{reason}; skipping (set CARDDEMO_REQUIRE_COBOL=1 to require it).")


def _frame_fixed_width_records(data: str, width: int) -> str:
    """Frame a record-sequential output stream into newline-delimited records.

    Purpose
    -------
    Convert the raw bytes ``CBSTM03A`` writes to ``STMTFILE`` / ``HTMLFILE`` into the
    newline-delimited, right-trimmed form the committed goldens use, so
    :func:`tests.helpers.golden_compare.assert_matches_golden` (text mode) compares
    like-for-like. GnuCOBOL writes an ``ORGANIZATION IS SEQUENTIAL`` file as contiguous
    fixed-``width`` records with NO delimiter, so the stream must be sliced by width
    before it can be compared to the line-oriented golden.

    Parameters
    ----------
    data : str
        The raw output content (decoded ``latin-1`` so every byte maps 1:1), either
        contiguous fixed-width records or (defensively) already newline-delimited.
    width : int
        The fixed logical record width in characters used to slice contiguous data
        (80 for ``STMTFILE``, 100 for ``HTMLFILE`` -- see :data:`_STATEMENT_OUTPUT_WIDTHS`).

    Returns
    -------
    str
        The framed text: each record right-stripped of trailing blanks and terminated
        by a single ``"\\n"`` (so the whole string ends in a trailing newline). An empty
        input yields ``""``.

    Raises
    ------
    None
    """
    if not data:
        return ""
    # WHY tolerate BOTH framings (Assumption + Trade-off): the record-sequential case
    # (no delimiter) is what GnuCOBOL emits here and is handled by width-slicing; the
    # newline-delimited branch is a defensive fallback so the helper stays correct if a
    # future runtime (or a shim) ever writes line-delimited output instead. Trying the
    # delimiter first would be WRONG -- a stray 0x0A *inside* a fixed record would be
    # mis-split -- so width-slicing is preferred whenever a delimiter is absent.
    if "\n" in data:
        records = data.split("\n")
        # Drop exactly one trailing empty element from a final terminator so a stream
        # ending in "\n" does not yield a spurious blank record.
        if records and records[-1] == "":
            records.pop()
    else:
        records = [data[i:i + width] for i in range(0, len(data), width)]
    # WHY rstrip each record then re-join with "\n" (Assumption): the goldens store each
    # print/HTML line right-trimmed and newline-terminated (fixed-width blank padding is
    # not meaningful output), so trimming here makes the produced bytes directly
    # comparable to the committed baseline without widening the comparator's tolerance.
    return "".join(record.rstrip() + "\n" for record in records)


def _neutralize_zos_scan(source_text: str) -> str:
    """Return ``CBSTM03A`` source with ONLY its z/OS control-block scan removed.

    Purpose
    -------
    Produce the text of the test-only compat variant: identical to the production
    ``CBSTM03A.CBL`` except that the block of statements between the
    ``SET ADDRESS OF PSA-BLOCK`` marker and the ``OPEN OUTPUT STMT-FILE`` marker (the
    uninitialized-POINTER PSA/TCB/TIOT scan that SIGSEGVs off-mainframe) is replaced by
    a single ``CONTINUE.``. Every other byte -- data division, file logic, HTML/text
    formatting -- is preserved verbatim, so the compiled binary computes the exact same
    statement bytes the real program would, minus only the crashing diagnostic DISPLAY.

    Parameters
    ----------
    source_text : str
        The full text of the production ``CBSTM03A.CBL`` (read, never written).

    Returns
    -------
    str
        The patched source text for the compat compile.

    Raises
    ------
    ValueError
        If either marker is not found, or they appear out of order -- a signal that the
        production source changed shape and the patch can no longer be localised safely
        (fail loudly rather than emit a subtly-wrong binary).
    """
    lines = source_text.splitlines(keepends=True)
    start_idx = end_idx = None
    for idx, line in enumerate(lines):
        if start_idx is None and _ZOS_SCAN_START_MARKER in line:
            start_idx = idx
        # Only accept the END marker at or after the START, so an unrelated earlier
        # mention could never mis-bracket the region.
        if start_idx is not None and _ZOS_SCAN_END_MARKER in line:
            end_idx = idx
            break
    # WHY fail loudly on a missing or mis-ordered marker (Trade-off): silently returning
    # the source unpatched would recreate the SIGSEGV; silently guessing an offset could
    # delete real logic. A ValueError surfaces the exact cause so a maintainer updates
    # the markers deliberately after a production-source change.
    if start_idx is None or end_idx is None or end_idx <= start_idx:
        raise ValueError(
            "could not locate the z/OS control-block scan in CBSTM03A.CBL "
            f"(start marker {_ZOS_SCAN_START_MARKER!r} at {start_idx}, "
            f"end marker {_ZOS_SCAN_END_MARKER!r} at {end_idx}); the production "
            "source shape changed -- update the markers before rebuilding the compat binary."
        )
    # Replace [start_idx, end_idx) with one Area-B ``CONTINUE.``. The OPEN statement at
    # end_idx (the start of business logic) is preserved as the next line.
    # WHY CONTINUE. (Assumption): the scan sits in CBSTM03A's implicit first paragraph
    # (before ``0000-START.``); a no-op ``CONTINUE.`` is the minimal valid statement that
    # keeps that paragraph non-empty and control flow intact after the DISPLAY block is
    # excised.
    patched = lines[:start_idx] + ["           CONTINUE.\n"] + lines[end_idx:]
    return "".join(patched)


def _ensure_statement_compat_program(build_dir: Path, repo_root: Path) -> "Path | None":
    """Build (once) the test-only ``CBSTM03A_LINUXCOMPAT`` binary; return its path.

    Purpose
    -------
    Guarantee that ``build_dir/CBSTM03A_LINUXCOMPAT`` -- the statically-linked
    ``CBSTM03A`` (z/OS scan neutralised) + unmodified ``CBSTM03B`` executable -- exists
    so the statement tests can genuinely RUN the program on this runner and assert its
    output against the goldens (resolving F-STMT-SKIP). Reuse-vs-rebuild is decided by
    existence, so the parametrized/​sibling tests pay the compat compile at most once.

    The production ``CBSTM03A.CBL`` is READ ONLY: it is patched in memory, written to a
    private scratch ``.CBL`` inside ``build_dir``, compiled, and only the finished binary
    is atomically published -- the on-disk production source is never modified and no
    stray ``.CBL`` is left behind (the same pattern the CSUTLDTC date test uses).

    Parameters
    ----------
    build_dir : pathlib.Path
        Directory that holds (or will hold) the compat binary -- the same directory
        ``cobol_runner`` resolves programs from, so the binary this returns is exactly
        the one a subsequent ``run("CBSTM03A_LINUXCOMPAT")`` executes.
    repo_root : pathlib.Path
        Repository root, used to locate the copybook include path (``app/cpy``) and the
        two uppercase ``.CBL`` sources (``app/cbl``).

    Returns
    -------
    pathlib.Path | None
        Path to the ensured ``CBSTM03A_LINUXCOMPAT`` executable, or ``None`` if the
        environment cannot build it (no compiler / compat compile failed). A ``None``
        return is the caller's cue to route through :func:`_require_or_skip`.

    Raises
    ------
    ValueError
        Propagated from :func:`_neutralize_zos_scan` if the scan markers cannot be
        located (a genuine, must-surface production-source-shape change).
    """
    binary = Path(build_dir) / _STATEMENT_COMPAT_MAIN
    # WHY existence-first (Trade-off, idempotence): cheap reuse keeps the compat compile
    # to at most once per session and lets both statement tests share the artifact.
    if binary.exists():
        return binary

    # No compiler -> the environment genuinely cannot build the compat binary. Return
    # None so the caller applies the strict gate (skip default / fail under strict),
    # rather than deciding the policy here.
    if shutil.which("cobc") is None:
        return None

    source = Path(repo_root) / "app" / "cbl" / _STATEMENT_SOURCES[0]
    subprogram = Path(repo_root) / "app" / "cbl" / _STATEMENT_SOURCES[1]
    cpy_dir = Path(repo_root) / "app" / "cpy"
    if not source.is_file() or not subprogram.is_file():
        # A partial checkout missing the sources is an environment/data condition, not a
        # code defect; signal "cannot build" and let the strict gate decide.
        return None

    binary.parent.mkdir(parents=True, exist_ok=True)
    patched_text = _neutralize_zos_scan(source.read_text(encoding="latin-1"))

    # WHY compile in a private scratch dir INSIDE build_dir, then atomically publish
    # (Refactoring Rationale + parallel safety, mirroring the CSUTLDTC date test): doing
    # the patch-write and compile in a throwaway directory and ``os.replace``-ing only the
    # finished binary (a) leaves no stray ``.CBL`` in build_dir, and (b) makes publication
    # atomic and same-filesystem, so a concurrent xdist worker can never observe or race a
    # half-built binary. The scratch dir lives inside build_dir so the rename is same-device.
    scratch = Path(tempfile.mkdtemp(prefix=".stmt_compat_", dir=str(build_dir)))
    try:
        patched_src = scratch / f"{_STATEMENT_COMPAT_MAIN}.CBL"
        patched_src.write_text(patched_text, encoding="latin-1")
        tmp_binary = scratch / _STATEMENT_COMPAT_MAIN
        command = [
            "cobc",
            *_STATEMENT_COMPAT_COMPILE_FLAGS,
            "-I", str(cpy_dir),
            "-o", str(tmp_binary),
            str(patched_src),
            str(subprogram),
        ]
        completed = subprocess.run(
            command,
            cwd=str(repo_root),
            capture_output=True,
            text=True,
            timeout=_COMPILE_TIMEOUT_S,
        )
        # WHY treat a missing binary as "cannot build" -> None (Trade-off): a compat
        # compile failure is not a production regression (the REAL pair's compile is
        # gated separately in _ensure_statement_program); it is an environment/toolchain
        # limitation on running the program here, so the strict gate is the right policy.
        if not tmp_binary.exists():
            return None
        os.replace(str(tmp_binary), str(binary))
    finally:
        shutil.rmtree(scratch, ignore_errors=True)
    return binary


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
    Robustly detect a residual segmentation fault across GnuCOBOL releases and
    across the two ways a crash can surface: a signalled process exit, or a
    libcob-caught fault that prints a diagnostic and exits with a conventional
    code. WHY this is now a DEFENSIVE guard rather than the primary gate
    (Refactoring Rationale): the compat binary neutralises the known z/OS PSA/TCB/
    TIOT scan, so on this runner the statement program no longer SIGSEGVs and this
    predicate returns ``False`` (the run proceeds to the golden assertion). It is
    retained only so that an *unrelated* environment-specific crash would still be
    routed honestly through :func:`_require_or_skip` instead of surfacing as a
    confusing assertion error on empty output.

    Parameters
    ----------
    result : tests.helpers.cobol_runner.RunResult
        The captured outcome of a run -- only its ``returncode`` (int) and
        ``stderr`` (str) attributes are consulted.

    Returns
    -------
    bool
        ``True`` if the run's return code or stderr indicates a SIGSEGV;
        ``False`` otherwise (i.e. the program actually ran and the assertion path
        should proceed).

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
    """Golden-master test of the plain-text + HTML statements (genuinely run).

    Purpose
    -------
    Exercise the full statement happy path end-to-end and ASSERT it, resolving QA
    finding F-STMT-SKIP (the feature must be genuinely verified, not skipped):

    1. Assert the compile-clean gate on the REAL, unmodified ``CBSTM03A`` +
       ``CBSTM03B`` pair (:func:`_ensure_statement_program`) -- a production compile
       regression is still a hard failure.
    2. Build the test-only ``CBSTM03A_LINUXCOMPAT`` binary
       (:func:`_ensure_statement_compat_program`), in which ONLY the off-mainframe
       z/OS control-block scan is neutralised (production source unmodified).
    3. Stage the four indexed inputs from ``tests/fixtures/statement/happy_path``,
       run the compat binary, frame its ``STMTFILE`` / ``HTMLFILE`` outputs into
       fixed-width records, and assert they match the committed goldens under
       ``tests/golden/statement/happy_path``.

    On this runner all three steps run for real and the golden comparison PASSES.
    Only a genuinely-degraded environment (no compiler / failed compat compile /
    a compat binary that still cannot run) routes through the local strict gate
    (:func:`_require_or_skip`): a documented skip by default, a HARD failure under
    ``CARDDEMO_REQUIRE_COBOL``.

    Parameters
    ----------
    cobol_runner : tests.helpers.cobol_runner.CobolRunner
        Per-test runner (from the ``conftest`` fixture) bound to the build dir and
        an isolated workspace.
    build_dir : pathlib.Path
        Session build directory; passed to the ensure-functions so the binaries they
        guarantee are the very ones ``cobol_runner`` will execute.
    repo_root : pathlib.Path
        Repository root, used to locate the sources, fixtures, and goldens.

    Returns
    -------
    None

    Raises
    ------
    Skipped
        (via :func:`_require_or_skip` / :func:`_resolve_fixture`) in default mode
        when the compat binary cannot be built or run, or a fixture is missing.
    Failed
        (via :func:`_require_or_skip`) under ``CARDDEMO_REQUIRE_COBOL`` when the
        compat binary cannot be built or run.
    AssertionError
        On a compile-clean gate failure, a non-zero run, or when a produced
        statement does not match its golden.
    """
    # Step 1: compile-clean GATE on the REAL, unmodified pair -- preserved invariant.
    _ensure_statement_program(build_dir, repo_root)

    # Step 2: build the test-only compat binary. A None return means the environment
    # genuinely cannot build it, which the strict gate turns into a skip (default) or a
    # hard failure (strict) -- never a silent green.
    compat_binary = _ensure_statement_compat_program(build_dir, repo_root)
    if compat_binary is None:
        _require_or_skip(
            "cannot build the CBSTM03A_LINUXCOMPAT statement binary (no usable cobc or "
            "the compat compile failed); the statement feature cannot be exercised"
        )

    # Step 3: stage the four indexed inputs for the happy-path scenario, then run.
    scenario_dir = Path(repo_root) / "tests" / "fixtures" / "statement" / "happy_path"
    _load_scenario_inputs(cobol_runner, scenario_dir)

    # WHY check=False (Trade-off): we inspect the return code ourselves below (and,
    # defensively, guard against a residual SIGSEGV) rather than letting the runner raise
    # on non-zero, so a genuine-degradation case yields the documented strict-gate outcome
    # rather than an opaque CalledProcessError.
    result = cobol_runner.run(_STATEMENT_COMPAT_MAIN, check=False)

    # Defensive residual-crash guard. WHY keep this even though the scan is neutralised
    # (Assumption): if some *other* environment-specific fault ever surfaced as a SIGSEGV
    # in the compat run, the honest outcome is the strict gate (skip/fail), never a
    # confusing assertion error on empty output. On this runner it is not triggered.
    if _is_sigsegv(result):
        _require_or_skip(
            "CBSTM03A_LINUXCOMPAT still crashed at runtime after neutralising the z/OS "
            "scan; the statement program cannot execute in this environment"
        )

    # The neutralised binary must run cleanly on the happy path (RC=0 per the rubric).
    assert result.returncode == 0, (
        f"{_STATEMENT_COMPAT_MAIN} exited {result.returncode} on the happy-path run "
        f"(expected 0). stderr tail:\n{(result.stderr or '')[-2000:]}"
    )

    # --- Golden-master comparison (runs for real on this runner) --------------------
    # WHY golden-master over field-by-field assertions (Trade-off): a statement is a
    # large, formatted document (headers, address block, per-transaction lines, HTML
    # scaffolding); a single byte-deterministic diff against a committed baseline gives
    # complete coverage of layout AND content, whereas hand-picked field assertions
    # would be verbose and would silently miss formatting regressions between the fields.
    #
    # WHY frame the raw output first (Assumption -- see _frame_fixed_width_records): the
    # program writes record-sequential fixed-width output with NO newline delimiter, so
    # the raw bytes are one undelimited run; framing by the FD width (80 for STMTFILE,
    # 100 for HTMLFILE) and right-trimming reproduces the newline-delimited form the
    # goldens store, so ``assert_matches_golden`` (text mode -- which also scrubs any
    # ISO-8601 timestamp) compares like-for-like.
    #
    # NOTE the goldens intentionally encode two documented, out-of-scope PRODUCT defects
    # (see the module docstring): F-STMT-HTML-TOTAL (HTML omits the "Total EXP" line) and
    # F-STMT-CITY (a city is rendered truncated). Matching the goldens is the honest
    # characterisation of current behaviour; it is not an endorsement of those defects.
    golden_dir = Path(repo_root) / "tests" / "golden" / "statement" / "happy_path"
    for assign_name, golden_name in _STATEMENT_OUTPUTS:
        produced = result.read_output(assign_name)
        framed = _frame_fixed_width_records(produced, _STATEMENT_OUTPUT_WIDTHS[assign_name])
        assert_matches_golden(framed, golden_dir / golden_name)


def test_statement_empty_input(cobol_runner, build_dir, repo_root) -> None:
    """Empty-transaction edge case: characterise ``CBSTM03A``'s fail-fast abend.

    Purpose
    -------
    Exercise and PIN the empty-input edge case (resolving QA finding
    F-STMT-EMPTY-ABEND): an empty ``TRNXFILE`` (zero transactions) with an otherwise
    valid master chain. The genuinely-run compat binary reads the empty transaction
    index, hits its "no records" condition, and takes the program's documented
    **fail-fast abend** path: it DISPLAYs ``ERROR READING TRNXFILE`` / ``RETURN CODE:
    10`` / ``ABENDING PROGRAM`` and exits non-zero (it does NOT complete a statement).
    This test asserts that exact, observable behaviour rather than pretending an empty
    run succeeds.

    WHY assert an abend, not a graceful zero-transaction statement (Refactoring
    Rationale -- corrects the prior assertion): the previous version asserted
    ``RETURN-CODE in (0, 4)`` and that both output files exist, which is FALSE for this
    program -- an empty transaction file drives ``CBSTM03A`` into its abend routine
    (exit 1, empty output files). Encoding the specification means encoding what the
    program actually does on empty input, which is to abend deterministically. (The
    abend itself, via LE ``CEE3ABD``, is the production program's chosen contract and
    is out of scope to change; we characterise it, we do not "fix" it.)

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
        (via :func:`_require_or_skip` / :func:`_resolve_fixture`) in default mode when
        the compat binary cannot be built/run, or a fixture is missing.
    Failed
        (via :func:`_require_or_skip`) under ``CARDDEMO_REQUIRE_COBOL`` when the compat
        binary cannot be built or run.
    AssertionError
        On a compile-clean gate failure, or if the empty-input run does NOT exhibit the
        documented fail-fast abend (non-zero exit with the expected diagnostics).
    """
    # Compile-clean GATE on the REAL, unmodified pair (shared invariant with happy path).
    _ensure_statement_program(build_dir, repo_root)

    # Build the test-only compat binary; strict-gate if the environment cannot.
    compat_binary = _ensure_statement_compat_program(build_dir, repo_root)
    if compat_binary is None:
        _require_or_skip(
            "cannot build the CBSTM03A_LINUXCOMPAT statement binary (no usable cobc or "
            "the compat compile failed); the empty-input edge case cannot be exercised"
        )

    # Stage the empty-input scenario: TRNXFILE is a zero-byte fixture (an empty index),
    # the three master files carry one linked record each.
    scenario_dir = Path(repo_root) / "tests" / "fixtures" / "statement" / "empty_input"
    _load_scenario_inputs(cobol_runner, scenario_dir)

    # WHY check=False (Assumption): the abend is the EXPECTED outcome here, so a non-zero
    # exit must not raise -- it is exactly what this test asserts on.
    result = cobol_runner.run(_STATEMENT_COMPAT_MAIN, check=False)

    # Defensive residual-crash guard (see happy-path test): a SIGSEGV would be an
    # environment fault, not the business abend, so route it through the strict gate.
    if _is_sigsegv(result):
        _require_or_skip(
            "CBSTM03A_LINUXCOMPAT crashed with SIGSEGV on empty input rather than taking "
            "its business abend path; the program cannot execute in this environment"
        )

    # --- Characterise the fail-fast abend (the documented empty-input contract) -----
    # WHY assert a NON-zero exit plus the DISPLAY markers rather than a specific numeric
    # code (Assumptions -- empirically verified; Trade-off): the program DISPLAYs its
    # diagnostics and then CALLs LE ``CEE3ABD``; the exact process exit code depends on
    # whether an LE ``CEE3ABD`` module is resolvable at run time (absent on this runner,
    # so libcob reports the abend and the process exits 1). The stable, environment-
    # independent contract is therefore "exited non-zero AND printed its abend
    # diagnostics" -- the DISPLAYs are emitted BEFORE the CALL, so they are present
    # regardless of how the abend itself is realised. Pinning a single exit integer would
    # make the test brittle to the presence/absence of the LE runtime.
    assert result.returncode != 0, (
        f"{_STATEMENT_COMPAT_MAIN} unexpectedly exited 0 on empty input; the documented "
        "behaviour is a fail-fast abend (non-zero exit). "
        f"stdout tail:\n{(result.stdout or '')[-2000:]}"
    )
    stdout = result.stdout or ""
    # WHY these two exact markers (Assumption): ``ERROR READING TRNXFILE`` identifies the
    # empty-transaction condition and ``ABENDING PROGRAM`` identifies the fail-fast path;
    # together they prove the program reached its documented abend for the RIGHT reason
    # (an empty transaction file), not some unrelated failure.
    for marker in ("ERROR READING TRNXFILE", "ABENDING PROGRAM"):
        assert marker in stdout, (
            f"expected the empty-input abend diagnostic {marker!r} in "
            f"{_STATEMENT_COMPAT_MAIN} stdout, but it was absent. "
            f"stdout tail:\n{stdout[-2000:]}"
        )

