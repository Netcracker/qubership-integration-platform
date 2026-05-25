package org.qubership.integration.platform.ai.chat.context;

import jakarta.enterprise.context.ApplicationScoped;
import org.qubership.integration.platform.ai.chat.chainplan.ActiveChainPlanService;
import org.qubership.integration.platform.ai.chat.conversation.ConversationService;
import org.qubership.integration.platform.ai.chat.conversation.ConversationTranscriptLabels;
import org.qubership.integration.platform.ai.chat.planning.ConversationPlanningDiaryService;
import org.qubership.integration.platform.ai.configuration.TranscriptLimits;

/** Builds labeled transcript blocks for guardrail, router, and plan-approval classifiers. */
@ApplicationScoped
public class ConversationContextBuilder {

  public enum ContextAppendices {
    TRANSCRIPT_ONLY,
    PLAN,
    DIARY_AND_PLAN
  }

  private final ConversationService conversationService;
  private final ConversationPlanningDiaryService planningDiaryService;
  private final ActiveChainPlanService activeChainPlanService;

  public ConversationContextBuilder(
      ConversationService conversationService,
      ConversationPlanningDiaryService planningDiaryService,
      ActiveChainPlanService activeChainPlanService) {
    this.conversationService = conversationService;
    this.planningDiaryService = planningDiaryService;
    this.activeChainPlanService = activeChainPlanService;
  }

  public String build(
      String conversationId, TranscriptLimits limits, ContextAppendices appendices) {
    String recent =
        conversationService.formatRecentTranscriptBalanced(
            conversationId,
            limits.maxMessages(),
            limits.maxCharsPerMessage(),
            limits.maxTotalChars(),
            1,
            ConversationTranscriptLabels.EMPTY);
    if (appendices == ContextAppendices.DIARY_AND_PLAN) {
      String diary = planningDiaryService.formatAppendix(conversationId);
      if (diary != null && !diary.isBlank()) {
        recent = recent + "\n\n" + diary;
      }
    }
    if (appendices == ContextAppendices.PLAN || appendices == ContextAppendices.DIARY_AND_PLAN) {
      String planAppendix = activeChainPlanService.formatPromptAppendix(conversationId);
      if (planAppendix != null && !planAppendix.isBlank()) {
        recent = recent + "\n\n" + planAppendix;
      }
    }
    return recent;
  }
}
