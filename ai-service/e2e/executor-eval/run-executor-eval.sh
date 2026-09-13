#!/usr/bin/env bash
# Replay saved planner responses through the production executor and compiler DAG.
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CASES_FILE="${DIR}/cases.json"
BASE_URL="${BASE_URL:-http://localhost:8094}"
RUNS=""
REPORT_DIR=""
SELECTED_CASE=""
TIMEOUT_SEC="${EXECUTOR_EVAL_TIMEOUT_SEC:-1800}"

usage() {
  cat >&2 <<'EOF'
Usage: run-executor-eval.sh \
  --runs <positive integer> \
  --report-dir <directory> \
  [--base-url <URL>] \
  [--case <id>]
EOF
  exit 2
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --runs) RUNS="${2:?}"; shift 2 ;;
    --report-dir) REPORT_DIR="${2:?}"; shift 2 ;;
    --base-url) BASE_URL="${2:?}"; shift 2 ;;
    --case) SELECTED_CASE="${2:?}"; shift 2 ;;
    -h|--help) usage ;;
    *) echo "Unknown option: $1" >&2; usage ;;
  esac
done

[[ -n "${RUNS}" && -n "${REPORT_DIR}" ]] || usage
[[ "${RUNS}" =~ ^[1-9][0-9]*$ ]] || {
  echo "FAIL: --runs must be a positive integer" >&2
  exit 2
}
command -v curl >/dev/null
command -v jq >/dev/null
jq -e '.cases | type == "array" and length > 0' "${CASES_FILE}" >/dev/null

if [[ -n "${SELECTED_CASE}" ]]; then
  jq -e --arg id "${SELECTED_CASE}" '.cases | any(.id == $id)' "${CASES_FILE}" >/dev/null \
    || { echo "FAIL: unknown executor case ${SELECTED_CASE}" >&2; exit 2; }
fi

mkdir -p "${REPORT_DIR}/runs"
results_file="${REPORT_DIR}/results.jsonl"
: >"${results_file}"

case_ids() {
  if [[ -n "${SELECTED_CASE}" ]]; then
    printf '%s\n' "${SELECTED_CASE}"
  else
    jq -r '.cases[].id' "${CASES_FILE}"
  fi
}

