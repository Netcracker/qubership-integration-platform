package org.qubership.integration.platform.ai.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Deque;
import java.util.Iterator;
import java.util.Map;

/** Inlines JSON Schema {@code $ref} nodes for networknt validation. */
final class ElementPatchSchemaInlining {

  private ElementPatchSchemaInlining() {}

  static JsonNode inlineRefs(
      JsonNode node,
      SchemaRefResolver resolver,
      JsonNode documentRoot,
      String documentUri,
      Deque<String> refStack) {
    if (node == null || node.isNull() || node.isMissingNode()) {
      return node;
    }
    if (node.isObject() && node.has("$ref")) {
      String refUri = node.get("$ref").asText();
      if (refUri == null || refUri.isBlank() || refStack.contains(refUri)) {
        return JsonNodeFactory.instance.objectNode().put("type", "object");
      }
      refStack.push(refUri);
      try {
        JsonNode resolved = resolver.dereference(documentRoot, documentUri, node, refStack);
        if (resolved == null
            || resolved.isMissingNode()
            || resolved.isNull()
            || resolved.equals(node)) {
          return node;
        }
        String targetDocUri = documentUri;
        JsonNode targetRoot = documentRoot;
        if (!refUri.startsWith("#")) {
          targetDocUri = QipConfModelUris.stripFragment(refUri);
          targetRoot = resolver.loadDocumentRoot(targetDocUri);
        }
        return inlineRefs(resolved, resolver, targetRoot, targetDocUri, refStack);
      } finally {
        refStack.pop();
      }
    }
    if (node.isObject()) {
      ObjectNode copy = node.deepCopy();
      Iterator<Map.Entry<String, JsonNode>> it = copy.fields();
      while (it.hasNext()) {
        Map.Entry<String, JsonNode> e = it.next();
        copy.set(
            e.getKey(), inlineRefs(e.getValue(), resolver, documentRoot, documentUri, refStack));
      }
      return copy;
    }
    if (node.isArray()) {
      ArrayNode copy = (ArrayNode) node.deepCopy();
      for (int i = 0; i < copy.size(); i++) {
        copy.set(i, inlineRefs(copy.get(i), resolver, documentRoot, documentUri, refStack));
      }
      return copy;
    }
    return node;
  }

  /**
   * Removes {@code $ref} / {@code definitions} so networknt does not hit cyclic conf-model graphs.
   */
  static JsonNode stripRemainingRefs(JsonNode node) {
    if (node == null || node.isNull() || node.isMissingNode()) {
      return node;
    }
    if (node.isObject()) {
      ObjectNode copy = node.deepCopy();
      copy.remove("$ref");
      copy.remove("definitions");
      Iterator<Map.Entry<String, JsonNode>> it = copy.fields();
      while (it.hasNext()) {
        Map.Entry<String, JsonNode> e = it.next();
        copy.set(e.getKey(), stripRemainingRefs(e.getValue()));
      }
      if (copy.isEmpty()) {
        return JsonNodeFactory.instance.objectNode().put("type", "object");
      }
      return copy;
    }
    if (node.isArray()) {
      ArrayNode copy = (ArrayNode) node.deepCopy();
      for (int i = 0; i < copy.size(); i++) {
        copy.set(i, stripRemainingRefs(copy.get(i)));
      }
      return copy;
    }
    return node;
  }
}
