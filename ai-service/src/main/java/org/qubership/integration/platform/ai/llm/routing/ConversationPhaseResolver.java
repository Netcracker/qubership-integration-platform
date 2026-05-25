package org.qubership.integration.platform.ai.llm.routing;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.qubership.integration.platform.ai.chat.chainplan.ActiveChainPlanService;
import org.qubership.integration.platform.ai.chat.chainplan.ActiveChainPlanSnapshot;
import org.qubership.integration.platform.ai.chat.chainplan.PlanOpenItem;
import org.qubership.integration.platform.ai.chat.chainplan.PlanOpenItemKind;

import java.util.Optional;

@ApplicationScoped
public class ConversationPhaseResolver {

  @Inject ActiveChainPlanService activeChainPlanService;

  public ConversationPhase resolve(String conversationId) {
    Optional<ActiveChainPlanSnapshot> active = activeChainPlanService.getActive(conversationId);
    if (active.isEmpty()) {
      return ConversationPhase.COLD;
    }
    boolean approved = activeChainPlanService.isApproved(conversationId);
    if (approved) {
      return ConversationPhase.PLAN_APPROVED;
    }
    if (hasUnresolvedServiceBinding(active.get())) {
      return ConversationPhase.DISCOVERY;
    }
    return ConversationPhase.PLAN_DRAFT;
  }

  private static boolean hasUnresolvedServiceBinding(ActiveChainPlanSnapshot snap) {
    if (snap.openItems() == null) {
      return false;
    }
    for (PlanOpenItem o : snap.openItems()) {
      if (!o.dismissedByUser() && o.kind() == PlanOpenItemKind.SERVICE_BINDING_UNRESOLVED) {
        return true;
      }
    }
    return false;
  }
}
