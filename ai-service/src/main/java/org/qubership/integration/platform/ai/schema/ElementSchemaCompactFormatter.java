package org.qubership.integration.platform.ai.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;

/** Builds compact JSON descriptions of JSON Schema fragments for LLM tools. */
public final class ElementSchemaCompactFormatter {

  private static final JsonNodeFactory NF = JsonNodeFactory.instance;

  private ElementSchemaCompactFormatter() {}

  /**
   * Retains the selected native schema and adds bounded reference-resolution metadata.
   *
   * <p>{@code $refTarget} is display metadata, not a conjunction with the native schema.
   * In draft 7, siblings of {@code $ref} do not add assertions to its target.
   */
  public static JsonNode describePropertySchema(
      JsonNode schema,
      SchemaRefResolver resolver,
      JsonNode documentRoot,
      String documentUri,
      int depth) {
    return describePropertySchema(schema, resolver, documentRoot, documentUri, depth, new ArrayDeque<>());
  }

  private static JsonNode describePropertySchema(
      JsonNode schema,
      SchemaRefResolver resolver,
      JsonNode documentRoot,
      String documentUri,
      int depth,
      Deque<String> activeRefs) {
    if (schema == null || !schema.isObject()) {
      return schema == null ? NF.nullNode() : schema.deepCopy();
    }
    ObjectNode out = schema.deepCopy();
    String baseUri = resolver.readDocumentUri(schema, documentUri);
    if (depth <= 0) {
      if (schema.has("$ref")) {
        out.set("$refTarget", referenceStatus(schema, baseUri, "DEPTH_LIMIT"));
      } else {
        out.put("$refExpansionStatus", "DEPTH_LIMIT");
      }
      return out;
    }
    // Recurse only through schema-bearing keywords. Enum, const, and default values are data.
    for (String key : List.of("properties", "patternProperties", "dependencies", "dependentSchemas")) {
      if (schema.path(key).isObject()) {
        ObjectNode children = (ObjectNode) out.get(key);
        schema.get(key).fields().forEachRemaining(entry -> children.set(entry.getKey(),
            describePropertySchema(entry.getValue(), resolver, documentRoot, baseUri, depth - 1, activeRefs)));
      }
    }
    for (String key : List.of("oneOf", "anyOf", "allOf", "prefixItems")) {
      if (schema.path(key).isArray()) {
        ArrayNode alternatives = NF.arrayNode();
        schema.get(key).forEach(child -> alternatives.add(
            describePropertySchema(child, resolver, documentRoot, baseUri, depth - 1, activeRefs)));
        out.set(key, alternatives);
      }
    }
    for (String key : List.of("if", "then", "else", "not", "items", "additionalProperties",
        "additionalItems", "contains", "propertyNames")) {
      if (schema.has(key)) {
        JsonNode child = schema.get(key);
        if (child.isArray()) {
          ArrayNode items = NF.arrayNode();
          child.forEach(item -> items.add(
              describePropertySchema(item, resolver, documentRoot, baseUri, depth - 1, activeRefs)));
          out.set(key, items);
        } else {
          out.set(key, describePropertySchema(child, resolver, documentRoot, baseUri, depth - 1, activeRefs));
        }
      }
    }
    if (schema.has("$ref")) {
      ObjectNode target = referenceStatus(schema, baseUri, "RESOLVED");
      String ref = target.path("ref").asText();
      if (activeRefs.contains(ref)) {
        target.put("status", "CYCLE");
      } else {
        try {
          JsonNode resolved = resolver.dereference(documentRoot, baseUri, schema, new ArrayDeque<>());
          activeRefs.push(ref);
          try {
            target.set("schema",
                describePropertySchema(resolved, resolver, documentRoot, baseUri, depth - 1, activeRefs));
          } finally {
            activeRefs.pop();
          }
        } catch (SchemaRefResolutionException | SchemaNotFoundException ex) {
          target.put("status", "UNRESOLVED");
          target.put("warning", ex.getMessage());
        }
      }
      out.set("$refTarget", target);
    }
    return out;
  }

