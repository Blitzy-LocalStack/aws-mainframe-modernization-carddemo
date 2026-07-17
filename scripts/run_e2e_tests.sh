#!/bin/bash
# =============================================================================
# scripts/run_e2e_tests.sh
# -----------------------------------------------------------------------------
# Purpose:
#   Run the Python end-to-end (full-batch-cycle) test layer with pytest. Ensures
#   the COBOL units under test are compiled, optionally brings up the headless
#   LocalStack AWS emulator for the dataset-staging E2E, then executes:
#       pytest tests/e2e -m e2e --junitxml=reports/e2e.xml
#   and maps pytest's exit status onto the CardDemo condition-code rubric.
#
# Usage:
#   scripts/run_e2e_tests.sh [--with-localstack] [-h|--help] [pytest args...]
#     e.g. scripts/run_e2e_tests.sh --with-localstack -k full_batch -vv
#     Long-form pytest options are passed after a `--` sentinel, e.g.
#       scripts/run_e2e_tests.sh --with-localstack -- --tb=long
#   The env var CARDDEMO_WITH_LOCALSTACK=1 is equivalent to --with-localstack.
#
# Parameters:
#   --with-localstack (optional flag) - bring up LocalStack before the run.
#   -h, --help                        - print usage and exit 0.
#   $@ (optional)                     - pytest passthrough. Short options
#                   (-k, -vv, ...) and bare positionals forward verbatim; any
#                   token after a `--` sentinel also forwards verbatim. An
#                   UNRECOGNISED long option (--foo) before `--` is a usage error
#                   (RC=2) so a mistyped flag never reaches pytest silently.
#   Environment inputs (from scripts/test_env.sh): CARDDEMO_REPO_ROOT,
#   CARDDEMO_BUILD_DIR, CARDDEMO_REPORTS_DIR, and all ASSIGN-name bindings.
#   CARDDEMO_WITH_LOCALSTACK (optional) - "1" enables the AWS layer.
#
# Return / Exit codes (CardDemo RC rubric):
#   0  all e2e tests passed (and LocalStack, if requested, came up clean).
#   2  usage error (unknown option) - DISTINCT and never aggregated into the
#      rubric, so a CLI typo cannot masquerade as a test warn/fail.
#   4  nothing to run yet (the tests/e2e directory is absent), OR the optional
#      LocalStack layer soft-skipped (missing tools/token) - warn only.
#   8  test failures, no tests collected (an empty/broken enabled layer, mapped
#      from pytest exit 5), an internal pytest error, or a hard build/prereq
#      failure.
#
# Errors / Exceptions:
#   Missing pytest -> diagnostic + exit 8. Hard build failure (RC>=8) -> exit 8
#   before invoking pytest. A LocalStack WARN never blocks the e2e run.
#
# WHY (design rationale):
#   - Assumption: e2e chains the *compiled* batch programs (provision -> post ->
#     interest -> statement -> report), so the build must run first.
#   - Trade-off: the AWS/LocalStack layer is OPTIONAL and opt-in; its WARN (4) is
#     aggregated (honest signal that a layer was skipped) but never fails the run.
#   - Refactoring rationale: pytest's own exit code is the completion signal
#     (synchronous), so no fixed `sleep` pacing is used.
# =============================================================================

set -euo pipefail

_e2e_script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/test_env.sh
source "$_e2e_script_dir/test_env.sh"

# WHY (Assumption): the Python test modules import `from tests.helpers ...` and
# `from tests.mocks ...`; putting the repo root on PYTHONPATH makes the `tests`
# package importable regardless of pytest's import mode / rootdir heuristics.
export PYTHONPATH="$CARDDEMO_REPO_ROOT${PYTHONPATH:+:$PYTHONPATH}"

CARDDEMO_E2E_REPORT="${CARDDEMO_REPORTS_DIR}/e2e.xml"
_edir="$CARDDEMO_REPO_ROOT/tests/e2e"

