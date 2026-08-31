#!/usr/bin/env bash
# Contract tests for live product quality-gate runners (no Docker, no LLM).
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

pass() {
  echo "PASS: $*"
}

SCENARIO_SH="${DIR}/run-product-scenario.sh"
GATE_SH="${DIR}/run-quality-gate.sh"
EVAL_PY="${DIR}/evaluate-plan.py"
BUILD_PY="${DIR}/build-report-from-evidence.py"

echo "=== chat API must use POST /api/v1/chat SSE ==="
rg -q 'chat-turn\.sh' "${SCENARIO_SH}" \
  || fail "run-product-scenario.sh must call chat-turn.sh"
rg -q '/api/v1/chat/conversations"' "${SCENARIO_SH}" \
  && fail "run-product-scenario.sh must not create conversations via nonexistent endpoint"
rg -q '/messages"' "${SCENARIO_SH}" \
  && fail "run-product-scenario.sh must not POST /messages"
pass "chat endpoint contract"

echo "=== product runner resolves shared helpers via SCRIPTS_DIR ==="
rg -qF '${DIR}/scripts' "${SCENARIO_SH}" \
  || fail "run-product-scenario.sh must set SCRIPTS_DIR to product-pipeline/scripts"
SCRIPTS_DIR="${DIR}/scripts"
[[ -f "${SCRIPTS_DIR}/chat-turn.sh" ]] || fail "missing ${SCRIPTS_DIR}/chat-turn.sh"
[[ -f "${SCRIPTS_DIR}/a2a-turn.sh" ]] || fail "missing ${SCRIPTS_DIR}/a2a-turn.sh"
[[ -f "${SCRIPTS_DIR}/assert-catalog.sh" ]] || fail "missing ${SCRIPTS_DIR}/assert-catalog.sh"
[[ -f "${SCRIPTS_DIR}/lib.sh" ]] || fail "missing ${SCRIPTS_DIR}/lib.sh"
[[ ! -f "${DIR}/../scripts/run""-scenario.sh" ]] || fail "legacy CREATE scenario runner must be absent"
[[ ! -f "${DIR}/../scripts/phase1""-agree-bundle-loop.sh" ]] || fail "legacy agree-bundle loop helper must be absent"
[[ ! -f "${DIR}/../scenarios.json" ]] || fail "legacy e2e/scenarios.json must be absent"
[[ ! -f "${DIR}/../run-e2e.sh" ]] || fail "legacy e2e/run-e2e.sh must be absent"
pass "shared helper paths and deleted CREATE-only harness"

echo "=== A2A runner uses the advertised JSON-RPC interface ==="
A2A_TURN_SH="${SCRIPTS_DIR}/a2a-turn.sh"
rg -q '"method": "SendMessage"' "${A2A_TURN_SH}" \
  || fail "a2a-turn.sh must call the A2A 1.0 SendMessage method"
rg -q '"\$\{BASE_URL\}/rpc"' "${A2A_TURN_SH}" \
  || fail "a2a-turn.sh must use the advertised /rpc endpoint"
rg -q '"skillId": "create-chain@2"' "${A2A_TURN_SH}" \
  || fail "a2a-turn.sh must select create-chain@2"
rg -qF 'result.get("task")' "${A2A_TURN_SH}" \
  || fail "a2a-turn.sh must read the JSON-RPC result.task envelope"
! rg -q '/message:send' "${A2A_TURN_SH}" \
  || fail "a2a-turn.sh must not call the unavailable REST message endpoint"
pass "A2A JSON-RPC transport contract"


echo "=== quality gate must deploy Compose without legacy runtime selectors ==="
rg -q 'docker compose' "${GATE_SH}" \
  || fail "run-quality-gate.sh must invoke docker compose for live deploy"
rg -q "QIP_CREATE""_RUNTIME" "${GATE_SH}" \
  && fail "run-quality-gate.sh must not require removed runtime selector after cutover"
rg -q "QIP_CREATE_PRODUCT""_PROFILE_ID" "${GATE_SH}" \
  && fail "run-quality-gate.sh must not require removed profile id selector after cutover"
rg -q "QIP_CREATE_PRODUCT""_PROFILE_VERSION" "${GATE_SH}" \
  && fail "run-quality-gate.sh must not require removed profile version selector after cutover"
rg -q -- '--knowledge-package' "${GATE_SH}" \
  || fail "run-quality-gate.sh must accept --knowledge-package"
! rg -q -- '--expected-tier|--full-package' "${GATE_SH}" \
  || fail "run-quality-gate.sh must not expose removed FULL/SLIM selectors"
! rg -q -- \
  '--knowledge-artifact-root|--runs-per-tier|resolve_artifact_id|sync_compose_product_pins' \
  "${GATE_SH}" "${SCENARIO_SH}" \
  || fail "legacy artifact-root / runs-per-tier selectors must be absent"
test ! -f "${DIR}/prepare-native-knowledge-artifact.sh" \
  || fail "prepare-native-knowledge-artifact.sh must be deleted"
pass "compose deploy contract"

echo "=== quality gate must support --compose-overlay ==="
rg -q -- '--compose-overlay' "${GATE_SH}" \
  || fail "run-quality-gate.sh must parse --compose-overlay"
rg -q 'COMPOSE_OVERLAY' "${GATE_SH}" \
  || fail "run-quality-gate.sh must define COMPOSE_OVERLAY"
rg -q 'compose_cmd' "${GATE_SH}" \
  || fail "run-quality-gate.sh must route docker compose through compose_cmd"
rg -q -- '-f "\$\{COMPOSE_FILE\}" -f "\$\{COMPOSE_OVERLAY\}"' "${GATE_SH}" \
  || fail "run-quality-gate.sh must pass a second -f when COMPOSE_OVERLAY is set"
pass "compose overlay contract"

echo "=== recovery scenario must prove causal plan repair before materialization ==="
jq -e '
  .["product-create-chain-recovery-revise-plan"] as $s
  | $s.status == "active"
    and $s.uniqueChainNamePrefix == "AiRecoveryRevise"
    and $s.recovery.faultStage == "design-execution"
    and $s.recovery.ownerStage == "design-planning"
    and (($s.recovery.followUp | type) == "string" and ($s.recovery.followUp | length) > 0)
' "${DIR}/scenarios.json" >/dev/null \
  || fail "recovery scenario must define the injected fault and causal owner"
rg -q 'QIP_E2E_RECOVERY_FAULT_CHAIN_PREFIX' "${GATE_SH}" \
  || fail "quality gate must scope the recovery fault to the selected chain prefix"
rg -q -- '--no-build --no-deps --force-recreate qip-ai-service' "${GATE_SH}" \
  || fail "skip-deploy must recreate qip-ai-service so the recovery-fault prefix reaches the container"
[[ "$(rg -c 'path: \.env\.local' "${ROOT:-${DIR}/../../..}/infrastructure/docker-compose.yml")" -ge 2 ]] \
  || fail "AI service and evaluator must load the documented optional .env.local overrides"
rg -q -- '--scenario' "${GATE_SH}" \
  || fail "quality gate must support running the recovery scenario in isolation"
rg -q 'action:"revise"' "${SCENARIO_SH}" \
  || fail "product runner must send a typed revise decision"
rg -q 'causal reopen of' "${SCENARIO_SH}" \
  || fail "product runner must assert the causal reopen transition"
rg -q 'MATERIALIZATION_REQUEST.*MATERIALIZATION_RESULT.*CATALOG_CHAIN_SNAPSHOT' "${SCENARIO_SH}" \
  || fail "product runner must reject materialization artifacts before revision"
pass "recovery scenario contract"

