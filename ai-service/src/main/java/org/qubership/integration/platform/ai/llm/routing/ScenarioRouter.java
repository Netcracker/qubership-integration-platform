package org.qubership.integration.platform.ai.llm.routing;

import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.UnsatisfiedResolutionException;
import org.jboss.logging.Logger;
import org.jboss.logmanager.MDC;
import org.qubership.integration.platform.ai.chat.ChatMdc;
import org.qubership.integration.platform.ai.chat.chainplan.ActiveChainPlanService;
import org.qubership.integration.platform.ai.chat.context.ConversationContextBuilder;
import org.qubership.integration.platform.ai.chat.context.ConversationContextBuilder.ContextAppendices;
import org.qubership.integration.platform.ai.chat.conversation.TranscriptBalancing;
import org.qubership.integration.platform.ai.chat.model.ChatRequest;
import org.qubership.integration.platform.ai.configuration.AppConfig;
import org.qubership.integration.platform.ai.configuration.TranscriptLimits;
import org.qubership.integration.platform.ai.llm.qute.QuteUserMessageEscaping;
import org.qubership.integration.platform.ai.llm.scenario.ForScenarioLiteral;
import org.qubership.integration.platform.ai.llm.scenario.ScenarioHandler;
import org.qubership.integration.platform.ai.model.ScenarioType;

import java.util.Locale;
import java.util.Optional;

/**
 * Classifies the user's intent and delegates to the appropriate {@link ScenarioHandler} via CDI
 * qualifier lookup.
 */
@ApplicationScoped
public class ScenarioRouter {

  private static final int ROUTER_TRANSCRIPT_TAIL_CHARS = 500;

  private static final Logger LOG = Logger.getLogger(ScenarioRouter.class);

  private final RouterAgent routerAgent;
  private final ActiveChainPlanService activeChainPlanService;
  private final AppConfig appConfig;
  private final ConversationPhaseResolver conversationPhaseResolver;
  private final EmbeddingScenarioRouter embeddingScenarioRouter;
  private final Instance<ScenarioHandler> handlers;
  private final ConversationContextBuilder conversationContextBuilder;

  public ScenarioRouter(
      RouterAgent routerAgent,
      ActiveChainPlanService activeChainPlanService,
      AppConfig appConfig,
      ConversationPhaseResolver conversationPhaseResolver,
      EmbeddingScenarioRouter embeddingScenarioRouter,
      @Any Instance<ScenarioHandler> handlers,
      ConversationContextBuilder conversationContextBuilder) {
    this.routerAgent = routerAgent;
    this.activeChainPlanService = activeChainPlanService;
    this.appConfig = appConfig;
    this.conversationPhaseResolver = conversationPhaseResolver;
    this.embeddingScenarioRouter = embeddingScenarioRouter;
    this.handlers = handlers;
    this.conversationContextBuilder = conversationContextBuilder;
  }

  /**
   * Routes the request to the appropriate scenario handler. If a scenarioHint is provided, it
   * bypasses classification.
   *
   * @return streaming token Multi
   */
  public Multi<String> route(ChatRequest request, String conversationId) {
    ScenarioType type = resolveScenarioType(request, conversationId);
    LOG.infof("Routing conversationId=%s to scenario=%s", conversationId, type);

    Instance<ScenarioHandler> selected = handlers.select(new ForScenarioLiteral(type));
    if (!selected.isResolvable()) {
      LOG.errorf("No ScenarioHandler CDI bean for scenario=%s — check @ForScenario wiring", type);
      throw new IllegalStateException("No ScenarioHandler registered for scenario: " + type);
    }
    ScenarioHandler handler;
    try {
      handler = selected.get();
    } catch (UnsatisfiedResolutionException e) {
      LOG.error(String.format("ScenarioHandler resolution failed for scenario=%s", type), e);
      throw e;
    }
    Multi<String> stream = handler.handle(request, conversationId, type);
    return stream
        .onSubscription()
        .invoke(
            subscription -> {
              MDC.put(ChatMdc.CONVERSATION_ID, conversationId);
              MDC.put(ChatMdc.SCENARIO_TYPE, type.name());
            })
        .onTermination()
        .invoke(
            () -> {
              MDC.remove(ChatMdc.CONVERSATION_ID);
              MDC.remove(ChatMdc.SCENARIO_TYPE);
            });
  }

