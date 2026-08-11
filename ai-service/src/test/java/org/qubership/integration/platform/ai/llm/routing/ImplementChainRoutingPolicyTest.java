package org.qubership.integration.platform.ai.llm.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.model.ScenarioType;

class ImplementChainRoutingPolicyTest {

  @Test
  void planApprovedRoutesAskPlanForPlanQuestion() {
    var result =
        PhaseRoutingPolicy.tryResolve(
            ConversationPhase.PLAN_APPROVED, "show graph", true, true, false);

    assertTrue(result.isPresent());
    assertEquals(ScenarioType.ASK_PLAN, result.get());
  }

  @Test
  void planApprovedRoutesImplementChainForBuildIntent() {
    var result =
        PhaseRoutingPolicy.tryResolve(
            ConversationPhase.PLAN_APPROVED, "implement the chain", true, true, false);

    assertTrue(result.isPresent());
    assertEquals(ScenarioType.IMPLEMENT_CHAIN, result.get());
  }

  @Test
  void planApprovedLeavesOtherScenariosToLaterLayers() {
    var result =
        PhaseRoutingPolicy.tryResolve(
            ConversationPhase.PLAN_APPROVED, "explain the design", true, true, false);

    assertTrue(result.isEmpty());
  }

  @Test
  void planApprovedDoesNotHardRouteRichImplementKeywordPrompt() {
    var result =
        PhaseRoutingPolicy.tryResolve(
            ConversationPhase.PLAN_APPROVED,
            """
            Execute the approved design. Agree on the plan wording if needed,
            then implement the chain in the catalog.
            """,
            true,
            true,
            false);

    assertTrue(result.isEmpty());
  }
}
