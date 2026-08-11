package org.qubership.integration.platform.ai.logging;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ToolTraceLogTest {

  @Test
  void previewOneLineTruncatesLongText() {
    String longText = "x".repeat(1000);

    String preview = AiTraceLog.previewOneLine(longText, 100);

    assertTrue(preview.length() < longText.length());
    assertTrue(preview.contains("chars)"));
  }

  @Test
  void previewHandlesNull() {
    assertTrue(AiTraceLog.preview(null, 100).contains("null"));
    assertTrue(AiTraceLog.previewOneLine(null, 100).contains("null"));
  }

  @Test
  void previewKeepsShortText() {
    String text = "hello";

    assertFalse(AiTraceLog.previewOneLine(text, 100).contains("chars)"));
  }
}
