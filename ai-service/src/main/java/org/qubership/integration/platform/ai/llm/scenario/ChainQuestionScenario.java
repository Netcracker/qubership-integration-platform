package org.qubership.integration.platform.ai.llm.scenario;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.eclipse.microprofile.faulttolerance.exceptions.TimeoutException;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.chain.presentation.ChainCatalogFacts;
import org.qubership.integration.platform.ai.chain.presentation.ChainCatalogFactsService;
import org.qubership.integration.platform.ai.chain.presentation.ChainCatalogViewService;
import org.qubership.integration.platform.ai.chain.presentation.ChainContextExtractor;
import org.qubership.integration.platform.ai.chat.CatalogRead;
import org.qubership.integration.platform.ai.chat.ChatEvent;
import org.qubership.integration.platform.ai.chat.LastAssistantTurn;
import org.qubership.integration.platform.ai.chat.OpenChainTurnContext;
import org.qubership.integration.platform.ai.chat.failure.CatalogOperation;
import org.qubership.integration.platform.ai.chat.failure.KnownFailure;
import org.qubership.integration.platform.ai.chat.failure.KnownFailureMapper;
import org.qubership.integration.platform.ai.chat.failure.PinnedFailure;
import org.qubership.integration.platform.ai.chat.failure.PinnedFailureStore;
import org.qubership.integration.platform.ai.chat.model.ChatRequest;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient.DeploymentDto;
import org.qubership.integration.platform.ai.llm.agent.ChainPresentationAgent;
import org.qubership.integration.platform.ai.llm.qute.QuteUserMessageEscaping;
import org.qubership.integration.platform.ai.llm.routing.OpenChainTurnPlan;
import org.qubership.integration.platform.ai.llm.routing.OpenChainTurnPlan.AnswerShape;
import org.qubership.integration.platform.ai.model.ScenarioType;
import org.qubership.integration.platform.ai.presentation.QuestionIntent;
import org.qubership.integration.platform.ai.presentation.QuestionIntentClassifier;

@ApplicationScoped
@ForScenario(ScenarioType.ASK_CHAIN)
public class ChainQuestionScenario implements ScenarioHandler {

  private static final Logger LOG = Logger.getLogger(ChainQuestionScenario.class);

  private final ChainContextExtractor chainContextExtractor;
  private final ChainCatalogFactsService chainCatalogFactsService;
  private final ChainCatalogViewService chainCatalogViewService;
  private final ChainPresentationAgent chainPresentationAgent;
  private final ObjectMapper objectMapper;
  private final KnownFailureMapper knownFailureMapper;
  private final PinnedFailureStore pinnedFailureStore;

