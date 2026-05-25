package org.qubership.integration.platform.ai.integration.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Parses catalog tool result envelopes in contract tests. */
public final class CatalogToolResultTestSupport {

  private CatalogToolResultTestSupport() {}

  public static JsonNode parseEnvelope(ObjectMapper mapper, String out) throws Exception {
    assertNotNull(out);
    JsonNode root = mapper.readTree(out);
    assertTrue(root.isObject(), out);
    assertTrue(root.has("ok"), out);
    assertTrue(root.has("tool"), out);
    return root;
  }

  public static JsonNode requireSuccess(ObjectMapper mapper, String out) throws Exception {
    JsonNode root = parseEnvelope(mapper, out);
    assertTrue(root.path("ok").asBoolean(), out);
    return root;
  }

  public static JsonNode requireError(ObjectMapper mapper, String out) throws Exception {
    JsonNode root = parseEnvelope(mapper, out);
    assertTrue(!root.path("ok").asBoolean(), out);
    assertTrue(root.has("error"), out);
    return root;
  }

  public static String dataAsString(ObjectMapper mapper, String out) throws Exception {
    JsonNode data = requireSuccess(mapper, out).get("data");
    return data != null ? mapper.writeValueAsString(data) : "";
  }

  public static JsonNode materializeReportData(ObjectMapper mapper, String out) throws Exception {
    return requireSuccess(mapper, out).path("data");
  }

  public static String combinedText(ObjectMapper mapper, String out) {
    if (out == null) {
      return "";
    }
    try {
      JsonNode root = mapper.readTree(out);
      if (root.isObject() && root.has("ok")) {
        StringBuilder sb = new StringBuilder();
        if (root.has("message")) {
          sb.append(root.get("message").asText());
        }
        if (root.has("data")) {
          sb.append(mapper.writeValueAsString(root.get("data")));
        }
        if (root.has("error")) {
          sb.append(mapper.writeValueAsString(root.get("error")));
        }
        return sb.toString();
      }
    } catch (Exception ignored) {
      // not envelope JSON
    }
    return out;
  }
}
