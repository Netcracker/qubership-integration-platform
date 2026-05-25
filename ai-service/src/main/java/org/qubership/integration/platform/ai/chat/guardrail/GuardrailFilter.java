package org.qubership.integration.platform.ai.chat.guardrail;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.chat.chainplan.ActiveChainPlanService;
import org.qubership.integration.platform.ai.chat.context.ConversationContextBuilder;
import org.qubership.integration.platform.ai.chat.context.ConversationContextBuilder.ContextAppendices;
import org.qubership.integration.platform.ai.chat.model.ChatRequest;
import org.qubership.integration.platform.ai.configuration.AppConfig;
import org.qubership.integration.platform.ai.configuration.TranscriptLimits;
import org.qubership.integration.platform.ai.llm.qute.QuteUserMessageEscaping;

/**
 * Evaluates whether an incoming user message is on-topic for QIP. Uses transcript and attachment
 * manifest; does not read inlined attachment bodies.
 */
@ApplicationScoped
public class GuardrailFilter {

  private static final Logger LOG = Logger.getLogger(GuardrailFilter.class);

  private static final String REFUSAL_MESSAGE =
      """
      I can only help with QIP integration topics — designing integration chains,\s
      working with APIs, creating design documents, or implementing and testing QIP chains.\s
      Please ask me about those topics.\
      """;

  private final GuardrailService guardrailService;
  private final ActiveChainPlanService activeChainPlanService;
  private final AppConfig appConfig;
  private final ConversationContextBuilder conversationContextBuilder;

  public GuardrailFilter(
      GuardrailService guardrailService,
      ActiveChainPlanService activeChainPlanService,
      AppConfig appConfig,
      ConversationContextBuilder conversationContextBuilder) {
    this.guardrailService = guardrailService;
    this.activeChainPlanService = activeChainPlanService;
    this.appConfig = appConfig;
    this.conversationContextBuilder = conversationContextBuilder;
  }

  /** On-topic check without conversation context (e.g. tests). */
  public boolean isOnTopic(String message) {
    return isOnTopic(
        new GuardrailTurnContext(
            message, AttachmentManifest.empty(), false, null, "", java.util.Optional.empty()));
  }

  /**
   * @param conversationId may be null; when set, prior messages are loaded into the turn context.
   */
  public boolean isOnTopic(String message, String conversationId) {
    return isOnTopic(createTurnContext(message, null, false, conversationId));
  }

  public boolean isOnTopic(GuardrailTurnContext turn) {
    if (turn.isEmptyTurn()) {
      return false;
    }

    String message = turn.userMessage();
    String conversationId = turn.conversationId();

    if (GuardrailShortReplyPolicy.allowThrough(
        message, turn.recentTranscript(), turn.activePlan())) {
      logDeterministicYes(conversationId, message, "short continuation");
      return true;
    }

    if (ThreadContinuationPolicy.allowAttachmentReply(turn)) {
      logDeterministicYes(conversationId, message, "thread_continuation: attachment_reply");
      return true;
    }

    String classifierMessage = turn.toClassifierMessage();
    String contextBlock = buildClassifierContext(conversationId);

    try {
      String verdict =
          guardrailService
              .check(
                  QuteUserMessageEscaping.escapeForAiServiceUserMessage(contextBlock),
                  QuteUserMessageEscaping.escapeForAiServiceUserMessage(classifierMessage))
              .trim()
              .toUpperCase();
      LOG.infof(
          "Guardrail: conversationId=%s, verdict=%s, messagePreview=%s, hasAttachments=%s,"
              + " attachmentCount=%s",
          conversationId != null ? conversationId : "(none)",
          verdict,
          preview(message),
          turn.hasAttachments(),
          turn.attachmentManifest().count());
      return "YES".equals(verdict);
    } catch (Exception e) {
      LOG.warnf("Guardrail check failed, allowing message through: %s", e.getMessage());
      return true;
    }
  }

  /**
   * Builds turn context from a chat request (call after {@code getOrCreate} on the conversation).
   */
  public GuardrailTurnContext createTurnContext(ChatRequest request, String conversationId) {
    return createTurnContext(
        request.getMessage(),
        request.getAttachmentObjectKeys(),
        request.hasAttachmentPayload(),
        conversationId);
  }

  public GuardrailTurnContext createTurnContext(
      String userMessage,
      java.util.List<String> attachmentObjectKeys,
      boolean hasAttachments,
      String conversationId) {
    String recentTranscript = buildTranscriptOnly(conversationId);
    var activePlan = activeChainPlanService.getActive(conversationId);
    AttachmentManifest manifest = AttachmentManifest.fromObjectKeys(attachmentObjectKeys);
    return new GuardrailTurnContext(
        userMessage, manifest, hasAttachments, conversationId, recentTranscript, activePlan);
  }

  private static void logDeterministicYes(String conversationId, String message, String reason) {
    LOG.infof(
        "Guardrail: conversationId=%s, verdict=YES (deterministic %s), messagePreview=%s",
        conversationId != null ? conversationId : "(none)", reason, preview(message));
  }

  private static String preview(String message) {
    if (message == null || message.isBlank()) {
      return "(blank)";
    }
    return message.substring(0, Math.min(120, message.length()))
        + (message.length() > 120 ? "…" : "");
  }

  private String buildTranscriptOnly(String conversationId) {
    return conversationContextBuilder.build(
        conversationId, guardrailLimits(), ContextAppendices.TRANSCRIPT_ONLY);
  }

  private String buildClassifierContext(String conversationId) {
    return conversationContextBuilder.build(
        conversationId, guardrailLimits(), ContextAppendices.PLAN);
  }

  private TranscriptLimits guardrailLimits() {
    return TranscriptLimits.from(appConfig.guardrail());
  }

  public String getRefusalMessage() {
    return REFUSAL_MESSAGE;
  }
}
