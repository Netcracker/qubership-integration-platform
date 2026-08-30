package org.qubership.integration.platform.ai.compiler;

import java.util.Objects;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.qipknowledge.artifact.ConfiguredTrigger;
import org.qubership.integration.platform.ai.qipknowledge.artifact.ConfiguredTriggerSet;

/**
 * Copies captured {@link ConfiguredTriggerSet} endpoint properties onto matching plan nodes.
 *
 * <p>Does not invent values — only copies already-captured trigger properties onto blank keys.
 */
final class ConfiguredTriggerSetGraphEnricher {

  private ConfiguredTriggerSetGraphEnricher() {}

  static ChainPlanGraph enrich(ChainPlanGraph graph, ConfiguredTriggerSet triggerSet) {
    if (graph == null
        || graph.nodes() == null
        || graph.nodes().isEmpty()
        || triggerSet == null
        || triggerSet.triggers() == null
        || triggerSet.triggers().isEmpty()) {
      return graph;
    }
    ChainPlanGraph merged = graph;
    for (ConfiguredTrigger trigger : triggerSet.triggers()) {
      if (trigger == null || trigger.properties() == null || trigger.properties().isEmpty()) {
        continue;
      }
      String nodeId = resolveTriggerNodeId(merged, trigger);
      if (nodeId == null) {
        continue;
      }
      String nodeType = nodeType(merged, nodeId);
      for (PlanProperty property : trigger.properties()) {
        if (property == null || property.key() == null || property.key().isBlank()) {
          continue;
        }
        if ("serviceCallId".equals(property.key()) && !"service-call".equals(nodeType)) {
          continue;
        }
        if (hasNonBlankProperty(merged, nodeId, property.key())) {
          continue;
        }
        merged = merged.withNodeProperty(nodeId, property.key(), property.value());
      }
    }
    return merged;
  }

  private static String resolveTriggerNodeId(ChainPlanGraph graph, ConfiguredTrigger trigger) {
    if (trigger.semanticNodeId() != null && !trigger.semanticNodeId().isBlank()) {
      for (ChainPlanNode node : graph.nodes()) {
        if (node != null && Objects.equals(node.nodeId(), trigger.semanticNodeId())) {
          return node.nodeId();
        }
      }
    }
    String expectedType =
        trigger.elementType() == null || trigger.elementType().isBlank()
            ? "http-trigger"
            : trigger.elementType();
    for (ChainPlanNode node : graph.nodes()) {
      if (node != null && expectedType.equalsIgnoreCase(nullToEmpty(node.type()))) {
        return node.nodeId();
      }
    }
    return null;
  }

  private static String nodeType(ChainPlanGraph graph, String nodeId) {
    for (ChainPlanNode node : graph.nodes()) {
      if (node != null && Objects.equals(node.nodeId(), nodeId)) {
        return node.type();
      }
    }
    return null;
  }

  private static boolean hasNonBlankProperty(ChainPlanGraph graph, String nodeId, String key) {
    for (ChainPlanNode node : graph.nodes()) {
      if (node == null || !Objects.equals(node.nodeId(), nodeId) || node.properties() == null) {
        continue;
      }
      for (PlanProperty property : node.properties()) {
        if (property != null
            && key.equals(property.key())
            && property.value() != null
            && !property.value().isBlank()) {
          return true;
        }
      }
    }
    return false;
  }

  private static String nullToEmpty(String value) {
    return value == null ? "" : value;
  }
}
