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
# WHY (finding F4): the coverage rcfile path is centralised here -- the single
# file every runner sources -- so the master (coverage combine/xml) and the
# per-layer coverage-run wrappers all reference ONE rcfile and can never drift.
# It is repo-root-relative because tests/.coveragerc's `source`/`[xml]` paths are
# written relative to the repo root (coverage is invoked from there).
export CARDDEMO_COVERAGERC="${CARDDEMO_COVERAGERC:-$CARDDEMO_REPO_ROOT/tests/.coveragerc}"

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
# WHY (F-ENV1 -- containment at the source): CARDDEMO_RUN_ID is an ENVIRONMENT
# input (an outer orchestrator exports it so several cooperating processes share
# ONE workspace), so a crafted value such as '../../evil' would otherwise embed a
# path-traversal component into CARDDEMO_TEST_WORKSPACE below and let BOTH the
# provisioning (mkdir -p) and the recursive cleanup (rm -rf) escape the contained
# per-UID base. We sanitise it with the SAME idiom already applied to
# PYTEST_XDIST_WORKER just below -- only [A-Za-z0-9_-] survive -- so no '.'/'/'
# can traverse out of the base. Assumption: the default ($$, a pure integer) and
# the documented shared-RUN_ID PID pattern pass through unchanged, so normal runs
# are unaffected. Trade-off: two different hostile ids can collapse to the same
# sanitised token, but that is a benign workspace-name collision, never an
# escape -- identical to the worker-token property relied on immediately below.
_carddemo_run_id="${_carddemo_run_id//[^A-Za-z0-9_-]/}"
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

# ---------------------------------------------------------------------------
# Repo-local virtualenv PATH self-provisioning (finding F1).
# Purpose:
#   Make the repo-local Python virtualenv's bin directory discoverable to every
#   runner that sources this file, so the documented single-command entry points
#   (e.g. `bash scripts/run_tests.sh --with-localstack`) find their Python-side
#   tooling -- pytest, coverage, and especially `awslocal` -- WITHOUT the caller
#   having to `source .venv/bin/activate` first.
# Parameters (environment, all optional):
#   CARDDEMO_VENV_DIR     - override the virtualenv location (default <repo>/.venv).
#   CARDDEMO_NO_VENV_PATH - set to 1 to DISABLE this provisioning entirely.
# Returns : none (exports an updated PATH when a venv bin dir is found).
# Errors  : none raised; a missing venv is silently ignored (system PATH is used).
#
# WHY (Root cause of F1): the AWS-integration E2E layer invokes `awslocal`, which
# on this runner is installed ONLY inside the repo virtualenv (.venv/bin/awslocal).
# When a user runs the DOCUMENTED command on a clean login shell (venv NOT
# activated), setup_localstack.sh could not find `awslocal`, reported
# "prerequisites missing", and SKIPPED the mandatory real-S3 dataset-staging tests
# -- silently turning a required AAP deliverable into a no-op while the suite still
# reported non-fatal. Prepending the venv bin dir here (the single file EVERY
# runner sources) restores the "no prior setup required" contract at its root
# cause instead of patching each runner.
# WHY (Idempotent + guarded, mirroring the COB_LIBRARY_PATH merge above):
#   - Alternatives Considered: (a) requiring users to `activate` the venv was
#     rejected because the entry points are documented as self-contained; (b)
#     hard-failing when awslocal is absent was rejected because the venv bin dir
#     is the deterministic, already-provisioned location -- adopting it is more
#     helpful than erroring. An explicit CARDDEMO_NO_VENV_PATH escape hatch is
#     provided for callers who deliberately manage their own interpreter (e.g. a
#     CI image whose tools are already on PATH).
#   - Assumption: a bin/ under the venv dir indicates a usable environment; we
#     PREPEND it so its tools win over any older system copies, and reuse the same
#     `case ":$PATH:"` guard as COB_LIBRARY_PATH so repeated sourcing in one
#     pipeline never double-prepends.
# ---------------------------------------------------------------------------
if [ "${CARDDEMO_NO_VENV_PATH:-0}" != "1" ]; then
    _carddemo_venv_dir="${CARDDEMO_VENV_DIR:-$CARDDEMO_REPO_ROOT/.venv}"
    _carddemo_venv_bin="$_carddemo_venv_dir/bin"
    if [ -d "$_carddemo_venv_bin" ]; then
        case ":$PATH:" in
            *":$_carddemo_venv_bin:"*) : ;;   # already on PATH -> no change
            *) export PATH="$_carddemo_venv_bin:$PATH" ;;
        esac
    fi
    unset _carddemo_venv_dir _carddemo_venv_bin
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

