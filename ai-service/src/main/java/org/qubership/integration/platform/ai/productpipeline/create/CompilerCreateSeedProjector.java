package org.qubership.integration.platform.ai.productpipeline.create;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.qipknowledge.artifact.ConfiguredTrigger;
import org.qubership.integration.platform.ai.qipknowledge.artifact.ConfiguredTriggerSet;
import org.qubership.integration.platform.ai.qipknowledge.artifact.ElementRole;
import org.qubership.integration.platform.ai.qipknowledge.artifact.ElementSkeleton;
import org.qubership.integration.platform.ai.qipknowledge.artifact.SelectedPattern;

/**
 * Projects CREATE compiler seed artifacts from the graph Java already compiled from the approved
 * semantic revision. Pattern selection and trigger capture must not ask the LLM to restate that
 * graph.
 */
final class CompilerCreateSeedProjector {

  private static final String GP_01 = "GP-01";
  private static final String GP_02 = "GP-02";

  private CompilerCreateSeedProjector() {}

  static SelectedPattern pattern(ChainPlanGraph graph) {
    boolean async = hasAsyncTrigger(graph);
    String patternId = async ? GP_02 : GP_01;
    String name = async ? "AsyncAPI Task Processor" : "Protected Request-Response";
    String reason =
        async
            ? "Compiled graph contains an AsyncAPI or Kafka consume trigger."
            : "Compiled graph contains an HTTP trigger.";
    return new SelectedPattern(patternId, name, reason, null, List.of(), reason);
  }

  static ElementSkeleton skeleton(ChainPlanGraph graph, String patternId) {
    List<String> entryPointRoleIds = new ArrayList<>();
    List<ElementRole> roles = new ArrayList<>();
    if (graph != null && graph.nodes() != null) {
      for (ChainPlanNode node : graph.nodes()) {
        if (node == null || node.nodeId() == null || node.nodeId().isBlank()) {
          continue;
        }
        String type = node.type() == null ? "" : node.type();
        roles.add(new ElementRole(node.nodeId(), type, node.parentNodeId(), 1, 1));
        if (isTriggerType(type) && node.parentNodeId() == null) {
          entryPointRoleIds.add(node.nodeId());
        }
      }
    }
    return new ElementSkeleton(
        1, patternId, entryPointRoleIds, roles, List.of(), List.of(), List.of(), List.of());
  }

  static ConfiguredTriggerSet triggerSet(ChainPlanGraph graph) {
    List<ConfiguredTrigger> triggers = new ArrayList<>();
    if (graph != null && graph.nodes() != null) {
      for (ChainPlanNode node : graph.nodes()) {
        if (node == null || !isTriggerType(node.type())) {
          continue;
        }
        String semanticNodeId = node.semanticNodeId().orElse(node.nodeId());
        triggers.add(
            new ConfiguredTrigger(
                node.nodeId(),
                semanticNodeId,
                node.type(),
                node.label() == null ? node.nodeId() : node.label(),
                node.properties()));
      }
    }
    return new ConfiguredTriggerSet(1, triggers, List.of(), List.of());
  }

  private static boolean hasAsyncTrigger(ChainPlanGraph graph) {
    if (graph == null || graph.nodes() == null) {
      return false;
    }
    for (ChainPlanNode node : graph.nodes()) {
      if (node == null) {
        continue;
      }
      String type = normalizeType(node.type());
      if (type.equals("async-api-trigger") || type.equals("kafka-trigger-2")) {
        return true;
      }
    }
    return false;
  }

  static boolean isTriggerType(String type) {
    String normalized = normalizeType(type);
    return normalized.endsWith("-trigger") || normalized.equals("quartz-scheduler");
  }

  private static String normalizeType(String type) {
    return type == null ? "" : type.trim().toLowerCase(Locale.ROOT);
  }
}
