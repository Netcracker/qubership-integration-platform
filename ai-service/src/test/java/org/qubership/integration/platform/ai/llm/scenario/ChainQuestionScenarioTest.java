package org.qubership.integration.platform.ai.llm.scenario;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.helpers.test.AssertSubscriber;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chain.presentation.ChainCatalogFacts;
import org.qubership.integration.platform.ai.chain.presentation.ChainCatalogFactsService;
import org.qubership.integration.platform.ai.chain.presentation.ChainCatalogViewService;
import org.qubership.integration.platform.ai.chain.presentation.ChainContextExtractor;
import org.qubership.integration.platform.ai.chat.ChatEvent;
import org.qubership.integration.platform.ai.chat.model.ChatRequest;
import org.qubership.integration.platform.ai.llm.agent.ChainPresentationAgent;
import org.qubership.integration.platform.ai.model.ScenarioType;
import com.fasterxml.jackson.databind.ObjectMapper;

class ChainQuestionScenarioTest {

  private static final String CONVERSATION_ID = "conv-ask-chain";

  private ChainContextExtractor chainContextExtractor;
  private ChainCatalogFactsService chainCatalogFactsService;
  private ChainCatalogViewService chainCatalogViewService;
  private ChainPresentationAgent chainPresentationAgent;
  private ChainQuestionScenario scenario;

  @BeforeEach
  void setUp() {
    chainContextExtractor = mock(ChainContextExtractor.class);
    chainCatalogFactsService = mock(ChainCatalogFactsService.class);
    chainCatalogViewService = new ChainCatalogViewService(new ObjectMapper());
    chainPresentationAgent = mock(ChainPresentationAgent.class);
    scenario =
        new ChainQuestionScenario(
            chainContextExtractor,
            chainCatalogFactsService,
            chainCatalogViewService,
            chainPresentationAgent,
            new ObjectMapper());
  }

  @Test
  void returnsHelpfulMessageWhenChainContextMissing() {
    when(chainContextExtractor.resolveChainId(any(), eq(CONVERSATION_ID)))
        .thenReturn(Optional.empty());

    AssertSubscriber<ChatEvent> sub =
        scenario
            .handle(chatRequest("explain this chain"), CONVERSATION_ID, ScenarioType.ASK_CHAIN)
            .subscribe()
            .withSubscriber(AssertSubscriber.create(1));

    sub.awaitCompletion();
    ChatEvent.Token token = (ChatEvent.Token) sub.getItems().get(0);
    assertTrue(token.text().contains("No chain context found"));
  }

  @Test
  void returnsMermaidForShowGraph() {
    when(chainContextExtractor.resolveChainId(any(), eq(CONVERSATION_ID)))
        .thenReturn(Optional.of("chain-1"));
    when(chainCatalogFactsService.load("chain-1")).thenReturn(sampleFacts());

    AssertSubscriber<ChatEvent> sub =
        scenario
            .handle(chatRequest("show graph"), CONVERSATION_ID, ScenarioType.ASK_CHAIN)
            .subscribe()
            .withSubscriber(AssertSubscriber.create(1));

    sub.awaitCompletion();
    ChatEvent.Token token = (ChatEvent.Token) sub.getItems().get(0);
    assertTrue(token.text().contains("flowchart TD"));
  }

  @Test
  void streamsExplainAnswerFromPresenter() {
    when(chainContextExtractor.resolveChainId(any(), eq(CONVERSATION_ID)))
        .thenReturn(Optional.of("chain-1"));
    when(chainCatalogFactsService.load("chain-1")).thenReturn(sampleFacts());
    when(chainPresentationAgent.chat(eq(CONVERSATION_ID), any()))
        .thenReturn(Multi.createFrom().items("This chain ", "handles greetings."));

    AssertSubscriber<ChatEvent> sub =
        scenario
            .handle(chatRequest("what does this chain do?"), CONVERSATION_ID, ScenarioType.ASK_CHAIN)
            .subscribe()
            .withSubscriber(AssertSubscriber.create(Long.MAX_VALUE));

    sub.awaitCompletion();

    String combined =
        sub.getItems().stream()
            .filter(ChatEvent.Token.class::isInstance)
            .map(event -> ((ChatEvent.Token) event).text())
            .reduce("", String::concat);

    assertTrue(combined.contains("handles greetings"));
  }

  private static ChatRequest chatRequest(String text) {
    ChatRequest request = new ChatRequest();
    request.setMessage(text);
    return request;
  }

  private static ChainCatalogFacts sampleFacts() {
    return new ChainCatalogFacts(
        "chain-1",
        "Greetings",
        "",
        1,
        0,
        "HTTP Trigger (http-trigger)",
        List.of(),
        List.of(),
        "built_in_catalog");
  }
}
