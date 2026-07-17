#!/bin/bash
# =============================================================================
# scripts/run_integration_tests.sh
# -----------------------------------------------------------------------------
# Purpose:
#   Run the Python integration-test layer with pytest. Ensures the COBOL units
#   under test are compiled, then executes:
#       pytest tests/integration -m integration --junitxml=reports/integration.xml
#   and maps pytest's exit status onto the CardDemo condition-code rubric.
#
# Usage:
#   scripts/run_integration_tests.sh [-h|--help] [pytest passthrough args...]
#     e.g. scripts/run_integration_tests.sh -k posting -vv
#     Long-form pytest options are passed after a `--` sentinel, e.g.
#       scripts/run_integration_tests.sh -- --tb=long --maxfail=1
#
# Parameters:
#   -h, --help    print usage and exit 0.
#   $@ (optional) - pytest passthrough. Short options (-k, -vv, ...) and bare
#                   positionals (test node ids) forward verbatim to pytest; any
#                   token after a `--` sentinel also forwards verbatim. An
#                   UNRECOGNISED long option (--foo) before `--` is a usage error
#                   (RC=2) so a mistyped flag is never silently handed to pytest.
#   Environment inputs (from scripts/test_env.sh): CARDDEMO_REPO_ROOT,
#   CARDDEMO_BUILD_DIR, CARDDEMO_REPORTS_DIR, and all ASSIGN-name bindings.
#
# Return / Exit codes (CardDemo RC rubric):
#   0  all integration tests passed.
#   2  usage error (unknown option) - DISTINCT and never aggregated into the
#      rubric, so a CLI typo cannot masquerade as a test warn/fail.
#   4  nothing to run yet (the tests/integration directory is absent).
#   8  test failures, no tests collected (an empty/broken enabled layer, mapped
#      from pytest exit 5), an internal pytest error, or a hard build/prereq
#      failure.
#
# Errors / Exceptions:
#   Missing pytest -> diagnostic + exit 8. Hard build failure (RC>=8) -> exit 8
#   before invoking pytest.
#
# WHY (design rationale):
#   - Assumption: integration tests invoke the *compiled* batch programs, so the
#     build must run first; a warn-level build (no COBOL test programs) does not
#     block integration, only a hard failure does.
#   - Refactoring rationale: pytest's own exit code is the completion signal
#     (synchronous), so no fixed `sleep` pacing is used.
#   - Trade-off: pytest exit codes are remapped (1=failed->8; 5=no-tests-collected
#     ->8, treated as an absent/broken ENABLED layer per test_env.sh MA-03) rather
#     than propagated raw, because they are not ordered by severity and would
#     otherwise corrupt the aggregate RC. WARN(4) is reserved for the distinct
#     directory-absent short-circuit below, NOT for an empty-but-present layer.
# =============================================================================

set -euo pipefail

_int_script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/test_env.sh
source "$_int_script_dir/test_env.sh"

# WHY (Assumption): the Python test modules import `from tests.helpers ...` and
# `from tests.mocks ...`; putting the repo root on PYTHONPATH makes the `tests`
# package importable regardless of pytest's import mode / rootdir heuristics.
export PYTHONPATH="$CARDDEMO_REPO_ROOT${PYTHONPATH:+:$PYTHONPATH}"

carddemo_int_usage() {
    # Purpose : print the integration runner's usage synopsis.
    # Parameters: none.
    # Returns : always 0; the banner is written to stdout.
    # Errors  : none.
    # WHY (consistency): mirrors carddemo_master_usage() in run_tests.sh and
    # carddemo_unit_usage() in run_unit_tests.sh so every runner presents a
    # uniform CLI contract (a lone -h/--help, and RC=2 rejection of an unknown
    # option) -- the asymmetry this closes is exactly QA finding [C2].
    cat <<'USAGE'
Usage: scripts/run_integration_tests.sh [-h|--help] [pytest passthrough args...]

Runs the Python integration layer:
  pytest tests/integration -m integration --junitxml=reports/integration.xml

Options:
  -h, --help   show this help and exit 0

Passthrough to pytest:
  Short options (-k, -vv, ...) and bare positionals forward verbatim, e.g.
    scripts/run_integration_tests.sh -k posting -vv
  Long-form pytest options are forwarded after a `--` sentinel, e.g.
    scripts/run_integration_tests.sh -- --tb=long --maxfail=1
  An unrecognised long option (--foo) before `--` is a usage error (RC=2).
USAGE
}

