package org.qubership.integration.platform.ai.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chain.deploy.MaasKafkaTopicsFollowUp;
import org.qubership.integration.platform.ai.chain.deploy.PendingRedeploy;
import org.qubership.integration.platform.ai.chain.deploy.PendingRedeployStore;
import org.qubership.integration.platform.ai.chain.folder.CatalogFolders;
import org.qubership.integration.platform.ai.chain.folder.ChainListAction;
import org.qubership.integration.platform.ai.chain.folder.ChainListCommand;
import org.qubership.integration.platform.ai.chain.folder.ChainTable;
import org.qubership.integration.platform.ai.chain.presentation.ChainContextExtractor;
import org.qubership.integration.platform.ai.chat.conversation.ConversationMessage;
import org.qubership.integration.platform.ai.chat.conversation.ConversationService;
import org.qubership.integration.platform.ai.chat.evidence.ConversationEvidenceStore;
import org.qubership.integration.platform.ai.chat.failure.PinnedFailure;
import org.qubership.integration.platform.ai.chat.failure.PinnedFailureStore;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationSessions;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.qubership.integration.platform.ai.compiler.capture.CaptureAttemptFeedbackStore;
import org.qubership.integration.platform.ai.compiler.capture.CaptureSession;
import org.qubership.integration.platform.ai.integration.catalog.cache.ConversationCatalogCache;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.chat.model.ChatDecisionCommand;
import org.qubership.integration.platform.ai.chat.model.ChatRequest;
import org.qubership.integration.platform.ai.llm.agent.ChainListIntentAgent;
import org.qubership.integration.platform.ai.llm.agent.ChainListReplyAgent;
import org.qubership.integration.platform.ai.llm.scenario.ChainListScenario;
import org.qubership.integration.platform.ai.model.ScenarioType;
import org.qubership.integration.platform.ai.plan.ChainPlanRepairDraftStore;
import org.qubership.integration.platform.ai.plan.ChainPlanStore;
import org.qubership.integration.platform.ai.plan.RequirementDraftStore;
import org.qubership.integration.platform.ai.skill.workspace.InMemorySkillWorkspaceStore;
import org.qubership.integration.platform.ai.integration.apihub.ApiHubRequirementRefs;
import org.qubership.integration.platform.ai.integration.apihub.ConversationApiHubCache;

class ConversationTurnResetTest {

  private static final String CONVERSATION_ID = "reset-conv";
  private static final String SAFE_TEXT = "Couldn't finish this catalog request.";

  private ConversationTurnReset reset;
  private ConversationService conversationService;
  private PinnedFailureStore pinnedFailureStore;
  private InMemorySkillWorkspaceStore workspaceStore;
  private ChainPlanStore chainPlanStore;
  private ConversationApiHubCache apiHubCache;
  private PendingRedeployStore pendingRedeployStore;
  private ChainListScenario chainListScenario;
  private ChainListIntentAgent chainListAgent;
  private CatalogRestClient catalog;

  @BeforeEach
  void setUp() {
    InMemoryArtifactBlobStore blobs = new InMemoryArtifactBlobStore();
    ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    CompilationArtifacts artifacts = new CompilationArtifacts(blobs, mapper, Clock.systemUTC());
    CompilationSessions sessions = new CompilationSessions(blobs, mapper, Clock.systemUTC());
    chainPlanStore = new ChainPlanStore(artifacts, sessions);
    workspaceStore = new InMemorySkillWorkspaceStore(chainPlanStore);
    conversationService = new ConversationService();
    pinnedFailureStore = new PinnedFailureStore();
    apiHubCache = new ConversationApiHubCache();
    pendingRedeployStore = new PendingRedeployStore();
    chainListAgent = mock(ChainListIntentAgent.class);
    catalog = mock(CatalogRestClient.class);
    ChainTable table = mock(ChainTable.class);
    when(table.find(List.of()))
        .thenReturn(List.of(new ChainTable.Row("chain-a", "Orders", "", null, "", null, "")));
    ChainContextExtractor chainContext = mock(ChainContextExtractor.class);
    when(chainContext.resolveChainId(any(), anyString())).thenReturn(java.util.Optional.empty());
    chainListScenario = new ChainListScenario(
        chainListAgent, mock(ChainListReplyAgent.class), mock(CatalogFolders.class), table,
        catalog, conversationService, chainContext);
    reset =
        new ConversationTurnReset(
            conversationService,
            mock(ChatMemoryStore.class),
            workspaceStore,
            new CaptureSession(),
            chainPlanStore,
            new ChainPlanRepairDraftStore(),
            new CaptureAttemptFeedbackStore(),
            sessions,
            new RequirementDraftStore(artifacts, sessions),
            mock(ConversationCatalogCache.class),
            new ConversationEvidenceStore(),
            pinnedFailureStore,
            new LastAssistantTurnStore(),
            apiHubCache,
            pendingRedeployStore,
            chainListScenario);
  }

