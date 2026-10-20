package org.qubership.integration.platform.ai.chat.model;

/** Request body for {@code POST /api/v1/chat/conversations/{conversationId}/truncate}. */
public record TruncateRequest(int afterMessageIndex) {}
