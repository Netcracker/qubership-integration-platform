package org.qubership.integration.platform.ai.schema;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Set;

/** Validates element PATCH JSON using networknt/json-schema-validator (draft-07). */
@ApplicationScoped
public class NetworkntElementPatchValidator {

  private final ElementPatchJsonSchemaCompiler schemaCompiler;
  private final ObjectMapper objectMapper;

  @Inject
  public NetworkntElementPatchValidator(
      ElementPatchJsonSchemaCompiler schemaCompiler, ObjectMapper objectMapper) {
    this.schemaCompiler = schemaCompiler;
    this.objectMapper = objectMapper;
  }

  public ObjectNode validate(
      String elementType,
      String patchJson,
      ElementPropertiesSchemaModel model,
      SchemaRefResolver schemaRefResolver) {
    ArrayNode errors = objectMapper.createArrayNode();
    ArrayNode warnings = objectMapper.createArrayNode();
    ArrayNode missingRequired = objectMapper.createArrayNode();

    if (patchJson == null || patchJson.isBlank()) {
      return invalidResult(errors, warnings, missingRequired, "", "patchJson is required");
    }

    JsonNode patch;
    try {
      patch = objectMapper.readTree(patchJson);
    } catch (JsonProcessingException e) {
      return invalidResult(
          errors, warnings, missingRequired, "", "Invalid JSON: " + e.getOriginalMessage());
    }

    if (!patch.isObject()) {
      return invalidResult(
          errors, warnings, missingRequired, "", "patchJson must be a JSON object");
    }

    ObjectNode patchObj = (ObjectNode) patch;
    for (String key : iterableFieldNames(patchObj)) {
      if (!ElementPatchJsonSchemaCompiler.PATCH_TOP_LEVEL_KEYS.contains(key)) {
        errors.add(
            errorNode(
                key,
                "Unknown top-level field (allowed: "
                    + ElementPatchJsonSchemaCompiler.PATCH_TOP_LEVEL_KEYS
                    + ")"));
      }
    }

    if (!patchObj.has("properties")) {
      warnings.add(warningNode("", "properties key is missing"));
    }

    JsonNode props = patchObj.path("properties");
    if (patchObj.has("properties") && !props.isObject()) {
      return invalidResult(
          errors, warnings, missingRequired, "properties", "properties must be a JSON object");
    }

    if (!errors.isEmpty()) {
      return result(false, errors, warnings, missingRequired);
    }

    for (String w : model.warnings()) {
      warnings.add(warningNode("", w));
    }
    warnings.add(
        warningNode(
            "",
            "Runtime catalog remains the final authority; unsupported rules may still require"
                + " catalog_validation_required."));

    try {
      if (props.isObject()) {
        JsonNode propertiesSchema = schemaCompiler.inlinedPropertiesSchema(elementType);
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);
        SchemaValidatorsConfig config = SchemaValidatorsConfig.builder().build();
        JsonSchema jsonSchema = factory.getSchema(propertiesSchema, config);
        Set<ValidationMessage> messages = jsonSchema.validate(props);
        appendNetworkntMessages(messages, errors, missingRequired);
      }
      for (String req : model.unconditionalRequired()) {
        if (props.isObject() && (!props.has(req) || props.get(req).isNull())) {
          if (!containsMissing(missingRequired, req)) {
            missingRequired.add(req);
            errors.add(errorNode("properties." + req, "Required property is missing"));
          }
        }
      }
      if (!errors.isEmpty()) {
        return result(false, errors, warnings, missingRequired);
      }
    } catch (RuntimeException ex) {
      return ElementPatchValidator.validate(patchJson, model, schemaRefResolver, objectMapper);
    }

    boolean valid = errors.isEmpty();
    return result(valid, errors, warnings, missingRequired);
  }

  private void appendNetworkntMessages(
      Set<ValidationMessage> messages, ArrayNode errors, ArrayNode missingRequired) {
    for (ValidationMessage message : messages) {
      String path = message.getInstanceLocation().toString();
      if (path.startsWith("/")) {
        path = path.substring(1);
      }
      if (path.isEmpty()) {
        path = message.getProperty() != null ? message.getProperty() : "";
      }
      if (!path.isBlank() && !path.startsWith("properties.")) {
        path = "properties." + path;
      }
      String text = message.getMessage();
      if (text != null && text.toLowerCase().contains("required")) {
        String field = lastSegment(path);
        if (!field.isBlank()) {
          missingRequired.add(field);
        }
      }
      errors.add(errorNode(path, text != null ? text : "Schema validation failed"));
    }
  }

  private static boolean containsMissing(ArrayNode missingRequired, String field) {
    for (JsonNode n : missingRequired) {
      if (n.isTextual() && field.equals(n.asText())) {
        return true;
      }
    }
    return false;
  }

  private static Iterable<String> iterableFieldNames(ObjectNode patchObj) {
    return () -> patchObj.fieldNames();
  }

  private static String lastSegment(String path) {
    if (path == null || path.isBlank()) {
      return "";
    }
    int slash = path.lastIndexOf('/');
    return slash >= 0 ? path.substring(slash + 1) : path;
  }

  private ObjectNode invalidResult(
      ArrayNode errors,
      ArrayNode warnings,
      ArrayNode missingRequired,
      String path,
      String message) {
    errors.add(errorNode(path, message));
    return result(false, errors, warnings, missingRequired);
  }

  private ObjectNode errorNode(String path, String message) {
    return objectMapper.createObjectNode().put("path", path).put("message", message);
  }

  private ObjectNode warningNode(String path, String message) {
    return objectMapper.createObjectNode().put("path", path).put("message", message);
  }

  private ObjectNode result(
      boolean valid, ArrayNode errors, ArrayNode warnings, ArrayNode missingRequired) {
    ObjectNode root = objectMapper.createObjectNode();
    root.put("valid", valid);
    root.set("errors", errors);
    root.set("warnings", warnings);
    root.set("missingRequired", missingRequired);
    return root;
  }
}
