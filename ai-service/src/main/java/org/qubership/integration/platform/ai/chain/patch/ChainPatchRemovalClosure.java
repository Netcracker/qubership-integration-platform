package org.qubership.integration.platform.ai.chain.patch;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.qubership.integration.platform.ai.plan.model.ChainPlanEdge;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.qipknowledge.patch.EdgePatch;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatch;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchOperation;
import org.qubership.integration.platform.ai.qipknowledge.patch.NodePatch;

/**
 * Grows a removal into everything the catalog will remove with it.
 *
 * <p>Deleting an element in the catalog takes its whole subtree and every connection touching it.
 * A patch that names only the element the user meant is therefore an understatement of what will
 * happen, and the graph built from it would disagree with the chain the moment the write lands --
 * while being the very graph the card, the writer and the post-write digest all read. Expanding the
 * patch first keeps all three honest, and lets the card say plainly that four elements are going,
 * not one.
 *
 * <p>It also settles an ordering problem for free: the applier refuses to remove a node while a
 * child still names it as parent, and applies removals in the order the patch lists them. Emitting
 * descendants alongside their container means that order is satisfied however the model wrote it.
 */
public final class ChainPatchRemovalClosure {

  private ChainPatchRemovalClosure() {}

  /**
   * The patch with every implied removal made explicit, and the reasons it cannot be expanded.
   *
   * <p>{@code conflicts} is non-empty only for a patch that is incoherent on its own terms -- one
   * that both adds and removes the same thing -- where any expansion would be guessing at which
   * the author meant.
   */
  public record Expansion(GraphPatch patch, List<String> conflicts) {

    public Expansion {
      conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
    }

    public boolean coherent() {
      return conflicts.isEmpty();
    }
  }

  public static Expansion expand(ChainPlanGraph base, GraphPatch patch) {
    List<NodePatch> nodePatches = patch.nodePatches() == null ? List.of() : patch.nodePatches();
    List<EdgePatch> edgePatches = patch.edgePatches() == null ? List.of() : patch.edgePatches();

    Set<String> removedNodeIds = removedNodeIds(nodePatches);
    List<String> conflicts = conflicts(nodePatches, edgePatches, removedNodeIds);
    if (!conflicts.isEmpty()) {
      return new Expansion(patch, conflicts);
    }
    if (removedNodeIds.isEmpty()) {
      return new Expansion(patch, List.of());
    }

    Set<String> closure = withDescendants(base, removedNodeIds);
    List<NodePatch> expandedNodes = new ArrayList<>(nodePatches);
    for (String nodeId : closure) {
      if (!removedNodeIds.contains(nodeId)) {
        expandedNodes.add(new NodePatch(GraphPatchOperation.REMOVE, null, nodeId));
      }
    }

    Set<String> alreadyRemovedEdgeIds = removedEdgeIds(edgePatches);
    List<EdgePatch> expandedEdges = new ArrayList<>(edgePatches);
    for (ChainPlanEdge edge : incidentEdges(base, closure)) {
      if (!alreadyRemovedEdgeIds.contains(edge.edgeId())) {
        expandedEdges.add(new EdgePatch(GraphPatchOperation.REMOVE, null, edge.edgeId()));
      }
    }

    return new Expansion(
        new GraphPatch(
            patch.patchId(),
            patch.ownerCapabilityId(),
            List.copyOf(expandedNodes),
            List.copyOf(expandedEdges),
            patch.propertyPatches(),
            patch.chainPatches(),
            patch.usedKnowledgeRefs(),
            patch.rationale()),
        List.of());
  }

  /** How many extra elements the closure pulled in beyond the ones the patch named. */
  public static int cascadeCount(ChainPlanGraph base, GraphPatch patch) {
    Set<String> named = removedNodeIds(patch.nodePatches() == null ? List.of() : patch.nodePatches());
    return named.isEmpty() ? 0 : withDescendants(base, named).size() - named.size();
  }

  private static Set<String> withDescendants(ChainPlanGraph base, Set<String> seeds) {
    Set<String> closure = new LinkedHashSet<>(seeds);
    Deque<String> pending = new ArrayDeque<>(seeds);
    List<ChainPlanNode> nodes = base.nodes() == null ? List.of() : base.nodes();
    while (!pending.isEmpty()) {
      String parentId = pending.removeFirst();
      for (ChainPlanNode node : nodes) {
        if (node != null
            && parentId.equals(node.parentNodeId())
            && node.nodeId() != null
            && closure.add(node.nodeId())) {
          pending.addLast(node.nodeId());
        }
      }
    }
    return closure;
  }

  private static List<ChainPlanEdge> incidentEdges(ChainPlanGraph base, Set<String> nodeIds) {
    List<ChainPlanEdge> incident = new ArrayList<>();
    for (ChainPlanEdge edge : base.edges() == null ? List.<ChainPlanEdge>of() : base.edges()) {
      if (edge == null || edge.edgeId() == null) {
        continue;
      }
      if (nodeIds.contains(edge.fromNodeId())
          || nodeIds.contains(edge.toNodeId())
          || nodeIds.contains(edge.scopeNodeId())) {
        incident.add(edge);
      }
    }
    return incident;
  }

  private static List<String> conflicts(
      List<NodePatch> nodePatches, List<EdgePatch> edgePatches, Set<String> removedNodeIds) {
    List<String> conflicts = new ArrayList<>();
    for (NodePatch nodePatch : nodePatches) {
      if (nodePatch != null
          && nodePatch.operation() == GraphPatchOperation.ADD
          && nodePatch.node() != null
          && removedNodeIds.contains(nodePatch.node().nodeId())) {
        conflicts.add("node '" + nodePatch.node().nodeId() + "' is both added and removed");
      }
    }
    Set<String> removedEdgeIds = removedEdgeIds(edgePatches);
    for (EdgePatch edgePatch : edgePatches) {
      if (edgePatch != null
          && edgePatch.operation() == GraphPatchOperation.ADD
          && edgePatch.edge() != null
          && removedEdgeIds.contains(edgePatch.edge().edgeId())) {
        conflicts.add("edge '" + edgePatch.edge().edgeId() + "' is both added and removed");
      }
    }
    return conflicts;
  }

  private static Set<String> removedNodeIds(List<NodePatch> nodePatches) {
    Set<String> removed = new LinkedHashSet<>();
    for (NodePatch nodePatch : nodePatches) {
      if (nodePatch != null
          && nodePatch.operation() == GraphPatchOperation.REMOVE
          && nodePatch.targetNodeId() != null) {
        removed.add(nodePatch.targetNodeId());
      }
    }
    return removed;
  }

  private static Set<String> removedEdgeIds(List<EdgePatch> edgePatches) {
    Set<String> removed = new LinkedHashSet<>();
    for (EdgePatch edgePatch : edgePatches) {
      if (edgePatch != null
          && edgePatch.operation() == GraphPatchOperation.REMOVE
          && edgePatch.targetEdgeId() != null) {
        removed.add(edgePatch.targetEdgeId());
      }
    }
    return removed;
  }
}
