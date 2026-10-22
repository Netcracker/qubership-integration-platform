package org.qubership.integration.platform.ai.plan.mapping;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.plan.mapping.atlas.MappingDescriptionDocument;
import org.qubership.integration.platform.ai.plan.mapping.atlas.MappingDescriptionDocument.AttributeReference;
import org.qubership.integration.platform.ai.plan.mapping.atlas.MappingDescriptionDocument.Constant;
import org.qubership.integration.platform.ai.plan.mapping.atlas.MappingDescriptionDocument.ConstantReference;
import org.qubership.integration.platform.ai.plan.mapping.atlas.MappingDescriptionDocument.GivenValue;
import org.qubership.integration.platform.ai.plan.mapping.atlas.MappingDescriptionDocument.MappingAction;
import org.qubership.integration.platform.ai.plan.mapping.atlas.MappingDescriptionDocument.StringType;
import org.qubership.integration.platform.ai.plan.mapping.envelope.JsonSchemaMessageSchemaFactory;
import org.qubership.integration.platform.ai.plan.mapping.envelope.MappingEnvelope;
import org.qubership.integration.platform.ai.plan.mapping.schema.MappingSchemaSide;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingPort;

class Mapper2ContractValidatorTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private MappingEnvelope envelope;
  private MappingEnvelope arrayEnvelope;

  @BeforeEach
  void setUp() throws Exception {
    envelope = envelopeFrom(orderSchema(), orderSchema());
    arrayEnvelope = envelopeFrom(arraySourceSchema(), arrayTargetSchema());
  }

  @Test
  void unknownTransformationFails() {
    MappingDescriptionDocument captured =
        identityCapture(envelope)
            .withActions(List.of(identityAction(envelope).withTransformation("shout", List.of())));
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> Mapper2ContractValidator.validate(envelope, captured));
    assertTrue(ex.getMessage().startsWith("Mapping contract:"));
  }

  @Test
  void danglingConstantIdFails() {
    MappingDescriptionDocument captured =
        identityCapture(envelope).withActions(List.of(constantAction("missing-const", envelope)));
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> Mapper2ContractValidator.validate(envelope, captured));
    assertTrue(ex.getMessage().startsWith("Mapping contract:"));
  }

  @Test
  void identityCopyOnEnvelopePasses() {
    Mapper2ContractValidator.validate(envelope, identityCapture(envelope));
  }

  @Test
  void arrayAndPrimitiveIntoArrayWithoutTransformationFails() {
    MappingDescriptionDocument captured =
        arrayEnvelopeCapture().withActions(List.of(arrayPlusPrimitiveAction()));
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> Mapper2ContractValidator.validate(arrayEnvelope, captured));
    assertTrue(ex.getMessage().startsWith("Mapping contract:"));
  }

  @Test
  void emptySourcesFail() {
    MappingDescriptionDocument captured =
        identityCapture(envelope)
            .withActions(
                List.of(
                    new MappingAction(
                        "action-empty", List.of(), attributeRef(envelope, "$.orderId"), null)));
    assertContractFails(envelope, captured);
  }

  @Test
  void nullTargetFails() {
    MappingDescriptionDocument captured =
        identityCapture(envelope)
            .withActions(
                List.of(
                    new MappingAction(
                        "action-null-target",
                        List.of(attributeRef(envelope, "$.orderId")),
                        null,
                        null)));
    assertContractFails(envelope, captured);
  }

  @Test
  void missingAttributeIdFails() {
    MappingDescriptionDocument captured =
        identityCapture(envelope)
            .withActions(
                List.of(
                    new MappingAction(
                        "action-missing-attr",
                        List.of(new AttributeReference("body", List.of("missing-attr-id"))),
                        attributeRef(envelope, "$.orderId"),
                        null)));
    assertContractFails(envelope, captured);
  }

  @Test
  void severalArraysToArrayWithoutTransformationFails() {
    MappingDescriptionDocument captured =
        arrayEnvelopeCapture().withActions(List.of(severalArraysAction()));
    assertContractFails(arrayEnvelope, captured);
  }

  @Test
  void manyPrimitivesToNonArrayWithoutTransformationFails() {
    MappingDescriptionDocument captured =
        arrayEnvelopeCapture().withActions(List.of(manyPrimitivesToNameAction()));
    assertContractFails(arrayEnvelope, captured);
  }

  @Test
  void trimRequiresEmptyParameters() {
    MappingDescriptionDocument captured =
        identityCapture(envelope)
            .withActions(
                List.of(identityAction(envelope).withTransformation("trim", List.of("both"))));
    assertContractFails(envelope, captured);
    assertDoesNotThrow(
        () ->
            Mapper2ContractValidator.validate(
                envelope,
                identityCapture(envelope)
                    .withActions(
                        List.of(identityAction(envelope).withTransformation("trim", List.of())))));
  }

  @Test
  void formatDateTimeRequiresTwoParameters() {
    MappingDescriptionDocument captured =
        identityCapture(envelope)
            .withActions(
                List.of(
                    identityAction(envelope)
                        .withTransformation("formatDateTime", List.of("yyyy-MM-dd"))));
    assertContractFails(envelope, captured);
    assertDoesNotThrow(
        () ->
            Mapper2ContractValidator.validate(
                envelope,
                identityCapture(envelope)
                    .withActions(
                        List.of(
                            identityAction(envelope)
                                .withTransformation(
                                    "formatDateTime", List.of("yyyy-MM-dd", "dd-MM-yyyy"))))));
  }

  @Test
  void defaultValueRequiresOneParameter() {
    MappingDescriptionDocument captured =
        identityCapture(envelope)
            .withActions(
                List.of(identityAction(envelope).withTransformation("defaultValue", List.of())));
    assertContractFails(envelope, captured);
    assertDoesNotThrow(
        () ->
            Mapper2ContractValidator.validate(
                envelope,
                identityCapture(envelope)
                    .withActions(
                        List.of(
                            identityAction(envelope)
                                .withTransformation("defaultValue", List.of("N/A"))))));
  }

  @Test
  void declaredConstantSourcePasses() {
    Constant constant =
        new Constant("status-const", "status", new StringType(), new GivenValue("OPEN"));
    MappingDescriptionDocument captured =
        new MappingDescriptionDocument(
            envelope.source(),
            envelope.target(),
            List.of(constant),
            List.of(constantAction("status-const", envelope)));
    Mapper2ContractValidator.validate(envelope, captured);
  }

  private static void assertContractFails(
      MappingEnvelope envelope, MappingDescriptionDocument captured) {
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> Mapper2ContractValidator.validate(envelope, captured));
    assertTrue(ex.getMessage().startsWith("Mapping contract:"));
  }

  private static MappingDescriptionDocument identityCapture(MappingEnvelope envelope) {
    return new MappingDescriptionDocument(
        envelope.source(), envelope.target(), List.of(), List.of(identityAction(envelope)));
  }

  private static MappingAction identityAction(MappingEnvelope envelope) {
    return new MappingAction(
        "action-order-id",
        List.of(attributeRef(envelope, "$.orderId")),
        attributeRef(envelope, "$.orderId"),
        null);
  }

  private static MappingAction constantAction(String constantId, MappingEnvelope envelope) {
    return new MappingAction(
        "action-const",
        List.of(new ConstantReference(constantId)),
        attributeRef(envelope, "$.orderId"),
        null);
  }

  private MappingDescriptionDocument arrayEnvelopeCapture() {
    return new MappingDescriptionDocument(
        arrayEnvelope.source(),
        arrayEnvelope.target(),
        List.of(),
        List.of(arrayPlusPrimitiveAction()));
  }

  private MappingAction arrayPlusPrimitiveAction() {
    return new MappingAction(
        "action-array-plus-primitive",
        List.of(attributeRef(arrayEnvelope, "$.items"), attributeRef(arrayEnvelope, "$.label")),
        attributeRef(arrayEnvelope, "$.items"),
        null);
  }

  private MappingAction severalArraysAction() {
    return new MappingAction(
        "action-several-arrays",
        List.of(attributeRef(arrayEnvelope, "$.items"), attributeRef(arrayEnvelope, "$.tags")),
        attributeRef(arrayEnvelope, "$.items"),
        null);
  }

  private MappingAction manyPrimitivesToNameAction() {
    return new MappingAction(
        "action-many-primitives",
        List.of(attributeRef(arrayEnvelope, "$.label"), attributeRef(arrayEnvelope, "$.title")),
        attributeRef(arrayEnvelope, "$.name"),
        null);
  }

  private static AttributeReference attributeRef(MappingEnvelope envelope, String jsonPath) {
    List<String> pathIds = new ArrayList<>();
    for (Map.Entry<String, String> entry : envelope.idToPath().entrySet()) {
      if (jsonPath.equals(entry.getValue())) {
        pathIds.add(entry.getKey());
      }
    }
    if (pathIds.isEmpty()) {
      throw new IllegalStateException("No Task 6 attribute id for " + jsonPath);
    }
    return new AttributeReference("body", pathIds);
  }

  private static MappingEnvelope envelopeFrom(String sourceSchema, String targetSchema)
      throws Exception {
    return new JsonSchemaMessageSchemaFactory(MAPPER)
        .fromSides(
            side("trigger-http", MappingPort.OUTPUT, MAPPER.readTree(sourceSchema)),
            side("call-1", MappingPort.REQUEST, MAPPER.readTree(targetSchema)));
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

  private static String orderSchema() {
    return """
        {
          "type": "object",
          "properties": { "orderId": { "type": "string" } },
          "required": ["orderId"]
        }
        """;
  }

  private static String arraySourceSchema() {
    return """
        {
          "type": "object",
          "properties": {
            "items": { "type": "array", "items": { "type": "string" } },
            "tags": { "type": "array", "items": { "type": "string" } },
            "label": { "type": "string" },
            "title": { "type": "string" }
          }
        }
        """;
  }

  private static String arrayTargetSchema() {
    return """
        {
          "type": "object",
          "properties": {
            "items": { "type": "array", "items": { "type": "string" } },
            "name": { "type": "string" }
          }
        }
        """;
  }
}
