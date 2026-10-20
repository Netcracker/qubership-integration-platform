package org.qubership.integration.platform.ai.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.PathType;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves {@code $ref} against embedded YAML schema documents (classpath) with cycle detection.
 */
@ApplicationScoped
public class SchemaRefResolver {

  private final SchemaResourceLoader resourceLoader;
  private final CipSchemaYamlParser yamlParser;
  private final JsonSchemaFactory validationFactory;
  private final ConcurrentHashMap<String, JsonNode> documentRootCache = new ConcurrentHashMap<>();

  @Inject
  public SchemaRefResolver(SchemaResourceLoader resourceLoader, CipSchemaYamlParser yamlParser) {
    this.resourceLoader = resourceLoader;
    this.yamlParser = yamlParser;
    this.validationFactory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7,
        builder -> builder.schemaLoaders(loaders ->
            loaders.schemas(uri -> loadDocumentRoot(uri).toString())));
  }

  public JsonNode loadDocumentRoot(String modelUriWithoutFragment) {
    return documentRootCache.computeIfAbsent(
        modelUriWithoutFragment,
        uri -> {
          JsonNode root = yamlParser.parseYaml(resourceLoader.readSchemaYamlByModelUri(uri));
          absolutizeSchemaReferences(root, readDocumentUri(root, uri));
          return root;
        });
  }

  Set<ValidationMessage> validate(JsonNode schema, JsonNode value) {
    return validationFactory.getSchema(schema,
        SchemaValidatorsConfig.builder().locale(Locale.ROOT).pathType(PathType.JSON_PATH)
            .formatAssertionsEnabled(false).build()).validate(value);
  }

  // Extracted property schemas must retain the document that owns their local references.
  private static void absolutizeSchemaReferences(JsonNode schema, String documentUri) {
    if (!schema.isObject()) {
      return;
    }
    String base = schema.path("$id").asText(documentUri);
    if (schema.path("$ref").asText().startsWith("#")) {
      ((ObjectNode) schema).put("$ref", base + schema.get("$ref").asText());
    }
    for (String key : List.of("properties", "patternProperties", "definitions", "dependencies")) {
      schema.path(key).elements().forEachRemaining(child -> absolutizeSchemaReferences(child, base));
    }
    for (String key : List.of("allOf", "oneOf", "anyOf")) {
      schema.path(key).forEach(child -> absolutizeSchemaReferences(child, base));
    }
    for (String key : List.of("if", "then", "else", "not", "items", "additionalProperties",
        "additionalItems", "contains", "propertyNames")) {
      JsonNode child = schema.path(key);
      if (child.isArray()) {
        child.forEach(item -> absolutizeSchemaReferences(item, base));
      } else {
        absolutizeSchemaReferences(child, base);
      }
    }
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
        String docPart = CipConfModelUris.stripFragment(ref);
        fragment = CipConfModelUris.fragmentPart(ref);
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
