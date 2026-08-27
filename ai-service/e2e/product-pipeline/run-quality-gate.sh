#!/usr/bin/env bash
# Live product CREATE quality gate for one certified Knowledge Package.
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "${DIR}/../../.." && pwd)"
SCENARIOS_FILE="${DIR}/scenarios.json"
COMPOSE_FILE="${ROOT}/infrastructure/docker-compose.yml"
COMPOSE_OVERLAY=""
STUB_EVALUATOR_URL="http://127.0.0.1:9"
LOCAL_EVALUATOR_URL="http://localhost:8099/evaluate"
LOCAL_EVALUATOR_HEALTH_URL="http://localhost:8099/health"

RUNS=""
KNOWLEDGE_PACKAGE=""
REPORT_DIR=""
BASE_URL="${BASE_URL:-http://localhost:8094}"
EVALUATOR_URL="${EVALUATOR_URL:-}"
SKIP_DEPLOY=0
SELECTED_SCENARIO=""

usage() {
  cat >&2 <<'EOF'
Usage: run-quality-gate.sh \
  --runs <positive integer> \
  --report-dir <directory> \
  --base-url <URL> \
  [--knowledge-package <directory>] \
  [--evaluator-url <URL>] \
  [--scenario <id>] \
  [--compose-overlay <path>] \
  [--skip-deploy]
EOF
  exit 2
}

# When COMPOSE_OVERLAY is set, every docker compose call uses base + overlay (-f twice).
compose_cmd() {
  if [[ -n "${COMPOSE_OVERLAY}" ]]; then
    docker compose -f "${COMPOSE_FILE}" -f "${COMPOSE_OVERLAY}" "$@"
  else
    docker compose -f "${COMPOSE_FILE}" "$@"
  fi
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --runs)
      RUNS="${2:?}"
      shift 2
      ;;
    --knowledge-package)
      KNOWLEDGE_PACKAGE="${2:?}"
      shift 2
      ;;
    --report-dir)
      REPORT_DIR="${2:?}"
      shift 2
      ;;
    --base-url)
      BASE_URL="${2:?}"
      shift 2
      ;;
    --evaluator-url)
      EVALUATOR_URL="${2:?}"
      shift 2
      ;;
    --scenario)
      SELECTED_SCENARIO="${2:?}"
      shift 2
      ;;
    --compose-overlay)
      COMPOSE_OVERLAY="${2:?}"
      shift 2
      ;;
    --skip-deploy)
      SKIP_DEPLOY=1
      shift
      ;;
    -h|--help)
      usage
      ;;
    *)
      echo "Unknown option: $1" >&2
      exit 2
      ;;
  esac
done

[[ -n "${RUNS}" && -n "${REPORT_DIR}" && -n "${BASE_URL}" ]] || usage
[[ "${RUNS}" =~ ^[1-9][0-9]*$ ]] || {
  echo "FAIL: --runs must be a positive integer" >&2
  exit 2
}
if [[ -n "${KNOWLEDGE_PACKAGE}" ]]; then
  [[ -d "${KNOWLEDGE_PACKAGE}" ]] || {
    echo "FAIL: --knowledge-package must point to a directory" >&2
    exit 2
  }
fi
if [[ -n "${COMPOSE_OVERLAY}" ]]; then
  [[ -f "${COMPOSE_OVERLAY}" ]] || {
    echo "FAIL: missing compose overlay ${COMPOSE_OVERLAY}" >&2
    exit 2
  }
fi

command -v jq >/dev/null
bash -n "${DIR}/run-product-scenario.sh"
bash -n "${DIR}/run-patch-scenario.sh"
bash -n "${DIR}/assert-product-run.sh"
bash -n "${DIR}/assert-patch-run.sh"
bash -n "${DIR}/scripts/seed-catalog-chain.sh"
python3 -m py_compile \
  "${DIR}/semantic-score.py" \
  "${DIR}/evaluate-plan.py" \
  "${DIR}/build-report-from-evidence.py"

