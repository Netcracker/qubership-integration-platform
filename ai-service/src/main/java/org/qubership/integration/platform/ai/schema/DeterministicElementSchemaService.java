package org.qubership.integration.platform.ai.schema;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Deterministic QIP element schema access for LLM tools (classpath YAML, not
 * RAG).
 */
@ApplicationScoped
public class DeterministicElementSchemaService {

  private static final int MAX_DESCRIPTION = 240;
  private static final String KEY_ELEMENT_TYPE = "elementType";
  private static final String KEY_PROPERTIES = "properties";
  private static final String KEY_ONE_OF = "oneOf";
  private static final String ERROR_ELEMENT_TYPE_REQUIRED = "elementType is required";
  private static final String ERROR_SCHEMA_NOT_FOUND_PREFIX = "Schema not found for element type: ";

  @Inject
  SchemaResourceLoader schemaResourceLoader;

  @Inject
  QipSchemaYamlParser qipSchemaYamlParser;

  @Inject
  SchemaRefResolver schemaRefResolver;

  @Inject
  ObjectMapper objectMapper;

  /** Wires dependencies for unit tests without CDI. */
  public static DeterministicElementSchemaService createForUnitTests(ObjectMapper objectMapper) {
    SchemaResourceLoader schemaResourceLoader = new SchemaResourceLoader();
    QipSchemaYamlParser qipSchemaYamlParser = new QipSchemaYamlParser();
    SchemaRefResolver schemaRefResolver =
        new SchemaRefResolver(schemaResourceLoader, qipSchemaYamlParser);
    DeterministicElementSchemaService service = new DeterministicElementSchemaService();
    service.schemaResourceLoader = schemaResourceLoader;
    service.qipSchemaYamlParser = qipSchemaYamlParser;
    service.schemaRefResolver = schemaRefResolver;
    service.objectMapper = objectMapper;
    return service;
  }

  public String describeElementPatchSchema(String elementType) {
    try {
      if (elementType == null || elementType.isBlank()) {
        return errorJson(ERROR_ELEMENT_TYPE_REQUIRED);
      }
      String trimmed = elementType.trim();
      if (!schemaResourceLoader.existsElementSchema(trimmed)) {
        return errorJson(ERROR_SCHEMA_NOT_FOUND_PREFIX + trimmed);
      }
      ElementPropertiesSchemaModel model = ElementPropertiesSchemaModelBuilder.build(trimmed, schemaRefResolver);
      ObjectNode out = objectMapper.createObjectNode();
      out.put(KEY_ELEMENT_TYPE, model.elementType());
      out.put("elementDocumentUri", model.elementDocumentUri());
      out.set("patchShape", ElementSchemaCompactFormatter.patchShapeSummary());
      // Unconditional required only (keys always required regardless of oneOf branch).
      out.set("requiredProperties", objectMapper.valueToTree(model.unconditionalRequired()));
      out.set(
          "unconditionalRequiredProperties",
          objectMapper.valueToTree(model.unconditionalRequired()));
      out.set(
          "rootOneOfAlternatives",
          ElementSchemaCompactFormatter.summarizeRootOneOfAlternatives(
              model.rootOneOfGroups(),
              schemaRefResolver,
              model.elementDocumentUri(),
              MAX_DESCRIPTION));
      out.put(
          "requiredNote",
          "requiredProperties / unconditionalRequiredProperties list only keys that are always"
              + " required. When rootOneOfAlternatives is non-empty, pick exactly one alternative"
              + " per schemaGroup (or the sole group) and include that alternative's required"
              + " properties. Catalog UI tab mandatory checks are not listed here.");
      ObjectNode props = objectMapper.createObjectNode();
      JsonNode elementRoot = schemaRefResolver.loadDocumentRoot(model.elementDocumentUri());
      for (Map.Entry<String, JsonNode> e : model.propertyDefs().entrySet()) {
        props.set(
            e.getKey(),
            ElementSchemaCompactFormatter.summarizePropertySchema(
                e.getValue(),
                schemaRefResolver,
                elementRoot,
                model.elementDocumentUri(),
                MAX_DESCRIPTION,
                5));
      }
      out.set(KEY_PROPERTIES, props);
      out.set("warnings", objectMapper.valueToTree(model.warnings()));
      return objectMapper.writeValueAsString(out);
    } catch (SchemaNotFoundException | SchemaRefResolutionException ex) {
      return errorJson(ex.getMessage());
    } catch (JsonProcessingException e) {
      return errorJson("Failed to serialize schema summary: " + e.getOriginalMessage());
    } catch (Exception e) {
      return errorJson("describeElementPatchSchema failed: " + e.getMessage());
    }
  }

