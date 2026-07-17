      ******************************************************************
      * PROGRAM : CSUTLDTC-test
      *
      * PURPOSE :
      *   GCBLUnit unit test that drives the CardDemo date-validation
      *   utility app/cbl/CSUTLDTC.cbl end-to-end.  CSUTLDTC is a clean
      *   callable subprogram (PROCEDURE DIVISION USING LS-DATE,
      *   LS-DATE-FORMAT, LS-RESULT), so this test executes the REAL
      *   program: it statically contains CSUTLDTC as a sibling unit
      *   and supplies a contained CEEDAYS stub in place of the IBM
      *   Language Environment date service that GnuCOBOL lacks.
      *
      * PARAMETERS :
      *   None.  Built as a `cobc -x` main; it takes no run-time
      *   arguments and reads no external files.
      *
      * RETURNS :
      *   RETURN-CODE = GCBLUnit accumulated failure count.  GnuCOBOL
      *   maps RETURN-CODE to the process exit status, so 0 => every
      *   assertion passed => scripts/run_unit_tests.sh marks this test
      *   PASS; a non-zero value (equal to the number of failed
      *   assertions) => FAIL.
      *
      * EXCEPTIONS :
      *   None raised.  Assertion failures are COUNTED by GCBLUnit, not
      *   thrown, so every scenario below always executes to completion.
      *
      * PROVENANCE :
      *   Consumes app/cbl/CSUTLDTC.cbl (REFERENCE, unmodified) and
      *   tests/cobol-unit/gcblunit.cbl (assertion framework, COPY'd).
      *
      * WHY - Alternatives Considered :
      *   Loading the separately built build/CSUTLDTC.so via
      *   COB_LIBRARY_PATH was REJECTED.  A dynamically loaded module
      *   resolves its own CALL "CEEDAYS" via COB_LIBRARY_PATH, where
      *   no CEEDAYS exists, and would NOT see this test's contained
      *   stub.  Static containment binds CALL "CEEDAYS" and
      *   CALL "CSUTLDTC" to the contained units at link time, which
      *   takes precedence over dynamic lookup.
      *
      * WHY - Assumptions :
      *   (1) The mask passed is always 'YYYY-MM-DD' (the CardDemo
      *       standard), so year=1-4, month=6-7, day=9-10.
      *   (2) The human-readable verdict lives at LS-RESULT(21:15), per
      *       the WS-MESSAGE layout inside CSUTLDTC.
      *   (3) Invalid tokens carry severity 3 (leading X'0003'); valid
      *       dates carry severity 0 (token X'00..00').
      *
      * WHY - Trade-offs :
      *   A minimal emulated CEEDAYS lets this test validate CSUTLDTC's
      *   feedback-to-verdict mapping and severity handling in isolation
      *   and deterministically.  Exhaustive Gregorian correctness is
      *   deferred to the real Language Environment at integration time.
      ******************************************************************
       IDENTIFICATION DIVISION.
       PROGRAM-ID. CSUTLDTC-test.
       DATA DIVISION.
       WORKING-STORAGE SECTION.
      * Arguments passed to CSUTLDTC on every scenario (match its
      * LINKAGE: two X(10) inputs and one X(80) result buffer).
       01 LS-DATE            PIC X(10).
       01 LS-DATE-FORMAT     PIC X(10).
       01 LS-RESULT          PIC X(80).
      * Observed vs expected severity.  Both are PIC S9(9) COMP so a
      * single byte-wise assert-equals is a valid value comparison
      * (identical PICTURE => identical bytes for equal values).
      * WHY - MA-19 (truncation elimination): CSUTLDTC returns severity
      * in the RETURN-CODE special register, which GnuCOBOL defines as
      * USAGE BINARY-LONG.  Capturing it into a narrower S9(4) field
      * raised -Wpossible-truncate; S9(9) COMP matches BINARY-LONG's
      * width, so the capture is loss-free and warning-free.  The value
      * domain (CEEDAYS severity 0-4095) fits either width; this change
      * removes the false-positive truncation without altering any
      * asserted value.
       01 WS-OBS-SEV         PIC S9(9) COMP.
       01 WS-EXP-SEV         PIC S9(9) COMP.
      * Unsigned copy of the observed severity for a clean numeric trace
      * line.  WHY: 9(9) matches WS-OBS-SEV's 9-digit width so the trace
      * MOVE is truncation-free too; an edited copy also avoids printing
      * the sign glyph a signed DISPLAY would emit.
       01 WS-SEV-DISP        PIC 9(9).
      * Observed vs expected verdict text.  X(15) matches WS-RESULT in
      * CSUTLDTC; both sides space-pad deterministically to 15 bytes.
       01 WS-OBS-MSG         PIC X(15).
       01 WS-EXP-MSG         PIC X(15).
      * Receives the GCBLUnit failure count.  PIC S9(9) COMP EXACTLY
      * matches the gcblunit-result LINKAGE contract; a mismatched
      * PICTURE here would misread the binary count as zoned digits.
       01 WS-FAILS           PIC S9(9) COMP.
       PROCEDURE DIVISION.
      * Reset the shared GCBLUnit counters up front.  WHY (Assumption):
      * EXTERNAL storage is zero-initialised by GnuCOBOL, so this is
      * belt-and-suspenders that also documents the test lifecycle.
           CALL "gcblunit-init"
           END-CALL
      * The mask is invariant across every scenario, so it is set once.
           MOVE "YYYY-MM-DD"      TO LS-DATE-FORMAT

      * ---- Scenario 1: plain valid date (ordinary happy path) --------
           MOVE "2022-07-19"      TO LS-DATE
           MOVE 0                 TO WS-EXP-SEV
           MOVE "Date is valid"   TO WS-EXP-MSG
           PERFORM RUN-ONE-CASE

      * ---- Scenario 2: valid leap day, year divisible by 4 -----------
           MOVE "2024-02-29"      TO LS-DATE
           MOVE 0                 TO WS-EXP-SEV
           MOVE "Date is valid"   TO WS-EXP-MSG
           PERFORM RUN-ONE-CASE

      * ---- Scenario 3: valid leap day, century year divisible by 400 -
           MOVE "2000-02-29"      TO LS-DATE
           MOVE 0                 TO WS-EXP-SEV
           MOVE "Date is valid"   TO WS-EXP-MSG
           PERFORM RUN-ONE-CASE

      * ---- Scenario 4: invalid Feb 29 in a non-leap year -------------
           MOVE "2023-02-29"      TO LS-DATE
           MOVE 3                 TO WS-EXP-SEV
           MOVE "Datevalue error" TO WS-EXP-MSG
           PERFORM RUN-ONE-CASE

      * ---- Scenario 5: invalid Feb 29, century year not div by 400 ---
           MOVE "2100-02-29"      TO LS-DATE
           MOVE 3                 TO WS-EXP-SEV
           MOVE "Datevalue error" TO WS-EXP-MSG
           PERFORM RUN-ONE-CASE

      * ---- Scenario 6: month above the valid range (13) --------------
           MOVE "2024-13-01"      TO LS-DATE
           MOVE 3                 TO WS-EXP-SEV
           MOVE "Invalid month"   TO WS-EXP-MSG
           PERFORM RUN-ONE-CASE

      * ---- Scenario 7: month below the valid range (00) --------------
           MOVE "2024-00-10"      TO LS-DATE
           MOVE 3                 TO WS-EXP-SEV
           MOVE "Invalid month"   TO WS-EXP-MSG
           PERFORM RUN-ONE-CASE

      * ---- Scenario 8: day beyond the month length (April 31) --------
           MOVE "2024-04-31"      TO LS-DATE
           MOVE 3                 TO WS-EXP-SEV
           MOVE "Datevalue error" TO WS-EXP-MSG
           PERFORM RUN-ONE-CASE

      * ---- Scenario 9: boundary valid day (Jan 31) -------------------
           MOVE "2024-01-31"      TO LS-DATE
           MOVE 0                 TO WS-EXP-SEV
           MOVE "Date is valid"   TO WS-EXP-MSG
           PERFORM RUN-ONE-CASE

      * ---- Scenario 10: day below the valid range (00) ---------------
           MOVE "2024-01-00"      TO LS-DATE
           MOVE 3                 TO WS-EXP-SEV
           MOVE "Datevalue error" TO WS-EXP-MSG
           PERFORM RUN-ONE-CASE

      * ---- Scenario 11: non-numeric character in the year field ------
           MOVE "20X4-02-01"      TO LS-DATE
           MOVE 3                 TO WS-EXP-SEV
           MOVE "Nonnumeric data" TO WS-EXP-MSG
           PERFORM RUN-ONE-CASE

      * Emit a readable tally, publish the failure count, and turn it
      * into the process exit code (the sole CI pass/fail signal).
           CALL "gcblunit-summary"
           END-CALL
           CALL "gcblunit-result" USING WS-FAILS
           END-CALL
           MOVE WS-FAILS          TO RETURN-CODE
           STOP RUN.

       RUN-ONE-CASE.
      * Purpose :
      *   Execute CSUTLDTC once for the currently staged LS-DATE and
      *   assert both observable outputs against the staged expectations
      *   (WS-EXP-SEV, WS-EXP-MSG).
      * Parameters (by shared WORKING-STORAGE, set before the PERFORM):
      *   LS-DATE     PIC X(10)  - the date under test.
      *   WS-EXP-SEV  PIC S9(4)  - expected severity (0 or 3)
      *   WS-EXP-MSG  PIC X(15)  - expected verdict text.
      * Returns : none (records pass/fail into the GCBLUnit counters).
           MOVE SPACES TO LS-RESULT
           CALL "CSUTLDTC" USING LS-DATE LS-DATE-FORMAT LS-RESULT
           END-CALL
      * WHY: capture RETURN-CODE right after CSUTLDTC returns,
      * before any other CALL. RETURN-CODE is a shared special
      * register the assert CALLs would otherwise overwrite.
           MOVE RETURN-CODE       TO WS-OBS-SEV
           MOVE LS-RESULT(21:15)  TO WS-OBS-MSG
      * WHY (Assumption): the verdict text occupies bytes 21-35 of the
      * WS-MESSAGE buffer CSUTLDTC returns through LS-RESULT.
           MOVE WS-OBS-SEV        TO WS-SEV-DISP
           DISPLAY "  case " LS-DATE " sev=" WS-SEV-DISP
               " verdict=<" WS-OBS-MSG ">"
           CALL "assert-equals" USING WS-EXP-SEV WS-OBS-SEV
           END-CALL
           CALL "assert-equals" USING WS-EXP-MSG WS-OBS-MSG

           END-CALL.
       END PROGRAM CSUTLDTC-test.

      ******************************************************************
      * Framework units (assert-equals, assert-notequals,
      * gcblunit-result, gcblunit-summary, gcblunit-init).  Each carries
      * its own END PROGRAM, so splicing them here as sibling units is
      * safe.  WHY: -I tests/cobol-unit supplies this member and the
      * quoted literal keeps the name exact on a case-sensitive path.
      ******************************************************************
       COPY "gcblunit.cbl".

      ******************************************************************
      * PROGRAM : CEEDAYS  (contained test stub)
      *
      * PURPOSE :
      *   Emulate just enough of the IBM Language Environment CEEDAYS
      *   date service to drive every EVALUATE branch CSUTLDTC selects
      *   on.  It parses the 'YYYY-MM-DD' date handed over in the first
      *   variable-length-string argument and returns a feedback token.
      *
      * PARAMETERS (positional, matching CSUTLDTC's CALL) :
      *   LK-DATE  - variable-length string: 2-byte length + text; the
      *              date is text(1:10).
      *   LK-FMT   - variable-length string (the mask); not inspected.
      *   LK-LILL  - S9(9) BINARY Lillian day count (output, unused by
      *              CSUTLDTC; set to 0 here).
      *   LK-FB    - 8-byte feedback token + trailing S9(9) BINARY.
      *
      * RETURNS :
      *   LK-FBTOK set to one of CSUTLDTC's documented tokens.
      *
      * EXCEPTIONS : none.
      *
      * WHY - the token mechanism :
      *   Moving the exact 8-byte token both sets the leading 2 severity
      *   bytes that CSUTLDTC reads (X'0003' => 3, X'0000' => 0) AND
      *   satisfies its EVALUATE `WHEN FC-...` byte comparison, so a
      *   single MOVE faithfully drives both the severity and the
      *   verdict-text branch.
      *
      * WHY - Trade-off :
      *   Days-in-month uses a compact table plus an explicit Gregorian
      *   leap rule rather than a full calendar library, which is all
      *   the scenarios require and keeps the stub deterministic.
      ******************************************************************
       IDENTIFICATION DIVISION.
       PROGRAM-ID. CEEDAYS.
       DATA DIVISION.
       WORKING-STORAGE SECTION.
      * Extracted date components (text form for the numeric class test,
      * numeric form for the range checks).
       01 WS-YR-X            PIC X(4).
       01 WS-MO-X            PIC X(2).
       01 WS-DY-X            PIC X(2).
       01 WS-YR              PIC 9(4).
       01 WS-MO              PIC 9(2).
       01 WS-DY              PIC 9(2).
       01 WS-DIM             PIC 9(2).
      * Scratch quotient/remainders for the leap-year determination.
       01 WS-Q               PIC 9(6).
       01 WS-R4              PIC 9(4).
       01 WS-R100            PIC 9(4).
       01 WS-R400            PIC 9(4).
       LINKAGE SECTION.
      * Overlay the caller's variable-length strings: a 2-byte binary
      * length prefix (which CSUTLDTC sets to 10) followed by the text.
       01 LK-DATE.
           05 LK-DLEN        PIC S9(4) BINARY.
           05 LK-DTXT        PIC X(256).
       01 LK-FMT.
           05 LK-FLEN        PIC S9(4) BINARY.
           05 LK-FTXT        PIC X(256).
       01 LK-LILL            PIC S9(9) BINARY.
      * Feedback area: the 8-byte token CSUTLDTC's 88s test, plus the
      * trailing information field it declares but does not read here.
       01 LK-FB.
           05 LK-FBTOK       PIC X(8).
           05 LK-FBIS        PIC S9(9) BINARY.
       PROCEDURE DIVISION USING LK-DATE LK-FMT LK-LILL LK-FB.
      * The Lillian count is irrelevant to CSUTLDTC's verdict; zero it
      * so the argument is always defined.
           MOVE 0 TO LK-LILL
           MOVE LK-DTXT(1:4)      TO WS-YR-X
           MOVE LK-DTXT(6:2)      TO WS-MO-X
           MOVE LK-DTXT(9:2)      TO WS-DY-X
      * A non-digit anywhere in the numeric positions is reported first,
      * mirroring the real service's non-numeric-data feedback.
           IF WS-YR-X IS NOT NUMERIC
              OR WS-MO-X IS NOT NUMERIC
              OR WS-DY-X IS NOT NUMERIC
               MOVE X"000309D859C3C5C5" TO LK-FBTOK
           ELSE
               MOVE WS-YR-X       TO WS-YR
               MOVE WS-MO-X       TO WS-MO
               MOVE WS-DY-X       TO WS-DY
      * Month range is validated before the day, so an out-of-range
      * month never indexes the days-in-month decision.
               IF WS-MO < 1 OR WS-MO > 12
                   MOVE X"000309D559C3C5C5" TO LK-FBTOK
               ELSE
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
      * February: apply the Gregorian leap rule
      * (div by 4) AND (not div by 100 OR div by 400).
                       DIVIDE WS-YR BY 4
                           GIVING WS-Q REMAINDER WS-R4
                       END-DIVIDE
                       DIVIDE WS-YR BY 100
                           GIVING WS-Q REMAINDER WS-R100
                       END-DIVIDE
                       DIVIDE WS-YR BY 400
                           GIVING WS-Q REMAINDER WS-R400
                       END-DIVIDE
                       IF WS-R4 = 0
                          AND (WS-R100 NOT = 0 OR WS-R400 = 0)
                           MOVE 29 TO WS-DIM
                       ELSE
                           MOVE 28 TO WS-DIM
                       END-IF
                   END-EVALUATE
                   IF WS-DY < 1 OR WS-DY > WS-DIM
                       MOVE X"000309CC59C3C5C5" TO LK-FBTOK
                   ELSE
                       MOVE X"0000000000000000" TO LK-FBTOK
                   END-IF
               END-IF
           END-IF
           GOBACK.
       END PROGRAM CEEDAYS.

      ******************************************************************
      * Unit under test, statically contained LAST.  WHY: CSUTLDTC.cbl
      * has no END PROGRAM terminator, so it must be the final unit in
      * the file, where physical end-of-file closes it cleanly.  The
      * relative path resolves against the repo-root working directory
      * used by scripts/build_test_programs.sh.
      ******************************************************************
       COPY "app/cbl/CSUTLDTC.cbl".
