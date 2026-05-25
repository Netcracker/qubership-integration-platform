package org.qubership.integration.platform.ai.chat.prompt;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.qubership.integration.platform.ai.chat.chainplan.ActiveChainPlanService;
import org.qubership.integration.platform.ai.chat.chainplan.ChainPlanStatus;
import org.qubership.integration.platform.ai.chat.planning.ConversationPlanningDiaryService;
import org.qubership.integration.platform.ai.llm.routing.ConversationPhase;
import org.qubership.integration.platform.ai.llm.routing.ConversationPhaseResolver;

/** Compact markdown block of durable conversation decisions (phase, plan, recent diary tail). */
@ApplicationScoped
public class AuthoritativeStateSection {

  private static final int RECENT_DIARY_EVENTS = 5;

  @Inject ConversationPhaseResolver conversationPhaseResolver;

  @Inject ActiveChainPlanService activeChainPlanService;

  @Inject ConversationPlanningDiaryService planningDiaryService;

  public String format(String conversationId) {
    if (conversationId == null || conversationId.isBlank()) {
      return "";
    }
    ConversationPhase phase = conversationPhaseResolver.resolve(conversationId);
    ChainPlanStatus plan = activeChainPlanService.getPlanStatus(conversationId);
    boolean implementGatePending = activeChainPlanService.isImplementGatePending(conversationId);
    String recentEvents =
        planningDiaryService.formatRecentEventsForAuthoritative(
            conversationId, RECENT_DIARY_EVENTS);

    StringBuilder sb = new StringBuilder();
    sb.append("## Authoritative state\n\n");
    sb.append("- scenario phase: ").append(phase.name()).append("\n");
    sb.append("- plan approved: ").append(plan.approved()).append("\n");
    sb.append("- implement gate pending: ").append(implementGatePending).append("\n");
    if (plan.planId() != null) {
      sb.append("- plan id: ").append(plan.planId()).append("\n");
    }
    sb.append("- open debt count: ").append(plan.openItemCount()).append("\n");
    if (recentEvents != null && !recentEvents.isBlank()) {
      sb.append("\n### Recent planning events\n").append(recentEvents).append("\n");
    }
    return sb.toString().trim();
  }
}
