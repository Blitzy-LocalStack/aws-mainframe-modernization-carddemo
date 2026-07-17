#!/bin/bash
# =============================================================================
# scripts/setup_localstack.sh
# -----------------------------------------------------------------------------
# Purpose:
#   Bring up LocalStack headless (Docker-less) using the Ephemeral Instances
#   path and seed the S3 resources needed by the dataset-staging tests. Because
#   the runner has NO Docker, the `localstack ephemeral create` flow is used
#   with an agent token; the resolved gateway URL is exported as
#   AWS_ENDPOINT_URL and also written to <workspace>/localstack.env so the
#   pytest E2E layer can pick it up.
#
# Usage:
#   scripts/setup_localstack.sh        # executed: brings up LocalStack, exits rc
#   scripts/setup_localstack.sh -h     # executed: print usage, exit 0 (NO bring-
#                                      #           up, NO token request, no side
#                                      #           effects)
#   source scripts/setup_localstack.sh # sourced  : also exports AWS_ENDPOINT_URL.
#                                      #           CLI args are NOT parsed when
#                                      #           sourced; the caller owns them.
#
# Parameters (environment inputs):
#   LOCALSTACK_AUTH_TOKEN      - if already set (CI secret), the token request is
#                                skipped.
#   LOCALSTACK_AGENT_TOKEN_URL - override for the agent-token endpoint. It is
#                                ALLOWLIST-CHECKED (see MA-05 below): a value
#                                that does not resolve to a documented LocalStack
#                                host is REJECTED (fail closed), never contacted.
#   CARDDEMO_LS_TIMEOUT        - readiness timeout in seconds (default 120). MUST
#                                be a positive integer; a non-numeric value is
#                                rejected and the default is used (finding MA-06).
#   CARDDEMO_REQUIRE_LOCALSTACK- when "1", a genuinely-absent AWS layer (missing
#                                tools, no token, instance not ready, manifest
#                                absent) is escalated from WARN to FAIL. Default
#                                "0": those reachability gaps stay soft because
#                                the AWS layer is optional on a bare runner.
#   (plus CARDDEMO_* paths and RC constants from scripts/test_env.sh)
#
# Return / Exit codes (CardDemo RC rubric):
#   0  LocalStack is up on an ALLOWLISTED endpoint and S3 was seeded AND verified
#      from the manifest.
#   2  usage error (unknown CLI argument; executed mode only) - DISTINCT from the
#      rubric so an invocation typo is never mistaken for a soft-skip. A -h/--help
#      flag prints usage and exits 0 with NO bring-up and NO token request.
#   4  soft skip/warn: an OPTIONAL prerequisite is missing (tools, token,
#      instance not ready, manifest absent) and CARDDEMO_REQUIRE_LOCALSTACK != 1.
#   8  HARD FAIL, used for two distinct classes (finding MA-05 -- fail closed):
#        (a) SECURITY: the token URL or the resolved gateway endpoint is NOT on
#            the LocalStack/loopback allowlist (e.g. an *.amazonaws.com or other
#            public host). This ALWAYS fails, regardless of REQUIRE, and the
#            offending endpoint is never contacted.
#        (b) INTEGRITY: after a healthy allowlisted endpoint is reached, a bucket
#            or object could not be created OR could not be VERIFIED to exist.
#            Seed failures are no longer swallowed.
#      Also used for any reachability gap when CARDDEMO_REQUIRE_LOCALSTACK=1.
#
# Errors / Exceptions:
#   Security/integrity failures return 8 (fail closed). Optional-layer gaps
#   return 4 by default. An EXIT/INT/TERM trap (executed mode only) removes the
#   scratch temp dir and, unless bring-up SUCCEEDED, deletes any ephemeral
#   instance that was created so an interrupted or failed run leaks nothing.
#
# WHY (design rationale):
#   - Assumption: no Docker is available on the runner, so the ephemeral
#     (cloud-pod) path is used instead of the container image.
#   - Alternatives Considered: a docker-compose LocalStack was rejected because
#     the runner cannot run Docker; the headless ephemeral CLI needs none.
#   - Trade-off (revised for MA-05): REACHING LocalStack stays optional (a bare
#     runner with no token still passes the core COBOL suite with a WARN), but
#     SECURITY and, once reached, seeding INTEGRITY are enforced as hard
#     failures. Previously every failure -- including routing to a non-emulator
#     host and swallowed seed errors -- degraded to WARN, which is exactly the
#     fail-open behaviour the finding calls out.
#   - Refactoring rationale: readiness is confirmed by polling the health
#     endpoint in a bounded loop (exits the instant it is healthy), NOT by a
#     fixed total `sleep` like the legacy demo scripts. Every network/CLI call
#     is additionally wrapped in connect/max-time (curl) or `timeout` (CLI) so a
#     hung remote cannot exceed the stated deadline (finding MA-06).
#   - Assumption (allowlist scope): the documented LocalStack surfaces are
#     loopback (localhost/127.0.0.1/::1), the ephemeral/API DNS under
#     *.localstack.cloud, and *.localhost.localstack.cloud. Anything else --
#     above all *.amazonaws.com -- is treated as hostile and rejected, so a
#     mis-set AWS_ENDPOINT_URL or token URL can never exfiltrate the agent token
#     or route emulated writes at real AWS.
# =============================================================================

