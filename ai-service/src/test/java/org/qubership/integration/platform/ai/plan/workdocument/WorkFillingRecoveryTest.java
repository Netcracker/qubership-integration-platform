package org.qubership.integration.platform.ai.plan.workdocument;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.plan.workdocument.task.WorkTaskRequest;

/**
 * Technical retry, stale output, a real second model call, and a contract recheck.
 * The driver calls only advance.
 */
class WorkFillingRecoveryTest {

  @Test
  void staleOutputRetriesWithoutASecondModelCallOrAcceptedSteps() throws Exception {
    FillingWorld world = FillingWorld.start("run-stale");
    world.model.duringComplete =
        () -> {
          world.model.duringComplete = null;
          ChainWorkDocument current = world.document();
          WorkProgress progress = current.progress();
          ChainWorkDocument changed =
              new ChainWorkDocument(
                  current.schemaVersion(),
                  current.documentId(),
                  current.sources(),
                  current.requirements(),
                  current.flow(),
                  new WorkProgress(
                      progress.tasks(),
                      progress.findings(),
                      progress.questions(),
                      "concurrent-answer",
                      progress.derivedResultReferences(),
                      progress.recheckStages()));
          world.documents.commitRecoveredDocument(
              world.runId,
              changed,
              "concurrent-note",
              "stale-hash",
              "concurrent-clarification",
              "LOGICAL_FLOW",
              null);
        };
    FillingResult stale = world.filling.advance(world.runId, "stale-1");
    assertEquals(FillingResult.Action.RETRY_CURRENT, stale.action(), stale.toString());
    assertEquals(0L, stale.retryDelayMs());
    assertTrue(stale.reasons().contains("stale-result"));
    assertEquals(1, world.model.count(WorkTaskKind.LOGICAL_DESIGN));
    assertTrue(world.document().flow().steps().isEmpty());
    assertTrue(stale.questionIds().isEmpty());
  }

  @Test
  void correctivePublishRemovesOnlyTheResolvedFinding() throws Exception {
    FillingWorld world = FillingWorld.start("run-finding");
    world.model.inject(
        WorkTaskKind.LOGICAL_DESIGN, 1, FillingWorld.ScriptedWorkModel.synchronousReceive("src-om"));
    FillingResult detected = world.advance(null);
    assertTrue(detected.reasons().contains("SYNCHRONOUS_RESULT"), detected.toString());
    ChainWorkDocument current = world.document();
    assertFalse(current.progress().findings().isEmpty(), detected.toString());
    WorkFinding open = current.progress().findings().get(0);
    List<WorkFinding> findings = new ArrayList<>(current.progress().findings());
    findings.add(
        new WorkFinding(
            "finding-unrelated",
            open.recordRef(),
            "UNRELATED_GAP",
            "A second defect remains.",
            List.of("src-om"),
            "other"));
    world.replaceProgress(current.progress().tasks(), findings, "plant-finding");
    FillingResult repaired = world.advance(null);
    assertEquals(FillingResult.Action.HALTED, repaired.action(), repaired.toString());
    assertTrue(repaired.reasons().contains("PREPARED"), repaired.toString());
    assertTrue(
        repaired.reasons().contains("A structural defect or unmet dependency remains."), repaired.toString());
    boolean unrelated = false;
    boolean resolvedGone = true;
    for (WorkFinding finding : world.document().progress().findings()) {
      if ("UNRELATED_GAP".equals(finding.issueCategory())) {
        unrelated = true;
      }
      if ("SYNCHRONOUS_RESULT".equals(finding.issueCategory()) && open.recordRef().equals(finding.recordRef())) {
        resolvedGone = false;
      }
    }
    assertTrue(unrelated, world.document().progress().findings().toString());
    assertTrue(resolvedGone, world.document().progress().findings().toString());
  }

  @Test
  void outlineRepairKeepsMissingRetainedWhenTheTransferOmitsIt() throws Exception {
    FillingWorld world = FillingWorld.start("run-retained-open");
    world.model.omitRetainedDeclaration = true;
    world.model.ineffectiveOutlineRepair = true;
    world.model.reportMissingRetained = true;
    List<String> trace = new ArrayList<>();
    String consumerTransfer = "";
    boolean detected = false;
    for (int step = 0; step < 30 && !detected; step++) {
      FillingResult result = world.advance(trace);
      if (result.reasons().contains("MISSING_RETAINED")) {
        detected = true;
        for (WorkFinding finding : world.document().progress().findings()) {
          if ("MISSING_RETAINED".equals(finding.issueCategory()) && finding.canonicalFieldPointer().isBlank()) {
            consumerTransfer = finding.recordRef();
          }
        }
      }
    }
    assertTrue(detected, trace.toString());
    assertFalse(consumerTransfer.isBlank(), world.document().progress().findings().toString());
    int outlines = world.model.count(WorkTaskKind.DEFINE_TRANSFERS);
    FillingResult repaired = world.advance(trace);
    assertTrue(world.model.count(WorkTaskKind.DEFINE_TRANSFERS) > outlines, repaired.toString());
    DataTransfer stored = transfer(world.document(), consumerTransfer);
    assertTrue(stored != null && stored.requiredRetainedIds().isEmpty(), trace.toString());
    boolean stillOpen = false;
    for (WorkFinding finding : world.document().progress().findings()) {
      if ("MISSING_RETAINED".equals(finding.issueCategory())
          && consumerTransfer.equals(finding.recordRef())
          && finding.canonicalFieldPointer().isBlank()) {
        stillOpen = true;
      }
    }
    assertTrue(stillOpen, world.document().progress().findings().toString());
  }

