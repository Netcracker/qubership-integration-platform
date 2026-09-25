package org.qubership.integration.platform.ai.plan.workdocument;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * N01–N12 through WorkDocumentFilling. The driver calls advance and acceptInput.
 * A scripted INPUT_DEFECT is routing evidence, not production detection.
 */
class WorkFillingFaultInjectionTest {

  @Test
  void n01ExtraReceiveIsRejectedAndTheCorrectiveDesignRuns() throws Exception {
    FillingWorld world = FillingWorld.start("run-n01");
    world.model.inject(
        WorkTaskKind.LOGICAL_DESIGN, 1, FillingWorld.ScriptedWorkModel.synchronousReceive("src-om"));
    List<String> trace = new ArrayList<>();
    FillingResult detected = world.advance(trace);
    assertEquals(FillingResult.Action.ADVANCED, detected.action(), trace.toString());
    assertTrue(detected.reasons().contains("SYNCHRONOUS_RESULT"), detected.toString());
    assertTrue(detected.reasons().contains("LOGICAL_FLOW"), detected.toString());
    assertEquals(1, world.model.count(WorkTaskKind.LOGICAL_DESIGN));
    assertTrue(world.document().flow().steps().isEmpty());

    FillingResult repaired = world.advance(trace);
    assertEquals(FillingResult.Action.ADVANCED, repaired.action(), trace.toString());
    assertEquals(2, world.model.count(WorkTaskKind.LOGICAL_DESIGN));
    assertEquals(List.of("TRIGGER", "SERVICE_CALL", "REPLY"), kinds(world.document()));
    assertFalse(labels(world.document()).contains("Salesforce result"));
    assertNotEquals(detected.documentRevision(), repaired.documentRevision());
  }

  @Test
  void n02MissingCatalogOperationWaitsOnTheBindingOwner() throws Exception {
    FillingWorld world = FillingWorld.start("run-n02");
    world.catalog.miss("createTask");
    List<String> trace = new ArrayList<>();
    FillingResult waiting = world.drive(trace, 20);
    assertEquals(FillingResult.Action.WAITING_FOR_INPUT, waiting.action(), trace.toString());
    boolean asked = false;
    for (WorkQuestion question : world.document().progress().questions()) {
      if (question.question().contains("No runtime catalog operation")
          && question.ownerTaskKey().startsWith("select-operation:")) {
        asked = true;
      }
    }
    assertTrue(asked, trace.toString());
    assertEquals(0, world.repairCharges());
  }

  @Test
  void n03WrongCandidateStaysOnOperationSelection() throws Exception {
    FillingWorld world = FillingWorld.start("run-n03");
    world.model.wrongServiceCandidate = true;
    List<String> trace = new ArrayList<>();
    String task = "";
    for (int step = 0; step < 20 && callsFor(world, task) < 2; step++) {
      FillingResult result = world.advance(trace);
      if (result.reasons().contains("WRONG_OPERATION")) {
        task = result.taskId();
        assertTrue(result.reasons().contains("SERVICES"), result.toString());
        assertEquals(1, world.model.count(WorkTaskKind.LOGICAL_DESIGN));
      }
    }
    assertEquals(2, callsFor(world, task), trace.toString());
    assertEquals(1, world.model.count(WorkTaskKind.LOGICAL_DESIGN));
    boolean bound = false;
    for (LogicalStep step : world.document().flow().steps()) {
      if ("createTask".equals(step.label()) && step.binding() != null) {
        assertEquals("createTask", step.binding().operationId());
        bound = true;
      }
    }
    assertTrue(bound, trace.toString());
  }

