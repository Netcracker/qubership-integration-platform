package org.qubership.integration.platform.ai.compiler.capture.policy;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Canonical fingerprint for capture tool arguments (ADR 0003).
 *
 * <p>Identity: {@code tool + NUL + capabilityOrEmpty + NUL + sha256Hex(canonicalJson)}.
 * Drops {@code rationale}/{@code explanation}/{@code comment}; sorts object keys; preserves
 * array order; strips string values.
 */
public final class ToolCallFingerprints {

  private static final Set<String> NOISE_KEYS = Set.of("rationale", "explanation", "comment");
  private static final JsonFactory JSON_FACTORY = new JsonFactory();
  private static final ObjectMapper FALLBACK_MAPPER = new ObjectMapper();

  private ToolCallFingerprints() {}

  public static String fingerprint(
      ObjectMapper objectMapper, String tool, String capability, Object args) {
    if (tool == null || tool.isBlank()) {
      throw new IllegalArgumentException("tool is required");
    }
    String capabilityOrEmpty = capability == null || capability.isBlank() ? "" : capability;
    ObjectMapper mapper = objectMapper == null ? FALLBACK_MAPPER : objectMapper;
    JsonNode tree = args == null ? NullNode.getInstance() : mapper.valueToTree(args);
    JsonNode normalized = normalize(tree, mapper);
    byte[] canonical = toCanonicalJsonBytes(normalized);
    String hash = sha256Hex(canonical);
    return tool + '\u0000' + capabilityOrEmpty + '\u0000' + hash;
  }

  static JsonNode normalize(JsonNode node) {
    return normalize(node, FALLBACK_MAPPER);
  }

  static JsonNode normalize(JsonNode node, ObjectMapper mapper) {
    if (node == null || node.isNull()) {
      return NullNode.getInstance();
    }
    if (node.isTextual()) {
      return mapper.getNodeFactory().textNode(node.asText().strip());
    }
    if (node.isArray()) {
      ArrayNode array = mapper.createArrayNode();
      for (JsonNode element : node) {
        array.add(normalize(element, mapper));
      }
      return array;
    }
    if (node.isObject()) {
      ObjectNode object = mapper.createObjectNode();
      List<String> names = new ArrayList<>();
      Iterator<String> fields = node.fieldNames();
      while (fields.hasNext()) {
        String name = fields.next();
        if (!NOISE_KEYS.contains(name)) {
          names.add(name);
        }
      }
      names.sort(String::compareTo);
      for (String name : names) {
        object.set(name, normalize(node.get(name), mapper));
      }
      return object;
    }
    return node;
  }

  static byte[] toCanonicalJsonBytes(JsonNode node) {
    try {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      try (JsonGenerator generator = JSON_FACTORY.createGenerator(out)) {
        writeCanonical(node, generator);
      }
      return out.toByteArray();
    } catch (IOException e) {
      throw new IllegalStateException("Failed to canonicalize fingerprint args", e);
    }
  }

  private static void writeCanonical(JsonNode node, JsonGenerator generator) throws IOException {
    if (node == null || node.isNull()) {
      generator.writeNull();
      return;
    }
    if (node.isBoolean()) {
      generator.writeBoolean(node.booleanValue());
      return;
    }
    if (node.isNumber()) {
      if (node.isIntegralNumber()) {
        generator.writeNumber(node.bigIntegerValue());
      } else {
        generator.writeNumber(node.decimalValue());
      }
      return;
    }
    if (node.isTextual()) {
      generator.writeString(node.asText());
      return;
    }
    if (node.isArray()) {
      generator.writeStartArray();
      for (JsonNode element : node) {
        writeCanonical(element, generator);
      }
      generator.writeEndArray();
      return;
    }
    if (node.isObject()) {
      generator.writeStartObject();
      List<Map.Entry<String, JsonNode>> fields = new ArrayList<>();
      node.fields().forEachRemaining(fields::add);
      fields.sort(Map.Entry.comparingByKey());
      for (Map.Entry<String, JsonNode> field : fields) {
        generator.writeFieldName(field.getKey());
        writeCanonical(field.getValue(), generator);
      }
      generator.writeEndObject();
      return;
    }
    generator.writeString(node.toString());
  }

  static String sha256Hex(byte[] bytes) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(bytes);
      StringBuilder hex = new StringBuilder(hash.length * 2);
      for (byte value : hash) {
        hex.append(Character.forDigit((value >> 4) & 0xF, 16));
        hex.append(Character.forDigit(value & 0xF, 16));
      }
      return hex.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 not available", e);
    }
  }

  /** UTF-8 preview of canonical JSON (tests / debugging only). */
  static String canonicalJson(JsonNode node) {
    return new String(toCanonicalJsonBytes(node), StandardCharsets.UTF_8);
  }
}
