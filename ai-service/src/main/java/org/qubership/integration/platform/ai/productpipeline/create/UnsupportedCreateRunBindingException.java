package org.qubership.integration.platform.ai.productpipeline.create;

/**
 * Thrown when a persisted CREATE conversation binding is not a supported product create-chain
 * profile ({@code create-chain@1} or {@code create-chain@2}) after cutover.
 */
public final class UnsupportedCreateRunBindingException extends RuntimeException {

  public static final String ERROR_ID = "CREATE_RUN_UNSUPPORTED_AFTER_CUTOVER";
  public static final String DISPLAY_MESSAGE =
      "This conversation uses an unsupported CREATE runtime or profile. "
          + "Start a new conversation to use the supported product CREATE profile (create-chain@2).";

  public UnsupportedCreateRunBindingException() {
    super(ERROR_ID + ": " + DISPLAY_MESSAGE);
  }

  public UnsupportedCreateRunBindingException(String detail) {
    super(
        ERROR_ID
            + ": "
            + DISPLAY_MESSAGE
            + (detail == null || detail.isBlank() ? "" : " (" + detail + ")"));
  }

  public String errorId() {
    return ERROR_ID;
  }

  public String sseMessage() {
    return ERROR_ID + ": " + DISPLAY_MESSAGE;
  }
}
