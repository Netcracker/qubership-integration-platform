package org.qubership.integration.platform.ai.catalog.binding;

import java.util.ArrayList;
import java.util.List;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;

/** Writes composition identity properties onto bound plan nodes. */
public final class CompositionCatalogIdentity {

  private CompositionCatalogIdentity() {}

  public static ChainPlanGraph upsertChainCall(
      ChainPlanGraph graph, String nodeId, String triggerElementId) {
    return upsertProperty(graph, nodeId, "elementId", triggerElementId);
  }

  public static ChainPlanGraph upsertReuseReference(
      ChainPlanGraph graph, String nodeId, String reuseNodeId) {
    return upsertProperty(graph, nodeId, "reuseElementId", reuseNodeId);
  }

  private static ChainPlanGraph upsertProperty(
      ChainPlanGraph graph, String nodeId, String key, String value) {
    ChainPlanNode target = findNode(graph, nodeId);
    List<PlanProperty> properties = new ArrayList<>();
    if (target.properties() != null) {
      for (PlanProperty property : target.properties()) {
        if (property == null || key.equals(property.key())) {
          continue;
        }
        properties.add(property);
      }
    }
    properties.add(new PlanProperty(key, value));
    ChainPlanNode updated =
        new ChainPlanNode(
            target.nodeId(),
            target.type(),
            target.label(),
            target.parentNodeId(),
            target.order(),
            List.copyOf(properties));
    List<ChainPlanNode> nodes =
        graph.nodes().stream()
            .map(node -> node.nodeId().equals(nodeId) ? updated : node)
            .toList();
    return new ChainPlanGraph(graph.schemaVersion(), graph.chain(), nodes, graph.edges());
  }

  private static ChainPlanNode findNode(ChainPlanGraph graph, String nodeId) {
    for (ChainPlanNode node : graph.nodes()) {
      if (nodeId.equals(node.nodeId())) {
        return node;
      }
    }
    throw new IllegalArgumentException("node not found: " + nodeId);
  }
}
