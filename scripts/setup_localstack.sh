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
#   source scripts/setup_localstack.sh # sourced  : also exports AWS_ENDPOINT_URL
#
# Parameters (environment inputs):
#   LOCALSTACK_AUTH_TOKEN     - if already set (CI secret), the token request is
#                               skipped.
#   LOCALSTACK_AGENT_TOKEN_URL- override for the agent-token endpoint.
#   CARDDEMO_LS_TIMEOUT       - readiness timeout in seconds (default 120).
#   (plus CARDDEMO_* paths and RC constants from scripts/test_env.sh)
#
# Return / Exit codes (CardDemo RC rubric):
#   0  LocalStack is up and S3 seeded from the manifest.
#   4  soft skip/warn: prerequisites missing, token unavailable, instance not
#      ready, or manifest absent (the AWS layer is OPTIONAL, so this never fails
#      the suite).
#
# Errors / Exceptions:
#   All failure modes degrade to a WARN (4) with a stderr diagnostic; the script
#   never returns a hard-fail code because AWS emulation is an optional layer.
#
# WHY (design rationale):
#   - Assumption: no Docker is available on the runner, so the ephemeral
#     (cloud-pod) path is used instead of the container image.
#   - Alternatives Considered: a docker-compose LocalStack was rejected because
#     the runner cannot run Docker; the headless ephemeral CLI needs none.
#   - Trade-off: the AWS layer is best-effort/optional -- every failure is a WARN
#     (not a hard fail) so the core COBOL suite still passes CI on a runner with
#     no LocalStack credentials.
#   - Refactoring rationale: readiness is confirmed by polling the health
#     endpoint in a bounded loop (exits the instant it is healthy), NOT by a
#     fixed total `sleep` like the legacy demo scripts.
# =============================================================================

# ---------------------------------------------------------------------------
# Dual-mode detection: this script is usually executed, but run_e2e_tests.sh
# may source it so the exported AWS_ENDPOINT_URL is visible to pytest.
# WHY (Trade-off): when sourced we must NOT enable `set -e` (it would leak into
# and could terminate the caller); fail-fast is only enabled when executed.
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

