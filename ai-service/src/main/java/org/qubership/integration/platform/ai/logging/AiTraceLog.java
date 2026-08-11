package org.qubership.integration.platform.ai.logging;

public final class AiTraceLog {

  public static final int DEFAULT_USER_PREVIEW_CHARS = 600;
  public static final int DEFAULT_TOOL_RESULT_CHARS = 800;
  public static final int DEFAULT_HTTP_BODY_DEBUG_CHARS = 2048;
  public static final int DEFAULT_CATALOG_RESPONSE_INFO_CHARS = 4096;

  private AiTraceLog() {}

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
