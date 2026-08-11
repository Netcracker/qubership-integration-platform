package org.qubership.integration.platform.ai.chat;

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
import org.qubership.integration.platform.ai.chat.conversation.ConversationService;
import org.qubership.integration.platform.ai.chat.evidence.ConversationEvidenceStore;
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

  private ConversationTurnReset reset;
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
    reset =
        new ConversationTurnReset(
            mock(ConversationService.class),
            mock(ChatMemoryStore.class),
            workspaceStore,
            new CaptureSession(),
            chainPlanStore,
            new ChainPlanRepairDraftStore(),
            new CaptureAttemptFeedbackStore(),
            sessions,
            new RequirementDraftStore(artifacts, sessions),
            mock(ConversationCatalogCache.class),
            new ConversationEvidenceStore());
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
}
