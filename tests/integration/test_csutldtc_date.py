"""Layer-2 integration test for the CardDemo COBOL date utility ``CSUTLDTC``.

Purpose
-------
Drive the *real, unmodified* ``CSUTLDTC`` subprogram (``app/cbl/CSUTLDTC.cbl``)
end-to-end and assert its two observable outputs -- the Language-Environment
*severity* it propagates to ``RETURN-CODE`` and the result text it writes into
its ``LS-RESULT`` linkage field -- for valid, leap-year, century-boundary,
invalid, and malformed dates. This is the automated formalisation of the
CardDemo "Test date utility" work item (AAP Sections 0.5.1 / 0.5.2). The
production source is REFERENCE ONLY and is never modified by this test.

Layer
-----
Layer-2 **integration**: a single COBOL program exercised through a compiled
driver (no data files involved). The module is marked ``integration`` at module
scope so the suite runner selects it via ``-m integration``.

Binding contract
----------------
Executed by the integration runner as::

    pytest tests/integration -m integration --junitxml=reports/integration.xml

Why two embedded COBOL artifacts are compiled on demand
-------------------------------------------------------
``CSUTLDTC`` cannot be exercised as-is on the GnuCOBOL runner for two reasons,
each solved by a small COBOL artifact this module compiles at run time. This
keeps the ``tests/integration`` directory to Python files only -- there is
deliberately no ``__init__.py`` and no checked-in ``.cbl``:

1. ``CSUTLDTC`` issues ``CALL 'CEEDAYS'`` -- an IBM Language-Environment date
   service that is **absent** from GnuCOBOL (not in libcob, not an intrinsic,
   not on disk); at run time it would fail with
   ``libcob: error: module 'CEEDAYS' not found``. We therefore build a tiny
   ``CEEDAYS.so`` *shim* that reproduces the exact LE linkage ``CSUTLDTC``
   expects and computes validity with GnuCOBOL's ``FUNCTION
   TEST-DATE-YYYYMMDD`` intrinsic. AAP Sections 0.3.1 / 0.10.2 explicitly
   endorse stubbing an unavailable external dependency for test purposes.
   (Alternatives Considered: a Java-based zUnit stub is impossible -- no JVM is
   installed on the runner; a real LE ``CEEDAYS`` is simply unavailable.)
2. ``CSUTLDTC`` is a ``PROCEDURE DIVISION USING`` *subprogram*, which the
   suite's ``cobol_runner`` (a main-program launcher) cannot invoke directly.
   We build a small ``DRVDATE`` *driver main* that reads the date/format from
   environment variables, ``CALL``s ``CSUTLDTC``, and ``DISPLAY``s the severity
   and result on stdout; it is **static-linked** with the real ``CSUTLDTC`` so
   the genuine unit under test is exercised.

Toolchain note (empirical)
--------------------------
All behaviour below was validated on this runner's GnuCOBOL 3.2.0 (gcc
backend). The severity/result truth table matches the one documented for the
3.1.x line. The one empirically-forced difference on 3.2.0 is that the driver's
``RESULT=[...]`` output spans two physical lines (CSUTLDTC copies a 2-byte
binary length prefix, value 10 == ``0x000A``, into its display field, embedding
a NUL + newline), so the result is parsed with ``re.DOTALL`` (see ``_RESULT_RE``).

Fidelity scope of the CEEDAYS shim (what this test DOES and does NOT prove)
--------------------------------------------------------------------------
This is an explicit statement of the shim's verification boundary (QA finding
CSUTLDTC-1). What is asserted here is CSUTLDTC's *calling contract* against a
faithful reproduction of the LE ``CEEDAYS`` **linkage** -- i.e. that CSUTLDTC
builds the two LE varying-string parameters correctly, invokes the date service,
reads back the feedback token, maps its severity halfword through its
``EVALUATE`` to the right result text, and propagates that severity to
``RETURN-CODE``. What is deliberately NOT asserted is bit-for-bit numerical
fidelity of IBM's real ``CEEDAYS`` (most visibly the ``S9(9)`` Lillian day count,
which CardDemo never consumes -- see the parser WHY note). The shim decides
validity with GnuCOBOL's ``FUNCTION TEST-DATE-YYYYMMDD`` intrinsic, whose
valid/invalid verdict matches LE for every date in the truth table above; it is
a validity oracle for the contract, not a re-implementation of LE internals.
WHY this boundary is correct and sufficient (Trade-off): CardDemo's callers
depend only on the severity + result-text contract, so pinning the Lillian value
or LE's internal feedback layout would test the shim rather than CSUTLDTC and
would couple the suite to LE implementation details it never uses. This scope is
an intentional, disclosed design choice -- not a coverage gap.

Explainability
--------------
Per the project's mandatory Explainability rule (AAP Section 0.10.1) every
function below carries a Purpose / Parameters / Returns / Raises docstring, and
each non-obvious decision is annotated with a WHY comment documenting at least
one of Alternatives Considered, Refactoring Rationale, Assumptions, or
Trade-offs.
"""

