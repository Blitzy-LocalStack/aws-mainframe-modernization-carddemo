       IDENTIFICATION DIVISION.
       PROGRAM-ID. CBEXPORT-test.
      ******************************************************************
      * PROGRAM     : CBEXPORT_test.cbl
      * APPLICATION : CardDemo automated test suite (COBOL unit layer)
      * TYPE        : GCBLUnit spec-replica / encoding-contract test
      *               (supplemental specification, per MA-15 / MA-18)
      * FRAMEWORK   : GCBLUnit 1.22.6 (COPY "gcblunit.cbl" siblings)
      * UNIT UNDER  : app/cbl/CBEXPORT.cbl  (REFERENCE ONLY - unchanged)
      * TARGET LOGIC: 2200-CREATE-CUSTOMER-EXP-REC (customer path)
      * Executes UUT: NO.  CBEXPORT is a monolithic, file-driven main
      *               (0000-MAIN-PROCESSING OPENs five indexed VSAM
      *               inputs plus an indexed output and abends on any
      *               OPEN failure).  This unit layer REPLICATES the
      *               pure customer record-formatting logic
      *               (2200-CREATE-CUSTOMER-EXP-REC) verbatim as a
      *               SUPPLEMENTAL SPECIFICATION and asserts the encoded
      *               field images; it does NOT, and does not claim to,
      *               execute CBEXPORT.  The byte-identical
      *               export->import round-trip that DOES run the real
      *               program is owned by the integration layer
      *               (tests/integration/test_export_import.py).
      *
      * PRIVACY (MA-13):
      *   All customer values seeded here are well-known SYNTHETIC
      *   placeholders (JOHN Q PUBLIC / 123 MAIN STREET / descending-
      *   digit non-issued SSN / DOB 1980-05-15) taken from the AWS
      *   CardDemo synthetic demo seeds - not real PII.  Assertions run
      *   through the vendored GCBLUnit, whose assert diagnostics are
      *   hardened to emit only operand LENGTHS and the first-diff
      *   OFFSET (never the operand bytes), so no complete SSN, name,
      *   DOB or government-ID is ever printed, even on failure.
      *
      * PURPOSE:
      *   Spec-encode the record-FORMATTING logic of CBEXPORT's
      *   customer-export path and assert that every observable output
      *   field is encoded exactly - with special emphasis on the
      *   COMP (binary) and COMP-3 (packed-decimal) fields, which are
      *   the highest-risk part of fixed-width export formatting
      *   (AAP 0.1.1 data-encoding integrity).  The paragraph
      *   2200-ENCODE-CUSTOMER-EXP-REC replicates the source header
      *   MOVEs and the full 18-field CUST-* -> EXP-CUST-* mapping
      *   verbatim, then the assertion paragraphs verify the result
      *   against typed expected items whose PICTURE/USAGE match each
      *   target field exactly.
      *
      * PARAMETERS : none (entry unit; no LINKAGE).
      * RETURNS    : RETURN-CODE = accumulated GCBLUnit failure count
      *              (0 => process exit 0 => PASS; non-zero => FAIL).
      * EXCEPTIONS : none raised; assertion failures are counted, never
      *              abended, so every assertion is evaluated each run.
      *
      * WHY - Alternatives Considered:
      *   Driving the real file-based main (0000-MAIN-PROCESSING) was
      *   REJECTED for the unit layer: it OPENs five indexed VSAM
      *   inputs (CUSTFILE/ACCTFILE/XREFFILE/TRANSACT/CARDFILE) plus an
      *   indexed output (EXPFILE) and abends via 9999-ABEND-PROGRAM if
      *   any OPEN fails.  Spec-encoding isolates the pure formatting
      *   logic with zero file dependencies; the byte-identical
      *   export->import round-trip is covered by the integration layer
      *   (tests/integration/test_export_import.py).
      *
      * WHY - Assumptions:
      *   A deterministic timestamp literal is injected in place of the
      *   source's 1050-GENERATE-TIMESTAMP, which builds the value from
      *   ACCEPT FROM DATE / ACCEPT FROM TIME and is therefore
      *   non-deterministic; wall-clock behaviour belongs to the
      *   integration layer, not to a reproducible unit assertion.
      *   Only the customer path is exercised here, so only CVCUS01Y
      *   and CVEXPORT are COPYd - the other four copybooks CBEXPORT
      *   uses (CVACT01Y/CVACT03Y/CVTRA05Y/CVACT02Y) are intentionally
      *   omitted because their export paths are out of this unit's
      *   scope.
      *
      * WHY - Trade-offs:
      *   A representative field subset covering every USAGE class
      *   (alphanumeric, zoned display, COMP binary, COMP-3 packed) is
      *   asserted rather than all 460 payload bytes: this pins the
      *   encoding contract for each distinct storage form while
      *   keeping the test readable and its failures diagnosable.
      ******************************************************************
       DATA DIVISION.
       WORKING-STORAGE SECTION.
      *
      * Single-source the SAME record layouts CBEXPORT compiles against
      * (resolved through -I app/cpy); never redefine them locally so
      * the test can never silently drift from the production contract.
       COPY CVCUS01Y.
       COPY CVEXPORT.
      *
      * Mirror of CBEXPORT's export-sequence counter.
      * WHY - Assumption: the source field WS-SEQUENCE-COUNTER is PIC
      * 9(09) DISPLAY, but its USAGE is immaterial to this test because
      * the assertion target EXPORT-SEQUENCE-NUM is PIC 9(9) COMP and a
      * MOVE preserves the numeric value across USAGE; COMP is declared
      * here to keep the counter and its assertion in one storage form.
       01  WS-SEQUENCE-COUNTER               PIC 9(9) COMP VALUE 0.
      *
      * Deterministic replacement for the source 26-byte timestamp.
       01  WS-FORMATTED-TIMESTAMP            PIC X(26)
               VALUE '2024-01-15 10:30:00.00'.
      *
      * Typed expected items - each shares its target field's exact
      * PICTURE and USAGE so GCBLUnit's byte-wise assert-equals is
      * equivalent to value-equality (framework contract).
      *
      * WHY - Refactoring Rationale (MA-19): every expected item is a
      * standalone 01-level item (NOT a 05 under a group) and every one
      * has a byte-identical 01-level "actual" mirror (WS-ACT-*) below.
      * GnuCOBOL under -Wall -Wextra rejects passing a group sub-item
      * BY REFERENCE to a CALL (-Wcall-params: "not a 01 or 77 level
      * item"); GCBLUnit's assert-equals IS a CALL, so BOTH operands of
      * every assertion must be 01/77.  The real EXPORT-* fields are
      * copybook sub-items, so each is copied into its same-PIC/USAGE
      * mirror immediately before the assertion (a byte-preserving
      * MOVE - the compared bytes are unchanged).  Alternatives
      * Considered: -Wno-call-params suppression was REJECTED - the
      * reviewer asked for a code fix, not a silenced diagnostic.
      *
      * WHY - Privacy (MA-13): every customer value below is a
      * well-known SYNTHETIC placeholder (JOHN Q PUBLIC / 123 MAIN
      * STREET / descending-digit non-issued SSN 987654321 / DOB
      * 1980-05-15) derived from the AWS CardDemo synthetic demo seeds -
      * it is NOT real PII.  In addition, the vendored GCBLUnit assert
      * diagnostics are hardened to print only operand LENGTHS and the
      * first-diff OFFSET (never the operand bytes), so no complete SSN,
      * name, DOB or government-ID is ever emitted at run time, even on
      * a failing assertion.
       01  WS-EXP-REC-TYPE       PIC X(1)  VALUE 'C'.
       01  WS-EXP-TIMESTAMP      PIC X(26) VALUE
               '2024-01-15 10:30:00.00'.
       01  WS-EXP-BRANCH-ID      PIC X(4)  VALUE '0001'.
       01  WS-EXP-REGION-CODE    PIC X(5)  VALUE 'NORTH'.
       01  WS-EXP-SEQUENCE-NUM   PIC 9(9) COMP VALUE 1.
       01  WS-EXP-CUST-ID        PIC 9(09) COMP VALUE 123456789.
       01  WS-EXP-FIRST-NAME     PIC X(25) VALUE 'JOHN'.
       01  WS-EXP-LAST-NAME      PIC X(25) VALUE 'PUBLIC'.
       01  WS-EXP-SSN            PIC 9(09) VALUE 987654321.
       01  WS-EXP-DOB            PIC X(10) VALUE '1980-05-15'.
       01  WS-EXP-FICO           PIC 9(03) COMP-3 VALUE 750.
       01  WS-EXP-ADDR-1         PIC X(50) VALUE
               '123 MAIN STREET'.
      *
      * Byte-identical 01-level "actual" mirrors (see WHY above): each
      * receives its EXPORT-* copybook field via a same-PIC/USAGE MOVE
      * immediately before the matching assert-equals CALL.
       01  WS-ACT-REC-TYPE       PIC X(1).
       01  WS-ACT-TIMESTAMP      PIC X(26).
       01  WS-ACT-BRANCH-ID      PIC X(4).
       01  WS-ACT-REGION-CODE    PIC X(5).
       01  WS-ACT-SEQUENCE-NUM   PIC 9(9) COMP.
       01  WS-ACT-CUST-ID        PIC 9(09) COMP.
       01  WS-ACT-FIRST-NAME     PIC X(25).
       01  WS-ACT-LAST-NAME      PIC X(25).
       01  WS-ACT-SSN            PIC 9(09).
       01  WS-ACT-DOB            PIC X(10).
       01  WS-ACT-FICO           PIC 9(03) COMP-3.
       01  WS-ACT-ADDR-1         PIC X(50).
      *
      * Record-length probe: LENGTH OF the copybook 01 must equal 500,
      * proving the single-sourced layout has not drifted.
       01  WS-EXP-RECORD-LEN                 PIC 9(4) VALUE 500.
       01  WS-ACT-RECORD-LEN                 PIC 9(4).
      *
      * Receives the GCBLUnit failure tally for the exit-code bridge.
       01  WS-RESULT                         PIC S9(9) COMP.
      *
       PROCEDURE DIVISION.
      *
      ******************************************************************
      * ROUTINE : 0000-MAIN
      * PURPOSE : Drive the customer-export spec in order - seed a
      *           synthetic CUSTOMER-RECORD, encode it into EXPORT-
      *           RECORD, assert the header and per-field mappings, then
      *           publish the GCBLUnit tally to the process exit status.
      * PARAMS  : none (standalone cobc -x main; no LINKAGE / run args).
      * RETURNS : sets RETURN-CODE to the accumulated assertion-failure
      *           count (0 => CI PASS; non-0 => CI FAIL).
      * ERRORS  : none raised; GCBLUnit counts failures, never abends.
      * WHY - Trade-off: the failure count is moved to RETURN-CODE (not
      *           merely DISPLAYed) because scripts/run_unit_tests.sh
      *           keys pass/fail purely off the process exit code, so the
      *           tally MUST reach the exit status to be observable.
      ******************************************************************
       0000-MAIN.
           PERFORM 1000-SETUP-CUSTOMER
           PERFORM 2200-ENCODE-CUSTOMER-EXP-REC
           PERFORM 3000-ASSERT-HEADER
           PERFORM 4000-ASSERT-CUSTOMER-MAPPING
           CALL 'gcblunit-summary'
           END-CALL
           CALL 'gcblunit-result' USING WS-RESULT
           END-CALL
           MOVE WS-RESULT TO RETURN-CODE
           STOP RUN.
      *
      ******************************************************************
      * 1000-SETUP-CUSTOMER : seed one representative CUSTOMER-RECORD.
      * PARAMS  : none (internal paragraph; seeds the COPYd CUSTOMER-
      *           RECORD in WORKING-STORAGE).
      * RETURNS : none (populates shared CUSTOMER-RECORD in place).
      * ERRORS  : none raised.
      * WHY - Assumption: values are chosen to exercise each USAGE
      * class - 9-digit CUST-ID / CUST-SSN (numeric), a 3-digit FICO
      * for the COMP-3 target, and alphanumeric name/address/date; all
      * are synthetic placeholders (see PRIVACY note in the header).
      ******************************************************************
       1000-SETUP-CUSTOMER.
           MOVE 123456789 TO CUST-ID
           MOVE 'JOHN' TO CUST-FIRST-NAME
           MOVE 'Q' TO CUST-MIDDLE-NAME
           MOVE 'PUBLIC' TO CUST-LAST-NAME
           MOVE '123 MAIN STREET' TO CUST-ADDR-LINE-1
           MOVE 'APT 4B' TO CUST-ADDR-LINE-2
           MOVE 'BUILDING C' TO CUST-ADDR-LINE-3
           MOVE 'NY' TO CUST-ADDR-STATE-CD
           MOVE 'USA' TO CUST-ADDR-COUNTRY-CD
           MOVE '10001' TO CUST-ADDR-ZIP
           MOVE '212-555-0100' TO CUST-PHONE-NUM-1
           MOVE '212-555-0199' TO CUST-PHONE-NUM-2
           MOVE 987654321 TO CUST-SSN
           MOVE 'DL-NY-12345' TO CUST-GOVT-ISSUED-ID
           MOVE '1980-05-15' TO CUST-DOB-YYYY-MM-DD
           MOVE 'EFT0012345' TO CUST-EFT-ACCOUNT-ID
           MOVE 'Y' TO CUST-PRI-CARD-HOLDER-IND
           MOVE 750 TO CUST-FICO-CREDIT-SCORE.
      *
      ******************************************************************
      * 2200-ENCODE-CUSTOMER-EXP-REC : verbatim replica of source
      * paragraph 2200-CREATE-CUSTOMER-EXP-REC (CBEXPORT lines ~271-
      * 299), EXCLUDING the WRITE, its file-status check, and the
      * statistics counters.
      * PARAMS  : none (internal paragraph; reads CUSTOMER-RECORD and
      *           WS-FORMATTED-TIMESTAMP, writes EXPORT-RECORD).
      * RETURNS : none (populates shared EXPORT-RECORD in place).
      * ERRORS  : none raised (the source WRITE/file-status path is
      *           deliberately omitted - see the Trade-off below).
      * WHY - Trade-off: the WRITE EXPORT-OUTPUT-RECORD and the
      * WS-EXPORT-OK guard are file I/O against the indexed EXPFILE;
      * omitting them isolates the in-memory formatting under test
      * while leaving the field-mapping logic byte-for-byte identical.
      ******************************************************************
       2200-ENCODE-CUSTOMER-EXP-REC.
           INITIALIZE EXPORT-RECORD
           MOVE 'C' TO EXPORT-REC-TYPE
           MOVE WS-FORMATTED-TIMESTAMP TO EXPORT-TIMESTAMP
           ADD 1 TO WS-SEQUENCE-COUNTER
           END-ADD
           MOVE WS-SEQUENCE-COUNTER TO EXPORT-SEQUENCE-NUM
           MOVE '0001' TO EXPORT-BRANCH-ID
           MOVE 'NORTH' TO EXPORT-REGION-CODE
           MOVE CUST-ID TO EXP-CUST-ID
           MOVE CUST-FIRST-NAME TO EXP-CUST-FIRST-NAME
           MOVE CUST-MIDDLE-NAME TO EXP-CUST-MIDDLE-NAME
           MOVE CUST-LAST-NAME TO EXP-CUST-LAST-NAME
           MOVE CUST-ADDR-LINE-1 TO EXP-CUST-ADDR-LINE(1)
           MOVE CUST-ADDR-LINE-2 TO EXP-CUST-ADDR-LINE(2)
           MOVE CUST-ADDR-LINE-3 TO EXP-CUST-ADDR-LINE(3)
           MOVE CUST-ADDR-STATE-CD TO EXP-CUST-ADDR-STATE-CD
           MOVE CUST-ADDR-COUNTRY-CD TO EXP-CUST-ADDR-COUNTRY-CD
           MOVE CUST-ADDR-ZIP TO EXP-CUST-ADDR-ZIP
           MOVE CUST-PHONE-NUM-1 TO EXP-CUST-PHONE-NUM(1)
           MOVE CUST-PHONE-NUM-2 TO EXP-CUST-PHONE-NUM(2)
           MOVE CUST-SSN TO EXP-CUST-SSN
           MOVE CUST-GOVT-ISSUED-ID TO EXP-CUST-GOVT-ISSUED-ID
           MOVE CUST-DOB-YYYY-MM-DD TO EXP-CUST-DOB-YYYY-MM-DD
           MOVE CUST-EFT-ACCOUNT-ID TO EXP-CUST-EFT-ACCOUNT-ID
           MOVE CUST-PRI-CARD-HOLDER-IND TO EXP-CUST-PRI-CARD-HOLDER-IND
           MOVE CUST-FICO-CREDIT-SCORE TO EXP-CUST-FICO-CREDIT-SCORE.
      *
      ******************************************************************
      * 3000-ASSERT-HEADER : fixed header + record-length invariants.
      * PARAMS  : none (internal paragraph; reads EXPORT-RECORD header
      *           fields and the WS-EXP-* / WS-ACT-* items).
      * RETURNS : none (increments the shared GCBLUnit counters via the
      *           assert-equals CALLs).
      * ERRORS  : none raised; a mismatch is counted, not abended.
      * WHY - Assumption: EXPORT-SEQUENCE-NUM is COMP, so its expected
      * counterpart is also PIC 9(9) COMP - byte-equality then proves
      * the binary encoding, not merely the display value.  Each actual
      * field is copied into a same-PIC/USAGE 01-level mirror before the
      * CALL (see the WS-ACT-* declaration block for the MA-19 reason).
      ******************************************************************
       3000-ASSERT-HEADER.
           MOVE FUNCTION LENGTH(EXPORT-RECORD) TO WS-ACT-RECORD-LEN
           CALL 'assert-equals' USING WS-EXP-RECORD-LEN
               WS-ACT-RECORD-LEN
           END-CALL
           MOVE EXPORT-REC-TYPE TO WS-ACT-REC-TYPE
           CALL 'assert-equals' USING WS-EXP-REC-TYPE WS-ACT-REC-TYPE
           END-CALL
           MOVE EXPORT-TIMESTAMP TO WS-ACT-TIMESTAMP
           CALL 'assert-equals' USING WS-EXP-TIMESTAMP WS-ACT-TIMESTAMP
           END-CALL
           MOVE EXPORT-BRANCH-ID TO WS-ACT-BRANCH-ID
           CALL 'assert-equals' USING WS-EXP-BRANCH-ID WS-ACT-BRANCH-ID
           END-CALL
           MOVE EXPORT-REGION-CODE TO WS-ACT-REGION-CODE
           CALL 'assert-equals' USING WS-EXP-REGION-CODE
               WS-ACT-REGION-CODE
           END-CALL
           MOVE EXPORT-SEQUENCE-NUM TO WS-ACT-SEQUENCE-NUM
           CALL 'assert-equals' USING WS-EXP-SEQUENCE-NUM
               WS-ACT-SEQUENCE-NUM
           END-CALL.
      *
      ******************************************************************
      * 4000-ASSERT-CUSTOMER-MAPPING : per-field encoding assertions.
      * PARAMS  : none (internal paragraph; reads the EXP-CUST-* fields
      *           and the WS-EXP-* / WS-ACT-* items).
      * RETURNS : none (increments the shared GCBLUnit counters via the
      *           assert-equals CALLs).
      * ERRORS  : none raised; a mismatch is counted, not abended.
      * WHY - Trade-off: EXP-CUST-ID (COMP) and EXP-CUST-FICO-CREDIT-
      * SCORE (COMP-3) are asserted with matching typed expecteds to
      * validate binary and packed-decimal encoding; EXP-CUST-SSN
      * (zoned 9(09)) and the X(nn) fields cover the display classes;
      * one OCCURS element (EXP-CUST-ADDR-LINE(1)) proves subscripted
      * table mapping.  Each actual field is copied into a same-PIC/
      * USAGE 01-level mirror before the CALL (MA-19; see WS-ACT-*).
      ******************************************************************
       4000-ASSERT-CUSTOMER-MAPPING.
           MOVE EXP-CUST-ID TO WS-ACT-CUST-ID
           CALL 'assert-equals' USING WS-EXP-CUST-ID WS-ACT-CUST-ID
           END-CALL
           MOVE EXP-CUST-FIRST-NAME TO WS-ACT-FIRST-NAME
           CALL 'assert-equals' USING WS-EXP-FIRST-NAME
               WS-ACT-FIRST-NAME
           END-CALL
           MOVE EXP-CUST-LAST-NAME TO WS-ACT-LAST-NAME
           CALL 'assert-equals' USING WS-EXP-LAST-NAME
               WS-ACT-LAST-NAME
           END-CALL
           MOVE EXP-CUST-SSN TO WS-ACT-SSN
           CALL 'assert-equals' USING WS-EXP-SSN WS-ACT-SSN
           END-CALL
           MOVE EXP-CUST-DOB-YYYY-MM-DD TO WS-ACT-DOB
           CALL 'assert-equals' USING WS-EXP-DOB
               WS-ACT-DOB
           END-CALL
           MOVE EXP-CUST-FICO-CREDIT-SCORE TO WS-ACT-FICO
           CALL 'assert-equals' USING WS-EXP-FICO
               WS-ACT-FICO
           END-CALL
           MOVE EXP-CUST-ADDR-LINE(1) TO WS-ACT-ADDR-1
           CALL 'assert-equals' USING WS-EXP-ADDR-1
               WS-ACT-ADDR-1
           END-CALL.
      *
       END PROGRAM CBEXPORT-test.
      *
      * Vendored GCBLUnit sibling units (assert-equals, assert-
      * notequals, gcblunit-result, gcblunit-summary, gcblunit-init)
      * are spliced in AFTER END PROGRAM so they compile into the same
      * cobc -x executable; resolved through -I tests/cobol-unit.
       COPY "gcblunit.cbl".