while IFS= read -r case_id; do
  input_relative="$(jq -r --arg id "${case_id}" '.cases[] | select(.id == $id) | .inputFile' "${CASES_FILE}")"
  planner_relative="$(jq -r --arg id "${case_id}" '.cases[] | select(.id == $id) | .plannerResponseFile' "${CASES_FILE}")"
  required_skills="$(jq -c --arg id "${case_id}" '.cases[] | select(.id == $id) | .requiredPlannedSkills' "${CASES_FILE}")"
  required_executed="$(jq -c --arg id "${case_id}" '.cases[] | select(.id == $id) | .requiredExecutedSkills' "${CASES_FILE}")"
  required_types="$(jq -c --arg id "${case_id}" '.cases[] | select(.id == $id) | .requiredGraphTypes' "${CASES_FILE}")"
  minimum_types="$(jq -c --arg id "${case_id}" '.cases[] | select(.id == $id) | .minimumGraphTypeOccurrences // []' "${CASES_FILE}")"
  required_mappings="$(jq -c --arg id "${case_id}" '.cases[] | select(.id == $id) | .requiredMappingIntentIds // []' "${CASES_FILE}")"
  expected_bindings="$(jq -c --arg id "${case_id}" '.cases[] | select(.id == $id) | .expectedCatalogBindings // []' "${CASES_FILE}")"
  input_path="${DIR}/${input_relative}"
  planner_path="${DIR}/${planner_relative}"
  [[ -f "${input_path}" ]] || { echo "FAIL: missing executor input ${input_path}" >&2; exit 1; }
  [[ -f "${planner_path}" ]] || { echo "FAIL: missing planner response ${planner_path}" >&2; exit 1; }

  rep=1
  while [[ "${rep}" -le "${RUNS}" ]]; do
    run_dir="${REPORT_DIR}/runs/${case_id}/rep-${rep}"
    mkdir -p "${run_dir}"
    request_path="${run_dir}/request.json"
    response_path="${run_dir}/response.json"
    conversation_id="executor-eval-${case_id}-${rep}-$(date +%s)-$$"

    jq \
      --arg conversationId "${conversation_id}" \
      --rawfile plannerResponse "${planner_path}" \
      '. + {conversationId:$conversationId,plannerResponse:$plannerResponse}' \
      "${input_path}" >"${request_path}"

    echo "==> executor case=${case_id} rep=${rep}" >&2
    set +e
    curl -fsS --max-time "${TIMEOUT_SEC}" \
      -H 'Content-Type: application/json' \
      --data-binary "@${request_path}" \
      "${BASE_URL}/api/v1/harness/executor-run" >"${response_path}"
    curl_rc=$?
    set -e

    if [[ "${curl_rc}" -ne 0 ]] || ! jq -e 'type == "object"' "${response_path}" >/dev/null 2>&1; then
      jq -n --arg message "executor request failed with curl exit code ${curl_rc}" \
        '{status:"TRANSPORT_ERROR",message:$message}' >"${response_path}"
    fi

    evaluation="$(jq -c \
      --argjson requiredSkills "${required_skills}" \
      --argjson requiredExecuted "${required_executed}" \
      --argjson requiredTypes "${required_types}" \
      --argjson minimumTypes "${minimum_types}" \
      --argjson requiredMappings "${required_mappings}" \
      --argjson expectedBindings "${expected_bindings}" '
        def prop($node; $key):
          ([$node.properties[]? | select(.key == $key) | .value][0] // null);
        . as $response
        | [$requiredSkills[] as $skill
            | select(($response.plannedSkillIds // [] | index($skill)) == null)
            | $skill] as $missingPlanned
        | [$requiredExecuted[] as $skill
            | select(($response.executedSkillIds // [] | index($skill)) == null)
            | $skill] as $missingExecuted
        | [$requiredTypes[] as $type
            | select(([$response.graph.nodes[]? | select(.type == $type)] | length) == 0)
            | $type] as $missingTypes
        | [$minimumTypes[] as $assertion
            | ([$response.graph.nodes[]? | select(.type == $assertion.type)] | length) as $actual
            | select($actual < $assertion.count)
            | {type:$assertion.type,expectedAtLeast:$assertion.count,actual:$actual}]
          as $insufficientTypes
        | [$requiredMappings[] as $mappingId
            | select(([$response.graph.nodes[]?.properties[]?
                | select(.key == "mappingIntentId" and .value == $mappingId)] | length) == 0)
            | $mappingId] as $missingMappings
        | [$expectedBindings[] as $expected
            | ([$response.graph.nodes[]? | select(.nodeId == $expected.nodeId)][0] // {}) as $node
            | {
                nodeId: $expected.nodeId,
                expectedServiceCallId: $expected.serviceCallId,
                actualServiceCallId: prop($node; "serviceCallId"),
                expectedOperationId: $expected.operationId,
                actualOperationId: prop($node; "integrationOperationId")
              }
            | select(.actualServiceCallId != .expectedServiceCallId
                or .actualOperationId != .expectedOperationId)] as $bindingMismatches
        | {
            passed: ($response.status == "COMPLETED"
              and $response.plannerInvoked == false
              and ($missingPlanned | length) == 0
              and ($missingExecuted | length) == 0
              and ($missingTypes | length) == 0
              and ($insufficientTypes | length) == 0
              and ($missingMappings | length) == 0
              and ($bindingMismatches | length) == 0
              and ($response.validationBundle.passes // [] | all(.result.valid == true))),
            serviceStatus: ($response.status // "MISSING"),
            plannerInvoked: (if $response | has("plannerInvoked") then $response.plannerInvoked else null end),
            missingPlannedSkills: $missingPlanned,
            missingExecutedSkills: $missingExecuted,
            missingGraphTypes: $missingTypes,
            insufficientGraphTypes: $insufficientTypes,
            missingMappingIntentIds: $missingMappings,
            bindingMismatches: $bindingMismatches,
            durationMillis: ($response.durationMillis // null),
            modelName: ($response.modelName // null),
            message: ($response.message // null)
          }
      ' "${response_path}")"

    jq -nc \
      --arg caseId "${case_id}" \
      --argjson repetition "${rep}" \
      --arg request "${request_path}" \
      --arg response "${response_path}" \
      --argjson evaluation "${evaluation}" \
      '{caseId:$caseId,repetition:$repetition,request:$request,response:$response} + $evaluation' \
      >>"${results_file}"
    rep=$((rep + 1))
  done
done < <(case_ids)

jq -s '
  . as $results
  | {
      totalRuns: ($results | length),
      passedRuns: ([$results[] | select(.passed)] | length),
      failedRuns: ([$results[] | select(.passed | not)] | length),
      plannerCalls: ([$results[] | select(.plannerInvoked == true)] | length),
      verdict: (if all($results[]; .passed) then "PASS" else "FAIL" end),
      results: $results
    }
' "${results_file}" >"${REPORT_DIR}/summary.json"

jq . "${REPORT_DIR}/summary.json"
[[ "$(jq -r '.verdict' "${REPORT_DIR}/summary.json")" == "PASS" ]]