carddemo_setup_localstack_main() {
    # Purpose : perform the full LocalStack bring-up + S3 seeding sequence.
    # Parameters: none (reads environment described in the file header).
    # Returns : 0 on full success; 4 on any soft-skip/warn condition.
    # Errors  : writes diagnostics to stderr; never returns a hard-fail code.
    # WHY (Refactoring rationale): the whole flow lives in one function that
    # RETURNS an rc, so the single top-level dispatcher below can translate that
    # into `return` (sourced) or `exit` (executed) without duplicating logic.
    local manifest="$CARDDEMO_REPO_ROOT/tests/mocks/localstack_s3_manifest.json"
    local envfile="$CARDDEMO_TEST_WORKSPACE/localstack.env"
    mkdir -p "$CARDDEMO_TEST_WORKSPACE"

    echo "[localstack] ============================================================"
    echo "[localstack] Headless (Docker-less) LocalStack bring-up"
    echo "[localstack] ============================================================"

    # -- prerequisites (all soft) --------------------------------------------
    # WHY (Assumption): the AWS layer is optional; a runner without these tools
    # should skip cleanly rather than fail the whole suite.
    local _missing=0 _c
    for _c in localstack jq curl awslocal; do
        if ! command -v "$_c" >/dev/null 2>&1; then
            echo "[localstack] optional tool '$_c' not found on PATH" >&2
            _missing=1
        fi
    done
    if [ "$_missing" = "1" ]; then
        echo "[localstack] prerequisites missing - skipping bring-up (warn)"
        return "${CARDDEMO_RC_WARN}"
    fi

    # -- agent token ----------------------------------------------------------
    # WHY (Assumption): CI may inject LOCALSTACK_AUTH_TOKEN as a secret; only
    # request an ephemeral agent token when one is not already present.
    if [ -z "${LOCALSTACK_AUTH_TOKEN:-}" ]; then
        local token_url="${LOCALSTACK_AGENT_TOKEN_URL:-https://v2.api.localstack.cloud/v1/auth/agent-token}"
        echo "[localstack] requesting agent token from $token_url ..."
        local _resp
        if ! _resp="$(curl -fsS -X POST "$token_url" 2>/dev/null)"; then
            echo "[localstack] agent-token request failed - skipping (warn)" >&2
            return "${CARDDEMO_RC_WARN}"
        fi
        LOCALSTACK_AUTH_TOKEN="$(printf '%s' "$_resp" | jq -r '.token // empty')"
        API_ENDPOINT="$(printf '%s' "$_resp" | jq -r '.api_endpoint // empty')"
        export LOCALSTACK_AUTH_TOKEN API_ENDPOINT
        if [ -z "$LOCALSTACK_AUTH_TOKEN" ]; then
            echo "[localstack] token response had no .token - skipping (warn)" >&2
            return "${CARDDEMO_RC_WARN}"
        fi
    fi

    # -- create ephemeral instance -------------------------------------------
    local _name="agent-$(date +%s)"
    echo "[localstack] creating ephemeral instance '$_name' ..."
    local _create_out
    if ! _create_out="$(localstack ephemeral create --name "$_name" 2>&1)"; then
        echo "[localstack] 'ephemeral create' failed - skipping (warn):" >&2
        echo "$_create_out" >&2
        return "${CARDDEMO_RC_WARN}"
    fi
    # WHY (Assumption): the CLI prints the gateway URL; take the first http(s)
    # URL it emits, falling back to the standard local gateway if none parses.
    local _endpoint
    _endpoint="$(printf '%s\n' "$_create_out" | grep -oE 'https?://[A-Za-z0-9._:/-]+' | head -1 || true)"
    if [ -z "$_endpoint" ]; then
        _endpoint="${AWS_ENDPOINT_URL:-http://localhost:4566}"
        echo "[localstack] could not parse endpoint from CLI output; using $_endpoint" >&2
    fi
    export AWS_ENDPOINT_URL="$_endpoint"
    echo "[localstack] gateway endpoint: $AWS_ENDPOINT_URL"

    # -- readiness poll (completion-aware, bounded) ---------------------------
    local _timeout="${CARDDEMO_LS_TIMEOUT:-120}"
    local _deadline=$(( SECONDS + _timeout )) _ready=0
    echo "[localstack] waiting up to ${_timeout}s for health ..."
    while [ "$SECONDS" -lt "$_deadline" ]; do
        if curl -fsS "$AWS_ENDPOINT_URL/_localstack/health" >/dev/null 2>&1; then
            _ready=1
            break
        fi
        # WHY (Refactoring rationale): a SHORT poll interval inside a loop that
        # breaks the moment health responds -- this is race-free completion
        # detection, not the fixed total `sleep` the demo scripts used.
        sleep 2
    done
    if [ "$_ready" != "1" ]; then
        echo "[localstack] instance not healthy before timeout - skipping seed (warn)" >&2
        printf 'AWS_ENDPOINT_URL=%s\n' "$AWS_ENDPOINT_URL" > "$envfile"
        return "${CARDDEMO_RC_WARN}"
    fi

    # -- seed S3 from the manifest -------------------------------------------
    if [ ! -f "$manifest" ]; then
        echo "[localstack] manifest not present ($manifest) - endpoint up, seeding skipped (warn)"
        printf 'AWS_ENDPOINT_URL=%s\n' "$AWS_ENDPOINT_URL" > "$envfile"
        return "${CARDDEMO_RC_WARN}"
    fi

    echo "[localstack] seeding S3 from $manifest ..."
    # WHY (Assumption): manifest schema is lenient -- buckets may be plain
    # strings or objects with .name; objects carry .bucket/.key and optional
    # .content. Seeding is best-effort (|| true) so one bad row cannot abort it.
    local _b
    while IFS= read -r _b; do
        [ -z "$_b" ] && continue
        echo "[localstack]   mb s3://$_b"
        awslocal s3 mb "s3://$_b" >/dev/null 2>&1 || true
    done < <(jq -r '(.buckets // [])[] | if type=="object" then .name else . end' "$manifest" 2>/dev/null)

    local _bucket _key _content _tmp
    while IFS=$'\t' read -r _bucket _key _content; do
        [ -z "$_bucket" ] && continue
        [ -z "$_key" ] && continue
        _tmp="$(mktemp)"
        printf '%s' "$_content" > "$_tmp"
        echo "[localstack]   cp -> s3://$_bucket/$_key"
        awslocal s3 cp "$_tmp" "s3://$_bucket/$_key" >/dev/null 2>&1 || true
        rm -f "$_tmp"
    done < <(jq -r '(.objects // [])[] | [.bucket, .key, (.content // "")] | @tsv' "$manifest" 2>/dev/null)

    printf 'AWS_ENDPOINT_URL=%s\n' "$AWS_ENDPOINT_URL" > "$envfile"
    echo "[localstack] bring-up complete; wrote $envfile"
    return 0
}

# ---------------------------------------------------------------------------
# Dispatcher: run the flow, then return (sourced) or exit (executed) with rc.
# ---------------------------------------------------------------------------
carddemo_setup_localstack_main
_carddemo_ls_rc=$?
if [ "$_CARDDEMO_LS_SOURCED" = "1" ]; then
    return "$_carddemo_ls_rc" 2>/dev/null || exit "$_carddemo_ls_rc"
else
    exit "$_carddemo_ls_rc"
fi
