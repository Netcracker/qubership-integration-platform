package org.qubership.integration.platform.ai.plan.workdocument;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Offline walkthrough. The driver calls only advance and acceptInput. The scripted model is a
 * transport fake and does not call handlers.
 */
class WorkDocumentFillingTest {

  @Test
  void walkthroughReachesPresentationFromTheOriginalSource() throws Exception {
    FillingWorld world = FillingWorld.start("run-walk");
    List<String> trace = new ArrayList<>();
    FillingResult waiting = drive(world, trace, 1);
    assertEquals(FillingResult.Action.WAITING_FOR_INPUT, waiting.action(), trace.toString());
    assertFalse(waiting.questionIds().isEmpty());
    assertTrue(behaviors(world.document()).contains("formatted fallback"), trace.toString());
    assertEquals(List.of("TRIGGER", "SERVICE_CALL", "REPLY"), kinds(world.document()));
    assertFalse(labels(world.document()).contains("Salesforce result"));
    int contextAt = callIndex(world, WorkTaskKind.DESCRIBE_CONTEXT);
    int lastMapping = lastCallIndex(world, WorkTaskKind.MAP_TRANSFER);
    assertTrue(contextAt >= 0 && contextAt < lastMapping, world.model.calls().toString());

    int callsBeforeRestart = world.model.calls().size();
    world.reopen();
    assertEquals(callsBeforeRestart, world.model.calls().size());
    world.filling.acceptInput(
        world.runId, waiting.questionIds().get(0), "process-id", FillingWorld.answerText());
    FillingResult ready = drive(world, trace, callsBeforeRestart + 1);
    assertEquals(FillingResult.Action.READY_FOR_PRESENTATION, ready.action(), trace.toString());
    String rules = behaviors(world.document());
    String constants = constantValues(world.document());
    assertTrue(rules.contains("formatted fallback"), rules);
    assertTrue(rules.contains("constant Not Started"), rules);
    assertTrue(rules.contains("processInstanceId"), rules);
    assertTrue(constants.contains("SALESFORCE_TASK_CREATE_ERROR"), constants);
    assertTrue(world.model.count(WorkTaskKind.LOGICAL_DESIGN) >= 1);
    assertTrue(world.model.count(WorkTaskKind.MAP_TRANSFER) >= 2);
  }

  @Test
  void answeredSourceCallsTheLogicalHandler() throws Exception {
    FillingWorld world = FillingWorld.start("run-logical-recheck");
    List<String> trace = new ArrayList<>();
    FillingResult waiting = world.drive(trace, 40);
    assertEquals(FillingResult.Action.WAITING_FOR_INPUT, waiting.action(), trace.toString());
    int logical = world.model.count(WorkTaskKind.LOGICAL_DESIGN);
    world.filling.acceptInput(
        world.runId, waiting.questionIds().get(0), "process-id", FillingWorld.answerText());
    FillingResult next = world.advance(trace);
    assertEquals(logical + 1, world.model.count(WorkTaskKind.LOGICAL_DESIGN), next.toString());
    assertFalse(next.reasons().contains("revalidated"), next.toString());
  }

  @Test
  void outlineRecheckRunsWhenContractHashesNoLongerMatch() throws Exception {
    FillingWorld world = FillingWorld.start("run-outline-hash");
    List<String> trace = new ArrayList<>();
    while (world.model.count(WorkTaskKind.DEFINE_TRANSFERS) < 3) {
      FillingResult result = world.advance(trace);
      assertEquals(FillingResult.Action.ADVANCED, result.action(), trace.toString());
    }
    String taskKey = "";
    for (LogicalStep step : world.document().flow().steps()) {
      if (!step.data().transfers().isEmpty()) {
        taskKey = "define-transfers:" + step.id();
      }
    }
    assertFalse(taskKey.isBlank(), trace.toString());
    world.catalog.contentGeneration(2);
    reopen(world, taskKey);
    int outlines = world.model.count(WorkTaskKind.DEFINE_TRANSFERS);
    FillingResult next = world.advance(trace);
    assertEquals(outlines + 1, world.model.count(WorkTaskKind.DEFINE_TRANSFERS), next.toString());
    assertFalse(next.reasons().contains("revalidated"), next.toString());
  }

  @Test
  void reopenedMappingWithTheSameFingerprintIsNotAcceptedBeforeItsHandler() throws Exception {
    FillingWorld world = FillingWorld.start("run-fingerprint-reopen");
    List<String> trace = new ArrayList<>();
    while (acceptedMapping(world.document()) == null) {
      FillingResult result = world.advance(trace);
      assertEquals(FillingResult.Action.ADVANCED, result.action(), trace.toString());
    }
    WorkTaskRecord acceptedMapping = acceptedMapping(world.document());
    String taskKey = acceptedMapping.taskKey();
    assertFalse(acceptedMapping.acceptedInputFingerprint().isBlank(), trace.toString());
    assertFalse(taskKey.isBlank(), trace.toString());
    String taskId = "map-transfer-" + taskKey.substring("map-transfer:".length());
    int calls = callsFor(world, taskId);
    reopen(world, taskKey);
    FillingResult next = world.advance(trace);
    WorkTaskState state = null;
    for (WorkTaskRecord task : world.document().progress().tasks()) {
      if (taskKey.equals(task.taskKey())) {
        state = task.state();
      }
    }
    if (state == WorkTaskState.ACCEPTED) {
      boolean handlerRan = calls != callsFor(world, taskId);
      boolean checked = next.reasons().contains("revalidated");
      assertTrue(handlerRan || checked, trace.toString());
    } else {
      assertEquals(WorkTaskState.NEEDS_RECHECK, state, trace.toString());
    }
  }