echo "=== exhausted halt scenario is expressible and skipped from the default gate ==="
jq -e '
  .["product-create-chain-recovery-exhausted-halt"] as $s
  | $s.status == "active"
    and $s.pipeline == "create-chain-v1"
    and $s.profileId == "create-chain"
    and $s.profileVersion == "2"
    and $s.terminalState == "WAITING_FOR_INPUT"
    and $s.retainCatalogChain == false
    and $s.uniqueChainNamePrefix == "AiRecoveryExhaust"
    and $s.recovery.exhaustHalt == true
    and $s.recovery.faultStage == "design-execution"
    and $s.recovery.ownerStage == "design-planning"
    and (($s.recovery.followUp | type) == "string" and ($s.recovery.followUp | length) > 0)
' "${DIR}/scenarios.json" >/dev/null \
  || fail "exhausted-halt scenario must pin WAITING_FOR_INPUT retain=false with a recovery fault"
rg -q 'exhaustHalt' "${SCENARIO_SH}" \
  || fail "run-product-scenario.sh must read recovery.exhaustHalt"
rg -q 'WAITING_FOR_INPUT' "${SCENARIO_SH}" \
  || fail "run-product-scenario.sh must accept WAITING_FOR_INPUT as an exhausted-halt terminal"
rg -q 'e2e_assert_observable_sse_output' "${SCENARIO_SH}" \
  || fail "run-product-scenario.sh must fail a command that produced no SSE output"
rg -q 'e2e_assert_observable_sse_output' "${DIR}/scripts/lib.sh" \
  || fail "lib.sh must assert observable SSE output"
rg -q 'E2E recovery fault' "${SCENARIO_SH}" \
  || fail "run-product-scenario.sh must detect the injected recovery fault"
rg -q 'implement_sent=0' "${SCENARIO_SH}" \
  || fail "run-product-scenario.sh must reset Implement after an automatic reopen"
rg -q 'stop-with-report' "${DIR}/assert-product-run.sh" \
  || fail "assert-product-run.sh must assert stop-with-report on an exhausted halt card"
rg -q 'haltGate|haltPrompt|haltActions' "${BUILD_PY}" \
  || fail "build-report-from-evidence.py must expose the halt card"
rg -q 'exhaustHalt != true' "${GATE_SH}" \
  || fail "quality gate must skip exhaustHalt from the default CREATE set"
rg -q -- '--scenario' "${GATE_SH}" \
  || fail "quality gate must support running the exhausted-halt scenario in isolation"
pass "exhausted halt scenario contract"

echo "=== live reports must be built from evidence ==="
[[ -f "${BUILD_PY}" ]] || fail "build-report-from-evidence.py is required"
rg -q 'build-report-from-evidence\.py' "${SCENARIO_SH}" \
  || fail "run-product-scenario.sh must call build-report-from-evidence.py"
rg -q 'objectSetDigest.: .live.|approvalTargetHash.: .live.' \
  "${SCENARIO_SH}" \
  && fail "run-product-scenario.sh must not hardcode live placeholder digests/hashes"
pass "evidence-backed report builder present"

TMP="$(mktemp -d)"
trap 'rm -rf "${TMP}"' EXIT

echo "=== evaluate-plan fail-closed ==="
python3 -m py_compile "${EVAL_PY}" "${BUILD_PY}"
missing_plan="$(mktemp "${TMP}/missing-XXXXXX.json")"
cat >"${missing_plan}" <<'EOF'
{"scenarioId":"x","requiredFacts":["GET /x"],"forbiddenFacts":[],"terminalState":"PLAN_APPROVED","requirementFacts":["GET /x"]}
EOF
set +e
python3 "${EVAL_PY}" \
  --evaluator-url "http://127.0.0.1:9" \
  --report "${missing_plan}" \
  --out "${TMP}/score.json" 2>"${TMP}/eval.err"
rc=$?
set -e
[[ "${rc}" -ne 0 ]] || fail "evaluate-plan.py must fail when ImplementationPlan is missing"
rg -qi 'ImplementationPlan|decodedPlan|missing decoded' "${TMP}/eval.err" \
  || fail "evaluate-plan.py must mention missing plan"
pass "evaluate-plan fails closed without plan"

echo "=== build-report-from-evidence uses real PlanValidationResult findings ==="
ev="${TMP}/evidence.json"
cat >"${ev}" <<'EOF'
{
  "conversationId": "c1",
  "currentState": "PLAN_APPROVED",
  "runRevision": 3,
  "runManifest": {
    "runId": "run-1",
    "runtimeSelection": "product",
    "profileId": "create-chain",
    "profileVersion": "1",
    "knowledgePackage": {
      "packageKey": "fixture@1.0.0",
      "knowledgeVersion": "1.0.0",
      "schemaVersion": "1.0.0",
      "packageChecksum": "sha256:fixture",
      "certificationStatus": "CERTIFIED",
      "certificationDigest": "sha256:certificate"
    }
  },
  "committedArtifactKinds": [
    "IMPLEMENTATION_PLAN",
    "PLAN_VALIDATION_RESULT",
    "APPROVAL_RECORD",
    "REQUIREMENT_BRIEF"
  ],
  "decodedArtifacts": {
    "IMPLEMENTATION_PLAN#p1": {
      "goal": "greetings",
      "steps": []
    },
    "PLAN_VALIDATION_RESULT#v1": {
      "findings": []
    },
    "APPROVAL_RECORD#a1": {
      "targetContentHash": "hash-1",
      "actor": "user",
      "comment": "Agree"
    },
    "REQUIREMENT_BRIEF#b1": {
      "facts": [
        {
          "text": "GET /greetings"
        }
      ],
      "summary": "GET /greetings"
    }
  },
  "attempts": [
    {
      "stageId": "plan",
      "outcome": "SUCCEEDED"
    }
  ],
  "transitions": [
    {
      "toStatus": "PLAN_APPROVED",
      "reason": "approved"
    }
  ],
  "knowledgeContext": {
    "packageRef": {
      "packageKey": "fixture@1.0.0",
      "knowledgeVersion": "1.0.0",
      "schemaVersion": "1.0.0",
      "packageChecksum": "sha256:fixture",
      "certificationStatus": "CERTIFIED",
      "certificationDigest": "sha256:certificate"
    },
    "objectIds": [
      "CIP:GEN-000049"
    ],
    "contentChars": 120
  }
}
EOF
python3 "${BUILD_PY}" \
  --evidence "${ev}" \
  --scenario-id product-greetings \
  --rep 1 \
  --required-facts '["GET /greetings"]' \
  --forbidden-facts '[]' \
  --expected-terminal-state PLAN_APPROVED \
  --expects-approval true \
  --out "${TMP}/report.json"
jq -e '.knowledgePackage.packageKey == "fixture@1.0.0"' \
  "${TMP}/report.json" >/dev/null
jq -e '
  .knowledgeContext.packageChecksum
      == .knowledgePackage.packageChecksum
  and .knowledgeContext.objectIds == ["CIP:GEN-000049"]
  and .knowledgeContext.contentChars == 120
' "${TMP}/report.json" >/dev/null
jq -e '.runtimeMode == "product"' "${TMP}/report.json" >/dev/null
jq -e '.validationVerdict == "PASS"' "${TMP}/report.json" >/dev/null
jq -e '.approvalEligible == true' "${TMP}/report.json" >/dev/null
jq -e '.approvalTargetHash == "hash-1"' "${TMP}/report.json" >/dev/null
jq -e '.decodedPlan.goal == "greetings"' "${TMP}/report.json" >/dev/null
jq -e '.requirementFacts | length > 0' "${TMP}/report.json" >/dev/null
pass "evidence report builder for eligible plan"

echo "=== mismatched knowledgeContext.packageRef must fail ==="
mismatch_ev="${TMP}/mismatch-knowledge.json"
python3 - "${ev}" "${mismatch_ev}" <<'PY'
import json
import pathlib
import sys

