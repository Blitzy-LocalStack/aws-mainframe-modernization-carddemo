#!/bin/bash
# =============================================================================
# scripts/test_env.sh
# -----------------------------------------------------------------------------
# Purpose:
#   Single source of truth for the CardDemo automated test suite's runtime
#   environment. Exports the GnuCOBOL ASSIGN-name -> file bindings that every
#   batch program under test requires at run time, plus the workspace / build /
#   reports directory paths shared by all runner scripts, and the small set of
#   shared shell helper functions (return-code aggregation and prerequisite
#   checks) used by every runner so the condition-code rubric is applied
#   identically across the whole suite.
#
#   GnuCOBOL binds each `SELECT ... ASSIGN TO <NAME>` clause to a same-named
#   environment variable at run time; this file therefore defines every such
#   NAME once, centrally, to prevent drift between the individual runners.
#
# Usage:
#   source scripts/test_env.sh     # normal use (from a runner or an interactive shell)
#   bash   scripts/test_env.sh     # diagnostic use: prints the resolved environment
#
# Parameters (environment inputs, all optional / overridable before sourcing):
#   CARDDEMO_REPO_ROOT        - Repo root. Auto-detected from BASH_SOURCE if unset.
#   CARDDEMO_TEST_WORKSPACE   - Root of the per-run test workspace.
#   CARDDEMO_BUILD_DIR        - Output dir for compiled COBOL programs.
#   CARDDEMO_REPORTS_DIR      - Output dir for JUnit XML reports.
#   CARDDEMO_DATA_DIR         - Dir holding the ASSIGN-name data files at run time.
#   <ASSIGN-NAME>=...         - Any individual binding may be pre-set to override
#                               the default (cobol_runner.py sets these to per-test
#                               temp files to guarantee isolation).
#
# Return / Exit codes:
#   When sourced : returns 0 on success; returns 1 if the repo root cannot be
#                  resolved (never calls `exit`, to avoid killing the caller's
#                  shell).
#   When executed: exits 0 after printing the resolved environment.
#
# Errors / Exceptions:
#   Prints a diagnostic to stderr and returns 1 (sourced) if repo-root
#   auto-detection fails.
#
# WHY (design rationale):
#   - Assumption: the GnuCOBOL runtime resolves each ASSIGN name via a
#     same-named environment variable, so exporting them here is sufficient to
#     bind every program's files without modifying any production source.
#   - Trade-off: this file is `source`d, not executed, by the runners; it
#     therefore deliberately does NOT enable `set -euo pipefail` and never calls
#     `exit`, because those would leak into and potentially terminate the
#     caller's shell. Fail-fast is applied in the executable runner scripts.
#   - Refactoring rationale: the helper functions live here (rather than being
#     copied into each runner) because every runner sources this file anyway; a
#     single definition keeps the RC rubric DRY and guarantees identical
#     aggregation everywhere.
# =============================================================================

# ---------------------------------------------------------------------------
# Resolve the repository root.
# WHY (Assumption): this file always lives in <repo>/scripts/, so the repo root
# is the parent of this file's directory. Using BASH_SOURCE (not $0) makes the
# detection correct even though the file is sourced rather than executed, and
# independent of the caller's current working directory.
# ---------------------------------------------------------------------------
_carddemo_env_self="${BASH_SOURCE[0]:-$0}"
_carddemo_scripts_dir="$(cd "$(dirname "$_carddemo_env_self")" 2>/dev/null && pwd)"
if [ -z "$_carddemo_scripts_dir" ]; then
    echo "[carddemo] ERROR: unable to resolve scripts directory from '$_carddemo_env_self'." >&2
    # WHY: return (not exit) so a bad source cannot kill an interactive shell.
    return 1 2>/dev/null || exit 1
fi
export CARDDEMO_REPO_ROOT="${CARDDEMO_REPO_ROOT:-$(cd "$_carddemo_scripts_dir/.." && pwd)}"

