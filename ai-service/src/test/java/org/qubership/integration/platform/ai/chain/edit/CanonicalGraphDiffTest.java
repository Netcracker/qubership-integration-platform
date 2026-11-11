package org.qubership.integration.platform.ai.chain.edit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.plan.model.ChainPlanEdge;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.ChainSection;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.qipknowledge.patch.EdgePatch;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatch;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchApplier;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchOperation;
import org.qubership.integration.platform.ai.qipknowledge.patch.NodePatch;
import org.qubership.integration.platform.ai.qipknowledge.patch.PropertyPatch;

class CanonicalGraphDiffTest {

  @Test
  void anUnchangedGraphDiffsToNothing() {
    assertTrue(CanonicalGraphDiff.isEmpty(diff(graph(nodeA()), graph(nodeA()))));
  }

  @Test
  void aPropertyAddedUpdatedAndRemovedEachGetsItsOwnOperation() {
    ChainPlanNode after =
        new ChainPlanNode(
            "a",
            "script",
            "A",
            null,
            null,
            List.of(new PlanProperty("script", "return 2"), new PlanProperty("label", "new")));

    GraphPatch patch = diff(graph(nodeA()), graph(after));

    assertEquals(
        List.of(
            new PropertyPatch(
                GraphPatchOperation.UPDATE, "a", new PlanProperty("script", "return 2")),
            new PropertyPatch(GraphPatchOperation.ADD, "a", new PlanProperty("label", "new")),
            new PropertyPatch(
                GraphPatchOperation.REMOVE, "a", new PlanProperty("retryCount", "3"))),
        patch.propertyPatches());
  }

  @Test
  void addedAndRemovedNodesAndEdgesAppearAsSuch() {
    ChainPlanNode added = new ChainPlanNode("b", "script", "B", null, null, List.of());
    ChainPlanGraph before = graph(nodeA());
    ChainPlanGraph after =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("c", "C"),
            List.of(added),
            List.of(new ChainPlanEdge("e-1", "b", "b", null)));

    GraphPatch patch = diff(before, after);

    assertEquals(
        List.of(
            new NodePatch(GraphPatchOperation.ADD, added, null),
            new NodePatch(GraphPatchOperation.REMOVE, null, "a")),
        patch.nodePatches());
    assertEquals(
        List.of(
            new EdgePatch(
                GraphPatchOperation.ADD, new ChainPlanEdge("e-1", "b", "b", null), null)),
        patch.edgePatches());
  }

  @Test
  void aRelabeledNodeUpdatesItsIdentityWithoutRestatingItsProperties() {
    ChainPlanNode after =
        new ChainPlanNode(
            "a", "script", "Renamed", null, null, List.of(new PlanProperty("retryCount", "3")));

    GraphPatch patch = diff(graph(nodeA()), graph(after));

    assertEquals(1, patch.nodePatches().size());
    assertEquals(GraphPatchOperation.UPDATE, patch.nodePatches().get(0).operation());
    assertEquals("Renamed", patch.nodePatches().get(0).node().label());
    assertEquals(List.of(), patch.nodePatches().get(0).node().properties());
  }

  @Test
  void theDiffAppliedToTheBaseReproducesTheCompiledGraph() {
    ChainPlanNode after =
        new ChainPlanNode(
            "a",
            "script",
            "Renamed",
            null,
            null,
            List.of(new PlanProperty("script", "return 2"), new PlanProperty("label", "new")));
    ChainPlanGraph base = graph(nodeA());
    ChainPlanGraph compiled = graph(after);

    var applied = new GraphPatchApplier().apply(base, diff(base, compiled));

    assertTrue(applied.validationResult().valid(), applied.validationResult().summary());
    assertEquals(compiled.nodes(), applied.graph().nodes());
  }

  private static GraphPatch diff(ChainPlanGraph base, ChainPlanGraph result) {
    return CanonicalGraphDiff.between(base, result, "patch-1", "owner", "why");
  }

  private static ChainPlanNode nodeA() {
    return new ChainPlanNode(
        "a",
        "script",
        "A",
        null,
        null,
        List.of(new PlanProperty("script", "return 1"), new PlanProperty("retryCount", "3")));
  }

  private static ChainPlanGraph graph(ChainPlanNode node) {
    return new ChainPlanGraph("1.0", new ChainSection("c", "C"), List.of(node), List.of());
  }
}
