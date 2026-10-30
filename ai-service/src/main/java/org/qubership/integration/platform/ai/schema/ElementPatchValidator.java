package org.qubership.integration.platform.ai.schema;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validates patch JSON against QIP schemas and Simple predicate syntax without runtime evaluation.
 */
public final class ElementPatchValidator {

  private static final Set<String> PATCH_TOP_LEVEL_KEYS =
      Set.of(
          "name",
          "description",
          "type",
          "parentElementId",
          "swimlaneId",
          "mandatoryChecksPassed",
          "properties");

  private ElementPatchValidator() {}

  public static ObjectNode validate(
      String patchJson,
      ElementPropertiesSchemaModel model,
      SchemaRefResolver resolver,
      ObjectMapper objectMapper) {
    ObjectNode root = objectMapper.createObjectNode();
    ArrayNode errors = objectMapper.createArrayNode();
    ArrayNode warnings = objectMapper.createArrayNode();
    ArrayNode missingRequired = objectMapper.createArrayNode();

    if (patchJson == null || patchJson.isBlank()) {
      root.put("valid", false);
      errors.add(
          objectMapper.createObjectNode().put("path", "").put("message", "patchJson is required"));
      root.set("errors", errors);
      root.set("warnings", warnings);
      root.set("missingRequired", missingRequired);
      return root;
    }

    JsonNode patch;
    try {
      patch = objectMapper.readTree(patchJson);
    } catch (JsonProcessingException e) {
      root.put("valid", false);
      errors.add(
          objectMapper
              .createObjectNode()
              .put("path", "")
              .put("message", "Invalid JSON: " + e.getOriginalMessage()));
      root.set("errors", errors);
      root.set("warnings", warnings);
      root.set("missingRequired", missingRequired);
      return root;
    }

    if (!patch.isObject()) {
      root.put("valid", false);
      errors.add(
          objectMapper
              .createObjectNode()
              .put("path", "")
              .put("message", "patchJson must be a JSON object"));
      root.set("errors", errors);
      root.set("warnings", warnings);
      root.set("missingRequired", missingRequired);
      return root;
    }

    ObjectNode patchObj = (ObjectNode) patch;
    Iterator<String> topNames = patchObj.fieldNames();
    while (topNames.hasNext()) {
      String k = topNames.next();
      if (!PATCH_TOP_LEVEL_KEYS.contains(k)) {
        errors.add(
            objectMapper
                .createObjectNode()
                .put("path", k)
                .put("message", "Unknown top-level field (allowed: " + PATCH_TOP_LEVEL_KEYS + ")"));
      }
    }

    if (!patchObj.has("properties")) {
      warnings.add(
          objectMapper
              .createObjectNode()
              .put("path", "")
              .put("message", "properties key is missing"));
    }

    JsonNode props = patchObj.path("properties");
    if (patchObj.has("properties") && !props.isObject()) {
      errors.add(
          objectMapper
              .createObjectNode()
              .put("path", "properties")
              .put("message", "properties must be a JSON object"));
    }

    if (!errors.isEmpty()) {
      root.put("valid", false);
      root.set("errors", errors);
      root.set("warnings", warnings);
      root.set("missingRequired", missingRequired);
      return root;
    }

    for (String w : model.warnings()) {
      if (isRedundantIfThenCatalogWarning(w, model)) {
        continue;
      }
      warnings.add(objectMapper.createObjectNode().put("path", "").put("message", w));
    }
    warnings.add(
        objectMapper
            .createObjectNode()
            .put("path", "")
            .put(
                "message",
                "Runtime catalog remains the final authority; unsupported rules may still require"
                    + " catalog_validation_required."));

    if (props.isObject()) {
      validatePropertiesAgainstModel(
          objectMapper, (ObjectNode) props, model, resolver, errors, missingRequired);
      props.fields().forEachRemaining(property ->
          CamelSimplePredicateValidator.validateProperty(model.elementType(), property.getKey(), property.getValue())
              .ifPresent(error -> errors.add(objectMapper.createObjectNode()
                  .put("path", "properties." + property.getKey()).put("message", error))));
    }

    boolean valid = errors.isEmpty();
    root.put("valid", valid);
    root.set("errors", errors);
    root.set("warnings", warnings);
    root.set("missingRequired", missingRequired);
    return root;
  }

