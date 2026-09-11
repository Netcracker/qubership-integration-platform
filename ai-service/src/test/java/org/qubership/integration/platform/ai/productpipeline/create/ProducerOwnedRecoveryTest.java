package org.qubership.integration.platform.ai.productpipeline.create;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.productpipeline.artifact.PlanValidationFinding;
import org.qubership.integration.platform.ai.productpipeline.capability.RecoveryCause;
import org.qubership.integration.platform.ai.productpipeline.capability.RecoveryCauseCode;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcomeClass;

class ProducerOwnedRecoveryTest {

  private static final List<OwnerCandidate> EXECUTION_CANDIDATES =
      List.of(
          new OwnerCandidate("design-execution", "plan-validation-result"),
          new OwnerCandidate("design-planning", "implementation-plan"),
          new OwnerCandidate("requirement-analysis", "requirement-brief"));

  @Test
  void unknownPropertyKeyRepairsTheObservingExecutionStage() {
    ProducerOwnedRecovery.Route route =
        route(
            "design-execution",
            RecoveryCause.of(RecoveryCauseCode.UNKNOWN_PROPERTY),
            EXECUTION_CANDIDATES,
            false,
            0,
            Optional.empty());

    assertEquals(ProducerOwnedRecovery.Action.REPAIR_CURRENT, route.action());
    assertEquals("design-execution", route.producerStageId());
  }

  @Test
  void formattedUnknownPropertyProseDoesNotSelectAnOwner() {
    ProducerOwnedRecovery.Route route =
        route(
            "design-execution",
            RecoveryCause.of(RecoveryCauseCode.VALIDATION_BLOCKER),
            EXECUTION_CANDIDATES,
            false,
            0,
            Optional.empty());

    assertEquals(ProducerOwnedRecovery.Action.PARK, route.action());
  }

  @Test
  void missingApprovedDraftReopensRequirementDiscovery() {
    ProducerOwnedRecovery.Route route =
        ProducerOwnedRecovery.route(
            new ProducerOwnedRecovery.Request(
                "requirement-analysis",
                StageOutcomeClass.MISSING_MANDATORY_INPUT,
                RecoveryCause.of(RecoveryCauseCode.MISSING_MANDATORY_INPUT),
                List.of(
                    new OwnerCandidate("requirement-analysis", "requirement-brief"),
                    new OwnerCandidate("requirement-discovery", "requirement-draft")),
                false,
                0,
                1,
                Optional.empty()));

    assertEquals(ProducerOwnedRecovery.Action.REOPEN_UPSTREAM, route.action());
    assertEquals("requirement-discovery", route.producerStageId());
  }

  @Test
  void anInvalidApprovedBriefReopensRequirementAnalysis() {
    ProducerOwnedRecovery.Route route =
        route(
            "design-input",
            RecoveryCause.of(RecoveryCauseCode.MISSING_BRIEF_FACTS),
            List.of(
                new OwnerCandidate("design-input", "ids-document"),
                new OwnerCandidate("requirement-analysis", "requirement-brief")),
            false,
            0,
            Optional.empty());

    assertEquals(ProducerOwnedRecovery.Action.REOPEN_UPSTREAM, route.action());
    assertEquals("requirement-analysis", route.producerStageId());
  }

  @Test
  void theSameRejectionAfterItsBudgetIsSpentParksWithFallbackActions() {
    ProducerOwnedRecovery.Route route =
        route(
            "design-execution",
            RecoveryCause.of(RecoveryCauseCode.UNKNOWN_PROPERTY),
            EXECUTION_CANDIDATES,
            false,
            1,
            Optional.empty());

    assertEquals(ProducerOwnedRecovery.Action.PARK, route.action());
    assertEquals("design-execution", route.producerStageId());
  }

  @Test
  void aChangedRejectionStillHasARepairCredit() {
    ProducerOwnedRecovery.Route first =
        route(
            "design-execution",
            RecoveryCause.of(RecoveryCauseCode.UNKNOWN_PROPERTY),
            EXECUTION_CANDIDATES,
            false,
            1,
            Optional.empty());
    ProducerOwnedRecovery.Route next =
        route(
            "design-execution",
            RecoveryCause.of(RecoveryCauseCode.UNKNOWN_PROPERTY),
            EXECUTION_CANDIDATES,
            false,
            0,
            Optional.empty());

    assertEquals(ProducerOwnedRecovery.Action.PARK, first.action());
    assertEquals(ProducerOwnedRecovery.Action.REPAIR_CURRENT, next.action());
  }

  @Test
  void postWriteFailuresCannotReopenAnUpstreamProducer() {
    ProducerOwnedRecovery.Route route =
        route(
            "design-input",
            RecoveryCause.of(RecoveryCauseCode.MISSING_BRIEF_FACTS),
            List.of(
                new OwnerCandidate("design-input", "ids-document"),
                new OwnerCandidate("requirement-analysis", "requirement-brief")),
            true,
            0,
            Optional.empty());

    assertEquals(ProducerOwnedRecovery.Action.PARK, route.action());
  }

