package org.qubership.integration.platform.ai.chain.patch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.plan.model.ChainPlanEdge;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.ChainSection;
import org.qubership.integration.platform.ai.qipknowledge.patch.EdgePatch;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatch;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchOperation;
import org.qubership.integration.platform.ai.qipknowledge.patch.NodePatch;

class ChainPatchRemovalClosureTest {

  @Test
  void takesTheWholeSubtreeWhenAContainerGoes() {
    // The catalog cascades a container delete to its descendants. A patch naming only the
    // container would leave the graph claiming children that no longer exist.
    ChainPatchRemovalClosure.Expansion expansion =
        ChainPatchRemovalClosure.expand(chain(), removeNode("tcf"));

    assertTrue(expansion.coherent());
    assertEquals(
        Set.of("tcf", "try", "catch", "script-in-try"), removedNodeIds(expansion.patch()));
  }

  @Test
  void takesEveryConnectionTouchingWhatItRemoves() {
    ChainPatchRemovalClosure.Expansion expansion =
        ChainPatchRemovalClosure.expand(chain(), removeNode("tcf"));

    // trigger->tcf enters the container; script-in-try->tail leaves a descendant of it.
    assertEquals(Set.of("trigger->tcf", "script-in-try->tail"), removedEdgeIds(expansion.patch()));
  }

  @Test
  void takesOnlyItsOwnEdgesWhenALeafGoes() {
    ChainPatchRemovalClosure.Expansion expansion =
        ChainPatchRemovalClosure.expand(chain(), removeNode("tail"));

    assertEquals(Set.of("tail"), removedNodeIds(expansion.patch()));
    assertEquals(Set.of("script-in-try->tail"), removedEdgeIds(expansion.patch()));
  }

  @Test
  void leavesAPatchWithoutRemovalsAlone() {
    GraphPatch patch =
        new GraphPatch("p", "chain-patch", List.of(), List.of(), List.of(), null, List.of(), "");

    ChainPatchRemovalClosure.Expansion expansion =
        ChainPatchRemovalClosure.expand(chain(), patch);

    assertEquals(patch, expansion.patch());
  }

  @Test
  void refusesAPatchThatBothAddsAndRemovesTheSameNode() {
    // Which one wins would come down to the order the applier happens to walk the list.
    GraphPatch patch =
        new GraphPatch(
            "p",
            "chain-patch",
            List.of(
                new NodePatch(
                    GraphPatchOperation.ADD,
                    new ChainPlanNode("tail", "script", "Tail", null, null, List.of()),
                    null),
                new NodePatch(GraphPatchOperation.REMOVE, null, "tail")),
            List.of(),
            List.of(),
            null,
            List.of(),
            "");

    ChainPatchRemovalClosure.Expansion expansion =
        ChainPatchRemovalClosure.expand(chain(), patch);

    assertTrue(!expansion.coherent());
    assertTrue(expansion.conflicts().get(0).contains("both added and removed"));
  }

  @Test
  void doesNotRepeatARemovalTheModelAlreadyNamed() {
    GraphPatch patch =
        new GraphPatch(
            "p",
            "chain-patch",
            List.of(
                new NodePatch(GraphPatchOperation.REMOVE, null, "tcf"),
                new NodePatch(GraphPatchOperation.REMOVE, null, "try")),
            List.of(new EdgePatch(GraphPatchOperation.REMOVE, null, "trigger->tcf")),
            List.of(),
            null,
            List.of(),
            "");

    ChainPatchRemovalClosure.Expansion expansion =
        ChainPatchRemovalClosure.expand(chain(), patch);

    assertEquals(4, expansion.patch().nodePatches().size());
    assertEquals(2, expansion.patch().edgePatches().size());
  }

  @Test
  void countsWhatTheCascadeAddsBeyondWhatWasAsked() {
    assertEquals(3, ChainPatchRemovalClosure.cascadeCount(chain(), removeNode("tcf")));
    assertEquals(0, ChainPatchRemovalClosure.cascadeCount(chain(), removeNode("tail")));
  }

  private static Set<String> removedNodeIds(GraphPatch patch) {
    return patch.nodePatches().stream()
        .filter(nodePatch -> nodePatch.operation() == GraphPatchOperation.REMOVE)
        .map(NodePatch::targetNodeId)
        .collect(Collectors.toSet());
  }

  private static Set<String> removedEdgeIds(GraphPatch patch) {
    return patch.edgePatches().stream()
        .filter(edgePatch -> edgePatch.operation() == GraphPatchOperation.REMOVE)
        .map(EdgePatch::targetEdgeId)
        .collect(Collectors.toSet());
  }

  private static GraphPatch removeNode(String nodeId) {
    return new GraphPatch(
        "p",
        "chain-patch",
        List.of(new NodePatch(GraphPatchOperation.REMOVE, null, nodeId)),
        List.of(),
        List.of(),
        null,
        List.of(),
        "");
  }

  /** trigger -> tcf[ try[ script-in-try ], catch ] -> tail */
  private static ChainPlanGraph chain() {
    return new ChainPlanGraph(
        "1.0",
        new ChainSection("Order sync", null),
        List.of(
            new ChainPlanNode("trigger", "http-trigger", "Receive", null, null, List.of()),
            new ChainPlanNode("tcf", "try-catch-finally-2", "Handle", null, null, List.of()),
            new ChainPlanNode("try", "try-2", "Try", "tcf", null, List.of()),
            new ChainPlanNode("catch", "catch-2", "Catch", "tcf", null, List.of()),
            new ChainPlanNode("script-in-try", "script", "Work", "try", null, List.of()),
            new ChainPlanNode("tail", "script", "Tail", null, null, List.of())),
        List.of(
            new ChainPlanEdge("trigger->tcf", "trigger", "tcf", null),
            new ChainPlanEdge("script-in-try->tail", "script-in-try", "tail", null)));
  }
}
