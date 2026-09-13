#!/usr/bin/env bash
# Validate planner-eval contracts without starting the service or calling a model.
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CASES_FILE="${DIR}/cases.json"

bash -n "${DIR}/run-planner-eval.sh"
bash -n "${DIR}/replay-planner-run.sh"
jq -e '
  . as $manifest
  | .defaultRequiredPatterns as $defaults
  | (.defaultInputArtifact | IN("NORMALIZED_DESIGN_FLOW", "IDS_DOCUMENT"))
  and (.defaultMaxAttempts | type == "number" and . >= 1)
  and (.cases | length) >= 20
  and ([.cases[] | select(.tags // [] | index("edge"))] | length) >= 10
  and ([.cases[].id] | length == (unique | length))
  and all(.cases[];
    (.id | type == "string" and length > 0)
    and (.inputFile | type == "string" and length > 0)
    and (.requiredPatterns | type == "array" and length > 0)
    and (.forbiddenPatterns | type == "array")
    and ((.tags // []) | type == "array")
    and ((.expectedStatus // "COMPLETED") | IN("COMPLETED", "FAILED"))
    and ((.inputArtifact // $manifest.defaultInputArtifact)
      | IN("NORMALIZED_DESIGN_FLOW", "IDS_DOCUMENT"))
    and ((.maxAttempts // $manifest.defaultMaxAttempts) | type == "number" and . >= 1)
    and all((.minimumOccurrences // [])[];
      (.pattern | type == "string" and length > 0)
      and (.count | type == "number" and . >= 1))
    and all(($defaults + .requiredPatterns + .forbiddenPatterns)[];
      . as $pattern | (try ("sample" | test($pattern)) catch null) != null)
    and all((.minimumOccurrences // [])[].pattern;
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
