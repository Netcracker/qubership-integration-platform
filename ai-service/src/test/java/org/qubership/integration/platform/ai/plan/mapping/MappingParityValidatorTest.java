package org.qubership.integration.platform.ai.plan.mapping;

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
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingContract;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntent;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntentRule;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingPort;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingRuleStatus;

class MappingParityValidatorTest {

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
  void extraActionFails() {
    MappingDescriptionDocument captured =
        identityCapture(envelope)
            .withActions(List.of(identityAction(envelope), extraAction(envelope, "$.other")));
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> MappingParityValidator.requireMapper2(envelope, identityOrderId(), captured));
    assertTrue(ex.getMessage().startsWith("Mapping parity:"));
  }

  @Test
  void droppedApprovedRuleFails() {
    MappingDescriptionDocument captured = identityCapture(envelope).withActions(List.of());
    assertThrows(
        IllegalArgumentException.class,
        () -> MappingParityValidator.requireMapper2(envelope, identityOrderId(), captured));
  }

  @Test
  void rewrittenSourceFails() {
    MappingDescriptionDocument captured =
        identityCapture(envelope).withSource(tamperedSource(envelope));
    assertThrows(
        IllegalArgumentException.class,
        () -> MappingParityValidator.requireMapper2(envelope, identityOrderId(), captured));
  }

  @Test
  void identityActionsOnEnvelopePass() {
    MappingParityValidator.requireMapper2(envelope, identityOrderId(), identityCapture(envelope));
  }

  @Test
  void scriptCoverageMustEqualApprovedTargets() {
    MappingParityValidator.requireScriptCoverage(identityOrderId(), List.of("$.orderId"));
  }

  @Test
  void scriptCoverageAcceptsBareNamesAndCommaSeparatedEcho() {
    MappingIntent intent =
        new MappingIntent(
            "response-result",
            "createTask",
            MappingPort.RESPONSE,
            "onTaskResult",
            MappingPort.REQUEST,
            List.of(
                new MappingIntentRule(
                    "", "commandType", "Set to completeTask.", MappingRuleStatus.USER_DEFINED),
                new MappingIntentRule(
                    "executionId, orderId",
                    "executionId, orderId",
                    "Echo preserved context.",
                    MappingRuleStatus.USER_DEFINED),
                new MappingIntentRule(
                    "name", "Subject", null, MappingRuleStatus.USER_DEFINED),
                new MappingIntentRule(
                    "subRequestType, orderId",
                    "Subject",
                    "Fallback subject",
                    MappingRuleStatus.USER_DEFINED)));
    MappingParityValidator.requireScriptCoverage(
        intent, List.of("commandType", "executionId", "orderId", "Subject"));
    MappingParityValidator.requireScriptCoverage(
        intent, List.of("$.commandType", "$.executionId", "$.orderId", "$.Subject"));
  }

  @Test
  void scriptCoverageParentPathCoversNestedApprovedTargets() {
    MappingIntent intent =
        new MappingIntent(
            "request-subject",
            "trigger-1",
            MappingPort.OUTPUT,
            "call-1",
            MappingPort.REQUEST,
            List.of(
                new MappingIntentRule("taskId", "Description.taskId", null, MappingRuleStatus.USER_DEFINED),
                new MappingIntentRule(
                    "executionId", "Description.executionId", null, MappingRuleStatus.USER_DEFINED),
                new MappingIntentRule("name", "Subject", null, MappingRuleStatus.USER_DEFINED)));
    MappingParityValidator.requireScriptCoverage(intent, List.of("Description", "Subject"));
  }

  @Test
  void scriptCoverageRequiresHopBodyFieldsNotPreserveForLaterContext() {
    MappingIntent intent =
        new MappingIntent(
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
    MappingContract createTask =
        MappingContract.of(
            new MappingContract.Field("$.Subject", "string", true),
            new MappingContract.Field("$.Description", "string", false),
            new MappingContract.Field("$.Priority", "string", false),
            new MappingContract.Field("$.Status", "string", false),
            new MappingContract.Field("$.ActivityDate", "string", false));
    MappingParityValidator.requireScriptCoverage(
        intent, List.of("Subject", "Description"), createTask);
  }

  @Test
  void missingScriptCoverageListFailsClosed() {
    assertThrows(
        IllegalArgumentException.class,
        () -> MappingParityValidator.requireScriptCoverage(identityOrderId(), null));
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
    return bodyCopyAction(envelope, "$.orderId", "$.orderId", "action-order-id");
  }

  private static MappingAction extraAction(MappingEnvelope envelope, String targetPath) {
    return bodyCopyAction(envelope, "$.orderId", targetPath, "action-extra");
  }

  private static MappingAction bodyCopyAction(
      MappingEnvelope envelope, String sourcePath, String targetPath, String actionId) {
    AttributeReference source = attributeRef(envelope.idToPath(), sourcePath);
    AttributeReference target = attributeRef(envelope.idToPath(), targetPath);
    return new MappingAction(actionId, List.of(source), target, null);
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
