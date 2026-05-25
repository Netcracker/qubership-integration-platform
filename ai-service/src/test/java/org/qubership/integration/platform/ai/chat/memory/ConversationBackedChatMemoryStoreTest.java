package org.qubership.integration.platform.ai.chat.memory;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chat.conversation.ConversationMessage;
import org.qubership.integration.platform.ai.chat.conversation.ConversationService;
import org.qubership.integration.platform.ai.model.ScenarioType;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class ConversationBackedChatMemoryStoreTest {

  @Inject ConversationBackedChatMemoryStore memoryStore;

  @Inject ConversationService conversationService;

  @Test
  void updateMessagesVisibleInTranscript() {
    String id = "mem-store-test-" + System.nanoTime();
    conversationService.getOrCreate(id, ScenarioType.UNKNOWN);

    memoryStore.updateMessages(id, List.of(UserMessage.from("first"), AiMessage.from("second")));

    List<ConversationMessage> messages = conversationService.getMessages(id);
    assertEquals(2, messages.size());
    assertEquals(ConversationMessage.Role.USER, messages.get(0).getRole());
    assertEquals("first", messages.get(0).getContent());
    assertEquals(ConversationMessage.Role.ASSISTANT, messages.get(1).getRole());

    String transcript = conversationService.formatRecentTranscript(id, 10, 500, "(empty)");
    assertTrue(transcript.contains("User: first"));
    assertTrue(transcript.contains("Assistant: second"));
  }
}
