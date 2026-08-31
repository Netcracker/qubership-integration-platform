package org.qubership.integration.platform.ai.plan.mapping.schema;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingContract;

/** Builds {@link MappingContract} from a JSON Schema object node. */
public final class JsonSchemaMappingContractFactory {

  private JsonSchemaMappingContractFactory() {}

  public static MappingContract from(JsonNode schema) {
    if (schema == null) {
      return MappingContract.unknown();
    }
    List<MappingContract.Field> fields = new ArrayList<>();
    collectFields(schema, schema, "$", fields);
    return new MappingContract(fields, true);
  }

  private static void collectFields(
      JsonNode root, JsonNode node, String pathPrefix, List<MappingContract.Field> out) {
    JsonNode resolved = resolveRef(root, node, new HashSet<>());
    if (resolved == null || !resolved.isObject()) {
      return;
    }
    JsonNode alternatives = firstArray(resolved.get("oneOf"), resolved.get("anyOf"));
    if (alternatives != null) {
      collectAlternativeFields(root, alternatives, pathPrefix, out);
      return;
    }
    JsonNode properties = resolved.get("properties");
    if (properties == null || !properties.isObject()) {
      return;
    }
    List<String> required = requiredNames(resolved);
    Iterator<Map.Entry<String, JsonNode>> propertyFields = properties.fields();
    while (propertyFields.hasNext()) {
      Map.Entry<String, JsonNode> entry = propertyFields.next();
      String name = entry.getKey();
      JsonNode propertySchema = resolveRef(root, entry.getValue(), new HashSet<>());
      String path = pathPrefix + "." + name;
      String type = schemaType(propertySchema);
      out.add(new MappingContract.Field(path, type, required.contains(name)));
      if ("object".equals(type)) {
        collectFields(root, propertySchema, path, out);
      }
    }
  }

  private static void collectAlternativeFields(
      JsonNode root, JsonNode alternatives, String pathPrefix, List<MappingContract.Field> out) {
    Map<String, MappingContract.Field> union = new LinkedHashMap<>();
    Map<String, Integer> presentCount = new HashMap<>();
    Map<String, Integer> requiredCount = new HashMap<>();
    int variants = 0;
    for (JsonNode variant : alternatives) {
      variants++;
      List<MappingContract.Field> variantFields = new ArrayList<>();
      collectFields(root, variant, pathPrefix, variantFields);
      for (MappingContract.Field field : variantFields) {
        union.putIfAbsent(field.path(), field);
        presentCount.put(field.path(), presentCount.getOrDefault(field.path(), 0) + 1);
        if (field.required()) {
          requiredCount.put(field.path(), requiredCount.getOrDefault(field.path(), 0) + 1);
        }
      }
    }
    if (variants == 0) {
      return;
    }
    for (MappingContract.Field field : union.values()) {
      boolean required =
          requiredCount.getOrDefault(field.path(), 0) == variants
              && presentCount.getOrDefault(field.path(), 0) == variants;
      out.add(new MappingContract.Field(field.path(), field.type(), required));
    }
  }

  private static JsonNode firstArray(JsonNode first, JsonNode second) {
    if (first != null && first.isArray() && !first.isEmpty()) {
      return first;
    }
    if (second != null && second.isArray() && !second.isEmpty()) {
      return second;
    }
    return null;
  }

  private static List<String> requiredNames(JsonNode node) {
    JsonNode required = node == null ? null : node.get("required");
    if (required == null || !required.isArray()) {
      return List.of();
    }
    List<String> names = new ArrayList<>();
    required.forEach(item -> {
      if (item.isTextual()) {
        names.add(item.asText());
      }
    });
    return names;
  }

  private static String schemaType(JsonNode node) {
    if (node == null || !node.isObject()) {
      return "";
    }
    JsonNode type = node.get("type");
    if (type != null && type.isTextual()) {
      return type.asText();
    }
    if (node.has("properties")) {
      return "object";
    }
    if (node.has("items")) {
      return "array";
    }
    return "";
  }

  private static JsonNode resolveRef(JsonNode root, JsonNode node, Set<String> visiting) {
    if (node == null || !node.isObject() || !node.has("$ref")) {
      return node;
    }
    String ref = node.get("$ref").asText();
    if (ref == null || !ref.startsWith("#/")) {
      return node;
    }
    if (!visiting.add(ref)) {
      return node;
    }
    JsonNode target = root.at(toJsonPointer(ref));
    if (target.isMissingNode()) {
      visiting.remove(ref);
      return node;
    }
    JsonNode resolved = resolveRef(root, target, visiting);
    visiting.remove(ref);
    return resolved;
  }

  private static String toJsonPointer(String ref) {
    if (ref == null || ref.isEmpty() || "#".equals(ref)) {
      return "";
    }
    if (!ref.startsWith("#/")) {
      return "/missing";
    }
    String[] parts = ref.substring(1).split("/", -1);
    StringBuilder sb = new StringBuilder();
    for (int i = 1; i < parts.length; i++) {
      sb.append('/');
      sb.append(parts[i]);
    }
    return sb.toString();
  }
}
