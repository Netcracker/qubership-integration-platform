package org.qubership.integration.platform.ai.chain.edit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.ChainSection;

class ChainEditIntentResolverTest {

  @Test
  void readsTheActionTheTargetsAndTheLookup() {
    ChainEditIntent intent =
        resolve(
            """
            action: REBIND_SERVICE_CALL
            targets: call-orders
            change: point it at the order-status operation
            lookup: order status
            ambiguous:
            """);

    assertEquals(ChainEditAction.REBIND_SERVICE_CALL, intent.action());
    assertEquals(List.of("call-orders"), intent.targetNodeIds());
    assertEquals("point it at the order-status operation", intent.requestedChange());
    assertEquals("order status", intent.externalBindingQuery());
    assertTrue(intent.resolved());
  }

  @Test
  void aTargetTheChainDoesNotHaveBecomesAQuestionRatherThanATarget() {
    ChainEditIntent intent =
        resolve(
            """
            action: REBIND_SERVICE_CALL
            targets: call-shipping
            change: rebind it
            lookup:
            ambiguous:
            """);

    assertEquals(List.of(), intent.targetNodeIds());
    assertFalse(intent.resolved());
    assertEquals(List.of("The chain has no element 'call-shipping'."), intent.unresolvedAmbiguities());
  }

  @Test
  void anUnrecognizedActionResolvesNothing() {
    ChainEditIntent intent =
        resolve(
            """
            action: REWRITE_EVERYTHING
            targets: call-orders
            change: do it all
            lookup:
            ambiguous:
            """);

    assertFalse(intent.resolved());
    assertEquals(List.of(), intent.targetNodeIds());
  }

  @Test
  void proseAroundTheAnswerIsIgnored() {
    ChainEditIntent intent =
        resolve(
            """
            Sure, here is the answer.
            action: DELETE
            targets: call-orders, call-invoices
            change: drop both calls
            lookup:
            ambiguous:
            """);

    assertEquals(ChainEditAction.DELETE, intent.action());
    assertEquals(List.of("call-orders", "call-invoices"), intent.targetNodeIds());
  }

  @Test
  void elementsAreRenderedAsIdTypeAndLabel() {
    assertEquals(
        """
        call-orders | service-call | Call orders
        call-invoices | service-call | Call invoices
        """,
        ChainEditIntentResolver.renderElements(graph()));
  }

  private static ChainEditIntent resolve(String reply) {
    return new ChainEditIntentResolver((elements, userRequest) -> reply)
        .resolve(graph(), "change something");
  }

  private static ChainPlanGraph graph() {
    return new ChainPlanGraph(
        "1.0",
        new ChainSection("orders", "Orders"),
        List.of(
            new ChainPlanNode("call-orders", "service-call", "Call orders", null, null, List.of()),
            new ChainPlanNode(
                "call-invoices", "service-call", "Call invoices", null, null, List.of())),
        List.of());
  }
}
