package org.qubership.integration.platform.ai.plan.mapping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.plan.BriefMappingValidator;
import org.qubership.integration.platform.ai.plan.mapping.schema.JsonSchemaMappingContractFactory;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingContract;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntent;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntentRule;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingPort;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingRuleStatus;

class MappingContractGateTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static final String ORDER_SCHEMA =
      """
      {
        "type": "object",
        "properties": { "orderId": { "type": "string" } },
        "required": ["orderId"]
      }
      """;

  private static final MappingContract SOURCE = contractFrom(ORDER_SCHEMA);

  private static final MappingContract TARGET = contractFrom(ORDER_SCHEMA);

  private static MappingContract contractFrom(String schema) {
    try {
      return JsonSchemaMappingContractFactory.from(MAPPER.readTree(schema));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  void unresolvedRequiredTargetDoesNotStartGenerator() {
    MappingIntent intent =
        new MappingIntent(
            "map-init",
            "trigger-http",
            MappingPort.OUTPUT,
            "node-call",
            MappingPort.REQUEST,
            List.of());
    Optional<String> message = MappingContractGate.blockedMessage(intent, SOURCE, TARGET);
    assertTrue(message.orElseThrow().startsWith(BriefMappingValidator.UNRESOLVED_REQUIRED_PREFIX));
    MappingContractEvaluation evaluated = MappingContractGate.evaluate(intent, SOURCE, TARGET);
    assertEquals(
        MappingFindingCode.MAPPING_MISSING_REQUIRED_TARGET,
        evaluated.blockerFindings().getFirst().code());
  }

  @Test
  void inventedFieldOnAnUnrelatedApiIsUnknownTargetNotMissingRequiredForThatField() {
    MappingIntent intent =
        new MappingIntent(
            "billing-create-invoice",
            "http-trigger",
            MappingPort.OUTPUT,
            "billing-create-invoice",
            MappingPort.REQUEST,
            List.of(
                new MappingIntentRule(
                    "$.orderId", "$.notARealInvoiceField", null, MappingRuleStatus.PROPOSED)));
    MappingContractEvaluation evaluated =
        MappingContractGate.evaluate(
            intent,
            contractFrom(
                """
                {
                  "type": "object",
                  "properties": { "orderId": { "type": "string" } },
                  "required": ["orderId"]
                }
                """),
            contractFrom(
                """
                {
                  "type": "object",
                  "properties": { "invoiceId": { "type": "string" } },
                  "required": ["invoiceId"]
                }
                """));
    assertEquals(
        MappingFindingCode.MAPPING_UNKNOWN_TARGET,
        evaluated.blockerFindings().stream()
            .filter(finding -> "$.notARealInvoiceField".equals(finding.targetPath()))
            .findFirst()
            .orElseThrow()
            .code());
    assertTrue(
        evaluated.blockerFindings().stream()
            .noneMatch(
                finding ->
                    finding.code() == MappingFindingCode.MAPPING_MISSING_REQUIRED_TARGET
                        && "$.notARealInvoiceField".equals(finding.targetPath())));
  }

  @Test
  void unknownTargetDoesNotStartGeneratorAndIsNotMissingRequired() {
    MappingContract target =
        contractFrom(
            """
            {
              "type": "object",
              "properties": { "Subject": { "type": "string" } },
              "required": ["Subject"]
            }
            """);
    MappingIntent intent =
        new MappingIntent(
            "salesforce-create-task",
            "onTaskStart",
            MappingPort.OUTPUT,
            "salesforce-create-task",
            MappingPort.REQUEST,
            List.of(
                new MappingIntentRule("$.subject", "$.Subject", null, MappingRuleStatus.PROPOSED),
                new MappingIntentRule(
                    "$.executionId",
                    "$.preserved.executionId",
                    "preserve for response",
                    MappingRuleStatus.PROPOSED)));
    MappingContractEvaluation evaluated =
        MappingContractGate.evaluate(
            intent,
            contractFrom(
                """
                {
                  "type": "object",
                  "properties": {
                    "executionId": { "type": "string" },
                    "subject": { "type": "string" }
                  }
                }
                """),
            target);
    assertTrue(evaluated.blocked());
    assertEquals(1, evaluated.blockerFindings().size());
    assertEquals(
        MappingFindingCode.MAPPING_UNKNOWN_TARGET, evaluated.blockerFindings().getFirst().code());
    assertFalse(
        evaluated.blockedMessage().startsWith(BriefMappingValidator.UNRESOLVED_REQUIRED_PREFIX));
  }

  @Test
  void rewordedUnknownTargetStillBlocks() {
    MappingContract target =
        contractFrom(
            """
            {
              "type": "object",
              "properties": { "Subject": { "type": "string" } },
              "required": ["Subject"]
            }
            """);
    MappingContract source =
        contractFrom(
            """
            {
              "type": "object",
              "properties": { "executionId": { "type": "string" } }
            }
            """);
    MappingIntent original =
        new MappingIntent(
            "map-request",
            "onTaskStart",
            MappingPort.OUTPUT,
            "create-task",
            MappingPort.REQUEST,
            List.of(
                new MappingIntentRule(
                    "$.executionId",
                    "$.preserved.executionId",
                    "preserve for response",
                    MappingRuleStatus.PROPOSED)));
    MappingIntent reworded =
        original.withRules(
            List.of(
                new MappingIntentRule(
                    "$.executionId",
                    "$.preserved.executionId",
                    "explicitly initialize preserved.executionId from inbound executionId",
                    MappingRuleStatus.PROPOSED)));
    assertTrue(MappingContractGate.evaluate(original, source, target).blocked());
    assertTrue(MappingContractGate.evaluate(reworded, source, target).blocked());
    assertEquals(
        MappingFindingCode.MAPPING_UNKNOWN_TARGET,
        MappingContractGate.evaluate(reworded, source, target).blockerFindings().stream()
            .filter(finding -> "$.preserved.executionId".equals(finding.targetPath()))
            .findFirst()
            .orElseThrow()
            .code());
  }

  @Test
  void capturedBriefShapeDoesNotBlockScriptGeneration() {
    MappingContract source =
        contractFrom(
            """
            {
              "type": "object",
              "properties": {
                "id": { "type": "string" },
                "success": { "type": "boolean" },
                "errors": { "type": "array" }
              },
              "required": ["id", "success", "errors"]
            }
            """);
    MappingContract target =
        contractFrom(
            """
            {
              "oneOf": [
                {
                  "type": "object",
                  "properties": {
                    "executionId": { "type": "string" },
                    "commandType": { "type": "string" },
                    "orderId": { "type": "string" },
                    "error": { "type": "object" }
                  },
                  "required": ["executionId", "commandType", "orderId", "error"]
                },
                {
                  "type": "object",
                  "properties": {
                    "executionId": { "type": "string" },
                    "commandType": { "type": "string" },
                    "orderId": { "type": "string" },
                    "executionNumber": { "type": "integer" }
                  },
                  "required": ["executionId", "commandType", "orderId", "executionNumber"]
                }
              ]
            }
            """);
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
                    "Echo preserved execution context fields.",
                    MappingRuleStatus.USER_DEFINED),
                new MappingIntentRule(
                    "", "sourceAppName", "Set to salesforce.", MappingRuleStatus.USER_DEFINED),
                new MappingIntentRule(
                    "id", "parameters.salesforceTaskId", null, MappingRuleStatus.USER_DEFINED)));
    assertTrue(MappingContractGate.blockedMessage(intent, source, target).isEmpty());
  }

  @Test
  void scriptEchoOfOffHopSourceDoesNotBlock() {
    MappingContract source =
        contractFrom(
            """
            {
              "type": "object",
              "properties": { "id": { "type": "string" } },
              "required": ["id"]
            }
            """);
    MappingContract target =
        contractFrom(
            """
            {
              "type": "object",
              "properties": { "processId": { "type": "string" } }
            }
            """);
    MappingIntent intent =
        new MappingIntent(
            "response-result",
            "createTask",
            MappingPort.RESPONSE,
            "onTaskResult",
            MappingPort.REQUEST,
            List.of(
                new MappingIntentRule(
                    "processInstanceId",
                    "processId",
                    null,
                    MappingRuleStatus.PROPOSED)));
    assertTrue(MappingContractGate.blockedMessage(intent, source, target).isEmpty());
  }

  @Test
  void completeRulesPass() {
    MappingIntent intent =
        new MappingIntent(
            "map-init",
            "trigger-http",
            MappingPort.OUTPUT,
            "node-call",
            MappingPort.REQUEST,
            List.of(
                new MappingIntentRule(
                    "$.orderId", "$.orderId", null, MappingRuleStatus.AUTO)));
    assertTrue(MappingContractGate.blockedMessage(intent, SOURCE, TARGET).isEmpty());
  }
}
