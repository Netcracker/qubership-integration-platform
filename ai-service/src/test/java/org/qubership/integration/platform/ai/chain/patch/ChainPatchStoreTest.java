package org.qubership.integration.platform.ai.chain.patch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.plan.model.ChainPlanEdge;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.qipknowledge.patch.EdgePatch;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchOperation;
import org.qubership.integration.platform.ai.qipknowledge.patch.NodePatch;
import org.qubership.integration.platform.ai.qipknowledge.patch.PropertyPatch;

/**
 * The tool asks the model to call it once. When it calls twice anyway -- adding an element in one
 * call and rewiring in the next -- what it said first must survive, or the fragment that happens to
 * arrive last gets applied as if it were the whole change.
 */
class ChainPatchStoreTest {

  private static final String CONVERSATION_ID = "conv-store";

  @Test
  void keepsBothHalvesOfAnEditTheModelSplitAcrossTwoCalls() {
    ChainPatchStore store = new ChainPatchStore();

    store.putCapture(CONVERSATION_ID, addElementAndEdge());
    store.putCapture(CONVERSATION_ID, removeEdge("edge-old"));

    ChainPatchCapture captured = store.takeCapture(CONVERSATION_ID).orElseThrow();
    assertEquals(1, captured.nodePatches().size());
    assertEquals("node-new", captured.nodePatches().get(0).node().nodeId());
    assertEquals(2, captured.edgePatches().size());
    assertEquals(GraphPatchOperation.ADD, captured.edgePatches().get(0).operation());
    assertEquals(GraphPatchOperation.REMOVE, captured.edgePatches().get(1).operation());
    assertEquals("edge-old", captured.edgePatches().get(1).targetEdgeId());
  }

  @Test
  void explainsBothHalvesOnTheCard() {
    ChainPatchStore store = new ChainPatchStore();

    store.putCapture(CONVERSATION_ID, addElementAndEdge());
    store.putCapture(CONVERSATION_ID, removeEdge("edge-old"));

    ChainPatchCapture captured = store.takeCapture(CONVERSATION_ID).orElseThrow();
    assertTrue(captured.rationale().contains("Adds the enrichment step"), captured.rationale());
    assertTrue(captured.rationale().contains("Cuts the connection it replaces"), captured.rationale());
  }

  /** A second property change to the same key is a correction, and the later value is the one meant. */
  @Test
  void lastValueWinsWhenTwoCallsTouchTheSameProperty() {
    ChainPatchStore store = new ChainPatchStore();

    store.putCapture(CONVERSATION_ID, setScript("return 200"));
    store.putCapture(CONVERSATION_ID, setScript("return 201"));

    ChainPatchCapture captured = store.takeCapture(CONVERSATION_ID).orElseThrow();
    assertEquals(1, captured.propertyPatches().size());
    assertEquals("return 201", captured.propertyPatches().get(0).property().value());
  }

  /**
   * The model's other habit: rather than adding to what it said, it says the whole thing again with
   * the part it forgot. Appending that verbatim gave the applier two ADDs of one element and it
   * refused the patch as self-contradictory.
   */
  @Test
  void takesACorrectionAsOneChangeRatherThanTwo() {
    ChainPatchStore store = new ChainPatchStore();

    store.putCapture(CONVERSATION_ID, addElementAndEdge());
    store.putCapture(CONVERSATION_ID, addElementAndEdge());

    ChainPatchCapture captured = store.takeCapture(CONVERSATION_ID).orElseThrow();
    assertEquals(1, captured.nodePatches().size());
    assertEquals(1, captured.edgePatches().size());
  }

  @Test
  void keepsWhatACorrectionAddsAlongsideWhatItRepeats() {
    ChainPatchStore store = new ChainPatchStore();

    store.putCapture(CONVERSATION_ID, addElementAndEdge());
    store.putCapture(CONVERSATION_ID, addElementAndEdgePlusContainer());

    ChainPatchCapture captured = store.takeCapture(CONVERSATION_ID).orElseThrow();
    assertEquals(2, captured.nodePatches().size());
    assertEquals(
        List.of("node-new", "node-branch"),
        captured.nodePatches().stream().map(patch -> patch.node().nodeId()).toList());
  }

  /** Adding and removing one element is a contradiction; folding must not resolve it silently. */
  @Test
  void keepsAnAddAndARemoveOfTheSameElementApart() {
    ChainPatchStore store = new ChainPatchStore();

    store.putCapture(CONVERSATION_ID, addElementAndEdge());
    store.putCapture(CONVERSATION_ID, removeNode("node-new"));

    ChainPatchCapture captured = store.takeCapture(CONVERSATION_ID).orElseThrow();
    assertEquals(2, captured.nodePatches().size());
  }

  @Test
  void startsTheNextTurnEmpty() {
    ChainPatchStore store = new ChainPatchStore();
    store.putCapture(CONVERSATION_ID, setScript("return 200"));

    store.takeCapture(CONVERSATION_ID);

    assertTrue(store.takeCapture(CONVERSATION_ID).isEmpty());
  }

  private static ChainPatchCapture addElementAndEdge() {
    return new ChainPatchCapture(
        "patch-1",
        List.of(
            new NodePatch(
                GraphPatchOperation.ADD,
                new ChainPlanNode(
                    "node-new",
                    "script",
                    "Enrich payload",
                    null,
                    null,
                    List.of(new PlanProperty("script", "return exchange;"))),
                null)),
        List.of(
            new EdgePatch(
                GraphPatchOperation.ADD,
                new ChainPlanEdge("edge-new", "element-script", "node-new", null),
                null)),
        List.of(),
        "Adds the enrichment step.");
  }

  /** The same change again, with the container it forgot the first time. */
  private static ChainPatchCapture addElementAndEdgePlusContainer() {
    ChainPatchCapture repeated = addElementAndEdge();
    return new ChainPatchCapture(
        repeated.patchId(),
        List.of(
            repeated.nodePatches().get(0),
            new NodePatch(
                GraphPatchOperation.ADD,
                new ChainPlanNode("node-branch", "if", "Bulk orders", null, null, List.of()),
                null)),
        repeated.edgePatches(),
        List.of(),
        repeated.rationale());
  }

  private static ChainPatchCapture removeNode(String nodeId) {
    return new ChainPatchCapture(
        null,
        List.of(new NodePatch(GraphPatchOperation.REMOVE, null, nodeId)),
        List.of(),
        List.of(),
        "Drops it after all.");
  }

  private static ChainPatchCapture removeEdge(String edgeId) {
    return new ChainPatchCapture(
        null,
        List.of(),
        List.of(new EdgePatch(GraphPatchOperation.REMOVE, null, edgeId)),
        List.of(),
        "Cuts the connection it replaces.");
  }

  private static ChainPatchCapture setScript(String value) {
    return new ChainPatchCapture(
        "patch-2",
        List.of(),
        List.of(),
        List.of(
            new PropertyPatch(
                GraphPatchOperation.UPDATE, "element-script", new PlanProperty("script", value))),
        "Fixes the script.");
  }
}
