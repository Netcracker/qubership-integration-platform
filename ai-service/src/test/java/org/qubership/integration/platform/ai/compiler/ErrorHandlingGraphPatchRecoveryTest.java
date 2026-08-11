package org.qubership.integration.platform.ai.compiler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.ChainSection;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchOperation;
import org.qubership.integration.platform.ai.qipknowledge.patch.NodePatch;

class ErrorHandlingGraphPatchRecoveryTest {

  @Test
  void recoversPropertyEnrichWhenAddConflictsWithExistingIncompleteCatch() {
    ChainPlanGraph base = ehGraphWithoutCatchProps();
    GraphPatchCapture attempted = wrapAddAttempt();

    Optional<GraphPatchCapture> recovered =
        ErrorHandlingGraphPatchRecovery.recover(
            ErrorHandlingGraphPatchRecovery.CAPABILITY_ID,
            base,
            attempted,
            "Patch apply failed: Patch blocked by 3 conflict(s): Node 'eh-wrap' already exists");

    assertTrue(recovered.isPresent());
    GraphPatchCapture patch = recovered.get();
    assertEquals("eh-recover-catch-2-mandatory-properties", patch.patchId());
    assertEquals(2, patch.propertyPatches().size());
    assertEquals("catch-shell", patch.propertyPatches().get(0).targetNodeId());
    assertEquals("exception", patch.propertyPatches().get(0).key());
    assertEquals("priority", patch.propertyPatches().get(1).key());
  }

  @Test
  void recoversNotApplicableWhenTopologyComplete() {
    ChainPlanGraph base =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("GeographicSite.Proxy.GetById", null),
            List.of(
                node("eh-wrap", "try-catch-finally-2", null),
                node("try-shell", "try-2", "eh-wrap"),
                catchWithException("catch-shell", "eh-wrap")),
            List.of());
    GraphPatchCapture attempted = wrapAddAttempt();

    Optional<GraphPatchCapture> recovered =
        ErrorHandlingGraphPatchRecovery.recover(
            ErrorHandlingGraphPatchRecovery.CAPABILITY_ID,
            base,
            attempted,
            "Node 'eh-wrap' already exists");

    assertTrue(recovered.isPresent());
    assertTrue(recovered.get().isNotApplicable());
    assertTrue(recovered.get().propertyPatches().isEmpty());
  }

  @Test
  void recoversPropertyEnrichOnContainmentFailureWhenEhTopologyAlreadyPresent() {
    ChainPlanGraph base = ehGraphWithoutCatchProps();
    GraphPatchCapture attempted =
        new GraphPatchCapture(
            "cip-error-handling-generator-add-try-catch-wrapper-atomic",
            ErrorHandlingGraphPatchRecovery.CAPABILITY_ID,
            List.of(
                new NodePatch(
                    GraphPatchOperation.ADD,
                    node("wrapper-new", "try-catch-finally-2", null),
                    null),
                new NodePatch(GraphPatchOperation.ADD, node("try-new", "try-2", "wrapper-new"), null),
                new NodePatch(
                    GraphPatchOperation.ADD, node("catch-new", "catch-2", "wrapper-new"), null)),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            "Greenfield EH wrap when captureChainPlan has no EH nodes",
            false);

    Optional<GraphPatchCapture> recovered =
        ErrorHandlingGraphPatchRecovery.recover(
            ErrorHandlingGraphPatchRecovery.CAPABILITY_ID,
            base,
            attempted,
            "Patch produced invalid graph: node 'service-call' (service-call) must have"
                + " parentNodeId='try-2' for catalog containment; edges alone do not place"
                + " elements inside try-2");

    assertTrue(recovered.isPresent());
    assertEquals("eh-recover-catch-2-mandatory-properties", recovered.get().patchId());
    assertEquals(2, recovered.get().propertyPatches().size());
  }

  @Test
  void ignoresContainmentFailureWhenBaseHasNoEhTopology() {
    ChainPlanGraph base =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("plain", null),
            List.of(
                node("trigger", "http-trigger", null),
                node("call-1", "service-call", null)),
            List.of());

    Optional<GraphPatchCapture> recovered =
        ErrorHandlingGraphPatchRecovery.recover(
            ErrorHandlingGraphPatchRecovery.CAPABILITY_ID,
            base,
            wrapAddAttempt(),
            "Patch produced invalid graph: node 'service-call' must have parentNodeId='try-2'");

    assertTrue(recovered.isEmpty());
  }

  private static GraphPatchCapture wrapAddAttempt() {
    return new GraphPatchCapture(
        "add-try-catch-wrapper-atomic",
        ErrorHandlingGraphPatchRecovery.CAPABILITY_ID,
        List.of(
            new NodePatch(
                GraphPatchOperation.ADD, node("eh-wrap", "try-catch-finally-2", null), null),
            new NodePatch(GraphPatchOperation.ADD, node("try-shell", "try-2", "eh-wrap"), null),
            new NodePatch(GraphPatchOperation.ADD, node("catch-shell", "catch-2", "eh-wrap"), null)),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        "Greenfield EH wrap",
        false);
  }

  private static ChainPlanGraph ehGraphWithoutCatchProps() {
    return new ChainPlanGraph(
        "1.0",
        new ChainSection("GeographicSite.Proxy.GetById", null),
        List.of(
            node("trigger", "http-trigger", null),
            node("eh-wrap", "try-catch-finally-2", null),
            node("try-shell", "try-2", "eh-wrap"),
            node("catch-shell", "catch-2", "eh-wrap"),
            node("call-1", "service-call", "try-shell")),
        List.of());
  }

  private static ChainPlanNode node(String id, String type, String parent) {
    return new ChainPlanNode(id, type, type, parent, null, List.of());
  }

  private static ChainPlanNode catchWithException(String id, String parent) {
    return new ChainPlanNode(
        id,
        "catch-2",
        "Catch",
        parent,
        null,
        List.of(
            new org.qubership.integration.platform.ai.plan.model.PlanProperty(
                "exception", "java.lang.Exception"),
            new org.qubership.integration.platform.ai.plan.model.PlanProperty("priority", "0")));
  }
}
