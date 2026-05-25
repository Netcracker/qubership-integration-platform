package org.qubership.integration.platform.ai.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;

/** Builds compact JSON descriptions of JSON Schema fragments for LLM tools. */
public final class ElementSchemaCompactFormatter {

  private static final JsonNodeFactory NF = JsonNodeFactory.instance;

  private ElementSchemaCompactFormatter() {}

  public static ObjectNode patchShapeSummary() {
    ObjectNode n = NF.objectNode();
    n.put("name", "optional string");
    n.put("description", "optional string");
    n.put("type", "optional string (element type)");
    n.put("parentElementId", "optional string");
    n.put("swimlaneId", "optional string");
    n.put("mandatoryChecksPassed", "optional boolean");
    n.put("properties", "object (catalog business properties for the element type)");
    return n;
  }

  public static ObjectNode summarizePropertySchema(
      JsonNode rawSchema,
      SchemaRefResolver resolver,
      JsonNode documentRoot,
      String documentUri,
      int maxDescriptionChars,
      int depth) {
    Deque<String> refStack = new ArrayDeque<>();
    return summarizePropertySchema(
        rawSchema, resolver, documentRoot, documentUri, refStack, maxDescriptionChars, depth);
  }

  private static ObjectNode summarizePropertySchema(
      JsonNode rawSchema,
      SchemaRefResolver resolver,
      JsonNode documentRoot,
      String documentUri,
      Deque<String> refStack,
      int maxDescriptionChars,
      int depth) {
    ObjectNode out = NF.objectNode();
    if (rawSchema == null || rawSchema.isNull() || rawSchema.isMissingNode()) {
      out.put("type", "unknown");
      return out;
    }
    JsonNode s = rawSchema;
    if (s.isObject() && s.has("$ref") && s.size() == 1) {
      try {
        s = resolver.dereference(documentRoot, documentUri, s, refStack);
      } catch (SchemaRefResolutionException ex) {
        out.put("type", "unresolvedRef");
        out.put("detailsAvailable", true);
        out.put("warning", ex.getMessage());
        return out;
      }
    }
    if (depth <= 0) {
      out.put("type", "truncated");
      out.put("detailsAvailable", true);
      return out;
    }
    if (s.has("oneOf") && s.get("oneOf").isArray()) {
      out.put("type", "oneOf");
      out.put("detailsAvailable", true);
      ArrayNode variants = NF.arrayNode();
      int i = 0;
      for (JsonNode branch : s.get("oneOf")) {
        if (i++ > 12) {
          break;
        }
        JsonNode b = branch;
        if (branch != null && branch.isObject() && branch.has("$ref") && branch.size() == 1) {
          try {
            b = resolver.dereference(documentRoot, documentUri, branch, refStack);
          } catch (SchemaRefResolutionException ex) {
            ObjectNode err = NF.objectNode();
            err.put("title", "unresolved");
            err.put("warning", ex.getMessage());
            variants.add(err);
            continue;
          }
        }
        variants.add(summarizeOneOfVariant(b, maxDescriptionChars));
      }
      out.set("variants", variants);
      return out;
    }
    if (s.has("allOf") && s.get("allOf").isArray()) {
      out.put("type", "allOf");
      out.put("detailsAvailable", true);
      return out;
    }
    if (s.has("type")) {
      if (s.get("type").isTextual()) {
        out.put("type", s.get("type").asText());
      } else if (s.get("type").isArray()) {
        out.put("type", s.get("type").toString());
      } else {
        out.put("type", s.get("type").toString());
      }
    }
    if (s.has("title") && s.get("title").isTextual()) {
      out.put("title", s.get("title").asText());
    }
    if (s.has("description") && s.get("description").isTextual()) {
      String d = s.get("description").asText().replaceAll("\\s+", " ").trim();
      if (d.length() > maxDescriptionChars) {
        d = d.substring(0, maxDescriptionChars) + "…";
      }
      out.put("description", d);
    }
    if (s.has("default")) {
      out.set("default", s.get("default"));
    }
    if (s.has("enum")) {
      out.set("enum", s.get("enum"));
    }
    if (s.has("const")) {
      out.set("const", s.get("const"));
    }
    if (s.has("required") && s.get("required").isArray()) {
      out.set("required", s.get("required"));
    }
    if (s.has("items")) {
      out.put("detailsAvailable", true);
      out.set(
          "items",
          summarizePropertySchema(
              s.get("items"),
              resolver,
              documentRoot,
              documentUri,
              refStack,
              maxDescriptionChars,
              depth - 1));
    }
    if (s.has("properties") && s.get("properties").isObject() && s.get("properties").size() > 0) {
      out.put("detailsAvailable", true);
      if (!out.has("type")) {
        out.put("type", "object");
      }
    }
    if (s.has("patternProperties") || s.has("additionalProperties")) {
      out.put("detailsAvailable", true);
    }
    return out;
  }

  private static ObjectNode summarizeOneOfVariant(JsonNode branch, int maxDescriptionChars) {
    ObjectNode v = NF.objectNode();
    if (branch != null && branch.has("title") && branch.get("title").isTextual()) {
      v.put("title", branch.get("title").asText());
    }
    if (branch != null && branch.has("properties") && branch.get("properties").isObject()) {
      ObjectNode consts = NF.objectNode();
      Iterator<String> names = branch.get("properties").fieldNames();
      while (names.hasNext()) {
        String name = names.next();
        JsonNode prop = branch.get("properties").get(name);
        if (prop != null && prop.has("const")) {
          consts.set(name, prop.get("const"));
        }
      }
      if (consts.size() > 0) {
        v.set("constDiscriminators", consts);
      }
    }
    if (branch != null && branch.has("required") && branch.get("required").isArray()) {
      v.set("required", branch.get("required"));
    }
    if (branch != null && branch.has("description") && branch.get("description").isTextual()) {
      String d = branch.get("description").asText().replaceAll("\\s+", " ").trim();
      if (d.length() > maxDescriptionChars) {
        d = d.substring(0, maxDescriptionChars) + "…";
      }
      v.put("description", d);
    }
    return v;
  }
}
