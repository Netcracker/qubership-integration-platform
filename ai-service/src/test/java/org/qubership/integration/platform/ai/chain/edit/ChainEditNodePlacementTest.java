package org.qubership.integration.platform.ai.chain.edit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.plan.model.ChainPlanEdge;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.ChainSection;

class ChainEditNodePlacementTest {

  @Test
  void aTriggerWithNoAnchorFansIntoTheExistingStart() {
    ChainPlanGraph base =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("c", "C"),
            List.of(node("http-trigger", "http-trigger", null), node("script", "script", null)),
            List.of(new ChainPlanEdge("edge-1", "http-trigger", "script", null)));

    ChainEditNodePlacement.Placement placement =
        ChainEditNodePlacement.addTrigger(base, List.of(), "quartz-scheduler", "Every 5 minutes");

    ChainPlanNode placed = node(placement.graph(), placement.newNodeId());
    assertEquals("quartz-scheduler", placed.type());
    assertEquals(null, placed.parentNodeId());
    assertTrue(
        placement.graph().edges().stream()
            .anyMatch(
                edge ->
                    placement.newNodeId().equals(edge.fromNodeId()) && "script".equals(edge.toNodeId())),
        "the new trigger must connect to the same successor the existing trigger already starts");
    assertTrue(
        placement.graph().edges().stream()
            .anyMatch(
                edge -> "http-trigger".equals(edge.fromNodeId()) && "script".equals(edge.toNodeId())),
        "the existing trigger-to-script edge stays");
  }

  private static ChainPlanNode node(String id, String type, String parentId) {
    return new ChainPlanNode(id, type, "Node " + id, parentId, null, List.of());
  }

  private static ChainPlanNode node(ChainPlanGraph graph, String id) {
    return graph.nodes().stream().filter(n -> id.equals(n.nodeId())).findFirst().orElseThrow();
  }
}
