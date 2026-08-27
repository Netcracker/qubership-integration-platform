package org.qubership.integration.platform.ai.plan.mapping;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntent;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntentRule;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatch;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchOperation;
import org.qubership.integration.platform.ai.qipknowledge.patch.PropertyPatch;

/**
 * Specialized-generator seam: configure an existing mapper-2 shell. Does not add nodes or edges.
 */
public final class Mapper2ConfigurationPhase {

  private static final ObjectMapper JSON = new ObjectMapper();

  private Mapper2ConfigurationPhase() {}

  public static ChainPlanGraph configure(ChainPlanGraph graph, RequirementBrief brief) {
    if (graph == null) {
      throw new IllegalArgumentException("graph is required");
    }
    if (brief == null || brief.mappingIntents().isEmpty()) {
      return graph;
    }
    ChainPlanGraph current = graph;
    for (MappingIntent intent : brief.mappingIntents()) {
      if (intent == null || intent.mappingIntentId().isBlank()) {
        continue;
      }
      ChainPlanNode site = requireSite(current, intent.mappingIntentId());
      current =
          current.withNodeProperty(
              site.nodeId(),
              MappingExecutionSite.MAPPING_DESCRIPTION_PROPERTY,
              mappingDescriptionJson(intent));
    }
    return current;
  }

  public static GraphPatch configurationPatch(ChainPlanGraph graph, RequirementBrief brief) {
    if (graph == null) {
      throw new IllegalArgumentException("graph is required");
    }
    List<PropertyPatch> propertyPatches = new ArrayList<>();
    List<MappingIntent> intents = brief == null ? List.of() : brief.mappingIntents();
    for (MappingIntent intent : intents) {
      if (intent == null || intent.mappingIntentId().isBlank()) {
        continue;
      }
      ChainPlanNode site = requireSite(graph, intent.mappingIntentId());
      propertyPatches.add(
          new PropertyPatch(
              GraphPatchOperation.ADD,
              site.nodeId(),
              new PlanProperty(
                  MappingExecutionSite.MAPPING_DESCRIPTION_PROPERTY,
                  mappingDescriptionJson(intent))));
    }
    return new GraphPatch(
        "configure-mapper-2",
        "cip-transformation-generator",
        List.of(),
        List.of(),
        List.copyOf(propertyPatches),
        List.of(),
        List.of(),
        "Configure existing mapper-2 shells from approved mapping intents");
  }

  private static ChainPlanNode requireSite(ChainPlanGraph graph, String mappingIntentId) {
    for (ChainPlanNode node : graph.nodes()) {
      if (mappingIntentId.equals(MappingExecutionSite.mappingIntentId(node))) {
        return node;
      }
    }
    throw new IllegalStateException(
        "Transform shell for mapping intent '"
            + mappingIntentId
            + "' is missing. Structure generation must insert the mapper-2 site before"
            + " cip-transformation-generator configures it.");
  }

  static String mappingDescriptionJson(MappingIntent intent) {
    List<Map<String, String>> actions = new ArrayList<>();
    for (MappingIntentRule rule : intent.rules()) {
      Map<String, String> action = new LinkedHashMap<>();
      action.put("sourcePath", rule.sourcePath());
      action.put("targetPath", rule.targetPath());
      if (MappingMechanismSelector.isConstantLiteral(rule.sourcePath())) {
        action.put("kind", "constant");
        action.put("value", MappingMechanismSelector.constantValue(rule.sourcePath()));
      } else {
        action.put("kind", "copy");
      }
      actions.add(action);
    }
    try {
      return JSON.writeValueAsString(actions);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Cannot serialize mapper-2 actions for " + intent.mappingIntentId(), e);
    }
  }
}
