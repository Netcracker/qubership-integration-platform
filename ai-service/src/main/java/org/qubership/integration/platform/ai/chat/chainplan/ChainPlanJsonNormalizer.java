package org.qubership.integration.platform.ai.chat.chainplan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Normalizes LLM-produced plan JSON into canonical {@link
 * org.qubership.integration.platform.ai.integration.catalog.model.ChainImplementationPlan} shape.
 */
final class ChainPlanJsonNormalizer {

  private ChainPlanJsonNormalizer() {}

  /**
   * When the model emits legacy root-level {@code name}/{@code description} instead of {@code
   * chain}, wrap them under {@code chain}.
   */
  static JsonNode normalizeRoot(ObjectMapper objectMapper, JsonNode root) {
    if (root == null || !root.isObject()) {
      return root;
    }
    if (root.has("chain") && !root.get("chain").isNull()) {
      return root;
    }
    if (!root.has("elements") || root.get("elements").isNull()) {
      return root;
    }
    JsonNode nameNode = root.get("name");
    if (nameNode == null || nameNode.isNull() || nameNode.asText().isBlank()) {
      return root;
    }
    ObjectNode out = root.deepCopy();
    ObjectNode chain = objectMapper.createObjectNode();
    chain.put("name", nameNode.asText());
    if (root.has("description") && !root.get("description").isNull()) {
      chain.put("description", root.get("description").asText());
    } else {
      chain.put("description", "");
    }
    out.set("chain", chain);
    out.remove("name");
    out.remove("description");
    return out;
  }

  /** Strips optional markdown fences when JSON is pasted inside a tool argument. */
  static String unwrapMarkdownFences(String raw) {
    if (raw == null) {
      return "";
    }
    String t = raw.trim();
    if (!t.startsWith("```")) {
      return t;
    }
    int firstNewline = t.indexOf('\n');
    if (firstNewline < 0) {
      return t;
    }
    String body = t.substring(firstNewline + 1);
    int close = body.lastIndexOf("```");
    if (close >= 0) {
      body = body.substring(0, close);
    }
    return body.trim();
  }
}