  @Test
  void n04ScriptedMissingRetainedRoutesBackToTheOutline() throws Exception {
    // Routing evidence only. The omitted retained value is not detected by production prose checks.
    FillingWorld world = FillingWorld.start("run-n04");
    List<String> trace = new ArrayList<>();
    List<String> requestRules = List.of();
    boolean armed = false;
    int outlines = 0;
    for (int step = 0; step < 30; step++) {
      if (!armed && world.model.count(WorkTaskKind.MAP_TRANSFER) >= 1) {
        requestRules = WorkDocumentFillingTest.ruleIds(world.document());
        world.model.routingDefectCategory = "MISSING_RETAINED";
        outlines = world.model.count(WorkTaskKind.DEFINE_TRANSFERS);
        armed = true;
      }
      FillingResult result = world.advance(trace);
      if (armed && world.model.count(WorkTaskKind.DEFINE_TRANSFERS) > outlines) {
        assertEquals(requestRules, WorkDocumentFillingTest.ruleIds(world.document()), trace.toString());
        assertTrue(result.taskId().startsWith("define-transfers-"), result.toString());
        return;
      }
      if (result.action() == FillingResult.Action.HALTED) {
        break;
      }
    }
    fail(trace.toString());
  }

  @Test
  void n05UnknownContextFieldIsCorrectedByASecondContextCall() throws Exception {
    FillingWorld world = FillingWorld.start("run-n05");
    world.model.badContextField = true;
    List<String> trace = new ArrayList<>();
    String task = "";
    for (int step = 0; step < 30 && callsFor(world, task) < 2; step++) {
      int before = world.model.count(WorkTaskKind.DESCRIBE_CONTEXT);
      FillingResult result = world.advance(trace);
      if (world.model.count(WorkTaskKind.DESCRIBE_CONTEXT) > before && task.isEmpty()) {
        task = result.taskId();
        if (result.reasons().contains("MALFORMED_REFERENCE")) {
          assertEquals(1, world.repairCharges());
        }
      }
    }
    assertEquals(2, callsFor(world, task), trace.toString());
    boolean resolved = false;
    for (LogicalStep step : world.document().flow().steps()) {
      for (RetainedValue value : step.data().retainedValues()) {
        if ("$.processInstanceId".equals(value.source().fieldPath())) {
          resolved = true;
        }
      }
    }
    assertTrue(resolved, trace.toString());
  }

  @Test
  void n06UnknownFieldPathInvokesTheSameMappingAgain() throws Exception {
    FillingWorld world = FillingWorld.start("run-n06");
    world.model.badMappingField = true;
    List<String> trace = new ArrayList<>();
    String task = "";
    String before = "";
    for (int step = 0; step < 30 && callsFor(world, task) < 2; step++) {
      FillingResult result = world.advance(trace);
      if (result.reasons().contains("MALFORMED_REFERENCE") && task.isEmpty()) {
        task = result.taskId();
        before = result.documentRevision();
      }
    }
    assertEquals(2, callsFor(world, task), trace.toString());
    assertNotEquals(before, world.documents.read(world.runId).revision());
    assertFalse(WorkDocumentFillingTest.behaviors(world.document()).contains("unknown field"));
  }

  @Test
  void n07SiblingSourceIsRejectedAndTheAssignedMappingRunsAgain() throws Exception {
    FillingWorld world = FillingWorld.start("run-n07");
    world.model.siblingSource = true;
    List<String> trace = new ArrayList<>();
    String task = "";
    for (int step = 0; step < 30 && callsFor(world, task) < 2; step++) {
      FillingResult result = world.advance(trace);
      if (result.reasons().contains("MALFORMED_REFERENCE") && task.isEmpty()) {
        task = result.taskId();
        assertTrue(result.taskId().startsWith("map-transfer-"), result.toString());
        assertFalse(WorkDocumentFillingTest.behaviors(world.document()).contains("sibling source"));
      }
    }
    assertEquals(2, callsFor(world, task), trace.toString());
    assertFalse(WorkDocumentFillingTest.behaviors(world.document()).contains("sibling source"));
  }

