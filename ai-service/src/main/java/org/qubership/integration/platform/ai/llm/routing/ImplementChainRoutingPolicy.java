package org.qubership.integration.platform.ai.llm.routing;

import org.qubership.integration.platform.ai.model.ScenarioType;

/**
 * When routing picks {@link ScenarioType#IMPLEMENT_CHAIN} but the conversation has no approved
 * captured plan, catalog tools would refuse; redirect to {@link ScenarioType#CREATE_CHAIN_PLAN}
 * instead.
 */
public final class ImplementChainRoutingPolicy {

  private ImplementChainRoutingPolicy() {}

  public static ScenarioType effectiveScenario(ScenarioType chosen, boolean planApproved) {
    if (chosen == ScenarioType.IMPLEMENT_CHAIN && !planApproved) {
      return ScenarioType.CREATE_CHAIN_PLAN;
    }
    return chosen;
  }
}
