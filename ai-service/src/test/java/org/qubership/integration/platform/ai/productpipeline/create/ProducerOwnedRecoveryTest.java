package org.qubership.integration.platform.ai.productpipeline.create;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
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
            "unknown property key 'topic' on kafka-trigger-2",
            EXECUTION_CANDIDATES,
            false,
            0,
            Optional.empty());

    assertEquals(ProducerOwnedRecovery.Action.REPAIR_CURRENT, route.action());
    assertEquals("design-execution", route.producerStageId());
  }

  @Test
  void anInvalidApprovedBriefReopensRequirementAnalysis() {
    ProducerOwnedRecovery.Route route =
        route(
            "design-input",
            "approved requirement brief is missing required facts",
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
            "unknown property key 'topic' on kafka-trigger-2",
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
            "unknown property key 'topic'",
            EXECUTION_CANDIDATES,
            false,
            1,
            Optional.empty());
    ProducerOwnedRecovery.Route next =
        route(
            "design-execution",
            "unknown property key 'tls'",
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
            "approved requirement brief is missing required facts",
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
            "catalog service Petstore is missing",
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
            "planning validation failed. Findings: PLAN_BLOCKER: missing quartz-scheduler",
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
                "",
                "Cannot deserialize value of type `java.lang.String` from Object value"
                    + " (token `JsonToken.START_OBJECT`) (through reference chain:"
                    + " ConfiguredTrigger[\"properties\"]->ArrayList[7]->PlanProperty[\"value\"])",
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
            "unknown property key 'topic'",
            EXECUTION_CANDIDATES,
            false,
            0,
            Optional.empty());

    assertEquals(ProducerOwnedRecovery.Action.REPAIR_CURRENT, route.action());
  }

  private static ProducerOwnedRecovery.Route route(
      String failedStageId,
      String evidence,
      List<OwnerCandidate> candidates,
      boolean catalogWritten,
      int semanticRepairsUsed,
      Optional<String> diagnosedOwner) {
    return ProducerOwnedRecovery.route(
        new ProducerOwnedRecovery.Request(
            failedStageId,
            StageOutcomeClass.VALIDATION_FAILURE,
            "",
            evidence,
            candidates,
            catalogWritten,
            semanticRepairsUsed,
            1,
            diagnosedOwner));
  }
}
