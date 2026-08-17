package org.qubership.integration.platform.ai.chain.edit;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.qubership.integration.platform.ai.plan.model.ChainPlanEdge;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
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
   * Removes the named elements. Descendants, dependencies and attached connections are added by the
   * removal closure, which mirrors what the catalog cascades.
   */
  public static GraphPatch delete(List<String> targetNodeIds) {
    List<NodePatch> removals = new ArrayList<>();
    for (String nodeId : targetNodeIds) {
      removals.add(new NodePatch(GraphPatchOperation.REMOVE, null, nodeId));
    }
    return patch(removals, List.of(), List.of(), "removes " + String.join(", ", targetNodeIds));
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
