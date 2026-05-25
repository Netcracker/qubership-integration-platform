package org.qubership.integration.platform.ai.chat.guardrail;

/** Layer 1: deterministic on-topic for attachment-only replies continuing a QIP thread. */
public final class ThreadContinuationPolicy {

  private ThreadContinuationPolicy() {}

  /**
   * Attachment reply completing an in-progress QIP conversation (slot filling after assistant
   * prompt).
   */
  public static boolean allowAttachmentReply(GuardrailTurnContext turn) {
    if (!turn.hasAttachments()) {
      return false;
    }
    String msg = turn.userMessage() != null ? turn.userMessage().trim() : "";
    if (!msg.isBlank() && !GuardrailClassificationText.isAttachmentBoilerplate(msg)) {
      return false;
    }

    if (turn.activePlan().isPresent()) {
      return true;
    }
    String transcript = turn.recentTranscript();
    if (GuardrailTranscriptHeuristics.isTranscriptQipish(transcript)) {
      return true;
    }
    return GuardrailTranscriptHeuristics.isLastAssistantOpenPrompt(transcript);
  }
}
