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
    # WHY (Trade-off): `return` when this file is sourced (the normal case) so a
    # bad source cannot kill an interactive shell; `exit` only when it is executed
    # directly. The `return ... || exit` idiom expresses both modes without an
    # explicit sourced/executed branch here.
    # shellcheck disable=SC2317  # MI-01: the `exit` fallback is reachable ONLY in
    # executed mode; shellcheck cannot know `return` fails outside a function when
    # executed, so it wrongly marks the fallback unreachable -- this directive
    # documents that the guard is intentional and correct.
    return 1 2>/dev/null || exit 1
fi
export CARDDEMO_REPO_ROOT="${CARDDEMO_REPO_ROOT:-$(cd "$_carddemo_scripts_dir/.." && pwd)}"

# ---------------------------------------------------------------------------
# Trusted, shared, repo-local build / reports paths (the "trusted cache").
# WHY (MA-04 -- separate the trusted cache from the mutable workspace):
# compiled programs and CI reports are reusable build artifacts, not per-test
# mutable state, so they stay in stable repo-local dirs that concurrent runs may
# share safely. They are git-ignored as generated outputs (see .gitignore, MI-06).
# All use `${VAR:-default}` so an outer override always wins and re-sourcing is
# idempotent.
# ---------------------------------------------------------------------------
export CARDDEMO_BUILD_DIR="${CARDDEMO_BUILD_DIR:-$CARDDEMO_REPO_ROOT/build}"
export CARDDEMO_REPORTS_DIR="${CARDDEMO_REPORTS_DIR:-$CARDDEMO_REPO_ROOT/reports}"

# ---------------------------------------------------------------------------
# Per-run, per-worker, CONTAINED test workspace (mutable, isolated) -- MA-04.
# WHY (unique contained workspace per run/worker): the previous default was a
# single shared repo-local dir (build/test-workspace) that every parallel
# pytest-xdist worker wrote into, so concurrent runs corrupted each other's data
# and generated files leaked into the repo tree. The workspace now lives OUTSIDE
# the repo, under a per-UID base in TMPDIR, keyed by a stable-per-run id and (when
# present) the xdist worker name, so concurrent runs/workers never collide and
# nothing mutable is written inside the repo (Repository Hygiene, MI-06).
# WHY (Alternatives Considered): a fresh `mktemp` on every source() was rejected
# because this file is sourced by several cooperating processes in one pipeline
# that must agree on ONE workspace. A stable key (CARDDEMO_RUN_ID, else the
# sourcing shell's PID) gives that agreement while staying unique across runs; a
# parent runner that wants all children to share one workspace exports
# CARDDEMO_RUN_ID. An explicit CARDDEMO_TEST_WORKSPACE override still wins and is
# symlink-validated before use by carddemo_ensure_workspace().
# ---------------------------------------------------------------------------
_carddemo_ws_base="${TMPDIR:-/tmp}"
# WHY (MI-01 / SC2155): compute the uid in its own statement first, so the
# command substitution's exit status is not masked by the `export` assignment.
_carddemo_uid="$(id -u 2>/dev/null || echo 0)"
export CARDDEMO_WS_BASE="${_carddemo_ws_base%/}/carddemo-test-$_carddemo_uid"
_carddemo_run_id="${CARDDEMO_RUN_ID:-$$}"
# WHY (Assumption/defensive): the xdist worker token comes from the environment,
# so it is sanitised to a safe path component -- only [A-Za-z0-9_-] survive -- to
# prevent a crafted PYTEST_XDIST_WORKER from escaping the workspace path.
_carddemo_worker="${PYTEST_XDIST_WORKER:-}"
_carddemo_worker="${_carddemo_worker//[^A-Za-z0-9_-]/}"
export CARDDEMO_TEST_WORKSPACE="${CARDDEMO_TEST_WORKSPACE:-$CARDDEMO_WS_BASE/run-$_carddemo_run_id${_carddemo_worker:+/$_carddemo_worker}}"
export CARDDEMO_DATA_DIR="${CARDDEMO_DATA_DIR:-$CARDDEMO_TEST_WORKSPACE/data}"

# ---------------------------------------------------------------------------
# COB_LIBRARY_PATH canonical merge (MA-02).
# WHY: dynamically CALL'd subprograms (CSUTLDTC, CBSTM03B, CBACT04C) are compiled
# as shared modules that GnuCOBOL locates at run time via COB_LIBRARY_PATH. The
# previous `${COB_LIBRARY_PATH:-$BUILD_DIR}` form silently OMITTED the build dir
# whenever the caller already had COB_LIBRARY_PATH set, leaving freshly-built
# modules unresolvable. We now PREPEND the build dir (idempotently) so the build
# output is always first on the search path without discarding an inherited one.
# ---------------------------------------------------------------------------
if [ -n "${COB_LIBRARY_PATH:-}" ]; then
    case ":$COB_LIBRARY_PATH:" in
        *":$CARDDEMO_BUILD_DIR:"*) : ;;   # build dir already present -> no change
        *) export COB_LIBRARY_PATH="$CARDDEMO_BUILD_DIR:$COB_LIBRARY_PATH" ;;
    esac
else
    export COB_LIBRARY_PATH="$CARDDEMO_BUILD_DIR"
fi

