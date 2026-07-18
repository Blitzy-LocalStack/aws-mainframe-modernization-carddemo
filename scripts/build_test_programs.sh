#!/bin/bash
# =============================================================================
# scripts/build_test_programs.sh
# -----------------------------------------------------------------------------
# Purpose:
#   Compile the CardDemo batch programs under test (and, optionally, the
#   GCBLUnit COBOL test programs) from app/cbl/ into the build/ directory using
#   the repository's GnuCOBOL convention. The inventory is split into three
#   HONEST classes (finding CR-01) so the script never promises an output it
#   cannot produce:
#     * 8 SUPPORTED MAINS  built as executables (`cobc -x`);
#     * 3 CALL'd SUBPROGRAMS (CBACT04C, CSUTLDTC, CBSTM03B) built as shared
#       modules (`cobc -m`) so the runtime loader resolves them by PROGRAM-ID
#       via COB_LIBRARY_PATH (they declare PROCEDURE DIVISION USING ...);
#     * 2 KNOWN-UNSUPPORTED (CBEXPORT, CBIMPORT) that carry a verified,
#       unfixable defect in the immutable baseline -- attempted, expected to
#       fail, and reported honestly without poisoning the aggregate RC.
#   A test-only driver (CBACT04D) that supplies CBACT04C's EXTERNAL-PARMS
#   linkage is also built so the interest calculator is executable (MA-01).
#
# Usage:
#   scripts/build_test_programs.sh [--coverage] [--with-tests] [-h|--help]
#
# Parameters (CLI flags):
#   --coverage    Add gcov instrumentation to the transpiled C so line coverage
#                 can be collected on the GnuCOBOL output.
#   --with-tests  Also compile tests/cobol-unit/*_test.cbl if that tree exists.
#   -h, --help    Print usage and exit 0.
#
# Parameters (environment inputs, from scripts/test_env.sh):
#   CARDDEMO_REPO_ROOT, CARDDEMO_BUILD_DIR  - resolved by the sourced test_env.sh.
#   CARDDEMO_COVERAGE=1                      - alternative way to enable coverage.
#
# Return / Exit codes (CardDemo RC rubric):
#   0  all requested programs (and, if --with-tests, all test programs) compiled
#      cleanly and no known-unsupported program was attempted (or none exist).
#   4  warn: a KNOWN-UNSUPPORTED, immutable-baseline-blocked program
#      (CBEXPORT/CBIMPORT) failed to compile exactly as documented, OR such a
#      program UNEXPECTEDLY compiled (inventory drift). The core programs built,
#      so the suite still runs, but the aggregate is honestly non-green because a
#      required deliverable was NOT produced (finding F2). Opt in to --require-cobol
#      (scripts/run_tests.sh) to escalate this WARN to a hard failure.
#   8  cobc is unavailable; a promised (supported) program failed to compile; a
#      known-unsupported program failed for a DIFFERENT reason than documented; or
#      --with-tests was requested but the COBOL unit-test layer is absent/empty
#      (finding MA-03: an explicitly ENABLED layer that is missing is a hard
#      failure, never a soft warning).
#   2  operator usage error (unknown flag).
#
#   WHY (finding F2 reconciled with MA-03): RC=4 IS now produced -- for a
#   KNOWN-UNSUPPORTED, permanently-blocked deliverable (a documented compile
#   failure the AAP forbids fixing, since app/cbl is REFERENCE-only). This is
#   deliberately distinct from "missing build/test infrastructure", which remains
#   a hard RC=8: a blocked deliverable is a KNOWN condition worth flagging honestly
#   (non-green) WITHOUT permanently reddening CI, whereas missing infrastructure is
#   an actionable failure. Formerly this path returned 0, dishonestly reporting a
#   blocked deliverable as build success.
#
# Errors / Exceptions:
#   Missing cobc -> diagnostic to stderr, exit 8. A per-program compile failure
#   is reported and aggregated (build continues so every failure is surfaced in
#   one run).
#
# WHY (design rationale):
#   - Trade-off: `--std=ibm-strict` is used (matching scripts/local_compile.sh)
#     rather than `-std=cobol85`, because ibm-strict accepts the COMP-3
#     packed-decimal money fields that cobol85 rejects. This is the single most
#     important dialect decision for these financial programs.
#   - Assumption + financial-correctness fix (finding MA-02): `-fsign=EBCDIC` is
#     supplied. The shipped seed data and the Python record codec encode signed
#     money as IBM zoned-decimal OVERPUNCH (e.g. trailing `C`=+3, `L`=-3,
#     `{`=+0, `}`=-0). GnuCOBOL's DEFAULT `-fsign=ASCII` MISREADS those overpunch
#     bytes (a trailing `L` decodes as +0 instead of -3, silently dropping the
#     sign and CORRUPTING every negative balance), whereas `-fsign=EBCDIC`
#     decodes them exactly as the codec/overpunch tables do. Verified empirically
#     on cobc 3.2.0. local_compile.sh omits this flag safely only because it
#     never RUNS a program against signed data; this harness does, so it must set
#     it. NOTE the required syntax is `-fsign=EBCDIC` (with `=`); the bare
#     `-fsign-ascii`/`-fsign-ebcdic` spellings are silently ignored by this cobc.
#   - Assumption: CBSTM03A needs `-ftab-width=4` on top of `-fixed` because a
#     copybook it COPYs (CUSTREC.cpy) indents with hard TABs; without the
#     override cobc miscounts columns and reports "unbalanced parentheses". This
#     is applied per-program via carddemo_program_extra_flags, not globally.
#   - Alternatives Considered: driving the build with `make` was rejected -- the
#     repository ships no Makefile at app/ or root, so programs are compiled
#     directly with cobc.
#   - Refactoring rationale: the build is synchronous, so completion is detected
#     via cobc's own exit status (no fixed `sleep` pacing like the demo scripts).
#   - Assumption: source files are fixed-format COBOL (`-fixed`), copybooks live
#     in app/cpy/ (`-I`), and the two collided-name statement programs are stored
#     with an UPPERCASE .CBL extension (CBSTM03A.CBL, CBSTM03B.CBL) while all
#     others are lowercase .cbl -- so each source path is resolved case-aware.
#   - Refactoring rationale: the 3 CALL'd subprograms are compiled as `-m`
#     modules (not `-x`) because they declare `PROCEDURE DIVISION USING ...`;
#     GnuCOBOL refuses to link a USING program as a standalone executable, so the
#     only way to "produce them cleanly" is as dynamically-loaded shared modules
#     resolved at run time by PROGRAM-ID on COB_LIBRARY_PATH.
# =============================================================================