  private ScenarioType resolveScenarioType(ChatRequest request, String conversationId) {
    ScenarioType resolved;
    if (request.getScenarioHint() != null) {
      LOG.infof(
          "Routing uses explicit scenarioHint=%s (classifier skipped)", request.getScenarioHint());
      resolved = request.getScenarioHint();
    } else {
      try {
        String recentConversation = buildRouterTranscript(conversationId);
        resolved = classifyWithTranscript(recentConversation, request, conversationId);
      } catch (Exception e) {
        LOG.warnf("Router classification failed: %s — falling back to UNKNOWN", e.getMessage());
        resolved = ScenarioType.UNKNOWN;
      }
    }
    return coerceImplementChainWhenPlanNotApproved(resolved, conversationId);
  }

  private String buildRouterTranscript(String conversationId) {
    return conversationContextBuilder.build(
        conversationId, routerLimits(), ContextAppendices.DIARY_AND_PLAN);
  }

  private ScenarioType classifyWithTranscript(
      String recentConversation, ChatRequest request, String conversationId) {
    ConversationPhase phase = conversationPhaseResolver.resolve(conversationId);
    var activePlan = activeChainPlanService.getActive(conversationId);
    boolean planApproved = activeChainPlanService.isApproved(conversationId);

    Optional<ScenarioType> phaseRoute =
        PhaseRoutingPolicy.tryResolve(phase, request.getMessage(), activePlan, planApproved);
    if (phaseRoute.isPresent()) {
      logRoutingDecision(conversationId, "phase", phase, phaseRoute.get(), true, null, null);
      return phaseRoute.get();
    }

    Optional<ScenarioType> fast =
        RouterHeuristics.tryFastResolve(
            request.getMessage(), recentConversation, activePlan, planApproved);
    if (fast.isPresent()) {
      logRoutingDecision(conversationId, "heuristic", phase, fast.get(), true, null, null);
      return fast.get();
    }

    String transcriptTail =
        TranscriptBalancing.tailOnly(recentConversation, ROUTER_TRANSCRIPT_TAIL_CHARS);
    Optional<RoutingMatch> emb =
        embeddingScenarioRouter.classify(request.getMessage(), phase, transcriptTail);
    if (emb.isPresent()) {
      RoutingMatch m = emb.get();
      logRoutingDecision(
          conversationId, "embedding", phase, m.scenario(), true, m.score(), m.margin());
      return m.scenario();
    }

    ScenarioType classified =
        routerAgent.classify(
            QuteUserMessageEscaping.escapeForAiServiceUserMessage(recentConversation),
            phase.name(),
            QuteUserMessageEscaping.escapeForAiServiceUserMessage(request.getMessage()));
    logRoutingDecision(conversationId, "llm", phase, classified, false, null, null);
    return classified;
  }

  private TranscriptLimits routerLimits() {
    return TranscriptLimits.from(appConfig.router());
  }

  private static void logRoutingDecision(
      String conversationId,
      String layer,
      ConversationPhase phase,
      ScenarioType scenario,
      boolean llmSkipped,
      Double embeddingScore,
      Double embeddingMargin) {
    LOG.infof(
        "Routing decision conversationId=%s layer=%s phase=%s scenario=%s llmSkipped=%s"
            + " embedScore=%s embedMargin=%s",
        conversationId,
        layer,
        phase,
        scenario,
        llmSkipped,
        embeddingScore != null ? String.format(Locale.ROOT, "%.4f", embeddingScore) : "n/a",
        embeddingMargin != null ? String.format(Locale.ROOT, "%.4f", embeddingMargin) : "n/a");
  }

  private ScenarioType coerceImplementChainWhenPlanNotApproved(
      ScenarioType type, String conversationId) {
    boolean approved = activeChainPlanService.isApproved(conversationId);
    ScenarioType out = ImplementChainRoutingPolicy.effectiveScenario(type, approved);
    if (out != type) {
      LOG.infof(
          "Re-routing IMPLEMENT_CHAIN -> CREATE_CHAIN_PLAN (no approved plan): conversationId=%s",
          conversationId);
    }
    return out;
  }
}
