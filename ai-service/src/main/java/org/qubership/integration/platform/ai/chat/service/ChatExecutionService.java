package org.qubership.integration.platform.ai.chat.service;

import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.chat.chainplan.ActiveChainPlanService;
import org.qubership.integration.platform.ai.chat.chainplan.ChainImplementationPlanCapture;
import org.qubership.integration.platform.ai.chat.chainplan.ImplementGateCoordinator;
import org.qubership.integration.platform.ai.chat.conversation.ConversationMessage;
import org.qubership.integration.platform.ai.chat.conversation.ConversationService;
import org.qubership.integration.platform.ai.chat.guardrail.GuardrailFilter;
import org.qubership.integration.platform.ai.chat.model.ChatRequest;
import org.qubership.integration.platform.ai.chat.planning.ConversationPlanningDiaryService;
import org.qubership.integration.platform.ai.configuration.AppConfig;
import org.qubership.integration.platform.ai.llm.routing.ScenarioRouter;
import org.qubership.integration.platform.ai.logging.AiTraceLog;
import org.qubership.integration.platform.ai.model.ScenarioType;

import java.util.Optional;
import java.util.UUID;

/**
 * Shared chat pipeline: guardrail, conversation, router stream, and persistence. {@link
 * org.qubership.integration.platform.ai.chat.rest.ChatController} uses the native SSE (v1) form;
 * the UI adapter uses {@link #streamUiDataLines(ChatRequest)}.
 */
@ApplicationScoped
public class ChatExecutionService {

  private static final Logger LOG = Logger.getLogger(ChatExecutionService.class);
  private static final String TOKEN_EVENT_PREFIX = "event: token\ndata: ";

  private final GuardrailFilter guardrailFilter;
  private final ScenarioRouter router;
  private final ConversationService conversationService;
  private final EffectiveUserTextService effectiveUserTextService;
  private final AppConfig appConfig;
  private final ActiveChainPlanService activeChainPlanService;
  private final ConversationPlanningDiaryService planningDiaryService;
  private final ImplementGateCoordinator implementGateCoordinator;

  public ChatExecutionService(
      GuardrailFilter guardrailFilter,
      ScenarioRouter router,
      ConversationService conversationService,
      EffectiveUserTextService effectiveUserTextService,
      AppConfig appConfig,
      ActiveChainPlanService activeChainPlanService,
      ConversationPlanningDiaryService planningDiaryService,
      ImplementGateCoordinator implementGateCoordinator) {
    this.guardrailFilter = guardrailFilter;
    this.router = router;
    this.conversationService = conversationService;
    this.effectiveUserTextService = effectiveUserTextService;
    this.appConfig = appConfig;
    this.activeChainPlanService = activeChainPlanService;
    this.planningDiaryService = planningDiaryService;
    this.implementGateCoordinator = implementGateCoordinator;
  }

  /** Native Quarkus SSE: {@code event: token|done|error|hitl_checkpoint}. */
  public Multi<String> streamV1Sse(ChatRequest request) {
    return streamSse(request, "v1-sse");
  }

  /**
   * Same SSE framing as {@link #streamV1Sse} ({@code event: token|done|error|hitl_checkpoint}) so
   * clients can stream progress and HITL checkpoints instead of a single buffered payload.
   */
  public Multi<String> streamUiDataLines(ChatRequest request) {
    return streamSse(request, "ui-with-progress");
  }

