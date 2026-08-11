package org.qubership.integration.platform.ai.llm.routing;

import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.UnsatisfiedResolutionException;
import org.jboss.logging.Logger;
import org.jboss.logmanager.MDC;
import org.qubership.integration.platform.ai.chat.ChatEvent;
import org.qubership.integration.platform.ai.chat.ChatMdc;
import org.qubership.integration.platform.ai.chat.conversation.ConversationMessage;
import org.qubership.integration.platform.ai.chat.conversation.ConversationService;
import org.qubership.integration.platform.ai.chain.presentation.ChainContextExtractor;
import org.qubership.integration.platform.ai.chat.intent.UserIntentPatterns;
import org.qubership.integration.platform.ai.chat.model.ChatRequest;
import org.qubership.integration.platform.ai.llm.scenario.ForScenarioLiteral;
import org.qubership.integration.platform.ai.llm.scenario.ScenarioHandler;
import org.qubership.integration.platform.ai.model.ScenarioType;
import org.qubership.integration.platform.ai.plan.RequirementDraft;
import org.qubership.integration.platform.ai.plan.RequirementDraftStore;
import org.qubership.integration.platform.ai.productpipeline.create.CreateRunSelectionService;
import org.qubership.integration.platform.ai.productpipeline.create.ProductPipelineChatAdapter;
import org.qubership.integration.platform.ai.productpipeline.create.UnsupportedCreateRunBindingException;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Classifies user intent and delegates to the appropriate {@link ScenarioHandler}.
 *
 * <p>CREATE-owned scenarios bind to the product pipeline. Legacy bundle/validation stores are
 * removed after hard cutover.
 */
@ApplicationScoped
public class ScenarioRouter {

  private static final Logger LOG = Logger.getLogger(ScenarioRouter.class);

  private final RouterAgent routerAgent;
  private final ConversationPhaseResolver conversationPhaseResolver;
  private final ConversationService conversationService;
  private final ChainContextExtractor chainContextExtractor;
  private final RequirementDraftStore requirementDraftStore;
  private final Instance<ScenarioHandler> handlers;
  private final CreateRunSelectionService createRunSelectionService;
  private final ProductPipelineChatAdapter productPipelineChatAdapter;

  public ScenarioRouter(
      RouterAgent routerAgent,
      ConversationPhaseResolver conversationPhaseResolver,
      ConversationService conversationService,
      ChainContextExtractor chainContextExtractor,
      RequirementDraftStore requirementDraftStore,
      @Any Instance<ScenarioHandler> handlers) {
    this(
        routerAgent,
        conversationPhaseResolver,
        conversationService,
        chainContextExtractor,
        requirementDraftStore,
        handlers,
        null,
        null);
  }

  @jakarta.inject.Inject
  public ScenarioRouter(
      RouterAgent routerAgent,
      ConversationPhaseResolver conversationPhaseResolver,
      ConversationService conversationService,
      ChainContextExtractor chainContextExtractor,
      RequirementDraftStore requirementDraftStore,
      @Any Instance<ScenarioHandler> handlers,
      CreateRunSelectionService createRunSelectionService,
      ProductPipelineChatAdapter productPipelineChatAdapter) {
    this.routerAgent = routerAgent;
    this.conversationPhaseResolver = conversationPhaseResolver;
    this.conversationService = conversationService;
    this.chainContextExtractor = chainContextExtractor;
    this.requirementDraftStore = requirementDraftStore;
    this.handlers = handlers;
    this.createRunSelectionService = createRunSelectionService;
    this.productPipelineChatAdapter = productPipelineChatAdapter;
  }