  @Test
  void aMissingCatalogBindingAsksTheBindingProducer() {
    ProducerOwnedRecovery.Route route =
        route(
            "design-execution",
            RecoveryCause.missingCatalogBinding("wfms-create-work-order"),
            List.of(
                new OwnerCandidate("design-execution", "plan-validation-result"),
                new OwnerCandidate("design-planning", "implementation-plan"),
                new OwnerCandidate("requirement-discovery", "requirement-draft"),
                new OwnerCandidate("requirement-analysis", "requirement-brief")),
            false,
            0,
            Optional.empty());

    assertEquals(ProducerOwnedRecovery.Action.ASK_CLARIFICATION, route.action());
    assertEquals("requirement-discovery", route.producerStageId());
    assertTrue(route.requestedFact() != null && !route.requestedFact().isBlank());
  }

  @Test
  void aMissingCatalogBindingWithoutAProducerStopsSafely() {
    ProducerOwnedRecovery.Route route =
        route(
            "design-execution",
            RecoveryCause.missingCatalogBinding("wfms-create-work-order"),
            EXECUTION_CANDIDATES,
            false,
            0,
            Optional.empty());

    assertEquals(ProducerOwnedRecovery.Action.PARK, route.action());
    assertEquals("design-execution", route.producerStageId());
  }

  @Test
  void aMissingCatalogBindingWithoutDiscoveryStopsSafely() {
    ProducerOwnedRecovery.Route route =
        route(
            "design-execution",
            RecoveryCause.missingCatalogBinding("wfms-create-work-order"),
            List.of(
                new OwnerCandidate("design-execution", "plan-validation-result"),
                new OwnerCandidate("uploaded-spec-import", "catalog-binding-hint")),
            false,
            0,
            Optional.empty());

    assertEquals(ProducerOwnedRecovery.Action.PARK, route.action());
    assertEquals("design-execution", route.producerStageId());
  }

  @Test
  void aBindingIdentityMismatchReopensTheSemanticProducer() {
    ProducerOwnedRecovery.Route route =
        route(
            "design-execution",
            RecoveryCause.bindingIdentityMismatch("create-work-order"),
            List.of(
                new OwnerCandidate("design-execution", "plan-validation-result"),
                new OwnerCandidate("design-input", "chain-semantic-revision"),
                new OwnerCandidate("requirement-discovery", "catalog-binding-hint")),
            false,
            0,
            Optional.empty());

    assertEquals(ProducerOwnedRecovery.Action.REOPEN_UPSTREAM, route.action());
    assertEquals("design-input", route.producerStageId());
  }

  @Test
  void aBindingIdentityMismatchAfterCatalogWriteStopsSafely() {
    ProducerOwnedRecovery.Route route =
        route(
            "design-execution",
            RecoveryCause.bindingIdentityMismatch("create-work-order"),
            List.of(
                new OwnerCandidate("design-execution", "plan-validation-result"),
                new OwnerCandidate("design-input", "chain-semantic-revision")),
            true,
            0,
            Optional.empty());

    assertEquals(ProducerOwnedRecovery.Action.PARK, route.action());
    assertEquals("design-input", route.producerStageId());
  }

  @Test
  void aBindingIdentityMismatchWithoutASemanticProducerStopsSafely() {
    ProducerOwnedRecovery.Route route =
        route(
            "design-execution",
            RecoveryCause.bindingIdentityMismatch("create-work-order"),
            EXECUTION_CANDIDATES,
            false,
            0,
            Optional.empty());

    assertEquals(ProducerOwnedRecovery.Action.PARK, route.action());
    assertEquals("design-execution", route.producerStageId());
  }

  @Test
  void mappingContractReopensTheBriefProducer() {
    ProducerOwnedRecovery.Route route =
        route(
            "design-execution",
            RecoveryCause.mappingContract(
                List.of(
                    new PlanValidationFinding(
                        "MAPPING_UNKNOWN_TARGET",
                        "Target path $.preserved.executionId is absent from the target contract.",
                        true))),
            EXECUTION_CANDIDATES,
            false,
            0,
            Optional.of("design-planning"));

    assertEquals(ProducerOwnedRecovery.Action.REOPEN_UPSTREAM, route.action());
    assertEquals("requirement-analysis", route.producerStageId());
  }

  @Test
  void mappingContractIgnoresNonBriefConsumedProvenance() {
    ProducerOwnedRecovery.Route route =
        ProducerOwnedRecovery.route(
            new ProducerOwnedRecovery.Request(
                "design-execution",
                StageOutcomeClass.VALIDATION_FAILURE,
                RecoveryCause.mappingContract(
                    List.of(
                        new PlanValidationFinding(
                            "MAPPING_UNKNOWN_TARGET",
                            "Target path $.preserved.executionId is absent from the target contract.",
                            true))),
                EXECUTION_CANDIDATES,
                false,
                0,
                1,
                Optional.empty(),
                Optional.of("design-planning")));

    assertEquals(ProducerOwnedRecovery.Action.REOPEN_UPSTREAM, route.action());
    assertEquals("requirement-analysis", route.producerStageId());
  }