  public String describeElementProperty(String elementType, String propertyPath) {
    try {
      if (elementType == null || elementType.isBlank()) {
        return errorJson(ERROR_ELEMENT_TYPE_REQUIRED);
      }
      if (propertyPath == null || propertyPath.isBlank()) {
        return errorJson("propertyPath is required");
      }
      String trimmed = elementType.trim();
      if (!schemaResourceLoader.existsElementSchema(trimmed)) {
        return errorJson(ERROR_SCHEMA_NOT_FOUND_PREFIX + trimmed);
      }
      return describeExistingElementProperty(trimmed, propertyPath);
    } catch (SchemaNotFoundException | SchemaRefResolutionException ex) {
      return errorJson(ex.getMessage());
    } catch (JsonProcessingException e) {
      return errorJson("Failed to serialize property schema: " + e.getOriginalMessage());
    } catch (Exception e) {
      return errorJson("describeElementProperty failed: " + e.getMessage());
    }
  }

  private String describeExistingElementProperty(String elementType, String propertyPath)
      throws JsonProcessingException {
    ElementPropertiesSchemaModel model = ElementPropertiesSchemaModelBuilder.build(elementType, schemaRefResolver);
    String normalized = normalizePropertyPath(propertyPath);
    String[] segments = normalized.split("\\.");
    if (segments.length == 0 || segments[0].isBlank()) {
      return errorJson("Invalid propertyPath");
    }
    JsonNode elementRoot = schemaRefResolver.loadDocumentRoot(model.elementDocumentUri());
    String docUri = model.elementDocumentUri();
    Deque<String> refStack = new ArrayDeque<>();
    JsonNode schema = model.propertyDefs().get(segments[0]);
    if (schema == null || schema.isMissingNode()) {
      return errorJson("Unknown property: " + segments[0]);
    }
    schema = derefOne(schema, schemaRefResolver, elementRoot, docUri, refStack);
    for (int i = 1; i < segments.length; i++) {
      schema = navigatePropertySchema(
          schema, segments[i], schemaRefResolver, elementRoot, docUri, refStack);
      if (schema == null || schema.isMissingNode()) {
        return errorJson("Path not found at segment: " + segments[i]);
      }
    }
    ObjectNode out = objectMapper.createObjectNode();
    out.put(KEY_ELEMENT_TYPE, model.elementType());
    out.put("propertyPath", normalized);
    out.set(
        "schema",
        ElementSchemaCompactFormatter.summarizePropertySchema(
            schema, schemaRefResolver, elementRoot, docUri, MAX_DESCRIPTION, 8));
    return objectMapper.writeValueAsString(out);
  }

  public String validateElementPatch(String elementType, String patchJson) {
    try {
      if (elementType == null || elementType.isBlank()) {
        return errorJson(ERROR_ELEMENT_TYPE_REQUIRED);
      }
      String trimmed = elementType.trim();
      if (!schemaResourceLoader.existsElementSchema(trimmed)) {
        return errorJson(ERROR_SCHEMA_NOT_FOUND_PREFIX + trimmed);
      }
      ElementPropertiesSchemaModel model = ElementPropertiesSchemaModelBuilder.build(trimmed, schemaRefResolver);
      DefaultsMergeResult merged = mergeDefaultsIntoPatchCopy(patchJson, model);
      ObjectNode result =
          ElementPatchValidator.validate(
              merged.mergedPatchJson(), model, schemaRefResolver, objectMapper);
      if (!merged.appliedKeys().isEmpty()) {
        result.set("defaultsApplied", merged.appliedKeys());
        result.set("patchWithDefaults", objectMapper.readTree(merged.mergedPatchJson()));
      }
      return objectMapper.writeValueAsString(result);
    } catch (SchemaNotFoundException | SchemaRefResolutionException ex) {
      return errorJson(ex.getMessage());
    } catch (JsonProcessingException e) {
      return errorJson("Failed to serialize validation result: " + e.getOriginalMessage());
    } catch (Exception e) {
      return errorJson("validateElementPatch failed: " + e.getMessage());
    }
  }