src = json.loads(pathlib.Path(sys.argv[1]).read_text())
ctx = src["knowledgeContext"]
ctx["packageRef"] = dict(ctx["packageRef"])
ctx["packageRef"]["packageChecksum"] = "sha256:different"
pathlib.Path(sys.argv[2]).write_text(json.dumps(src))
PY
set +e
python3 "${BUILD_PY}" \
  --evidence "${mismatch_ev}" \
  --scenario-id product-greetings \
  --rep 1 \
  --required-facts '["GET /greetings"]' \
  --forbidden-facts '[]' \
  --expected-terminal-state PLAN_APPROVED \
  --expects-approval true \
  --out "${TMP}/mismatch-report.json" 2>"${TMP}/mismatch.err"
mismatch_rc=$?
set -e
[[ "${mismatch_rc}" -ne 0 ]] || fail "builder must reject mismatched packageRef checksum"
rg -q 'knowledgeContext.packageRef must equal runManifest.knowledgePackage' "${TMP}/mismatch.err" \
  || fail "builder must report packageRef equality failure"
pass "mismatched packageRef rejected"


echo "=== blocker findings make approvalEligible false ==="
cat >"${ev}" <<'EOF'
{
  "conversationId": "c2",
  "currentState": "FAILED",
  "runRevision": 4,
  "runManifest": {
    "runId": "run-2",
    "runtimeSelection": "product",
    "profileId": "create-chain",
    "profileVersion": "1",
    "knowledgePackage": {
      "packageKey": "fixture@1.0.0",
      "knowledgeVersion": "1.0.0",
      "schemaVersion": "1.0.0",
      "packageChecksum": "sha256:fixture",
      "certificationStatus": "CERTIFIED",
      "certificationDigest": "sha256:certificate"
    }
  },
  "committedArtifactKinds": [
    "REQUIREMENT_BRIEF",
    "PLAN_VALIDATION_RESULT"
  ],
  "decodedArtifacts": {
    "PLAN_VALIDATION_RESULT#v2": {
      "findings": [
        {
          "code": "EXCLUSION",
          "message": "else.condition is forbidden",
          "blocker": true
        }
      ]
    },
    "REQUIREMENT_BRIEF#b2": {
      "facts": [
        {
          "text": "LangRouter"
        }
      ],
      "summary": "LangRouter"
    }
  },
  "attempts": [
    {
      "stageId": "plan",
      "outcome": "FAILED",
      "failureEvidence": "VALIDATION_FAILURE: planning validation failed"
    }
  ],
  "transitions": [
    {
      "toStatus": "FAILED",
      "reason": "VALIDATION_FAILURE: planning validation failed"
    }
  ],
  "knowledgeContext": {
    "packageRef": {
      "packageKey": "fixture@1.0.0",
      "knowledgeVersion": "1.0.0",
      "schemaVersion": "1.0.0",
      "packageChecksum": "sha256:fixture",
      "certificationStatus": "CERTIFIED",
      "certificationDigest": "sha256:certificate"
    },
    "objectIds": [
      "CIP:GEN-000049"
    ],
    "contentChars": 120
  }
}
EOF
python3 "${BUILD_PY}" \
  --evidence "${ev}" \
  --scenario-id fixture-validation-failure \
  --rep 1 \
  --required-facts '["VALIDATION_FAILURE","else.condition"]' \
  --forbidden-facts '["PLAN_APPROVED"]' \
  --expected-terminal-state FAILED \
  --expects-approval true \
  --out "${TMP}/failed-report.json"
jq -e '.validationVerdict == "FAIL"' "${TMP}/failed-report.json" >/dev/null
jq -e '.approvalEligible == false' "${TMP}/failed-report.json" >/dev/null
jq -e '.runtimeMode == "product"' "${TMP}/failed-report.json" >/dev/null
jq -e '.missingRequiredFacts == []' "${TMP}/failed-report.json" >/dev/null
# expectsApproval must not force eligibility
jq -e '.approvalEligible == false' "${TMP}/failed-report.json" >/dev/null
pass "blocker findings drive eligibility from evidence"

echo "=== inventing verdict fields must not be required ==="
bad="${TMP}/bad-evidence.json"
cat >"${bad}" <<'EOF'
{
  "conversationId": "c3",
  "currentState": "PLAN_APPROVED",
  "runManifest": {
    "runId": "run-3",
    "runtimeSelection": "product",
    "profileId": "create-chain",
    "profileVersion": "1",
    "knowledgePackage": {
      "packageKey": "fixture@1.0.0",
      "knowledgeVersion": "1.0.0",
      "schemaVersion": "1.0.0",
      "packageChecksum": "sha256:fixture",
      "certificationStatus": "CERTIFIED",
      "certificationDigest": "sha256:certificate"
    }
  },
  "committedArtifactKinds": [
    "PLAN_VALIDATION_RESULT",
    "APPROVAL_RECORD",
    "IMPLEMENTATION_PLAN"
  ],
  "decodedArtifacts": {
    "IMPLEMENTATION_PLAN#p3": {
      "goal": "x"
    },
    "PLAN_VALIDATION_RESULT#v3": {
      "verdict": "PASS",
      "approvalTargetHash": "hash"
    },
    "APPROVAL_RECORD#a3": {
      "targetContentHash": "hash"
    }
  },
  "attempts": [],
  "transitions": [],
  "knowledgeContext": {
    "packageRef": {
      "packageKey": "fixture@1.0.0",
      "knowledgeVersion": "1.0.0",
      "schemaVersion": "1.0.0",
      "packageChecksum": "sha256:fixture",
      "certificationStatus": "CERTIFIED",
      "certificationDigest": "sha256:certificate"
    },
    "objectIds": [
      "CIP:GEN-000049"
    ],
    "contentChars": 120
  }
}
EOF
set +e
python3 "${BUILD_PY}" \
  --evidence "${bad}" \
  --scenario-id product-greetings \
  --rep 1 \
  --required-facts '[]' \
  --forbidden-facts '[]' \
  --expected-terminal-state PLAN_APPROVED \
  --out "${TMP}/bad-report.json" 2>"${TMP}/bad.err"
bad_rc=$?
set -e
[[ "${bad_rc}" -ne 0 ]] || fail "builder must reject PLAN_VALIDATION_RESULT without findings"
rg -qi 'findings' "${TMP}/bad.err" || fail "builder must require findings"
pass "fake verdict payload rejected"

echo "=== WAITING_FOR_INPUT does not require PLAN_VALIDATION_RESULT ==="
cat >"${ev}" <<'EOF'
{
  "conversationId": "c-wait",
  "runId": "run-wait",
  "currentState": "WAITING_FOR_INPUT",
  "runRevision": 1,
  "runManifest": {
    "runId": "run-wait",
    "runtimeSelection": "product",
    "profileId": "create-chain",
    "profileVersion": "1",
    "knowledgePackage": {
      "packageKey": "fixture@1.0.0",
      "knowledgeVersion": "1.0.0",
      "schemaVersion": "1.0.0",
      "packageChecksum": "sha256:fixture",
      "certificationStatus": "CERTIFIED",
      "certificationDigest": "sha256:certificate"
    }
  },
  "committedArtifactKinds": [
    "RUN_MANIFEST"
  ],
  "decodedArtifacts": {},
  "attempts": [],
  "transitions": [
    {
      "toStatus": "WAITING_FOR_INPUT",
      "reason": "need input"
    }
  ],
  "knowledgeContext": {
    "packageRef": {
      "packageKey": "fixture@1.0.0",
      "knowledgeVersion": "1.0.0",
      "schemaVersion": "1.0.0",
      "packageChecksum": "sha256:fixture",
      "certificationStatus": "CERTIFIED",
      "certificationDigest": "sha256:certificate"
    },
    "objectIds": [
      "CIP:GEN-000049"
    ],
    "contentChars": 120
  }
}
EOF
python3 "${BUILD_PY}" \
  --evidence "${ev}" \
  --scenario-id product-ambiguous-waiting \
  --rep 1 \
  --required-facts '[]' \
  --forbidden-facts '[]' \
  --expected-terminal-state WAITING_FOR_INPUT \
  --out "${TMP}/wait-report.json"
