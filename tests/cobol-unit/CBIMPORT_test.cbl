      ***************************************************************
      * CBIMPORT_test.cbl - GCBLUnit test for app/cbl/CBIMPORT.cbl
      ***************************************************************
      * TYPE        : GCBLUnit spec-replica / reverse-map contract
      *               test (supplemental specification, MA-15 / MA-18).
      * Executes UUT: NO.  app/cbl/CBIMPORT.cbl is a monolithic, file-
      *               driven main (0000-MAIN OPENs an INDEXED EXPFILE
      *               plus six sequential output files).  This unit
      *               layer REPLICATES only the record-type dispatch and
      *               the customer reverse-map logic in WORKING-STORAGE
      *               as a SUPPLEMENTAL SPECIFICATION; it does NOT, and
      *               does not claim to, execute CBIMPORT.  The byte-
      *               identical export->import round-trip that DOES run
      *               the real program is owned by the integration layer
      *               (tests/integration/test_export_import.py).
      * PRIVACY (MA-13):
      *   All customer values seeded here are well-known SYNTHETIC
      *   placeholders (JOHN DOE / descending-digit non-issued SSN
      *   987654321 / DOB 1985-03-15) taken from the AWS CardDemo
      *   synthetic demo seeds - not real PII.  Assertions run through
      *   the vendored GCBLUnit, whose assert diagnostics are hardened
      *   to emit only operand LENGTHS and the first-diff OFFSET (never
      *   the operand bytes), so no complete SSN, name, DOB or
      *   government-ID is ever printed, even on a failing assertion.
      ***************************************************************
      * PURPOSE:
      *   Spec-encode the unit-testable core of CBIMPORT (Branch
      *   Migration Import): the 6-way record-type dispatch of
      *   2200-PROCESS-RECORD-BY-TYPE and the customer reverse-map of
      *   2300-PROCESS-CUSTOMER-RECORD (including the COMP and COMP-3
      *   decode back to DISPLAY), and DOCUMENT the no-op checksum gap
      *   in 3000-VALIDATE-IMPORT.  Byte-identical full round-trips are
      *   covered by tests/integration/test_export_import.py.
      * PARAMETERS:
      *   None.  This is a self-contained GCBLUnit main; it takes no
      *   run-time arguments and reads no files (see WHY below).
      * RETURNS:
      *   RETURN-CODE = the accumulated GCBLUnit failure count, so exit
      *   0 == PASS and any non-zero == the number of failed assertions
      *   (scripts/run_unit_tests.sh keys pass/fail off this exit code).
      * EXCEPTIONS / ERRORS:
      *   None are raised.  Assertion failures are counted, never
      *   thrown; the program always reaches STOP RUN.
      * NOTES:
      *   - app/cbl/CBIMPORT.cbl and app/cpy/*.cpy are REFERENCE
      *     only and are NEVER modified by this test.
      *   - CBIMPORT's abend path (CALL 'CEE3ABD' in 9999) is
      *     environment-bounded (LE) and is NOT exercised here.
      *   - No file I/O at the unit layer: the dispatch and
      *     reverse-map logic is replicated in WORKING-STORAGE, so
      *     it runs with no EXPFILE input and no output files.
      ***************************************************************
      * WHY - Alternatives Considered:
      *   Driving the real file-based main (0000-MAIN: 1000-INIT ->
      *   2000-PROCESS-EXPORT-FILE -> 3000 -> 4000) was REJECTED for a
      *   unit test: it OPENs an INDEXED EXPFILE and six sequential
      *   output files and would need a staged export dataset plus a
      *   temp workspace - that is the integration layer's job.  Here
      *   we replicate only the record routing and field mapping.
      ***************************************************************
       IDENTIFICATION DIVISION.
       PROGRAM-ID. CBIMPORT-test.
       DATA DIVISION.
       WORKING-STORAGE SECTION.
      * WHY - Assumption: the record layouts are single-sourced from
      * app/cpy via -I; CUSTOMER-RECORD and EXPORT-RECORD are COPY'd,
      * never re-declared, so this test asserts against the SAME bytes
      * the production program maps.  Only the customer path is
      * exercised at the unit layer, so the four sibling copybooks
      * (CVACT01Y/CVACT03Y/CVTRA05Y/CVACT02Y) that CBIMPORT also COPYs
      * are intentionally NOT included here.
       COPY CVCUS01Y.
       COPY CVEXPORT.
      * Dispatch proxy: WS-HANDLER receives the branch code 2200 would
      * select; WS-EXP-HANDLER carries the expected code per scenario.
       01  WS-HANDLER               PIC 9.
       01  WS-EXP-HANDLER           PIC 9.
      * Unknown-type tally mirroring WS-UNKNOWN-RECORD-TYPE-COUNT and
      * its expected value after one unknown record is processed.
       01  WS-UNKNOWN-COUNT         PIC 9(09) VALUE 0.
       01  WS-EXP-UNKNOWN           PIC 9(09) VALUE 1.
      * Expected customer fields.  WHY (Trade-off): each expected item
      * shares the target field's PICTURE and USAGE so the framework's
      * byte-wise assert-equals equals value-equality; the DISPLAY
      * types here are what prove the COMP / COMP-3 decode.
       01  WS-EXP-CUST-ID           PIC 9(09) VALUE 123456789.
       01  WS-EXP-FIRST-NAME        PIC X(25) VALUE 'JOHN'.
       01  WS-EXP-LAST-NAME         PIC X(25) VALUE 'DOE'.
       01  WS-EXP-SSN               PIC 9(09) VALUE 987654321.
       01  WS-EXP-DOB               PIC X(10) VALUE '1985-03-15'.
       01  WS-EXP-FICO              PIC 9(03) VALUE 789.
      * Byte-identical 01-level "actual" mirrors for the decoded
      * CUSTOMER-RECORD fields.  WHY (Refactoring Rationale, MA-19):
      * GCBLUnit's assert-equals is a CALL, and GnuCOBOL under
      * -Wall -Wextra rejects a 05-level copybook sub-item passed BY
      * REFERENCE ("not a 01 or 77 level item", -Wcall-params).  The
      * decoded CUST-* fields are 05-level, so each is copied into its
      * same-PIC/USAGE 01-level mirror immediately before the assertion
      * (a byte-preserving MOVE).  Alternatives Considered: suppressing
      * with -Wno-call-params was REJECTED - the reviewer asked for a
      * code fix, not a silenced diagnostic.
       01  WS-ACT-CUST-ID           PIC 9(09).
       01  WS-ACT-FIRST-NAME        PIC X(25).
       01  WS-ACT-LAST-NAME         PIC X(25).
       01  WS-ACT-SSN               PIC 9(09).
       01  WS-ACT-DOB               PIC X(10).
       01  WS-ACT-FICO              PIC 9(03).
      * Receives the failure count from gcblunit-result (S9(9) COMP per
      * the framework contract) before it is mapped onto RETURN-CODE.
       01  WS-RESULT                PIC S9(9) COMP VALUE 0.
       PROCEDURE DIVISION.
      * ============================================================
      * 0000-MAIN : entry unit - runs the three scenarios in order,
      * then publishes the failure count as the process exit code.
      * PARAMS  : none (standalone cobc -x main; no LINKAGE / run args).
      * RETURNS : sets RETURN-CODE to the accumulated assertion-failure
      *           count (0 => CI PASS; non-0 => CI FAIL).
      * ERRORS  : none raised; GCBLUnit counts failures, never abends.
      * WHY - Trade-off: the failure count is moved to RETURN-CODE (not
      *           merely DISPLAYed) because scripts/run_unit_tests.sh
      *           keys pass/fail off the process exit code, so the tally
      *           MUST reach the exit status to be observable in CI.
      * ============================================================
       0000-MAIN.
      * Scenario 1 - record-type dispatch (spec-encodes the 2200
      * EVALUATE, all six branches).
      * WHY - Assumption: WS-HANDLER is a test-local proxy for which
      * paragraph 2200 selects; encoding the routing this way pins the
      * dispatch contract without needing the file-driven 2300..2700
      * paragraphs (which perform WRITEs).
           MOVE 'C' TO EXPORT-REC-TYPE
           PERFORM 2200-ENCODED
           MOVE 1 TO WS-EXP-HANDLER
           CALL 'assert-equals' USING WS-EXP-HANDLER WS-HANDLER
           END-CALL
           MOVE 'A' TO EXPORT-REC-TYPE
           PERFORM 2200-ENCODED
           MOVE 2 TO WS-EXP-HANDLER
           CALL 'assert-equals' USING WS-EXP-HANDLER WS-HANDLER
           END-CALL
           MOVE 'X' TO EXPORT-REC-TYPE
           PERFORM 2200-ENCODED
           MOVE 3 TO WS-EXP-HANDLER
           CALL 'assert-equals' USING WS-EXP-HANDLER WS-HANDLER
           END-CALL
           MOVE 'T' TO EXPORT-REC-TYPE
           PERFORM 2200-ENCODED
           MOVE 4 TO WS-EXP-HANDLER
           CALL 'assert-equals' USING WS-EXP-HANDLER WS-HANDLER
           END-CALL
           MOVE 'D' TO EXPORT-REC-TYPE
           PERFORM 2200-ENCODED
           MOVE 5 TO WS-EXP-HANDLER
           CALL 'assert-equals' USING WS-EXP-HANDLER WS-HANDLER
           END-CALL
      * 'Q' stands in for ANY value other than C/A/X/T/D (WHEN OTHER).
           MOVE 'Q' TO EXPORT-REC-TYPE
           PERFORM 2200-ENCODED
           MOVE 6 TO WS-EXP-HANDLER
           CALL 'assert-equals' USING WS-EXP-HANDLER WS-HANDLER
           END-CALL
      * Scenario 2 - customer reverse-map round-trip (spec 2300).
      * WHY - Trade-off: asserting the DISPLAY result of the binary
      * (COMP) id and the packed (COMP-3) FICO score proves the import
      * is the exact inverse of CBEXPORT's encoding - the round-trip
      * integrity the AAP requires (0.5.2).  A representative subset
      * (id, names, ssn, dob, fico) is asserted rather than all 17
      * mapped fields, to keep the COMP/COMP-3 decode evidence sharp;
      * the full byte-identical round-trip is an integration concern.
           MOVE 'C' TO EXPORT-REC-TYPE
           MOVE 123456789 TO EXP-CUST-ID
           MOVE 'JOHN' TO EXP-CUST-FIRST-NAME
           MOVE 'DOE' TO EXP-CUST-LAST-NAME
           MOVE 987654321 TO EXP-CUST-SSN
           MOVE '1985-03-15' TO EXP-CUST-DOB-YYYY-MM-DD
           MOVE 789 TO EXP-CUST-FICO-CREDIT-SCORE
           PERFORM 2300-ENCODED
      * Copy each decoded 05-level field into its 01-level mirror before
      * the CALL (see WS-ACT-* block for the MA-19 rationale); every MOVE
      * is a same-PIC/USAGE byte-for-byte copy, so the compared value is
      * identical to the decoded field.
           MOVE CUST-ID TO WS-ACT-CUST-ID
           CALL 'assert-equals' USING WS-EXP-CUST-ID WS-ACT-CUST-ID
           END-CALL
           MOVE CUST-FIRST-NAME TO WS-ACT-FIRST-NAME
           CALL 'assert-equals' USING WS-EXP-FIRST-NAME
               WS-ACT-FIRST-NAME
           END-CALL
           MOVE CUST-LAST-NAME TO WS-ACT-LAST-NAME
           CALL 'assert-equals' USING WS-EXP-LAST-NAME WS-ACT-LAST-NAME
           END-CALL
           MOVE CUST-SSN TO WS-ACT-SSN
           CALL 'assert-equals' USING WS-EXP-SSN WS-ACT-SSN
           END-CALL
           MOVE CUST-DOB-YYYY-MM-DD TO WS-ACT-DOB
           CALL 'assert-equals' USING WS-EXP-DOB WS-ACT-DOB
           END-CALL
           MOVE CUST-FICO-CREDIT-SCORE TO WS-ACT-FICO
           CALL 'assert-equals' USING WS-EXP-FICO WS-ACT-FICO
           END-CALL
      * Scenario 3 - unknown-record tally (spec-encodes 2700) plus the
      * documented VALIDATE gap.
      * WHY - Assumption: 2700-ENCODED mirrors the single observable
      * side effect of 2700-PROCESS-UNKNOWN-RECORD - ADD 1 to the
      * unknown-type counter (the error-file WRITE is a file concern).
      ***************************************************************
      * DOCUMENTED GAP (AAP 0.10.1 audit trail):
      *   Source paragraph 3000-VALIDATE-IMPORT is a NO-OP.  It only
      *   DISPLAYs 'Import validation completed' and 'No validation
      *   errors detected' - it computes NO checksum, even though the
      *   CBIMPORT header advertises 'Validate data integrity using
      *   checksums.'  No assertion can cover behaviour that does not
      *   exist, so this comment records the discrepancy in the test
      *   suite rather than silently accepting it.
      ***************************************************************
           MOVE 'Q' TO EXPORT-REC-TYPE
           PERFORM 2700-ENCODED
           CALL 'assert-equals' USING WS-EXP-UNKNOWN WS-UNKNOWN-COUNT
           END-CALL
      * Emit a readable tally into the CI log, then bridge the failure
      * count onto RETURN-CODE (GnuCOBOL maps it to the exit status).
           CALL 'gcblunit-summary'
           END-CALL
           CALL 'gcblunit-result' USING WS-RESULT
           END-CALL
           MOVE WS-RESULT TO RETURN-CODE
           STOP RUN.
      * ============================================================
      * 2200-ENCODED : replica of source 2200-PROCESS-RECORD-BY-TYPE.
      * Sets WS-HANDLER to the branch code the dispatcher would pick.
      * PARAMS  : none (internal paragraph; reads EXPORT-REC-TYPE).
      * RETURNS : none (sets the shared WS-HANDLER proxy in place).
      * ERRORS  : none raised.
      * ============================================================
       2200-ENCODED.
           EVALUATE EXPORT-REC-TYPE
               WHEN 'C' MOVE 1 TO WS-HANDLER
               WHEN 'A' MOVE 2 TO WS-HANDLER
               WHEN 'X' MOVE 3 TO WS-HANDLER
               WHEN 'T' MOVE 4 TO WS-HANDLER
               WHEN 'D' MOVE 5 TO WS-HANDLER
               WHEN OTHER MOVE 6 TO WS-HANDLER
           END-EVALUATE.
      * ============================================================
      * 2300-ENCODED : replica of source 2300-PROCESS-CUSTOMER-RECORD
      * reverse map, minus the WRITE (a file-layer concern).
      * PARAMS  : none (internal paragraph; reads the EXP-CUST-* fields
      *           of EXPORT-RECORD).
      * RETURNS : none (populates the shared CUSTOMER-RECORD in place).
      * ERRORS  : none raised.
      * WHY - Assumption: the MOVE set mirrors source 2300 field-for-
      * field so the decode under test is the exact production inverse;
      * INITIALIZE clears CUSTOMER-RECORD first, exactly as 2300 does.
      * ============================================================
       2300-ENCODED.
           INITIALIZE CUSTOMER-RECORD
           MOVE EXP-CUST-ID TO CUST-ID
           MOVE EXP-CUST-FIRST-NAME TO CUST-FIRST-NAME
           MOVE EXP-CUST-MIDDLE-NAME TO CUST-MIDDLE-NAME
           MOVE EXP-CUST-LAST-NAME TO CUST-LAST-NAME
           MOVE EXP-CUST-ADDR-LINE(1) TO CUST-ADDR-LINE-1
           MOVE EXP-CUST-ADDR-LINE(2) TO CUST-ADDR-LINE-2
           MOVE EXP-CUST-ADDR-LINE(3) TO CUST-ADDR-LINE-3
           MOVE EXP-CUST-ADDR-STATE-CD TO CUST-ADDR-STATE-CD
           MOVE EXP-CUST-ADDR-COUNTRY-CD TO CUST-ADDR-COUNTRY-CD
           MOVE EXP-CUST-ADDR-ZIP TO CUST-ADDR-ZIP
           MOVE EXP-CUST-PHONE-NUM(1) TO CUST-PHONE-NUM-1
           MOVE EXP-CUST-PHONE-NUM(2) TO CUST-PHONE-NUM-2
           MOVE EXP-CUST-SSN TO CUST-SSN
           MOVE EXP-CUST-GOVT-ISSUED-ID TO CUST-GOVT-ISSUED-ID
           MOVE EXP-CUST-DOB-YYYY-MM-DD TO CUST-DOB-YYYY-MM-DD
           MOVE EXP-CUST-EFT-ACCOUNT-ID TO CUST-EFT-ACCOUNT-ID
           MOVE EXP-CUST-PRI-CARD-HOLDER-IND TO CUST-PRI-CARD-HOLDER-IND
           MOVE EXP-CUST-FICO-CREDIT-SCORE TO CUST-FICO-CREDIT-SCORE.
      * ============================================================
      * 2700-ENCODED : replica of source 2700-PROCESS-UNKNOWN-RECORD
      * one observable side effect - increment the unknown-type tally.
      * PARAMS  : none (internal paragraph).
      * RETURNS : none (increments the shared WS-UNKNOWN-COUNT tally).
      * ERRORS  : none raised.
      * ============================================================
       2700-ENCODED.
           ADD 1 TO WS-UNKNOWN-COUNT
           END-ADD.
       END PROGRAM CBIMPORT-test.
      * ============================================================
      * Vendored GCBLUnit framework units (assert-equals, gcblunit-*).
      * WHY - Assumption: COPY'd AFTER END PROGRAM so the framework
      * compiles as sibling units in the SAME cobc -x executable as
      * this test; the quoted member name resolves via -I
      * tests/cobol-unit at build time.
      * ============================================================
       COPY "gcblunit.cbl".