set -euo pipefail

# ---------------------------------------------------------------------------
# Load the shared environment (paths, RC rubric, helper functions).
# WHY (Refactoring rationale): every runner sources this one file so the RC
# rubric and ASSIGN bindings are defined in exactly one place.
# ---------------------------------------------------------------------------
_build_script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/test_env.sh
source "$_build_script_dir/test_env.sh"

# ---------------------------------------------------------------------------
# Program inventory (by PROGRAM NAME; extension resolved at compile time).
#
# The inventory is split into THREE honest classes (finding CR-01) instead of
# the previous flat 10-main list that falsely PROMISED outputs it could not
# produce:
#
#   * SUPPORTED MAINS (8): compile cleanly as `-x` executables under the
#     verified per-program recipes. These are the PROMISED outputs -- a failure
#     here is a real build failure (RC=8).
#   * MODULES (3): declare `PROCEDURE DIVISION USING ...`, so GnuCOBOL rejects
#     `-x` ("executable program requested but PROCEDURE/ENTRY has USING clause").
#     They are compiled as `-m` shared modules resolved at run time by PROGRAM-ID
#     on COB_LIBRARY_PATH (exported by test_env.sh). Also PROMISED outputs.
#   * KNOWN-UNSUPPORTED (2): CBEXPORT and CBIMPORT declare
#     `RECORD KEY IS EXPORT-SEQUENCE-NUM` on their FD, but that field is defined
#     only in WORKING-STORAGE (via CVEXPORT.cpy), NOT in the FD record. This is a
#     genuine, verified semantic defect in the IMMUTABLE baseline production
#     source (CBEXPORT.cbl:68 / CBIMPORT.cbl:40) that NO compiler flag can fix
#     and that the minimal-change/test-only principle forbids editing. They are
#     therefore NOT promised outputs: the build ATTEMPTS them, EXPECTS the
#     documented failure, and reports it honestly -- never a false success -- but
#     the documented failure does not poison the aggregate RC (see the
#     known-unsupported loop below).
# ---------------------------------------------------------------------------
CARDDEMO_MAIN_PROGRAMS=(CBACT01C CBACT02C CBACT03C CBCUS01C \
                        CBTRN01C CBTRN02C CBTRN03C CBSTM03A)