  public Multi<ChatEvent> route(ChatRequest request, String conversationId) {
    if (createRunSelectionService != null && productPipelineChatAdapter != null) {
      try {
        if (createRunSelectionService.existing(conversationId).isPresent()) {
          LOG.infof("Routing conversationId=%s to product CREATE pipeline", conversationId);
          return productPipelineChatAdapter.handle(request, conversationId);
        }
      } catch (UnsupportedCreateRunBindingException e) {
        LOG.warnf(
            "Unsupported CREATE binding conversationId=%s errorId=%s",
            conversationId, e.errorId());
        return Multi.createFrom().item(ChatEvent.error(e.sseMessage()));
      }
    }

    RoutingOutcome outcome = resolveRouting(request, conversationId);
    if (outcome.errorMessage() != null) {
      LOG.warnf(
          "Routing error response conversationId=%s message=%s",
          conversationId, outcome.errorMessage());
      return Multi.createFrom().item(ChatEvent.error(outcome.errorMessage()));
    }
    if (outcome.terminalMessage() != null) {
      LOG.infof(
          "Routing terminal response conversationId=%s message=%s",
          conversationId, outcome.terminalMessage());
      return Multi.createFrom().item(ChatEvent.token(outcome.terminalMessage()));
    }

    ScenarioType type = outcome.scenarioType();
    if (createRunSelectionService != null
        && productPipelineChatAdapter != null
        && isCreateOwnedScenario(type)) {
      try {
        createRunSelectionService.selectOrCreate(conversationId);
      } catch (UnsupportedCreateRunBindingException e) {
        LOG.warnf(
            "Unsupported CREATE binding during select conversationId=%s errorId=%s",
            conversationId, e.errorId());
        return Multi.createFrom().item(ChatEvent.error(e.sseMessage()));
      }
      LOG.infof("Routing conversationId=%s to product CREATE pipeline (new run)", conversationId);
      return productPipelineChatAdapter.handle(request, conversationId);
    }

    ScenarioType handlerType = resolveHandlerType(type);
    LOG.infof("Routing conversationId=%s to scenario=%s handler=%s", conversationId, type, handlerType);

    Instance<ScenarioHandler> selected = handlers.select(new ForScenarioLiteral(handlerType));
    if (!selected.isResolvable()) {
      LOG.errorf("No ScenarioHandler CDI bean for scenario=%s", type);
      throw new IllegalStateException("No ScenarioHandler registered for scenario: " + type);
    }

    ScenarioHandler handler;
    try {
      handler = selected.get();
    } catch (UnsatisfiedResolutionException e) {
      LOG.errorf(e, "ScenarioHandler resolution failed for scenario=%s", type);
      throw e;
    }

    return handler
        .handle(request, conversationId, type)
        .onSubscription()
        .invoke(
            __ -> {
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

  RoutingOutcome resolveRouting(ChatRequest request, String conversationId) {
    ConversationPhase phase = conversationPhaseResolver.resolve(conversationId);

    Optional<RoutingOutcome> managedImport =
        ImportSpecificationRoutingPolicy.tryResolveManagedImportRouting(
            request, conversationId, requirementDraftStore);
    if (managedImport.isPresent()) {
      return applyPostRoutingEffects(managedImport.get(), conversationId);
    }

    ScenarioType resolved;
    boolean explicitScenarioHint = request.getScenarioHint() != null;
    if (explicitScenarioHint) {
      LOG.infof(
          "Routing uses explicit scenarioHint=%s (classifier skipped)", request.getScenarioHint());
      resolved = request.getScenarioHint();
    } else {
      try {
        resolved = classify(request, conversationId, phase);
      } catch (Exception e) {
        if (phase == ConversationPhase.COLD || phase == ConversationPhase.DISCOVERY) {
          LOG.warnf(
              e,
              "Router classification failed — falling back to GATHER_REQUIREMENTS (phase=%s)",
              phase);
          resolved = ScenarioType.GATHER_REQUIREMENTS;
        } else {
          LOG.warnf(e, "Router classification failed — surfacing error (phase=%s)", phase);
          return RoutingOutcome.error("Router classification failed: " + e.getMessage());
        }
      }
    }

    return applyPostRoutingEffects(RoutingOutcome.scenario(resolved), conversationId);
  }

  private RoutingOutcome applyPostRoutingEffects(RoutingOutcome outcome, String conversationId) {
    if (outcome.terminalMessage() != null || outcome.errorMessage() != null) {
      return outcome;
    }

    ScenarioType effective = coerceToSupportedHandler(outcome.scenarioType());

    if (effective == ScenarioType.IMPLEMENT_CHAIN) {
      ScenarioType advanced =
          ImplementCapabilityLadder.advance(false, hasReadyDraft(conversationId), false);
      if (advanced != ScenarioType.IMPLEMENT_CHAIN) {
        LOG.infof(
            "Implement intent advanced conversationId=%s from IMPLEMENT_CHAIN to %s",
            conversationId, advanced);
        effective = advanced;
      }
    }

    if (effective == ScenarioType.CREATE_CHAIN_PLAN && !hasReadyDraft(conversationId)) {
      LOG.infof(
          "Create-chain intent advanced conversationId=%s to GATHER_REQUIREMENTS (no ready draft)",
          conversationId);
      return RoutingOutcome.scenario(ScenarioType.GATHER_REQUIREMENTS);
    }

    return RoutingOutcome.scenario(effective);
  }

  private boolean hasReadyDraft(String conversationId) {
    return requirementDraftStore
        .get(conversationId)
        .filter(RequirementDraft::readyForPlan)
        .isPresent();
  }

  private boolean hasImportConfirmOpenQuestion(String conversationId) {
    return requirementDraftStore
        .get(conversationId)
        .filter(RequirementDraft::hasImportConfirmOpenQuestion)
        .isPresent();
  }

  private ScenarioType classify(
      ChatRequest request, String conversationId, ConversationPhase phase) {
    String userMessage = request.getEffectiveUserText();
    boolean hasChainContext = chainContextExtractor.hasChainContext(request, conversationId);

    Optional<ScenarioType> phaseRoute =
        PhaseRoutingPolicy.tryResolve(
            phase,
            userMessage,
            false,
            false,
            hasChainContext,
            hasReadyDraft(conversationId),
            hasImportConfirmOpenQuestion(conversationId));
    if (phaseRoute.isPresent()) {
      logRoutingDecision(conversationId, "phase", phase, phaseRoute.get());
      return phaseRoute.get();
    }

    Optional<ScenarioType> fast = RouterHeuristics.tryFastResolve(userMessage);
    if (fast.isPresent()) {
      logRoutingDecision(conversationId, "heuristic", phase, fast.get());
      return fast.get();
    }

    String transcript = buildRouterTranscript(conversationId);
    ScenarioType classified = routerAgent.classify(transcript, phase.name(), userMessage);
    logRoutingDecision(conversationId, "llm", phase, classified);
    return classified;
  }

  private String buildRouterTranscript(String conversationId) {
    List<ConversationMessage> messages = conversationService.getMessages(conversationId);
    if (messages.isEmpty()) {
      return "";
    }
    StringBuilder sb = new StringBuilder();
    for (ConversationMessage message : messages) {
      if (sb.length() > 0) {
        sb.append('\n');
      }
      sb.append(message.role().name().toLowerCase(Locale.ROOT))
          .append(": ")
          .append(message.content());
    }
    return sb.toString();
  }

  private ScenarioType coerceToSupportedHandler(ScenarioType type) {
    ScenarioType handlerType = resolveHandlerType(type);
    if (handlers.select(new ForScenarioLiteral(handlerType)).isResolvable()) {
      return type;
    }
    LOG.warnf("No handler for scenario=%s — falling back to CREATE_CHAIN_PLAN", type);
    return ScenarioType.CREATE_CHAIN_PLAN;
  }

  private static ScenarioType resolveHandlerType(ScenarioType type) {
    if (type == ScenarioType.IMPLEMENT_CHAIN) {
      return ScenarioType.CREATE_CHAIN_PLAN;
    }
    return type;
  }

  private static boolean isCreateOwnedScenario(ScenarioType type) {
    return type == ScenarioType.GATHER_REQUIREMENTS
        || type == ScenarioType.CREATE_CHAIN_PLAN
        || type == ScenarioType.IMPLEMENT_CHAIN
        || type == ScenarioType.IMPORT_SPECIFICATION;
  }

  private static void logRoutingDecision(
      String conversationId, String layer, ConversationPhase phase, ScenarioType scenario) {
    LOG.infof(
        "Routing decision conversationId=%s layer=%s phase=%s scenario=%s",
        conversationId, layer, phase, scenario);
  }

  record RoutingOutcome(ScenarioType scenarioType, String terminalMessage, String errorMessage) {
    static RoutingOutcome scenario(ScenarioType scenarioType) {
      return new RoutingOutcome(scenarioType, null, null);
    }

    static RoutingOutcome terminal(String message) {
      return new RoutingOutcome(null, message, null);
    }

    static RoutingOutcome error(String message) {
      return new RoutingOutcome(null, null, message);
    }
  }
}