  @Test
  void repeatedAdvanceOnUnchangedAcceptedInputsMakesNoContextCall() throws Exception {
    FillingWorld world = FillingWorld.start("run-repeat-advance");
    List<String> trace = new ArrayList<>();
    while (world.model.count(WorkTaskKind.DESCRIBE_CONTEXT) < 1) {
      FillingResult result = world.advance(trace);
      assertEquals(FillingResult.Action.ADVANCED, result.action(), trace.toString());
    }
    int contextCalls = world.model.count(WorkTaskKind.DESCRIBE_CONTEXT);
    FillingResult again = world.advance(trace);
    assertEquals(FillingResult.Action.ADVANCED, again.action(), trace.toString());
    assertEquals(contextCalls, world.model.count(WorkTaskKind.DESCRIBE_CONTEXT), trace.toString());
    FillingResult third = world.advance(trace);
    if (third.action() == FillingResult.Action.ADVANCED) {
      assertEquals(contextCalls, world.model.count(WorkTaskKind.DESCRIBE_CONTEXT), trace.toString());
    }
  }

  @Test
  void promptRepairCaptureLeavesOmittedPortsEmpty() {
    String capture =
        PromptRepairCapture.outline(
            """
            allowed-update xfer-1
            finding f-1 category MISSING_RETAINED record xfer-1 pointer  The outline has no retained declaration.
            """,
            "enum xfer-1",
            PromptRepairCapture.Link.DECLARE);
    assertTrue(capture.contains("\"existingId\":\"xfer-1\""));
    assertTrue(capture.contains("\"sourceStepId\":\"\""));
    assertTrue(capture.contains("\"sourcePort\":\"\""));
    assertFalse(capture.contains("keep-process"));
  }

  @Test
  void sameCategoryFindingsRepairOnlyTheDispatchRecord() throws Exception {
    FillingWorld world = FillingWorld.start("run-same-category");
    world.model.omitRetainedDeclaration = true;
    world.model.reportMissingRetained = true;
    List<String> trace = new ArrayList<>();
    boolean planted = false;
    String consumerTransfer = "";
    for (int step = 0; step < 40 && consumerTransfer.isBlank(); step++) {
      if (!planted) {
        String other = portTransfer(world.document(), "failure");
        if (!other.isBlank()) {
          List<WorkFinding> findings = new ArrayList<>(world.document().progress().findings());
          findings.add(
              new WorkFinding(
                  "finding-other",
                  other,
                  "MISSING_RETAINED",
                  "A different retained gap stays open.",
                  List.of(world.document().sources().get(0).id()),
                  "data"));
          world.replaceProgress(world.document().progress().tasks(), findings, "plant-other-category");
          planted = true;
        }
      }
      FillingResult result = world.advance(trace);
      if (result.reasons().contains("MISSING_RETAINED")) {
        for (WorkFinding finding : world.document().progress().findings()) {
          if ("MISSING_RETAINED".equals(finding.issueCategory())
              && !"finding-other".equals(finding.id())
              && finding.canonicalFieldPointer().isBlank()) {
            consumerTransfer = finding.recordRef();
          }
        }
      }
    }
    assertTrue(planted, trace.toString());
    assertFalse(consumerTransfer.isBlank(), world.document().progress().findings().toString());
    RepairAssignment repair = activeRepair(world.document());
    assertEquals(consumerTransfer, repair.recordRef(), repair.toString());
    assertNotEquals("finding-other", repair.findingId());
    FillingResult owner = world.advance(trace);
    assertEquals(FillingResult.Action.ADVANCED, owner.action(), owner.toString());
    WorkTaskRequest corrective = correctiveOutline(world);
    assertTrue(corrective.prompt().contains("finding "));
    assertTrue(corrective.prompt().contains(repair.findingId()), corrective.prompt());
    assertTrue(corrective.prompt().contains(consumerTransfer), corrective.prompt());
    assertTrue(corrective.prompt().contains(repair.contradiction()), corrective.prompt());
    assertTrue(corrective.responseSchema().toString().contains(consumerTransfer));
    assertFalse(corrective.prompt().contains("allowed-create TRANSFER"), corrective.prompt());
    assertEquals(
        PromptRepairCapture.outline(
            corrective.prompt(),
            corrective.responseSchema().toString(),
            PromptRepairCapture.Link.DECLARE),
        world.model.lastResponse());
    for (int step = 0; step < 25 && findingOpen(world.document(), repair.findingId()); step++) {
      FillingResult result = world.advance(trace);
      if (result.action() == FillingResult.Action.WAITING_FOR_INPUT && !result.questionIds().isEmpty()) {
        world.filling.acceptInput(
            world.runId, result.questionIds().get(0), "answer-same", FillingWorld.answerText());
      }
    }
    assertTrue(findingOpen(world.document(), "finding-other"), world.document().progress().findings().toString());
    assertFalse(findingOpen(world.document(), repair.findingId()), world.document().progress().findings().toString());
    WorkTaskRequest consumer = consumerRequest(world, repair);
    assertTrue(consumer.prompt().contains("finding " + repair.findingId()), consumer.prompt());
    assertTrue(consumer.prompt().contains("record " + repair.recordRef()), consumer.prompt());
    assertTrue(consumer.prompt().contains(repair.contradiction()), consumer.prompt());
  }

