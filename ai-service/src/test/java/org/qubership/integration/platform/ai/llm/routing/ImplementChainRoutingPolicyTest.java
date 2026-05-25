package org.qubership.integration.platform.ai.llm.routing;

import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.model.ScenarioType;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ImplementChainRoutingPolicyTest {

  @Test
  void implementChainWithoutApprovalBecomesCreateChainPlan() {
    assertEquals(
        ScenarioType.CREATE_CHAIN_PLAN,
        ImplementChainRoutingPolicy.effectiveScenario(ScenarioType.IMPLEMENT_CHAIN, false));
  }

  @Test
  void implementChainWithApprovalUnchanged() {
    assertEquals(
        ScenarioType.IMPLEMENT_CHAIN,
        ImplementChainRoutingPolicy.effectiveScenario(ScenarioType.IMPLEMENT_CHAIN, true));
  }

  @Test
  void otherScenariosUnchangedRegardlessOfApproval() {
    assertEquals(
        ScenarioType.UNKNOWN,
        ImplementChainRoutingPolicy.effectiveScenario(ScenarioType.UNKNOWN, false));
    assertEquals(
        ScenarioType.CREATE_CHAIN_PLAN,
        ImplementChainRoutingPolicy.effectiveScenario(ScenarioType.CREATE_CHAIN_PLAN, false));
  }
}