# ``from __future__ import annotations`` keeps annotations lazy strings so the
# ``tuple[int, str, int]`` return hint below is valid on the AAP's Python 3.9
# floor as well as the 3.13 interpreter on this runner.
# WHY (Trade-off): matches the convention already used by tests/conftest.py and
# tests/helpers/cobol_runner.py, keeping the suite's annotation style uniform.
from __future__ import annotations

import os
import re
import shutil
import subprocess
import tempfile
from pathlib import Path

import pytest

# Every test in this module is an integration-layer test.
# WHY (Assumption): the integration runner selects tests with ``-m integration``
# (the binding contract in the module docstring); declaring the marker at module
# scope guarantees the whole file is collected under that selection rather than
# being silently deselected.
pytestmark = pytest.mark.integration


# ---------------------------------------------------------------------------
# Embedded COBOL artifacts (compiled on demand). Reproduced verbatim as the
# empirically-verified sources that compile cleanly and run correctly on this
# runner's GnuCOBOL 3.2.0.
# ---------------------------------------------------------------------------

# The CEEDAYS shim: a minimal stand-in for the absent LE date service. Its
# LINKAGE mirrors EXACTLY how CSUTLDTC calls CEEDAYS -- two LE varying strings
# (a halfword ``S9(4) BINARY`` length + ``X(10)`` text), an ``S9(9) BINARY``
# Lillian output, and a 12-byte feedback token whose first halfword is SEVERITY.
# A valid date yields an all-zero (LOW-VALUES) feedback token, which CSUTLDTC's
# 88-level ``FC-INVALID-DATE`` recognises as "valid"; an invalid date sets the
# severity halfword to 0x000C (== 12) so CSUTLDTC falls into its WHEN OTHER
# ("Date is invalid") branch.
# WHY free format here (Assumption): the shim is compiled with ``-free`` so the
# fixed-column rules do not apply to it; the leading indentation is cosmetic.
CEEDAYS_SHIM_SRC = """\
       IDENTIFICATION DIVISION.
       PROGRAM-ID. CEEDAYS.
       DATA DIVISION.
       WORKING-STORAGE SECTION.
       01 WS-YYYYMMDD    PIC 9(8).
       01 WS-RC          PIC S9(9) BINARY.
       LINKAGE SECTION.
       01 LK-DATE.
          05 LK-DATE-LEN   PIC S9(4) BINARY.
          05 LK-DATE-TEXT  PIC X(10).
       01 LK-FMT.
          05 LK-FMT-LEN    PIC S9(4) BINARY.
          05 LK-FMT-TEXT   PIC X(10).
       01 LK-LILLIAN     PIC S9(9) BINARY.
       01 LK-FEEDBACK    PIC X(12).
       PROCEDURE DIVISION USING LK-DATE LK-FMT LK-LILLIAN LK-FEEDBACK.
           COMPUTE WS-YYYYMMDD =
               (FUNCTION NUMVAL(LK-DATE-TEXT(1:4)) * 10000)
             + (FUNCTION NUMVAL(LK-DATE-TEXT(6:2)) * 100)
             + (FUNCTION NUMVAL(LK-DATE-TEXT(9:2)))
           MOVE FUNCTION TEST-DATE-YYYYMMDD(WS-YYYYMMDD) TO WS-RC
           IF WS-RC = 0
              MOVE LOW-VALUES TO LK-FEEDBACK
           ELSE
              MOVE X'000C000059C3C5C5' TO LK-FEEDBACK(1:8)
           END-IF
           GOBACK.
"""

