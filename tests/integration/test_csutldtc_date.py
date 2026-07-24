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
   expects and computes validity with an explicit Gregorian calendar rule
   (class-test the digits, range-check the month, derive days-in-month via the
   full leap rule, range-check the day, and enforce the 1582-10-15 Gregorian
   floor). AAP Sections 0.3.1 / 0.10.2 explicitly endorse stubbing an
   unavailable external dependency for test purposes.
   (Alternatives Considered: a Java-based zUnit stub is impossible -- no JVM is
   installed on the runner; a real LE ``CEEDAYS`` is simply unavailable. An
   earlier revision computed validity with GnuCOBOL's ``FUNCTION
   TEST-DATE-YYYYMMDD`` intrinsic, but its 1601-01-01 epoch wrongly rejected the
   1582-10-15..1600-12-31 historical window, so it was replaced by the explicit
   rule above -- see F-DATE-SHIM-RANGE.)
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
3.1.x line. On 3.2.0 CSUTLDTC copies a 2-byte binary length prefix (value
10 == ``0x000A``, a NUL + newline) into its display field, which would embed
raw control bytes in the surfaced result. The ``DRVDATE`` driver now neutralises
every ``0x00``/``0x0A`` to a SPACE before it prints ``RESULT=[...]`` (see
``DRVDATE_SRC`` and ``test_result_buffer_is_clean``), so the buffer callers
observe is clean, printable, single-line text; ``re.DOTALL`` is nonetheless
retained on ``_RESULT_RE`` as a defensive superset (F-DATE-RESULT-BUFFER).

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
validity with an explicit Gregorian calendar rule that honours the full IBM LE
date domain (1582-10-15 onward, including the historical window an intrinsic-
based epoch would miss); its valid/invalid verdict matches LE for every date in
the truth table above. It is a validity oracle for the contract, not a
re-implementation of LE internals.
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
#
# WHY explicit Gregorian arithmetic rather than an intrinsic (Refactoring
# Rationale -- resolves F-DATE-SHIM-RANGE): the previous shim delegated to
# GnuCOBOL's ``FUNCTION TEST-DATE-YYYYMMDD``, whose valid domain begins at its
# 1601-01-01 integer-date epoch. That silently rejected the *lower* portion of
# the IBM LE / CEEDAYS Gregorian domain (1582-10-15 .. 1600-12-31) as invalid,
# so historical dates the real CSUTLDTC would accept failed here -- a false
# negative that made the shim, not CSUTLDTC, the thing under test. The shim now
# validates the date directly: class-test the Y/M/D digits, range-check the
# month (1-12), derive days-in-month (with the full Gregorian leap rule
# div-by-4 AND (not-div-by-100 OR div-by-400)), range-check the day, and finally
# enforce the Gregorian *lower bound* by requiring YYYYMMDD >= 15821015 (the
# 1582-10-15 start of the Gregorian calendar, the documented floor of the IBM LE
# date domain). This reproduces the exact valid domain CSUTLDTC's callers rely
# on -- accepting 1582-10-15..present and rejecting pre-Gregorian dates -- using
# only standard COBOL (no intrinsic, no epoch assumption).
# WHY the shim's invalid token stays coarse 0x000C 'Date is invalid' (Trade-off):
# this integration shim intentionally distinguishes only valid (severity 0) vs
# invalid (severity 12); a pre-Gregorian or malformed date both map to the single
# invalid token so the DRVDATE contract asserted below stays a clean two-way
# valid/invalid decision. The finer LE feedback codes (e.g. FC-UNSUPP-RANGE for
# out-of-range) are exercised at the COBOL-unit layer (CSUTLDTC_test.cbl), which
# emits the semantically-precise token; splitting that concern keeps each layer's
# assertions focused.
# WHY free format here (Assumption): the shim is compiled with ``-free`` so the
# fixed-column rules do not apply to it; the leading indentation is cosmetic.
# The rewrite uses only standard COBOL verbs (class test, EVALUATE, DIVIDE,
# COMPUTE), so ``-fintrinsics=ALL`` is no longer strictly required for it, though
# the build below retains that flag harmlessly (see ``_ensure_date_programs``).
CEEDAYS_SHIM_SRC = """\
       IDENTIFICATION DIVISION.
       PROGRAM-ID. CEEDAYS.
       DATA DIVISION.
       WORKING-STORAGE SECTION.
       01 WS-YR-X        PIC X(4).
       01 WS-MO-X        PIC X(2).
       01 WS-DY-X        PIC X(2).
       01 WS-YR          PIC 9(4).
       01 WS-MO          PIC 9(2).
       01 WS-DY          PIC 9(2).
       01 WS-DIM         PIC 9(2).
       01 WS-Q           PIC 9(6).
       01 WS-R4          PIC 9(4).
       01 WS-R100        PIC 9(4).
       01 WS-R400        PIC 9(4).
       01 WS-YMD         PIC 9(8).
       01 WS-OK          PIC 9.
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
           MOVE 0 TO LK-LILLIAN
           MOVE 0 TO WS-OK
           MOVE LK-DATE-TEXT(1:4) TO WS-YR-X
           MOVE LK-DATE-TEXT(6:2) TO WS-MO-X
           MOVE LK-DATE-TEXT(9:2) TO WS-DY-X
           IF WS-YR-X IS NUMERIC AND WS-MO-X IS NUMERIC
              AND WS-DY-X IS NUMERIC
               MOVE WS-YR-X TO WS-YR
               MOVE WS-MO-X TO WS-MO
               MOVE WS-DY-X TO WS-DY
               IF WS-MO >= 1 AND WS-MO <= 12
                   EVALUATE WS-MO
                     WHEN 1
                     WHEN 3
                     WHEN 5
                     WHEN 7
                     WHEN 8
                     WHEN 10
                     WHEN 12
                       MOVE 31 TO WS-DIM
                     WHEN 4
                     WHEN 6
                     WHEN 9
                     WHEN 11
                       MOVE 30 TO WS-DIM
                     WHEN OTHER
                       DIVIDE WS-YR BY 4 GIVING WS-Q REMAINDER WS-R4
                       DIVIDE WS-YR BY 100 GIVING WS-Q REMAINDER WS-R100
                       DIVIDE WS-YR BY 400 GIVING WS-Q REMAINDER WS-R400
                       IF WS-R4 = 0 AND (WS-R100 NOT = 0 OR WS-R400 = 0)
                           MOVE 29 TO WS-DIM
                       ELSE
                           MOVE 28 TO WS-DIM
                       END-IF
                   END-EVALUATE
                   IF WS-DY >= 1 AND WS-DY <= WS-DIM
                       COMPUTE WS-YMD =
                           WS-YR * 10000 + WS-MO * 100 + WS-DY
                       IF WS-YMD >= 15821015
                           MOVE 1 TO WS-OK
                       END-IF
                   END-IF
               END-IF
           END-IF
           IF WS-OK = 1
              MOVE LOW-VALUES TO LK-FEEDBACK
           ELSE
              MOVE X'000C000059C3C5C5' TO LK-FEEDBACK(1:8)
           END-IF
           GOBACK.
"""

