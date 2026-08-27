package org.qubership.integration.platform.ai.productpipeline.create;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
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
  void aMissingCatalogServiceAsksOneClarificationQuestion() {
    ProducerOwnedRecovery.Route route =
        route(
            "design-execution",
            RecoveryCause.catalogResolution("catalog service"),
            EXECUTION_CANDIDATES,
            false,
            0,
            Optional.empty());

    assertEquals(ProducerOwnedRecovery.Action.ASK_CLARIFICATION, route.action());
    assertEquals("design-execution", route.producerStageId());
    assertTrue(route.requestedFact() != null && !route.requestedFact().isBlank());
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
