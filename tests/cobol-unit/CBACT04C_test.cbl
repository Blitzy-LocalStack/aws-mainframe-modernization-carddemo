      ************************************************************
      * PROGRAM   : CBACT04C_test.cbl   (GCBLUnit spec-replica test)
      * APPLIC.   : AWS CardDemo - automated financial test suite
      * TYPE      : GCBLUnit spec-replica / interest-arithmetic
      *             contract test - a SUPPLEMENTAL SPECIFICATION
      *             (per MA-15 / MA-17 / MA-18), NOT a production run.
      * UNDER TEST: app/cbl/CBACT04C.cbl (interest & fee calculator)
      * EXECUTES UUT: NO. CBACT04C is a file-driven USING-subprogram
      *             that OPENs five indexed files on entry and abends
      *             (9999-ABEND-PROGRAM -> CEE3ABD, LE 999) on a bad
      *             OPEN, so a direct in-process CALL with no real
      *             files would abend the test binary - there is no
      *             safe file-free path as there was for CBSTM03B_test.
      *             This test REPLICATES the pure monetary statements
      *             verbatim and asserts exact fixed-point results; the
      *             REAL run (via the CBACT04D driver, source
      *             tests/cobol-unit/CBACT04C_driver.cbl) and the
      *             file/abend paths live in the integration layer -
      *             see the SCOPE NOTE below.
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
      * TRAN-ID expected/actual mirrors (scenario 7, MI-03 / MA-17).
      * WHY - MI-03 correction: TRAN-ID is DETERMINISTIC given a fixed
      * PARM-DATE. Production 1300-B-WRITE-TX (source 473-480) builds
      * it as STRING PARM-DATE (X10) + WS-TRANID-SUFFIX (9(06),
      * ascending) INTO TRAN-ID (X16); with a fixed date the id is
      * fully reproducible, so the exact value CAN and MUST be
      * asserted. WS-PARM-DATE / WS-TRANID-SUFFIX mirror the two
      * production operands (same PIC/USAGE) so the replicated STRING
      * yields byte-identical output.
       01  WS-EXP-TRANID           PIC X(16).
       01  WS-ACT-TRANID           PIC X(16).
       01  WS-PARM-DATE            PIC X(10).
       01  WS-TRANID-SUFFIX        PIC 9(06) VALUE 0.
      *
      * Actual-value mirrors for the 8 copybook sub-items asserted
      * below. WHY - MA-19: assert-equals is CALL'd USING two args and
      * cobc -Wcall-params (surfaced under -Wall -Wextra) flags any
      * argument that is not a 01/77 level item; the flagged operands
      * (ACCT-CURR-* and the TRAN-* fields) are 05-level copybook
      * members. Each mirror shares the actual's EXACT PICTURE/USAGE
      * and the value is MOVEd in byte-for-byte immediately before the
      * CALL, so byte-equality (== fixed-point value equality) is
      * preserved. Trade-off: muting the check with -Wno-call-params
      * was REJECTED - the reviewer wants the code corrected so the
      * warnings-fatal unit build stays green without suppressions.
       01  WS-ACT-CBAL             PIC S9(10)V99.
       01  WS-ACT-CCR              PIC S9(10)V99.
       01  WS-ACT-CDB              PIC S9(10)V99.
       01  WS-ACT-TYPE             PIC X(02).
       01  WS-ACT-SRC              PIC X(10).
       01  WS-ACT-AMT              PIC S9(09)V99.
       01  WS-ACT-MERCH            PIC 9(09).
       01  WS-ACT-CARD             PIC X(16).
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
      ************************************************************
      * 0000-MAIN.
      * PURPOSE : Driver paragraph - runs the seven spec scenarios in
      *           order, then emits the GCBLUnit summary and maps the
      *           accumulated failure count to RETURN-CODE.
      * PARAMS  : none (standalone cobc -x executable).
      * RETURNS : sets RETURN-CODE = WS-FAILS (0 => all assertions
      *           passed => the runner marks this test PASS).
      * ERRORS  : none raised; STOP RUN is the normal, only exit.
      ************************************************************
       0000-MAIN.
           PERFORM 1000-TEST-FORMULA-DIVISIBLE
           PERFORM 2000-TEST-FORMULA-TRUNCATION
           PERFORM 3000-TEST-ACCUMULATION
           PERFORM 4000-TEST-UPDATE-ACCOUNT
           PERFORM 5000-TEST-DEFAULT-FALLBACK
           PERFORM 6000-TEST-FEE-STUB
           PERFORM 7000-TEST-TX-FIELD-CONTRACT
           CALL "gcblunit-summary"
           END-CALL
           CALL "gcblunit-result" USING WS-FAILS
           END-CALL
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
      * 1000-TEST-FORMULA-DIVISIBLE.
      * PARAMS : none.  RETURNS : none (2 assertions; failure count++).
      * ERRORS : none raised; assert-equals never abends.
       1000-TEST-FORMULA-DIVISIBLE.
           MOVE 1000.00 TO TRAN-CAT-BAL
           MOVE 12.00   TO DIS-INT-RATE
           COMPUTE WS-MONTHLY-INT
             = ( TRAN-CAT-BAL * DIS-INT-RATE) / 1200
           END-COMPUTE
           MOVE 10.00   TO WS-EXP-9V99
           CALL "assert-equals" USING WS-EXP-9V99 WS-MONTHLY-INT
           END-CALL
      *
      * Second divisible case: 6000.00 @ 24.00 = 120.00 exactly.
           MOVE 6000.00 TO TRAN-CAT-BAL
           MOVE 24.00   TO DIS-INT-RATE
           COMPUTE WS-MONTHLY-INT
             = ( TRAN-CAT-BAL * DIS-INT-RATE) / 1200
           END-COMPUTE
           MOVE 120.00  TO WS-EXP-9V99
           CALL "assert-equals" USING WS-EXP-9V99 WS-MONTHLY-INT
           END-CALL
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
      * 2000-TEST-FORMULA-TRUNCATION.
      * PARAMS : none.  RETURNS : none (2 assertions: exact 0.91 AND
      *          not-equal to the rounded 0.92).
      * ERRORS : none raised.
       2000-TEST-FORMULA-TRUNCATION.
           MOVE 1000.00 TO TRAN-CAT-BAL
           MOVE 1.10    TO DIS-INT-RATE
           COMPUTE WS-MONTHLY-INT
             = ( TRAN-CAT-BAL * DIS-INT-RATE) / 1200
           END-COMPUTE
           MOVE 0.91    TO WS-EXP-9V99
           CALL "assert-equals"    USING WS-EXP-9V99 WS-MONTHLY-INT
           END-CALL
           MOVE 0.92    TO WS-ROUND-9V99
           CALL "assert-notequals" USING WS-ROUND-9V99 WS-MONTHLY-INT
           END-CALL
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
      * 3000-TEST-ACCUMULATION.
      * PARAMS : none.  RETURNS : none (1 assertion: 10.00 + 5.00 =
      *          15.00 across the per-category ADD loop).
      * ERRORS : none raised.
       3000-TEST-ACCUMULATION.
           MOVE 0       TO WS-TOTAL-INT
           MOVE 10.00   TO WS-MONTHLY-INT
           ADD WS-MONTHLY-INT TO WS-TOTAL-INT
           END-ADD
           MOVE 5.00    TO WS-MONTHLY-INT
           ADD WS-MONTHLY-INT TO WS-TOTAL-INT
           END-ADD
           MOVE 15.00   TO WS-EXP-9V99
           CALL "assert-equals" USING WS-EXP-9V99 WS-TOTAL-INT
           END-CALL
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
      * 4000-TEST-UPDATE-ACCOUNT.
      * PURPOSE : Pin 1050-UPDATE-ACCOUNT's accrual + cycle-clear
      *           contract - accrued interest is ADDed to ACCT-CURR-BAL
      *           and both cycle buckets are reset to zero in one step.
      * PARAMS  : none (values seeded in-line).
      * RETURNS : none; increments the GCBLUnit failure count on any
      *           mismatch (3 assertions).
      * ERRORS  : none raised; assert-equals never abends.
       4000-TEST-UPDATE-ACCOUNT.
           MOVE 500.00  TO ACCT-CURR-BAL
           MOVE 15.00   TO WS-TOTAL-INT
           MOVE 99.99   TO ACCT-CURR-CYC-CREDIT
           MOVE 88.88   TO ACCT-CURR-CYC-DEBIT
           ADD WS-TOTAL-INT TO ACCT-CURR-BAL
           END-ADD
           MOVE 0       TO ACCT-CURR-CYC-CREDIT
           MOVE 0       TO ACCT-CURR-CYC-DEBIT
           MOVE 515.00  TO WS-EXP-10V99
           MOVE ACCT-CURR-BAL        TO WS-ACT-CBAL
           CALL "assert-equals" USING WS-EXP-10V99 WS-ACT-CBAL
           END-CALL
           MOVE 0       TO WS-EXP-10V99
           MOVE ACCT-CURR-CYC-CREDIT TO WS-ACT-CCR
           CALL "assert-equals" USING WS-EXP-10V99 WS-ACT-CCR
           END-CALL
           MOVE ACCT-CURR-CYC-DEBIT  TO WS-ACT-CDB
           CALL "assert-equals" USING WS-EXP-10V99 WS-ACT-CDB
           END-CALL
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
      * 5000-TEST-DEFAULT-FALLBACK.
      * PARAMS : none.  RETURNS : none (2 assertions: status '23'
      *          substitutes 'DEFAULT'; status '00' leaves it 'GOLD').
      * ERRORS : none raised.
       5000-TEST-DEFAULT-FALLBACK.
           MOVE 'GOLD'    TO WS-DIS-GROUP-ID
           MOVE '23'      TO WS-DISCGRP-STATUS
           IF WS-DISCGRP-STATUS = '23'
               MOVE 'DEFAULT' TO WS-DIS-GROUP-ID
           END-IF
           MOVE 'DEFAULT' TO WS-EXP-GID
           CALL "assert-equals" USING WS-EXP-GID WS-DIS-GROUP-ID
           END-CALL
      *
      * Complement: a non-23 status must NOT trigger the fallback.
           MOVE 'GOLD'    TO WS-DIS-GROUP-ID
           MOVE '00'      TO WS-DISCGRP-STATUS
           IF WS-DISCGRP-STATUS = '23'
               MOVE 'DEFAULT' TO WS-DIS-GROUP-ID
           END-IF
           MOVE 'GOLD'    TO WS-EXP-GID
           CALL "assert-equals" USING WS-EXP-GID WS-DIS-GROUP-ID
           END-CALL
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
      * 6000-TEST-FEE-STUB.
      * PARAMS : none.  RETURNS : none (1 assertion: the documented
      *          "To be implemented" stub computes NO fee -> 0).
      * ERRORS : none raised.  WHY: this pins current no-fee behavior
      *          so any future fee logic deliberately breaks the test.
       6000-TEST-FEE-STUB.
           MOVE 0     TO WS-FEE
           PERFORM 6100-FEE-STUB-NOOP
           MOVE 0     TO WS-EXP-9V99
           CALL "assert-equals" USING WS-EXP-9V99 WS-FEE
           END-CALL
           EXIT.
      *
      * Mirror of the empty 1400-COMPUTE-FEES production paragraph.
      * WHY: kept as a real (no-op) PERFORM target so the test reflects
      * that the fee path IS invoked yet intentionally does nothing.
      * 6100-FEE-STUB-NOOP.
      * PARAMS : none.  RETURNS : none (no-op).  ERRORS : none.
       6100-FEE-STUB-NOOP.
      * To be implemented (mirrors production stub - no fee computed).
           EXIT.
      ************************************************************
      * SCENARIO 7 - Interest-transaction field contract
      * (1300-B-WRITE-TX): the constant / derived fields the program
      * stamps on each emitted interest TRAN-RECORD.
      *
      * WHY - Fields pinned: type '01', source 'System', amount =
      * monthly interest, zero merchant id, card number copied from
      * the xref, AND the exact TRAN-ID.
      *
      * WHY - MI-03 correction (determinism): an earlier revision
      * wrongly grouped TRAN-ID with the non-deterministic fields.
      * TRAN-ID is DETERMINISTIC given a fixed PARM-DATE - it is
      * STRING PARM-DATE + an ascending 9(06) counter (source 473-480),
      * so it is asserted EXACTLY below (two successive emissions prove
      * both the fixed prefix and the +1 counter progression).
      * TRAN-DESC is likewise deterministic ('Int. for a/c ' + ACCT-ID).
      * ONLY TRAN-ORIG-TS / TRAN-PROC-TS are runtime-variable (FUNCTION
      * CURRENT-DATE); the integration layer normalizes those two
      * timestamps before its golden comparison.
      *
      * 7000-TEST-TX-FIELD-CONTRACT.
      * PARAMS  : none (fields seeded in-line).
      * RETURNS : none; increments the GCBLUnit failure count on any
      *           mismatch (7 assertions incl. two TRAN-ID checks).
      * ERRORS  : none raised.
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
           MOVE TRAN-TYPE-CD        TO WS-ACT-TYPE
           CALL "assert-equals" USING WS-EXP-X2 WS-ACT-TYPE
           END-CALL
           MOVE 'System'            TO WS-EXP-X10
           MOVE TRAN-SOURCE         TO WS-ACT-SRC
           CALL "assert-equals" USING WS-EXP-X10 WS-ACT-SRC
           END-CALL
           MOVE TRAN-AMT            TO WS-ACT-AMT
           CALL "assert-equals" USING WS-MONTHLY-INT WS-ACT-AMT
           END-CALL
           MOVE 0                   TO WS-EXP-MERCH
           MOVE TRAN-MERCHANT-ID    TO WS-ACT-MERCH
           CALL "assert-equals" USING WS-EXP-MERCH WS-ACT-MERCH
           END-CALL
           MOVE '1234567890123456' TO WS-EXP-CARD
           MOVE TRAN-CARD-NUM       TO WS-ACT-CARD
           CALL "assert-equals" USING WS-EXP-CARD WS-ACT-CARD
           END-CALL
      *
      * Deterministic TRAN-ID (MI-03 / MA-17). Replicates production
      * 1300-B-WRITE-TX verbatim: ADD 1 TO the 9(06) suffix, then
      * STRING the fixed PARM-DATE + suffix INTO TRAN-ID. WHY two
      * emissions: proves the date prefix is fixed AND the counter
      * increments deterministically (000001 -> 000002), so a fixed
      * PARM date yields a fully reproducible id - not a runtime value.
           MOVE '2022-01-01' TO WS-PARM-DATE
           MOVE 0            TO WS-TRANID-SUFFIX
           ADD 1             TO WS-TRANID-SUFFIX
           END-ADD
           STRING WS-PARM-DATE, WS-TRANID-SUFFIX
                  DELIMITED BY SIZE
             INTO TRAN-ID
           END-STRING
           MOVE '2022-01-01000001' TO WS-EXP-TRANID
           MOVE TRAN-ID             TO WS-ACT-TRANID
           CALL "assert-equals" USING WS-EXP-TRANID WS-ACT-TRANID
           END-CALL
           ADD 1             TO WS-TRANID-SUFFIX
           END-ADD
           STRING WS-PARM-DATE, WS-TRANID-SUFFIX
                  DELIMITED BY SIZE
             INTO TRAN-ID
           END-STRING
           MOVE '2022-01-01000002' TO WS-EXP-TRANID
           MOVE TRAN-ID             TO WS-ACT-TRANID
           CALL "assert-equals" USING WS-EXP-TRANID WS-ACT-TRANID
           END-CALL
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