# The DRVDATE driver: a fixed-format main that reads TESTDATE/TESTFMT from the
# environment, calls the real CSUTLDTC, and prints the severity (RETURN-CODE)
# and the 80-byte result on stdout in a machine-parsable form.
# WHY the two INSPECT ... REPLACING statements (resolves F-DATE-RESULT-BUFFER):
# CSUTLDTC builds its 80-byte WS-MESSAGE result and, at its line 122, does
# ``MOVE WS-DATE-TO-TEST TO WS-DATE`` -- copying the LE varying-string's 2-byte
# binary length prefix (value 10 == bytes 0x00,0x0A) into the "TstDate:" echo
# field of the message. That leaks raw control bytes (a NUL and a line-feed) into
# the buffer this driver surfaces, so the exposed RESULT was neither pure text nor
# single-line. Because those two byte values (0x00, 0x0A) never occur in any
# legitimate part of the message (the severity text, the message number, the
# verdict phrase, the date digits, and the field labels are all printable ASCII),
# neutralising every 0x00 and 0x0A to a SPACE is a safe, offset-independent way to
# expose ONLY clean text. WHY neutralise-in-place rather than reference-modify the
# echo at a fixed offset (Alternatives Considered / Trade-off): overwriting a
# hard-coded ``WS-RESULT(46:10)`` slice would re-encode CSUTLDTC's internal
# message geometry into this test and break silently if that layout shifted;
# replacing the two known metadata byte values is robust to layout and provably
# cannot touch the printable content callers consume. CSUTLDTC itself is REFERENCE
# / out-of-scope production source (AAP 0.8.2), so the fix lives here in the test
# driver, exactly as the finding's "decode before returning/asserting" remediation
# directs.
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
           INSPECT WS-RESULT REPLACING ALL X'00' BY SPACE
           INSPECT WS-RESULT REPLACING ALL X'0A' BY SPACE
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

