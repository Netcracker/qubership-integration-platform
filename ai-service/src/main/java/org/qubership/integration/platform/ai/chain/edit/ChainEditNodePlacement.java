package org.qubership.integration.platform.ai.chain.edit;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.qubership.integration.platform.ai.plan.ChainPlanGraphValidator;
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

  /**
   * Adds a trigger at chain root and fans it into the same start the existing triggers already
   * share. Named {@code connectToNodeIds} are used as those start nodes when the request named them;
   * otherwise the method infers them from the current roots.
   */
  static Placement addTrigger(
      ChainPlanGraph base, List<String> connectToNodeIds, String elementType, String label) {
    Objects.requireNonNull(base, "base");
    List<String> starts = connectTo(base, connectToNodeIds);
    String newNodeId = newNodeId(elementType);
    ChainPlanNode placed = new ChainPlanNode(newNodeId, elementType, label, null, null, List.of());

    List<ChainPlanEdge> nextEdges =
        base.edges() == null ? new ArrayList<>() : new ArrayList<>(base.edges());
    for (String startId : starts) {
      nextEdges.add(new ChainPlanEdge(newEdgeId(), newNodeId, startId, null));
    }

    List<ChainPlanNode> nextNodes =
        base.nodes() == null ? new ArrayList<>() : new ArrayList<>(base.nodes());
    nextNodes.add(placed);

    ChainPlanGraph augmented =
        new ChainPlanGraph(
            base.schemaVersion(), base.chain(), List.copyOf(nextNodes), List.copyOf(nextEdges));
    return new Placement(newNodeId, augmented);
  }

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

    String newNodeId = newNodeId(elementType);
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

  private static List<String> connectTo(ChainPlanGraph base, List<String> connectToNodeIds) {
    if (connectToNodeIds != null && !connectToNodeIds.isEmpty()) {
      return requireExisting(base, connectToNodeIds);
    }
    List<String> roots = rootNodeIds(base);
    if (roots.isEmpty() || !allTriggers(base, roots)) {
      return roots;
    }
    return successorsOf(base, roots);
  }

  private static List<String> requireExisting(ChainPlanGraph base, List<String> nodeIds) {
    List<String> named = new ArrayList<>();
    for (String nodeId : nodeIds) {
      if (node(base, nodeId) == null) {
        throw new IllegalArgumentException("the chain has no element '" + nodeId + "'");
      }
      named.add(nodeId);
    }
    return named;
  }

  private static List<String> rootNodeIds(ChainPlanGraph base) {
    List<ChainPlanEdge> edges = base.edges() == null ? List.of() : base.edges();
    List<String> roots = new ArrayList<>();
    if (base.nodes() == null) {
      return roots;
    }
    for (ChainPlanNode candidate : base.nodes()) {
      if (isRoot(candidate, edges)) {
        roots.add(candidate.nodeId());
      }
    }
    return roots;
  }

  private static boolean isRoot(ChainPlanNode candidate, List<ChainPlanEdge> edges) {
    return candidate != null
        && candidate.nodeId() != null
        && candidate.parentNodeId() == null
        && !hasIncoming(edges, candidate.nodeId());
  }

  private static boolean allTriggers(ChainPlanGraph base, List<String> nodeIds) {
    for (String nodeId : nodeIds) {
      ChainPlanNode root = node(base, nodeId);
      if (root == null || !ChainPlanGraphValidator.isTriggerElementType(root.type())) {
        return false;
      }
    }
    return true;
  }

  private static List<String> successorsOf(ChainPlanGraph base, List<String> fromNodeIds) {
    List<ChainPlanEdge> edges = base.edges() == null ? List.of() : base.edges();
    List<String> successors = new ArrayList<>();
    for (String fromNodeId : fromNodeIds) {
      for (ChainPlanEdge edge : outgoingFrom(edges, fromNodeId)) {
        if (edge.toNodeId() != null && !successors.contains(edge.toNodeId())) {
          successors.add(edge.toNodeId());
        }
      }
    }
    return successors;
  }

  private static boolean hasIncoming(List<ChainPlanEdge> edges, String nodeId) {
    for (ChainPlanEdge edge : edges) {
      if (edge != null && nodeId.equals(edge.toNodeId())) {
        return true;
      }
    }
    return false;
  }

  private static String newNodeId(String elementType) {
    return elementType + "-" + UUID.randomUUID().toString().substring(0, 8);
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