# ---------------------------------------------------------------------------
# Core workspace / build / reports paths.
# WHY (Refactoring rationale): defining these once here means each runner can
# assume the directories exist instead of repeating mkdir logic (single source
# of truth). All use `${VAR:-default}` so an outer override (from CI or a
# per-test harness) always wins and re-sourcing is idempotent.
# ---------------------------------------------------------------------------
export CARDDEMO_BUILD_DIR="${CARDDEMO_BUILD_DIR:-$CARDDEMO_REPO_ROOT/build}"
export CARDDEMO_REPORTS_DIR="${CARDDEMO_REPORTS_DIR:-$CARDDEMO_REPO_ROOT/reports}"
export CARDDEMO_TEST_WORKSPACE="${CARDDEMO_TEST_WORKSPACE:-$CARDDEMO_BUILD_DIR/test-workspace}"
export CARDDEMO_DATA_DIR="${CARDDEMO_DATA_DIR:-$CARDDEMO_TEST_WORKSPACE/data}"

# WHY (Assumption): dynamically CALL'd subprograms (CSUTLDTC, CBSTM03B) are
# compiled as shared modules; GnuCOBOL locates them at run time via
# COB_LIBRARY_PATH, so default it to the build dir.
export COB_LIBRARY_PATH="${COB_LIBRARY_PATH:-$CARDDEMO_BUILD_DIR}"

# Ensure the shared directories exist (idempotent).
# WHY (Trade-off): `|| true` so that sourcing on a read-only filesystem still
# succeeds for callers that only need the variable bindings, not the dirs.
mkdir -p "$CARDDEMO_BUILD_DIR" "$CARDDEMO_REPORTS_DIR" "$CARDDEMO_DATA_DIR" 2>/dev/null || true

# ---------------------------------------------------------------------------
# GnuCOBOL ASSIGN-name -> file bindings.
# Each name below is a `SELECT ... ASSIGN TO <NAME>` target verified in the
# programs under test in app/cbl/. Individual per-test overrides win because of
# the `${NAME:-...}` form. The complete, de-duplicated set of 28 names follows.
#
# WHY (Assumption): a few names are intentionally distinct because the same
# logical file is ASSIGNed under different external names by different programs
# (the cross-reference file is XREFFILE in most programs but CARDXREF in
# CBTRN03C; the transaction file is TRANFILE in the posting/daily programs but
# TRANSACT in CBACT04C/CBEXPORT). All external names are exported verbatim so
# every program binds correctly.
# ---------------------------------------------------------------------------

# Master / account domain (CBACT01-04C, CBCUS01C, CBACT04C lookups)
export ACCTFILE="${ACCTFILE:-$CARDDEMO_DATA_DIR/ACCTFILE}"
export CARDFILE="${CARDFILE:-$CARDDEMO_DATA_DIR/CARDFILE}"
export XREFFILE="${XREFFILE:-$CARDDEMO_DATA_DIR/XREFFILE}"
export CUSTFILE="${CUSTFILE:-$CARDDEMO_DATA_DIR/CUSTFILE}"
export DISCGRP="${DISCGRP:-$CARDDEMO_DATA_DIR/DISCGRP}"
export TCATBALF="${TCATBALF:-$CARDDEMO_DATA_DIR/TCATBALF}"
export TRANSACT="${TRANSACT:-$CARDDEMO_DATA_DIR/TRANSACT}"

# CBACT01C print / extra sequential outputs
export OUTFILE="${OUTFILE:-$CARDDEMO_DATA_DIR/OUTFILE}"
export ARRYFILE="${ARRYFILE:-$CARDDEMO_DATA_DIR/ARRYFILE}"
export VBRCFILE="${VBRCFILE:-$CARDDEMO_DATA_DIR/VBRCFILE}"

# Daily posting domain (CBTRN01C / CBTRN02C)
export DALYTRAN="${DALYTRAN:-$CARDDEMO_DATA_DIR/DALYTRAN}"
export TRANFILE="${TRANFILE:-$CARDDEMO_DATA_DIR/TRANFILE}"
export DALYREJS="${DALYREJS:-$CARDDEMO_DATA_DIR/DALYREJS}"

