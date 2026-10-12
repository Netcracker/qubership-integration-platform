package org.qubership.integration.platform.ai.chain.edit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchApplier;
import org.qubership.integration.platform.ai.qipknowledge.patch.PropertyPatch;

class ChainEditDeterministicTransformsTest {

  @Test
  void deletionTakesDescendantsAndAttachedConnectionsWithIt() {
    GraphPatch expanded =
        ChainPatchRemovalClosure.expand(
                graph(), ChainEditDeterministicTransforms.delete(graph(), List.of("try"), true))
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
  void deletingAdjacentElementsReconnectsAcrossTheWholeRun() {
    ChainPlanGraph base = linearGraph(null);
    GraphPatch patch = ChainEditDeterministicTransforms.delete(base, List.of("first", "second"), true);
    var result = new GraphPatchApplier().apply(base, patch);

    assertTrue(result.applied(), result.validationResult().summary());
    assertEquals(List.of("before", "after"), result.graph().nodes().stream().map(ChainPlanNode::nodeId).toList());
    assertEquals(1, result.graph().edges().size());
    assertEquals("before", result.graph().edges().getFirst().fromNodeId());
    assertEquals("after", result.graph().edges().getFirst().toNodeId());
  }

  @Test
  void explicitDisconnectionDoesNotCreateABridge() {
    GraphPatch patch = ChainEditDeterministicTransforms.delete(linearGraph(null), List.of("first", "second"), false);
    assertTrue(patch.edgePatches().stream().allMatch(edge -> edge.operation() == GraphPatchOperation.REMOVE));
  }

  @Test
  void deletingAContainerReconnectsOutsideItsEntireCascade() {
    ChainPlanGraph base = linearGraph(null);
    var nodes = new java.util.ArrayList<>(base.nodes());
    nodes.set(1, new ChainPlanNode("first", "try-catch-finally-2", "Container", null, null, List.of()));
    nodes.add(new ChainPlanNode("branch", "try-2", "Try", "first", null, List.of()));
    nodes.add(new ChainPlanNode("child", "script", "Child", "branch", null, List.of()));
    base = new ChainPlanGraph(base.schemaVersion(), base.chain(), nodes, base.edges());
    var result = new GraphPatchApplier().apply(base,
        ChainEditDeterministicTransforms.delete(base, List.of("first", "second"), true));
    assertTrue(result.applied(), result.validationResult().summary());
    assertEquals(List.of("before", "after"), result.graph().nodes().stream().map(ChainPlanNode::nodeId).toList());
    assertEquals(1, result.graph().edges().size());
  }

  @Test
  void anExistingBypassIsKeptWithoutADuplicate() {
    ChainPlanGraph base = linearGraph(null);
    var edges = new java.util.ArrayList<>(base.edges());
    edges.add(new ChainPlanEdge("existing-bypass", "before", "after", null));
    base = new ChainPlanGraph(base.schemaVersion(), base.chain(), base.nodes(), edges);
    var result = new GraphPatchApplier().apply(base,
        ChainEditDeterministicTransforms.delete(base, List.of("first", "second"), true));
    assertTrue(result.applied(), result.validationResult().summary());
    assertEquals(List.of("existing-bypass"), result.graph().edges().stream().map(ChainPlanEdge::edgeId).toList());
  }

  @Test
  void branchesRequireClarificationInsteadOfInventingAConnection() {
    ChainPlanGraph base = linearGraph(null);
    var edges = new java.util.ArrayList<>(base.edges());
    edges.add(new ChainPlanEdge("another-exit", "second", "before", null));
    ChainPlanGraph branching = new ChainPlanGraph(base.schemaVersion(), base.chain(), base.nodes(), edges);
    assertThrows(IllegalArgumentException.class,
        () -> ChainEditDeterministicTransforms.delete(branching, List.of("first", "second"), true));
  }

  @Test
  void separateRemovedRunsDoNotCreateTheSameBridgeTwice() {
    ChainPlanGraph base = linearGraph(null);
    base = new ChainPlanGraph(base.schemaVersion(), base.chain(), base.nodes(), List.of(
        new ChainPlanEdge("a-in", "before", "first", null),
        new ChainPlanEdge("a-out", "first", "after", null),
        new ChainPlanEdge("b-in", "before", "second", null),
        new ChainPlanEdge("b-out", "second", "after", null)));
    var result = new GraphPatchApplier().apply(base,
        ChainEditDeterministicTransforms.delete(base, List.of("first", "second"), true));
    assertTrue(result.applied(), result.validationResult().summary());
    assertEquals(1, result.graph().edges().size());
  }

  @Test
  void aBridgeKeepsTheBranchScope() {
    ChainPlanGraph base = linearGraph("try");
    var patch = ChainEditDeterministicTransforms.delete(base, List.of("first", "second"), true);
    assertEquals("try", patch.edgePatches().stream()
        .filter(edge -> edge.operation() == GraphPatchOperation.ADD).findFirst().orElseThrow().edge().scopeNodeId());
  }

  @Test
  void differentScopesCannotBeBridged() {
    ChainPlanGraph base = linearGraph(null);
    ChainPlanGraph crossScope = new ChainPlanGraph(base.schemaVersion(), base.chain(), base.nodes(),
        List.of(base.edges().get(0), base.edges().get(1), new ChainPlanEdge("out", "second", "after", "try")));
    assertThrows(IllegalArgumentException.class,
        () -> ChainEditDeterministicTransforms.delete(crossScope, List.of("first", "second"), true));
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

  private static ChainPlanGraph linearGraph(String scope) {
    return new ChainPlanGraph("1.0", new ChainSection("test", "Test"),
        List.of(
            new ChainPlanNode("before", "script", "Before", scope, null, List.of()),
            new ChainPlanNode("first", "script", "First", scope, null, List.of()),
            new ChainPlanNode("second", "script", "Second", scope, null, List.of()),
            new ChainPlanNode("after", "script", "After", scope, null, List.of())),
        List.of(
            new ChainPlanEdge("in", "before", "first", scope),
            new ChainPlanEdge("within", "first", "second", scope),
            new ChainPlanEdge("out", "second", "after", scope)));
  }
}
