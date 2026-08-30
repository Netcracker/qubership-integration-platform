package org.qubership.integration.platform.ai.productpipeline.recovery;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.schema.DeterministicElementSchemaService;
import org.qubership.integration.platform.ai.schema.ElementPatchDefaultsApplicator;
import org.qubership.integration.platform.ai.schema.ElementPatchValidator;
import org.qubership.integration.platform.ai.schema.ElementPropertiesSchemaModel;
import org.qubership.integration.platform.ai.schema.ElementPropertiesSchemaModelBuilder;
import org.qubership.integration.platform.ai.schema.SchemaRefResolver;
import org.qubership.integration.platform.ai.schema.SchemaResourceLoader;

/** Builds lossless semantic findings from element patch validator JSON. */
public final class RecoveryEvidenceFactory {

  private static final String CODE_MISSING_REQUIRED = "MISSING_REQUIRED_PROPERTY";
  private static final String UNKNOWN_PROPERTY_PREFIX = "properties.";
  private static final String FIELD_ERRORS = "errors";
  private static final String FIELD_MISSING_REQUIRED = "missingRequired";

  private RecoveryEvidenceFactory() {}

  /**
   * @param observingStageId reserved for {@code RecoveryEvidence.observingStageId} in a later task
   */
  @SuppressWarnings("java:S1172")
  public static SemanticFinding fromElementValidation(
      ChainPlanNode node,
      String validationJson,
      String observingStageId,
      ObjectMapper objectMapper,
      SchemaRefResolver schemaRefResolver) {
    JsonNode root = parseRoot(validationJson, objectMapper);
    if (root == null) {
      root = objectMapper.createObjectNode();
    }
    String rawValidatorJson = rawValidatorJson(validationJson, root, objectMapper);
    List<String> missingKeys = collectMissingKeys(root);
    List<String> unexpectedKeys = collectUnexpectedKeys(root);
    List<String> oneOfBranchHints = collectOneOfBranchHints(root);
    String code = resolveCode(root, missingKeys);
    String violatedRule = resolveViolatedRule(root);

    String nodeId = node == null || node.nodeId() == null ? "" : node.nodeId();
    String elementType = node == null || node.type() == null ? "" : node.type().trim();
    List<String> presentKeys = collectPresentKeys(node);
    Map<String, String> schemaDefaults =
        collectSchemaDefaults(elementType, missingKeys, objectMapper, schemaRefResolver);

    return new SemanticFinding(
        code,
        violatedRule,
        nodeId,
        nodeId,
        elementType,
        missingKeys,
        unexpectedKeys,
        oneOfBranchHints,
        "",
        schemaDefaults,
        presentKeys,
        rawValidatorJson);
  }

  static SemanticFinding fromElementValidation(
      ChainPlanNode node, JsonNode validationRoot, ObjectMapper objectMapper, SchemaRefResolver resolver) {
    try {
      return fromElementValidation(
          node, objectMapper.writeValueAsString(validationRoot), "", objectMapper, resolver);
    } catch (JsonProcessingException e) {
      return fromElementValidation(node, "{}", "", objectMapper, resolver);
    }
  }

  /** Element-level semantic findings for each invalid node in a rejected plan graph. */
  public static List<SemanticFinding> findingsFromChainPlanGraph(
      ChainPlanGraph graph,
      String observingStageId,
      ObjectMapper objectMapper,
      SchemaRefResolver schemaRefResolver,
      DeterministicElementSchemaService deterministicElementSchemaService,
      SchemaResourceLoader schemaResourceLoader) {
    if (graph == null || graph.nodes() == null || graph.nodes().isEmpty()) {
      return List.of();
    }
    List<SemanticFinding> findings = new ArrayList<>();
    for (ChainPlanNode node : graph.nodes()) {
      if (node == null || node.type() == null || node.type().isBlank()) {
        continue;
      }
      String elementType = node.type().trim();
      if (schemaResourceLoader == null || !schemaResourceLoader.existsElementSchema(elementType)) {
        continue;
      }
      String patchJson =
          toPropertiesPatchJson(
              elementType,
              node.properties(),
              objectMapper,
              deterministicElementSchemaService,
              schemaRefResolver);
      var model = ElementPropertiesSchemaModelBuilder.build(elementType, schemaRefResolver);
      JsonNode result =
          ElementPatchValidator.validate(patchJson, model, schemaRefResolver, objectMapper);
      if (!result.path("valid").asBoolean(true)) {
        findings.add(
            fromElementValidation(
                node, result.toString(), observingStageId, objectMapper, schemaRefResolver));
      }
    }
    return List.copyOf(findings);
  }

  private static String toPropertiesPatchJson(
      String elementType,
      List<PlanProperty> properties,
      ObjectMapper objectMapper,
      DeterministicElementSchemaService deterministicElementSchemaService,
      SchemaRefResolver schemaRefResolver) {
    try {
      var root = objectMapper.createObjectNode();
      var props = objectMapper.createObjectNode();
      if (properties != null) {
        for (PlanProperty property : properties) {
          if (property == null || property.key() == null || property.key().isBlank()) {
            continue;
          }
          Object coerced =
              deterministicElementSchemaService.coercePatchPropertyValue(
                  elementType, property.key(), property.value());
          props.set(property.key(), objectMapper.valueToTree(coerced));
        }
      }
      root.set("properties", props);
      if (elementType != null && !elementType.isBlank()) {
        var model = ElementPropertiesSchemaModelBuilder.build(elementType.trim(), schemaRefResolver);
        ElementPatchDefaultsApplicator.applyMissingPropertyDefaults(
            root, model, schemaRefResolver, objectMapper, null);
      }
      return objectMapper.writeValueAsString(root);
    } catch (Exception e) {
      return "{\"properties\":{}}";
    }
  }

