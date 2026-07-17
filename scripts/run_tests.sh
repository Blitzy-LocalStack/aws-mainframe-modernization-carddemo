#!/bin/bash
# =============================================================================
# scripts/run_tests.sh   (MASTER RUNNER - single-command entry point)
# -----------------------------------------------------------------------------
# Purpose:
#   Run the ENTIRE CardDemo automated test suite end-to-end from one command:
#       build -> unit (GCBLUnit) -> integration (pytest) -> e2e (pytest)
#   Each layer's condition code is aggregated onto the CardDemo RC rubric and the
#   suite exits with the WORST (highest) severity, giving CI one deterministic
#   exit status. All JUnit XML lands in reports/ for CI ingestion.
#
# Usage:
#   scripts/run_tests.sh [--fail-fast|-x] [--with-localstack] [-h|--help]
#     source scripts/test_env.sh is performed internally; no prior setup needed.
#
# Parameters:
#   --fail-fast, -x    stop at the first layer that FAILS (rc>=8); default is
#                      run-all-layers-then-report.
#   --with-localstack  bring up LocalStack for the e2e dataset-staging tests
#                      (passed through to run_e2e_tests.sh).
#   -h, --help         print usage and exit 0.
#   (no positional args; unknown options are a usage error.)
#
# Return / Exit codes (EXIT-CODE POLICY):
#   0   every layer passed.
#   4   warn: a layer soft-skipped or produced reject-level output, none failed.
#   8   fail: at least one layer had test failures / a hard error.
#   16  fatal/abend propagated from a layer.
#   2   usage error (bad option) - deliberately DISTINCT and NEVER aggregated
#       into the rubric, so a CLI mistake cannot masquerade as a test warn.
#
# Errors / Exceptions:
#   Layer scripts are invoked with `bash <path>`; a missing/failed layer surfaces
#   as that layer's rc and is aggregated. Usage errors exit 2 immediately.
#
# WHY (design rationale):
#   - Refactoring rationale: uses `set -uo pipefail` WITHOUT `-e`. The whole point
#     of the master is to run EVERY layer and report the worst rc; `-e` would abort
#     on the first non-zero layer and defeat run-all-then-report. Fail-fast is
#     offered explicitly via --fail-fast instead.
#   - Trade-off: the default is run-all-then-report (maximal diagnostics in one CI
#     run); --fail-fast is opt-in for fast local iteration.
#   - Assumption: child layers are invoked via `bash "<path>"` (not the exec bit)
#     because the create step does not reliably set +x; this is robust regardless
#     of file mode.
#   - Trade-off: an explicit up-front build is run even though each sub-runner also
#     builds; the rebuild is idempotent (cobc recompiles) and lets --fail-fast
#     abort a broken-compile suite before any test layer, which is clearer than
#     coupling every sub-runner to a shared skip-build flag.
# =============================================================================

# WHY (Refactoring rationale): -u catches unset-variable bugs and -o pipefail
# surfaces failures inside pipelines, but -e is intentionally omitted (see header)
# so a failing layer does not abort aggregation.
set -uo pipefail

_master_script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/test_env.sh
source "$_master_script_dir/test_env.sh"

carddemo_master_usage() {
    # Purpose : print the master runner's usage synopsis.
    # Parameters: none.
    # Returns : always 0.
    # Errors  : none.
    cat <<USAGE
Usage: scripts/run_tests.sh [--fail-fast|-x] [--with-localstack] [-h|--help]

Runs the full CardDemo test suite (build -> unit -> integration -> e2e) and
exits with the worst condition code (0 pass / 4 warn / 8 fail / 16 fatal).

Options:
  -x, --fail-fast     stop at the first failing layer (rc>=8)
      --with-localstack  bring up LocalStack for the e2e AWS staging tests
  -h, --help          show this help and exit
USAGE
}

