package org.qubership.integration.platform.ai.chat.chainplan;

import dev.langchain4j.agent.tool.Tool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import org.jboss.logmanager.MDC;
import org.qubership.integration.platform.ai.chat.ChatMdc;

/** LangChain4j tools for mutating plan-debt state on {@link ActiveChainPlanService}. */
@ApplicationScoped
public class ChainPlanOpenDebtTool {

  private static final Logger LOG = Logger.getLogger(ChainPlanOpenDebtTool.class);

  private final ActiveChainPlanService activeChainPlanService;

  @Inject
  public ChainPlanOpenDebtTool(ActiveChainPlanService activeChainPlanService) {
    this.activeChainPlanService = activeChainPlanService;
  }

  @Tool(
      "Call once after the user explicitly chose in requestConfirmation to ignore remaining open"
          + " plan items / verify debt themselves. Marks all snapshot open items as user-dismissed"
          + " so you can continue implementation without repeating the same HITL until new debt"
          + " appears.")
  public String markPlanOpenDebtIgnoredByUser() {
    String conversationId = resolveConversationIdFromMdc();
    if (conversationId.isBlank()) {
      LOG.warn("markPlanOpenDebtIgnoredByUser: missing conversationId on MDC");
      return "Error: missing chat session context.";
    }
    activeChainPlanService.markAllOpenPlanItemsDismissedByUser(conversationId);
    return "Recorded: all open plan items marked user-dismissed. User should verify skipped"
               + " parameters in the UI.";
  }

  private static String resolveConversationIdFromMdc() {
    String fromMdc = MDC.get(ChatMdc.CONVERSATION_ID);
    return fromMdc != null ? fromMdc.trim() : "";
  }
}
