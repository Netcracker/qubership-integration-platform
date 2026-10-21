package org.qubership.integration.platform.ai.plan;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;
import org.jboss.logmanager.MDC;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chat.ChatMdc;
import org.qubership.integration.platform.ai.compiler.capture.CaptureAttemptFeedbackStore;
import org.qubership.integration.platform.ai.plan.model.ChainPlanEdge;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.ChainSection;
import org.qubership.integration.platform.ai.qipknowledge.patch.EdgePatch;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchApplier;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchOperation;
import org.qubership.integration.platform.ai.schema.DeterministicElementSchemaService;

class ChainPlanRepairToolTest {

  private ChainPlanStore planStore;
  private ChainPlanRepairDraftStore draftStore;
  private CaptureAttemptFeedbackStore feedbackStore;
  private ChainPlanRepairTool tool;

  @BeforeEach
  void setUp() {
    DeterministicElementSchemaService schemaService = mock(DeterministicElementSchemaService.class);
    when(schemaService.allowedPatchPropertyKeys(any())).thenReturn(Set.of());
    ChainPlanGraphValidator validator = new ChainPlanGraphValidator(schemaService);
    planStore = new ChainPlanStore();
    draftStore = new ChainPlanRepairDraftStore();
    feedbackStore = new CaptureAttemptFeedbackStore();
    tool =
        new ChainPlanRepairTool(
            draftStore, planStore, validator, new GraphPatchApplier(), feedbackStore);
    MDC.put(ChatMdc.CONVERSATION_ID, "repair-conv");
  }

  @AfterEach
  void tearDown() {
    MDC.remove(ChatMdc.CONVERSATION_ID);
  }

  @Test
  void storesPlanAfterMissingSiblingEdgeRepair() {
    draftStore.put("repair-conv", graphMissingSiblingEdge());

    String result =
        tool.repairChainPlanPatch(
            new ChainPlanRepairPatchCapture(
                "repair-1",
                List.of(
                    new EdgePatch(
                        GraphPatchOperation.ADD,
                        new ChainPlanEdge("e2", "a", "b", null),
                        null)),
                "Connect sibling scripts"));

    assertTrue(result.contains("Plan repaired"));
    assertTrue(planStore.get("repair-conv").isPresent());
    assertTrue(draftStore.get("repair-conv").isEmpty());
  }

  @Test
  void updatesBadEdgeReference() {
    draftStore.put("repair-conv", graphWithBadEdgeRef());

    String result =
        tool.repairChainPlanPatch(
            new ChainPlanRepairPatchCapture(
                "repair-1",
                List.of(
                    new EdgePatch(
                        GraphPatchOperation.UPDATE,
                        new ChainPlanEdge("e1", "trigger", "script", null),
                        "e1")),
                "Point edge at existing node"));

    assertTrue(result.contains("Plan repaired"));
    assertTrue(planStore.get("repair-conv").isPresent());
  }

  @Test
  void rejectsAddPatchForBadEdgeReference() {
    draftStore.put("repair-conv", graphWithBadEdgeRef());

    String result =
        tool.repairChainPlanPatch(
            new ChainPlanRepairPatchCapture(
                "repair-1",
                List.of(
                    new EdgePatch(
                        GraphPatchOperation.ADD,
                        new ChainPlanEdge("e2", "trigger", "script", null),
                        null)),
                "Add another edge"));

    assertTrue(result.contains("ADD edge patches are only allowed"));
    assertTrue(planStore.get("repair-conv").isEmpty());
    assertTrue(feedbackStore.lastPlanFailure("repair-conv").isPresent());
  }

  private static ChainPlanGraph graphMissingSiblingEdge() {
    return new ChainPlanGraph(
        "1.0",
        new ChainSection("missing-sibling", "Missing sibling"),
        List.of(
            new ChainPlanNode("trigger", "http-trigger", "HTTP", null, null, List.of()),
            new ChainPlanNode("a", "script", "A", null, null, List.of()),
            new ChainPlanNode("b", "script", "B", null, null, List.of())),
        List.of(new ChainPlanEdge("e1", "trigger", "a", null)));
  }

  private static ChainPlanGraph graphWithBadEdgeRef() {
    return new ChainPlanGraph(
        "1.0",
        new ChainSection("bad-edge", "Bad edge"),
        List.of(
            new ChainPlanNode("trigger", "http-trigger", "HTTP", null, null, List.of()),
            new ChainPlanNode("script", "script", "Script", null, null, List.of())),
        List.of(new ChainPlanEdge("e1", "trigger", "missing", null)));
  }
}
