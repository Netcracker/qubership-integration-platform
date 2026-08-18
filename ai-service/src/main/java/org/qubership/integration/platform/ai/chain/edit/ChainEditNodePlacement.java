package org.qubership.integration.platform.ai.chain.edit;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.qubership.integration.platform.ai.plan.model.ChainPlanEdge;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;

/**
 * Places a bare node the graph does not have yet, for a generator whose capture tool can only
 * configure a node that already exists.
 *
 * <p>{@code cip-script-generator}'s capture tool is {@code repairScriptBodies}: it fills the
 * {@code script} property of a node the graph already carries, and has no shape for adding one.
 * CREATE never notices, because its structure generator places every node — including empty
 * script shells — before any repair generator runs. An edit has no structure generator in its cut
 * DAG, so the shell has to exist before the compiler run starts, not come out of it.
 *
 * <p>The new node is wired next to the first named anchor: appended after it when the anchor has
 * no single successor, spliced between the anchor and its one successor otherwise. It inherits the
 * anchor's container, so a node placed inside a branch stays inside that branch.
 */
final class ChainEditNodePlacement {

  private ChainEditNodePlacement() {}

  record Placement(String newNodeId, ChainPlanGraph graph) {}

  static Placement insertAfter(
      ChainPlanGraph base, List<String> anchorNodeIds, String elementType, String label) {
    Objects.requireNonNull(base, "base");
    if (anchorNodeIds == null || anchorNodeIds.isEmpty()) {
      throw new IllegalArgumentException("at least one anchor node id is required");
    }
    String anchorId = anchorNodeIds.get(0);
    ChainPlanNode anchor = node(base, anchorId);
    if (anchor == null) {
      throw new IllegalArgumentException("the chain has no element '" + anchorId + "'");
    }

    String newNodeId = elementType + "-" + UUID.randomUUID().toString().substring(0, 8);
    ChainPlanNode placed =
        new ChainPlanNode(newNodeId, elementType, label, anchor.parentNodeId(), null, List.of());

    List<ChainPlanEdge> edges =
        base.edges() == null ? new ArrayList<>() : new ArrayList<>(base.edges());
    List<ChainPlanEdge> outgoing = outgoingFrom(edges, anchorId);

    List<ChainPlanEdge> nextEdges = new ArrayList<>(edges);
    if (outgoing.size() == 1) {
      ChainPlanEdge replaced = outgoing.get(0);
      nextEdges.remove(replaced);
      nextEdges.add(new ChainPlanEdge(replaced.edgeId(), anchorId, newNodeId, replaced.scopeNodeId()));
      nextEdges.add(
          new ChainPlanEdge(
              newEdgeId(), newNodeId, replaced.toNodeId(), replaced.scopeNodeId()));
    } else {
      nextEdges.add(new ChainPlanEdge(newEdgeId(), anchorId, newNodeId, anchor.parentNodeId()));
    }

    List<ChainPlanNode> nextNodes =
        base.nodes() == null ? new ArrayList<>() : new ArrayList<>(base.nodes());
    nextNodes.add(placed);

    ChainPlanGraph augmented =
        new ChainPlanGraph(base.schemaVersion(), base.chain(), List.copyOf(nextNodes), List.copyOf(nextEdges));
    return new Placement(newNodeId, augmented);
  }

  private static List<ChainPlanEdge> outgoingFrom(List<ChainPlanEdge> edges, String nodeId) {
    List<ChainPlanEdge> found = new ArrayList<>();
    for (ChainPlanEdge edge : edges) {
      if (edge != null && nodeId.equals(edge.fromNodeId())) {
        found.add(edge);
      }
    }
    return found;
  }

  private static ChainPlanNode node(ChainPlanGraph graph, String nodeId) {
    if (graph.nodes() == null) {
      return null;
    }
    return graph.nodes().stream()
        .filter(candidate -> candidate != null && nodeId.equals(candidate.nodeId()))
        .findFirst()
        .orElse(null);
  }

  private static String newEdgeId() {
    return "edge-" + UUID.randomUUID().toString().substring(0, 8);
  }
}