carddemo_e2e_usage() {
    # Purpose : print the e2e runner's usage synopsis.
    # Parameters: none.
    # Returns : always 0; the banner is written to stdout.
    # Errors  : none.
    # WHY (consistency): mirrors carddemo_master_usage() in run_tests.sh and
    # carddemo_int_usage() in run_integration_tests.sh so every runner presents a
    # uniform CLI contract (a lone -h/--help, and RC=2 rejection of an unknown
    # option) -- closing QA finding [C2].
    cat <<'USAGE'
Usage: scripts/run_e2e_tests.sh [--with-localstack] [-h|--help] [pytest args...]

Runs the Python end-to-end layer:
  pytest tests/e2e -m e2e --junitxml=reports/e2e.xml

Options:
  --with-localstack   bring up the headless LocalStack AWS layer first
  -h, --help          show this help and exit 0

Passthrough to pytest:
  Short options (-k, -vv, ...) and bare positionals forward verbatim, e.g.
    scripts/run_e2e_tests.sh --with-localstack -k full_batch -vv
  Long-form pytest options are forwarded after a `--` sentinel, e.g.
    scripts/run_e2e_tests.sh -- --tb=long --maxfail=1
  An unrecognised long option (--foo) before `--` is a usage error (RC=2).
USAGE
}

# ---------------------------------------------------------------------------
# Parse CLI arguments.
# WHY (QA [C2]): the previous parser recognised ONLY --with-localstack and swept
# everything else (including -h/--help and typos like --bogus) into the pytest
# passthru, so --help was silently ignored and a mistyped flag surfaced only as
# pytest's RC=8 with no option-naming diagnostic. We now honour -h/--help
# (usage + exit 0) and reject an UNRECOGNISED long option with the reserved
# usage code (CARDDEMO_RC_USAGE=2), DISTINCT from the test rubric {0,4,8}.
# WHY (Trade-off -- passthrough contract): short options (-k, -vv) and bare
# positionals still forward verbatim; long-form pytest options forward after a
# `--` sentinel; only an unrecognised long option BEFORE `--` is a usage error.
# This preserves the documented `--with-localstack -k full_batch -vv` example
# while catching a fat-fingered `--bogus` (Alternatives Considered: a full
# allowlist of valid pytest flags was rejected as unmaintainable and brittle
# against pytest upgrades).
# ---------------------------------------------------------------------------
_with_localstack="${CARDDEMO_WITH_LOCALSTACK:-0}"
_passthru=()
while [ "$#" -gt 0 ]; do
    case "$1" in
        --with-localstack)
            _with_localstack=1
            ;;
        -h|--help)
            carddemo_e2e_usage
            exit 0
            ;;
        --)
            # Explicit end-of-options sentinel: forward everything after verbatim.
            shift
            _passthru+=("$@")
            break
            ;;
        --*)
            echo "[e2e] ERROR: unknown option '$1'" >&2
            echo "[e2e] (to pass a long pytest option through, place it after '--')" >&2
            carddemo_e2e_usage >&2
            exit "${CARDDEMO_RC_USAGE}"
            ;;
        *)
            # Short options (-k, -vv) and bare positionals go straight to pytest.
            _passthru+=("$1")
            ;;
    esac
    shift
done

carddemo_write_empty_junit() {
    # Purpose : write a valid, empty JUnit document so CI always finds a report.
    # Parameters:
    #   $1 (string) - suite name.
    #   $2 (path)   - output file path.
    # Returns : always 0.
    # Errors  : none.
    # WHY (Trade-off): emitting an empty-but-valid report on a soft-skip keeps CI
    # ingestion uniform (a missing file would otherwise be flagged as an error).
    local name="$1" out="$2"
    {
        echo '<?xml version="1.0" encoding="UTF-8"?>'
        echo '<testsuites>'
        echo "  <testsuite name=\"$name\" tests=\"0\" failures=\"0\" errors=\"0\" skipped=\"0\" time=\"0\"/>"
        echo '</testsuites>'
    } > "$out"
}

# ---------------------------------------------------------------------------
# Select a pytest invocation.
# WHY (Alternatives Considered): prefer the `pytest` console script, but fall
# back to `python3 -m pytest` so the layer still runs when only the module (not
# the script) is on PATH; if neither exists it is a hard prerequisite failure.
# ---------------------------------------------------------------------------
if command -v pytest >/dev/null 2>&1; then
    CARDDEMO_PYTEST=(pytest)
