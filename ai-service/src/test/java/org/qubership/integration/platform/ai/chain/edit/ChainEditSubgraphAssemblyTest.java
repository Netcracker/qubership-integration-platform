package org.qubership.integration.platform.ai.chain.edit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.plan.model.ChainPlanEdge;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.ChainSection;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.qipknowledge.artifact.ChainEditSubgraph;
import org.qubership.integration.platform.ai.qipknowledge.artifact.ChainEditSubgraphBody;
import org.qubership.integration.platform.ai.qipknowledge.artifact.ChainEditSubgraphBranch;
import org.qubership.integration.platform.ai.qipknowledge.artifact.ChainEditSubgraphConnection;
import org.qubership.integration.platform.ai.qipknowledge.artifact.ChainEditSubgraphElement;

/**
 * The imported chain and a capture of what the edit adds go in, the proposed graph comes out.
 *
 * <p>The chain under test is the one the live wrap scenario failed on: an HTTP trigger, a service
 * call, and a script in a row, with a request to add error handling to the service call.
 */
class ChainEditSubgraphAssemblyTest {

  private static final String TRIGGER = "http-entry";
  private static final String CALL = "call-orders";
  private static final String SCRIPT = "normalize";

  @Test
  void onlyTheElementTheEditNamesEndsUpInsideTheContainer() {
    ChainPlanGraph assembled =
        ChainEditSubgraphAssembly.assemble(importedChain(), errorHandling(), wrap(CALL));

    ChainPlanNode container = nodeOfType(assembled, "try-catch-finally-2");
    ChainPlanNode tryBranch = nodeOfType(assembled, "try-2");
    assertNull(container.parentNodeId());
    assertEquals(container.nodeId(), tryBranch.parentNodeId());
    assertEquals(tryBranch.nodeId(), node(assembled, CALL).parentNodeId());
    assertNull(node(assembled, SCRIPT).parentNodeId());
    assertNull(node(assembled, TRIGGER).parentNodeId());
  }

  @Test
  void theWrappedElementKeepsItsIncomingAndOutgoingConnectionsThroughTheContainer() {
    ChainPlanGraph assembled =
        ChainEditSubgraphAssembly.assemble(importedChain(), errorHandling(), wrap(CALL));

    String container = nodeOfType(assembled, "try-catch-finally-2").nodeId();
    assertTrue(connects(assembled, TRIGGER, container));
    assertTrue(connects(assembled, container, SCRIPT));
    assertFalse(connects(assembled, TRIGGER, CALL));
    assertFalse(connects(assembled, CALL, SCRIPT));
    assertEquals(
        List.of("trigger-to-call", "call-to-script"),
        assembled.edges().stream()
            .filter(edge -> edge.scopeNodeId() == null)
            .map(ChainPlanEdge::edgeId)
            .toList());
  }

  @Test
  void anExistingElementKeepsEverythingTheChainAlreadyGivesIt() {
    ChainPlanGraph assembled =
        ChainEditSubgraphAssembly.assemble(importedChain(), errorHandling(), wrap(CALL));

    ChainPlanNode wrapped = node(assembled, CALL);
    assertEquals("service-call", wrapped.type());
    assertEquals("Call orders", wrapped.label());
    assertEquals(List.of(new PlanProperty("retryCount", "3")), wrapped.properties());
  }

  @Test
  void aNewElementNestsInTheBranchThatDeclaredItAndCarriesNoProperties() {
    ChainPlanGraph assembled =
        ChainEditSubgraphAssembly.assemble(importedChain(), errorHandling(), wrap(CALL));

    ChainPlanNode catchBranch = nodeOfType(assembled, "catch-2");
    ChainPlanNode handler = node(assembled, "log-failure");
    assertEquals(catchBranch.nodeId(), handler.parentNodeId());
    assertEquals(List.of(), handler.properties());
  }

  @Test
  void aConnectionInsideABranchIsScopedToThatBranch() {
    ChainEditSubgraph capture =
        new ChainEditSubgraph(
            "try-catch-finally-2",
            "Error handler",
            List.of(
                tryBranch(CALL),
                new ChainEditSubgraphBranch(
                    "catch-2",
                    "Catch",
                    List.of(),
                    null,
                    List.of(),
                    new ChainEditSubgraphBody(
                        List.of(
                            new ChainEditSubgraphElement("log-failure", "script", "Log failure"),
                            new ChainEditSubgraphElement("error-reply", "script", "Error reply")),
                        List.of(
                            new ChainEditSubgraphConnection("log-failure", "error-reply"))))));

    ChainPlanGraph assembled =
        ChainEditSubgraphAssembly.assemble(importedChain(), capture, wrap(CALL));

    ChainPlanEdge inCatch =
        assembled.edges().stream()
            .filter(edge -> "log-failure".equals(edge.fromNodeId()))
            .findFirst()
            .orElseThrow();
    assertEquals("error-reply", inCatch.toNodeId());
    assertEquals(nodeOfType(assembled, "catch-2").nodeId(), inCatch.scopeNodeId());
  }

