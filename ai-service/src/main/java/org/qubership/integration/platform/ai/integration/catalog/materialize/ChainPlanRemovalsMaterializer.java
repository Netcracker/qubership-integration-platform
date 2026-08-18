package org.qubership.integration.platform.ai.integration.catalog.materialize;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.materialize.plan.CatalogDependencyKeys;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogDependencyDto;
import org.qubership.integration.platform.ai.plan.model.ChainPlanEdge;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;

/**
 * Deletes catalog elements and connections a patch removed.
 *
 * <p>The fourth of the materializers, and the only one whose work cannot be taken back. Both
 * deletes go through the catalog's bulk endpoints, which apply atomically -- a removal that half
 * lands is the one failure this cannot report its way out of.
 */
@ApplicationScoped
public class ChainPlanRemovalsMaterializer {

  private static final Logger LOG = Logger.getLogger(ChainPlanRemovalsMaterializer.class);

  private final CatalogRestClient catalogRestClient;

  @Inject
  public ChainPlanRemovalsMaterializer(@RestClient CatalogRestClient catalogRestClient) {
    this.catalogRestClient = Objects.requireNonNull(catalogRestClient, "catalogRestClient");
  }

  /**
   * Removes the named connections, then the named elements.
   *
   * @param before the chain as it was, needed to read the placement of what is being removed
   * @param removedNodeIds every node the patch removes, cascade included
   * @param removedEdges every edge the patch removes
   */
  public RemovalsApplyResult apply(
      ChainPlanGraph before,
      Set<String> removedNodeIds,
      List<ChainPlanEdge> removedEdges,
      MaterializationMap map) {
    Objects.requireNonNull(before, "before");
    Objects.requireNonNull(map, "map");
    Set<String> nodeIds = removedNodeIds == null ? Set.of() : removedNodeIds;
    List<ChainPlanEdge> edges = removedEdges == null ? List.of() : removedEdges;
    if (nodeIds.isEmpty() && edges.isEmpty()) {
      return new RemovalsApplyResult(List.of(), List.of(), List.of(), List.of(), null);
    }

    DependencyResolution dependencyResolution = resolveDependencyIds(edges, map);
    List<String> dependencyIds = dependencyResolution.dependencyIds();
    List<String> failedEdgeIds = new ArrayList<>();
    String error = dependencyResolution.error();
    if (error != null) {
      edges.forEach(edge -> failedEdgeIds.add(edge.edgeId()));
    }

    List<String> removedDependencyIds = List.of();
    if (error == null && !dependencyIds.isEmpty()) {
      try {
        catalogRestClient.deleteDependencies(map.chainId(), dependencyIds);
        removedDependencyIds = dependencyIds;
      } catch (RuntimeException e) {
        LOG.errorf(e, "Failed to delete dependencies in chain %s", map.chainId());
        edges.forEach(edge -> failedEdgeIds.add(edge.edgeId()));
        error = e.getMessage();
      }
    }

    // Elements last, and only once the connections are gone: this is the step nothing undoes.
    List<String> removalRoots = removalRoots(before, nodeIds, map);
    List<String> removedElementIds = List.of();
    List<String> failedNodeIds = new ArrayList<>();
    if (!removalRoots.isEmpty() && failedEdgeIds.isEmpty()) {
      try {
        catalogRestClient.deleteElements(map.chainId(), removalRoots);
        removedElementIds = elementIds(nodeIds, map);
      } catch (RuntimeException e) {
        LOG.errorf(e, "Failed to delete elements in chain %s", map.chainId());
        failedNodeIds.addAll(nodeIds);
        error = error == null ? e.getMessage() : error;
      }
    }

    return new RemovalsApplyResult(
        removedElementIds,
        removedDependencyIds,
        List.copyOf(failedNodeIds),
        List.copyOf(failedEdgeIds),
        error);
  }

