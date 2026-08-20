package org.qubership.integration.platform.ai.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Configurable property keys of an element type, read from {@code qip-schemas/element}.
 *
 * <p>A reader asked to change an element does not know what the catalog calls the thing they want
 * changed, and neither does a model that only sees a chain's elements listed by id and type. The
 * schema names every key, so the names come from there rather than from a paraphrase.
 *
 * <p>The walk collects the keys of every {@code properties} object below the element's own
 * properties block, follows the {@code definitions} a variant schema keeps its keys in, and
 * resolves one external {@code $ref} at a time. It does not evaluate {@code oneOf} or {@code allOf}
 * to decide which variant applies, so a type offering alternative shapes — an HTTP trigger's custom
 * and implemented-service endpoints, for one — contributes the keys of all of them. The result is a
 * list of names to choose from, not a claim that every name fits every configuration; the schema
 * still refuses a key the chosen shape has no room for.
 */
@ApplicationScoped
public class ChainElementPropertyKeys {

  private static final String ELEMENT_PATH = "qip-schemas/element/";
  private static final String REF_MARKER = "/element/";
  private static final int MAX_REF_DEPTH = 3;

  private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

  private final Map<String, List<String>> byType = new ConcurrentHashMap<>();

  /** Property keys for {@code type}, or an empty list when the schema names none. */
  public List<String> forType(String type) {
    if (type == null || type.isBlank()) {
      return List.of();
    }
    return byType.computeIfAbsent(type.trim(), ChainElementPropertyKeys::read);
  }

  private static List<String> read(String type) {
    JsonNode schema = load(ELEMENT_PATH + type + ".schema.yaml");
    if (schema == null) {
      return List.of();
    }
    Set<String> keys = new LinkedHashSet<>();
    collect(schema.path("properties").path("properties"), keys, 0);
    collect(schema.path("definitions"), keys, 0);
    return List.copyOf(new ArrayList<>(keys));
  }

  private static void collect(JsonNode node, Set<String> keys, int refDepth) {
    if (node == null || node.isMissingNode() || node.isNull()) {
      return;
    }
    if (node.isArray()) {
      node.forEach(item -> collect(item, keys, refDepth));
      return;
    }
    if (!node.isObject()) {
      return;
    }
    JsonNode ref = node.get("$ref");
    if (ref != null && ref.isTextual() && refDepth < MAX_REF_DEPTH) {
      collectFromRef(ref.asText(), keys, refDepth);
    }
    node.properties()
        .forEach(
            field -> {
              JsonNode value = field.getValue();
              if ("properties".equals(field.getKey()) && value.isObject()) {
                value.fieldNames().forEachRemaining(keys::add);
              }
              collect(value, keys, refDepth);
            });
  }

  /** Follows a {@code $ref} that names another element schema file. Local refs are ignored. */
  private static void collectFromRef(String ref, Set<String> keys, int refDepth) {
    int marker = ref.lastIndexOf(REF_MARKER);
    if (marker < 0 || !ref.endsWith(".schema.yaml")) {
      return;
    }
    JsonNode referenced = load(ELEMENT_PATH + ref.substring(marker + REF_MARKER.length()));
    if (referenced == null) {
      return;
    }
    JsonNode properties = referenced.path("properties");
    if (properties.isObject()) {
      properties.fieldNames().forEachRemaining(keys::add);
    }
    collect(properties, keys, refDepth + 1);
  }

  private static JsonNode load(String resource) {
    ClassLoader loader = Thread.currentThread().getContextClassLoader();
    if (loader == null) {
      loader = ChainElementPropertyKeys.class.getClassLoader();
    }
    try (InputStream in = loader.getResourceAsStream(resource)) {
      return in == null ? null : YAML.readTree(in);
    } catch (Exception e) {
      // A hint list is worth less than the request it accompanies: an unreadable schema leaves the
      // element without suggested keys rather than failing the edit.
      return null;
    }
  }
}
