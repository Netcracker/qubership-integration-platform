package org.qubership.integration.platform.ai.chat.conversation;

import java.time.Instant;

public record ConversationMessage(Role role, String content, Instant createdAt) {

  public enum Role {
    USER, ASSISTANT, SYSTEM
  }

  public static ConversationMessage user(String content) {
    return new ConversationMessage(Role.USER, content, Instant.now());
  }

  public static ConversationMessage assistant(String content) {
    return new ConversationMessage(Role.ASSISTANT, content, Instant.now());
  }
}