elif command -v python3 >/dev/null 2>&1 && python3 -c 'import pytest' >/dev/null 2>&1; then
    CARDDEMO_PYTEST=(python3 -m pytest)
else
    echo "[e2e] ERROR: pytest not available (need 'pytest' or python3 with pytest)." >&2
    exit "${CARDDEMO_RC_FAIL}"
fi

mkdir -p "$CARDDEMO_REPORTS_DIR"

echo "[e2e] ============================================================"
echo "[e2e] Python end-to-end layer (pytest)"
echo "[e2e] ============================================================"

# ---------------------------------------------------------------------------
# Ensure the units under test are compiled.
# ---------------------------------------------------------------------------
overall_rc=0
echo "[e2e] building units under test ..."
if bash "$_e2e_script_dir/build_test_programs.sh"; then
    build_rc=0
else
    build_rc=$?
fi
overall_rc="$(carddemo_rc_worst "$overall_rc" "$build_rc")"
if [ "$build_rc" -ge "${CARDDEMO_RC_FAIL}" ]; then
    echo "[e2e] build failed (rc=$build_rc); cannot run e2e tests" >&2
    carddemo_write_empty_junit "carddemo-e2e" "$CARDDEMO_E2E_REPORT"
    exit "$overall_rc"
fi

# ---------------------------------------------------------------------------
# Soft-skip when the e2e suite is not present yet.
# WHY (Assumption): tests/e2e is authored in parallel; its absence is a WARN,
# not a failure. Checked BEFORE LocalStack so we never provision AWS with
# nothing to run.
# ---------------------------------------------------------------------------
if [ ! -d "$_edir" ]; then
    echo "[e2e] $_edir not present yet - nothing to run (warn)"
    carddemo_write_empty_junit "carddemo-e2e" "$CARDDEMO_E2E_REPORT"
    overall_rc="$(carddemo_rc_worst "$overall_rc" "${CARDDEMO_RC_WARN}")"
    exit "$overall_rc"
fi

# ---------------------------------------------------------------------------
# Optional: bring up LocalStack for the dataset-staging E2E.
# WHY (Trade-off): we SOURCE setup_localstack.sh (not run it in a subshell) so
# its exported AWS_ENDPOINT_URL reaches pytest in THIS process; we wrap it in
# set +e/set -e because a WARN (rc=4) return must neither trip our fail-fast nor
# block e2e -- the AWS layer is optional and best-effort.
# ---------------------------------------------------------------------------
if [ "$_with_localstack" = "1" ]; then
    echo "[e2e] --with-localstack requested; bringing up optional AWS layer ..."
    set +e
    source "$_e2e_script_dir/setup_localstack.sh"
    ls_rc=$?
    set -e
    overall_rc="$(carddemo_rc_worst "$overall_rc" "$ls_rc")"
    echo "[e2e] LocalStack setup rc=$ls_rc (aggregated, non-blocking)"
else
    echo "[e2e] LocalStack layer not requested (pass --with-localstack to enable)"
fi

# ---------------------------------------------------------------------------
# Run pytest and map its exit code onto the rubric.
# WHY (Assumption): "${_passthru[@]+...}" guards against an unbound-variable
# error under `set -u` when no passthru args were supplied (empty array).
# ---------------------------------------------------------------------------
echo "[e2e] running: ${CARDDEMO_PYTEST[*]} $_edir -m e2e --junitxml=$CARDDEMO_E2E_REPORT ${_passthru[*]:-}"
set +e
"${CARDDEMO_PYTEST[@]}" "$_edir" -m e2e \
    --junitxml="$CARDDEMO_E2E_REPORT" ${_passthru[@]+"${_passthru[@]}"}
pyrc=$?
set -e

mapped_rc="$(carddemo_rc_from_pytest "$pyrc")"
overall_rc="$(carddemo_rc_worst "$overall_rc" "$mapped_rc")"

echo "[e2e] ============================================================"
echo "[e2e] pytest exit=$pyrc -> rubric=$mapped_rc ; aggregate RC=$overall_rc"
echo "[e2e] report -> $CARDDEMO_E2E_REPORT"
echo "[e2e] ============================================================"
exit "$overall_rc"
