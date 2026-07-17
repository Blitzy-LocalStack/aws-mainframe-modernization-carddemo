#!/bin/bash
# =============================================================================
# scripts/run_unit_tests.sh
# -----------------------------------------------------------------------------
# Purpose:
#   Run the COBOL unit-test layer: build the units-under-test and the GCBLUnit
#   test programs, execute each compiled `tests/cobol-unit/*_test.cbl` runner,
#   and aggregate the results into a single JUnit-style report at
#   reports/unit.xml for CI ingestion. One compiled test program == one JUnit
#   <testcase>.
#
# Usage:
#   scripts/run_unit_tests.sh          # normal run
#   source scripts/test_env.sh first is NOT required (this script sources it).
#
# Parameters (environment inputs, from scripts/test_env.sh):
#   CARDDEMO_REPO_ROOT, CARDDEMO_BUILD_DIR, CARDDEMO_REPORTS_DIR.
#
# Return / Exit codes (CardDemo RC rubric):
#   0  all unit-test programs passed (exit 0).
#   4  no unit-test programs found yet (suite authored in parallel) -> warn.
#   8  one or more unit-test programs failed, or the build failed.
#
# Errors / Exceptions:
#   A build failure (RC>=8) aborts before running tests. A missing compiled
#   executable for a discovered test source is reported and counted as a failure.
#
# WHY (design rationale):
#   - Alternatives Considered: GCBLUnit (pure GnuCOBOL) was selected over a
#     Java-based zUnit because no JVM is installed on the runner.
#   - Trade-off: results are synthesised into ONE JUnit file keyed per test
#     program (pass/fail from the process exit code) rather than parsing
#     GCBLUnit's native per-assertion output, whose schema we do not control.
#     This guarantees a deterministic, CI-consumable document regardless of the
#     framework's own reporting; finer per-assertion locality is sacrificed.
#   - Refactoring rationale: completion is detected by each test process exiting
#     (a synchronous wait on its status), never a fixed `sleep`.
# =============================================================================

set -euo pipefail

# WHY (Assumption/Trade-off): bash 5.2 enables `patsub_replacement` by default,
# which makes an unescaped `&` in a ${var//pat/repl} replacement expand to the
# matched text -- corrupting our XML entity escaping (e.g. `<` -> `<lt;`). We
# disable it so literal `&amp;`/`&lt;`/... are inserted; `|| true` keeps this a
# harmless no-op on older bash where the option does not exist.
shopt -u patsub_replacement 2>/dev/null || true

_unit_script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/test_env.sh
source "$_unit_script_dir/test_env.sh"

CARDDEMO_UNIT_REPORT="${CARDDEMO_REPORTS_DIR}/unit.xml"

carddemo_xml_escape() {
    # Purpose : XML-escape a string so captured COBOL output is safe inside the
    #           JUnit document.
    # Parameters:
    #   $1 (string) - raw text.
    # Returns : always 0; escaped text is written to stdout.
    # Errors  : none.
    # WHY (Assumption): only the five XML predefined entities need escaping for
    # well-formed attribute/text content; control-char stripping is left to the
    # CI parser to keep this helper simple and dependency-free.
    local s="${1:-}"
    s="${s//&/&amp;}"
    s="${s//</&lt;}"
    s="${s//>/&gt;}"
    s="${s//\"/&quot;}"
    s="${s//\'/&apos;}"
    printf '%s' "$s"
}

# ---------------------------------------------------------------------------
# 1. Ensure the units-under-test AND the COBOL test programs are compiled.
# WHY (Trade-off): a warn-level build result (e.g. no test programs yet) must
# NOT block the run -- only a hard build failure (RC>=8) does.
# ---------------------------------------------------------------------------
overall_rc=0
echo "[unit] ============================================================"
echo "[unit] COBOL unit-test layer (GCBLUnit)"
echo "[unit] ============================================================"
echo "[unit] building units-under-test and test programs ..."
if bash "$_unit_script_dir/build_test_programs.sh" --with-tests; then
    build_rc=0
else
    build_rc=$?
fi
overall_rc="$(carddemo_rc_worst "$overall_rc" "$build_rc")"
if [ "$build_rc" -ge "${CARDDEMO_RC_FAIL}" ]; then
    echo "[unit] build failed (rc=$build_rc); cannot run unit tests" >&2
    exit "$overall_rc"
