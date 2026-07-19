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
#   scripts/run_tests.sh [--fail-fast|-x] [--with-localstack|--require-localstack]
#                        [--require-cobol] [--coverage] [-h|--help]
#     source scripts/test_env.sh is performed internally; no prior setup needed.
#
# Parameters:
#   --fail-fast, -x    stop at the first layer that FAILS (rc>=8); default is
#                      run-all-layers-then-report.
#   --with-localstack  bring up LocalStack for the e2e dataset-staging tests AND
#                      REQUIRE it: exports CARDDEMO_REQUIRE_LOCALSTACK=1 so that,
#                      if the emulator cannot be reached, the mandatory real-S3
#                      staging tests HARD-FAIL (rc>=8) instead of soft-skipping.
#   --require-localstack  explicit synonym for --with-localstack (identical
#                      bring-up + REQUIRE behaviour); provided for discoverability
#                      so the enforcement is obvious at the call site.
#   --require-cobol    escalate a blocked COBOL deliverable (CBEXPORT/CBIMPORT,
#                      which only WARN by default) into a HARD failure: exports
#                      CARDDEMO_REQUIRE_COBOL=1 so the conftest gate turns the
#                      export/import skips into failures (rc>=8). Opt-in because
#                      the .cbl defect is unfixable here (app/cbl REFERENCE-only).
#   --coverage         measure coverage across the whole suite from this one
#                      command: exports CARDDEMO_COVERAGE=1 (gcov-instruments the
#                      COBOL build; runs the Python layers under coverage.py), then
#                      combines the data and writes reports/coverage.xml plus a
#                      console summary. Below the harness threshold is informational
#                      (AAP 0.7 -- a recommendation, not an SLA), not a suite fail.
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
#   - Assumption (finding F3): passing --with-localstack is an EXPLICIT opt-in, so
#     it must ENFORCE what it advertises. The master therefore exports
#     CARDDEMO_REQUIRE_LOCALSTACK=1, which both setup_localstack.sh (bring-up) and
#     the conftest LocalStack gate honour, converting an unreachable emulator from
#     a silent soft-skip into a hard failure. Alternatives Considered: a separate
#     "bring-up-but-tolerate-absence" flag was rejected -- the flag-absent default
#     already provides exactly that lenient behaviour (staging soft-skips as warn),
#     so a second lenient spelling would only blur the contract.
# =============================================================================

# WHY (Refactoring rationale): -u catches unset-variable bugs and -o pipefail
# surfaces failures inside pipelines, but -e is intentionally omitted (see header)
# so a failing layer does not abort aggregation.
set -uo pipefail

_master_script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# WHY (F-P3 -- one workspace shared by this master and every child stage, then
# reclaimed in a single sweep): scripts/test_env.sh keys its per-run workspace on
# CARDDEMO_RUN_ID (falling back to the sourcing shell's PID) and EXPORTS
# CARDDEMO_TEST_WORKSPACE. Exporting a stable run id HERE -- before sourcing --
# makes this master and all four child stages (build + unit + integration + e2e)
# agree on ONE run-<id> directory, which the EXIT trap below removes exactly once.
# Without it the master's own run-<PID> workspace shell leaked on every invocation
# (unbounded count over repeated CI runs). Assumption: an outer orchestrator that
# already exported CARDDEMO_RUN_ID still wins via the ${VAR:-default} form.
export CARDDEMO_RUN_ID="${CARDDEMO_RUN_ID:-$$}"

# shellcheck source=scripts/test_env.sh
source "$_master_script_dir/test_env.sh"

# WHY (F-P3 -- no orphaned workspaces): reclaim the per-run workspace on EVERY exit
# path so repeated CI invocations do not accumulate empty
# $TMPDIR/carddemo-test-<uid>/run-* shells. carddemo_cleanup_workspace is
# containment-guarded (it deletes ONLY a path under our own CARDDEMO_WS_BASE, never
# build/ or reports/, which live in the repo, not the workspace). Trade-off: a bash
# EXIT trap runs its body but does NOT change the script's exit status unless it
# calls `exit`, so the aggregate RC returned below is preserved. Alternatives
# Considered: installing the trap inside test_env.sh was rejected (a trap set by a
# *sourced* file fires on the CALLER's lifecycle) -- the runner owns the run, so the
# runner owns teardown (mirrors run_unit_tests.sh).
trap 'carddemo_sweep_stray_coverage; carddemo_cleanup_workspace' EXIT

