package org.qubership.integration.platform.ai.chat.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.subscription.Cancellable;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.jboss.logging.Logger;
import org.jboss.logmanager.MDC;
import org.qubership.integration.platform.ai.chat.ChatEvent;
import org.qubership.integration.platform.ai.chat.ChatMdc;
import org.qubership.integration.platform.ai.chat.OpenChainTurnContextFactory;
import org.qubership.integration.platform.ai.chat.activity.LlmRateLimitBackoffSink;
import org.qubership.integration.platform.ai.chat.activity.ToolInvocationSink;
import org.qubership.integration.platform.ai.chat.conversation.ConversationMessage;
import org.qubership.integration.platform.ai.chat.conversation.ConversationService;
import org.qubership.integration.platform.ai.chat.model.ChatRequest;
import org.qubership.integration.platform.ai.chain.deploy.PendingRedeploy;
import org.qubership.integration.platform.ai.chain.deploy.PendingRedeployStore;
import org.qubership.integration.platform.ai.compiler.capture.ChatMemorySanitizer;
import org.qubership.integration.platform.ai.configuration.AppConfig;
import org.qubership.integration.platform.ai.llm.routing.ScenarioRouter;
import org.qubership.integration.platform.ai.model.ScenarioType;
import org.qubership.integration.platform.ai.logging.AiTraceLog;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.productpipeline.artifact.ProductPipelineArtifactStore;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunStore;

/**
 * Shared chat pipeline: conversation → router → persist.
 *
 * <p>Notable differences from ai-service:
 * <ul>
 *   <li>No mid-stream plan capture from markdown — plans arrive via tool calls only</li>
 *   <li>Step progress events ({@code event: step}) emitted by scenario handlers, not here</li>
 * </ul>
 */
@ApplicationScoped
public class ChatExecutionService {

  private static final Logger LOG = Logger.getLogger(ChatExecutionService.class);
  private static final String TOKEN_EVENT_PREFIX = "event: token\ndata: ";
  private static final String PATH_LABEL = "v1-sse";

  private final ScenarioRouter router;
  private final ConversationService conversationService;
  private final EffectiveUserTextService effectiveUserTextService;
  private final AppConfig appConfig;
  private final ProductPipelineRunStore runStore;
  private final ProductPipelineArtifactStore artifactStore;
  private final ObjectMapper objectMapper;
  private final ChatMemorySanitizer chatMemorySanitizer;
  private final ChatDecisionService decisionService;
  private final PendingRedeployStore pendingRedeployStore;
  private final OpenChainTurnContextFactory openChainTurnContextFactory;

  public ChatExecutionService(
      ScenarioRouter router,
      ConversationService conversationService,
      EffectiveUserTextService effectiveUserTextService,
      AppConfig appConfig,
      ProductPipelineRunStore runStore,
      ProductPipelineArtifactStore artifactStore,
      ObjectMapper objectMapper,
      ChatMemorySanitizer chatMemorySanitizer,
      ChatDecisionService decisionService,
      PendingRedeployStore pendingRedeployStore,
      OpenChainTurnContextFactory openChainTurnContextFactory) {
    this.decisionService = decisionService;
    this.pendingRedeployStore = pendingRedeployStore;
    this.openChainTurnContextFactory = openChainTurnContextFactory;
    this.router = router;
    this.conversationService = conversationService;
    this.effectiveUserTextService = effectiveUserTextService;
    this.appConfig = appConfig;
    this.runStore = runStore;
    this.artifactStore = artifactStore;
    this.objectMapper = objectMapper;
    this.chatMemorySanitizer = chatMemorySanitizer;
  }

  /** True when the request answers a card with a command the facade runs rather than a scenario. */
  private static boolean runsAsCommand(ChatRequest request) {
    return request.getDecision() != null && !runsAsScenario(request.getDecision().getAction());
  }

  /** Cards owned by a scenario rather than by the CREATE facade, answered by that scenario. */
  private static boolean runsAsScenario(String action) {
    return ChatEvent.IMPORT_ACTION.equals(action)
        || ChatEvent.APPLY_CHAIN_PATCH_ACTION.equals(action)
        || ChatEvent.REDEPLOY_ACTION.equals(action)
        || ChatEvent.CANCEL_REDEPLOY_ACTION.equals(action)
        || ChatEvent.DEPLOY_ACTION.equals(action)
        || ChatEvent.CANCEL_DEPLOY_ACTION.equals(action)
        || ChatEvent.UNDEPLOY_ACTION.equals(action)
        || ChatEvent.CANCEL_UNDEPLOY_ACTION.equals(action);
  }