# The DRVDATE driver: a fixed-format main that reads TESTDATE/TESTFMT from the
# environment, calls the real CSUTLDTC, and prints the severity (RETURN-CODE)
# and the 80-byte result on stdout in a machine-parsable form.
# WHY fixed format + the exact 7/11-column indentation (Assumption): DRVDATE is
# compiled with ``-fixed`` and ``--std=ibm-strict`` (the repo's production
# convention), so division headers / 01-levels sit in Area A (column 8) and
# statements in Area B (column 12); the indentation below is significant.
DRVDATE_SRC = """\
       IDENTIFICATION DIVISION.
       PROGRAM-ID. DRVDATE.
       DATA DIVISION.
       WORKING-STORAGE SECTION.
       01 WS-DATE     PIC X(10).
       01 WS-FMT      PIC X(10).
       01 WS-RESULT   PIC X(80).
       PROCEDURE DIVISION.
           ACCEPT WS-DATE FROM ENVIRONMENT 'TESTDATE'
           ACCEPT WS-FMT  FROM ENVIRONMENT 'TESTFMT'
           CALL 'CSUTLDTC' USING WS-DATE WS-FMT WS-RESULT
           DISPLAY 'SEV=[' RETURN-CODE ']'
           DISPLAY 'RESULT=[' WS-RESULT ']'
           GOBACK.
"""


# ---------------------------------------------------------------------------
# Output parsers for the DRVDATE stdout contract.
# ---------------------------------------------------------------------------
# WHY assert on the severity + result TEXT rather than the LILLIAN day count
# (Trade-off): CEEDAYS also returns an ``S9(9)`` Lillian-day integer, but the
# outputs CardDemo actually consumes are (1) the severity CSUTLDTC forwards to
# ``RETURN-CODE`` -- which the suite's condition-code rubric keys off -- and
# (2) the human-readable result text its EVALUATE maps ('Date is valid' /
# 'Date is invalid'). The driver therefore surfaces only those two, and this
# test asserts on the exact contract production callers depend on; the Lillian
# value is an internal LE detail CardDemo never reads, so pinning it would test
# the shim rather than CSUTLDTC.

# Severity marker: ``SEV=[+000000012]``. The sign and zero padding come from
# COBOL's DISPLAY of the signed ``RETURN-CODE`` special register.
_SEV_RE = re.compile(r"SEV=\[\s*([+-]?\d+)\s*\]")

# Result marker: ``RESULT=[<80 bytes of WS-MESSAGE>]``.
# WHY re.DOTALL (empirical Trade-off): on GnuCOBOL 3.2.0 CSUTLDTC's WS-MESSAGE
# embeds a NUL + newline (the 2-byte binary length prefix, value 10 == 0x000A,
# that ``MOVE WS-DATE-TO-TEST TO WS-DATE`` copies into the display field), so the
# ``RESULT=[...]`` text straddles two physical lines and its closing ``]`` is on
# the second line. Without DOTALL ``.`` would stop at the first newline and the
# pattern would never match. DOTALL is a strict superset of the single-line
# behaviour, so it is also correct on toolchains (e.g. 3.1.x) that keep it on one
# line. The result *text* CSUTLDTC maps ('Date is valid' / 'Date is invalid')
# precedes the embedded newline, so the substring assertions below are unaffected.
_RESULT_RE = re.compile(r"RESULT=\[(.*)\]", re.DOTALL)


# ---------------------------------------------------------------------------
# Empirical truth table (encoded EXACTLY, verified on this runner).
# ---------------------------------------------------------------------------

# Dates CSUTLDTC must ACCEPT (severity 0, 'Date is valid').
# WHY this exact trio (boundary coverage): an ordinary in-month date, a 4-year
# leap day, and a 400-year century leap day together exercise the ordinary path
# plus both leap-year rules the utility must honour.
_VALID_DATES = ("2023-06-15", "2024-02-29", "2000-02-29")

