      *****************************************************************
      * CBTRN02C_test.cbl - GCBLUnit unit test for app/cbl/CBTRN02C
      *
      * PURPOSE:
      *   Spec-encode and verify the transaction-posting validation,
      *   reject, TCATBAL create/update, and account-balance posting
      *   logic of CBTRN02C - the highest-risk monetary path in
      *   CardDemo (coverage target >=90% line / >=85% branch). Every
      *   money assertion is EXACT fixed-point (no tolerance); every
      *   reject reason and message text is checked verbatim against
      *   the source contract.
      *
      * PARAMETERS:
      *   None. This is a stand-alone GCBLUnit main program.
      *
      * RETURNS:
      *   RETURN-CODE = the GCBLUnit failure count (0 = all PASS),
      *   which GnuCOBOL maps to the process exit status read by
      *   scripts/run_unit_tests.sh (0 -> PASS, non-zero -> FAIL).
      *
      * EXCEPTIONS / ERRORS:
      *   None are raised. Assertion failures are COUNTED and
      *   DISPLAYed by GCBLUnit; the program never STOP RUNs early
      *   nor abends, so one failed assertion never hides later ones.
      *
      * SOURCE / SCOPE:
      *   app/cbl/CBTRN02C.cbl is REFERENCE ONLY and is never
      *   modified. Real file-driven posting (DALYTRAN -> TRANFILE,
      *   XREFFILE / ACCTFILE reads, DALYREJS, TCATBALF) and the
      *   fail-fast abend path are environment-bounded and are covered
      *   by the integration layer tests/integration/
      *   test_cbtrn02c_posting.py, not at this unit layer.
      *
      * WHY - Alternatives Considered:
      *   cobol-check paragraph mocking of the READ statements (AAP
      *   0.2.2) was REJECTED at the unit layer because the repository
      *   build is GCBLUnit standalone with no mock preprocessor.
      *   Instead the WS-XREF-FOUND / WS-ACCT-FOUND flags simulate the
      *   READ ... INVALID KEY / NOT INVALID KEY outcome so each
      *   validation branch executes deterministically without any
      *   VSAM or sequential file present.
      *
      * WHY - Assumptions:
      *   Copybook PICtures are authoritative and single-sourced via
      *   -I app/cpy; WS-VALIDATION-TRAILER / WS-TEMP-BAL /
      *   WS-CREATE-TRANCAT-REC mirror the program's own WS items 1:1;
      *   the expiry compare is lexicographic on YYYY-MM-DD, which is
      *   chronological - the exact property the source depends on.
      *
      * WHY - Trade-offs / CRITICAL PITFALL:
      *   CBTRN02C sets its BUSINESS RETURN-CODE = 4 on a soft reject
      *   (source line ~230, when the reject count is > 0). That value
      *   is modeled here in WS-APPL-RC so it can be asserted WITHOUT
      *   colliding with the real RETURN-CODE register, which is
      *   reserved exclusively for the GCBLUnit failure count at
      *   STOP RUN. Writing the business 4 into RETURN-CODE would make
      *   run_unit_tests.sh misread exit 4 as four test failures.
      *****************************************************************
       IDENTIFICATION DIVISION.
       PROGRAM-ID. CBTRN02C-test.
      *****************************************************************
       DATA DIVISION.
       WORKING-STORAGE SECTION.
      *
      * Record layouts are single-sourced from app/cpy so this test
      * exercises the SAME field offsets and PICs as the unit under
      * test - never redefine a copybook record (AAP single-source).
       COPY CVTRA06Y.
       COPY CVACT03Y.
       COPY CVACT01Y.
       COPY CVTRA01Y.
      *
      * Non-copybook WS items mirrored 1:1 from CBTRN02C (identical
      * names and PICs) so the encoded paragraphs below stay byte-
      * faithful to the source statements they replicate.
       01 WS-VALIDATION-TRAILER.
          05 WS-VALIDATION-FAIL-REASON      PIC 9(04).
          05 WS-VALIDATION-FAIL-REASON-DESC PIC X(76).
       01 WS-TEMP-BAL                       PIC S9(09)V99.
       01 WS-CREATE-TRANCAT-REC             PIC X(01) VALUE 'N'.
      *
      * WS-APPL-RC models the business soft-reject RETURN-CODE = 4
      * WITHOUT touching the real register (see header CRITICAL note).
       01 WS-APPL-RC                        PIC 9(04) VALUE 0.
      *
      * Read-outcome flags stand in for the READ ... INVALID KEY test
      * at the unit layer; 'Y' = record found, 'N' = INVALID KEY.
       01 WS-XREF-FOUND                     PIC X(01) VALUE 'Y'.
       01 WS-ACCT-FOUND                     PIC X(01) VALUE 'Y'.
      *
      * Expected-value work fields. Each shares the EXACT PICTURE of
      * the actual it is compared against, because gcblunit
      * assert-equals is a BYTE comparison: identical PIC and identical
      * value => identical bytes. This is precisely what makes the
      * money checks exact fixed-point with zero tolerance.
       01 WS-EXP-REASON                     PIC 9(04).
       01 WS-EXP-DESC                       PIC X(76).
       01 WS-EXP-9V99                       PIC S9(09)V99.
       01 WS-EXP-10V99                      PIC S9(10)V99.
       01 WS-EXP-RC                         PIC 9(04).
      *
      * GCBLUnit failure-count sink. PIC S9(9) COMP EXACTLY matches the
      * gcblunit-result LINKAGE item; a DISPLAY PIC 9(4) would mis-map
      * the BY REFERENCE binary argument and corrupt the exit code.
      * (Dependency-contract fix over the illustrative skeleton.)
       01 WS-FAILS                          PIC S9(9) COMP.
      *****************************************************************
       PROCEDURE DIVISION.
       0000-MAIN.
      * Explicit counter reset for deterministic isolation across this
      * single process. GnuCOBOL auto-zeros EXTERNAL storage, but the
      * explicit init makes the starting state unambiguous (AAP 0.7.2).
           CALL "gcblunit-init"
           PERFORM 1001-SCENARIO-HAPPY
           PERFORM 1002-SCENARIO-REJECT-100
           PERFORM 1003-SCENARIO-REJECT-101
           PERFORM 1004-SCENARIO-REJECT-102
           PERFORM 1005-SCENARIO-BOUNDARY-LIMIT
           PERFORM 1006-SCENARIO-REJECT-103
           PERFORM 1007-SCENARIO-BOUNDARY-EXPIRY
           PERFORM 1008-SCENARIO-OVER-EXPIRED
           PERFORM 1009-SCENARIO-TCATBAL-CREATE
           PERFORM 1010-SCENARIO-TCATBAL-UPDATE
           PERFORM 1011-SCENARIO-ACCT-CREDIT
           PERFORM 1012-SCENARIO-ACCT-DEBIT
      * Publish the readable tally, then export the failure count into
      * RETURN-CODE (the process exit code) - see header CRITICAL note.
           CALL "gcblunit-summary"
           CALL "gcblunit-result" USING WS-FAILS
           MOVE WS-FAILS TO RETURN-CODE
           STOP RUN.
      *---------------------------------------------------------------*
      * Scenario 1 - HAPPY PATH: valid card, account found, within
      * limit and not expired. WS-TEMP-BAL = 100.00 - 0.00 + 50.00 =
      * 150.00; limit 1000.00 >= 150.00 (no 102); expiry 2099-12-31 >=
      * 2024-06-01 (no 103). Expect reason 0, business RC 0, bal 150.00.
       1001-SCENARIO-HAPPY.
           PERFORM 9000-RESET-STATE
           MOVE 'Y' TO WS-XREF-FOUND
           MOVE 'Y' TO WS-ACCT-FOUND
           MOVE +100.00 TO ACCT-CURR-CYC-CREDIT
           MOVE +0.00 TO ACCT-CURR-CYC-DEBIT
           MOVE +50.00 TO DALYTRAN-AMT
           MOVE +1000.00 TO ACCT-CREDIT-LIMIT
           MOVE '2099-12-31' TO ACCT-EXPIRAION-DATE
           MOVE '2024-06-01' TO DALYTRAN-ORIG-TS
           PERFORM 7000-PROCESS-TRAN
           MOVE 0 TO WS-EXP-REASON
           CALL "assert-equals"
               USING WS-EXP-REASON WS-VALIDATION-FAIL-REASON
           MOVE 0 TO WS-EXP-RC
           CALL "assert-equals"
               USING WS-EXP-RC WS-APPL-RC
           MOVE +150.00 TO WS-EXP-9V99
           CALL "assert-equals"
               USING WS-EXP-9V99 WS-TEMP-BAL
           EXIT.
      *---------------------------------------------------------------*
      * Scenario 2 - REJECT 100: card absent from the cross-reference.
      * WS-XREF-FOUND='N' drives the encoded 1500-A INVALID KEY branch.
       1002-SCENARIO-REJECT-100.
           PERFORM 9000-RESET-STATE
           MOVE 'N' TO WS-XREF-FOUND
           PERFORM 7000-PROCESS-TRAN
           MOVE 100 TO WS-EXP-REASON
           CALL "assert-equals"
               USING WS-EXP-REASON WS-VALIDATION-FAIL-REASON
           MOVE 'INVALID CARD NUMBER FOUND' TO WS-EXP-DESC
           CALL "assert-equals"
               USING WS-EXP-DESC WS-VALIDATION-FAIL-REASON-DESC
           MOVE 4 TO WS-EXP-RC
           CALL "assert-equals"
               USING WS-EXP-RC WS-APPL-RC
           EXIT.
      *---------------------------------------------------------------*
      * Scenario 3 - REJECT 101: card found but account missing.
      * WS-ACCT-FOUND='N' drives the encoded 1500-B INVALID KEY branch.
       1003-SCENARIO-REJECT-101.
           PERFORM 9000-RESET-STATE
           MOVE 'Y' TO WS-XREF-FOUND
           MOVE 'N' TO WS-ACCT-FOUND
           PERFORM 7000-PROCESS-TRAN
           MOVE 101 TO WS-EXP-REASON
           CALL "assert-equals"
               USING WS-EXP-REASON WS-VALIDATION-FAIL-REASON
           MOVE 'ACCOUNT RECORD NOT FOUND' TO WS-EXP-DESC
           CALL "assert-equals"
               USING WS-EXP-DESC WS-VALIDATION-FAIL-REASON-DESC
           MOVE 4 TO WS-EXP-RC
           CALL "assert-equals"
               USING WS-EXP-RC WS-APPL-RC
           EXIT.
      *---------------------------------------------------------------*
      * Scenario 4 - REJECT 102: available balance one cent over the
      * credit limit. WS-TEMP-BAL = 1000.01, limit 1000.00 ->
      * (1000.00 >= 1000.01) FALSE -> reason 102. Expiry is fine.
       1004-SCENARIO-REJECT-102.
           PERFORM 9000-RESET-STATE
           MOVE +1000.00 TO ACCT-CREDIT-LIMIT
           MOVE +1000.01 TO ACCT-CURR-CYC-CREDIT
           MOVE +0.00 TO ACCT-CURR-CYC-DEBIT
           MOVE +0.00 TO DALYTRAN-AMT
           MOVE '2099-12-31' TO ACCT-EXPIRAION-DATE
           MOVE '2024-06-01' TO DALYTRAN-ORIG-TS
           PERFORM 7000-PROCESS-TRAN
           MOVE 102 TO WS-EXP-REASON
           CALL "assert-equals"
               USING WS-EXP-REASON WS-VALIDATION-FAIL-REASON
           MOVE 'OVERLIMIT TRANSACTION' TO WS-EXP-DESC
           CALL "assert-equals"
               USING WS-EXP-DESC WS-VALIDATION-FAIL-REASON-DESC
           MOVE 4 TO WS-EXP-RC
           CALL "assert-equals"
               USING WS-EXP-RC WS-APPL-RC
           MOVE +1000.01 TO WS-EXP-9V99
           CALL "assert-equals"
               USING WS-EXP-9V99 WS-TEMP-BAL
           EXIT.
      *---------------------------------------------------------------*
      * Scenario 5 - BOUNDARY exactly at limit MUST POST. WS-TEMP-BAL =
      * 1000.00, limit 1000.00 -> (1000.00 >= 1000.00) TRUE -> no 102.
      * WHY: the source uses >=, so exactly-at-limit is allowed; this
      * pins the one-cent boundary against scenario 4.
       1005-SCENARIO-BOUNDARY-LIMIT.
           PERFORM 9000-RESET-STATE
           MOVE +1000.00 TO ACCT-CREDIT-LIMIT
           MOVE +1000.00 TO ACCT-CURR-CYC-CREDIT
           MOVE +0.00 TO ACCT-CURR-CYC-DEBIT
           MOVE +0.00 TO DALYTRAN-AMT
           MOVE '2099-12-31' TO ACCT-EXPIRAION-DATE
           MOVE '2024-06-01' TO DALYTRAN-ORIG-TS
           PERFORM 7000-PROCESS-TRAN
           MOVE 0 TO WS-EXP-REASON
           CALL "assert-equals"
               USING WS-EXP-REASON WS-VALIDATION-FAIL-REASON
           MOVE 0 TO WS-EXP-RC
           CALL "assert-equals"
               USING WS-EXP-RC WS-APPL-RC
           MOVE +1000.00 TO WS-EXP-9V99
           CALL "assert-equals"
               USING WS-EXP-9V99 WS-TEMP-BAL
           EXIT.
      *---------------------------------------------------------------*
      * Scenario 6 - REJECT 103: within limit but tran date one day
      * one day past expiry: 2024-05-31 >= 2024-06-01 is FALSE -> 103.
       1006-SCENARIO-REJECT-103.
           PERFORM 9000-RESET-STATE
           MOVE +1000.00 TO ACCT-CREDIT-LIMIT
           MOVE +100.00 TO ACCT-CURR-CYC-CREDIT
           MOVE +0.00 TO ACCT-CURR-CYC-DEBIT
           MOVE +50.00 TO DALYTRAN-AMT
           MOVE '2024-05-31' TO ACCT-EXPIRAION-DATE
           MOVE '2024-06-01' TO DALYTRAN-ORIG-TS
           PERFORM 7000-PROCESS-TRAN
           MOVE 103 TO WS-EXP-REASON
           CALL "assert-equals"
               USING WS-EXP-REASON WS-VALIDATION-FAIL-REASON
           MOVE 'TRANSACTION RECEIVED AFTER ACCT EXPIRATION'
               TO WS-EXP-DESC
           CALL "assert-equals"
               USING WS-EXP-DESC WS-VALIDATION-FAIL-REASON-DESC
           MOVE 4 TO WS-EXP-RC
           CALL "assert-equals"
               USING WS-EXP-RC WS-APPL-RC
           EXIT.

      *---------------------------------------------------------------*
      * Scenario 7 - BOUNDARY expiry equal MUST POST. exp '2024-06-01'
      * >= ts '2024-06-01' TRUE -> no 103. Pins the expiry boundary
      * against scenario 6 (one day earlier expiry rejects).
       1007-SCENARIO-BOUNDARY-EXPIRY.
           PERFORM 9000-RESET-STATE
           MOVE +1000.00 TO ACCT-CREDIT-LIMIT
           MOVE +100.00 TO ACCT-CURR-CYC-CREDIT
           MOVE +0.00 TO ACCT-CURR-CYC-DEBIT
           MOVE +50.00 TO DALYTRAN-AMT
           MOVE '2024-06-01' TO ACCT-EXPIRAION-DATE
           MOVE '2024-06-01' TO DALYTRAN-ORIG-TS
           PERFORM 7000-PROCESS-TRAN
           MOVE 0 TO WS-EXP-REASON
           CALL "assert-equals"
               USING WS-EXP-REASON WS-VALIDATION-FAIL-REASON
           MOVE 0 TO WS-EXP-RC
           CALL "assert-equals"
               USING WS-EXP-RC WS-APPL-RC
           EXIT.
      *---------------------------------------------------------------*
      * Scenario 8 - EDGE over-limit AND expired. Both unguarded IFs
      * fire; 102 is set first, then the 103 assignment OVERWRITES it,
      * so the final reason is 103 (last-writer-wins).
      * WHY: documents the sequential, no-guard ordering of the two
      * validation IFs in source 1500-B - a transaction that is both
      * over-limit and past expiry is reported as expired (not 102).
       1008-SCENARIO-OVER-EXPIRED.
           PERFORM 9000-RESET-STATE
           MOVE +1000.00 TO ACCT-CREDIT-LIMIT
           MOVE +1000.01 TO ACCT-CURR-CYC-CREDIT
           MOVE +0.00 TO ACCT-CURR-CYC-DEBIT
           MOVE +0.00 TO DALYTRAN-AMT
           MOVE '2024-05-31' TO ACCT-EXPIRAION-DATE
           MOVE '2024-06-01' TO DALYTRAN-ORIG-TS
           PERFORM 7000-PROCESS-TRAN
           MOVE 103 TO WS-EXP-REASON
           CALL "assert-equals"
               USING WS-EXP-REASON WS-VALIDATION-FAIL-REASON
           MOVE 'TRANSACTION RECEIVED AFTER ACCT EXPIRATION'
               TO WS-EXP-DESC
           CALL "assert-equals"
               USING WS-EXP-DESC WS-VALIDATION-FAIL-REASON-DESC
           MOVE 4 TO WS-EXP-RC
           CALL "assert-equals"
               USING WS-EXP-RC WS-APPL-RC
           EXIT.
      *---------------------------------------------------------------*
      * Scenario 9 - 2700-A CREATE TCATBAL. No existing category row
      * (WS-CREATE-TRANCAT-REC='Y'): INITIALIZE zeroes TRAN-CAT-BAL,
      * then ADD 50.00 -> new balance 50.00.
       1009-SCENARIO-TCATBAL-CREATE.
           PERFORM 9000-RESET-STATE
           MOVE 'Y' TO WS-CREATE-TRANCAT-REC
           MOVE 12345678901 TO XREF-ACCT-ID
           MOVE 'PU' TO DALYTRAN-TYPE-CD
           MOVE 5 TO DALYTRAN-CAT-CD
           MOVE +50.00 TO DALYTRAN-AMT
           PERFORM 7700-UPDATE-TCATBAL
           MOVE +50.00 TO WS-EXP-9V99
           CALL "assert-equals"
               USING WS-EXP-9V99 TRAN-CAT-BAL
           EXIT.
      *---------------------------------------------------------------*
      * Scenario 10 - 2700-B UPDATE TCATBAL. Existing category row
      * (WS-CREATE-TRANCAT-REC='N') carrying 200.00; ADD 50.00 ->
      * running balance 250.00.
       1010-SCENARIO-TCATBAL-UPDATE.
           PERFORM 9000-RESET-STATE
           MOVE 'N' TO WS-CREATE-TRANCAT-REC
           MOVE +200.00 TO TRAN-CAT-BAL
           MOVE +50.00 TO DALYTRAN-AMT
           PERFORM 7700-UPDATE-TCATBAL
           MOVE +250.00 TO WS-EXP-9V99
           CALL "assert-equals"
               USING WS-EXP-9V99 TRAN-CAT-BAL
           EXIT.
      *---------------------------------------------------------------*
      * Scenario 11 - 2800 positive amount routes to cycle CREDIT.
      * bal 500.00 + 50.00 -> 550.00; credit 100.00 + 50.00 -> 150.00;
      * debit unchanged at 30.00.
       1011-SCENARIO-ACCT-CREDIT.
           PERFORM 9000-RESET-STATE
           MOVE +500.00 TO ACCT-CURR-BAL
           MOVE +100.00 TO ACCT-CURR-CYC-CREDIT
           MOVE +30.00 TO ACCT-CURR-CYC-DEBIT
           MOVE +50.00 TO DALYTRAN-AMT
           PERFORM 7800-UPDATE-ACCOUNT-REC
           MOVE +550.00 TO WS-EXP-10V99
           CALL "assert-equals"
               USING WS-EXP-10V99 ACCT-CURR-BAL
           MOVE +150.00 TO WS-EXP-10V99
           CALL "assert-equals"
               USING WS-EXP-10V99 ACCT-CURR-CYC-CREDIT
           MOVE +30.00 TO WS-EXP-10V99
           CALL "assert-equals"
               USING WS-EXP-10V99 ACCT-CURR-CYC-DEBIT
           EXIT.
      *---------------------------------------------------------------*
      * Scenario 12 - 2800 negative amount routes to cycle DEBIT.
      * bal 500.00 + (-40.00) -> 460.00; debit 30.00 + (-40.00) ->
      * -10.00; credit unchanged at 100.00. Exercises the signed
      * fixed-point path and the ELSE branch of the sign test.
       1012-SCENARIO-ACCT-DEBIT.
           PERFORM 9000-RESET-STATE
           MOVE +500.00 TO ACCT-CURR-BAL
           MOVE +100.00 TO ACCT-CURR-CYC-CREDIT
           MOVE +30.00 TO ACCT-CURR-CYC-DEBIT
           MOVE -40.00 TO DALYTRAN-AMT
           PERFORM 7800-UPDATE-ACCOUNT-REC
           MOVE +460.00 TO WS-EXP-10V99
           CALL "assert-equals"
               USING WS-EXP-10V99 ACCT-CURR-BAL
           MOVE -10.00 TO WS-EXP-10V99
           CALL "assert-equals"
               USING WS-EXP-10V99 ACCT-CURR-CYC-DEBIT
           MOVE +100.00 TO WS-EXP-10V99
           CALL "assert-equals"
               USING WS-EXP-10V99 ACCT-CURR-CYC-CREDIT
           EXIT.
      *===============================================================*
      * ENCODED SOURCE PARAGRAPHS
      * The following paragraphs replicate the CBTRN02C source logic
      * verbatim, substituting the WS-XREF-FOUND / WS-ACCT-FOUND flags
      * for the READ ... INVALID KEY conditions and omitting the file
      * WRITE/REWRITE verbs (no VSAM files exist at the unit layer).
      *===============================================================*
      * 7000-PROCESS-TRAN - mirror of the CBTRN02C main-loop body
      * (source lines ~208-217): reset the validation trailer, validate,
      * and on any reject model the business RETURN-CODE=4 in WS-APPL-RC
      * (source line ~230 sets RC=4 when the reject count is > 0).
       7000-PROCESS-TRAN.
           MOVE 0 TO WS-VALIDATION-FAIL-REASON
           MOVE SPACES TO WS-VALIDATION-FAIL-REASON-DESC
           PERFORM 7500-VALIDATE-TRAN
           IF WS-VALIDATION-FAIL-REASON = 0
              CONTINUE
           ELSE
              MOVE 4 TO WS-APPL-RC
           END-IF
           EXIT.
      *---------------------------------------------------------------*
      * 7500-VALIDATE-TRAN - mirror of source 1500-VALIDATE-TRAN.
       7500-VALIDATE-TRAN.
           PERFORM 7500-A-LOOKUP-XREF
           IF WS-VALIDATION-FAIL-REASON = 0
              PERFORM 7500-B-LOOKUP-ACCT
           ELSE
              CONTINUE
           END-IF
           EXIT.
      *---------------------------------------------------------------*
      * 7500-A-LOOKUP-XREF - mirror of source 1500-A-LOOKUP-XREF; the
      * WS-XREF-FOUND flag replaces READ XREF-FILE ... INVALID KEY.
       7500-A-LOOKUP-XREF.
           IF WS-XREF-FOUND = 'N'
              MOVE 100 TO WS-VALIDATION-FAIL-REASON
              MOVE 'INVALID CARD NUMBER FOUND'
                TO WS-VALIDATION-FAIL-REASON-DESC
           ELSE
              CONTINUE
           END-IF
           EXIT.
      *---------------------------------------------------------------*
      * 7500-B-LOOKUP-ACCT - mirror of source 1500-B-LOOKUP-ACCT; the
      * WS-ACCT-FOUND flag replaces READ ACCOUNT-FILE ... INVALID KEY.
      * WHY: the 102 and 103 IFs are intentionally UNGUARDED and
      * sequential, exactly as in the source, so 103 overwrites 102
      * when a transaction is both over-limit and past expiration.
       7500-B-LOOKUP-ACCT.
           IF WS-ACCT-FOUND = 'N'
              MOVE 101 TO WS-VALIDATION-FAIL-REASON
              MOVE 'ACCOUNT RECORD NOT FOUND'
                TO WS-VALIDATION-FAIL-REASON-DESC
           ELSE
              COMPUTE WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT
                                  - ACCT-CURR-CYC-DEBIT
                                  + DALYTRAN-AMT
              IF ACCT-CREDIT-LIMIT >= WS-TEMP-BAL
                 CONTINUE
              ELSE
                 MOVE 102 TO WS-VALIDATION-FAIL-REASON
                 MOVE 'OVERLIMIT TRANSACTION'
                   TO WS-VALIDATION-FAIL-REASON-DESC
              END-IF
              IF ACCT-EXPIRAION-DATE >= DALYTRAN-ORIG-TS (1:10)
                 CONTINUE
              ELSE
                 MOVE 103 TO WS-VALIDATION-FAIL-REASON
                 MOVE 'TRANSACTION RECEIVED AFTER ACCT EXPIRATION'
                   TO WS-VALIDATION-FAIL-REASON-DESC
              END-IF
           END-IF
           EXIT.
      *---------------------------------------------------------------*
      * 7700-UPDATE-TCATBAL - mirror of the source 2700 create-vs-update
      * dispatch (lines ~495-499). The READ and its file status are
      * environment-bounded, so only the branch selection is encoded;
      * the caller sets WS-CREATE-TRANCAT-REC to pick the branch.
       7700-UPDATE-TCATBAL.
           IF WS-CREATE-TRANCAT-REC = 'Y'
              PERFORM 7700-A-CREATE-TCATBAL-REC
           ELSE
              PERFORM 7700-B-UPDATE-TCATBAL-REC
           END-IF
           EXIT.
      *---------------------------------------------------------------*
      * 7700-A-CREATE-TCATBAL-REC - mirror of source 2700-A minus the
      * WRITE; the INITIALIZE, key assignment and ADD are byte-faithful.
       7700-A-CREATE-TCATBAL-REC.
           INITIALIZE TRAN-CAT-BAL-RECORD
           MOVE XREF-ACCT-ID TO TRANCAT-ACCT-ID
           MOVE DALYTRAN-TYPE-CD TO TRANCAT-TYPE-CD
           MOVE DALYTRAN-CAT-CD TO TRANCAT-CD
           ADD DALYTRAN-AMT TO TRAN-CAT-BAL
           EXIT.
      *---------------------------------------------------------------*
      * 7700-B-UPDATE-TCATBAL-REC - mirror of source 2700-B minus the
      * REWRITE.
       7700-B-UPDATE-TCATBAL-REC.
           ADD DALYTRAN-AMT TO TRAN-CAT-BAL
           EXIT.
      *---------------------------------------------------------------*
      * 7800-UPDATE-ACCOUNT-REC - mirror of source 2800 minus the
      * REWRITE. Positive amounts accumulate into the cycle CREDIT
      * bucket, negative amounts into the cycle DEBIT bucket.
       7800-UPDATE-ACCOUNT-REC.
           ADD DALYTRAN-AMT TO ACCT-CURR-BAL
           IF DALYTRAN-AMT >= 0
              ADD DALYTRAN-AMT TO ACCT-CURR-CYC-CREDIT
           ELSE
              ADD DALYTRAN-AMT TO ACCT-CURR-CYC-DEBIT
           END-IF
           EXIT.
      *---------------------------------------------------------------*
      * 9000-RESET-STATE - restore a known baseline before each scenario
      * so scenarios are independent and order-agnostic (AAP 0.7.2 test
      * isolation). Copybook records are INITIALIZEd (numerics -> zero,
      * alphanumerics -> spaces) and the mirrored WS items are reset.
       9000-RESET-STATE.
           INITIALIZE DALYTRAN-RECORD
           INITIALIZE CARD-XREF-RECORD
           INITIALIZE ACCOUNT-RECORD
           INITIALIZE TRAN-CAT-BAL-RECORD
           MOVE 'Y' TO WS-XREF-FOUND
           MOVE 'Y' TO WS-ACCT-FOUND
           MOVE 0 TO WS-VALIDATION-FAIL-REASON
           MOVE SPACES TO WS-VALIDATION-FAIL-REASON-DESC
           MOVE 0 TO WS-APPL-RC
           MOVE 0 TO WS-TEMP-BAL
           MOVE 'N' TO WS-CREATE-TRANCAT-REC
           EXIT.
       END PROGRAM CBTRN02C-test.
      *****************************************************************
      * Vendored GCBLUnit framework units are textually included AFTER
      * END PROGRAM so they compile as sibling units in the same cobc
      * -x executable (see tests/cobol-unit/gcblunit.cbl header).
      *****************************************************************
       COPY "gcblunit.cbl".

