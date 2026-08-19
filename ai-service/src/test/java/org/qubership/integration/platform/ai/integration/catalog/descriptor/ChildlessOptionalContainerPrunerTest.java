package org.qubership.integration.platform.ai.integration.catalog.descriptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.plan.model.ChainPlanEdge;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.ChainSection;

class ChildlessOptionalContainerPrunerTest {

  private static final String WRAPPER = "try-catch-finally-2";

  @Test
  void dropsAnEmptyFinallyBranchSoPreflightAcceptsTheWrap() {
    ChainPlanGraph desired =
        graph(
            node("wrap", WRAPPER, null),
            node("try-shell", "try-2", "wrap"),
            node("main-script", "script", "try-shell"),
            node("catch-shell", "catch-2", "wrap"),
            node("error-script", "script", "catch-shell"),
            node("finally-shell", "finally-2", "wrap"));

    ChainPlanGraph pruned =
        ChildlessOptionalContainerPruner.prune(desired, empty(), cache());

    assertEquals(
        List.of("wrap", "try-shell", "main-script", "catch-shell", "error-script"),
        nodeIds(pruned));
    new DesiredGraphDescriptorPreflight().validate(pruned, empty(), cache());
  }

  @Test
  void keepsAnEmptyBranchTheChainAlreadyHas() {
    ChainPlanGraph desired =
        graph(
            node("wrap", WRAPPER, null),
            node("try-shell", "try-2", "wrap"),
            node("main-script", "script", "try-shell"),
            node("catch-shell", "catch-2", "wrap"),
            node("error-script", "script", "catch-shell"),
            node("finally-shell", "finally-2", "wrap"));
    ChainPlanGraph current = graph(node("finally-shell", "finally-2", "wrap"));

    ChainPlanGraph pruned = ChildlessOptionalContainerPruner.prune(desired, current, cache());

    assertEquals(desired.nodes(), pruned.nodes());
  }

  @Test
  void keepsAnEmptyMandatoryBranchSoPreflightStillRejectsIt() {
    ChainPlanGraph desired =
        graph(
            node("wrap", WRAPPER, null),
            node("try-shell", "try-2", "wrap"),
            node("catch-shell", "catch-2", "wrap"),
            node("error-script", "script", "catch-shell"));

    ChainPlanGraph pruned = ChildlessOptionalContainerPruner.prune(desired, empty(), cache());

    assertEquals(desired.nodes(), pruned.nodes());
    assertThrows(
        DesiredGraphDescriptorPreflightException.class,
        () -> new DesiredGraphDescriptorPreflight().validate(pruned, empty(), cache()));
  }

  @Test
  void dropsEdgesThatReferencedThePrunedBranch() {
    ChainPlanGraph desired =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("demo-chain", "Demo"),
            List.of(
                node("wrap", WRAPPER, null),
                node("try-shell", "try-2", "wrap"),
                node("main-script", "script", "try-shell"),
                node("catch-shell", "catch-2", "wrap"),
                node("error-script", "script", "catch-shell"),
                node("finally-shell", "finally-2", "wrap")),
            List.of(
                new ChainPlanEdge("wrap-to-finally", "wrap", "finally-shell", null),
                new ChainPlanEdge("try-to-script", "try-shell", "main-script", null)));

    ChainPlanGraph pruned = ChildlessOptionalContainerPruner.prune(desired, empty(), cache());

    assertEquals(
        List.of("try-to-script"),
        pruned.edges().stream().map(ChainPlanEdge::edgeId).toList());
  }

  /** The wrapper allows one try, many catches, and at most one finally. */
  private static CatalogElementDescriptorCache cache() {
    CatalogElementDescriptorLoader loader = mock(CatalogElementDescriptorLoader.class);
    lenient()
        .when(loader.load(anyString()))
        .thenAnswer(
            invocation -> {
              String type = invocation.getArgument(0);
              return switch (type) {
                case WRAPPER ->
                    new CatalogElementDescriptor(
                        type,
                        true,
                        Map.of(
                            "try-2", CatalogChildQuantity.ONE,
                            "catch-2", CatalogChildQuantity.ONE_OR_MANY,
                            "finally-2", CatalogChildQuantity.ONE_OR_ZERO),
                        List.of(),
                        false,
                        "priority",
                        false,
                        false,
                        false,
                        true);
                case "try-2", "catch-2", "finally-2" ->
                    CatalogElementDescriptorTestSupport.containerRequiringInner(type);
                default -> CatalogElementDescriptorTestSupport.leaf(type);
              };
            });
    return new CatalogElementDescriptorCache(loader);
  }

  private static List<String> nodeIds(ChainPlanGraph graph) {
    return graph.nodes().stream().map(ChainPlanNode::nodeId).toList();
  }

  private static ChainPlanGraph empty() {
    return new ChainPlanGraph("1.0", new ChainSection("demo-chain", "Demo"), List.of(), List.of());
  }

  private static ChainPlanGraph graph(ChainPlanNode... nodes) {
    return new ChainPlanGraph(
        "1.0", new ChainSection("demo-chain", "Demo"), List.of(nodes), List.of());
  }

  private static ChainPlanNode node(String nodeId, String type, String parentNodeId) {
    return new ChainPlanNode(nodeId, type, nodeId, parentNodeId, null, List.of());
  }
}
