package org.qubership.integration.platform.ai.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import org.eclipse.microprofile.faulttolerance.exceptions.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chain.presentation.ChainCatalogFacts;
import org.qubership.integration.platform.ai.chain.presentation.ChainCatalogFactsService;
import org.qubership.integration.platform.ai.chain.presentation.ChainContextExtractor;
import org.qubership.integration.platform.ai.chat.conversation.ConversationMessage;
import org.qubership.integration.platform.ai.chat.conversation.ConversationService;
import org.qubership.integration.platform.ai.chat.failure.KnownFailureMapper;
import org.qubership.integration.platform.ai.chat.failure.PinnedFailure;
import org.qubership.integration.platform.ai.chat.failure.PinnedFailureStore;
import org.qubership.integration.platform.ai.chat.model.ChatRequest;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.llm.routing.OpenChainTurnPlan.AnswerShape;
import org.qubership.integration.platform.ai.llm.routing.OpenChainTurnPlan.DeployOp;
import org.qubership.integration.platform.ai.llm.routing.OpenChainTurnPlan.InfoNeed;
import org.qubership.integration.platform.ai.llm.routing.OpenChainTurnPlan.TurnReferent;
import org.qubership.integration.platform.ai.llm.routing.OpenChainTurnPlanner;
import org.qubership.integration.platform.ai.llm.routing.OpenChainTurnPlanner.Capture;
import org.qubership.integration.platform.ai.llm.routing.OpenChainTurnPlanner.Kind;
import org.qubership.integration.platform.ai.model.ScenarioType;

class OpenChainTurnContextFactoryTest {

  private static final String CONVERSATION_ID = "conv-open-1";
  private static final String CHAIN_A = "chain-a";
  private static final String CHAIN_B = "chain-b";

  private final ConversationService conversations = new ConversationService();
  private final PinnedFailureStore pins = new PinnedFailureStore();
  private final KnownFailureMapper mapper = new KnownFailureMapper();

  private ChainContextExtractor extractor;
  private ChainCatalogFactsService factsService;
  private CatalogRestClient catalogRestClient;
  private OpenChainTurnPlanner turnPlanner;
  private LastAssistantTurnStore lastTurnStore;
  private OpenChainTurnContextFactory factory;

  @BeforeEach
  void setUp() {
    extractor = mock(ChainContextExtractor.class);
    factsService = mock(ChainCatalogFactsService.class);
    catalogRestClient = mock(CatalogRestClient.class);
    turnPlanner = mock(OpenChainTurnPlanner.class);
    lastTurnStore = new LastAssistantTurnStore();
    when(turnPlanner.plan(anyString(), anyString(), anyString()))
        .thenReturn(
            new Capture(
                Kind.ASK,
                TurnReferent.OPEN_CHAIN,
                List.of(InfoNeed.FACTS),
                DeployOp.NONE,
                AnswerShape.EXPLAIN));
    factory =
        new OpenChainTurnContextFactory(
            extractor,
            conversations,
            pins,
            factsService,
            mapper,
            catalogRestClient,
            turnPlanner,
            lastTurnStore);
  }

  @Test
  void windowKeepsLastTwelveMessagesAndClipsEachToFiveHundredChars() {
    when(extractor.resolveChainId(any(), anyString())).thenReturn(Optional.of(CHAIN_A));
    when(factsService.load(CHAIN_A)).thenReturn(facts(CHAIN_A));
    conversations.getOrCreate(CONVERSATION_ID);
    for (int i = 0; i < 12; i++) {
      conversations.addMessage(
          CONVERSATION_ID,
          i % 2 == 0
              ? ConversationMessage.user("early-" + i)
              : ConversationMessage.assistant("early-" + i));
    }
    String longUser = "x".repeat(600);
    conversations.addMessage(CONVERSATION_ID, ConversationMessage.user(longUser));

    ChatRequest request = request("what does this chain do?");
    OpenChainTurnContext context = factory.build(request, CONVERSATION_ID);

    String window = context.transcriptWindow();
    assertFalse(window.contains("early-0"));
    assertTrue(window.startsWith("assistant: early-1\n"));
    assertTrue(window.contains("user: early-2"));
    assertTrue(window.endsWith("user: " + "x".repeat(500)));
    assertEquals(12, window.split("\n", -1).length);
    assertFalse(window.contains("x".repeat(501)));
  }

