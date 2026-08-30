package org.qubership.integration.platform.ai.chat;

/** Typed summary of the previous assistant turn for follow-up resolution. */
public record LastAssistantTurn(Kind kind, String text) {

  private static final int MAX_TEXT_CHARS = 2_000;

  public LastAssistantTurn {
    kind = kind == null ? Kind.OTHER : kind;
    text = text == null ? "" : text.strip();
    if (text.length() > MAX_TEXT_CHARS) {
      text = text.substring(0, MAX_TEXT_CHARS);
    }
  }

  public enum Kind {
    PATCH_WRITE_FAILED,
    PATCH_WRITE_OK,
    DEPLOY_PROCESSING,
    DEPLOY_OK,
    DEPLOY_FAILED,
    DESCRIBE,
    DECISION,
    OTHER
  }
}