  @Inject
  public ChainQuestionScenario(
      ChainContextExtractor chainContextExtractor,
      ChainCatalogFactsService chainCatalogFactsService,
      ChainCatalogViewService chainCatalogViewService,
      ChainPresentationAgent chainPresentationAgent,
      ObjectMapper objectMapper,
      KnownFailureMapper knownFailureMapper,
      PinnedFailureStore pinnedFailureStore) {
    this.chainContextExtractor = chainContextExtractor;
    this.chainCatalogFactsService = chainCatalogFactsService;
    this.chainCatalogViewService = chainCatalogViewService;
    this.chainPresentationAgent = chainPresentationAgent;
    this.objectMapper = objectMapper;
    this.knownFailureMapper = knownFailureMapper;
    this.pinnedFailureStore = pinnedFailureStore;
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

    AnswerShape answerShape = answerShape(request, userMessage);
    LOG.infof(
        "ASK_CHAIN conversationId=%s chainId=%s intent=%s userChars=%d",
        conversationId,
        chainId,
        answerShape,
        userMessage != null ? userMessage.length() : 0);

    OpenChainTurnContext turn = request != null ? request.getOpenChainTurnContext() : null;
    try {
      if (turn != null) {
        return answerFromTurn(turn, conversationId, userMessage, answerShape, chainId);
      }
      ChainCatalogFacts facts = chainCatalogFactsService.load(chainId);
      return answerWithFacts(
          conversationId, userMessage, answerShape, facts, "", Optional.empty());
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
      AnswerShape answerShape,
      String chainId)
      throws JsonProcessingException {
    Optional<ChainCatalogFacts> facts = turn.chainFacts();
    if (answerShape == AnswerShape.EXPLAIN) {
      return streamExplainAnswer(conversationId, userMessage, turn);
    }
    if (!turn.factsUnavailable() && facts.isPresent()) {
      return Multi.createFrom()
          .item(
              ChatEvent.token(
                  formatDeterministicAnswer(facts.get(), answerShape),
                  LastAssistantTurn.Kind.DESCRIBE));
    }
    return knownOrRethrow(
        new TimeoutException("open-chain catalog facts unavailable"), conversationId, chainId);
  }

  private Multi<ChatEvent> answerWithFacts(
      String conversationId,
      String userMessage,
      AnswerShape answerShape,
      ChainCatalogFacts facts,
      String transcriptWindow,
      Optional<PinnedFailure> pin)
      throws JsonProcessingException {
    if (answerShape != AnswerShape.EXPLAIN) {
      return Multi.createFrom()
          .item(
              ChatEvent.token(
                  formatDeterministicAnswer(facts, answerShape),
                  LastAssistantTurn.Kind.DESCRIBE));
    }
    OpenChainTurnContext turn =
        new OpenChainTurnContext(
            conversationId,
            facts.chainId(),
            userMessage,
            transcriptWindow,
            pin,
            Optional.of(facts),
            false);
    return streamExplainAnswer(conversationId, userMessage, turn);
  }

  private String formatDeterministicAnswer(ChainCatalogFacts facts, AnswerShape answerShape)
      throws JsonProcessingException {
    return switch (answerShape) {
      case GRAPH -> chainCatalogViewService.formatMermaidFlowchart(facts);
      case TREE -> chainCatalogViewService.formatTree(facts);
      case JSON -> chainCatalogViewService.formatPrettyJson(facts);
      case SCRIPT -> chainCatalogViewService.formatScriptDetails(facts);
      case EXPLAIN -> chainCatalogFactsService.formatFallbackSummary(facts);
    };
  }

  private Multi<ChatEvent> streamExplainAnswer(
      String conversationId, String userMessage, OpenChainTurnContext turn)
      throws JsonProcessingException {
    ChainCatalogFacts facts = turn.chainFacts().orElse(null);
    Optional<PinnedFailure> pin = turn.pinnedFailure();
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
        buildExplainUserMessage(turn, pinnedSafeText, userMessage);

    return chainPresentationAgent
        .chat(conversationId, agentMessage)
        .onItem()
        .transform(text -> ChatEvent.token(text, LastAssistantTurn.Kind.DESCRIBE))
        .onFailure()
        .recoverWithMulti(err -> {
          LOG.errorf(err, "Chain presentation agent failed conversationId=%s", conversationId);
          return Multi.createFrom()
              .item(ChatEvent.token(fallback, LastAssistantTurn.Kind.DESCRIBE));
        });
  }

  private String buildExplainUserMessage(
      OpenChainTurnContext turn, String pinnedSafeText, String userMessage)
      throws JsonProcessingException {
    String body =
        """
        Last assistant turn:
        %s

        Recent transcript (context, not a source of catalog facts):
        %s

        Safe failure summary (may be empty; diagnostic details are intentionally omitted):
        %s

        User question:
        %s

        Chain facts evidence:
        %s

        Snapshot evidence:
        %s

        Deployment evidence (runtime errors are intentionally omitted):
        %s
        """
            .formatted(
                formatLastTurn(turn.lastAssistantTurn()),
                turn.transcriptWindow() != null ? turn.transcriptWindow() : "",
                pinnedSafeText != null ? pinnedSafeText : "",
                userMessage != null ? userMessage : "",
                formatRead(turn.facts(), value -> value),
                formatRead(turn.snapshots(), value -> value),
                formatRead(turn.deployments(), ChainQuestionScenario::safeDeployments));
    return QuteUserMessageEscaping.escapeForAiServiceUserMessage(body);
  }

  private String formatRead(CatalogRead<?> read, java.util.function.Function<Object, Object> safe)
      throws JsonProcessingException {
    if (read.state() != CatalogRead.State.AVAILABLE) {
      return read.state().name();
    }
    Object value = safe.apply(read.value());
    return "AVAILABLE\n"
        + objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
  }

  private static List<Map<String, Object>> safeDeployments(Object value) {
    @SuppressWarnings("unchecked")
    List<DeploymentDto> deployments = (List<DeploymentDto>) value;
    return deployments.stream()
        .map(
            deployment -> {
              Map<String, Object> safe = new LinkedHashMap<>();
              safe.put("id", deployment.id());
              safe.put("snapshotId", deployment.snapshotId());
              safe.put("name", deployment.name());
              safe.put("domain", deployment.domain());
              Map<String, String> states = new LinkedHashMap<>();
              if (deployment.runtime() != null && deployment.runtime().states() != null) {
                deployment.runtime().states().forEach(
                    (name, state) -> states.put(name, state == null ? null : state.status()));
              }
              safe.put("states", states);
              return safe;
            })
        .toList();
  }

  private static String formatLastTurn(Optional<LastAssistantTurn> turn) {
    return turn.map(value -> "kind: " + value.kind() + "\ntext: " + value.text())
        .orElse("kind: OTHER\ntext: (none)");
  }

  private static AnswerShape answerShape(ChatRequest request, String userMessage) {
    if (request != null && request.getOpenChainTurnPlan() instanceof OpenChainTurnPlan.Ask ask) {
      return ask.answerShape();
    }
    QuestionIntent legacy = QuestionIntentClassifier.classify(userMessage);
    return AnswerShape.valueOf(legacy.name());
  }

  private Multi<ChatEvent> knownOrRethrow(Throwable error, String conversationId, String chainId) {
    Optional<KnownFailure> known = knownFailureMapper.tryMap(error, CatalogOperation.FACTS);
    if (known.isEmpty()) {
      return Multi.createFrom().failure(error);
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