  @Test
  void pinForChainAIsNotReturnedForChainB() {
    pins.put(
        new PinnedFailure(
            CONVERSATION_ID, CHAIN_A, KnownFailureMapper.CATALOG_TIMEOUT_MESSAGE, "TimeoutException"));
    when(extractor.resolveChainId(any(), anyString())).thenReturn(Optional.of(CHAIN_B));
    when(factsService.load(CHAIN_B)).thenReturn(facts(CHAIN_B));

    OpenChainTurnContext context = factory.build(request("patch the mapper"), CONVERSATION_ID);

    assertEquals(CHAIN_B, context.chainId());
    assertTrue(context.pinnedFailure().isEmpty());
  }

  @Test
  void pinForOpenChainIsReturned() {
    PinnedFailure pin =
        new PinnedFailure(
            CONVERSATION_ID, CHAIN_A, KnownFailureMapper.CATALOG_TIMEOUT_MESSAGE, "TimeoutException");
    pins.put(pin);
    when(extractor.resolveChainId(any(), anyString())).thenReturn(Optional.of(CHAIN_A));
    when(factsService.load(CHAIN_A)).thenReturn(facts(CHAIN_A));

    OpenChainTurnContext context = factory.build(request("try again"), CONVERSATION_ID);

    assertEquals(pin, context.pinnedFailure().orElseThrow());
  }

  @Test
  void catalogTimeoutSetsFactsUnavailableWithoutThrowingOrPinning() {
    when(extractor.resolveChainId(any(), anyString())).thenReturn(Optional.of(CHAIN_A));
    when(factsService.load(CHAIN_A))
        .thenThrow(new TimeoutException("CatalogRestClient$$CDIWrapper#getChain timed out"));

    OpenChainTurnContext context = factory.build(request("what is in this chain?"), CONVERSATION_ID);

    assertTrue(context.factsUnavailable());
    assertTrue(context.chainFacts().isEmpty());
    assertTrue(context.pinnedFailure().isEmpty());
    assertTrue(pins.find(CONVERSATION_ID, CHAIN_A).isEmpty());
    assertEquals(CONVERSATION_ID, context.conversationId());
    assertEquals(CHAIN_A, context.chainId());
    assertEquals("what is in this chain?", context.userMessage());
  }

  @Test
  void snapshotQuestionReadsOnlySnapshotsAndPreservesAnEmptySuccessfulRead() {
    when(extractor.resolveChainId(any(), anyString())).thenReturn(Optional.of(CHAIN_A));
    when(turnPlanner.plan(anyString(), anyString(), anyString()))
        .thenReturn(
            new Capture(
                Kind.ASK,
                TurnReferent.OPEN_CHAIN,
                List.of(InfoNeed.SNAPSHOTS),
                DeployOp.NONE,
                AnswerShape.EXPLAIN));
    when(catalogRestClient.listSnapshots(CHAIN_A)).thenReturn(List.of());

    OpenChainTurnContext context = factory.build(request("has it any snapshots?"), CONVERSATION_ID);

    assertEquals(CatalogRead.State.AVAILABLE, context.snapshots().state());
    assertEquals(List.of(), context.snapshots().value());
    assertEquals(CatalogRead.State.NOT_REQUESTED, context.facts().state());
    assertEquals(CatalogRead.State.NOT_REQUESTED, context.deployments().state());
    verify(factsService, never()).load(anyString());
    verify(catalogRestClient, never()).listDeployments(anyString());
  }

  @Test
  void snapshotTimeoutIsUnavailableRatherThanAnEmptyList() {
    when(extractor.resolveChainId(any(), anyString())).thenReturn(Optional.of(CHAIN_A));
    when(turnPlanner.plan(anyString(), anyString(), anyString()))
        .thenReturn(
            new Capture(
                Kind.ASK,
                TurnReferent.OPEN_CHAIN,
                List.of(InfoNeed.SNAPSHOTS),
                DeployOp.NONE,
                AnswerShape.EXPLAIN));
    when(catalogRestClient.listSnapshots(CHAIN_A))
        .thenThrow(new TimeoutException("snapshot read timed out"));

    OpenChainTurnContext context = factory.build(request("has it any snapshots?"), CONVERSATION_ID);

    assertEquals(CatalogRead.State.UNAVAILABLE, context.snapshots().state());
    assertTrue(context.snapshots().availableValue().isEmpty());
  }