  /**
   * Maps each removed edge onto the live catalog dependency it stands for.
   *
   * <p>Plan edge ids are synthesized on import, so the catalog's own id has to be looked up fresh
   * by endpoints. An edge with no live dependency is skipped rather than failed: the catalog may
   * have taken it already as a side effect of some earlier removal, and complaining about work
   * that is already done helps nobody.
   */
  private record DependencyResolution(List<String> dependencyIds, String error) {}

  private DependencyResolution resolveDependencyIds(List<ChainPlanEdge> edges, MaterializationMap map) {
    if (edges.isEmpty()) {
      return new DependencyResolution(List.of(), null);
    }
    Map<String, String> dependencyIdByEdgeKey = new LinkedHashMap<>();
    List<CatalogDependencyDto> live;
    try {
      live = catalogRestClient.listDependencies(map.chainId());
    } catch (RuntimeException e) {
      LOG.errorf(e, "listDependencies failed for chain %s", map.chainId());
      return new DependencyResolution(List.of(), e.getMessage());
    }
    for (CatalogDependencyDto dependency : live == null ? List.<CatalogDependencyDto>of() : live) {
      if (dependency != null && dependency.id != null) {
        dependencyIdByEdgeKey.put(
            CatalogDependencyKeys.edgeKey(dependency.from, dependency.to), dependency.id);
      }
    }

    Set<String> resolved = new LinkedHashSet<>();
    for (ChainPlanEdge edge : edges) {
      String fromElementId = map.nodeIdToElementId().get(edge.fromNodeId());
      String toElementId = map.nodeIdToElementId().get(edge.toNodeId());
      if (fromElementId == null || toElementId == null) {
        continue;
      }
      String dependencyId =
          dependencyIdByEdgeKey.get(CatalogDependencyKeys.edgeKey(fromElementId, toElementId));
      if (dependencyId != null) {
        resolved.add(dependencyId);
      }
    }
    return new DependencyResolution(List.copyOf(resolved), null);
  }

  /**
   * The removed nodes that have no removed ancestor.
   *
   * <p>Sending a descendant alongside its container would ask the catalog to delete something its
   * own cascade has already taken.
   */
  private static List<String> removalRoots(
      ChainPlanGraph before, Set<String> removedNodeIds, MaterializationMap map) {
    Map<String, ChainPlanNode> nodesById = new LinkedHashMap<>();
    for (ChainPlanNode node : before.nodes() == null ? List.<ChainPlanNode>of() : before.nodes()) {
      if (node != null && node.nodeId() != null) {
        nodesById.put(node.nodeId(), node);
      }
    }
    List<String> roots = new ArrayList<>();
    for (String nodeId : removedNodeIds) {
      if (!hasRemovedAncestor(nodesById, removedNodeIds, nodeId)) {
        String elementId = map.nodeIdToElementId().get(nodeId);
        if (elementId != null) {
          roots.add(elementId);
        }
      }
    }
    return List.copyOf(roots);
  }

  private static boolean hasRemovedAncestor(
      Map<String, ChainPlanNode> nodesById, Set<String> removedNodeIds, String nodeId) {
    ChainPlanNode node = nodesById.get(nodeId);
    Set<String> visited = new LinkedHashSet<>();
    while (node != null && node.parentNodeId() != null && visited.add(node.nodeId())) {
      if (removedNodeIds.contains(node.parentNodeId())) {
        return true;
      }
      node = nodesById.get(node.parentNodeId());
    }
    return false;
  }

  private static List<String> elementIds(Set<String> nodeIds, MaterializationMap map) {
    List<String> elementIds = new ArrayList<>();
    for (String nodeId : nodeIds) {
      String elementId = map.nodeIdToElementId().get(nodeId);
      elementIds.add(elementId == null ? nodeId : elementId);
    }
    return List.copyOf(elementIds);
  }

  public record RemovalsApplyResult(
      List<String> removedElementIds,
      List<String> removedDependencyIds,
      List<String> failedNodeIds,
      List<String> failedEdgeIds,
      String error) {

    public boolean succeeded() {
      return failedNodeIds.isEmpty() && failedEdgeIds.isEmpty() && error == null;
    }
  }
}
