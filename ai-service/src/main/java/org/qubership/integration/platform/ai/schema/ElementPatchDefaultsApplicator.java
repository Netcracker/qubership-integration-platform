package org.qubership.integration.platform.ai.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Fills missing or null {@code properties.*} keys that are **unconditionally required** for the
 * element type when the merged schema defines a {@code default} for that property (after {@code
 * $ref} resolution). Does not overwrite explicit values. Optional properties with defaults are not
 * auto-filled here so partial patches (e.g. service-call operation branches) stay valid for local
 * oneOf checks.
 */
public final class ElementPatchDefaultsApplicator {

  private static final int MAX_DEREF_DEPTH = 24;

  private ElementPatchDefaultsApplicator() {}

  /**
   * Mutates {@code patchRoot} in place: ensures {@code properties} exists, then for each key in
   * {@code model.unconditionalRequired()} that is absent or null in {@code properties}, sets the
   * value from the property schema's {@code default} when present after {@code $ref} resolution.
   *
   * @param appliedOut if non-null, receives names of properties that were set
   */
  public static void applyMissingPropertyDefaults(
      ObjectNode patchRoot,
      ElementPropertiesSchemaModel model,
      SchemaRefResolver resolver,
      ObjectMapper objectMapper,
      ArrayNode appliedOut) {
    ObjectNode props;
    if (!patchRoot.has("properties") || patchRoot.get("properties").isNull()) {
      props = objectMapper.createObjectNode();
      patchRoot.set("properties", props);
    } else if (!patchRoot.get("properties").isObject()) {
      return;
    } else {
      props = (ObjectNode) patchRoot.get("properties");
    }

    JsonNode elementRoot = resolver.loadDocumentRoot(model.elementDocumentUri());
    String docUri = model.elementDocumentUri();

    for (String key : model.unconditionalRequired()) {
      JsonNode schema = model.propertyDefs().get(key);
      if (schema == null) {
        continue;
      }
      if (!props.has(key) || props.get(key).isNull()) {
        JsonNode def =
            extractSchemaDefault(schema, resolver, elementRoot, docUri, new ArrayDeque<>(), 0);
        if (def != null && !def.isNull()) {
          props.set(key, def.deepCopy());
          if (appliedOut != null) {
            appliedOut.add(key);
          }
        }
      }
    }
  }

  private static JsonNode extractSchemaDefault(
      JsonNode schema,
      SchemaRefResolver resolver,
      JsonNode documentRoot,
      String documentUri,
      Deque<String> refStack,
      int depth) {
    if (schema == null || schema.isNull() || schema.isMissingNode() || depth > MAX_DEREF_DEPTH) {
      return null;
    }
    JsonNode s = schema;
    if (s.isObject() && s.has("$ref") && s.size() == 1) {
      try {
        s = resolver.dereference(documentRoot, documentUri, s, refStack);
      } catch (SchemaRefResolutionException ex) {
        return null;
      }
      return extractSchemaDefault(s, resolver, documentRoot, documentUri, refStack, depth + 1);
    }
    if (s != null && s.isObject() && s.has("default")) {
      return s.get("default");
    }
    return null;
  }
}
