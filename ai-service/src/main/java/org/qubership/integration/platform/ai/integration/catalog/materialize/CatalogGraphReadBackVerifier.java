package org.qubership.integration.platform.ai.integration.catalog.materialize;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.chain.imports.ChainPlanGraphImporter;
import org.qubership.integration.platform.ai.chain.imports.ImportedChainPlan;
import org.qubership.integration.platform.ai.chain.presentation.ChainCatalogFacts;
import org.qubership.integration.platform.ai.chain.presentation.ChainCatalogFactsService;
import org.qubership.integration.platform.ai.integration.catalog.materialize.ChainPlanConnectionsMaterializer.Projection;
import org.qubership.integration.platform.ai.integration.catalog.materialize.ChainPlanConnectionsMaterializer.ProjectionAction;
import org.qubership.integration.platform.ai.plan.model.ChainPlanEdge;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;

/**
 * Imports catalog state after materialization and compares it with the desired graph through the
 * materialization map.
 */
@ApplicationScoped
public class CatalogGraphReadBackVerifier {

  private static final Logger LOG = Logger.getLogger(CatalogGraphReadBackVerifier.class);

  private final ChainCatalogFactsService factsService;
  private final ChainPlanGraphImporter graphImporter;

  @Inject
  public CatalogGraphReadBackVerifier(
      ChainCatalogFactsService factsService, ChainPlanGraphImporter graphImporter) {
    this.factsService = Objects.requireNonNull(factsService);
    this.graphImporter = Objects.requireNonNull(graphImporter);
  }

  /**
   * @return verification error message, or {@code null} when the imported graph matches
   */
  public String verify(
      String chainId,
      ChainPlanGraph currentGraph,
      ChainPlanGraph desiredGraph,
      MaterializationMap materializationMap,
      MaterializationMap originalMap,
      List<String> createdNodeIds) {
    Objects.requireNonNull(chainId, "chainId");
    Objects.requireNonNull(currentGraph, "currentGraph");
    Objects.requireNonNull(desiredGraph, "desiredGraph");
    Objects.requireNonNull(materializationMap, "materializationMap");
    Objects.requireNonNull(originalMap, "originalMap");
    Objects.requireNonNull(createdNodeIds, "createdNodeIds");

    ImportedChainPlan imported;
    try {
      ChainCatalogFacts facts = factsService.load(chainId);
      imported = graphImporter.importChain(facts);
    } catch (RuntimeException e) {
      LOG.errorf(e, "Catalog read-back import failed for chain %s", chainId);
      return "Catalog read-back verification failed: " + e.getMessage();
    }

    ChainPlanGraph importedGraph = imported.graph();
    Map<String, ChainPlanNode> importedById = indexNodes(importedGraph);

    for (ChainPlanNode desiredNode : desiredGraph.nodes()) {
      if (desiredNode == null || desiredNode.nodeId() == null) {
        continue;
      }
      String elementId = catalogId(materializationMap, desiredNode.nodeId());
      if (elementId == null) {
        return missingDesiredNode(desiredNode.nodeId(), "has no catalog mapping");
      }
      ChainPlanNode importedNode = importedById.get(elementId);
      if (importedNode == null) {
        return missingDesiredNode(desiredNode.nodeId(), "is absent from catalog import");
      }
      String expectedParent = catalogId(materializationMap, desiredNode.parentNodeId());
      if (!Objects.equals(expectedParent, importedNode.parentNodeId())) {
        return parentMismatch(
            desiredNode.nodeId(),
            expectedParent,
            importedNode.parentNodeId());
      }
    }

    String leftover = unrequestedGeneratedDescendant(
        desiredGraph, materializationMap, createdNodeIds, importedGraph, importedById);
    if (leftover != null) {
      return leftover;
    }

    String removed = removedNodeStillPresent(currentGraph, desiredGraph, originalMap, importedById);
    if (removed != null) {
      return removed;
    }

    String stable = unrelatedIdsStable(currentGraph, desiredGraph, originalMap, materializationMap);
    if (stable != null) {
      return stable;
    }

    if (!projectedDependenciesMatch(desiredGraph, materializationMap, importedGraph, imported)) {
      return "Catalog read-back verification failed: projected dependencies do not match desired graph";
    }

    return null;
  }

  private static String missingDesiredNode(String nodeId, String reason) {
    return "Catalog read-back verification failed: desired node '" + nodeId + "' " + reason;
  }

  private static String parentMismatch(String nodeId, String expectedParent, String actualParent) {
    return "Catalog read-back verification failed: node '"
        + nodeId
        + "' parent is '"
        + actualParent
        + "' but expected '"
        + expectedParent
        + "'";
  }

