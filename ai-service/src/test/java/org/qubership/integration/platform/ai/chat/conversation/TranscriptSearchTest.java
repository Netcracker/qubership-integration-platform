package org.qubership.integration.platform.ai.chat.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class TranscriptSearchTest {

  @Test
  void findReturnsMatchingContent() {
    List<ConversationMessage> messages =
        List.of(
            ConversationMessage.user("hello world"),
            ConversationMessage.assistant("unrelated"),
            ConversationMessage.user("hello again"));

    List<String> hits = TranscriptSearch.find(messages, "hello", 5);

    assertEquals(List.of("hello world", "hello again"), hits);
  }

  @Test
  void findIsCaseInsensitive() {
    List<ConversationMessage> messages = List.of(ConversationMessage.user("Retry Policy"));

    List<String> hits = TranscriptSearch.find(messages, "retry", 5);

    assertEquals(List.of("Retry Policy"), hits);
  }

  @Test
  void findCapsAtMaxHits() {
    List<ConversationMessage> messages =
        List.of(
            ConversationMessage.user("hit 1"),
            ConversationMessage.user("hit 2"),
            ConversationMessage.user("hit 3"),
            ConversationMessage.user("hit 4"),
            ConversationMessage.user("hit 5"),
            ConversationMessage.user("hit 6"));

    List<String> hits = TranscriptSearch.find(messages, "hit", 5);

    assertEquals(List.of("hit 1", "hit 2", "hit 3", "hit 4", "hit 5"), hits);
  }

  @Test
  void findClipsEachHitTo300Characters() {
    String longContent = "x".repeat(400);
    List<ConversationMessage> messages = List.of(ConversationMessage.user(longContent));

    List<String> hits = TranscriptSearch.find(messages, "xxx", 5);

    assertEquals(1, hits.size());
    assertEquals(300, hits.get(0).length());
    assertEquals("x".repeat(300), hits.get(0));
  }

  @Test
  void findReturnsEmptyForNullOrBlankQuery() {
    List<ConversationMessage> messages = List.of(ConversationMessage.user("anything"));

    assertTrue(TranscriptSearch.find(messages, null, 5).isEmpty());
    assertTrue(TranscriptSearch.find(messages, "", 5).isEmpty());
    assertTrue(TranscriptSearch.find(messages, "   ", 5).isEmpty());
  }

  @Test
  void findSkipsMessageEqualToQuery() {
    List<ConversationMessage> messages =
        List.of(
            ConversationMessage.user("retry failed"),
            ConversationMessage.user("what happened?"));

    List<String> hits = TranscriptSearch.find(messages, "what happened?", 5);

    assertTrue(hits.isEmpty());
  }

  @Test
  void findStillMatchesOlderMessageContainingQuery() {
    List<ConversationMessage> messages =
        List.of(
            ConversationMessage.user("earlier: what happened? please check"),
            ConversationMessage.user("what happened?"));

    List<String> hits = TranscriptSearch.find(messages, "what happened?", 5);

    assertEquals(List.of("earlier: what happened? please check"), hits);
  }

  @Test
  void findSkipsBlankContent() {
    List<ConversationMessage> messages =
        List.of(
            ConversationMessage.user("   "),
            ConversationMessage.user(""),
            ConversationMessage.assistant("keep this match"));

    List<String> hits = TranscriptSearch.find(messages, "match", 5);

    assertEquals(List.of("keep this match"), hits);
  }
}
