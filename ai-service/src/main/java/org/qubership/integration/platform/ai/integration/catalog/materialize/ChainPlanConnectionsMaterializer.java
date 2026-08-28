package org.qubership.integration.platform.ai.integration.catalog.materialize;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.materialize.plan.CatalogDependencyKeys;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogCreateDependencyRequest;
import org.qubership.integration.platform.ai.plan.model.ChainPlanEdge;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;

/** Creates catalog dependencies from plan graph edges. */
@ApplicationScoped
public class ChainPlanConnectionsMaterializer {

  private static final Logger LOG = Logger.getLogger(ChainPlanConnectionsMaterializer.class);

  private final CatalogRestClient catalogRestClient;

  @Inject
  public ChainPlanConnectionsMaterializer(@RestClient CatalogRestClient catalogRestClient) {
    this.catalogRestClient = catalogRestClient;
  }

  public ConnectionsApplyResult apply(ChainPlanGraph graph, MaterializationMap map) {
    Objects.requireNonNull(graph, "graph");
    Objects.requireNonNull(map, "map");

    if (graph.edges() == null || graph.edges().isEmpty()) {
      return new ConnectionsApplyResult(0, List.of());
    }

    Map<String, ChainPlanNode> nodesById = nodesById(graph);
    int createdCount = 0;
    List<String> failedEdgeIds = new ArrayList<>();
    boolean listDependenciesFailed = false;
    Set<String> existing = Set.of();
    try {
      existing = CatalogDependencyKeys.edgeKeysFromDependencies(
          catalogRestClient.listDependencies(map.chainId()));
    } catch (Exception e) {
      listDependenciesFailed = true;
      LOG.warnf(e, "listDependencies failed chainId=%s", map.chainId());
    }

    for (ChainPlanEdge edge : graph.edges()) {
      Projection projection = classify(edge, nodesById, map);
      switch (projection.action()) {
        case SKIP_STRUCTURAL -> {
          /* containment is placement, not a catalog dependency */ }
        case SKIP_NON_DEPENDENCY, FAIL_INVALID -> failedEdgeIds.add(edge.edgeId());
        case CREATE -> {
          if (listDependenciesFailed) {
            failedEdgeIds.add(edge.edgeId());
            continue;
          }
          String edgeKey = CatalogDependencyKeys.edgeKey(
              projection.fromElementId(), projection.toElementId());
          if (existing.contains(edgeKey)) {
            continue;
          }
          try {
            catalogRestClient.createConnection(
                map.chainId(),
                new CatalogCreateDependencyRequest(
                    projection.fromElementId(), projection.toElementId()));
            createdCount++;
          } catch (Exception e) {
            failedEdgeIds.add(edge.edgeId());
          }
        }
      }
    }

    return new ConnectionsApplyResult(createdCount, List.copyOf(failedEdgeIds));
  }

  /**
   * Same CREATE / skip / fail decision as {@link #apply}. An UPDATE only replaces a catalog
   * dependency when ADD would have written one.
   */
  public static Projection project(
      ChainPlanEdge edge, ChainPlanGraph graph, MaterializationMap map) {
    Objects.requireNonNull(edge, "edge");
    Objects.requireNonNull(graph, "graph");
    Objects.requireNonNull(map, "map");
    return classify(edge, nodesById(graph), map);
  }

  private static Map<String, ChainPlanNode> nodesById(ChainPlanGraph graph) {
    Map<String, ChainPlanNode> index = new LinkedHashMap<>();
    if (graph.nodes() == null) {
      return index;
    }
    for (ChainPlanNode node : graph.nodes()) {
      if (node.nodeId() != null) {
        index.put(node.nodeId(), node);
      }
    }
    return index;
  }

  private static Projection classify(
      ChainPlanEdge edge, Map<String, ChainPlanNode> nodesById, MaterializationMap map) {
    ChainPlanNode from = nodesById.get(edge.fromNodeId());
    ChainPlanNode to = nodesById.get(edge.toNodeId());
    if (from == null || to == null) {
      return new Projection(ProjectionAction.FAIL_INVALID, null, null);
    }

    String fromElementId = map.nodeIdToElementId().get(edge.fromNodeId());
    String toElementId = map.nodeIdToElementId().get(edge.toNodeId());
    if (fromElementId == null || toElementId == null) {
      return new Projection(ProjectionAction.FAIL_INVALID, null, null);
    }

    if (isStructuralBranchEntry(from, to)) {
      return new Projection(ProjectionAction.SKIP_STRUCTURAL, fromElementId, toElementId);
    }

    return new Projection(ProjectionAction.CREATE, fromElementId, toElementId);
  }

  /**
   * Parent-to-direct-child edges express containment in the plan graph; the
   * catalog represents
   * them through element placement, not runtime dependencies.
   */
  private static boolean isStructuralBranchEntry(ChainPlanNode from, ChainPlanNode to) {
    String toParent = to.parentNodeId();
    return toParent != null && !toParent.isBlank() && toParent.equals(from.nodeId());
  }

  public enum ProjectionAction {
    CREATE,
    SKIP_STRUCTURAL,
    SKIP_NON_DEPENDENCY,
    FAIL_INVALID
  }

  public record Projection(ProjectionAction action, String fromElementId, String toElementId) {

    /** Catalog endpoint pair, or null when this edge is not a catalog dependency. */
    public String edgeKey() {
      if (fromElementId == null || toElementId == null) {
        return null;
      }
      return CatalogDependencyKeys.edgeKey(fromElementId, toElementId);
    }
  }

  public record ConnectionsApplyResult(int createdCount, List<String> failedEdgeIds) {
  }
}
