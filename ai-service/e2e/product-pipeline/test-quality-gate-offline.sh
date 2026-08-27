#!/usr/bin/env bash
# Offline proof that run-quality-gate.sh orchestrates every active scenario.
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
report_dir="$(mktemp -d "${TMPDIR:-/tmp}/product-quality.XXXXXX")"
trap 'rm -rf "${report_dir}"' EXIT

set +e
bash "${DIR}/run-quality-gate.sh" --runs 3 --not-a-real-flag
rc=$?
set -e
[[ "${rc}" -eq 2 ]] || {
  echo "FAIL: unknown option expected exit 2, got ${rc}" >&2
  exit 1
}

active="$(
  jq '[to_entries[] | select(
    (.value.status // "active") == "active"
    and (.value.recovery.exhaustHalt != true)
  )] | length' \
    "${DIR}/scenarios.json"
)"
PRODUCT_PIPELINE_STUB_MODE=1 bash "${DIR}/run-quality-gate.sh" \
  --runs 3 \
  --report-dir "${report_dir}" \
  --base-url http://127.0.0.1:9 \
  --evaluator-url http://127.0.0.1:9 \
  --skip-deploy

jq -e --argjson expected "$((active * 3))" \
  '.totalRuns == $expected and .verdict == "PASS"' \
  "${report_dir}/summary.json" >/dev/null
find "${report_dir}/runs" -name report.json -print0 \
  | xargs -0 jq -e '
      if .pipeline == "compare-and-patch" then
        .terminalState == "CHAIN_PATCHED" and .stub == true
      else
        .knowledgePackage.certificationStatus == "CERTIFIED"
        and (.knowledgePackage | has("tier") | not)
      end
    '

echo "PASS: offline quality gate covered every active scenario with one package contract"
