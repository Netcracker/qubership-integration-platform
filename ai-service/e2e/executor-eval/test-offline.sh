#!/usr/bin/env bash
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

jq -e '.cases | type == "array" and length > 0' "${DIR}/cases.json" >/dev/null
while IFS= read -r path; do
  [[ -f "${DIR}/${path}" ]] || { echo "FAIL: missing ${path}" >&2; exit 1; }
done < <(jq -r '.cases[] | .inputFile, .plannerResponseFile' "${DIR}/cases.json")

for script in \
  "${DIR}/run-executor-eval.sh" \
  "${DIR}/run-pre-release-sample.sh" \
  "${DIR}/test-offline.sh"; do
  bash -n "${script}"
done

"${DIR}/run-executor-eval.sh" --runs 1 --report-dir /tmp/executor-eval-offline \
  --case does-not-exist >/dev/null 2>&1 && {
    echo "FAIL: unknown case must be rejected" >&2
    exit 1
  }

echo "PASS: executor-eval fixtures and scripts are valid"
