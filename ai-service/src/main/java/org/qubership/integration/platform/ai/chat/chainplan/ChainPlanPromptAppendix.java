package org.qubership.integration.platform.ai.chat.chainplan;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.configuration.AppConfig;
import org.qubership.integration.platform.ai.logging.AiTraceLog;

import java.util.List;

/**
 * Formats the active {@link ChainImplementationPlan} and open-debt sections for router/agent
 * prompts.
 */
@ApplicationScoped
class ChainPlanPromptAppendix {

  private static final Logger LOG = Logger.getLogger(ChainPlanPromptAppendix.class);

  private final ObjectMapper objectMapper;
  private final AppConfig appConfig;

  @Inject
  ChainPlanPromptAppendix(ObjectMapper objectMapper, AppConfig appConfig) {
    this.objectMapper = objectMapper;
    this.appConfig = appConfig;
  }

  String format(
      String conversationId,
      ActiveChainPlanSnapshot snapshot,
      ConversationPlanStateStore.ConversationPlanState planState) {
    try {
      String json =
          objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(snapshot.plan());
      int max = appConfig.chainPlan().promptMaxChars();
      if (json.length() > max) {
        json = AiTraceLog.preview(json, max);
      }
      StringBuilder sb = new StringBuilder();
      sb.append("## Current active chain implementation plan\n");
      sb.append("(planId=")
          .append(snapshot.planId())
          .append(", chain=")
          .append(snapshot.chainName() != null ? snapshot.chainName() : "(unnamed)")
          .append(")\n");
      if (snapshot.apiHubRequired() != null) {
        sb.append("apiHubRequired=").append(snapshot.apiHubRequired());
        if (snapshot.apiHubReason() != null && !snapshot.apiHubReason().isBlank()) {
          sb.append(" — ").append(snapshot.apiHubReason());
        }
        sb.append("\n");
      }
      sb.append("```json\n").append(json).append("\n```\n");
      appendOpenPlanDebtSections(sb, snapshot, planState);
      return sb.toString();
    } catch (Exception e) {
      LOG.warnf(e, "Failed to format chain plan appendix for conversationId=%s", conversationId);
      return "";
    }
  }

  private void appendOpenPlanDebtSections(
      StringBuilder sb,
      ActiveChainPlanSnapshot s,
      ConversationPlanStateStore.ConversationPlanState planState) {
    List<PlanOpenItem> items = s.openItems();
    if (items == null || items.isEmpty()) {
      return;
    }
    List<PlanOpenItem> blocking = items.stream().filter(o -> !o.dismissedByUser()).toList();
    List<PlanOpenItem> dismissed = items.stream().filter(PlanOpenItem::dismissedByUser).toList();
    if (!blocking.isEmpty()) {
      sb.append("\n## Open plan items (requires attention or verify in UI)\n");
      for (PlanOpenItem o : blocking) {
        sb.append("- **").append(o.kind()).append("** `").append(o.itemId()).append("`");
        if (o.clientId() != null && !o.clientId().isBlank()) {
          sb.append(" clientId=").append(o.clientId());
        }
        if (o.elementId() != null && !o.elementId().isBlank()) {
          sb.append(" elementId=").append(o.elementId());
        }
        if (o.elementType() != null && !o.elementType().isBlank()) {
          sb.append(" type=").append(o.elementType());
        }
        sb.append("\n  ").append(o.message()).append("\n");
        if (!o.removedKeys().isEmpty()) {
          sb.append("  removedOrSkippedKeys: ")
              .append(String.join(", ", o.removedKeys()))
              .append("\n");
        }
      }
      sb.append("\n## Skipped / verify in catalog UI\n");
      sb.append(
          "Review the keys and PATCH failures above in the Integration UI before relying on runtime"
              + " behavior.\n");
      if (!blocking.isEmpty()) {
        String fp = ChainPlanOpenDebtMerge.fingerprintNonDismissedOpenItems(blocking);
        if (!fp.isEmpty()) {
          sb.append("\nopenDebtFingerprint=").append(fp).append("\n");
          if (planState == null || !fp.equals(planState.lastMergedOpenDebtFingerprint)) {
            sb.append(
                "(If you already called `requestConfirmation` for this exact fingerprint and the"
                    + " user answered, do not repeat the same HITL until the fingerprint"
                    + " changes.)\n");
            if (planState != null) {
              planState.lastMergedOpenDebtFingerprint = fp;
            }
          }
        }
      }
    }
    if (!dismissed.isEmpty()) {
      sb.append("\n## User-dismissed plan debt (still listed for audit)\n");
      sb.append("count=").append(dismissed.size()).append("\n");
    }
  }
}
