package org.qubership.integration.platform.ai.chain.edit;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.qubership.integration.platform.ai.chain.patch.ChainPatchRemovalClosure;
import org.qubership.integration.platform.ai.plan.model.ChainPlanEdge;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.qipknowledge.patch.EdgePatch;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatch;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchOperation;
import org.qubership.integration.platform.ai.qipknowledge.patch.NodePatch;
import org.qubership.integration.platform.ai.qipknowledge.patch.PropertyPatch;

/**
 * Edits the platform already knows how to make.
 *
 * <p>Deleting an element, cutting a connection and reordering branches are mechanics, not domain
 * decisions: the catalog defines exactly what each one does, and a model asked to encode them adds
 * only the chance of getting them wrong. What the model is still needed for is which element the
 * reader meant, and that has already been settled by the time these run.
 */
public final class ChainEditDeterministicTransforms {

  /** Catalog property that orders sibling branches; {@code OrderedElementService} renumbers from it. */
  public static final String PRIORITY_PROPERTY = "priority";

  private static final String OWNER = "chain-edit-transform";

  private ChainEditDeterministicTransforms() {}

  /**
   * Removes the catalog cascade and bypasses each removed run when its surviving neighbors are unambiguous.
   */
  public static GraphPatch delete(
      ChainPlanGraph graph, List<String> targetNodeIds, boolean reconnectNeighbors) {
    List<NodePatch> removals = new ArrayList<>();
    for (String nodeId : targetNodeIds) {
      removals.add(new NodePatch(GraphPatchOperation.REMOVE, null, nodeId));
    }
    GraphPatch deletion =
        ChainPatchRemovalClosure.expand(
            graph, patch(removals, List.of(), List.of(), "removes " + String.join(", ", targetNodeIds)))
            .patch();
    if (!reconnectNeighbors) {
      return deletion;
    }
    Set<String> remaining = new LinkedHashSet<>();
    deletion.nodePatches().forEach(removal -> remaining.add(removal.targetNodeId()));
    List<ChainPlanEdge> edges = graph.edges() == null ? List.of() : graph.edges();
    List<EdgePatch> changes = new ArrayList<>(deletion.edgePatches());
    while (!remaining.isEmpty()) {
      Set<String> run = new LinkedHashSet<>();
      ArrayDeque<String> pending = new ArrayDeque<>();
      pending.add(remaining.iterator().next());
      while (!pending.isEmpty()) {
        String id = pending.removeFirst();
        if (!remaining.remove(id)) {
          continue;
        }
        run.add(id);
        for (ChainPlanEdge edge : edges) {
          if (id.equals(edge.fromNodeId()) && remaining.contains(edge.toNodeId())) {
            pending.add(edge.toNodeId());
          }
          if (id.equals(edge.toNodeId()) && remaining.contains(edge.fromNodeId())) {
            pending.add(edge.fromNodeId());
          }
        }
        // Containment joins a container's cascade even when its branches have no execution edges.
        for (ChainPlanNode node : graph.nodes()) {
          if (id.equals(node.parentNodeId()) && remaining.contains(node.nodeId())) {
            pending.add(node.nodeId());
          }
          if (id.equals(node.nodeId()) && remaining.contains(node.parentNodeId())) {
            pending.add(node.parentNodeId());
          }
        }
      }
      List<ChainPlanEdge> incoming = edges.stream()
          .filter(edge -> run.contains(edge.toNodeId()) && !run.contains(edge.fromNodeId())).toList();
      List<ChainPlanEdge> outgoing = edges.stream()
          .filter(edge -> run.contains(edge.fromNodeId()) && !run.contains(edge.toNodeId())).toList();
      if (incoming.isEmpty() || outgoing.isEmpty()) {
        continue;
      }
      if (incoming.size() != 1 || outgoing.size() != 1) {
        throw new IllegalArgumentException(
            "Deletion has several surviving connections around " + String.join(", ", targetNodeIds)
                + ". Should the elements be deleted without reconnecting their neighbors?");
      }
      ChainPlanEdge before = incoming.getFirst();
      ChainPlanEdge after = outgoing.getFirst();
      String source = before.fromNodeId();
      String target = after.toNodeId();
      String sourceParent = graph.nodes().stream().filter(node -> source.equals(node.nodeId()))
          .findFirst().orElseThrow().parentNodeId();
      String targetParent = graph.nodes().stream().filter(node -> target.equals(node.nodeId()))
          .findFirst().orElseThrow().parentNodeId();
      if (source.equals(target) || !Objects.equals(before.scopeNodeId(), after.scopeNodeId())
          || !Objects.equals(sourceParent, targetParent)) {
        throw new IllegalArgumentException(
            "Deletion cannot reconnect neighbors across scopes or into a loop."
                + " Should the elements be deleted without reconnecting their neighbors?");
      }
      if (edges.stream().anyMatch(edge -> source.equals(edge.fromNodeId())
          && target.equals(edge.toNodeId()) && Objects.equals(before.scopeNodeId(), edge.scopeNodeId()))
          || changes.stream().anyMatch(change -> change.operation() == GraphPatchOperation.ADD
              && source.equals(change.edge().fromNodeId()) && target.equals(change.edge().toNodeId())
              && Objects.equals(before.scopeNodeId(), change.edge().scopeNodeId()))) {
        continue;
      }
      String edgeId = "delete-bridge-" + UUID.nameUUIDFromBytes(
          (before.edgeId() + ":" + after.edgeId()).getBytes(StandardCharsets.UTF_8));
      changes.add(new EdgePatch(GraphPatchOperation.ADD,
          new ChainPlanEdge(edgeId, source, target, before.scopeNodeId()), null));
    }
    return patch(deletion.nodePatches(), changes, List.of(),
        deletion.rationale() + " and reconnects surviving neighbors");
  }

