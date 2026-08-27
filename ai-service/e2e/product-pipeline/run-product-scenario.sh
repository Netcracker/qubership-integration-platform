#!/usr/bin/env bash
# Drive one product CREATE scenario to its expected durable terminal state.
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRIPTS_DIR="$(cd "${DIR}/scripts" && pwd)"

SCENARIO_ID=""
REP=""
BASE_URL=""
EVALUATOR_URL=""
REPORT_PATH=""
TRANSPORT="chat"

usage() {
  cat >&2 <<'EOF'
Usage: run-product-scenario.sh \
  --scenario <id> --rep <n> \
  --base-url <url> --evaluator-url <url> \
  --report <path> [--transport chat|a2a]

--transport picks how turns are sent. Both transports drive the same scenario to the same
durable terminal state and assert the same evidence, so a divergence names the transport.
EOF
  exit 2
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --scenario) SCENARIO_ID="${2:?}"; shift 2 ;;
    --rep) REP="${2:?}"; shift 2 ;;
    --base-url) BASE_URL="${2:?}"; shift 2 ;;
    --evaluator-url) EVALUATOR_URL="${2:?}"; shift 2 ;;
    --report) REPORT_PATH="${2:?}"; shift 2 ;;
    --transport) TRANSPORT="${2:?}"; shift 2 ;;
    -h|--help) usage ;;
    *) echo "Unknown option: $1" >&2; usage ;;
  esac
done

[[ -n "${SCENARIO_ID}" && -n "${REP}" && -n "${BASE_URL}" ]] || usage
[[ -n "${EVALUATOR_URL}" && -n "${REPORT_PATH}" ]] || usage
case "${TRANSPORT}" in
  chat|a2a) ;;
  *)
    echo "FAIL: --transport must be chat or a2a" >&2
    exit 2
    ;;
esac
SCENARIOS_FILE="${DIR}/scenarios.json"
[[ -f "${SCENARIOS_FILE}" ]] || { echo "FAIL: missing scenarios.json" >&2; exit 1; }
jq -e --arg s "${SCENARIO_ID}" '.[$s]' "${SCENARIOS_FILE}" >/dev/null \
  || { echo "FAIL: unknown scenario ${SCENARIO_ID}" >&2; exit 1; }

prompt="$(jq -r --arg s "${SCENARIO_ID}" '.[$s].prompt // empty' "${SCENARIOS_FILE}")"
expected_state="$(jq -r --arg s "${SCENARIO_ID}" '.[$s].terminalState // empty' "${SCENARIOS_FILE}")"
expects_approval="$(jq -r --arg s "${SCENARIO_ID}" '.[$s].expectsApproval // true' "${SCENARIOS_FILE}")"
expects_implement="$(jq -r --arg s "${SCENARIO_ID}" '.[$s].expectsImplement // false' "${SCENARIOS_FILE}")"
profile_id="$(jq -r --arg s "${SCENARIO_ID}" '.[$s].profileId // empty' "${SCENARIOS_FILE}")"
profile_version="$(jq -r --arg s "${SCENARIO_ID}" '.[$s].profileVersion // "1"' "${SCENARIOS_FILE}")"
# create-chain@2 design-input choice when WAITING_FOR_INPUT.
# Default GENERATE. Script-only briefs are handled deterministically inside design-input
# (no SERVICE_CALL / APIHub binding invented for HTTP trigger + script flows).
design_input_choice="$(jq -r --arg s "${SCENARIO_ID}" \
  '.[$s].designInputChoice // "Generate full IDS"' "${SCENARIOS_FILE}")"
discovery_answers_json="$(jq -c --arg s "${SCENARIO_ID}" '.[$s].discoveryAnswers // []' "${SCENARIOS_FILE}")"
design_input_answers_json="$(jq -c --arg s "${SCENARIO_ID}" \
  '.[$s].designInputAnswers // []' "${SCENARIOS_FILE}")"
