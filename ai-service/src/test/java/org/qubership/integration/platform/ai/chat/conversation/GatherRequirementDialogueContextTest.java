package org.qubership.integration.platform.ai.chat.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Ticker;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chat.conversation.ConversationArchive;
import org.qubership.integration.platform.ai.chat.conversation.ConversationMessage;
import org.qubership.integration.platform.ai.chat.conversation.ConversationService;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationSessions;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.qubership.integration.platform.ai.llm.scenario.GatherRequirementsPromptBuilder;
import org.qubership.integration.platform.ai.plan.RequirementDraftStore;
import org.qubership.integration.platform.ai.productpipeline.create.RequirementFactFixtures;

class GatherRequirementDialogueContextTest {
  @Test
  void amendmentWithdrawalAndShortReplySurviveRejectedCaptureAndRestart() {
    var blobs = new InMemoryArtifactBlobStore();
    var mapper = new ObjectMapper().findAndRegisterModules();
    var archive = new ConversationArchive(blobs, mapper);
    var conversations = conversations(archive);
    var drafts = drafts(blobs, mapper, conversations);
    conversations.addMessage("chat", ConversationMessage.user("Timeout is 30 seconds. Log request bodies."));
    String originalId = archive.pendingInputs("chat").getFirst().id();
    drafts.beginTurn("chat");
    drafts.put("chat", RequirementFactFixtures.readyDraft("Timeout is 30 seconds. Log request bodies."));
    assertTrue(archive.pendingInputs("chat").isEmpty());

    String amendment = "Change the timeout to 60 seconds. Withdraw body logging.";
    conversations.addMessage("chat", ConversationMessage.user(amendment));
    drafts.beginTurn("chat");
    drafts.recordCaptureRejection("chat", "The attempted capture omitted the timeout.");
    assertEquals(amendment, archive.pendingInputs("chat").getFirst().message().content());

    var restoredArchive = new ConversationArchive(blobs, mapper);
    var restoredConversations = conversations(restoredArchive);
    var restoredDrafts = drafts(blobs, mapper, restoredConversations);
    restoredDrafts.restoreAccepted("chat", drafts.get("chat").orElseThrow());
    var builder = new GatherRequirementsPromptBuilder(null, null, restoredDrafts, restoredConversations);
    String pending = builder.wrap("chat", amendment, "en");
    assertTrue(pending.contains(amendment), pending);
    assertTrue(pending.contains("Use the current draft as accepted state"), pending);
    assertEquals(1, restoredArchive.pendingInputs("chat").size());

    restoredDrafts.beginTurn("chat");
    restoredDrafts.put("chat", RequirementFactFixtures.readyDraft("Timeout is 60 seconds. Do not log request bodies."));
    assertTrue(restoredArchive.pendingInputs("chat").isEmpty());
    assertEquals(1, restoredDrafts.latestRevision("chat").orElseThrow().inputs().size());
    restoredConversations.addMessage("chat", ConversationMessage.assistant("Use the existing operation or a custom one?"));
    restoredConversations.addMessage("chat", ConversationMessage.user("existing"));
    String shortReply = builder.wrap("chat", "existing", "en");
    assertTrue(shortReply.contains("Timeout is 60 seconds"), shortReply);
    assertTrue(shortReply.contains("Do not log request bodies"), shortReply);
    assertTrue(shortReply.contains("Use the existing operation or a custom one?"), shortReply);
    assertTrue(shortReply.contains("existing"), shortReply);
    assertFalse(shortReply.contains("Timeout is 30 seconds"), shortReply);
    assertFalse(shortReply.contains("Current author message:"), shortReply);
    assertTrue(restoredArchive.readSource("chat", originalId, 0, 16000).contains("Timeout is 30 seconds"));
  }

  private static ConversationService conversations(ConversationArchive archive) {
    return new ConversationService(Duration.ofHours(1), Ticker.systemTicker(), archive);
  }

  private static RequirementDraftStore drafts(
      InMemoryArtifactBlobStore blobs, ObjectMapper mapper, ConversationService conversations) {
    return new RequirementDraftStore(new CompilationArtifacts(blobs, mapper, Clock.systemUTC()),
        new CompilationSessions(blobs, mapper, Clock.systemUTC()), conversations);
  }
}
