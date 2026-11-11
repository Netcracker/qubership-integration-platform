package org.qubership.integration.platform.ai.schema;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;

/** Applies server-owned schema defaults to assembled plan graphs before validation. */
public final class UnconditionalElementDefaults {

  private static final String KEY_PROPERTIES = "properties";

  private UnconditionalElementDefaults() {}

  public static ChainPlanGraph apply(
      ChainPlanGraph graph, ObjectMapper objectMapper, SchemaRefResolver resolver) {
    if (graph == null || graph.nodes() == null) {
      return graph;
    }
    SchemaResourceLoader schemaResourceLoader = new SchemaResourceLoader();
    List<ChainPlanNode> updatedNodes = new ArrayList<>(graph.nodes().size());
    boolean changed = false;
    for (ChainPlanNode node : graph.nodes()) {
      ChainPlanNode updated = applyNodeDefaults(node, objectMapper, resolver, schemaResourceLoader);
      updatedNodes.add(updated);
      if (updated != node) {
        changed = true;
      }
    }
    if (!changed) {
      return graph;
    }
    return new ChainPlanGraph(
        graph.schemaVersion(), graph.chain(), List.copyOf(updatedNodes), graph.edges());
  }

  private static ChainPlanNode applyNodeDefaults(
      ChainPlanNode node,
      ObjectMapper objectMapper,
      SchemaRefResolver resolver,
      SchemaResourceLoader schemaResourceLoader) {
    if (node == null || node.type() == null || node.type().isBlank()) {
      return node;
    }
    String elementType = node.type().trim();
    if (!schemaResourceLoader.existsElementSchema(elementType)) {
      return node;
    }
    try {
      ElementPropertiesSchemaModel model =
          ElementPropertiesSchemaModelBuilder.build(elementType, resolver);
      ObjectNode patch = objectMapper.createObjectNode();
      ObjectNode props = objectMapper.createObjectNode();
      LinkedHashSet<String> existingKeys = new LinkedHashSet<>();
      List<PlanProperty> current = node.properties() == null ? List.of() : node.properties();
      for (PlanProperty property : current) {
        if (property == null || property.key() == null || property.key().isBlank()) {
          continue;
        }
        existingKeys.add(property.key());
        props.set(property.key(), objectMapper.valueToTree(property.value()));
      }
      patch.set(KEY_PROPERTIES, props);
      ArrayNode applied = objectMapper.createArrayNode();
      ElementPatchDefaultsApplicator.applyMissingPropertyDefaults(
          patch, model, resolver, objectMapper, applied);
      if (applied.isEmpty()) {
        return node;
      }
      List<PlanProperty> merged = new ArrayList<>(current);
      JsonNode filled = patch.get(KEY_PROPERTIES);
      for (JsonNode keyNode : applied) {
        String key = keyNode.asText();
        if (existingKeys.contains(key) || filled == null || !filled.has(key)) {
          continue;
        }
        merged.add(new PlanProperty(key, jsonValueToPlanString(objectMapper, filled.get(key))));
      }
      return new ChainPlanNode(
          node.nodeId(),
          node.type(),
          node.label(),
          node.parentNodeId(),
          node.order(),
          List.copyOf(merged));
    } catch (SchemaNotFoundException | SchemaRefResolutionException | JsonProcessingException e) {
      return node;
    }
  }

  private static String jsonValueToPlanString(ObjectMapper objectMapper, JsonNode value)
      throws JsonProcessingException {
    if (value == null || value.isNull()) {
      return null;
    }
    if (value.isTextual()) {
      return value.asText();
    }
    return objectMapper.writeValueAsString(value);
  }
}
