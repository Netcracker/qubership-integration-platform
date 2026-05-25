package org.qubership.integration.platform.ai.chat.memory;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.qubership.integration.platform.ai.chat.conversation.ConversationService;

import java.util.List;

/**
 * LangChain4j chat memory backed by {@link ConversationService} (same source as REST history and
 * transcripts).
 */
@ApplicationScoped
public class ConversationBackedChatMemoryStore implements ChatMemoryStore {

  @Inject ConversationService conversationService;

  @Override
  public List<ChatMessage> getMessages(Object memoryId) {
    return conversationService.getHistory(memoryId.toString());
  }

  @Override
  public void updateMessages(Object memoryId, List<ChatMessage> messages) {
    conversationService.replaceFromChatMessages(memoryId.toString(), messages);
  }

  @Override
  public void deleteMessages(Object memoryId) {
    conversationService.clearMessages(memoryId.toString());
  }
}
