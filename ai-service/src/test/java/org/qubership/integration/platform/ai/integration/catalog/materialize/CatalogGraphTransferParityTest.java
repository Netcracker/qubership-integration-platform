package org.qubership.integration.platform.ai.integration.catalog.materialize;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.plan.model.ChainPlanEdge;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.ChainSection;

/** Transfer and dependency replacement cases on the in-memory catalog REST boundary. */
class CatalogGraphTransferParityTest {

  private static final ChainSection SECTION = new ChainSection("parity-chain", "Parity");

  private CatalogGraphMaterializerTestHarness harness;

  @BeforeEach
  void setUp() {
    harness = new CatalogGraphMaterializerTestHarness();
    harness.catalog().setGeneratedChildDelivery(InMemoryCatalogRestClient.GeneratedChildDelivery.INLINE);
  }

  @Test
  void movesOneExistingElementUnderGeneratedTry2() {
    ChainPlanGraph current =
        graph(
            trigger("trigger"),
            node("service-call", "service-call", null));
    ChainPlanGraph desired =
        graph(
            trigger("trigger"),
            node("wrapper", "try-catch-finally-2", null),
            node("try", "try-2", "wrapper"),
            node("catch-1", "catch-2", "wrapper"),
            node("service-call", "service-call", "try"));

    CatalogGraphMaterializeResult result = harness.edit(current, desired);
    assertTrue(result.succeeded(), result.error());
    CatalogGraphParityAssertions.assertOwnership(desired, result.materializationMap());
  }

  @Test
  void movesConnectedSequenceTogether() {
    ChainPlanGraph current =
        new ChainPlanGraph(
            "1.0",
            SECTION,
            List.of(
                trigger("trigger"),
                node("node-a", "script", null),
                node("node-b", "script", null)),
            List.of());
    ChainPlanGraph desired =
        new ChainPlanGraph(
            "1.0",
            SECTION,
            List.of(
                trigger("trigger"),
                node("wrapper", "try-catch-finally-2", null),
                node("try", "try-2", "wrapper"),
                node("catch-1", "catch-2", "wrapper"),
                node("node-a", "script", "try"),
                node("node-b", "script", "try")),
            List.of());

    CatalogGraphMaterializeResult result = harness.edit(current, desired);
    assertTrue(result.succeeded(), result.error());
    CatalogGraphParityAssertions.assertOwnership(desired, result.materializationMap());
  }

  @Test
  void movesElementsToSeveralDestinationParents() {
    ChainPlanGraph current =
        graph(
            trigger("trigger"),
            node("service-a", "service-call", null),
            node("service-b", "service-call", null));
    ChainPlanGraph desired =
        graph(
            trigger("trigger"),
            node("try-wrapper", "try-catch-finally-2", null),
            node("try", "try-2", "try-wrapper"),
            node("catch-1", "catch-2", "try-wrapper"),
            node("condition-1", "condition", null),
            node("if-1", "if", "condition-1"),
            node("service-a", "service-call", "try"),
            node("service-b", "service-call", "if-1"));

    CatalogGraphMaterializeResult result = harness.edit(current, desired);
    assertTrue(result.succeeded(), result.error());
    CatalogGraphParityAssertions.assertOwnership(desired, result.materializationMap());
  }

  @Test
  void replacesDependencyWhenEdgeEndpointsChange() {
    ChainPlanGraph current =
        new ChainPlanGraph(
            "1.0",
            SECTION,
            List.of(
                trigger("trigger"),
                node("node-a", "script", null),
                node("node-b", "script", null),
                node("node-c", "script", null)),
            List.of(new ChainPlanEdge("edge-ab", "node-a", "node-b", null)));
    ChainPlanGraph desired =
        new ChainPlanGraph(
            "1.0",
            SECTION,
            List.of(
                trigger("trigger"),
                node("node-a", "script", null),
                node("node-b", "script", null),
                node("node-c", "script", null)),
            List.of(new ChainPlanEdge("edge-ab", "node-a", "node-c", null)));

    CatalogGraphMaterializeResult result = harness.edit(current, desired);
    assertTrue(result.succeeded(), result.error());
    CatalogGraphParityAssertions.assertOwnership(desired, result.materializationMap());

    var imported = harness.importCatalog(result.materializationMap());
    Map<String, String> ids = result.materializationMap().nodeIdToElementId();
    String nodeA = ids.get("node-a");
    String nodeB = ids.get("node-b");
    String nodeC = ids.get("node-c");
    assertTrue(
        imported.graph().edges().stream()
            .anyMatch(edge -> nodeA.equals(edge.fromNodeId()) && nodeC.equals(edge.toNodeId())));
    assertFalse(
        imported.graph().edges().stream()
            .anyMatch(edge -> nodeA.equals(edge.fromNodeId()) && nodeB.equals(edge.toNodeId())));
  }

  @Test
  void rejectsTransferThatCreatesDependencyInsteadOfChangingParent() {
    harness.catalog().setTransferBehavior(InMemoryCatalogRestClient.TransferBehavior.DEPENDENCY_INSTEAD);

    ChainPlanGraph current =
        graph(
            trigger("trigger"),
            node("service-call", "service-call", null));
    ChainPlanGraph desired =
        graph(
            trigger("trigger"),
            node("wrapper", "try-catch-finally-2", null),
            node("try", "try-2", "wrapper"),
            node("catch-1", "catch-2", "wrapper"),
            node("service-call", "service-call", "try"));

    CatalogGraphMaterializeResult result = harness.edit(current, desired);
    assertFalse(result.succeeded());
    assertNotNull(result.error());
    assertTrue(result.error().contains("catalog parent is still"));
  }

  private static ChainPlanGraph graph(ChainPlanNode... nodes) {
    return new ChainPlanGraph("1.0", SECTION, List.of(nodes), List.of());
  }

  private static ChainPlanNode trigger(String nodeId) {
    return node(nodeId, "http-trigger", null);
  }

  private static ChainPlanNode node(String nodeId, String type, String parentNodeId) {
    return new ChainPlanNode(nodeId, type, nodeId, parentNodeId, null, List.of());
  }
}
