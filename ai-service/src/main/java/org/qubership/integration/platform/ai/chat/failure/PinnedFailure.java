package org.qubership.integration.platform.ai.chat.failure;

/**
 * Last known catalog failure pinned for one open chain in a conversation.
 * {@code safeText} is transcript copy; {@code diagnosticDetail} is log-only.
 */
public record PinnedFailure(
    String conversationId, String chainId, String safeText, String diagnosticDetail) {}