# ---------------------------------------------------------------------------
# Parse options.
# WHY (Assumption): a genuine CLI mistake should be loud and DISTINCT from a
# test outcome, hence a dedicated usage rc (2) that is never aggregated.
# ---------------------------------------------------------------------------
_fail_fast=0
_with_localstack=0
while [ "$#" -gt 0 ]; do
    case "$1" in
        -x|--fail-fast) _fail_fast=1 ;;
        --with-localstack) _with_localstack=1 ;;
        -h|--help) carddemo_master_usage; exit 0 ;;
        *)
            echo "[run_tests] ERROR: unknown option '$1'" >&2
            carddemo_master_usage >&2
            exit "${CARDDEMO_RC_USAGE}"
            ;;
    esac
    shift
done

mkdir -p "$CARDDEMO_REPORTS_DIR" "$CARDDEMO_BUILD_DIR"

# e2e passthrough args (only --with-localstack, if requested).
_e2e_args=()
if [ "$_with_localstack" = "1" ]; then
    _e2e_args=(--with-localstack)
fi

echo "[run_tests] ############################################################"
echo "[run_tests] # CardDemo full test suite"
echo "[run_tests] #   repo      : $CARDDEMO_REPO_ROOT"
echo "[run_tests] #   reports   : $CARDDEMO_REPORTS_DIR"
echo "[run_tests] #   fail-fast : $_fail_fast ; with-localstack : $_with_localstack"
echo "[run_tests] ############################################################"

overall_rc=0
_summary=()

carddemo_stage() {
    # Purpose : run one suite stage, announce it, aggregate its rc onto the
    #           running worst-rc, and honor --fail-fast.
    # Parameters:
    #   $1     (string) - human-readable stage label.
    #   $2..   (argv)   - the command (and args) to execute for the stage.
    # Returns : 0 normally; 1 when --fail-fast should abort the suite.
    # Errors  : none raised; a stage's own non-zero rc is captured, not thrown.
    # WHY (Refactoring rationale): centralising banner + rc-capture + aggregation
    # in one routine keeps the stage list declarative and prevents copy-paste
    # drift in how each layer's rc is folded into the total.
    local label="$1"; shift
    echo ""
    echo "[run_tests] ===================== STAGE: $label ====================="
    local rc=0
    # WHY (Assumption): `|| rc=$?` captures the layer's exit code without `set -e`
    # aborting; a layer never throws, it returns an rc we must aggregate.
    "$@" || rc=$?
    _summary+=("$(printf '  %-12s rc=%s' "$label" "$rc")")
    overall_rc="$(carddemo_rc_worst "$overall_rc" "$rc")"
    echo "[run_tests] stage '$label' rc=$rc ; running aggregate=$overall_rc"
    if [ "$_fail_fast" = "1" ] && [ "$rc" -ge "${CARDDEMO_RC_FAIL}" ]; then
        echo "[run_tests] --fail-fast: aborting suite after '$label' (rc=$rc)" >&2
        return 1
    fi
    return 0
}

# ---------------------------------------------------------------------------
# Execute the layers in dependency order. The `while :; do ... break; done`
# wrapper lets `|| break` cleanly abort the sequence when --fail-fast trips.
# WHY (Trade-off): a loop+break is clearer than nesting four `if` blocks and
# keeps every stage at the same indentation for readability.
# ---------------------------------------------------------------------------
while :; do
    carddemo_stage "build"       bash "$_master_script_dir/build_test_programs.sh" --with-tests || break
    carddemo_stage "unit"        bash "$_master_script_dir/run_unit_tests.sh"                   || break
    carddemo_stage "integration" bash "$_master_script_dir/run_integration_tests.sh"           || break
    carddemo_stage "e2e"         bash "$_master_script_dir/run_e2e_tests.sh" ${_e2e_args[@]+"${_e2e_args[@]}"} || break
    break
done

echo ""
echo "[run_tests] ############################################################"
echo "[run_tests] # SUMMARY"
for _line in "${_summary[@]}"; do
    echo "[run_tests] #$_line"
done
echo "[run_tests] #   aggregate RC = $overall_rc  (0 pass / 4 warn / 8 fail / 16 fatal)"
echo "[run_tests] #   reports in   : $CARDDEMO_REPORTS_DIR"
echo "[run_tests] ############################################################"
exit "$overall_rc"