  @Test
  void unlinkedRetainedValueLeavesTheOriginalFindingOpen() throws Exception {
    FillingWorld world = FillingWorld.start("run-unlinked-retained");
    world.model.omitRetainedDeclaration = true;
    world.model.unlinkedRetainedRepair = true;
    world.model.reportMissingRetained = true;
    List<String> trace = new ArrayList<>();
    String consumerTransfer = "";
    for (int step = 0; step < 30 && consumerTransfer.isBlank(); step++) {
      FillingResult result = world.advance(trace);
      if (result.reasons().contains("MISSING_RETAINED")) {
        for (WorkFinding finding : world.document().progress().findings()) {
          if ("MISSING_RETAINED".equals(finding.issueCategory()) && finding.canonicalFieldPointer().isBlank()) {
            consumerTransfer = finding.recordRef();
          }
        }
      }
    }
    assertFalse(consumerTransfer.isBlank(), trace.toString());
    String findingId = "";
    for (WorkFinding finding : world.document().progress().findings()) {
      if (consumerTransfer.equals(finding.recordRef()) && "MISSING_RETAINED".equals(finding.issueCategory())) {
        findingId = finding.id();
      }
    }
    int retainedBefore = retainedCount(world.document());
    FillingResult repaired = world.advance(trace);
    assertTrue(
        repaired.reasons().contains("PREPARED") || repaired.action() == FillingResult.Action.ADVANCED,
        repaired.toString());
    DataTransfer stored = transfer(world.document(), consumerTransfer);
    assertTrue(stored != null && stored.requiredRetainedIds().isEmpty(), trace.toString());
    assertTrue(retainedCount(world.document()) > retainedBefore, trace.toString());
    assertTrue(findingOpen(world.document(), findingId), world.document().progress().findings().toString());
  }

  @Test
  void correctionInFlightKeepsTheLatestAcceptedInput() throws Exception {
    FillingWorld world = FillingWorld.start("run-inflight-correction");
    world.model.omitRetainedDeclaration = true;
    world.model.reportMissingRetained = true;
    List<String> trace = new ArrayList<>();
    String consumerTransfer = "";
    for (int step = 0; step < 30 && consumerTransfer.isBlank(); step++) {
      FillingResult result = world.advance(trace);
      if (result.reasons().contains("MISSING_RETAINED")) {
        for (WorkFinding finding : world.document().progress().findings()) {
          if ("MISSING_RETAINED".equals(finding.issueCategory()) && finding.canonicalFieldPointer().isBlank()) {
            consumerTransfer = finding.recordRef();
          }
        }
      }
    }
    assertFalse(consumerTransfer.isBlank(), trace.toString());
    int transfersBefore = transferCount(world.document());
    world.model.duringComplete =
        () -> {
          world.model.duringComplete = null;
          publishNote(world, "latest-accepted-note", "concurrent-input");
        };
    FillingResult stale = world.advance(trace);
    assertEquals(FillingResult.Action.RETRY_CURRENT, stale.action(), stale.toString());
    assertTrue(stale.reasons().contains("stale-result"), stale.toString());
    assertEquals("latest-accepted-note", world.document().progress().approvalReference());
    DataTransfer stored = transfer(world.document(), consumerTransfer);
    assertTrue(stored != null && stored.requiredRetainedIds().isEmpty(), stored == null ? "missing" : stored.toString());
    assertEquals(transfersBefore, transferCount(world.document()));
    assertFalse(world.document().progress().repairs().isEmpty());
  }

