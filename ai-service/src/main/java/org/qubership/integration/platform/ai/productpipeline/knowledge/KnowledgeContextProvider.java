package org.qubership.integration.platform.ai.productpipeline.knowledge;

/** Resolves the pinned knowledge identity for a conversation or startup defaults. */
public interface KnowledgeContextProvider {
  KnowledgeQueryContext forConversation(String conversationId);
}