CARDDEMO_MODULE_PROGRAMS=(CBACT04C CSUTLDTC CBSTM03B)
CARDDEMO_UNSUPPORTED_PROGRAMS=(CBEXPORT CBIMPORT)

# The verified signature of the CBEXPORT/CBIMPORT baseline defect. The build
# recognises THIS specific compiler diagnostic as the documented, expected
# failure; any OTHER failure (or an unexpected success) is surfaced distinctly.
# WHY (Assumption): pinning the exact missing-field name means a future baseline
# change (e.g. the field being added to the FD) cannot be silently mistaken for
# the known defect -- it will be reported as an unexpected outcome to reconcile.
CARDDEMO_UNSUPPORTED_SIGNATURE="EXPORT-SEQUENCE-NUM"

# Test-only CBACT04C driver (finding MA-01): a `-x` main that constructs the
# EXTERNAL-PARMS linkage CBACT04C requires and CALLs it, so the interest
# calculator can be executed by the harness. It is a PROMISED output.
# WHY: it lives under tests/cobol-unit/ (test-only) with a non-`_test.cbl` name
# so the --with-tests glob does not also try to build it as a GCBLUnit test.
CARDDEMO_DRIVER_SOURCE="tests/cobol-unit/CBACT04C_driver.cbl"
CARDDEMO_DRIVER_NAME="CBACT04D"

# Default flags; extended when --coverage is requested.
CARDDEMO_COVERAGE="${CARDDEMO_COVERAGE:-0}"
CARDDEMO_WITH_TESTS=0