  @Test
  void mappingContractUsesAnyBriefProducerFromConsumedProvenance() {
    ProducerOwnedRecovery.Route route =
        ProducerOwnedRecovery.route(
            new ProducerOwnedRecovery.Request(
                "design-execution",
                StageOutcomeClass.VALIDATION_FAILURE,
                RecoveryCause.mappingContract(
                    List.of(
                        new PlanValidationFinding(
                            "MAPPING_UNKNOWN_TARGET",
                            "Target path $.preserved.executionId is absent from the target contract.",
                            true))),
                List.of(
                    new OwnerCandidate("design-execution", "plan-validation-result"),
                    new OwnerCandidate("requirement-analysis", "requirement-brief"),
                    new OwnerCandidate("requirement-analysis-alt", "requirement-brief")),
                false,
                0,
                1,
                Optional.empty(),
                Optional.of("requirement-analysis")));

    assertEquals(ProducerOwnedRecovery.Action.REOPEN_UPSTREAM, route.action());
    assertEquals("requirement-analysis", route.producerStageId());
  }

  @Test
  void mappingContractDoesNotFallBackToThePlanProducer() {
    ProducerOwnedRecovery.Route route =
        route(
            "design-execution",
            RecoveryCause.mappingContract(
                List.of(
                    new PlanValidationFinding(
                        "MAPPING_UNKNOWN_TARGET",
                        "Target path $.preserved.executionId is absent from the target contract.",
                        true))),
            List.of(
                new OwnerCandidate("design-execution", "plan-validation-result"),
                new OwnerCandidate("design-planning", "implementation-plan")),
            false,
            0,
            Optional.of("design-planning"));

    assertEquals(ProducerOwnedRecovery.Action.PARK, route.action());
    assertEquals("design-execution", route.producerStageId());
  }

  @Test
  void aDiagnosedOwnerIsUsedOnlyWhenTheFindingDoesNotNameAProducer() {
    ProducerOwnedRecovery.Route route =
        route(
            "design-execution",
            RecoveryCause.of(RecoveryCauseCode.VALIDATION_BLOCKER),
            EXECUTION_CANDIDATES,
            false,
            0,
            Optional.of("requirement-analysis"));

    assertEquals(ProducerOwnedRecovery.Action.REOPEN_UPSTREAM, route.action());
    assertEquals("requirement-analysis", route.producerStageId());
  }

  @Test
  void exhaustedMappingCaptureRepairParksWithoutReopeningMappingIntent() {
    ProducerOwnedRecovery.Route route =
        ProducerOwnedRecovery.route(
            new ProducerOwnedRecovery.Request(
                "design-execution",
                StageOutcomeClass.CONTRACT_FAILURE,
                RecoveryCause.of(RecoveryCauseCode.VALIDATION_BLOCKER),
                EXECUTION_CANDIDATES,
                false,
                1,
                1,
                Optional.empty()));

    assertEquals(ProducerOwnedRecovery.Action.PARK, route.action());
    assertEquals("design-execution", route.producerStageId());
  }

  @Test
  void aCaptureContractFailureWithoutFindingsRepairsTheObservingExecutionStage() {
    ProducerOwnedRecovery.Route route =
        ProducerOwnedRecovery.route(
            new ProducerOwnedRecovery.Request(
                "design-execution",
                StageOutcomeClass.CONTRACT_FAILURE,
                RecoveryCause.of(RecoveryCauseCode.CONTRACT_SHAPE),
                EXECUTION_CANDIDATES,
                false,
                0,
                1,
                Optional.empty()));

    assertEquals(ProducerOwnedRecovery.Action.REPAIR_CURRENT, route.action());
    assertEquals("design-execution", route.producerStageId());
  }

  @Test
  void aMalformedDiagnosisDoesNotBlockCurrentProducerRepair() {
    ProducerOwnedRecovery.Route route =
        route(
            "design-execution",
            RecoveryCause.of(RecoveryCauseCode.UNKNOWN_PROPERTY),
            EXECUTION_CANDIDATES,
            false,
            0,
            Optional.empty());

    assertEquals(ProducerOwnedRecovery.Action.REPAIR_CURRENT, route.action());
  }

  private static ProducerOwnedRecovery.Route route(
      String failedStageId,
      RecoveryCause cause,
      List<OwnerCandidate> candidates,
      boolean catalogWritten,
      int semanticRepairsUsed,
      Optional<String> diagnosedOwner) {
    return ProducerOwnedRecovery.route(
        new ProducerOwnedRecovery.Request(
            failedStageId,
            StageOutcomeClass.VALIDATION_FAILURE,
            cause,
            candidates,
            catalogWritten,
            semanticRepairsUsed,
            1,
            diagnosedOwner));
  }
}
