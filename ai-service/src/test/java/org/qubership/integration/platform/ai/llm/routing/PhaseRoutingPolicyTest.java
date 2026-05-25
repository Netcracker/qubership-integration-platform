package org.qubership.integration.platform.ai.llm.routing;

import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chat.chainplan.ActiveChainPlanSnapshot;
import org.qubership.integration.platform.ai.integration.catalog.model.ChainImplementationPlan;
import org.qubership.integration.platform.ai.model.ScenarioType;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PhaseRoutingPolicyTest {

  @Test
  void modifyPlanWithActivePlanRoutesToCreateChainPlan() {
    Optional<ScenarioType> r = PhaseRoutingPolicy.tryResolve(
        ConversationPhase.PLAN_APPROVED,
        "Modify plan",
        Optional.of(
            new ActiveChainPlanSnapshot(
                "p1",
                "c1",
                false,
                "",
                new ChainImplementationPlan(),
                Instant.now(),
                List.of(),
                List.of())),
        true);
    assertEquals(Optional.of(ScenarioType.CREATE_CHAIN_PLAN), r);
  }

  @Test
  void implementIntentPlanDraftMapsToCreateChainPlan() {
    Optional<ScenarioType> r = PhaseRoutingPolicy.tryResolve(
        ConversationPhase.PLAN_DRAFT,
        "Please implement the chain from the design",
        Optional.of(
            new ActiveChainPlanSnapshot(
                "p1",
                "c1",
                false,
                "",
                new ChainImplementationPlan(),
                Instant.now(),
                List.of(),
                List.of())),
        false);
    assertEquals(Optional.of(ScenarioType.CREATE_CHAIN_PLAN), r);
  }

  @Test
  void implementIntentColdNoActivePlanRoutesToCreateChainPlan() {
    Optional<ScenarioType> r = PhaseRoutingPolicy.tryResolve(
        ConversationPhase.COLD,
        "Please implement the chain from the design",
        Optional.empty(),
        false);
    assertEquals(Optional.of(ScenarioType.CREATE_CHAIN_PLAN), r);
  }

  @Test
  void createChainIntentColdNoActivePlanRoutesToCreateChainPlan() {
    Optional<ScenarioType> r = PhaseRoutingPolicy.tryResolve(
        ConversationPhase.COLD,
        "Create a chain based on attached integration design",
        Optional.empty(),
        false);
    assertEquals(Optional.of(ScenarioType.CREATE_CHAIN_PLAN), r);
  }

  @Test
  void implementIntentPlanApprovedRoutesToImplementChain() {
    Optional<ScenarioType> r = PhaseRoutingPolicy.tryResolve(
        ConversationPhase.PLAN_APPROVED,
        "Create the chain",
        Optional.of(
            new ActiveChainPlanSnapshot(
                "p1",
                "c1",
                false,
                "",
                new ChainImplementationPlan(),
                Instant.now(),
                List.of(),
                List.of())),
        true);
    assertEquals(Optional.of(ScenarioType.IMPLEMENT_CHAIN), r);
  }
}
