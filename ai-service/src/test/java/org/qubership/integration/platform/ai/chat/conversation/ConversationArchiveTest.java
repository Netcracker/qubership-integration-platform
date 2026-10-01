package org.qubership.integration.platform.ai.chat.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.compiler.artifact.ArtifactBlobStore;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;

class ConversationArchiveTest {
  private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

  @Test
  void originalEffectiveDecisionAndConsumedSourcesSurviveRestartAndReset() {
    var blobs = new InMemoryArtifactBlobStore();
    var archive = new ConversationArchive(blobs, mapper);
    var input = archive.beginInput("chat", "  keep original  ", mapper.createObjectNode().put("decision", "continue"));
    archive.resolveInput("chat", input, "expanded attachment text");
    assertEquals(1, archive.pendingInputs("chat").size());
    archive.consume("chat", input.generation(), List.of(input.turnId()), "draft-v1");
    var restored = new ConversationArchive(blobs, mapper);
    var turn = restored.read("chat").activeTurns().getFirst();
    assertEquals("  keep original  ", turn.rawText());
    assertEquals("expanded attachment text", turn.message().content());
    assertEquals("continue", turn.metadata().get("decision").textValue());
    assertEquals("draft-v1", turn.consumedDraftReference());
    assertTrue(restored.pendingInputs("chat").isEmpty());
    var service = new ConversationService(java.time.Duration.ofHours(1),
        com.github.benmanes.caffeine.cache.Ticker.systemTicker(), restored);
    service.clearMessages("chat");
    assertFalse(restored.appendAssistant("chat", input.generation(), "late reply", null, "COMPLETE"));
    assertTrue(service.getMessages("chat").isEmpty());
    assertTrue(restored.readSource("chat", input.turnId(), 0, 16000).contains("expanded attachment text"));
    assertTrue(restored.readSource("chat", input.turnId(), 0, 16000).contains("keep original"));
    assertTrue(restored.readSource("chat", input.turnId(), 0, 16000).contains("continue"));
    assertThrows(IllegalArgumentException.class, () -> restored.readSource("other-chat", input.turnId(), 0, 100));
  }

  @Test
  void lateAssistantCannotReplaceAnEarlierQuestionAfterANewInputOrReset() {
    var archive = new ConversationArchive(new InMemoryArtifactBlobStore(), mapper);
    var input = archive.beginInput("chat", "first", null);
    archive.resolveInput("chat", input, "first");
    assertTrue(archive.saveAssistant("chat", input.generation(), "question", "Keep this question?",
        null, "QUESTION", input.turnId()));
    var next = archive.beginInput("chat", "second", null);
    archive.resolveInput("chat", next, "second");
    assertFalse(archive.saveAssistant("chat", input.generation(), "question", "obsolete answer",
        null, "COMPLETE", input.turnId()));
    var service = new ConversationService(java.time.Duration.ofHours(1),
        com.github.benmanes.caffeine.cache.Ticker.systemTicker(), archive);
    service.truncateAfter("chat", 1);
    assertFalse(archive.saveAssistant("chat", input.generation(), "question", "late after reset",
        null, "COMPLETE"));
    assertEquals("Keep this question?", service.getMessages("chat").getLast().content());
  }

  @Test
  void concurrentConditionalWritesKeepBothTurnsAndFailureIsVisible() throws Exception {
    var blobs = new InMemoryArtifactBlobStore();
    var first = new ConversationArchive(blobs, mapper);
    var second = new ConversationArchive(blobs, mapper);
    try (var workers = Executors.newFixedThreadPool(2)) {
      var a = workers.submit(() -> first.beginInput("chat", "first", null));
      var b = workers.submit(() -> second.beginInput("chat", "second", null));
      a.get();
      b.get();
    }
    assertEquals(2, first.read("chat").activeTurns().size());
    ArtifactBlobStore broken = mock(ArtifactBlobStore.class);
    doThrow(new IllegalStateException("storage unavailable")).when(broken).putIfVersion(any(), any(), any());
    assertThrows(IllegalStateException.class,
        () -> new ConversationArchive(broken, mapper).beginInput("chat", "persist first", null));
  }
}
