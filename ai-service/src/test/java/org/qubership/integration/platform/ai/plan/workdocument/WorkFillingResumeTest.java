package org.qubership.integration.platform.ai.plan.workdocument;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

  @Test
  void recoveryPublicationRestartsRestoreTheSameTarget() throws Exception {
    FillingWorld world = FillingWorld.start("run-r13-boundaries");
    world.model.omitRetainedDeclaration = true;
    world.model.reportMissingRetained = true;
    List<String> trace = new ArrayList<>();
    int commands = 0;

    FillingResult routed = null;
    String routedCommand = "";
    while (routed == null && commands < 40) {
      String commandId = "r13-" + (++commands);
      FillingResult result = advance(world, trace, commandId);
      if (result.reasons().contains("MISSING_RETAINED")) {
        routed = result;
        routedCommand = commandId;
      } else if (result.action() != FillingResult.Action.ADVANCED) {
        break;
      }
    }
    assertNotNull(routed, trace.toString());
    assertTrue(routed.reasons().contains("DATA_BEHAVIOR"), trace.toString());
    assertTrue(routed.taskId().startsWith("map-transfer-"), trace.toString());
    plantUnrelatedFinding(world);
    RepairAssignment assigned = activeRepair(world.document());
    assertNotNull(assigned, trace.toString());
    assertEquals(RepairAssignment.OWNER, assigned.phase(), trace.toString());
    assertEquals(WorkTaskKind.DEFINE_TRANSFERS, assigned.responsibleKind(), trace.toString());
    assertEquals(
        WorkTaskPlanner.taskKey(WorkTaskKind.MAP_TRANSFER, assigned.recordRef()),
        assigned.consumerTaskKey(),
        trace.toString());
    String consumerTaskId = WorkTaskPlanner.taskId(WorkTaskKind.MAP_TRANSFER, assigned.recordRef());
    assertEquals(consumerTaskId, routed.taskId(), trace.toString());
    String ownerTaskId =
        WorkTaskPlanner.taskId(assigned.responsibleKind(), assigned.responsibleRecordId());
    List<String> transferIds = transferIdList(world.document());
    List<String> ruleIds = WorkDocumentFillingTest.ruleIds(world.document());
    int charges = world.repairCharges();
    assertTrue(charges >= 1, trace.toString());
    assertTrue(findingOpen(world.document(), assigned.findingId()), trace.toString());
    assertTrue(findingOpen(world.document(), "finding-unrelated"), trace.toString());
    int reconstructions = 0;

    RecoveryView afterRouting = RecoveryView.of(world);
    int calls = world.model.calls().size();
    world.reopen();
    reconstructions++;
    FillingResult routedReplay = advance(world, trace, routedCommand);
    assertEquals(routed, routedReplay, trace.toString());
    assertEquals(calls, world.model.calls().size(), trace.toString());
    assertEquals(afterRouting, RecoveryView.of(world), trace.toString());

    String ownerCommand = "r13-" + (++commands);
    int outlines = world.model.count(WorkTaskKind.DEFINE_TRANSFERS);
    int consumerCalls = callsFor(world, consumerTaskId);
    FillingResult owner = advance(world, trace, ownerCommand);
    assertEquals(ownerTaskId, owner.taskId(), trace.toString());
    assertEquals(outlines + 1, world.model.count(WorkTaskKind.DEFINE_TRANSFERS), trace.toString());
    assertEquals(consumerCalls, callsFor(world, consumerTaskId), trace.toString());
    assertEquals(transferIds, transferIdList(world.document()), trace.toString());
    assertEquals(ruleIds, WorkDocumentFillingTest.ruleIds(world.document()), trace.toString());
    assertEquals(charges, world.repairCharges(), trace.toString());
    RepairAssignment verifying = activeRepair(world.document());
    assertNotNull(verifying, trace.toString());
    assertEquals(RepairAssignment.VERIFY, verifying.phase(), trace.toString());
    assertEquals(assigned.findingId(), verifying.findingId(), trace.toString());
    assertEquals(assigned.recordRef(), verifying.recordRef(), trace.toString());
    assertEquals(assigned.consumerTaskKey(), verifying.consumerTaskKey(), trace.toString());
    assertEquals(assigned.responsibleRecordId(), verifying.responsibleRecordId(), trace.toString());
    DataTransfer repaired = transfer(world.document(), assigned.recordRef());
    assertNotNull(repaired, trace.toString());
    assertFalse(repaired.requiredRetainedIds().isEmpty(), repaired.toString());
    List<String> obligations = repaired.requiredRetainedIds();
    assertTrue(findingOpen(world.document(), assigned.findingId()), trace.toString());
    assertTrue(findingOpen(world.document(), "finding-unrelated"), trace.toString());

    RecoveryView afterOwner = RecoveryView.of(world);
    calls = world.model.calls().size();
    world.reopen();
    reconstructions++;
    FillingResult ownerReplay = advance(world, trace, ownerCommand);
    assertEquals(owner, ownerReplay, trace.toString());
    assertEquals(calls, world.model.calls().size(), trace.toString());
    assertEquals(afterOwner, RecoveryView.of(world), trace.toString());

    String settledCommand = ownerCommand;
    FillingResult settled = owner;
    int guard = 0;
    while (!consumerReady(world.document(), assigned.consumerTaskKey()) && guard < 20) {
      guard++;
      List<String> allowed = dependencyTaskIds(world.document(), assigned.consumerTaskKey());
      String commandId = "r13-" + (++commands);
      FillingResult prepared = advance(world, trace, commandId);
      assertEquals(FillingResult.Action.ADVANCED, prepared.action(), trace.toString());
      assertTrue(allowed.contains(prepared.taskId()), prepared + " " + trace);
      assertEquals(consumerCalls, callsFor(world, consumerTaskId), trace.toString());
      assertEquals(transferIds, transferIdList(world.document()), trace.toString());
      assertEquals(obligations, transfer(world.document(), assigned.recordRef()).requiredRetainedIds(), trace.toString());
      assertEquals(charges, world.repairCharges(), trace.toString());
      assertTrue(findingOpen(world.document(), assigned.findingId()), trace.toString());
      assertTrue(findingOpen(world.document(), "finding-unrelated"), trace.toString());
      settledCommand = commandId;
      settled = prepared;
    }
    assertTrue(consumerReady(world.document(), assigned.consumerTaskKey()), trace.toString());
    assertEquals(consumerCalls, callsFor(world, consumerTaskId), trace.toString());

    RecoveryView beforeVerification = RecoveryView.of(world);
    calls = world.model.calls().size();
    world.reopen();
    reconstructions++;
    FillingResult settledReplay = advance(world, trace, settledCommand);
    assertEquals(settled, settledReplay, trace.toString());
    assertEquals(calls, world.model.calls().size(), trace.toString());
    assertEquals(beforeVerification, RecoveryView.of(world), trace.toString());
    assertEquals(consumerCalls, callsFor(world, consumerTaskId), trace.toString());

    String verificationCommand = "r13-" + (++commands);
    FillingResult verification = advance(world, trace, verificationCommand);
    assertEquals(consumerTaskId, verification.taskId(), trace.toString());
    if (!verification.questionIds().isEmpty()) {
      world.filling.acceptInput(
          world.runId, verification.questionIds().get(0), "process-id", FillingWorld.answerText());
    }
    guard = 0;
    while (findingOpen(world.document(), assigned.findingId()) && guard < 25) {
      guard++;
      verificationCommand = "r13-" + (++commands);
      verification = advance(world, trace, verificationCommand);
      assertNotEqualsHalted(verification, trace);
      if (!verification.questionIds().isEmpty()) {
        world.filling.acceptInput(
            world.runId, verification.questionIds().get(0), "process-id", FillingWorld.answerText());
      }
    }
    assertFalse(findingOpen(world.document(), assigned.findingId()), trace.toString());
    assertEquals(consumerTaskId, verification.taskId(), trace.toString());
    assertTrue(findingOpen(world.document(), "finding-unrelated"), trace.toString());
    assertEquals(transferIds, transferIdList(world.document()), trace.toString());
    assertTrue(
        transfer(world.document(), assigned.recordRef()).requiredRetainedIds().containsAll(obligations),
        trace.toString());
    assertTrue(WorkDocumentFillingTest.ruleIds(world.document()).containsAll(ruleIds), trace.toString());
    assertEquals(charges, world.repairCharges(), trace.toString());
    assertTrue(world.document().progress().repairs().isEmpty(), trace.toString());

    RecoveryView afterVerification = RecoveryView.of(world);
    calls = world.model.calls().size();
    world.reopen();
    reconstructions++;
    FillingResult verificationReplay = advance(world, trace, verificationCommand);
    assertEquals(verification, verificationReplay, trace.toString());
    assertEquals(calls, world.model.calls().size(), trace.toString());
    assertEquals(afterVerification, RecoveryView.of(world), trace.toString());
    assertFalse(findingOpen(world.document(), assigned.findingId()), trace.toString());
    assertTrue(findingOpen(world.document(), "finding-unrelated"), trace.toString());
    assertEquals(4, reconstructions, trace.toString());
  }

  private static void assertNotEqualsHalted(FillingResult result, List<String> trace) {
    if (result.action() == FillingResult.Action.HALTED && result.questionIds().isEmpty()) {
      assertEquals(FillingResult.Action.ADVANCED, result.action(), trace.toString());
    }
  }

  private static FillingResult advance(FillingWorld world, List<String> trace, String commandId) {
    FillingResult result = world.filling.advance(world.runId, commandId);
    trace.add(commandId + " " + result.action() + " " + result.taskId() + " " + result.reasons());
    return result;
  }

  private static void plantUnrelatedFinding(FillingWorld world) {
    ChainWorkDocument current = world.document();
    List<WorkFinding> findings = new ArrayList<>();
    findings.add(
        new WorkFinding(
            "finding-unrelated",
            "unrelated-record",
            "UNRELATED_GAP",
            "A second defect remains.",
            List.of(current.sources().get(0).id()),
            "other"));
    findings.addAll(current.progress().findings());
    WorkProgress progress =
        current.progress().replacing(current.progress().tasks(), findings, current.progress().questions());
    world.documents.commitRecoveredDocument(
        world.runId,
        new ChainWorkDocument(
            current.schemaVersion(),
            current.documentId(),
            current.sources(),
            current.requirements(),
            current.flow(),
            progress),
        "plant-unrelated",
        "plant-unrelated",
        "task-progress",
        "LOGICAL_FLOW",
        null);
  }

  private static boolean consumerReady(ChainWorkDocument document, String consumerKey) {
    RepairAssignment repair = activeRepair(document);
    if (repair == null || !RepairAssignment.VERIFY.equals(repair.phase())) {
      return false;
    }
    if (!consumerKey.equals(repair.consumerTaskKey())) {
      return false;
    }
    DataTransfer transfer = transfer(document, repair.recordRef());
    if (transfer == null || transfer.requiredRetainedIds().isEmpty()) {
      return false;
    }
    for (String retainedId : transfer.requiredRetainedIds()) {
      RetainedValue value = retained(document, retainedId);
      if (value == null || value.producerStepId().isBlank()) {
        return false;
      }
    }
    for (WorkQuestion question : document.progress().questions()) {
      if (question.resolution() == QuestionResolution.OPEN && consumerKey.equals(question.ownerTaskKey())) {
        return false;
      }
    }
    WorkTaskPlanner.Plan plan = new WorkTaskPlanner().plan(document);
    WorkTaskPlanner.Task consumer = null;
    for (WorkTaskPlanner.Task task : plan.tasks()) {
      if (consumerKey.equals(task.taskKey())) {
        consumer = task;
      }
    }
    if (consumer == null) {
      return false;
    }
    for (WorkTaskPlanner.Task task : plan.tasks()) {
      if (consumer.dependencyKeys().contains(task.taskKey()) && task.state() != WorkTaskState.ACCEPTED) {
        return false;
      }
    }
    return true;
  }

  private static List<String> dependencyTaskIds(ChainWorkDocument document, String consumerKey) {
    List<String> allowed = new ArrayList<>();
    WorkTaskPlanner.Plan plan = new WorkTaskPlanner().plan(document);
    for (WorkTaskPlanner.Task task : plan.tasks()) {
      if (!consumerKey.equals(task.taskKey())) {
        continue;
      }
      for (String dependency : task.dependencyKeys()) {
        allowed.add(taskIdOf(dependency));
      }
    }
    return allowed;
  }

  private static String taskIdOf(String taskKey) {
    int colon = taskKey.indexOf(':');
    if (colon < 0) {
      return taskKey;
    }
    return taskKey.substring(0, colon) + "-" + taskKey.substring(colon + 1);
  }

  private static RepairAssignment activeRepair(ChainWorkDocument document) {
    List<RepairAssignment> repairs = document.progress().repairs();
    if (repairs.isEmpty()) {
      return null;
    }
    return repairs.get(repairs.size() - 1);
  }

  private static boolean findingOpen(ChainWorkDocument document, String findingId) {
    for (WorkFinding finding : document.progress().findings()) {
      if (findingId.equals(finding.id())) {
        return true;
      }
    }
    return false;
  }

  private static List<String> transferIdList(ChainWorkDocument document) {
    List<String> ids = new ArrayList<>();
    for (LogicalStep step : document.flow().steps()) {
      for (DataTransfer transfer : step.data().transfers()) {
        ids.add(transfer.id());
      }
    }
    return ids;
  }

  private static DataTransfer transfer(ChainWorkDocument document, String transferId) {
    for (LogicalStep step : document.flow().steps()) {
      for (DataTransfer candidate : step.data().transfers()) {
        if (transferId.equals(candidate.id())) {
          return candidate;
        }
      }
    }
    return null;
  }

  private static RetainedValue retained(ChainWorkDocument document, String retainedId) {
    for (LogicalStep step : document.flow().steps()) {
      for (RetainedValue value : step.data().retainedValues()) {
        if (retainedId.equals(value.id())) {
          return value;
        }
      }
    }
    return null;
  }

  private static int callsFor(FillingWorld world, String taskId) {
    int count = 0;
    for (String call : world.model.calls()) {
      if (call.endsWith(" " + taskId)) {
        count++;
      }
    }
    return count;
  }

  private record RecoveryView(
      List<String> transferIds,
      List<String> ruleIds,
      List<String> obligations,
      List<String> retainedIds,
      List<String> findingIds,
      List<String> taskStates,
      List<RepairAssignment> repairs,
      int charges) {

    private static RecoveryView of(FillingWorld world) {
      ChainWorkDocument document = world.document();
      List<String> obligations = List.of();
      List<RepairAssignment> repairs = document.progress().repairs();
      if (!repairs.isEmpty()) {
        DataTransfer transfer = transfer(document, repairs.get(repairs.size() - 1).recordRef());
        if (transfer != null) {
          obligations = transfer.requiredRetainedIds();
        }
      }
      List<String> retainedIds = new ArrayList<>();
      for (LogicalStep step : document.flow().steps()) {
        for (RetainedValue value : step.data().retainedValues()) {
          retainedIds.add(value.id());
        }
      }
      List<String> findingIds = new ArrayList<>();
      for (WorkFinding finding : document.progress().findings()) {
        findingIds.add(finding.id());
      }
      List<String> taskStates = new ArrayList<>();
      for (WorkTaskRecord task : document.progress().tasks()) {
        taskStates.add(task.taskKey() + " " + task.state());
      }
      return new RecoveryView(
          transferIdList(document),
          WorkDocumentFillingTest.ruleIds(document),
          obligations,
          retainedIds,
          findingIds,
          taskStates,
          repairs,
          world.repairCharges());
    }
  }
}