active_entries() {
  local pipeline="$1"
  jq -r --arg p "${pipeline}" \
    'to_entries[] | select(
      .value.tier == "product-pipeline"
      and (.value.status // "active") == "active"
      and .value.pipeline == $p
      and (.value.recovery.exhaustHalt != true)
    ) | .key' \
    "${SCENARIOS_FILE}" | sort
}

CREATE_SCENARIO_IDS=()
while IFS= read -r scenario; do
  [[ -n "${scenario}" ]] || continue
  CREATE_SCENARIO_IDS+=("${scenario}")
done < <(active_entries "create-chain-v1")

PATCH_SCENARIO_IDS=()
while IFS= read -r scenario; do
  [[ -n "${scenario}" ]] || continue
  PATCH_SCENARIO_IDS+=("${scenario}")
done < <(active_entries "compare-and-patch")

if [[ -n "${SELECTED_SCENARIO}" ]]; then
  jq -e --arg s "${SELECTED_SCENARIO}" '
    .[$s].tier == "product-pipeline" and (.[$s].status // "active") == "active"
  ' "${SCENARIOS_FILE}" >/dev/null || {
    echo "FAIL: --scenario must name an active product-pipeline scenario" >&2
    exit 2
  }
  selected_pipeline="$(jq -r --arg s "${SELECTED_SCENARIO}" '.[$s].pipeline' "${SCENARIOS_FILE}")"
  CREATE_SCENARIO_IDS=()
  PATCH_SCENARIO_IDS=()
  if [[ "${selected_pipeline}" == "create-chain-v1" ]]; then
    CREATE_SCENARIO_IDS=("${SELECTED_SCENARIO}")
  elif [[ "${selected_pipeline}" == "compare-and-patch" ]]; then
    PATCH_SCENARIO_IDS=("${SELECTED_SCENARIO}")
  else
    echo "FAIL: selected scenario uses unsupported pipeline ${selected_pipeline}" >&2
    exit 2
  fi
fi

SCENARIO_IDS=()
for scenario in "${CREATE_SCENARIO_IDS[@]:-}"; do
  [[ -n "${scenario}" ]] && SCENARIO_IDS+=("${scenario}")
done
for scenario in "${PATCH_SCENARIO_IDS[@]:-}"; do
  [[ -n "${scenario}" ]] && SCENARIO_IDS+=("${scenario}")
done
[[ "${#SCENARIO_IDS[@]}" -gt 0 ]] || {
  echo "FAIL: no active product-pipeline scenarios selected" >&2
  exit 1
}

if [[ "${#CREATE_SCENARIO_IDS[@]}" -gt 0 ]]; then
  for scenario in "${CREATE_SCENARIO_IDS[@]}"; do
    if jq -e --arg s "${scenario}" '.[$s].recovery.exhaustHalt == true' "${SCENARIOS_FILE}" >/dev/null; then
      jq -e --arg s "${scenario}" '
        .[$s].pipeline == "create-chain-v1"
        and .[$s].profileId == "create-chain"
        and .[$s].profileVersion == "2"
        and .[$s].terminalState == "WAITING_FOR_INPUT"
        and .[$s].retainCatalogChain == false
        and .[$s].recovery.faultStage == "design-execution"
        and .[$s].recovery.ownerStage == "design-planning"
        and ((.[$s].recovery.followUp | type) == "string" and (.[$s].recovery.followUp | length) > 0)
        and ((.[$s].uniqueChainNamePrefix | type) == "string" and (.[$s].uniqueChainNamePrefix | length) > 0)
      ' "${SCENARIOS_FILE}" >/dev/null || {
        echo "FAIL: exhausted-halt scenario ${scenario} must pin WAITING_FOR_INPUT retain=false with a recovery fault" >&2
        exit 1
      }
      continue
    fi
    jq -e --arg s "${scenario}" '
      .[$s].pipeline == "create-chain-v1"
      and .[$s].profileId == "create-chain"
      and .[$s].profileVersion == "2"
      and .[$s].terminalState == "CHAIN_MATERIALIZED"
      and .[$s].retainCatalogChain == true
    ' "${SCENARIOS_FILE}" >/dev/null || {
      echo "FAIL: active scenario ${scenario} must pin create-chain@2 CHAIN_MATERIALIZED retain=true" >&2
      exit 1
    }
    if jq -e --arg s "${scenario}" '.[$s].recovery != null' "${SCENARIOS_FILE}" >/dev/null; then
      jq -e --arg s "${scenario}" '
        .[$s].recovery.faultStage == "design-execution"
        and .[$s].recovery.ownerStage == "design-planning"
        and ((.[$s].recovery.followUp | type) == "string" and (.[$s].recovery.followUp | length) > 0)
        and ((.[$s].uniqueChainNamePrefix | type) == "string" and (.[$s].uniqueChainNamePrefix | length) > 0)
      ' "${SCENARIOS_FILE}" >/dev/null || {
        echo "FAIL: recovery scenario ${scenario} must define fault stage, owner, follow-up, and prefix" >&2
        exit 1
      }
    fi
  done
