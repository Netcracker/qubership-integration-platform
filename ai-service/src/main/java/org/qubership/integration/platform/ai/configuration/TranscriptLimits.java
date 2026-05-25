package org.qubership.integration.platform.ai.configuration;

/**
 * Budget for {@link
 * org.qubership.integration.platform.ai.chat.conversation.ConversationService#formatRecentTranscriptBalanced}.
 */
public record TranscriptLimits(int maxMessages, int maxCharsPerMessage, int maxTotalChars) {

  public static TranscriptLimits from(AppConfig.GuardrailConfig config) {
    return new TranscriptLimits(
        config.transcriptMaxMessages(),
        config.transcriptMaxCharsPerMessage(),
        config.transcriptMaxTotalChars());
  }

  public static TranscriptLimits from(AppConfig.RouterConfig config) {
    return new TranscriptLimits(
        config.transcriptMaxMessages(),
        config.transcriptMaxCharsPerMessage(),
        config.transcriptMaxTotalChars());
  }
}