  /**
   * Cuts every connection between the named elements, leaving the elements themselves.
   *
   * <p>Two targets mean the connections that run between them. One target means every connection
   * that touches it, which is what "disconnect this step" asks for.
   */
  public static GraphPatch disconnect(ChainPlanGraph graph, List<String> targetNodeIds) {
    Set<String> targets = new LinkedHashSet<>(targetNodeIds);
    List<EdgePatch> cuts = new ArrayList<>();
    for (ChainPlanEdge edge : graph.edges() == null ? List.<ChainPlanEdge>of() : graph.edges()) {
      if (edge == null || edge.edgeId() == null) {
        continue;
      }
      boolean bothEnds = targets.contains(edge.fromNodeId()) && targets.contains(edge.toNodeId());
      boolean oneEnd =
          targets.size() == 1
              && (targets.contains(edge.fromNodeId()) || targets.contains(edge.toNodeId()));
      if (bothEnds || oneEnd) {
        cuts.add(new EdgePatch(GraphPatchOperation.REMOVE, null, edge.edgeId()));
      }
    }
    return patch(
        List.of(), cuts, List.of(), "disconnects " + String.join(", ", targetNodeIds));
  }

  /**
   * Puts the named branches in the order they were named.
   *
   * <p>Priority is written as the ordinary catalog property rather than as plan order: plan order
   * never reaches the catalog, and {@code OrderedElementService} renumbers siblings from the
   * property. Branches the request did not name keep the priority they had.
   */
  public static GraphPatch reorder(List<String> targetNodeIds) {
    List<PropertyPatch> priorities = new ArrayList<>();
    for (int index = 0; index < targetNodeIds.size(); index++) {
      priorities.add(
          new PropertyPatch(
              GraphPatchOperation.UPDATE,
              targetNodeIds.get(index),
              new PlanProperty(PRIORITY_PROPERTY, String.valueOf(index))));
    }
    return patch(
        List.of(), List.of(), priorities, "reorders " + String.join(", ", targetNodeIds));
  }

  private static GraphPatch patch(
      List<NodePatch> nodePatches,
      List<EdgePatch> edgePatches,
      List<PropertyPatch> propertyPatches,
      String rationale) {
    return new GraphPatch(
        OWNER + ":" + Integer.toHexString(rationale.hashCode()),
        OWNER,
        nodePatches,
        edgePatches,
        propertyPatches,
        List.of(),
        List.of(),
        rationale);
  }
}