  @Test
  void linkingAnExistingRetainedIdMovesTheRepairToVerification() throws Exception {
    FillingWorld world = FillingWorld.start("run-link-existing");
    world.model.omitRetainedDeclaration = true;
    List<String> trace = new ArrayList<>();
    String triggerId = "";
    String transferId = "";
    boolean planted = false;
    for (int step = 0; step < 40 && transferId.isBlank(); step++) {
      FillingResult result = world.advance(trace);
      answer(world, result);
      if (triggerId.isBlank()) {
        triggerId = stepId(world.document(), StepKind.TRIGGER);
      }
      if (!planted && !triggerId.isBlank()) {
        plantRetained(world, triggerId, "keep-existing");
        planted = true;
      }
      transferId = successTransfer(world.document());
    }
    assertTrue(planted, trace.toString());
    assertFalse(transferId.isBlank(), trace.toString());
    DataTransfer open = transfer(world.document(), transferId);
    assertFalse(open.requiredRetainedIds().contains("keep-existing"), open.toString());
    int mappings = world.model.count(WorkTaskKind.MAP_TRANSFER);
    world.model.inject(
        WorkTaskKind.MAP_TRANSFER,
        mappings + 1,
        missingRetainedDefect(transferId, world.document().sources().get(0).id()));
    String findingId = "";
    for (int step = 0; step < 20 && findingId.isBlank(); step++) {
      FillingResult result = world.advance(trace);
      answer(world, result);
      if (result.reasons().contains("MISSING_RETAINED")) {
        for (WorkFinding finding : world.document().progress().findings()) {
          if ("MISSING_RETAINED".equals(finding.issueCategory()) && transferId.equals(finding.recordRef())) {
            findingId = finding.id();
          }
        }
      }
    }
    assertFalse(findingId.isBlank(), trace.toString());
    int outlines = world.model.count(WorkTaskKind.DEFINE_TRANSFERS);
    world.model.inject(
        WorkTaskKind.DEFINE_TRANSFERS,
        outlines + 1,
        outlineCapture(
            world.document(),
            transferId,
            List.of("keep-existing"),
            "",
            coveragePassage(world.document(), transferId)));
    FillingResult repaired = world.advance(trace);
    RepairAssignment repair = activeRepair(world.document());
    assertTrue(repair != null, repaired.toString());
    assertEquals(RepairAssignment.VERIFY, repair.phase(), repaired + "\n" + trace);
    assertTrue(findingOpen(world.document(), findingId), world.document().progress().findings().toString());
    DataTransfer stored = transfer(world.document(), transferId);
    assertEquals(transferId, stored.id());
    assertTrue(stored.requiredRetainedIds().contains("keep-existing"), stored.toString());
    assertTrue(repair.candidateRevision().startsWith("prior:"), repair.candidateRevision());
    assertFalse(repair.candidateRevision().contains("keep-existing"), repair.candidateRevision());
  }

  @Test
  void retainedObligationStaysOpenWithoutANewResolvingLink() throws Exception {
    assertUnlinkedValueStaysOpen();
    assertIdAlreadyOnTheTransferStaysOpen();
    assertCoverageOnlyEditStaysOpen();
  }

  @Test
  void consumerWaitsWhileADependencyIsNotReady() throws Exception {
    FillingWorld world = FillingWorld.start("run-blocked-consumer");
    world.model.omitRetainedDeclaration = true;
    world.model.reportMissingRetained = true;
    List<String> trace = new ArrayList<>();
    RepairAssignment repair = null;
    for (int step = 0; step < 40 && (repair == null || !RepairAssignment.VERIFY.equals(repair.phase())); step++) {
      world.advance(trace);
      repair = activeRepair(world.document());
    }
    assertTrue(repair != null && RepairAssignment.VERIFY.equals(repair.phase()), trace.toString());
    String dependency = contextDependency(world.document(), repair.consumerTaskKey());
    assertFalse(dependency.isBlank(), repair.toString());
    int consumerCalls = callsFor(world, taskId(repair.consumerTaskKey()));
    blockDependency(world, dependency);
    FillingResult next = world.advance(trace);
    assertEquals(consumerCalls, callsFor(world, taskId(repair.consumerTaskKey())), next.toString());
    assertNotEquals(taskId(repair.consumerTaskKey()), next.taskId());
  }

  @Test
  void networkFailureRetriesWithTheConfiguredDelayAndNoBusinessQuestion() throws Exception {
    FillingWorld world = FillingWorld.start("run-network");
    world.model.inject(WorkTaskKind.LOGICAL_DESIGN, 1, "CONNECT");
    FillingResult retry = world.advance(null);
    assertEquals(FillingResult.Action.RETRY_CURRENT, retry.action(), retry.toString());
    assertEquals(250L, retry.retryDelayMs());
    assertTrue(retry.questionIds().isEmpty());
    var run = world.runs.load(world.runId).orElseThrow();
    assertTrue(world.runs.uncertainProviderDeliveries(run).contains("advance-1:1"));
    assertTrue(world.runs.confirmedProviderDeliveries(run).isEmpty());
    FillingResult next = world.advance(null);
    assertEquals(FillingResult.Action.ADVANCED, next.action(), next.toString());
    assertEquals(2, world.model.count(WorkTaskKind.LOGICAL_DESIGN));
  }

