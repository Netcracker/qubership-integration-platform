package org.qubership.integration.platform.ai.logging;

/** Helpers for consistent preview/truncation in AI assistant trace logs (INFO level). */
public final class AiTraceLog {

  /** Default max chars for user message previews in INFO logs. */
  public static final int DEFAULT_USER_PREVIEW_CHARS = 600;

  /** Max chars for tool return values logged at INFO. */
  public static final int DEFAULT_TOOL_RESULT_CHARS = 800;

  /** Max chars for outbound HTTP body snippets at DEBUG. */
  public static final int DEFAULT_HTTP_BODY_DEBUG_CHARS = 2048;

  /** Max chars for catalog REST response previews at INFO (one line). */
  public static final int DEFAULT_CATALOG_RESPONSE_INFO_CHARS = 4096;

  private AiTraceLog() {}

  /** Returns a single-line preview suitable for INFO logs (newlines replaced with spaces). */
  public static String previewOneLine(String text, int maxChars) {
    if (text == null) {
      return "(null)";
    }
    String normalized = text.replace('\n', ' ').replace('\r', ' ').trim();
    if (normalized.length() <= maxChars) {
      return normalized;
    }
    return normalized.substring(0, maxChars) + "… (" + normalized.length() + " chars)";
  }

  public static String preview(String text, int maxChars) {
    if (text == null) {
      return "(null)";
    }
    if (text.length() <= maxChars) {
      return text;
    }
    return text.substring(0, maxChars) + "… (" + text.length() + " chars)";
  }
}