  private static void validatePropertiesAgainstModel(
      ObjectMapper objectMapper,
      ObjectNode patchProps,
      ElementPropertiesSchemaModel model,
      SchemaRefResolver resolver,
      ArrayNode errors,
      ArrayNode missingRequired) {
    Set<String> allowed = new LinkedHashSet<>(model.propertyDefs().keySet());
    Iterator<String> it = patchProps.fieldNames();
    while (it.hasNext()) {
      String key = it.next();
      if (!allowed.contains(key)) {
        errors.add(
            objectMapper
                .createObjectNode()
                .put("path", "properties." + key)
                .put("message", "Unknown property for element type " + model.elementType()));
      }
    }

    JsonNode elementRoot = resolver.loadDocumentRoot(model.elementDocumentUri());
    Deque<String> refStack = new ArrayDeque<>();

    Map<String, String> asText = new LinkedHashMap<>();
    patchProps.fields().forEachRemaining(e -> asText.put(e.getKey(), textOrEmpty(e.getValue())));
    for (String req : model.requiredKeysFor(asText)) {
      if (!patchProps.has(req) || patchProps.get(req).isNull()) {
        missingRequired.add(req);
        errors.add(
            objectMapper
                .createObjectNode()
                .put("path", "properties." + req)
                .put("message", "Required property is missing"));
      }
    }

    for (JsonNode rootOneOf : model.rootOneOfGroups()) {
      Set<String> footprint = rootFragmentFootprint(rootOneOf);
      if (footprint.isEmpty()) {
        continue;
      }
      if (!intersects(patchProps, footprint)) {
        continue;
      }
      String groupUri =
          rootOneOf.has("$id") && rootOneOf.get("$id").isTextual()
              ? CipConfModelUris.stripFragment(rootOneOf.get("$id").asText())
              : model.elementDocumentUri();
      JsonNode groupRoot = resolver.loadDocumentRoot(groupUri);
      OneOfGroupEvaluation evaluation =
          evaluateOneOfPlusSiblingRequired(
              patchProps, rootOneOf, resolver, groupRoot, groupUri, refStack);
      if (!evaluation.matched()) {
        ObjectNode err =
            objectMapper
                .createObjectNode()
                .put("path", "properties")
                .put("code", "ONEOF_GROUP_MISMATCH")
                .put("message", evaluation.summaryMessage());
        if (evaluation.schemaGroupLabel() != null) {
          err.put("schemaGroup", evaluation.schemaGroupLabel());
        }
        ArrayNode missingProps = objectMapper.createArrayNode();
        for (String k : evaluation.missingProperties()) {
          missingProps.add(k);
        }
        err.set("missingProperties", missingProps);
        if (!evaluation.branchHints().isEmpty()) {
          ArrayNode hints = objectMapper.createArrayNode();
          for (String h : evaluation.branchHints()) {
            hints.add(h);
          }
          err.set("oneOfBranchHints", hints);
        }
        errors.add(err);
      }
    }

    JsonNode propertiesSchema = elementRoot.path("properties").path("properties");
    if (!propertiesSchema.isMissingNode()) {
      validateValueForSchema(
          objectMapper,
          "properties",
          patchProps,
          propertiesSchema,
          resolver,
          errors);
    }
  }

  private static boolean intersects(ObjectNode patchProps, Set<String> groupKeys) {
    Iterator<String> it = patchProps.fieldNames();
    while (it.hasNext()) {
      if (groupKeys.contains(it.next())) {
        return true;
      }
    }
    return false;
  }

