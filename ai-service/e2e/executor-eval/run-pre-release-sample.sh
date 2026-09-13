#!/usr/bin/env bash
# Run one bounded binding and mapping sample against a local AI service.
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BASE_URL="${BASE_URL:-http://localhost:8094}"
BASE_URL="${BASE_URL%/}"
REPORT_DIR="${REPORT_DIR:-/tmp/rocky-binding-mapping-$(date +%Y%m%d-%H%M%S)}"

command -v curl >/dev/null
command -v jq >/dev/null

curl -fsS --max-time 10 "${BASE_URL}/q/health" | jq -e '.status == "UP"' >/dev/null || {
  echo "FAIL: AI service is not healthy at ${BASE_URL}" >&2
  exit 1
}

"${DIR}/run-executor-eval.sh" \
  --runs 1 \
  --case repeated-operation-mappings \
  --base-url "${BASE_URL}" \
  --report-dir "${REPORT_DIR}"

jq -e '
  [.results[].modelName]
  | length > 0
    and all(. == "claude-sonnet-5" or . == "gpt-5.6-luna")
' "${REPORT_DIR}/summary.json" >/dev/null || {
  echo "FAIL: live sample used a model other than claude-sonnet-5 or gpt-5.6-luna" >&2
  exit 1
}

echo "PASS: bounded live sample completed"
echo "Report: ${REPORT_DIR}/summary.json"