  /** The click names the scenario, so the router does not guess it from transcript wording. */
  private static void applyScenarioHint(ChatRequest request) {
    String action = request.getDecision().getAction();
    if (ChatEvent.IMPORT_ACTION.equals(action)) {
      request.setScenarioHint(ScenarioType.IMPORT_SPECIFICATION);
      return;
    }
    if (ChatEvent.APPLY_CHAIN_PATCH_ACTION.equals(action)) {
      request.setScenarioHint(ScenarioType.COMPARE_AND_PATCH);
      return;
    }
    if (ChatEvent.REDEPLOY_ACTION.equals(action)
        || ChatEvent.CANCEL_REDEPLOY_ACTION.equals(action)
        || ChatEvent.DEPLOY_ACTION.equals(action)
        || ChatEvent.CANCEL_DEPLOY_ACTION.equals(action)
        || ChatEvent.UNDEPLOY_ACTION.equals(action)
        || ChatEvent.CANCEL_UNDEPLOY_ACTION.equals(action)) {
      request.setScenarioHint(ScenarioType.DEPLOY_CHAIN);
    }
  }

  private Multi<ChatEvent> openGate(String conversationId) {
    return decisionService
        .openDecision(conversationId)
        .map(decision -> Multi.createFrom().item((ChatEvent) decision))
        .orElseGet(() -> Multi.createFrom().empty());
  }

  public Multi<String> streamV1Sse(ChatRequest request) {
    return streamSse(request);
  }

  public Multi<String> streamUiDataLines(ChatRequest request) {
    return streamSse(request);
  }

  private Multi<String> streamSse(ChatRequest request) {
    String conversationId =
        request.getConversationId() != null ? request.getConversationId() : UUID.randomUUID().toString();

    MDC.put(ChatMdc.CONVERSATION_ID, conversationId);

    conversationService.getOrCreate(conversationId);
    // Repair dangling tool_calls left by prior ToolArgumentsException / aborted tool turns so the
    // next OpenAI request is well-formed (invalid_request_error otherwise).
    chatMemorySanitizer.repairDanglingToolCalls(conversationId);
    if (request.getDecision() != null) {
      // A typed answer needs no attachment or memory resolution: the marker is what the model reads.
      String domain =
          pendingRedeployStore
              .find(conversationId)
              .map(PendingRedeploy::domain)
              .orElse(null);
      request.setResolvedEffectiveUserText(
          ChatDecisionService.transcriptMarker(request.getDecision(), domain));
      applyScenarioHint(request);
    } else {
      request.setResolvedEffectiveUserText(effectiveUserTextService.resolve(request, conversationId));
      if (pendingRedeployStore
          .find(conversationId)
          .filter(PendingRedeploy::waitingForDomain)
          .isPresent()) {
        request.setScenarioHint(ScenarioType.DEPLOY_CHAIN);
      }
    }

    LOG.infof(
        "Chat request (%s): conversationId=%s, scenarioHint=%s, userPreview=%s",
        PATH_LABEL,
        conversationId,
        request.getScenarioHint(),
        AiTraceLog.previewOneLine(
            request.getEffectiveUserText(), AiTraceLog.DEFAULT_USER_PREVIEW_CHARS));

    conversationService.addMessage(
        conversationId, ConversationMessage.user(request.getEffectiveUserText()));

    logAiTurnStart(conversationId);

    StringBuilder responseBuffer = new StringBuilder();
    String finalConversationId = conversationId;
    AtomicReference<Cancellable> routedCancellation = new AtomicReference<>();

    // Approve / create-chain commands skip the router, but planning and materialization still
    // invoke tools. Bind the turn sink on both paths so event: step (kind=tool / kind=skill)
    // reaches the client the same way requirement-analysis already does.
    Multi<ChatEvent> routedWork;
    try {
      request.setOpenChainTurnContext(
          openChainTurnContextFactory.build(request, conversationId));
      routedWork =
          runsAsCommand(request)
              ? decisionService.apply(conversationId, request.getDecision())
              : router
                  .route(request, conversationId)
                  // A routed turn can end at a gate without knowing it did, so the turn closes by
                  // reporting whatever the run waits for now.
                  .onCompletion()
                  .switchTo(() -> openGate(conversationId));
    } catch (RuntimeException error) {
      routedWork = Multi.createFrom().failure(error);
    }
    Multi<ChatEvent> routed =
        bindBackoffSinkForTurn(
            routedWork,
            routedCancellation,
            appConfig.llm().rateLimit().maxTurnBackoffs(),
            conversationId);

    return Multi.createBy()
        .concatenating()
        .streams(
            Multi.createFrom().item(ChatEvent.meta(conversationId)), routed)
        .onItem()
        .invoke(event -> {
          if (event instanceof ChatEvent.Token token && token.text() != null) {
            responseBuffer.append(token.text());
          } else if (event instanceof ChatEvent.Decision decision) {
            if (!responseBuffer.isEmpty()) {
              responseBuffer.append('\n');
            }
            responseBuffer
                .append("[decision kind=")
                .append(decision.kind())
                .append(" actions=")
                .append(String.join(",", decision.actions()))
                .append("] ")
                .append(decision.question());
          }
        })
        .map(this::toSse)
        .onCancellation()
        .invoke(
            () -> {
              Cancellable route = routedCancellation.get();
              if (route != null) {
                route.cancel();
              }
            })
        .onTermination()
        .invoke(
            (failure, cancelled) -> {
              if (!responseBuffer.isEmpty()) {
                logAssistantResultIfEnabled(finalConversationId, responseBuffer.toString());
                conversationService.addMessage(
                    finalConversationId, ConversationMessage.assistant(responseBuffer.toString()));
              }
              if (failure != null) {
                LOG.warnf(
                    failure,
                    "Chat stream ended with failure (%s) for conversationId=%s — activePlan=%s",
                    PATH_LABEL,
                    finalConversationId,
                    describeActivePlan(finalConversationId));
              }
              MDC.remove(ChatMdc.CONVERSATION_ID);
            })
        .onCompletion()
        .continueWith("event: done\ndata: " + conversationId + "\n\n")
        .onFailure()
        .recoverWithMulti(
            err -> {
              LOG.errorf(
                  err,
                  "Chat stream failed (%s) for conversationId=%s, activePlan=%s",
                  PATH_LABEL,
                  finalConversationId,
                  describeActivePlan(finalConversationId));
              MDC.remove(ChatMdc.CONVERSATION_ID);
              return recoverFailedSse(finalConversationId, err);
            });
  }

