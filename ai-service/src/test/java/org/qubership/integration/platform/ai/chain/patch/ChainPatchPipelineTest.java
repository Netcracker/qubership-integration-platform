package org.qubership.integration.platform.ai.chain.patch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.plan.model.ChainPlanEdge;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.ChainSection;
import org.qubership.integration.platform.ai.qipknowledge.patch.EdgePatch;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatch;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchOperation;
import org.qubership.integration.platform.ai.qipknowledge.patch.NodePatch;

/**
 * {@code operation} is the field a small model drops first, and the chain answers it: a body naming
 * an element the chain does not have is an add, one naming an element it has is an update.
 */
class ChainPatchPipelineTest {

  @Test
  void readsABodyTheChainDoesNotHaveAsAnAdd() {
    GraphPatch patch =
        ChainPatchPipeline.toGraphPatch(captureAddingNode("node-new", null), chain());

    assertEquals(GraphPatchOperation.ADD, patch.nodePatches().get(0).operation());
  }

  @Test
  void readsABodyTheChainAlreadyHasAsAnUpdate() {
    GraphPatch patch =
        ChainPatchPipeline.toGraphPatch(captureAddingNode("element-script", null), chain());

    assertEquals(GraphPatchOperation.UPDATE, patch.nodePatches().get(0).operation());
  }

  /**
   * The one guess that destroys something. A patch the reader has to ask for again costs less than
   * an element they have to rebuild, so an entry with only a target id stays refused downstream.
   */
  @Test
  void neverReadsAMissingOperationAsARemoval() {
    GraphPatch patch =
        ChainPatchPipeline.toGraphPatch(captureTargetingNodeOnly("element-script"), chain());

    assertNull(patch.nodePatches().get(0).operation());
  }

  @Test
  void leavesAnOperationTheModelDidStateAlone() {
    GraphPatch patch =
        ChainPatchPipeline.toGraphPatch(
            captureAddingNode("element-script", GraphPatchOperation.ADD), chain());

    assertEquals(GraphPatchOperation.ADD, patch.nodePatches().get(0).operation());
  }

  @Test
  void readsAConnectionTheChainDoesNotHaveAsAnAdd() {
    GraphPatch patch = ChainPatchPipeline.toGraphPatch(captureAddingEdge("edge-new"), chain());

    assertEquals(GraphPatchOperation.ADD, patch.edgePatches().get(0).operation());
  }

  @Test
  void readsAConnectionTheChainAlreadyHasAsAnUpdate() {
    GraphPatch patch =
        ChainPatchPipeline.toGraphPatch(captureAddingEdge("trigger->script"), chain());

    assertEquals(GraphPatchOperation.UPDATE, patch.edgePatches().get(0).operation());
  }

  /** Without a chain to compare against there is nothing to infer from; the capture passes through. */
  @Test
  void inventsNothingWithoutAChainToReadItAgainst() {
    GraphPatch patch = ChainPatchPipeline.toGraphPatch(captureAddingNode("node-new", null));

    assertNull(patch.nodePatches().get(0).operation());
  }

  private static ChainPatchCapture captureAddingNode(String nodeId, GraphPatchOperation operation) {
    return new ChainPatchCapture(
        "p",
        List.of(
            new NodePatch(
                operation,
                new ChainPlanNode(nodeId, "script", "Step", null, null, List.of()),
                null)),
        List.of(),
        List.of(),
        "");
  }

  private static ChainPatchCapture captureTargetingNodeOnly(String nodeId) {
    return new ChainPatchCapture(
        "p", List.of(new NodePatch(null, null, nodeId)), List.of(), List.of(), "");
  }

  private static ChainPatchCapture captureAddingEdge(String edgeId) {
    return new ChainPatchCapture(
        "p",
        List.of(),
        List.of(
            new EdgePatch(
                null, new ChainPlanEdge(edgeId, "element-trigger", "element-script", null), null)),
        List.of(),
        "");
  }

  private static ChainPlanGraph chain() {
    return new ChainPlanGraph(
        "1.0",
        new ChainSection("Order sync", null),
        List.of(
            new ChainPlanNode("element-trigger", "http-trigger", "Receive", null, null, List.of()),
            new ChainPlanNode("element-script", "script", "Normalize", null, null, List.of())),
        List.of(new ChainPlanEdge("trigger->script", "element-trigger", "element-script", null)));
  }
}