  private static JsonNode parseRoot(String validationJson, ObjectMapper objectMapper) {
    if (validationJson == null || validationJson.isBlank()) {
      return objectMapper.createObjectNode();
    }
    try {
      return objectMapper.readTree(validationJson);
    } catch (JsonProcessingException e) {
      return objectMapper.createObjectNode();
    }
  }

  private static String rawValidatorJson(
      String validationJson, JsonNode root, ObjectMapper objectMapper) {
    if (validationJson == null || validationJson.isBlank()) {
      return "{}";
    }
    if (root == null || root.isEmpty()) {
      return validationJson;
    }
    try {
      return objectMapper.writeValueAsString(root);
    } catch (JsonProcessingException e) {
      return validationJson;
    }
  }

  private static List<String> collectMissingKeys(JsonNode root) {
    LinkedHashSet<String> keys = new LinkedHashSet<>();
    addTextArrayToSet(root.path(FIELD_MISSING_REQUIRED), keys);
    JsonNode errors = root.path(FIELD_ERRORS);
    if (errors.isArray()) {
      for (JsonNode error : errors) {
        addTextArrayToSet(error.get("missingProperties"), keys);
      }
    }
    return List.copyOf(keys);
  }

  private static void addTextArrayToSet(JsonNode array, LinkedHashSet<String> keys) {
    if (array == null || !array.isArray()) {
      return;
    }
    array.forEach(
        node -> {
          if (node.isTextual() && !node.asText().isBlank()) {
            keys.add(node.asText());
          }
        });
  }

  private static List<String> collectUnexpectedKeys(JsonNode root) {
    List<String> keys = new ArrayList<>();
    JsonNode errors = root.path(FIELD_ERRORS);
    if (!errors.isArray()) {
      return List.of();
    }
    for (JsonNode error : errors) {
      String message = error.path("message").asText("");
      if (!message.contains("Unknown property")) {
        continue;
      }
      String path = error.path("path").asText("");
      if (path.startsWith(UNKNOWN_PROPERTY_PREFIX)) {
        keys.add(path.substring(UNKNOWN_PROPERTY_PREFIX.length()));
      }
    }
    return List.copyOf(keys);
  }

  private static List<String> collectOneOfBranchHints(JsonNode root) {
    LinkedHashSet<String> hints = new LinkedHashSet<>();
    JsonNode errors = root.path(FIELD_ERRORS);
    if (!errors.isArray()) {
      return List.of();
    }
    for (JsonNode error : errors) {
      JsonNode branchHints = error.get("oneOfBranchHints");
      if (branchHints == null || !branchHints.isArray()) {
        continue;
      }
      branchHints.forEach(
          node -> {
            if (node.isTextual() && !node.asText().isBlank()) {
              hints.add(node.asText());
            }
          });
    }
    return List.copyOf(hints);
  }

  private static String resolveCode(JsonNode root, List<String> missingKeys) {
    JsonNode errors = root.path(FIELD_ERRORS);
    if (errors.isArray()) {
      for (JsonNode error : errors) {
        String code = error.path("code").asText("");
        if (!code.isBlank()) {
          return code;
        }
      }
    }
    if (!missingKeys.isEmpty()) {
      return CODE_MISSING_REQUIRED;
    }
    return "";
  }

  private static String resolveViolatedRule(JsonNode root) {
    JsonNode errors = root.path(FIELD_ERRORS);
    if (!errors.isArray() || errors.isEmpty()) {
      return "";
    }
    JsonNode first = errors.get(0);
    String schemaGroup = first.path("schemaGroup").asText("");
    if (!schemaGroup.isBlank()) {
      return schemaGroup;
    }
    return first.path("path").asText("");
  }

  private static List<String> collectPresentKeys(ChainPlanNode node) {
    if (node == null || node.properties() == null) {
      return List.of();
    }
    List<String> keys = new ArrayList<>();
    for (PlanProperty property : node.properties()) {
      if (property == null || property.key() == null || property.key().isBlank()) {
        continue;
      }
      keys.add(property.key());
    }
    return List.copyOf(keys);
  }

  private static Map<String, String> collectSchemaDefaults(
      String elementType,
      List<String> missingKeys,
      ObjectMapper objectMapper,
      SchemaRefResolver schemaRefResolver) {
    if (elementType == null
        || elementType.isBlank()
        || missingKeys.isEmpty()
        || schemaRefResolver == null) {
      return Map.of();
    }
    ElementPropertiesSchemaModel model;
    try {
      model = ElementPropertiesSchemaModelBuilder.build(elementType, schemaRefResolver);
    } catch (RuntimeException e) {
      return Map.of();
    }
    JsonNode elementRoot = schemaRefResolver.loadDocumentRoot(model.elementDocumentUri());
    String docUri = model.elementDocumentUri();
    Map<String, String> defaults = new LinkedHashMap<>();
    for (String key : missingKeys) {
      JsonNode schema = model.propertyDefs().get(key);
      if (schema != null) {
        JsonNode defaultValue =
            ElementPatchDefaultsApplicator.schemaDefault(
                schema, schemaRefResolver, elementRoot, docUri);
        if (defaultValue != null && !defaultValue.isNull()) {
          defaults.put(key, jsonValueToString(defaultValue, objectMapper));
        }
      }
    }
    return Map.copyOf(defaults);
  }

  private static String jsonValueToString(JsonNode value, ObjectMapper objectMapper) {
    if (value == null || value.isNull()) {
      return "";
    }
    if (value.isTextual()) {
      return value.asText();
    }
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      return value.toString();
    }
  }
}
