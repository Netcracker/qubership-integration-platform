package org.qubership.integration.platform.ai.chat.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ConversationServiceTruncateTest {

  @Test
  void truncateKeepsInclusivePrefix() {
    ConversationService svc = new ConversationService();
    svc.addMessage("c1", ConversationMessage.user("u0"));
    svc.addMessage("c1", ConversationMessage.assistant("a1"));
    svc.addMessage("c1", ConversationMessage.user("u2"));

    svc.truncateAfter("c1", 1);

    assertEquals(2, svc.getMessages("c1").size());
    assertEquals("u0", svc.getMessages("c1").get(0).content());
    assertEquals("a1", svc.getMessages("c1").get(1).content());
  }

  @Test
  void truncateAfterNegativeClearsAll() {
    ConversationService svc = new ConversationService();
    svc.addMessage("c1", ConversationMessage.user("u0"));

    svc.truncateAfter("c1", -1);

    assertTrue(svc.getMessages("c1").isEmpty());
  }

  @Test
  void clearMessagesEmptiesListButKeepsConversation() {
    ConversationService svc = new ConversationService();
    svc.addMessage("c1", ConversationMessage.user("u0"));
    svc.getOrCreate("c1");

    svc.clearMessages("c1");

    assertTrue(svc.getMessages("c1").isEmpty());
    svc.addMessage("c1", ConversationMessage.user("u1"));
    assertEquals(1, svc.getMessages("c1").size());
  }

  @Test
  void editContractA_noDuplicateUserAfterTruncateThenAddMessage() {
    ConversationService svc = new ConversationService();
    svc.addMessage("c1", ConversationMessage.user("u0"));
    svc.addMessage("c1", ConversationMessage.assistant("a1"));
    svc.addMessage("c1", ConversationMessage.user("u2"));
    svc.addMessage("c1", ConversationMessage.assistant("a3"));

    int serverUserIndex = 2;
    svc.truncateAfter("c1", serverUserIndex - 1);
    svc.addMessage("c1", ConversationMessage.user("edited"));

    var messages = svc.getMessages("c1");
    assertEquals(3, messages.size());
    assertEquals("u0", messages.get(0).content());
    assertEquals("a1", messages.get(1).content());
    assertEquals("edited", messages.get(2).content());
    assertEquals(
        1,
        messages.stream()
            .filter(m -> m.role() == ConversationMessage.Role.USER && "edited".equals(m.content()))
            .count());
  }
}
