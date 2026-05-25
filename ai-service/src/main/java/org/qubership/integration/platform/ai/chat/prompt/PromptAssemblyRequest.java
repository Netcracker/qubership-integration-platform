package org.qubership.integration.platform.ai.chat.prompt;

import org.qubership.integration.platform.ai.chat.model.ChatRequest;

/** Input for {@link ConversationPromptAssembler}. */
public record PromptAssemblyRequest(
    String conversationId, ChatRequest request, PromptProfile profile) {

  public static PromptAssemblyRequest of(
      String conversationId, ChatRequest request, PromptProfile profile) {
    return new PromptAssemblyRequest(conversationId, request, profile);
  }
}
