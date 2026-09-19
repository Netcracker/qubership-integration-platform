package org.qubership.integration.platform.ai.catalog.binding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.ChainSection;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;

class CompositionCatalogIdentityTest {

  @Test
  void upsertChainCallWritesTriggerElementIdAndKeepsTimeout() {
    ChainPlanGraph graph =
        graph("call-1", "chain-call-2", List.of(new PlanProperty("timeout", "30000")));

    ChainPlanGraph out =
        CompositionCatalogIdentity.upsertChainCall(graph, "call-1", "trigger-el-9");

    ChainPlanNode node = node(out, "call-1");
    assertEquals("trigger-el-9", property(node, "elementId"));
    assertEquals("30000", property(node, "timeout"));
  }

  @Test
  void upsertChainCallReplacesPreviousElementId() {
    ChainPlanGraph graph =
        graph("call-1", "chain-call-2", List.of(new PlanProperty("elementId", "old-trigger")));

    ChainPlanGraph out =
        CompositionCatalogIdentity.upsertChainCall(graph, "call-1", "new-trigger");

    assertEquals("new-trigger", property(node(out, "call-1"), "elementId"));
  }

  @Test
  void upsertChainCallRejectsMissingNode() {
    ChainPlanGraph graph = graph("call-1", "chain-call-2", List.of());

    assertThrows(
        IllegalArgumentException.class,
        () -> CompositionCatalogIdentity.upsertChainCall(graph, "missing", "trigger-el-9"));
  }

  @Test
  void upsertReuseReferenceWritesReuseNodeIdAndKeepsName() {
    ChainPlanGraph graph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("n", "n"),
            List.of(
                new ChainPlanNode(
                    "reuse-ref",
                    "reuse-reference",
                    "Reuse Reference",
                    null,
                    null,
                    List.of()),
                new ChainPlanNode(
                    "reuse-container", "reuse", "Reuse", null, null, List.of())),
            List.of());

    ChainPlanGraph out =
        CompositionCatalogIdentity.upsertReuseReference(graph, "reuse-ref", "reuse-container");

    assertEquals("reuse-container", property(node(out, "reuse-ref"), "reuseElementId"));
    assertNull(property(node(out, "reuse-container"), "reuseElementId"));
  }

  private static ChainPlanGraph graph(String nodeId, String type, List<PlanProperty> properties) {
    return new ChainPlanGraph(
        "1.0",
        new ChainSection("n", "n"),
        List.of(new ChainPlanNode(nodeId, type, "Call", null, null, properties)),
        List.of());
  }

  private static ChainPlanNode node(ChainPlanGraph graph, String nodeId) {
    return graph.nodes().stream()
        .filter(candidate -> candidate.nodeId().equals(nodeId))
        .findFirst()
        .orElseThrow();
  }

  private static String property(ChainPlanNode node, String key) {
    if (node.properties() == null) {
      return null;
    }
    for (PlanProperty property : node.properties()) {
      if (key.equals(property.key())) {
        return property.value();
      }
    }
    return null;
  }
}
