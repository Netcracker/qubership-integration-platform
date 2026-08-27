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
 * Applies configured mapping sites in graph order. {@link #apply} walks every reachable node from
 * all trigger entries. {@link #applyAlong} applies only the sites on one path so a branch-local
 * mapping cannot rewrite a sibling or another entry.
 */
public final class MappingFlowExecutor {

  private MappingFlowExecutor() {}

  public static String apply(ChainPlanGraph graph, String jsonBody) {
    if (graph == null) {
      return jsonBody == null ? "{}" : jsonBody;
    }
    return applyAlong(graph, nodeIds(executionOrder(graph)), jsonBody);
  }

  /**
   * Applies configured mapping sites that appear on {@code pathNodeIds}, in that order.
   * Non-transform nodes pass the current body through.
   */
  public static String applyAlong(ChainPlanGraph graph, List<String> pathNodeIds, String jsonBody) {
    String current = jsonBody == null ? "{}" : jsonBody;
    if (graph == null || pathNodeIds == null || pathNodeIds.isEmpty()) {
      return current;
    }
    Map<String, ChainPlanNode> nodesById = indexNodes(graph);
    for (String nodeId : pathNodeIds) {
      ChainPlanNode node = nodesById.get(nodeId);
      if (node == null) {
        throw new IllegalArgumentException("Path node '" + nodeId + "' is not in the graph.");
      }
      current = applySite(node, current);
    }
    return current;
  }

  private static String applySite(ChainPlanNode node, String jsonBody) {
    if (!MappingExecutionSite.isConfigured(node)) {
      return jsonBody;
    }
    if (MappingExecutionSite.isMapper2(node)) {
      return SimpleMapper2Executor.applyNode(node, jsonBody);
    }
    if (MappingExecutionSite.isScript(node)) {
      return SimpleScriptExecutor.applyNode(node, jsonBody);
    }
    return jsonBody;
  }

  private static List<String> nodeIds(List<ChainPlanNode> nodes) {
    List<String> ids = new ArrayList<>(nodes.size());
    for (ChainPlanNode node : nodes) {
      ids.add(node.nodeId());
    }
    return List.copyOf(ids);
  }

  private static Map<String, ChainPlanNode> indexNodes(ChainPlanGraph graph) {
    Map<String, ChainPlanNode> nodesById = new LinkedHashMap<>();
    for (ChainPlanNode node : graph.nodes()) {
      nodesById.put(node.nodeId(), node);
    }
    return nodesById;
  }

  static List<ChainPlanNode> executionOrder(ChainPlanGraph graph) {
    Map<String, ChainPlanNode> nodesById = indexNodes(graph);
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
