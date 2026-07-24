#!/bin/bash
# =============================================================================
# tests/helpers/load_indexed.sh
# -----------------------------------------------------------------------------
# Purpose:
#   Thin command-line front door for the flat-fixture -> GnuCOBOL native indexed
#   (ISAM) loader. It is the automated analog of the mainframe IDCAMS
#   DELETE -> DEFINE -> REPRO file-load pattern (see app/jcl/ACCTFILE.jcl) and
#   exists so bash runner scripts and manual / interactive use can load a fixture
#   into an indexed file in EXACTLY the same way the Python test harness does.
#
#   All real work (fixture normalisation, generation + compilation of the COBOL
#   loader, and writing the native indexed file INCLUDING any alternate-key
#   sidecars) is delegated to the single source of truth,
#   `python3 -m tests.helpers.vsam_loader`. This wrapper only validates its
#   arguments and forwards them (options AND positionals); it deliberately
#   implements NONE of the loading logic itself.
#
# Usage:
#   bash tests/helpers/load_indexed.sh <flat_file> <indexed_file> <reclen> <key_length> [key_offset]
#   bash tests/helpers/load_indexed.sh --layout NAME [--alt-key OFF:LEN[:dup]] <flat_file> <indexed_file>
#   bash tests/helpers/load_indexed.sh -h | --help
#
# Parameters (positional):
#   <flat_file>     (string, required) Path to the flat fixed-width fixture to
#                   read (input). Each line becomes one fixed-length record.
#   <indexed_file>  (string, required) Destination path for the GnuCOBOL native
#                   indexed file to create (output). Any pre-existing target is
#                   removed first by the loader (the DELETE analog).
#   <reclen>        (positive integer, required UNLESS --layout is given) Fixed
#                   record length in characters, matching the copybook layout.
#   <key_length>    (positive integer, required UNLESS --layout is given) Length
#                   in characters of the leading primary RECORD KEY.
#   [key_offset]    (non-negative integer, optional; default 0) Key offset within
#                   the record. Only 0 is supported by the underlying loader.
#
# Options (forwarded verbatim to the module, which validates them):
#   --layout NAME            Derive reclen/key_length AND the layout's alternate
#                            keys from a record_codec layout name (e.g. XREF,
#                            ACCOUNT). Mutually exclusive with the positional
#                            reclen/key_length. This is how CBACT04C's XREF file
#                            (which is read by its account-id ALTERNATE key) is
#                            staged so its `.1` sidecar is produced (QA fix C1).
#   --alt-key OFF:LEN[:dup]  Add an ALTERNATE RECORD KEY at byte OFF, LEN wide;
#                            optional dup=0 disables WITH DUPLICATES. Repeatable;
#                            merged with any keys implied by --layout.
#   --std STD                cobc dialect (default: ibm-strict).
#   --cobc COBC              cobc command/path used to build the loader.
#   --cache-dir DIR          directory for the cached loader binary.
#   --compile-timeout SECONDS / --run-timeout SECONDS  loader wall-clock ceilings.
#   -h, --help               Print this usage banner and exit 0.
#
# Return / Exit codes (CardDemo RC rubric, sourced from scripts/test_env.sh):
#   0  (CARDDEMO_RC_PASS)  the fixture was loaded into the indexed file.
#   2  (CARDDEMO_RC_USAGE) operator/usage error: wrong argument count, or a
#                          non-numeric / non-positive reclen|key_length, or a
#                          non-numeric key_offset. (`-h`/`--help` still exits 0.)
#   8  (CARDDEMO_RC_FAIL)  python3 is not on PATH, OR the delegated loader failed
#                          for any reason (bad input, compile error, or run
#                          error). Every nonzero loader exit collapses to 8.
#
# Errors / Exceptions:
#   Usage problems and load failures are reported with a one-line
#   "[load_indexed]" diagnostic on STDERR before the corresponding non-zero exit
#   listed above.
#
# WHY (design rationale):
#   - Trade-off (intentionally "dumb" wrapper): ALL validation of record
#     layout/format lives in the Python module, so this script performs only the
#     cheap operator-facing checks (arg count / numeric-ness) that let a mistyped
#     command fail as a usage error (2) rather than being misreported as a real
#     load failure (8). Everything else is forwarded verbatim so the bash path
#     and the Python path load fixtures identically (same normalisation, same
#     generated loader, same GnuCOBOL indexed format).
#   - Alternatives Considered: re-deriving the DELETE/DEFINE/REPRO steps in bash
#     was rejected outright -- only GnuCOBOL can write its own native indexed
#     format, so duplicating it would be brittle and could diverge from what the
#     programs under test can actually read. Delegating to vsam_loader.py keeps a
#     single source of truth for both entry points.
#   - Assumption: this file lives at <repo>/tests/helpers/, so the repo root is
#     two directories up; scripts/test_env.sh is the one place the RC rubric and
#     CARDDEMO_REPO_ROOT are defined, so we SOURCE it rather than re-declaring any
#     constant here.
#   - Trade-off (stdout hygiene): the Python module prints the resulting indexed
#     path to stdout so a caller can capture it; this wrapper therefore keeps its
#     own human-readable confirmation on STDERR, leaving stdout a byte-for-byte
#     pass-through of the module's output (a true 1:1 CLI surface).
#   - Note: callers invoke this via `bash <path>` and must not rely on the
#     executable bit, because the file-creation tooling may not set +x.
# =============================================================================