fi

if [[ "${#PATCH_SCENARIO_IDS[@]}" -gt 0 ]]; then
  for scenario in "${PATCH_SCENARIO_IDS[@]}"; do
    jq -e --arg s "${scenario}" '
      .[$s].pipeline == "compare-and-patch"
      and .[$s].terminalState == "CHAIN_PATCHED"
      and .[$s].retainCatalogChain == true
      and ((.[$s].uniqueChainNamePrefix | type) == "string" and (.[$s].uniqueChainNamePrefix | length) > 0)
      and (
        ((.[$s].prompts | type) == "array" and (.[$s].prompts | length) > 0)
        or ((.[$s].prompt | type) == "string" and (.[$s].prompt | length) > 0)
      )
      and ((.[$s].seed.elements | type) == "array" and (.[$s].seed.elements | length) > 0)
    ' "${SCENARIOS_FILE}" >/dev/null || {
      echo "FAIL: active scenario ${scenario} must pin compare-and-patch CHAIN_PATCHED retain=true with seed and prompts" >&2
      exit 1
    }
  done
fi

EXPECTED_TOTAL=$(( ${#SCENARIO_IDS[@]} * RUNS ))
mkdir -p "${REPORT_DIR}/runs"
reliability_file="$(mktemp)"
: >"${reliability_file}"
score_list_file="$(mktemp)"
: >"${score_list_file}"
trap 'rm -f "${reliability_file}" "${score_list_file}"' EXIT

if [[ -n "${KNOWLEDGE_PACKAGE}" ]]; then
  export QIP_KNOWLEDGE_HOST_PATH
  QIP_KNOWLEDGE_HOST_PATH="$(cd "${KNOWLEDGE_PACKAGE}" && pwd)"
fi

recovery_fault_prefixes="$(
  for scenario in "${CREATE_SCENARIO_IDS[@]:-}"; do
    [[ -n "${scenario}" ]] || continue
    jq -r --arg s "${scenario}" 'select(.[$s].recovery != null) | .[$s].uniqueChainNamePrefix' \
      "${SCENARIOS_FILE}"
  done | awk 'NF' | sort -u
)"
recovery_fault_prefix_count="$(printf '%s\n' "${recovery_fault_prefixes}" | awk 'NF' | wc -l | tr -d ' ')"
if [[ "${recovery_fault_prefix_count}" -gt 1 ]]; then
  echo "FAIL: live gate supports one recovery fault chain prefix per deployment" >&2
  exit 1
fi
if [[ "${recovery_fault_prefix_count}" -eq 1 ]]; then
  export QIP_E2E_RECOVERY_FAULT_CHAIN_PREFIX="${recovery_fault_prefixes}"
fi

wait_for_health() {
  local base_url="$1"
  local timeout="${2:-180}"
  local deadline=$((SECONDS + timeout))
  while (( SECONDS < deadline )); do
    if curl -sf "${base_url}/q/health" >/dev/null 2>&1; then
      return 0
    fi
    sleep 3
  done
  echo "FAIL: service not healthy at ${base_url}/q/health" >&2
  return 1
}

wait_for_evaluator_health() {
  local health_url="${1:-${LOCAL_EVALUATOR_HEALTH_URL}}"
  local timeout="${2:-180}"
  local deadline=$((SECONDS + timeout))
  while (( SECONDS < deadline )); do
    if curl -sf "${health_url}" >/dev/null 2>&1; then
      return 0
    fi
    sleep 3
  done
  echo "FAIL: evaluator not healthy at ${health_url}" >&2
  return 1
}

start_local_evaluator() {
  command -v docker >/dev/null
  [[ -f "${COMPOSE_FILE}" ]] || {
    echo "FAIL: missing compose file ${COMPOSE_FILE}" >&2
    return 1
  }
  echo "Starting local evaluator via Compose (profile ai-e2e)"
  compose_cmd --profile ai-e2e up -d --build qip-e2e-evaluator
  wait_for_evaluator_health "${LOCAL_EVALUATOR_HEALTH_URL}"
}

resolve_evaluator_url() {
  if [[ "${PRODUCT_PIPELINE_STUB_MODE:-0}" == "1" ]]; then
    EVALUATOR_URL="${STUB_EVALUATOR_URL}"
    return 0
  fi
  if [[ -n "${EVALUATOR_URL}" ]]; then
    return 0
  fi
  EVALUATOR_URL="${LOCAL_EVALUATOR_URL}"
  start_local_evaluator
}

total_runs=0

resolve_evaluator_url
if [[ "${PRODUCT_PIPELINE_CONTRACT_RESOLVE_ONLY:-0}" == "1" ]]; then
  printf 'EVALUATOR_URL=%s\n' "${EVALUATOR_URL}"
  exit 0
fi

if [[ "${PRODUCT_PIPELINE_STUB_MODE:-0}" == "1" ]]; then
  echo "SKIP deploy (stub mode)"
elif [[ "${SKIP_DEPLOY}" -eq 1 ]]; then
  echo "SKIP deploy (--skip-deploy; stack must already use the intended package)"
  if [[ "${recovery_fault_prefix_count}" -eq 1 ]]; then
    command -v docker >/dev/null
    [[ -f "${COMPOSE_FILE}" ]] || {
      echo "FAIL: missing compose file ${COMPOSE_FILE}" >&2
      exit 1
    }
    echo "Recreating qip-ai-service so the recovery-fault prefix reaches the running container"
    compose_cmd --profile ai up -d --no-build --no-deps --force-recreate qip-ai-service
    wait_for_health "${BASE_URL}"
  fi
fi

if [[ "${PRODUCT_PIPELINE_STUB_MODE:-0}" != "1" && "${SKIP_DEPLOY}" -eq 0 ]]; then
  command -v docker >/dev/null
  [[ -f "${COMPOSE_FILE}" ]] || {
    echo "FAIL: missing compose file ${COMPOSE_FILE}" >&2
    exit 1
  }
  echo "Force-recreating knowledge sidecar and qip-ai-service"
  compose_cmd --profile ai up -d --build --force-recreate \
    qip-knowledge-sidecar qip-ai-service
  wait_for_health "${BASE_URL}"
  compose_cmd exec -T qip-knowledge-sidecar python -c \
    'import json, urllib.request; ref=json.load(urllib.request.urlopen("http://127.0.0.1:8095/v1/package"))["packageRef"]; assert ref["certificationStatus"] == "CERTIFIED"; assert ref["packageChecksum"].startswith("sha256:")'
fi

for scenario in "${SCENARIO_IDS[@]}"; do
  rep=1
  while [[ "${rep}" -le "${RUNS}" ]]; do
    run_dir="${REPORT_DIR}/runs/${scenario}/rep-${rep}"
    mkdir -p "${run_dir}"
    report_path="${run_dir}/report.json"
    pipeline="$(jq -r --arg s "${scenario}" '.[$s].pipeline' "${SCENARIOS_FILE}")"
    set +e
    if [[ "${pipeline}" == "compare-and-patch" ]]; then
      bash "${DIR}/run-patch-scenario.sh" \
        --scenario "${scenario}" \
        --rep "${rep}" \
        --base-url "${BASE_URL}" \
        --evaluator-url "${EVALUATOR_URL}" \
        --report "${report_path}"
    else
      bash "${DIR}/run-product-scenario.sh" \
        --scenario "${scenario}" \
        --rep "${rep}" \
        --base-url "${BASE_URL}" \
        --evaluator-url "${EVALUATOR_URL}" \
        --report "${report_path}"
    fi
    rc=$?
    set -e
    if [[ "${rc}" -ne 0 ]]; then
      printf '%s\n' \
        "{\"scenario\":\"${scenario}\",\"rep\":${rep},\"class\":\"runtime\",\"message\":\"scenario failed rc=${rc}\"}" \
        >>"${reliability_file}"
      rep=$((rep + 1))
      continue
    fi
    total_runs=$((total_runs + 1))
    if [[ -f "${run_dir}/semantic-scores.json" ]]; then
      echo "${run_dir}/semantic-scores.json" >>"${score_list_file}"
    fi
    rep=$((rep + 1))
  done
done

if [[ -s "${reliability_file}" ]]; then
  reliability_json="$(jq -s '.' "${reliability_file}")"
else
  reliability_json='[]'
fi

semantic_summary='{"skipped":true}'
if [[ -s "${score_list_file}" ]]; then
  scores_tmp="$(mktemp)"
  # shellcheck disable=SC2046
  jq -s '[.[] | if type=="array" then .[] else . end]' $(cat "${score_list_file}") >"${scores_tmp}"
  set +e
  semantic_out="$(python3 "${DIR}/semantic-score.py" --scores-file "${scores_tmp}" 2>&1)"
  semantic_rc=$?
  set -e
  rm -f "${scores_tmp}"
  if [[ "${semantic_rc}" -ne 0 ]]; then
    echo "${semantic_out}" >&2
    semantic_summary="$(jq -nc --arg err "${semantic_out}" '{failed:true,error:$err}')"
  else
    semantic_summary="${semantic_out}"
  fi
fi

# Reports from one gate run share one compiler pipeline digest when present.
digest_check="$(
  find "${REPORT_DIR}/runs" -name report.json -print0 2>/dev/null \
    | xargs -0 jq -r '.compilerPipelineDigest // empty' 2>/dev/null \
    | awk 'NF' \
    | sort -u
)"
digest_count="$(printf '%s\n' "${digest_check}" | awk 'NF' | wc -l | tr -d ' ')"
if [[ "${digest_count}" -gt 1 ]]; then
  reliability_json="$(jq -c --arg m "compilerPipelineDigest mismatch within gate run: ${digest_check}" \
    '. + [{"class":"runtime","message":$m}]' <<<"${reliability_json}")"
fi

jq -nc \
  --argjson total "${total_runs}" \
  --argjson expected "${EXPECTED_TOTAL}" \
  --argjson reliability "${reliability_json}" \
  --argjson semantic "${semantic_summary}" \
  --arg digests "$(printf '%s' "${digest_check}" | paste -sd, -)" \
  '{
     totalRuns: $total,
     expectedRuns: $expected,
     reliabilityFailures: $reliability,
     semantic: $semantic,
     compilerPipelineDigests: $digests,
     verdict: (if ($reliability|length)==0 and $total==$expected then "PASS" else "FAIL" end)
   }' >"${REPORT_DIR}/summary.json"

echo "Quality gate summary written to ${REPORT_DIR}/summary.json"
jq . "${REPORT_DIR}/summary.json"

if [[ "$(jq -r '.verdict' "${REPORT_DIR}/summary.json")" != "PASS" ]]; then
  exit 1
fi