# Dates CSUTLDTC must REJECT (severity 12, 'Date is invalid').
# WHY this exact set (error-path coverage): a non-leap Feb 29, a century
# NON-leap Feb 29 (divisible by 100 but not 400), an out-of-range month, an
# out-of-range day, and a wholly non-numeric string exercise every route into
# CSUTLDTC's WHEN OTHER branch and confirm severity propagation.
#
# WHY the trailing empty-string and whitespace-only entries (Refactoring
# Rationale -- closes QA finding CSUTLDTC-2): the previously-committed set proved
# rejection of malformed *content* but never exercised the degenerate inputs a
# real caller can pass -- a completely empty date field and an all-blank one
# (e.g. an online screen submitted with the date left blank, which arrives as
# spaces in the fixed X(10) field). Adding them makes the adversarial matrix
# explicit rather than implied.
#   * ""            -- an empty TESTDATE; the DRVDATE ``ACCEPT FROM ENVIRONMENT``
#                      leaves its X(10) field as spaces, so this and the blank
#                      string below both reach CSUTLDTC as an all-space date.
#   * "          "  -- ten explicit blanks (the full mask width).
# WHY both must yield severity 12 (Assumption -- EMPIRICALLY VERIFIED on this
# runner's GnuCOBOL 3.2.0 via the real DRVDATE->CSUTLDTC->CEEDAYS-shim chain):
# the shim computes YYYYMMDD via ``FUNCTION NUMVAL`` over the (blank) text, which
# yields 0; ``FUNCTION TEST-DATE-YYYYMMDD(0)`` is non-zero (0 is not a valid
# Gregorian date), so the shim returns the 0x000C feedback and CSUTLDTC maps it
# to severity 12 / 'Date is invalid' -- exactly the same rejection route the
# other invalid strings take, with NO abort or crash. Both were confirmed to
# return driver exit code 12 before being committed here.
# WHY NOT assert a distinct "blank date" message (Trade-off): CSUTLDTC has a
# single 'Date is invalid' rejection text (its EVALUATE has no dedicated
# empty-input arm), so the honest contract to pin is severity 12 + that text --
# inventing a finer assertion would test a message CSUTLDTC does not emit.
_INVALID_DATES = (
    "2023-02-29",
    "1900-02-29",
    "2023-13-01",
    "2023-02-30",
    "ABCD-EF-GH",
    "",
    "          ",
)

# The date mask CardDemo's callers use.
# WHY assume ``YYYY-MM-DD`` (Assumption): the CardDemo online callers of CSUTLDTC
# (e.g. COTRN02C / CORPT00C) pass exactly this mask, so the utility is validated
# under the format it is actually used with in production.
_DATE_MASK = "YYYY-MM-DD"