# WHY (Trade-off): this script is always EXECUTED (never sourced -- contrast
# scripts/test_env.sh, which is sourced), so fail-fast is correct and desirable:
# an unset variable or an unexpected command failure must abort immediately
# rather than silently mis-loading a fixture.
set -euo pipefail

# ---------------------------------------------------------------------------
# Locate the repository root and load the shared environment.
# WHY (Assumption): CARDDEMO_REPO_ROOT and the RC-rubric constants are defined in
# scripts/test_env.sh (the single source of truth). We cannot use that env var to
# find the file that DEFINES it, so we first resolve the path locally from
# BASH_SOURCE (this file is at <root>/tests/helpers/, hence ../.. == root), then
# source test_env.sh and rely on its exported variables from that point on.
# ---------------------------------------------------------------------------
_li_script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
_li_repo_root="$(cd "$_li_script_dir/../.." && pwd)"
# shellcheck source=scripts/test_env.sh
source "$_li_repo_root/scripts/test_env.sh"

_li_usage() {
    # Purpose : print the exact CLI usage banner for this wrapper.
    # Parameters: none.
    # Returns : always 0 (the caller decides the process exit code).
    # Errors  : none.
    # WHY (Trade-off): emitted via a single here-doc so the banner text has one
    # definition, reused by both the --help path (printed to stdout) and the
    # error paths (redirected to stderr by the caller), preventing drift between
    # the two.
    cat <<'USAGE'
Usage: load_indexed.sh <flat_file> <indexed_file> <reclen> <key_length> [key_offset]
       load_indexed.sh --layout NAME [--alt-key OFF:LEN[:dup]] <flat_file> <indexed_file>
       load_indexed.sh -h | --help

  <flat_file>     path to the flat fixed-width fixture to load (input)
  <indexed_file>  destination path for the GnuCOBOL indexed file (output)
  <reclen>        fixed record length in characters (positive integer; omit with --layout)
  <key_length>    leading primary-key length in characters (positive integer; omit with --layout)
  [key_offset]    optional key offset within the record; only 0 is supported
                  (non-negative integer, default 0)

Options (forwarded to the module):
  --layout NAME            derive reclen/key_length + alternate keys from a
                           record_codec layout (e.g. XREF); mutually exclusive
                           with positional reclen/key_length
  --alt-key OFF:LEN[:dup]  add an ALTERNATE RECORD KEY (repeatable; merged with --layout)
  --std STD                cobc dialect (default: ibm-strict)
  --cobc COBC              cobc command/path
  --cache-dir DIR          cached-loader directory
  --compile-timeout SEC    loader compile ceiling
  --run-timeout SEC        loader run ceiling
  -h, --help               show this help and exit 0

Delegates all loading to: python3 -m tests.helpers.vsam_loader
USAGE
}

_li_is_nonneg_int() {
    # Purpose : test whether a token is a base-10 non-negative integer (all
    #           digits, non-empty), using pure string matching.
    # Parameters:
    #   $1 (string) - the token to test.
    # Returns : 0 if the token is a non-negative integer, 1 otherwise.
    # Errors  : none.
    # WHY (Alternatives Considered / Trade-off): a glob character-class test
    # (*[!0-9]*) is used instead of an arithmetic comparison such as
    # `[ "$1" -ge 0 ]`, because arithmetic on a pathologically long or a
    # non-numeric token can itself error out under `set -e`, whereas string
    # matching never does. Positivity (> 0) is enforced separately by the caller
    # only for the fields where a zero value is invalid (reclen, key_length).
    case "${1:-}" in
        ''|*[!0-9]*) return 1 ;;
        *)           return 0 ;;
    esac
}