  /** Keys allowed under {@code properties} by embedded JSON Schema. */
  public java.util.Set<String> allowedPatchPropertyKeys(String elementType) {
    if (elementType == null || elementType.isBlank()) {
      return java.util.Set.of();
    }
    String trimmed = elementType.trim();
    if (!schemaResourceLoader.existsElementSchema(trimmed)) {
      return java.util.Set.of();
    }
    ElementPropertiesSchemaModel model = ElementPropertiesSchemaModelBuilder.build(trimmed, schemaRefResolver);
    return java.util.Set.copyOf(model.propertyDefs().keySet());
  }

  /** True when an element schema resource exists for the type (including property-less schemas). */
  public boolean hasElementSchema(String elementType) {
    if (elementType == null || elementType.isBlank()) {
      return false;
    }
    return schemaResourceLoader.existsElementSchema(elementType.trim());
  }

  /**
   * Unconditionally required keys under {@code properties} by embedded JSON
   * Schema.
   */
  public java.util.Set<String> requiredPatchPropertyKeys(String elementType) {
    if (elementType == null || elementType.isBlank()) {
      return java.util.Set.of();
    }
    String trimmed = elementType.trim();
    if (!schemaResourceLoader.existsElementSchema(trimmed)) {
      return java.util.Set.of();
    }
    ElementPropertiesSchemaModel model = ElementPropertiesSchemaModelBuilder.build(trimmed, schemaRefResolver);
    return java.util.Set.copyOf(model.unconditionalRequired());
  }

  /**
   * Validates a structured capture value ({@link JsonNode}) against the element property schema.
   *
   * @return validation error message, or empty when valid or schema is unavailable
   */
  public Optional<String> validateCapturePropertyValue(
      String elementType, String propertyKey, JsonNode value) {
    if (elementType == null
        || elementType.isBlank()
        || propertyKey == null
        || propertyKey.isBlank()
        || value == null
        || value.isNull()) {
      return Optional.empty();
    }
    try {
      String trimmedType = elementType.trim();
      if (!schemaResourceLoader.existsElementSchema(trimmedType)) {
        return Optional.empty();
      }
      ElementPropertiesSchemaModel model =
          ElementPropertiesSchemaModelBuilder.build(trimmedType, schemaRefResolver);
      JsonNode schema = model.propertyDefs().get(propertyKey.trim());
      if (schema == null || schema.isMissingNode()) {
        return Optional.empty();
      }
      JsonNode elementRoot = schemaRefResolver.loadDocumentRoot(model.elementDocumentUri());
      List<String> errors =
          ElementPatchValidator.validatePropertyValue(
              propertyKey.trim(),
              value,
              schema,
              schemaRefResolver,
              elementRoot,
              model.elementDocumentUri(),
              objectMapper);
      return errors.isEmpty() ? Optional.empty() : Optional.of(errors.get(0));
    } catch (Exception e) {
      return Optional.of("Property validation failed: " + e.getMessage());
    }
  }

  public Object coercePatchPropertyValue(String elementType, String propertyKey, String rawValue) {
    if (elementType == null || elementType.isBlank() || propertyKey == null || propertyKey.isBlank()) {
      return rawValue;
    }
    try {
      String trimmed = elementType.trim();
      if (!schemaResourceLoader.existsElementSchema(trimmed)) {
        return rawValue;
      }
      ElementPropertiesSchemaModel model = ElementPropertiesSchemaModelBuilder.build(trimmed, schemaRefResolver);
      JsonNode schema = model.propertyDefs().get(propertyKey);
      if (schema == null || schema.isMissingNode()) {
        return rawValue;
      }
      JsonNode elementRoot = schemaRefResolver.loadDocumentRoot(model.elementDocumentUri());
      String documentUri = model.elementDocumentUri();
      Optional<Object> coerced = coerceBySchema(rawValue, schema, schemaRefResolver, elementRoot, documentUri);
      if (coerced.isPresent()) {
        return coerced.get();
      }
      Optional<Object> structured = coerceStructuredJson(rawValue, schema, schemaRefResolver, elementRoot, documentUri);
      if (structured.isPresent()) {
        return structured.get();
      }
      return rawValue;
    } catch (Exception e) {
      return rawValue;
    }
  }

