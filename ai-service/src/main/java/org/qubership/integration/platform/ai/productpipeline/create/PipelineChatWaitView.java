package org.qubership.integration.platform.ai.productpipeline.create;

import java.util.regex.Pattern;

/**
 * Chat-facing view of product-pipeline wait prompts ({@code WaitingForInput} /
 * {@code WaitingForApproval}).
 *
 * <p>Stage outcomes may carry machine status strings (enum-like tokens, capture-tool jargon) that
 * must not appear in chat — same policy as hiding plan hash metadata. Non-blank user-facing prompts
 * get a leading blank line so they do not glue to prior streamed assistant tokens.
 */
public final class PipelineChatWaitView {

  /**
   * Pipeline / draft state tokens that belong in logs and transitions, not in the user transcript.
   */
  private static final Pattern INTERNAL_STATUS_TOKEN =
      Pattern.compile(
          "\\b(?:READY_FOR_[A-Z0-9_]+|WAITING_FOR_[A-Z0-9_]+|CHAIN_MATERIALIZED|PLAN_APPROVED|"
              + "NEEDS_INPUT|CONTRACT_FAILURE|RETRYABLE_TECHNICAL_FAILURE|"
              + "MISSING_MANDATORY_INPUT)\\b");

  /** Agent-facing capture instructions accidentally reused as wait prompts. */
  private static final Pattern AGENT_CAPTURE_JARGON =
      Pattern.compile("(?i)\\bcapture[A-Z][A-Za-z0-9]+\\b|\\bthe agent must\\b");

  private PipelineChatWaitView() {}

  /**
   * Returns a wait prompt safe for chat: suppresses internal status / agent jargon; otherwise
   * prefixes {@code \\n\\n} when the text does not already start with a newline.
   */
  public static String forChatWait(String prompt) {
    if (prompt == null || prompt.isBlank()) {
      return "";
    }
    String trimmed = prompt.strip();
    if (INTERNAL_STATUS_TOKEN.matcher(trimmed).find()
        || AGENT_CAPTURE_JARGON.matcher(trimmed).find()) {
      return "";
    }
    if (trimmed.startsWith("\n")) {
      return trimmed;
    }
    return "\n\n" + trimmed;
  }
}
