package org.qubership.integration.platform.ai.chain.edit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.plan.model.ChainPlanEdge;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.ChainSection;

class ChainEditNodePlacementTest {

  @Test
  void insertsAfterALeafAnchorWithNoRewiring() {
    ChainEditNodePlacement.Placement placement =
        ChainEditNodePlacement.insertAfter(leafGraph(), List.of("a"), "script", "New script");

    ChainPlanGraph graph = placement.graph();
    assertEquals(2, graph.nodes().size());
    ChainPlanNode placed = node(graph, placement.newNodeId());
    assertEquals("script", placed.type());
    assertEquals("New script", placed.label());
    assertEquals(List.of(), placed.properties());
    assertEquals(
        List.of(new ChainPlanEdge(graph.edges().get(0).edgeId(), "a", placement.newNodeId(), null)),
        graph.edges());
  }

  @Test
  void spliceBetweenAnAnchorAndItsOneSuccessorPreservesTheEdgeIdAndScope() {
    ChainPlanGraph base =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("c", "C"),
            List.of(
                node("a", null),
                node("b", null)),
            List.of(new ChainPlanEdge("edge-1", "a", "b", "container-1")));

    ChainEditNodePlacement.Placement placement =
        ChainEditNodePlacement.insertAfter(base, List.of("a"), "script", "New script");

    assertEquals(
        List.of(
            new ChainPlanEdge("edge-1", "a", placement.newNodeId(), "container-1")),
        placement.graph().edges().stream().filter(e -> "edge-1".equals(e.edgeId())).toList());
    assertTrue(
        placement.graph().edges().stream()
            .anyMatch(
                e ->
                    placement.newNodeId().equals(e.fromNodeId())
                        && "b".equals(e.toNodeId())
                        && "container-1".equals(e.scopeNodeId())),
        "the cut edge's successor is reconnected from the new node, in the same scope");
  }

  @Test
  void thePlacedNodeInheritsTheAnchorsContainer() {
    ChainPlanGraph base =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("c", "C"),
            List.of(node("a", "container-1")),
            List.of());

    ChainEditNodePlacement.Placement placement =
        ChainEditNodePlacement.insertAfter(base, List.of("a"), "script", "New script");

    assertEquals(
        "container-1", node(placement.graph(), placement.newNodeId()).parentNodeId());
  }

  @Test
  void anUnknownAnchorIsRejected() {
    assertThrows(
        IllegalArgumentException.class,
        () -> ChainEditNodePlacement.insertAfter(leafGraph(), List.of("missing"), "script", "x"));
  }

  private static ChainPlanGraph leafGraph() {
    return new ChainPlanGraph(
        "1.0", new ChainSection("c", "C"), List.of(node("a", null)), List.of());
  }

  private static ChainPlanNode node(String id, String parentId) {
    return new ChainPlanNode(id, "script", "Node " + id, parentId, null, List.of());
  }

  private static ChainPlanNode node(ChainPlanGraph graph, String id) {
    return graph.nodes().stream().filter(n -> id.equals(n.nodeId())).findFirst().orElseThrow();
  }
}