# ---------------------------------------------------------------------------
# Parse CLI arguments.
# WHY (QA [C2] -- an invalid option must never masquerade as a test outcome):
# this runner previously forwarded "$@" verbatim to pytest, so a mistyped flag
# (e.g. --bogus) reached pytest and surfaced as pytest's RC=8 with NO diagnostic
# naming the offending option -- indistinguishable from a genuine test failure.
# We now enforce an explicit CLI contract mirroring run_tests.sh: -h/--help
# prints usage and exits 0; an UNRECOGNISED long option is rejected with the
# reserved usage code (CARDDEMO_RC_USAGE=2), DISTINCT from the test rubric
# {0,4,8}, so an invocation typo can never be reclassified as a run result.
# WHY (Trade-off -- passthrough contract): the layer must still accept arbitrary
# pytest options. Short options (-k, -vv, ...) and bare positionals (test node
# ids) forward verbatim; long-form pytest options forward after a `--` sentinel.
# Only an unrecognised long option BEFORE `--` is a usage error. This keeps the
# documented `-k posting -vv` example working while still catching a fat-fingered
# `--bogus` (Alternatives Considered: a full allowlist of valid pytest flags was
# rejected as unmaintainable and brittle against pytest upgrades).
# ---------------------------------------------------------------------------
_passthru=()
while [ "$#" -gt 0 ]; do
    case "$1" in
        -h|--help)
            carddemo_int_usage
            exit 0
            ;;
        --)
            # Explicit end-of-options sentinel: forward everything after verbatim.
            shift
            _passthru+=("$@")
            break
            ;;
        --*)
            echo "[integration] ERROR: unknown option '$1'" >&2
            echo "[integration] (to pass a long pytest option through, place it after '--')" >&2
            carddemo_int_usage >&2
            exit "${CARDDEMO_RC_USAGE}"
            ;;
        *)
            # Short options (-k, -vv) and bare positionals go straight to pytest.
            _passthru+=("$1")
            ;;
    esac
    shift
done

CARDDEMO_INT_REPORT="${CARDDEMO_REPORTS_DIR}/integration.xml"
_idir="$CARDDEMO_REPO_ROOT/tests/integration"

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
    echo "[integration] ERROR: pytest not available (need 'pytest' or python3 with pytest)." >&2
    exit "${CARDDEMO_RC_FAIL}"
fi

mkdir -p "$CARDDEMO_REPORTS_DIR"

echo "[integration] ============================================================"
echo "[integration] Python integration layer (pytest)"
echo "[integration] ============================================================"

# ---------------------------------------------------------------------------
# Ensure the units under test are compiled.
# ---------------------------------------------------------------------------
overall_rc=0
echo "[integration] building units under test ..."
if bash "$_int_script_dir/build_test_programs.sh"; then
    build_rc=0
else
    build_rc=$?
fi
overall_rc="$(carddemo_rc_worst "$overall_rc" "$build_rc")"
if [ "$build_rc" -ge "${CARDDEMO_RC_FAIL}" ]; then
    echo "[integration] build failed (rc=$build_rc); cannot run integration tests" >&2
    carddemo_write_empty_junit "carddemo-integration" "$CARDDEMO_INT_REPORT"
    exit "$overall_rc"
fi

# ---------------------------------------------------------------------------
# Soft-skip when the integration suite is not present yet.
# WHY (Assumption): tests/integration is authored in parallel; its absence is a
# WARN, not a failure.
# ---------------------------------------------------------------------------
if [ ! -d "$_idir" ]; then
    echo "[integration] $_idir not present yet - nothing to run (warn)"
    carddemo_write_empty_junit "carddemo-integration" "$CARDDEMO_INT_REPORT"
    overall_rc="$(carddemo_rc_worst "$overall_rc" "${CARDDEMO_RC_WARN}")"
    exit "$overall_rc"
fi

# ---------------------------------------------------------------------------
# Run pytest and map its exit code onto the rubric.
# ---------------------------------------------------------------------------
echo "[integration] running: ${CARDDEMO_PYTEST[*]} $_idir -m integration --junitxml=$CARDDEMO_INT_REPORT ${_passthru[*]:-}"
set +e
# WHY (Assumption): "${_passthru[@]+...}" guards against an unbound-variable
# error under `set -u` when no passthrough args were supplied (empty array).
"${CARDDEMO_PYTEST[@]}" "$_idir" -m integration \
    --junitxml="$CARDDEMO_INT_REPORT" ${_passthru[@]+"${_passthru[@]}"}
pyrc=$?
set -e

mapped_rc="$(carddemo_rc_from_pytest "$pyrc")"
overall_rc="$(carddemo_rc_worst "$overall_rc" "$mapped_rc")"

echo "[integration] ============================================================"
echo "[integration] pytest exit=$pyrc -> rubric=$mapped_rc ; aggregate RC=$overall_rc"
echo "[integration] report -> $CARDDEMO_INT_REPORT"
echo "[integration] ============================================================"
exit "$overall_rc"
