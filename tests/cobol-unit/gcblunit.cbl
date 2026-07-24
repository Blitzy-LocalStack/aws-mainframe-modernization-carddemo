      ************************************************************
      * GCBLUnit 1.22.6  (vendored)                                *
      *                                                            *
      * PURPOSE:                                                   *
      *   Minimal COBOL unit-test assertion and result framework   *
      *   for the AWS CardDemo test suite.  This source is NOT      *
      *   compiled on its own; it is textually included through    *
      *       COPY "gcblunit.cbl".                                  *
      *   placed AFTER the END PROGRAM line of every                *
      *   tests/cobol-unit/*_test.cbl program, so the units below   *
      *   are linked as sibling compilation units into the same     *
      *   cobc -x executable as the test that COPYs them.           *
      *                                                            *
      * PUBLIC PROGRAM UNITS AND PARAMETERS:                        *
      *   assert-equals    USING <expected> <actual>                *
      *     expected : PIC X ANY LENGTH - value asserted correct    *
      *     actual   : PIC X ANY LENGTH - value from code-under-test *
      *   assert-notequals USING <unexpected> <actual>              *
      *     unexpected: PIC X ANY LENGTH - value that must differ    *
      *     actual   : PIC X ANY LENGTH - value from code-under-test *
      *   gcblunit-result  USING <return-count>                     *
      *     return-count: PIC S9(9) COMP (numeric, OUTPUT) -         *
      *                   receives accumulated failure count        *
      *   gcblunit-summary (no parameters)                          *
      *   gcblunit-init    (no parameters)                          *
      *                                                            *
      * RETURNS:                                                    *
      *   gcblunit-result copies the shared failure counter into    *
      *   its linkage argument.  The test main then performs        *
      *       MOVE <return-count> TO RETURN-CODE                     *
      *       STOP RUN                                               *
      *   GnuCOBOL maps RETURN-CODE to the process exit status, so   *
      *   0 failures -> exit 0 -> scripts/run_unit_tests.sh marks    *
      *   the test PASS; any failures -> non-zero exit -> FAIL.      *
      *                                                            *
      * EXCEPTIONS / ERRORS:                                        *
      *   None are raised.  Assertion failures are COUNTED and       *
      *   DISPLAYed to the console; the framework never STOP RUNs    *
      *   nor abends, so one failed assertion never hides the        *
      *   assertions that follow it in the same test program.       *
      *                                                            *
      * LICENSE (GPL - preserved, as the GPL requires for a        *
      * modified copy):                                            *
      *   This vendored unit is a DERIVATIVE of GCBLUnit by        *
      *   Olegs Kunicins, free software under the GNU General      *
      *   Public License; either version 2, or (at your option)    *
      *   any later version.  SPDX: GPL-2.0-or-later (upstream     *
      *   modules.json declares GPL-2.0).  Distributed WITHOUT     *
      *   ANY WARRANTY; without even the implied warranty of       *
      *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.     *
      *   See the GNU General Public License for more details.     *
      *   Full license text ships upstream as LICENSE at the       *
      *   pinned commit below.                                     *
      *                                                            *
      * PROVENANCE (pinned, immutable):                            *
      *   Upstream : GCBLUnit - Simple Unit Testing for            *
      *              GnuCOBOL                                      *
      *   Copyright: (C) Olegs Kunicins                            *
      *   Repo     : github.com/OlegKunitsyn/gcblunit              *
      *   Tag      : 1.22.6                                        *
      *   Commit   : f40c65df7ab834b9acf03fa17657167362cfdf23      *
      *   File     : gcblunit.cbl at that commit                   *
      *   SHA-256  : 55a096cede232ade01bbcb894cb91fbf1b1           *
      *              d8308b5fac7950970b2127257c2ef                 *
      *   Fetch    : raw.githubusercontent.com/OlegKunitsyn/       *
      *              gcblunit/f40c65df7ab834b9acf03fa1765716       *
      *              7362cfdf23/gcblunit.cbl                       *
      *   Declared : tests/cobolget.json + pinned in               *
      *              tests/import.json (both GPL-2.0, 1.22.6).     *
      *                                                            *
      * MODIFICATION HISTORY (derivative vs upstream f40c65d):     *
      *   A REWRITTEN derivative, not a verbatim copy, so the      *
      *   vendored SHA-256 differs from the upstream hash above.   *
      *   1. Removed the free-format driver main (program-id       *
      *      'runner') and '--job' orchestration; reshaped as      *
      *      plain fixed-format SIBLING units COPY-d after each    *
      *      test's END PROGRAM.  WHY: CardDemo builds one -x      *
      *      executable per *_test.cbl and reads pass/fail from    *
      *      each process exit code, so a driver main would        *
      *      break the one-executable-per-test model.              *
      *   2. Replaced per-assertion detail storage with two        *
      *      process-wide EXTERNAL counters (assertions,           *
      *      failures).                                            *
      *   3. Hardened assert diagnostics to NOT print operand      *
      *      bytes by default (length + first-diff offset only),   *
      *      preventing PAN/SSN leakage in output (MA-13).         *
      *   4. Clamped the published failure count to 255 so a       *
      *      large total cannot wrap the 8-bit exit code to a      *
      *      false PASS (MI-07).                                   *
      *   5. Re-added JUnit XML emission (unit gcblunit-junit)     *
      *      so the COBOL layer yields a CI-ingestible report      *
      *      with no external driver (MA-25).                      *
      *   6. Added explicit scope terminators (END-ADD,            *
      *      END-WRITE, END-COMPUTE) on every ADD/WRITE/COMPUTE    *
      *      so the framework is warning-clean under -Wall -Wextra;*
      *      build_test_programs.sh makes -Wterminator fatal for   *
      *      the *_test.cbl programs that COPY this member (MA-19).*
      *                                                            *
      * WHY - Alternatives Considered:                              *
      *   Upstream GCBLUnit ships a FREE-format driver main          *
      *   (program-id "runner") invoked as                          *
      *     cobc -x -debug gcblunit.cbl t1.cbl t2.cbl --job='t1 t2'  *
      *   which orchestrates many test programs and writes the       *
      *   JUnit XML itself.  That model is REJECTED here because     *
      *   CardDemo's scripts/build_test_programs.sh compiles exactly *
      *   ONE -x executable per *_test.cbl and                       *
      *   scripts/run_unit_tests.sh derives pass/fail purely from    *
      *   each process exit code.  A driver main would own program   *
      *   entry and break the one-executable-per-test build, so this *
      *   vendored copy is reshaped as plain sibling units with NO   *
      *   main and NO --job driver.                                  *
      *                                                            *
      * WHY - Assumptions:                                          *
      *   FIXED reference format (Area A at column 8, indicator in   *
      *   column 7) is mandatory: the build uses -fixed and a        *
      *   COPY-d member is parsed in the INCLUDING program's source  *
      *   format; the tests are fixed-format, so a free-format       *
      *   member would fail to parse once spliced into a test.       *
      ************************************************************

      ************************************************************
      * UNIT: assert-equals                                        *
      * PURPOSE:                                                    *
      *   Assert that two operands are byte-equal.  Increments the   *
      *   shared assertion counter; on inequality increments the     *
      *   shared failure counter and DISPLAYs a diagnostic.          *
      * PARAMETERS:                                                  *
      *   LK-EXPECTED : PIC X ANY LENGTH - expected value (passed     *
      *                 first, matching the GCBLUnit convention).    *
      *   LK-ACTUAL   : PIC X ANY LENGTH - value produced by the      *
      *                 code under test.                             *
      * RETURNS: none.  Side effect is on the shared EXTERNAL         *
      *   counters and, on failure, one console line.               *
      ************************************************************
       IDENTIFICATION DIVISION.
       PROGRAM-ID. assert-equals.
       DATA DIVISION.
       WORKING-STORAGE SECTION.
      * WHY - Trade-off: EXTERNAL shared counters are used instead of
      * threading a summary record through LINKAGE on every CALL.
      * GnuCOBOL shares ONE process-wide instance across all sibling
      * units that declare this identical record, which keeps the
      * test-facing assertion signatures at the GCBLUnit-standard two
      * operands rather than three.
       01  GCBLUNIT-COUNTERS EXTERNAL.
           05  GCBLUNIT-ASSERTIONS  PIC 9(9) COMP.
           05  GCBLUNIT-FAILURES    PIC 9(9) COMP.
      * WHY - MA-13: the diagnostic reports only operand LENGTHS and
      * the offset of the first differing byte - never the operand
      * CONTENT - so a failing assertion on a PAN / SSN / name fixture
      * cannot leak that value into CI logs.  Alternatives Considered:
      * an opt-in verbose mode printing raw bytes was REJECTED because
      * one mis-set env var in CI would defeat the control; the failure
      * COUNT plus the first-diff offset already localizes the defect
      * alongside the test body's assertion order.
       01  WS-LEN-EXP     PIC 9(9) COMP.
       01  WS-LEN-ACT     PIC 9(9) COMP.
       01  WS-MIN-LEN     PIC 9(9) COMP.
       01  WS-OFF         PIC 9(9) COMP.
       01  WS-FIRST-DIFF  PIC 9(9) COMP.
       01  WS-EDIT-EXP    PIC ZZZZZZZZ9.
       01  WS-EDIT-ACT    PIC ZZZZZZZZ9.
       01  WS-EDIT-OFF    PIC ZZZZZZZZ9.
       LINKAGE SECTION.
      * WHY - Trade-off: PIC X ANY LENGTH lets a single assertion
      * serve alphanumeric, zoned-decimal and packed operands through
      * one byte-wise comparison, avoiding a combinatorial set of
      * typed assertions.  Contract: the caller passes two items of
      * identical PICTURE / USAGE / length so byte-equality is
      * equivalent to value-equality.
       01  LK-EXPECTED  PIC X ANY LENGTH.
       01  LK-ACTUAL    PIC X ANY LENGTH.
       PROCEDURE DIVISION USING LK-EXPECTED LK-ACTUAL.
           ADD 1 TO GCBLUNIT-ASSERTIONS END-ADD
           IF LK-EXPECTED NOT = LK-ACTUAL
               ADD 1 TO GCBLUNIT-FAILURES END-ADD
               MOVE FUNCTION LENGTH(LK-EXPECTED) TO WS-LEN-EXP
               MOVE FUNCTION LENGTH(LK-ACTUAL) TO WS-LEN-ACT
               IF WS-LEN-EXP < WS-LEN-ACT
                   MOVE WS-LEN-EXP TO WS-MIN-LEN
               ELSE
                   MOVE WS-LEN-ACT TO WS-MIN-LEN
               END-IF
               MOVE 0 TO WS-FIRST-DIFF
               PERFORM VARYING WS-OFF FROM 1 BY 1
                       UNTIL WS-OFF > WS-MIN-LEN
                       OR WS-FIRST-DIFF NOT = 0
                   IF LK-EXPECTED(WS-OFF:1) NOT = LK-ACTUAL(WS-OFF:1)
                       MOVE WS-OFF TO WS-FIRST-DIFF
                   END-IF
               END-PERFORM
               IF WS-FIRST-DIFF = 0
                   COMPUTE WS-FIRST-DIFF = WS-MIN-LEN + 1 END-COMPUTE
               END-IF
               MOVE WS-LEN-EXP TO WS-EDIT-EXP
               MOVE WS-LEN-ACT TO WS-EDIT-ACT
               MOVE WS-FIRST-DIFF TO WS-EDIT-OFF
               DISPLAY "F assert-equals: mismatch (expected-len="
                   FUNCTION TRIM(WS-EDIT-EXP) " actual-len="
                   FUNCTION TRIM(WS-EDIT-ACT) " first-diff-offset="
                   FUNCTION TRIM(WS-EDIT-OFF) ")"
           END-IF
           GOBACK.
       END PROGRAM assert-equals.

      ************************************************************
      * UNIT: assert-notequals                                     *
      * PURPOSE:                                                    *
      *   Assert that two operands are NOT byte-equal.  Increments   *
      *   the shared assertion counter; on equality increments the   *
      *   shared failure counter and DISPLAYs a diagnostic.          *
      * PARAMETERS:                                                  *
      *   LK-UNEXPECTED : PIC X ANY LENGTH - value that must NOT      *
      *                   equal LK-ACTUAL (passed first).           *
      *   LK-ACTUAL     : PIC X ANY LENGTH - value produced by the    *
      *                   code under test.                          *
      * RETURNS: none.  Side effect is on the shared EXTERNAL         *
      *   counters and, on failure, one console line.               *
      ************************************************************
       IDENTIFICATION DIVISION.
       PROGRAM-ID. assert-notequals.
       DATA DIVISION.
       WORKING-STORAGE SECTION.
       01  GCBLUNIT-COUNTERS EXTERNAL.
           05  GCBLUNIT-ASSERTIONS  PIC 9(9) COMP.
           05  GCBLUNIT-FAILURES    PIC 9(9) COMP.
      * WHY - MA-13: on failure the operands are EQUAL, so there is
      * nothing to diff; the diagnostic reports only their shared
      * LENGTH and never the value, for the same no-leak reason.
       01  WS-LEN-ACT     PIC 9(9) COMP.
       01  WS-EDIT-ACT    PIC ZZZZZZZZ9.
       LINKAGE SECTION.
       01  LK-UNEXPECTED  PIC X ANY LENGTH.
       01  LK-ACTUAL      PIC X ANY LENGTH.
       PROCEDURE DIVISION USING LK-UNEXPECTED LK-ACTUAL.
           ADD 1 TO GCBLUNIT-ASSERTIONS END-ADD
      * WHY - Refactoring Rationale: this is the exact logical mirror
      * of assert-equals (fail-on-equal instead of fail-on-differ),
      * kept as its own unit so tests read with the same two-operand
      * GCBLUnit vocabulary rather than a mode flag on one assertion.
           IF LK-UNEXPECTED = LK-ACTUAL
               ADD 1 TO GCBLUNIT-FAILURES END-ADD
               MOVE FUNCTION LENGTH(LK-ACTUAL) TO WS-LEN-ACT
               MOVE WS-LEN-ACT TO WS-EDIT-ACT
               DISPLAY "F assert-notequals: operands are equal but "
                   "must differ (len=" FUNCTION TRIM(WS-EDIT-ACT) ")"
           END-IF
           GOBACK.
       END PROGRAM assert-notequals.

      ************************************************************
      * UNIT: gcblunit-result                                      *
      * PURPOSE:                                                    *
      *   Publish the accumulated failure count so the test main     *
      *   can turn it into the process exit code.                   *
      * PARAMETERS:                                                  *
      *   LK-RESULT : PIC S9(9) COMP (numeric, OUTPUT) - receives    *
      *               the shared failure counter.                   *
      * RETURNS: LK-RESULT = number of failed assertions so far.     *
      ************************************************************
       IDENTIFICATION DIVISION.
       PROGRAM-ID. gcblunit-result.
       DATA DIVISION.
       WORKING-STORAGE SECTION.
       01  GCBLUNIT-COUNTERS EXTERNAL.
           05  GCBLUNIT-ASSERTIONS  PIC 9(9) COMP.
           05  GCBLUNIT-FAILURES    PIC 9(9) COMP.
       LINKAGE SECTION.
       01  LK-RESULT  PIC S9(9) COMP.
       PROCEDURE DIVISION USING LK-RESULT.
      * WHY - this is the exit-code bridge.  scripts/run_unit_tests.sh
      * reads only the executable's process exit status, and GnuCOBOL
      * maps the RETURN-CODE special register to that status.  By
      * returning the failure count, the caller can perform
      *   MOVE LK-RESULT TO RETURN-CODE / STOP RUN
      * yielding exit 0 (PASS) when there are no failures and a
      * non-zero exit (FAIL) equal to the number of failures.
      * WHY - MI-07: the caller MOVEs LK-RESULT to RETURN-CODE, which
      * GnuCOBOL maps to the 8-bit process exit status (0-255).  A
      * failure total that is a multiple of 256 would wrap to 0 and be
      * misread as PASS, so the count is clamped to 255 - an exact
      * count when < 256, and a stable nonzero otherwise.  A multi-byte
      * encoding was REJECTED because POSIX exit codes are 8-bit; the
      * precise total stays available on the summary line and report.
           IF GCBLUNIT-FAILURES > 255
               MOVE 255 TO LK-RESULT
           ELSE
               MOVE GCBLUNIT-FAILURES TO LK-RESULT
           END-IF
           GOBACK.
       END PROGRAM gcblunit-result.

      ************************************************************
      * UNIT: gcblunit-summary                                     *
      * PURPOSE:                                                    *
      *   DISPLAY a one-line tally of total assertions and failures  *
      *   so the stdout captured into reports/unit.xml is readable.  *
      * PARAMETERS: none.                                           *
      * RETURNS: none (writes exactly one line to the console).      *
      ************************************************************
       IDENTIFICATION DIVISION.
       PROGRAM-ID. gcblunit-summary.
       DATA DIVISION.
       WORKING-STORAGE SECTION.
       01  GCBLUNIT-COUNTERS EXTERNAL.
           05  GCBLUNIT-ASSERTIONS  PIC 9(9) COMP.
           05  GCBLUNIT-FAILURES    PIC 9(9) COMP.
      * WHY - Trade-off: edited numeric work items render the COMP
      * counters as readable, zero-suppressed decimals on the summary
      * line only; they are display-only and never feed the exit code.
       01  WS-EDIT-ASSERTIONS  PIC ZZZZZZZZ9.
       01  WS-EDIT-FAILURES    PIC ZZZZZZZZ9.
       PROCEDURE DIVISION.
           MOVE GCBLUNIT-ASSERTIONS TO WS-EDIT-ASSERTIONS
           MOVE GCBLUNIT-FAILURES   TO WS-EDIT-FAILURES
           DISPLAY "GCBLUnit: assertions=" WS-EDIT-ASSERTIONS
               " failures=" WS-EDIT-FAILURES
      * WHY - MA-25: emit the machine-readable JUnit report from within
      * the COBOL layer itself.  run_unit_tests.sh is a separate AAP
      * task and may not exist yet, so JUnit emission must be self-
      * contained here.  gcblunit-junit no-ops unless GCBLUNIT_JUNIT is
      * set, so tests that do not request a report are unaffected, and
      * it is called from summary (not from each test main) so no test
      * main has to change for the report to be produced.
           CALL "gcblunit-junit"
           END-CALL
           GOBACK.
       END PROGRAM gcblunit-summary.

      ************************************************************
      * UNIT: gcblunit-init                                        *
      * PURPOSE:                                                    *
      *   Reset the shared assertion and failure counters to zero.   *
      * PARAMETERS: none.                                           *
      * RETURNS: none (zeroes the shared EXTERNAL counters).         *
      ************************************************************
       IDENTIFICATION DIVISION.
       PROGRAM-ID. gcblunit-init.
       DATA DIVISION.
       WORKING-STORAGE SECTION.
       01  GCBLUNIT-COUNTERS EXTERNAL.
           05  GCBLUNIT-ASSERTIONS  PIC 9(9) COMP.
           05  GCBLUNIT-FAILURES    PIC 9(9) COMP.
       PROCEDURE DIVISION.
      * WHY - Assumption: GnuCOBOL zero-initializes EXTERNAL storage
      * on first reference, so an explicit reset is normally
      * unnecessary.  This unit exists only as a belt-and-suspenders
      * entry a test main may CALL first when it wants to be explicit
      * about the starting state (for example, if two test bodies are
      * ever run within one process).
           MOVE 0 TO GCBLUNIT-ASSERTIONS
           MOVE 0 TO GCBLUNIT-FAILURES
           GOBACK.
       END PROGRAM gcblunit-init.

      **************************************************************
      * UNIT: gcblunit-junit                                       *
      * PURPOSE:                                                   *
      *   Write a JUnit-XML report for THIS test executable so a   *
      *   CI system can ingest COBOL unit results next to the      *
      *   pytest reports.  One file, one <testsuite>, one          *
      *   <testcase>, driven only by the shared EXTERNAL counters  *
      *   - no per-assertion detail is stored, so operand CONTENT  *
      *   can never reach the report (MA-13-safe by construction). *
      * PARAMETERS: none; reads two environment variables:         *
      *   GCBLUNIT_JUNIT : output file path.  If unset/empty the   *
      *                    unit no-ops (the report is opt-in).     *
      *   GCBLUNIT_SUITE : suite/testcase name; defaults to        *
      *                    "GCBLUnit" when unset.                  *
      * RETURNS: none.  Writes at most one file; on OPEN failure   *
      *   it reports to SYSERR and returns without abending so a   *
      *   reporting problem never masks the test result itself.    *
      * WHY - Trade-off: failures is reported at TESTCASE          *
      *   granularity (0 or 1) to stay xunit2-consistent           *
      *   (tests=1), while the EXACT failed-assertion count is     *
      *   preserved in the <failure> message and summary line.     *
      **************************************************************
       IDENTIFICATION DIVISION.
       PROGRAM-ID. gcblunit-junit.
       ENVIRONMENT DIVISION.
       INPUT-OUTPUT SECTION.
       FILE-CONTROL.
           SELECT junit-out ASSIGN TO JUNITDD
               ORGANIZATION IS LINE SEQUENTIAL
               FILE STATUS IS junit-status.
       DATA DIVISION.
       FILE SECTION.
       FD  junit-out.
       01  junit-line       PIC X(512).
       WORKING-STORAGE SECTION.
       01  GCBLUNIT-COUNTERS EXTERNAL.
           05  GCBLUNIT-ASSERTIONS  PIC 9(9) COMP.
           05  GCBLUNIT-FAILURES    PIC 9(9) COMP.
       01  junit-path       PIC X(256) VALUE SPACES.
       01  junit-dd-name    PIC X(256) VALUE SPACES.
       01  junit-dd-len     PIC 9(4)   COMP VALUE 0.
       01  junit-status     PIC X(2)   VALUE '00'.
       01  ws-suite         PIC X(128) VALUE SPACES.
       01  ws-edit-assert   PIC ZZZZZZZZ9.
       01  ws-edit-fail     PIC ZZZZZZZZ9.
       01  ws-suite-fail    PIC 9      VALUE 0.
       PROCEDURE DIVISION.
      * WHY - Assumption: GnuCOBOL binds a data-name ASSIGN to the
      * runtime CONTENT of that item (dynamic assignment), mirroring
      * upstream GCBLUnit's own 'assign to junit-file'.  The path
      * comes from the environment so the runner can give each
      * parallel test process its own report file.
           ACCEPT junit-path FROM ENVIRONMENT "GCBLUNIT_JUNIT"
           IF junit-path = SPACES
               GOBACK
           END-IF
           ACCEPT ws-suite FROM ENVIRONMENT "GCBLUNIT_SUITE"
           IF ws-suite = SPACES
               MOVE "GCBLUnit" TO ws-suite
           END-IF
           IF GCBLUNIT-FAILURES > 0
               MOVE 1 TO ws-suite-fail
           ELSE
               MOVE 0 TO ws-suite-fail
           END-IF
           MOVE GCBLUNIT-ASSERTIONS TO ws-edit-assert
           MOVE GCBLUNIT-FAILURES   TO ws-edit-fail
      * WHY - ibm-strict rejects ASSIGN...DYNAMIC and treats a data-
      * name ASSIGN as an IBM DD / environment name (not the item's
      * content), so the path cannot be bound directly from junit-
      * path.  Instead the DD word JUNITDD is mapped at runtime to the
      * requested path via DISPLAY UPON ENVIRONMENT-NAME / VALUE -
      * GnuCOBOL's supported strict-dialect idiom, the same env-var
      * binding CardDemo uses for DALYTRAN / TRANFILE et al.  The path
      * is reference-modified to its trimmed length so the DD value
      * carries no trailing blanks.
           MOVE FUNCTION TRIM(junit-path) TO junit-dd-name
           COMPUTE junit-dd-len =
               FUNCTION LENGTH(FUNCTION TRIM(junit-path))
           END-COMPUTE
           DISPLAY "JUNITDD" UPON ENVIRONMENT-NAME
           DISPLAY junit-dd-name(1:junit-dd-len)
               UPON ENVIRONMENT-VALUE
           OPEN OUTPUT junit-out
           IF junit-status NOT = "00"
               DISPLAY "GCBLUnit: cannot write JUnit report "
                   FUNCTION TRIM(junit-path)
                   " (status " junit-status ")" UPON SYSERR
               GOBACK
           END-IF
           MOVE '<?xml version="1.0" encoding="UTF-8"?>'
               TO junit-line
           WRITE junit-line END-WRITE
           MOVE "<testsuites>" TO junit-line
           WRITE junit-line END-WRITE
           MOVE SPACES TO junit-line
           STRING '  <testsuite name="'
                   DELIMITED BY SIZE
               FUNCTION TRIM(ws-suite) DELIMITED BY SIZE
               '" tests="1" assertions="'
                   DELIMITED BY SIZE
               FUNCTION TRIM(ws-edit-assert) DELIMITED BY SIZE
               '" failures="'
                   DELIMITED BY SIZE
               ws-suite-fail DELIMITED BY SIZE
               '" errors="0" skipped="0">'
                   DELIMITED BY SIZE
               INTO junit-line
           END-STRING
           WRITE junit-line END-WRITE
           MOVE SPACES TO junit-line
           STRING '    <testcase name="'
                   DELIMITED BY SIZE
               FUNCTION TRIM(ws-suite) DELIMITED BY SIZE
               '" classname="'
                   DELIMITED BY SIZE
               FUNCTION TRIM(ws-suite) DELIMITED BY SIZE
               '" assertions="'
                   DELIMITED BY SIZE
               FUNCTION TRIM(ws-edit-assert) DELIMITED BY SIZE
               '">'
                   DELIMITED BY SIZE
               INTO junit-line
           END-STRING
           WRITE junit-line END-WRITE
           IF GCBLUNIT-FAILURES > 0
               MOVE SPACES TO junit-line
               STRING '      <failure type="assertion" message="'
                   DELIMITED BY SIZE
                   FUNCTION TRIM(ws-edit-fail) DELIMITED BY SIZE
                   ' of ' DELIMITED BY SIZE
                   FUNCTION TRIM(ws-edit-assert) DELIMITED BY SIZE
                   ' assertion(s) failed">'
                       DELIMITED BY SIZE
                   INTO junit-line
               END-STRING
               WRITE junit-line END-WRITE
               MOVE "      </failure>" TO junit-line
               WRITE junit-line END-WRITE
           END-IF
           MOVE "    </testcase>" TO junit-line
           WRITE junit-line END-WRITE
           MOVE "  </testsuite>" TO junit-line
           WRITE junit-line END-WRITE
           MOVE "</testsuites>" TO junit-line
           WRITE junit-line END-WRITE
           CLOSE junit-out
           GOBACK.
       END PROGRAM gcblunit-junit.