# ---------------------------------------------------------------------------
# Dual-mode detection: this script is usually executed, but run_e2e_tests.sh
# may source it so the exported AWS_ENDPOINT_URL is visible to pytest.
# WHY (Trade-off): when sourced we must NOT enable `set -e` (it would leak into
# and could terminate the caller); fail-fast is only enabled when executed.
# Likewise the cleanup TRAP is installed only when executed -- an EXIT/INT/TERM
# trap in a sourced context would fire on the CALLER's lifecycle, not ours.
# ---------------------------------------------------------------------------
if [ "${BASH_SOURCE[0]:-}" != "${0:-}" ]; then
    _CARDDEMO_LS_SOURCED=1
else
    _CARDDEMO_LS_SOURCED=0
    set -euo pipefail
fi

_ls_script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/test_env.sh
source "$_ls_script_dir/test_env.sh"

carddemo_ls_usage() {
    # Purpose : print the LocalStack setup script's usage synopsis.
    # Parameters: none.
    # Returns : always 0; the banner is written to stdout.
    # Errors  : none.
    # WHY (consistency): mirrors carddemo_master_usage() in run_tests.sh so every
    # entry point presents a uniform CLI contract (a lone -h/--help, and RC=2
    # rejection of anything else) -- closing QA finding [C2].
    cat <<'USAGE'
Usage: scripts/setup_localstack.sh [-h|--help]

Brings up headless (Docker-less) LocalStack and seeds the S3 resources needed by
the dataset-staging E2E tests, exporting AWS_ENDPOINT_URL. Takes NO positional
arguments; it is parameterised entirely by the environment (see the file header:
LOCALSTACK_AUTH_TOKEN, LOCALSTACK_AGENT_TOKEN_URL, CARDDEMO_LS_TIMEOUT,
CARDDEMO_REQUIRE_LOCALSTACK, and CARDDEMO_* paths from scripts/test_env.sh).

Options:
  -h, --help   show this help and exit 0 (NO bring-up, NO token request)
USAGE
}

# ---------------------------------------------------------------------------
# Top-level CLI contract (EXECUTED mode only).
# WHY (QA [C2] -- a help flag must never provision a cloud resource): this script
# previously had NO top-level argument parser (its `case` statements lived only
# inside functions), so `setup_localstack.sh --help` fell through to the bring-up
# flow and initiated a REAL `localstack ephemeral create` -- a genuine cloud
# side-effect that even mints an agent token first. We now parse a small explicit
# contract at entry, BEFORE the teardown trap or the dispatcher: -h/--help prints
# usage and exits 0 with no bring-up; any other argument is rejected with the
# reserved usage code (CARDDEMO_RC_USAGE=2), DISTINCT from the rubric {0,4,8} so
# an invocation typo is never reclassified as a soft-skip.
# WHY (Assumption -- parse ONLY when executed): when this file is SOURCED (by
# run_e2e_tests.sh, so the exported AWS_ENDPOINT_URL reaches pytest's process),
# "$@" refers to the CALLER's positional parameters, which may legitimately hold
# the caller's own flags / pytest passthrough. Parsing them here would wrongly
# reject the caller's arguments, so the parser is gated to executed mode and the
# sourced entry takes no arguments (Trade-off: `source ... <args>` is
# intentionally unsupported; the caller owns its own CLI).
# WHY (Refactoring rationale -- `if`, not a `while ... shift` loop): every branch
# terminates the script (like run_unit_tests.sh), so a shift-loop would leave the
# shift provably unreachable (shellcheck SC2317). A single guarded `case` on the
# first token is the faithful, symmetric adaptation.
# ---------------------------------------------------------------------------
if [ "$_CARDDEMO_LS_SOURCED" = "0" ] && [ "$#" -gt 0 ]; then
    case "$1" in
        -h|--help)
            carddemo_ls_usage
            exit 0
            ;;
        *)
            echo "[localstack] ERROR: unknown argument '$1'" >&2
            carddemo_ls_usage >&2
            exit "${CARDDEMO_RC_USAGE}"
            ;;
    esac
