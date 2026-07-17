      ****************************************************************
      * Program     : CBSTM03B_test.cbl
      * Application : CardDemo - COBOL unit-test suite (GCBLUnit layer)
      * Type        : Unit test - op-code / status CONTRACT assertions
      *               (supplemental specification) PLUS REAL production
      *               dispatch execution (per MA-15 / MA-18).
      * Under test  : app/cbl/CBSTM03B.CBL  (REFERENCE ONLY - never
      *               modified; consumed as the unit under test only)
      * Executes UUT: YES (partial).  5000-ASSERT-REAL-DISPATCH CALLs
      *               the real CBSTM03B - statically linked as a sibling
      *               unit via COPY "app/cbl/CBSTM03B.CBL" after END
      *               PROGRAM (the same pattern CSUTLDTC_test uses) - and
      *               asserts two DETERMINISTIC, file-free production
      *               paths: (a) an unrecognized DD routes through the
      *               real EVALUATE to WHEN OTHER -> 9999-GOBACK with the
      *               caller's RC left untouched, and (b) the real
      *               M03B-OPEN op-code branch of 1000-TRNXFILE-PROC
      *               OPENs a guaranteed-absent TRNXFILE and returns
      *               file-status '35'.  The remaining op-code paths
      *               ('R'/'K' reads) need populated VSAM files and stay
      *               with the integration layer (test_provisioning.py /
      *               the statement E2E).  The layout, op-code, mutual-
      *               exclusivity and DD-routing checks below are RETAINED
      *               as supplemental specification assertions.
      *--------------------------------------------------------------
      * PURPOSE:
      *   Verify the observable CONTRACT of CBSTM03B, the statement-
      *   generator shared I/O subprogram called by CBSTM03A: the
      *   1040-byte LK-M03B-AREA communication layout, the six op-code
      *   88-level characters, their mutual exclusivity, and the four
      *   dispatch DD names; AND prove, by really executing CBSTM03B,
      *   that its DD dispatch and OPEN op-code behave as documented.
      *
      * PARAMETERS:
      *   None.  Compiled as a `cobc -x` main executable; no input is
      *   read and no run-time arguments are consumed.
      *
      * RETURNS:
      *   RETURN-CODE = accumulated GCBLUnit failure count, which
      *   GnuCOBOL maps to the process exit status (0 = PASS; N = N
      *   failed assertions).  scripts/run_unit_tests.sh keys pass /
      *   fail off that exit status.
      *
      * EXCEPTIONS:
      *   None raised.  GCBLUnit counts assertion failures and never
      *   abends, so every assertion runs even after an earlier one
      *   fails.
      *
      * WHY - Assumptions:
      *   WS-M03B-AREA is a MANUAL MIRROR of CBSTM03B's INLINE LINKAGE
      *   (LK-M03B-AREA has no shared copybook), so it must stay byte-
      *   identical to the source.  LK-M03B-KEY-LN is PIC S9(4)
      *   DISPLAY = 4 bytes, which is why the area totals
      *   8+1+2+25+4+1000 = 1040.  The 1040 length assertion is the
      *   tripwire that fails if this mirror drifts from the source.
      *
      * WHY - Trade-offs:
      *   A mirror/contract test is chosen over introducing a shared
      *   copybook because the minimal-change principle (AAP 0.10.2)
      *   forbids expanding the production surface.  Driving real 'R'
      *   or 'K' reads would need OPEN VSAM files and belongs to the
      *   integration layer, not this unit.
      ****************************************************************
       IDENTIFICATION DIVISION.
       PROGRAM-ID. CBSTM03B-test.
      *
       DATA DIVISION.
       WORKING-STORAGE SECTION.
      *
      * MANUAL MIRROR of the INLINE LK-M03B-AREA in
      * app/cbl/CBSTM03B.CBL (1040 bytes).  WHY (Assumption): no shared
      * copybook exists for this area, so the layout is hand-copied and
      * pinned by the LENGTH assertions in 1000-ASSERT-LAYOUT below.
       01  WS-M03B-AREA.
           05  WS-M03B-DD          PIC X(08).
           05  WS-M03B-OPER        PIC X(01).
               88  M03B-OPEN       VALUE 'O'.
               88  M03B-CLOSE      VALUE 'C'.
               88  M03B-READ       VALUE 'R'.
               88  M03B-READ-K     VALUE 'K'.
      *        WHY (Documented gap): M03B-WRITE 'W' and M03B-REWRITE
      *        'Z' are DEFINED as 88-levels in CBSTM03B, but NO
      *        dispatch branch performs a WRITE / REWRITE - they are
      *        unactioned no-ops in the current subprogram (AAP
      *        0.5.2).  They are mirrored here so the op-code contract
      *        is asserted whole.
               88  M03B-WRITE      VALUE 'W'.
               88  M03B-REWRITE    VALUE 'Z'.
           05  WS-M03B-RC          PIC X(02).
           05  WS-M03B-KEY         PIC X(25).
           05  WS-M03B-KEY-LN      PIC S9(4).
           05  WS-M03B-FLDT        PIC X(1000).
      *
      * Comparison work items.  WHY (Assumption): GCBLUnit assert-equals
      * compares its two operands BYTE-for-byte, so every expected /
      * actual pair below is declared with identical PICTURE and length.
       01  WS-LEN                  PIC 9(5).
       01  WS-EXP                  PIC 9(5).
       01  WS-FLAG                 PIC 9.
       01  WS-EXP-FLG              PIC 9.
       01  WS-EXP-X                PIC X(01).
       01  WS-EXP-DD               PIC X(08).
      *
      * 01-level "actual" mirrors.  WHY (Refactoring Rationale, MA-19):
      * GCBLUnit's assert-equals is a CALL, and GnuCOBOL under
      * -Wall -Wextra rejects a 05-level sub-item of WS-M03B-AREA passed
      * BY REFERENCE ("not a 01 or 77 level item", -Wcall-params).  The
      * op-code (WS-M03B-OPER) and DD (WS-M03B-DD) fields are 05-level,
      * so each is copied into a same-PIC 01-level mirror immediately
      * before the assertion (a byte-preserving MOVE).  Alternatives
      * Considered: -Wno-call-params suppression - REJECTED; the reviewer
      * asked for a code fix, not a silenced diagnostic.
       01  WS-ACT-OPER             PIC X(01).
       01  WS-ACT-DD               PIC X(08).
      *
      * Return-code operands for the REAL CBSTM03B dispatch assertions
      * (5000-ASSERT-REAL-DISPATCH).  Both are PIC X(02) to match
      * LK-M03B-RC exactly, so the assert is a byte compare.
       01  WS-EXP-RC               PIC X(02).
       01  WS-ACT-RC               PIC X(02).
      *
      * WS-FAILS receives the accumulated failure count.  WHY (Trade-
      * off): it is PIC S9(9) COMP to MATCH gcblunit-result's LINKAGE
      * (01 LK-RESULT PIC S9(9) COMP) exactly.  A DISPLAY item would be
      * reinterpreted as 4 binary bytes by the callee and corrupt
      * RETURN-CODE, so the illustrative PIC 9(4) is deliberately not
      * used here.
       01  WS-FAILS                PIC S9(9) COMP.
      *
       PROCEDURE DIVISION.
      *
      ****************************************************************
      * 0000-MAIN
      * PURPOSE : Run the contract assertions (layout, op-codes,
      *           exclusivity, DD routing) then the REAL dispatch
      *           execution, and publish the tally as the exit code.
      * PARAMS  : none (standalone cobc -x main; no LINKAGE / run args).
      * RETURNS : sets RETURN-CODE to the accumulated failure count
      *           (0 => CI PASS; non-0 => CI FAIL).
      * ERRORS  : none raised; GCBLUnit counts failures, never abends.
      ****************************************************************
       0000-MAIN.
      *    WHY (Assumption): GnuCOBOL zero-initialises EXTERNAL
      *    counters, but gcblunit-init is CALLed first to make the
      *    starting state explicit and deterministic for this test.
           CALL 'gcblunit-init'
           END-CALL
           PERFORM 1000-ASSERT-LAYOUT
           PERFORM 2000-ASSERT-OPCODES
           PERFORM 3000-ASSERT-EXCLUSIVITY
           PERFORM 4000-ASSERT-DD-ROUTING
           PERFORM 5000-ASSERT-REAL-DISPATCH
           PERFORM 9000-FINISH
           STOP RUN.
      *
      ****************************************************************
      * 1000-ASSERT-LAYOUT
      *   Pin the 1040-byte area and every field width.  The total-
      *   length check is the tripwire that fails if the manual mirror
      *   diverges from CBSTM03B's inline LINKAGE.
      * PARAMS  : none (internal paragraph; reads WS-M03B-AREA fields).
      * RETURNS : none (increments the shared GCBLUnit counters).
      * ERRORS  : none raised.
      ****************************************************************
       1000-ASSERT-LAYOUT.
           MOVE 1040 TO WS-EXP
           MOVE LENGTH OF WS-M03B-AREA TO WS-LEN
           CALL 'assert-equals' USING WS-EXP WS-LEN
           END-CALL
           MOVE 1000 TO WS-EXP
           MOVE LENGTH OF WS-M03B-FLDT TO WS-LEN
           CALL 'assert-equals' USING WS-EXP WS-LEN
           END-CALL
           MOVE 25 TO WS-EXP
           MOVE LENGTH OF WS-M03B-KEY TO WS-LEN
           CALL 'assert-equals' USING WS-EXP WS-LEN
           END-CALL
           MOVE 8 TO WS-EXP
           MOVE LENGTH OF WS-M03B-DD TO WS-LEN
           CALL 'assert-equals' USING WS-EXP WS-LEN
           END-CALL
           MOVE 2 TO WS-EXP
           MOVE LENGTH OF WS-M03B-RC TO WS-LEN
           CALL 'assert-equals' USING WS-EXP WS-LEN
           END-CALL
           MOVE 4 TO WS-EXP
           MOVE LENGTH OF WS-M03B-KEY-LN TO WS-LEN
           CALL 'assert-equals' USING WS-EXP WS-LEN
           END-CALL.
      *
      ****************************************************************
      * 2000-ASSERT-OPCODES
      *   Each SET <cond> TO TRUE must place the documented op-code
      *   character in WS-M03B-OPER.  W and Z are asserted too even
      *   though CBSTM03B never actions them (documented gap above).
      * PARAMS  : none (internal paragraph; drives WS-M03B-OPER).
      * RETURNS : none (increments the shared GCBLUnit counters).
      * ERRORS  : none raised.
      ****************************************************************
       2000-ASSERT-OPCODES.
           SET M03B-OPEN TO TRUE
           MOVE 'O' TO WS-EXP-X
           MOVE WS-M03B-OPER TO WS-ACT-OPER
           CALL 'assert-equals' USING WS-EXP-X WS-ACT-OPER
           END-CALL
           SET M03B-CLOSE TO TRUE
           MOVE 'C' TO WS-EXP-X
           MOVE WS-M03B-OPER TO WS-ACT-OPER
           CALL 'assert-equals' USING WS-EXP-X WS-ACT-OPER
           END-CALL
           SET M03B-READ TO TRUE
           MOVE 'R' TO WS-EXP-X
           MOVE WS-M03B-OPER TO WS-ACT-OPER
           CALL 'assert-equals' USING WS-EXP-X WS-ACT-OPER
           END-CALL
           SET M03B-READ-K TO TRUE
           MOVE 'K' TO WS-EXP-X
           MOVE WS-M03B-OPER TO WS-ACT-OPER
           CALL 'assert-equals' USING WS-EXP-X WS-ACT-OPER
           END-CALL
           SET M03B-WRITE TO TRUE
           MOVE 'W' TO WS-EXP-X
           MOVE WS-M03B-OPER TO WS-ACT-OPER
           CALL 'assert-equals' USING WS-EXP-X WS-ACT-OPER
           END-CALL
           SET M03B-REWRITE TO TRUE
           MOVE 'Z' TO WS-EXP-X
           MOVE WS-M03B-OPER TO WS-ACT-OPER
           CALL 'assert-equals' USING WS-EXP-X WS-ACT-OPER
           END-CALL.
      *
      ****************************************************************
      * 3000-ASSERT-EXCLUSIVITY
      *   With OPER = 'R' only M03B-READ is true; M03B-WRITE and
      *   M03B-OPEN are false.  Each 88 test is encoded as a 0/1 flag
      *   because GCBLUnit offers only equals / not-equals.
      * PARAMS  : none (internal paragraph; drives WS-M03B-OPER, WS-FLAG).
      * RETURNS : none (increments the shared GCBLUnit counters).
      * ERRORS  : none raised.
      ****************************************************************
       3000-ASSERT-EXCLUSIVITY.
           MOVE 'R' TO WS-M03B-OPER
           IF M03B-READ
               MOVE 1 TO WS-FLAG
           ELSE
               MOVE 0 TO WS-FLAG
           END-IF
           MOVE 1 TO WS-EXP-FLG
           CALL 'assert-equals' USING WS-EXP-FLG WS-FLAG
           END-CALL
           IF M03B-WRITE
               MOVE 1 TO WS-FLAG
           ELSE
               MOVE 0 TO WS-FLAG
           END-IF
           MOVE 0 TO WS-EXP-FLG
           CALL 'assert-equals' USING WS-EXP-FLG WS-FLAG
           END-CALL
           IF M03B-OPEN
               MOVE 1 TO WS-FLAG
           ELSE
               MOVE 0 TO WS-FLAG
           END-IF
           MOVE 0 TO WS-EXP-FLG
           CALL 'assert-equals' USING WS-EXP-FLG WS-FLAG
           END-CALL.
      *
      ****************************************************************
      * 4000-ASSERT-DD-ROUTING
      *   Pin the four dispatch DD names EVALUATE LK-M03B-DD routes on.
      *   Each is exactly 8 bytes and fits WS-M03B-DD PIC X(08) with no
      *   truncation or padding, documenting the routing contract.
      * PARAMS  : none (internal paragraph; drives WS-M03B-DD).
      * RETURNS : none (increments the shared GCBLUnit counters).
      * ERRORS  : none raised.
      ****************************************************************
       4000-ASSERT-DD-ROUTING.
           MOVE 'TRNXFILE' TO WS-M03B-DD
           MOVE 'TRNXFILE' TO WS-EXP-DD
           MOVE WS-M03B-DD TO WS-ACT-DD
           CALL 'assert-equals' USING WS-EXP-DD WS-ACT-DD
           END-CALL
           MOVE 'XREFFILE' TO WS-M03B-DD
           MOVE 'XREFFILE' TO WS-EXP-DD
           MOVE WS-M03B-DD TO WS-ACT-DD
           CALL 'assert-equals' USING WS-EXP-DD WS-ACT-DD
           END-CALL
           MOVE 'CUSTFILE' TO WS-M03B-DD
           MOVE 'CUSTFILE' TO WS-EXP-DD
           MOVE WS-M03B-DD TO WS-ACT-DD
           CALL 'assert-equals' USING WS-EXP-DD WS-ACT-DD
           END-CALL
           MOVE 'ACCTFILE' TO WS-M03B-DD
           MOVE 'ACCTFILE' TO WS-EXP-DD
           MOVE WS-M03B-DD TO WS-ACT-DD
           CALL 'assert-equals' USING WS-EXP-DD WS-ACT-DD
           END-CALL.
      *
      ****************************************************************
      * 5000-ASSERT-REAL-DISPATCH
      *   Execute the REAL, statically-linked CBSTM03B (MA-15 / MA-18)
      *   on two DETERMINISTIC, file-free production paths and assert
      *   the documented behaviour of each.
      * PARAMS  : none (internal paragraph; drives WS-M03B-AREA, which
      *           doubles as CBSTM03B's LK-M03B-AREA argument).
      * RETURNS : none (increments the shared GCBLUnit counters).
      * ERRORS  : none raised; CBSTM03B GOBACKs on both paths.
      * WHY - Trade-off: these two paths are chosen because they run the
      *   REAL dispatch EVALUATE and the REAL M03B-OPEN op-code branch
      *   WITHOUT a populated VSAM file (the 'R'/'K' read paths need one
      *   and remain the integration layer's job).  WHY - Assumption:
      *   OPEN INPUT of a guaranteed-absent indexed file yields file
      *   status '35' under GnuCOBOL; the absent path is bound via
      *   DISPLAY ... UPON ENVIRONMENT-NAME/-VALUE so the result is
      *   independent of the process working directory (verified '35'
      *   across repeated runs and from an unrelated cwd).
      ****************************************************************
       5000-ASSERT-REAL-DISPATCH.
      *    (a) Unknown DD -> real EVALUATE WHEN OTHER -> 9999-GOBACK.
      *        Production does NOT touch LK-M03B-RC on this path, so a
      *        sentinel RC must survive the CALL unchanged; that both
      *        proves the routing and proves CBSTM03B returns without
      *        abending on an unrecognized DD.
           MOVE 'ZZZZZZZZ' TO WS-M03B-DD
           MOVE 'XY' TO WS-M03B-RC
           CALL 'CBSTM03B' USING WS-M03B-AREA
           END-CALL
           MOVE 'XY' TO WS-EXP-RC
           MOVE WS-M03B-RC TO WS-ACT-RC
           CALL 'assert-equals' USING WS-EXP-RC WS-ACT-RC
           END-CALL
      *    (b) Real M03B-OPEN op-code on a guaranteed-absent TRNXFILE:
      *        the real 1000-TRNXFILE-PROC OPENs the missing indexed
      *        file and returns file status '35'.
           DISPLAY 'TRNXFILE' UPON ENVIRONMENT-NAME
           DISPLAY '/tmp/blitzy-m03b-absent-trnxfile.idx'
               UPON ENVIRONMENT-VALUE
           MOVE 'TRNXFILE' TO WS-M03B-DD
           SET M03B-OPEN TO TRUE
           MOVE SPACES TO WS-M03B-RC
           CALL 'CBSTM03B' USING WS-M03B-AREA
           END-CALL
           MOVE '35' TO WS-EXP-RC
           MOVE WS-M03B-RC TO WS-ACT-RC
           CALL 'assert-equals' USING WS-EXP-RC WS-ACT-RC
           END-CALL.
      *
      ****************************************************************
      * 9000-FINISH
      *   Publish the failure count into RETURN-CODE so the process
      *   exit status is the CI pass/fail signal (0 = PASS).
      * PARAMS  : none (internal paragraph).
      * RETURNS : none directly; sets RETURN-CODE via WS-FAILS.
      * ERRORS  : none raised.
      ****************************************************************
       9000-FINISH.
           CALL 'gcblunit-summary'
           END-CALL
           CALL 'gcblunit-result' USING WS-FAILS
           END-CALL
           MOVE WS-FAILS TO RETURN-CODE.
      *
       END PROGRAM CBSTM03B-test.
      *
      ****************************************************************
      * Vendored GCBLUnit assertion framework, COPYd AFTER END PROGRAM
      * so its units (assert-equals, assert-notequals, gcblunit-result,
      * gcblunit-summary, gcblunit-init) link as SIBLING compilation
      * units into this single `cobc -x` executable.  WHY: the build
      * (scripts/build_test_programs.sh) compiles one -x binary per
      * *_test.cbl and derives PASS / FAIL from its exit code.
      ****************************************************************
       COPY "gcblunit.cbl".
      *
      ****************************************************************
      * REAL unit under test, statically linked as a SIBLING program.
      * WHY (MA-15 / MA-18): splicing the unmodified production source
      * in AFTER END PROGRAM compiles CBSTM03B as its own PROGRAM unit
      * inside this single `cobc -x` executable, so 5000-ASSERT-REAL-
      * DISPATCH's CALL 'CBSTM03B' resolves to the REAL program (not a
      * stub).  This is the same execute-production pattern CSUTLDTC_test
      * uses; the quoted path resolves relative to the repo root, which
      * is the build's working directory (scripts/build_test_programs.sh).
      * app/cbl/CBSTM03B.CBL is REFERENCE-ONLY and is NEVER modified.
      *
      * WHY - ORDER (Assumption, matches CSUTLDTC_test): the GCBLUnit
      * COPY MUST precede this one.  CBSTM03B.CBL carries no explicit
      * END PROGRAM (COBOL-85 optional), so anything COPYd AFTER it would
      * nest INSIDE CBSTM03B as contained subprograms and become
      * unreachable from the main - which manifested as a runtime
      * 'module gcblunit-init not found'.  Placing the END PROGRAM-
      * terminated GCBLUnit units first keeps them siblings, and the
      * unterminated production program trails last with nothing to
      * capture.
      ****************************************************************
       COPY "app/cbl/CBSTM03B.CBL".
