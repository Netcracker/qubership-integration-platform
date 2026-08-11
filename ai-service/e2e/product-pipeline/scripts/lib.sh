#!/usr/bin/env bash
# Shared helpers for ai-service live E2E.
set -euo pipefail

E2E_LIB_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
E2E_DIR="$(cd "${E2E_LIB_DIR}/.." && pwd)"

: "${E2E_BASE_URL:=http://localhost:8094}"
: "${E2E_CATALOG_URL:=http://localhost:8091}"
: "${E2E_CONTAINER:=qip-ai-service}"
# A CREATE turn runs whole pipeline stages behind one request. Measured turns reach 8 minutes on
# design-input and design-planning, so a budget under that reports a client timeout as a run
# failure while the run is still healthy.
: "${E2E_TURN_TIMEOUT_SEC:=900}"
: "${E2E_HEALTH_TIMEOUT_SEC:=180}"

e2e_fail() {
  echo "FAIL: $*" >&2
  exit 1
}

e2e_warn() {
  echo "WARN: $*" >&2
}

e2e_info() {
  echo "==> $*" >&2
}

e2e_pass() {
  echo "PASS: $*" >&2
}

e2e_require_cmd() {
  local cmd="$1"
  command -v "$cmd" >/dev/null 2>&1 || e2e_fail "missing required command: ${cmd}"
}

e2e_require_cmds() {
  local cmd
  for cmd in "$@"; do
    e2e_require_cmd "$cmd"
  done
}

e2e_timestamp() {
  date -u +"%Y%m%dT%H%M%SZ"
}

e2e_wait_health() {
  local base_url="${1:-$E2E_BASE_URL}"
  local timeout="${2:-$E2E_HEALTH_TIMEOUT_SEC}"
  local deadline=$((SECONDS + timeout))
  e2e_info "waiting for health at ${base_url}/q/health (timeout ${timeout}s)"
  while ((SECONDS < deadline)); do
    if curl -sf "${base_url}/q/health" >/dev/null 2>&1; then
      e2e_pass "service healthy"
      return 0
    fi
    sleep 3
  done
  e2e_fail "service not healthy after ${timeout}s: ${base_url}/q/health"
}

e2e_fetch_logs() {
  local out_file="$1"
  if docker ps --format '{{.Names}}' 2>/dev/null | grep -qx "$E2E_CONTAINER"; then
    docker logs "$E2E_CONTAINER" 2>&1 >"$out_file" || true
    return 0
  fi
  e2e_warn "container ${E2E_CONTAINER} not running; log assertions use empty log file"
  : >"$out_file"
}

e2e_conversation_logs() {
  local full_log="$1"
  local conversation_id="$2"
  local out_file="$3"
  if [[ -z "$conversation_id" || "$conversation_id" == "-" ]]; then
    cp "$full_log" "$out_file"
    return 0
  fi
  {
    rg -F "conversationId=${conversation_id}" "$full_log" 2>/dev/null || true
    rg -F "${conversation_id}" "$full_log" 2>/dev/null || true
  } | sort -u >"$out_file"
}

e2e_json_escape() {
  python3 -c 'import json,sys; print(json.dumps(sys.stdin.read()))' <<<"$1"
}

e2e_extract_done_conversation_id() {
  local sse_file="$1"
  local conv_id=""
  conv_id="$(awk '
    /^event: done$/ { getline; sub(/^data: /, ""); print; exit }
    /^data:event: done$/ { getline; sub(/^data:data: /, ""); print; exit }
  ' "$sse_file" | tr -d '\r')"
  if [[ -z "$conv_id" ]]; then
    conv_id="$(rg -o 'event: done\ndata: [^\n]+' "$sse_file" 2>/dev/null | tail -1 | sed 's/^event: done\ndata: //' | tr -d '\r' || true)"
  fi
  if [[ -z "$conv_id" ]]; then
    conv_id="$(rg -o 'data:event: done\ndata:data: [^\n]+' "$sse_file" 2>/dev/null | tail -1 | sed 's/^data:event: done\ndata:data: //' | tr -d '\r' || true)"
  fi
  printf '%s' "$conv_id"
}

