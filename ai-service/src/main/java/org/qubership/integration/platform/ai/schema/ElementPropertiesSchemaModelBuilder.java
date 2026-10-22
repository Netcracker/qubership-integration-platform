package org.qubership.integration.platform.ai.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds a merged catalog-properties schema model from the inner {@code properties.properties}
 * block.
 */
public final class ElementPropertiesSchemaModelBuilder {

  private static final JsonNodeFactory NODE_FACTORY = JsonNodeFactory.instance;

  private ElementPropertiesSchemaModelBuilder() {}

  public static ElementPropertiesSchemaModel build(String elementType, SchemaRefResolver resolver) {
    String trimmed = elementType == null ? "" : elementType.trim();
    if (trimmed.isEmpty()) {
      return ElementPropertiesSchemaModel.empty("", "", "elementType is required");
    }
    String elementUri = CipConfModelUris.elementModelUri(trimmed);
    JsonNode elementRoot = resolver.loadDocumentRoot(elementUri);
    String docUri = resolver.readDocumentUri(elementRoot, elementUri);
    JsonNode inner = elementRoot.path("properties").path("properties");
    if (inner.isMissingNode() || inner.isNull()) {
      return ElementPropertiesSchemaModel.empty(
          trimmed, docUri, "Inner properties block not found in element schema");
    }

    Map<String, JsonNode> props = new LinkedHashMap<>();
    Set<String> req = new LinkedHashSet<>();
    List<JsonNode> rootOneOfs = new ArrayList<>();
    List<SchemaIfThenBranch> branches = new ArrayList<>();
    List<String> warnings = new ArrayList<>();
    Deque<String> refStack = new ArrayDeque<>();

    mergeConditionalWarnings(inner, warnings);

    if (inner.isObject() && inner.has("$ref")) {
      JsonNode resolvedInner = resolver.dereference(elementRoot, docUri, inner, refStack);
      if (resolvedInner != null && resolvedInner.isObject()) {
        DocumentContext innerCtx =
            documentContextForAllOfItem(resolver, elementRoot, docUri, inner, resolvedInner);
        absorbAllOfFragment(
            resolvedInner, innerCtx, resolver, props, req, rootOneOfs, branches, warnings, refStack);
      }
    }

    mergeObjectProperties(inner, props);
    mergeRequired(inner, req);

    if (inner.has("allOf") && inner.get("allOf").isArray()) {
      for (JsonNode item : inner.get("allOf")) {
        JsonNode resolved = resolver.dereference(elementRoot, docUri, item, refStack);
        if (resolved == null || resolved.isNull() || resolved.isMissingNode()) {
          continue;
        }
        DocumentContext ctx =
            documentContextForAllOfItem(resolver, elementRoot, docUri, item, resolved);
        absorbAllOfFragment(resolved, ctx, resolver, props, req, rootOneOfs, branches, warnings, refStack);
      }
    }

    collectIfThen(inner, branches, resolver);
    mergeConditionalThenElseProperties(inner, props, resolver);

    return new ElementPropertiesSchemaModel(
        trimmed,
        docUri,
        Map.copyOf(props),
        Set.copyOf(req),
        List.copyOf(rootOneOfs),
        List.copyOf(branches),
        List.copyOf(warnings));
  }

  private record DocumentContext(JsonNode documentRoot, String documentUri) {}

  private static DocumentContext documentContextForAllOfItem(
      SchemaRefResolver resolver,
      JsonNode elementRoot,
      String elementDocUri,
      JsonNode item,
      JsonNode resolved) {
    if (item != null && item.isObject() && item.has("$ref")) {
      String ref = item.get("$ref").asText();
      if (ref.startsWith("#")) {
        return new DocumentContext(elementRoot, elementDocUri);
      }
      String docPart = CipConfModelUris.stripFragment(ref);
      JsonNode docRoot = resolver.loadDocumentRoot(docPart);
      String uri = resolver.readDocumentUri(docRoot, docPart);
      return new DocumentContext(docRoot, uri);
    }
    return new DocumentContext(elementRoot, elementDocUri);
  }