  @Test
  void repeatedAdvanceReturnsTheSameResult() throws Exception {
    FillingWorld world = FillingWorld.start("run-replay");
    FillingResult first = world.filling.advance(world.runId, "advance-1");
    int calls = world.model.calls().size();
    FillingResult replay = world.filling.advance(world.runId, "advance-1");
    assertEquals(first, replay);
    assertEquals(calls, world.model.calls().size());
    assertEquals(FillingResult.Action.ADVANCED, first.action());
    assertTrue(first.taskId().startsWith("logical-design-"));
  }

  private static WorkTaskRecord acceptedMapping(ChainWorkDocument document) {
    WorkTaskRecord found = null;
    for (WorkTaskRecord task : document.progress().tasks()) {
      if (task.kind() == WorkTaskKind.MAP_TRANSFER
          && task.state() == WorkTaskState.ACCEPTED
          && !task.acceptedInputFingerprint().isBlank()) {
        found = task;
      }
    }
    return found;
  }

  private static void reopen(FillingWorld world, String taskKey) {
    ChainWorkDocument current = world.document();
    List<WorkTaskRecord> tasks = new ArrayList<>();
    for (WorkTaskRecord task : current.progress().tasks()) {
      if (task.taskKey().equals(taskKey)) {
        tasks.add(
            new WorkTaskRecord(
                task.taskKey(),
                task.kind(),
                task.taskId(),
                WorkTaskState.NEEDS_RECHECK,
                task.stage(),
                task.skillId(),
                task.acceptedInputFingerprint(),
                task.producedRecordIds()));
      } else {
        tasks.add(task);
      }
    }
    world.replaceProgress(tasks, current.progress().findings(), "reopen-" + taskKey);
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

  private static FillingResult drive(FillingWorld world, List<String> trace, int from) {
    FillingResult last = null;
    for (int step = from; step < from + 40; step++) {
      last = world.filling.advance(world.runId, "advance-" + step);
      trace.add(step + " " + last.action() + " " + last.taskId() + " " + last.reasons());
      if (last.action() != FillingResult.Action.ADVANCED) {
        return last;
      }
    }
    return last;
  }

  private static List<String> kinds(ChainWorkDocument document) {
    List<String> kinds = new ArrayList<>();
    for (LogicalStep step : document.flow().steps()) {
      kinds.add(step.kind().name());
    }
    return kinds;
  }

  private static List<String> labels(ChainWorkDocument document) {
    List<String> labels = new ArrayList<>();
    for (LogicalStep step : document.flow().steps()) {
      labels.add(step.label());
    }
    return labels;
  }

  static int callIndex(FillingWorld world, WorkTaskKind kind) {
    List<String> calls = world.model.calls();
    for (int index = 0; index < calls.size(); index++) {
      if (calls.get(index).startsWith(kind.name() + " ")) {
        return index;
      }
    }
    return -1;
  }

  static int lastCallIndex(FillingWorld world, WorkTaskKind kind) {
    List<String> calls = world.model.calls();
    int found = -1;
    for (int index = 0; index < calls.size(); index++) {
      if (calls.get(index).startsWith(kind.name() + " ")) {
        found = index;
      }
    }
    return found;
  }

  static List<String> ruleIds(ChainWorkDocument document) {
    List<String> ids = new ArrayList<>();
    for (LogicalStep step : document.flow().steps()) {
      for (DataTransfer transfer : step.data().transfers()) {
        for (MappingRule rule : transfer.rules()) {
          ids.add(rule.id());
        }
      }
    }
    return ids;
  }

  static String behaviors(ChainWorkDocument document) {
    StringBuilder text = new StringBuilder();
    for (LogicalStep step : document.flow().steps()) {
      for (DataTransfer transfer : step.data().transfers()) {
        for (MappingRule rule : transfer.rules()) {
          text.append(rule.behavior()).append('\n');
        }
      }
    }
    return text.toString();
  }

  static String constantValues(ChainWorkDocument document) {
    StringBuilder text = new StringBuilder();
    for (LogicalStep step : document.flow().steps()) {
      for (DataTransfer transfer : step.data().transfers()) {
        for (MappingRule rule : transfer.rules()) {
          for (JsonConstant constant : rule.constants()) {
            text.append(constant.value().asText()).append('\n');
          }
        }
      }
    }
    return text.toString();
  }
}