  /**
   * Terminal SSE frames for a failed chat Multi. Always ends with {@code event: done} so clients
   * can fail closed without reusing a prior conversationId.
   */
  static Multi<String> recoverFailedSse(String conversationId, Throwable err) {
    String message = err == null || err.getMessage() == null ? "chat stream failed" : err.getMessage();
    return Multi.createFrom()
        .items(
            "event: error\ndata: " + dataEscape(message) + "\n\n",
            "event: done\ndata: " + conversationId + "\n\n");
  }

  /**
   * Binds {@link LlmRateLimitBackoffSink} and {@link ToolInvocationSink} for the routed chat turn
   * and unbinds both on completion, failure, or cancellation. Tool progress reaches the client as
   * {@code event: step} only while the tool sink is bound; without that bind, {@code ToolTraceLog}
   * still logs but the Rocky activity UI stays empty.
   */
  static Multi<ChatEvent> bindBackoffSinkForTurn(
      Multi<ChatEvent> routed, AtomicReference<Cancellable> routedCancellation) {
    return bindBackoffSinkForTurn(routed, routedCancellation, Integer.MAX_VALUE, null);
  }

  static Multi<ChatEvent> bindBackoffSinkForTurn(
      Multi<ChatEvent> routed,
      AtomicReference<Cancellable> routedCancellation,
      int maxTurnBackoffs) {
    return bindBackoffSinkForTurn(routed, routedCancellation, maxTurnBackoffs, null);
  }

  static Multi<ChatEvent> bindBackoffSinkForTurn(
      Multi<ChatEvent> routed,
      AtomicReference<Cancellable> routedCancellation,
      int maxTurnBackoffs,
      String conversationId) {
    return Multi.createFrom()
        .emitter(
            emitter -> {
              LlmRateLimitBackoffSink.bind(emitter::emit, null, maxTurnBackoffs);
              ToolInvocationSink.bind(emitter::emit, null, conversationId);
              emitter.onTermination(
                  () -> {
                    LlmRateLimitBackoffSink.unbind();
                    ToolInvocationSink.unbind();
                  });
              Cancellable subscription =
                  routed.subscribe().with(emitter::emit, emitter::fail, emitter::complete);
              routedCancellation.set(subscription::cancel);
            });
  }