  private static void absorbAllOfFragment(
      JsonNode resolved,
      DocumentContext ctx,
      SchemaRefResolver resolver,
      Map<String, JsonNode> props,
      Set<String> req,
      List<JsonNode> rootOneOfs,
      List<SchemaIfThenBranch> branches,
      List<String> warnings,
      Deque<String> refStack) {
    mergeConditionalWarnings(resolved, warnings);

    boolean hasRootOneOf = resolved.has("oneOf") && resolved.get("oneOf").isArray();
    if (hasRootOneOf) {
      rootOneOfs.add(resolved);
      mergeOneOfBranchesIntoPropertyDefs(
          resolved.get("oneOf"), props, resolver, ctx.documentRoot(), ctx.documentUri(), refStack);
    }

    if (resolved.has("allOf") && resolved.get("allOf").isArray()) {
      for (JsonNode sub : resolved.get("allOf")) {
        JsonNode subResolved =
            resolver.dereference(ctx.documentRoot, ctx.documentUri, sub, refStack);
        if (subResolved == null || subResolved.isNull() || subResolved.isMissingNode()) {
          continue;
        }
        DocumentContext subCtx =
            documentContextForAllOfItem(
                resolver, ctx.documentRoot, ctx.documentUri, sub, subResolved);
        absorbAllOfFragment(
            subResolved, subCtx, resolver, props, req, rootOneOfs, branches, warnings, refStack);
      }
    }

    mergeObjectProperties(resolved, props);
    if (!hasRootOneOf) {
      mergeRequired(resolved, req);
    }
    mergeConditionalThenElseProperties(resolved, props, resolver);
    collectIfThen(resolved, branches, resolver);
  }

  private static void collectIfThen(
      JsonNode node, List<SchemaIfThenBranch> branches, SchemaRefResolver resolver) {
    collectIfThen(node, branches, false, resolver);
  }

  private static void collectIfThen(
      JsonNode node, List<SchemaIfThenBranch> branches, boolean nested, SchemaRefResolver resolver) {
    node = resolveBranch(node, resolver);
    SchemaIfThenBranch parsed = parseIfThen(node, nested, resolver);
    if (parsed != null) {
      branches.add(parsed);
    }
  }

  private static SchemaIfThenBranch parseIfThen(
      JsonNode node, boolean nested, SchemaRefResolver resolver) {
    if (node == null || !node.isObject() || !node.has("if")) {
      return null;
    }
    JsonNode ifProps = node.path("if").path("properties");
    if (!ifProps.isObject() || ifProps.size() != 1) {
      return null;
    }
    var entry = ifProps.fields().next();
    JsonNode constNode = entry.getValue().path("const");
    if (constNode.isMissingNode() || constNode.isNull()) {
      return null;
    }
    String value =
        constNode.isBoolean() ? Boolean.toString(constNode.booleanValue()) : constNode.asText();
    JsonNode thenNode = node.path("then");
    JsonNode elseNode = node.path("else");
    List<SchemaIfThenBranch> nestedThen = new ArrayList<>();
    List<SchemaIfThenBranch> nestedElse = new ArrayList<>();
    collectIfThen(thenNode, nestedThen, true, resolver);
    collectIfThen(elseNode, nestedElse, true, resolver);
    return new SchemaIfThenBranch(
        entry.getKey(),
        value,
        requiredSet(thenNode, resolver, nested),
        nestedThen,
        requiredSet(elseNode, resolver, nested),
        nestedElse);
  }

  private static JsonNode resolveBranch(JsonNode node, SchemaRefResolver resolver) {
    if (node.has("$ref")) {
      return resolver.dereference(null, "", node, new ArrayDeque<>());
    }
    return node;
  }

  private static Set<String> requiredSet(JsonNode branch, SchemaRefResolver resolver, boolean nested) {
    JsonNode resolved = resolveBranch(branch, resolver);
    Set<String> keys = new LinkedHashSet<>();
    mergeRequired(resolved, keys);
    for (JsonNode item : resolved.path("allOf")) {
      keys.addAll(requiredSet(item, resolver, false));
    }
    if (nested && keys.isEmpty()) {
      resolved.path("properties").fieldNames().forEachRemaining(keys::add);
    }
    return keys;
  }

