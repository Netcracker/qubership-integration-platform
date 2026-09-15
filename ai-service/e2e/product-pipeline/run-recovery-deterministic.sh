#!/usr/bin/env bash
# Run the recovery gate without calling an LLM.
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${DIR}/../../.." && pwd)"

cd "${REPO_ROOT}"
RECOVERY_TESTS="DesignInputRecoveryTest,DesignInputCapabilityTest,ChainSemanticCaptureToolTest,"
RECOVERY_TESTS+="RecoveryExecutorTest,ProducerOwnedRecoveryTest,RecoveryOutcomeMatrixTest,"
RECOVERY_TESTS+="RecoveryAttemptLedgerTest,SupersededBriefLineageGuardTest,"
RECOVERY_TESTS+="BindingIdentityMismatchRecoveryTest,MappingContractExhaustedRecoveryTest,"
RECOVERY_TESTS+="MissingBriefFactsExhaustedRecoveryTest,"
RECOVERY_TESTS+="ProductPipelineDerivedRunTest,ProductPipelineRunStoreTest,"
RECOVERY_TESTS+="ProvidedIdsFlowOrchestratorTest,LatestIterationJpaInstanceOperationsTest,"
RECOVERY_TESTS+="ProductPipelineStageExecutorTest#"
RECOVERY_TESTS+="missingRecoveryDecisionOnCaptureContractShapeRetriesThenParks+"
RECOVERY_TESTS+="spentDesignInputCaptureRepairParksInsteadOfEscalatingOwners+"
RECOVERY_TESTS+="retryCannotBypassTheExhaustedDesignInputBudget+"
RECOVERY_TESTS+="executionRegenerationRetriesItsProducerUnlessCatalogWasWritten+"
RECOVERY_TESTS+="sequentialFailuresCanReopenDifferentOwnersAndStillComplete+"
RECOVERY_TESTS+="restartAfterReopenRetainsTheOwnerAndDownstreamInvalidation+"
RECOVERY_TESTS+="preWriteMaterializationContractShapeReopensSucceededDesignExecution+"
RECOVERY_TESTS+="repairedUpstreamArtifactCanContinueThroughMaterialization"
RECOVERY_TESTS+=",E2eRecoveryFaultInjectorTest,"
RECOVERY_TESTS+="DesignExecutionCapabilityTest#"
RECOVERY_TESTS+="configuredRecoveryFaultFailsTheFirstTwoMatchingRunAttempts+"
RECOVERY_TESTS+="configuredCatalogMismatchFaultCarriesDesignInputRecoveryEvidenceOnce,"
RECOVERY_TESTS+="MaterializationCapabilityTest#"
RECOVERY_TESTS+="configuredRecoveryFaultStopsBeforeTheFirstCatalogWrite"

mvn -pl ai-service \
  -Dtest="${RECOVERY_TESTS}" \
  test
