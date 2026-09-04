package org.qubership.integration.platform.ai.plan.mapping;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import org.qubership.integration.platform.ai.plan.mapping.atlas.MappingDescriptionDocument.Attribute;
import org.qubership.integration.platform.ai.plan.mapping.atlas.MappingDescriptionDocument.AttributeReference;
import org.qubership.integration.platform.ai.plan.mapping.atlas.MappingDescriptionDocument.MappingAction;
import org.qubership.integration.platform.ai.plan.mapping.atlas.MappingDescriptionDocument.MessageSchema;
import org.qubership.integration.platform.ai.plan.mapping.atlas.MappingDescriptionDocument.ObjectSchema;
import org.qubership.integration.platform.ai.plan.mapping.atlas.MappingDescriptionDocument.ObjectType;
import org.qubership.integration.platform.ai.plan.mapping.atlas.MappingDescriptionDocument.StringType;
import org.qubership.integration.platform.ai.plan.mapping.envelope.JsonSchemaMessageSchemaFactory;
import org.qubership.integration.platform.ai.plan.mapping.envelope.MappingEnvelope;
import org.qubership.integration.platform.ai.plan.mapping.schema.MappingSchemaSide;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntent;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntentRule;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingPort;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingRuleStatus;

class MappingCaptureValidatorTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private MappingEnvelope envelope;

  @BeforeEach
  void setUp() throws Exception {
    JsonNode orderSchema =
        MAPPER.readTree(
            """
            {
              "type": "object",
              "properties": { "orderId": { "type": "string" } },
              "required": ["orderId"]
            }
            """);
    MappingSchemaSide sourceSide = side("trigger-http", MappingPort.OUTPUT, orderSchema);
    MappingSchemaSide targetSide = side("call-1", MappingPort.REQUEST, orderSchema);
    envelope = new JsonSchemaMessageSchemaFactory(MAPPER).fromSides(sourceSide, targetSide);
  }

  @Test
  void rewrittenSourceFailsCapture() {
    MappingCaptureValidator validator = new MappingCaptureValidator();
    MappingDescriptionDocument captured = identityCapture(envelope).withSource(tamperedSource(envelope));
    assertThrows(
        IllegalArgumentException.class,
        () -> validator.validateMapper2(envelope, identityOrderId(), captured));
  }

  @Test
  void unknownTransformationFailsCapture() {
    MappingCaptureValidator validator = new MappingCaptureValidator();
    MappingDescriptionDocument captured =
        identityCapture(envelope).withActions(List.of(identityAction(envelope).withTransformation("shout", List.of())));
    assertThrows(
        IllegalArgumentException.class,
        () -> validator.validateMapper2(envelope, identityOrderId(), captured));
  }

  @Test
  void grabScriptFailsCapture() {
    MappingCaptureValidator validator = new MappingCaptureValidator();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            validator.validateScript(
                identityOrderId(), "@Grab('foo:bar:1')\ndef x = 1\n", List.of("$.orderId")));
  }

  @Test
  void goodIdentityMapperCapturePasses() {
    new MappingCaptureValidator()
        .validateMapper2(envelope, identityOrderId(), identityCapture(envelope));
  }

  @Test
  void goodIdentityScriptCapturePasses() {
    assertDoesNotThrow(
        () ->
            new MappingCaptureValidator()
                .validateScript(
                    identityOrderId(),
                    "target['orderId'] = source['orderId']\n",
                    List.of("$.orderId")));
  }

  @Test
  void missingScriptCoverageFailsCapture() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new MappingCaptureValidator()
                .validateScript(identityOrderId(), "def x = 1\n", null));
  }

  @Test
  void unexpectedScriptCoverageFailsCaptureEvenWhenGroovyCompiles() {
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new MappingCaptureValidator()
                    .validateScript(
                        identityOrderId(),
                        "target['orderId'] = source['orderId']\ntarget['extra'] = 1\n",
                        List.of("$.orderId", "$.extra")));
    assertTrue(ex.getMessage().contains("unexpected="));
    assertTrue(ex.getMessage().contains("$.extra"));
  }

  @Test
  void responseHopScriptCoveragePassesWhenTargetBodyHasNoProperties() throws Exception {
    JsonNode salesforceSource =
        MAPPER.readTree(
            """
            {
              "type": "object",
              "properties": { "id": { "type": "string" } }
            }
            """);
    JsonNode oneOfTarget =
        MAPPER.readTree(
            """
            {
              "oneOf": [
                { "type": "object" },
                { "type": "string" }
              ]
            }
            """);
    MappingEnvelope responseEnvelope =
        new JsonSchemaMessageSchemaFactory(MAPPER)
            .fromSides(
                side("createTask", MappingPort.RESPONSE, salesforceSource),
                side("onTaskResult", MappingPort.REQUEST, oneOfTarget));
    MappingIntent intent =
        new MappingIntent(
            "response-result",
            "createTask",
            MappingPort.RESPONSE,
            "onTaskResult",
            MappingPort.REQUEST,
            List.of(
                new MappingIntentRule(
                    "", "$.commandType", "Set to completeTask.", MappingRuleStatus.USER_DEFINED),
                new MappingIntentRule(
                    "id", "$.executionId", null, MappingRuleStatus.USER_DEFINED),
                new MappingIntentRule(
                    "orderId", "$.orderId", "Echo preserved context.", MappingRuleStatus.USER_DEFINED)));
    assertDoesNotThrow(
        () ->
            new MappingCaptureValidator()
                .validateScript(
                    intent,
                    """
                    target['commandType'] = 'completeTask'
                    target['executionId'] = source['id']
                    target['orderId'] = orderId
                    """,
                    List.of("$.commandType", "$.executionId", "$.orderId"),
                    responseEnvelope));
  }

  @Test
  void requestHopEnvelopeScriptCoverageDoesNotRequirePreserveForLaterContext()
      throws Exception {
    MappingEnvelope requestEnvelope = requestHopEnvelope();
    assertDoesNotThrow(
        () ->
            new MappingCaptureValidator()
                .validateScript(
                    requestHopIntent(),
                    requestHopScript(),
                    List.of("Subject", "Description"),
                    requestEnvelope));
  }

  @Test
  void requestHopCoverageKeepsHopBodyPathsAndDropsResponseKeepPaths() throws Exception {
    MappingEnvelope requestEnvelope = requestHopEnvelope();
    MappingCaptureValidator validator = new MappingCaptureValidator();
    List<String> implemented =
        List.of(
            "Subject",
            "Description",
            "$.response.executionId",
            "$.response.orderId");
    List<String> hopBody = validator.hopBodyCoverage(implemented, requestEnvelope);
    assertEquals(List.of("$.Subject", "$.Description"), hopBody);
    assertDoesNotThrow(
        () -> validator.validateScript(requestHopIntent(), requestHopScript(), hopBody, requestEnvelope));
  }

  @Test
  void requestHopCoverageStillFailsWhenHopBodyFieldIsMissing() throws Exception {
    MappingEnvelope requestEnvelope = requestHopEnvelope();
    MappingCaptureValidator validator = new MappingCaptureValidator();
    List<String> hopBody =
        validator.hopBodyCoverage(
            List.of("Description", "$.response.executionId", "$.response.orderId"),
            requestEnvelope);
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                validator.validateScript(
                    requestHopIntent(), requestHopScript(), hopBody, requestEnvelope));
    assertTrue(ex.getMessage().contains("missing="));
    assertTrue(ex.getMessage().contains("Subject"));
    assertFalse(ex.getMessage().contains("$.response"));
  }

  private static MappingEnvelope requestHopEnvelope() throws Exception {
    JsonNode onTaskStart =
        MAPPER.readTree(
            """
            {
              "type": "object",
              "properties": {
                "name": { "type": "string" },
                "taskId": { "type": "string" },
                "executionId": { "type": "string" },
                "orderId": { "type": "string" }
              }
            }
            """);
    JsonNode createTask =
        MAPPER.readTree(
            """
            {
              "type": "object",
              "properties": {
                "Subject": { "type": "string" },
                "Description": { "type": "string" },
                "Priority": { "type": "string" },
                "Status": { "type": "string" },
                "ActivityDate": { "type": "string" }
              }
            }
            """);
    return new JsonSchemaMessageSchemaFactory(MAPPER)
        .fromSides(
            side("onTaskStart", MappingPort.OUTPUT, onTaskStart),
            side("createTask", MappingPort.REQUEST, createTask));
  }

  private static MappingIntent requestHopIntent() {
    return new MappingIntent(
        "request-onTaskStart-to-createTask",
        "onTaskStart",
        MappingPort.OUTPUT,
        "createTask",
        MappingPort.REQUEST,
        List.of(
            new MappingIntentRule("name", "Subject", null, MappingRuleStatus.USER_DEFINED),
            new MappingIntentRule(
                "taskId", "Description.taskId", null, MappingRuleStatus.USER_DEFINED),
            new MappingIntentRule(
                "executionId",
                "responseContext.executionId",
                "Keep for the response.",
                MappingRuleStatus.USER_DEFINED),
            new MappingIntentRule(
                "orderId",
                "responseContext.orderId",
                "Keep for the response.",
                MappingRuleStatus.USER_DEFINED)));
  }

  private static String requestHopScript() {
    return """
        target['Subject'] = source['name']
        target['Description'] = source['taskId']
        response.executionId = source['executionId']
        response.orderId = source['orderId']
        """;
  }

  private static MappingIntent identityOrderId() {
    return new MappingIntent(
        "map-init",
        "trigger-http",
        MappingPort.OUTPUT,
        "call-1",
        MappingPort.REQUEST,
        List.of(
            new MappingIntentRule(
                "$.orderId", "$.orderId", null, MappingRuleStatus.USER_DEFINED)));
  }

  private static MappingDescriptionDocument identityCapture(MappingEnvelope envelope) {
    return new MappingDescriptionDocument(
        envelope.source(), envelope.target(), List.of(), List.of(identityAction(envelope)));
  }

  private static MappingAction identityAction(MappingEnvelope envelope) {
    AttributeReference source = attributeRef(envelope.idToPath(), "$.orderId");
    AttributeReference target = attributeRef(envelope.idToPath(), "$.orderId");
    return new MappingAction("action-order-id", List.of(source), target, null);
  }

  private static AttributeReference attributeRef(Map<String, String> idToPath, String jsonPath) {
    List<String> pathIds = new ArrayList<>();
    for (Map.Entry<String, String> entry : idToPath.entrySet()) {
      if (jsonPath.equals(entry.getValue())) {
        pathIds.add(entry.getKey());
      }
    }
    if (pathIds.isEmpty()) {
      pathIds.add("missing-" + jsonPath.replace("$.", ""));
    }
    return new AttributeReference("body", pathIds);
  }

  private static MessageSchema tamperedSource(MappingEnvelope envelope) {
    MessageSchema source = envelope.source();
    ObjectType body = (ObjectType) source.body();
    ObjectSchema schema = body.schema();
    List<Attribute> attributes = new ArrayList<>(schema.attributes());
    attributes.add(new Attribute("tampered-id", "tampered", new StringType()));
    return new MessageSchema(
        source.headers(),
        source.properties(),
        new ObjectType(new ObjectSchema(schema.id(), attributes)));
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