  @Test
  void aBranchKeepsThePropertyAndOrderThatTellItFromItsSibling() {
    ChainEditSubgraph twoCatches =
        new ChainEditSubgraph(
            "try-catch-finally-2",
            "Error handler",
            List.of(
                tryBranch(CALL),
                catchBranch("java.net.SocketTimeoutException", 0, "log-timeout"),
                catchBranch("java.lang.Exception", 1, "log-failure")));

    ChainPlanGraph assembled =
        ChainEditSubgraphAssembly.assemble(importedChain(), twoCatches, wrap(CALL));

    List<ChainPlanNode> catches =
        assembled.nodes().stream().filter(node -> "catch-2".equals(node.type())).toList();
    assertEquals(2, catches.size());
    assertEquals(
        List.of(new PlanProperty("exception", "java.net.SocketTimeoutException")),
        catches.get(0).properties());
    assertEquals(0, catches.get(0).order());
    assertEquals(1, catches.get(1).order());
  }

  @Test
  void wrappingAnElementInsideAContainerLeavesTheWrapperInThatContainer() {
    ChainPlanGraph nested =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("Orders", "Orders"),
            List.of(
                new ChainPlanNode("split", "split-2", "Split", null, null, List.of()),
                new ChainPlanNode(
                    "split-element", "split-element-2", "Element", "split", null, List.of()),
                new ChainPlanNode(
                    CALL, "service-call", "Call orders", "split-element", null, List.of())),
            List.of());

    ChainPlanGraph assembled =
        ChainEditSubgraphAssembly.assemble(nested, errorHandling(), wrap(CALL));

