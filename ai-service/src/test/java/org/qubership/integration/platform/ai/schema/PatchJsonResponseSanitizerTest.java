package org.qubership.integration.platform.ai.schema;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PatchJsonResponseSanitizerTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void extractJsonObjectPlainObject() {
    String out = PatchJsonResponseSanitizer.extractJsonObject("{\"a\":1}", objectMapper);
    assertEquals("{\"a\":1}", out);
  }

  @Test
  void extractJsonObjectStripsMarkdownFence() {
    String raw = "```json\n{\"x\":\"y\"}\n```";
    String out = PatchJsonResponseSanitizer.extractJsonObject(raw, objectMapper);
    assertEquals("{\"x\":\"y\"}", out);
  }

  @Test
  void extractJsonObjectPrefixNoise() {
    String raw = "Here is the patch:\n{\"properties\":{\"k\":1}}\nThanks";
    String out = PatchJsonResponseSanitizer.extractJsonObject(raw, objectMapper);
    assertEquals("{\"properties\":{\"k\":1}}", out);
  }

  @Test
  void extractJsonObjectEmptyThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () -> PatchJsonResponseSanitizer.extractJsonObject("", objectMapper));
  }
}