  private static String unrequestedGeneratedDescendant(
      ChainPlanGraph desiredGraph,
      MaterializationMap map,
      List<String> createdNodeIds,
      ChainPlanGraph importedGraph,
      Map<String, ChainPlanNode> importedById) {
    Set<String> desiredCatalogIds = new LinkedHashSet<>();
    for (ChainPlanNode desiredNode : desiredGraph.nodes()) {
      if (desiredNode == null || desiredNode.nodeId() == null) {
        continue;
      }
      String catalogId = catalogId(map, desiredNode.nodeId());
      if (catalogId != null) {
        desiredCatalogIds.add(catalogId);
      }
    }
    for (String createdPlanId : createdNodeIds) {
      String containerCatalogId = catalogId(map, createdPlanId);
      if (containerCatalogId == null) {
        continue;
      }
      for (ChainPlanNode importedNode : importedGraph.nodes()) {
        if (importedNode == null || importedNode.nodeId() == null) {
          continue;
        }
        if (!isDescendantOf(importedNode, containerCatalogId, importedById)) {
          continue;
        }
        if (!desiredCatalogIds.contains(importedNode.nodeId())) {
          return "Catalog read-back verification failed: unrequested generated descendant '"
              + importedNode.nodeId()
              + "' remains under newly created container '"
              + createdPlanId
              + "'";
        }
      }
    }
    return null;
  }

  private static String removedNodeStillPresent(
      ChainPlanGraph currentGraph,
      ChainPlanGraph desiredGraph,
      MaterializationMap originalMap,
      Map<String, ChainPlanNode> importedById) {
    Set<String> desiredIds = nodeIds(desiredGraph);
    for (ChainPlanNode currentNode : currentGraph.nodes()) {
      if (currentNode == null || currentNode.nodeId() == null) {
        continue;
      }
      if (desiredIds.contains(currentNode.nodeId())) {
        continue;
      }
      String elementId = catalogId(originalMap, currentNode.nodeId());
      if (elementId != null && importedById.containsKey(elementId)) {
        return "Catalog read-back verification failed: removed node '"
            + currentNode.nodeId()
            + "' is still present in catalog import";
      }
    }
    return null;
  }

  private static String unrelatedIdsStable(
      ChainPlanGraph currentGraph,
      ChainPlanGraph desiredGraph,
      MaterializationMap originalMap,
      MaterializationMap materializationMap) {
    Set<String> currentIds = nodeIds(currentGraph);
    for (ChainPlanNode desiredNode : desiredGraph.nodes()) {
      if (desiredNode == null || desiredNode.nodeId() == null) {
        continue;
      }
      if (!currentIds.contains(desiredNode.nodeId())) {
        continue;
      }
      String originalId = catalogId(originalMap, desiredNode.nodeId());
      String currentId = catalogId(materializationMap, desiredNode.nodeId());
      if (!Objects.equals(originalId, currentId)) {
        return "Catalog read-back verification failed: node '"
            + desiredNode.nodeId()
            + "' catalog id changed from '"
            + originalId
            + "' to '"
            + currentId
            + "'";
      }
    }
    return null;
  }

  private static boolean projectedDependenciesMatch(
      ChainPlanGraph desiredGraph,
      MaterializationMap map,
      ChainPlanGraph importedGraph,
      ImportedChainPlan imported) {
    Set<String> desiredKeys = projectedDependencyKeys(desiredGraph, map);
    Set<String> importedKeys =
        projectedDependencyKeys(importedGraph, imported.materializationMap());
    return desiredKeys.equals(importedKeys);
  }

  private static Set<String> projectedDependencyKeys(ChainPlanGraph graph, MaterializationMap map) {
    Set<String> keys = new LinkedHashSet<>();
    if (graph.edges() == null) {
      return keys;
    }
    for (ChainPlanEdge edge : graph.edges()) {
      if (edge == null) {
        continue;
      }
      Projection projection = ChainPlanConnectionsMaterializer.project(edge, graph, map);
      if (projection.action() == ProjectionAction.CREATE) {
        String edgeKey = projection.edgeKey();
        if (edgeKey != null) {
          keys.add(edgeKey);
        }
      }
    }
    return keys;
  }

  private static boolean isDescendantOf(
      ChainPlanNode node, String ancestorCatalogId, Map<String, ChainPlanNode> importedById) {
    String parentId = node.parentNodeId();
    while (parentId != null && !parentId.isBlank()) {
      if (ancestorCatalogId.equals(parentId)) {
        return true;
      }
      ChainPlanNode parent = importedById.get(parentId);
      if (parent == null) {
        return false;
      }
      parentId = parent.parentNodeId();
    }
    return false;
  }

  private static Set<String> nodeIds(ChainPlanGraph graph) {
    Set<String> ids = new LinkedHashSet<>();
    if (graph.nodes() == null) {
      return ids;
    }
    for (ChainPlanNode node : graph.nodes()) {
      if (node != null && node.nodeId() != null) {
        ids.add(node.nodeId());
      }
    }
    return ids;
  }

  private static Map<String, ChainPlanNode> indexNodes(ChainPlanGraph graph) {
    Map<String, ChainPlanNode> index = new java.util.LinkedHashMap<>();
    if (graph.nodes() == null) {
      return index;
    }
    for (ChainPlanNode node : graph.nodes()) {
      if (node != null && node.nodeId() != null) {
        index.put(node.nodeId(), node);
      }
    }
    return index;
  }

  private static String catalogId(MaterializationMap map, String nodeId) {
    if (nodeId == null || map == null || map.nodeIdToElementId() == null) {
      return null;
    }
    return map.nodeIdToElementId().get(nodeId);
  }
}
