package org.qubership.integration.platform.ai.schema;

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
import java.util.Map;

/** Deterministic QIP element schema access for LLM tools (classpath YAML, not RAG). */
@ApplicationScoped
public class DeterministicElementSchemaService {

  private static final int MAX_DESCRIPTION = 240;

  @Inject SchemaResourceLoader schemaResourceLoader;

  @Inject QipSchemaYamlParser qipSchemaYamlParser;

  @Inject SchemaRefResolver schemaRefResolver;

  @Inject ObjectMapper objectMapper;

  @Inject ElementPatchValidationRouter elementPatchValidationRouter;

  public String describeElementPatchSchema(String elementType) {
    try {
      if (elementType == null || elementType.isBlank()) {
        return errorJson("elementType is required");
      }
      String trimmed = elementType.trim();
      if (!schemaResourceLoader.existsElementSchema(trimmed)) {
        return errorJson("Schema not found for element type: " + trimmed);
      }
      ElementPropertiesSchemaModel model =
          ElementPropertiesSchemaModelBuilder.build(trimmed, schemaRefResolver);
      ObjectNode out = objectMapper.createObjectNode();
      out.put("elementType", model.elementType());
      out.put("elementDocumentUri", model.elementDocumentUri());
      out.set("patchShape", ElementSchemaCompactFormatter.patchShapeSummary());
      out.set("requiredProperties", objectMapper.valueToTree(model.unconditionalRequired()));
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
      out.set("properties", props);
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
        return errorJson("elementType is required");
      }
      if (propertyPath == null || propertyPath.isBlank()) {
        return errorJson("propertyPath is required");
      }
      String trimmed = elementType.trim();
      if (!schemaResourceLoader.existsElementSchema(trimmed)) {
        return errorJson("Schema not found for element type: " + trimmed);
      }
      ElementPropertiesSchemaModel model =
          ElementPropertiesSchemaModelBuilder.build(trimmed, schemaRefResolver);
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
        schema =
            navigatePropertySchema(
                schema, segments[i], schemaRefResolver, elementRoot, docUri, refStack);
        if (schema == null || schema.isMissingNode()) {
          return errorJson("Path not found at segment: " + segments[i]);
        }
      }
      ObjectNode out = objectMapper.createObjectNode();
      out.put("elementType", model.elementType());
      out.put("propertyPath", normalized);
      out.set(
          "schema",
          ElementSchemaCompactFormatter.summarizePropertySchema(
              schema, schemaRefResolver, elementRoot, docUri, MAX_DESCRIPTION, 8));
      return objectMapper.writeValueAsString(out);
    } catch (SchemaNotFoundException | SchemaRefResolutionException ex) {
      return errorJson(ex.getMessage());
    } catch (JsonProcessingException e) {
      return errorJson("Failed to serialize property schema: " + e.getOriginalMessage());
    } catch (Exception e) {
      return errorJson("describeElementProperty failed: " + e.getMessage());
    }
  }

  public String validateElementPatch(String elementType, String patchJson) {
    try {
      if (elementType == null || elementType.isBlank()) {
        return errorJson("elementType is required");
      }
      String trimmed = elementType.trim();
      if (!schemaResourceLoader.existsElementSchema(trimmed)) {
        return errorJson("Schema not found for element type: " + trimmed);
      }
      ElementPropertiesSchemaModel model =
          ElementPropertiesSchemaModelBuilder.build(trimmed, schemaRefResolver);
      DefaultsMergeResult merged = mergeDefaultsIntoPatchCopy(patchJson, model);
      ObjectNode result =
          elementPatchValidationRouter.validate(
              trimmed, merged.mergedPatchJson(), model, schemaRefResolver);
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

  /**
   * Keys allowed under {@code properties} by embedded JSON Schema. Used to intersect catalog
   * descriptor defaults so YAML-only properties are not injected before schema validation.
   */
  public java.util.Set<String> allowedPatchPropertyKeys(String elementType) {
    if (elementType == null || elementType.isBlank()) {
      return java.util.Set.of();
    }
    String trimmed = elementType.trim();
    if (!schemaResourceLoader.existsElementSchema(trimmed)) {
      return java.util.Set.of();
    }
    ElementPropertiesSchemaModel model =
        ElementPropertiesSchemaModelBuilder.build(trimmed, schemaRefResolver);
    return java.util.Set.copyOf(model.propertyDefs().keySet());
  }

  private record DefaultsMergeResult(String mergedPatchJson, ArrayNode appliedKeys) {}

  /**
   * For a JSON object patch, copies it and fills missing or null {@code properties.*} entries that
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
    if (p.startsWith("properties.")) {
      p = p.substring("properties.".length());
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
    if (s.has("properties") && s.get("properties").isObject() && s.get("properties").has(segment)) {
      return s.get("properties").get(segment);
    }
    if (s.has("items")) {
      return navigatePropertySchema(
          s.get("items"), segment, resolver, documentRoot, documentUri, refStack);
    }
    if (s.has("oneOf") && s.get("oneOf").isArray()) {
      for (JsonNode option : s.get("oneOf")) {
        JsonNode hit =
            navigatePropertySchema(option, segment, resolver, documentRoot, documentUri, refStack);
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