carddemo_normalize_junit() {
    # Purpose : write a DETERMINISTIC copy of a JUnit XML report in which the
    #           inherently run-varying metadata -- the wall-clock `timestamp`,
    #           the elapsed `time`, and the `hostname` -- is replaced with fixed
    #           canonical tokens, so two runs of the SAME test set over the SAME
    #           code produce a byte-identical (sha256-stable) published artifact.
    # Parameters:
    #   $1 (path)  - source JUnit report (the raw, human/CI report that KEEPS its
    #                real timestamps/durations). May be empty or absent.
    #   $2 (path)  - destination for the normalized copy. Parent dirs are created.
    # Returns : always 0. A missing/empty source is a silent no-op (the caller
    #           may fire this from an EXIT trap on paths where no report exists).
    # Errors  : none propagated; sed/mkdir failures degrade to a no-op so the
    #           function is safe to call unconditionally from a trap.
    # WHY a SEPARATE normalized artifact rather than normalizing in place
    # (Trade-off + Alternatives Considered): the raw reports/*.xml MUST retain
    # their real `timestamp`/`time` -- that truthfulness is itself a fixed QA
    # finding (the unit report used to hard-code time="0"/no-timestamp). Rewriting
    # the raw file back to constants would REGRESS that fix, so determinism is
    # delivered as an ADDITIONAL reports/normalized/*.xml sibling: humans and
    # coverage keep the real report, while a byte-stable copy is available for
    # signature/diff-based CI gates and reproducibility audits.
    # WHY only timestamp/time/hostname (Assumption): everything else pytest and
    # the unit runner emit is a deterministic function of the code and the fixed
    # test set -- testcase order is definition order under the serial invocation
    # these layer runners use (no -n), file/line/classname are repo-relative, and
    # skip/failure reasons are fixed strings. Those three attributes are the sole
    # per-run variables, so canonicalizing exactly them is necessary and
    # sufficient for run-over-run byte stability.
    local in="${1:-}" out="${2:-}"
    if [ -z "$in" ] || [ ! -f "$in" ] || [ -z "$out" ]; then
        return 0
    fi
    mkdir -p "$(dirname "$out")" 2>/dev/null || return 0
    # WHY substitute timestamp BEFORE time and match a leading space (Assumption):
    # the token " time=\"" cannot match inside " timestamp=\"" (after "time" comes
    # "stamp", not "=\""), so the two passes are independent and order-safe; the
    # leading space also prevents matching an attribute whose NAME merely ends in
    # "time" (there are none today, but it keeps the rule robust). `[^"]*` is safe
    # because JUnit escapes any literal quote inside a value as &quot;, so a value
    # can never contain a bare double-quote that would over-run the match.
    sed -E \
        -e 's/ timestamp="[^"]*"/ timestamp="1970-01-01T00:00:00.000000+00:00"/g' \
        -e 's/ time="[^"]*"/ time="0.000"/g' \
        -e 's/ hostname="[^"]*"/ hostname="carddemo-normalized"/g' \
        "$in" > "$out" 2>/dev/null || return 0
    return 0
}

_carddemo_gcov_block() {
    # Purpose : run gcov for ONE unit-under-test and echo the coverage figures for
    #           its transpiled-C file as four space-separated fields:
    #               "<line_pct> <line_total> <branch_pct> <branch_total>"
    #           (e.g. "59.70 1283 73.53 612"). Prints NOTHING if the target's
    #           `File '<name>.c'` block is absent from gcov's output.
    # Parameters:
    #   $1 (build_dir) - dir holding the instrumented .c/.gcno/.gcda; gcov is run
    #                    with CWD=build_dir so it correlates notes/data by the same
    #                    path the coverage build compiled into (see build script).
    #   $2 (name)      - UUT base name (e.g. CBTRN02C); selects the `File '<name>.c'`
    #                    block, i.e. the COBOL program's transpiled C -- NOT the
    #                    `.c.h`/`.c.l.h` header blocks gcov also prints.
    #   $3 (target)    - gcov argument: "<name>.c" for a `-x` main, or
    #                    "<name>.so-<name>.gcno" for a `-m` module (whose notes/data
    #                    carry the shared-object prefix).
    # Returns : 0 always; the OUTCOME is conveyed by the single line emitted:
    #             "<line_pct> <line_total> <branch_pct> <branch_total>"  (valid data)
    #             "STALE"                                                (orphaned .gcda)
    #             (nothing)                                              (no such block)
    # Errors  : none propagated.
    #
    # WHY detect "stamp mismatch" and emit STALE (Refactoring Rationale / honesty):
    # the `--with-tests` build compiles each UUT twice under the same base name --
    # once as the standalone program and again while building its GCBLUnit
    # <NAME>_test companion -- so the SECOND compile regenerates <NAME>.gcno AFTER an
    # earlier execution already wrote <NAME>.gcda. gcov then prints "stamp mismatch
    # with notes file" to STDERR and a MISLEADING 0.00% block to stdout. Programs
    # re-executed by the integration/e2e layers AFTER all compiles (CBTRN02C,
    # CBTRN03C, CBACT04C) match and report real coverage; the read/print provisioning
    # programs do not. Rather than publish a false 0%, we surface these as STALE so
    # the report distinguishes "instrumented-but-orphaned" from "genuinely 0%".
    #
    # WHY parse gcov stdout with mawk-safe awk (Alternatives Considered): gcovr --
    # the usual Cobertura emitter -- is ABSENT on this runner, and the system awk is
    # mawk (no gawk 3-arg match()), so the parser is written to POSIX awk. A single
    # quote is built via sprintf("%c",39) to avoid unquotable literals inside the
    # shell-single-quoted program. Lines/Branches are captured anywhere WITHIN the
    # block and flushed at END, so a program that happens to print "No branches"
    # (bp/bt stay empty -> normalized to 0) still yields a well-formed row.
    local build_dir="${1:-}" name="${2:-}" target="${3:-}"
    local _diag
    # Capture STDERR only (2>&1 >/dev/null) to detect the orphaned-data condition
    # BEFORE trusting the 0% block gcov still prints on a mismatch.
    _diag="$( cd "$build_dir" 2>/dev/null && gcov -b "$target" 2>&1 >/dev/null )"
    if printf '%s' "$_diag" | grep -qi 'stamp mismatch\|cannot open data file'; then
        echo "STALE"
        return 0
    fi
    ( cd "$build_dir" 2>/dev/null && gcov -b "$target" 2>/dev/null ) | awk -v f="$name.c" '
        BEGIN { q = sprintf("%c", 39); want = "File " q f q; inb = 0 }
        $0 == want { inb = 1; next }
        index($0, "File " q) == 1 && $0 != want { inb = 0 }
        inb && /^Lines executed:/ {
            s = $0; sub(/^Lines executed:/, "", s); split(s, a, "%");
            lp = a[1]; t = a[2]; sub(/^ of /, "", t); lt = t;
        }
        inb && /^Branches executed:/ {
            s = $0; sub(/^Branches executed:/, "", s); split(s, a, "%");
            bp = a[1]; t = a[2]; sub(/^ of /, "", t); bt = t;
        }
        END {
            if (lp != "")
                printf "%s %s %s %s\n", lp, (lt == "" ? "0" : lt),
                                        (bp == "" ? "0.00" : bp), (bt == "" ? "0" : bt);
        }
    '
    return 0
}

