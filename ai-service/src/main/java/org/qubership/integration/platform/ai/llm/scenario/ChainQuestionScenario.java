package org.qubership.integration.platform.ai.llm.scenario;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.chain.presentation.ChainCatalogFacts;
import org.qubership.integration.platform.ai.chain.presentation.ChainCatalogFactsService;
import org.qubership.integration.platform.ai.chain.presentation.ChainCatalogViewService;
import org.qubership.integration.platform.ai.chain.presentation.ChainContextExtractor;
import org.qubership.integration.platform.ai.chain.presentation.ChainImplementationPresentationFacts;
import org.qubership.integration.platform.ai.presentation.QuestionIntent;
import org.qubership.integration.platform.ai.presentation.QuestionIntentClassifier;
import org.qubership.integration.platform.ai.chat.ChatEvent;
import org.qubership.integration.platform.ai.chat.model.ChatRequest;
import org.qubership.integration.platform.ai.llm.agent.ChainPresentationAgent;
import org.qubership.integration.platform.ai.llm.qute.QuteUserMessageEscaping;
import org.qubership.integration.platform.ai.model.ScenarioType;
import com.fasterxml.jackson.databind.ObjectMapper;

@ApplicationScoped
@ForScenario(ScenarioType.ASK_CHAIN)
public class ChainQuestionScenario implements ScenarioHandler {

  private static final Logger LOG = Logger.getLogger(ChainQuestionScenario.class);

  private final ChainContextExtractor chainContextExtractor;
  private final ChainCatalogFactsService chainCatalogFactsService;
  private final ChainCatalogViewService chainCatalogViewService;
  private final ChainPresentationAgent chainPresentationAgent;
  private final ObjectMapper objectMapper;

  @Inject
  public ChainQuestionScenario(
      ChainContextExtractor chainContextExtractor,
      ChainCatalogFactsService chainCatalogFactsService,
      ChainCatalogViewService chainCatalogViewService,
      ChainPresentationAgent chainPresentationAgent,
      ObjectMapper objectMapper) {
    this.chainContextExtractor = chainContextExtractor;
    this.chainCatalogFactsService = chainCatalogFactsService;
    this.chainCatalogViewService = chainCatalogViewService;
    this.chainPresentationAgent = chainPresentationAgent;
    this.objectMapper = objectMapper;
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

    try {
      ChainCatalogFacts facts = chainCatalogFactsService.load(chainId);
      if (intent != QuestionIntent.EXPLAIN) {
        String answer = formatDeterministicAnswer(facts, intent);
        return Multi.createFrom().item(ChatEvent.token(answer));
      }
      return streamExplainAnswer(conversationId, userMessage, facts);
    } catch (JsonProcessingException e) {
      LOG.errorf(e, "Failed to format chain JSON conversationId=%s", conversationId);
      return Multi.createFrom()
          .item(ChatEvent.error("Failed to format chain JSON: " + e.getMessage()));
    } catch (RuntimeException e) {
      LOG.errorf(e, "ASK_CHAIN failed conversationId=%s chainId=%s", conversationId, chainId);
      return Multi.createFrom()
          .item(ChatEvent.error("Failed to read chain from catalog: " + e.getMessage()));
    }
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
      String conversationId, String userMessage, ChainCatalogFacts facts)
      throws JsonProcessingException {
    String fallback = chainCatalogFactsService.formatFallbackSummary(facts);
    ChainImplementationPresentationFacts payload =
        new ChainImplementationPresentationFacts(
            userMessage, userMessage, facts, null);
    String agentMessage = buildExplainUserMessage(payload);

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

  private String buildExplainUserMessage(ChainImplementationPresentationFacts facts)
      throws JsonProcessingException {
    String factsJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(facts);
    String body =
        """
        Explain this catalog chain for the user.

        User question:
        %s

        Chain facts JSON (use only this data):
        %s
        """
            .formatted(facts.userQuestion(), factsJson);
    return QuteUserMessageEscaping.escapeForAiServiceUserMessage(body);
  }
}
