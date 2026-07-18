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
    # Purpose : write a valid, CI-ingestible JUnit document when pytest itself did
    #           not (or could not) produce one. Two shapes are emitted:
    #             * benign EMPTY suite (tests=0, errors=0) -- a legitimate
    #               "nothing to run" soft-skip that is NOT a failure; or
    #             * ERROR-MARKED suite (errors=1 + a synthetic failing <testcase>
    #               carrying an <error>) -- for a FAILED/aborted run so the
    #               machine-readable report can never be mistaken for green.
    #           The shape is chosen by whether an error message ($3) is supplied.
    # Parameters:
    #   $1 (string)           - suite name.
    #   $2 (path)             - output file path.
    #   $3 (string, optional) - error message. When present, an ERROR-MARKED
    #                           report is written; when absent/empty, a benign
    #                           EMPTY report is written.
    # Returns : always 0; the JUnit document is written to $2.
    # Errors  : none.
    # WHY (Trade-off -- QA-INT-01 + Areas-of-Concern #1): a *missing* report trips
    # a CI publisher's "report not found" alarm, but a benign green empty report on
    # a run that actually FAILED/aborted is worse -- it asserts failures=0 for a run
    # that did not pass. So every failure/abnormal path now emits an EXPLICIT error
    # marker (errors=1 + a failing <testcase>) instead of a green empty suite; the
    # benign empty form is reserved solely for the genuine WARN soft-skip (the e2e
    # directory being absent), which is a "nothing ran", not a failure. Alternatives
    # Considered: simply deleting the report on failure was rejected because a
    # guaranteed-present, error-marked report is more actionable for a pure-XML CI
    # consumer (that keys only on the XML) than an absent file.
    local name="$1" out="$2" errmsg="${3:-}"
    if [ -n "$errmsg" ]; then
        # WHY (Assumption + Trade-off): the messages passed here are controlled
        # runner strings (fixed text + a numeric exit code) that carry no XML
        # metacharacters today, but we still escape &, < and > so the document is
        # well-formed for ANY future message. bash 5.2's `patsub_replacement` (on by
        # default) treats an unescaped '&' in a ${var//pat/repl} replacement as
        # "the matched text" (sed-like), which would corrupt the &amp;/&lt;/&gt;
        # entities into '<lt;', '>gt;', etc.; we therefore disable it for the
        # duration of the escaping and restore the prior state, so '&' is a literal
        # on every bash version (the guards make this a no-op on bashes that lack
        # the option). Order matters: '&' is escaped FIRST so the '&' it introduces
        # is not itself re-escaped by the following < / > passes.
        local esc="$errmsg" _had_patsub=0
        if shopt -q patsub_replacement 2>/dev/null; then _had_patsub=1; fi
        shopt -u patsub_replacement 2>/dev/null || true
        esc="${esc//&/&amp;}"
        esc="${esc//</&lt;}"
        esc="${esc//>/&gt;}"
        if [ "$_had_patsub" -eq 1 ]; then shopt -s patsub_replacement 2>/dev/null || true; fi
        {
            echo '<?xml version="1.0" encoding="UTF-8"?>'
            echo '<testsuites>'
            echo "  <testsuite name=\"$name\" tests=\"1\" failures=\"0\" errors=\"1\" skipped=\"0\" time=\"0\">"
            echo "    <testcase classname=\"$name\" name=\"run\" time=\"0\">"
            echo "      <error message=\"$esc\">$esc</error>"
            echo '    </testcase>'
            echo '  </testsuite>'
            echo '</testsuites>'
        } > "$out"
    else
        {
            echo '<?xml version="1.0" encoding="UTF-8"?>'
            echo '<testsuites>'
            echo "  <testsuite name=\"$name\" tests=\"0\" failures=\"0\" errors=\"0\" skipped=\"0\" time=\"0\"/>"
            echo '</testsuites>'
        } > "$out"
    fi
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
    # WHY (Areas-of-Concern #1): a build failure means pytest never ran, so an
    # error-marked report (not a green tests=0 one) is written -- a pure-XML CI
    # consumer keying only on failures/errors then sees the failure rather than a
    # misleading green empty suite whose non-zero exit code it might ignore.
    carddemo_write_empty_junit "carddemo-e2e" "$CARDDEMO_E2E_REPORT" \
        "e2e build step failed (rc=$build_rc) before pytest could run"
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
# WHY (QA-INT-01 -- an aborted run must never leave a STALE GREEN report):
# pytest writes the --junitxml file only at its pytest_sessionfinish hook. If
# pytest is terminated by a signal (SIGTERM/SIGINT/SIGKILL) before that hook runs,
# the file is never refreshed, so a green report from a PRIOR run in a re-used
# workspace would survive and misrepresent THIS aborted run as fully passing
# (tests=N failures=0) even though the runner correctly exits non-zero. Removing
# any pre-existing report immediately before invoking pytest guarantees a
# signal-killed run leaves NO stale report; a normal run (pass OR fail) re-creates
# an accurate one at sessionfinish. Alternatives Considered: trapping the signal to
# rewrite the report was rejected as racy and shell-fragile; deleting-first is
# deterministic and pairs with the post-run error-marker below.
rm -f "$CARDDEMO_E2E_REPORT"

