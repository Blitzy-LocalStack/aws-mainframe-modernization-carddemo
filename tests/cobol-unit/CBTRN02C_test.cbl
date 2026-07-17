      *****************************************************************
      * CBTRN02C_test.cbl - GCBLUnit spec-replica test for CBTRN02C
      *
      * TYPE : GCBLUnit spec-replica / posting-validation contract test
      *        - a SUPPLEMENTAL SPECIFICATION (per MA-15 / MA-16),
      *        NOT a production run.
      * EXECUTES UUT: NO. CBTRN02C is a file-driven main that OPENs its
      *        VSAM/sequential files on entry and abends on a bad OPEN,
      *        so a direct in-process CALL with no real files would
      *        abend the test binary. This test REPLICATES the pure
      *        validation / reject / TCATBAL / posting statements
      *        verbatim (byte-faithful PICs and logic) and asserts
      *        fixed-point results; the REAL file-driven posting run is
      *        owned by the integration layer (see SOURCE / SCOPE and
      *        DOCUMENTED LIMITATIONS below).
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
      * DOCUMENTED LIMITATIONS (MA-16) - what this unit layer does NOT
      * cover, all delegated to the integration layer:
      *   - Real file WRITEs/REWRITEs (TRANFILE post, TCATBALF
      *     create/update, ACCTFILE update): only the in-memory field
      *     effects are asserted here; no record is physically written.
      *   - The DALYREJS reject-record OUTPUT stream: only the reject
      *     REASON code and message TEXT are asserted, not the emitted
      *     reject record image.
      *   - Real VSAM/sequential FILE STATUS values: the WS-*-FOUND
      *     flags stand in for READ ... INVALID KEY outcomes.
      *   - The real RETURN-CODE register: the business soft-reject
      *     RC=4 is modeled in WS-APPL-RC (see CRITICAL PITFALL below).
      *   These are asserted end-to-end against real datasets by
      *   tests/integration/test_cbtrn02c_posting.py.
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
      * Created-TCATBAL key expected mirrors (scenario 9, MA-16). The
      * 2700-A create path stamps the composite key AND the balance;
      * MA-16 flagged that only the balance was asserted, so the three
      * key fields are now pinned too.
       01 WS-EXP-TCB-ACCT                    PIC 9(11).
       01 WS-EXP-TCB-TYPE                    PIC X(02).
       01 WS-EXP-TCB-CD                      PIC 9(04).
      *
      * Actual-value 01-level mirrors for the copybook / group sub-items
      * asserted below. WHY - MA-19: gcblunit assert-equals is CALL'd
      * USING two args and cobc -Wcall-params (fires under -Wall) flags
      * any argument that is not a 01/77 item; WS-VALIDATION-FAIL-* are
      * 05-level group members and TRAN-CAT-BAL / ACCT-CURR-* /
      * TRANCAT-* are 05-level copybook members. Each mirror shares the
      * actual's EXACT PIC/USAGE and the value is MOVEd in byte-for-byte
      * right before the CALL, so byte-equality (== fixed-point
      * equality) is preserved. -Wno-call-params suppression was
      * REJECTED - the code is corrected so the warnings-fatal build
      * stays green without muting the check.
       01 WS-ACT-REASON                     PIC 9(04).
       01 WS-ACT-DESC                       PIC X(76).
       01 WS-ACT-TCB                        PIC S9(09)V99.
       01 WS-ACT-TCB-ACCT                   PIC 9(11).
       01 WS-ACT-TCB-TYPE                   PIC X(02).
       01 WS-ACT-TCB-CD                     PIC 9(04).
       01 WS-ACT-ABAL                       PIC S9(10)V99.
       01 WS-ACT-CCR                        PIC S9(10)V99.
       01 WS-ACT-CDB                        PIC S9(10)V99.
      *
      * GCBLUnit failure-count sink. PIC S9(9) COMP EXACTLY matches the
      * gcblunit-result LINKAGE item; a DISPLAY PIC 9(4) would mis-map
      * the BY REFERENCE binary argument and corrupt the exit code.
      * (Dependency-contract fix over the illustrative skeleton.)
       01 WS-FAILS                          PIC S9(9) COMP.
      *****************************************************************
       PROCEDURE DIVISION.
      * PARAMS : none (standalone cobc -x main; no run args).
      * RETURNS: sets RETURN-CODE = the GCBLUnit failure count.
      * ERRORS : none raised; STOP RUN is the only exit.
       0000-MAIN.
      * Explicit counter reset for deterministic isolation across this
      * single process. GnuCOBOL auto-zeros EXTERNAL storage, but the
      * explicit init makes the starting state unambiguous (AAP 0.7.2).
           CALL "gcblunit-init"
           END-CALL
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
           PERFORM 1013-SCENARIO-MAX-MAGNITUDE
      * Publish the readable tally, then export the failure count into
      * RETURN-CODE (the process exit code) - see header CRITICAL note.
           CALL "gcblunit-summary"
           END-CALL
           CALL "gcblunit-result" USING WS-FAILS
           END-CALL
           MOVE WS-FAILS TO RETURN-CODE
           STOP RUN.
      *---------------------------------------------------------------*
      * Scenario 1 - HAPPY PATH: valid card, account found, within
      * limit and not expired. WS-TEMP-BAL = 100.00 - 0.00 + 50.00 =
      * 150.00; limit 1000.00 >= 150.00 (no 102); expiry 2099-12-31 >=
      * 2024-06-01 (no 103). Expect reason 0, business RC 0, bal 150.00.
      * PARAMS : none (scenario state seeded in-line).
      * RETURNS: none; PERFORMs assertions that increment the
      *          GCBLUnit failure count on any mismatch.
      * ERRORS : none raised; assert-equals never abends.
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
           MOVE WS-VALIDATION-FAIL-REASON TO WS-ACT-REASON
           CALL "assert-equals"
               USING WS-EXP-REASON WS-ACT-REASON
           END-CALL
           MOVE 0 TO WS-EXP-RC
           CALL "assert-equals"
               USING WS-EXP-RC WS-APPL-RC
           END-CALL
           MOVE +150.00 TO WS-EXP-9V99
           CALL "assert-equals"
               USING WS-EXP-9V99 WS-TEMP-BAL
           END-CALL
           EXIT.
      *---------------------------------------------------------------*
      * Scenario 2 - REJECT 100: card absent from the cross-reference.
      * WS-XREF-FOUND='N' drives the encoded 1500-A INVALID KEY branch.
      * PARAMS : none (scenario state seeded in-line).
      * RETURNS: none; PERFORMs assertions that increment the
      *          GCBLUnit failure count on any mismatch.
      * ERRORS : none raised; assert-equals never abends.
       1002-SCENARIO-REJECT-100.
           PERFORM 9000-RESET-STATE
           MOVE 'N' TO WS-XREF-FOUND
           PERFORM 7000-PROCESS-TRAN
           MOVE 100 TO WS-EXP-REASON
           MOVE WS-VALIDATION-FAIL-REASON TO WS-ACT-REASON
           CALL "assert-equals"
               USING WS-EXP-REASON WS-ACT-REASON
           END-CALL
           MOVE 'INVALID CARD NUMBER FOUND' TO WS-EXP-DESC
           MOVE WS-VALIDATION-FAIL-REASON-DESC TO WS-ACT-DESC
           CALL "assert-equals"
               USING WS-EXP-DESC WS-ACT-DESC
           END-CALL
           MOVE 4 TO WS-EXP-RC
           CALL "assert-equals"
               USING WS-EXP-RC WS-APPL-RC
           END-CALL
           EXIT.
      *---------------------------------------------------------------*
      * Scenario 3 - REJECT 101: card found but account missing.
      * WS-ACCT-FOUND='N' drives the encoded 1500-B INVALID KEY branch.
      * PARAMS : none (scenario state seeded in-line).
      * RETURNS: none; PERFORMs assertions that increment the
      *          GCBLUnit failure count on any mismatch.
      * ERRORS : none raised; assert-equals never abends.
       1003-SCENARIO-REJECT-101.
           PERFORM 9000-RESET-STATE
           MOVE 'Y' TO WS-XREF-FOUND
           MOVE 'N' TO WS-ACCT-FOUND
           PERFORM 7000-PROCESS-TRAN
           MOVE 101 TO WS-EXP-REASON
           MOVE WS-VALIDATION-FAIL-REASON TO WS-ACT-REASON
           CALL "assert-equals"
               USING WS-EXP-REASON WS-ACT-REASON
           END-CALL
           MOVE 'ACCOUNT RECORD NOT FOUND' TO WS-EXP-DESC
           MOVE WS-VALIDATION-FAIL-REASON-DESC TO WS-ACT-DESC
           CALL "assert-equals"
               USING WS-EXP-DESC WS-ACT-DESC
           END-CALL
           MOVE 4 TO WS-EXP-RC
           CALL "assert-equals"
               USING WS-EXP-RC WS-APPL-RC
           END-CALL
           EXIT.
      *---------------------------------------------------------------*
      * Scenario 4 - REJECT 102: available balance one cent over the
      * credit limit. WS-TEMP-BAL = 1000.01, limit 1000.00 ->
      * (1000.00 >= 1000.01) FALSE -> reason 102. Expiry is fine.
      * PARAMS : none (scenario state seeded in-line).
      * RETURNS: none; PERFORMs assertions that increment the
      *          GCBLUnit failure count on any mismatch.
      * ERRORS : none raised; assert-equals never abends.
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
           MOVE WS-VALIDATION-FAIL-REASON TO WS-ACT-REASON
           CALL "assert-equals"
               USING WS-EXP-REASON WS-ACT-REASON
           END-CALL
           MOVE 'OVERLIMIT TRANSACTION' TO WS-EXP-DESC
           MOVE WS-VALIDATION-FAIL-REASON-DESC TO WS-ACT-DESC
           CALL "assert-equals"
               USING WS-EXP-DESC WS-ACT-DESC
           END-CALL
           MOVE 4 TO WS-EXP-RC
           CALL "assert-equals"
               USING WS-EXP-RC WS-APPL-RC
           END-CALL
           MOVE +1000.01 TO WS-EXP-9V99
           CALL "assert-equals"
               USING WS-EXP-9V99 WS-TEMP-BAL
           END-CALL
           EXIT.
      *---------------------------------------------------------------*
      * Scenario 5 - BOUNDARY exactly at limit MUST POST. WS-TEMP-BAL =
      * 1000.00, limit 1000.00 -> (1000.00 >= 1000.00) TRUE -> no 102.
      * WHY: the source uses >=, so exactly-at-limit is allowed; this
      * pins the one-cent boundary against scenario 4.
      * PARAMS : none (scenario state seeded in-line).
      * RETURNS: none; PERFORMs assertions that increment the
      *          GCBLUnit failure count on any mismatch.
      * ERRORS : none raised; assert-equals never abends.
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
           MOVE WS-VALIDATION-FAIL-REASON TO WS-ACT-REASON
           CALL "assert-equals"
               USING WS-EXP-REASON WS-ACT-REASON
           END-CALL
           MOVE 0 TO WS-EXP-RC
           CALL "assert-equals"
               USING WS-EXP-RC WS-APPL-RC
           END-CALL
           MOVE +1000.00 TO WS-EXP-9V99
           CALL "assert-equals"
               USING WS-EXP-9V99 WS-TEMP-BAL
           END-CALL
           EXIT.
      *---------------------------------------------------------------*
      * Scenario 6 - REJECT 103: within limit but tran date one day
      * one day past expiry: 2024-05-31 >= 2024-06-01 is FALSE -> 103.
      * PARAMS : none (scenario state seeded in-line).
      * RETURNS: none; PERFORMs assertions that increment the
      *          GCBLUnit failure count on any mismatch.
      * ERRORS : none raised; assert-equals never abends.
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
           MOVE WS-VALIDATION-FAIL-REASON TO WS-ACT-REASON
           CALL "assert-equals"
               USING WS-EXP-REASON WS-ACT-REASON
           END-CALL
           MOVE 'TRANSACTION RECEIVED AFTER ACCT EXPIRATION'
               TO WS-EXP-DESC
           MOVE WS-VALIDATION-FAIL-REASON-DESC TO WS-ACT-DESC
           CALL "assert-equals"
               USING WS-EXP-DESC WS-ACT-DESC
           END-CALL
           MOVE 4 TO WS-EXP-RC
           CALL "assert-equals"
               USING WS-EXP-RC WS-APPL-RC
           END-CALL
           EXIT.

      *---------------------------------------------------------------*
      * Scenario 7 - BOUNDARY expiry equal MUST POST. exp '2024-06-01'
      * >= ts '2024-06-01' TRUE -> no 103. Pins the expiry boundary
      * against scenario 6 (one day earlier expiry rejects).
      * PARAMS : none (scenario state seeded in-line).
      * RETURNS: none; PERFORMs assertions that increment the
      *          GCBLUnit failure count on any mismatch.
      * ERRORS : none raised; assert-equals never abends.
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
           MOVE WS-VALIDATION-FAIL-REASON TO WS-ACT-REASON
           CALL "assert-equals"
               USING WS-EXP-REASON WS-ACT-REASON
           END-CALL
           MOVE 0 TO WS-EXP-RC
           CALL "assert-equals"
               USING WS-EXP-RC WS-APPL-RC
           END-CALL
           EXIT.
      *---------------------------------------------------------------*
      * Scenario 8 - EDGE over-limit AND expired. Both unguarded IFs
      * fire; 102 is set first, then the 103 assignment OVERWRITES it,
      * so the final reason is 103 (last-writer-wins).
      * WHY: documents the sequential, no-guard ordering of the two
      * validation IFs in source 1500-B - a transaction that is both
      * over-limit and past expiry is reported as expired (not 102).
      * PARAMS : none (scenario state seeded in-line).
      * RETURNS: none; PERFORMs assertions that increment the
      *          GCBLUnit failure count on any mismatch.
      * ERRORS : none raised; assert-equals never abends.
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
           MOVE WS-VALIDATION-FAIL-REASON TO WS-ACT-REASON
           CALL "assert-equals"
               USING WS-EXP-REASON WS-ACT-REASON
           END-CALL
           MOVE 'TRANSACTION RECEIVED AFTER ACCT EXPIRATION'
               TO WS-EXP-DESC
           MOVE WS-VALIDATION-FAIL-REASON-DESC TO WS-ACT-DESC
           CALL "assert-equals"
               USING WS-EXP-DESC WS-ACT-DESC
           END-CALL
           MOVE 4 TO WS-EXP-RC
           CALL "assert-equals"
               USING WS-EXP-RC WS-APPL-RC
           END-CALL
           EXIT.
      *---------------------------------------------------------------*
      * Scenario 9 - 2700-A CREATE TCATBAL. No existing category row
      * (WS-CREATE-TRANCAT-REC='Y'): INITIALIZE zeroes TRAN-CAT-BAL,
      * then ADD 50.00 -> new balance 50.00.
      * PARAMS : none (scenario state seeded in-line).
      * RETURNS: none; PERFORMs assertions that increment the
      *          GCBLUnit failure count on any mismatch.
      * ERRORS : none raised; assert-equals never abends.
       1009-SCENARIO-TCATBAL-CREATE.
           PERFORM 9000-RESET-STATE
           MOVE 'Y' TO WS-CREATE-TRANCAT-REC
           MOVE 12345678901 TO XREF-ACCT-ID
           MOVE 'PU' TO DALYTRAN-TYPE-CD
           MOVE 5 TO DALYTRAN-CAT-CD
           MOVE +50.00 TO DALYTRAN-AMT
           PERFORM 7700-UPDATE-TCATBAL
      * MA-16: assert the CREATED composite key, not just the balance.
      * 2700-A stamps XREF-ACCT-ID -> TRANCAT-ACCT-ID, DALYTRAN-TYPE-CD
      * -> TRANCAT-TYPE-CD, DALYTRAN-CAT-CD -> TRANCAT-CD before adding.
           MOVE 12345678901 TO WS-EXP-TCB-ACCT
           MOVE TRANCAT-ACCT-ID TO WS-ACT-TCB-ACCT
           CALL "assert-equals"
               USING WS-EXP-TCB-ACCT WS-ACT-TCB-ACCT
           END-CALL
           MOVE 'PU' TO WS-EXP-TCB-TYPE
           MOVE TRANCAT-TYPE-CD TO WS-ACT-TCB-TYPE
           CALL "assert-equals"
               USING WS-EXP-TCB-TYPE WS-ACT-TCB-TYPE
           END-CALL
           MOVE 5 TO WS-EXP-TCB-CD
           MOVE TRANCAT-CD TO WS-ACT-TCB-CD
           CALL "assert-equals"
               USING WS-EXP-TCB-CD WS-ACT-TCB-CD
           END-CALL
           MOVE +50.00 TO WS-EXP-9V99
           MOVE TRAN-CAT-BAL TO WS-ACT-TCB
           CALL "assert-equals"
               USING WS-EXP-9V99 WS-ACT-TCB
           END-CALL
           EXIT.
      *---------------------------------------------------------------*
      * Scenario 10 - 2700-B UPDATE TCATBAL. Existing category row
      * (WS-CREATE-TRANCAT-REC='N') carrying 200.00; ADD 50.00 ->
      * running balance 250.00.
      * PARAMS : none (scenario state seeded in-line).
      * RETURNS: none; PERFORMs assertions that increment the
      *          GCBLUnit failure count on any mismatch.
      * ERRORS : none raised; assert-equals never abends.
       1010-SCENARIO-TCATBAL-UPDATE.
           PERFORM 9000-RESET-STATE
           MOVE 'N' TO WS-CREATE-TRANCAT-REC
           MOVE +200.00 TO TRAN-CAT-BAL
           MOVE +50.00 TO DALYTRAN-AMT
           PERFORM 7700-UPDATE-TCATBAL
           MOVE +250.00 TO WS-EXP-9V99
           MOVE TRAN-CAT-BAL TO WS-ACT-TCB
           CALL "assert-equals"
               USING WS-EXP-9V99 WS-ACT-TCB
           END-CALL
           EXIT.
      *---------------------------------------------------------------*
      * Scenario 11 - 2800 positive amount routes to cycle CREDIT.
      * bal 500.00 + 50.00 -> 550.00; credit 100.00 + 50.00 -> 150.00;
      * debit unchanged at 30.00.
      * PARAMS : none (scenario state seeded in-line).
      * RETURNS: none; PERFORMs assertions that increment the
      *          GCBLUnit failure count on any mismatch.
      * ERRORS : none raised; assert-equals never abends.
       1011-SCENARIO-ACCT-CREDIT.
           PERFORM 9000-RESET-STATE
           MOVE +500.00 TO ACCT-CURR-BAL
           MOVE +100.00 TO ACCT-CURR-CYC-CREDIT
           MOVE +30.00 TO ACCT-CURR-CYC-DEBIT
           MOVE +50.00 TO DALYTRAN-AMT
           PERFORM 7800-UPDATE-ACCOUNT-REC
           MOVE +550.00 TO WS-EXP-10V99
           MOVE ACCT-CURR-BAL TO WS-ACT-ABAL
           CALL "assert-equals"
               USING WS-EXP-10V99 WS-ACT-ABAL
           END-CALL
           MOVE +150.00 TO WS-EXP-10V99
           MOVE ACCT-CURR-CYC-CREDIT TO WS-ACT-CCR
           CALL "assert-equals"
               USING WS-EXP-10V99 WS-ACT-CCR
           END-CALL
           MOVE +30.00 TO WS-EXP-10V99
           MOVE ACCT-CURR-CYC-DEBIT TO WS-ACT-CDB
           CALL "assert-equals"
               USING WS-EXP-10V99 WS-ACT-CDB
           END-CALL
           EXIT.
      *---------------------------------------------------------------*
      * Scenario 12 - 2800 negative amount routes to cycle DEBIT.
      * bal 500.00 + (-40.00) -> 460.00; debit 30.00 + (-40.00) ->
      * -10.00; credit unchanged at 100.00. Exercises the signed
      * fixed-point path and the ELSE branch of the sign test.
      *
      * WHY - DOCUMENTED PRODUCTION QUIRK (MA-16), NOT desirable
      * behavior: source 2800 ADDs the SIGNED DALYTRAN-AMT straight
      * into ACCT-CURR-CYC-DEBIT, so a negative amount drives the
      * cycle-DEBIT bucket NEGATIVE (30.00 + -40.00 = -10.00) instead
      * of accumulating a positive debit total. This test pins that
      * literal behavior so the bucket sign stays auditable; any future
      * correction (e.g. accumulating the ABS value) deliberately
      * breaks this assertion and forces a conscious review. It does
      * NOT endorse the negative-bucket result as correct.
      * PARAMS : none (scenario state seeded in-line).
      * RETURNS: none; PERFORMs assertions that increment the
      *          GCBLUnit failure count on any mismatch.
      * ERRORS : none raised; assert-equals never abends.
       1012-SCENARIO-ACCT-DEBIT.
           PERFORM 9000-RESET-STATE
           MOVE +500.00 TO ACCT-CURR-BAL
           MOVE +100.00 TO ACCT-CURR-CYC-CREDIT
           MOVE +30.00 TO ACCT-CURR-CYC-DEBIT
           MOVE -40.00 TO DALYTRAN-AMT
           PERFORM 7800-UPDATE-ACCOUNT-REC
           MOVE +460.00 TO WS-EXP-10V99
           MOVE ACCT-CURR-BAL TO WS-ACT-ABAL
           CALL "assert-equals"
               USING WS-EXP-10V99 WS-ACT-ABAL
           END-CALL
           MOVE -10.00 TO WS-EXP-10V99
           MOVE ACCT-CURR-CYC-DEBIT TO WS-ACT-CDB
           CALL "assert-equals"
               USING WS-EXP-10V99 WS-ACT-CDB
           END-CALL
           MOVE +100.00 TO WS-EXP-10V99
           MOVE ACCT-CURR-CYC-CREDIT TO WS-ACT-CCR
           CALL "assert-equals"
               USING WS-EXP-10V99 WS-ACT-CCR
           END-CALL
           EXIT.
      *---------------------------------------------------------------*
      * Scenario 13 - MAX-MAGNITUDE boundary (MA-16 / AAP 0.1.1). Drive
      * WS-TEMP-BAL to the largest value its S9(09)V99 PIC can hold
      * (999999999.99) from S9(10)V99 operands, under an even larger
      * credit limit, and prove the posting still validates (reason 0)
      * with the balance carried EXACTLY - no zoned-decimal truncation
      * or sign loss at the top of the fixed-point range.
      * PARAMS : none (scenario state seeded in-line).
      * RETURNS: none; PERFORMs assertions that increment the
      *          GCBLUnit failure count on any mismatch.
      * ERRORS : none raised; assert-equals never abends.
       1013-SCENARIO-MAX-MAGNITUDE.
           PERFORM 9000-RESET-STATE
           MOVE +9999999999.99 TO ACCT-CREDIT-LIMIT
           MOVE +999999999.99 TO ACCT-CURR-CYC-CREDIT
           MOVE +0.00 TO ACCT-CURR-CYC-DEBIT
           MOVE +0.00 TO DALYTRAN-AMT
           MOVE '2099-12-31' TO ACCT-EXPIRAION-DATE
           MOVE '2024-06-01' TO DALYTRAN-ORIG-TS
           PERFORM 7000-PROCESS-TRAN
           MOVE 0 TO WS-EXP-REASON
           MOVE WS-VALIDATION-FAIL-REASON TO WS-ACT-REASON
           CALL "assert-equals"
               USING WS-EXP-REASON WS-ACT-REASON
           END-CALL
           MOVE +999999999.99 TO WS-EXP-9V99
           CALL "assert-equals"
               USING WS-EXP-9V99 WS-TEMP-BAL
           END-CALL
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
      * PARAMS : none (operates on the shared WS / copybook
      *          state established by the calling scenario).
      * RETURNS: none; mutates that shared state in place.
      * ERRORS : none raised.
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
      * PARAMS : none (operates on the shared WS / copybook
      *          state established by the calling scenario).
      * RETURNS: none; mutates that shared state in place.
      * ERRORS : none raised.
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
      * PARAMS : none (operates on the shared WS / copybook
      *          state established by the calling scenario).
      * RETURNS: none; mutates that shared state in place.
      * ERRORS : none raised.
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
      * PARAMS : none (operates on the shared WS / copybook
      *          state established by the calling scenario).
      * RETURNS: none; mutates that shared state in place.
      * ERRORS : none raised.
       7500-B-LOOKUP-ACCT.
           IF WS-ACCT-FOUND = 'N'
              MOVE 101 TO WS-VALIDATION-FAIL-REASON
              MOVE 'ACCOUNT RECORD NOT FOUND'
                TO WS-VALIDATION-FAIL-REASON-DESC
           ELSE
      * WHY - byte-faithful to source 1500-B COMPUTE (app/cbl/
      * CBTRN02C.cbl:403). It mixes S9(10)V99 operands into an
      * S9(09)V99 result, so cobc emits -Warithmetic-osvs ("precision
      * of result may change") EXACTLY as the production compile does.
      * Preserved deliberately (MA-16 byte-faithfulness); widening
      * WS-TEMP-BAL would diverge from the source PIC. The build gate
      * allowlists this single inherited warning (documented in
      * scripts/build_test_programs.sh) rather than muting -Wall.
              COMPUTE WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT
                                  - ACCT-CURR-CYC-DEBIT
                                  + DALYTRAN-AMT
              END-COMPUTE
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
      * PARAMS : none (operates on the shared WS / copybook
      *          state established by the calling scenario).
      * RETURNS: none; mutates that shared state in place.
      * ERRORS : none raised.
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
      * PARAMS : none (operates on the shared WS / copybook
      *          state established by the calling scenario).
      * RETURNS: none; mutates that shared state in place.
      * ERRORS : none raised.
       7700-A-CREATE-TCATBAL-REC.
           INITIALIZE TRAN-CAT-BAL-RECORD
           MOVE XREF-ACCT-ID TO TRANCAT-ACCT-ID
           MOVE DALYTRAN-TYPE-CD TO TRANCAT-TYPE-CD
           MOVE DALYTRAN-CAT-CD TO TRANCAT-CD
           ADD DALYTRAN-AMT TO TRAN-CAT-BAL
           END-ADD
           EXIT.
      *---------------------------------------------------------------*
      * 7700-B-UPDATE-TCATBAL-REC - mirror of source 2700-B minus the
      * REWRITE.
      * PARAMS : none (operates on the shared WS / copybook
      *          state established by the calling scenario).
      * RETURNS: none; mutates that shared state in place.
      * ERRORS : none raised.
       7700-B-UPDATE-TCATBAL-REC.
           ADD DALYTRAN-AMT TO TRAN-CAT-BAL
           END-ADD
           EXIT.
      *---------------------------------------------------------------*
      * 7800-UPDATE-ACCOUNT-REC - mirror of source 2800 minus the
      * REWRITE. Positive amounts accumulate into the cycle CREDIT
      * bucket, negative amounts into the cycle DEBIT bucket.
      * PARAMS : none (operates on the shared WS / copybook
      *          state established by the calling scenario).
      * RETURNS: none; mutates that shared state in place.
      * ERRORS : none raised.
       7800-UPDATE-ACCOUNT-REC.
           ADD DALYTRAN-AMT TO ACCT-CURR-BAL
           END-ADD
           IF DALYTRAN-AMT >= 0
              ADD DALYTRAN-AMT TO ACCT-CURR-CYC-CREDIT
              END-ADD
           ELSE
              ADD DALYTRAN-AMT TO ACCT-CURR-CYC-DEBIT
              END-ADD
           END-IF
           EXIT.
      *---------------------------------------------------------------*
      * 9000-RESET-STATE - restore a known baseline before each scenario
      * so scenarios are independent and order-agnostic (AAP 0.7.2 test
      * isolation). Copybook records are INITIALIZEd (numerics -> zero,
      * alphanumerics -> spaces) and the mirrored WS items are reset.
      * PARAMS : none (operates on the shared WS / copybook
      *          state established by the calling scenario).
      * RETURNS: none; mutates that shared state in place.
      * ERRORS : none raised.
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