carddemo_cobol_coverage_report() {
    # Purpose : after an instrumented (--coverage) suite run, produce the COBOL
    #           line/branch coverage report from the gcov data GnuCOBOL emitted for
    #           each unit-under-test's transpiled C, and publish BOTH a
    #           machine-readable Cobertura-style reports/cobol-coverage.xml and a
    #           human-readable reports/cobol-coverage.txt. Closes finding
    #           F-COVERAGE-NO-COBOL-REPORT: `run_tests.sh --coverage` previously
    #           emitted ONLY Python (coverage.py) results and never a COBOL report,
    #           despite AAP 0.7.1 naming gcov-on-transpiled-C as THE COBOL method.
    # Parameters:
    #   $1 (build_dir)   - dir with the instrumented .c/.gcno/.gcda (defaults to
    #                      CARDDEMO_BUILD_DIR). MUST be the same dir the coverage
    #                      build compiled into (gcov correlates by compile path).
    #   $2 (reports_dir) - dir to write cobol-coverage.{xml,txt} (defaults to
    #                      CARDDEMO_REPORTS_DIR).
    # Returns (rubric code):
    #   0                 - report written AND both financial-critical programs
    #                       (CBTRN02C, CBACT04C) had coverage records.
    #   CARDDEMO_RC_WARN  - report written but a financial-critical program's gcov
    #                       data was absent, OR gcov itself is unavailable. This is
    #                       a coverage-TOOLING gap and therefore a WARN, never a
    #                       FAIL -- consistent with the run_tests coverage
    #                       philosophy and AAP 0.7.1 (the 90/85 figures are a
    #                       NON-contractual recommendation; the mandatory
    #                       business-rule branch coverage is enforced by the
    #                       asserting tests, not by this aggregate).
    # Errors : none propagated; every failure degrades to a WARN with a written note.
    #
    # WHY auto-detect main vs module (Refactoring Rationale / DRY): CBTRN02C is a
    # `-x` main (clean CBTRN02C.gcda; gcov -b CBTRN02C.c) whereas CBACT04C is a `-m`
    # module (CBACT04C.so-CBACT04C.{gcno,gcda}; gcov -b on that .gcno). Rather than
    # duplicate build_test_programs.sh's module list here, we key off the .gcda
    # naming that actually exists on disk, so the report stays correct if that list
    # ever changes.
    # WHY report the TRANSPILED-C denominator verbatim (Assumption/Trade-off): AAP
    # 0.7.1 specifies gcov on the GnuCOBOL-transpiled C. That denominator (~1.2k C
    # lines / ~600 branches per program) INCLUDES GnuCOBOL runtime boilerplate and
    # abend/error paths no black-box test can reach, so the percentages are an
    # upper-bounded recommendation, not an SLA. We print gcov's numbers unadjusted
    # and state the caveat in the .txt header rather than silently inflating them.
    local build_dir="${1:-${CARDDEMO_BUILD_DIR:-build}}"
    local reports_dir="${2:-${CARDDEMO_REPORTS_DIR:-reports}}"
    local txt="$reports_dir/cobol-coverage.txt"
    local xml="$reports_dir/cobol-coverage.xml"
    local warn_rc="${CARDDEMO_RC_WARN:-4}"
    mkdir -p "$reports_dir" 2>/dev/null || true

    if ! command -v gcov >/dev/null 2>&1; then
        echo "[run_tests] WARN: gcov not on PATH; COBOL coverage report not generated." >&2
        {
            echo "CardDemo COBOL coverage report NOT generated: 'gcov' unavailable on PATH."
            echo "AAP 0.7.1 specifies gcov-on-transpiled-C as the COBOL coverage method;"
            echo "install a gcov matching the gcc that GnuCOBOL uses, then re-run --coverage."
        } > "$txt" 2>/dev/null || true
        return "$warn_rc"
    fi

    # -- Enumerate UUTs that produced runtime data, keyed off .gcda naming. --
    local -a rows=()            # each element: "NAME line_pct line_tot branch_pct branch_tot"
    local gcda base name parsed
    local had_cbtrn02c=0 had_cbact04c=0
    shopt -s nullglob
    # main programs: <NAME>.gcda  (skip test harness/driver artifacts and modules)
    for gcda in "$build_dir"/*.gcda; do
        base="$(basename "$gcda" .gcda)"
        case "$base" in
            *_test|*_driver) continue ;;   # GCBLUnit test programs / CBACT04D-style drivers are not UUTs
            *.so-*) continue ;;            # module runtime data is handled in the next loop
        esac
        name="$base"
        [ -f "$build_dir/$name.c" ] || continue
        parsed="$(_carddemo_gcov_block "$build_dir" "$name" "$name.c")"
        [ -n "$parsed" ] || continue
        rows+=("$name $parsed")
    done
    # module programs: <NAME>.so-<NAME>.gcda
    for gcda in "$build_dir"/*.so-*.gcda; do
        base="$(basename "$gcda" .gcda)"    # e.g. CBACT04C.so-CBACT04C
        name="${base%%.so-*}"               # CBACT04C
        case "$name" in *_test|*_driver) continue ;; esac
        [ -f "$build_dir/$name.c" ] || continue
        parsed="$(_carddemo_gcov_block "$build_dir" "$name" "$base.gcno")"
        [ -n "$parsed" ] || continue
        rows+=("$name $parsed")
    done
    shopt -u nullglob

    # WHY sort (Determinism): glob order is filesystem-dependent; sorting by program
    # name makes cobol-coverage.{txt,xml} byte-stable run-over-run for CI diffing.
    local -a sorted=()
    local _line
    while IFS= read -r _line; do
        [ -n "$_line" ] && sorted+=("$_line")
    done < <(printf '%s\n' "${rows[@]}" | sort)

    # -- Aggregate covered/valid counts for the Cobertura top-level rates. --
    local sum_lc=0 sum_lt=0 sum_bc=0 sum_bt=0 n_valid=0 n_stale=0
    local r nm lp lt bp bt lc bc
    for r in "${sorted[@]}"; do
        # shellcheck disable=SC2086  # word-splitting the space-joined row is intentional
        # WHY ${N:-} default-expansion (Refactoring Rationale): a STALE row is only two
        # fields ("<name> STALE"), so positional $3/$4/$5 are UNSET for it. run_tests.sh
        # runs under `set -u`, where reading an unset positional is FATAL -- so every
        # field is read through ${N:-} and the STALE guard below consumes the row before
        # the numeric fields are ever used. (The isolated bring-up test sourced test_env.sh
        # into a plain shell WITHOUT -u -- test_env.sh deliberately never sets it -- which
        # is why this only surfaced under the full `set -u` run_tests.sh pipeline.)
        set -- $r; nm="${1:-}"; lp="${2:-}"; lt="${3:-}"; bp="${4:-}"; bt="${5:-}"
        if [ "$lp" = "STALE" ]; then
            # Orphaned .gcda (see _carddemo_gcov_block): excluded from the aggregate
            # and the machine-readable XML so neither is skewed by a false 0%; the
            # program is still LISTED (as stale) in the .txt for full transparency.
            n_stale=$((n_stale + 1))
            continue
        fi
        n_valid=$((n_valid + 1))
        [ "$nm" = "CBTRN02C" ] && had_cbtrn02c=1
        [ "$nm" = "CBACT04C" ] && had_cbact04c=1
        lc="$(awk -v p="$lp" -v t="$lt" 'BEGIN{printf "%d", (p/100.0*t)+0.5}')"
        bc="$(awk -v p="$bp" -v t="$bt" 'BEGIN{printf "%d", (p/100.0*t)+0.5}')"
        sum_lc=$((sum_lc + lc)); sum_lt=$((sum_lt + lt))
        sum_bc=$((sum_bc + bc)); sum_bt=$((sum_bt + bt))
    done

    local agg_lr agg_br
    agg_lr="$(awk -v c="$sum_lc" -v t="$sum_lt" 'BEGIN{printf "%.4f", (t>0)?c/t:0}')"
    agg_br="$(awk -v c="$sum_bc" -v t="$sum_bt" 'BEGIN{printf "%.4f", (t>0)?c/t:0}')"

    # -- Human-readable .txt report. --
    {
        echo "CardDemo COBOL coverage -- gcov on the GnuCOBOL-transpiled C (AAP 0.7.1 method)"
        echo "Generated by scripts/run_tests.sh --coverage"
        echo ""
        printf '  %-10s %8s %12s %9s %14s   %s\n' "PROGRAM" "LINE%" "(C lines)" "BRANCH%" "(C branches)" "ROLE"
        printf '  %-10s %8s %12s %9s %14s   %s\n' "-------" "-----" "---------" "-------" "------------" "----"
        for r in "${sorted[@]}"; do
            # shellcheck disable=SC2086
            set -- $r; nm="${1:-}"; lp="${2:-}"; lt="${3:-}"; bp="${4:-}"; bt="${5:-}"
            local role="batch"
            { [ "$nm" = "CBTRN02C" ] || [ "$nm" = "CBACT04C" ]; } && role="financial-critical"
            if [ "$lp" = "STALE" ]; then
                printf '  %-10s %8s %12s %9s %14s   %s\n' "$nm" "stale*" "-" "stale*" "-" "$role"
            else
                printf '  %-10s %8s %12s %9s %14s   %s\n' "$nm" "$lp" "$lt" "$bp" "$bt" "$role"
            fi
        done
        if [ "$n_stale" -gt 0 ]; then
            echo ""
            echo "* stale = the program was instrumented and executed, but its .gcda was ORPHANED"
            echo "  when the --with-tests build recompiled the program's transpiled C a second time"
            echo "  (once standalone, once while building its GCBLUnit <NAME>_test companion). gcov"
            echo "  reports a stamp mismatch, so a FALSE 0% is deliberately suppressed rather than"
            echo "  published. These are the read/print provisioning programs, which the layers do"
            echo "  not re-execute against the build-dir binary after the final compile; they are NOT"
            echo "  the AAP 0.7.1 financial-critical targets (CBTRN02C, CBACT04C), both of which report"
            echo "  valid coverage above."
        fi
        echo ""
        echo "Financial-critical recommendation (AAP 0.7.1, NON-contractual): >=90% line, >=85% branch."
        echo "NOTE: the denominator is the TRANSPILED C -- GnuCOBOL emits ~1.2k C lines / ~600"
        echo "branches per program, INCLUDING runtime boilerplate and abend/error paths that no"
        echo "black-box test can exercise. These percentages are therefore upper-bounded and are"
        echo "reported verbatim (never adjusted upward). The MANDATORY 100% business-rule branch"
        echo "coverage (reject 100-103, over-limit & expiry boundaries, DEFAULT-group fallback,"
        echo "TCATBAL create/update, interest formula) is enforced separately by the asserting"
        echo "unit/integration tests, independent of these aggregate figures."
    } > "$txt" 2>/dev/null || true

    # -- Machine-readable Cobertura-style .xml (ingestable by common CI plugins). --
    # WHY Cobertura schema (Alternatives Considered): it is the de-facto lingua franca
    # for line/branch coverage in CI (Jenkins, GitLab, Azure), so emitting it lets the
    # COBOL numbers ride the same ingestion path as the Python coverage.xml without a
    # bespoke reader. line-rate/branch-rate are fractions in [0,1] per that schema.
    {
        echo '<?xml version="1.0" encoding="UTF-8"?>'
        printf '<coverage line-rate="%s" branch-rate="%s" lines-valid="%s" lines-covered="%s" branches-valid="%s" branches-covered="%s" version="gcov" timestamp="0">\n' \
            "$agg_lr" "$agg_br" "$sum_lt" "$sum_lc" "$sum_bt" "$sum_bc"
        echo '  <sources><source>app/cbl</source></sources>'
        echo '  <packages>'
        printf '    <package name="cobol-batch" line-rate="%s" branch-rate="%s">\n' "$agg_lr" "$agg_br"
        echo '      <classes>'
        for r in "${sorted[@]}"; do
            # shellcheck disable=SC2086
            set -- $r; nm="${1:-}"; lp="${2:-}"; bp="${4:-}"
            # WHY skip STALE in the XML (Trade-off): the machine-readable artifact must
            # carry only MEASURED data, so a stale-orphaned program is omitted here
            # (it remains visible, flagged, in the human-readable .txt).
            [ "$lp" = "STALE" ] && continue
            local clr cbr fn
            clr="$(awk -v p="$lp" 'BEGIN{printf "%.4f", p/100.0}')"
            cbr="$(awk -v p="$bp" 'BEGIN{printf "%.4f", p/100.0}')"
            # WHY prefer .cbl then .CBL (Assumption): most programs ship lowercase,
            # but CBSTM03A/B use uppercase .CBL; the filename is a label for CI drill-
            # down, so we point at whichever source actually exists.
            if [ -f "$CARDDEMO_REPO_ROOT/app/cbl/$nm.cbl" ]; then fn="app/cbl/$nm.cbl"
            elif [ -f "$CARDDEMO_REPO_ROOT/app/cbl/$nm.CBL" ]; then fn="app/cbl/$nm.CBL"
            else fn="app/cbl/$nm.cbl"; fi
            printf '        <class name="%s" filename="%s" line-rate="%s" branch-rate="%s"><methods/><lines/></class>\n' \
                "$nm" "$fn" "$clr" "$cbr"
        done
        echo '      </classes>'
        echo '    </package>'
        echo '  </packages>'
        echo '</coverage>'
    } > "$xml" 2>/dev/null || true

    # -- Console summary + financial-critical presence gate. --
    if [ "$n_valid" -eq 0 ]; then
        echo "[run_tests] WARN: --coverage produced no VALID COBOL gcov records" \
             "(${n_stale} stale, 0 valid in $build_dir)." >&2
        return "$warn_rc"
    fi
    echo "[run_tests] cobol-coverage: wrote $xml (+ $(basename "$txt")) --" \
         "${n_valid} program(s) with valid data$([ "$n_stale" -gt 0 ] && echo ", ${n_stale} stale (see .txt)")"
    for r in "${sorted[@]}"; do
        # shellcheck disable=SC2086
        set -- $r; nm="${1:-}"; lp="${2:-}"; bp="${4:-}"
        if { [ "$nm" = "CBTRN02C" ] || [ "$nm" = "CBACT04C" ]; } && [ "$lp" != "STALE" ]; then
            echo "[run_tests]   $nm (financial-critical): line ${lp}% / branch ${bp}% of transpiled C"
        fi
    done
    if [ "$had_cbtrn02c" -ne 1 ] || [ "$had_cbact04c" -ne 1 ]; then
        echo "[run_tests] WARN: a financial-critical program had NO gcov data" \
             "(CBTRN02C=$had_cbtrn02c CBACT04C=$had_cbact04c); COBOL coverage is incomplete." >&2
        return "$warn_rc"
    fi
    return 0
}

carddemo_sweep_stray_coverage() {
    # Purpose : belt-and-braces cleanup of coverage byproducts that must never be
    #           left at the repository ROOT (finding GCOV-STRAY-ARTIFACTS). The
    #           compile-time root cause is already fixed in build_test_programs.sh
    #           (gcov .gcno/.gcda now land in CARDDEMO_BUILD_DIR); this sweep removes
    #           any residual gcov file plus the Python .coverage data file so a
    #           --coverage run leaves the working tree exactly as it found it.
    # Parameters: none (reads CARDDEMO_REPO_ROOT and CARDDEMO_COVERAGE).
    # Returns : always 0 -- cleanup is best-effort and must never alter the suite RC.
    # Errors  : none; individual rm/find failures are ignored.
    #
    # WHY guard on CARDDEMO_COVERAGE=1 (Trade-off / least-surprise): only a coverage
    # run produces these files, so a plain run does not delete a .coverage a user may
    # have left from an earlier manual session. WHY -maxdepth 1 (Safety): strays are
    # removed at the repo root ONLY -- never recursively and never inside /build --
    # so genuine in-build gcov artifacts and any unrelated subtree are untouched.
    [ "${CARDDEMO_COVERAGE:-0}" = "1" ] || return 0
    local root="${CARDDEMO_REPO_ROOT:-.}"
    find "$root" -maxdepth 1 -type f \
        \( -name '*.gcno' -o -name '*.gcda' -o -name '*.gcov' \) -delete 2>/dev/null || true
    rm -f "$root/.coverage" "$root"/.coverage.* 2>/dev/null || true
    return 0
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
    # WHY (F-ENV1 -- canonicalise BEFORE the containment test): a naive
    # string-prefix `case "$ws" in "$base"/*)` is DEFEATED by traversal -- a value
    # like "$base/run-../../evil" textually starts with "$base/" yet the kernel
    # resolves the '..' segments to a path OUTSIDE the base, so `rm -rf "$ws"`
    # would escape and delete evil/. We therefore resolve BOTH paths with
    # `readlink -m` (which collapses '..'/symlinks/'//' WITHOUT requiring the path
    # to exist), compare the RESOLVED forms, and remove the RESOLVED path -- so
    # containment is decided on the real target the OS would act on, not on its
    # spelling. This makes the safety claim above TRUE at runtime for every
    # traversal input and closes the sibling traversal-bearing
    # CARDDEMO_TEST_WORKSPACE variant, not just the sanitised-RUN_ID vector.
    # Assumption/fail-safe: if `readlink -m` (coreutils) were somehow unavailable
    # the substitution yields an empty canonical path that fails the '/'-anchored
    # match and is REFUSED -- it fails safe (never a wider delete).
    local ws="${CARDDEMO_TEST_WORKSPACE:-}" base="${CARDDEMO_WS_BASE:-}"
    if [ -z "$ws" ] || [ -z "$base" ]; then
        return 0
    fi
    local ws_c base_c
    ws_c="$(readlink -m -- "$ws" 2>/dev/null)"
    base_c="$(readlink -m -- "$base" 2>/dev/null)"
    if [ -z "$ws_c" ] || [ -z "$base_c" ]; then
        echo "[carddemo] not cleaning workspace: unresolvable path '$ws'" >&2
        return 0
    fi
    # WHY: the trailing '/' on the subject plus the '/'-anchored pattern rejects
    # both (a) ws_c == base_c exactly (never nuke the base itself, only a child)
    # and (b) a shared-prefix sibling base (e.g. .../carddemo-test-0-evil) that
    # would spoof a bare prefix match.
    case "$ws_c/" in
        "$base_c"/*) rm -rf "$ws_c" 2>/dev/null || true ;;
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
# Python dependency supply-chain audit gate (finding F-SUPPLY-NO-AUDIT-GATE).
# ---------------------------------------------------------------------------
carddemo_audit_python_deps() {
    # Purpose : audit the pinned Python test dependencies for known published
    #           advisories using pip-audit, subtract any operator-adjudicated
    #           (allowlisted) advisory IDs, write a machine-readable JSON report,
    #           and return a condition code under the suite's RC rubric. This is
    #           the shared engine behind both `run_tests.sh --audit` (local,
    #           opt-in) and the CI "Audit Python dependencies" gate step.
    # Parameters:
    #   $1 (path, optional) - requirements file to audit
    #                         (default: $CARDDEMO_REPO_ROOT/tests/requirements-test.txt).
    #   $2 (path, optional) - adjudication allowlist JSON
    #                         (default: $CARDDEMO_REPO_ROOT/tests/audit-allowlist.json).
    #   $3 (path, optional) - machine-readable JSON report to write
    #                         (default: $CARDDEMO_REPORTS_DIR/audit-python.json).
    # Returns (echoes nothing; sets exit status):
    #   CARDDEMO_RC_PASS (0) - audit ran and NO unadjudicated advisory remains.
    #   CARDDEMO_RC_WARN (4) - audit could NOT be completed (pip-audit or jq
    #                          absent, advisory DB unreachable/offline, or
    #                          unparseable output). A tooling/connectivity gap is
    #                          deliberately non-fatal so a transient outage never
    #                          reddens the build on a condition no code change fixes.
    #   CARDDEMO_RC_FAIL (8) - audit ran and >=1 advisory is present that is NOT
    #                          adjudicated in the allowlist. This is the real gate.
    # Errors  : none propagated; every fallible command's status is captured, so
    #           the function is safe to call from a `set -uo pipefail` runner.
    #
    # WHY (severity policy — Alternatives Considered): pip-audit's OSV/PyPI source
    # does not emit a uniform, reliable CVSS severity for every advisory, so a
    # numeric "fail above severity X" threshold would be inconsistent and gameable.
    # Instead the policy is FAIL-CLOSED: ANY advisory fails the gate UNLESS it has
    # been explicitly adjudicated (with a written reason + owner) in the allowlist.
    # That makes every suppression an auditable, reviewable decision rather than an
    # opaque numeric cutoff — the correct posture for a financial-enterprise suite.
    #
    # WHY npm is NOT audited here (Assumption): after F-SUPPLY-COBOLGET-VULNS
    # removed the sole npm install (cobolget) from CI, the repository declares no
    # package.json / node_modules, so there is no npm dependency surface to audit.
    local req_file="${1:-$CARDDEMO_REPO_ROOT/tests/requirements-test.txt}"
    local allowlist="${2:-$CARDDEMO_REPO_ROOT/tests/audit-allowlist.json}"
    local report_out="${3:-$CARDDEMO_REPORTS_DIR/audit-python.json}"
    local pass_rc="${CARDDEMO_RC_PASS:-0}"
    local warn_rc="${CARDDEMO_RC_WARN:-4}"
    local fail_rc="${CARDDEMO_RC_FAIL:-8}"

    mkdir -p "$(dirname "$report_out")" 2>/dev/null || true
    local ts; ts="$(date -u +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || echo unknown)"

    # WHY (helper): emit the machine-readable report in ONE place so every exit
    # path produces an identically-shaped artifact (status/rc/findings), which the
    # CI upload step and any downstream tooling can parse uniformly.
    _carddemo_audit_write_report() {
        # $1 status, $2 rc, $3 adjudicated-json, $4 findings-json
        local _st="$1" _rc="$2" _adj="${3:-[]}" _find="${4:-[]}"
        if command -v jq >/dev/null 2>&1; then
            jq -n \
               --arg tool "pip-audit" --arg status "$_st" --arg req "$req_file" \
               --arg allow "$allowlist" --arg ts "$ts" --argjson rc "$_rc" \
               --argjson adjudicated "$_adj" --argjson findings "$_find" \
               '{tool:$tool, status:$status, requirements:$req, allowlist:$allow,
                 generated_at_utc:$ts, rc:$rc, adjudicated:$adjudicated,
                 unadjudicated_findings:$findings}' \
               > "$report_out" 2>/dev/null || true
        else
            # jq unavailable: still leave a minimal, valid JSON breadcrumb.
            printf '{"tool":"pip-audit","status":"%s","rc":%s,"requirements":"%s","generated_at_utc":"%s"}\n' \
                   "$_st" "$_rc" "$req_file" "$ts" > "$report_out" 2>/dev/null || true
        fi
    }

    if [ ! -f "$req_file" ]; then
        echo "[carddemo-audit] WARN: requirements file not found: $req_file" >&2
        _carddemo_audit_write_report "warn-no-requirements" "$warn_rc" "[]" "[]"
        return "$warn_rc"
    fi

    # Resolve the pip-audit invocation (direct binary or module form).
    local -a audit_cmd=()
    if command -v pip-audit >/dev/null 2>&1; then
        audit_cmd=(pip-audit)
    elif command -v python3 >/dev/null 2>&1 && python3 -c 'import pip_audit' >/dev/null 2>&1; then
        audit_cmd=(python3 -m pip_audit)
    fi

    # Obtain the pip-audit JSON. WHY (Trade-off — testability seam):
    # CARDDEMO_AUDIT_JSON_OVERRIDE lets a test feed CANNED pip-audit output so the
    # adjudication/clean/findings branches can be verified deterministically and
    # OFFLINE (the live advisory DB requires network). It is an explicit env opt-in
    # that never triggers in normal runs, so it cannot mask a real live audit.
    local raw_json="" audit_ec=0 tmp_out
    if [ -n "${CARDDEMO_AUDIT_JSON_OVERRIDE:-}" ] && [ -f "${CARDDEMO_AUDIT_JSON_OVERRIDE}" ]; then
        raw_json="$(cat "${CARDDEMO_AUDIT_JSON_OVERRIDE}" 2>/dev/null || echo '')"
        audit_ec=1   # pretend "vulns found" so the parse path decides pass/fail
    elif [ "${#audit_cmd[@]}" -eq 0 ]; then
        echo "[carddemo-audit] WARN: pip-audit not available; audit skipped (WARN)" >&2
        _carddemo_audit_write_report "warn-tool-absent" "$warn_rc" "[]" "[]"
        return "$warn_rc"
    else
        tmp_out="$(mktemp 2>/dev/null || echo /tmp/carddemo-audit.$$)"
        # WHY --no-deps: the requirements file is a COMPLETE, hash-locked closure,
        # so we audit exactly those pins without resolving/installing a dependency
        # tree (deterministic + no build step). WHY the && / || dance: capture the
        # exit status WITHOUT letting a non-zero (exit 1 == "vulns found") abort a
        # `set -e` caller.
        "${audit_cmd[@]}" --requirement "$req_file" --no-deps --format json \
            > "$tmp_out" 2>/dev/null && audit_ec=0 || audit_ec=$?
        raw_json="$(cat "$tmp_out" 2>/dev/null || echo '')"
        rm -f "$tmp_out" 2>/dev/null || true
        # pip-audit exit codes: 0 = no vulns, 1 = vulns found, anything else = a
        # real error (most commonly, here, the advisory DB being unreachable
        # offline). Only 0/1 mean the audit actually completed.
        if [ "$audit_ec" -ne 0 ] && [ "$audit_ec" -ne 1 ]; then
            echo "[carddemo-audit] WARN: pip-audit could not complete (ec=$audit_ec; likely offline advisory DB); audit inconclusive (WARN)" >&2
            _carddemo_audit_write_report "warn-unreachable" "$warn_rc" "[]" "[]"
            return "$warn_rc"
        fi
    fi

    # From here we have (or claim to have) pip-audit JSON; adjudication needs jq.
    if ! command -v jq >/dev/null 2>&1; then
        echo "[carddemo-audit] WARN: jq not available; cannot adjudicate audit output (WARN)" >&2
        _carddemo_audit_write_report "warn-no-jq" "$warn_rc" "[]" "[]"
        return "$warn_rc"
    fi
    if ! printf '%s' "$raw_json" | jq -e . >/dev/null 2>&1; then
        echo "[carddemo-audit] WARN: pip-audit produced no parseable JSON; audit inconclusive (WARN)" >&2
        _carddemo_audit_write_report "warn-bad-json" "$warn_rc" "[]" "[]"
        return "$warn_rc"
    fi

    # Collect adjudicated (allowlisted) advisory IDs, if any.
    local allow_ids="[]"
    if [ -f "$allowlist" ] && jq -e . "$allowlist" >/dev/null 2>&1; then
        allow_ids="$(jq -c '[.adjudications[]?.id] | map(select(. != null))' "$allowlist" 2>/dev/null || echo '[]')"
    fi

    # WHY the `if ... has("dependencies")` normalization: pip-audit's JSON is an
    # object `{dependencies:[...]}` in current releases but was a bare array in
    # older ones; normalizing here keeps the gate working across versions.
    local findings adjudicated n_find
    findings="$(printf '%s' "$raw_json" | jq -c --argjson allow "$allow_ids" '
        ( if type=="object" and has("dependencies") then .dependencies else . end ) as $deps
        | [ $deps[]? as $d | ($d.vulns[]?)
            | select( (.id | IN($allow[])) | not )
            | {package:$d.name, version:$d.version, id:.id,
               fix_versions:(.fix_versions // []), aliases:(.aliases // []),
               description:(.description // "")} ]' 2>/dev/null || echo '[]')"
    adjudicated="$(printf '%s' "$raw_json" | jq -c --argjson allow "$allow_ids" '
        ( if type=="object" and has("dependencies") then .dependencies else . end ) as $deps
        | [ $deps[]? as $d | ($d.vulns[]?)
            | select( .id | IN($allow[]) )
            | {package:$d.name, version:$d.version, id:.id} ]' 2>/dev/null || echo '[]')"
    n_find="$(printf '%s' "$findings" | jq 'length' 2>/dev/null || echo 0)"

    if [ "${n_find:-0}" -gt 0 ]; then
        echo "[carddemo-audit] FAIL: $n_find unadjudicated advisory finding(s); see $report_out" >&2
        _carddemo_audit_write_report "findings" "$fail_rc" "$adjudicated" "$findings"
        return "$fail_rc"
    fi

    echo "[carddemo-audit] PASS: no unadjudicated advisories in $req_file"
    _carddemo_audit_write_report "clean" "$pass_rc" "$adjudicated" "$findings"
    return "$pass_rc"
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
