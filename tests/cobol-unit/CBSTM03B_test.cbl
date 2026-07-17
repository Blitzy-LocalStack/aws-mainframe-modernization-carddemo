      ****************************************************************
      * Program     : CBSTM03B_test.cbl
      * Application : CardDemo - COBOL unit-test suite (GCBLUnit layer)
      * Type        : Unit test  (op-code / status CONTRACT test)
      * Under test  : app/cbl/CBSTM03B.CBL  (REFERENCE ONLY - never
      *               modified; consumed as the unit under test only)
      *--------------------------------------------------------------
      * PURPOSE:
      *   Verify the observable CONTRACT of CBSTM03B, the statement-
      *   generator shared I/O subprogram called by CBSTM03A: the
      *   1040-byte LK-M03B-AREA communication layout, the six op-code
      *   88-level characters, their mutual exclusivity, and the four
      *   dispatch DD names.  Real VSAM file I/O is the integration
      *   layer's concern, so this UNIT layer asserts interface only.
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
       0000-MAIN.
      *    WHY (Assumption): GnuCOBOL zero-initialises EXTERNAL
      *    counters, but gcblunit-init is CALLed first to make the
      *    starting state explicit and deterministic for this test.
           CALL 'gcblunit-init'
           PERFORM 1000-ASSERT-LAYOUT
           PERFORM 2000-ASSERT-OPCODES
           PERFORM 3000-ASSERT-EXCLUSIVITY
           PERFORM 4000-ASSERT-DD-ROUTING
           PERFORM 9000-FINISH
           STOP RUN.
      *
      ****************************************************************
      * 1000-ASSERT-LAYOUT
      *   Pin the 1040-byte area and every field width.  The total-
      *   length check is the tripwire that fails if the manual mirror
      *   diverges from CBSTM03B's inline LINKAGE.
      ****************************************************************
       1000-ASSERT-LAYOUT.
           MOVE 1040 TO WS-EXP
           MOVE LENGTH OF WS-M03B-AREA TO WS-LEN
           CALL 'assert-equals' USING WS-EXP WS-LEN
           MOVE 1000 TO WS-EXP
           MOVE LENGTH OF WS-M03B-FLDT TO WS-LEN
           CALL 'assert-equals' USING WS-EXP WS-LEN
           MOVE 25 TO WS-EXP
           MOVE LENGTH OF WS-M03B-KEY TO WS-LEN
           CALL 'assert-equals' USING WS-EXP WS-LEN
           MOVE 8 TO WS-EXP
           MOVE LENGTH OF WS-M03B-DD TO WS-LEN
           CALL 'assert-equals' USING WS-EXP WS-LEN
           MOVE 2 TO WS-EXP
           MOVE LENGTH OF WS-M03B-RC TO WS-LEN
           CALL 'assert-equals' USING WS-EXP WS-LEN
           MOVE 4 TO WS-EXP
           MOVE LENGTH OF WS-M03B-KEY-LN TO WS-LEN
           CALL 'assert-equals' USING WS-EXP WS-LEN.
      *
      ****************************************************************
      * 2000-ASSERT-OPCODES
      *   Each SET <cond> TO TRUE must place the documented op-code
      *   character in WS-M03B-OPER.  W and Z are asserted too even
      *   though CBSTM03B never actions them (documented gap above).
      ****************************************************************
       2000-ASSERT-OPCODES.
           SET M03B-OPEN TO TRUE
           MOVE 'O' TO WS-EXP-X
           CALL 'assert-equals' USING WS-EXP-X WS-M03B-OPER
           SET M03B-CLOSE TO TRUE
           MOVE 'C' TO WS-EXP-X
           CALL 'assert-equals' USING WS-EXP-X WS-M03B-OPER
           SET M03B-READ TO TRUE
           MOVE 'R' TO WS-EXP-X
           CALL 'assert-equals' USING WS-EXP-X WS-M03B-OPER
           SET M03B-READ-K TO TRUE
           MOVE 'K' TO WS-EXP-X
           CALL 'assert-equals' USING WS-EXP-X WS-M03B-OPER
           SET M03B-WRITE TO TRUE
           MOVE 'W' TO WS-EXP-X
           CALL 'assert-equals' USING WS-EXP-X WS-M03B-OPER
           SET M03B-REWRITE TO TRUE
           MOVE 'Z' TO WS-EXP-X
           CALL 'assert-equals' USING WS-EXP-X WS-M03B-OPER.
      *
      ****************************************************************
      * 3000-ASSERT-EXCLUSIVITY
      *   With OPER = 'R' only M03B-READ is true; M03B-WRITE and
      *   M03B-OPEN are false.  Each 88 test is encoded as a 0/1 flag
      *   because GCBLUnit offers only equals / not-equals.
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
           IF M03B-WRITE
               MOVE 1 TO WS-FLAG
           ELSE
               MOVE 0 TO WS-FLAG
           END-IF
           MOVE 0 TO WS-EXP-FLG
           CALL 'assert-equals' USING WS-EXP-FLG WS-FLAG
           IF M03B-OPEN
               MOVE 1 TO WS-FLAG
           ELSE
               MOVE 0 TO WS-FLAG
           END-IF
           MOVE 0 TO WS-EXP-FLG
           CALL 'assert-equals' USING WS-EXP-FLG WS-FLAG.
      *
      ****************************************************************
      * 4000-ASSERT-DD-ROUTING
      *   Pin the four dispatch DD names EVALUATE LK-M03B-DD routes on.
      *   Each is exactly 8 bytes and fits WS-M03B-DD PIC X(08) with no
      *   truncation or padding, documenting the routing contract.
      ****************************************************************
       4000-ASSERT-DD-ROUTING.
           MOVE 'TRNXFILE' TO WS-M03B-DD
           MOVE 'TRNXFILE' TO WS-EXP-DD
           CALL 'assert-equals' USING WS-EXP-DD WS-M03B-DD
           MOVE 'XREFFILE' TO WS-M03B-DD
           MOVE 'XREFFILE' TO WS-EXP-DD
           CALL 'assert-equals' USING WS-EXP-DD WS-M03B-DD
           MOVE 'CUSTFILE' TO WS-M03B-DD
           MOVE 'CUSTFILE' TO WS-EXP-DD
           CALL 'assert-equals' USING WS-EXP-DD WS-M03B-DD
           MOVE 'ACCTFILE' TO WS-M03B-DD
           MOVE 'ACCTFILE' TO WS-EXP-DD
           CALL 'assert-equals' USING WS-EXP-DD WS-M03B-DD.
      *
      ****************************************************************
      * 9000-FINISH
      *   Publish the failure count into RETURN-CODE so the process
      *   exit status is the CI pass/fail signal (0 = PASS).
      ****************************************************************
       9000-FINISH.
           CALL 'gcblunit-summary'
           CALL 'gcblunit-result' USING WS-FAILS
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
