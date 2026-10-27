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
RECOVERY_TESTS+="repairedUpstreamArtifactCanContinueThroughMaterialization+"
RECOVERY_TESTS+="sourceCandidateMustPassTheActiveMappingGateBeforeHandoff+"
RECOVERY_TESTS+="scriptLiteralRefusalCanDiagnoseTheSourceInsteadOfOnlyRegeneratingTheScript+"
RECOVERY_TESTS+="aCaptureRefusalCannotHideTheMappingContractFromTheNextSourceHandoff+"
RECOVERY_TESTS+="aSourceCitedLiteralCorrectionReopensItsCommittedDraftAndRequiresBriefApproval"
RECOVERY_TESTS+=",E2eRecoveryFaultInjectorTest,"
RECOVERY_TESTS+="DesignExecutionCapabilityTest#"
RECOVERY_TESTS+="configuredRecoveryFaultFailsTheFirstTwoMatchingRunAttempts+"
RECOVERY_TESTS+="configuredCatalogMismatchFaultCarriesDesignInputRecoveryEvidenceOnce,"
RECOVERY_TESTS+="MaterializationCapabilityTest#"
RECOVERY_TESTS+="configuredRecoveryFaultStopsBeforeTheFirstCatalogWrite"
RECOVERY_TESTS+=",HttpTriggerCaptureToolTest,HttpTriggerCaptureAdapterTest,"
RECOVERY_TESTS+="E2eRecoveryTestControlTest,RecoveryEvidenceProbeTest,"
RECOVERY_TESTS+="MaterializationCapabilityTest#focusedCheckpointRequiresNativeAdmissionAndPreventsTheFirstCatalogWrite"
RECOVERY_TESTS+=",ScriptedStreamingChatModelTest,ScriptedRecoveryTest,ScriptedPatchRecoveryTest,"
RECOVERY_TESTS+="CompilerGraphPatchToolTest,CompilerSkillRuntimeTest,CaptureRepairMessageBuilderTest,"
RECOVERY_TESTS+="RepairHistoryTest,ProductAnalysisRepairLoopTest,"
RECOVERY_TESTS+="RateLimitAwareChatModelTest,RateLimitAwareStreamingChatModelTest,"
RECOVERY_TESTS+="RateLimitStreamingChatModelTest,LlmRunBudgetTest"
RECOVERY_TESTS+=",ToolCallErrorHandlerTest,StringifiedToolArgumentsNormalizerTest,"
RECOVERY_TESTS+="RequirementDiscoveryCapabilityTest,RequirementCaptureToolsTest,RequirementCaptureProviderTest,"
RECOVERY_TESTS+="CaptureAttemptFeedbackStoreTest,RecoveryDecisionValidatorTest,CompilerQualityValidatorTest,"
RECOVERY_TESTS+="ProviderBoundaryEvidenceTest,HaltExitGuaranteeMatrixTest"
RECOVERY_TESTS+=",RequirementSchemaToolWiringTest"
RECOVERY_TESTS+=",ToolReadProgressTest,CompilerReadNoProgressProviderTest,RequirementProtectionAcceptanceTest"
RECOVERY_TESTS+=",RequirementFailureConsistencyAdmissionTest,RequirementRevisionAdmissionTest"
RECOVERY_TESTS+=",RequirementCaptureEditorTest,RequirementPendingInitialRepairTest"
RECOVERY_TESTS+=",AdditiveLiteralBindingRecoveryTest,RejectedLiteralValidationStagingTest"
RECOVERY_TESTS+=",RequirementPendingProposalTest,MiniTopologyRepresentationRecoveryTest"
RECOVERY_TESTS+=",RequirementRecoveryLadderTest,PendingCandidateCheckpointResumeTest"
RECOVERY_TESTS+=",RequirementRejectedReferenceRepairTest"
RECOVERY_TESTS+=",MappingRepairCaptureValidatorTest,ProductRequirementBriefToolTest"
RECOVERY_TESTS+=",RequirementCaptureProjectionTest,RequirementFlowValidatorTest,AuthorQuestionAdmissionTest"
RECOVERY_TESTS+=",SerializedClarificationFlowTest"

python3 -m unittest discover -s ai-service/e2e/product-pipeline -p 'test_recovery_*.py'

mvn -pl ai-service \
    -Dtest="${RECOVERY_TESTS}" \
    test

python3 -m unittest discover -s ai-service/e2e/product-pipeline -p 'test_serialized_clarifications.py'
