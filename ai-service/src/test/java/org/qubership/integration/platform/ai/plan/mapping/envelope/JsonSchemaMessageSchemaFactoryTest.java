package org.qubership.integration.platform.ai.plan.mapping.envelope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.qubership.integration.platform.ai.plan.mapping.atlas.MappingDescriptionDocument.ArrayType;
import org.qubership.integration.platform.ai.plan.mapping.atlas.MappingDescriptionDocument.MessageSchema;
import org.qubership.integration.platform.ai.plan.mapping.atlas.MappingDescriptionDocument.NullType;
import org.qubership.integration.platform.ai.plan.mapping.atlas.MappingDescriptionDocument.ObjectType;
import org.qubership.integration.platform.ai.plan.mapping.schema.JsonSchemaMappingContractFactory;
import org.qubership.integration.platform.ai.plan.mapping.schema.MappingSchemaSide;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingPort;

class JsonSchemaMessageSchemaFactoryTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private JsonSchemaMessageSchemaFactory factory;
  private MappingSchemaSide sourceSide;
  private MappingSchemaSide targetSide;

  @BeforeEach
  void setUp() throws Exception {
    factory = new JsonSchemaMessageSchemaFactory(MAPPER);
    JsonNode orderSchema =
        MAPPER.readTree(
            """
            {
              "type": "object",
              "properties": { "orderId": { "type": "string" } },
              "required": ["orderId"]
            }
            """);
    sourceSide = side("trigger-http", MappingPort.OUTPUT, orderSchema);
    targetSide = side("call-1", MappingPort.REQUEST, orderSchema);
  }

  @ParameterizedTest
  @ValueSource(strings = {"oneOf", "anyOf"})
  void objectAlternativesKeepTheSameFieldsTypesAndRequirementsAsTheContract(String keyword)
      throws Exception {
    JsonNode schema = MAPPER.readTree("""
        {"%s":[
          {"type":"object","properties":{
            "id":{"type":"string"},"count":{"type":"integer"},
            "details":{"type":"object","properties":{"first":{"type":"string"}}}
          },"required":["id","count"]},
          {"type":"object","properties":{
            "id":{"type":"string"},"active":{"type":"boolean"},
            "details":{"type":"object","properties":{"second":{"type":"number"}}}
          },"required":["id","active"]}
        ]}
        """.formatted(keyword));

    var contract = JsonSchemaMappingContractFactory.from(schema);
    MappingEnvelope envelope = factory.fromSides(side("source", MappingPort.OUTPUT, schema), targetSide);
    ObjectType body = assertInstanceOf(ObjectType.class, envelope.source().body());
    assertEquals(List.of("id", "count", "details", "active"),
        body.schema().attributes().stream().map(attribute -> attribute.name()).toList());
    for (var attribute : body.schema().attributes()) {
      var field = contract.field("$." + attribute.name()).orElseThrow();
      assertEquals(field.required(), Boolean.TRUE.equals(attribute.required()));
      assertEquals("integer".equals(field.type()) ? "number" : field.type(), attribute.type().name());
    }
    ObjectType details = assertInstanceOf(ObjectType.class, body.schema().attributes().get(2).type());
    assertEquals(List.of("first", "second"),
        details.schema().attributes().stream().map(attribute -> attribute.name()).toList());
    assertTrue(envelope.idToPath().containsValue("$.details.first"));
    assertTrue(envelope.idToPath().containsValue("$.details.second"));
  }

  @Test
  void nestedRequiredFieldsAreOptionalWhenTheirParentIsAbsentFromAnAlternative() throws Exception {
    JsonNode schema = MAPPER.readTree("""
        {"oneOf":[
          {"type":"object","properties":{"details":{
            "type":"object","properties":{"id":{"type":"string"}},"required":["id"]
          }},"required":["details"]},
          {"type":"object","properties":{}}
        ]}
        """);

    var contract = JsonSchemaMappingContractFactory.from(schema);
    MappingEnvelope envelope = factory.fromSides(side("source", MappingPort.OUTPUT, schema), targetSide);
    ObjectType body = assertInstanceOf(ObjectType.class, envelope.source().body());
    var details = body.schema().attributes().getFirst();
    assertEquals(contract.field("$.details").orElseThrow().required(),
        Boolean.TRUE.equals(details.required()));
    ObjectType nested = assertInstanceOf(ObjectType.class, details.type());
    assertEquals(contract.field("$.details.id").orElseThrow().required(),
        Boolean.TRUE.equals(nested.schema().attributes().getFirst().required()));
  }

  @Test
  void incompatibleAlternativeFieldTypesAreRejected() throws Exception {
    JsonNode schema = MAPPER.readTree("""
        {"oneOf":[
          {"type":"object","properties":{"id":{"type":"string"}}},
          {"type":"object","properties":{"id":{"type":"integer"}}}
        ]}
        """);

    IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
        () -> factory.fromSides(side("source", MappingPort.OUTPUT, schema), targetSide));
    assertTrue(error.getMessage().contains("$.id"));
  }

  @Test
  void alternativeArrayItemsResolveReferencesAndMergeObjectFields() throws Exception {
    JsonNode schema = MAPPER.readTree("""
        {"oneOf":[
          {"type":"object","properties":{"values":{
            "type":"array","items":{"$ref":"#/$defs/Left"}
          }},"required":["values"]},
          {"type":"object","properties":{"values":{
            "type":"array","items":{"$ref":"#/$defs/Right"}
          }},"required":["values"]}
        ],"$defs":{
          "Left":{"type":"object","properties":{"id":{"type":"string"}},"required":["id"]},
          "Right":{"type":"object","properties":{
            "id":{"type":"string"},"count":{"type":"integer"}
          },"required":["id","count"]}
        }}
        """);

    MappingEnvelope envelope = factory.fromSides(side("source", MappingPort.OUTPUT, schema), targetSide);
    ObjectType body = assertInstanceOf(ObjectType.class, envelope.source().body());
    var values = body.schema().attributes().getFirst();
    assertEquals(Boolean.TRUE, values.required());
    ArrayType array = assertInstanceOf(ArrayType.class, values.type());
    ObjectType item = assertInstanceOf(ObjectType.class, array.itemType());
    assertEquals(List.of("id", "count"),
        item.schema().attributes().stream().map(attribute -> attribute.name()).toList());
    assertEquals(Boolean.TRUE, item.schema().attributes().getFirst().required());
    assertNull(item.schema().attributes().get(1).required());
    assertTrue(envelope.idToPath().containsValue("$.values.id"));
    assertTrue(envelope.idToPath().containsValue("$.values.count"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"string", "null"})
  void mixedAlternativesKeepTheOpaqueBodyWithoutVariantPaths(String scalarType) throws Exception {
    JsonNode schema = MAPPER.readTree("""
        {"oneOf":[
          {"type":"object","properties":{"variantOnly":{"type":"string"}}},
          {"type":"%s"}
        ]}
        """.formatted(scalarType));

    MappingEnvelope envelope = factory.fromSides(side("source", MappingPort.OUTPUT, schema), targetSide);

    assertInstanceOf(NullType.class, envelope.source().body());
    assertEquals(factory.fromSides(sourceSide, targetSide).idToPath(), envelope.idToPath());
  }

  @Test
  void recursiveObjectReferenceIsRejected() throws Exception {
    JsonNode schema = MAPPER.readTree("""
        {"$ref":"#/$defs/Node","$defs":{"Node":{
          "type":"object","properties":{"child":{"$ref":"#/$defs/Node"}}
        }}}
        """);

    IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
        () -> factory.fromSides(side("source", MappingPort.OUTPUT, schema), targetSide));
    assertTrue(error.getMessage().contains("Recursive JSON Schema"));
  }

  @Test
  void recursiveArrayReferenceIsRejected() throws Exception {
    JsonNode schema = MAPPER.readTree("""
        {"$ref":"#/$defs/Nodes","$defs":{"Nodes":{
          "type":"array","items":{"$ref":"#/$defs/Nodes"}
        }}}
        """);

    assertThrows(IllegalArgumentException.class,
        () -> factory.fromSides(sourceSide, side("target", MappingPort.REQUEST, schema)));
  }

  @Test
  void repeatedSiblingReferencesRemainValid() throws Exception {
    JsonNode schema = MAPPER.readTree("""
        {"type":"object","properties":{
          "first":{"$ref":"#/$defs/Address"},"second":{"$ref":"#/$defs/Address"}
        },"$defs":{"Address":{"type":"object","properties":{"city":{"type":"string"}}}}}
        """);

    MappingEnvelope envelope = factory.fromSides(side("source", MappingPort.OUTPUT, schema), targetSide);
    assertTrue(envelope.idToPath().containsValue("$.first.city"));
    assertTrue(envelope.idToPath().containsValue("$.second.city"));
  }

  @Test
  void twoBuildsSameDigestAndIds() {
    MappingEnvelope first = factory.fromSides(sourceSide, targetSide);
    MappingEnvelope second = factory.fromSides(sourceSide, targetSide);
    assertEquals(first.digest(), second.digest());
    assertEquals(first.idToPath(), second.idToPath());
  }

  @Test
  void idToPathRoundTripsOrderId() {
    MappingEnvelope envelope = factory.fromSides(sourceSide, targetSide);
    String id = AttributeIds.forPath("body", "$.orderId");
    assertEquals("$.orderId", envelope.idToPath().get(id));
  }

  @Test
  void operationSchemaFillsBodyOnly() {
    MappingEnvelope envelope = factory.fromSides(sourceSide, targetSide);
    assertBodyOnly(envelope.source());
    assertBodyOnly(envelope.target());
  }

  private static void assertBodyOnly(MessageSchema schema) {
    assertTrue(schema.headers().isEmpty());
    assertTrue(schema.properties().isEmpty());
  }

  private static MappingSchemaSide side(
      String serviceCallId, MappingPort direction, JsonNode schema) {
    return new MappingSchemaSide(
        "1",
        serviceCallId,
        "op-1",
        direction,
        "application/json",
        null,
        "sha-test",
        "test-provenance",
        schema);
  }
}