  @Test
  void n08ConstantOutsideTheEnumIsRejectedThenReplaced() throws Exception {
    FillingWorld world = FillingWorld.start("run-n08");
    world.catalog.priorityEnum(true);
    world.model.urgentPriority = true;
    List<String> trace = new ArrayList<>();
    String task = "";
    for (int step = 0; step < 30 && callsFor(world, task) < 2; step++) {
      FillingResult result = world.advance(trace);
      if (result.reasons().contains("INVALID_CONSTANT")) {
        task = result.taskId();
        assertFalse(WorkDocumentFillingTest.constantValues(world.document()).contains("Urgent"));
      }
    }
    assertEquals(2, callsFor(world, task), trace.toString());
    assertTrue(WorkDocumentFillingTest.constantValues(world.document()).contains("Not Started"));
    assertFalse(WorkDocumentFillingTest.constantValues(world.document()).contains("Urgent"));
  }

  @Test
  void n09BindingDefectReturnsToLogicalDesignAndRechecksTheAffectedMapping() throws Exception {
    FillingWorld world = FillingWorld.start("run-n09");
    List<String> trace = new ArrayList<>();
    FillingResult waiting = world.drive(trace, 40);
    assertEquals(FillingResult.Action.WAITING_FOR_INPUT, waiting.action(), trace.toString());
    List<String> failureRules = failureRuleIds(world.document());
    assertFalse(failureRules.isEmpty(), trace.toString());
    int logical = world.model.count(WorkTaskKind.LOGICAL_DESIGN);
    int selects = world.model.count(WorkTaskKind.SELECT_OPERATION);
    world.model.routingDefectCategory = "WRONG_OPERATION";
    world.model.citeRequirement = true;
    world.filling.acceptInput(
        world.runId, waiting.questionIds().get(0), "process-id", FillingWorld.answerText());
    boolean sawSelect = false;
    boolean sawLogical = false;
    for (int step = 0; step < 20; step++) {
      int selectBefore = world.model.count(WorkTaskKind.SELECT_OPERATION);
      int logicalBefore = world.model.count(WorkTaskKind.LOGICAL_DESIGN);
      FillingResult result = world.advance(trace);
      if (world.model.count(WorkTaskKind.SELECT_OPERATION) > selectBefore) {
        sawSelect = true;
        assertFalse(sawLogical, trace.toString());
      }
      if (world.model.count(WorkTaskKind.LOGICAL_DESIGN) > logicalBefore) {
        assertTrue(sawSelect, trace.toString());
        sawLogical = true;
      }
      if (result.action() == FillingResult.Action.HALTED) {
        break;
      }
      if (sawLogical && requirementText(world.document()).contains("corrected operation")) {
        break;
      }
    }
    assertTrue(sawSelect, trace.toString());
    assertTrue(sawLogical, trace.toString());
    assertTrue(world.model.count(WorkTaskKind.SELECT_OPERATION) > selects);
    assertTrue(world.model.count(WorkTaskKind.LOGICAL_DESIGN) > logical);
    assertEquals(failureRules, failureRuleIds(world.document()), trace.toString());
    assertTrue(world.repairCharges() <= 3);
    assertTrue(world.repairCharges() >= 2, trace.toString());
  }

  @Test
  void n10AnswerIsEvidenceAndTheOwnerCanAskAgain() throws Exception {
    FillingWorld world = FillingWorld.start("run-n10");
    List<String> trace = new ArrayList<>();
    FillingResult waiting = world.drive(trace, 40);
    assertEquals(FillingResult.Action.WAITING_FOR_INPUT, waiting.action(), trace.toString());
    List<String> requestRules = requestRuleIds(world.document());
    world.model.contradictAnswer = true;
    world.filling.acceptInput(
        world.runId, waiting.questionIds().get(0), "process-id", FillingWorld.answerText());
    assertEquals(1, answers(world.document()));
    FillingResult again = world.drive(trace, 20);
    assertEquals(FillingResult.Action.WAITING_FOR_INPUT, again.action(), trace.toString());
    assertTrue(again.questionIds().size() >= 1);
    assertEquals(requestRules, requestRuleIds(world.document()));
    assertEquals(1, answers(world.document()));
  }

