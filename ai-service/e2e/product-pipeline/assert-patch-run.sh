#!/usr/bin/env bash
# Asserts a COMPARE_AND_PATCH run report.
set -euo pipefail

REPORT="${1:?report json}"
command -v jq >/dev/null

require_string() {
  local key="$1"
  local value
  value="$(jq -r --arg k "${key}" '.[$k] // empty' "${REPORT}")"
  [[ -n "${value}" && "${value}" != "null" ]] || {
    echo "FAIL: missing ${key}" >&2
    exit 1
  }
}

for key in scenarioId conversationId chainId pipeline runtimeMode terminalState expectedTerminalState; do
  require_string "${key}"
done

[[ "$(jq -r '.pipeline' "${REPORT}")" == "compare-and-patch" ]] \
  || { echo "FAIL: pipeline must be compare-and-patch" >&2; exit 1; }
[[ "$(jq -r '.runtimeMode' "${REPORT}")" == "product" ]] \
  || { echo "FAIL: runtimeMode must be product" >&2; exit 1; }

state="$(jq -r '.terminalState' "${REPORT}")"
expected="$(jq -r '.expectedTerminalState' "${REPORT}")"
[[ "${state}" == "${expected}" ]] || {
  echo "FAIL: terminalState=${state} expected=${expected}" >&2
  exit 1
}
[[ "${expected}" == "CHAIN_PATCHED" ]] \
  || { echo "FAIL: expectedTerminalState must be CHAIN_PATCHED" >&2; exit 1; }

jq -e '.promptCount | type == "number" and . >= 1' "${REPORT}" >/dev/null \
  || { echo "FAIL: promptCount must be >= 1" >&2; exit 1; }
jq -e '(.prompts | type == "array") and ((.prompts | length) == .promptCount)' "${REPORT}" >/dev/null \
  || { echo "FAIL: prompts length must match promptCount" >&2; exit 1; }
jq -e '.retainCatalogChain == true' "${REPORT}" >/dev/null \
  || { echo "FAIL: retainCatalogChain must be true" >&2; exit 1; }

echo "PASS: patch run assertions"