# ---------------------------------------------------------------------------
# Separate options (forwarded verbatim to the module) from positionals.
# WHY (QA fix C1 / Refactoring Rationale): the previous version was positional-only
# and could not stage an alternate-keyed file (CBACT04C reads XREF by its account-id
# ALTERNATE key, which needs the `.1` sidecar). The underlying module ALREADY accepts
# --layout / --alt-key (and --std/--cobc/...); this wrapper's sole remaining job is to
# forward them, so we parse a leading run of recognised options into `_li_opts` and
# collect the rest as positionals. Validation of the options themselves (layout name,
# alt-key format, mutual exclusion) stays in the module -- the single source of truth --
# rather than being duplicated here. `-h/--help` is handled inside the loop so it works
# regardless of position, matching operators' expectations.
# ---------------------------------------------------------------------------
_li_opts=()          # option tokens forwarded to the module (e.g. --layout XREF)
_li_pos=()           # positional tokens: flat, indexed, [reclen], [key_length], [key_offset]
_li_layout_given=0

while [ "$#" -gt 0 ]; do
    case "$1" in
        -h|--help)
            _li_usage
            exit 0
            ;;
        --layout|--alt-key|--std|--cobc|--cache-dir|--compile-timeout|--run-timeout)
            # Two-token option (`--opt value`). WHY: value-taking options must consume
            # their argument so it is never misread as a positional (flat/indexed).
            if [ "$#" -lt 2 ]; then
                echo "[load_indexed] ERROR: option '$1' requires a value." >&2
                _li_usage >&2
                exit "${CARDDEMO_RC_USAGE}"
            fi
            [ "$1" = "--layout" ] && _li_layout_given=1
            _li_opts+=("$1" "$2")
            shift 2
            ;;
        --layout=*|--alt-key=*|--std=*|--cobc=*|--cache-dir=*|--compile-timeout=*|--run-timeout=*)
            # Single-token option (`--opt=value`).
            case "$1" in --layout=*) _li_layout_given=1 ;; esac
            _li_opts+=("$1")
            shift
            ;;
        --)
            # Explicit end-of-options: everything after is positional.
            shift
            while [ "$#" -gt 0 ]; do _li_pos+=("$1"); shift; done
            ;;
        -*)
            echo "[load_indexed] ERROR: unknown option '$1'." >&2
            _li_usage >&2
            exit "${CARDDEMO_RC_USAGE}"
            ;;
        *)
            _li_pos+=("$1")
            shift
            ;;
    esac
done