  private static Set<String> rootFragmentFootprint(JsonNode fragment) {
    Set<String> keys = keysDeclaredUnderOneOf(fragment);
    if (fragment != null && fragment.has("properties") && fragment.get("properties").isObject()) {
      fragment.get("properties").fieldNames().forEachRemaining(keys::add);
    }
    return keys;
  }

  private record OneOfGroupEvaluation(
      boolean matched,
      String summaryMessage,
      String schemaGroupLabel,
      List<String> missingProperties,
      List<String> branchHints) {}

  private static OneOfGroupEvaluation evaluateOneOfPlusSiblingRequired(
      ObjectNode patchProps,
      JsonNode fragment,
      SchemaRefResolver resolver,
      JsonNode documentRoot,
      String documentUri,
      Deque<String> refStack) {
    if (fragment == null || !fragment.has("oneOf") || !fragment.get("oneOf").isArray()) {
      return new OneOfGroupEvaluation(true, "", null, List.of(), List.of());
    }
    String groupLabel = schemaGroupLabel(fragment);
    Set<String> triggerKeys = intersectingKeys(patchProps, rootFragmentFootprint(fragment));

    List<String> branchHints = new ArrayList<>();
    boolean anyBranch = false;
    int index = 0;
    for (JsonNode branch : fragment.get("oneOf")) {
      index++;
      if (resolver.validate(branch, patchProps).isEmpty()) {
        anyBranch = true;
        break;
      }
      List<String> missingInBranch = new ArrayList<>();
      collectMissingRequiredForBranch(
          patchProps, branch, resolver, documentRoot, documentUri, refStack, missingInBranch);
      if (missingInBranch.isEmpty()) {
        branchHints.add(
            "alternative " + index + ": did not match (check const values and nested oneOf)");
      } else {
        branchHints.add(
            "alternative " + index + " missing or invalid: " + String.join(", ", missingInBranch));
      }
    }

    if (!anyBranch) {
      String triggers = triggerKeys.isEmpty() ? "(none)" : String.join(", ", triggerKeys);
      return new OneOfGroupEvaluation(
          false,
          groupLabel
              + ": no oneOf alternative matched. Patch keys involved: "
              + triggers
              + ". See oneOfBranchHints for per-alternative missing fields.",
          groupLabel,
          List.of(),
          List.copyOf(branchHints));
    }

    List<String> missingSibling = new ArrayList<>();
    if (fragment.has("required") && fragment.get("required").isArray()) {
      for (JsonNode r : fragment.get("required")) {
        if (!r.isTextual()) {
          continue;
        }
        String name = r.asText();
        if (!patchProps.has(name) || patchProps.get(name).isNull()) {
          missingSibling.add(name);
        }
      }
    }
    if (!missingSibling.isEmpty()) {
      return new OneOfGroupEvaluation(
          false,
          groupLabel
              + ": a oneOf alternative matched but required properties are missing: "
              + String.join(", ", missingSibling)
              + ". Call describeElementPatchSchema and describeElementProperty for this element"
              + " type.",
          groupLabel,
          List.copyOf(missingSibling),
          List.copyOf(branchHints));
    }
    return new OneOfGroupEvaluation(true, "", groupLabel, List.of(), List.of());
  }

  private static Set<String> intersectingKeys(ObjectNode patchProps, Set<String> groupKeys) {
    Set<String> hit = new LinkedHashSet<>();
    Iterator<String> it = patchProps.fieldNames();
    while (it.hasNext()) {
      String key = it.next();
      if (groupKeys.contains(key)) {
        hit.add(key);
      }
    }
    return hit;
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
    return "operation configuration";
  }

