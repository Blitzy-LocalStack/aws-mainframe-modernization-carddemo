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
#   scripts/run_integration_tests.sh [extra pytest args...]
#     e.g. scripts/run_integration_tests.sh -k posting -vv
#
# Parameters:
#   $@ (optional) - additional arguments passed through verbatim to pytest.
#   Environment inputs (from scripts/test_env.sh): CARDDEMO_REPO_ROOT,
#   CARDDEMO_BUILD_DIR, CARDDEMO_REPORTS_DIR, and all ASSIGN-name bindings.
#
# Return / Exit codes (CardDemo RC rubric):
#   0  all integration tests passed.
#   4  nothing to run yet (tests/integration absent or no tests collected).
#   8  test failures, an internal pytest error, or a hard build/prereq failure.
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
#   - Trade-off: pytest exit codes are remapped (1=failed->8, 5=none collected->4)
#     rather than propagated raw, because they are not ordered by severity and
#     would otherwise corrupt the aggregate RC.
# =============================================================================

set -euo pipefail

_int_script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/test_env.sh
source "$_int_script_dir/test_env.sh"

# WHY (Assumption): the Python test modules import `from tests.helpers ...` and
# `from tests.mocks ...`; putting the repo root on PYTHONPATH makes the `tests`
# package importable regardless of pytest's import mode / rootdir heuristics.
export PYTHONPATH="$CARDDEMO_REPO_ROOT${PYTHONPATH:+:$PYTHONPATH}"

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
echo "[integration] running: ${CARDDEMO_PYTEST[*]} $_idir -m integration --junitxml=$CARDDEMO_INT_REPORT $*"
set +e
"${CARDDEMO_PYTEST[@]}" "$_idir" -m integration \
    --junitxml="$CARDDEMO_INT_REPORT" "$@"
pyrc=$?
set -e

mapped_rc="$(carddemo_rc_from_pytest "$pyrc")"
overall_rc="$(carddemo_rc_worst "$overall_rc" "$mapped_rc")"

echo "[integration] ============================================================"
echo "[integration] pytest exit=$pyrc -> rubric=$mapped_rc ; aggregate RC=$overall_rc"
echo "[integration] report -> $CARDDEMO_INT_REPORT"
echo "[integration] ============================================================"
exit "$overall_rc"
