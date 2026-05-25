package org.qubership.integration.platform.ai.schema;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves {@code $ref} against embedded YAML schema documents (classpath) with cycle detection.
 */
@ApplicationScoped
public class SchemaRefResolver {

  private final SchemaResourceLoader resourceLoader;
  private final QipSchemaYamlParser yamlParser;
  private final ConcurrentHashMap<String, JsonNode> documentRootCache = new ConcurrentHashMap<>();

  @Inject
  public SchemaRefResolver(SchemaResourceLoader resourceLoader, QipSchemaYamlParser yamlParser) {
    this.resourceLoader = resourceLoader;
    this.yamlParser = yamlParser;
  }

  public JsonNode loadDocumentRoot(String modelUriWithoutFragment) {
    return documentRootCache.computeIfAbsent(
        modelUriWithoutFragment,
        uri -> yamlParser.parseYaml(resourceLoader.readSchemaYamlByModelUri(uri)));
  }

  public String readDocumentUri(JsonNode documentRoot, String fallbackUri) {
    if (documentRoot != null && documentRoot.has("$id") && documentRoot.get("$id").isTextual()) {
      return documentRoot.get("$id").asText();
    }
    return fallbackUri;
  }

  public JsonNode dereference(
      JsonNode documentRoot, String documentUri, JsonNode node, Deque<String> refStack) {
    if (node != null && node.isObject() && node.has("$ref")) {
      return resolveRef(documentRoot, documentUri, node.get("$ref").asText(), refStack);
    }
    return node;
  }

  /** Resolves a JSON Schema {@code $ref} string to a node in the correct document. */
  public JsonNode resolveRef(
      JsonNode baseDocumentRoot, String baseDocumentUri, String ref, Deque<String> refStack) {
    if (ref == null || ref.isBlank()) {
      throw new SchemaRefResolutionException("Empty $ref");
    }
    if (refStack.contains(ref)) {
      throw new SchemaRefResolutionException("cyclic $ref: " + ref);
    }
    refStack.push(ref);
    try {
      JsonNode targetRoot;
      String fragment;
      if (ref.startsWith("#")) {
        targetRoot = baseDocumentRoot;
        fragment = ref;
      } else {
        String docPart = QipConfModelUris.stripFragment(ref);
        fragment = QipConfModelUris.fragmentPart(ref);
        targetRoot = loadDocumentRoot(docPart);
      }
      String pointer = toJsonPointer(fragment);
      if (pointer.isEmpty()) {
        return targetRoot;
      }
      JsonNode at = targetRoot.at(pointer);
      if (at == null || at.isMissingNode()) {
        throw new SchemaRefResolutionException("$ref target not found: " + ref);
      }
      return at;
    } finally {
      refStack.pop();
    }
  }

  static String toJsonPointer(String uriFragment) {
    if (uriFragment == null || uriFragment.isEmpty() || uriFragment.equals("#")) {
      return "";
    }
    if (!uriFragment.startsWith("#/")) {
      throw new SchemaRefResolutionException(
          "Unsupported $ref fragment (expected #/path): " + uriFragment);
    }
    String pointer = uriFragment.substring(1);
    return escapeJsonPointer(pointer);
  }

  private static String escapeJsonPointer(String pointer) {
    if (pointer == null || pointer.isEmpty()) {
      return "";
    }
    String[] parts = pointer.split("/", -1);
    StringBuilder sb = new StringBuilder();
    for (int i = 1; i < parts.length; i++) {
      sb.append('/');
      sb.append(parts[i].replace("~", "~0").replace("/", "~1"));
    }
    return sb.toString();
  }
}
