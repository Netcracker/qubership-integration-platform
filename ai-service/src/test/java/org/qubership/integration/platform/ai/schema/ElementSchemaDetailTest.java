package org.qubership.integration.platform.ai.schema;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class ElementSchemaDetailTest {
  private final ObjectMapper json = new ObjectMapper();
  private final SchemaRefResolver refs = new SchemaRefResolver(new SchemaResourceLoader(), new CipSchemaYamlParser());
  private final DeterministicElementSchemaService schemas = DeterministicElementSchemaService.createForUnitTests(json);

  @Test
  void preservesNestedLookupThroughTwoRootReferencesAndTopLevelNativeReferences() throws Exception {
    String fixture = """
        {"$id":"http://netcracker.com/schemas/product/cloud-integration-platform/conf-model/element/ref-chain-detail",
         "type":"object","properties":{"type":{"const":"ref-chain-detail"},
         "properties":{"type":"object","properties":{"holder":{"$ref":"#/definitions/First"}}}},
         "definitions":{"First":{"$ref":"#/definitions/Second"},"Second":{"type":"object",
         "properties":{"leaf":{"type":"string","enum":["left","right"]}}}}}
        """;
    var loader = new SchemaResourceLoader() {
      @Override public boolean existsElementSchema(String type) { return true; }
      @Override public String readSchemaYamlByModelUri(String uri) { return fixture; }
    };
    var parser = new CipSchemaYamlParser();
    var service = new DeterministicElementSchemaService();
    service.schemaResourceLoader = loader;
    service.qipSchemaYamlParser = parser;
    service.schemaRefResolver = new SchemaRefResolver(loader, parser);
    service.objectMapper = json;

    var nested = json.readTree(service.describeElementProperty("ref-chain-detail", "holder.leaf"));
    assertFalse(nested.has("error"), nested.toString());
    assertEquals(json.readTree("{\"type\":\"string\",\"enum\":[\"left\",\"right\"]}"), nested.path("schema"));
    var topLevel = json.readTree(service.describeElementProperty("ref-chain-detail", "holder"));
    String fixtureUri = json.readTree(fixture).path("$id").asText();
    assertEquals(fixtureUri + "#/definitions/First", topLevel.at("/schema/$ref").asText());
    assertEquals("RESOLVED", topLevel.at("/schema/$refTarget/status").asText());
    assertEquals(fixtureUri + "#/definitions/Second", topLevel.at("/schema/$refTarget/schema/$ref").asText());
  }

  @Test
  void followsTheRealResponseHandlerChainWithoutConjoiningDraftSevenSiblings() throws Exception {
    var detail = json.readTree(schemas.describeElementProperty("service-call", "after"));
    var handler = detail.at("/schema/items/$refTarget/schema");
    assertTrue(handler.has("$ref"), detail.toString());
    assertTrue(handler.path("properties").has("wildcard"), detail.toString());
    var alternatives = handler.at("/$refTarget/schema/oneOf");
    assertEquals(3, alternatives.size(), detail.toString());
    assertEquals(List.of("None", "Mapper", "Scripting"),
        alternatives.findValuesAsText("title").stream().filter(List.of("None", "Mapper", "Scripting")::contains).toList());
    assertEquals("script", alternatives.get(2).at("/properties/type/const").asText());
    assertEquals("type", alternatives.get(2).path("required").get(0).asText());
    var resource = alternatives.get(2).at("/allOf/0/$refTarget/schema");
    assertEquals("groovy", resource.at("/properties/exportFileExtension/const").asText());
    assertEquals("script", resource.at("/properties/propertiesToExportInSeparateFile/const").asText());
    assertEquals("string", resource.at("/properties/script/type").asText());
    assertTrue(detail.path("schemaView").asText().contains("not additional assertions"));
  }

  @Test
  void retainsFullEnumsConditionsBooleanSchemasAndLiteralData() throws Exception {
    ObjectNode schema = (ObjectNode) json.readTree("""
        {"type":"object","properties":{"mode":{"type":"string"},"nested":{"type":"object",
          "patternProperties":{"^field":{"minLength":2}},"additionalProperties":false}},
          "required":["mode"],"oneOf":[{"required":["left"]},{"required":["right"]}],
          "allOf":[{"if":{"properties":{"mode":{"const":"variant-36"}}},
          "then":{"required":["nested"]},"else":{"not":{"required":["nested"]}}}],
          "const":{"$ref":"literal-data"},"default":{"$ref":"default-data"}}
        """);
    ((ObjectNode) schema.at("/properties/mode")).set("enum",
        json.valueToTree(IntStream.range(0, 37).mapToObj(i -> "variant-" + i).toList()));
    var detail = ElementSchemaCompactFormatter.describePropertySchema(schema, refs, schema, "", 8);
    assertEquals(schema, detail);
    assertEquals(37, detail.at("/properties/mode/enum").size());
    assertFalse(detail.at("/properties/nested/additionalProperties").asBoolean());
    assertEquals(schema.path("const"), detail.path("const"));
    assertEquals(schema.path("default"), detail.path("default"));
  }

  @Test
  void marksCyclesAndDepthWithoutDroppingTheNativeSubtree() throws Exception {
    JsonNode root = json.readTree("""
        {"definitions":{"A":{"$ref":"#/definitions/B","title":"native-a"},
          "B":{"$ref":"#/definitions/A","required":["kept"]}}}
        """);
    JsonNode selected = root.at("/definitions/A");
    var cycle = ElementSchemaCompactFormatter.describePropertySchema(selected, refs, root, "", 8);
    assertTrue(cycle.toString().contains("\"status\":\"CYCLE\""), cycle.toString());
    var depth = ElementSchemaCompactFormatter.describePropertySchema(selected, refs, root, "", 0);
    assertEquals("#/definitions/B", depth.path("$ref").asText());
    assertEquals("native-a", depth.path("title").asText());
    assertEquals("DEPTH_LIMIT", depth.at("/$refTarget/status").asText());
    ObjectNode nested = json.createObjectNode().put("type", "object");
    nested.set("properties", json.createObjectNode().set("deep", root));
    var unchanged = ElementSchemaCompactFormatter.describePropertySchema(nested, refs, root, "", 0);
    assertEquals(nested.path("properties"), unchanged.path("properties"));
    assertEquals("DEPTH_LIMIT", unchanged.path("$refExpansionStatus").asText());
  }

  @Test
  void marksAnUnresolvedReferenceRatherThanInventingAnEmptySchema() throws Exception {
    JsonNode schema = json.readTree("{\"$ref\":\"#/definitions/missing\",\"required\":[\"native-key\"]}");
    var detail = ElementSchemaCompactFormatter.describePropertySchema(schema, refs, json.createObjectNode(), "", 8);
    assertEquals(schema.path("$ref"), detail.path("$ref"));
    assertEquals(schema.path("required"), detail.path("required"));
    assertEquals("UNRESOLVED", detail.at("/$refTarget/status").asText());
  }

  @Test
  void keepsTheActualDraftSevenValidatorAndBranchRefusalsUnchanged() throws Exception {
    JsonNode sibling = json.readTree("""
        {"$ref":"#/definitions/target","type":"integer","definitions":{"target":{"type":"string"}}}
        """);
    assertTrue(refs.validate(sibling, json.valueToTree("native string")).isEmpty());
    assertFalse(refs.validate(sibling, json.valueToTree(7)).isEmpty());
    var valid = json.readTree("[{\"type\":\"script\",\"script\":\"return 1\",\"exportFileExtension\":\"groovy\"}]");
    var invalid = json.readTree("[{\"type\":\"script\",\"script\":\"return 1\",\"exportFileExtension\":\"json\"}]");
    var beforeValid = schemas.validateCapturePropertyValue("service-call", "after", valid);
    var beforeInvalid = schemas.validateCapturePropertyValue("service-call", "after", invalid);
    assertTrue(beforeValid.isEmpty(), beforeValid.toString());
    assertTrue(beforeInvalid.isPresent());
    String summary = schemas.describeElementPatchSchema("service-call");
    schemas.describeElementProperty("service-call", "after");
    ElementSchemaCompactFormatter.describePropertySchema(sibling, refs, sibling, "", 8);
    assertEquals(beforeValid, schemas.validateCapturePropertyValue("service-call", "after", valid));
    assertEquals(beforeInvalid, schemas.validateCapturePropertyValue("service-call", "after", invalid));
    assertEquals(summary, schemas.describeElementPatchSchema("service-call"));
    assertFalse(summary.contains("$refTarget"));
    assertTrue(refs.validate(sibling, json.valueToTree("native string")).isEmpty());
    assertFalse(refs.validate(sibling, json.valueToTree(7)).isEmpty());
  }
}
