package org.qubership.integration.platform.ai.llm.scenario;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Optional;
import org.eclipse.microprofile.faulttolerance.exceptions.TimeoutException;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.chain.presentation.ChainCatalogFacts;
import org.qubership.integration.platform.ai.chain.presentation.ChainCatalogFactsService;
import org.qubership.integration.platform.ai.chain.presentation.ChainCatalogViewService;
import org.qubership.integration.platform.ai.chain.presentation.ChainContextExtractor;
import org.qubership.integration.platform.ai.chat.ChatEvent;
import org.qubership.integration.platform.ai.chat.OpenChainTurnContext;
import org.qubership.integration.platform.ai.chat.conversation.ConversationService;
import org.qubership.integration.platform.ai.chat.conversation.TranscriptSearch;
import org.qubership.integration.platform.ai.chat.failure.CatalogOperation;
import org.qubership.integration.platform.ai.chat.failure.KnownFailure;
import org.qubership.integration.platform.ai.chat.failure.KnownFailureMapper;
import org.qubership.integration.platform.ai.chat.failure.PinnedFailure;
import org.qubership.integration.platform.ai.chat.failure.PinnedFailureStore;
import org.qubership.integration.platform.ai.chat.model.ChatRequest;
import org.qubership.integration.platform.ai.llm.agent.ChainPresentationAgent;
import org.qubership.integration.platform.ai.llm.qute.QuteUserMessageEscaping;
import org.qubership.integration.platform.ai.model.ScenarioType;
import org.qubership.integration.platform.ai.presentation.QuestionIntent;
import org.qubership.integration.platform.ai.presentation.QuestionIntentClassifier;

@ApplicationScoped
@ForScenario(ScenarioType.ASK_CHAIN)
public class ChainQuestionScenario implements ScenarioHandler {

  private static final Logger LOG = Logger.getLogger(ChainQuestionScenario.class);

  private static final String FACTS_UNAVAILABLE = "FACTS_UNAVAILABLE";

  private static final int OLDER_TRANSCRIPT_HIT_LIMIT = 5;

  private final ChainContextExtractor chainContextExtractor;
  private final ChainCatalogFactsService chainCatalogFactsService;
  private final ChainCatalogViewService chainCatalogViewService;
  private final ChainPresentationAgent chainPresentationAgent;
  private final ObjectMapper objectMapper;
  private final KnownFailureMapper knownFailureMapper;
  private final PinnedFailureStore pinnedFailureStore;
  private final ConversationService conversationService;

  @Inject
  public ChainQuestionScenario(
      ChainContextExtractor chainContextExtractor,
      ChainCatalogFactsService chainCatalogFactsService,
      ChainCatalogViewService chainCatalogViewService,
      ChainPresentationAgent chainPresentationAgent,
      ObjectMapper objectMapper,
      KnownFailureMapper knownFailureMapper,
      PinnedFailureStore pinnedFailureStore,
      ConversationService conversationService) {
    this.chainContextExtractor = chainContextExtractor;
    this.chainCatalogFactsService = chainCatalogFactsService;
    this.chainCatalogViewService = chainCatalogViewService;
    this.chainPresentationAgent = chainPresentationAgent;
    this.objectMapper = objectMapper;
    this.knownFailureMapper = knownFailureMapper;
    this.pinnedFailureStore = pinnedFailureStore;
    this.conversationService = conversationService;
  }

  @Override
  public Multi<ChatEvent> handle(
      ChatRequest request, String conversationId, ScenarioType scenarioType) {
    String userMessage = request != null ? request.getEffectiveUserText() : "";
    String chainId =
        chainContextExtractor
            .resolveChainId(request, conversationId)
            .orElse(null);

    if (chainId == null) {
      LOG.infof("ASK_CHAIN without chain context conversationId=%s", conversationId);
      return Multi.createFrom()
          .item(
              ChatEvent.token(
                  "No chain context found. Open a chain in the UI or implement a chain first,"
                      + " then ask about it."));
    }

    QuestionIntent intent = QuestionIntentClassifier.classify(userMessage);
    LOG.infof(
        "ASK_CHAIN conversationId=%s chainId=%s intent=%s userChars=%d",
        conversationId,
        chainId,
        intent,
        userMessage != null ? userMessage.length() : 0);

    OpenChainTurnContext turn = request != null ? request.getOpenChainTurnContext() : null;
    try {
      if (turn != null) {
        return answerFromTurn(turn, conversationId, userMessage, intent, chainId);
      }
      ChainCatalogFacts facts = chainCatalogFactsService.load(chainId);
      return answerWithFacts(conversationId, userMessage, intent, facts, "", Optional.empty());
    } catch (JsonProcessingException e) {
      LOG.errorf(e, "Failed to format chain JSON conversationId=%s", conversationId);
      throw new RuntimeException(e);
    } catch (RuntimeException e) {
      return knownOrRethrow(e, conversationId, chainId);
    }
  }