required_json="$(jq -c --arg s "${SCENARIO_ID}" '.[$s].requiredFacts // []' "${SCENARIOS_FILE}")"
forbidden_json="$(jq -c --arg s "${SCENARIO_ID}" '.[$s].forbiddenFacts // []' "${SCENARIOS_FILE}")"
unique_prefix="$(jq -r --arg s "${SCENARIO_ID}" '.[$s].uniqueChainNamePrefix // empty' "${SCENARIOS_FILE}")"
retain_catalog_chain="$(jq -r --arg s "${SCENARIO_ID}" '.[$s].retainCatalogChain // false' "${SCENARIOS_FILE}")"
recovery_fault_stage="$(jq -r --arg s "${SCENARIO_ID}" '.[$s].recovery.faultStage // empty' "${SCENARIOS_FILE}")"
recovery_owner_stage="$(jq -r --arg s "${SCENARIO_ID}" '.[$s].recovery.ownerStage // empty' "${SCENARIOS_FILE}")"
recovery_follow_up="$(jq -r --arg s "${SCENARIO_ID}" '.[$s].recovery.followUp // empty' "${SCENARIOS_FILE}")"
recovery_exhaust_halt="$(jq -r --arg s "${SCENARIO_ID}" '.[$s].recovery.exhaustHalt // false' "${SCENARIOS_FILE}")"
catalog_url="${E2E_CATALOG_URL:-http://localhost:8091}"
pipeline="$(jq -r --arg s "${SCENARIO_ID}" '.[$s].pipeline // empty' "${SCENARIOS_FILE}")"
[[ "${pipeline}" == "create-chain-v1" ]] \
  || { echo "FAIL: scenario ${SCENARIO_ID} pipeline must be create-chain-v1 (got ${pipeline})" >&2; exit 1; }
[[ -n "${profile_id}" && "${profile_id}" == "create-chain" ]] \
  || { echo "FAIL: scenario ${SCENARIO_ID} profileId must be create-chain" >&2; exit 1; }
# Active gate scenarios pin create-chain@2 (new CREATE cutover). Historical @1 coverage
# lives in CreateChainProductPipelineRestartIT and the offline @1 profile contract.
[[ -n "${profile_version}" && "${profile_version}" == "2" ]] \
  || { echo "FAIL: scenario ${SCENARIO_ID} profileVersion must be 2 (new CREATE)" >&2; exit 1; }
if [[ "${recovery_exhaust_halt}" == "true" ]]; then
  [[ -n "${expected_state}" && "${expected_state}" == "WAITING_FOR_INPUT" ]] \
    || { echo "FAIL: scenario ${SCENARIO_ID} exhaustHalt requires terminalState WAITING_FOR_INPUT" >&2; exit 1; }
  [[ "${retain_catalog_chain}" == "false" ]] \
    || { echo "FAIL: scenario ${SCENARIO_ID} exhaustHalt requires retainCatalogChain=false" >&2; exit 1; }
  [[ -n "${recovery_fault_stage}" && -n "${recovery_follow_up}" ]] \
    || { echo "FAIL: scenario ${SCENARIO_ID} exhaustHalt requires recovery.faultStage and recovery.followUp" >&2; exit 1; }
else
  [[ -n "${expected_state}" && "${expected_state}" == "CHAIN_MATERIALIZED" ]] \
    || { echo "FAIL: scenario ${SCENARIO_ID} terminalState must be CHAIN_MATERIALIZED" >&2; exit 1; }
  [[ "${retain_catalog_chain}" == "true" ]] \
    || { echo "FAIL: scenario ${SCENARIO_ID} retainCatalogChain must be true" >&2; exit 1; }
fi
[[ -n "${prompt}" ]] || { echo "FAIL: scenario ${SCENARIO_ID} missing prompt" >&2; exit 1; }
if [[ -n "${recovery_fault_stage}" && "${TRANSPORT}" != "chat" ]]; then
  echo "FAIL: recovery scenario currently requires chat transport for the typed revise decision" >&2
  exit 2
fi


mkdir -p "$(dirname "${REPORT_PATH}")"
run_dir="$(dirname "${REPORT_PATH}")"
run_stamp="$(date -u +%Y%m%dT%H%M%S)"
unique_chain_name=""
if [[ -n "${unique_prefix}" ]]; then
  unique_chain_name="${unique_prefix}.${run_stamp}.r${REP}"
  prompt="${prompt//\{\{UNIQUE_CHAIN_NAME\}\}/${unique_chain_name}}"
  printf '%s\n' "${unique_chain_name}" >"${run_dir}/unique-chain-name.txt"
fi
# Retention is intentional for catalog inspection — never delete created chains here.
printf 'retainCatalogChain=%s uniqueChainName=%s\n' \
  "${retain_catalog_chain}" "${unique_chain_name}" >"${run_dir}/catalog-retention.txt"

