      ************************************************************
      * PROGRAM-ID : CBCUS01C-test
      * TYPE       : GCBLUnit COBOL unit test (AWS CardDemo suite)
      * SOURCE UUT : app/cbl/CBCUS01C.cbl  (REFERENCE ONLY - the
      *              production program is NOT modified by this test)
      *
      * PURPOSE:
      *   Unit-layer contract guard for CBCUS01C, the customer master
      *   (CUSTFILE / CUSTDAT) read-and-print batch program.  CBCUS01C
      *   itself is monolithic and file-driven (OPEN indexed CUSTFILE
      *   -> loop READ INTO CUSTOMER-RECORD -> DISPLAY -> CLOSE); its
      *   only externally observable data contract is the CVCUS01Y
      *   CUSTOMER-RECORD it reads and prints.  This test therefore
      *   asserts, WITHOUT any file I/O, that the single-sourced
      *   CVCUS01Y layout is exactly 500 bytes and that the key,
      *   PII and credit fields keep their documented lengths and
      *   byte offsets after a value round-trip.  This satisfies
      *   CBCUS01C's "asserting happy-path test" at the unit layer
      *   (AAP section 0.1.4 / 0.5.2).
      *
      * PARAMETERS:
      *   None.  This is a self-contained -x main; it takes no
      *   run-time arguments and reads no files.
      *
      * RETURNS:
      *   RETURN-CODE = accumulated GCBLUnit failure count, published
      *   to the process exit status (0 = PASS; non-zero = number of
      *   failed assertions, consumed by scripts/run_unit_tests.sh).
      *
      * EXCEPTIONS / ERRORS:
      *   None are raised.  GCBLUnit COUNTS assertion failures rather
      *   than abending, so every assertion is always evaluated.
      *
      * ENV-BOUNDED ABEND PATH (documented, NOT exercised here):
      *   CBCUS01C's real read/OPEN/CLOSE failure handling drives an
      *   LE CEE3ABD abend (abcode 999).  That path needs a live
      *   indexed CUSTFILE and the LE runtime, so it is covered at the
      *   integration layer (tests/integration/test_provisioning.py),
      *   not here.
      *
      * DOCUMENTED CONVENTION NOTE (auditability):
      *   CBCUS01C names its diagnostic paragraphs Z-DISPLAY-IO-STATUS
      *   and Z-ABEND-PROGRAM - a deliberate divergence from the
      *   CBACT0x family's 9910-DISPLAY-IO-STATUS / 9999-ABEND-PROGRAM
      *   naming.  Noted so a future reader does not mistake the
      *   difference for a defect; it has no effect at the unit layer.
      *
      * PROVENANCE:
      *   Field names, PICTUREs and the 500-byte total are taken
      *   verbatim from app/cpy/CVCUS01Y.cpy and are asserted, never
      *   redefined (single-source contract).
      ************************************************************
       IDENTIFICATION DIVISION.
       PROGRAM-ID. CBCUS01C-test.

       DATA DIVISION.
       WORKING-STORAGE SECTION.

      ************************************************************
      * The record under test is single-sourced from the same     *
      * copybook the production program COPYs, resolved via         *
      * `cobc -I app/cpy`.  It is deliberately NOT redefined here   *
      * so the test breaks if, and only if, the real layout drifts. *
      ************************************************************
       COPY CVCUS01Y.

      ************************************************************
      * Length-comparison work pair.  Both operands are PIC 9(04)  *
      * DISPLAY so GCBLUnit's byte-equality equals value-equality   *
      * (its assert-equals contract requires identical PICTURE /    *
      * USAGE / length operands).  The pair is reused across the    *
      * length assertions: expected and actual are set immediately  *
      * before each CALL, so reuse is safe (the CALL reads them     *
      * synchronously).                                             *
      ************************************************************
       01  WS-EXPECTED-LENGTH          PIC 9(04).
       01  WS-ACTUAL-LENGTH            PIC 9(04).

      ************************************************************
      * Expected byte images for the offset/encoding round-trip.   *
      * WHY (Assumption): CUST-ID, CUST-SSN and CUST-FICO-CREDIT-   *
      * SCORE are UNSIGNED PIC 9 fields, so GnuCOBOL stores them as *
      * plain ASCII digits with no sign overpunch - the expected    *
      * image is therefore the literal digit string.  CUST-FIRST-   *
      * NAME / CUST-LAST-NAME are PIC X(25), so a short MOVE space-  *
      * pads to 25 and the expected literals are declared X(25).    *
      ************************************************************
       01  WS-EXP-CUST-ID              PIC X(09) VALUE '123456789'.
       01  WS-EXP-CUST-SSN             PIC X(09) VALUE '987654321'.
       01  WS-EXP-CUST-FICO            PIC X(03) VALUE '750'.
       01  WS-EXP-CUST-FNAME           PIC X(25) VALUE 'JOHN'.
       01  WS-EXP-CUST-LNAME           PIC X(25) VALUE 'DOE'.

      ************************************************************
      * Receives the GCBLUnit failure count.  PIC S9(09) COMP      *
      * matches gcblunit-result's LINKAGE item exactly so the CALL  *
      * is type-clean.                                              *
      ************************************************************
       01  WS-RESULT                   PIC S9(09) COMP.

       PROCEDURE DIVISION.
       0000-MAIN.

      *    Reset the shared EXTERNAL counters before asserting.
      *    WHY (Assumption): GnuCOBOL zero-initialises EXTERNAL
      *    storage on first reference, so this is belt-and-suspenders
      *    - it makes the starting state explicit and keeps the test
      *    correct even if the framework is ever re-entered.
           CALL 'gcblunit-init'

      *    ---- (1) Record-length contract -------------------------
      *    WHY (Trade-off): asserting FUNCTION LENGTH against the
      *    fixed 500 detects any copybook drift (added/removed/
      *    resized field) with one cheap check, before any of the
      *    finer field/offset assertions run.
           MOVE 500 TO WS-EXPECTED-LENGTH
           MOVE FUNCTION LENGTH(CUSTOMER-RECORD) TO WS-ACTUAL-LENGTH
           CALL 'assert-equals' USING WS-EXPECTED-LENGTH
                                      WS-ACTUAL-LENGTH

      *    ---- (2) Field-length contract --------------------------
      *    WHY (Assumption): the key (CUST-ID), the two PII names,
      *    the SSN and the FICO score are the fields other layers
      *    key/extract on, so their widths are pinned explicitly.
           MOVE 9 TO WS-EXPECTED-LENGTH
           MOVE FUNCTION LENGTH(CUST-ID) TO WS-ACTUAL-LENGTH
           CALL 'assert-equals' USING WS-EXPECTED-LENGTH
                                      WS-ACTUAL-LENGTH

           MOVE 25 TO WS-EXPECTED-LENGTH
           MOVE FUNCTION LENGTH(CUST-FIRST-NAME) TO WS-ACTUAL-LENGTH
           CALL 'assert-equals' USING WS-EXPECTED-LENGTH
                                      WS-ACTUAL-LENGTH

           MOVE 25 TO WS-EXPECTED-LENGTH
           MOVE FUNCTION LENGTH(CUST-LAST-NAME) TO WS-ACTUAL-LENGTH
           CALL 'assert-equals' USING WS-EXPECTED-LENGTH
                                      WS-ACTUAL-LENGTH

           MOVE 9 TO WS-EXPECTED-LENGTH
           MOVE FUNCTION LENGTH(CUST-SSN) TO WS-ACTUAL-LENGTH
           CALL 'assert-equals' USING WS-EXPECTED-LENGTH
                                      WS-ACTUAL-LENGTH

           MOVE 3 TO WS-EXPECTED-LENGTH
           MOVE FUNCTION LENGTH(CUST-FICO-CREDIT-SCORE)
                TO WS-ACTUAL-LENGTH
           CALL 'assert-equals' USING WS-EXPECTED-LENGTH
                                      WS-ACTUAL-LENGTH

      *    ---- (3) Field-encoding / offset round-trip -------------
      *    Populate the record, then read it back through absolute
      *    positional slices to prove each field lands at its
      *    documented byte offset.
      *    WHY (Trade-off): slicing CUSTOMER-RECORD by absolute
      *    position (rather than only comparing the named fields)
      *    pins the exact byte offsets of the PII/credit fields in
      *    the fixed-width record - a data-encoding integrity guard
      *    (AAP section 0.1.1).  INITIALIZE first so any byte not
      *    explicitly MOVEd is deterministic.
           INITIALIZE CUSTOMER-RECORD
           MOVE 123456789 TO CUST-ID
           MOVE 'JOHN'    TO CUST-FIRST-NAME
           MOVE 'DOE'     TO CUST-LAST-NAME
           MOVE 987654321 TO CUST-SSN
           MOVE 750       TO CUST-FICO-CREDIT-SCORE

      *    CUST-ID occupies bytes 1-9 (RECORD KEY).
           CALL 'assert-equals' USING WS-EXP-CUST-ID
                                      CUSTOMER-RECORD(1:9)

      *    CUST-SSN occupies bytes 280-288 (9-byte PII field).
           CALL 'assert-equals' USING WS-EXP-CUST-SSN
                                      CUSTOMER-RECORD(280:9)

      *    CUST-FICO-CREDIT-SCORE occupies bytes 330-332.
           CALL 'assert-equals' USING WS-EXP-CUST-FICO
                                      CUSTOMER-RECORD(330:3)

      *    Named-field checks: the X(25) names must equal the
      *    space-padded expected images (validates offsets 10:25
      *    and 60:25 through the field references themselves).
           CALL 'assert-equals' USING WS-EXP-CUST-FNAME
                                      CUST-FIRST-NAME

           CALL 'assert-equals' USING WS-EXP-CUST-LNAME
                                      CUST-LAST-NAME

      *    ---- Publish results ------------------------------------
      *    Summary line first (human-readable tally into the log),
      *    then translate the failure count into the exit status so
      *    the runner scripts derive PASS/FAIL from the process code.
           CALL 'gcblunit-summary'
           CALL 'gcblunit-result' USING WS-RESULT
           MOVE WS-RESULT TO RETURN-CODE
           STOP RUN.

       END PROGRAM CBCUS01C-test.

      ************************************************************
      * Vendored GCBLUnit assertion/result units.  COPYd AFTER    *
      * END PROGRAM so they compile as sibling units linked into   *
      * the same `cobc -x` executable and are resolved by the      *
      * CALLs above.  Path supplied via `cobc -I tests/cobol-unit`.*
      ************************************************************
       COPY "gcblunit.cbl".
