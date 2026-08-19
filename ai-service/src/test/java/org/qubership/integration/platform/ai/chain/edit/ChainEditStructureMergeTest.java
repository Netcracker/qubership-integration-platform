package org.qubership.integration.platform.ai.chain.edit;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
  void splicesALinkedSubgraphBetweenTwoElementsAndKeepsThemWhereTheyWere() {
    ChainPlanGraph proposed =
        new ChainPlanGraph(
            "1.0",
            linearBase().chain(),
            List.of(
                node(linearBase(), "a"),
                node(linearBase(), "b"),
                node(linearBase(), "outsider"),
                new ChainPlanNode("script", "script", "Transform", null, null, List.of()),
                new ChainPlanNode("call", "service-call", "Call shipping", null, null, List.of())),
            List.of(
                new ChainPlanEdge("a-b", "a", "script", null),
                new ChainPlanEdge("script-call", "script", "call", null),
                new ChainPlanEdge("call-b", "call", "b", null),
                new ChainPlanEdge("outsider-b", "outsider", "b", null)));

    ChainPlanGraph merged =
        ChainEditStructureMerge.merge(linearBase(), proposed, spliceIntent("a", "b"));

    assertEquals(node(linearBase(), "a"), node(merged, "a"));
    assertEquals(node(linearBase(), "b"), node(merged, "b"));
    assertEquals(node(linearBase(), "outsider"), node(merged, "outsider"));
    assertEquals(
        List.of("a script", "call b", "outsider b", "script call"),
        merged.edges().stream()
            .map(edge -> edge.fromNodeId() + " " + edge.toNodeId())
            .sorted()
            .toList());
  }

  @Test
  void attachesDroppedIncomingAndOutgoingToLinearSubgraphEntryAndExit() {
    ChainPlanGraph base =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("demo", "Demo"),
            List.of(
                new ChainPlanNode("trigger", "http-trigger", "Trigger", null, null, List.of()),
                new ChainPlanNode("script", "script", "Script", null, null, List.of()),
                new ChainPlanNode("sink", "script", "Sink", null, null, List.of())),
            List.of(
                new ChainPlanEdge("trigger-script", "trigger", "script", null),
                new ChainPlanEdge("script-sink", "script", "sink", null)));
    ChainPlanGraph proposed =
        new ChainPlanGraph(
            "1.0",
            base.chain(),
            List.of(
                node(base, "trigger"),
                new ChainPlanNode("script", "script", "Script", "map", null, List.of()),
                node(base, "sink"),
                new ChainPlanNode("map", "script", "Map", null, null, List.of()),
                new ChainPlanNode("enrich", "script", "Enrich", null, null, List.of()),
                new ChainPlanNode("call", "service-call", "Call", null, null, List.of())),
            List.of(
                new ChainPlanEdge("map-enrich", "map", "enrich", null),
                new ChainPlanEdge("enrich-call", "enrich", "call", null)));

    ChainPlanGraph merged = ChainEditStructureMerge.merge(base, proposed, intent("script"));

    assertEquals(
        List.of("call sink", "enrich call", "map enrich", "trigger map"),
        merged.edges().stream()
            .map(edge -> edge.fromNodeId() + " " + edge.toNodeId())
            .sorted()
            .toList());
  }

  @Test
  void keepsEveryNeighbourThroughALinearSubgraph() {
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
                node(base, "a"),
                node(base, "b"),
                new ChainPlanNode("x", "script", "X", "map", null, List.of()),
                node(base, "d"),
                node(base, "e"),
                new ChainPlanNode("map", "script", "Map", null, null, List.of()),
                new ChainPlanNode("enrich", "script", "Enrich", null, null, List.of()),
                new ChainPlanNode("call", "service-call", "Call", null, null, List.of())),
            List.of(
                new ChainPlanEdge("map-enrich", "map", "enrich", null),
                new ChainPlanEdge("enrich-call", "enrich", "call", null)));

    ChainPlanGraph merged = ChainEditStructureMerge.merge(base, proposed, intent("x"));

    assertEquals(
        List.of("a map", "b map", "call d", "call e", "enrich call", "map enrich"),
        merged.edges().stream()
            .map(edge -> edge.fromNodeId() + " " + edge.toNodeId())
            .sorted()
            .toList());
  }

  @Test
  void doesNotDuplicateLinearConnectionsTheCaptureAlreadyListed() {
    ChainPlanGraph base =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("demo", "Demo"),
            List.of(
                new ChainPlanNode("trigger", "http-trigger", "Trigger", null, null, List.of()),
                new ChainPlanNode("script", "script", "Script", null, null, List.of()),
                new ChainPlanNode("sink", "script", "Sink", null, null, List.of())),
            List.of(
                new ChainPlanEdge("trigger-script", "trigger", "script", null),
                new ChainPlanEdge("script-sink", "script", "sink", null)));
    ChainPlanGraph proposed =
        new ChainPlanGraph(
            "1.0",
            base.chain(),
            List.of(
                node(base, "trigger"),
                new ChainPlanNode("script", "script", "Script", "map", null, List.of()),
                node(base, "sink"),
                new ChainPlanNode("map", "script", "Map", null, null, List.of()),
                new ChainPlanNode("call", "service-call", "Call", null, null, List.of())),
            List.of(
                new ChainPlanEdge("trigger-map", "trigger", "map", null),
                new ChainPlanEdge("map-call", "map", "call", null),
                new ChainPlanEdge("call-sink", "call", "sink", null)));

    ChainPlanGraph merged = ChainEditStructureMerge.merge(base, proposed, intent("script"));

    assertEquals(3, merged.edges().size());
    assertEquals(
        List.of("call sink", "map call", "trigger map"),
        merged.edges().stream()
            .map(edge -> edge.fromNodeId() + " " + edge.toNodeId())
            .sorted()
            .toList());
  }

  @Test
  void doesNotPutTheOriginalAddressEdgeBackWhenTheCaptureReplacedItWithASubgraph() {
    ChainPlanGraph proposed =
        new ChainPlanGraph(
            "1.0",
            linearBase().chain(),
            List.of(
                node(linearBase(), "a"),
                node(linearBase(), "b"),
                node(linearBase(), "outsider"),
                new ChainPlanNode("script", "script", "Transform", null, null, List.of()),
                new ChainPlanNode("call", "service-call", "Call shipping", null, null, List.of())),
            List.of(
                new ChainPlanEdge("a-script", "a", "script", null),
                new ChainPlanEdge("script-call", "script", "call", null),
                new ChainPlanEdge("call-b", "call", "b", null),
                new ChainPlanEdge("outsider-b", "outsider", "b", null)));

    ChainPlanGraph merged =
        ChainEditStructureMerge.merge(linearBase(), proposed, spliceIntent("a", "b"));

    assertEquals(
        List.of("a script", "call b", "outsider b", "script call"),
        merged.edges().stream()
            .map(edge -> edge.fromNodeId() + " " + edge.toNodeId())
            .sorted()
            .toList());
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

  @Test
  void replacesTheNamedElementWithASubgraphAndFollowsItsConnections() {
    ChainPlanGraph proposed =
        new ChainPlanGraph(
            "1.0",
            replacementBase().chain(),
            List.of(
                node(replacementBase(), "trigger"),
                node(replacementBase(), "sink"),
                node(replacementBase(), "outsider"),
                new ChainPlanNode("map", "script", "Map", null, null, List.of()),
                new ChainPlanNode("call", "service-call", "Call", null, null, List.of())),
            List.of(new ChainPlanEdge("map-call", "map", "call", null)));

    ChainPlanGraph merged =
        ChainEditStructureMerge.merge(replacementBase(), proposed, replaceIntent("script"));

    assertTrue(merged.nodes().stream().noneMatch(node -> "script".equals(node.nodeId())));
    assertEquals(node(replacementBase(), "trigger"), node(merged, "trigger"));
    assertEquals(node(replacementBase(), "sink"), node(merged, "sink"));
    assertEquals(node(replacementBase(), "outsider"), node(merged, "outsider"));
    assertEquals(
        List.of("call sink", "map call", "outsider map", "trigger map"),
        merged.edges().stream()
            .map(edge -> edge.fromNodeId() + " " + edge.toNodeId())
            .sorted()
            .toList());
  }

  @Test
  void keepsEveryNeighbourWhenReplacingANodeWithSeveralConnections() {
    ChainPlanGraph proposed =
        new ChainPlanGraph(
            "1.0",
            replacementBase().chain(),
            List.of(
                node(replacementBase(), "trigger"),
                node(replacementBase(), "sink"),
                node(replacementBase(), "outsider"),
                new ChainPlanNode("map", "script", "Map", null, null, List.of()),
                new ChainPlanNode("enrich", "script", "Enrich", null, null, List.of()),
                new ChainPlanNode("call", "service-call", "Call", null, null, List.of())),
            List.of(
                new ChainPlanEdge("map-enrich", "map", "enrich", null),
                new ChainPlanEdge("enrich-call", "enrich", "call", null)));

    ChainPlanGraph merged =
        ChainEditStructureMerge.merge(replacementBase(), proposed, replaceIntent("script"));

    assertEquals(
        List.of("call sink", "enrich call", "map enrich", "outsider map", "trigger map"),
        merged.edges().stream()
            .map(edge -> edge.fromNodeId() + " " + edge.toNodeId())
            .sorted()
            .toList());
  }

  @Test
  void stillRemovesTheReplacedElementWhenTheCaptureLeftItInPlace() {
    ChainPlanGraph proposed =
        new ChainPlanGraph(
            "1.0",
            replacementBase().chain(),
            List.of(
                node(replacementBase(), "trigger"),
                node(replacementBase(), "script"),
                node(replacementBase(), "sink"),
                node(replacementBase(), "outsider"),
                new ChainPlanNode("map", "script", "Map", null, null, List.of()),
                new ChainPlanNode("call", "service-call", "Call", null, null, List.of())),
            List.of(
                new ChainPlanEdge("trigger-map", "trigger", "map", null),
                new ChainPlanEdge("map-call", "map", "call", null),
                new ChainPlanEdge("call-sink", "call", "sink", null),
                new ChainPlanEdge("outsider-map", "outsider", "map", null)));

    ChainPlanGraph merged =
        ChainEditStructureMerge.merge(replacementBase(), proposed, replaceIntent("script"));

    assertTrue(merged.nodes().stream().noneMatch(node -> "script".equals(node.nodeId())));
    assertEquals(
        List.of("call sink", "map call", "outsider map", "trigger map"),
        merged.edges().stream()
            .map(edge -> edge.fromNodeId() + " " + edge.toNodeId())
            .sorted()
            .toList());
  }

  @Test
  void doesNotDuplicateReplacementConnectionsTheCaptureAlreadyListed() {
    ChainPlanGraph proposed =
        new ChainPlanGraph(
            "1.0",
            replacementBase().chain(),
            List.of(
                node(replacementBase(), "trigger"),
                node(replacementBase(), "sink"),
                node(replacementBase(), "outsider"),
                new ChainPlanNode("map", "script", "Map", null, null, List.of()),
                new ChainPlanNode("call", "service-call", "Call", null, null, List.of())),
            List.of(
                new ChainPlanEdge("trigger-script", "trigger", "map", null),
                new ChainPlanEdge("map-call", "map", "call", null),
                new ChainPlanEdge("script-sink", "call", "sink", null),
                new ChainPlanEdge("outsider-script", "outsider", "map", null)));

    ChainPlanGraph merged =
        ChainEditStructureMerge.merge(replacementBase(), proposed, replaceIntent("script"));

    assertEquals(4, merged.edges().size());
    assertEquals(
        List.of("call sink", "map call", "outsider map", "trigger map"),
        merged.edges().stream()
            .map(edge -> edge.fromNodeId() + " " + edge.toNodeId())
            .sorted()
            .toList());
  }

  @Test
  void keepsAReplacementInsideTheSameContainer() {
    ChainPlanGraph base =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("demo", "Demo"),
            List.of(
                new ChainPlanNode("try", "try-2", "Try", "wrapper", null, List.of()),
                new ChainPlanNode(
                    "wrapper", "try-catch-finally-2", "Error handler", null, null, List.of()),
                new ChainPlanNode("script", "script", "Script", "try", null, List.of()),
                new ChainPlanNode("sink", "script", "Sink", "try", null, List.of())),
            List.of(new ChainPlanEdge("script-sink", "script", "sink", null)));
    ChainPlanGraph proposed =
        new ChainPlanGraph(
            "1.0",
            base.chain(),
            List.of(
                node(base, "try"),
                node(base, "wrapper"),
                node(base, "sink"),
                new ChainPlanNode("map", "script", "Map", null, null, List.of()),
                new ChainPlanNode("call", "service-call", "Call", null, null, List.of())),
            List.of(new ChainPlanEdge("map-call", "map", "call", null)));

    ChainPlanGraph merged = ChainEditStructureMerge.merge(base, proposed, replaceIntent("script"));

    assertEquals("try", node(merged, "map").parentNodeId());
    assertEquals("try", node(merged, "call").parentNodeId());
    assertEquals("try", node(merged, "sink").parentNodeId());
    assertEquals(
        List.of("call sink", "map call"),
        merged.edges().stream()
            .map(edge -> edge.fromNodeId() + " " + edge.toNodeId())
            .sorted()
            .toList());
  }

  @Test
  void stillRejectsDeletingAnUnrelatedNodeDuringReplacement() {
    ChainPlanGraph proposed =
        new ChainPlanGraph(
            "1.0",
            replacementBase().chain(),
            List.of(
                node(replacementBase(), "trigger"),
                new ChainPlanNode("map", "script", "Map", null, null, List.of())),
            List.of());

    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                ChainEditStructureMerge.merge(
                    replacementBase(), proposed, replaceIntent("script")));
    assertTrue(thrown.getMessage().contains("sink"), thrown.getMessage());
  }

  private static ChainPlanGraph linearBase() {
    return new ChainPlanGraph(
        "1.0",
        new ChainSection("demo", "Demo"),
        List.of(
            new ChainPlanNode("a", "script", "A", null, null, List.of()),
            new ChainPlanNode("b", "script", "B", null, null, List.of()),
            new ChainPlanNode("outsider", "script", "Outsider", null, null, List.of())),
        List.of(
            new ChainPlanEdge("a-b", "a", "b", null),
            new ChainPlanEdge("outsider-b", "outsider", "b", null)));
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

  private static ChainPlanGraph replacementBase() {
    return new ChainPlanGraph(
        "1.0",
        new ChainSection("demo", "Demo"),
        List.of(
            new ChainPlanNode("trigger", "http-trigger", "Trigger", null, null, List.of()),
            new ChainPlanNode("script", "script", "Script", null, null, List.of()),
            new ChainPlanNode("sink", "script", "Sink", null, null, List.of()),
            new ChainPlanNode("outsider", "script", "Outsider", null, null, List.of())),
        List.of(
            new ChainPlanEdge("trigger-script", "trigger", "script", null),
            new ChainPlanEdge("script-sink", "script", "sink", null),
            new ChainPlanEdge("outsider-script", "outsider", "script", null)));
  }

  private static ChainEditIntent replaceIntent(String targetNodeId) {
    return new ChainEditIntent(
        ChainEditAction.ADD_ELEMENTS,
        List.of(targetNodeId),
        "replace the script with a mapper and a service call",
        null,
        "script",
        null,
        List.of(),
        List.of(),
        ChainEditDisposition.REMOVE);
  }

  private static ChainEditIntent spliceIntent(String... addressNodeIds) {
    return new ChainEditIntent(
        ChainEditAction.ADD_ELEMENTS,
        List.of(addressNodeIds),
        "add a script that normalizes the payload, then call the shipping service",
        null,
        "script",
        null,
        List.of(),
        List.of());
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
        List.of(),
        List.of(),
        ChainEditDisposition.NEST);
  }

  private static ChainPlanNode node(ChainPlanGraph graph, String nodeId) {
    return graph.nodes().stream().filter(node -> nodeId.equals(node.nodeId())).findFirst().orElseThrow();
  }
}