    assertEquals("split-element", nodeOfType(assembled, "try-catch-finally-2").parentNodeId());
  }

  @Test
  void movingAnElementTheEditDoesNotNameIsRefusedNamingThatElement() {
    ChainEditSubgraph movesTheNeighbourToo =
        new ChainEditSubgraph(
            "try-catch-finally-2",
            "Error handler",
            List.of(
                tryBranch(CALL, SCRIPT),
                catchBranch("java.lang.Exception", null, "log-failure")));

    ChainEditScopeException refused =
        assertThrows(
            ChainEditScopeException.class,
            () ->
                ChainEditSubgraphAssembly.assemble(
                    importedChain(), movesTheNeighbourToo, wrap(CALL)));

    assertTrue(refused.getMessage().contains(SCRIPT), refused.getMessage());
    assertFalse(refused.unsatisfiable());
  }

  @Test
  void leavingOutAnElementTheEditNamesIsRefused() {
    ChainEditScopeException refused =
        assertThrows(
            ChainEditScopeException.class,
            () ->
                ChainEditSubgraphAssembly.assemble(
                    importedChain(), errorHandling(), wrap(CALL, SCRIPT)));

    assertTrue(refused.getMessage().contains(SCRIPT), refused.getMessage());
  }

  @Test
  void anIntentTargetTheChainDoesNotHoldEndsTheTurn() {
    ChainEditScopeException refused =
        assertThrows(
            ChainEditScopeException.class,
            () ->
                ChainEditSubgraphAssembly.assemble(
                    importedChain(), tryOnly("ghost"), wrap("ghost")));

    assertTrue(refused.unsatisfiable(), refused.getMessage());
  }

  @Test
  void creatingAnElementUnderAnIdTheChainAlreadyUsesIsRefused() {
    ChainEditSubgraph reusesAnId =
        new ChainEditSubgraph(
            "try-catch-finally-2",
            "Error handler",
            List.of(
                tryBranch(CALL),
                new ChainEditSubgraphBranch(
                    "catch-2",
                    "Catch",
                    List.of(),
                    null,
                    List.of(),
                    new ChainEditSubgraphBody(
                        List.of(new ChainEditSubgraphElement(SCRIPT, "script", "Log failure")),
                        List.of()))));

    ChainEditScopeException refused =
        assertThrows(
            ChainEditScopeException.class,
            () -> ChainEditSubgraphAssembly.assemble(importedChain(), reusesAnId, wrap(CALL)));

    assertTrue(refused.getMessage().contains(SCRIPT), refused.getMessage());
  }

  @Test
  void connectingToAnElementAnotherBranchCreatesIsRefused() {
    ChainEditSubgraph crossesBranches =
        new ChainEditSubgraph(
            "try-catch-finally-2",
            "Error handler",
            List.of(
                new ChainEditSubgraphBranch(
                    "try-2",
                    "Try",
                    List.of(),
                    null,
                    List.of(CALL),
                    new ChainEditSubgraphBody(
                        List.of(new ChainEditSubgraphElement("audit", "script", "Audit")),
                        List.of(new ChainEditSubgraphConnection("audit", "log-failure")))),
                new ChainEditSubgraphBranch(
                    "catch-2",
                    "Catch",
                    List.of(),
                    null,
                    List.of(),
                    new ChainEditSubgraphBody(
                        List.of(
                            new ChainEditSubgraphElement("log-failure", "script", "Log failure")),
                        List.of()))));

    ChainEditScopeException refused =
        assertThrows(
            ChainEditScopeException.class,
            () -> ChainEditSubgraphAssembly.assemble(importedChain(), crossesBranches, wrap(CALL)));

    assertTrue(refused.getMessage().contains("log-failure"), refused.getMessage());
  }

  @Test
  void aCaptureWithoutABranchIsRefused() {
    ChainEditScopeException refused =
        assertThrows(
            ChainEditScopeException.class,
            () ->
                ChainEditSubgraphAssembly.assemble(
                    importedChain(),
                    new ChainEditSubgraph("try-catch-finally-2", "Error handler", List.of()),
                    wrap(CALL)));

    assertTrue(refused.getMessage().contains("try-catch-finally-2"), refused.getMessage());
  }

  private static ChainEditSubgraph errorHandling() {
    return new ChainEditSubgraph(
        "try-catch-finally-2",
        "Error handler",
        List.of(tryBranch(CALL), catchBranch("java.lang.Exception", null, "log-failure")));
  }

  private static ChainEditSubgraph tryOnly(String... moveExisting) {
    return new ChainEditSubgraph(
        "try-catch-finally-2", "Error handler", List.of(tryBranch(moveExisting)));
  }

  private static ChainEditSubgraphBranch tryBranch(String... moveExisting) {
    return new ChainEditSubgraphBranch(
        "try-2", "Try", List.of(), null, List.of(moveExisting), null);
  }

  private static ChainEditSubgraphBranch catchBranch(
      String exception, Integer order, String handlerNodeId) {
    return new ChainEditSubgraphBranch(
        "catch-2",
        "Catch",
        List.of(new PlanProperty("exception", exception)),
        order,
        List.of(),
        new ChainEditSubgraphBody(
            List.of(new ChainEditSubgraphElement(handlerNodeId, "script", "Log failure")),
            List.of()));
  }

  private static ChainEditIntent wrap(String... targetNodeIds) {
    return new ChainEditIntent(
        ChainEditAction.ADD_ELEMENTS,
        List.of(targetNodeIds),
        "add error handling to the service call",
        null,
        "try-catch-finally-2",
        null,
        List.of(),
        List.of(),
        ChainEditDisposition.NEST);
  }

  private static ChainPlanGraph importedChain() {
    return new ChainPlanGraph(
        "1.0",
        new ChainSection("Orders", "Orders"),
        List.of(
            new ChainPlanNode(TRIGGER, "http-trigger", "HTTP trigger", null, 1, List.of()),
            new ChainPlanNode(
                CALL,
                "service-call",
                "Call orders",
                null,
                2,
                List.of(new PlanProperty("retryCount", "3"))),
            new ChainPlanNode(SCRIPT, "script", "Normalize payload", null, 3, List.of())),
        List.of(
            new ChainPlanEdge("trigger-to-call", TRIGGER, CALL, null),
            new ChainPlanEdge("call-to-script", CALL, SCRIPT, null)));
  }

  private static boolean connects(ChainPlanGraph graph, String fromNodeId, String toNodeId) {
    return graph.edges().stream()
        .anyMatch(
            edge -> fromNodeId.equals(edge.fromNodeId()) && toNodeId.equals(edge.toNodeId()));
  }

  private static ChainPlanNode node(ChainPlanGraph graph, String nodeId) {
    return graph.nodes().stream()
        .filter(candidate -> nodeId.equals(candidate.nodeId()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no element " + nodeId));
  }

  private static ChainPlanNode nodeOfType(ChainPlanGraph graph, String type) {
    return graph.nodes().stream()
        .filter(candidate -> type.equals(candidate.type()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no element of type " + type));
  }
}