# Transaction reporting domain (CBTRN03C)
export CARDXREF="${CARDXREF:-$CARDDEMO_DATA_DIR/CARDXREF}"
export TRANTYPE="${TRANTYPE:-$CARDDEMO_DATA_DIR/TRANTYPE}"
export TRANCATG="${TRANCATG:-$CARDDEMO_DATA_DIR/TRANCATG}"
export TRANREPT="${TRANREPT:-$CARDDEMO_DATA_DIR/TRANREPT}"
export DATEPARM="${DATEPARM:-$CARDDEMO_DATA_DIR/DATEPARM}"

# Statement domain (CBSTM03A / CBSTM03B)
export STMTFILE="${STMTFILE:-$CARDDEMO_DATA_DIR/STMTFILE}"
export HTMLFILE="${HTMLFILE:-$CARDDEMO_DATA_DIR/HTMLFILE}"
export TRNXFILE="${TRNXFILE:-$CARDDEMO_DATA_DIR/TRNXFILE}"

# Export / import domain (CBEXPORT / CBIMPORT)
export EXPFILE="${EXPFILE:-$CARDDEMO_DATA_DIR/EXPFILE}"
export CUSTOUT="${CUSTOUT:-$CARDDEMO_DATA_DIR/CUSTOUT}"
export ACCTOUT="${ACCTOUT:-$CARDDEMO_DATA_DIR/ACCTOUT}"
export XREFOUT="${XREFOUT:-$CARDDEMO_DATA_DIR/XREFOUT}"
export TRNXOUT="${TRNXOUT:-$CARDDEMO_DATA_DIR/TRNXOUT}"
export CARDOUT="${CARDOUT:-$CARDDEMO_DATA_DIR/CARDOUT}"
export ERROUT="${ERROUT:-$CARDDEMO_DATA_DIR/ERROUT}"

# ---------------------------------------------------------------------------
# Condition-code (RC) rubric constants.
# Mirrors the mainframe condition-code convention used by the existing
# scripts/run_*.sh demonstration jobs so CI receives one deterministic status.
# ---------------------------------------------------------------------------
export CARDDEMO_RC_PASS=0     # all good
export CARDDEMO_RC_WARN=4     # soft warning / reject / nothing to do
export CARDDEMO_RC_FAIL=8     # a test or a required step failed
export CARDDEMO_RC_FATAL=16   # abend / unrecoverable
export CARDDEMO_RC_USAGE=2    # operator invoked a script incorrectly (bad args)

carddemo_rc_worst() {
    # Purpose : echo the worst (highest-severity) of the RC arguments given.
    # Parameters:
    #   $@ (int...) - zero or more return codes drawn from the rubric {0,4,8,16}.
    #                 Non-integer tokens are ignored defensively.
    # Returns : always 0; the aggregated RC is written to stdout.
    # Errors  : none (invalid tokens are skipped).
    # WHY (Assumption/Trade-off): the rubric is monotonic (higher value = worse),
    # and every value fed here is first normalised into that rubric, so a plain
    # numeric maximum is a correct and cheap aggregator. Usage errors (RC=2)
    # abort a script immediately and never reach this function, so they cannot
    # distort the maximum.
    local worst=0 rc
    for rc in "$@"; do
        case "$rc" in
            ''|*[!0-9]*) continue ;;   # skip empty / non-numeric tokens
        esac
        if [ "$rc" -gt "$worst" ]; then
            worst="$rc"
        fi
    done
    echo "$worst"
}

