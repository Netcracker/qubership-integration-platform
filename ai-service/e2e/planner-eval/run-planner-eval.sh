#!/usr/bin/env bash
# Run isolated design-planner evaluations and retain every request and response.
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CASES_FILE="${DIR}/cases.json"
BASE_URL="${BASE_URL:-http://localhost:8094}"
RUNS=""
REPORT_DIR=""
SELECTED_CASE=""
TIMEOUT_SEC="${PLANNER_EVAL_TIMEOUT_SEC:-900}"

usage() {
  cat >&2 <<'EOF'
Usage: run-planner-eval.sh \
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
    || { echo "FAIL: unknown planner case ${SELECTED_CASE}" >&2; exit 2; }
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
  input_path="${DIR}/${input_relative}"
  repair_relative="$(
    jq -r --arg id "${case_id}" \
      '.cases[] | select(.id == $id) | .repairEvidenceFile // empty' "${CASES_FILE}"
  )"
  required_patterns="$(
    jq -c --arg id "${case_id}" \
      '.defaultRequiredPatterns + (.cases[] | select(.id == $id) | .requiredPatterns)' \
      "${CASES_FILE}"
  )"
  forbidden_patterns="$(
    jq -c --arg id "${case_id}" \
      '.cases[] | select(.id == $id) | .forbiddenPatterns' "${CASES_FILE}"
  )"
  [[ -f "${input_path}" ]] || { echo "FAIL: missing planner input ${input_path}" >&2; exit 1; }

  rep=1
  while [[ "${rep}" -le "${RUNS}" ]]; do
    run_dir="${REPORT_DIR}/runs/${case_id}/rep-${rep}"
    mkdir -p "${run_dir}"
    request_path="${run_dir}/request.json"
    response_path="${run_dir}/response.json"
    conversation_id="planner-eval-${case_id}-${rep}-$(date +%s)-$$"

    if [[ -n "${repair_relative}" ]]; then
      repair_path="${DIR}/${repair_relative}"
      [[ -f "${repair_path}" ]] || { echo "FAIL: missing repair evidence ${repair_path}" >&2; exit 1; }
      jq -n \
        --arg conversationId "${conversation_id}" \
        --rawfile input "${input_path}" \
        --rawfile repairEvidenceText "${repair_path}" \
        '{conversationId:$conversationId,input:$input,repairEvidenceText:$repairEvidenceText}' \
        >"${request_path}"
    else
      jq -n \
        --arg conversationId "${conversation_id}" \
        --rawfile input "${input_path}" \
        '{conversationId:$conversationId,input:$input,repairEvidenceText:""}' \
        >"${request_path}"
    fi

    echo "==> planner case=${case_id} rep=${rep}" >&2
    set +e
    curl -fsS --max-time "${TIMEOUT_SEC}" \
      -H 'Content-Type: application/json' \
      --data-binary "@${request_path}" \
      "${BASE_URL}/api/v1/harness/planner-run" >"${response_path}"
    curl_rc=$?
    set -e

    if [[ "${curl_rc}" -ne 0 ]] || ! jq -e 'type == "object"' "${response_path}" >/dev/null 2>&1; then
      jq -n --arg message "planner request failed with curl exit code ${curl_rc}" \
        '{status:"TRANSPORT_ERROR",message:$message,attempts:[]}' >"${response_path}"
    fi

    evaluation="$(jq -c \
      --argjson required "${required_patterns}" \
      --argjson forbidden "${forbidden_patterns}" '
        . as $response
        | ($response.message // "") as $message
        | [$required[] as $pattern
            | select((($message | test($pattern; "i")) | not))
            | $pattern] as $missing
        | [$forbidden[] as $pattern
            | select($message | test($pattern; "i"))
            | $pattern] as $presentForbidden
        | {
            passed: ($response.status == "COMPLETED"
              and ($missing | length) == 0
              and ($presentForbidden | length) == 0),
            serviceStatus: $response.status,
            attempts: ($response.attempts | length),
            missingRequiredPatterns: $missing,
            presentForbiddenPatterns: $presentForbidden,
            skillHash: ($response.skillHash // null),
            modelName: ($response.modelName // null)
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
      firstAttemptPasses: ([$results[] | select(.passed and .attempts == 1)] | length),
      recoveredPasses: ([$results[] | select(.passed and .attempts > 1)] | length),
      failedRuns: ([$results[] | select(.passed | not)] | length),
      verdict: (if all($results[]; .passed) then "PASS" else "FAIL" end),
      results: $results
    }
' "${results_file}" >"${REPORT_DIR}/summary.json"

jq . "${REPORT_DIR}/summary.json"
[[ "$(jq -r '.verdict' "${REPORT_DIR}/summary.json")" == "PASS" ]]
