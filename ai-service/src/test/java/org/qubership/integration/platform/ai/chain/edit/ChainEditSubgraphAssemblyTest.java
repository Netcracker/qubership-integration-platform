package org.qubership.integration.platform.ai.chain.edit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.integration.catalog.descriptor.CatalogChildQuantity;
import org.qubership.integration.platform.ai.integration.catalog.descriptor.CatalogElementDescriptor;
import org.qubership.integration.platform.ai.integration.catalog.descriptor.CatalogElementDescriptorCache;
import org.qubership.integration.platform.ai.integration.catalog.descriptor.CatalogElementDescriptorLoader;
import org.qubership.integration.platform.ai.integration.catalog.descriptor.CatalogElementDescriptorTestSupport;
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
  private static final String REPLY = "send-reply";

  @Test
  void onlyTheElementTheEditNamesEndsUpInsideTheContainer() {
    ChainPlanGraph assembled =
        ChainEditSubgraphAssembly.assemble(
            importedChain(), errorHandling(), wrap(CALL), permissiveCache());

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
        ChainEditSubgraphAssembly.assemble(
            importedChain(), errorHandling(), wrap(CALL), permissiveCache());

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
        ChainEditSubgraphAssembly.assemble(
            importedChain(), errorHandling(), wrap(CALL), permissiveCache());

    ChainPlanNode wrapped = node(assembled, CALL);
    assertEquals("service-call", wrapped.type());
    assertEquals("Call orders", wrapped.label());
    assertEquals(List.of(new PlanProperty("retryCount", "3")), wrapped.properties());
  }

  @Test
  void aNewElementNestsInTheBranchThatDeclaredItAndCarriesNoProperties() {
    ChainPlanGraph assembled =
        ChainEditSubgraphAssembly.assemble(
            importedChain(), errorHandling(), wrap(CALL), permissiveCache());

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
        ChainEditSubgraphAssembly.assemble(importedChain(), capture, wrap(CALL), permissiveCache());

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
        ChainEditSubgraphAssembly.assemble(
            importedChain(), twoCatches, wrap(CALL), permissiveCache());

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
  void branchOrderFollowsThePriorityPropertyRatherThanTheCaptureListPosition() {
    CatalogElementDescriptorCache descriptors = tryCatchDescriptors(true);
    ChainEditSubgraph reversedInTheCapture =
        new ChainEditSubgraph(
            "try-catch-finally-2",
            "Error handler",
            List.of(
                tryBranch(CALL),
                catchBranch("java.lang.Exception", 1, "log-failure"),
                catchBranch("java.net.SocketTimeoutException", 0, "log-timeout")));

    ChainPlanGraph assembled =
        ChainEditSubgraphAssembly.assemble(
            importedChain(), reversedInTheCapture, wrap(CALL), descriptors);

    List<ChainPlanNode> catches =
        assembled.nodes().stream().filter(node -> "catch-2".equals(node.type())).toList();
    assertEquals(2, catches.size());
    assertEquals(0, catches.get(0).order());
    assertEquals(1, catches.get(1).order());
    assertEquals(
        List.of(
            new PlanProperty("exception", "java.net.SocketTimeoutException"),
            new PlanProperty("priority", "0")),
        catches.get(0).properties());
  }

  @Test
  void aRepeatableRoleOtherThanCatchIsOrderedByTheDescriptorsOwnPriorityProperty() {
    CatalogElementDescriptorCache descriptors = ifDescriptors(true, "branchPriority");
    ChainEditSubgraph reversedInTheCapture =
        new ChainEditSubgraph(
            "if-2",
            "Route by amount",
            List.of(whenBranch("true", 1, SCRIPT), whenBranch("payload.amount > 100", 0, CALL)));

    ChainPlanGraph assembled =
        ChainEditSubgraphAssembly.assemble(
            importedChain(), reversedInTheCapture, wrap(CALL, SCRIPT), descriptors);

    List<ChainPlanNode> branches =
        assembled.nodes().stream().filter(node -> "when-2".equals(node.type())).toList();
    assertEquals(2, branches.size());
    assertEquals(branches.get(0).nodeId(), node(assembled, CALL).parentNodeId());
    assertEquals(branches.get(1).nodeId(), node(assembled, SCRIPT).parentNodeId());
    assertEquals(
        List.of(
            new PlanProperty("condition", "payload.amount > 100"),
            new PlanProperty("branchPriority", "0")),
        branches.get(0).properties());
  }

  @Test
  void aBranchWithNeitherNewNorMovedElementsIsLeftToChildlessContainerPruning() {
    CatalogElementDescriptorCache descriptors = tryCatchDescriptorsWithOptionalFinally();
    ChainEditSubgraph withAnEmptyFinally =
        new ChainEditSubgraph(
            "try-catch-finally-2",
            "Error handler",
            List.of(
                tryBranch(CALL),
                catchBranch("java.lang.Exception", null, "log-failure"),
                new ChainEditSubgraphBranch(
                    "finally-2", "Finally", List.of(), null, List.of(), null)));

    ChainPlanGraph assembled =
        assertDoesNotThrow(
            () ->
                ChainEditSubgraphAssembly.assemble(
                    importedChain(), withAnEmptyFinally, wrap(CALL), descriptors));

    assertEquals("finally-2", nodeOfType(assembled, "finally-2").type());
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
        ChainEditSubgraphAssembly.assemble(nested, errorHandling(), wrap(CALL), permissiveCache());

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
                    importedChain(), movesTheNeighbourToo, wrap(CALL), permissiveCache()));

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
                    importedChain(), errorHandling(), wrap(CALL, SCRIPT), permissiveCache()));

    assertTrue(refused.getMessage().contains(SCRIPT), refused.getMessage());
  }

  @Test
  void anIntentTargetTheChainDoesNotHoldEndsTheTurn() {
    ChainEditScopeException refused =
        assertThrows(
            ChainEditScopeException.class,
            () ->
                ChainEditSubgraphAssembly.assemble(
                    importedChain(), tryOnly("ghost"), wrap("ghost"), permissiveCache()));

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
            () ->
                ChainEditSubgraphAssembly.assemble(
                    importedChain(), reusesAnId, wrap(CALL), permissiveCache()));

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
            () ->
                ChainEditSubgraphAssembly.assemble(
                    importedChain(), crossesBranches, wrap(CALL), permissiveCache()));

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
                    wrap(CALL),
                    permissiveCache()));

    assertTrue(refused.getMessage().contains("try-catch-finally-2"), refused.getMessage());
  }

  @Test
  void aBranchTypeTheContainerDoesNotAllowIsRefusedNamingTheTypeAndTheContainer() {
    CatalogElementDescriptorCache descriptors =
        cacheWithContainer(
            container("try-catch-finally-2", Map.of("try-2", CatalogChildQuantity.ONE), false));

    ChainEditScopeException refused =
        assertThrows(
            ChainEditScopeException.class,
            () ->
                ChainEditSubgraphAssembly.assemble(
                    importedChain(), errorHandling(), wrap(CALL), descriptors));

    assertTrue(refused.getMessage().contains("catch-2"), refused.getMessage());
    assertTrue(refused.getMessage().contains("try-catch-finally-2"), refused.getMessage());
  }

  @Test
  void twoTryBranchesExceedTheContainersQuantityBoundsAndAreRefused() {
    CatalogElementDescriptorCache descriptors = tryCatchDescriptors(false);
    ChainEditSubgraph twoTries =
        new ChainEditSubgraph(
            "try-catch-finally-2",
            "Error handler",
            List.of(
                tryBranch(CALL),
                new ChainEditSubgraphBranch("try-2", "Try", List.of(), null, List.of(), null),
                catchBranch("java.lang.Exception", null, "log-failure")));

    ChainEditScopeException refused =
        assertThrows(
            ChainEditScopeException.class,
            () -> ChainEditSubgraphAssembly.assemble(importedChain(), twoTries, wrap(CALL), descriptors));

    assertTrue(refused.getMessage().contains("try-2"), refused.getMessage());
    assertTrue(refused.getMessage().contains("at most 1"), refused.getMessage());
  }

  @Test
  void twoCatchBranchesFallWithinTheContainersQuantityBoundsAndAreAccepted() {
    CatalogElementDescriptorCache descriptors = tryCatchDescriptors(false);
    ChainEditSubgraph twoCatches =
        new ChainEditSubgraph(
            "try-catch-finally-2",
            "Error handler",
            List.of(
                tryBranch(CALL),
                catchBranch("java.net.SocketTimeoutException", null, "log-timeout"),
                catchBranch("java.lang.Exception", null, "log-failure")));

    assertDoesNotThrow(
        () -> ChainEditSubgraphAssembly.assemble(importedChain(), twoCatches, wrap(CALL), descriptors));
  }

  @Test
  void aRepeatedBranchMissingItsDistinguishingPropertyIsRefused() {
    CatalogElementDescriptorCache descriptors = tryCatchDescriptors(false);
    ChainEditSubgraph undistinguished =
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
                        List.of(new ChainEditSubgraphElement("log-timeout", "script", "Log timeout")),
                        List.of())),
                catchBranch("java.lang.Exception", null, "log-failure")));

    ChainEditScopeException refused =
        assertThrows(
            ChainEditScopeException.class,
            () ->
                ChainEditSubgraphAssembly.assemble(
                    importedChain(), undistinguished, wrap(CALL), descriptors));

    assertTrue(refused.getMessage().contains("catch-2"), refused.getMessage());
    assertTrue(refused.getMessage().contains("no property to tell it from its sibling"),
        refused.getMessage());
  }

  @Test
  void aRepeatedBranchInAnOrderedContainerMissingItsOrderIsRefused() {
    CatalogElementDescriptorCache descriptors = tryCatchDescriptors(true);
    ChainEditSubgraph unordered =
        new ChainEditSubgraph(
            "try-catch-finally-2",
            "Error handler",
            List.of(
                tryBranch(CALL),
                catchBranch("java.net.SocketTimeoutException", null, "log-timeout"),
                catchBranch("java.lang.Exception", 1, "log-failure")));

    ChainEditScopeException refused =
        assertThrows(
            ChainEditScopeException.class,
            () ->
                ChainEditSubgraphAssembly.assemble(
                    importedChain(), unordered, wrap(CALL), descriptors));

    assertTrue(refused.getMessage().contains("catch-2"), refused.getMessage());
    assertTrue(refused.getMessage().contains("no order"), refused.getMessage());
  }

  @Test
  void theAssembledGraphIsCheckedAgainstTheDescriptorPreflightInTheSameTurn() {
    CatalogElementDescriptorCache descriptors = tryCatchDescriptorsWithMandatoryInnerBranches();
    ChainEditSubgraph emptyCatch =
        new ChainEditSubgraph(
            "try-catch-finally-2",
            "Error handler",
            List.of(
                tryBranch(CALL),
                new ChainEditSubgraphBranch(
                    "catch-2",
                    "Catch",
                    List.of(new PlanProperty("exception", "java.lang.Exception")),
                    null,
                    List.of(),
                    null)));

    ChainEditScopeException refused =
        assertThrows(
            ChainEditScopeException.class,
            () ->
                ChainEditSubgraphAssembly.assemble(
                    importedChain(), emptyCatch, wrap(CALL), descriptors));

    assertTrue(refused.getMessage().contains("requires inner content"), refused.getMessage());
  }

  @Test
  void aCaptureThatSatisfiesARestrictiveOrderedDescriptorIsUnaffected() {
    CatalogElementDescriptorCache descriptors = tryCatchDescriptors(true);

    assertDoesNotThrow(
        () -> ChainEditSubgraphAssembly.assemble(importedChain(), errorHandling(), wrap(CALL), descriptors));
  }

  @Test
  void everyElementOfAnAdjacentGroupMovesIntoTheBranchThatClaimsThem() {
    ChainPlanGraph assembled =
        ChainEditSubgraphAssembly.assemble(
            chainWithATail(),
            errorHandlingAround(CALL, SCRIPT),
            wrap(CALL, SCRIPT),
            permissiveCache());

    String tryBranch = nodeOfType(assembled, "try-2").nodeId();
    assertEquals(tryBranch, node(assembled, CALL).parentNodeId());
    assertEquals(tryBranch, node(assembled, SCRIPT).parentNodeId());
    assertNull(node(assembled, TRIGGER).parentNodeId());
    assertNull(node(assembled, REPLY).parentNodeId());
  }

  @Test
  void aConnectionBetweenTwoElementsOfTheGroupIsKeptAsItWas() {
    ChainPlanGraph assembled =
        ChainEditSubgraphAssembly.assemble(
            chainWithATail(),
            errorHandlingAround(CALL, SCRIPT),
            wrap(CALL, SCRIPT),
            permissiveCache());

    ChainPlanEdge withinTheGroup = edge(assembled, "call-to-script");
    assertEquals(CALL, withinTheGroup.fromNodeId());
    assertEquals(SCRIPT, withinTheGroup.toNodeId());
  }

  @Test
  void theConnectionsIntoAndOutOfTheGroupAttachToTheContainer() {
    ChainPlanGraph assembled =
        ChainEditSubgraphAssembly.assemble(
            chainWithATail(),
            errorHandlingAround(CALL, SCRIPT),
            wrap(CALL, SCRIPT),
            permissiveCache());

    String container = nodeOfType(assembled, "try-catch-finally-2").nodeId();
    assertTrue(connects(assembled, TRIGGER, container));
    assertTrue(connects(assembled, container, REPLY));
    assertFalse(connects(assembled, TRIGGER, CALL));
    assertFalse(connects(assembled, SCRIPT, REPLY));
  }

  @Test
  void everyBranchTakesTheElementItNamesAndTheirUnionIsTheApprovedGroup() {
    ChainEditSubgraph condition =
        new ChainEditSubgraph(
            "if-2",
            "Route by amount",
            List.of(
                whenBranch("payload.amount > 100", 0, CALL),
                whenBranch("true", 1, SCRIPT)));

    ChainPlanGraph assembled =
        ChainEditSubgraphAssembly.assemble(
            importedChain(), condition, wrap(CALL, SCRIPT), permissiveCache());

    List<ChainPlanNode> branches =
        assembled.nodes().stream().filter(node -> "when-2".equals(node.type())).toList();
    assertEquals(branches.get(0).nodeId(), node(assembled, CALL).parentNodeId());
    assertEquals(branches.get(1).nodeId(), node(assembled, SCRIPT).parentNodeId());
  }

  @Test
  void movingOneElementIntoTwoBranchesIsRefused() {
    ChainEditSubgraph claimedTwice =
        new ChainEditSubgraph(
            "if-2",
            "Route by amount",
            List.of(whenBranch("payload.amount > 100", 0, CALL), whenBranch("true", 1, CALL)));

    ChainEditScopeException refused =
        assertThrows(
            ChainEditScopeException.class,
            () ->
                ChainEditSubgraphAssembly.assemble(
                    importedChain(), claimedTwice, wrap(CALL), permissiveCache()));

    assertTrue(refused.getMessage().contains(CALL), refused.getMessage());
    assertTrue(refused.getMessage().contains("more than one branch"), refused.getMessage());
  }

  @Test
  void severalLinkedElementsSpliceIntoOneRunBetweenTheNamedAddress() {
    ChainPlanGraph assembled =
        ChainEditSubgraphAssembly.assemble(
            importedChain(),
            insertion(
                List.of(element("audit"), element("notify")),
                List.of(new ChainEditSubgraphConnection("audit", "notify"))),
            insertBetween(CALL, SCRIPT),
            permissiveCache());

    assertTrue(connects(assembled, CALL, "audit"));
    assertTrue(connects(assembled, "audit", "notify"));
    assertTrue(connects(assembled, "notify", SCRIPT));
    assertFalse(connects(assembled, CALL, SCRIPT));
  }

  @Test
  void theAddressElementsStayExactlyWhereTheyAreAndKeepTheirOtherConnections() {
    ChainPlanGraph assembled =
        ChainEditSubgraphAssembly.assemble(
            importedChain(), insertion(element("audit")), insertBetween(CALL, SCRIPT), permissiveCache());

    assertEquals(node(importedChain(), CALL), node(assembled, CALL));
    assertEquals(node(importedChain(), SCRIPT), node(assembled, SCRIPT));
    assertTrue(connects(assembled, TRIGGER, CALL), "the address element's other connections are untouched");
  }

  @Test
  void namingOnlyThePrecedingElementInsertsBeforeItsSoleSuccessor() {
    ChainPlanGraph assembled =
        ChainEditSubgraphAssembly.assemble(
            importedChain(), insertion(element("audit")), insertBetween(CALL), permissiveCache());

    assertTrue(connects(assembled, CALL, "audit"));
    assertTrue(connects(assembled, "audit", SCRIPT));
    assertFalse(connects(assembled, CALL, SCRIPT));
  }

  @Test
  void anInsertionAfterAnElementWithNoSuccessorAppendsAtTheEnd() {
    ChainPlanGraph assembled =
        ChainEditSubgraphAssembly.assemble(
            importedChain(), insertion(element("audit")), insertBetween(SCRIPT), permissiveCache());

    assertTrue(connects(assembled, SCRIPT, "audit"));
  }

  @Test
  void anAddressNamedAloneWithMoreThanOneSuccessorIsUnsatisfiable() {
    ChainPlanGraph branching =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("Orders", "Orders"),
            List.of(
                new ChainPlanNode(CALL, "service-call", "Call orders", null, null, List.of()),
                new ChainPlanNode("branch-a", "script", "Branch A", null, null, List.of()),
                new ChainPlanNode("branch-b", "script", "Branch B", null, null, List.of())),
            List.of(
                new ChainPlanEdge("edge-a", CALL, "branch-a", null),
                new ChainPlanEdge("edge-b", CALL, "branch-b", null)));

    ChainEditScopeException refused =
        assertThrows(
            ChainEditScopeException.class,
            () ->
                ChainEditSubgraphAssembly.assemble(
                    branching, insertion(element("audit")), insertBetween(CALL), permissiveCache()));

    assertTrue(refused.unsatisfiable(), refused.getMessage());
  }

  @Test
  void aContainerNamedForAnInsertionIsRefused() {
    ChainEditScopeException refused =
        assertThrows(
            ChainEditScopeException.class,
            () ->
                ChainEditSubgraphAssembly.assemble(
                    importedChain(),
                    new ChainEditSubgraph("try-catch-finally-2", "Wrap", List.of()),
                    insertBetween(CALL, SCRIPT),
                    permissiveCache()));

    assertTrue(refused.getMessage().contains("try-catch-finally-2"), refused.getMessage());
  }

  @Test
  void branchesNamedForAnInsertionAreRefused() {
    ChainEditScopeException refused =
        assertThrows(
            ChainEditScopeException.class,
            () ->
                ChainEditSubgraphAssembly.assemble(
                    importedChain(),
                    new ChainEditSubgraph(null, null, List.of(tryBranch(CALL))),
                    insertBetween(CALL, SCRIPT),
                    permissiveCache()));

    assertTrue(refused.getMessage().contains("branches"), refused.getMessage());
  }

  @Test
  void anEmptyInsertionBodyIsRefused() {
    ChainEditScopeException refused =
        assertThrows(
            ChainEditScopeException.class,
            () ->
                ChainEditSubgraphAssembly.assemble(
                    importedChain(),
                    new ChainEditSubgraph(null, null, List.of()),
                    insertBetween(CALL, SCRIPT),
                    permissiveCache()));

    assertTrue(refused.getMessage().contains("creates no elements"), refused.getMessage());
  }

  @Test
  void aBodyThatDoesNotFormASingleConnectedRunIsRefused() {
    ChainEditScopeException refused =
        assertThrows(
            ChainEditScopeException.class,
            () ->
                ChainEditSubgraphAssembly.assemble(
                    importedChain(),
                    insertion(List.of(element("audit"), element("notify")), List.of()),
                    insertBetween(CALL, SCRIPT),
                    permissiveCache()));

    assertTrue(refused.getMessage().contains("single linked run"), refused.getMessage());
  }

  @Test
  void connectingToAnElementTheInsertionDoesNotCreateIsRefused() {
    ChainEditSubgraph reachesOutsideTheBody =
        insertion(
            List.of(element("audit")), List.of(new ChainEditSubgraphConnection("audit", CALL)));

    ChainEditScopeException refused =
        assertThrows(
            ChainEditScopeException.class,
            () ->
                ChainEditSubgraphAssembly.assemble(
                    importedChain(), reachesOutsideTheBody, insertBetween(CALL, SCRIPT), permissiveCache()));

    assertTrue(refused.getMessage().contains(CALL), refused.getMessage());
    assertTrue(refused.getMessage().contains("does not create"), refused.getMessage());
  }

  @Test
  void anInsertionBetweenTwoElementsOfAContainerKeepsTheNewElementsInThatContainer() {
    ChainPlanGraph nested =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("Orders", "Orders"),
            List.of(
                new ChainPlanNode("split", "split-2", "Split", null, null, List.of()),
                new ChainPlanNode(
                    "split-element", "split-element-2", "Element", "split", null, List.of()),
                new ChainPlanNode(
                    CALL, "service-call", "Call orders", "split-element", null, List.of()),
                new ChainPlanNode(SCRIPT, "script", "Normalize payload", "split-element", null, List.of())),
            List.of(new ChainPlanEdge("call-to-script", CALL, SCRIPT, "split-element")));

    ChainPlanGraph assembled =
        ChainEditSubgraphAssembly.assemble(
            nested, insertion(element("audit")), insertBetween(CALL, SCRIPT), permissiveCache());

    assertEquals("split-element", node(assembled, "audit").parentNodeId());
  }

  private static ChainEditIntent insertBetween(String... targetNodeIds) {
    return new ChainEditIntent(
        ChainEditAction.ADD_ELEMENTS,
        List.of(targetNodeIds),
        "add error handling between the named elements",
        null,
        "script",
        null,
        List.of(),
        List.of(),
        ChainEditDisposition.KEEP);
  }

  private static ChainEditSubgraph insertion(ChainEditSubgraphElement element) {
    return insertion(List.of(element), List.of());
  }

  private static ChainEditSubgraph insertion(
      List<ChainEditSubgraphElement> elements, List<ChainEditSubgraphConnection> connections) {
    return new ChainEditSubgraph(null, null, List.of(), new ChainEditSubgraphBody(elements, connections));
  }

  private static ChainEditSubgraphElement element(String nodeId) {
    return new ChainEditSubgraphElement(nodeId, "script", "Audit");
  }

  private static ChainEditSubgraph errorHandling() {
    return errorHandlingAround(CALL);
  }

  /** The same wrap, around whichever run of existing elements the reader named. */
  private static ChainEditSubgraph errorHandlingAround(String... moveExisting) {
    return new ChainEditSubgraph(
        "try-catch-finally-2",
        "Error handler",
        List.of(tryBranch(moveExisting), catchBranch("java.lang.Exception", null, "log-failure")));
  }

  /** One branch of a condition, claiming the existing elements that route through it. */
  private static ChainEditSubgraphBranch whenBranch(
      String condition, Integer order, String... moveExisting) {
    return new ChainEditSubgraphBranch(
        "when-2",
        "When",
        List.of(new PlanProperty("condition", condition)),
        order,
        List.of(moveExisting),
        null);
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

  /** The same chain with an element after the script, so a group has a hop leaving it as well. */
  private static ChainPlanGraph chainWithATail() {
    ChainPlanGraph chain = importedChain();
    List<ChainPlanNode> nodes = new ArrayList<>(chain.nodes());
    nodes.add(new ChainPlanNode(REPLY, "script", "Send reply", null, 4, List.of()));
    List<ChainPlanEdge> edges = new ArrayList<>(chain.edges());
    edges.add(new ChainPlanEdge("script-to-reply", SCRIPT, REPLY, null));
    return new ChainPlanGraph(chain.schemaVersion(), chain.chain(), nodes, edges);
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

  private static ChainPlanEdge edge(ChainPlanGraph graph, String edgeId) {
    return graph.edges().stream()
        .filter(candidate -> edgeId.equals(candidate.edgeId()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no connection " + edgeId));
  }

  private static ChainPlanNode nodeOfType(ChainPlanGraph graph, String type) {
    return graph.nodes().stream()
        .filter(candidate -> type.equals(candidate.type()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no element of type " + type));
  }

  /** Every type is a permissive container, so descriptor validation never fires. */
  private static CatalogElementDescriptorCache permissiveCache() {
    CatalogElementDescriptorLoader loader = mock(CatalogElementDescriptorLoader.class);
    CatalogElementDescriptorTestSupport.stubPermissive(loader);
    return new CatalogElementDescriptorCache(loader);
  }

  /** {@code container} is stubbed as given; every other type stays permissive. */
  private static CatalogElementDescriptorCache cacheWithContainer(
      CatalogElementDescriptor container) {
    CatalogElementDescriptorLoader loader = mock(CatalogElementDescriptorLoader.class);
    lenient()
        .when(loader.load(anyString()))
        .thenAnswer(
            invocation -> {
              String type = invocation.getArgument(0);
              return container.name().equals(type)
                  ? container
                  : CatalogElementDescriptorTestSupport.permissive(type);
            });
    return new CatalogElementDescriptorCache(loader);
  }

  private static CatalogElementDescriptor container(
      String type, Map<String, CatalogChildQuantity> allowedChildren, boolean ordered) {
    return new CatalogElementDescriptor(
        type, true, allowedChildren, List.of(), ordered, "priority", false, false, false, true);
  }

  /** The wrapper allows exactly one try branch and any number of catch branches. */
  private static CatalogElementDescriptorCache tryCatchDescriptors(boolean ordered) {
    return cacheWithContainer(
        container(
            "try-catch-finally-2",
            Map.of("try-2", CatalogChildQuantity.ONE, "catch-2", CatalogChildQuantity.ONE_OR_MANY),
            ordered));
  }

  /**
   * A repeatable role that is not catch, so descriptor-driven assembly is checked against more than
   * one container type. {@code priorityProperty} is caller-supplied rather than the catalog default,
   * so a test using it proves the property name is read from the descriptor rather than hardcoded.
   */
  private static CatalogElementDescriptorCache ifDescriptors(
      boolean ordered, String priorityProperty) {
    return cacheWithContainer(
        new CatalogElementDescriptor(
            "if-2",
            true,
            Map.of("when-2", CatalogChildQuantity.ONE_OR_MANY),
            List.of(),
            ordered,
            priorityProperty,
            false,
            false,
            false,
            true));
  }

  /**
   * Like {@link #tryCatchDescriptors}, but {@code finally-2} is optional and, like try and catch,
   * requires inner content when present -- so an empty one is only safe when pruned before it is
   * declared to need content.
   */
  private static CatalogElementDescriptorCache tryCatchDescriptorsWithOptionalFinally() {
    CatalogElementDescriptorLoader loader = mock(CatalogElementDescriptorLoader.class);
    lenient()
        .when(loader.load(anyString()))
        .thenAnswer(
            invocation -> {
              String type = invocation.getArgument(0);
              return switch (type) {
                case "try-catch-finally-2" ->
                    container(
                        type,
                        Map.of(
                            "try-2", CatalogChildQuantity.ONE,
                            "catch-2", CatalogChildQuantity.ONE_OR_MANY,
                            "finally-2", CatalogChildQuantity.ONE_OR_ZERO),
                        false);
                case "finally-2" -> CatalogElementDescriptorTestSupport.containerRequiringInner(type);
                default -> CatalogElementDescriptorTestSupport.permissive(type);
              };
            });
    return new CatalogElementDescriptorCache(loader);
  }

  /** Like {@link #tryCatchDescriptors}, but the try and catch branches require inner content. */
  private static CatalogElementDescriptorCache tryCatchDescriptorsWithMandatoryInnerBranches() {
    CatalogElementDescriptorLoader loader = mock(CatalogElementDescriptorLoader.class);
    lenient()
        .when(loader.load(anyString()))
        .thenAnswer(
            invocation -> {
              String type = invocation.getArgument(0);
              return switch (type) {
                case "try-catch-finally-2" ->
                    container(
                        type,
                        Map.of(
                            "try-2", CatalogChildQuantity.ONE,
                            "catch-2", CatalogChildQuantity.ONE_OR_MANY),
                        false);
                case "try-2", "catch-2" ->
                    CatalogElementDescriptorTestSupport.containerRequiringInner(type);
                default -> CatalogElementDescriptorTestSupport.leaf(type);
              };
            });
    return new CatalogElementDescriptorCache(loader);
  }
}