def _ensure_date_programs(build_dir: Path, repo_root: Path) -> None:
    """Compile the CEEDAYS shim and DRVDATE driver into ``build_dir`` on demand.

    Purpose
    -------
    Guarantee that ``build_dir/CEEDAYS.so`` (the LE ``CEEDAYS`` shim) and
    ``build_dir/DRVDATE`` (the driver main static-linked with the real
    ``CSUTLDTC``) exist before a test runs the driver. If both already exist the
    function returns immediately, so the parametrized tests pay the compile cost
    at most once per session.

    Parameters
    ----------
    build_dir : pathlib.Path
        Directory the compiled artifacts are placed in. This is the same
        directory ``cobol_runner`` puts on ``COB_LIBRARY_PATH``, so the
        dynamically-CALL'd ``CEEDAYS.so`` shim resolves at run time.
    repo_root : pathlib.Path
        Repository root, used to locate the unit under test
        (``app/cbl/CSUTLDTC.cbl``) and the copybook include path (``app/cpy``).

    Returns
    -------
    None

    Raises
    ------
    Skipped
        Via ``pytest.skip`` when the environment cannot build the artifacts --
        ``cobc`` is not on ``PATH``, the ``CSUTLDTC`` source is missing, or a
        compile fails. WHY skip rather than fail: a toolchain gap is an
        environment capability condition, not a defect in CSUTLDTC or this test,
        and mirrors the ``built_programs`` policy in ``tests/conftest.py``.
    """
    ceedays_so = build_dir / "CEEDAYS.so"
    drvdate = build_dir / "DRVDATE"
    # WHY the existence guard (Trade-off, idempotence): a cheap check makes this
    # near-free to call from every parametrized case -- the first case compiles,
    # the rest short-circuit -- and, paired with the atomic publish below, keeps
    # concurrent pytest-xdist workers from doing redundant or racing work.
    if ceedays_so.exists() and drvdate.exists():
        return

    cobc = shutil.which("cobc")
    if cobc is None:
        pytest.skip("GnuCOBOL 'cobc' not on PATH; cannot build the CSUTLDTC date driver")

    csutldtc_src = repo_root / "app" / "cbl" / "CSUTLDTC.cbl"
    copybook_dir = repo_root / "app" / "cpy"
    if not csutldtc_src.is_file():
        # WHY (Assumption): during a partial checkout the unit under test may be
        # absent; treat that like a missing toolchain (skip) rather than erroring
        # so collection and the rest of the suite stay green.
        pytest.skip(f"unit under test not found: {csutldtc_src}; skipping date-utility test")

    build_dir.mkdir(parents=True, exist_ok=True)

    # WHY compile in a private temp dir INSIDE build_dir, then atomically publish
    # (Refactoring Rationale + parallel safety): doing all scratch work (writing
    # the two ``.cbl`` sources and compiling) in a throwaway directory and then
    # ``os.replace``-ing only the two finished artifacts into ``build_dir``
    # (a) leaves no stray ``.cbl`` in ``build_dir`` (honouring the "Python files
    # only" rule), and (b) makes publication atomic and same-filesystem, so a
    # concurrent xdist worker can never observe or write a half-built artifact.
    # WHY the temp dir lives inside build_dir (Assumption): ``os.replace`` is only
    # atomic within a single filesystem; co-locating the scratch dir guarantees a
    # same-device rename and avoids a cross-device (EXDEV) failure that a system
    # ``/tmp`` on a different mount could otherwise cause.
    scratch = Path(tempfile.mkdtemp(prefix=".date_build_", dir=str(build_dir)))
    try:
        ceedays_src = scratch / "CEEDAYS.cbl"
        drvdate_src = scratch / "DRVDATE.cbl"
        ceedays_src.write_text(CEEDAYS_SHIM_SRC, encoding="ascii")
        drvdate_src.write_text(DRVDATE_SRC, encoding="ascii")
        tmp_ceedays = scratch / "CEEDAYS.so"
        tmp_drvdate = scratch / "DRVDATE"

        # Compile the CEEDAYS shim as a dynamically-loadable module (``-m``).
        # WHY ``-fintrinsics=ALL`` and NOT ``--std=ibm-strict`` (Trade-off): the
        # shim uses the GnuCOBOL intrinsic ``FUNCTION TEST-DATE-YYYYMMDD``, which
        # the strict IBM dialect REJECTS. The shim is a pure test artifact that
        # only stands in for an absent LE service, so it is intentionally not
        # bound to the repo's production dialect; the unit under test (CSUTLDTC)
        # below is still compiled under production ``--std=ibm-strict``.
        built = subprocess.run(
            [cobc, "-m", "-free", "-fintrinsics=ALL", "-o", str(tmp_ceedays), str(ceedays_src)],
            cwd=str(scratch),
            capture_output=True,
            text=True,
        )
        if built.returncode != 0 or not tmp_ceedays.exists():
            pytest.skip(
                "CEEDAYS shim build failed: "
                + (built.stderr or built.stdout or "(no compiler output)").strip()
            )

        # Compile DRVDATE as a main executable (``-x``) static-linked with the
        # real, unmodified CSUTLDTC.
        # WHY static-link (Refactoring Rationale): it makes CSUTLDTC a called
        # subprogram of a genuine main program, so the harness launches it like
        # any other batch executable; the ``USING`` clause is on the subprogram
        # CALL, not on the main's entry point, so ``-x`` is legal.
        # WHY ``--std=ibm-strict`` + ``-I app/cpy`` (Assumption): mirrors the
        # repo's documented compile convention so CSUTLDTC compiles identically to
        # production.
        built = subprocess.run(
            [
                cobc, "-x", "-fixed", "--std=ibm-strict",
                "-I", str(copybook_dir),
                "-o", str(tmp_drvdate), str(drvdate_src), str(csutldtc_src),
            ],
            cwd=str(scratch),
            capture_output=True,
            text=True,
        )
        if built.returncode != 0 or not tmp_drvdate.exists():
            pytest.skip(
                "CSUTLDTC driver (DRVDATE) build failed: "
                + (built.stderr or built.stdout or "(no compiler output)").strip()
            )

        # Publish atomically. WHY ``os.replace`` (Assumption): a POSIX rename
        # within one filesystem is atomic, so a reader sees either the old
        # artifact or the fully-written new one -- never a truncated file -- which
        # is precisely what makes the concurrent-worker race benign.
        os.replace(str(tmp_ceedays), str(ceedays_so))
        os.replace(str(tmp_drvdate), str(drvdate))
    finally:
        # Always remove the scratch dir (best-effort). WHY (Trade-off): the two
        # artifacts were moved out by ``os.replace``, so only the ``.cbl`` sources
        # and any cobc intermediates remain; deleting them keeps ``build_dir``
        # clean and honours the suite's "no stray ``.cbl``" rule.
        shutil.rmtree(scratch, ignore_errors=True)