  private static void collectMissingRequiredForBranch(
      ObjectNode patchProps,
      JsonNode branch,
      SchemaRefResolver resolver,
      JsonNode documentRoot,
      String documentUri,
      Deque<String> refStack,
      List<String> missingOut) {
    if (branch == null || !branch.isObject()) {
      return;
    }
    if (!partialConstPropertiesMatch(patchProps, branch)) {
      if (branch.has("properties") && branch.get("properties").isObject()) {
        branch
            .get("properties")
            .fields()
            .forEachRemaining(
                e -> {
                  JsonNode propSchema = e.getValue();
                  if (propSchema != null && propSchema.has("const") && patchProps.has(e.getKey())) {
                    if (!jsonEquals(patchProps.get(e.getKey()), propSchema.get("const"))) {
                      missingOut.add(e.getKey() + " (expected " + propSchema.get("const") + ")");
                    }
                  }
                });
      }
      return;
    }
    if (branch.has("required") && branch.get("required").isArray()) {
      for (JsonNode r : branch.get("required")) {
        if (!r.isTextual()) {
          continue;
        }
        String name = r.asText();
        if (!patchProps.has(name) || patchProps.get(name).isNull()) {
          if (!missingOut.contains(name)) {
            missingOut.add(name);
          }
        }
      }
    }
    if (branch.has("oneOf") && branch.get("oneOf").isArray()) {
      for (JsonNode inner : branch.get("oneOf")) {
        if (partialConstPropertiesMatch(patchProps, inner)) {
          collectMissingRequiredForBranch(
              patchProps, inner, resolver, documentRoot, documentUri, refStack, missingOut);
          return;
        }
      }
    }
  }

  /** True when every {@code const} on branch properties matches patch (or is absent in branch). */
  private static boolean partialConstPropertiesMatch(ObjectNode patchProps, JsonNode branch) {
    if (branch == null || !branch.has("properties") || !branch.get("properties").isObject()) {
      return true;
    }
    Iterator<Map.Entry<String, JsonNode>> it = branch.get("properties").properties().iterator();
    while (it.hasNext()) {
      Map.Entry<String, JsonNode> e = it.next();
      JsonNode propSchema = e.getValue();
      if (propSchema != null && propSchema.has("const")) {
        if (!patchProps.has(e.getKey())) {
          return false;
        }
        if (!jsonEquals(patchProps.get(e.getKey()), propSchema.get("const"))) {
          return false;
        }
      }
    }
    return true;
  }

  private static Set<String> keysDeclaredUnderOneOf(JsonNode rootOneOf) {
    Set<String> keys = new LinkedHashSet<>();
    if (rootOneOf == null || !rootOneOf.has("oneOf") || !rootOneOf.get("oneOf").isArray()) {
      return keys;
    }
    for (JsonNode branch : rootOneOf.get("oneOf")) {
      collectBranchPropertySchemasKeys(branch, keys);
    }
    return keys;
  }

  private static void collectBranchPropertySchemasKeys(JsonNode branch, Set<String> keys) {
    if (branch == null || !branch.isObject()) {
      return;
    }
    if (branch.has("oneOf") && branch.get("oneOf").isArray()) {
      for (JsonNode inner : branch.get("oneOf")) {
        collectBranchPropertySchemasKeys(inner, keys);
      }
    }
    if (branch.has("properties") && branch.get("properties").isObject()) {
      branch.get("properties").fieldNames().forEachRemaining(keys::add);
    }
  }

  /**
   * Validates a single captured property value against its element schema definition.
   *
   * @return human-readable error messages; empty when the value matches the schema
   */
  public static List<String> validatePropertyValue(
      String propertyPath,
      JsonNode value,
      JsonNode propertySchema,
      SchemaRefResolver resolver,
      JsonNode documentRoot,
      String documentUri,
      ObjectMapper objectMapper) {
    ArrayNode errors = objectMapper.createArrayNode();
    validateValueForSchema(
        objectMapper,
        propertyPath,
        value,
        propertySchema,
        resolver,
        errors);
    List<String> messages = new ArrayList<>();
    for (JsonNode error : errors) {
      if (error.has("message")) {
        messages.add(error.get("message").asText());
      }
    }
    return List.copyOf(messages);
  }

