package org.qubership.integration.platform.ai.schema;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Extracts a JSON object string from LLM text (strips markdown fences, trims noise). */
public final class PatchJsonResponseSanitizer {

  private PatchJsonResponseSanitizer() {}

  /**
   * Returns trimmed JSON object text suitable for {@link ObjectMapper#readTree(String)}.
   *
   * @throws IllegalArgumentException if no parseable JSON object is found
   */
  public static String extractJsonObject(String raw, ObjectMapper objectMapper) {
    if (raw == null || raw.isBlank()) {
      throw new IllegalArgumentException("empty llm patch response");
    }
    String s = stripMarkdownFences(raw.trim());
    s = s.trim();
    try {
      JsonNode n = objectMapper.readTree(s);
      if (n.isObject()) {
        return objectMapper.writeValueAsString(n);
      }
    } catch (JsonProcessingException ignored) {
      // try substring extraction
    }
    int start = s.indexOf('{');
    int end = s.lastIndexOf('}');
    if (start < 0 || end <= start) {
      throw new IllegalArgumentException("no json object in llm patch response");
    }
    try {
      String slice = s.substring(start, end + 1);
      JsonNode n = objectMapper.readTree(slice);
      if (!n.isObject()) {
        throw new IllegalArgumentException("extracted fragment is not a json object");
      }
      return objectMapper.writeValueAsString(n);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException(
          "invalid json in llm patch response: " + e.getOriginalMessage(), e);
    }
  }

  static String stripMarkdownFences(String text) {
    String s = text;
    if (s.startsWith("```")) {
      int firstNl = s.indexOf('\n');
      if (firstNl > 0) {
        s = s.substring(firstNl + 1);
      } else {
        s = s.substring(Math.min(s.length(), 3));
      }
    }
    int fence = s.lastIndexOf("```");
    if (fence >= 0) {
      s = s.substring(0, fence);
    }
    return s.trim();
  }
}
