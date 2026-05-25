package org.qubership.integration.platform.ai.llm.routing;

import org.qubership.integration.platform.ai.chat.chainplan.ActiveChainPlanSnapshot;
import org.qubership.integration.platform.ai.chat.intent.UserIntentPatterns;
import org.qubership.integration.platform.ai.model.ScenarioType;

import java.util.Optional;

/**
 * Deterministic routing shortcuts from {@link ConversationPhase} before
 * embedding / LLM.
 */
public final class PhaseRoutingPolicy {

  private PhaseRoutingPolicy() {
  }

  /**
   * @param activePlan optional active snapshot (presence only; details unused
   *                   here)
   */
  public static Optional<ScenarioType> tryResolve(
      ConversationPhase phase,
      String userMessage,
      Optional<ActiveChainPlanSnapshot> activePlan,
      boolean planApproved) {
    if (userMessage == null || userMessage.isBlank()) {
      return Optional.empty();
    }
    String msg = userMessage.trim();
    boolean hasActive = activePlan.isPresent();

    if (hasActive && UserIntentPatterns.matchesModifyPlan(msg)) {
      return Optional.of(ScenarioType.CREATE_CHAIN_PLAN);
    }

    if (planApproved && hasActive && UserIntentPatterns.matchesCreateChainIntent(msg)) {
      return Optional.of(ScenarioType.IMPLEMENT_CHAIN);
    }

    if (!planApproved
        && UserIntentPatterns.matchesCreateChainIntent(msg)
        && (!hasActive
            || phase == ConversationPhase.PLAN_DRAFT
            || phase == ConversationPhase.DISCOVERY)) {
      return Optional.of(ScenarioType.CREATE_CHAIN_PLAN);
    }

    return Optional.empty();
  }
}