fi

# ---------------------------------------------------------------------------
# 2. Discover compiled test programs.
# WHY (Assumption): the tests/ tree is authored in parallel; when no test source
# exists yet we emit a valid empty report and warn (RC=4) rather than fail.
# ---------------------------------------------------------------------------
_tdir="$CARDDEMO_REPO_ROOT/tests/cobol-unit"
declare -a test_srcs=()
if [ -d "$_tdir" ]; then
    shopt -s nullglob
    test_srcs=("$_tdir"/*_test.cbl "$_tdir"/*_test.CBL)
    shopt -u nullglob
fi

mkdir -p "$CARDDEMO_REPORTS_DIR"

if [ "${#test_srcs[@]}" -eq 0 ]; then
    echo "[unit] no *_test.cbl found under $_tdir - nothing to run (warn)"
    {
        echo '<?xml version="1.0" encoding="UTF-8"?>'
        echo '<testsuites>'
        echo '  <testsuite name="carddemo-cobol-unit" tests="0" failures="0" errors="0" skipped="0" time="0"/>'
        echo '</testsuites>'
    } > "$CARDDEMO_UNIT_REPORT"
    overall_rc="$(carddemo_rc_worst "$overall_rc" "${CARDDEMO_RC_WARN}")"
    echo "[unit] wrote empty report -> $CARDDEMO_UNIT_REPORT ; RC=$overall_rc"
    exit "$overall_rc"
fi

# ---------------------------------------------------------------------------
# 3. Execute each test program, capturing exit status and output.
# ---------------------------------------------------------------------------
_total=0
_failures=0
_cases=""
for _src in "${test_srcs[@]}"; do
    _name="$(basename "${_src%.*}")"
    _exe="$CARDDEMO_BUILD_DIR/$_name"
    _total=$((_total + 1))
    echo "[unit] running $_name ..."
    if [ ! -x "$_exe" ]; then
        echo "[unit]   MISSING executable $_exe (build did not produce it)" >&2
        _failures=$((_failures + 1))
        _cases+="  <testcase name=\"$(carddemo_xml_escape "$_name")\" classname=\"cobol-unit\">"
        _cases+="<error message=\"missing executable\">expected $(carddemo_xml_escape "$_exe")</error></testcase>"$'\n'
        overall_rc="$(carddemo_rc_worst "$overall_rc" "${CARDDEMO_RC_FAIL}")"
        continue
    fi
    # WHY: disable -e around the test invocation so a failing test is recorded
    # (not aborted); GCBLUnit returns 0 on all-pass, nonzero on any failure.
    set +e
    _out="$("$_exe" 2>&1)"
    _trc=$?
    set -e
    if [ "$_trc" -eq 0 ]; then
        echo "[unit]   PASS $_name"
        _cases+="  <testcase name=\"$(carddemo_xml_escape "$_name")\" classname=\"cobol-unit\"/>"$'\n'
    else
        echo "[unit]   FAIL $_name (rc=$_trc)"
        _failures=$((_failures + 1))
        _cases+="  <testcase name=\"$(carddemo_xml_escape "$_name")\" classname=\"cobol-unit\">"
        _cases+="<failure message=\"exit $_trc\">$(carddemo_xml_escape "$_out")</failure></testcase>"$'\n'
        overall_rc="$(carddemo_rc_worst "$overall_rc" "${CARDDEMO_RC_FAIL}")"
    fi
done

# ---------------------------------------------------------------------------
# 4. Emit the aggregated JUnit report.
# ---------------------------------------------------------------------------
{
    echo '<?xml version="1.0" encoding="UTF-8"?>'
    echo '<testsuites>'
    echo "  <testsuite name=\"carddemo-cobol-unit\" tests=\"$_total\" failures=\"$_failures\" errors=\"0\" skipped=\"0\" time=\"0\">"
    printf '%s' "$_cases"
    echo '  </testsuite>'
    echo '</testsuites>'
} > "$CARDDEMO_UNIT_REPORT"

echo "[unit] ============================================================"
echo "[unit] ran $_total test program(s), $_failures failure(s)"
echo "[unit] report -> $CARDDEMO_UNIT_REPORT ; aggregate RC=$overall_rc"
echo "[unit] ============================================================"
exit "$overall_rc"