if [[ "${PRODUCT_PIPELINE_STUB_MODE:-0}" == "1" ]]; then
  conversation_id="stub-${SCENARIO_ID}-${REP}"
  run_id="${conversation_id}-${profile_id}-${profile_version}"
  stub_facts="$(jq -c 'if length > 0 then . else ["CREATE"] end' <<<"${required_json}")"
  stub_approval_eligible="true"
  stub_validation_verdict="PASS"
  stub_approval_hash="stub-hash"
  stub_kinds='[]'
  stub_chain_id=""
  stub_reconcile="null"
  stub_patches='[]'
  stub_compiler_digest="stub-compiler-pipeline-digest"
  if [[ "${expected_state}" == "PLAN_APPROVED" ]]; then
    stub_kinds='["IMPLEMENTATION_PLAN","PLAN_VALIDATION_RESULT","APPROVAL_RECORD","REQUIREMENT_BRIEF"]'
  elif [[ "${expected_state}" == "WAITING_FOR_APPROVAL" ]]; then
    stub_kinds='["REQUIREMENT_BRIEF","IMPLEMENTATION_PLAN","PLAN_VALIDATION_RESULT"]'
  elif [[ "${expected_state}" == "CHAIN_MATERIALIZED" ]]; then
    stub_kinds='["IMPLEMENTATION_PLAN","PLAN_VALIDATION_RESULT","APPROVAL_RECORD","REQUIREMENT_BRIEF","GRAPH_PATCH_ARTIFACT","MATERIALIZATION_RESULT","CATALOG_CHAIN_SNAPSHOT","RECONCILE_RESULT"]'
    stub_chain_id="stub-chain-${SCENARIO_ID}"
    stub_reconcile="true"
    stub_patches='[{"ownerCapabilityId":"cip-script-generator","applicability":"APPLICABLE","baseGraphDigest":"base-stub","resultGraphDigest":"result-stub","operationCount":1}]'
  elif [[ "${expected_state}" == "WAITING_FOR_INPUT" ]]; then
    stub_kinds='["REQUIREMENT_BRIEF","IMPLEMENTATION_PLAN","PLAN_VALIDATION_RESULT","APPROVAL_RECORD"]'
    stub_approval_eligible="true"
    stub_validation_verdict="PASS"
    stub_approval_hash="stub-hash"
  elif [[ "${expected_state}" == "FAILED" ]]; then
    stub_kinds='["REQUIREMENT_BRIEF","PLAN_VALIDATION_RESULT"]'
    stub_approval_eligible="false"
    stub_validation_verdict="FAIL"
    stub_approval_hash=""
    stub_facts="$(jq -c '. + ["VALIDATION_FAILURE","else.condition"] | unique' <<<"${stub_facts}")"
  fi
  stub_halt_gate=""
  stub_halt_guard=""
  stub_halt_prompt=""
  stub_halt_actions='[]'
  if [[ "${recovery_exhaust_halt}" == "true" ]]; then
    stub_halt_gate="stage-escalated"
    stub_halt_guard="NAMED_STAGE_OUTSIDE_CANDIDATE_SET"
    stub_halt_prompt="That stage is not a candidate for this defect."
    stub_halt_actions='["stop-with-report"]'
  fi
  cat >"${REPORT_PATH}" <<EOF
{
  "scenarioId": "${SCENARIO_ID}",
  "rep": ${REP},
  "conversationId": "${conversation_id}",
  "runId": "${run_id}",
  "runtimeMode": "product",
  "profileId": "${profile_id}",
  "profileVersion": "${profile_version}",
  "knowledgePackage": {
    "packageKey": "stub@1.0.0",
    "knowledgeVersion": "1.0.0",
    "schemaVersion": "1.0.0",
    "packageChecksum": "sha256:stub-package",
    "certificationStatus": "CERTIFIED",
    "certificationDigest": "sha256:stub-certificate"
  },
  "knowledgeContext": {
    "packageChecksum": "sha256:stub-package",
    "objectIds": ["CIP:GEN-000049"],
    "contentChars": 120
  },
  "materializedElementTypes": $(
    if [[ "${SCENARIO_ID}" == "product-create-chain-error-handling" ]]; then
      echo '["catch-2","try-2","try-catch-finally-2"]'
    else
      echo '[]'
    fi
  ),
  "validationVerdict": "${stub_validation_verdict}",
  "approvalTargetHash": "${stub_approval_hash}",
  "approvedPlanContentHash": "${stub_approval_hash}",
  "compilerPackageDigest": "${stub_compiler_digest}",
  "pipelineIndexDigest": "${stub_compiler_digest}",
  "resolvedDagDigest": "stub-dag-digest",
  "compilerPipelineDigest": "${stub_compiler_digest}",
  "materializedChainId": "${stub_chain_id}",
  "reconcileMatches": ${stub_reconcile},
  "graphPatchArtifacts": ${stub_patches},
  "terminalState": "${expected_state}",
  "expectedTerminalState": "${expected_state}",
  "approvalEligible": ${stub_approval_eligible},
  "requiredFacts": ${required_json},
  "forbiddenFacts": ${forbidden_json},
  "missingRequiredFacts": [],
  "presentForbiddenFacts": [],
  "committedArtifactKinds": ${stub_kinds},
  "hasGeneratedBundle": false,
  "hasPublicationReceipt": false,
  "hasCatalogMutation": false,
  "hasDeploymentArtifact": false,
  "decodedPlan": {"goal": "stub", "steps": []},
  "requirementFacts": ${stub_facts},
  "validationFindings": $(
    if [[ "${expected_state}" == "FAILED" ]]; then
      echo '[{"code":"EXCLUSION","message":"else.condition","blocker":true}]'
    else
      echo '[]'
    fi
  ),
  "prompt": $(jq -Rn --arg p "${prompt}" '$p'),
  "haltGate": "${stub_halt_gate}",
  "haltGuard": "${stub_halt_guard}",
  "haltPrompt": $(jq -Rn --arg p "${stub_halt_prompt}" '$p'),
  "haltActions": ${stub_halt_actions},
  "stub": true
}
EOF
  jq '{conversationId, runId, currentState: .terminalState, committedArtifactKinds, stub: true}' \
    "${REPORT_PATH}" >"${run_dir}/evidence.json"

  if [[ "${expected_state}" == "PLAN_APPROVED" || "${expected_state}" == "CHAIN_MATERIALIZED" ]]; then
    scores_path="${run_dir}/semantic-scores.json"
    python3 "${DIR}/evaluate-plan.py" \
      --evaluator-url "${EVALUATOR_URL}" \
      --report "${REPORT_PATH}" \
      --out "${scores_path}" || true
  fi

  report_pv="$(jq -r '.profileVersion // empty' "${REPORT_PATH}")"
  [[ "${report_pv}" == "${profile_version}" ]] \
    || { echo "FAIL: report profileVersion=${report_pv} expected ${profile_version}" >&2; exit 1; }
  bash "${DIR}/assert-product-run.sh" "${REPORT_PATH}"
  exit 0