echo "[e2e] running: ${CARDDEMO_PYTEST[*]} $_edir -m e2e --junitxml=$CARDDEMO_E2E_REPORT ${_passthru[*]:-}"
set +e
"${CARDDEMO_PYTEST[@]}" "$_edir" -m e2e \
    --junitxml="$CARDDEMO_E2E_REPORT" ${_passthru[@]+"${_passthru[@]}"}
pyrc=$?
set -e

mapped_rc="$(carddemo_rc_from_pytest "$pyrc")"
overall_rc="$(carddemo_rc_worst "$overall_rc" "$mapped_rc")"

# WHY (QA-INT-01 / Areas-of-Concern #1 -- keep the machine-readable report in
# agreement with the authoritative exit code): after pytest returns, reconcile the
# junitxml with what actually happened.
#   * Report ABSENT -> pytest was killed before sessionfinish (a signal); it wrote
#     nothing and we already removed any stale copy above. Emit an ERROR-MARKED
#     junit recording the abnormal termination so CI finds a truthful (non-green)
#     report instead of no file at all.
#   * pytest exit 5 (no tests collected) -> pytest DID write a report, but a
#     misleading tests=0/failures=0 (green-looking) one, while the rubric treats an
#     empty enabled layer as FAIL. Overwrite it with an ERROR-MARKED junit so a
#     pure-XML consumer cannot read the aborted/empty run as a pass. The runner's
#     exit code (already aggregated to FAIL) is unchanged.
# A normal completion (exit 0 with passes, or exit 1 with real failures) leaves the
# authoritative pytest-written report untouched.
if [ ! -f "$CARDDEMO_E2E_REPORT" ]; then
    echo "[e2e] pytest wrote no report (exit=$pyrc); emitting error-marked junit" >&2
    carddemo_write_empty_junit "carddemo-e2e" "$CARDDEMO_E2E_REPORT" \
        "pytest terminated abnormally without writing a report (exit=$pyrc); run aborted"
elif [ "$pyrc" -eq 5 ]; then
    echo "[e2e] pytest collected no tests (exit=5); replacing green empty report with error-marked junit" >&2
    carddemo_write_empty_junit "carddemo-e2e" "$CARDDEMO_E2E_REPORT" \
        "pytest collected no tests (exit=5); the enabled e2e layer is empty or broken"
fi

echo "[e2e] ============================================================"
echo "[e2e] pytest exit=$pyrc -> rubric=$mapped_rc ; aggregate RC=$overall_rc"
echo "[e2e] report -> $CARDDEMO_E2E_REPORT"
echo "[e2e] ============================================================"
exit "$overall_rc"
