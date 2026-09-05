package org.qubership.integration.platform.ai.chain.edit.planning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.plan.mapping.schema.OperationSchemaMaps;
import org.qubership.integration.platform.ai.plan.model.ChainPlanEdge;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.ChainSection;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.productpipeline.create.design.planning.PlannerReportFormatException;
import org.qubership.integration.platform.ai.qipknowledge.artifact.ChainEditSubgraph;
import org.qubership.integration.platform.ai.qipknowledge.artifact.ChainEditSubgraphBody;
import org.qubership.integration.platform.ai.qipknowledge.artifact.ChainEditSubgraphBranch;
import org.qubership.integration.platform.ai.qipknowledge.artifact.ChainEditSubgraphElement;

class ChainEditStructuralPlanParserTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private final ChainEditStructuralPlanParser parser = new ChainEditStructuralPlanParser(MAPPER);
  private final ChainEditStructuralPlanValidator validator = new ChainEditStructuralPlanValidator();

  @Test
  void parsesAValidHttpPlan() {
    ChainEditStructuralPlan plan =
        parser.parse(
            """
            {
              "failureDelivery": "HTTP_RESPONSE",
              "originalTargetNodeIds": ["svc-1"],
              "tryMoveExisting": ["svc-1"],
              "catchRole": "NEW_SCRIPT",
              "catchScriptLabel": "Return HTTP error",
              "finallyMoveExisting": [],
              "rationale": "HTTP sink"
            }
            """);

    validator.validate(plan, httpGraph(), Map.of());
    assertEquals(FailureDeliveryStrategy.HTTP_RESPONSE, plan.failureDelivery());
    assertEquals(List.of("svc-1"), plan.tryMoveExisting());
  }

  @Test
  void parsesAValidOmPlan() {
    ChainEditStructuralPlan plan =
        parser.parse(
            """
            {
              "failureDelivery": "OM_TASK_RESULT",
              "originalTargetNodeIds": ["call-1"],
              "tryMoveExisting": ["call-1", "success-1"],
              "catchRole": "NEW_SCRIPT",
              "catchScriptLabel": "Prepare failed task result",
              "finallyMoveExisting": ["task-result"],
              "reporterNodeId": "task-result",
              "reporterSchemaVariant": "task-failed",
              "rationale": "unique onTaskResult"
            }
            """);

    validator.validate(plan, omGraph(), Map.of("op-result", catalogAsyncTaskResultSchema()));
    validator.validate(plan, omGraph(), Map.of("op-result", taskFailedSchema()));
    assertEquals(List.of("call-1", "success-1", "task-result"), plan.expandedTargetNodeIds());
    assertTrue(
        ChainEditStructuralPlanValidator.schemaDeclaresVariant(
            taskFailedSchema(), "task-failed"));
    assertEquals(
        List.of("executionId", "commandType", "orderId", "executionNumber", "error"),
        requiredFields(taskFailedSchema(), "task-failed"));
  }

  @Test
  void rejectsUnknownIds() {
    ChainEditStructuralPlan plan =
        parser.parse(
            """
            {
              "failureDelivery": "HTTP_RESPONSE",
              "originalTargetNodeIds": ["ghost"],
              "tryMoveExisting": ["ghost"],
              "catchRole": "NEW_SCRIPT"
            }
            """);

    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> validator.validate(plan, httpGraph(), Map.of()));
    assertTrue(error.getMessage().contains("ghost"), error.getMessage());
  }

  @Test
  void clarifyWhenSeveralSinksAreNamedAsAmbiguities() {
    ChainEditStructuralPlan plan =
        parser.parse(
            """
            {
              "failureDelivery": "CLARIFY",
              "originalTargetNodeIds": ["svc-1"],
              "ambiguities": ["HTTP response and onTaskResult"],
              "clarificationQuestion": "Which sink should receive the failure?",
              "clarificationChoices": ["HTTP error response", "OM onTaskResult"]
            }
            """);

    validator.validate(plan, httpGraph(), Map.of());
    assertTrue(plan.clarify());
  }

  @Test
  void rejectsAnUnknownSchemaVariant() {
    ChainEditStructuralPlan plan =
        parser.parse(
            """
            {
              "failureDelivery": "OM_TASK_RESULT",
              "originalTargetNodeIds": ["call-1"],
              "tryMoveExisting": ["call-1", "success-1"],
              "catchRole": "NEW_SCRIPT",
              "finallyMoveExisting": ["task-result"],
              "reporterNodeId": "task-result",
              "reporterSchemaVariant": "not-a-variant"
            }
            """);

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () -> validator.validate(plan, omGraph(), Map.of("op-result", taskFailedSchema())));
    assertTrue(error.getMessage().contains("not-a-variant"), error.getMessage());
  }

  @Test
  void rejectsNonJsonOutput() {
    assertThrows(PlannerReportFormatException.class, () -> parser.parse("not json"));
  }

  @Test
  void captureMustCopyPlannedBranchAssignments() {
    ChainEditStructuralPlan plan =
        parser.parse(
            """
            {
              "failureDelivery": "OM_TASK_RESULT",
              "originalTargetNodeIds": ["call-1"],
              "tryMoveExisting": ["call-1", "success-1"],
              "catchRole": "NEW_SCRIPT",
              "finallyMoveExisting": ["task-result"],
              "reporterNodeId": "task-result",
              "reporterSchemaVariant": "task-failed"
            }
            """);
    ChainEditSubgraph capture =
        new ChainEditSubgraph(
            "try-catch-finally-2",
            "Error handling",
            List.of(
                branch("try-2", List.of("call-1", "success-1"), List.of()),
                branch(
                    "catch-2",
                    List.of(),
                    List.of(new ChainEditSubgraphElement("failed-payload", "script", "Failed"))),
                branch("finally-2", List.of("task-result"), List.of())));

    validator.assertCaptureMatches(plan, capture);
  }

  @Test
  void captureMayNotMoveAnUnplannedId() {
    ChainEditStructuralPlan plan =
        parser.parse(
            """
            {
              "failureDelivery": "HTTP_RESPONSE",
              "originalTargetNodeIds": ["svc-1"],
              "tryMoveExisting": ["svc-1"],
              "catchRole": "NEW_SCRIPT"
            }
            """);
    ChainEditSubgraph capture =
        new ChainEditSubgraph(
            "try-catch-finally-2",
            "Error handling",
            List.of(
                branch("try-2", List.of("svc-1", "extra"), List.of()),
                branch(
                    "catch-2",
                    List.of(),
                    List.of(new ChainEditSubgraphElement("err", "script", "Error")))));

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class, () -> validator.assertCaptureMatches(plan, capture));
    assertTrue(error.getMessage().contains("extra"), error.getMessage());
  }

  private static ChainEditSubgraphBranch branch(
      String childType, List<String> moveExisting, List<ChainEditSubgraphElement> elements) {
    return new ChainEditSubgraphBranch(
        childType, childType, List.of(), null, moveExisting, new ChainEditSubgraphBody(elements, List.of()));
  }

  private static ChainPlanGraph httpGraph() {
    return new ChainPlanGraph(
        "1.0",
        new ChainSection("HTTP", "HTTP"),
        List.of(
            new ChainPlanNode("http-1", "http-trigger", "HTTP", null, null, List.of()),
            new ChainPlanNode("svc-1", "service-call", "Call", null, null, List.of())),
        List.of(new ChainPlanEdge("e1", "http-1", "svc-1", null)));
  }

  private static ChainPlanGraph omGraph() {
    return new ChainPlanGraph(
        "1.0",
        new ChainSection("OM", "OM"),
        List.of(
            new ChainPlanNode("start", "async-api-trigger", "Start", null, null, List.of()),
            new ChainPlanNode("call-1", "service-call", "Salesforce", null, null, List.of()),
            new ChainPlanNode("success-1", "script", "Success payload", null, null, List.of()),
            new ChainPlanNode(
                "task-result",
                "service-call",
                "Task result",
                null,
                null,
                List.of(new PlanProperty("integrationOperationId", "op-result")))),
        List.of(
            new ChainPlanEdge("e1", "start", "call-1", null),
            new ChainPlanEdge("e2", "call-1", "success-1", null),
            new ChainPlanEdge("e3", "success-1", "task-result", null)));
  }

  private static List<String> requiredFields(OperationSchemaMaps maps, String variant) {
    for (var schema : maps.requestByContentType().values()) {
      var options = schema.get("oneOf");
      if (options == null || !options.isArray()) {
        continue;
      }
      for (var option : options) {
        if (!variant.equals(option.path("title").asText())) {
          continue;
        }
        List<String> fields = new java.util.ArrayList<>();
        for (var field : option.path("required")) {
          fields.add(field.asText());
        }
        return fields;
      }
    }
    return List.of();
  }

  private static OperationSchemaMaps catalogAsyncTaskResultSchema() {
    ObjectNode failed = MAPPER.createObjectNode();
    failed.put("$id", "http://system.catalog/schemas/task-failed");
    ObjectNode complete = MAPPER.createObjectNode();
    complete.put("$id", "http://system.catalog/schemas/task-complete");
    return new OperationSchemaMaps(
        "op-result",
        Map.of(),
        Map.of(
            "task-failed", Map.of("application/json", failed),
            "task-complete", Map.of("application/json", complete)));
  }

  private static OperationSchemaMaps taskFailedSchema() {
    ObjectNode variant = MAPPER.createObjectNode();
    variant.put("title", "task-failed");
    ArrayNode required = variant.putArray("required");
    required.add("executionId");
    required.add("commandType");
    required.add("orderId");
    required.add("executionNumber");
    required.add("error");
    ObjectNode root = MAPPER.createObjectNode();
    ArrayNode oneOf = root.putArray("oneOf");
    oneOf.add(variant);
    return new OperationSchemaMaps("op-result", Map.of("application/json", root), Map.of());
  }
}