carddemo_master_usage() {
    # Purpose : print the master runner's usage synopsis.
    # Parameters: none.
    # Returns : always 0.
    # Errors  : none.
    cat <<USAGE
Usage: scripts/run_tests.sh [--fail-fast|-x]
                            [--with-localstack|--require-localstack]
                            [--require-cobol] [--coverage] [--audit] [-h|--help]

Runs the full CardDemo test suite (build -> unit -> integration -> e2e) and
exits with the worst condition code (0 pass / 4 warn / 8 fail / 16 fatal).

Options:
  -x, --fail-fast        stop at the first failing layer (rc>=8)
      --with-localstack  bring up LocalStack for the e2e AWS staging tests AND
                         require it (unreachable emulator -> hard fail, not skip)
      --require-localstack  synonym for --with-localstack (explicit enforcement)
      --require-cobol    escalate blocked CBEXPORT/CBIMPORT (WARN by default) to a
                         hard failure (rc>=8); opt-in, the .cbl is unfixable here
      --coverage         instrument the suite and write reports/coverage.xml (+ a
                         console summary) from this single command
      --audit            run the Python dependency supply-chain audit gate
                         (pip-audit vs tests/requirements-test.txt, adjudicated by
                         tests/audit-allowlist.json) and write reports/audit-python.json;
                         offline/tool-absent degrades to WARN, never a hard fail
  -h, --help             show this help and exit
USAGE
}

# ---------------------------------------------------------------------------
# Parse options.
# WHY (Assumption): a genuine CLI mistake should be loud and DISTINCT from a
# test outcome, hence a dedicated usage rc (2) that is never aggregated.
# ---------------------------------------------------------------------------
_fail_fast=0
_with_localstack=0
_require_cobol=0
_coverage=0
_audit=0
while [ "$#" -gt 0 ]; do
    case "$1" in
        -x|--fail-fast) _fail_fast=1 ;;
        # WHY (finding F3): --with-localstack and its explicit synonym
        # --require-localstack both mean "bring up AND require"; they set the same
        # flag so the enforcement wiring below has a single source of truth and the
        # two spellings can never drift into different behaviours.
        --with-localstack|--require-localstack) _with_localstack=1 ;;
        # WHY (finding F2): --require-cobol lets an operator escalate a blocked
        # COBOL deliverable (CBEXPORT/CBIMPORT, which only WARN by default) into a
        # hard failure. It is OPT-IN (not the default) precisely because the
        # blocked .cbl cannot be fixed (app/cbl is REFERENCE-only per AAP 0.8.2),
        # so defaulting it on would redden CI permanently.
        --require-cobol) _require_cobol=1 ;;
        # WHY (finding F4): --coverage wires the whole suite for a single-command
        # coverage report -- it instruments the COBOL build (gcov) and runs the
        # Python layers under coverage.py, then combines + writes reports/coverage.xml.
        --coverage) _coverage=1 ;;
        # WHY (finding F-SUPPLY-NO-AUDIT-GATE): --audit is an OPT-IN supply-chain
        # gate that audits the pinned Python test dependencies against the published
        # advisory DB. It is opt-in (not default) because it needs network access to
        # the advisory DB; on an offline developer box or air-gapped CI it degrades
        # to WARN rather than blocking, so the default `run_tests.sh` stays
        # deterministic and network-free. CI arms it in a dedicated enforcing step.
        --audit) _audit=1 ;;
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

