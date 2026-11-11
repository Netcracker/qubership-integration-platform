package org.qubership.integration.platform.ai.qipknowledge.patch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.plan.model.ChainPlanEdge;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.ChainSection;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;

class GraphPatchShapeValidatorTest {

  @Test
  void acceptsEmptyPatch() {
    GraphPatch patch =
        new GraphPatch(
            "empty",
            "cip-security-generator",
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            "No changes");

    assertTrue(GraphPatchShapeValidator.validate(patch).isEmpty());
  }

  @Test
  void rejectsNodePatchWithoutOperation() {
    GraphPatch patch =
        new GraphPatch(
            "bad-node",
            "cip-security-generator",
            List.of(new NodePatch(null, null, "http-trigger-1")),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            "Bad node patch");

    List<String> errors = GraphPatchShapeValidator.validate(patch);

    assertEquals(1, errors.size());
    assertEquals("nodePatches[0].operation is required", errors.get(0));
    assertTrue(GraphPatchShapeValidator.summarize(errors).contains("propertyPatches"));
  }

  @Test
  void rejectsAddNodeWithoutNodeId() {
    GraphPatch patch =
        new GraphPatch(
            "bad-add-node",
            "cip-script-generator",
            List.of(
                new NodePatch(
                    GraphPatchOperation.ADD,
                    new ChainPlanNode(null, "script", "Script", null, 1, List.of()),
                    null)),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            "Bad add node");

    List<String> errors = GraphPatchShapeValidator.validate(patch);

    assertEquals(1, errors.size());
    assertEquals("nodePatches[0].node.nodeId is required for ADD", errors.get(0));
  }

  @Test
  void rejectsPropertyPatchMissingRequiredFields() {
    GraphPatch patch =
        new GraphPatch(
            "bad-property",
            "cip-security-generator",
            List.of(),
            List.of(),
            List.of(
                new PropertyPatch(GraphPatchOperation.UPDATE, " ", new PlanProperty(" ", "RBAC"))),
            List.of(),
            List.of(),
            "Bad property patch");

    List<String> errors = GraphPatchShapeValidator.validate(patch);

    assertEquals(2, errors.size());
    assertTrue(errors.contains("propertyPatches[0].targetNodeId is required"));
    assertTrue(errors.contains("propertyPatches[0].property.key is required"));
  }
}
