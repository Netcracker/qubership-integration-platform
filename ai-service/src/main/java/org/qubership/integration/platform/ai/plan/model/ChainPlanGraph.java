package org.qubership.integration.platform.ai.plan.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.langchain4j.model.output.structured.Description;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Flat graph representation of a chain implementation plan.
 *
 * <p>Containment (structural hierarchy) is expressed through {@link ChainPlanNode#parentNodeId()}.
 * Execution order (dependencies) is expressed through {@link ChainPlanEdge}, with
 * {@link ChainPlanEdge#scopeNodeId()} indicating which container branch an edge belongs to.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChainPlanGraph(
    @Description("Plan schema version, use 1.0") String schemaVersion,
    @Description("Chain metadata") ChainSection chain,
    @Description("Flat list of plan nodes including container children") List<ChainPlanNode> nodes,
    @Description("Execution edges between nodes at root or scoped branches") List<ChainPlanEdge> edges) {

  public ChainPlanGraph withNodeProperty(String nodeId, String key, String value) {
    List<ChainPlanNode> updated =
        nodes.stream()
            .map(
                node ->
                    node.nodeId().equals(nodeId)
                        ? withProperty(node, key, value)
                        : node)
            .toList();
    return new ChainPlanGraph(schemaVersion, chain, updated, edges);
  }

  private static ChainPlanNode withProperty(ChainPlanNode node, String key, String value) {
    List<PlanProperty> properties = new ArrayList<>();
    boolean replaced = false;
    if (node.properties() != null) {
      for (PlanProperty property : node.properties()) {
        if (Objects.equals(property.key(), key)) {
          properties.add(new PlanProperty(key, value));
          replaced = true;
        } else {
          properties.add(property);
        }
      }
    }
    if (!replaced) {
      properties.add(new PlanProperty(key, value));
    }
    return new ChainPlanNode(
        node.nodeId(),
        node.type(),
        node.label(),
        node.parentNodeId(),
        node.order(),
        List.copyOf(properties));
  }
}