  private Multi<ChatEvent> answerFromTurn(
      OpenChainTurnContext turn,
      String conversationId,
      String userMessage,
      QuestionIntent intent,
      String chainId)
      throws JsonProcessingException {
    Optional<ChainCatalogFacts> facts = turn.chainFacts();
    String transcriptWindow = turn.transcriptWindow() != null ? turn.transcriptWindow() : "";
    Optional<PinnedFailure> pin = turn.pinnedFailure();
    if (!turn.factsUnavailable() && facts.isPresent()) {
      return answerWithFacts(
          conversationId, userMessage, intent, facts.get(), transcriptWindow, pin);
    }
    if (intent == QuestionIntent.EXPLAIN) {
      return streamExplainAnswer(conversationId, userMessage, null, transcriptWindow, pin);
    }
    if (pin.isPresent()) {
      return Multi.createFrom().item(ChatEvent.token(pin.get().safeText()));
    }
    return knownOrRethrow(
        new TimeoutException("open-chain catalog facts unavailable"), conversationId, chainId);
  }

  private Multi<ChatEvent> answerWithFacts(
      String conversationId,
      String userMessage,
      QuestionIntent intent,
      ChainCatalogFacts facts,
      String transcriptWindow,
      Optional<PinnedFailure> pin)
      throws JsonProcessingException {
    if (intent != QuestionIntent.EXPLAIN) {
      return Multi.createFrom().item(ChatEvent.token(formatDeterministicAnswer(facts, intent)));
    }
    return streamExplainAnswer(conversationId, userMessage, facts, transcriptWindow, pin);
  }

  private String formatDeterministicAnswer(ChainCatalogFacts facts, QuestionIntent intent)
      throws JsonProcessingException {
    return switch (intent) {
      case GRAPH -> chainCatalogViewService.formatMermaidFlowchart(facts);
      case TREE -> chainCatalogViewService.formatTree(facts);
      case JSON -> chainCatalogViewService.formatPrettyJson(facts);
      case SCRIPT -> chainCatalogViewService.formatScriptDetails(facts);
      case EXPLAIN -> chainCatalogFactsService.formatFallbackSummary(facts);
    };
  }

  private Multi<ChatEvent> streamExplainAnswer(
      String conversationId,
      String userMessage,
      ChainCatalogFacts facts,
      String transcriptWindow,
      Optional<PinnedFailure> pin)
      throws JsonProcessingException {
    String pinnedSafeText = pin.map(PinnedFailure::safeText).orElse("");
    String fallback;
    if (facts != null) {
      fallback = chainCatalogFactsService.formatFallbackSummary(facts);
    } else if (!pinnedSafeText.isBlank()) {
      fallback = pinnedSafeText;
    } else {
      fallback = KnownFailureMapper.CATALOG_TIMEOUT_MESSAGE;
    }
    String agentMessage =
        buildExplainUserMessage(
            conversationId, transcriptWindow, pinnedSafeText, userMessage, facts);

    return chainPresentationAgent
        .chat(conversationId, agentMessage)
        .onItem()
        .transform(ChatEvent::token)
        .onFailure()
        .recoverWithMulti(err -> {
          LOG.errorf(err, "Chain presentation agent failed conversationId=%s", conversationId);
          return Multi.createFrom().item(ChatEvent.token(fallback));
        });
  }

  private String buildExplainUserMessage(
      String conversationId,
      String transcriptWindow,
      String pinnedSafeText,
      String userMessage,
      ChainCatalogFacts facts)
      throws JsonProcessingException {
    String factsOrFlag =
        facts == null
            ? FACTS_UNAVAILABLE
            : objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(facts);
    String body =
        """
        Recent transcript:
        %s

        Pinned catalog failure (may be empty):
        %s

        User question:
        %s

        Chain facts JSON or FACTS_UNAVAILABLE:
        %s

        Older transcript hits:
        %s
        """
            .formatted(
                transcriptWindow != null ? transcriptWindow : "",
                pinnedSafeText != null ? pinnedSafeText : "",
                userMessage != null ? userMessage : "",
                factsOrFlag,
                formatOlderTranscriptHits(conversationId, userMessage));
    return QuteUserMessageEscaping.escapeForAiServiceUserMessage(body);
  }

  private String formatOlderTranscriptHits(String conversationId, String userMessage) {
    List<String> hits =
        TranscriptSearch.find(
            conversationService.getMessages(conversationId),
            userMessage,
            OLDER_TRANSCRIPT_HIT_LIMIT);
    if (hits.isEmpty()) {
      return "(none)";
    }
    return String.join("\n", hits);
  }

  private Multi<ChatEvent> knownOrRethrow(Throwable error, String conversationId, String chainId) {
    Optional<KnownFailure> known = knownFailureMapper.tryMap(error, CatalogOperation.FACTS);
    if (known.isEmpty()) {
      if (error instanceof RuntimeException runtime) {
        throw runtime;
      }
      throw new RuntimeException(error);
    }
    KnownFailure failure = known.get();
    LOG.warnf(error, "ASK_CHAIN failed conversationId=%s chainId=%s", conversationId, chainId);
    if (chainId != null && !chainId.isBlank()) {
      pinnedFailureStore.put(
          new PinnedFailure(
              conversationId, chainId, failure.safeText(), failure.diagnosticDetail()));
    }
    return Multi.createFrom().item(ChatEvent.token(failure.safeText()));
  }
}