  @Test
  void describeChainLoadsFactsSnapshotsAndDeployments() {
    when(extractor.resolveChainId(any(), anyString())).thenReturn(Optional.of(CHAIN_A));
    when(factsService.load(CHAIN_A)).thenReturn(facts(CHAIN_A));
    when(catalogRestClient.listSnapshots(CHAIN_A))
        .thenReturn(List.of(new CatalogRestClient.SnapshotDto("snap-1", "v1")));
    when(catalogRestClient.listDeployments(CHAIN_A)).thenReturn(List.of());

    OpenChainTurnContext context = factory.build(request("Describe chain"), CONVERSATION_ID);

    assertEquals(CatalogRead.State.AVAILABLE, context.facts().state());
    assertEquals(CatalogRead.State.AVAILABLE, context.snapshots().state());
    assertEquals(1, context.snapshots().value().size());
    assertEquals(CatalogRead.State.AVAILABLE, context.deployments().state());
    verify(factsService).load(CHAIN_A);
    verify(catalogRestClient).listSnapshots(CHAIN_A);
    verify(catalogRestClient).listDeployments(CHAIN_A);
  }

  @Test
  void askChainHintStillLoadsOperationalStateForDescribe() {
    when(extractor.resolveChainId(any(), anyString())).thenReturn(Optional.of(CHAIN_A));
    when(factsService.load(CHAIN_A)).thenReturn(facts(CHAIN_A));
    when(catalogRestClient.listSnapshots(CHAIN_A)).thenReturn(List.of());
    when(catalogRestClient.listDeployments(CHAIN_A)).thenReturn(List.of());
    ChatRequest request = request("Describe chain");
    request.setScenarioHint(ScenarioType.ASK_CHAIN);

    factory.build(request, CONVERSATION_ID);

    verify(turnPlanner, never()).plan(anyString(), anyString(), anyString());
    verify(factsService).load(CHAIN_A);
    verify(catalogRestClient).listSnapshots(CHAIN_A);
    verify(catalogRestClient).listDeployments(CHAIN_A);
  }

  @Test
  void plannerReceivesTypedLastAssistantTurn() {
    when(extractor.resolveChainId(any(), anyString())).thenReturn(Optional.of(CHAIN_A));
    when(factsService.load(CHAIN_A)).thenReturn(facts(CHAIN_A));
    lastTurnStore.put(
        CONVERSATION_ID,
        CHAIN_A,
        new LastAssistantTurn(
            LastAssistantTurn.Kind.PATCH_WRITE_FAILED, "The catalog did not confirm the write."));

    factory.build(request("why?"), CONVERSATION_ID);

    verify(turnPlanner)
        .plan(
            org.mockito.ArgumentMatchers.contains("PATCH_WRITE_FAILED"),
            anyString(),
            org.mockito.ArgumentMatchers.eq("why?"));
  }

  @Test
  void unknownFactsFailureIsRethrown() {
    when(extractor.resolveChainId(any(), anyString())).thenReturn(Optional.of(CHAIN_A));
    when(factsService.load(CHAIN_A)).thenThrow(new NullPointerException("catalog npe"));

    ChatRequest request = request("ask");
    assertThrows(NullPointerException.class, () -> factory.build(request, CONVERSATION_ID));
    assertTrue(pins.find(CONVERSATION_ID, CHAIN_A).isEmpty());
  }

  @Test
  void returnsNullWhenNoChainIsOpen() {
    when(extractor.resolveChainId(any(), anyString())).thenReturn(Optional.empty());

    assertNull(factory.build(request("create a new chain"), CONVERSATION_ID));
    verify(factsService, never()).load(anyString());
  }

  private static ChatRequest request(String message) {
    ChatRequest request = new ChatRequest();
    request.setConversationId(CONVERSATION_ID);
    request.setMessage(message);
    return request;
  }

  private static ChainCatalogFacts facts(String chainId) {
    return new ChainCatalogFacts(chainId, "Demo", "", 0, 0, "", List.of(), List.of(), "");
  }
}
