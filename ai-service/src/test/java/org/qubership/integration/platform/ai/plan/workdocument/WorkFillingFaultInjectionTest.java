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
  void n03KnownOperationWithAnIncompatibleContractReturnsToSelection() throws Exception {
    FillingWorld world = FillingWorld.start("run-n03");
    List<String> trace = new ArrayList<>();
    List<String> notes = new ArrayList<>();
    while (!WorkDocumentFillingTest.behaviors(world.document()).contains("formatted fallback")) {
      FillingResult result = world.advance(trace);
      if (WorkDocumentFillingTest.behaviors(world.document()).contains("formatted fallback")) {
        break;
      }
      assertEquals(FillingResult.Action.ADVANCED, result.action(), trace.toString());
    }
    List<String> siblingRules = requestRuleIds(world.document());
    String siblingFingerprint = taskFingerprint(world.document(), transferTaskKey(world.document(), "formatted fallback"));
    assertFalse(siblingRules.isEmpty(), trace.toString());
    assertFalse(siblingFingerprint.isBlank(), trace.toString());
    String replyId = stepId(world.document(), StepKind.REPLY);
    world.catalog.incompatible("onTaskResult");
    String failure = failureTransferId(world.document());
    assertFalse(failure.isBlank(), trace.toString());
    reopen(world, "map-transfer:" + failure);
    boolean detected = false;
    int selectsAtDetection = world.model.count(WorkTaskKind.SELECT_OPERATION);
    for (int step = 0; step < 20 && !detected; step++) {
      FillingResult result = world.advance(trace);
      notes.add(revisionNote(world, result, siblingRules));
      if (result.reasons().contains("INCOMPATIBLE_CONTRACT")) {
        detected = true;
        assertTrue(
            result.taskId().startsWith("define-transfers-") || result.taskId().startsWith("map-transfer-"),
            result.toString());
        assertTrue(result.reasons().contains("SERVICES"), result.toString());
        assertEquals(selectsAtDetection, world.model.count(WorkTaskKind.SELECT_OPERATION));
        assertEquals(siblingRules, requestRuleIds(world.document()), trace.toString());
        assertEquals(siblingFingerprint, taskFingerprint(world.document(), transferTaskKey(world.document(), "formatted fallback")));
        assertEquals("onTaskResult", bindingOperation(world.document(), replyId));
      }
    }
    assertTrue(detected, trace.toString());
    world.catalog.compatible("onTaskResult");
    int selects = world.model.count(WorkTaskKind.SELECT_OPERATION);
    int replyOutlines = callsFor(world, "define-transfers-" + replyId);
    int replyMappings = mappingsForStep(world, replyId);
    FillingResult repaired = world.advance(trace);
    notes.add(revisionNote(world, repaired, siblingRules));
    assertEquals(selects + 1, world.model.count(WorkTaskKind.SELECT_OPERATION), trace.toString());
    assertEquals("onTaskResult", bindingOperation(world.document(), replyId), trace.toString());
    assertEquals(siblingRules, requestRuleIds(world.document()));
    assertEquals(siblingFingerprint, taskFingerprint(world.document(), transferTaskKey(world.document(), "formatted fallback")));
    assertEquals(WorkTaskState.ACCEPTED, taskState(world.document(), "select-operation:" + replyId), trace.toString());
    String siblingKey = transferTaskKey(world.document(), "formatted fallback");
    boolean replyRechecked = false;
    for (int step = 0; step < 16; step++) {
      int outlinesNow = callsFor(world, "define-transfers-" + replyId);
      int mappingsNow = mappingsForStep(world, replyId);
      FillingResult result = world.advance(trace);
      notes.add(revisionNote(world, result, siblingRules));
      assertEquals(siblingRules, requestRuleIds(world.document()), trace.toString());
      boolean outlineCall = callsFor(world, "define-transfers-" + replyId) > outlinesNow;
      boolean mappingCall = mappingsForStep(world, replyId) > mappingsNow;
      boolean cheapOutline =
          result.reasons().contains("revalidated") && result.taskId().equals("define-transfers-" + replyId);
      boolean cheapMapping =
          result.reasons().contains("revalidated") && result.taskId().equals("map-transfer-" + failure);
      if (outlineCall || mappingCall || cheapOutline || cheapMapping) {
        assertEquals(
            WorkTaskState.ACCEPTED, taskState(world.document(), "select-operation:" + replyId), result.toString());
        assertEquals(siblingFingerprint, taskFingerprint(world.document(), siblingKey), result.toString());
        if (result.taskId().equals("define-transfers-" + replyId) || result.taskId().equals("map-transfer-" + failure)) {
          replyRechecked = true;
        }
      }
      if (result.action() != FillingResult.Action.ADVANCED) {
        break;
      }
    }
    assertTrue(replyRechecked, trace.toString());
    assertTrue(notes.size() >= 2, notes.toString());
    assertTrue(world.repairCharges() >= 1, trace.toString());
  }

  @Test
  void n04ConsumerFindsTheMissingRetainedDeclarationAfterTheOutline() throws Exception {
    FillingWorld world = FillingWorld.start("run-n04");
    world.model.omitRetainedDeclaration = true;
    world.model.reportMissingRetained = true;
    List<String> trace = new ArrayList<>();
    List<String> notes = new ArrayList<>();
    String failureTransfer = "";
    List<String> siblingRules = List.of();
    String siblingFingerprint = "";
    String consumer = "";
    int outlinesAtDetection = 0;
    int contextAtDetection = 0;
    boolean detected = false;
    for (int step = 0; step < 30 && !detected; step++) {
      FillingResult result = world.advance(trace);
      notes.add(revisionNote(world, result, siblingRules));
      if (result.reasons().contains("MISSING_RETAINED")) {
        detected = true;
        consumer = result.taskId();
        assertTrue(consumer.startsWith("map-transfer-"), result.toString());
        assertTrue(result.reasons().contains("DATA_BEHAVIOR"), result.toString());
        assertTrue(world.model.count(WorkTaskKind.DEFINE_TRANSFERS) >= 3, trace.toString());
        failureTransfer = failureTransferId(world.document());
        siblingRules = requestRuleIds(world.document());
        if (!siblingRules.isEmpty()) {
          siblingFingerprint = taskFingerprint(world.document(), transferTaskKey(world.document(), "formatted fallback"));
        }
        outlinesAtDetection = world.model.count(WorkTaskKind.DEFINE_TRANSFERS);
        contextAtDetection = world.model.count(WorkTaskKind.DESCRIBE_CONTEXT);
        assertFalse(failureTransfer.isBlank(), trace.toString());
        assertEquals(0, contextAtDetection, trace.toString());
      }
    }
    assertTrue(detected, trace.toString());
    boolean outlineRepaired = false;
    boolean contextRan = false;
    boolean mappingRan = false;
    String repairedOutline = "";
    int consumerCalls = callsFor(world, consumer);
    for (int step = 0; step < 20 && !mappingRan; step++) {
      int outlines = world.model.count(WorkTaskKind.DEFINE_TRANSFERS);
      int context = world.model.count(WorkTaskKind.DESCRIBE_CONTEXT);
      int mappings = world.model.count(WorkTaskKind.MAP_TRANSFER);
      FillingResult result = world.advance(trace);
      notes.add(revisionNote(world, result, siblingRules));
      if (!siblingRules.isEmpty()) {
        assertEquals(siblingRules, requestRuleIds(world.document()), trace.toString());
        assertEquals(siblingFingerprint, taskFingerprint(world.document(), transferTaskKey(world.document(), "formatted fallback")));
      }
      assertEquals(failureTransfer, failureTransferId(world.document()), trace.toString());
      if (world.model.count(WorkTaskKind.DEFINE_TRANSFERS) > outlines) {
        outlineRepaired = true;
        repairedOutline = "define-transfers:" + result.taskId().substring("define-transfers-".length());
        assertFalse(contextRan, trace.toString());
        assertEquals(consumerCalls, callsFor(world, consumer), trace.toString());
        assertEquals(WorkTaskState.ACCEPTED, taskState(world.document(), repairedOutline), trace.toString());
      }
      if (world.model.count(WorkTaskKind.DESCRIBE_CONTEXT) > context) {
        assertTrue(outlineRepaired, trace.toString());
        assertEquals(WorkTaskState.ACCEPTED, taskState(world.document(), repairedOutline), trace.toString());
        contextRan = true;
      }
      if (contextRan && world.model.count(WorkTaskKind.MAP_TRANSFER) > mappings) {
        mappingRan = true;
      }
      if (callsFor(world, consumer) > consumerCalls && !outlineRepaired) {
        fail(trace.toString());
      }
      if (result.action() == FillingResult.Action.HALTED) {
        break;
      }
    }
    assertTrue(outlineRepaired, trace.toString());
    assertTrue(contextRan, trace.toString());
    assertTrue(mappingRan, trace.toString());
    assertTrue(world.model.count(WorkTaskKind.DEFINE_TRANSFERS) > outlinesAtDetection);
    assertTrue(world.model.count(WorkTaskKind.DESCRIBE_CONTEXT) > contextAtDetection);
    assertTrue(notes.size() >= 3, notes.toString());
  }

  @Test
  void n05UnknownFieldAndAFieldFromTheWrongProducerAreCorrected() throws Exception {
    FillingWorld world = FillingWorld.start("run-n05");
    world.model.contextFieldPaths.add("$.notAField");
    world.model.contextFieldPaths.add("$.status");
    List<String> trace = new ArrayList<>();
    List<String> notes = new ArrayList<>();
    String task = "";
    String failureTransfer = "";
    String failureFingerprint = "";
    List<String> contradictions = new ArrayList<>();
    for (int step = 0; step < 40 && callsFor(world, task) < 3; step++) {
      if (failureTransfer.isBlank()) {
        failureTransfer = failureTransferId(world.document());
        if (!failureTransfer.isBlank()) {
          failureFingerprint = taskFingerprint(world.document(), "map-transfer:" + failureTransfer);
        }
      }
      int before = world.model.count(WorkTaskKind.DESCRIBE_CONTEXT);
      FillingResult result = world.advance(trace);
      notes.add(revisionNote(world, result, List.of(failureTransfer)));
      if (world.model.count(WorkTaskKind.DESCRIBE_CONTEXT) > before) {
        if (task.isEmpty()) {
          task = result.taskId();
        }
        assertEquals(task, result.taskId(), trace.toString());
        if (result.reasons().contains("MALFORMED_REFERENCE")) {
          for (WorkFinding finding : world.document().progress().findings()) {
            if ("MALFORMED_REFERENCE".equals(finding.issueCategory())
                && !contradictions.contains(finding.contradiction())) {
              contradictions.add(finding.contradiction());
            }
          }
        }
      }
    }
    assertEquals(3, callsFor(world, task), trace.toString());
    assertTrue(contradictions.stream().anyMatch(text -> text.contains("$.notAField")), contradictions.toString());
    assertTrue(contradictions.stream().anyMatch(text -> text.contains("$.status")), contradictions.toString());
    assertFalse(failureTransfer.isBlank(), trace.toString());
    assertEquals(failureTransfer, failureTransferId(world.document()), trace.toString());
    if (!failureFingerprint.isBlank()) {
      assertEquals(failureFingerprint, taskFingerprint(world.document(), "map-transfer:" + failureTransfer));
    }
    boolean resolved = false;
    for (LogicalStep step : world.document().flow().steps()) {
      for (RetainedValue value : step.data().retainedValues()) {
        if ("$.processInstanceId".equals(value.source().fieldPath())) {
          resolved = true;
        }
      }
    }
    assertTrue(resolved, trace.toString());
    assertTrue(notes.size() >= 2, notes.toString());
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
  void n09BindingDiscoversTheWrongLogicalInputAndRechecksDependents() throws Exception {
    FillingWorld world = FillingWorld.start("run-n09");
    List<String> trace = new ArrayList<>();
    List<String> notes = new ArrayList<>();
    List<String> failureRules = List.of();
    String failureKey = "";
    String siblingBehavior = "";
    while (failureRules.isEmpty()) {
      FillingResult result = world.advance(trace);
      assertEquals(FillingResult.Action.ADVANCED, result.action(), trace.toString());
      failureRules = failureRuleIds(world.document());
      failureKey = transferTaskKey(world.document(), "failure code");
      siblingBehavior = "failure code";
      if (failureRules.isEmpty()) {
        failureRules = requestRuleIds(world.document());
        failureKey = transferTaskKey(world.document(), "formatted fallback");
        siblingBehavior = "formatted fallback";
      }
    }
    String failureFingerprint = taskFingerprint(world.document(), failureKey);
    String replyId = stepId(world.document(), StepKind.REPLY);
    assertEquals("onTaskResult", bindingOperation(world.document(), replyId));
    reopen(world, "select-operation:" + replyId);
    world.model.overrideStepLabel = "onTaskResult";
    world.model.overrideCandidateId = "createTask";
    int logicalBefore = world.model.count(WorkTaskKind.LOGICAL_DESIGN);
    int outlinesBefore = world.model.count(WorkTaskKind.DEFINE_TRANSFERS);
    int mappingsBefore = world.model.count(WorkTaskKind.MAP_TRANSFER);
    boolean sawSelect = false;
    boolean logicalAccepted = false;
    boolean sawOutline = false;
    boolean sawContext = false;
    boolean sawMapping = false;
    for (int step = 0; step < 25 && !sawMapping; step++) {
      int selects = world.model.count(WorkTaskKind.SELECT_OPERATION);
      int logical = world.model.count(WorkTaskKind.LOGICAL_DESIGN);
      int outlines = world.model.count(WorkTaskKind.DEFINE_TRANSFERS);
      int context = world.model.count(WorkTaskKind.DESCRIBE_CONTEXT);
      int mappings = world.model.count(WorkTaskKind.MAP_TRANSFER);
      FillingResult result = world.advance(trace);
      notes.add(revisionNote(world, result, failureRules));
      if (world.model.count(WorkTaskKind.SELECT_OPERATION) > selects) {
        sawSelect = true;
      }
      if (result.reasons().contains("WRONG_OPERATION")) {
        assertTrue(sawSelect, trace.toString());
        assertFalse(logicalAccepted, trace.toString());
        assertTrue(result.reasons().contains("LOGICAL_FLOW"), result.toString());
        world.model.appendCorrectedOperation = true;
      }
      if (world.model.count(WorkTaskKind.LOGICAL_DESIGN) > logical) {
        assertTrue(sawSelect, trace.toString());
      }
      if (WorkTaskState.ACCEPTED == taskState(world.document(), "logical-design:" + world.document().documentId())
          && world.model.count(WorkTaskKind.LOGICAL_DESIGN) > logicalBefore) {
        logicalAccepted = true;
      }
      if (world.model.count(WorkTaskKind.DEFINE_TRANSFERS) > outlines
          || (result.reasons().contains("revalidated") && result.taskId().startsWith("define-transfers-"))) {
        assertTrue(logicalAccepted, trace.toString());
        sawOutline = true;
      }
      if (world.model.count(WorkTaskKind.DESCRIBE_CONTEXT) > context
          || (result.reasons().contains("revalidated") && result.taskId().startsWith("describe-context-"))) {
        assertTrue(logicalAccepted, trace.toString());
        sawContext = true;
      }
      if (world.model.count(WorkTaskKind.MAP_TRANSFER) > mappings) {
        assertTrue(logicalAccepted, trace.toString());
        sawMapping = true;
      }
      assertEquals(failureRules, ruleIds(world.document(), siblingBehavior), trace.toString());
      String seenFingerprint = taskFingerprint(world.document(), failureKey);
      if (logicalAccepted && taskState(world.document(), failureKey) == WorkTaskState.ACCEPTED) {
        assertFalse(seenFingerprint.isBlank(), trace.toString());
      } else {
        assertEquals(failureFingerprint, seenFingerprint, trace.toString());
      }
      if (result.action() == FillingResult.Action.HALTED || result.action() == FillingResult.Action.WAITING_FOR_INPUT) {
        break;
      }
    }
    assertTrue(sawSelect, trace.toString());
    assertTrue(logicalAccepted, trace.toString());
    assertTrue(requirementText(world.document()).contains("corrected operation"), trace.toString());
    assertTrue(sawOutline, trace.toString());
    assertTrue(sawContext, trace.toString());
    assertTrue(sawMapping || world.model.count(WorkTaskKind.MAP_TRANSFER) > mappingsBefore, trace.toString());
    assertTrue(world.model.count(WorkTaskKind.DEFINE_TRANSFERS) >= outlinesBefore, trace.toString());
    assertEquals(failureRules, ruleIds(world.document(), siblingBehavior));
    assertTrue(world.repairCharges() <= 3);
    assertTrue(world.repairCharges() >= 1, trace.toString());
    assertTrue(notes.size() >= 2, notes.toString());
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
      if (WorkDocumentFillingTest.behaviors(world.document()).contains("formatted fallback")) {
        break;
      }
      assertEquals(FillingResult.Action.ADVANCED, result.action(), trace.toString());
    }
    assertTrue(world.document().progress().findings().isEmpty());
    List<String> requestRules = requestRuleIds(world.document());
    int mappings = world.model.count(WorkTaskKind.MAP_TRANSFER);
    world.model.routingDefectCategory = "SEMANTIC_BRANCH";
    reopenBlank(world, otherMapping(world.document()));
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

  private static String otherMapping(ChainWorkDocument document) {
    String requestKey = transferTaskKey(document, "formatted fallback");
    String found = "";
    for (WorkTaskRecord task : document.progress().tasks()) {
      if (task.kind() == WorkTaskKind.MAP_TRANSFER
          && task.state() == WorkTaskState.ACCEPTED
          && !task.taskKey().equals(requestKey)) {
        found = task.taskKey();
      }
    }
    return found;
  }

  private static void reopenBlank(FillingWorld world, String taskKey) {
    if (taskKey == null || taskKey.isBlank()) {
      return;
    }
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
                "",
                task.producedRecordIds()));
      } else {
        tasks.add(task);
      }
    }
    world.replaceProgress(tasks, current.progress().findings(), "reopen-blank-" + taskKey);
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

  private static String revisionNote(FillingWorld world, FillingResult result, List<String> siblingIds) {
    return result.documentRevision()
        + " "
        + result.taskId()
        + " "
        + result.reasons()
        + " siblings="
        + siblingIds;
  }

  private static String stepId(ChainWorkDocument document, StepKind kind) {
    for (LogicalStep step : document.flow().steps()) {
      if (step.kind() == kind) {
        return step.id();
      }
    }
    return "";
  }

  private static String bindingOperation(ChainWorkDocument document, String stepId) {
    for (LogicalStep step : document.flow().steps()) {
      if (step.id().equals(stepId) && step.binding() != null) {
        return step.binding().operationId();
      }
    }
    return "";
  }

  private static WorkTaskState taskState(ChainWorkDocument document, String taskKey) {
    for (WorkTaskRecord task : document.progress().tasks()) {
      if (taskKey.equals(task.taskKey())) {
        return task.state();
      }
    }
    return null;
  }

  private static String taskFingerprint(ChainWorkDocument document, String taskKey) {
    for (WorkTaskRecord task : document.progress().tasks()) {
      if (taskKey.equals(task.taskKey())) {
        return task.acceptedInputFingerprint();
      }
    }
    return "";
  }

  private static String transferTaskKey(ChainWorkDocument document, String behavior) {
    for (LogicalStep step : document.flow().steps()) {
      for (DataTransfer transfer : step.data().transfers()) {
        for (MappingRule rule : transfer.rules()) {
          if (rule.behavior().contains(behavior)) {
            return "map-transfer:" + transfer.id();
          }
        }
      }
    }
    return "";
  }

  private static String failureTransferId(ChainWorkDocument document) {
    for (LogicalStep step : document.flow().steps()) {
      for (DataTransfer transfer : step.data().transfers()) {
        if (!transfer.sourcePorts().isEmpty() && "failure".equals(transfer.sourcePorts().get(0).portName())) {
          return transfer.id();
        }
      }
    }
    return "";
  }

  private static int mappingsForStep(FillingWorld world, String stepId) {
    int count = 0;
    for (String call : world.model.calls()) {
      if (!call.startsWith(WorkTaskKind.MAP_TRANSFER.name() + " ")) {
        continue;
      }
      String taskId = call.substring(call.indexOf(' ') + 1);
      if (taskId.startsWith("map-transfer-")) {
        String record = taskId.substring("map-transfer-".length());
        for (LogicalStep step : world.document().flow().steps()) {
          if (!step.id().equals(stepId)) {
            continue;
          }
          for (DataTransfer transfer : step.data().transfers()) {
            if (record.equals(transfer.id())) {
              count++;
            }
          }
        }
      }
    }
    return count;
  }

  private static boolean outlineAccepted(ChainWorkDocument document) {
    for (WorkTaskRecord task : document.progress().tasks()) {
      if (task.kind() == WorkTaskKind.DEFINE_TRANSFERS && task.state() != WorkTaskState.ACCEPTED) {
        return false;
      }
    }
    return true;
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