fi

# ---------------------------------------------------------------------------
# Security allowlist + reliability tunables.
# WHY (Assumption): these are the ONLY hosts the script will ever contact. The
# list is a default-DENY allowlist (fail closed) rather than a block-list of
# bad hosts, because a block-list can never enumerate every public endpoint an
# accident could point at.
# ---------------------------------------------------------------------------
# Per-call network budgets (seconds). Kept small so a black-holed remote cannot
# stall the suite; the overall readiness budget is CARDDEMO_LS_TIMEOUT.
readonly CARDDEMO_LS_CONNECT_TIMEOUT=10
readonly CARDDEMO_LS_MAX_TIME=30
readonly CARDDEMO_LS_HEALTH_CONNECT_TIMEOUT=5
readonly CARDDEMO_LS_HEALTH_MAX_TIME=10
readonly CARDDEMO_LS_CLI_TIMEOUT=120
readonly CARDDEMO_LS_AWSCLI_TIMEOUT=60

# Whether a genuinely-absent optional layer escalates to a hard failure.
CARDDEMO_REQUIRE_LOCALSTACK="${CARDDEMO_REQUIRE_LOCALSTACK:-0}"

# Cleanup state (see _carddemo_ls_cleanup). Kept at file scope so the trap can
# see them regardless of which function set them.
_carddemo_ls_instance_id=""
_carddemo_ls_tempdir=""
_carddemo_ls_success=0

_carddemo_ls_url_host() {
    # Purpose : extract the bare lower-cased host from an http(s) URL, dropping
    #           scheme, userinfo, path and port (IPv6-bracket aware).
    # Parameters:
    #   $1 (string) - a URL such as https://user@host:443/path.
    # Returns : 0 and echoes the host; 1 if $1 is empty.
    # Errors  : none.
    # WHY (Refactoring rationale): host parsing is factored out so BOTH the
    # token URL and the gateway endpoint are checked by identical logic -- one
    # parser, no chance of the two paths diverging.
    local url="$1" rest host
    [ -n "$url" ] || return 1
    rest="${url#*://}"      # strip scheme
    rest="${rest%%/*}"      # strip /path
    rest="${rest##*@}"      # strip userinfo@
    if [ "${rest#\[}" != "$rest" ]; then
        # Bracketed IPv6 literal: host is the text between [ and ].
        host="${rest#\[}"
        host="${host%%\]*}"
    else
        host="${rest%%:*}"  # strip :port
    fi
    printf '%s' "$host" | tr '[:upper:]' '[:lower:]'
    return 0
}

_carddemo_ls_host_allowed() {
    # Purpose : decide whether a host is a documented LocalStack/loopback target.
    # Parameters:
    #   $1 (string) - a bare host name or IP (as produced by _carddemo_ls_url_host).
    # Returns : 0 if the host is ALLOWED; 1 if it must be rejected (fail closed).
    # Errors  : none.
    # WHY (Assumption): default-deny. Only loopback and the *.localstack.cloud /
    # *.localhost.localstack.cloud emulator DNS are permitted. Public AWS hosts
    # (*.amazonaws.com) fall through to the default reject, so the emulator can
    # never be swapped for real AWS by a stray environment variable.
    local host="$1"
    case "$host" in
        localhost|127.0.0.1|::1|0:0:0:0:0:0:0:1) return 0 ;;
        localhost.localstack.cloud|*.localhost.localstack.cloud) return 0 ;;
        localstack.cloud|*.localstack.cloud) return 0 ;;
        *) return 1 ;;
    esac
}

