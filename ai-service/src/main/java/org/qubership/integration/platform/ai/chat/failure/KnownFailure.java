package org.qubership.integration.platform.ai.chat.failure;

/**
 * Allowlisted catalog failure for chat. {@code safeText} may go to the token and transcript;
 * {@code diagnosticDetail} is log-only.
 */
public record KnownFailure(String safeText, String diagnosticDetail) {}