  private static ObjectNode referenceStatus(JsonNode schema, String documentUri, String status) {
    String ref = schema.path("$ref").asText();
    ObjectNode target = NF.objectNode().put("status", status);
    target.put("ref", ref.startsWith("#") ? CipConfModelUris.stripFragment(documentUri) + ref : ref);
    return target;
  }

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

  /**
   * Summarizes root {@code oneOf} groups from the element properties model.
   *
   * <p>Each entry is one alternative: title/name plus that branch's {@code required} keys.
   * Catalog tab-level mandatory checks (description.yaml) are not included here.
   */
  public static ArrayNode summarizeRootOneOfAlternatives(
      List<JsonNode> rootOneOfGroups,
      SchemaRefResolver resolver,
      String elementDocumentUri,
      int maxDescriptionChars) {
    ArrayNode out = NF.arrayNode();
    if (rootOneOfGroups == null || rootOneOfGroups.isEmpty() || resolver == null) {
      return out;
    }
    String fallbackUri = elementDocumentUri == null ? "" : elementDocumentUri;
    for (JsonNode group : rootOneOfGroups) {
      if (group == null || !group.has("oneOf") || !group.get("oneOf").isArray()) {
        continue;
      }
      String groupUri =
          group.has("$id") && group.get("$id").isTextual()
              ? CipConfModelUris.stripFragment(group.get("$id").asText())
              : fallbackUri;
      JsonNode groupRoot = resolver.loadDocumentRoot(groupUri);
      String schemaGroup = schemaGroupLabel(group);
      Deque<String> refStack = new ArrayDeque<>();
      for (JsonNode branch : group.get("oneOf")) {
        ObjectNode alt = summarizeRootOneOfBranch(
            branch, resolver, groupRoot, groupUri, refStack, maxDescriptionChars);
        if (schemaGroup != null && !schemaGroup.isBlank()) {
          alt.put("schemaGroup", schemaGroup);
        }
        out.add(alt);
      }
    }
    return out;
  }

  private static ObjectNode summarizeRootOneOfBranch(
      JsonNode branch,
      SchemaRefResolver resolver,
      JsonNode documentRoot,
      String documentUri,
      Deque<String> refStack,
      int maxDescriptionChars) {
    String refName = refFragmentName(branch);
    JsonNode resolved = branch;
    if (branch != null && branch.isObject() && branch.has("$ref") && branch.size() == 1) {
      try {
        resolved = resolver.dereference(documentRoot, documentUri, branch, refStack);
      } catch (SchemaRefResolutionException ex) {
        ObjectNode err = NF.objectNode();
        if (refName != null) {
          err.put("name", refName);
        }
        err.put("title", "unresolved");
        err.put("warning", ex.getMessage());
        return err;
      }
    }
    ObjectNode summary = summarizeOneOfVariant(resolved, maxDescriptionChars);
    if (refName != null && !summary.has("name")) {
      summary.put("name", refName);
    }
    return summary;
  }

  private static String schemaGroupLabel(JsonNode fragment) {
    if (fragment != null && fragment.has("title") && fragment.get("title").isTextual()) {
      return fragment.get("title").asText();
    }
    if (fragment != null && fragment.has("$id") && fragment.get("$id").isTextual()) {
      String id = fragment.get("$id").asText();
      int slash = id.lastIndexOf('/');
      return slash >= 0 ? id.substring(slash + 1) : id;
    }
    return null;
  }

  private static String refFragmentName(JsonNode branch) {
    if (branch == null || !branch.isObject() || !branch.has("$ref") || !branch.get("$ref").isTextual()) {
      return null;
    }
    String ref = branch.get("$ref").asText();
    int slash = ref.lastIndexOf('/');
    if (slash < 0 || slash == ref.length() - 1) {
      return null;
    }
    return ref.substring(slash + 1);
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
    if (s.isObject() && s.has("$ref")) {
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
    String union = s.has("anyOf") ? "anyOf" : "oneOf";
    if (s.path(union).isArray()) {
      out.put("type", union);
      out.put("detailsAvailable", true);
      ArrayNode variants = NF.arrayNode();
      int i = 0;
      for (JsonNode branch : s.get(union)) {
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