  private record DefaultsMergeResult(String mergedPatchJson, ArrayNode appliedKeys) {
  }

  private record CoercedValue(Object value) {
  }

  private static final Pattern INTEGER_PATTERN = Pattern.compile("-?\\d+");

  private static final Pattern NUMBER_PATTERN = Pattern.compile("-?(?:\\d+\\.?\\d*|\\d*\\.\\d+)");

  private static Optional<Object> coerceBySchema(
      String rawValue,
      JsonNode schema,
      SchemaRefResolver resolver,
      JsonNode documentRoot,
      String documentUri) {
    if (rawValue == null) {
      return Optional.empty();
    }
    CoercedValue coerced = coerceBySchema(rawValue.trim(), schema, resolver, documentRoot, documentUri,
        new ArrayDeque<>());
    return coerced != null ? Optional.of(coerced.value()) : Optional.empty();
  }

  private static CoercedValue coerceBySchema(
      String rawValue,
      JsonNode schema,
      SchemaRefResolver resolver,
      JsonNode documentRoot,
      String documentUri,
      Deque<String> refStack) {
    JsonNode s = derefOne(schema, resolver, documentRoot, documentUri, refStack);
    if (s == null || s.isMissingNode() || s.isNull()) {
      return null;
    }
    if (s.has(KEY_ONE_OF) && s.get(KEY_ONE_OF).isArray()) {
      for (JsonNode option : s.get(KEY_ONE_OF)) {
        CoercedValue coerced = coerceBySchema(rawValue, option, resolver, documentRoot, documentUri, refStack);
        if (coerced != null) {
          return coerced;
        }
      }
      return null;
    }
    if (!s.has("type") || !s.get("type").isTextual()) {
      return null;
    }
    return coerceScalar(rawValue, s.get("type").asText());
  }

  private static CoercedValue coerceScalar(String rawValue, String type) {
    return switch (type) {
      case "boolean" -> coerceBoolean(rawValue);
      case "integer" -> coerceInteger(rawValue);
      case "number" -> coerceNumber(rawValue);
      default -> null;
    };
  }

  private static CoercedValue coerceBoolean(String rawValue) {
    if ("true".equalsIgnoreCase(rawValue) || "false".equalsIgnoreCase(rawValue)) {
      return new CoercedValue(Boolean.valueOf(rawValue));
    }
    return null;
  }

