package org.qubership.integration.platform.ai.chat.conversation;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** Lightweight DTO for a single message in a conversation (in-memory use and API responses). */
@Getter
@Setter
@NoArgsConstructor
public class ConversationMessage {

  public enum Role {
    USER,
    ASSISTANT,
    SYSTEM
  }

  private Long id;
  private String conversationId;
  private Role role;
  private String content;
  private Instant createdAt;

  public ConversationMessage(String conversationId, Role role, String content) {
    this.conversationId = conversationId;
    this.role = role;
    this.content = content;
    this.createdAt = Instant.now();
  }
}
