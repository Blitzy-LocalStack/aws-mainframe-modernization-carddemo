      ************************************************************
      * Program-ID  : CBACT02C-test
      * Application : CardDemo automated test suite (UNIT layer)
      * Type        : GCBLUnit COBOL layout-contract test
      *               (supplemental specification, per MA-15 / MA-18)
      * Contract    : copybook CVACT02Y (CARD-RECORD, 150 bytes) -- the
      *               fixed-width record layout CBACT02C depends on
      * Executes UUT: NO. CBACT02C is a monolithic, file-driven main; its
      *               real read/print round-trip and record counts are
      *               owned by the integration layer (tests/integration/
      *               test_provisioning.py). This unit asserts ONLY the
      *               data contract (record/field lengths and byte offsets)
      *               and is deliberately retained as a supplemental
      *               specification test -- NOT a CBACT02C execution test.
      * Framework   : GCBLUnit 1.22.6 (COPY "gcblunit.cbl" at end)
      *
      * PURPOSE:
      *   Assert the single-sourced CVACT02Y (CARD-RECORD, 150
      *   bytes) layout contract and the fixed-width field-encoding
      *   / byte-offset integrity of the card master record.  This
      *   is the "asserting happy-path test" required for CBACT02C
      *   by AAP section 0.1.4.  CBACT02C itself is a monolithic,
      *   file-driven main (OPEN indexed CARDFILE -> READ INTO
      *   CARD-RECORD -> DISPLAY -> CLOSE); its real indexed-file
      *   read round-trip and record-count checks are exercised at
      *   the integration layer (tests/integration/
      *   test_provisioning.py), NOT here.
      *
      * PARAMETERS:
      *   None.  Standalone `cobc -x` executable with no LINKAGE.
      *
      * RETURNS:
      *   RETURN-CODE = accumulated assertion-failure count, which
      *   GnuCOBOL maps to the process exit status (0 => PASS,
      *   nonzero => FAIL == number of failed assertions).
      *
      * EXCEPTIONS:
      *   None raised.  GCBLUnit COUNTS and DISPLAYs failed
      *   assertions but never STOP RUNs nor abends, so one failure
      *   never masks the assertions that follow it.
      *
      * NOTES (REFERENCE-only / env bounds / documented quirk):
      *   - app/cbl/CBACT02C.cbl and app/cpy/CVACT02Y.cpy are
      *     consumed as REFERENCE only; never modified by this test.
      *   - CBACT02C's abend path (9999-ABEND-PROGRAM -> LE
      *     CEE3ABD, abend code 999) is environment-bounded and is
      *     NOT invoked at the unit layer; fail-fast abend behaviour
      *     is asserted by the integration suite, not by this
      *     layout-contract test.
      *   - The copybook field CARD-EXPIRAION-DATE is MISSPELLED
      *     ("EXPIRAION", missing the second I) in CVACT02Y.  The
      *     name is used here EXACTLY as the copybook spells it and
      *     is intentionally NOT "corrected", so this test stays
      *     byte-faithful to the production record contract.
      ************************************************************
       IDENTIFICATION DIVISION.
       PROGRAM-ID. CBACT02C-test.
      * WHY (MA-19): the obsolete AUTHOR paragraph was removed. AUTHOR is
      * an archaic COBOL construct that cobc flags under -Wobsolete, which
      * the test build (scripts/build_test_programs.sh) now treats as
      * fatal; authorship is recorded in the header comment block above.
       DATA DIVISION.
       WORKING-STORAGE SECTION.
      *
      * WHY - Assumption: CARD-RECORD and every subordinate field
      * width and offset are single-sourced from
      * app/cpy/CVACT02Y.cpy via COPY (resolved with -I app/cpy).
      * The record is deliberately NEVER redefined here, so if the
      * production copybook layout ever drifts this test fails
      * loudly instead of silently masking the change.
       COPY CVACT02Y.
      *
      * Numeric length-comparison pair.  Both items share ONE
      * picture (PIC 9(4)) because GCBLUnit assert-equals is a
      * byte-wise compare; identical PICTURE/USAGE/length makes
      * byte-equality equivalent to value-equality for the
      * LENGTH OF checks below.
       01  WS-EXPECTED-LEN           PIC 9(4).
       01  WS-ACTUAL-LEN             PIC 9(4).
      *
      * Expected slice values, each sized to EXACTLY its target
      * field width.  WHY - Assumption: assert-equals requires the
      * expected and actual operands to be the same length, so
      * these mirror the widths of the CARD-RECORD reference-
      * modified slices they are compared to.
       01  WS-EXP-CARD-NUM           PIC X(16)
                                     VALUE '4111111111111111'.
       01  WS-EXP-ACCT-ID            PIC X(11)
                                     VALUE '12345678901'.
       01  WS-EXP-CVV                PIC X(03)
                                     VALUE '123'.
       01  WS-EXP-EXP-DATE           PIC X(10)
                                     VALUE '2027-12-31'.
       01  WS-EXP-STATUS             PIC X(01)
                                     VALUE 'Y'.
      *
      * Receives the accumulated failure count from gcblunit-result
      * and is copied into RETURN-CODE to become the exit status.
       01  WS-FAILURES               PIC S9(9) COMP.
      *
      *-----------------------------------------------------------
      * ROUTINE : MAIN (implicit first paragraph of PROCEDURE DIVISION)
      * PURPOSE : Initialise GCBLUnit, assert the CVACT02Y record/field
      *           lengths and the fixed-width byte-offset round-trip of
      *           CARD-RECORD, then publish the tally and bridge the
      *           failure count to the process exit status.
      * PARAMS  : none (standalone cobc -x main; no LINKAGE).
      * RETURNS : sets RETURN-CODE = accumulated assertion-failure count.
      * ERRORS  : none raised; GCBLUnit counts failures, never abends.
      * WHY - Trade-off: offsets are asserted against positional
      *           CARD-RECORD(offset:len) slices rather than named fields
      *           so a shifted offset (not just a wrong value) fails.
      *-----------------------------------------------------------
       PROCEDURE DIVISION.
      *
      * WHY - Assumption: EXTERNAL counters are zero-initialised by
      * GnuCOBOL on first reference, but gcblunit-init is called up
      * front to make the starting state explicit and keep the test
      * reproducible regardless of prior in-process state
      * (financial-grade determinism).
           CALL 'gcblunit-init'
           END-CALL
      *
      *-----------------------------------------------------------
      * 1. Layout contract - record and per-field byte lengths
      *-----------------------------------------------------------
      * WHY - Trade-off: pinning LENGTH OF (not just comparing
      * field values) guards the 150-byte fixed-width record
      * geometry that the VSAM KSDS and every downstream reader
      * depend on; a value-only compare would not catch a widened
      * or dropped field.
           MOVE 150 TO WS-EXPECTED-LEN
           MOVE LENGTH OF CARD-RECORD TO WS-ACTUAL-LEN
           CALL 'assert-equals'
               USING WS-EXPECTED-LEN WS-ACTUAL-LEN
           END-CALL

           MOVE 16 TO WS-EXPECTED-LEN
           MOVE LENGTH OF CARD-NUM TO WS-ACTUAL-LEN
           CALL 'assert-equals'
               USING WS-EXPECTED-LEN WS-ACTUAL-LEN
           END-CALL

           MOVE 11 TO WS-EXPECTED-LEN
           MOVE LENGTH OF CARD-ACCT-ID TO WS-ACTUAL-LEN
           CALL 'assert-equals'
               USING WS-EXPECTED-LEN WS-ACTUAL-LEN
           END-CALL

           MOVE 3 TO WS-EXPECTED-LEN
           MOVE LENGTH OF CARD-CVV-CD TO WS-ACTUAL-LEN
           CALL 'assert-equals'
               USING WS-EXPECTED-LEN WS-ACTUAL-LEN
           END-CALL

           MOVE 50 TO WS-EXPECTED-LEN
           MOVE LENGTH OF CARD-EMBOSSED-NAME TO WS-ACTUAL-LEN
           CALL 'assert-equals'
               USING WS-EXPECTED-LEN WS-ACTUAL-LEN
           END-CALL

      * Documented quirk: field spelled CARD-EXPIRAION-DATE verbatim
           MOVE 10 TO WS-EXPECTED-LEN
           MOVE LENGTH OF CARD-EXPIRAION-DATE TO WS-ACTUAL-LEN
           CALL 'assert-equals'
               USING WS-EXPECTED-LEN WS-ACTUAL-LEN
           END-CALL

           MOVE 1 TO WS-EXPECTED-LEN
           MOVE LENGTH OF CARD-ACTIVE-STATUS TO WS-ACTUAL-LEN
           CALL 'assert-equals'
               USING WS-EXPECTED-LEN WS-ACTUAL-LEN
           END-CALL
      *
      *-----------------------------------------------------------
      * 2. Field-encoding / offset round-trip
      *-----------------------------------------------------------
      * WHY - Assumption: INITIALIZE gives the FILLER and any field
      * not asserted below a defined (space/zero) content, so the
      * populated record is fully reproducible run-to-run - a
      * determinism guard per AAP section 0.7.2, not a correctness
      * requirement of the asserted slices (each asserted byte
      * range is explicitly MOVEd).
           INITIALIZE CARD-RECORD
           MOVE '4111111111111111' TO CARD-NUM
           MOVE 12345678901 TO CARD-ACCT-ID
           MOVE 123 TO CARD-CVV-CD
           MOVE 'JOHN Q PUBLIC' TO CARD-EMBOSSED-NAME
      * Documented quirk: misspelled copybook field used as-is
           MOVE '2027-12-31' TO CARD-EXPIRAION-DATE
           MOVE 'Y' TO CARD-ACTIVE-STATUS
      *
      * WHY - Trade-off: asserting on positional
      * CARD-RECORD(offset:len) slices (not the named fields) pins
      * the exact fixed-width byte offsets of the record - the
      * data-encoding integrity called out in AAP section 0.1.1 -
      * which a plain field-to-field compare would silently satisfy
      * even if an offset shifted.
           CALL 'assert-equals'
               USING WS-EXP-CARD-NUM CARD-RECORD(1:16)
           END-CALL

           CALL 'assert-equals'
               USING WS-EXP-ACCT-ID CARD-RECORD(17:11)
           END-CALL

           CALL 'assert-equals'
               USING WS-EXP-CVV CARD-RECORD(28:3)
           END-CALL

           CALL 'assert-equals'
               USING WS-EXP-EXP-DATE CARD-RECORD(81:10)
           END-CALL

           CALL 'assert-equals'
               USING WS-EXP-STATUS CARD-RECORD(91:1)
           END-CALL
      *
      *-----------------------------------------------------------
      * 3. Publish result and bridge to the process exit code
      *-----------------------------------------------------------
      * WHY - scripts/run_unit_tests.sh derives PASS/FAIL solely
      * from the executable's exit status; gcblunit-result surfaces
      * the shared failure count and RETURN-CODE carries it out of
      * the process.
           CALL 'gcblunit-summary'
           END-CALL
           CALL 'gcblunit-result' USING WS-FAILURES
           END-CALL
           MOVE WS-FAILURES TO RETURN-CODE
           STOP RUN.
       END PROGRAM CBACT02C-test.
      *
      * WHY - Alternatives Considered: the GCBLUnit assertion/result
      * units are spliced in AFTER END PROGRAM as sibling
      * compilation units (rather than invoked through the upstream
      * free-format driver main) so a single `cobc -x` build yields
      * one self-contained test executable whose exit code is the
      * only CI signal, matching scripts/build_test_programs.sh
      * (one executable per *_test.cbl).
       COPY "gcblunit.cbl".
