package org.qubership.integration.platform.ai.plan.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ChainPlanNodeServiceCallIdentityTest {

  @Test
  void readsReservedIdentityPropertiesWithoutFallback() {
    ChainPlanNode owned =
        new ChainPlanNode(
            "node-a",
            "service-call",
            "Get Order",
            null,
            null,
            List.of(
                new PlanProperty("serviceCallId", "call-a"),
                new PlanProperty("semanticRevisionId", "rev-1"),
                new PlanProperty("semanticNodeId", "node-a"),
                new PlanProperty("integrationOperationId", "op-shared")));

    assertEquals("call-a", owned.serviceCallId().orElseThrow());
    assertEquals("node-a", owned.semanticNodeId().orElseThrow());

    ChainPlanNode bare =
        new ChainPlanNode("node-a", "service-call", "Get Order", null, null, List.of());
    assertTrue(bare.serviceCallId().isEmpty());
    assertTrue(bare.semanticNodeId().isEmpty());
  }

  @Test
  void keepsDistinctOwnersWhenOperationIdsMatch() {
    ChainPlanNode first = serviceCall("node-a", "call-a", "op-shared");
    ChainPlanNode second = serviceCall("node-b", "call-b", "op-shared");

    assertEquals("call-a", first.serviceCallId().orElseThrow());
    assertEquals("call-b", second.serviceCallId().orElseThrow());
    assertEquals("node-a", first.semanticNodeId().orElseThrow());
    assertEquals("node-b", second.semanticNodeId().orElseThrow());
    assertEquals(
        property(first, "integrationOperationId"), property(second, "integrationOperationId"));
  }

  private static ChainPlanNode serviceCall(String nodeId, String serviceCallId, String operationId) {
    return new ChainPlanNode(
        nodeId,
        "service-call",
        "Call",
        null,
        null,
        List.of(
            new PlanProperty("serviceCallId", serviceCallId),
            new PlanProperty("semanticNodeId", nodeId),
            new PlanProperty("integrationOperationId", operationId)));
  }

  private static String property(ChainPlanNode node, String key) {
    for (PlanProperty property : node.properties()) {
      if (key.equals(property.key())) {
        return property.value();
      }
    }
    throw new AssertionError("missing property " + key);
  }
}
