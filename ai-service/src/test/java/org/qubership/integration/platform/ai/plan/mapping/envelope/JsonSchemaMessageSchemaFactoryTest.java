package org.qubership.integration.platform.ai.plan.mapping.envelope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.plan.mapping.atlas.MappingDescriptionDocument.MessageSchema;
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