fi

# Live path: drive POST /api/v1/chat (SSE) and durable evidence endpoint.
command -v curl >/dev/null
command -v jq >/dev/null
[[ -f "${SCRIPTS_DIR}/chat-turn.sh" ]] || {
  echo "FAIL: missing ${SCRIPTS_DIR}/chat-turn.sh" >&2
  exit 1
}
# shellcheck source=scripts/lib.sh
source "${SCRIPTS_DIR}/lib.sh"

export E2E_BASE_URL="${BASE_URL}"
conversation_id="-"
sse_dir="${run_dir}/sse"
mkdir -p "${sse_dir}"
implement_sent=0
design_input_sent=0
recovery_follow_up_sent=0
recovery_revise_sent=0
recovery_reopened_approval_seen=0
recovery_pre_materialization_clean=0

last_a2a_response=""

send_turn() {
  local label="$1"
  local text="$2"
  local structured="${3:-}"
  local decision="${4:-}"
  if [[ "${TRANSPORT}" == "a2a" ]]; then
    local out_json="${sse_dir}/${label}.json"
    conversation_id="$(
      bash "${SCRIPTS_DIR}/a2a-turn.sh" \
        "${BASE_URL}" "${conversation_id}" "${text}" "${out_json}" "${structured}"
    )"
    last_a2a_response="${out_json}"
  else
    local out_sse="${sse_dir}/${label}.sse"
    conversation_id="$(
      bash "${SCRIPTS_DIR}/chat-turn.sh" \
        "${BASE_URL}" "${conversation_id}" "${text}" "${out_sse}" "" "${decision}"
    )"
    if [[ "${recovery_exhaust_halt}" == "true" ]]; then
      e2e_assert_observable_sse_output "${out_sse}" "${label}"
    fi
  fi
  [[ -n "${conversation_id}" && "${conversation_id}" != "-" ]] \
    || { echo "FAIL: turn did not return conversationId" >&2; exit 1; }
}

