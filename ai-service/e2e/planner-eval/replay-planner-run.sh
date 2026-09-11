#!/usr/bin/env bash
# Replay saved planner responses through the current Java adapter without calling the model.
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "${DIR}/../../.." && pwd)"
REQUEST=""
RESPONSE=""
EXPECTED_STATUS=""
REQUIRED_PATTERNS=()
FORBIDDEN_PATTERNS=()

usage() {
  cat >&2 <<'EOF'
Usage: replay-planner-run.sh \
  --request <request.json> \
  --response <response.json> \
  --expected-status <COMPLETED|FAILED> \
  [--required-pattern <regex>]... \
  [--forbidden-pattern <regex>]...
EOF
  exit 2
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --request) REQUEST="${2:?}"; shift 2 ;;
    --response) RESPONSE="${2:?}"; shift 2 ;;
    --expected-status) EXPECTED_STATUS="${2:?}"; shift 2 ;;
    --required-pattern) REQUIRED_PATTERNS+=("${2:?}"); shift 2 ;;
    --forbidden-pattern) FORBIDDEN_PATTERNS+=("${2:?}"); shift 2 ;;
    -h|--help) usage ;;
    *) echo "Unknown option: $1" >&2; usage ;;
  esac
done

[[ -f "${REQUEST}" && -f "${RESPONSE}" ]] || usage
[[ "${EXPECTED_STATUS}" == "COMPLETED" || "${EXPECTED_STATUS}" == "FAILED" ]] || usage
REQUEST="$(cd "$(dirname "${REQUEST}")" && pwd)/$(basename "${REQUEST}")"
RESPONSE="$(cd "$(dirname "${RESPONSE}")" && pwd)/$(basename "${RESPONSE}")"
required="$(printf '%s\n' "${REQUIRED_PATTERNS[@]:-}")"
forbidden="$(printf '%s\n' "${FORBIDDEN_PATTERNS[@]:-}")"

mvn -f "${ROOT}/pom.xml" -pl ai-service \
  -Dtest=PlannerHarnessReplayTest \
  -Dplanner.replay.request="${REQUEST}" \
  -Dplanner.replay.response="${RESPONSE}" \
  -Dplanner.replay.expectedStatus="${EXPECTED_STATUS}" \
  -Dplanner.replay.requiredPatterns="${required}" \
  -Dplanner.replay.forbiddenPatterns="${forbidden}" \
  test