  @Test
  void rejectedFieldReferenceInvokesTheModelAgain() throws Exception {
    FillingWorld world = FillingWorld.start("run-bad-ref");
    world.model.badMappingField = true;
    List<String> trace = new ArrayList<>();
    String rejectedTask = "";
    String revisionBeforeRepair = "";
    for (int step = 0; step < 30 && (rejectedTask.isBlank() || callsFor(world, rejectedTask) < 2); step++) {
      FillingResult result = world.advance(trace);
      if (rejectedTask.isBlank() && result.reasons().contains("MALFORMED_REFERENCE")) {
        rejectedTask = result.taskId();
        revisionBeforeRepair = result.documentRevision();
        assertEquals(1, world.repairCharges(), trace.toString());
      }
      if (result.action() == FillingResult.Action.WAITING_FOR_INPUT && rejectedTask.isBlank()) {
        break;
      }
    }
    assertEquals(2, callsFor(world, rejectedTask), trace.toString());
    assertNotEquals(revisionBeforeRepair, world.documents.read(world.runId).revision());
    assertTrue(acceptedBehavior(world.document()), trace.toString());
  }

  @Test
  void changedContractRechecksTheMappingAndLeavesTheOutlineCallCount() throws Exception {
    FillingWorld world = FillingWorld.start("run-contract");
    List<String> trace = new ArrayList<>();
    while (world.model.count(WorkTaskKind.DESCRIBE_CONTEXT) < 1
        || world.model.count(WorkTaskKind.MAP_TRANSFER) > 0) {
      FillingResult result = world.advance(trace);
      assertEquals(FillingResult.Action.ADVANCED, result.action(), trace.toString());
    }
    int outlines = world.model.count(WorkTaskKind.DEFINE_TRANSFERS);
    world.model.routingDefectCategory = "WRONG_OPERATION";
    FillingResult detected = world.advance(trace);
    assertTrue(detected.reasons().contains("WRONG_OPERATION"), detected.toString());
    assertEquals(1, world.model.count(WorkTaskKind.MAP_TRANSFER));
    world.catalog.version("2");
    int selects = world.model.count(WorkTaskKind.SELECT_OPERATION);
    FillingResult corrected = world.advance(trace);
    assertEquals(FillingResult.Action.ADVANCED, corrected.action(), corrected.toString());
    assertEquals(selects + 1, world.model.count(WorkTaskKind.SELECT_OPERATION));
    boolean versionTwo = false;
    for (LogicalStep step : world.document().flow().steps()) {
      if (step.binding() != null && "2".equals(step.binding().version())) {
        versionTwo = true;
      }
    }
    assertTrue(versionTwo, trace.toString());
    String mappingTask = detected.taskId();
    while (callsFor(world, mappingTask) < 2) {
      FillingResult result = world.advance(trace);
      if (result.action() != FillingResult.Action.ADVANCED) {
        break;
      }
    }
    assertEquals(2, callsFor(world, mappingTask), trace.toString());
    assertEquals(outlines, world.model.count(WorkTaskKind.DEFINE_TRANSFERS), trace.toString());
  }

  private static RepairAssignment activeRepair(ChainWorkDocument document) {
    List<RepairAssignment> repairs = document.progress().repairs();
    return repairs.isEmpty() ? null : repairs.get(repairs.size() - 1);
  }

  private static boolean findingOpen(ChainWorkDocument document, String findingId) {
    for (WorkFinding finding : document.progress().findings()) {
      if (findingId.equals(finding.id())) {
        return true;
      }
    }
    return false;
  }

  private static String portTransfer(ChainWorkDocument document, String port) {
    for (LogicalStep step : document.flow().steps()) {
      for (DataTransfer candidate : step.data().transfers()) {
        if (!candidate.sourcePorts().isEmpty() && port.equals(candidate.sourcePorts().get(0).portName())) {
          return candidate.id();
        }
      }
    }
    return "";
  }

  private static int retainedCount(ChainWorkDocument document) {
    int count = 0;
    for (LogicalStep step : document.flow().steps()) {
      count += step.data().retainedValues().size();
    }
    return count;
  }

  private static int transferCount(ChainWorkDocument document) {
    int count = 0;
    for (LogicalStep step : document.flow().steps()) {
      count += step.data().transfers().size();
    }
    return count;
  }

  private static void publishNote(FillingWorld world, String note, String commandId) {
    ChainWorkDocument current = world.document();
    WorkProgress progress = current.progress();
    ChainWorkDocument changed =
        new ChainWorkDocument(
            current.schemaVersion(),
            current.documentId(),
            current.sources(),
            current.requirements(),
            current.flow(),
            new WorkProgress(
                progress.tasks(),
                progress.findings(),
                progress.questions(),
                note,
                progress.derivedResultReferences(),
                progress.recheckStages(),
                progress.repairs()));
    world.documents.commitRecoveredDocument(
        world.runId, changed, commandId, commandId, "concurrent-clarification", "DATA_BEHAVIOR", null);
  }

  private static WorkTaskRequest correctiveOutline(FillingWorld world) {
    WorkTaskRequest found = null;
    for (WorkTaskRequest request : world.model.requests()) {
      if (request.kind() == WorkTaskKind.DEFINE_TRANSFERS
          && request.prompt() != null
          && request.prompt().contains("allowed-update ")) {
        found = request;
      }
    }
    assertTrue(found != null, world.model.calls().toString());
    return found;
  }

