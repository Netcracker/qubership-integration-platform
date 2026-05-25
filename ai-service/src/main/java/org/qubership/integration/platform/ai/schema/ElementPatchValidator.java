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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic pre-validation of {@code updateElement} patch JSON against embedded QIP schemas.
 *
 * @deprecated Prefer {@link ElementPatchValidationRouter} with {@link
 *     ElementPatchValidationEngine#NETWORKNT} for pilot types; retained as default legacy engine.
 */
@Deprecated(since = "1.0", forRemoval = false)
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
          objectMapper, (ObjectNode) props, model, resolver, errors, missingRequired, warnings);
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
      ArrayNode missingRequired,
      ArrayNode warnings) {
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
    String docUri = model.elementDocumentUri();
    Deque<String> refStack = new ArrayDeque<>();

    for (String req : model.unconditionalRequired()) {
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
              ? QipConfModelUris.stripFragment(rootOneOf.get("$id").asText())
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

    Iterator<String> keys = patchProps.fieldNames();
    while (keys.hasNext()) {
      String key = keys.next();
      JsonNode schema = model.propertyDefs().get(key);
      if (schema == null) {
        continue;
      }
      validateValueForSchema(
          objectMapper,
          "properties." + key,
          patchProps.get(key),
          schema,
          resolver,
          elementRoot,
          docUri,
          refStack,
          errors,
          warnings,
          0);
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
      if (branchMatchesRecursive(
          patchProps, branch, resolver, documentRoot, documentUri, refStack, 0)) {
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

  private static boolean branchMatchesRecursive(
      JsonNode patchProps,
      JsonNode branch,
      SchemaRefResolver resolver,
      JsonNode documentRoot,
      String documentUri,
      Deque<String> refStack,
      int depth) {
    if (branch == null || !branch.isObject() || depth > 12) {
      return false;
    }
    if (branch.has("properties") && branch.get("properties").isObject()) {
      Iterator<Map.Entry<String, JsonNode>> it = branch.get("properties").properties().iterator();
      while (it.hasNext()) {
        Map.Entry<String, JsonNode> e = it.next();
        JsonNode propSchema = e.getValue();
        if (propSchema != null && propSchema.has("const") && patchProps.has(e.getKey())) {
          if (!jsonEquals(patchProps.get(e.getKey()), propSchema.get("const"))) {
            return false;
          }
        }
      }
    }
    if (branch.has("oneOf") && branch.get("oneOf").isArray()) {
      boolean matched = false;
      for (JsonNode option : branch.get("oneOf")) {
        if (branchMatchesRecursive(
            patchProps, option, resolver, documentRoot, documentUri, refStack, depth + 1)) {
          matched = true;
          break;
        }
      }
      if (!matched) {
        return false;
      }
    }
    if (branch.has("required") && branch.get("required").isArray()) {
      for (JsonNode r : branch.get("required")) {
        if (!r.isTextual()) {
          continue;
        }
        String name = r.asText();
        if (!patchProps.has(name) || patchProps.get(name).isNull()) {
          return false;
        }
      }
    }
    return true;
  }

  private static void validateValueForSchema(
      ObjectMapper objectMapper,
      String path,
      JsonNode value,
      JsonNode schema,
      SchemaRefResolver resolver,
      JsonNode documentRoot,
      String documentUri,
      Deque<String> refStack,
      ArrayNode errors,
      ArrayNode warnings,
      int depth) {
    if (depth > 10) {
      warnings.add(
          objectMapper
              .createObjectNode()
              .put("path", path)
              .put("message", "catalog_validation_required (nesting depth limit)"));
      return;
    }
    JsonNode s = schema;
    if (s.isObject() && s.has("$ref") && s.size() == 1) {
      try {
        s = resolver.dereference(documentRoot, documentUri, s, refStack);
      } catch (SchemaRefResolutionException ex) {
        warnings.add(
            objectMapper
                .createObjectNode()
                .put("path", path)
                .put("message", "catalog_validation_required (" + ex.getMessage() + ")"));
        return;
      }
    }
    if (s.has("oneOf") && s.get("oneOf").isArray()) {
      boolean any = false;
      for (JsonNode option : s.get("oneOf")) {
        if (valueMatchesSchema(
            value, option, resolver, documentRoot, documentUri, refStack, depth + 1)) {
          any = true;
          break;
        }
      }
      if (!any) {
        errors.add(
            objectMapper
                .createObjectNode()
                .put("path", path)
                .put("message", "Value does not match any allowed oneOf alternative"));
      }
      return;
    }
    if (!valueMatchesSchema(value, s, resolver, documentRoot, documentUri, refStack, depth + 1)) {
      errors.add(
          objectMapper
              .createObjectNode()
              .put("path", path)
              .put("message", "Type or constraint mismatch for property"));
    }
  }

  private static boolean valueMatchesSchema(
      JsonNode value,
      JsonNode schema,
      SchemaRefResolver resolver,
      JsonNode documentRoot,
      String documentUri,
      Deque<String> refStack,
      int depth) {
    if (schema == null || schema.isNull() || schema.isMissingNode()) {
      return true;
    }
    JsonNode s = schema;
    if (s.isObject() && s.has("$ref") && s.size() == 1) {
      try {
        s = resolver.dereference(documentRoot, documentUri, s, refStack);
      } catch (SchemaRefResolutionException ex) {
        return true;
      }
    }
    if (s.has("const")) {
      return jsonEquals(value, s.get("const"));
    }
    if (s.has("enum") && s.get("enum").isArray()) {
      for (JsonNode e : s.get("enum")) {
        if (jsonEquals(value, e)) {
          return true;
        }
      }
      return false;
    }
    if (s.has("oneOf") && s.get("oneOf").isArray()) {
      for (JsonNode option : s.get("oneOf")) {
        if (valueMatchesSchema(
            value, option, resolver, documentRoot, documentUri, refStack, depth + 1)) {
          return true;
        }
      }
      return false;
    }
    if (s.has("type") && s.get("type").isTextual()) {
      String t = s.get("type").asText();
      return switch (t) {
        case "string" -> value.isTextual() || value.isNull();
        case "integer" -> value.isIntegralNumber() || value.isTextual();
        case "number" -> value.isNumber() || value.isTextual();
        case "boolean" -> value.isBoolean();
        case "array" -> value.isArray() || value.isNull();
        case "object" -> value.isObject() || value.isNull();
        default -> true;
      };
    }
    if (s.has("properties") && s.get("properties").isObject() && value.isObject()) {
      return true;
    }
    return true;
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