# ---------------------------------------------------------------------------
# Validate the positional geometry, distinctly from a real load failure (RC=2 vs 8).
# WHY: <flat_file> and <indexed_file> are always required. Without --layout we also
# require a POSITIVE reclen + key_length (a zero-length record or key is meaningless)
# and accept an optional NON-NEGATIVE key_offset. With --layout the geometry comes from
# the codec, so numeric positionals are rejected here to give a crisp operator error
# instead of relying on the module's argparse (whose nonzero exit this wrapper would
# otherwise collapse into the generic FAIL code).
# ---------------------------------------------------------------------------
_li_npos=${#_li_pos[@]}
if [ "$_li_npos" -lt 2 ]; then
    echo "[load_indexed] ERROR: <flat_file> and <indexed_file> are required." >&2
    _li_usage >&2
    exit "${CARDDEMO_RC_USAGE}"
fi
_li_flat="${_li_pos[0]}"
_li_indexed="${_li_pos[1]}"

if [ "$_li_layout_given" -eq 1 ]; then
    # --layout supplies reclen/key_length/alternate-keys, so no numeric positionals.
    if [ "$_li_npos" -gt 2 ]; then
        echo "[load_indexed] ERROR: --layout cannot be combined with positional" \
             "reclen/key_length/key_offset." >&2
        _li_usage >&2
        exit "${CARDDEMO_RC_USAGE}"
    fi
else
    if [ "$_li_npos" -lt 4 ] || [ "$_li_npos" -gt 5 ]; then
        echo "[load_indexed] ERROR: expected <flat> <indexed> <reclen> <key_length>" \
             "[key_offset] (or use --layout), received $_li_npos positional(s)." >&2
        _li_usage >&2
        exit "${CARDDEMO_RC_USAGE}"
    fi
    _li_reclen="${_li_pos[2]}"
    _li_key_length="${_li_pos[3]}"
    _li_key_offset="${_li_pos[4]:-}"
    # The `2>/dev/null` guards the arithmetic test against a non-numeric token that
    # short-circuiting has not already rejected.
    if ! _li_is_nonneg_int "$_li_reclen" || ! [ "$_li_reclen" -gt 0 ] 2>/dev/null; then
        echo "[load_indexed] ERROR: <reclen> must be a positive integer, got '$_li_reclen'." >&2
        _li_usage >&2
        exit "${CARDDEMO_RC_USAGE}"
    fi
    if ! _li_is_nonneg_int "$_li_key_length" || ! [ "$_li_key_length" -gt 0 ] 2>/dev/null; then
        echo "[load_indexed] ERROR: <key_length> must be a positive integer, got '$_li_key_length'." >&2
        _li_usage >&2
        exit "${CARDDEMO_RC_USAGE}"
    fi
    if [ -n "$_li_key_offset" ] && ! _li_is_nonneg_int "$_li_key_offset"; then
        echo "[load_indexed] ERROR: [key_offset] must be a non-negative integer, got '$_li_key_offset'." >&2
        _li_usage >&2
        exit "${CARDDEMO_RC_USAGE}"
    fi
fi

# ---------------------------------------------------------------------------
# Ensure the Python interpreter is available.
# WHY (Trade-off): without python3 the delegated loader cannot run at all, so a
# missing interpreter is a hard FAIL (8), not a soft skip. carddemo_require_cmd
# (from test_env.sh) emits the diagnostic; we only translate its 0/1 result into
# the rubric code, reusing the shared helper rather than re-checking PATH here.
# ---------------------------------------------------------------------------
if ! carddemo_require_cmd python3 "Install Python 3.9+ to run the fixture loader."; then
    exit "${CARDDEMO_RC_FAIL}"
fi

# ---------------------------------------------------------------------------
# Make the repo root importable so `python3 -m tests.helpers.vsam_loader`
# resolves the `tests` PEP 420 namespace package (there is no __init__.py under
# tests/). Prepending (rather than overwriting) preserves any PYTHONPATH the
# caller already set.
# ---------------------------------------------------------------------------
export PYTHONPATH="$CARDDEMO_REPO_ROOT${PYTHONPATH:+:$PYTHONPATH}"

# ---------------------------------------------------------------------------
# Delegate to the single source of truth and translate its exit status.
# WHY (Trade-off): the module call is placed in the `if` condition so `set -e`
# does NOT abort on a nonzero loader exit -- that lets us capture the code and map
# EVERY failure mode (VsamLoadError=1, geometry/usage=2, etc.) onto the single
# rubric FAIL code (8), keeping this wrapper's contract simple: it either loaded
# the fixture (0) or it did not (8). Forwarded options (`_li_opts`, e.g.
# --layout/--alt-key) precede the positionals (`_li_pos`) so argparse binds them
# cleanly; both are passed via "${arr[@]}" which is empty-array-safe under `set -u`
# on this image's bash 5.x. The module's own stdout (the indexed path) is left
# untouched for callers that capture it.
# ---------------------------------------------------------------------------
if python3 -m tests.helpers.vsam_loader "${_li_opts[@]}" "${_li_pos[@]}"; then
    # Confirmation goes to stderr so stdout stays a 1:1 pass-through of the path.
    # WHY: describe the geometry source (explicit numbers or --layout) so the log line
    # is meaningful in both invocation modes.
    if [ "$_li_layout_given" -eq 1 ]; then
        echo "[load_indexed] OK: loaded '$_li_flat' -> '$_li_indexed' (via --layout)." >&2
    else
        echo "[load_indexed] OK: loaded '$_li_flat' -> '$_li_indexed'" \
             "(reclen=$_li_reclen, key_length=$_li_key_length)." >&2
    fi
    exit "${CARDDEMO_RC_PASS}"
else
    _li_rc=$?
    echo "[load_indexed] ERROR: vsam_loader failed (loader rc=$_li_rc) while loading" \
         "'$_li_flat' -> '$_li_indexed'." >&2
    exit "${CARDDEMO_RC_FAIL}"
fi
