package org.qubership.integration.platform.ai.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.cache.CacheResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Builds a draft-07 JSON Schema envelope for catalog element PATCH bodies (cached per element
 * type).
 */
@ApplicationScoped
public class ElementPatchJsonSchemaCompiler {

  public static final String PROPERTIES_CACHE_NAME = "element-patch-properties-schema";

  public static final Set<String> PATCH_TOP_LEVEL_KEYS =
      Set.of(
          "name",
          "description",
          "type",
          "parentElementId",
          "swimlaneId",
          "mandatoryChecksPassed",
          "properties");

  private final SchemaRefResolver schemaRefResolver;
  private final ObjectMapper objectMapper;

  @Inject
  public ElementPatchJsonSchemaCompiler(
      SchemaRefResolver schemaRefResolver, ObjectMapper objectMapper) {
    this.schemaRefResolver = schemaRefResolver;
    this.objectMapper = objectMapper;
  }

  /**
   * Fully inlined draft-07 schema for the {@code properties} object only (no external {@code
   * $ref}).
   */
  @CacheResult(cacheName = PROPERTIES_CACHE_NAME)
  public JsonNode inlinedPropertiesSchema(String elementType) {
    ElementPropertiesSchemaModel model =
        ElementPropertiesSchemaModelBuilder.build(elementType, schemaRefResolver);
    ObjectNode schema = buildPropertiesSubschema(model);
    schema.put("additionalProperties", false);
    return (ObjectNode) ElementPatchSchemaInlining.stripRemainingRefs(schema);
  }

  Map<String, JsonNode> schemaDocumentsFor(ElementPropertiesSchemaModel model) {
    JsonNode propertiesSchema = buildPropertiesSubschema(model);
    Set<String> uris = ElementPatchSchemaDocumentCollector.collectDocumentUris(propertiesSchema);
    uris.add(QipConfModelUris.stripFragment(model.elementDocumentUri()));
    Map<String, JsonNode> documents = new LinkedHashMap<>();
    for (String uri : uris) {
      documents.put(uri, schemaRefResolver.loadDocumentRoot(uri));
    }
    return documents;
  }

  private ObjectNode buildPropertiesSubschema(ElementPropertiesSchemaModel model) {
    JsonNode elementRoot = schemaRefResolver.loadDocumentRoot(model.elementDocumentUri());
    String docUri = model.elementDocumentUri();
    Deque<String> refStack = new ArrayDeque<>();

    ObjectNode schema = objectMapper.createObjectNode();
    schema.put("type", "object");
    ObjectNode props = schema.putObject("properties");
    for (Map.Entry<String, JsonNode> e : model.propertyDefs().entrySet()) {
      JsonNode inlined =
          ElementPatchSchemaInlining.inlineRefs(
              e.getValue(), schemaRefResolver, elementRoot, docUri, refStack);
      props.set(e.getKey(), inlined);
    }
    if (!model.unconditionalRequired().isEmpty()) {
      ArrayNode required = schema.putArray("required");
      model.unconditionalRequired().forEach(required::add);
    }
    if (!model.rootOneOfGroups().isEmpty()) {
      ArrayNode allOf = schema.putArray("allOf");
      for (JsonNode group : model.rootOneOfGroups()) {
        JsonNode inlined =
            ElementPatchSchemaInlining.inlineRefs(
                group, schemaRefResolver, elementRoot, docUri, refStack);
        allOf.add(inlined);
      }
    }
    return schema;
  }
}
