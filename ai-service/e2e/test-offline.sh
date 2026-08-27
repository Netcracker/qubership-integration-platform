#!/usr/bin/env bash
# Offline entry point for product CREATE E2E contracts (no Docker, no LLM).
set -euo pipefail

E2E_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PRODUCT_DIR="${E2E_DIR}/product-pipeline"

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

pass() {
  echo "PASS: $*"
}

echo "=== product CREATE offline entry ==="
[[ -f "${PRODUCT_DIR}/scenarios.json" ]] || fail "missing product-pipeline/scenarios.json"
[[ -f "${PRODUCT_DIR}/run-product-scenario.sh" ]] || fail "missing run-product-scenario.sh"
[[ -f "${PRODUCT_DIR}/run-quality-gate.sh" ]] || fail "missing run-quality-gate.sh"
[[ -f "${PRODUCT_DIR}/run-patch-scenario.sh" ]] || fail "missing run-patch-scenario.sh"
[[ -f "${PRODUCT_DIR}/assert-patch-run.sh" ]] || fail "missing assert-patch-run.sh"
[[ -f "${PRODUCT_DIR}/scripts/seed-catalog-chain.sh" ]] || fail "missing seed-catalog-chain.sh"
[[ -f "${PRODUCT_DIR}/scripts/chat-turn.sh" ]] || fail "missing product-pipeline/scripts/chat-turn.sh"
[[ -f "${PRODUCT_DIR}/scripts/assert-catalog.sh" ]] || fail "missing product-pipeline/scripts/assert-catalog.sh"
[[ -f "${PRODUCT_DIR}/scripts/lib.sh" ]] || fail "missing product-pipeline/scripts/lib.sh"
[[ ! -f "${E2E_DIR}/scenarios.json" ]] || fail "legacy e2e/scenarios.json must be deleted"
[[ ! -f "${E2E_DIR}/run-e2e.sh" ]] || fail "legacy e2e/run-e2e.sh must be deleted"
[[ ! -f "${E2E_DIR}/scripts/run-scenario.sh" ]] || fail "legacy run-scenario.sh must be deleted"
[[ ! -f "${E2E_DIR}/scripts/phase1-agree-bundle-loop.sh" ]] || fail "legacy phase1-agree-bundle-loop.sh must be deleted"
pass "legacy CREATE harness absent; product helpers present"

bash -n "${PRODUCT_DIR}/run-product-scenario.sh"
bash -n "${PRODUCT_DIR}/run-patch-scenario.sh"
bash -n "${PRODUCT_DIR}/assert-patch-run.sh"
bash -n "${PRODUCT_DIR}/run-quality-gate.sh"
bash -n "${PRODUCT_DIR}/test-live-runner-contracts.sh"
bash -n "${PRODUCT_DIR}/test-quality-gate-offline.sh"
bash -n "${PRODUCT_DIR}/scripts/chat-turn.sh"
bash -n "${PRODUCT_DIR}/scripts/assert-catalog.sh"
bash -n "${PRODUCT_DIR}/scripts/seed-catalog-chain.sh"
bash -n "${PRODUCT_DIR}/scripts/lib.sh"
pass "product shell syntax"

echo "=== product scenarios pin create-chain@2 (new CREATE) ==="
jq -e '
  [to_entries[] | select(.value.tier == "product-pipeline" and (.value.status // "active") == "active" and .value.pipeline == "create-chain-v1" and (.value.recovery.exhaustHalt != true))]
  | length >= 7
  and all(
    .value.pipeline == "create-chain-v1"
    and .value.profileId == "create-chain"
    and .value.profileVersion == "2"
    and .value.terminalState == "CHAIN_MATERIALIZED"
    and .value.retainCatalogChain == true
  )
' "${PRODUCT_DIR}/scenarios.json" >/dev/null \
  || fail "every active CREATE scenario except exhaustHalt must pin create-chain@2 CHAIN_MATERIALIZED retain=true"
jq -e '
  .["product-create-chain-recovery-exhausted-halt"]
  | .status == "active"
    and .terminalState == "WAITING_FOR_INPUT"
    and .retainCatalogChain == false
    and .recovery.exhaustHalt == true
' "${PRODUCT_DIR}/scenarios.json" >/dev/null \
  || fail "exhausted-halt CREATE scenario must pin WAITING_FOR_INPUT retain=false"
jq -e '
  ([to_entries[].value.pipeline] | index("create-plan-v1") == null)
  and ([to_entries[].value.pipeline] | index("design-first") == null)
  and ([to_entries[].value.pipeline] | index("structure-e2e") == null)
  and ([to_entries[].value.tier] | index("design-first") == null)
  and ([to_entries[].value.tier] | index("structure-e2e") == null)
' "${PRODUCT_DIR}/scenarios.json" >/dev/null \
  || fail "product scenarios must not include create-plan/design-first/structure-e2e"
pass "create-chain@2 scenarios plus exhausted-halt terminal"

echo "=== create-chain@1 backward-compat path remains ==="
AI_ROOT="$(cd "${PRODUCT_DIR}/../.." && pwd)"
[[ -f "${AI_ROOT}/src/main/resources/product-pipelines/profiles/create-chain-v1.yaml" ]] \
  || fail "create-chain@1 profile YAML must remain on the classpath"
[[ -f "${AI_ROOT}/src/test/java/org/qubership/integration/platform/ai/productpipeline/create/CreateChainProductPipelineRestartIT.java" ]] \
  || fail "CreateChainProductPipelineRestartIT must cover historical create-chain@1 restarts"
rg -q 'create-chain@1|profileVersion.*"1"|CREATE_PROFILE_VERSION.*"1"|assertEquals\("1", resumed\.runManifest\(\)\.profileVersion' \
  "${AI_ROOT}/src/test/java/org/qubership/integration/platform/ai/productpipeline/create/CreateChainProductPipelineRestartIT.java" \
  || fail "restart IT must exercise create-chain@1 bindings"
pass "create-chain@1 profile + restart IT present"

echo "=== product contract suites ==="
bash "${PRODUCT_DIR}/test-live-runner-contracts.sh"
bash "${PRODUCT_DIR}/test-quality-gate-offline.sh"
pass "product offline contracts"

echo "ALL OFFLINE CHECKS PASSED"