carddemo_rc_from_pytest() {
    # Purpose : translate a pytest process exit status into the CardDemo rubric.
    # Parameters:
    #   $1 (int) - pytest's raw exit code.
    # Returns : always 0; the mapped rubric RC is written to stdout.
    # Errors  : none.
    # WHY (Refactoring rationale): pytest's exit codes are NOT ordered by
    # severity (1 = tests failed, 5 = no tests collected), so feeding them
    # straight into a numeric max would wrongly rank a real failure (1) below a
    # warning (4). Mapping them explicitly keeps the aggregate status meaningful.
    local pyrc="${1:-1}" mapped
    case "$pyrc" in
        0) mapped="$CARDDEMO_RC_PASS" ;;   # all tests passed
        5) mapped="$CARDDEMO_RC_WARN" ;;   # no tests collected -> warn (a layer's
                                           # suite may be built in parallel and be
                                           # absent at run time)
        1) mapped="$CARDDEMO_RC_FAIL" ;;   # one or more tests failed
        *) mapped="$CARDDEMO_RC_FAIL" ;;   # 2/3/4 (interrupt/internal/usage) -> fail
    esac
    echo "$mapped"
}

carddemo_require_cmd() {
    # Purpose : verify an external command is on PATH, emitting a clear
    #           diagnostic if it is not.
    # Parameters:
    #   $1 (string) - command name (required).
    #   $2 (string) - optional remediation hint appended to the diagnostic.
    # Returns : 0 if the command exists, 1 otherwise.
    # Errors  : writes a one-line diagnostic to stderr when the command is
    #           absent; never exits (the caller decides the severity).
    # WHY (Trade-off): returning 0/1 rather than a rubric code keeps the function
    # usable directly in `if` tests, while letting each caller choose whether a
    # missing tool is a hard failure (e.g. cobc for the build) or a soft skip
    # (e.g. localstack for the optional AWS layer).
    local cmd="${1:-}" hint="${2:-}"
    if [ -z "$cmd" ]; then
        echo "[carddemo] ERROR: carddemo_require_cmd called without a command name." >&2
        return 1
    fi
    if command -v "$cmd" >/dev/null 2>&1; then
        return 0
    fi
    echo "[carddemo] ERROR: required command '$cmd' not found on PATH. ${hint}" >&2
    return 1
}

# ---------------------------------------------------------------------------
# Diagnostic entry point.
# WHY (Trade-off): this file is intended to be *sourced*, but running it
# directly is a convenient way to inspect the resolved bindings. The two cases
# are distinguished by comparing BASH_SOURCE[0] with $0 (equal only when the
# file is executed rather than sourced).
# ---------------------------------------------------------------------------
if [ "${BASH_SOURCE[0]:-}" = "${0:-}" ]; then
    echo "[carddemo] test_env.sh is meant to be sourced: 'source scripts/test_env.sh'"
    echo "[carddemo] Resolved environment:"
    echo "  CARDDEMO_REPO_ROOT      = $CARDDEMO_REPO_ROOT"
    echo "  CARDDEMO_BUILD_DIR      = $CARDDEMO_BUILD_DIR"
    echo "  CARDDEMO_REPORTS_DIR    = $CARDDEMO_REPORTS_DIR"
    echo "  CARDDEMO_TEST_WORKSPACE = $CARDDEMO_TEST_WORKSPACE"
    echo "  CARDDEMO_DATA_DIR       = $CARDDEMO_DATA_DIR"
    echo "  COB_LIBRARY_PATH        = $COB_LIBRARY_PATH"
    echo "[carddemo] ASSIGN-name bindings:"
    for _carddemo_n in ACCTFILE CARDFILE XREFFILE CUSTFILE DISCGRP TCATBALF TRANSACT \
                       OUTFILE ARRYFILE VBRCFILE DALYTRAN TRANFILE DALYREJS \
                       CARDXREF TRANTYPE TRANCATG TRANREPT DATEPARM \
                       STMTFILE HTMLFILE TRNXFILE \
                       EXPFILE CUSTOUT ACCTOUT XREFOUT TRNXOUT CARDOUT ERROUT; do
        # WHY: ${!name} indirect expansion prints each binding without eval.
        echo "  $_carddemo_n = ${!_carddemo_n}"
    done
    unset _carddemo_n
fi