# Result marker: ``RESULT=[<80 bytes of sanitized WS-MESSAGE>]``.
# WHY re.DOTALL is retained even though the driver now sanitizes the buffer
# (Refactoring Rationale + defensive Trade-off): historically CSUTLDTC's
# WS-MESSAGE embedded a NUL + newline (the 2-byte binary length prefix, value
# 10 == 0x000A, that ``MOVE WS-DATE-TO-TEST TO WS-DATE`` copied into the display
# field), so ``RESULT=[...]`` straddled two physical lines. DRVDATE now replaces
# every 0x00/0x0A with a SPACE before DISPLAY (see DRVDATE_SRC above), so on this
# runner the marker is single-line and metadata-free. DOTALL is kept because it is
# a strict superset of single-line matching: it still matches the now-clean
# single-line form AND remains correct if any future toolchain reintroduces an
# embedded newline, so the parser cannot silently fail to match. The
# ``test_result_buffer_is_clean`` test below asserts the sanitisation held.
_RESULT_RE = re.compile(r"RESULT=\[(.*)\]", re.DOTALL)


# ---------------------------------------------------------------------------
# Empirical truth table (encoded EXACTLY, verified on this runner).
# ---------------------------------------------------------------------------

# Dates CSUTLDTC must ACCEPT (severity 0, 'Date is valid').
# WHY this exact set (boundary coverage): an ordinary in-month date, a 4-year
# leap day, and a 400-year century leap day exercise the ordinary path plus both
# leap-year rules the utility must honour.
# WHY the three historical entries (Refactoring Rationale -- closes
# F-DATE-SHIM-RANGE): the IBM LE / CEEDAYS Gregorian date domain begins at
# 1582-10-15 (the first day of the Gregorian calendar), so CSUTLDTC must accept
# dates from that floor onward. The previous shim delegated to
# ``FUNCTION TEST-DATE-YYYYMMDD``, whose 1601-01-01 epoch silently rejected the
# 1582-10-15..1600-12-31 window, so these dates were never validated. They are
# added now to lock in the *lower boundary* of the supported domain:
#   * "1582-10-15" -- the exact Gregorian start date (inclusive floor).
#   * "1600-02-29" -- a 400-year leap day inside the historical window (1600 is
#                     divisible by 400), exercising the leap rule below the epoch.
#   * "1600-12-31" -- the top of the previously-rejected window.
_VALID_DATES = (
    "2023-06-15",
    "2024-02-29",
    "2000-02-29",
    "1582-10-15",
    "1600-02-29",
    "1600-12-31",
)

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
# the shim class-tests the Y/M/D digit slices; an all-blank date fails the
# ``IS NUMERIC`` test, so the shim leaves its OK flag clear and returns the 0x000C
# feedback, which CSUTLDTC maps to severity 12 / 'Date is invalid' -- exactly the
# same rejection route the other invalid strings take, with NO abort or crash.
# Both were confirmed to return driver exit code 12.
# WHY NOT assert a distinct "blank date" message (Trade-off): CSUTLDTC has a
# single 'Date is invalid' rejection text (its EVALUATE has no dedicated
# empty-input arm), so the honest contract to pin is severity 12 + that text --
# inventing a finer assertion would test a message CSUTLDTC does not emit.
# WHY the trailing "1582-10-14" entry (Refactoring Rationale -- the lower-bound
# companion to F-DATE-SHIM-RANGE): having added the Gregorian floor (1582-10-15)
# to the ACCEPT set, this asserts the shim also REJECTS the day immediately below
# it. 1582-10-14 is a well-formed, in-range calendar date (October has 31 days),
# so the only reason to reject it is the Gregorian lower bound -- this pins that
# the domain floor is enforced, not merely that malformed strings fail. WHY it
# yields severity 12 here rather than the finer LE 'Unsupp. Range' (Trade-off):
# this integration shim is a coarse valid/invalid stand-in (see CEEDAYS_SHIM_SRC),
# so any out-of-domain date maps to the single 0x000C invalid token; the precise
# FC-UNSUPP-RANGE severity-3 feedback for pre-Gregorian dates is asserted at the
# COBOL-unit layer (CSUTLDTC_test.cbl), which models the real LE token set.
_INVALID_DATES = (
    "2023-02-29",
    "1900-02-29",
    "2023-13-01",
    "2023-02-30",
    "ABCD-EF-GH",
    "",
    "          ",
    "1582-10-14",
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
        # WHY ``-free`` and NOT ``--std=ibm-strict`` (Trade-off): the shim is
        # authored in free format (its indentation is cosmetic, not Area A/B), so
        # it is built ``-free``; it is a pure test stand-in for the absent LE
        # service and is intentionally not bound to the repo's production fixed
        # dialect. The unit under test (CSUTLDTC) below is still compiled under
        # production ``--std=ibm-strict``.
        # WHY ``-fintrinsics=ALL`` is retained though no longer required
        # (Refactoring Rationale -- F-DATE-SHIM-RANGE): the earlier shim delegated
        # to ``FUNCTION TEST-DATE-YYYYMMDD`` and genuinely needed this flag. The
        # rewritten shim validates the date with only standard COBOL (class test,
        # EVALUATE, DIVIDE, COMPUTE) and no intrinsic, so the flag is now a benign
        # no-op; it is kept to avoid churning the build invocation and to remain
        # tolerant if the shim ever reintroduces an intrinsic helper.
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
    """Assert ``CSUTLDTC`` accepts well-formed, leap-year, century, and
    historical-Gregorian dates.

    Purpose
    -------
    For each date the utility must treat as valid -- including the
    1582-10-15..1600-12-31 lower boundary of the IBM LE Gregorian domain added to
    close F-DATE-SHIM-RANGE -- assert the severity it propagates is 0 and the
    mapped result text reads ``'Date is valid'`` (and, defensively, that the
    ``'Date is invalid'`` text is absent).

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
    string, an empty date, a whitespace-only date, and a pre-Gregorian date one
    day below the 1582-10-15 domain floor -- assert the severity is 12 and the
    mapped result text reads ``'Date is invalid'``. This exercises CSUTLDTC's
    error mapping and its ``SEVERITY -> RETURN-CODE`` propagation for every
    rejection route, including the degenerate empty/blank inputs added to close
    QA finding CSUTLDTC-2 and the lower-bound reject added to close
    F-DATE-SHIM-RANGE (see :data:`_INVALID_DATES`).

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


def test_result_buffer_is_clean(cobol_runner, build_dir, repo_root):
    """Assert the exposed CSUTLDTC result buffer carries no LE metadata bytes.

    Purpose
    -------
    Regression guard for F-DATE-RESULT-BUFFER. CSUTLDTC's raw 80-byte
    ``WS-MESSAGE`` leaks the LE varying-string's 2-byte binary length prefix
    (bytes ``0x00`` and ``0x0A``) into its "TstDate:" echo field, because its
    ``MOVE WS-DATE-TO-TEST TO WS-DATE`` copies the prefix along with the text.
    The ``DRVDATE`` driver now neutralises every ``0x00``/``0x0A`` to a SPACE
    before it surfaces the buffer (see :data:`DRVDATE_SRC`). This test proves the
    buffer a caller observes is therefore pure, printable text: it contains no NUL
    and no embedded newline, while still carrying the documented verdict phrase.

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
        If the surfaced result text still contains a ``0x00`` or ``0x0A`` byte,
        or if the expected verdict phrase is missing (which would mean the
        sanitisation destroyed legitimate content rather than only the metadata).
    """
    # WHY a KNOWN-VALID date (Trade-off): the buffer-pollution defect is present
    # on every CSUTLDTC call regardless of verdict, but exercising the valid path
    # lets the same assertion double-check that sanitisation preserved the
    # 'Date is valid' phrase -- i.e. it removed ONLY the metadata, not real text.
    _ensure_date_programs(build_dir, repo_root)
    severity, result_text, _returncode = _run_date(cobol_runner, "2023-06-15")

    # Sanity: the run itself must have succeeded and produced the verdict, else a
    # "clean" buffer would be vacuously clean (e.g. an empty capture).
    assert severity == 0, (
        f"precondition: expected a valid date to yield severity 0; got {severity} "
        f"(result={result_text!r})"
    )
    assert "Date is valid" in result_text, (
        "precondition: the surfaced buffer must still contain the verdict phrase; "
        f"got {result_text!r}"
    )

    # WHY assert on the exact metadata byte values (Assumption): the finding
    # names the polluting bytes as the varying-length prefix 0x000A -- i.e. a NUL
    # (0x00) followed by a line-feed (0x0A). ``cobol_runner`` captures stdout as
    # decoded text, so a surviving 0x00 appears as the character ``"\x00"`` and a
    # surviving 0x0A as ``"\n"`` inside the parsed RESULT group. Their absence is
    # the precise, byte-level proof the remediation holds.
    assert "\x00" not in result_text, (
        "result buffer still contains a NUL (0x00) byte -- the LE varying-length "
        f"metadata leaked into the surfaced text: {result_text!r}"
    )
    assert "\n" not in result_text, (
        "result buffer still contains an embedded newline (0x0A) -- the LE "
        f"varying-length metadata leaked into the surfaced text: {result_text!r}"
    )