_carddemo_ls_require_allowed_url() {
    # Purpose : guard a URL against the allowlist, emitting a security diagnostic
    #           and failing closed when it is not permitted.
    # Parameters:
    #   $1 (string) - a human label for the URL's role (e.g. "token endpoint").
    #   $2 (string) - the URL to validate.
    # Returns : 0 if allowed; CARDDEMO_RC_FAIL (8) if rejected.
    # Errors  : writes a SECURITY diagnostic to stderr on rejection.
    # WHY (Trade-off): this is the single choke point that turns MA-05 from
    # "fail open" to "fail closed" -- a rejected URL returns a HARD 8 and the
    # caller must abort before any bytes are sent to the host.
    local label="$1" url="$2" host
    host="$(_carddemo_ls_url_host "$url" || true)"
    if [ -z "$host" ]; then
        echo "[localstack] SECURITY: $label has no parseable host: '$url'" >&2
        return "${CARDDEMO_RC_FAIL}"
    fi
    if ! _carddemo_ls_host_allowed "$host"; then
        echo "[localstack] SECURITY: $label host '$host' is not on the LocalStack" >&2
        echo "[localstack]           allowlist (loopback / *.localstack.cloud); refusing" >&2
        echo "[localstack]           to contact it. Set only documented emulator hosts." >&2
        return "${CARDDEMO_RC_FAIL}"
    fi
    return 0
}

_carddemo_ls_positive_int() {
    # Purpose : test whether a value is a positive decimal integer.
    # Parameters:
    #   $1 (string) - the candidate value.
    # Returns : 0 if $1 matches ^[1-9][0-9]*$; 1 otherwise.
    # Errors  : none.
    # WHY (Assumption): the readiness timeout drives loop arithmetic; a
    # non-numeric or zero/negative value would break `SECONDS + t` math, so it is
    # validated and the caller falls back to a safe default (finding MA-06).
    case "$1" in
        ''|*[!0-9]*) return 1 ;;
        0) return 1 ;;
        *) return 0 ;;
    esac
}

