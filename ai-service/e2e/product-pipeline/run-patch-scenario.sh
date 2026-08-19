#!/usr/bin/env bash
# Drive one COMPARE_AND_PATCH conversation through POST /api/v1/chat (SSE).
#
# Production path: open-chain attachment, LLM classifier, ChainPatchScenario, apply-chain-patch
# decision card, catalog write. Each prompts[] item is a separate chat turn.
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRIPTS_DIR="$(cd "${DIR}/scripts" && pwd)"

SCENARIO_ID=""
REP=""
BASE_URL=""
REPORT_PATH=""

usage() {
  cat >&2 <<'EOF'
Usage: run-patch-scenario.sh \
  --scenario <id> --rep <n> \
  --base-url <url> --report <path>
EOF
  exit 2
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --scenario) SCENARIO_ID="${2:?}"; shift 2 ;;
    --rep) REP="${2:?}"; shift 2 ;;
    --base-url) BASE_URL="${2:?}"; shift 2 ;;
    --report) REPORT_PATH="${2:?}"; shift 2 ;;
    --evaluator-url) shift 2 ;; # accepted so the CREATE gate can share argv
    --transport)
      [[ "${2:?}" == "chat" ]] || {
        echo "FAIL: compare-and-patch e2e uses chat transport only" >&2
        exit 2
      }
      shift 2
      ;;
    -h|--help) usage ;;
    *) echo "Unknown option: $1" >&2; usage ;;
  esac
done

[[ -n "${SCENARIO_ID}" && -n "${REP}" && -n "${BASE_URL}" && -n "${REPORT_PATH}" ]] || usage

# shellcheck source=scripts/lib.sh
source "${SCRIPTS_DIR}/lib.sh"

SCENARIOS_FILE="${DIR}/scenarios.json"
[[ -f "${SCENARIOS_FILE}" ]] || e2e_fail "missing scenarios.json"
jq -e --arg s "${SCENARIO_ID}" '.[$s]' "${SCENARIOS_FILE}" >/dev/null \
  || e2e_fail "unknown scenario ${SCENARIO_ID}"

pipeline="$(jq -r --arg s "${SCENARIO_ID}" '.[$s].pipeline // empty' "${SCENARIOS_FILE}")"
expected_state="$(jq -r --arg s "${SCENARIO_ID}" '.[$s].terminalState // empty' "${SCENARIOS_FILE}")"
retain_catalog_chain="$(jq -r --arg s "${SCENARIO_ID}" '.[$s].retainCatalogChain // false' "${SCENARIOS_FILE}")"
unique_prefix="$(jq -r --arg s "${SCENARIO_ID}" '.[$s].uniqueChainNamePrefix // empty' "${SCENARIOS_FILE}")"
prompts_json="$(jq -c --arg s "${SCENARIO_ID}" '
  if (.[$s].prompts | type) == "array" and (.[$s].prompts | length) > 0 then .[$s].prompts
  elif (.[$s].prompt | type) == "string" and (.[$s].prompt | length) > 0 then [.[$s].prompt]
  else []
  end
' "${SCENARIOS_FILE}")"
catalog_url="${E2E_CATALOG_URL:-http://localhost:8091}"

[[ "${pipeline}" == "compare-and-patch" ]] \
  || e2e_fail "scenario ${SCENARIO_ID} pipeline must be compare-and-patch (got ${pipeline})"
[[ "${expected_state}" == "CHAIN_PATCHED" ]] \
  || e2e_fail "scenario ${SCENARIO_ID} terminalState must be CHAIN_PATCHED"
[[ "${retain_catalog_chain}" == "true" ]] \
  || e2e_fail "scenario ${SCENARIO_ID} retainCatalogChain must be true"
[[ -n "${unique_prefix}" ]] \
  || e2e_fail "scenario ${SCENARIO_ID} uniqueChainNamePrefix is required"
jq -e 'type == "array" and length > 0' <<<"${prompts_json}" >/dev/null \
  || e2e_fail "scenario ${SCENARIO_ID} needs prompt or prompts[]"

mkdir -p "$(dirname "${REPORT_PATH}")"
run_dir="$(dirname "${REPORT_PATH}")"
run_stamp="$(date -u +%Y%m%dT%H%M%S)"
unique_chain_name="${unique_prefix}.${run_stamp}.r${REP}"
printf '%s\n' "${unique_chain_name}" >"${run_dir}/unique-chain-name.txt"
printf 'retainCatalogChain=%s uniqueChainName=%s\n' \
  "${retain_catalog_chain}" "${unique_chain_name}" >"${run_dir}/catalog-retention.txt"

prompt_count="$(jq 'length' <<<"${prompts_json}")"

