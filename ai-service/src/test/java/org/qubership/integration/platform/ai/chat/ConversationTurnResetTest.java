package org.qubership.integration.platform.ai.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import org.qubership.integration.platform.ai.plan.ChainPlanRepairDraftStore;
import org.qubership.integration.platform.ai.plan.ChainPlanStore;
import org.qubership.integration.platform.ai.plan.RequirementDraftStore;
import org.qubership.integration.platform.ai.skill.workspace.InMemorySkillWorkspaceStore;

class ConversationTurnResetTest {

  private static final String CONVERSATION_ID = "reset-conv";
  private static final String SAFE_TEXT = "Couldn't finish this catalog request.";

  private ConversationTurnReset reset;
  private ConversationService conversationService;
  private PinnedFailureStore pinnedFailureStore;
  private InMemorySkillWorkspaceStore workspaceStore;
  private ChainPlanStore chainPlanStore;

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
            new LastAssistantTurnStore());
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
}