carddemo_build_usage() {
    # Purpose : print the CLI usage banner.
    # Parameters: none.
    # Returns : always 0.
    # Errors  : none.
    cat <<USAGE
Usage: build_test_programs.sh [--coverage] [--with-tests] [-h|--help]
  --coverage     add gcov instrumentation to the transpiled C
  --with-tests   also compile tests/cobol-unit/*_test.cbl when present
  -h, --help     show this help and exit 0
USAGE
}

# ---------------------------------------------------------------------------
# Parse CLI flags.
# WHY (Assumption): an unknown flag is an operator error, so we exit with the
# reserved usage code (2) rather than a rubric code, keeping test outcomes and
# invocation errors distinguishable in CI.
# ---------------------------------------------------------------------------
while [ "$#" -gt 0 ]; do
    case "$1" in
        --coverage)   CARDDEMO_COVERAGE=1 ;;
        --with-tests) CARDDEMO_WITH_TESTS=1 ;;
        -h|--help)    carddemo_build_usage; exit 0 ;;
        *) echo "[build] ERROR: unknown argument '$1'" >&2; carddemo_build_usage >&2
           exit "${CARDDEMO_RC_USAGE}" ;;
    esac
    shift
done

# ---------------------------------------------------------------------------
# Pre-flight guard: the compiler must be present.
# WHY (Trade-off): without cobc no layer can run, so a missing compiler is a
# hard FAIL (8), not a soft skip.
# ---------------------------------------------------------------------------
if ! carddemo_require_cmd cobc "Install GnuCOBOL (cobc >= 2.2; repo verified on 3.1.2)."; then
    exit "${CARDDEMO_RC_FAIL}"
fi

carddemo_find_source() {
    # Purpose : resolve a program NAME to its on-disk source path, case-aware.
    # Parameters:
    #   $1 (string) - program name without extension (e.g. CBTRN02C).
    # Returns : 0 and echoes the path if found; 1 if neither .cbl nor .CBL exists.
    # Errors  : none (absence is signalled by the return code).
    # WHY (Assumption): statement programs ship as UPPERCASE .CBL while the rest
    # are lowercase .cbl; checking both avoids hard-coding the case per program.
    local name="$1" base="$CARDDEMO_REPO_ROOT/app/cbl/$1"
    if [ -f "$base.cbl" ]; then
        echo "$base.cbl"
    elif [ -f "$base.CBL" ]; then
        echo "$base.CBL"
    else
        return 1
    fi
}

carddemo_cobc_flags() {
    # Purpose : emit the common cobc flag list (one token per line) shared by
    #           every compile, extended with gcov flags when coverage is on.
    # Parameters: none (reads CARDDEMO_COVERAGE / CARDDEMO_* env).
    # Returns : always 0; flags are written to stdout, newline-separated.
    # Errors  : none.
    # WHY (Refactoring rationale): centralising the flags guarantees the mains
    # and the modules are built with identical dialect/copybook settings.
    # WHY (MA-02 -- reconciled EBCDIC sign setting; financial correctness):
    # `-fsign=EBCDIC` is REQUIRED. The CardDemo ASCII seeds and the test fixtures
    # store signed money as trailing IBM overpunch ('{'==+0 .. 'I'==+9,
    # '}'==-0 .. 'R'==-9), matching tests/helpers/record_codec. GnuCOBOL's
    # DEFAULT (`-fsign=ASCII`) MISREADS that overpunch -- verified on this runner
    # that '19}' decodes to +190 (sign lost) under ASCII but correctly to -190
    # under EBCDIC, and '12L' decodes to +120 (wrong) vs -123 (correct). Omitting
    # this flag would silently corrupt every negative balance, so it is pinned
    # here rather than left to an environment-dependent default.
    printf '%s\n' -fixed -fsign=EBCDIC --std=ibm-strict \
        -I "$CARDDEMO_REPO_ROOT/app/cpy"
    if [ "$CARDDEMO_COVERAGE" = "1" ]; then
        # WHY (Assumption): GnuCOBOL transpiles to C, so gcov coverage is obtained
        # by passing --coverage through to the C compiler (-A) and linker (-Q),
        # and keeping the generated C (-save-temps) alongside the .gcno files.
        printf '%s\n' -g "-save-temps=$CARDDEMO_BUILD_DIR" -A --coverage -Q --coverage
    fi
}

carddemo_program_extra_flags() {
    # Purpose : emit any PER-PROGRAM extra cobc flags a specific source needs, on
    #           top of the common carddemo_cobc_flags (one token per line).
    # Parameters:
    #   $1 (string) - program name (e.g. CBSTM03A).
    # Returns : always 0; zero or more extra flag tokens on stdout.
    # Errors  : none.
    # WHY (MA-02 -- verified per-program recipe): CBSTM03A COPYs CUSTREC.cpy,
    # whose line 6 is indented with a literal TAB. Under the default fixed-format
    # tab handling GnuCOBOL mis-columns that line and fails with "unbalanced
    # parentheses". Compiling CBSTM03A with `-ftab-width=4` expands tabs the way
    # the copybook was authored and it then compiles cleanly (verified). Only
    # CBSTM03A needs this, so the override is scoped to it rather than applied
    # globally (a global tab-width could shift columns in the other sources).
    case "$1" in
        CBSTM03A) printf '%s\n' -ftab-width=4 ;;
        *) : ;;
    esac
}

carddemo_compile() {
    # Purpose : compile a single program as a main executable or shared module.
    # Parameters:
    #   $1 (string) - program name (e.g. CBTRN02C).
    #   $2 (string) - "main" for `-x` executable, "module" for `-m` shared object.
    # Returns : 0 on a clean compile; 8 if the source is missing or cobc fails.
    # Errors  : writes a diagnostic to stderr on failure.
    # WHY (Assumption): modules are emitted with an explicit .so name so the
    # GnuCOBOL runtime loader finds them on COB_LIBRARY_PATH by PROGRAM-ID.
    local name="$1" mode="$2" src out
    local -a flags extra
    mapfile -t flags < <(carddemo_cobc_flags)
    # Per-program recipe overrides (e.g. CBSTM03A tab-width), appended AFTER the
    # common flags so a program-specific token wins on any conflict.
    mapfile -t extra < <(carddemo_program_extra_flags "$name")
    if ! src="$(carddemo_find_source "$name")"; then
        echo "[build]   ERROR: no source (.cbl/.CBL) for '$name' under app/cbl/" >&2
        return "${CARDDEMO_RC_FAIL}"
    fi
    if [ "$mode" = "main" ]; then
        out="$CARDDEMO_BUILD_DIR/$name"
        cobc -x "${flags[@]}" "${extra[@]}" -o "$out" "$src" \
            || return "${CARDDEMO_RC_FAIL}"
    else
        out="$CARDDEMO_BUILD_DIR/$name.so"
        cobc -m "${flags[@]}" "${extra[@]}" -o "$out" "$src" \
            || return "${CARDDEMO_RC_FAIL}"
    fi
    return 0
}

carddemo_compile_unsupported() {
    # Purpose : ATTEMPT a known-unsupported program and classify the outcome
    #           honestly (finding CR-01) -- never reporting a false success.
    # Parameters:
    #   $1 (string) - program name (CBEXPORT / CBIMPORT).
    # Returns :
    #   4  EITHER (a) the documented baseline defect occurred exactly as expected
    #      (compile failed with the CARDDEMO_UNSUPPORTED_SIGNATURE diagnostic) --
    #      a BLOCKED DELIVERABLE that must be surfaced honestly (finding F2); OR
    #      (b) UNEXPECTED SUCCESS -- the source now compiles (the immutable
    #      baseline may have changed) and the inventory needs reconciling. Both
    #      are WARN: non-fatal (the core built, so the suite still runs) yet
    #      non-green, so neither a blocked deliverable nor inventory drift is
    #      silently reported as build success.
    #   8  UNEXPECTED FAILURE -- it failed for a DIFFERENT reason than the
    #      documented defect; surfaced as a real failure to investigate.
    # Errors  : writes the captured compiler diagnostics to stderr.
    # WHY (finding F2): the documented-defect path formerly returned 0, which let
    # the aggregate build report GREEN even though a required AAP deliverable
    # (CBEXPORT/CBIMPORT) was blocked and produced no binary -- redefining a
    # blocked deliverable as acceptable success. It now returns WARN(4): the exit
    # code honestly reflects "not fully built" while staying non-fatal so the rest
    # of the suite still runs (the conftest build fixture and every sub-runner
    # already treat build rc==4 as non-blocking and rc>=8 as fatal, so this change
    # aligns the build with the contract they were designed for).
    # WHY (Trade-off, WARN not FAIL): a blocked-but-documented deliverable is a
    # KNOWN, immutable-baseline condition (app/cbl is REFERENCE-only per AAP 0.8.2,
    # so the .cbl cannot be fixed), deliberately distinct from a real test failure.
    # An operator who wants it to be a hard error opts in via --require-cobol
    # (run_tests.sh), which the conftest gate honours. Attempting the compile (not
    # skipping) still detects an upstream fix immediately, and the signature check
    # keeps the "expected" path from masking a genuinely new error (which returns 8).
    local name="$1" src out log
    local -a flags
    mapfile -t flags < <(carddemo_cobc_flags)
    if ! src="$(carddemo_find_source "$name")"; then
        echo "[build]   ERROR: no source for known-unsupported '$name'" >&2
        return "${CARDDEMO_RC_FAIL}"
    fi
    out="$CARDDEMO_BUILD_DIR/$name"
    if log="$(cobc -x "${flags[@]}" -o "$out" "$src" 2>&1)"; then
        echo "[build]   UNEXPECTED SUCCESS: '$name' compiled." >&2
        echo "[build]   The immutable baseline may have changed; reconcile" >&2
        echo "[build]   CARDDEMO_UNSUPPORTED_PROGRAMS in this script." >&2
        return "${CARDDEMO_RC_WARN}"
    fi
    if printf '%s' "$log" | grep -q "$CARDDEMO_UNSUPPORTED_SIGNATURE"; then
        echo "[build]   KNOWN-UNSUPPORTED: '$name' cannot compile against the"
        echo "[build]   immutable baseline (missing FD key field"
        echo "[build]   '$CARDDEMO_UNSUPPORTED_SIGNATURE'); documented, expected."
        echo "[build]   -> aggregating WARN (rc=4): blocked deliverable makes the"
        echo "[build]   build non-green but stays non-fatal so the suite runs (F2)."
        return "${CARDDEMO_RC_WARN}"
    fi
    echo "[build]   UNEXPECTED FAILURE building '$name' (not the documented" >&2
    echo "[build]   '$CARDDEMO_UNSUPPORTED_SIGNATURE' defect):" >&2
    printf '%s\n' "$log" >&2
    return "${CARDDEMO_RC_FAIL}"
}

carddemo_test_warn_allowlist() {
    # Purpose : emit the per-test KNOWN-WARNINGS allowlist as a single grep -E
    #           pattern (or nothing), used by the --with-tests warnings-fatal
    #           gate (finding MA-19) to decide which -Wall/-Wextra diagnostics
    #           are tolerated for a given test program and which are fatal.
    # Parameters:
    #   $1 (string) - test program name WITHOUT extension (e.g. CBTRN02C_test).
    # Returns : always 0; on stdout either an extended-regexp string matching
    #           the documented, immutable warnings that test is allowed to
    #           carry, or NOTHING for tests that must be 100% warning-clean.
    # Errors  : none.
    #
    # WHY (Trade-off -- documented allowlist vs. blanket -Wno-*): three tests
    # legitimately carry warnings we CANNOT remove without editing immutable
    # files (app/** is out of scope and enforced clean by `git diff --quiet`):
    #   * CBSTM03B_test / CSUTLDTC_test EXECUTE the real UUT by COPY-ing the
    #     production source; the warnings are attributed to app/cbl/CBSTM03B.CBL
    #     (lines 3, 118) and app/cbl/CSUTLDTC.cbl (lines 116, 122) -- i.e. they
    #     live in the frozen production code, not in the test.
    #   * CBTRN02C_test faithfully REPLICATES the production COMPUTE at
    #     app/cbl/CBTRN02C.cbl:403 (identical PIC S9(09)V99 operands), so it
    #     inherits the same -Warithmetic-osvs the production program emits;
    #     "fixing" it would make the replica diverge from the code it documents.
    # Alternatives considered: (a) `-Wno-terminator`/`-Wno-arithmetic-osvs` --
    # rejected, it would also mask genuinely NEW defects of the same class in
    # the test's own code; (b) pinning the exact test-file line -- rejected for
    # the osvs case because that line moves as the test is edited (it shifted
    # 618->689 when MA-20 contracts were added), so we match the STABLE warning
    # CLASS tag `[-Warithmetic-osvs]` instead. For the two production-attributed
    # cases we match "<basename>:<line>:" (no path prefix) so the pattern holds
    # whether cobc renders the COPY path relative ("app/cbl/CSUTLDTC.cbl:116")
    # or absolute ("/.../app/cbl/CSUTLDTC.cbl:116") -- both were observed
    # depending on whether the source resolves via cwd or via the -I repo root.
    # Assumption: app/** is immutable this session, so the production line
    # numbers (3/118, 116/122) are stable and safe to pin.
    case "$1" in
        CBSTM03B_test) printf '%s\n' 'CBSTM03B\.CBL:(3|118):' ;;
        CSUTLDTC_test) printf '%s\n' 'CSUTLDTC\.cbl:(116|122):' ;;
        CBTRN02C_test) printf '%s\n' '\[-Warithmetic-osvs\]' ;;
        *) : ;;   # every other test must be completely warning-clean
    esac
}

# ---------------------------------------------------------------------------
# Compile everything, aggregating to the worst RC.
# ---------------------------------------------------------------------------
# WHY (MA-02 -- controlled cwd): anchor the working directory at the repo root
# for the whole build so nothing resolves relative to the caller's cwd. Every
# path the build uses is already absolute (via $CARDDEMO_REPO_ROOT), so this is
# belt-and-braces, but it makes the build deterministic regardless of where it
# is invoked from and removes the "compilation depends on current directory"
# hazard the finding calls out.
cd "$CARDDEMO_REPO_ROOT" || { echo "[build] ERROR: cannot cd to repo root" >&2; exit "$CARDDEMO_RC_FAIL"; }

overall_rc=0
echo "[build] ============================================================"
echo "[build] Compiling CardDemo programs under test (ibm-strict, fsign=EBCDIC)"
echo "[build]   repo   : $CARDDEMO_REPO_ROOT"
echo "[build]   output : $CARDDEMO_BUILD_DIR"
echo "[build]   coverage: $CARDDEMO_COVERAGE"
echo "[build] ============================================================"

for _p in "${CARDDEMO_MAIN_PROGRAMS[@]}"; do
    echo "[build] main   : $_p"
    if carddemo_compile "$_p" main; then
        echo "[build]   OK -> $CARDDEMO_BUILD_DIR/$_p"
    else
        echo "[build]   FAILED: $_p" >&2
        overall_rc="$(carddemo_rc_worst "$overall_rc" "$CARDDEMO_RC_FAIL")"
    fi
done

for _p in "${CARDDEMO_MODULE_PROGRAMS[@]}"; do
    echo "[build] module : $_p"
    if carddemo_compile "$_p" module; then
        echo "[build]   OK -> $CARDDEMO_BUILD_DIR/$_p.so"
    else
        echo "[build]   FAILED: $_p" >&2
        overall_rc="$(carddemo_rc_worst "$overall_rc" "$CARDDEMO_RC_FAIL")"
    fi
done

# ---------------------------------------------------------------------------
# Build the test-only CBACT04C driver (finding MA-01) -- a PROMISED output that
# makes the USING-linkage interest calculator runnable. It compiles as a `-x`
# main; the CBACT04C module it CALLs is resolved at run time via
# COB_LIBRARY_PATH, so the build order above is sufficient.
# ---------------------------------------------------------------------------
echo "[build] driver : $CARDDEMO_DRIVER_NAME (for CBACT04C)"
if [ -f "$CARDDEMO_REPO_ROOT/$CARDDEMO_DRIVER_SOURCE" ]; then
    _drv_flags=()
    mapfile -t _drv_flags < <(carddemo_cobc_flags)
    if cobc -x "${_drv_flags[@]}" \
            -o "$CARDDEMO_BUILD_DIR/$CARDDEMO_DRIVER_NAME" \
            "$CARDDEMO_REPO_ROOT/$CARDDEMO_DRIVER_SOURCE"; then
        echo "[build]   OK -> $CARDDEMO_BUILD_DIR/$CARDDEMO_DRIVER_NAME"
    else
        echo "[build]   FAILED: $CARDDEMO_DRIVER_NAME" >&2
        overall_rc="$(carddemo_rc_worst "$overall_rc" "$CARDDEMO_RC_FAIL")"
    fi
else
    # The driver is an in-repo test artifact; its absence is a real build
    # problem (RC=8), not a soft skip -- it is a promised output (MA-03).
    echo "[build]   ERROR: driver source missing: $CARDDEMO_DRIVER_SOURCE" >&2
    overall_rc="$(carddemo_rc_worst "$overall_rc" "$CARDDEMO_RC_FAIL")"
fi

# ---------------------------------------------------------------------------
# Attempt the KNOWN-UNSUPPORTED programs and report honestly (findings CR-01, F2).
# The documented baseline defect now WARNs (rc=4) so the aggregate is honestly
# non-green (a required deliverable was not produced); an unexpected outcome
# (success, or a different error) is surfaced at rc=4/rc=8 respectively.
# ---------------------------------------------------------------------------
for _p in "${CARDDEMO_UNSUPPORTED_PROGRAMS[@]}"; do
    echo "[build] unsup  : $_p (known-unsupported; attempting)"
    # WHY (errexit-safe, finding F2): this call now returns a non-zero WARN(4) for
    # the documented-defect path (it used to return 0). Under `set -euo pipefail`
    # a BARE call would abort the entire build the instant it returns non-zero --
    # skipping CBIMPORT and the final aggregate/exit. Capturing via `|| _unsup_rc=$?`
    # (the same errexit suppression the main/module loops get for free from their
    # `if carddemo_compile ...; then` guards) lets the loop process every program
    # and fold each outcome into overall_rc. Alternatives Considered: wrapping the
    # call in `if ! ...; then` was rejected because we need the EXACT rc (4 vs 8),
    # not just pass/fail.
    _unsup_rc=0
    carddemo_compile_unsupported "$_p" || _unsup_rc=$?
    # WHY (finding F2): every non-zero outcome here is aggregated -- RC 4 (blocked
    # deliverable failing as documented, OR unexpected success/inventory drift) and
    # RC 8 (unexpected different failure) all surface in the total so the build can
    # never report green while a required program is missing its binary.
    overall_rc="$(carddemo_rc_worst "$overall_rc" "$_unsup_rc")"
done

# ---------------------------------------------------------------------------
# Optionally compile the GCBLUnit test programs.
# WHY (MA-03 -- an explicitly-enabled layer's absence is a hard failure): when
# the operator passes --with-tests they are ENABLING the COBOL unit-test layer,
# so a missing tree or an empty glob is an ABSENT ENABLED LAYER and returns
# RC=8, NOT the soft RC=4 the previous version used. RC=4 is reserved for
# expected business rejects, never for missing test infrastructure. (Without
# --with-tests the layer is simply not requested and nothing happens here.)
# ---------------------------------------------------------------------------
if [ "$CARDDEMO_WITH_TESTS" = "1" ]; then
    _tdir="$CARDDEMO_REPO_ROOT/tests/cobol-unit"
    if [ -d "$_tdir" ]; then
        shopt -s nullglob
        _tfiles=("$_tdir"/*_test.cbl "$_tdir"/*_test.CBL)
        shopt -u nullglob
        if [ "${#_tfiles[@]}" -eq 0 ]; then
            echo "[build] ERROR: --with-tests requested but no *_test.cbl in $_tdir" >&2
            overall_rc="$(carddemo_rc_worst "$overall_rc" "$CARDDEMO_RC_FAIL")"
        else
            local_flags=()
            mapfile -t local_flags < <(carddemo_cobc_flags)
            for _tf in "${_tfiles[@]}"; do
                _tname="$(basename "${_tf%.*}")"
                echo "[build] test   : $_tname"
                # Test programs may need the same per-program recipe as the UUT
                # they exercise (e.g. a CBSTM03A-derived test needs the tab-width
                # override), so their extra flags are resolved by name too.
                _textra=()
                mapfile -t _textra < <(carddemo_program_extra_flags "$_tname")
                # WHY (MA-19 -- warnings-fatal gate): the COBOL unit layer is
                # held to a ZERO-WARNING standard. We compile every test under
                # -Wall -Wextra, capture the diagnostics, and FAIL the build on
                # any warning that is not on the test's documented allowlist
                # (carddemo_test_warn_allowlist). This is preferred over -Werror
                # so we can (a) exclude the environmental gcc _FORTIFY_SOURCE
                # note that has nothing to do with the COBOL source, and (b) give
                # a precise, auditable per-warning failure message.
                # WHY (MA-02 -- cwd-independent COPY resolution): tests that
                # EXECUTE the real UUT COPY it by a repo-root-relative path
                # ("app/cbl/CSUTLDTC.cbl"); adding -I "$CARDDEMO_REPO_ROOT" lets
                # that resolve via the include path regardless of the caller's
                # cwd (verified: builds+runs green from an unrelated directory),
                # removing the "compilation depends on current directory" hazard.
                # -I "$_tdir" resolves test-local copybooks; app/cpy comes from
                # carddemo_cobc_flags.
                _tlog="$CARDDEMO_BUILD_DIR/$_tname.buildlog"
                if cobc -x "${local_flags[@]}" "${_textra[@]}" \
                        -Wall -Wextra \
                        -I "$CARDDEMO_REPO_ROOT" -I "$_tdir" \
                        -o "$CARDDEMO_BUILD_DIR/$_tname" "$_tf" 2>"$_tlog"; then
                    # Compiled without a hard error; now apply the warning gate.
                    _allow="$(carddemo_test_warn_allowlist "$_tname")"
                    _resfile="$CARDDEMO_BUILD_DIR/$_tname.warnres"
                    # WHY (errexit/pipefail-safe): under `set -euo pipefail` a
                    # grep that matches nothing returns 1 and would abort the
                    # script, so the filter pipeline is written to a file and
                    # guarded with `|| true`; emptiness is then tested with -s.
                    # The environmental `_FORTIFY_SOURCE redefined` note (emitted
                    # by the gcc back end, not cobc) is always excluded.
                    if [ -n "$_allow" ]; then
                        grep 'warning:' "$_tlog" \
                            | grep -v '_FORTIFY_SOURCE' \
                            | grep -Ev "$_allow" >"$_resfile" || true
                    else
                        grep 'warning:' "$_tlog" \
                            | grep -v '_FORTIFY_SOURCE' >"$_resfile" || true
                    fi
                    if [ -s "$_resfile" ]; then
                        echo "[build]   FAILED (warnings-fatal): $_tname emitted" >&2
                        echo "[build]   non-allowlisted warning(s):" >&2
                        sed 's/^/[build]     /' "$_resfile" >&2
                        overall_rc="$(carddemo_rc_worst "$overall_rc" "$CARDDEMO_RC_FAIL")"
                    else
                        echo "[build]   OK -> $CARDDEMO_BUILD_DIR/$_tname"
                        # Surface allowlisted (documented, immutable) warnings for
                        # the audit trail without failing the build.
                        if [ -n "$_allow" ] && grep -Eq "$_allow" "$_tlog"; then
                            echo "[build]     note: carries documented allowlisted warning(s):"
                            grep 'warning:' "$_tlog" | grep -Ev '_FORTIFY_SOURCE' \
                                | grep -E "$_allow" | sed 's/^/[build]       /' || true
                        fi
                    fi
                    rm -f "$_resfile"
                else
                    echo "[build]   FAILED: $_tname (compile error)" >&2
                    sed 's/^/[build]     /' "$_tlog" >&2
                    overall_rc="$(carddemo_rc_worst "$overall_rc" "$CARDDEMO_RC_FAIL")"
                fi
                rm -f "$_tlog"
            done
        fi
    else
        echo "[build] ERROR: --with-tests requested but $_tdir does not exist" >&2
        overall_rc="$(carddemo_rc_worst "$overall_rc" "$CARDDEMO_RC_FAIL")"
    fi
fi

echo "[build] ============================================================"
echo "[build] Build complete. Aggregate RC=$overall_rc"
echo "[build] ============================================================"
exit "$overall_rc"
