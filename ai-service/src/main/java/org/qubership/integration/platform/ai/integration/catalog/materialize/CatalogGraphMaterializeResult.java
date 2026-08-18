package org.qubership.integration.platform.ai.integration.catalog.materialize;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.qubership.integration.platform.ai.plan.model.ChainPlanEdge;

/** Outcome of converging a current plan graph into a desired plan graph in the catalog. */
public record CatalogGraphMaterializeResult(
    MaterializationMap materializationMap,
    List<String> changedNodeIds,
    List<String> failedNodeIds,
    String error,
    List<String> removedElementIds,
    List<String> createdNodeIds,
    Map<String, Set<String>> changedKeysByNodeId,
    List<ChainPlanEdge> createdEdges,
    List<ChainPlanEdge> recreatableEdges,
    boolean noOp) {

  public CatalogGraphMaterializeResult {
    changedNodeIds = changedNodeIds == null ? List.of() : List.copyOf(changedNodeIds);
    failedNodeIds = failedNodeIds == null ? List.of() : List.copyOf(failedNodeIds);
    removedElementIds = removedElementIds == null ? List.of() : List.copyOf(removedElementIds);
    createdNodeIds = createdNodeIds == null ? List.of() : List.copyOf(createdNodeIds);
    changedKeysByNodeId = changedKeysByNodeId == null ? Map.of() : Map.copyOf(changedKeysByNodeId);
    createdEdges = createdEdges == null ? List.of() : List.copyOf(createdEdges);
    recreatableEdges = recreatableEdges == null ? List.of() : List.copyOf(recreatableEdges);
  }

  public boolean succeeded() {
    return failedNodeIds.isEmpty() && error == null;
  }

  public static CatalogGraphMaterializeResult noOp(MaterializationMap map) {
    return new CatalogGraphMaterializeResult(
        map, List.of(), List.of(), null, List.of(), List.of(), Map.of(), List.of(), List.of(), true);
  }
}