jq -e '.terminalState == "WAITING_FOR_INPUT"' "${TMP}/wait-report.json" >/dev/null
jq -e '.validationVerdict == "SKIPPED"' "${TMP}/wait-report.json" >/dev/null
jq -e '.approvalEligible == false' "${TMP}/wait-report.json" >/dev/null
pass "WAITING_FOR_INPUT report without plan validation"

echo "=== exhausted halt report asserts card text, actions, and surviving artifacts ==="
halt_ev="${TMP}/halt-evidence.json"
jq '
  .currentState = "WAITING_FOR_INPUT"
  | .committedArtifactKinds = ["REQUIREMENT_BRIEF", "IMPLEMENTATION_PLAN", "PLAN_VALIDATION_RESULT", "APPROVAL_RECORD"]
  | .transitions = [{
      "toStatus": "WAITING_FOR_INPUT",
      "reason": "__GATE:stage-escalated__That stage is not a candidate for this defect. Allowed stages: design-planning.__GUARD__NAMED_STAGE_OUTSIDE_CANDIDATE_SET"
    }]
' "${ev}" >"${halt_ev}"
python3 "${BUILD_PY}" \
  --evidence "${halt_ev}" \
  --scenario-id product-create-chain-recovery-exhausted-halt \
  --rep 1 \
  --required-facts '[]' \
  --forbidden-facts '[]' \
  --expected-terminal-state WAITING_FOR_INPUT \
  --out "${TMP}/halt-report.json"
jq -e '
  .terminalState == "WAITING_FOR_INPUT"
  and .haltGate == "stage-escalated"
  and .haltGuard == "NAMED_STAGE_OUTSIDE_CANDIDATE_SET"
  and (.haltPrompt | contains("That stage is not a candidate for this defect."))
  and (.haltActions | index("stop-with-report") != null)
' "${TMP}/halt-report.json" >/dev/null \
  || fail "builder must expose the exhausted halt card"
bash "${DIR}/assert-product-run.sh" "${TMP}/halt-report.json"
pass "exhausted halt card report contract"

echo "=== live runner continues discovery WAITING_FOR_INPUT ==="
rg -q 'discoveryAnswers' "${SCENARIO_SH}" \
  || fail "run-product-scenario.sh must read discoveryAnswers from scenarios.json"
rg -q 'discovery_answer' "${SCENARIO_SH}" \
  || fail "run-product-scenario.sh must send per-scenario discovery answers"