def _run_date(cobol_runner, testdate: str, testfmt: str = _DATE_MASK) -> tuple[int, str, int]:
    """Run ``DRVDATE`` for one date/format and return the parsed outcome.

    Purpose
    -------
    Execute the ``DRVDATE`` driver (which calls the real ``CSUTLDTC``) with the
    supplied date and mask, then parse the driver's stdout into the observable
    outputs CardDemo relies on plus the driver process exit status.

    Parameters
    ----------
    cobol_runner : tests.helpers.cobol_runner.CobolRunner
        The suite runner fixture. Its ``build_env`` already puts ``build_dir`` on
        ``COB_LIBRARY_PATH``, so the dynamically-CALL'd ``CEEDAYS.so`` shim
        resolves without any extra path juggling.
    testdate : str
        The 10-character candidate date (e.g. ``"2023-02-29"``), exported to the
        driver as the ``TESTDATE`` environment variable.
    testfmt : str, optional
        The date mask, exported as ``TESTFMT``. Defaults to ``"YYYY-MM-DD"``.

    Returns
    -------
    tuple[int, str, int]
        ``(severity, result_text, returncode)`` where ``severity`` is the integer
        LE severity CSUTLDTC placed in ``RETURN-CODE``, ``result_text`` is the
        captured result text (the ``WS-MESSAGE`` body), and ``returncode`` is the
        driver process exit status.

    Raises
    ------
    AssertionError
        If the driver's stdout does not contain both the ``SEV=[...]`` and
        ``RESULT=[...]`` markers -- surfaced with the raw streams for auditability.
    """
    # WHY env_overrides (Assumption): DRVDATE reads its inputs via
    # ``ACCEPT ... FROM ENVIRONMENT 'TESTDATE'/'TESTFMT'``; passing them as
    # overrides layers them on top of the runner's standard bindings.
    # WHY check=False (Refactoring Rationale): an invalid date is a legitimate,
    # expected scenario that returns a non-zero RETURN-CODE (severity 12), so a
    # non-zero exit must NOT raise -- it is exactly what several tests assert on.
    result = cobol_runner.run(
        "DRVDATE",
        env_overrides={"TESTDATE": testdate, "TESTFMT": testfmt},
        timeout=60,
        check=False,
    )
    stdout = result.stdout

    sev_match = _SEV_RE.search(stdout)
    result_match = _RESULT_RE.search(stdout)
    # WHY assert-with-context (Trade-off): if a marker is missing the run did
    # something unexpected (e.g. the shim failed to load), so embedding the raw
    # stdout/stderr in the message makes the failure self-diagnosing rather than a
    # bare ``AttributeError: 'NoneType' object has no attribute 'group'``.
    assert sev_match is not None and result_match is not None, (
        "DRVDATE did not emit the expected SEV=/RESULT= markers for "
        f"testdate={testdate!r} testfmt={testfmt!r}.\n"
        f"--- stdout ---\n{stdout!r}\n--- stderr ---\n{result.stderr!r}"
    )

    severity = int(sev_match.group(1))
    result_text = result_match.group(1)
    return severity, result_text, result.returncode


@pytest.mark.parametrize("testdate", _VALID_DATES)
def test_valid_dates(cobol_runner, build_dir, repo_root, testdate):
    """Assert ``CSUTLDTC`` accepts well-formed, leap-year, and century dates.

    Purpose
    -------
    For each date the utility must treat as valid, assert the severity it
    propagates is 0 and the mapped result text reads ``'Date is valid'`` (and,
    defensively, that the ``'Date is invalid'`` text is absent).

    Parameters
    ----------
    cobol_runner : tests.helpers.cobol_runner.CobolRunner
        Suite runner fixture (see the module docstring for its role).
    build_dir : pathlib.Path
        Shared build-directory fixture; where the driver/shim are compiled.
    repo_root : pathlib.Path
        Repository-root fixture; locates the ``CSUTLDTC`` source for the build.
    testdate : str
        The parametrized valid date under test.

    Returns
    -------
    None

    Raises
    ------
    AssertionError
        If the severity is not 0 or the result text does not confirm validity.
    """
    # WHY ensure-then-run (Assumption): the driver/shim are test-only artifacts
    # that scripts/build_test_programs.sh does not emit, so the test builds them
    # on demand to stay self-sufficient before asking the runner to launch them.
    _ensure_date_programs(build_dir, repo_root)
    severity, result_text, _returncode = _run_date(cobol_runner, testdate)

    assert severity == 0, (
        f"expected severity 0 (valid) for {testdate!r}, got {severity}; "
        f"result={result_text!r}"
    )
    assert "Date is valid" in result_text, (
        f"expected 'Date is valid' in the result for {testdate!r}; got {result_text!r}"
    )
    # WHY the exclusion assertion (Trade-off): 'Date is valid' is not a substring
    # of 'Date is invalid', so this catches a pathological regression where the
    # wrong EVALUATE branch fired -- a bare positive-substring check alone could
    # miss it.
    assert "Date is invalid" not in result_text, (
        f"unexpected 'Date is invalid' text for a valid date {testdate!r}: {result_text!r}"
    )


