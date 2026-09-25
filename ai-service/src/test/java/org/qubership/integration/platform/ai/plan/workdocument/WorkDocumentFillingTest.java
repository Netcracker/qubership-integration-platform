package org.qubership.integration.platform.ai.plan.workdocument;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