write_report() {
  local conversation_id="$1"
  local chain_id="$2"
  local terminal="$3"
  local stub_flag="$4"
  jq -nc \
    --arg scenario "${SCENARIO_ID}" \
    --argjson rep "${REP}" \
    --arg conversationId "${conversation_id}" \
    --arg chainId "${chain_id}" \
    --arg uniqueName "${unique_chain_name}" \
    --arg terminal "${terminal}" \
    --arg expected "${expected_state}" \
    --argjson promptCount "${prompt_count}" \
    --argjson prompts "${prompts_json}" \
    --argjson stub "${stub_flag}" \
    '{
      scenarioId: $scenario,
      rep: $rep,
      conversationId: $conversationId,
      chainId: $chainId,
      uniqueChainName: $uniqueName,
      pipeline: "compare-and-patch",
      runtimeMode: "product",
      terminalState: $terminal,
      expectedTerminalState: $expected,
      promptCount: $promptCount,
      prompts: $prompts,
      retainCatalogChain: true,
      stub: $stub
    }' >"${REPORT_PATH}"
}

if [[ "${PRODUCT_PIPELINE_STUB_MODE:-0}" == "1" ]]; then
  write_report "stub-${SCENARIO_ID}-${REP}" "stub-chain-${SCENARIO_ID}" "${expected_state}" true
  bash "${DIR}/assert-patch-run.sh" "${REPORT_PATH}"
  exit 0
fi

command -v curl >/dev/null
command -v jq >/dev/null
[[ -f "${SCRIPTS_DIR}/chat-turn.sh" ]] || e2e_fail "missing ${SCRIPTS_DIR}/chat-turn.sh"
[[ -f "${SCRIPTS_DIR}/seed-catalog-chain.sh" ]] || e2e_fail "missing seed-catalog-chain.sh"

export E2E_BASE_URL="${BASE_URL}"
sse_dir="${run_dir}/sse"
mkdir -p "${sse_dir}"

chain_id="$(
  bash "${SCRIPTS_DIR}/seed-catalog-chain.sh" \
    "${SCENARIOS_FILE}" "${SCENARIO_ID}" "${catalog_url}" "${unique_chain_name}"
)"
[[ -n "${chain_id}" ]] || e2e_fail "seed-catalog-chain.sh returned no chain id"
printf '%s\n' "${chain_id}" >"${run_dir}/seeded-chain-id.txt"
e2e_info "seeded catalog chain ${chain_id}"

attachment="$(e2e_chain_attachment "${unique_chain_name}" "${chain_id}")"
conversation_id="-"

send_turn() {
  local label="$1"
  local text="$2"
  local decision_json="${3:-}"
  local out_sse="${sse_dir}/${label}.sse"
  local new_id
  if [[ -n "${decision_json}" ]]; then
    new_id="$(
      E2E_CHAT_ATTACHMENT="${attachment}" \
      E2E_CHAT_DECISION_JSON="${decision_json}" \
        bash "${SCRIPTS_DIR}/chat-turn.sh" "${BASE_URL}" "${conversation_id}" "${text}" "${out_sse}"
    )"
  else
    new_id="$(
      E2E_CHAT_ATTACHMENT="${attachment}" \
        bash "${SCRIPTS_DIR}/chat-turn.sh" "${BASE_URL}" "${conversation_id}" "${text}" "${out_sse}"
    )"
  fi
  conversation_id="${new_id}"
  [[ -n "${conversation_id}" && "${conversation_id}" != "-" ]] \
    || e2e_fail "turn ${label} did not return conversationId"
  e2e_abort_if_sse_error "${label}" "${out_sse}"
}

idx=0
while [[ "${idx}" -lt "${prompt_count}" ]]; do
  prompt_text="$(jq -r --argjson i "${idx}" '.[$i]' <<<"${prompts_json}")"
  turn_label="$(printf '%02d-prompt' "$((idx + 1))")"
  send_turn "${turn_label}" "${prompt_text}"
  decision_json=""
  if ! decision_json="$(e2e_extract_apply_chain_patch_decision "${sse_dir}/${turn_label}.sse")"; then
    tokens="$(e2e_extract_sse_tokens "${sse_dir}/${turn_label}.sse" || true)"
    e2e_fail "turn ${turn_label} produced no apply-chain-patch decision card: ${tokens:-<empty SSE tokens>}"
  fi
  printf '%s\n' "${decision_json}" >"${sse_dir}/${turn_label}.decision.json"
  apply_label="$(printf '%02d-apply' "$((idx + 1))")"
  send_turn "${apply_label}" "" "${decision_json}"
  idx=$((idx + 1))
done

write_report "${conversation_id}" "${chain_id}" "${expected_state}" false
printf '%s\n' "${chain_id}" >"${run_dir}/patched-chain-id.txt"
echo "INFO: retainCatalogChain=true — leaving chain ${chain_id} in catalog (no teardown)"

if jq -e --arg s "${SCENARIO_ID}" '.[$s].catalog' "${SCENARIOS_FILE}" >/dev/null 2>&1; then
  bash "${SCRIPTS_DIR}/assert-catalog.sh" \
    "${SCENARIOS_FILE}" "${SCENARIO_ID}" "${catalog_url}" "${chain_id}"
fi

bash "${DIR}/assert-patch-run.sh" "${REPORT_PATH}"