@pytest.mark.parametrize("testdate", _INVALID_DATES)
def test_invalid_dates(cobol_runner, build_dir, repo_root, testdate):
    """Assert ``CSUTLDTC`` rejects invalid and malformed dates with severity 12.

    Purpose
    -------
    For each date the utility must reject -- a non-leap Feb 29, a century
    non-leap Feb 29, an out-of-range month, an out-of-range day, a non-numeric
    string, an empty date, and a whitespace-only date -- assert the severity is
    12 and the mapped result text reads ``'Date is invalid'``. This exercises
    CSUTLDTC's error mapping and its ``SEVERITY -> RETURN-CODE`` propagation for
    every rejection route, including the degenerate empty/blank inputs added to
    close QA finding CSUTLDTC-2 (see :data:`_INVALID_DATES`).

    Parameters
    ----------
    cobol_runner : tests.helpers.cobol_runner.CobolRunner
        Suite runner fixture (see the module docstring for its role).
    build_dir : pathlib.Path
        Shared build-directory fixture; where the driver/shim are compiled.
    repo_root : pathlib.Path
        Repository-root fixture; locates the ``CSUTLDTC`` source for the build.
    testdate : str
        The parametrized invalid/malformed date under test.

    Returns
    -------
    None

    Raises
    ------
    AssertionError
        If the severity is not 12 or the result text does not report invalidity.
    """
    _ensure_date_programs(build_dir, repo_root)
    severity, result_text, _returncode = _run_date(cobol_runner, testdate)

    assert severity == 12, (
        f"expected severity 12 (invalid) for {testdate!r}, got {severity}; "
        f"result={result_text!r}"
    )
    assert "Date is invalid" in result_text, (
        f"expected 'Date is invalid' in the result for {testdate!r}; got {result_text!r}"
    )


def test_severity_matches_return_code(cobol_runner, build_dir, repo_root):
    """Assert the driver's process exit code equals the parsed severity.

    Purpose
    -------
    Prove CSUTLDTC's ``MOVE WS-SEVERITY-N TO RETURN-CODE`` reaches the operating
    system: for a known-invalid date the driver's OS exit status must equal the
    severity parsed from its stdout. This underpins the whole suite's return-code
    rubric (RC=0 pass, RC=4 warn, ...), which depends on that propagation.

    Parameters
    ----------
    cobol_runner : tests.helpers.cobol_runner.CobolRunner
        Suite runner fixture (see the module docstring for its role).
    build_dir : pathlib.Path
        Shared build-directory fixture; where the driver/shim are compiled.
    repo_root : pathlib.Path
        Repository-root fixture; locates the ``CSUTLDTC`` source for the build.

    Returns
    -------
    None

    Raises
    ------
    AssertionError
        If the severity is not 12, or the process exit code does not equal it.
    """
    # WHY a rejected date with severity 12 (Trade-off): 12 is < 256, so it
    # survives POSIX's 8-bit exit-status truncation and the equality assertion is
    # exact rather than a modulo-256 approximation (a larger LE abend code such as
    # 999 would be truncated to 231 and could not be compared directly).
    _ensure_date_programs(build_dir, repo_root)
    severity, result_text, returncode = _run_date(cobol_runner, "2023-02-29")

    assert severity == 12, (
        f"expected severity 12 for a known-invalid date; got {severity} "
        f"(result={result_text!r})"
    )
    assert returncode == severity, (
        f"driver exit code {returncode} does not equal the reported severity "
        f"{severity}; the RETURN-CODE propagation contract is broken"
    )