  @Test
  void fullResetInvalidatesPendingChainListChanges() {
    assertOldChainListDecisionIsRejected(() -> reset.fullReset(CONVERSATION_ID));
  }

  @Test
  void truncateInvalidatesPendingChainListChanges() {
    assertOldChainListDecisionIsRejected(() -> reset.truncateAndReset(CONVERSATION_ID, -1));
  }

  @Test
  void fullResetDropsTheLastChainList() {
    when(chainListAgent.resolve(any(), any(), any(), any())).thenReturn(
        new ChainListCommand(ChainListAction.FIND_CHAINS, null, null, null,
            List.of(), List.of(), List.of(), null));
    chainListEvent(new ChatRequest(), CONVERSATION_ID);
    reset.fullReset(CONVERSATION_ID);
    when(chainListAgent.resolve(any(), any(), any(), any())).thenReturn(
        new ChainListCommand(ChainListAction.DELETE_CHAINS, null, null, null,
            null, null, null, null, null, null, null, true, List.of(1), null, null));

    assertInstanceOf(ChatEvent.Token.class, chainListEvent(new ChatRequest(), CONVERSATION_ID));
    verify(catalog, never()).deleteChain(anyString());
  }

  private void assertOldChainListDecisionIsRejected(Runnable resetAction) {
    when(chainListAgent.resolve(any(), any(), any(), any())).thenReturn(
        new ChainListCommand(ChainListAction.DELETE_CHAINS, null, null, null,
            List.of(), List.of("Orders"), List.of(), null));
    ChatEvent.Decision oldCard = assertInstanceOf(ChatEvent.Decision.class,
        chainListEvent(new ChatRequest(), CONVERSATION_ID));
    ChatEvent.Decision otherCard = assertInstanceOf(ChatEvent.Decision.class,
        chainListEvent(new ChatRequest(), "other-conv"));
    resetAction.run();

    assertTrue(chainListScenario.openDecision(CONVERSATION_ID).isEmpty());
    assertEquals(otherCard, chainListScenario.openDecision("other-conv").orElseThrow());

    chainListEvent(chainListAnswer(oldCard), CONVERSATION_ID);
    verify(catalog, never()).deleteChain(anyString());
    chainListEvent(chainListAnswer(otherCard), "other-conv");
    verify(catalog).deleteChain("chain-a");
  }

  private ChatEvent chainListEvent(ChatRequest request, String conversationId) {
    return chainListScenario.handle(request, conversationId, ScenarioType.MANAGE_CHAIN_LIST)
        .collect().asList().await().indefinitely().getFirst();
  }

  private static ChatRequest chainListAnswer(ChatEvent.Decision card) {
    ChatDecisionCommand command = new ChatDecisionCommand();
    command.setAction(ChatEvent.APPLY_CHAIN_LIST_CHANGE_ACTION);
    command.setArtifactHash(card.artifactHash());
    ChatRequest request = new ChatRequest();
    request.setDecision(command);
    return request;
  }

  @Test
  void fullResetClearsWorkspace() {
    reset.fullReset(CONVERSATION_ID);
    assertTrue(workspaceStore.getOrCreate(CONVERSATION_ID).presentTypes().isEmpty());
  }

  @Test
  void conversationResetDoesNotTouchLegacyPipelineState() throws Exception {
    Path source =
        Path.of(
            "src/main/java/org/qubership/integration/platform/ai/chat/ConversationTurnReset.java");
    String text = Files.readString(source);
    assertFalse(text.contains("PipelineStateRepository"));
    assertFalse(text.contains("InMemoryPipelineStateRepository"));
    reset.fullReset(CONVERSATION_ID);
    assertTrue(workspaceStore.getOrCreate(CONVERSATION_ID).presentTypes().isEmpty());
  }

  @Test
  void fullResetClearsEveryPinForTheConversation() {
    pinnedFailureStore.put(
        new PinnedFailure(CONVERSATION_ID, "chain-a", SAFE_TEXT, "TimeoutException"));
    pinnedFailureStore.put(
        new PinnedFailure(CONVERSATION_ID, "chain-b", "other", "TimeoutException"));
    pinnedFailureStore.put(
        new PinnedFailure("other-conv", "chain-a", SAFE_TEXT, "TimeoutException"));

    reset.fullReset(CONVERSATION_ID);

    assertTrue(pinnedFailureStore.find(CONVERSATION_ID, "chain-a").isEmpty());
    assertTrue(pinnedFailureStore.find(CONVERSATION_ID, "chain-b").isEmpty());
    assertEquals(
        SAFE_TEXT, pinnedFailureStore.find("other-conv", "chain-a").orElseThrow().safeText());
  }

