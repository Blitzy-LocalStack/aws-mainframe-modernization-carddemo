      ******************************************************************
      * Program     : CBACT03C_test.cbl
      * Application : CardDemo -- automated test suite (unit layer)
      * Type        : GCBLUnit COBOL unit test
      * Under test  : app/cbl/CBACT03C.cbl (XREF master read/print)
      *
      * PURPOSE:
      *   Validate the single-sourced, fixed-width record-layout
      *   contract of copybook CVACT03Y (CARD-XREF-RECORD, RECLN 50)
      *   that CBACT03C depends on when it READs and DISPLAYs the card
      *   cross-reference (XREF) master.  This provides CBACT03C its
      *   required asserting happy-path test at the unit layer
      *   (AAP sections 0.1.4 and 0.5.2).
      *
      * PARAMETERS:
      *   None.  Standalone cobc -x main; no LINKAGE, no run-time args.
      *
      * RETURNS:
      *   RETURN-CODE = accumulated GCBLUnit failure count.
      *     0     -> every assertion passed (CI marks PASS)
      *     non-0 -> that many assertions failed (CI marks FAIL)
      *
      * EXCEPTIONS / ERRORS:
      *   None raised.  GCBLUnit counts assertion failures and surfaces
      *   them through the exit code; this test never abends.
      *
      * REFERENCE NOTE:
      *   app/cbl/CBACT03C.cbl is REFERENCE-ONLY -- neither modified nor
      *   executed here.  Its abend path (9999-ABEND-PROGRAM calling
      *   'CEE3ABD') is environment-bounded because GnuCOBOL ships no
      *   Language Environment CEE3ABD, so that path is left to the
      *   integration layer, not this unit test.
      *
      * WHY - Trade-off:
      *   CBACT03C is a monolithic, file-driven main (OPEN indexed
      *   XREFFILE -> READ INTO CARD-XREF-RECORD -> DISPLAY -> CLOSE)
      *   that cannot run without VSAM files, so the real indexed-file
      *   read round-trip and record counts are owned by the
      *   integration layer (tests/integration/test_provisioning.py).
      *   The unit layer instead pins the DATA CONTRACT the program
      *   relies on -- record length, field lengths and byte offsets.
      *   A drift in CVACT03Y would break every program sharing the
      *   layout, so failing fast here protects them all.
      *
      * WHY - Assumptions:
      *   Field offsets are taken verbatim from CVACT03Y:
      *     XREF-CARD-NUM 1:16, XREF-CUST-ID 17:9, XREF-ACCT-ID 26:11.
      *   XREF-CUST-ID and XREF-ACCT-ID are UNSIGNED PIC 9 zoned
      *   decimals, so their bytes are plain ASCII digits with no sign
      *   overpunch; the expected slices are therefore literal digits.
      ******************************************************************
       IDENTIFICATION DIVISION.
       PROGRAM-ID. CBACT03C-test.
      *
       DATA DIVISION.
       WORKING-STORAGE SECTION.
      *
      * Single-source the SAME layout CBACT03C COPYs (resolved via
      * -I app/cpy).  WHY - Assumption: CARD-XREF-RECORD is never
      * redefined locally, so a copybook drift is detected here rather
      * than masked by a divergent local mirror.
       COPY CVACT03Y.
      *
      * Length-assertion operands.  WHY - Trade-off: expected and actual
      * share one PICTURE (PIC 9(4)) so GCBLUnit's byte-wise
      * assert-equals is equivalent to value-equality, per its contract.
       01  WS-LEN                      PIC 9(4).
       01  WS-EXP                      PIC 9(4).
      *
      * Offset round-trip operands: one matched expected/actual pair per
      * field width so every assert-equals compares equal-length items.
       01  WS-X16                      PIC X(16).
       01  WS-E16                      PIC X(16).
       01  WS-X9                       PIC X(9).
       01  WS-E9                       PIC X(9).
       01  WS-X11                      PIC X(11).
       01  WS-E11                      PIC X(11).
      *
      * WHY - Assumption: gcblunit-result's LINKAGE operand is declared
      * PIC S9(9) COMP; WS-FAILS matches that USAGE/length so
      * CALL BY REFERENCE transfers the binary failure count intact.
      * A DISPLAY item here would be reinterpreted as binary and would
      * corrupt the returned count and hence the process exit code.
       01  WS-FAILS                    PIC S9(9) COMP.
      *
       PROCEDURE DIVISION.
       0000-MAIN.
           DISPLAY 'CBACT03C_test: CVACT03Y layout contract'
      *
      *----------------------------------------------------------------*
      * 1) Record-length guard: CARD-XREF-RECORD stays 50 bytes.       *
      *----------------------------------------------------------------*
           MOVE 50 TO WS-EXP
           MOVE LENGTH OF CARD-XREF-RECORD TO WS-LEN
           CALL 'assert-equals' USING WS-EXP WS-LEN
      *
      *----------------------------------------------------------------*
      * 2) Field-length guards for each named field.                   *
      *----------------------------------------------------------------*
           MOVE 16 TO WS-EXP
           MOVE LENGTH OF XREF-CARD-NUM TO WS-LEN
           CALL 'assert-equals' USING WS-EXP WS-LEN
      *
           MOVE 9 TO WS-EXP
           MOVE LENGTH OF XREF-CUST-ID TO WS-LEN
           CALL 'assert-equals' USING WS-EXP WS-LEN
      *
           MOVE 11 TO WS-EXP
           MOVE LENGTH OF XREF-ACCT-ID TO WS-LEN
           CALL 'assert-equals' USING WS-EXP WS-LEN
      *
      *----------------------------------------------------------------*
      * 3) Field-encoding / offset round-trip.                         *
      *    WHY - Trade-off: comparing positional slices of the         *
      *    GROUP record (not the elementary fields) proves each        *
      *    field lands at the exact byte offset the fixed-width        *
      *    VSAM contract requires -- a check a field compare misses.   *
      *----------------------------------------------------------------*
           MOVE '1234567890123456' TO XREF-CARD-NUM
           MOVE 123456789 TO XREF-CUST-ID
           MOVE 12345678901 TO XREF-ACCT-ID
      *
           MOVE '1234567890123456' TO WS-E16
           MOVE CARD-XREF-RECORD(1:16) TO WS-X16
           CALL 'assert-equals' USING WS-E16 WS-X16
      *
           MOVE '123456789' TO WS-E9
           MOVE CARD-XREF-RECORD(17:9) TO WS-X9
           CALL 'assert-equals' USING WS-E9 WS-X9
      *
           MOVE '12345678901' TO WS-E11
           MOVE CARD-XREF-RECORD(26:11) TO WS-X11
           CALL 'assert-equals' USING WS-E11 WS-X11
      *
      *----------------------------------------------------------------*
      * Publish the tally (audit readability), then the failure        *
      * count, and map it to the process exit status (GCBLUnit).       *
      *----------------------------------------------------------------*
           CALL 'gcblunit-summary'
           CALL 'gcblunit-result' USING WS-FAILS
           MOVE WS-FAILS TO RETURN-CODE
           STOP RUN.
      *
       END PROGRAM CBACT03C-test.
      *
      * GCBLUnit assertion/result units are textually appended AFTER the
      * END PROGRAM line so they compile as sibling units in the same
      * cobc -x executable (see gcblunit.cbl header for the rationale).
       COPY "gcblunit.cbl".