  private Multi<String> streamSse(ChatRequest request, String pathLabel) {
    String conversationId =
        request.getConversationId() != null
            ? request.getConversationId()
            : UUID.randomUUID().toString();

    conversationService.getOrCreate(conversationId, ScenarioType.UNKNOWN);
    request.setResolvedEffectiveUserText(effectiveUserTextService.resolve(request, conversationId));

    planningDiaryService.recordDesignHintsFromUserTurn(
        conversationId, request.getEffectiveUserText(), request.getAttachmentObjectKeys());

    LOG.infof(
        "Chat request (%s): conversationId=%s, scenarioHint=%s, userPreview=%s",
        pathLabel,
        conversationId,
        request.getScenarioHint(),
        AiTraceLog.previewOneLine(
            request.getEffectiveUserText(), AiTraceLog.DEFAULT_USER_PREVIEW_CHARS));

    activeChainPlanService.onUserMessage(conversationId, request.getEffectiveUserText());

    if (!guardrailFilter.isOnTopic(guardrailFilter.createTurnContext(request, conversationId))) {
      LOG.infof(
          "Guardrail refused message (%s): conversationId=%s — skipping router",
          pathLabel, conversationId);
      String refusal = guardrailFilter.getRefusalMessage();
      return Multi.createFrom()
          .items(
              TOKEN_EVENT_PREFIX + refusal + "\n\n",
              "event: done\ndata: " + conversationId + "\n\n");
    }

    conversationService.addMessage(
        conversationId, ConversationMessage.Role.USER, request.getEffectiveUserText());

    logAiTurnStart(pathLabel, conversationId);

    if (request.getScenarioHint() == ScenarioType.IMPLEMENT_CHAIN) {
      activeChainPlanService.acknowledgeImplementGate(conversationId);
    }

    StringBuilder responseBuffer = new StringBuilder();
    String finalConversationId = conversationId;
    String[] lastCaptureAttemptPayload = new String[1];

    return routeAfterOptionalImplementGate(request, conversationId)
        // Buffer raw scenario tokens before SSE framing; reversing SSE lines breaks fenced JSON
        // (newlines become "\ndata: ") and prevents ChainImplementationPlan capture.
        .onItem()
        .invoke(
            token -> {
              if (token != null) {
                responseBuffer.append(token);
                maybeCapturePlanFromStreamBuffer(
                    finalConversationId, responseBuffer.toString(), lastCaptureAttemptPayload);
              }
            })
        .map(this::formatV1SseEvent)
        .onTermination()
        .invoke(
            (failure, cancelled) -> {
              if (responseBuffer.isEmpty()) {
                return;
              }
              logAssistantResultIfEnabled(
                  pathLabel, finalConversationId, responseBuffer.toString());
              conversationService.addMessage(
                  finalConversationId,
                  ConversationMessage.Role.ASSISTANT,
                  responseBuffer.toString());
              if (failure != null) {
                LOG.warnf(
                    "Chat stream ended with failure (%s) for conversationId=%s — assistant"
                        + " response persisted, activePlan=%s",
                    pathLabel,
                    finalConversationId,
                    activeChainPlanService.describeActiveForLog(finalConversationId));
              }
            })
        .onCompletion()
        .continueWith("event: done\ndata: " + conversationId + "\n\n")
        .onFailure()
        .recoverWithItem(
            err -> {
              LOG.errorf(
                  err,
                  "Chat stream failed (%s) for conversationId=%s",
                  pathLabel,
                  finalConversationId);
              return "event: error\ndata: " + err.getMessage() + "\n\n";
            });
  }

  private Multi<String> routeAfterOptionalImplementGate(
      ChatRequest request, String conversationId) {
    if (request.getScenarioHint() != ScenarioType.IMPLEMENT_CHAIN
        && activeChainPlanService.needsImplementGateHitl(conversationId)) {
      return implementGateCoordinator.runGateThenRoute(request, conversationId);
    }
    return router.route(request, conversationId);
  }

  private void logAiTurnStart(String pathLabel, String conversationId) {
    int historySize = conversationService.getMessages(conversationId).size();
    LOG.infof(
        "AI turn: path=%s, conversationId=%s, historyMessages=%d, activePlan=%s",
        pathLabel,
        conversationId,
        historySize,
        activeChainPlanService.describeActiveForLog(conversationId));
  }

  private String formatV1SseEvent(String token) {
    if (token.startsWith("[HITL]")) {
      String payload = token.substring(6);
      return "event: hitl_checkpoint\ndata: " + payload + "\n\n";
    }
    String escapedToken = token.replace("\n", "\ndata: ");
    return TOKEN_EVENT_PREFIX + escapedToken + "\n\n";
  }

  public Response getHistoryResponse(String conversationId) {
    return Response.ok(conversationService.getMessages(conversationId)).build();
  }

  /**
   * Captures a plan when the stream buffer contains a complete fenced {@code ChainImplementationPlan}
   * JSON block. Skips repeat attempts for the same payload in one turn (dedup also runs in {@link
   * ActiveChainPlanService#publishFromJson}).
   */
  private void maybeCapturePlanFromStreamBuffer(
      String conversationId, String assistantText, String[] lastCaptureAttemptPayload) {
    Optional<String> planJson = ChainImplementationPlanCapture.findLatestPlanJson(assistantText);
    if (planJson.isEmpty()) {
      return;
    }
    String payload = planJson.get();
    if (payload.equals(lastCaptureAttemptPayload[0])) {
      return;
    }
    lastCaptureAttemptPayload[0] = payload;
    activeChainPlanService.captureFromAssistantText(conversationId, assistantText);
  }

  private void logAssistantResultIfEnabled(String pathLabel, String conversationId, String text) {
    if (!appConfig.trace().logAssistantResult()) {
      return;
    }
    if (text == null || text.isEmpty()) {
      return;
    }
    int maxChars = appConfig.trace().assistantResultMaxChars();
    LOG.infof(
        "Chat assistant result (%s): conversationId=%s, chars=%d, preview=%s",
        pathLabel, conversationId, text.length(), AiTraceLog.preview(text, maxChars));
  }
}
