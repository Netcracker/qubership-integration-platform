package org.qubership.integration.platform.ai.plan.mapping;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.qubership.integration.platform.ai.plan.ChainPlanGraphValidator;
import org.qubership.integration.platform.ai.plan.model.ChainPlanEdge;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;

/**
 * Applies each configured mapping site once in graph execution order, starting from trigger
 * entries. Non-transform nodes pass the current body through.
 */
public final class MappingFlowExecutor {

  private MappingFlowExecutor() {}

  public static String apply(ChainPlanGraph graph, String jsonBody) {
    String current = jsonBody == null ? "{}" : jsonBody;
    if (graph == null) {
      return current;
    }
    for (ChainPlanNode node : executionOrder(graph)) {
      if (!MappingExecutionSite.isConfigured(node)) {
        continue;
      }
      if (MappingExecutionSite.isMapper2(node)) {
        current = SimpleMapper2Executor.applyNode(node, current);
      } else if (MappingExecutionSite.isScript(node)) {
        current = SimpleScriptExecutor.applyNode(node, current);
      }
    }
    return current;
  }

  static List<ChainPlanNode> executionOrder(ChainPlanGraph graph) {
    Map<String, ChainPlanNode> nodesById = new LinkedHashMap<>();
    for (ChainPlanNode node : graph.nodes()) {
      nodesById.put(node.nodeId(), node);
    }
    ArrayDeque<String> queue = new ArrayDeque<>();
    for (ChainPlanNode node : graph.nodes()) {
      if (ChainPlanGraphValidator.isTriggerElementType(node.type())) {
        queue.add(node.nodeId());
      }
    }
    List<ChainPlanNode> ordered = new ArrayList<>();
    while (!queue.isEmpty()) {
      String nodeId = queue.removeFirst();
      ChainPlanNode node = nodesById.remove(nodeId);
      if (node != null) {
        ordered.add(node);
        enqueueUnvisitedTargets(graph, nodeId, nodesById, queue);
      }
    }
    return List.copyOf(ordered);
  }

  private static void enqueueUnvisitedTargets(
      ChainPlanGraph graph,
      String nodeId,
      Map<String, ChainPlanNode> remaining,
      ArrayDeque<String> queue) {
    if (graph.edges() == null) {
      return;
    }
    for (ChainPlanEdge edge : graph.edges()) {
      if (nodeId.equals(edge.fromNodeId()) && remaining.containsKey(edge.toNodeId())) {
        queue.add(edge.toNodeId());
      }
    }
  }
}
