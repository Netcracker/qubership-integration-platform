package org.qubership.integration.platform.ai.chain.edit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.plan.model.ChainPlanEdge;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.ChainSection;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;

class ChainEditStructureMergeTest {

  @Test
  void acceptsNewTopologyWhilePreservingExistingConfiguration() {
    ChainPlanGraph base = baseGraph();
    ChainPlanGraph proposed =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("renamed", "Renamed"),
            List.of(
                new ChainPlanNode("trigger", "http-trigger", "Trigger", null, null, List.of()),
                new ChainPlanNode("script", "script", "Script", "try", null, List.of()),
                new ChainPlanNode(
                    "wrapper",
                    "try-catch-finally-2",
                    "Error handler",
                    null,
                    null,
                    List.of(new PlanProperty("invented", "ignored"))),
                new ChainPlanNode("try", "try-2", "Try", "wrapper", null, List.of()),
                new ChainPlanNode("catch", "catch-2", "Catch", "wrapper", null, List.of())),
            List.of(new ChainPlanEdge("trigger-script", "trigger", "wrapper", null)));

    ChainPlanGraph merged = ChainEditStructureMerge.merge(base, proposed, intent("script"));

    assertEquals(base.chain(), merged.chain());
    assertEquals(
        List.of(new PlanProperty("script", "return 'kept'")), node(merged, "script").properties());
    assertEquals("try", node(merged, "script").parentNodeId());
    assertEquals(List.of(), node(merged, "wrapper").properties());
  }

  @Test
  void rejectsReparentingAnExistingNodeOutsideTheNamedBoundary() {
    ChainPlanGraph proposed =
        new ChainPlanGraph(
            "1.0",
            baseGraph().chain(),
            List.of(
                new ChainPlanNode("trigger", "http-trigger", "Trigger", "wrapper", null, List.of()),
                node(baseGraph(), "script"),
                new ChainPlanNode(
                    "wrapper", "try-catch-finally-2", "Error handler", null, null, List.of())),
            baseGraph().edges());

    assertThrows(
        IllegalArgumentException.class,
        () -> ChainEditStructureMerge.merge(baseGraph(), proposed, intent("script")));
  }

  @Test
  void pinsEchoedIdentityFieldsBackToTheImportedNode() {
    ChainPlanGraph proposed =
        new ChainPlanGraph(
            "1.0",
            baseGraph().chain(),
            List.of(
                new ChainPlanNode("trigger", "http-trigger", "Trigger", null, 7, List.of()),
                new ChainPlanNode(
                    "script",
                    "service-call",
                    "Renamed by the generator",
                    "try",
                    3,
                    List.of(new PlanProperty("script", "return 'echoed over'"))),
                new ChainPlanNode(
                    "wrapper", "try-catch-finally-2", "Error handler", null, null, List.of()),
                new ChainPlanNode("try", "try-2", "Try", "wrapper", null, List.of())),
            List.of(new ChainPlanEdge("trigger-script", "trigger", "wrapper", null)));

    ChainPlanGraph merged = ChainEditStructureMerge.merge(baseGraph(), proposed, intent("script"));

    ChainPlanNode script = node(merged, "script");
    assertEquals("script", script.type());
    assertEquals("Script", script.label());
    assertNull(script.order());
    assertEquals(List.of(new PlanProperty("script", "return 'kept'")), script.properties());
    assertNull(node(merged, "trigger").order());
    assertEquals("try", script.parentNodeId());
  }

  @Test
  void movesADroppedTriggerConnectionOntoTheNewWrapper() {
    ChainPlanGraph proposed =
        new ChainPlanGraph(
            "1.0",
            baseGraph().chain(),
            List.of(
                new ChainPlanNode("trigger", "http-trigger", "Trigger", null, null, List.of()),
                new ChainPlanNode("script", "script", "Script", "try", null, List.of()),
                new ChainPlanNode(
                    "wrapper", "try-catch-finally-2", "Error handler", null, null, List.of()),
                new ChainPlanNode("try", "try-2", "Try", "wrapper", null, List.of())),
            List.of());

    ChainPlanGraph merged = ChainEditStructureMerge.merge(baseGraph(), proposed, intent("script"));

    assertEquals(1, merged.edges().size());
    ChainPlanEdge restored = merged.edges().get(0);
    assertEquals("trigger", restored.fromNodeId());
    assertEquals("wrapper", restored.toNodeId());
  }

  @Test
  void keepsEveryConnectionOfAWrappedNodeWithSeveralNeighbours() {
    ChainPlanGraph base =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("demo", "Demo"),
            List.of(
                new ChainPlanNode("a", "script", "A", null, null, List.of()),
                new ChainPlanNode("b", "script", "B", null, null, List.of()),
                new ChainPlanNode("x", "script", "X", null, null, List.of()),
                new ChainPlanNode("d", "script", "D", null, null, List.of()),
                new ChainPlanNode("e", "script", "E", null, null, List.of())),
            List.of(
                new ChainPlanEdge("a-x", "a", "x", null),
                new ChainPlanEdge("b-x", "b", "x", null),
                new ChainPlanEdge("x-d", "x", "d", null),
                new ChainPlanEdge("x-e", "x", "e", null)));
    ChainPlanGraph proposed =
        new ChainPlanGraph(
            "1.0",
            base.chain(),
            List.of(
                new ChainPlanNode("a", "script", "A", null, null, List.of()),
                new ChainPlanNode("b", "script", "B", null, null, List.of()),
                new ChainPlanNode("x", "script", "X", "try", null, List.of()),
                new ChainPlanNode("d", "script", "D", null, null, List.of()),
                new ChainPlanNode("e", "script", "E", null, null, List.of()),
                new ChainPlanNode(
                    "wrapper", "try-catch-finally-2", "Error handler", null, null, List.of()),
                new ChainPlanNode("try", "try-2", "Try", "wrapper", null, List.of())),
            List.of());

    ChainPlanGraph merged = ChainEditStructureMerge.merge(base, proposed, intent("x"));

    assertEquals(
        List.of("a wrapper", "b wrapper", "wrapper d", "wrapper e"),
        merged.edges().stream()
            .map(edge -> edge.fromNodeId() + " " + edge.toNodeId())
            .sorted()
            .toList());
  }

  @Test
  void leavesAConnectionWhoseBothEndsMovedIntoTheSameContainer() {
    ChainPlanGraph base =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("demo", "Demo"),
            List.of(
                new ChainPlanNode("x", "script", "X", null, null, List.of()),
                new ChainPlanNode("y", "script", "Y", null, null, List.of())),
            List.of(new ChainPlanEdge("x-y", "x", "y", null)));
    ChainPlanGraph proposed =
        new ChainPlanGraph(
            "1.0",
            base.chain(),
            List.of(
                new ChainPlanNode("x", "script", "X", "try", null, List.of()),
                new ChainPlanNode("y", "script", "Y", "try", null, List.of()),
                new ChainPlanNode(
                    "wrapper", "try-catch-finally-2", "Error handler", null, null, List.of()),
                new ChainPlanNode("try", "try-2", "Try", "wrapper", null, List.of())),
            List.of());

    ChainPlanGraph merged =
        ChainEditStructureMerge.merge(base, proposed, intentFor("x", "y"));

    assertEquals(1, merged.edges().size());
    assertEquals("x", merged.edges().get(0).fromNodeId());
    assertEquals("y", merged.edges().get(0).toNodeId());
  }

  @Test
  void doesNotDuplicateAConnectionTheCaptureAlreadyRelisted() {
    ChainPlanGraph proposed =
        new ChainPlanGraph(
            "1.0",
            baseGraph().chain(),
            List.of(
                new ChainPlanNode("trigger", "http-trigger", "Trigger", null, null, List.of()),
                new ChainPlanNode("script", "script", "Script", "try", null, List.of()),
                new ChainPlanNode(
                    "wrapper", "try-catch-finally-2", "Error handler", null, null, List.of()),
                new ChainPlanNode("try", "try-2", "Try", "wrapper", null, List.of())),
            List.of(new ChainPlanEdge("trigger-wrapper", "trigger", "wrapper", null)));

    ChainPlanGraph merged = ChainEditStructureMerge.merge(baseGraph(), proposed, intent("script"));

    assertEquals(1, merged.edges().size());
    assertEquals("trigger-wrapper", merged.edges().get(0).edgeId());
  }

  @Test
  void rejectsDeletingAnExistingNode() {
    ChainPlanGraph proposed =
        new ChainPlanGraph(
            "1.0",
            baseGraph().chain(),
            List.of(node(baseGraph(), "trigger")),
            List.of());

    assertThrows(
        IllegalArgumentException.class,
        () -> ChainEditStructureMerge.merge(baseGraph(), proposed, intent("script")));
  }

  private static ChainPlanGraph baseGraph() {
    return new ChainPlanGraph(
        "1.0",
        new ChainSection("demo", "Demo"),
        List.of(
            new ChainPlanNode("trigger", "http-trigger", "Trigger", null, null, List.of()),
            new ChainPlanNode(
                "script",
                "script",
                "Script",
                null,
                null,
                List.of(new PlanProperty("script", "return 'kept'")))),
        List.of(new ChainPlanEdge("trigger-script", "trigger", "script", null)));
  }

  private static ChainEditIntent intent(String targetNodeId) {
    return intentFor(targetNodeId);
  }

  private static ChainEditIntent intentFor(String... targetNodeIds) {
    return new ChainEditIntent(
        ChainEditAction.ADD_ELEMENTS,
        List.of(targetNodeIds),
        "wrap the script with error handling",
        null,
        "try-catch-finally-2",
        null,
        ChainEditPlacement.GENERATOR,
        List.of());
  }

  private static ChainPlanNode node(ChainPlanGraph graph, String nodeId) {
    return graph.nodes().stream().filter(node -> nodeId.equals(node.nodeId())).findFirst().orElseThrow();
  }
}
