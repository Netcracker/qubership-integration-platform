package org.qubership.integration.platform.ai.integration.catalog.materialize;

import java.util.ArrayList;
import java.util.List;
import org.qubership.integration.platform.ai.plan.model.ChainPlanEdge;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.ChainSection;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;

/** Builds desired graphs for CREATE/EDIT parity contract cases. */
final class CatalogGraphParityScenarios {

  private static final ChainSection SECTION = new ChainSection("parity-chain", "Parity");

  private CatalogGraphParityScenarios() {}

  static ChainPlanGraph triggerOnlyCurrent() {
    return graph(trigger("trigger"));
  }

  static ChainPlanGraph conditionOneIf(boolean withElse) {
    List<ChainPlanNode> nodes = new ArrayList<>();
    nodes.add(trigger("trigger"));
    nodes.add(node("condition-1", "condition", null));
    nodes.add(node("if-1", "if", "condition-1"));
    if (withElse) {
      nodes.add(node("else-1", "else", "condition-1"));
    }
    return graph(nodes);
  }

  static ChainPlanGraph conditionSeveralIf(boolean withElse) {
    List<ChainPlanNode> nodes = new ArrayList<>();
    nodes.add(trigger("trigger"));
    nodes.add(node("condition-1", "condition", null));
    nodes.add(
        node(
            "if-1",
            "if",
            "condition-1",
            List.of(new PlanProperty("priority", "1"), new PlanProperty("script", "a"))));
    nodes.add(
        node(
            "if-2",
            "if",
            "condition-1",
            List.of(new PlanProperty("priority", "2"), new PlanProperty("script", "b"))));
    if (withElse) {
      nodes.add(node("else-1", "else", "condition-1"));
    }
    return graph(nodes);
  }

  static ChainPlanGraph tryCatchSeveralCatch(boolean withFinally) {
    List<ChainPlanNode> nodes = new ArrayList<>();
    nodes.add(trigger("trigger"));
    nodes.add(node("wrapper", "try-catch-finally-2", null));
    nodes.add(node("try", "try-2", "wrapper"));
    nodes.add(node("catch-1", "catch-2", "wrapper"));
    nodes.add(node("catch-2", "catch-2", "wrapper"));
    if (withFinally) {
      nodes.add(node("finally-1", "finally-2", "wrapper"));
    }
    nodes.add(node("script-1", "script", "try"));
    return graph(nodes);
  }

  static ChainPlanGraph splitAsyncBranches(int branchCount) {
    List<ChainPlanNode> nodes = new ArrayList<>();
    nodes.add(trigger("trigger"));
    nodes.add(node("split", "split-async-2", null));
    for (int index = 1; index <= branchCount; index++) {
      nodes.add(node("branch-" + index, "async-split-element-2", "split"));
    }
    return graph(nodes);
  }

  static ChainPlanGraph split2Graph(boolean withMain, int extraBranches) {
    List<ChainPlanNode> nodes = new ArrayList<>();
    nodes.add(trigger("trigger"));
    nodes.add(node("split", "split-2", null));
    if (withMain) {
      nodes.add(node("main", "main-split-element-2", "split"));
    }
    for (int index = 1; index <= extraBranches; index++) {
      nodes.add(node("branch-" + index, "split-element-2", "split"));
    }
    return graph(nodes);
  }

  static ChainPlanGraph circuitBreaker() {
    return graph(
        trigger("trigger"),
        node("breaker", "circuit-breaker-2", null),
        node("config", "circuit-breaker-configuration-2", "breaker"),
        node("fallback", "on-fallback-2", "breaker"));
  }

  static ChainPlanGraph loopWithBody() {
    return graph(
        trigger("trigger"),
        node("loop", "loop-2", null),
        node("body", "script", "loop"));
  }

  static ChainPlanGraph nestedConditionInTry() {
    return graph(
        trigger("trigger"),
        node("wrapper", "try-catch-finally-2", null),
        node("try", "try-2", "wrapper"),
        node("catch-1", "catch-2", "wrapper"),
        node("condition-1", "condition", "try"),
        node("if-1", "if", "condition-1"),
        node("script-1", "script", "if-1"));
  }

  static ChainPlanGraph conditionWithoutElseDriftGuard() {
    ChainPlanGraph desired = conditionOneIf(false);
    return new ChainPlanGraph(
        desired.schemaVersion(),
        desired.chain(),
        desired.nodes(),
        List.of(new ChainPlanEdge("edge-1", "trigger", "if-1", null)));
  }

  private static ChainPlanGraph graph(ChainPlanNode... nodes) {
    return graph(List.of(nodes));
  }

  private static ChainPlanGraph graph(List<ChainPlanNode> nodes) {
    return new ChainPlanGraph("1.0", SECTION, List.copyOf(nodes), List.of());
  }

  private static ChainPlanNode trigger(String nodeId) {
    return node(nodeId, "http-trigger", null);
  }

  private static ChainPlanNode node(String nodeId, String type, String parentNodeId) {
    return node(nodeId, type, parentNodeId, List.of());
  }

  private static ChainPlanNode node(
      String nodeId, String type, String parentNodeId, List<PlanProperty> properties) {
    return new ChainPlanNode(nodeId, type, nodeId, parentNodeId, null, properties);
  }
}
