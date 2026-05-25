package org.qubership.integration.platform.ai.schema;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashSet;
import java.util.Set;

/** Collects conf-model document URIs referenced via {@code $ref} in a schema subtree. */
final class ElementPatchSchemaDocumentCollector {

  private ElementPatchSchemaDocumentCollector() {}

  static Set<String> collectDocumentUris(JsonNode root) {
    Set<String> uris = new LinkedHashSet<>();
    collectDocumentUris(root, uris);
    return uris;
  }

  private static void collectDocumentUris(JsonNode node, Set<String> uris) {
    if (node == null || node.isMissingNode() || node.isNull()) {
      return;
    }
    if (node.isObject()) {
      JsonNode ref = node.get("$ref");
      if (ref != null && ref.isTextual()) {
        uris.add(QipConfModelUris.stripFragment(ref.asText()));
      }
      node.fields().forEachRemaining(e -> collectDocumentUris(e.getValue(), uris));
    } else if (node.isArray()) {
      for (JsonNode child : node) {
        collectDocumentUris(child, uris);
      }
    }
  }
}