  /** Keeps native locations, values, and schema constraints intact for capture repair. */
  public static ArrayNode validatePropertyIssues(String propertyKey, JsonNode value,
      JsonNode schema, SchemaRefResolver resolver, ObjectMapper mapper) {
    ArrayNode errors = mapper.createArrayNode();
    for (var violation : resolver.validate(schema, value)) {
      String pointer = "";
      var location = violation.getInstanceLocation();
      for (int i = 0; i < location.getNameCount(); i++) {
        pointer += "/" + location.getName(i).replace("~", "~0").replace("/", "~1");
      }
      boolean missing = "required".equals(violation.getType());
      if (missing) {
        pointer += "/" + violation.getProperty().replace("~", "~0").replace("/", "~1");
      }
      ObjectNode error = errors.addObject();
      error.put("code", violation.getType());
      error.put("path", "/properties/" + propertyKey.replace("~", "~0").replace("/", "~1") + pointer);
      error.put("message", "enum".equals(violation.getType())
          ? "Value is not in the schema enum" : violation.getMessage());
      error.put("valueMissing", missing);
      error.set("rejectedValue", missing ? mapper.nullNode() : value.at(pointer));
      error.put("schemaLocation", violation.getSchemaLocation().toString());
      ObjectNode constraint = mapper.createObjectNode();
      constraint.set(violation.getType(), violation.getSchemaNode());
      error.set("constraint", constraint);
      var schemaLocation = violation.getSchemaLocation();
      JsonNode schemaRoot = schemaLocation.getAbsoluteIri() == null ? schema
          : resolver.loadDocumentRoot(schemaLocation.getAbsoluteIri().toString());
      String prefix = "";
      ArrayNode conditions = mapper.createArrayNode();
      for (int i = 0; i < schemaLocation.getFragment().getNameCount(); i++) {
        String segment = schemaLocation.getFragment().getName(i);
        JsonNode rule = schemaRoot.at(prefix);
        if (("then".equals(segment) || "else".equals(segment)) && rule.has("if")) {
          ObjectNode condition = conditions.addObject().put("branch", segment);
          condition.set("if", rule.get("if"));
        }
        prefix += "/" + segment.replace("~", "~0").replace("/", "~1");
      }
      if (!conditions.isEmpty()) {
        error.set("conditions", conditions);
      }
    }
    return errors;
  }

  private static void validateValueForSchema(
      ObjectMapper objectMapper,
      String path,
      JsonNode value,
      JsonNode schema,
      SchemaRefResolver resolver,
      ArrayNode errors) {
    for (var violation : resolver.validate(schema, value)) {
      String location = violation.getInstanceLocation().toString();
      errors.add(objectMapper.createObjectNode()
          .put("path", path + (location.startsWith("$") ? location.substring(1) : location))
          .put("message", violation.getMessage()
              + (schema.has("enum") ? ". Allowed values: " + schema.get("enum")
                  + ". Match their spelling and case exactly." : "")));
    }
  }

  private static boolean isRedundantIfThenCatalogWarning(
      String warning, ElementPropertiesSchemaModel model) {
    return warning.startsWith("Conditional rules (if/then/else)")
        && !model.conditionalBranches().isEmpty();
  }

  private static String textOrEmpty(JsonNode node) {
    if (node == null || node.isNull()) {
      return "";
    }
    String text = node.asText();
    return text == null ? "" : text;
  }

  private static boolean jsonEquals(JsonNode a, JsonNode b) {
    if (a == null || a.isNull()) {
      return b == null || b.isNull();
    }
    if (a.isBoolean() && b.isBoolean()) {
      return a.booleanValue() == b.booleanValue();
    }
    if (a.isNumber() && b.isNumber()) {
      return a.decimalValue().compareTo(b.decimalValue()) == 0;
    }
    if (a.isTextual() && b.isTextual()) {
      return a.asText().equals(b.asText());
    }
    return a.equals(b);
  }
}