  private void logAiTurnStart(String conversationId) {
    int historySize = conversationService.getMessages(conversationId).size();
    LOG.infof(
        "AI turn: path=%s, conversationId=%s, historyMessages=%d, activePlan=%s",
        PATH_LABEL,
        conversationId,
        historySize,
        describeActivePlan(conversationId));
  }

  private void logAssistantResultIfEnabled(String conversationId, String text) {
    if (!appConfig.trace().logAssistantResult()) {
      return;
    }
    if (text == null || text.isEmpty()) {
      return;
    }
    int maxChars = appConfig.trace().assistantResultMaxChars();
    LOG.infof(
        "Chat assistant result (%s): conversationId=%s, chars=%d, preview=%s",
        PATH_LABEL,
        conversationId,
        text.length(),
        AiTraceLog.preview(text, maxChars));
  }

  private String describeActivePlan(String conversationId) {
    return describeActivePlanForTrace(conversationId, runStore, artifactStore);
  }

  static String describeActivePlanForTrace(
      String conversationId,
      ProductPipelineRunStore runStore,
      ProductPipelineArtifactStore artifactStore) {
    return runStore
        .loadByConversation(conversationId)
        .flatMap(
            doc ->
                artifactStore
                    .latest(doc.run().runId(), Kind.CHAIN_PLAN_GRAPH)
                    .map(revision -> artifactStore.payload(revision, ChainPlanGraph.class))
                    .map(ChatExecutionService::formatActivePlan))
        .or(
            () ->
                runStore
                    .loadByConversation(conversationId)
                    .flatMap(
                        doc ->
                            artifactStore
                                .latest(doc.run().runId(), Kind.MATERIALIZATION_RESULT)
                                .map(revision -> "(materialized)")))
        .orElse("(none)");
  }

  private static String formatActivePlan(ChainPlanGraph graph) {
    String chainName =
        graph.chain() != null && graph.chain().name() != null ? graph.chain().name() : "unnamed";
    int nodeCount = graph.nodes() != null ? graph.nodes().size() : 0;
    return chainName + " nodes=" + nodeCount;
  }

  private String toSse(ChatEvent event) {
    return toSse(event, objectMapper);
  }

  /**
   * Single SSE framing authority: serializes a typed {@link ChatEvent} to an SSE frame, escaping
   * multi-line payloads and using Jackson for JSON event bodies. Package-private and static so the
   * framing can be unit-tested without standing up the bean.
   */
  static String toSse(ChatEvent event, ObjectMapper objectMapper) {
    return switch (event) {
      case ChatEvent.Meta m ->
          "event: meta\ndata: "
              + json(objectMapper, Map.of("conversationId", m.conversationId()))
              + "\n\n";
      case ChatEvent.Token t -> TOKEN_EVENT_PREFIX + dataEscape(t.text()) + "\n\n";
      case ChatEvent.Error e -> "event: error\ndata: " + dataEscape(e.message()) + "\n\n";
      case ChatEvent.Step s ->
          "event: step\ndata: " + json(objectMapper, stepPayload(s)) + "\n\n";
      case ChatEvent.Decision d ->
          "event: decision\ndata: " + json(objectMapper, decisionPayload(d)) + "\n\n";
    };
  }

  private static Map<String, Object> decisionPayload(ChatEvent.Decision decision) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("id", decision.id());
    payload.put("kind", decision.kind());
    payload.put("question", decision.question());
    payload.put("revision", decision.revision());
    payload.put("actions", decision.actions());
    if (decision.artifactType() != null) {
      payload.put("artifactType", decision.artifactType());
    }
    if (decision.artifactHash() != null) {
      payload.put("artifactHash", decision.artifactHash());
    }
    if (decision.reason() != null) {
      payload.put("reason", decision.reason());
    }
    if (!decision.missingEvidence().isEmpty()) {
      payload.put("missingEvidence", decision.missingEvidence());
    }
    return payload;
  }

  private static Map<String, Object> stepPayload(ChatEvent.Step step) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("id", step.id());
    payload.put("kind", step.kind());
    payload.put("status", step.status());
    if (step.label() != null) {
      payload.put("label", step.label());
    }
    if (step.parentId() != null) {
      payload.put("parentId", step.parentId());
    }
    return payload;
  }

  private static String dataEscape(String text) {
    return text == null ? "" : text.replace("\n", "\ndata: ");
  }

  private static String json(ObjectMapper objectMapper, Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      return "{}";
    }
  }
}
