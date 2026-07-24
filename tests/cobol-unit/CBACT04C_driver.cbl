      *****************************************************************
      * CBACT04C_driver.cbl   (PROGRAM-ID: CBACT04D)                  *
      *---------------------------------------------------------------*
      * Purpose:                                                      *
      *   Test-only driver that builds the EXTERNAL-PARMS linkage     *
      *   record CBACT04C declares and CALLs it, so the interest &    *
      *   fee calculator -- which cannot link as a standalone -x      *
      *   executable because it has PROCEDURE DIVISION USING          *
      *   EXTERNAL-PARMS -- can be run by the integration/E2E harness *
      *   (finding MA-01).                                            *
      *                                                               *
      * Parameters (linkage built for CBACT04C):                     *
      *   PARM-LENGTH PIC S9(04) COMP - halfword parm length; set to  *
      *     10 (the length of PARM-DATE), mirroring the z/OS JCL PARM *
      *     length prefix.                                            *
      *   PARM-DATE   PIC X(10)       - processing date CBACT04C uses *
      *     to prefix each generated interest TRAN-ID. Defaults to    *
      *     '2022071800' (the value in app/jcl/INTCALC.jcl) and is    *
      *     overridable via env var CARDDEMO_PARM_DATE for a          *
      *     deterministic per-test date.                             *
      *                                                               *
      * Environment inputs:                                          *
      *   CARDDEMO_PARM_DATE - optional 10-char date override.        *
      *                                                               *
      * Return codes (propagated as this program's RETURN-CODE):     *
      *   what CBACT04C returns on a clean GOBACK (0 = success), or   *
      *   16 if the CBACT04C module cannot be loaded / called.        *
      *                                                               *
      * Errors:                                                      *
      *   A failed dynamic CALL (module absent from COB_LIBRARY_PATH) *
      *   is caught by ON EXCEPTION and reported with RC=16. A file   *
      *   OPEN/I-O failure inside CBACT04C follows that program's own *
      *   fail-fast abend (LE CEE3ABD) and ends the process -- that   *
      *   documented behaviour is intentionally NOT masked here.      *
      *                                                               *
      * WHY (design rationale):                                      *
      *   - Assumption: CBACT04C is built as a shared MODULE (-m) and *
      *     resolved at run time by PROGRAM-ID via COB_LIBRARY_PATH   *
      *     (exported by scripts/test_env.sh); a dynamic literal CALL *
      *     binds it with no compile-time dependency.                 *
      *   - Alternatives Considered: passing the date on the command  *
      *     line was rejected -- CBACT04C reads it from the fixed      *
      *     EXTERNAL-PARMS linkage, so replicating that linkage is the *
      *     faithful adapter.                                         *
      *   - Trade-off: PARM-LENGTH is set even though CBACT04C never   *
      *     reads it, so the linkage is byte-for-byte what production  *
      *     JCL delivers -- a faithful contract over a minimal one.    *
      *****************************************************************
       IDENTIFICATION DIVISION.
       PROGRAM-ID. CBACT04D.
       ENVIRONMENT DIVISION.
       DATA DIVISION.
       WORKING-STORAGE SECTION.
      * Linkage image handed to CBACT04C, matching its LINKAGE SECTION
      * (01 EXTERNAL-PARMS / 05 PARM-LENGTH S9(04) COMP / PARM-DATE
      * X(10)) exactly.
       01  EXTERNAL-PARMS.
           05  PARM-LENGTH         PIC S9(04) COMP.
           05  PARM-DATE           PIC X(10).
      * Optional processing-date override read from the environment;
      * initialised to SPACES so an unset variable keeps the default.
       01  WS-ENV-DATE             PIC X(10) VALUE SPACES.
       PROCEDURE DIVISION.
       0000-MAIN.
      * Establish the deterministic default (matches INTCALC.jcl), then
      * allow an explicit environment override for date-pinned tests.
           MOVE '2022071800' TO PARM-DATE
           MOVE SPACES TO WS-ENV-DATE
           ACCEPT WS-ENV-DATE FROM ENVIRONMENT 'CARDDEMO_PARM_DATE'
           IF WS-ENV-DATE NOT = SPACES
      * WHY (Assumption): a non-blank value is a full 10-char date; the
      * harness supplies it in the CBACT04C-expected YYYYMMDDHH shape.
               MOVE WS-ENV-DATE TO PARM-DATE
           END-IF
           MOVE 10 TO PARM-LENGTH
      * Clear RETURN-CODE so the ON EXCEPTION branch (CBACT04C never
      * ran) reports an explicit failure code, not a stale value.
           MOVE 0 TO RETURN-CODE
           CALL 'CBACT04C' USING EXTERNAL-PARMS
               ON EXCEPTION
                   DISPLAY 'CBACT04D: cannot call CBACT04C'
                   MOVE 16 TO RETURN-CODE
               NOT ON EXCEPTION
                   DISPLAY 'CBACT04D: CBACT04C RC=' RETURN-CODE
           END-CALL
           GOBACK.
