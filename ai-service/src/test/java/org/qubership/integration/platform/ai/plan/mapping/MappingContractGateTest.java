package org.qubership.integration.platform.ai.plan.mapping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.qubership.integration.platform.ai.productpipeline.capability.RecoveryCause;
import org.qubership.integration.platform.ai.productpipeline.runtime.RecoveryAttemptLedger;
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

  @ParameterizedTest
  @EnumSource(value = MappingFindingCode.class, names = {"MAPPING_UNKNOWN_TARGET", "MAPPING_INVALID_SOURCE"})
  void mappingFindingsKeepTheirTypedFieldIdentityWhenWordingAndOtherFieldsChange(MappingFindingCode code) {
    var original = new MappingRuleFinding(code, "The field is absent.", true, "mapping-A",
        "source", MappingPort.OUTPUT, "target", MappingPort.REQUEST, "$.sourceA", "$.targetA",
        "", "", "", "");
    var reworded = new MappingRuleFinding(code, "Correct this mapping field.", true, "mapping-A",
        "source", MappingPort.OUTPUT, "target", MappingPort.REQUEST,
        code == MappingFindingCode.MAPPING_INVALID_SOURCE ? "$.sourceA" : "$.sourceB",
        code == MappingFindingCode.MAPPING_INVALID_SOURCE ? "$.targetB" : "$.targetA", "", "", "", "");
    var first = MappingContractGate.toPlanFinding(original, null, null, "brief-a", "hash-a");
    var changed = MappingContractGate.toPlanFinding(reworded, null, null, "brief-b", "hash-b");
    assertEquals("mapping-A", first.originRecordId());
    assertEquals(code == MappingFindingCode.MAPPING_INVALID_SOURCE ? "$.sourceA" : "$.targetA", first.fieldPointer());
    assertEquals(RecoveryAttemptLedger.evidenceIdentity(RecoveryCause.mappingContract(List.of(first))),
        RecoveryAttemptLedger.evidenceIdentity(RecoveryCause.mappingContract(List.of(changed))));
  }

  private static MappingContract contractFrom(String schema) {
    try {
      return JsonSchemaMappingContractFactory.from(MAPPER.readTree(schema));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  void retainedInputExpressionsSupplyRequiredResponseFields() {
    MappingContract response = contractFrom("""
        {"type":"object","properties":{"id":{"type":"string"}},"required":["id"]}
        """);
    MappingContract delivery = contractFrom("""
        {"type":"object","properties":{"executionId":{"type":"string"},
        "orderId":{"type":"string"}},"required":["executionId","orderId"]}
        """);
    MappingIntent missing = new MappingIntent("result", "create-record", MappingPort.RESPONSE,
        "send-result", MappingPort.REQUEST, List.of());
    assertTrue(MappingContractGate.evaluate(missing, response, delivery).blocked());

    List<MappingIntentRule> rules = List.of(
        new MappingIntentRule(null, "executionId", "Read executionId saved from entry before create-record."),
        new MappingIntentRule(null, "orderId", "Read orderId saved from entry before create-record."));
    MappingIntent retained = missing.withRules(rules);
    MappingContractEvaluation evaluated = MappingContractGate.evaluate(retained, response, delivery);

    assertFalse(evaluated.blocked(), evaluated.blockedMessage());
    List<MappingIntentRule> accepted = evaluated.intent().orElseThrow().rules();
    assertEquals(2, accepted.size());
    assertTrue(accepted.stream().allMatch(rule -> rule.sourcePath().isBlank()));
    assertTrue(accepted.stream().allMatch(rule -> rule.expression().contains("saved from entry")));
    assertTrue(accepted.stream().allMatch(rule -> rule.status() != MappingRuleStatus.UNRESOLVED));
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
  void additionalFieldInsideOpenParametersObjectDoesNotBlock() {
    MappingContract source =
        contractFrom(
            """
            {
              "type": "object",
              "properties": { "id": { "type": "string" } }
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
                    "parameters": {
                      "type": "object",
                      "properties": {}
                    }
                  }
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
                    "id",
                    "parameters.salesforceTaskId",
                    null,
                    MappingRuleStatus.PROPOSED)));

    MappingContractEvaluation evaluated = MappingContractGate.evaluate(intent, source, target);

    assertFalse(evaluated.blocked(), evaluated.blockedMessage());
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
  void inventedFieldOnClosedEmptyTargetIsUnknownTarget() {
    MappingContract closedEmpty =
        contractFrom(
            """
            {"type":"object","properties":{},"additionalProperties":false}
            """);
    MappingIntent intent =
        new MappingIntent(
            "map-init",
            "trigger-http",
            MappingPort.OUTPUT,
            "node-call",
            MappingPort.REQUEST,
            List.of(
                new MappingIntentRule(
                    "$.orderId", "$.invented", null, MappingRuleStatus.PROPOSED)));
    MappingContractEvaluation evaluated = MappingContractGate.evaluate(intent, SOURCE, closedEmpty);
    assertEquals(
        MappingFindingCode.MAPPING_UNKNOWN_TARGET,
        evaluated.blockerFindings().stream()
            .filter(finding -> "$.invented".equals(finding.targetPath()))
            .findFirst()
            .orElseThrow()
            .code());
    assertTrue(evaluated.blocked());
  }

  @Test
  void propertyLessTargetSchemaDoesNotInventUnknownTarget() {
    MappingContract emptyTarget =
        contractFrom(
            """
            {
              "type": "object"
            }
            """);
    MappingIntent intent =
        new MappingIntent(
            "map-init",
            "trigger-http",
            MappingPort.OUTPUT,
            "node-call",
            MappingPort.REQUEST,
            List.of(
                new MappingIntentRule(
                    "$.orderId", "$.orderId", null, MappingRuleStatus.PROPOSED)));
    MappingContractEvaluation evaluated =
        MappingContractGate.evaluate(intent, SOURCE, emptyTarget);
    assertTrue(
        evaluated.findings().stream()
            .noneMatch(
                finding ->
                    finding.code() == MappingFindingCode.MAPPING_UNKNOWN_TARGET
                        || finding.code() == MappingFindingCode.MAPPING_MISSING_REQUIRED_TARGET),
        evaluated.findings().toString());
    assertFalse(evaluated.blocked());
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
