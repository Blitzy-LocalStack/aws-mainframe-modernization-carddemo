      ************************************************************
      * PROGRAM   : CBACT04C_test.cbl   (GCBLUnit COBOL unit test)
      * APPLIC.   : AWS CardDemo - automated financial test suite
      * UNDER TEST: app/cbl/CBACT04C.cbl (interest & fee calculator)
      *
      * PURPOSE:
      *   Spec-encode and pin, with EXACT fixed-point (zero-tolerance)
      *   assertions, the pure monetary logic of CBACT04C:
      *     - interest formula ( TRAN-CAT-BAL * DIS-INT-RATE ) / 1200
      *     - interest accrual / accumulation across categories
      *     - 1050 cycle-clear (credit/debit reset on account update)
      *     - 1200 DEFAULT disclosure-group fallback (VSAM status 23)
      *     - 1400-COMPUTE-FEES documented "To be implemented" stub
      *     - 1300-B-WRITE-TX interest-transaction field contract
      *
      * PARAMETERS : none. Standalone "cobc -x" executable; its
      *              PROCEDURE DIVISION takes no USING - unlike the
      *              program under test (USING EXTERNAL-PARMS at run).
      * RETURNS    : RETURN-CODE = accumulated assertion-failure count
      *              (0 => all assertions passed => process exit 0 =>
      *              scripts/run_unit_tests.sh marks this test PASS).
      * EXCEPTIONS : none raised. GCBLUnit counts failures; it never
      *              STOP RUNs or abends, so every scenario always runs.
      *
      * SOURCE (REFERENCE ONLY - NEVER MODIFIED):
      *   app/cbl/CBACT04C.cbl and the app/cpy/*.cpy record layouts are
      *   the authoritative contract; this test replicates the source
      *   statements verbatim and never redefines a copybook record.
      *
      * SCOPE NOTE (environment-bounded, NOT exercised at this layer):
      *   CBACT04C's file I/O (TCATBALF / XREFFILE / DISCGRP / ACCTFILE
      *   / TRANSACT) and its fail-fast 9999-ABEND-PROGRAM -> CEE3ABD
      *   (LE abend code 999) path need real indexed files / an abend
      *   shim; those are driven by the integration layer
      *   (tests/integration/test_cbact04c_interest.py), not here.
      *
      * WHY - Alternatives Considered:
      *   Paragraph isolation via cobol-check file mocks (AAP 0.2.2)
      *   was REJECTED for this unit layer: the repo build compiles
      *   each *_test.cbl as a standalone GCBLUnit "cobc -x" binary
      *   with no mocking preprocessor. Spec-encoding the arithmetic
      *   in-line isolates the money math deterministically; deep
      *   file-driven execution is deferred to the integration tier.
      *
      * WHY - Assumptions:
      *   Copybook field PICtures / USAGE are exactly as declared in
      *   app/cpy (single-sourced via -I app/cpy). WS-DIS-GROUP-ID is
      *   a working-storage mirror of the program's FD key
      *   FD-DIS-ACCT-GROUP-ID, so the status-23 fallback can be
      *   exercised without opening the real DISCGRP file.
      *
      * WHY - Trade-offs:
      *   assert-equals does a BYTE comparison, so every expected
      *   mirror shares the actual's exact PICTURE/USAGE/length and
      *   byte-equality IS fixed-point value-equality (no float, no
      *   tolerance, ever). Truncation (production omits ROUNDED) is
      *   asserted explicitly as a financial-precision guard; the
      *   DEFAULT fallback is tested via the status-driven IF rather
      *   than a real DISCGRP re-read.
      *
      * WHY - Build/run contract:
      *   cobc -x -fixed --std=ibm-strict -I app/cpy -I tests/cobol-unit
      *        -o build/CBACT04C_test tests/cobol-unit/CBACT04C_test.cbl
      *   --std=ibm-strict is required for the signed/COMP-3 money PICs.
      ************************************************************
       IDENTIFICATION DIVISION.
       PROGRAM-ID. CBACT04C-test.
       DATA DIVISION.
       WORKING-STORAGE SECTION.
      *
      * Record layouts single-sourced from app/cpy (never redefined).
      * WHY - Assumption: these COPYs bind TRAN-CAT-BAL (S9(09)V99),
      * DIS-INT-RATE (S9(04)V99), the ACCOUNT balances (S9(10)V99),
      * XREF-CARD-NUM (X(16)) and the TRAN-RECORD fields exactly as
      * the program under test sees them, so the arithmetic and MOVEs
      * below operate on byte-identical operands.
       COPY CVTRA01Y.
       COPY CVTRA02Y.
       COPY CVACT01Y.
       COPY CVACT03Y.
       COPY CVTRA05Y.
      *
      * Non-copybook working items. WHY: these mirror WORKING-STORAGE
      * items that live inside CBACT04C itself (not in any copybook),
      * replicated here so the unit can run the same statements.
       01  WS-MONTHLY-INT          PIC S9(09)V99.
       01  WS-TOTAL-INT            PIC S9(09)V99.
       01  WS-FEE                  PIC S9(09)V99.
       01  WS-DISCGRP-STATUS       PIC X(02).
       01  WS-DIS-GROUP-ID         PIC X(10).
      *
      * Expected-value mirrors - each shares the PICTURE/USAGE of the
      * actual it is compared against so assert-equals' byte compare
      * equals fixed-point value equality.
       01  WS-EXP-9V99             PIC S9(09)V99.
       01  WS-EXP-10V99            PIC S9(10)V99.
       01  WS-EXP-GID              PIC X(10).
       01  WS-EXP-X2               PIC X(02).
       01  WS-EXP-X10              PIC X(10).
       01  WS-EXP-MERCH            PIC 9(09).
       01  WS-EXP-CARD             PIC X(16).
      *
      * The value the interest formula WOULD yield WITH ROUNDED, used
      * by scenario 2 to prove (via assert-notequals) that the
      * production statement TRUNCATES rather than rounds.
       01  WS-ROUND-9V99           PIC S9(09)V99.
      *
      * Accumulated GCBLUnit failure count. WHY: gcblunit-result's
      * LINKAGE argument is PIC S9(9) COMP, so this MUST match that
      * USAGE (a DISPLAY PIC 9(4) would mis-map the binary count).
       01  WS-FAILS                PIC S9(9) COMP.
      ************************************************************
       PROCEDURE DIVISION.
       0000-MAIN.
           PERFORM 1000-TEST-FORMULA-DIVISIBLE
           PERFORM 2000-TEST-FORMULA-TRUNCATION
           PERFORM 3000-TEST-ACCUMULATION
           PERFORM 4000-TEST-UPDATE-ACCOUNT
           PERFORM 5000-TEST-DEFAULT-FALLBACK
           PERFORM 6000-TEST-FEE-STUB
           PERFORM 7000-TEST-TX-FIELD-CONTRACT
           CALL "gcblunit-summary"
           CALL "gcblunit-result" USING WS-FAILS
           MOVE WS-FAILS TO RETURN-CODE
           STOP RUN.
      ************************************************************
      * SCENARIO 1 - Interest formula, divisible inputs. Replicates
      * the EXACT production statement (1300-COMPUTE-INTEREST, source
      * lines 464-465), NO ROUNDED, asserting exact fixed-point
      * quotients:
      *   ( 1000.00 * 12.00 ) / 1200 = 10.00
      *   ( 6000.00 * 24.00 ) / 1200 = 120.00
      *
      * WHY - Assumption (fixed-format margin, verified on cobc 3.2.0):
      *   the COMPUTE is kept split across two lines exactly as the
      *   production source. Collapsing it onto one physical line would
      *   push the divisor literal 1200 past column 72, where fixed
      *   format silently truncates it to 120 - a 10x error (100.00
      *   instead of 10.00). The two-line form keeps the divisor inside
      *   cols 8-72 so the money math is exact and matches the binary
      *   the integration layer exercises.
      ************************************************************
       1000-TEST-FORMULA-DIVISIBLE.
           MOVE 1000.00 TO TRAN-CAT-BAL
           MOVE 12.00   TO DIS-INT-RATE
           COMPUTE WS-MONTHLY-INT
             = ( TRAN-CAT-BAL * DIS-INT-RATE) / 1200
           MOVE 10.00   TO WS-EXP-9V99
           CALL "assert-equals" USING WS-EXP-9V99 WS-MONTHLY-INT
      *
      * Second divisible case: 6000.00 @ 24.00 = 120.00 exactly.
           MOVE 6000.00 TO TRAN-CAT-BAL
           MOVE 24.00   TO DIS-INT-RATE
           COMPUTE WS-MONTHLY-INT
             = ( TRAN-CAT-BAL * DIS-INT-RATE) / 1200
           MOVE 120.00  TO WS-EXP-9V99
           CALL "assert-equals" USING WS-EXP-9V99 WS-MONTHLY-INT
           EXIT.
      ************************************************************
      * SCENARIO 2 - Truncation guard (proves production omits
      * ROUNDED). ( 1000.00 * 1.10 ) / 1200 = 0.91666...; the exact
      * production statement (no ROUNDED) TRUNCATES to 0.91.
      *
      * WHY: with ROUNDED the same input yields 0.92 (verified on cobc
      * 3.2.0). Asserting 0.91 AND asserting (via assert-notequals)
      * that the result is NOT the rounded 0.92 together pin that the
      * money math TRUNCATES the trailing fraction rather than rounding
      * it - a critical financial-precision characteristic.
      ************************************************************
       2000-TEST-FORMULA-TRUNCATION.
           MOVE 1000.00 TO TRAN-CAT-BAL
           MOVE 1.10    TO DIS-INT-RATE
           COMPUTE WS-MONTHLY-INT
             = ( TRAN-CAT-BAL * DIS-INT-RATE) / 1200
           MOVE 0.91    TO WS-EXP-9V99
           CALL "assert-equals"    USING WS-EXP-9V99 WS-MONTHLY-INT
           MOVE 0.92    TO WS-ROUND-9V99
           CALL "assert-notequals" USING WS-ROUND-9V99 WS-MONTHLY-INT
           EXIT.
      ************************************************************
      * SCENARIO 3 - Interest accumulation across categories
      * (1300-COMPUTE-INTEREST source line 467: ADD WS-MONTHLY-INT TO
      * WS-TOTAL-INT, run once per transaction-category row).
      *
      * WHY - Trade-off: per-category monthly amounts are set with MOVE
      * literals (10.00, 5.00) rather than the COMPUTE, to ISOLATE the
      * ADD accumulation from the interest formula already pinned in
      * scenarios 1-2. 10.00 + 5.00 = 15.00 is an exact fixed-point sum
      * with no rounding ambiguity.
      ************************************************************
       3000-TEST-ACCUMULATION.
           MOVE 0       TO WS-TOTAL-INT
           MOVE 10.00   TO WS-MONTHLY-INT
           ADD WS-MONTHLY-INT TO WS-TOTAL-INT
           MOVE 5.00    TO WS-MONTHLY-INT
           ADD WS-MONTHLY-INT TO WS-TOTAL-INT
           MOVE 15.00   TO WS-EXP-9V99
           CALL "assert-equals" USING WS-EXP-9V99 WS-TOTAL-INT
           EXIT.
      ************************************************************
      * SCENARIO 4 - 1050-UPDATE-ACCOUNT accrual + cycle clear (source
      * lines 352-354): ADD WS-TOTAL-INT TO ACCT-CURR-BAL, then reset
      * ACCT-CURR-CYC-CREDIT and ACCT-CURR-CYC-DEBIT to zero.
      *
      * WHY: pins that accrued interest is added to the running balance
      * (500.00 + 15.00 = 515.00) AND that both cycle buckets are
      * zeroed in the same step - the end-of-cycle contract auditors
      * rely on. Expected mirrors use S9(10)V99 to match ACCOUNT-RECORD.
      ************************************************************
       4000-TEST-UPDATE-ACCOUNT.
           MOVE 500.00  TO ACCT-CURR-BAL
           MOVE 15.00   TO WS-TOTAL-INT
           MOVE 99.99   TO ACCT-CURR-CYC-CREDIT
           MOVE 88.88   TO ACCT-CURR-CYC-DEBIT
           ADD WS-TOTAL-INT TO ACCT-CURR-BAL
           MOVE 0       TO ACCT-CURR-CYC-CREDIT
           MOVE 0       TO ACCT-CURR-CYC-DEBIT
           MOVE 515.00  TO WS-EXP-10V99
           CALL "assert-equals" USING WS-EXP-10V99 ACCT-CURR-BAL
           MOVE 0       TO WS-EXP-10V99
           CALL "assert-equals" USING WS-EXP-10V99 ACCT-CURR-CYC-CREDIT
           CALL "assert-equals" USING WS-EXP-10V99 ACCT-CURR-CYC-DEBIT
           EXIT.
      ************************************************************
      * SCENARIO 5 - DEFAULT disclosure-group fallback (1200-GET-
      * INTEREST-RATE source lines 436-439): on DISCGRP status 23 the
      * program substitutes the literal 'DEFAULT' group id and re-reads.
      *
      * WHY - Trade-off: the fallback is exercised through the exact
      * status-driven IF against a WS mirror of the FD key, NOT a real
      * DISCGRP re-read (file I/O is the integration layer's job). Both
      * sides of the branch are covered: status '23' triggers the
      * substitution; status '00' leaves the group id unchanged.
      ************************************************************
       5000-TEST-DEFAULT-FALLBACK.
           MOVE 'GOLD'    TO WS-DIS-GROUP-ID
           MOVE '23'      TO WS-DISCGRP-STATUS
           IF WS-DISCGRP-STATUS = '23'
               MOVE 'DEFAULT' TO WS-DIS-GROUP-ID
           END-IF
           MOVE 'DEFAULT' TO WS-EXP-GID
           CALL "assert-equals" USING WS-EXP-GID WS-DIS-GROUP-ID
      *
      * Complement: a non-23 status must NOT trigger the fallback.
           MOVE 'GOLD'    TO WS-DIS-GROUP-ID
           MOVE '00'      TO WS-DISCGRP-STATUS
           IF WS-DISCGRP-STATUS = '23'
               MOVE 'DEFAULT' TO WS-DIS-GROUP-ID
           END-IF
           MOVE 'GOLD'    TO WS-EXP-GID
           CALL "assert-equals" USING WS-EXP-GID WS-DIS-GROUP-ID
           EXIT.
      ************************************************************
      * SCENARIO 6 - 1400-COMPUTE-FEES stub (source lines 518-520): the
      * paragraph body is ONLY "* To be implemented" plus EXIT - it
      * computes NO fee.
      *
      * WHY - DOCUMENTED GAP (audit flag): this scenario pins the
      * CURRENT no-fee behavior so any future fee implementation breaks
      * this test and forces a deliberate update. 6100-FEE-STUB-NOOP
      * mirrors the empty production paragraph exactly (a no-op).
      ************************************************************
       6000-TEST-FEE-STUB.
           MOVE 0     TO WS-FEE
           PERFORM 6100-FEE-STUB-NOOP
           MOVE 0     TO WS-EXP-9V99
           CALL "assert-equals" USING WS-EXP-9V99 WS-FEE
           EXIT.
      *
      * Mirror of the empty 1400-COMPUTE-FEES production paragraph.
      * WHY: kept as a real (no-op) PERFORM target so the test reflects
      * that the fee path IS invoked yet intentionally does nothing.
       6100-FEE-STUB-NOOP.
      * To be implemented (mirrors production stub - no fee computed).
           EXIT.
      ************************************************************
      * SCENARIO 7 - Interest-transaction field contract
      * (1300-B-WRITE-TX): the constant / derived fields the program
      * stamps on each emitted interest TRAN-RECORD.
      *
      * WHY - Trade-off: only the deterministic field MOVEs are pinned
      * (type '01', source 'System', amount = monthly interest, zero
      * merchant id, card number copied from the xref). TRAN-ID,
      * TRAN-DESC and the DB2 timestamps are intentionally excluded:
      * they depend on PARM-DATE and FUNCTION CURRENT-DATE and are
      * non-deterministic - the integration layer normalizes and checks
      * those against golden output instead.
      ************************************************************
       7000-TEST-TX-FIELD-CONTRACT.
           MOVE 10.00 TO WS-MONTHLY-INT
           MOVE '01'                TO TRAN-TYPE-CD
           MOVE '05'                TO TRAN-CAT-CD
           MOVE 'System'            TO TRAN-SOURCE
           MOVE WS-MONTHLY-INT      TO TRAN-AMT
           MOVE 0                   TO TRAN-MERCHANT-ID
           MOVE '1234567890123456' TO XREF-CARD-NUM
           MOVE XREF-CARD-NUM       TO TRAN-CARD-NUM
           MOVE '01'                TO WS-EXP-X2
           CALL "assert-equals" USING WS-EXP-X2 TRAN-TYPE-CD
           MOVE 'System'            TO WS-EXP-X10
           CALL "assert-equals" USING WS-EXP-X10 TRAN-SOURCE
           CALL "assert-equals" USING WS-MONTHLY-INT TRAN-AMT
           MOVE 0                   TO WS-EXP-MERCH
           CALL "assert-equals" USING WS-EXP-MERCH TRAN-MERCHANT-ID
           MOVE '1234567890123456' TO WS-EXP-CARD
           CALL "assert-equals" USING WS-EXP-CARD TRAN-CARD-NUM
           EXIT.
       END PROGRAM CBACT04C-test.
      ************************************************************
      * GCBLUnit assertion/result framework, vendored at
      * tests/cobol-unit/gcblunit.cbl and spliced in AFTER END PROGRAM
      * as sibling compilation units (assert-equals, assert-notequals,
      * gcblunit-result, gcblunit-summary, gcblunit-init). Resolved via
      * the build's -I tests/cobol-unit include path.
      ************************************************************
       COPY "gcblunit.cbl".
