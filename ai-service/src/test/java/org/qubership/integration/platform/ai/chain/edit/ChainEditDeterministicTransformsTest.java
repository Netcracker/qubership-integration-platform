package org.qubership.integration.platform.ai.chain.edit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chain.patch.ChainPatchRemovalClosure;
import org.qubership.integration.platform.ai.plan.model.ChainPlanEdge;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.ChainSection;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.qipknowledge.patch.EdgePatch;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatch;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchOperation;
import org.qubership.integration.platform.ai.qipknowledge.patch.PropertyPatch;

class ChainEditDeterministicTransformsTest {

  @Test
  void deletionTakesDescendantsAndAttachedConnectionsWithIt() {
    GraphPatch expanded =
        ChainPatchRemovalClosure.expand(
                graph(), ChainEditDeterministicTransforms.delete(List.of("try")))
            .patch();

    assertEquals(
        List.of("try", "inner"),
        expanded.nodePatches().stream()
            .filter(patch -> patch.operation() == GraphPatchOperation.REMOVE)
            .map(patch -> patch.targetNodeId())
            .toList());
    assertTrue(
        expanded.edgePatches().stream()
            .anyMatch(patch -> "edge-trigger-try".equals(patch.targetEdgeId())),
        "the connection into the deleted element goes too");
  }

  @Test
  void disconnectingOneElementCutsEveryConnectionThatTouchesIt() {
    GraphPatch patch = ChainEditDeterministicTransforms.disconnect(graph(), List.of("try"));

    assertEquals(
        List.of("edge-trigger-try"),
        patch.edgePatches().stream().map(EdgePatch::targetEdgeId).toList());
    assertTrue(patch.nodePatches().isEmpty(), "disconnecting removes no element");
  }

  @Test
  void reorderWritesCatalogPriorityInTheOrderTheBranchesWereNamed() {
    GraphPatch patch = ChainEditDeterministicTransforms.reorder(List.of("catch-b", "catch-a"));

    assertEquals(
        List.of(
            new PropertyPatch(
                GraphPatchOperation.UPDATE,
                "catch-b",
                new PlanProperty(ChainEditDeterministicTransforms.PRIORITY_PROPERTY, "0")),
            new PropertyPatch(
                GraphPatchOperation.UPDATE,
                "catch-a",
                new PlanProperty(ChainEditDeterministicTransforms.PRIORITY_PROPERTY, "1"))),
        patch.propertyPatches());
    assertTrue(patch.nodePatches().isEmpty(), "reorder adds and removes nothing");
  }

  private static ChainPlanGraph graph() {
    return new ChainPlanGraph(
        "1.0",
        new ChainSection("orders", "Orders"),
        List.of(
            new ChainPlanNode("trigger", "http-trigger", "Receive", null, null, List.of()),
            new ChainPlanNode("try", "try-2", "Try", null, null, List.of()),
            new ChainPlanNode("inner", "script", "Inner", "try", null, List.of()),
            new ChainPlanNode("catch-a", "catch-2", "Catch A", null, 0, List.of()),
            new ChainPlanNode("catch-b", "catch-2", "Catch B", null, 1, List.of())),
        List.of(new ChainPlanEdge("edge-trigger-try", "trigger", "try", null)));
  }
}