_carddemo_ls_cleanup() {
    # Purpose : idempotent teardown -- remove the scratch temp dir always, and
    #           delete a created ephemeral instance UNLESS bring-up succeeded.
    # Parameters: none (reads the _carddemo_ls_* file-scope state).
    # Returns : always 0 (cleanup must never itself abort the run).
    # Errors  : cleanup diagnostics go to stderr; failures are swallowed HERE
    #           (and only here) because a best-effort teardown must not crash.
    # WHY (Trade-off): the instance is kept ONLY on full success because the
    # whole point of a successful run is to leave a healthy endpoint for the
    # pytest layer; on any interrupt or failure we delete it so nothing leaks
    # (finding MA-06). Temp files are removed unconditionally.
    if [ -n "$_carddemo_ls_tempdir" ] && [ -d "$_carddemo_ls_tempdir" ]; then
        # Containment guard: only ever remove a dir under our workspace base.
        case "$_carddemo_ls_tempdir" in
            "$CARDDEMO_WS_BASE"/*) rm -rf "$_carddemo_ls_tempdir" 2>/dev/null || true ;;
            *) echo "[localstack] refusing to remove temp dir outside workspace: $_carddemo_ls_tempdir" >&2 ;;
        esac
        _carddemo_ls_tempdir=""
    fi
    if [ "$_carddemo_ls_success" != "1" ] && [ -n "$_carddemo_ls_instance_id" ]; then
        if command -v localstack >/dev/null 2>&1; then
            echo "[localstack] cleanup: deleting ephemeral instance '$_carddemo_ls_instance_id'" >&2
            timeout "$CARDDEMO_LS_AWSCLI_TIMEOUT" \
                localstack ephemeral delete "$_carddemo_ls_instance_id" >/dev/null 2>&1 || true
        fi
        _carddemo_ls_instance_id=""
    fi
    return 0
}

carddemo_setup_localstack_main() {
    # Purpose : perform the full LocalStack bring-up + S3 seeding + verification.
    # Parameters: none (reads the environment described in the file header).
    # Returns : 0 on full success; 4 on an optional-layer skip; 8 on a security
    #           or seeding-integrity failure (fail closed).
    # Errors  : writes diagnostics to stderr.
    # WHY (Refactoring rationale): the whole flow lives in one function that
    # RETURNS an rc, so the single top-level dispatcher below can translate that
    # into `return` (sourced) or `exit` (executed) without duplicating logic.
    local manifest="$CARDDEMO_REPO_ROOT/tests/mocks/localstack_s3_manifest.json"
    local envfile="$CARDDEMO_TEST_WORKSPACE/localstack.env"

    # The workspace is provisioned + symlink-validated by test_env.sh; ensure it
    # exists for the envfile write. carddemo_ensure_workspace is idempotent.
    carddemo_ensure_workspace || true

    echo "[localstack] ============================================================"
    echo "[localstack] Headless (Docker-less) LocalStack bring-up"
    echo "[localstack] ============================================================"

    # -- validate the readiness timeout input (MA-06) ------------------------
    local _timeout="${CARDDEMO_LS_TIMEOUT:-120}"
    if ! _carddemo_ls_positive_int "$_timeout"; then
        echo "[localstack] CARDDEMO_LS_TIMEOUT='$_timeout' is not a positive integer;" >&2
        echo "[localstack] falling back to the 120s default." >&2
        _timeout=120
    fi

    # -- helper to grade an OPTIONAL-layer gap per REQUIRE flag ---------------
    # WHY (Refactoring rationale): reachability gaps share one policy (WARN, or
    # FAIL when REQUIRE=1); centralising it keeps every skip consistent and
    # auditable rather than sprinkling the choice across each return.
    local _soft_rc="$CARDDEMO_RC_WARN"
    if [ "$CARDDEMO_REQUIRE_LOCALSTACK" = "1" ]; then
        _soft_rc="$CARDDEMO_RC_FAIL"
    fi

    # -- prerequisites (optional layer) --------------------------------------
    # WHY (Assumption): the AWS layer is optional; a runner without these tools
    # should skip cleanly (WARN) unless the operator demanded it (REQUIRE=1).
    local _missing=0 _c
    for _c in localstack jq curl awslocal; do
        if ! command -v "$_c" >/dev/null 2>&1; then
            echo "[localstack] optional tool '$_c' not found on PATH" >&2
            _missing=1
        fi
    done
    if [ "$_missing" = "1" ]; then
        echo "[localstack] prerequisites missing - skipping bring-up (rc=$_soft_rc)"
        return "$_soft_rc"
    fi

    # -- scratch temp dir (signal-safe; removed by the cleanup trap) ---------
    # WHY (Refactoring rationale): all object payloads are staged inside ONE
    # contained temp dir under the workspace so the EXIT/INT/TERM trap can wipe
    # them in a single rm, rather than tracking N individual mktemp files.
    _carddemo_ls_tempdir="$(mktemp -d "$CARDDEMO_WS_BASE/ls-seed.XXXXXX")" || {
        echo "[localstack] could not create scratch temp dir - skipping (rc=$_soft_rc)" >&2
        return "$_soft_rc"
    }

    # -- agent token ----------------------------------------------------------
    # WHY (Assumption): CI may inject LOCALSTACK_AUTH_TOKEN as a secret; only
    # request an ephemeral agent token when one is not already present.
    if [ -z "${LOCALSTACK_AUTH_TOKEN:-}" ]; then
        local token_url="${LOCALSTACK_AGENT_TOKEN_URL:-https://v2.api.localstack.cloud/v1/auth/agent-token}"
        # SECURITY (MA-05): the token carries an authorization credential, so the
        # endpoint that mints it MUST be an allowlisted LocalStack host. A
        # rejected URL is a HARD failure and is never contacted.
        _carddemo_ls_require_allowed_url "token endpoint" "$token_url" || return "$CARDDEMO_RC_FAIL"
        echo "[localstack] requesting agent token from $token_url ..."
        local _resp
        if ! _resp="$(curl -fsS \
                --connect-timeout "$CARDDEMO_LS_CONNECT_TIMEOUT" \
                --max-time "$CARDDEMO_LS_MAX_TIME" \
                -X POST "$token_url" 2>/dev/null)"; then
            echo "[localstack] agent-token request failed/timed out - skipping (rc=$_soft_rc)" >&2
            return "$_soft_rc"
        fi
        LOCALSTACK_AUTH_TOKEN="$(printf '%s' "$_resp" | jq -r '.token // empty')"
        API_ENDPOINT="$(printf '%s' "$_resp" | jq -r '.api_endpoint // empty')"
        export LOCALSTACK_AUTH_TOKEN API_ENDPOINT
        if [ -z "$LOCALSTACK_AUTH_TOKEN" ]; then
            echo "[localstack] token response had no .token - skipping (rc=$_soft_rc)" >&2
            return "$_soft_rc"
        fi
    fi

    # -- create ephemeral instance (bounded) ---------------------------------
    # MI-01 (SC2155): declaration and command substitution are split so a
    # non-zero rc from the substitution is not masked by `local`.
    local _name
    _name="agent-$(date +%s)-$$"
    echo "[localstack] creating ephemeral instance '$_name' ..."
    local _create_out
    if ! _create_out="$(timeout "$CARDDEMO_LS_CLI_TIMEOUT" \
            localstack ephemeral create --name "$_name" 2>&1)"; then
        echo "[localstack] 'ephemeral create' failed/timed out - skipping (rc=$_soft_rc):" >&2
        echo "$_create_out" >&2
        return "$_soft_rc"
    fi
    # Capture an instance identifier for cleanup (MA-06). Prefer a JSON id/name
    # from the CLI output; fall back to the name we chose (which we control).
    # WHY (Assumption): the create output format may be JSON or human text, so we
    # try jq first and degrade to our known --name so cleanup always has a key.
    _carddemo_ls_instance_id="$(printf '%s' "$_create_out" \
        | jq -r '(.id // .instance_id // .name // empty)' 2>/dev/null || true)"
    if [ -z "$_carddemo_ls_instance_id" ]; then
        _carddemo_ls_instance_id="$_name"
    fi
    echo "[localstack] ephemeral instance id: $_carddemo_ls_instance_id"

    # WHY (Assumption): the CLI prints the gateway URL; take the first http(s)
    # URL it emits, falling back to the standard local gateway if none parses.
    local _endpoint
    _endpoint="$(printf '%s\n' "$_create_out" | grep -oE 'https?://[A-Za-z0-9._:/-]+' | head -1 || true)"
    if [ -z "$_endpoint" ]; then
        _endpoint="${AWS_ENDPOINT_URL:-http://localhost:4566}"
        echo "[localstack] could not parse endpoint from CLI output; using $_endpoint" >&2
    fi
    # SECURITY (MA-05): whether parsed or defaulted, the gateway MUST be an
    # allowlisted LocalStack host before we export it or send a single AWS call
    # to it. This closes the "fallback can route outside LocalStack" hole.
    _carddemo_ls_require_allowed_url "gateway endpoint" "$_endpoint" || return "$CARDDEMO_RC_FAIL"
    export AWS_ENDPOINT_URL="$_endpoint"
    echo "[localstack] gateway endpoint: $AWS_ENDPOINT_URL (allowlisted)"

    # -- readiness poll (completion-aware, bounded) ---------------------------
    local _deadline=$(( SECONDS + _timeout )) _ready=0
    echo "[localstack] waiting up to ${_timeout}s for health ..."
    while [ "$SECONDS" -lt "$_deadline" ]; do
        if curl -fsS \
                --connect-timeout "$CARDDEMO_LS_HEALTH_CONNECT_TIMEOUT" \
                --max-time "$CARDDEMO_LS_HEALTH_MAX_TIME" \
                "$AWS_ENDPOINT_URL/_localstack/health" >/dev/null 2>&1; then
            _ready=1
            break
        fi
        # WHY (Refactoring rationale): a SHORT poll interval inside a loop that
        # breaks the moment health responds -- this is race-free completion
        # detection, not the fixed total `sleep` the demo scripts used. The curl
        # connect/max-time caps each probe so a hung socket cannot outlast the
        # overall deadline.
        sleep 2
    done
    if [ "$_ready" != "1" ]; then
        echo "[localstack] instance not healthy before timeout - skipping seed (rc=$_soft_rc)" >&2
        printf 'AWS_ENDPOINT_URL=%s\n' "$AWS_ENDPOINT_URL" > "$envfile"
        return "$_soft_rc"
    fi

    # -- manifest presence (optional layer) ----------------------------------
    if [ ! -f "$manifest" ]; then
        echo "[localstack] manifest not present ($manifest) - endpoint up, seed skipped (rc=$_soft_rc)"
        printf 'AWS_ENDPOINT_URL=%s\n' "$AWS_ENDPOINT_URL" > "$envfile"
        return "$_soft_rc"
    fi

    # -- seed S3 from the manifest AND verify (MA-05: no swallowed failures) --
    echo "[localstack] seeding S3 from $manifest ..."
    # WHY (Assumption): manifest schema is lenient -- buckets may be plain
    # strings or objects with .name; objects carry .bucket/.key and optional
    # .content. Unlike the previous version, EACH mb/cp result is checked and the
    # resource is then VERIFIED to exist; any failure returns a hard 8 because at
    # this point we are talking to a healthy, allowlisted emulator, so a failure
    # is real -- not an optional-layer gap.
    local _b
    while IFS= read -r _b; do
        [ -z "$_b" ] && continue
        echo "[localstack]   mb s3://$_b"
        if ! timeout "$CARDDEMO_LS_AWSCLI_TIMEOUT" awslocal s3 mb "s3://$_b" >/dev/null 2>&1; then
            # `mb` on an existing bucket is not an error we want to abort on, so
            # distinguish "already exists" (fine) from a genuine failure by a
            # follow-up existence probe below rather than trusting mb's rc alone.
            echo "[localstack]   (mb returned non-zero for '$_b'; verifying existence)" >&2
        fi
        if ! timeout "$CARDDEMO_LS_AWSCLI_TIMEOUT" awslocal s3 ls "s3://$_b" >/dev/null 2>&1; then
            echo "[localstack]   INTEGRITY: bucket '$_b' could not be verified after mb" >&2
            return "$CARDDEMO_RC_FAIL"
        fi
    done < <(jq -r '(.buckets // [])[] | if type=="object" then .name else . end' "$manifest" 2>/dev/null)

    local _bucket _key _content _tmp
    while IFS=$'\t' read -r _bucket _key _content; do
        [ -z "$_bucket" ] && continue
        [ -z "$_key" ] && continue
        _tmp="$_carddemo_ls_tempdir/obj.$$"
        printf '%s' "$_content" > "$_tmp"
        echo "[localstack]   cp -> s3://$_bucket/$_key"
        if ! timeout "$CARDDEMO_LS_AWSCLI_TIMEOUT" \
                awslocal s3 cp "$_tmp" "s3://$_bucket/$_key" >/dev/null 2>&1; then
            echo "[localstack]   INTEGRITY: failed to upload s3://$_bucket/$_key" >&2
            rm -f "$_tmp" 2>/dev/null || true
            return "$CARDDEMO_RC_FAIL"
        fi
        rm -f "$_tmp" 2>/dev/null || true
        # Verify the object is actually present (do not trust cp's rc alone).
        if ! timeout "$CARDDEMO_LS_AWSCLI_TIMEOUT" \
                awslocal s3 ls "s3://$_bucket/$_key" >/dev/null 2>&1; then
            echo "[localstack]   INTEGRITY: object s3://$_bucket/$_key not found after cp" >&2
            return "$CARDDEMO_RC_FAIL"
        fi
    done < <(jq -r '(.objects // [])[] | [.bucket, .key, (.content // "")] | @tsv' "$manifest" 2>/dev/null)

    printf 'AWS_ENDPOINT_URL=%s\n' "$AWS_ENDPOINT_URL" > "$envfile"
    echo "[localstack] bring-up complete; wrote $envfile"
    # Mark success LAST so the EXIT trap keeps (does not delete) the instance the
    # pytest layer is about to use.
    _carddemo_ls_success=1
    return 0
}

# ---------------------------------------------------------------------------
# Install the teardown trap (executed mode only; see the dual-mode note above).
# WHY (Trade-off): a trap in a sourced shell would fire on the CALLER's EXIT and
# could delete the instance the caller wants to keep, so we register it only
# when this script owns the process.
# ---------------------------------------------------------------------------
if [ "$_CARDDEMO_LS_SOURCED" = "0" ]; then
    trap '_carddemo_ls_cleanup' EXIT INT TERM
fi

# ---------------------------------------------------------------------------
# Dispatcher: run the flow, then return (sourced) or exit (executed) with rc.
# ---------------------------------------------------------------------------
carddemo_setup_localstack_main
_carddemo_ls_rc=$?

# When sourced, there is no EXIT trap, so run cleanup inline: this deletes a
# created-but-unsuccessful instance and removes the scratch temp dir without
# affecting a successful bring-up (success keeps the instance for the caller).
if [ "$_CARDDEMO_LS_SOURCED" = "1" ]; then
    _carddemo_ls_cleanup
    # shellcheck disable=SC2317  # reachable only in the sourced branch; the
    # `return` executes when sourced and the `|| exit` covers the executed
    # fallback, so shellcheck's "unreachable" heuristic is a false positive here.
    return "$_carddemo_ls_rc" 2>/dev/null || exit "$_carddemo_ls_rc"
else
    exit "$_carddemo_ls_rc"
fi