# ---------------------------------------------------------------------------
# LocalStack enforcement wiring (finding F3).
# WHY: the finding is that --with-localstack ENABLED but did not REQUIRE the
# emulator, so an unreachable LocalStack degraded the mandatory real-S3 staging
# tests to a silent soft-skip (warn) instead of a failure. Exporting
# CARDDEMO_REQUIRE_LOCALSTACK=1 here -- at the public entry point, when the user
# explicitly opted in -- makes BOTH the bring-up (setup_localstack.sh) and the
# conftest LocalStack gate treat an absent/unhealthy emulator as a hard failure
# (rc>=8). Trade-off: this is intentionally exported for the WHOLE suite process
# (not just the e2e child) so the gate is armed no matter which layer reaches the
# staging tests; the flag-absent path leaves it unset, preserving the lenient
# default. This also makes the standalone CLI consistent with CI, which already
# arms the same variable in its --with-localstack job.
# ---------------------------------------------------------------------------
_e2e_args=()
if [ "$_with_localstack" = "1" ]; then
    export CARDDEMO_REQUIRE_LOCALSTACK=1
    _e2e_args=(--with-localstack)
fi

# ---------------------------------------------------------------------------
# Blocked-COBOL enforcement wiring (finding F2).
# WHY: by default a KNOWN-UNSUPPORTED program (CBEXPORT/CBIMPORT) that fails to
# compile against the immutable baseline is a WARN (rc=4) -- honestly non-green
# but non-fatal, so the rest of the suite still runs. Passing --require-cobol
# exports CARDDEMO_REQUIRE_COBOL=1, which the conftest gate honours by escalating
# the corresponding export/import test skips into HARD failures (rc>=8). Trade-off:
# this is opt-in, not default, because the .cbl defect cannot be fixed (app/cbl is
# REFERENCE-only per AAP 0.8.2); forcing it on would make CI permanently red on a
# condition no one is allowed to remediate here. Offering the flag still gives a
# team that DOES intend to fix the copybook a single switch to make the gap fatal.
# ---------------------------------------------------------------------------
if [ "$_require_cobol" = "1" ]; then
    export CARDDEMO_REQUIRE_COBOL=1
fi

# ---------------------------------------------------------------------------
# Coverage wiring (finding F4) -- PRE-STAGE setup.
# WHY: --coverage must instrument BOTH sides of the suite from one command, so
# the switches are exported BEFORE any stage runs:
#   * CARDDEMO_COVERAGE=1 makes build_test_programs.sh add gcov flags to the COBOL
#     transpiled C (best-effort COBOL line coverage) and makes the integration/e2e
#     sub-runners run pytest under coverage.py (Python-harness coverage).
#   * COVERAGE_FILE is pinned to an ABSOLUTE path so every child process (each
#     sub-runner, possibly in a different CWD) and the master's post-stage
#     `coverage combine` agree on ONE data-file base regardless of CWD -- the
#     alternative (relying on a shared CWD) was rejected as fragile across the
#     `bash <child>` invocations.
# A stale-data `coverage erase` is best-effort so a prior run cannot inflate this
# run's numbers. Coverage tooling is an opt-in add-on: if `coverage` is missing we
# degrade gracefully (the sub-runners warn and run uninstrumented).
# ---------------------------------------------------------------------------
_coverage_cmd=()
if [ "$_coverage" = "1" ]; then
    export CARDDEMO_COVERAGE=1
    export COVERAGE_FILE="$CARDDEMO_REPO_ROOT/.coverage"
    if command -v coverage >/dev/null 2>&1; then
        _coverage_cmd=(coverage)
    elif command -v python3 >/dev/null 2>&1 && python3 -c 'import coverage' >/dev/null 2>&1; then
        _coverage_cmd=(python3 -m coverage)
    fi
    if [ "${#_coverage_cmd[@]}" -gt 0 ]; then
        # WHY: erase from the repo root so the default/absolute data file and any
        # leftover parallel shards from a previous run are cleared consistently.
        ( cd "$CARDDEMO_REPO_ROOT" && "${_coverage_cmd[@]}" erase --rcfile="$CARDDEMO_COVERAGERC" ) \
            >/dev/null 2>&1 || true
    else
        echo "[run_tests] WARN: --coverage requested but 'coverage' not available;" >&2
        echo "[run_tests]       layers run uninstrumented and no coverage.xml is produced." >&2
    fi
fi