e2e_assert_patterns() {
  local label="$1"
  local haystack_file="$2"
  local mode="$3"
  shift 3
  local patterns=("$@")
  local pattern
  for pattern in "${patterns[@]}"; do
    if [[ "$mode" == "must" ]]; then
      if ! rg -F -q -- "$pattern" "$haystack_file" 2>/dev/null; then
        e2e_fail "${label}: missing required pattern: ${pattern}"
      fi
      e2e_pass "${label}: found '${pattern}'"
    else
      if rg -F -q -- "$pattern" "$haystack_file" 2>/dev/null; then
        e2e_fail "${label}: forbidden pattern present: ${pattern}"
      fi
      e2e_pass "${label}: absent '${pattern}'"
    fi
  done
}

e2e_jq_strings() {
  local file="$1"
  local jq_expr="$2"
  jq -r "$jq_expr | if type == \"array\" then .[] else empty end" "$file"
}

: "${E2E_TPM_RETRY:=1}"
: "${E2E_TPM_FALLBACK_SLEEP_SEC:=5}"
: "${LLM_EXCHANGE_LOG_PATH:=/tmp/llm-exchange.log}"

e2e_detect_tpm() {
  local haystack_file="$1"
  if [[ ! -f "$haystack_file" ]]; then
    return 1
  fi
  rg -q "rate_limit_exceeded|tokens per min \\(TPM\\)|Rate limit reached for gpt-" "$haystack_file" 2>/dev/null
}

# True when the SSE stream ended with a terminal error event.
e2e_detect_sse_error() {
  local haystack_file="$1"
  if [[ ! -f "$haystack_file" ]]; then
    return 1
  fi
  rg -q '^data:event: error$' "$haystack_file" 2>/dev/null
}

# Abort the scenario when a turn hits a non-TPM SSE error.
# Continuing would send Agree against PLAN_DRAFT (no ImplementationPlan) and
# re-run CREATE_CHAIN_PLAN instead of approving → missing bundle.
e2e_abort_if_sse_error() {
  local label="$1"
  local haystack_file="$2"
  if ! e2e_detect_sse_error "$haystack_file"; then
    return 0
  fi
  if e2e_detect_tpm "$haystack_file"; then
    return 0
  fi
  local detail
  detail="$(
    rg -A1 '^data:event: error$' "$haystack_file" 2>/dev/null \
      | tail -1 \
      | sed 's/^data:data: //' \
      | tr -d '\r' \
      || true
  )"
  e2e_fail "turn ${label}: SSE event:error (non-TPM): ${detail:-unknown}"
}

e2e_parse_retry_after_sec() {
  local haystack_file="$1"
  local fallback="${2:-$E2E_TPM_FALLBACK_SLEEP_SEC}"
  local ms
  ms="$(rg -o 'Please try again in [0-9.]+ms' "$haystack_file" 2>/dev/null | tail -1 | rg -o '[0-9.]+' || true)"
  if [[ -n "$ms" ]]; then
  python3 - <<PY
import math
ms = float("${ms}")
print(max(1, int(math.ceil(ms / 1000.0))))
PY
    return 0
  fi
  local sec
  sec="$(rg -o 'Please try again in [0-9.]+s' "$haystack_file" 2>/dev/null | tail -1 | rg -o '[0-9.]+' || true)"
  if [[ -n "$sec" ]]; then
    python3 - <<PY
import math
sec = float("${sec}")
print(max(1, int(math.ceil(sec))))
PY
    return 0
  fi
  echo "$fallback"
}

e2e_copy_llm_exchange() {
  local dest_dir="$1"
  if [[ -f "$LLM_EXCHANGE_LOG_PATH" ]]; then
    cp "$LLM_EXCHANGE_LOG_PATH" "${dest_dir}/llm-exchange.log" 2>/dev/null || true
    e2e_info "copied llm exchange log to ${dest_dir}/llm-exchange.log"
  fi
}

e2e_tpm_sleep_if_needed() {
  local haystack_file="$1"
  if ! e2e_detect_tpm "$haystack_file"; then
    return 1
  fi
  local wait_sec
  wait_sec="$(e2e_parse_retry_after_sec "$haystack_file")"
  e2e_warn "TPM detected; sleeping ${wait_sec}s before retry"
  sleep "$wait_sec"
  return 0
}
