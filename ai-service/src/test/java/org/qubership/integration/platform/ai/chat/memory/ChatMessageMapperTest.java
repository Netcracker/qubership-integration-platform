package org.qubership.integration.platform.ai.chat.memory;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chat.conversation.ConversationMessage;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ChatMessageMapperTest {

  @Test
  void roundTripUserAndAssistant() {
    List<ChatMessage> original = List.of(UserMessage.from("hello"), AiMessage.from("world"));
    var drafts = ChatMessageMapper.toStoredDrafts(original);
    assertEquals(ConversationMessage.Role.USER, drafts.get(0).role());
    assertEquals("hello", drafts.get(0).content());
    assertEquals(ConversationMessage.Role.ASSISTANT, drafts.get(1).role());
    assertEquals("world", drafts.get(1).content());

    ChatMessage user =
        ChatMessageMapper.toChatMessage(drafts.get(0).role(), drafts.get(0).content());
    assertEquals("hello", ((UserMessage) user).singleText());
  }

  @Test
  void toolResultMapsToSystemWithPrefix() {
    ToolExecutionResultMessage tool =
        ToolExecutionResultMessage.from("id-1", "searchCatalogSystems", "{\"systems\":[]}");
    var draft = ChatMessageMapper.toStoredDrafts(List.of(tool)).getFirst();
    assertEquals(ConversationMessage.Role.SYSTEM, draft.role());
    assertTrue(draft.content().startsWith("[Tool] "));
    assertTrue(draft.content().contains("searchCatalogSystems"));

    ChatMessage restored = ChatMessageMapper.toChatMessage(draft.role(), draft.content());
    assertInstanceOf(SystemMessage.class, restored);
  }
}