echo "[run_tests] ############################################################"
echo "[run_tests] # CardDemo full test suite"
echo "[run_tests] #   repo      : $CARDDEMO_REPO_ROOT"
echo "[run_tests] #   reports   : $CARDDEMO_REPORTS_DIR"
echo "[run_tests] #   fail-fast : $_fail_fast ; with-localstack : $_with_localstack ; require-localstack : ${CARDDEMO_REQUIRE_LOCALSTACK:-0} ; require-cobol : ${CARDDEMO_REQUIRE_COBOL:-0} ; coverage : $_coverage"
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
    # WHY (F-P5 -- build once, reuse across layers): the master has now compiled
    # every unit-under-test AND every COBOL test program (--with-tests is a SUPERSET
    # of what each layer needs), so signalling the three layer runners to skip their
    # own rebuild elides 3 identical recompiles (~74% of master runtime) while
    # keeping recompile-always for a STANDALONE layer-runner invocation (which never
    # sees this flag). Guard: only set the flag when the build actually SUCCEEDED
    # (rc < FAIL); the build is the FIRST stage, so overall_rc == the build rc here.
    # A failed build must NOT set the flag, or a layer runner would run against
    # stale/absent binaries instead of re-detecting the failure itself. Trade-off
    # (safety + standalone-runnability vs duplicate cost): the flag preserves both
    # properties and removes the cost.
    if [ "$overall_rc" -lt "${CARDDEMO_RC_FAIL}" ]; then
        export CARDDEMO_SKIP_BUILD=1
    fi
    carddemo_stage "unit"        bash "$_master_script_dir/run_unit_tests.sh"                   || break
    carddemo_stage "integration" bash "$_master_script_dir/run_integration_tests.sh"           || break
    carddemo_stage "e2e"         bash "$_master_script_dir/run_e2e_tests.sh" ${_e2e_args[@]+"${_e2e_args[@]}"} || break
    break
done

# ---------------------------------------------------------------------------
# Coverage wiring (finding F4) -- POST-STAGE combine + report.
# WHY: coverage.py parallel mode wrote one data shard per process; they must be
# COMBINED before a report is meaningful. We then emit reports/coverage.xml (the
# CI-ingestable artifact this finding is about) and print a console summary. All
# steps run from the repo root so the rcfile's repo-root-relative source/output
# paths resolve. Trade-off / mapping decision (AAP 0.7 -- the >=90% line target is
# a RECOMMENDATION, not an SLA, and the mandatory branch coverage is already met):
#   * a coverage TOOLING problem (missing tool, no data, xml not written) is a
#     WARN -- you asked for coverage and we could not fully deliver it; while
#   * being BELOW the harness fail_under threshold (coverage's exit 2, with the
#     xml STILL written) is INFORMATIONAL only and is deliberately NOT folded into
#     the suite RC, so an under-tested helper cannot masquerade as a test failure
#     nor mask the real test outcome. The console `coverage report` still shows the
#     percentages for visibility.
# ---------------------------------------------------------------------------
if [ "$_coverage" = "1" ]; then
    _cov_xml="$CARDDEMO_REPORTS_DIR/coverage.xml"
    if [ "${#_coverage_cmd[@]}" -gt 0 ]; then
        (
            cd "$CARDDEMO_REPO_ROOT" || exit 0
            # Combine the per-process parallel shards; "no data" is tolerated and
            # surfaced by the artifact check below rather than aborting here.
            "${_coverage_cmd[@]}" combine --rcfile="$CARDDEMO_COVERAGERC" >/dev/null 2>&1 || true
            # Write the XML artifact. -o is explicit so it is CWD-independent and
            # cannot be silently redirected by an rcfile edit.
            "${_coverage_cmd[@]}" xml --rcfile="$CARDDEMO_COVERAGERC" -o "$_cov_xml" >/dev/null 2>&1 || true
            # Human-readable console summary (best-effort; fail_under exit 2 is
            # informational only, hence `|| true`).
            "${_coverage_cmd[@]}" report --rcfile="$CARDDEMO_COVERAGERC" 2>/dev/null || true
        )
        if [ -s "$_cov_xml" ]; then
            echo "[run_tests] coverage: wrote $_cov_xml"
            _summary+=("$(printf '  %-12s %s' "coverage" "reports/coverage.xml")")
        else
            echo "[run_tests] WARN: --coverage produced no coverage.xml (no data or tool error)" >&2
            overall_rc="$(carddemo_rc_worst "$overall_rc" "${CARDDEMO_RC_WARN}")"
            _summary+=("$(printf '  %-12s %s' "coverage" "MISSING (warn)")")
        fi
    else
        # Tool was unavailable (already warned pre-stage); surface it in the summary
        # and as a WARN so a requested-but-undelivered coverage run is not silent.
        overall_rc="$(carddemo_rc_worst "$overall_rc" "${CARDDEMO_RC_WARN}")"
        _summary+=("$(printf '  %-12s %s' "coverage" "unavailable (warn)")")
    fi

    # -----------------------------------------------------------------------
    # COBOL coverage (finding F-COVERAGE-NO-COBOL-REPORT) -- publish the gcov
    # report for the transpiled C of every instrumented unit-under-test.
    # WHY separate from the coverage.py branch above (Refactoring Rationale):
    # the .gcno/.gcda were produced by the instrumented COBOL BUILD
    # (CARDDEMO_COVERAGE=1) and accumulated as the layers executed the compiled
    # programs, so a COBOL report can be published even when the Python
    # `coverage` tool was absent. WHY fold its result WARN-only: a coverage
    # measurement/tooling gap must not turn the suite red -- the AAP 0.7.1 90/85
    # figures are a non-contractual recommendation and the mandatory
    # business-rule branch coverage is asserted by the tests themselves -- which
    # mirrors the Python-side philosophy documented above. carddemo_cobol_coverage_report
    # returns 0 on a complete report and CARDDEMO_RC_WARN on any gap.
    # -----------------------------------------------------------------------
    carddemo_cobol_coverage_report "$CARDDEMO_BUILD_DIR" "$CARDDEMO_REPORTS_DIR"
    _cobol_cov_rc=$?
    if [ -s "$CARDDEMO_REPORTS_DIR/cobol-coverage.xml" ]; then
        _summary+=("$(printf '  %-12s %s' "cobol-cov" "reports/cobol-coverage.xml")")
    else
        _summary+=("$(printf '  %-12s %s' "cobol-cov" "MISSING (warn)")")
    fi
    if [ "$_cobol_cov_rc" -ne 0 ]; then
        overall_rc="$(carddemo_rc_worst "$overall_rc" "$_cobol_cov_rc")"
    fi