# Reads the approve descriptor the Task advertises, so the runner approves the exact revision it
# was shown rather than a revision it guessed.
#
# It reads the current Task rather than the last response: message:send can return while the Task
# is still WORKING, and that response carries no pending action.
a2a_pending_approve() {
  local snapshot="${sse_dir}/task-$(date +%s).json"
  curl -fsS --max-time 30 \
    -H 'A2A-Version: 1.0' \
    "${BASE_URL}/tasks/${conversation_id}" >"${snapshot}" 2>/dev/null || return 1
  python3 - "${snapshot}" <<'PY'
import json
import sys

document = json.load(open(sys.argv[1]))
task = document.get("task") or document
parts = ((task.get("status") or {}).get("message") or {}).get("parts") or []
for part in parts:
    data = part.get("data")
    if isinstance(data, dict) and data.get("action") == "approve":
        print(json.dumps({
            "action": "approve",
            "artifactType": data.get("artifactType"),
            "artifactHash": data.get("artifactHash"),
            "revision": data.get("revision"),
        }))
        break
PY
}

send_turn "01-prompt" "${prompt}"

# Overall poll cap (was hard-coded 600s). Stuck planning without plan artifacts
# aborts earlier via PRODUCT_PIPELINE_PLANNING_STALL_SEC.
: "${PRODUCT_PIPELINE_POLL_TIMEOUT_SEC:=300}"
: "${PRODUCT_PIPELINE_PLANNING_STALL_SEC:=180}"
deadline=$((SECONDS + PRODUCT_PIPELINE_POLL_TIMEOUT_SEC))
current_state=""
evidence=""
discovery_continues=0
planning_stall_since=""
planning_stall_fingerprint=""
while (( SECONDS < deadline )); do
  evidence="$(curl -fsS "${BASE_URL}/api/v1/chat/conversations/${conversation_id}/product-pipeline" || true)"
  current_state="$(jq -r '.currentState // empty' <<<"${evidence}")"
  latest_wait_reason="$(jq -r '
    [.transitions[]? | select(.toStatus == "WAITING_FOR_INPUT") | .reason // ""]
    | last // empty
  ' <<<"${evidence}")"
  recovery_fault_injected="$(jq -r '
    if any(.attempts[]?; ((.failureEvidence // "") | contains("E2E recovery fault")))
    then "1" else "0" end
  ' <<<"${evidence}")"
  if [[ "${recovery_exhaust_halt}" == "true" \
      && "${current_state}" == "WAITING_FOR_INPUT" \
      && "${latest_wait_reason}" == *"__GATE:stage-escalated__"* ]]; then
    break
  fi
  if [[ "${recovery_exhaust_halt}" != "true" && "${current_state}" == "${expected_state}" ]]; then
    break
  fi
  if [[ "${current_state}" == "FAILED" || "${current_state}" == "CANCELLED" ]]; then
    echo "FAIL: durable state=${current_state} before expected ${expected_state}" >&2
    break
  fi

  # Abort when planning stays RUNNING with no IMPLEMENTATION_PLAN /
  # PLAN_VALIDATION_RESULT and no revision/stage progress for N seconds.
  stage_id="$(jq -r '.attempts[-1].stageId // empty' <<<"${evidence}")"
  attempt_outcome="$(jq -r '.attempts[-1].outcome // empty' <<<"${evidence}")"
  run_revision="$(jq -r '.runRevision // empty' <<<"${evidence}")"
  has_plan_kind="$(jq -r '
    ((.committedArtifactKinds // []) | map(select(. == "IMPLEMENTATION_PLAN" or . == "PLAN_VALIDATION_RESULT")) | length) as $n
    | if $n > 0 then "1" else "0" end
  ' <<<"${evidence}")"
  if [[ "${current_state}" == "RUNNING" \
      && ("${stage_id}" == "planning" || "${stage_id}" == "design-planning") \
      && "${attempt_outcome}" == "RUNNING" \
      && "${has_plan_kind}" == "0" ]]; then
    fingerprint="${run_revision}|${stage_id}|${attempt_outcome}"
    if [[ "${fingerprint}" != "${planning_stall_fingerprint}" ]]; then
      planning_stall_fingerprint="${fingerprint}"
      planning_stall_since="${SECONDS}"
    elif [[ -n "${planning_stall_since}" ]] \
        && (( SECONDS - planning_stall_since >= PRODUCT_PIPELINE_PLANNING_STALL_SEC )); then
      echo "FAIL: planning stall: RUNNING without IMPLEMENTATION_PLAN/PLAN_VALIDATION_RESULT for ${PRODUCT_PIPELINE_PLANNING_STALL_SEC}s (runRevision=${run_revision})" >&2
      break
    fi
  else
    planning_stall_since=""
    planning_stall_fingerprint=""
  fi

  if [[ "${current_state}" == "WAITING_FOR_APPROVAL" && "${expects_approval}" == "true" ]]; then
    if [[ -n "${recovery_fault_stage}" && "${stage_id}" == "${recovery_owner_stage}" \
        && "${recovery_revise_sent}" -eq 1 ]]; then
      recovery_reopened_approval_seen=1
    fi
    if [[ -n "${recovery_fault_stage}" && "${recovery_fault_injected}" == "1" ]]; then
      # Automatic reopen returns to the owner for approval. Reset Implement so the next
      # execution can park after the owner-already-reopened guard.
      implement_sent=0
    fi
    if [[ "${TRANSPORT}" == "a2a" ]]; then
      approve_data="$(a2a_pending_approve || true)"
      if [[ -z "${approve_data}" ]]; then
        # The durable run flips to WAITING_FOR_APPROVAL before the transport persists the
        # input-required snapshot. Let the next poll find the descriptor.
        sleep 2
        continue
      fi
      send_turn "agree-$(date +%s)" "" "${approve_data}"
    else
      send_turn "agree-$(date +%s)" "Agree"
    fi
  elif [[ "${current_state}" == "WAITING_FOR_IMPLEMENT" && "${expects_implement}" == "true" \
      && "${TRANSPORT}" != "a2a" ]]; then
    if [[ "${implement_sent}" -eq 0 ]]; then
      approved_hash="$(jq -r '
        ([.attempts[]?
          | select(.stageId == "design-planning" and .outcome == "SUCCEEDED")
          | .outputs[]?
          | select(.kind == "IMPLEMENTATION_PLAN")
          | .contentHash][-1] // empty)
      ' <<<"${evidence}")"
      [[ -n "${approved_hash}" && "${approved_hash}" != "null" ]] \
        || { echo "FAIL: WAITING_FOR_IMPLEMENT missing approved plan hash" >&2; break; }
      [[ -n "${run_revision}" && "${run_revision}" != "null" ]] \
        || { echo "FAIL: WAITING_FOR_IMPLEMENT missing runRevision" >&2; break; }
      # Free-form "Implement" is not a creation command. Name the approved plan on the card.
      implement_decision="$(jq -nc \
        --arg hash "${approved_hash}" \
        --argjson revision "${run_revision}" \
        '{action:"create-chain",artifactType:"implementation-plan",artifactHash:$hash,revision:$revision}')"
      send_turn "implement-$(date +%s)" "Implement ${approved_hash}" "" "${implement_decision}"
      implement_sent=1
    fi
  elif [[ "${current_state}" == "WAITING_FOR_INPUT" && -n "${recovery_fault_stage}" \
      && ( "${stage_id}" == "${recovery_fault_stage}" \
        || ( "${recovery_exhaust_halt}" == "true" && "${recovery_fault_injected}" == "1" ) ) ]]; then
    if [[ "${recovery_follow_up_sent}" -eq 0 ]]; then
      if jq -e '
        ((.committedArtifactKinds // [])
          | any(. == "MATERIALIZATION_REQUEST" or . == "MATERIALIZATION_RESULT" or . == "CATALOG_CHAIN_SNAPSHOT"))
        or ((.attempts // []) | any(.stageId == "materialization"))
      ' <<<"${evidence}" >/dev/null; then
        echo "FAIL: recovery fault occurred after materialization had already started" >&2
        break
      fi
      recovery_pre_materialization_clean=1
      send_turn "recovery-follow-up-$(date +%s)" "${recovery_follow_up}"
      recovery_follow_up_sent=1
    elif [[ "${recovery_exhaust_halt}" != "true" && "${recovery_revise_sent}" -eq 0 ]]; then
      [[ -n "${run_revision}" && "${run_revision}" != "null" ]] \
        || { echo "FAIL: recovery decision missing runRevision" >&2; break; }
      recovery_decision="$(jq -nc --argjson revision "${run_revision}" \
        '{action:"revise",revision:$revision}')"
      send_turn "recovery-revise-$(date +%s)" "" "" "${recovery_decision}"
      recovery_revise_sent=1
      implement_sent=0
    fi
  elif [[ "${current_state}" == "WAITING_FOR_INPUT" \
      && ( "${expected_state}" != "WAITING_FOR_INPUT" || "${recovery_exhaust_halt}" == "true" ) ]]; then
    # create-chain@2 design-input asks for Generate full IDS vs Derive minimal IDS.
    if [[ "${stage_id}" == "design-input" ]]; then
      # design-input asks the IDS question first and can follow up, for example for data-mapping
      # intents. Answer the follow-ups from designInputAnswers rather than calling a second
      # question a failure.
      #
      # The durable state says WAITING_FOR_INPUT for both a question and an approval, so on A2A ask
      # the Task what it actually wants. Answering an approval prompt with prose burns a scripted
      # answer and leaves the stage waiting.
      if [[ "${TRANSPORT}" == "a2a" ]]; then
        approve_data="$(a2a_pending_approve || true)"
        if [[ -n "${approve_data}" ]]; then
          send_turn "design-input-approve-$(date +%s)" "" "${approve_data}"
          sleep 2
          continue
        fi
      fi
      if [[ "${design_input_sent}" -eq 0 ]]; then
        send_turn "design-input-$(date +%s)" "${design_input_choice}"
        design_input_sent=1
      else
        follow_up="$(jq -nr --argjson idx "$((design_input_sent - 1))" \
          --argjson answers "${design_input_answers_json}" '$answers[$idx] // empty')"
        if [[ -z "${follow_up}" ]]; then
          echo "FAIL: design-input asked again and designInputAnswers is exhausted for ${SCENARIO_ID}" >&2
          break
        fi
        send_turn "design-input-$(date +%s)" "${follow_up}"
        design_input_sent=$((design_input_sent + 1))
      fi
    else
      discovery_answer="$(jq -nr --argjson idx "${discovery_continues}" \
        --argjson answers "${discovery_answers_json}" '$answers[$idx] // empty')"
      if [[ -z "${discovery_answer}" ]]; then
        echo "FAIL: discovery still WAITING_FOR_INPUT; add discoveryAnswers to scenarios.json for ${SCENARIO_ID}" >&2
        break
      fi
      discovery_continues=$((discovery_continues + 1))
      send_turn "continue-${discovery_continues}" "${discovery_answer}"
    fi
  fi
  sleep 2
done

evidence="$(curl -fsS "${BASE_URL}/api/v1/chat/conversations/${conversation_id}/product-pipeline")"
printf '%s\n' "${evidence}" >"${run_dir}/evidence.json"

if [[ -n "${recovery_fault_stage}" ]]; then
  if [[ "${recovery_exhaust_halt}" == "true" ]]; then
    [[ "${recovery_follow_up_sent}" -eq 1 && "${recovery_pre_materialization_clean}" -eq 1 ]] || {
      echo "FAIL: exhausted-halt recovery did not send the follow-up before materialization" >&2
      exit 1
    }
    jq -e '
      any(.attempts[]?; ((.failureEvidence // "") | contains("E2E recovery fault")))
      and (([.transitions[]? | select(.toStatus == "WAITING_FOR_INPUT") | .reason // ""] | last // "")
        | contains("__GATE:stage-escalated__"))
      and ((["REQUIREMENT_BRIEF", "IMPLEMENTATION_PLAN"]
        - (.committedArtifactKinds // [])) | length == 0)
      and ((.committedArtifactKinds // [])
        | any(. == "MATERIALIZATION_RESULT" or . == "CATALOG_CHAIN_SNAPSHOT")
        | not)
    ' <<<"${evidence}" >/dev/null || {
      echo "FAIL: final evidence does not prove an exhausted halt with the approved brief and plan" >&2
      exit 1
    }
    jq --arg fault "${recovery_fault_stage}" --arg owner "${recovery_owner_stage}" '{
      exhaustHalt: true,
      faultStage: $fault,
      ownerStage: $owner,
      haltReason: ([.transitions[]? | select(.toStatus == "WAITING_FOR_INPUT") | .reason // ""] | last // ""),
      committedArtifactKinds
    }' <<<"${evidence}" >"${run_dir}/recovery.json"
  else
    [[ "${recovery_follow_up_sent}" -eq 1 && "${recovery_revise_sent}" -eq 1 \
        && "${recovery_reopened_approval_seen}" -eq 1 \
        && "${recovery_pre_materialization_clean}" -eq 1 ]] || {
      echo "FAIL: recovery flow did not complete follow-up, revise, reopen approval, and pre-materialization checks" >&2
      exit 1
    }
    jq -e --arg fault "${recovery_fault_stage}" --arg owner "${recovery_owner_stage}" '
      any(.attempts[]?; .stageId == $fault and ((.failureEvidence // "") | contains("E2E recovery fault")))
      and any(.transitions[]?; ((.reason // "") | startswith("causal reopen of " + $owner)))
      and ([.attempts[]?.outputs[]? | select(.kind == "IMPLEMENTATION_PLAN") | .contentHash] | unique | length >= 2)
      and ((["MATERIALIZATION_RESULT", "CATALOG_CHAIN_SNAPSHOT", "RECONCILE_RESULT"]
        - (.committedArtifactKinds // [])) | length == 0)
    ' <<<"${evidence}" >/dev/null || {
      echo "FAIL: final evidence does not prove causal plan repair and materialization" >&2
      exit 1
    }
    jq --arg fault "${recovery_fault_stage}" --arg owner "${recovery_owner_stage}" '{
      faultStage: $fault,
      ownerStage: $owner,
      planContentHashes: ([.attempts[]?.outputs[]? | select(.kind == "IMPLEMENTATION_PLAN") | .contentHash] | unique),
      causalReopens: [.transitions[]? | select((.reason // "") | startswith("causal reopen of " + $owner))],
      materializedChainId,
      reconcileMatches
    }' <<<"${evidence}" >"${run_dir}/recovery.json"
  fi
fi

python3 "${DIR}/build-report-from-evidence.py" \
  --evidence "${run_dir}/evidence.json" \
  --scenario-id "${SCENARIO_ID}" \
  --rep "${REP}" \
  --required-facts "${required_json}" \
  --forbidden-facts "${forbidden_json}" \
  --expected-terminal-state "${expected_state}" \
  --expects-approval "${expects_approval}" \
  --out "${REPORT_PATH}"

current_state="$(jq -r '.terminalState // empty' "${REPORT_PATH}")"
if [[ "${current_state}" == "PLAN_APPROVED" || "${current_state}" == "CHAIN_MATERIALIZED" ]]; then
  if [[ "${current_state}" == "CHAIN_MATERIALIZED" ]]; then
    chain_id="$(jq -r '.materializedChainId // empty' "${REPORT_PATH}")"
    reconcile="$(jq -r '.reconcileMatches // empty' "${REPORT_PATH}")"
    [[ -n "${chain_id}" ]] || { echo "FAIL: blank materializedChainId" >&2; exit 1; }
    [[ "${reconcile}" == "true" ]] || { echo "FAIL: reconcileMatches must be true" >&2; exit 1; }
    printf '%s\n' "${chain_id}" >"${run_dir}/materialized-chain-id.txt"
    # Prove the chain exists in runtime-catalog. Do not delete — retention is intentional.
    catalog_json="$(mktemp)"
    if curl -fsS "${catalog_url}/v1/chains/${chain_id}" >"${catalog_json}"; then
      catalog_name="$(jq -r '.name // .content.name // empty' "${catalog_json}")"
      printf 'catalogChainId=%s catalogChainName=%s retained=true\n' \
        "${chain_id}" "${catalog_name}" | tee "${run_dir}/catalog-chain.txt"
      if [[ "${retain_catalog_chain}" == "true" ]]; then
        echo "INFO: retainCatalogChain=true — leaving chain ${chain_id} in catalog (no teardown)"
      fi
      if [[ -f "${SCENARIOS_FILE}" ]] \
          && jq -e --arg s "${SCENARIO_ID}" '.[$s].catalog' "${SCENARIOS_FILE}" >/dev/null 2>&1; then
        bash "${SCRIPTS_DIR}/assert-catalog.sh" \
          "${SCENARIOS_FILE}" "${SCENARIO_ID}" "${catalog_url}" "${chain_id}"
      fi
    else
      echo "FAIL: catalog GET /v1/chains/${chain_id} failed" >&2
      rm -f "${catalog_json}"
      exit 1
    fi
    rm -f "${catalog_json}"
  fi
  set +e
  python3 "${DIR}/evaluate-plan.py" \
    --evaluator-url "${EVALUATOR_URL}" \
    --report "${REPORT_PATH}" \
    --out "${run_dir}/semantic-scores.json"
  eval_rc=$?
  set -e
  if [[ "${eval_rc}" -ne 0 ]]; then
    if [[ "${current_state}" == "CHAIN_MATERIALIZED" && "${retain_catalog_chain}" == "true" ]]; then
      echo "WARN: evaluator failed after CHAIN_MATERIALIZED + catalog retention; continuing to assert durable success" >&2
    else
      exit "${eval_rc}"
    fi
  fi
fi

report_pv="$(jq -r '.profileVersion // empty' "${REPORT_PATH}")"
[[ "${report_pv}" == "${profile_version}" ]] \
  || { echo "FAIL: report profileVersion=${report_pv} expected ${profile_version}" >&2; exit 1; }
bash "${DIR}/assert-product-run.sh" "${REPORT_PATH}"