jq -e '
  [to_entries[] | select((.value.status // "active") == "active" and .value.pipeline == "create-chain-v1")]
  | all(.value | has("discoveryAnswers"))
' "${DIR}/scenarios.json" >/dev/null \
  || fail "active CREATE scenarios must declare discoveryAnswers"
for phrase in \
  'Capture READY_FOR_PLAN' \
  'Use platform defaults' \
  'revise the requirement draft before approval'; do
  if jq -e --arg p "${phrase}" '
    [to_entries[] | select((.value.status // "active") == "active")
      | (.value.prompt // ""), (.value.discoveryAnswers // [])[]]
    | any(test($p; "i"))
  ' "${DIR}/scenarios.json" >/dev/null; then
    fail "active scenarios must not embed process phrase: ${phrase}"
  fi
done
pass "discovery WAITING_FOR_INPUT continue contract"

ROOT="$(cd "${DIR}/../../.." && pwd)"
COMPOSE_FILE="${ROOT}/infrastructure/docker-compose.yml"
EVALUATOR_DOCKERFILE="${ROOT}/ai-service/e2e/evaluator/Dockerfile"

echo "=== compose and properties use one package path ==="
rg -q 'QIP_KNOWLEDGE_PATH' "${COMPOSE_FILE}" \
  || fail "docker-compose.yml must set QIP_KNOWLEDGE_PATH"
rg -q 'QIP_KNOWLEDGE_HOST_PATH' "${COMPOSE_FILE}" \
  || fail "docker-compose.yml must mount QIP_KNOWLEDGE_HOST_PATH"
! rg -q 'QIP_KNOWLEDGE_(FULL|SLIM)_PATH' "${COMPOSE_FILE}" \
  || fail "docker-compose.yml must not retain FULL/SLIM package paths"
! rg -q \
  'QIP_KNOWLEDGE_ARTIFACT_ROOT|QIP_KNOWLEDGE_ARTIFACT_ID|QIP_KNOWLEDGE_PROFILE' \
  "${COMPOSE_FILE}" "${GATE_SH}" "${ROOT}/ai-service/src/main/resources/application.properties" \
  || fail "legacy knowledge artifact root/id/profile must be absent from compose/gate/properties"
pass "package path contract"

echo "=== compose evaluator sidecar contract ==="
[[ -f "${COMPOSE_FILE}" ]] || fail "missing ${COMPOSE_FILE}"
rg -q 'qip-e2e-evaluator' "${COMPOSE_FILE}" \
  || fail "docker-compose.yml must define qip-e2e-evaluator"
rg -q 'profiles: \[ai-e2e\]|profiles:\s*\[ai-e2e\]' "${COMPOSE_FILE}" \
  || fail "docker-compose.yml must use ai-e2e profile for evaluator"
rg -q '"8100:8099"' "${COMPOSE_FILE}" \
  || fail "docker-compose.yml must map evaluator port 8100:8099"
rg -q 'read_only: true' "${COMPOSE_FILE}" \
  || fail "docker-compose.yml must set read_only on evaluator"
rg -q 'ai-service-dev\.env' "${COMPOSE_FILE}" \
  || fail "docker-compose.yml must reference ai-service-dev.env for evaluator"
pass "compose evaluator sidecar contract"

echo "=== evaluator Dockerfile contract ==="
[[ -f "${EVALUATOR_DOCKERFILE}" ]] || fail "missing ${EVALUATOR_DOCKERFILE}"
rg -q 'FROM python:3\.12' "${EVALUATOR_DOCKERFILE}" \
  || fail "evaluator Dockerfile must pin Python 3.12"
rg -q '^USER evaluator' "${EVALUATOR_DOCKERFILE}" \
  || fail "evaluator Dockerfile must run as USER evaluator"
pass "evaluator Dockerfile contract"

echo "=== evaluate-plan caller timeout ==="
rg -q 'timeout=135' "${EVAL_PY}" \
  || fail "evaluate-plan.py must use timeout=135 for evaluator HTTP calls"
pass "evaluate-plan caller timeout"

echo "=== quality gate evaluator URL precedence ==="
rg -q '127\.0\.0\.1:9' "${GATE_SH}" \
  || fail "run-quality-gate.sh must define stub evaluator URL 127.0.0.1:9"
rg -q 'resolve_evaluator_url' "${GATE_SH}" \
  || fail "run-quality-gate.sh must define resolve_evaluator_url"
rg -q 'ai-e2e' "${GATE_SH}" \
  || fail "run-quality-gate.sh must reference ai-e2e profile for evaluator"
rg -q 'qip-e2e-evaluator' "${GATE_SH}" \
  || fail "run-quality-gate.sh must start qip-e2e-evaluator when auto-resolving URL"
pass "quality gate evaluator URL precedence markers"

echo "=== run-product-scenario still requires --evaluator-url ==="
set +e
bash "${SCENARIO_SH}" \
  --scenario product-greetings \
  --rep 1 \
  --base-url "http://127.0.0.1:9" \
  --report "${TMP}/missing-eval-report.json" 2>"${TMP}/missing-eval.err"
missing_eval_rc=$?
set -e
[[ "${missing_eval_rc}" -eq 2 ]] \
  || fail "run-product-scenario.sh must exit 2 when --evaluator-url is omitted"
rg -q -- '--evaluator-url' "${TMP}/missing-eval.err" \
  || fail "run-product-scenario.sh usage must mention --evaluator-url"
pass "run-product-scenario requires --evaluator-url"

echo "=== resolve_evaluator_url docker stub precedence ==="
STUB_BIN="${TMP}/stub-bin"
mkdir -p "${STUB_BIN}"
DOCKER_STUB_LOG="${TMP}/docker-invocations.log"
: >"${DOCKER_STUB_LOG}"
cat >"${STUB_BIN}/docker" <<'EOF'
#!/usr/bin/env bash
printf '%s\n' "$*" >> "${DOCKER_STUB_LOG:?}"
exit 0
EOF
chmod +x "${STUB_BIN}/docker"
cat >"${STUB_BIN}/curl" <<'EOF'
#!/usr/bin/env bash
if [[ "$*" == *"/health"* ]]; then
  exit 0
fi
exit 1
EOF
chmod +x "${STUB_BIN}/curl"

resolve_contract() {
  local stub_mode="$1"
  local evaluator_url="${2:-}"
  local -a env_args=(
    PATH="${STUB_BIN}:${PATH}"
    DOCKER_STUB_LOG="${DOCKER_STUB_LOG}"
    PRODUCT_PIPELINE_STUB_MODE="${stub_mode}"
    PRODUCT_PIPELINE_CONTRACT_RESOLVE_ONLY=1
  )
  : >"${DOCKER_STUB_LOG}"
  if [[ -n "${evaluator_url}" ]]; then
    env_args+=(EVALUATOR_URL="${evaluator_url}")
    env "${env_args[@]}" bash "${GATE_SH}" \
      --runs 1 \
      --report-dir "${TMP}/resolve-report" \
      --base-url "http://127.0.0.1:9"
    return
  fi
  env -u EVALUATOR_URL "${env_args[@]}" bash "${GATE_SH}" \
    --runs 1 \
    --report-dir "${TMP}/resolve-report" \
    --base-url "http://127.0.0.1:9"
}

out="$(resolve_contract 1 "")"
[[ "${out}" == *"EVALUATOR_URL=http://127.0.0.1:9"* ]] \
  || fail "stub mode must resolve to 127.0.0.1:9"
[[ ! -s "${DOCKER_STUB_LOG}" ]] \
  || fail "stub mode must not invoke docker for evaluator"

out="$(resolve_contract 0 "http://eval.example/evaluate")"
[[ "${out}" == *"EVALUATOR_URL=http://eval.example/evaluate"* ]] \
  || fail "explicit evaluator URL must be preserved"
[[ ! -s "${DOCKER_STUB_LOG}" ]] \
  || fail "explicit evaluator URL must not invoke docker"

out="$(resolve_contract 0 "")"
[[ "${out}" == *"EVALUATOR_URL=http://localhost:8100/evaluate"* ]] \
  || fail "missing URL must default to local evaluator endpoint"
[[ -s "${DOCKER_STUB_LOG}" ]] \
  || fail "missing URL must invoke docker to start local evaluator"
rg -q -- '--profile' "${DOCKER_STUB_LOG}" \
  && rg -q 'ai-e2e' "${DOCKER_STUB_LOG}" \
  && rg -q 'qip-e2e-evaluator' "${DOCKER_STUB_LOG}" \
  || fail "auto-start must use ai-e2e profile and qip-e2e-evaluator"
pass "resolve_evaluator_url docker stub precedence"

SCENARIOS_FILE="${DIR}/scenarios.json"
ASSERT_SH="${DIR}/assert-product-run.sh"

echo "=== WAITING_FOR_IMPLEMENT causes one Implement <approvedPlanContentHash> turn ==="
rg -q 'WAITING_FOR_IMPLEMENT' "${SCENARIO_SH}" \
  || fail "run-product-scenario.sh must handle WAITING_FOR_IMPLEMENT"
rg -q 'Implement ' "${SCENARIO_SH}" \
  || fail "run-product-scenario.sh must send Implement <hash> turn"
rg -q 'targetContentHash|approvedPlanContentHash' "${SCENARIO_SH}" \
  || fail "run-product-scenario.sh must read approved plan hash from evidence"
python3 - "${SCENARIO_SH}" <<'PY'
import pathlib
import sys

src = pathlib.Path(sys.argv[1]).read_text()
idx = src.find("WAITING_FOR_IMPLEMENT")
if idx < 0:
    raise SystemExit("missing WAITING_FOR_IMPLEMENT")
if "Implement" not in src[idx : idx + 1200]:
    raise SystemExit("Implement turn must follow WAITING_FOR_IMPLEMENT")
# Terminal PLAN_APPROVED stub/live branches must not send Implement.
for marker in ('expected_state}" == "PLAN_APPROVED"', 'current_state}" == "PLAN_APPROVED"'):
    start = 0
    while True:
        pos = src.find(marker, start)
        if pos < 0:
            break
        block = src[pos : pos + 500]
        if "send_turn" in block and "Implement" in block:
            raise SystemExit("PLAN_APPROVED must never cause an Implement turn")
        start = pos + 1
print("ok")
PY
pass "WAITING_FOR_IMPLEMENT Implement turn contract"

echo "=== CHAIN_MATERIALIZED requires materialization evidence kinds ==="
rg -q 'CHAIN_MATERIALIZED' "${ASSERT_SH}" \
  || fail "assert-product-run.sh must assert CHAIN_MATERIALIZED"
for kind in MATERIALIZATION_RESULT CATALOG_CHAIN_SNAPSHOT RECONCILE_RESULT; do
  rg -q "${kind}" "${ASSERT_SH}" \
    || fail "assert-product-run.sh must require ${kind} for CHAIN_MATERIALIZED"
done
rg -q 'materializedChainId' "${ASSERT_SH}" \
  || fail "assert-product-run.sh must require materializedChainId"
rg -q 'reconcileMatches|matches' "${ASSERT_SH}" \
  || fail "assert-product-run.sh must check reconcile verdict"
pass "CHAIN_MATERIALIZED evidence kinds contract"

echo "=== create-chain greetings scenario and APPLICABLE script patch ==="
jq -e '."product-create-chain-greetings".pipeline == "create-chain-v1"' "${SCENARIOS_FILE}" >/dev/null \
  || fail "product-create-chain-greetings must use create-chain-v1"
jq -e '."product-create-chain-greetings".terminalState == "CHAIN_MATERIALIZED"' "${SCENARIOS_FILE}" >/dev/null \
  || fail "product-create-chain-greetings must terminate at CHAIN_MATERIALIZED"
jq -e '."product-create-chain-greetings".profileId == "create-chain"' "${SCENARIOS_FILE}" >/dev/null \
  || fail "product-create-chain-greetings must set profileId=create-chain"
jq -e '."product-create-chain-greetings".retainCatalogChain == true' "${SCENARIOS_FILE}" >/dev/null \
  || fail "product-create-chain-greetings must retain catalog chains (no teardown)"
jq -e '."product-create-chain-greetings".uniqueChainNamePrefix == "AiCreateSmoke"' "${SCENARIOS_FILE}" >/dev/null \
  || fail "product-create-chain-greetings must use AiCreateSmoke unique name prefix"
rg -q 'UNIQUE_CHAIN_NAME|uniqueChainNamePrefix|retainCatalogChain' "${DIR}/run-product-scenario.sh" \
  || fail "run-product-scenario.sh must expand unique chain names and retain catalog chains"
rg -q 'cip-script-generator' "${ASSERT_SH}" \
  || fail "assert-product-run.sh must assert APPLICABLE cip-script-generator GraphPatchArtifact"
rg -q 'APPLICABLE|applicability' "${ASSERT_SH}" \
  || fail "assert-product-run.sh must check GraphPatchArtifact applicability"
pass "create-chain greetings GraphPatch contract"

echo "=== all active create-chain scenarios retain catalog ==="
for create_chain_scenario in \
  product-create-chain-greetings \
  product-create-chain-dual-trigger-greetings \
  product-create-chain-error-handling \
  product-create-chain-lang-router \
  product-create-chain-page-walker \
  product-create-chain-ambiguous-waiting \
  product-create-chain-multi-turn-revision; do
  jq -e --arg s "${create_chain_scenario}" '
    .[$s].pipeline == "create-chain-v1"
    and .[$s].terminalState == "CHAIN_MATERIALIZED"
    and .[$s].profileId == "create-chain"
    and .[$s].profileVersion == "2"
    and .[$s].retainCatalogChain == true
    and (.[$s].uniqueChainNamePrefix | type == "string" and length > 0)
    and ((.[$s].catalog.requiredTypes // []) | length > 0)
  ' "${SCENARIOS_FILE}" >/dev/null \
    || fail "${create_chain_scenario} must be create-chain@2 with retain + catalog types"
done
jq -e '."product-create-chain-error-handling".uniqueChainNamePrefix == "AiCreateEhSmoke"' \
  "${SCENARIOS_FILE}" >/dev/null \
  || fail "EH create-chain must use AiCreateEhSmoke prefix"
jq -e '."product-create-chain-lang-router".uniqueChainNamePrefix == "AiCreateRouteSmoke"' \
  "${SCENARIOS_FILE}" >/dev/null \
  || fail "lang-router create-chain must use AiCreateRouteSmoke prefix"
jq -e '."product-create-chain-error-handling".catalog.requiredTypes | index("try-catch-finally-2") != null' \
  "${SCENARIOS_FILE}" >/dev/null \
  || fail "EH create-chain catalog must require try-catch-finally-2"
jq -e '."product-create-chain-lang-router".catalog.requiredTypes | index("if") != null' \
  "${SCENARIOS_FILE}" >/dev/null \
  || fail "lang-router create-chain catalog must require if"
pass "create-chain EH and lang-router retain catalog contract"

echo "=== stub mode never starts Docker ==="
rg -q 'PRODUCT_PIPELINE_STUB_MODE' "${GATE_SH}" \
  || fail "run-quality-gate.sh must honor stub mode"
rg -q 'SKIP deploy|stub mode' "${GATE_SH}" \
  || fail "run-quality-gate.sh must skip Compose in stub mode"
pass "stub mode docker skip markers"

echo "=== gate run records a compiler pipeline digest ==="
rg -q 'compilerPackageDigest|pipelineIndexDigest|compilerPipelineDigest' "${BUILD_PY}" \
  || fail "build-report-from-evidence.py must expose compiler pipeline digest"
rg -q 'compilerPackageDigest|pipelineIndexDigest|compilerPipelineDigest' "${GATE_SH}" \
  || fail "run-quality-gate.sh must compare compiler digests within a gate run"
pass "compiler pipeline digest contract"

echo "=== expected run count equals selected scenarios times repetitions ==="
rg -q 'SCENARIO_IDS\[@\]' "${GATE_SH}" \
  || fail "run-quality-gate.sh must derive EXPECTED_TOTAL from selected scenarios"
rg -q 'expected 5 active' "${GATE_SH}" \
  && fail "run-quality-gate.sh must not hard-require exactly five scenarios"
rg -q '\[\[ "\$\{#SCENARIO_IDS\[@\]\}" -eq 5 \]\]' "${GATE_SH}" \
  && fail "run-quality-gate.sh must not require exactly five scenarios"
pass "dynamic expected run count contract"

echo "=== gate deploy recreates qip-ai-service but not evaluator ==="
rg -q 'force-recreate' "${GATE_SH}" \
  || fail "run-quality-gate.sh must force-recreate product services"
rg -q 'qip-ai-service' "${GATE_SH}" \
  || fail "run-quality-gate.sh must recreate qip-ai-service"
python3 - "${GATE_SH}" <<'PY'
import pathlib
import re
import sys

src = pathlib.Path(sys.argv[1]).read_text()
# Product stack restart/recreate helpers must not also recreate the evaluator.
for match in re.finditer(
    r"(?:restart_stack_for_tier|recreate_product_runtime|force-recreate)"
    r"[^\n]*\n(?:.*\n){0,45}",
    src,
):
    block = match.group(0)
    if "qip-e2e-evaluator" in block and "ai-e2e" not in block:
        raise SystemExit("gate recreate must not recreate qip-e2e-evaluator")
print("ok")
PY
pass "knowledge package recreate contract"

echo "=== create-chain@2 scenario pins (new CREATE) ==="
rg -q 'profileId|create-chain' "${GATE_SH}" \
  || fail "gate must read scenario profileId including create-chain"
jq -e '
  [to_entries[] | select(
    (.value.status // "active") == "active"
    and .value.pipeline == "create-chain-v1"
    and (.value.recovery.exhaustHalt != true)
  )]
  | length >= 8
  and all(
    .value.pipeline == "create-chain-v1"
    and .value.profileId == "create-chain"
    and .value.profileVersion == "2"
    and .value.terminalState == "CHAIN_MATERIALIZED"
    and .value.retainCatalogChain == true
  )
' "${SCENARIOS_FILE}" >/dev/null \
  || fail "active create-chain@2 scenarios other than exhaustHalt must pin CHAIN_MATERIALIZED retain=true"
legacy_create_plan="$(printf '%s-%s' create plan-v1)"
for legacy in "${legacy_create_plan}" design-first structure-e2e; do
  if jq -e --arg p "${legacy}" '[.. | strings] | index($p) != null' "${SCENARIOS_FILE}" >/dev/null; then
    fail "product scenarios must not mention ${legacy}"
  fi
done
pass "create-chain@2 scenario pin contract"

echo "=== create-chain@2 design-input GENERATE choice ==="
rg -q 'design-input|Generate full IDS|designInputChoice' "${DIR}/run-product-scenario.sh" \
  || fail "run-product-scenario.sh must answer design-input WAITING_FOR_INPUT"
jq -e '
  [to_entries[] | select((.value.status // "active") == "active" and .value.pipeline == "create-chain-v1")]
  | all(.value.designInputChoice == "Generate full IDS")
' "${SCENARIOS_FILE}" >/dev/null \
  || fail "active CREATE scenarios must default designInputChoice to Generate full IDS"
pass "create-chain@2 design-input GENERATE contract"

echo "=== compare-and-patch product scenarios ==="
PATCH_SH="${DIR}/run-patch-scenario.sh"
ASSERT_PATCH_SH="${DIR}/assert-patch-run.sh"
SEED_SH="${DIR}/scripts/seed-catalog-chain.sh"
[[ -f "${PATCH_SH}" ]] || fail "missing run-patch-scenario.sh"
[[ -f "${ASSERT_PATCH_SH}" ]] || fail "missing assert-patch-run.sh"
[[ -f "${SEED_SH}" ]] || fail "missing scripts/seed-catalog-chain.sh"
bash -n "${PATCH_SH}"
bash -n "${ASSERT_PATCH_SH}"
bash -n "${SEED_SH}"
rg -q 'chat-turn\.sh' "${PATCH_SH}" \
  || fail "run-patch-scenario.sh must call chat-turn.sh"
if rg -q '/api/v1/harness' "${PATCH_SH}" "${SEED_SH}"; then
  fail "patch e2e must not call the thin harness"
fi
rg -q 'E2E_CHAT_ATTACHMENT|e2e_chain_attachment' "${PATCH_SH}" \
  || fail "run-patch-scenario.sh must send the open-chain attachment"
rg -q 'apply-chain-patch|e2e_extract_apply_chain_patch_decision' "${PATCH_SH}" \
  || fail "run-patch-scenario.sh must answer the apply-chain-patch decision card"
rg -q 'E2E_CHAT_ATTACHMENT' "${DIR}/scripts/chat-turn.sh" \
  || fail "chat-turn.sh must send E2E_CHAT_ATTACHMENT"
rg -q 'E2E_CHAT_DECISION_JSON' "${DIR}/scripts/chat-turn.sh" \
  || fail "chat-turn.sh must send E2E_CHAT_DECISION_JSON"
rg -q 'e2e_extract_apply_chain_patch_decision' "${DIR}/scripts/lib.sh" \
  || fail "lib.sh must extract apply-chain-patch decision cards"
rg -q 'compare-and-patch' "${GATE_SH}" \
  || fail "run-quality-gate.sh must dispatch compare-and-patch scenarios"
rg -q 'run-patch-scenario.sh' "${GATE_SH}" \
  || fail "run-quality-gate.sh must invoke run-patch-scenario.sh"
jq -e '
  [to_entries[] | select((.value.status // "active") == "active" and .value.pipeline == "compare-and-patch")]
  | length == 4
  and all(
    .value.terminalState == "CHAIN_PATCHED"
    and .value.retainCatalogChain == true
    and ((.value.uniqueChainNamePrefix | type) == "string" and (.value.uniqueChainNamePrefix | length) > 0)
    and ((.value.prompts | type) == "array" and (.value.prompts | length) > 0)
    and ((.value.seed.elements | type) == "array" and (.value.seed.elements | length) > 0)
  )
' "${SCENARIOS_FILE}" >/dev/null \
  || fail "exactly four active compare-and-patch CHAIN_PATCHED retain scenarios required"
jq -e '."product-patch-chain-multi-turn".prompts | length == 2' "${SCENARIOS_FILE}" >/dev/null \
  || fail "multi-turn patch scenario must send two separate prompts"
jq -e '."product-patch-chain-try-catch".status == "active"' "${SCENARIOS_FILE}" >/dev/null \
  || fail "try-catch wrap patch must stay active after live validation"
jq -e '."product-patch-chain-replace-subgraph".status == "inactive"' "${SCENARIOS_FILE}" >/dev/null \
  || fail "replace-subgraph patch stays inactive beside the wrap scenario until that wrap run is green"
jq -e '."product-patch-chain-replace-subgraph".catalog.minTypeCounts.script == 2' "${SCENARIOS_FILE}" >/dev/null \
  || fail "replace-subgraph patch must require two scripts after the swap"
jq -e '
  [to_entries[] | select((.value.status // "active") == "active" and .value.pipeline == "compare-and-patch")]
  | all(.value.prompts | all((contains("open catalog chain") | not)))
' "${SCENARIOS_FILE}" >/dev/null \
  || fail "active patch prompts must look like a UI change request, not an open-chain classifier cue"
pass "compare-and-patch product scenario contract"

echo "=== apply-chain-patch decision extraction ==="
# shellcheck source=scripts/lib.sh
source "${DIR}/scripts/lib.sh"
sse_fix="${TMP}/patch-decision.sse"
cat >"${sse_fix}" <<'EOF'
event: meta
data: {"conversationId":"conv-patch"}

event: decision
data: {"id":"chain-patch:abc","kind":"approve","question":"Change Return greeting.","revision":0,"actions":["apply-chain-patch","request-changes"],"artifactType":"CHAIN_PATCH","artifactHash":"sha256:abc"}

event: done
data: conv-patch
EOF
got=""
if ! got="$(e2e_extract_apply_chain_patch_decision "${sse_fix}")"; then
  fail "extractor missed apply-chain-patch card"
fi
jq -e '.action == "apply-chain-patch" and .artifactHash == "sha256:abc" and .artifactType == "CHAIN_PATCH"' \
  <<<"${got}" >/dev/null \
  || fail "extractor must bind apply-chain-patch to the card hash"
pass "apply-chain-patch decision extraction"

echo "=== chat-turn payload includes attachment and decision ==="
payload_out="${TMP}/chat-payload.json"
attachment_text='## Current Chain: Demo (ID: chain-42)'
decision_text='{"action":"apply-chain-patch","artifactType":"CHAIN_PATCH","artifactHash":"sha256:abc","revision":0}'
python3 - "-" "change the script" "" "${attachment_text}" "${decision_text}" >"${payload_out}" <<'PY'
import json
import sys

conv_id, message, hint, attachment, decision_json = sys.argv[1:6]
body = {"message": message}
if conv_id and conv_id != "-":
    body["conversationId"] = conv_id
if hint:
    body["scenarioHint"] = hint
if attachment:
    body["attachment"] = attachment
if decision_json:
    body["decision"] = json.loads(decision_json)
print(json.dumps(body))
PY
jq -e '
  .attachment == "## Current Chain: Demo (ID: chain-42)"
  and .decision.action == "apply-chain-patch"
  and .decision.artifactHash == "sha256:abc"
  and (has("scenarioHint") | not)
' "${payload_out}" >/dev/null \
  || fail "chat payload must carry attachment and decision without a scenario hint"
pass "chat-turn attachment and decision payload"

echo "=== create-chain@1 backward-compat remains loadable ==="
AI_SVC_ROOT="$(cd "${DIR}/../.." && pwd)"
[[ -f "${AI_SVC_ROOT}/src/main/resources/product-pipelines/profiles/create-chain-v1.yaml" ]] \
  || fail "create-chain-v1.yaml must remain for historical bindings"
[[ -f "${AI_SVC_ROOT}/src/main/resources/product-pipelines/profiles/create-chain-v2.yaml" ]] \
  || fail "create-chain-v2.yaml must exist for new CREATE"
rg -q 'assertEquals\("1", resumed\.runManifest\(\)\.profileVersion|create-chain@1' \
  "${AI_SVC_ROOT}/src/test/java/org/qubership/integration/platform/ai/productpipeline/create/CreateChainProductPipelineRestartIT.java" \
  || fail "CreateChainProductPipelineRestartIT must cover create-chain@1 restart"
pass "create-chain@1 backward-compat contract"

echo "=== forbidden facts ignore negation phrases and skill metadata ==="
rg -q '_forbidden_haystack|_positive_fact_texts' "${BUILD_PY}" \
  || fail "build-report-from-evidence.py must use a dedicated forbidden-facts haystack"
neg_ev="${TMP}/forbidden-negation-evidence.json"
cat >"${neg_ev}" <<'EOF'
{
  "conversationId": "c-forbid-neg",
  "currentState": "CHAIN_MATERIALIZED",
  "runId": "run-forbid-neg",
  "runManifest": {
    "runId": "run-forbid-neg",
    "runtimeSelection": "product",
    "profileId": "create-chain-v1",
    "profileVersion": "1",
    "knowledgePackage": {
      "packageKey": "fixture@1.0.0",
      "knowledgeVersion": "1.0.0",
      "schemaVersion": "1.0.0",
      "packageChecksum": "sha256:fixture",
      "certificationStatus": "CERTIFIED",
      "certificationDigest": "sha256:certificate"
    }
  },
  "committedArtifactKinds": [
    "IMPLEMENTATION_PLAN",
    "PLAN_VALIDATION_RESULT",
    "APPROVAL_RECORD",
    "REQUIREMENT_BRIEF",
    "CHAIN_PLAN_GRAPH",
    "CATALOG_CHAIN_SNAPSHOT",
    "MATERIALIZATION_RESULT",
    "RECONCILE_RESULT"
  ],
  "decodedArtifacts": {
    "IMPLEMENTATION_PLAN#p1": {
      "planText": "No service calls. No APIHub.",
      "endpointFacts": [
        "GET /greetings"
      ],
      "branchFacts": [],
      "scriptOutcomes": [
        "Hello"
      ],
      "serviceBindings": [
        "step-6-cip-service-call-generator queries GET"
      ],
      "negativeConstraints": [
        "No service calls",
        "No APIHub"
      ]
    },
    "PLAN_VALIDATION_RESULT#v1": {
      "findings": []
    },
    "APPROVAL_RECORD#a1": {
      "targetContentHash": "hash-1"
    },
    "REQUIREMENT_BRIEF#r1": {
      "facts": [
        {
          "polarity": "POSITIVE",
          "text": "GET /greetings"
        },
        {
          "polarity": "NEGATIVE",
          "text": "No service calls"
        },
        {
          "polarity": "NEGATIVE",
          "text": "No APIHub"
        }
      ],
      "summary": "Greetings with No APIHub"
    },
    "CHAIN_PLAN_GRAPH#g1": {
      "nodes": [
        {
          "id": "t1",
          "type": "http-trigger"
        },
        {
          "id": "s1",
          "type": "script"
        }
      ],
      "edges": [
        {
          "from": "t1",
          "to": "s1"
        }
      ]
    },
    "CATALOG_CHAIN_SNAPSHOT#c1": {
      "chainId": "chain-1",
      "elements": [
        {
          "elementId": "t1",
          "type": "http-trigger"
        },
        {
          "elementId": "s1",
          "type": "script"
        }
      ]
    },
    "GRAPH_PATCH_ARTIFACT#gp1": {
      "ownerCapabilityId": "cip-service-call-generator",
      "applicability": "NOT_APPLICABLE",
      "applicabilitySignals": [
        "Bind catalog operations on existing service-call nodes"
      ]
    },
    "MATERIALIZATION_RESULT#m1": {
      "chainId": "chain-1"
    },
    "RECONCILE_RESULT#r1": {
      "matches": true
    }
  },
  "attempts": [],
  "transitions": [],
  "materializedChainId": "chain-1",
  "reconcileMatches": true,
  "knowledgeContext": {
    "packageRef": {
      "packageKey": "fixture@1.0.0",
      "knowledgeVersion": "1.0.0",
      "schemaVersion": "1.0.0",
      "packageChecksum": "sha256:fixture",
      "certificationStatus": "CERTIFIED",
      "certificationDigest": "sha256:certificate"
    },
    "objectIds": [
      "CIP:GEN-000049"
    ],
    "contentChars": 120
  }
}
EOF
python3 "${BUILD_PY}" \
  --evidence "${neg_ev}" \
  --scenario-id product-create-chain-greetings \
  --rep 1 \
  --required-facts '["GET /greetings"]' \
  --forbidden-facts '["service-call","APIHub"]' \
  --expected-terminal-state CHAIN_MATERIALIZED \
  --out "${TMP}/forbidden-negation-report.json"
jq -e '.presentForbiddenFacts == []' "${TMP}/forbidden-negation-report.json" >/dev/null \
  || fail "negation phrases and skill metadata must not set presentForbiddenFacts"

pos_ev="${TMP}/forbidden-positive-evidence.json"
python3 - "${neg_ev}" "${pos_ev}" <<'PY'
import json
import pathlib
import sys

src = json.loads(pathlib.Path(sys.argv[1]).read_text())
src["decodedArtifacts"]["CATALOG_CHAIN_SNAPSHOT#c1"]["elements"].append(
    {"elementId": "sc1", "type": "service-call"}
)
src["decodedArtifacts"]["CHAIN_PLAN_GRAPH#g1"]["nodes"].append(
    {"id": "sc1", "type": "service-call"}
)
pathlib.Path(sys.argv[2]).write_text(json.dumps(src))
PY
python3 "${BUILD_PY}" \
  --evidence "${pos_ev}" \
  --scenario-id product-create-chain-greetings \
  --rep 1 \
  --required-facts '["GET /greetings"]' \
  --forbidden-facts '["service-call","APIHub"]' \
  --expected-terminal-state CHAIN_MATERIALIZED \
  --out "${TMP}/forbidden-positive-report.json"
jq -e '.presentForbiddenFacts == ["service-call"]' "${TMP}/forbidden-positive-report.json" >/dev/null \
  || fail "real service-call topology must set presentForbiddenFacts"
pass "forbidden facts negation vs topology contract"

echo "=== required HTTP endpoint facts tolerate split method/path ==="
rg -q '_required_fact_present|_structured_http_endpoints|_http_endpoint_in_text' "${BUILD_PY}" \
  || fail "build-report-from-evidence.py must match METHOD /path beyond literal substring"
split_ev="${TMP}/required-split-endpoint-evidence.json"
python3 - "${neg_ev}" "${split_ev}" <<'PY'
import json
import pathlib
import sys

src = json.loads(pathlib.Path(sys.argv[1]).read_text())
# Remove contiguous "GET /greetings" while keeping method+path evidence.
src["decodedArtifacts"]["IMPLEMENTATION_PLAN#p1"]["planText"] = (
    "## Endpoints\n- GET\n- /greetings\n- internal\n"
)
src["decodedArtifacts"]["IMPLEMENTATION_PLAN#p1"]["endpointFacts"] = []
src["decodedArtifacts"]["REQUIREMENT_BRIEF#r1"] = {
    "facts": [
        {
            "polarity": "POSITIVE",
            "text": 'Chain receives GET on internal route "/greetings"',
        }
    ],
    "summary": 'GET on internal route "/greetings"',
    "inputs": ['GET request on internal route "/greetings"'],
}
src["decodedArtifacts"]["NORMALIZED_DESIGN_FLOW#f1"] = {
    "trigger": {
        "kind": "http",
        "operationName": "GET",
        "endpointOrTopic": "/greetings",
    },
    "steps": [{"kind": "script"}],
}
pathlib.Path(sys.argv[2]).write_text(json.dumps(src))
PY
python3 "${BUILD_PY}" \
  --evidence "${split_ev}" \
  --scenario-id product-create-chain-greetings \
  --rep 1 \
  --required-facts '["GET /greetings","script"]' \
  --forbidden-facts '["service-call","APIHub"]' \
  --expected-terminal-state CHAIN_MATERIALIZED \
  --out "${TMP}/required-split-endpoint-report.json"
jq -e '.missingRequiredFacts == []' "${TMP}/required-split-endpoint-report.json" >/dev/null \
  || fail "split METHOD/path + structured trigger must satisfy GET /greetings"
# Contiguous literal still works (baseline fixture).
python3 "${BUILD_PY}" \
  --evidence "${neg_ev}" \
  --scenario-id product-create-chain-greetings \
  --rep 1 \
  --required-facts '["GET /greetings"]' \
  --forbidden-facts '[]' \
  --expected-terminal-state CHAIN_MATERIALIZED \
  --out "${TMP}/required-literal-endpoint-report.json"
jq -e '.missingRequiredFacts == []' "${TMP}/required-literal-endpoint-report.json" >/dev/null \
  || fail "literal GET /greetings must still satisfy required facts"
# Missing path must still fail.
miss_ev="${TMP}/required-missing-endpoint-evidence.json"
python3 - "${split_ev}" "${miss_ev}" <<'PY'
import json
import pathlib
import sys

src = json.loads(pathlib.Path(sys.argv[1]).read_text())
src["decodedArtifacts"]["IMPLEMENTATION_PLAN#p1"]["planText"] = "script only"
src["decodedArtifacts"].pop("NORMALIZED_DESIGN_FLOW#f1", None)
src["decodedArtifacts"]["REQUIREMENT_BRIEF#r1"] = {
    "facts": [{"polarity": "POSITIVE", "text": "script returns hello"}],
    "summary": "script only",
}
pathlib.Path(sys.argv[2]).write_text(json.dumps(src))
PY
python3 "${BUILD_PY}" \
  --evidence "${miss_ev}" \
  --scenario-id product-create-chain-greetings \
  --rep 1 \
  --required-facts '["GET /greetings"]' \
  --forbidden-facts '[]' \
  --expected-terminal-state CHAIN_MATERIALIZED \
  --out "${TMP}/required-missing-endpoint-report.json"
jq -e '.missingRequiredFacts == ["GET /greetings"]' \
  "${TMP}/required-missing-endpoint-report.json" >/dev/null \
  || fail "absent endpoint evidence must keep GET /greetings missing"
pass "required HTTP endpoint split-path contract"

echo "ALL live runner contracts PASS"
