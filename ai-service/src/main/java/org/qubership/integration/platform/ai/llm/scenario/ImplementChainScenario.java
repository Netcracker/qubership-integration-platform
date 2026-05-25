package org.qubership.integration.platform.ai.llm.scenario;

import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.chat.chainplan.ActiveChainPlanService;
import org.qubership.integration.platform.ai.chat.chainplan.ChainPlanOpenDebtMerge;
import org.qubership.integration.platform.ai.chat.hitl.HitlStreamRegistry;
import org.qubership.integration.platform.ai.chat.model.ChatRequest;
import org.qubership.integration.platform.ai.chat.prompt.PromptProfile;
import org.qubership.integration.platform.ai.llm.agent.ImplementChainAgent;
import org.qubership.integration.platform.ai.model.ScenarioType;

import static org.qubership.integration.platform.ai.model.ScenarioType.IMPLEMENT_CHAIN;

/** Scenario 3 — Implement a QIP chain from an **approved** {@code ChainImplementationPlan}. */
@ApplicationScoped
@ForScenario(IMPLEMENT_CHAIN)
public class ImplementChainScenario implements ScenarioHandler {

  private static final Logger LOG = Logger.getLogger(ImplementChainScenario.class);

  private final ImplementChainAgent agent;
  private final HitlStreamRegistry hitlStreamRegistry;
  private final ActiveChainPlanService activeChainPlanService;
  private final StreamingScenarioSupport streamingScenarioSupport;

  public ImplementChainScenario(
      ImplementChainAgent agent,
      HitlStreamRegistry hitlStreamRegistry,
      ActiveChainPlanService activeChainPlanService,
      StreamingScenarioSupport streamingScenarioSupport) {
    this.agent = agent;
    this.hitlStreamRegistry = hitlStreamRegistry;
    this.activeChainPlanService = activeChainPlanService;
    this.streamingScenarioSupport = streamingScenarioSupport;
  }

  @Override
  public Multi<String> handle(
      ChatRequest request, String conversationId, ScenarioType scenarioType) {
    if (!activeChainPlanService.isApproved(conversationId)) {
      LOG.infof(
          "Scenario IMPLEMENT_CHAIN refused: no approved plan: conversationId=%s", conversationId);
      String refusal =
          "No approved chain implementation plan for this chat. Please use the planning flow first "
              + "(CREATE_CHAIN_PLAN). It will produce a ChainImplementationPlan and ask for Agree.";
      return hitlStreamRegistry.wrapWithHitl(conversationId, Multi.createFrom().item(refusal));
    }
    if (activeChainPlanService
        .getActive(conversationId)
        .filter(ChainPlanOpenDebtMerge::hasBlockingOpenDebt)
        .isPresent()) {
      LOG.infof(
          "Scenario IMPLEMENT_CHAIN refused: blocking open plan debt: conversationId=%s",
          conversationId);
      String refusal =
          "The approved plan still has blocking open items (for example missing runtime"
              + " connections[] for a multi-step flow). Revise the ChainImplementationPlan in"
              + " CREATE_CHAIN_PLAN, then approve again.";
      return hitlStreamRegistry.wrapWithHitl(conversationId, Multi.createFrom().item(refusal));
    }

    return streamingScenarioSupport.streamAgent(
        LOG,
        IMPLEMENT_CHAIN,
        conversationId,
        request,
        PromptProfile.IMPLEMENT,
        agent::chat,
        true,
        hitlStreamRegistry);
  }
}