  private static CoercedValue coerceInteger(String rawValue) {
    if (!INTEGER_PATTERN.matcher(rawValue).matches()) {
      return null;
    }
    try {
      return new CoercedValue(Long.valueOf(rawValue));
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static CoercedValue coerceNumber(String rawValue) {
    if (!NUMBER_PATTERN.matcher(rawValue).matches()) {
      return null;
    }
    try {
      return new CoercedValue(Double.valueOf(rawValue));
    } catch (NumberFormatException e) {
      return null;
    }
  }

  /**
   * Parses JSON array/object text in
   * {@link org.qubership.integration.platform.ai.plan.model.PlanProperty}
   * values when the property schema type is {@code array} or {@code object}.
   */
  private Optional<Object> coerceStructuredJson(
      String rawValue,
      JsonNode schema,
      SchemaRefResolver resolver,
      JsonNode documentRoot,
      String documentUri) {
    if (rawValue == null || rawValue.isBlank()) {
      return Optional.empty();
    }
    String trimmed = rawValue.trim();
    if (!trimmed.startsWith("[") && !trimmed.startsWith("{")) {
      return Optional.empty();
    }
    try {
      JsonNode parsed = objectMapper.readTree(trimmed);
      if (!matchesStructuredSchema(parsed, schema, resolver, documentRoot, documentUri, new ArrayDeque<>())) {
        return Optional.empty();
      }
      if (parsed.isArray()) {
        return Optional.of(objectMapper.convertValue(parsed, new TypeReference<List<Object>>() {
        }));
      }
      if (parsed.isObject()) {
        return Optional.of(objectMapper.convertValue(parsed, new TypeReference<Map<String, Object>>() {
        }));
      }
      return Optional.empty();
    } catch (JsonProcessingException e) {
      return Optional.empty();
    }
  }

  private static boolean matchesStructuredSchema(
      JsonNode parsed,
      JsonNode schema,
      SchemaRefResolver resolver,
      JsonNode documentRoot,
      String documentUri,
      Deque<String> refStack) {
    JsonNode s = derefOne(schema, resolver, documentRoot, documentUri, refStack);
    if (s == null || s.isMissingNode() || s.isNull()) {
      return false;
    }
    if (s.has(KEY_ONE_OF) && s.get(KEY_ONE_OF).isArray()) {
      for (JsonNode option : s.get(KEY_ONE_OF)) {
        if (matchesStructuredSchema(parsed, option, resolver, documentRoot, documentUri, refStack)) {
          return true;
        }
      }
      return false;
    }
    if (!s.has("type") || !s.get("type").isTextual()) {
      return false;
    }
    return switch (s.get("type").asText()) {
      case "array" -> parsed.isArray();
      case "object" -> parsed.isObject();
      default -> false;
    };
  }

  /**
   * For a JSON object patch, copies it and fills missing or null
   * {@code properties.*} entries that
   * are unconditionally required and have a schema {@code default} (see {@link
   * ElementPatchDefaultsApplicator}).
   */
  private DefaultsMergeResult mergeDefaultsIntoPatchCopy(
      String patchJson, ElementPropertiesSchemaModel model) throws JsonProcessingException {
    ArrayNode emptyApplied = objectMapper.createArrayNode();
    if (patchJson == null || patchJson.isBlank()) {
      return new DefaultsMergeResult(patchJson, emptyApplied);
    }
    JsonNode root = objectMapper.readTree(patchJson);
    if (!root.isObject()) {
      return new DefaultsMergeResult(patchJson, emptyApplied);
    }
    ObjectNode working = (ObjectNode) root.deepCopy();
    ArrayNode applied = objectMapper.createArrayNode();
    ElementPatchDefaultsApplicator.applyMissingPropertyDefaults(
        working, model, schemaRefResolver, objectMapper, applied);
    return new DefaultsMergeResult(objectMapper.writeValueAsString(working), applied);
  }

  private static String normalizePropertyPath(String propertyPath) {
    String p = propertyPath.trim();
    String prefix = KEY_PROPERTIES + ".";
    if (p.startsWith(prefix)) {
      p = p.substring(prefix.length());
    }
    return p;
  }

  private static JsonNode derefOne(
      JsonNode schema,
      SchemaRefResolver resolver,
      JsonNode documentRoot,
      String documentUri,
      Deque<String> refStack) {
    if (schema != null && schema.isObject() && schema.has("$ref") && schema.size() == 1) {
      return resolver.dereference(documentRoot, documentUri, schema, refStack);
    }
    return schema;
  }

  private static JsonNode navigatePropertySchema(
      JsonNode schema,
      String segment,
      SchemaRefResolver resolver,
      JsonNode documentRoot,
      String documentUri,
      Deque<String> refStack) {
    JsonNode s = derefOne(schema, resolver, documentRoot, documentUri, refStack);
    if (s == null || s.isMissingNode()) {
      return s;
    }
    if (s.has(KEY_PROPERTIES)
        && s.get(KEY_PROPERTIES).isObject()
        && s.get(KEY_PROPERTIES).has(segment)) {
      return s.get(KEY_PROPERTIES).get(segment);
    }
    if (s.has("items")) {
      return navigatePropertySchema(
          s.get("items"), segment, resolver, documentRoot, documentUri, refStack);
    }
    if (s.has(KEY_ONE_OF) && s.get(KEY_ONE_OF).isArray()) {
      for (JsonNode option : s.get(KEY_ONE_OF)) {
        JsonNode hit = navigatePropertySchema(option, segment, resolver, documentRoot, documentUri, refStack);
        if (hit != null && !hit.isMissingNode()) {
          return hit;
        }
      }
    }
    return MissingNode.getInstance();
  }

  private String errorJson(String message) {
    try {
      return objectMapper.writeValueAsString(Map.of("error", message));
    } catch (JsonProcessingException e) {
      return "{\"error\":\"serialization_failed\"}";
    }
  }
}
