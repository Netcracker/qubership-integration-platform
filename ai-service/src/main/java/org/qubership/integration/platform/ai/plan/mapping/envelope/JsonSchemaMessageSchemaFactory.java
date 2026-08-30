package org.qubership.integration.platform.ai.plan.mapping.envelope;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import org.qubership.integration.platform.ai.plan.mapping.atlas.MappingDescriptionDocument.ArrayType;
import org.qubership.integration.platform.ai.plan.mapping.atlas.MappingDescriptionDocument.Attribute;
import org.qubership.integration.platform.ai.plan.mapping.atlas.MappingDescriptionDocument.BooleanType;
import org.qubership.integration.platform.ai.plan.mapping.atlas.MappingDescriptionDocument.DataType;
import org.qubership.integration.platform.ai.plan.mapping.atlas.MappingDescriptionDocument.MessageSchema;
import org.qubership.integration.platform.ai.plan.mapping.atlas.MappingDescriptionDocument.NullType;
import org.qubership.integration.platform.ai.plan.mapping.atlas.MappingDescriptionDocument.NumberType;
import org.qubership.integration.platform.ai.plan.mapping.atlas.MappingDescriptionDocument.ObjectSchema;
import org.qubership.integration.platform.ai.plan.mapping.atlas.MappingDescriptionDocument.ObjectType;
import org.qubership.integration.platform.ai.plan.mapping.atlas.MappingDescriptionDocument.StringType;
import org.qubership.integration.platform.ai.plan.mapping.schema.MappingSchemaSide;

/** Builds mapper-2 {@link MessageSchema} envelopes from operation JSON Schema sides. */
public final class JsonSchemaMessageSchemaFactory {

  private static final String BODY = "body";

  private final ObjectMapper objectMapper;

  public JsonSchemaMessageSchemaFactory(ObjectMapper objectMapper) {
    this.objectMapper =
        Objects.requireNonNull(objectMapper, "objectMapper")
            .copy()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
  }

  public MappingEnvelope fromSides(MappingSchemaSide sourceSide, MappingSchemaSide targetSide) {
    Objects.requireNonNull(sourceSide, "sourceSide");
    Objects.requireNonNull(targetSide, "targetSide");
    Map<String, String> idToPath = new LinkedHashMap<>();
    MessageSchema source = fromSchema(sourceSide.schema(), idToPath);
    MessageSchema target = fromSchema(targetSide.schema(), idToPath);
    return new MappingEnvelope(source, target, idToPath, digest(source, target));
  }

  private MessageSchema fromSchema(JsonNode schema, Map<String, String> idToPath) {
    DataType body = toDataType(schema, schema, "$", idToPath);
    return new MessageSchema(List.of(), List.of(), body);
  }

  private DataType toDataType(
      JsonNode root, JsonNode node, String path, Map<String, String> idToPath) {
    JsonNode resolved = resolveRef(root, node, new HashSet<>());
    String type = schemaType(resolved);
    return switch (type) {
      case "object" -> toObjectType(root, resolved, path, idToPath);
      case "array" -> {
        JsonNode items = resolved == null ? null : resolved.get("items");
        DataType itemType =
            items == null ? new NullType() : toDataType(root, items, path, idToPath);
        yield new ArrayType(itemType);
      }
      case "string" -> new StringType();
      case "number", "integer" -> new NumberType();
      case "boolean" -> new BooleanType();
      default -> new NullType();
    };
  }

  private ObjectType toObjectType(
      JsonNode root, JsonNode node, String path, Map<String, String> idToPath) {
    List<Attribute> attributes = new ArrayList<>();
    JsonNode properties = node == null ? null : node.get("properties");
    List<String> required = requiredNames(node);
    if (properties != null && properties.isObject()) {
      for (Map.Entry<String, JsonNode> entry : properties.properties()) {
        String name = entry.getKey();
        String propertyPath = path + "." + name;
        String id = AttributeIds.forPath(BODY, propertyPath);
        idToPath.put(id, propertyPath);
        DataType type = toDataType(root, entry.getValue(), propertyPath, idToPath);
        Boolean requiredFlag = required.contains(name) ? Boolean.TRUE : null;
        attributes.add(new Attribute(id, name, type, null, requiredFlag));
      }
    }
    return new ObjectType(new ObjectSchema(AttributeIds.forPath(BODY, path), attributes));
  }

  private String digest(MessageSchema source, MessageSchema target) {
    Map<String, MessageSchema> payload = new TreeMap<>();
    payload.put("source", source);
    payload.put("target", target);
    try {
      byte[] json = objectMapper.writeValueAsBytes(payload);
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is unavailable", e);
    } catch (Exception e) {
      throw new IllegalStateException("Cannot serialize mapping envelope for digest", e);
    }
  }

  private static List<String> requiredNames(JsonNode node) {
    JsonNode required = node == null ? null : node.get("required");
    if (required == null || !required.isArray()) {
      return List.of();
    }
    List<String> names = new ArrayList<>();
    required.forEach(
        item -> {
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
