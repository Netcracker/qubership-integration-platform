package org.qubership.integration.platform.ai.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
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

  @Test
  void previewJsonSerializesValues() {
    assertEquals(
        "{\"value\":\"hello\"}",
        AiTraceLog.previewJson(new ObjectMapper(), Map.of("value", "hello"), 100));
  }

  @Test
  void previewJsonReturnsLiteralNull() {
    assertEquals("null", AiTraceLog.previewJson(new ObjectMapper(), null, 100));
  }

  @Test
  void previewJsonFallsBackToToString() throws JsonProcessingException {
    Object value = new Object();
    ObjectMapper objectMapper = mock(ObjectMapper.class);
    when(objectMapper.writeValueAsString(value))
        .thenThrow(
            new JsonProcessingException("serialization failed") {
              private static final long serialVersionUID = 1L;
            });

    assertEquals(value.toString(), AiTraceLog.previewJson(objectMapper, value, 100));
  }
}
