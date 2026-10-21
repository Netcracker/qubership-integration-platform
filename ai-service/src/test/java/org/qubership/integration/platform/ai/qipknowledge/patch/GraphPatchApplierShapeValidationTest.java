package org.qubership.integration.platform.ai.qipknowledge.patch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.plan.model.ChainPlanEdge;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.ChainSection;

class GraphPatchApplierShapeValidationTest {

  private GraphPatchApplier applier;
  private ChainPlanGraph baseGraph;

  @BeforeEach
  void setUp() {
    applier = new GraphPatchApplier();
    baseGraph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("Greetings", "test"),
            List.of(
                new ChainPlanNode(
                    "http-trigger-1", "http-trigger", "Trigger", null, null, List.of())),
            List.of());
  }

  @Test
  void rejectsInvalidShapeWithoutThrowing() {
    GraphPatch patch =
        new GraphPatch(
            "bad-node",
            "cip-security-generator",
            List.of(new NodePatch(null, null, "http-trigger-1")),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            "Invalid nested property patch shape");

    GraphPatchApplyResult result = applier.apply(baseGraph, patch);

    assertFalse(result.applied());
    assertTrue(result.validationResult().hasBlockingIssues());
    assertTrue(
        result.validationResult().summary().contains("nodePatches[0].operation is required"));
    assertEquals(1, result.graph().nodes().size());
  }
}
