package org.qubership.integration.platform.ai.plan.mapping;

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
