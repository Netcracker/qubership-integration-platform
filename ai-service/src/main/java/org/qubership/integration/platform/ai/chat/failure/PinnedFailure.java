package org.qubership.integration.platform.ai.chat.failure;

/**
 * Last known catalog failure pinned for one open chain in a conversation.
 * {@code safeText} is the transcript copy shown to the reader. {@code diagnosticDetail} is the
 * catalog runtime error: the chain-edit compiler reads it, and logs keep it. It is not copied into
 * the chat token.
 */
public record PinnedFailure(
    String conversationId, String chainId, String safeText, String diagnosticDetail) {}