  private static WorkTaskRequest consumerRequest(FillingWorld world, RepairAssignment repair) {
    WorkTaskRequest found = null;
    for (WorkTaskRequest request : world.model.requests()) {
      if (repair.consumerTaskKey().equals(request.taskKey())
          && request.prompt() != null
          && request.prompt().contains("finding " + repair.findingId())) {
        found = request;
      }
    }
    assertTrue(found != null, world.model.calls().toString());
    return found;
  }

  private static String contextDependency(ChainWorkDocument document, String consumerKey) {
    WorkTaskPlanner.Task consumer = null;
    WorkTaskPlanner.Plan plan = new WorkTaskPlanner().plan(document);
    for (WorkTaskPlanner.Task task : plan.tasks()) {
      if (consumerKey.equals(task.taskKey())) {
        consumer = task;
      }
    }
    if (consumer == null) {
      return "";
    }
    for (String dependency : consumer.dependencyKeys()) {
      if (dependency.startsWith("describe-context:")) {
        return dependency;
      }
    }
    return "";
  }

  private static void blockDependency(FillingWorld world, String taskKey) {
    ChainWorkDocument current = world.document();
    WorkProgress progress = current.progress();
    List<WorkTaskRecord> tasks = new ArrayList<>();
    for (WorkTaskRecord task : progress.tasks()) {
      if (taskKey.equals(task.taskKey())) {
        tasks.add(
            new WorkTaskRecord(
                task.taskKey(),
                task.kind(),
                task.taskId(),
                WorkTaskState.PENDING,
                task.stage(),
                task.skillId(),
                "",
                task.producedRecordIds()));
      } else {
        tasks.add(task);
      }
    }
    String source = current.sources().get(0).id();
    List<WorkQuestion> questions = new ArrayList<>(progress.questions());
    questions.add(
        new WorkQuestion(
            "q-block-context",
            "operation",
            "Which source field should stay?",
            List.of(source),
            taskKey,
            QuestionSubject.unspecified(),
            List.of(),
            List.of(),
            QuestionResolution.OPEN));
    world.documents.commitRecoveredDocument(
        world.runId,
        new ChainWorkDocument(
            current.schemaVersion(),
            current.documentId(),
            current.sources(),
            current.requirements(),
            current.flow(),
            new WorkProgress(
                tasks,
                progress.findings(),
                questions,
                progress.approvalReference(),
                progress.derivedResultReferences(),
                progress.recheckStages(),
                progress.repairs())),
        "block-dependency",
        "block-dependency",
        "task-progress",
        "DATA_BEHAVIOR",
        null);
  }

  private static String taskId(String taskKey) {
    return taskKey == null ? "" : taskKey.replace(':', '-');
  }

