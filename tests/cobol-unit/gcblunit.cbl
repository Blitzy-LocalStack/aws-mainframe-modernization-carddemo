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
      * PROVENANCE:                                                 *
      *   GCBLUnit 1.22.6 (Olegs Kunicins, GPL).  Vendored per       *
      *   tests/import.json ("gcblunit":"1.22.6") and declared in    *
      *   tests/cobolget.json (dependency gcblunit ^1.22.6,          *
      *   dialect gnucobol).                                         *
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
           ADD 1 TO GCBLUNIT-ASSERTIONS
           IF LK-EXPECTED NOT = LK-ACTUAL
               ADD 1 TO GCBLUNIT-FAILURES
      * WHY - Assumption: DISPLAY renders the operands as raw bytes;
      * for alphanumeric operands this is human-readable, for packed
      * or binary operands it may be non-printable, but the
      * authoritative CI signal is the failure COUNT surfaced through
      * the exit code, not this best-effort diagnostic text.
               DISPLAY "F assert-equals: expected <"
                   LK-EXPECTED "> got <" LK-ACTUAL ">"
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
       LINKAGE SECTION.
       01  LK-UNEXPECTED  PIC X ANY LENGTH.
       01  LK-ACTUAL      PIC X ANY LENGTH.
       PROCEDURE DIVISION USING LK-UNEXPECTED LK-ACTUAL.
           ADD 1 TO GCBLUNIT-ASSERTIONS
      * WHY - Refactoring Rationale: this is the exact logical mirror
      * of assert-equals (fail-on-equal instead of fail-on-differ),
      * kept as its own unit so tests read with the same two-operand
      * GCBLUnit vocabulary rather than a mode flag on one assertion.
           IF LK-UNEXPECTED = LK-ACTUAL
               ADD 1 TO GCBLUNIT-FAILURES
               DISPLAY "F assert-notequals: value should differ "
                   "from <" LK-ACTUAL ">"
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
           MOVE GCBLUNIT-FAILURES TO LK-RESULT
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
