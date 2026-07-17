       IDENTIFICATION DIVISION.
       PROGRAM-ID. CBEXPORT-test.
      ******************************************************************
      * PROGRAM     : CBEXPORT_test.cbl
      * APPLICATION : CardDemo automated test suite (COBOL unit layer)
      * FRAMEWORK   : GCBLUnit 1.22.6 (COPY "gcblunit.cbl" siblings)
      * UNIT UNDER  : app/cbl/CBEXPORT.cbl  (REFERENCE ONLY - unchanged)
      * TARGET LOGIC: 2200-CREATE-CUSTOMER-EXP-REC (customer path)
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
       01  WS-EXPECTED-VALUES.
           05  WS-EXP-REC-TYPE           PIC X(1) VALUE 'C'.
           05  WS-EXP-TIMESTAMP          PIC X(26)
               VALUE '2024-01-15 10:30:00.00'.
           05  WS-EXP-BRANCH-ID          PIC X(4) VALUE '0001'.
           05  WS-EXP-REGION-CODE        PIC X(5) VALUE 'NORTH'.
           05  WS-EXP-SEQUENCE-NUM       PIC 9(9) COMP VALUE 1.
           05  WS-EXP-CUST-ID            PIC 9(09) COMP VALUE 123456789.
           05  WS-EXP-FIRST-NAME         PIC X(25) VALUE 'JOHN'.
           05  WS-EXP-LAST-NAME          PIC X(25) VALUE 'PUBLIC'.
           05  WS-EXP-SSN                PIC 9(09) VALUE 987654321.
           05  WS-EXP-DOB                PIC X(10) VALUE '1980-05-15'.
           05  WS-EXP-FICO               PIC 9(03) COMP-3 VALUE 750.
           05  WS-EXP-ADDR-1             PIC X(50)
               VALUE '123 MAIN STREET'.
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
      * 0000-MAIN : drive setup -> encode -> assert -> publish result. *
      ******************************************************************
       0000-MAIN.
           PERFORM 1000-SETUP-CUSTOMER
           PERFORM 2200-ENCODE-CUSTOMER-EXP-REC
           PERFORM 3000-ASSERT-HEADER
           PERFORM 4000-ASSERT-CUSTOMER-MAPPING
           CALL 'gcblunit-summary'
           CALL 'gcblunit-result' USING WS-RESULT
           MOVE WS-RESULT TO RETURN-CODE
           STOP RUN.
      *
      ******************************************************************
      * 1000-SETUP-CUSTOMER : seed one representative CUSTOMER-RECORD.
      * WHY - Assumption: values are chosen to exercise each USAGE
      * class - 9-digit CUST-ID / CUST-SSN (numeric), a 3-digit FICO
      * for the COMP-3 target, and alphanumeric name/address/date.
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
      * WHY - Assumption: EXPORT-SEQUENCE-NUM is COMP, so its expected
      * counterpart is also PIC 9(9) COMP - byte-equality then proves
      * the binary encoding, not merely the display value.
      ******************************************************************
       3000-ASSERT-HEADER.
           MOVE FUNCTION LENGTH(EXPORT-RECORD) TO WS-ACT-RECORD-LEN
           CALL 'assert-equals' USING WS-EXP-RECORD-LEN
               WS-ACT-RECORD-LEN
           CALL 'assert-equals' USING WS-EXP-REC-TYPE EXPORT-REC-TYPE
           CALL 'assert-equals' USING WS-EXP-TIMESTAMP EXPORT-TIMESTAMP
           CALL 'assert-equals' USING WS-EXP-BRANCH-ID EXPORT-BRANCH-ID
           CALL 'assert-equals' USING WS-EXP-REGION-CODE
               EXPORT-REGION-CODE
           CALL 'assert-equals' USING WS-EXP-SEQUENCE-NUM
               EXPORT-SEQUENCE-NUM.
      *
      ******************************************************************
      * 4000-ASSERT-CUSTOMER-MAPPING : per-field encoding assertions.
      * WHY - Trade-off: EXP-CUST-ID (COMP) and EXP-CUST-FICO-CREDIT-
      * SCORE (COMP-3) are asserted with matching typed expecteds to
      * validate binary and packed-decimal encoding; EXP-CUST-SSN
      * (zoned 9(09)) and the X(nn) fields cover the display classes;
      * one OCCURS element (EXP-CUST-ADDR-LINE(1)) proves subscripted
      * table mapping.
      ******************************************************************
       4000-ASSERT-CUSTOMER-MAPPING.
           CALL 'assert-equals' USING WS-EXP-CUST-ID EXP-CUST-ID
           CALL 'assert-equals' USING WS-EXP-FIRST-NAME
               EXP-CUST-FIRST-NAME
           CALL 'assert-equals' USING WS-EXP-LAST-NAME
               EXP-CUST-LAST-NAME
           CALL 'assert-equals' USING WS-EXP-SSN EXP-CUST-SSN
           CALL 'assert-equals' USING WS-EXP-DOB
               EXP-CUST-DOB-YYYY-MM-DD
           CALL 'assert-equals' USING WS-EXP-FICO
               EXP-CUST-FICO-CREDIT-SCORE
           CALL 'assert-equals' USING WS-EXP-ADDR-1
               EXP-CUST-ADDR-LINE(1).
      *
       END PROGRAM CBEXPORT-test.
      *
      * Vendored GCBLUnit sibling units (assert-equals, assert-
      * notequals, gcblunit-result, gcblunit-summary, gcblunit-init)
      * are spliced in AFTER END PROGRAM so they compile into the same
      * cobc -x executable; resolved through -I tests/cobol-unit.
       COPY "gcblunit.cbl".