fi

# ---------------------------------------------------------------------------
# Python dependency supply-chain audit gate (finding F-SUPPLY-NO-AUDIT-GATE)
# -- POST-STAGE. WHY run it here: after the test layers so a network probe to
# the advisory DB never delays test feedback, and so its JSON report joins the
# other reports/ artifacts for CI upload. carddemo_audit_python_deps returns
# 0 (clean) / 4 (WARN: pip-audit or jq absent, or advisory DB unreachable
# offline) / 8 (FAIL: an unadjudicated advisory is present). Its rc is folded
# into the aggregate with the SAME worst-rc rule as every test stage, so a
# genuine advisory becomes a real failure WHEN the audit can run, while the
# offline / tool-absent path is only a WARN and can never hard-fail a
# network-free run (satisfying "degrade to WARN offline"). Trade-off: opt-in
# (--audit) not always-on, so the default suite stays deterministic + offline.
# ---------------------------------------------------------------------------
if [ "$_audit" = "1" ]; then
    echo ""
    echo "[run_tests] ===================== STAGE: audit ====================="
    _audit_rc=0
    carddemo_audit_python_deps \
        "$CARDDEMO_REPO_ROOT/tests/requirements-test.txt" \
        "$CARDDEMO_REPO_ROOT/tests/audit-allowlist.json" \
        "$CARDDEMO_REPORTS_DIR/audit-python.json" || _audit_rc=$?
    if [ -s "$CARDDEMO_REPORTS_DIR/audit-python.json" ]; then
        _summary+=("$(printf '  %-12s rc=%s (%s)' "audit" "$_audit_rc" "reports/audit-python.json")")
    else
        _summary+=("$(printf '  %-12s rc=%s (%s)' "audit" "$_audit_rc" "no report")")
    fi
    overall_rc="$(carddemo_rc_worst "$overall_rc" "$_audit_rc")"
    echo "[run_tests] stage 'audit' rc=$_audit_rc ; running aggregate=$overall_rc"
fi

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
