package org.qubership.integration.platform.ai.compiler;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.qipknowledge.artifact.ChainStructure;
import org.qubership.integration.platform.ai.schema.DeterministicElementSchemaService;

@ApplicationScoped
public class ChainStructurePropertySanitizer {

  private final DeterministicElementSchemaService schemaService;

  @Inject
  public ChainStructurePropertySanitizer(DeterministicElementSchemaService schemaService) {
    this.schemaService = schemaService;
  }

  public SanitizationResult sanitize(ChainStructure capture) {
    if (capture == null
        || capture.graph() == null
        || capture.graph().nodes() == null
        || capture.graph().nodes().isEmpty()) {
      return new SanitizationResult(capture, List.of());
    }
    List<RemovedProperty> removed = new ArrayList<>();
    List<ChainPlanNode> nodes =
        capture.graph().nodes().stream()
            .map(node -> sanitizeNode(node, removed))
            .toList();
    ChainPlanGraph graph =
        new ChainPlanGraph(
            capture.graph().schemaVersion(),
            capture.graph().chain(),
            nodes,
            capture.graph().edges());
    return new SanitizationResult(
        new ChainStructure(
            graph, capture.sourceRequirementFactIds(), capture.knowledgeCitations()),
        List.copyOf(removed));
  }

  private ChainPlanNode sanitizeNode(
      ChainPlanNode node, List<RemovedProperty> removed) {
    if (node == null
        || node.type() == null
        || node.type().isBlank()
        || node.properties() == null
        || node.properties().isEmpty()
        || !schemaService.hasElementSchema(node.type())) {
      return node;
    }
    Set<String> allowed = schemaService.allowedPatchPropertyKeys(node.type());
    List<PlanProperty> properties = new ArrayList<>();
    for (PlanProperty property : node.properties()) {
      if (property == null
          || property.key() == null
          || property.key().isBlank()
          || allowed.contains(property.key().trim())) {
        properties.add(property);
      } else {
        removed.add(new RemovedProperty(node.nodeId(), node.type(), property.key().trim()));
      }
    }
    if (properties.size() == node.properties().size()) {
      return node;
    }
    return new ChainPlanNode(
        node.nodeId(),
        node.type(),
        node.label(),
        node.parentNodeId(),
        node.order(),
        List.copyOf(properties));
  }

  public record SanitizationResult(
      ChainStructure structure, List<RemovedProperty> removedProperties) {
    public SanitizationResult {
      removedProperties =
          removedProperties == null ? List.of() : List.copyOf(removedProperties);
    }
  }

  public record RemovedProperty(String nodeId, String elementType, String key) {}
}
