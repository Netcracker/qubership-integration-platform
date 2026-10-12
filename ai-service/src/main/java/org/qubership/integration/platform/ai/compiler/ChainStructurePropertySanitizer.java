package org.qubership.integration.platform.ai.compiler;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.qubership.integration.platform.ai.plan.mapping.MappingExecutionSite;
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
    return sanitize(capture, null);
  }

  public SanitizationResult sanitize(ChainStructure capture, ChainPlanGraph baseGraph) {
    if (capture == null
        || capture.graph() == null
        || capture.graph().nodes() == null
        || capture.graph().nodes().isEmpty()) {
      return new SanitizationResult(capture, List.of());
    }
    List<RemovedProperty> removed = new ArrayList<>();
    Map<String, ChainPlanNode> baseNodes = new java.util.HashMap<>();
    if (baseGraph != null && baseGraph.nodes() != null) {
      for (ChainPlanNode node : baseGraph.nodes()) {
        baseNodes.put(node.nodeId(), node);
      }
    }
    List<ChainPlanNode> nodes =
        capture.graph().nodes().stream()
            .map(node -> sanitizeNode(node, baseNodes.get(node == null ? null : node.nodeId()), removed))
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
      ChainPlanNode node, ChainPlanNode before, List<RemovedProperty> removed) {
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
          || MappingExecutionSite.isCompilerMetadataKey(property.key().trim())
          || allowed.contains(property.key().trim())
          || unchangedImportedProperty(node, property, before)) {
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

  private static boolean unchangedImportedProperty(
      ChainPlanNode node, PlanProperty property, ChainPlanNode before) {
    return before != null
        && Objects.equals(before.nodeId(), node.nodeId())
        && Objects.equals(before.type(), node.type())
        && before.properties() != null
        && before.properties().contains(property);
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