  @Test
  void n11MalformedCaptureHaltsAtThreeCorrectiveChargesAcrossRestart() throws Exception {
    FillingWorld world = FillingWorld.start("run-n11");
    world.model.alwaysMalformedLogical = true;
    List<String> trace = new ArrayList<>();
    FillingResult last = null;
    for (int step = 0; step < 8; step++) {
      if (world.model.count(WorkTaskKind.LOGICAL_DESIGN) == 2) {
        world.reopen();
      }
      last = world.advance(trace);
      if (last.action() == FillingResult.Action.HALTED) {
        break;
      }
    }
    assertEquals(FillingResult.Action.HALTED, last.action(), trace.toString());
    assertEquals(4, world.model.count(WorkTaskKind.LOGICAL_DESIGN), trace.toString());
    assertTrue(last.reasons().toString().contains("spent"), last.toString());
    assertTrue(last.questionIds().isEmpty(), last.toString());
    int calls = world.model.count(WorkTaskKind.LOGICAL_DESIGN);
    FillingResult blocked = world.advance(trace);
    assertEquals(FillingResult.Action.HALTED, blocked.action(), blocked.toString());
    assertEquals(calls, world.model.count(WorkTaskKind.LOGICAL_DESIGN));
  }

  @Test
  void n12OmittedPriorityBranchStaysAcceptedUntilScriptedRouting() throws Exception {
    // Routing evidence only. Schema validation accepted the mapping. This defect is not production detection.
    FillingWorld world = FillingWorld.start("run-n12");
    List<String> trace = new ArrayList<>();
    while (!WorkDocumentFillingTest.behaviors(world.document()).contains("formatted fallback")) {
      FillingResult result = world.advance(trace);
      assertEquals(FillingResult.Action.ADVANCED, result.action(), trace.toString());
    }
    assertTrue(world.document().progress().findings().isEmpty());
    List<String> requestRules = requestRuleIds(world.document());
    int mappings = world.model.count(WorkTaskKind.MAP_TRANSFER);
    world.model.routingDefectCategory = "SEMANTIC_BRANCH";
    boolean routed = false;
    for (int step = 0; step < 10 && !routed; step++) {
      FillingResult result = world.advance(trace);
      if (result.reasons().contains("SEMANTIC_BRANCH")) {
        routed = true;
        assertTrue(result.reasons().contains("DATA_BEHAVIOR"), result.toString());
      }
    }
    assertTrue(routed, trace.toString());
    assertTrue(world.model.count(WorkTaskKind.MAP_TRANSFER) > mappings);
    assertEquals(requestRules, requestRuleIds(world.document()));
  }

  private static int callsFor(FillingWorld world, String taskId) {
    if (taskId == null || taskId.isBlank()) {
      return 0;
    }
    int count = 0;
    for (String call : world.model.calls()) {
      if (call.endsWith(" " + taskId)) {
        count++;
      }
    }
    return count;
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

  private static List<String> requestRuleIds(ChainWorkDocument document) {
    return ruleIds(document, "formatted fallback");
  }

  private static List<String> failureRuleIds(ChainWorkDocument document) {
    return ruleIds(document, "failure code");
  }

  private static List<String> ruleIds(ChainWorkDocument document, String behavior) {
    List<String> ids = new ArrayList<>();
    for (LogicalStep step : document.flow().steps()) {
      for (DataTransfer transfer : step.data().transfers()) {
        for (MappingRule rule : transfer.rules()) {
          if (rule.behavior().contains(behavior)) {
            ids.add(rule.id());
          }
        }
      }
    }
    return ids;
  }

  private static String requirementText(ChainWorkDocument document) {
    StringBuilder text = new StringBuilder();
    for (WorkRequirement requirement : document.requirements()) {
      text.append(requirement.text()).append('\n');
    }
    return text.toString();
  }

  private static int answers(ChainWorkDocument document) {
    int count = 0;
    for (WorkSource source : document.sources()) {
      if ("answer".equals(source.role())) {
        count++;
      }
    }
    return count;
  }
}
