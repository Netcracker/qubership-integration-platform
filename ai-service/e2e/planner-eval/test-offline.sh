#!/usr/bin/env bash
# Validate planner-eval contracts without starting the service or calling a model.
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CASES_FILE="${DIR}/cases.json"

bash -n "${DIR}/run-planner-eval.sh"
bash -n "${DIR}/replay-planner-run.sh"
jq -e '
  .defaultRequiredPatterns as $defaults
  | (.cases | length) >= 10
  and ([.cases[].id] | length == (unique | length))
  and all(.cases[];
    (.id | type == "string" and length > 0)
    and (.inputFile | type == "string" and length > 0)
    and (.requiredPatterns | type == "array" and length > 0)
    and (.forbiddenPatterns | type == "array")
    and all(($defaults + .requiredPatterns + .forbiddenPatterns)[];
      . as $pattern | (try ("sample" | test($pattern)) catch null) != null)
  )
' "${CASES_FILE}" >/dev/null

while IFS= read -r relative_path; do
  [[ -f "${DIR}/${relative_path}" ]] || {
    echo "FAIL: missing planner-eval file ${relative_path}" >&2
    exit 1
  }
done < <(jq -r '.cases[] | .inputFile, (.repairEvidenceFile // empty)' "${CASES_FILE}")

echo "PASS: planner-eval runners and cases"
