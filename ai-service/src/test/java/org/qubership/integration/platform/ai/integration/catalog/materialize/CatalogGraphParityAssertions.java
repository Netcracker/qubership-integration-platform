package org.qubership.integration.platform.ai.integration.catalog.materialize;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.qubership.integration.platform.ai.chain.imports.ImportedChainPlan;
import org.qubership.integration.platform.ai.integration.catalog.materialize.ChainPlanConnectionsMaterializer.Projection;
import org.qubership.integration.platform.ai.integration.catalog.materialize.ChainPlanConnectionsMaterializer.ProjectionAction;
import org.qubership.integration.platform.ai.plan.model.ChainPlanEdge;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;

/** Compares materialized catalog graphs through logical plan ids instead of catalog UUIDs. */
final class CatalogGraphParityAssertions {

  private CatalogGraphParityAssertions() {}

  static void assertCreateEditParity(
      ChainPlanGraph desired,
      MaterializationMap createMap,
      ImportedChainPlan createImport,
      MaterializationMap editMap,
      ImportedChainPlan editImport) {
    assertNormalizedGraphsEqual(
        normalize(desired, createMap, createImport),
        normalize(desired, editMap, editImport));
  }

  static NormalizedGraph normalize(
      ChainPlanGraph desired, MaterializationMap map, ImportedChainPlan imported) {
    Map<String, String> catalogToLogical = reverseMap(map);
    Map<String, ChainPlanNode> importedByCatalogId = index(imported.graph());

    Map<String, NodeSnapshot> nodes = new LinkedHashMap<>();
    for (ChainPlanNode desiredNode : desired.nodes()) {
      String catalogId = map.nodeIdToElementId().get(desiredNode.nodeId());
      ChainPlanNode importedNode =
          catalogId == null ? null : importedByCatalogId.get(catalogId);
      String importedParent =
          importedNode == null || importedNode.parentNodeId() == null
              ? null
              : catalogToLogical.getOrDefault(importedNode.parentNodeId(), importedNode.parentNodeId());
      nodes.put(
          desiredNode.nodeId(),
          new NodeSnapshot(
              importedNode == null ? null : importedNode.type(),
              importedParent,
              importedPropertiesForDesired(desiredNode, importedNode)));
    }

    Set<String> dependencyPairs = projectedDependencyPairs(desired, map);
    Set<String> importedDependencyPairs =
        projectedDependencyPairs(imported.graph(), imported.materializationMap());
    return new NormalizedGraph(nodes, dependencyPairs, importedDependencyPairs);
  }

  private static void assertNormalizedGraphsEqual(
      NormalizedGraph createView, NormalizedGraph editView) {
    assertEquals(createView.nodes(), editView.nodes(), "CREATE vs EDIT parity drift: node shape");
    assertEquals(
        createView.desiredDependencyPairs(),
        editView.desiredDependencyPairs(),
        "CREATE vs EDIT parity drift: desired dependencies");
    assertEquals(
        createView.importedDependencyPairs(),
        editView.importedDependencyPairs(),
        "CREATE vs EDIT parity drift: imported dependencies");
  }

  private static Map<String, Object> importedPropertiesForDesired(
      ChainPlanNode desiredNode, ChainPlanNode importedNode) {
    if (desiredNode.properties() == null || desiredNode.properties().isEmpty()) {
      return Map.of();
    }
    Map<String, Object> importedProps = new LinkedHashMap<>();
    if (importedNode != null && importedNode.properties() != null) {
      for (PlanProperty property : importedNode.properties()) {
        if (property != null && property.key() != null) {
          importedProps.put(property.key(), property.value());
        }
      }
    }
    Map<String, Object> filtered = new LinkedHashMap<>();
    for (PlanProperty desiredProperty : desiredNode.properties()) {
      if (desiredProperty == null || desiredProperty.key() == null) {
        continue;
      }
      filtered.put(desiredProperty.key(), importedProps.get(desiredProperty.key()));
    }
    return Map.copyOf(filtered);
  }

  private static Set<String> projectedDependencyPairs(
      ChainPlanGraph graph, MaterializationMap map) {
    Set<String> pairs = new LinkedHashSet<>();
    if (graph.edges() == null) {
      return pairs;
    }
    Map<String, String> catalogToLogical = reverseMap(map);
    for (ChainPlanEdge edge : graph.edges()) {
      Projection projection = ChainPlanConnectionsMaterializer.project(edge, graph, map);
      if (projection.action() == ProjectionAction.CREATE) {
        pairs.add(
            logicalPair(
                catalogToLogical.get(projection.fromElementId()),
                catalogToLogical.get(projection.toElementId())));
      }
    }
    return pairs;
  }

  private static String logicalPair(String from, String to) {
    return from + "->" + to;
  }

  private static Map<String, ChainPlanNode> index(ChainPlanGraph graph) {
    Map<String, ChainPlanNode> index = new LinkedHashMap<>();
    for (ChainPlanNode node : graph.nodes()) {
      if (node != null && node.nodeId() != null) {
        index.put(node.nodeId(), node);
      }
    }
    return index;
  }

  private static Map<String, String> reverseMap(MaterializationMap map) {
    Map<String, String> reverse = new LinkedHashMap<>();
    for (Map.Entry<String, String> entry : map.nodeIdToElementId().entrySet()) {
      reverse.put(entry.getValue(), entry.getKey());
    }
    return reverse;
  }

  record NormalizedGraph(
      Map<String, NodeSnapshot> nodes,
      Set<String> desiredDependencyPairs,
      Set<String> importedDependencyPairs) {}

  record NodeSnapshot(String type, String parentNodeId, Map<String, Object> properties) {}
}
