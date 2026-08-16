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

    // Always a LinkedHashSet, empty or not: an edge with no scope asks it about a null, which the
    // immutable empty set answers with an exception rather than false.
    Set<String> closure = withDescendants(base, removedNodeIds);
    List<NodePatch> expandedNodes = new ArrayList<>(nodePatches);
    for (String nodeId : closure) {
      if (!removedNodeIds.contains(nodeId)) {
        expandedNodes.add(new NodePatch(GraphPatchOperation.REMOVE, null, nodeId));
      }
    }

    Set<String> alreadyRemovedEdgeIds = new LinkedHashSet<>(removedEdgeIds(edgePatches));
    List<EdgePatch> expandedEdges = new ArrayList<>(edgePatches);
    for (ChainPlanEdge edge : incidentEdges(base, closure)) {
      if (alreadyRemovedEdgeIds.add(edge.edgeId())) {
        expandedEdges.add(new EdgePatch(GraphPatchOperation.REMOVE, null, edge.edgeId()));
      }
    }
    for (ChainPlanEdge edge : replacedEdges(base, nodePatches, edgePatches)) {
      if (alreadyRemovedEdgeIds.add(edge.edgeId())) {
        expandedEdges.add(new EdgePatch(GraphPatchOperation.REMOVE, null, edge.edgeId()));
      }
    }

    if (expandedNodes.size() == nodePatches.size() && expandedEdges.size() == edgePatches.size()) {
      return new Expansion(patch, List.of());
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

  /**
   * The connections an inserted element takes the place of.
   *
   * <p>Putting an element between two that are already joined means the join it replaces has to go,
   * and a model that forgets that leaves a fork: the chain runs both the old way round and the new
   * one. Nobody asked for a fork, so the patch is grown to what "between" actually means instead of
   * relying on the model to remember -- cutting a connection can be undone by drawing it again,
   * which is what makes deriving this safe where deriving an element removal would not be.
   *
   * <p>It fires only on the exact shape it can be sure of: the patch adds {@code N}, joins
   * {@code A -> N} and {@code N -> B}, and the chain already joins {@code A -> B}. Appending to the
   * end of a branch has no such {@code A -> B} and is left alone.
   */
  private static List<ChainPlanEdge> replacedEdges(
      ChainPlanGraph base, List<NodePatch> nodePatches, List<EdgePatch> edgePatches) {
    Set<String> addedNodeIds = addedNodeIds(nodePatches);
    if (addedNodeIds.isEmpty() || base.edges() == null) {
      return List.of();
    }
    List<ChainPlanEdge> addedEdges = addedEdges(edgePatches);
    List<ChainPlanEdge> replaced = new ArrayList<>();
    for (String addedNodeId : addedNodeIds) {
      for (ChainPlanEdge into : addedEdges) {
        if (!addedNodeId.equals(into.toNodeId())) {
          continue;
        }
        for (ChainPlanEdge outOf : addedEdges) {
          if (!addedNodeId.equals(outOf.fromNodeId())) {
            continue;
          }
          for (ChainPlanEdge existing : base.edges()) {
            if (existing != null
                && existing.edgeId() != null
                && java.util.Objects.equals(existing.fromNodeId(), into.fromNodeId())
                && java.util.Objects.equals(existing.toNodeId(), outOf.toNodeId())) {
              replaced.add(existing);
            }
          }
        }
      }
    }
    return replaced;
  }

  private static Set<String> addedNodeIds(List<NodePatch> nodePatches) {
    Set<String> added = new LinkedHashSet<>();
    for (NodePatch nodePatch : nodePatches) {
      if (nodePatch != null
          && nodePatch.operation() == GraphPatchOperation.ADD
          && nodePatch.node() != null
          && nodePatch.node().nodeId() != null) {
        added.add(nodePatch.node().nodeId());
      }
    }
    return added;
  }

  private static List<ChainPlanEdge> addedEdges(List<EdgePatch> edgePatches) {
    List<ChainPlanEdge> added = new ArrayList<>();
    for (EdgePatch edgePatch : edgePatches) {
      if (edgePatch != null
          && edgePatch.operation() == GraphPatchOperation.ADD
          && edgePatch.edge() != null) {
        added.add(edgePatch.edge());
      }
    }
    return added;
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