  private static boolean acceptedBehavior(ChainWorkDocument document) {
    String rules = WorkDocumentFillingTest.behaviors(document);
    return rules.contains("formatted fallback")
        || rules.contains("failure code")
        || rules.contains("processInstanceId");
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

  private static int callsFor(FillingWorld world, String taskId) {
    int count = 0;
    for (String call : world.model.calls()) {
      if (call.endsWith(" " + taskId)) {
        count++;
      }
    }
    return count;
  }

  private static void assertUnlinkedValueStaysOpen() throws Exception {
    Held held = holdMissingRetained("run-new-unlinked", true);
    int retainedBefore = retainedCount(held.world.document());
    FillingResult repaired =
        repairOutline(held, List.of(), "keep-unlinked", coveragePassage(held.world.document(), held.transferId));
    assertOwnerStillOpen(held, repaired);
    DataTransfer stored = transfer(held.world.document(), held.transferId);
    assertTrue(stored.requiredRetainedIds().isEmpty(), stored.toString());
    assertTrue(retainedCount(held.world.document()) > retainedBefore, held.trace.toString());
  }

  private static void assertIdAlreadyOnTheTransferStaysOpen() throws Exception {
    FillingWorld world = FillingWorld.start("run-already-linked");
    List<String> trace = new ArrayList<>();
    String transferId = "";
    for (int step = 0; step < 40 && transferId.isBlank(); step++) {
      FillingResult result = world.advance(trace);
      answer(world, result);
      transferId = linkedTransfer(world.document());
    }
    assertFalse(transferId.isBlank(), trace.toString());
    List<String> linked = List.copyOf(transfer(world.document(), transferId).requiredRetainedIds());
    int mappings = world.model.count(WorkTaskKind.MAP_TRANSFER);
    world.model.inject(
        WorkTaskKind.MAP_TRANSFER,
        mappings + 1,
        missingRetainedDefect(transferId, world.document().sources().get(0).id()));
    String findingId = "";
    for (int step = 0; step < 20 && findingId.isBlank(); step++) {
      FillingResult result = world.advance(trace);
      answer(world, result);
      if (result.reasons().contains("MISSING_RETAINED")) {
        for (WorkFinding finding : world.document().progress().findings()) {
          if ("MISSING_RETAINED".equals(finding.issueCategory()) && transferId.equals(finding.recordRef())) {
            findingId = finding.id();
          }
        }
      }
    }
    assertFalse(findingId.isBlank(), trace.toString());
    Held held = new Held(world, trace, transferId, findingId);
    FillingResult repaired =
        repairOutline(held, linked, "", coveragePassage(world.document(), transferId));
    assertOwnerStillOpen(held, repaired);
    assertEquals(linked, transfer(world.document(), transferId).requiredRetainedIds());
  }

  private static void assertCoverageOnlyEditStaysOpen() throws Exception {
    Held held = holdMissingRetained("run-coverage-only", true);
    String passage = alternatePassage(held.world.document(), held.transferId);
    assertNotEquals(coveragePassage(held.world.document(), held.transferId), passage);
    FillingResult repaired = repairOutline(held, List.of(), "", passage);
    assertOwnerStillOpen(held, repaired);
    DataTransfer stored = transfer(held.world.document(), held.transferId);
    assertTrue(stored.requiredRetainedIds().isEmpty(), stored.toString());
    boolean cited = false;
    for (LogicalStep step : held.world.document().flow().steps()) {
      for (DataTransfer candidate : step.data().transfers()) {
        if (!held.transferId.equals(candidate.id())) {
          continue;
        }
        for (CoverageEntry entry : step.data().outline().coverage()) {
          if (passage.equals(entry.passageId())) {
            cited = true;
          }
        }
      }
    }
    assertTrue(cited, held.trace.toString());
  }

  private static Held holdMissingRetained(String runId, boolean omitRetained) throws Exception {
    FillingWorld world = FillingWorld.start(runId);
    world.model.omitRetainedDeclaration = omitRetained;
    world.model.reportMissingRetained = true;
    List<String> trace = new ArrayList<>();
    String transferId = "";
    String findingId = "";
    for (int step = 0; step < 40 && findingId.isBlank(); step++) {
      FillingResult result = world.advance(trace);
      answer(world, result);
      if (result.reasons().contains("MISSING_RETAINED")) {
        for (WorkFinding finding : world.document().progress().findings()) {
          if ("MISSING_RETAINED".equals(finding.issueCategory()) && finding.canonicalFieldPointer().isBlank()) {
            transferId = finding.recordRef();
            findingId = finding.id();
          }
        }
      }
    }
    assertFalse(findingId.isBlank(), trace.toString());
    return new Held(world, trace, transferId, findingId);
  }

  private static FillingResult repairOutline(
      Held held, List<String> retainedIds, String placeholderAlias, String passageId) {
    int outlines = held.world.model.count(WorkTaskKind.DEFINE_TRANSFERS);
    held.world.model.inject(
        WorkTaskKind.DEFINE_TRANSFERS,
        outlines + 1,
        outlineCapture(held.world.document(), held.transferId, retainedIds, placeholderAlias, passageId));
    return held.world.advance(held.trace);
  }

  private static void assertOwnerStillOpen(Held held, FillingResult repaired) {
    RepairAssignment repair = activeRepair(held.world.document());
    assertTrue(repair != null, repaired.toString());
    assertEquals(RepairAssignment.OWNER, repair.phase(), repaired + "\n" + held.trace);
    assertTrue(findingOpen(held.world.document(), held.findingId), held.world.document().progress().findings().toString());
  }

  private static void answer(FillingWorld world, FillingResult result) throws Exception {
    if (result.action() == FillingResult.Action.WAITING_FOR_INPUT && !result.questionIds().isEmpty()) {
      world.filling.acceptInput(world.runId, result.questionIds().get(0), "answer-retained", FillingWorld.answerText());
    }
  }

  private static void plantRetained(FillingWorld world, String producerId, String retainedId) {
    ChainWorkDocument current = world.document();
    String evidence = current.sources().get(0).passages().get(0).id();
    List<LogicalStep> steps = new ArrayList<>();
    for (LogicalStep step : current.flow().steps()) {
      if (!step.id().equals(producerId)) {
        steps.add(step);
        continue;
      }
      List<RetainedValue> values = new ArrayList<>(step.data().retainedValues());
      values.add(
          new RetainedValue(
              retainedId, null, "process id", List.of(evidence), producerId, RetainedResolution.UNRESOLVED));
      steps.add(
          new LogicalStep(
              step.id(),
              step.kind(),
              step.label(),
              step.intent(),
              step.sourceIds(),
              step.requirementIds(),
              step.binding(),
              step.data().withRetained(values)));
    }
    LogicalFlow flow = current.flow();
    world.documents.commitRecoveredDocument(
        world.runId,
        new ChainWorkDocument(
            current.schemaVersion(),
            current.documentId(),
            current.sources(),
            current.requirements(),
            new LogicalFlow(
                steps,
                flow.connections(),
                flow.sequenceGroups(),
                flow.conditionGroups(),
                flow.splitGroups(),
                flow.loopGroups(),
                flow.retryGroups(),
                flow.errorScopeGroups()),
            current.progress()),
        "plant-" + retainedId,
        "plant-" + retainedId,
        "plant-retained",
        "DATA_BEHAVIOR",
        null);
  }

  private static String outlineCapture(
      ChainWorkDocument document,
      String transferId,
      List<String> retainedIds,
      String placeholderAlias,
      String passageId) {
    DataTransfer transfer = transfer(document, transferId);
    String sourceStep = transfer.sourcePorts().get(0).stepId();
    String sourcePort = transfer.sourcePorts().get(0).portName();
    String targetPort = transfer.targetPort().portName();
    String requirement = requirementFor(document, transferId);
    StringBuilder retained = new StringBuilder();
    for (String id : retainedIds) {
      if (retained.length() > 0) {
        retained.append(',');
      }
      retained.append('"').append(id).append('"');
    }
    String placeholder = "";
    if (placeholderAlias != null && !placeholderAlias.isBlank()) {
      placeholder =
          "{\"existingId\":\"\",\"alias\":\""
              + placeholderAlias
              + "\",\"producerStepId\":\""
              + sourceStep
              + "\",\"intendedUse\":\"process id\",\"evidenceRefs\":[\""
              + passageId
              + "\"]}";
    }
    return "{\"outcome\":\"PREPARED\",\"transfers\":[{\"existingId\":\""
        + transferId
        + "\",\"alias\":\"\",\"sourceStepId\":\""
        + sourceStep
        + "\",\"sourcePort\":\""
        + sourcePort
        + "\",\"targetPort\":\""
        + targetPort
        + "\",\"outcome\":\""
        + transfer.outcome().name()
        + "\",\"requirementIds\":[\""
        + requirement
        + "\"],\"requiredRetainedIds\":["
        + retained
        + "],\"decision\":\"\"}],\"retainedPlaceholders\":["
        + placeholder
        + "],\"coverage\":[{\"requirementId\":\""
        + requirement
        + "\",\"passageId\":\""
        + passageId
        + "\",\"disposition\":\"ASSIGNED\"}]}";
  }

  private static String missingRetainedDefect(String transferId, String sourceId) {
    return "{\"outcome\":\"INPUT_DEFECT\",\"rules\":[],\"decision\":\"\",\"evidenceRefs\":[],\"question\":{\"text\":\"\",\"choiceKind\":\"UNSPECIFIED\",\"sourceStepId\":\"\",\"sourcePort\":\"\",\"sourceField\":\"\",\"sourceRetainedId\":\"\",\"targetStepId\":\"\",\"targetPort\":\"\",\"targetField\":\"\",\"targetRetainedId\":\"\",\"evidenceRefs\":[]},\"defect\":{\"recordRef\":\""
        + transferId
        + "\",\"category\":\"MISSING_RETAINED\",\"contradiction\":\"The outline has no retained declaration for the process id.\",\"evidenceRefs\":[\""
        + sourceId
        + "\"]}}";
  }

  private static String requirementFor(ChainWorkDocument document, String transferId) {
    for (LogicalStep step : document.flow().steps()) {
      for (DataTransfer candidate : step.data().transfers()) {
        if (!transferId.equals(candidate.id())) {
          continue;
        }
        if (!candidate.requirementIds().isEmpty()) {
          return candidate.requirementIds().get(0);
        }
        if (!step.requirementIds().isEmpty()) {
          return step.requirementIds().get(0);
        }
      }
    }
    return document.requirements().isEmpty() ? "" : document.requirements().get(0).id();
  }

  private static String coveragePassage(ChainWorkDocument document, String transferId) {
    for (LogicalStep step : document.flow().steps()) {
      boolean owns = false;
      for (DataTransfer candidate : step.data().transfers()) {
        if (transferId.equals(candidate.id())) {
          owns = true;
        }
      }
      if (!owns) {
        continue;
      }
      for (CoverageEntry entry : step.data().outline().coverage()) {
        if (!entry.passageId().isBlank()) {
          return entry.passageId();
        }
      }
    }
    return document.sources().get(0).passages().get(0).id();
  }

  private static String alternatePassage(ChainWorkDocument document, String transferId) {
    String current = coveragePassage(document, transferId);
    for (SourcePassage passage : document.sources().get(0).passages()) {
      if (!passage.id().equals(current)) {
        return passage.id();
      }
    }
    return current;
  }

  private static String successTransfer(ChainWorkDocument document) {
    for (LogicalStep step : document.flow().steps()) {
      for (DataTransfer candidate : step.data().transfers()) {
        if (!candidate.sourcePorts().isEmpty() && "success".equals(candidate.sourcePorts().get(0).portName())) {
          return candidate.id();
        }
      }
    }
    return "";
  }

  private static String linkedTransfer(ChainWorkDocument document) {
    for (LogicalStep step : document.flow().steps()) {
      for (DataTransfer candidate : step.data().transfers()) {
        if (!candidate.requiredRetainedIds().isEmpty()) {
          return candidate.id();
        }
      }
    }
    return "";
  }

  private static String stepId(ChainWorkDocument document, StepKind kind) {
    for (LogicalStep step : document.flow().steps()) {
      if (step.kind() == kind) {
        return step.id();
      }
    }
    return "";
  }

  private record Held(FillingWorld world, List<String> trace, String transferId, String findingId) {}
}