  private static void mergeOneOfBranchesIntoPropertyDefs(
      JsonNode oneOfArray,
      Map<String, JsonNode> props,
      SchemaRefResolver resolver,
      JsonNode documentRoot,
      String documentUri,
      Deque<String> refStack) {
    if (oneOfArray == null || !oneOfArray.isArray()) {
      return;
    }
    Map<String, List<JsonNode>> perKey = new LinkedHashMap<>();
    for (JsonNode branch : oneOfArray) {
      collectBranchPropertySchemas(branch, perKey, resolver, documentRoot, documentUri, refStack);
    }
    for (Map.Entry<String, List<JsonNode>> e : perKey.entrySet()) {
      List<JsonNode> variants = e.getValue().stream().distinct().toList();
      if (variants.size() == 1) {
        props.putIfAbsent(e.getKey(), variants.get(0));
      } else {
        ArrayNode arr = NODE_FACTORY.arrayNode();
        for (JsonNode v : variants) {
          arr.add(v);
        }
        ObjectNode wrap = NODE_FACTORY.objectNode();
        wrap.set("anyOf", arr);
        props.putIfAbsent(e.getKey(), wrap);
      }
    }
  }

  private static void collectBranchPropertySchemas(
      JsonNode branch,
      Map<String, List<JsonNode>> perKey,
      SchemaRefResolver resolver,
      JsonNode documentRoot,
      String documentUri,
      Deque<String> refStack) {
    if (branch == null || branch.isNull()) {
      return;
    }
    JsonNode resolved = resolver.dereference(documentRoot, documentUri, branch, refStack);
    if (resolved == null || resolved.isNull() || !resolved.isObject()) {
      return;
    }
    if (resolved.has("oneOf") && resolved.get("oneOf").isArray()) {
      for (JsonNode inner : resolved.get("oneOf")) {
        collectBranchPropertySchemas(inner, perKey, resolver, documentRoot, documentUri, refStack);
      }
    }
    if (resolved.has("properties") && resolved.get("properties").isObject()) {
      resolved
          .get("properties")
          .fields()
          .forEachRemaining(
              entry ->
                  perKey
                      .computeIfAbsent(entry.getKey(), k -> new ArrayList<>())
                      .add(entry.getValue()));
    }
  }

  private static void mergeObjectProperties(JsonNode node, Map<String, JsonNode> props) {
    if (node == null || !node.isObject()) {
      return;
    }
    if (!node.has("properties") || !node.get("properties").isObject()) {
      return;
    }
    node.get("properties").fields().forEachRemaining(e -> props.put(e.getKey(), e.getValue()));
  }

  private static void mergeRequired(JsonNode node, Set<String> req) {
    if (node == null
        || !node.isObject()
        || !node.has("required")
        || !node.get("required").isArray()) {
      return;
    }
    for (JsonNode r : node.get("required")) {
      if (r != null && r.isTextual()) {
        req.add(r.asText());
      }
    }
  }

  private static void mergeConditionalWarnings(JsonNode node, List<String> warnings) {
    if (node != null
        && node.isObject()
        && (node.has("if") || node.has("then") || node.has("else"))) {
      warnings.add(
          "Conditional rules (if/then/else) are present; full validation may require"
              + " catalog_validation_required.");
    }
  }

  private static void mergeConditionalThenElseProperties(
      JsonNode node, Map<String, JsonNode> props, SchemaRefResolver resolver) {
    for (String key : List.of("then", "else")) {
      mergeBranchProperties(node.path(key), props, resolver);
    }
  }

  private static void mergeBranchProperties(
      JsonNode node, Map<String, JsonNode> props, SchemaRefResolver resolver) {
    JsonNode resolved = resolveBranch(node, resolver);
    mergeObjectPropertiesIfAbsent(resolved, props);
    for (String key : List.of("allOf", "anyOf", "oneOf")) {
      for (JsonNode branch : resolved.path(key)) {
        mergeBranchProperties(branch, props, resolver);
      }
    }
    if (resolved.has("then") || resolved.has("else")) {
      mergeConditionalThenElseProperties(resolved, props, resolver);
    }
  }

  private static void mergeObjectPropertiesIfAbsent(JsonNode node, Map<String, JsonNode> props) {
    if (node == null || !node.isObject()) {
      return;
    }
    if (!node.has("properties") || !node.get("properties").isObject()) {
      return;
    }
    node.get("properties")
        .fields()
        .forEachRemaining(e -> props.putIfAbsent(e.getKey(), e.getValue()));
  }
}
