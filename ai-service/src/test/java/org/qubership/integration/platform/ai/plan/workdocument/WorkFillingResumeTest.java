package org.qubership.integration.platform.ai.plan.workdocument;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.productpipeline.store.CommandPayloadConflictException;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunStore.ProviderDeliveryOutcome;

/**
 * Restart, duplicate commands, and a provider redelivery. The driver calls advance and acceptInput.
 */
class WorkFillingResumeTest {

  @Test
  void requestRulesSurviveQuestionAnswerAndRestart() throws Exception {
    FillingWorld world = FillingWorld.start("run-resume-rules");
    List<String> trace = new ArrayList<>();
    FillingResult waiting = world.drive(trace, 40);
    assertEquals(FillingResult.Action.WAITING_FOR_INPUT, waiting.action(), trace.toString());
    List<String> rules = WorkDocumentFillingTest.ruleIds(world.document());
    assertTrue(rules.size() >= 5, trace.toString());

    world.reopen();
    assertEquals(rules, WorkDocumentFillingTest.ruleIds(world.document()));
    world.filling.acceptInput(
        world.runId, waiting.questionIds().get(0), "process-id", FillingWorld.answerText());
    world.reopen();
    FillingResult ready = world.drive(trace, 40);
    assertEquals(FillingResult.Action.READY_FOR_PRESENTATION, ready.action(), trace.toString());
    for (String id : rules) {
      assertTrue(WorkDocumentFillingTest.ruleIds(world.document()).contains(id), id);
    }
    assertTrue(WorkDocumentFillingTest.behaviors(world.document()).contains("processInstanceId"));
  }

  @Test
  void sameInputReplaysAndADifferentPayloadIsRejected() throws Exception {
    FillingWorld world = FillingWorld.start("run-resume-input");
    FillingResult waiting = world.drive(null, 40);
    assertEquals(FillingResult.Action.WAITING_FOR_INPUT, waiting.action());
    String questionId = waiting.questionIds().get(0);
    String text = FillingWorld.answerText();
    WorkCommit first = world.filling.acceptInput(world.runId, questionId, "process-id", text);
    int sources = world.document().sources().size();
    WorkCommit replay = world.filling.acceptInput(world.runId, questionId, "process-id", text);
    assertEquals(first.documentRevision(), replay.documentRevision());
    assertEquals(sources, world.document().sources().size());
    assertThrows(
        CommandPayloadConflictException.class,
        () -> world.filling.acceptInput(world.runId, questionId, "process-id", text + " more"));
  }

  @Test
  void completedAdvanceReplaysWithoutTheNextTask() throws Exception {
    FillingWorld world = FillingWorld.start("run-resume-replay");
    FillingResult first = world.filling.advance(world.runId, "advance-a");
    int calls = world.model.calls().size();
    FillingResult replay = world.filling.advance(world.runId, "advance-a");
    assertEquals(first, replay);
    assertEquals(calls, world.model.calls().size());
    assertEquals(FillingResult.Action.ADVANCED, first.action());
  }

  @Test
  void redeliveryAfterACompletedProviderCallDoesNotChargeTheRepairAgain() throws Exception {
    FillingWorld world = FillingWorld.start("run-resume-redeliver");
    world.model.inject(
        WorkTaskKind.LOGICAL_DESIGN, 1, FillingWorld.ScriptedWorkModel.synchronousReceive("src-om"));
    FillingResult routed = world.advance(null);
    assertTrue(routed.reasons().contains("SYNCHRONOUS_RESULT"), routed.toString());
    assertEquals(1, world.repairCharges());
    assertTrue(world.document().flow().steps().isEmpty());

    String commandId = "redeliver-1";
    world.documents.recordFillingReceipt(
        world.runId,
        "dispatch:" + commandId,
        "marker",
        "filling-dispatch:LOGICAL_DESIGN|" + world.document().documentId() + "|corrective-target",
        "",
        null);
    world.runs.reserveProviderDelivery(world.runId, commandId + ":1");
    world.runs.recordProviderDelivery(world.runId, commandId + ":1", ProviderDeliveryOutcome.COMPLETED);

    FillingResult delivered = world.filling.advance(world.runId, commandId);
    assertEquals(FillingResult.Action.ADVANCED, delivered.action(), delivered.toString());
    assertEquals(2, world.model.count(WorkTaskKind.LOGICAL_DESIGN));
    assertEquals(1, world.repairCharges());
    assertEquals(3, world.document().flow().steps().size());
    var run = world.runs.load(world.runId).orElseThrow();
    assertTrue(world.runs.confirmedProviderDeliveries(run).contains(commandId + ":1"));
    assertTrue(world.runs.confirmedProviderDeliveries(run).contains(commandId + ":2"));
  }
}
