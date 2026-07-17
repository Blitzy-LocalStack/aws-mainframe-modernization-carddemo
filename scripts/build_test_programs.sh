#!/bin/bash
# =============================================================================
# scripts/build_test_programs.sh
# -----------------------------------------------------------------------------
# Purpose:
#   Compile the CardDemo batch programs under test (and, optionally, the
#   GCBLUnit COBOL test programs) from app/cbl/ into the build/ directory using
#   the repository's GnuCOBOL convention. Ten programs that own a MAIN entry
#   point are built as executables (`cobc -x`); the three subprograms that are
#   dynamically CALL'd (CBACT04C, CSUTLDTC, CBSTM03B) are built as shared
#   modules (`cobc -m`) so the runtime module loader resolves them by
#   PROGRAM-ID via COB_LIBRARY_PATH.
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
#   0  all requested programs compiled cleanly.
#   4  a soft warning (e.g. --with-tests requested but no test programs present).
#   8  cobc is unavailable, or one or more programs failed to compile.
#   2  operator usage error (unknown flag).
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
#   - Alternatives Considered: driving the build with `make` was rejected -- the
#     repository ships no Makefile at app/ or root, so programs are compiled
#     directly with cobc.
#   - Refactoring rationale: the build is synchronous, so completion is detected
#     via cobc's own exit status (no fixed `sleep` pacing like the demo scripts).
#   - Assumption: source files are fixed-format COBOL (`-fixed`), copybooks live
#     in app/cpy/ (`-I`), and the two collided-name statement programs are stored
#     with an UPPERCASE .CBL extension (CBSTM03A.CBL, CBSTM03B.CBL) while all
#     others are lowercase .cbl -- so each source path is resolved case-aware.
#   - Refactoring rationale: CBACT04C is compiled as a `-m` module (not `-x`)
#     because it declares `PROCEDURE DIVISION USING EXTERNAL-PARMS`; GnuCOBOL
#     refuses to link a USING program as a standalone executable, so the only
#     way to "produce it cleanly" is as a dynamically-loaded shared module.
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
# 10 MAIN executables + 3 dynamically-CALL'd shared modules = 13 sources.
# ---------------------------------------------------------------------------
CARDDEMO_MAIN_PROGRAMS=(CBACT01C CBACT02C CBACT03C CBCUS01C \
                        CBEXPORT CBIMPORT CBTRN01C CBTRN02C CBTRN03C CBSTM03A)
# WHY (Assumption): CBACT04C, CSUTLDTC and CBSTM03B each declare
# `PROCEDURE DIVISION USING ...`, so GnuCOBOL rejects `-x` for them
# ("executable program requested but PROCEDURE/ENTRY has USING clause"). They
# are therefore compiled as `-m` shared modules and resolved at run time by
# PROGRAM-ID on COB_LIBRARY_PATH (exported by test_env.sh -> the build dir),
# exactly as cobcrun expects for a dynamically-CALL'd subprogram.
CARDDEMO_MODULE_PROGRAMS=(CBACT04C CSUTLDTC CBSTM03B)

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
    printf '%s\n' -fixed --std=ibm-strict -I "$CARDDEMO_REPO_ROOT/app/cpy"
    if [ "$CARDDEMO_COVERAGE" = "1" ]; then
        # WHY (Assumption): GnuCOBOL transpiles to C, so gcov coverage is obtained
        # by passing --coverage through to the C compiler (-A) and linker (-Q),
        # and keeping the generated C (-save-temps) alongside the .gcno files.
        printf '%s\n' -g "-save-temps=$CARDDEMO_BUILD_DIR" -A --coverage -Q --coverage
    fi
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
    local -a flags
    mapfile -t flags < <(carddemo_cobc_flags)
    if ! src="$(carddemo_find_source "$name")"; then
        echo "[build]   ERROR: no source (.cbl/.CBL) for '$name' under app/cbl/" >&2
        return "${CARDDEMO_RC_FAIL}"
    fi
    if [ "$mode" = "main" ]; then
        out="$CARDDEMO_BUILD_DIR/$name"
        cobc -x "${flags[@]}" -o "$out" "$src" || return "${CARDDEMO_RC_FAIL}"
    else
        out="$CARDDEMO_BUILD_DIR/$name.so"
        cobc -m "${flags[@]}" -o "$out" "$src" || return "${CARDDEMO_RC_FAIL}"
    fi
    return 0
}

# ---------------------------------------------------------------------------
# Compile everything, aggregating to the worst RC.
# ---------------------------------------------------------------------------
overall_rc=0
echo "[build] ============================================================"
echo "[build] Compiling CardDemo programs under test (--std=ibm-strict)"
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
# Optionally compile the GCBLUnit test programs (soft-skip if absent).
# WHY (Assumption): the tests/ tree is authored in parallel by other agents, so
# its absence at build time is a WARN, not a failure -- the master runner stays
# usable before the COBOL unit tests land.
# ---------------------------------------------------------------------------
if [ "$CARDDEMO_WITH_TESTS" = "1" ]; then
    _tdir="$CARDDEMO_REPO_ROOT/tests/cobol-unit"
    if [ -d "$_tdir" ]; then
        shopt -s nullglob
        _tfiles=("$_tdir"/*_test.cbl "$_tdir"/*_test.CBL)
        shopt -u nullglob
        if [ "${#_tfiles[@]}" -eq 0 ]; then
            echo "[build] no *_test.cbl in $_tdir (built in parallel) - skipping"
            overall_rc="$(carddemo_rc_worst "$overall_rc" "$CARDDEMO_RC_WARN")"
        else
            local_flags=()
            mapfile -t local_flags < <(carddemo_cobc_flags)
            for _tf in "${_tfiles[@]}"; do
                _tname="$(basename "${_tf%.*}")"
                echo "[build] test   : $_tname"
                # WHY: test programs COPY the same copybooks (app/cpy) plus any
                # test-local copybooks, so both include dirs are supplied.
                if cobc -x "${local_flags[@]}" -I "$_tdir" \
                        -o "$CARDDEMO_BUILD_DIR/$_tname" "$_tf"; then
                    echo "[build]   OK -> $CARDDEMO_BUILD_DIR/$_tname"
                else
                    echo "[build]   FAILED: $_tname" >&2
                    overall_rc="$(carddemo_rc_worst "$overall_rc" "$CARDDEMO_RC_FAIL")"
                fi
            done
        fi
    else
        echo "[build] tests/cobol-unit not present yet - skipping test-program build"
    fi
fi

echo "[build] ============================================================"
echo "[build] Build complete. Aggregate RC=$overall_rc"
echo "[build] ============================================================"
exit "$overall_rc"
