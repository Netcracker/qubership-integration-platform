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
    String elementUri = QipConfModelUris.elementModelUri(trimmed);
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
    List<String> warnings = new ArrayList<>();
    Deque<String> refStack = new ArrayDeque<>();

    mergeConditionalWarnings(inner, warnings);

    if (inner.isObject() && inner.has("$ref")) {
      JsonNode resolvedInner = resolver.dereference(elementRoot, docUri, inner, refStack);
      if (resolvedInner != null && resolvedInner.isObject()) {
        DocumentContext innerCtx =
            documentContextForAllOfItem(resolver, elementRoot, docUri, inner, resolvedInner);
        absorbAllOfFragment(
            resolvedInner, innerCtx, resolver, props, req, rootOneOfs, warnings, refStack);
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
        absorbAllOfFragment(resolved, ctx, resolver, props, req, rootOneOfs, warnings, refStack);
      }
    }

    mergeConditionalThenElseProperties(inner, props);

    return new ElementPropertiesSchemaModel(
        trimmed,
        docUri,
        Map.copyOf(props),
        Set.copyOf(req),
        List.copyOf(rootOneOfs),
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
      String docPart = QipConfModelUris.stripFragment(ref);
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
            subResolved, subCtx, resolver, props, req, rootOneOfs, warnings, refStack);
      }
    }

    mergeObjectProperties(resolved, props);
    if (!hasRootOneOf) {
      mergeRequired(resolved, req);
    }
    mergeConditionalThenElseProperties(resolved, props);
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
      List<JsonNode> variants = e.getValue();
      if (variants.size() == 1) {
        props.putIfAbsent(e.getKey(), variants.get(0));
      } else {
        ArrayNode arr = NODE_FACTORY.arrayNode();
        for (JsonNode v : variants) {
          arr.add(v);
        }
        ObjectNode wrap = NODE_FACTORY.objectNode();
        wrap.set("oneOf", arr);
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

  /**
   * JSON Schema {@code if}/{@code then}/{@code else} often adds properties not listed under
   * unconditional {@code properties}. Patch validation uses a flat allow-list ({@link
   * ElementPatchValidator}); merge {@code then.properties} / {@code else.properties} with {@code
   * putIfAbsent} so catalog defaults (e.g. {@code integrationOperationSkipEmptyQueryParameters} on
   * HTTP service-call) are not rejected as unknown.
   */
  private static void mergeConditionalThenElseProperties(
      JsonNode node, Map<String, JsonNode> props) {
    if (node == null || !node.isObject()) {
      return;
    }
    if (node.has("then") && node.get("then").isObject()) {
      JsonNode thenNode = node.get("then");
      mergeObjectPropertiesIfAbsent(thenNode, props);
      mergeConditionalThenElseProperties(thenNode, props);
    }
    if (node.has("else") && node.get("else").isObject()) {
      JsonNode elseNode = node.get("else");
      mergeObjectPropertiesIfAbsent(elseNode, props);
      mergeConditionalThenElseProperties(elseNode, props);
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
