#!/usr/bin/env bash
# Run the checkpoint-restart campaign without calling an LLM.
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${DIR}/../../.." && pwd)"
LEVEL="deterministic"
REPORT_DIR="${REPORT_DIR:-/tmp/rocky-checkpoint-restart-$(date +%Y%m%d-%H%M%S)}"

usage() {
  cat <<'EOF'
Usage: run-checkpoint-restart-campaign.sh [--level focused|deterministic|full] [--report-dir DIR]

  focused       Restart backend and UI tests only.
  deterministic Focused tests plus the Rocky recovery and binding/mapping regression gates.
  full          Deterministic gates plus PostgreSQL-backed Flow persistence tests.

Live model samples are intentionally separate because they require an approved provider/model and
explicit egress authorization. See docs/plans/restart-checkpoint-full-campaign.md.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --level)
      LEVEL="${2:?missing level}"
      shift 2
      ;;
    --report-dir)
      REPORT_DIR="${2:?missing report directory}"
      shift 2
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

case "${LEVEL}" in
  focused|deterministic|full) ;;
  *)
    echo "Unsupported level: ${LEVEL}" >&2
    exit 2
    ;;
esac

mkdir -p "${REPORT_DIR}"
cd "${REPO_ROOT}"

FOCUSED_TESTS="ProductPipelineDerivedRunTest,ProductPipelineRunStoreTest,"
FOCUSED_TESTS+="ProvidedIdsFlowOrchestratorTest,LatestIterationJpaInstanceOperationsTest,"
FOCUSED_TESTS+="CreateChainApplicationFacadeTest,ChatDecisionServiceTest,ChatExecutionServiceTest"

mvn -pl ai-service \
  -Dqip.ai.qipknowledge.build.skip=true \
  -Dtest="${FOCUSED_TESTS}" \
  test 2>&1 | tee "${REPORT_DIR}/focused-backend.log"

(
  cd ui
  npm test -- \
    --runInBand \
    tests/components/ai/AiDecisionCard.test.tsx \
    tests/components/ai/chatDecisionUtils.test.ts
) 2>&1 | tee "${REPORT_DIR}/focused-ui.log"

(
  cd ui
  npx eslint \
    src/components/ai/AiDecisionCard.tsx \
    src/components/ai/chatDecisionUtils.ts \
    tests/components/ai/AiDecisionCard.test.tsx \
    tests/components/ai/chatDecisionUtils.test.ts
) 2>&1 | tee "${REPORT_DIR}/focused-ui-lint.log"

if [[ "${LEVEL}" == "deterministic" || "${LEVEL}" == "full" ]]; then
  "${DIR}/run-recovery-deterministic.sh" \
    2>&1 | tee "${REPORT_DIR}/recovery-deterministic.log"

  EXPANDED_TESTS="BindingMappingContractInvestigationTest,"
  EXPANDED_TESTS+="ChainSemanticMappingPlacementInvestigationTest,ScopedBindingMappingCompileTest,"
  EXPANDED_TESTS+="ResolvedServiceCallBindingTest,ServiceCallBindingResolverTest,CreateRunBindingStoreTest,"
  EXPANDED_TESTS+="CatalogBindingMatcherTest,DefaultExecutorCatalogBindingAdapterTest,"
  EXPANDED_TESTS+="DefaultChainSemanticGraphCompilerTest,DefaultChainSemanticRevisionValidatorTest,"
  EXPANDED_TESTS+="ChainSemanticCaptureAdapterTest,BranchingSemanticRegionTest,ScopedSemanticRegionTest,"
  EXPANDED_TESTS+="SemanticMaterializationParityTest,BranchAndMultipleEntryMappingCompileTest,"
  EXPANDED_TESTS+="IndependentMappingBoundaryCompileTest,MappingCaptureValidatorTest,MappingContractGateTest,"
  EXPANDED_TESTS+="MappingExecutionSiteValidatorTest,MappingGenerationPipelineTest,MappingGeneratorContextTest,"
  EXPANDED_TESTS+="MappingMechanismSelectorTest,MappingParityValidatorTest,MappingSiteReconciliationTest,"
  EXPANDED_TESTS+="ScriptMappingCompileTest,SecureGroovyMappingCompilerTest,MappingBoundarySchemaResolverTest,"
  EXPANDED_TESTS+="RequirementBriefCoverageValidatorTest,ExecutorEvalFixtureTest"

  mvn -pl ai-service \
    -Dqip.ai.qipknowledge.build.skip=true \
    -Dtest="${EXPANDED_TESTS}" \
    test 2>&1 | tee "${REPORT_DIR}/binding-mapping-expanded.log"

  ai-service/e2e/executor-eval/test-offline.sh \
    2>&1 | tee "${REPORT_DIR}/executor-eval-offline.log"
  "${DIR}/test-live-runner-contracts.sh" \
    2>&1 | tee "${REPORT_DIR}/live-runner-contracts.log"
  "${DIR}/test-quality-gate-offline.sh" \
    2>&1 | tee "${REPORT_DIR}/quality-gate-offline.log"
fi

if [[ "${LEVEL}" == "full" ]]; then
  PERSISTENCE_TESTS="DurableCreateChainFlowInstanceIT#"
  PERSISTENCE_TESTS+="restartFlowWaitsForActivationBeforeExecutingAStage+"
  PERSISTENCE_TESTS+="scanAllRestoresALoopedInstanceWaitingAtAHumanGate,"
  PERSISTENCE_TESTS+="DurableFlowSuspendRestartIT#"
  PERSISTENCE_TESTS+="suspendsPersistsRestoresAndResumesOnlyTheCorrelatedInstance"

  mvn -pl ai-service \
    -DskipITs=false \
    -Dqip.ai.qipknowledge.build.skip=true \
    -Dtest="${PERSISTENCE_TESTS}" \
    test 2>&1 | tee "${REPORT_DIR}/flow-persistence.log"
fi

COMMIT="$(git rev-parse HEAD)"
FINISHED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
cat > "${REPORT_DIR}/summary.json" <<EOF
{
  "campaign": "checkpoint-restart",
  "commit": "${COMMIT}",
  "finishedAt": "${FINISHED_AT}",
  "level": "${LEVEL}",
  "result": "PASS",
  "reportDirectory": "${REPORT_DIR}"
}
EOF

echo "Checkpoint restart campaign passed. Evidence: ${REPORT_DIR}"