  @Test
  void truncateAfterNegativeIndexClearsEveryPinForTheConversation() {
    pinnedFailureStore.put(
        new PinnedFailure(CONVERSATION_ID, "chain-a", SAFE_TEXT, "TimeoutException"));
    pinnedFailureStore.put(
        new PinnedFailure("other-conv", "chain-a", SAFE_TEXT, "TimeoutException"));

    reset.truncateAndReset(CONVERSATION_ID, -1);

    assertTrue(pinnedFailureStore.find(CONVERSATION_ID, "chain-a").isEmpty());
    assertEquals(
        SAFE_TEXT, pinnedFailureStore.find("other-conv", "chain-a").orElseThrow().safeText());
  }

  @Test
  void truncateDropsPinWhenSafeTextIsGoneFromRemainingMessages() {
    conversationService.addMessage(CONVERSATION_ID, ConversationMessage.user("u0"));
    conversationService.addMessage(CONVERSATION_ID, ConversationMessage.assistant(SAFE_TEXT));
    conversationService.addMessage(CONVERSATION_ID, ConversationMessage.user("retry"));
    pinnedFailureStore.put(
        new PinnedFailure(CONVERSATION_ID, "chain-a", SAFE_TEXT, "TimeoutException"));

    reset.truncateAndReset(CONVERSATION_ID, 0);

    assertTrue(pinnedFailureStore.find(CONVERSATION_ID, "chain-a").isEmpty());
  }

  @Test
  void truncateKeepsPinWhenSafeTextRemainsInMessages() {
    conversationService.addMessage(CONVERSATION_ID, ConversationMessage.user("u0"));
    conversationService.addMessage(CONVERSATION_ID, ConversationMessage.assistant(SAFE_TEXT));
    conversationService.addMessage(CONVERSATION_ID, ConversationMessage.user("retry"));
    pinnedFailureStore.put(
        new PinnedFailure(CONVERSATION_ID, "chain-a", SAFE_TEXT, "TimeoutException"));

    reset.truncateAndReset(CONVERSATION_ID, 1);

    assertEquals(
        SAFE_TEXT, pinnedFailureStore.find(CONVERSATION_ID, "chain-a").orElseThrow().safeText());
  }

  @Test
  void fullResetClearsApiHubCache() {
    apiHubCache.rememberCandidate(
        CONVERSATION_ID,
        new ApiHubRequirementRefs("pkg", "v1", "op-1", null, "rest", "Pkg", "Spec"));
    assertTrue(apiHubCache.latestCandidate(CONVERSATION_ID).isPresent());

    reset.fullReset(CONVERSATION_ID);

    assertTrue(apiHubCache.latestCandidate(CONVERSATION_ID).isEmpty());
  }

  @Test
  void fullResetClearsPendingRedeploy() {
    pendingRedeployStore.put(
        CONVERSATION_ID,
        PendingRedeploy.maasTopicsWait(
            "chain-a",
            "default",
            "snap-1",
            "op-1",
            List.of(new MaasKafkaTopicsFollowUp.TopicPair("qip-dev", "orders-in"))));
    pendingRedeployStore.put(
        "other-conv",
        PendingRedeploy.maasTopicsWait(
            "chain-b",
            "default",
            "snap-2",
            "op-2",
            List.of(new MaasKafkaTopicsFollowUp.TopicPair("qip-dev", "orders-out"))));

    reset.fullReset(CONVERSATION_ID);

    assertTrue(pendingRedeployStore.find(CONVERSATION_ID).isEmpty());
    assertTrue(pendingRedeployStore.find("other-conv").isPresent());
  }

  @Test
  void artifactInventoryResetClearsPendingRedeploy() {
    pendingRedeployStore.put(
        CONVERSATION_ID,
        PendingRedeploy.maasTopicsWait(
            "chain-a",
            "default",
            "snap-1",
            "op-1",
            List.of(new MaasKafkaTopicsFollowUp.TopicPair("qip-dev", "orders-in"))));

    reset.truncateAndReset(CONVERSATION_ID, 0);

    assertTrue(pendingRedeployStore.find(CONVERSATION_ID).isEmpty());
  }
}