# Create the TRUSTED dirs eagerly (cheap; the build needs BUILD_DIR).
# WHY (MA-04 -- failures are surfaced, not swallowed): the previous `|| true`
# hid a failed mkdir; we now emit a diagnostic and record CARDDEMO_ENV_WARN so a
# diligent caller can detect it, while still not hard-aborting a sourced shell.
# The mutable workspace is provisioned by carddemo_ensure_workspace() (defined
# below and invoked once helpers exist) so a build needing only BUILD_DIR is
# unaffected by a workspace that cannot yet be created.
export CARDDEMO_ENV_WARN=0
for _carddemo_d in "$CARDDEMO_BUILD_DIR" "$CARDDEMO_REPORTS_DIR"; do
    if ! mkdir -p "$_carddemo_d" 2>/dev/null; then
        echo "[carddemo] WARN: could not create trusted dir '$_carddemo_d'" >&2
        CARDDEMO_ENV_WARN=1
    fi
done
unset _carddemo_d

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
        # WHY (MA-03 -- reserve RC=4 for expected business rejects only): pytest
        # exit 5 means "no tests were collected". At the remediation stage every
        # test layer exists, so an empty collection is an ABSENT ENABLED LAYER
        # (a mis-selected path, a broken import, or a lost test tree), NOT a
        # benign warning. It is therefore mapped to FAIL(8) so a silently empty
        # run can never be mistaken for success. RC=4 stays reserved for genuine
        # business rejects (e.g. posting reject codes) surfaced by the tests.
        5) mapped="$CARDDEMO_RC_FAIL" ;;   # no tests collected -> absent layer -> FAIL
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

carddemo_reject_symlink() {
    # Purpose : fail if a path exists and is a symbolic link (MA-04 path-safety).
    # Parameters:
    #   $1 (string) - absolute path to check.
    # Returns : 0 if the path is absent or a real (non-symlink) entry; 1 if it is
    #           a symbolic link.
    # Errors  : writes a diagnostic to stderr when rejecting a symlink.
    # WHY (Trade-off): a pre-existing symlink at a workspace/output path is the
    # classic "redirect the write" attack -- mkdir -p and subsequent writes would
    # happily follow it out of the contained area. Refusing to use a symlinked
    # leaf fails closed. We check the leaf explicitly (mkdir -p alone would
    # traverse a symlinked component silently).
    local p="${1:-}"
    if [ -L "$p" ]; then
        echo "[carddemo] ERROR: refusing to use symlinked path '$p'" >&2
        return 1
    fi
    return 0
}

carddemo_ensure_dir() {
    # Purpose : create a directory, refusing symlinks and surfacing failures.
    # Parameters:
    #   $1 (string) - absolute directory path.
    # Returns : 0 on success; 1 on symlink rejection, mkdir failure, or a
    #           non-writable result.
    # Errors  : diagnostics to stderr; never exits (safe when sourced).
    # WHY (MA-04): centralises the "safe mkdir" so every mutable path is created
    # the same way -- symlink-checked and writability-verified -- rather than a
    # bare `mkdir -p ... || true` that hides both problems.
    local d="${1:-}"
    if [ -z "$d" ]; then
        echo "[carddemo] ERROR: carddemo_ensure_dir requires a path." >&2
        return 1
    fi
    carddemo_reject_symlink "$d" || return 1
    if ! mkdir -p "$d" 2>/dev/null; then
        echo "[carddemo] ERROR: cannot create directory '$d'." >&2
        return 1
    fi
    if [ ! -w "$d" ]; then
        echo "[carddemo] ERROR: directory not writable: '$d'." >&2
        return 1
    fi
    return 0
}

carddemo_ensure_workspace() {
    # Purpose : provision the per-run mutable workspace and its data dir (MA-04).
    # Parameters: none (reads CARDDEMO_TEST_WORKSPACE / CARDDEMO_DATA_DIR).
    # Returns : 0 on success; 1 if either directory cannot be safely created.
    # Errors  : diagnostics to stderr via carddemo_ensure_dir.
    # WHY (Refactoring rationale): runners call this the moment they actually need
    # the workspace, so a pure build (which needs only the trusted BUILD_DIR) is
    # never blocked by a workspace that cannot be provisioned.
    carddemo_ensure_dir "$CARDDEMO_TEST_WORKSPACE" || return 1
    carddemo_ensure_dir "$CARDDEMO_DATA_DIR" || return 1
    return 0
}

carddemo_cleanup_workspace() {
    # Purpose : remove the per-run workspace tree ("clean it", MA-04).
    # Parameters: none (reads CARDDEMO_TEST_WORKSPACE / CARDDEMO_WS_BASE).
    # Returns : always 0 (best-effort; a missing tree is not an error).
    # Errors  : none.
    # WHY (Trade-off / fail-safe): we delete ONLY a workspace that sits UNDER our
    # own per-UID TMPDIR base, never a parent, the repo, or an operator-supplied
    # path outside the base. This guarantees an accidental CARDDEMO_TEST_WORKSPACE
    # override to a sensitive location can never trigger a destructive recursive
    # remove -- the containment check is the safety interlock.
    local ws="${CARDDEMO_TEST_WORKSPACE:-}" base="${CARDDEMO_WS_BASE:-}"
    if [ -z "$ws" ] || [ -z "$base" ]; then
        return 0
    fi
    case "$ws" in
        "$base"/*) rm -rf "$ws" 2>/dev/null || true ;;
        *) echo "[carddemo] not cleaning workspace outside contained base: $ws" >&2 ;;
    esac
    return 0
}

# Eagerly provision the mutable workspace so consumers that expect
# CARDDEMO_DATA_DIR to exist after sourcing still find it. Failures are surfaced
# (MA-04) via CARDDEMO_ENV_WARN, never silently swallowed, and never fatal to a
# sourced shell.
if ! carddemo_ensure_workspace; then
    echo "[carddemo] WARN: per-run workspace not provisioned at source time" >&2
    CARDDEMO_ENV_WARN=1
fi

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
