package org.qubership.integration.platform.ai.plan.workdocument;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.plan.workdocument.ResolvedWorkBinding.PortContentHash;
import org.qubership.integration.platform.ai.plan.workdocument.WorkTaskPlanner.Block;
import org.qubership.integration.platform.ai.plan.workdocument.WorkTaskPlanner.Plan;
import org.qubership.integration.platform.ai.plan.workdocument.WorkTaskPlanner.Reason;
import org.qubership.integration.platform.ai.plan.workdocument.WorkTaskPlanner.Task;
import org.qubership.integration.platform.ai.plan.workdocument.flow.WorkLogicalFlow;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ConditionBranchRole;

class WorkTaskPlannerTest {

  private final WorkTaskPlanner planner = new WorkTaskPlanner();

  @Test
  void absentFlowSelectsOneLogicalTaskFromTheDocumentId() {
    ChainWorkDocument document = WorkPlanningDocuments.document("doc-1", List.of(), List.of(), WorkProgress.empty());

    Plan plan = planner.plan(document);

    assertEquals(List.of("logical-design:doc-1"), keys(plan));
    assertNotNull(plan.selected());
    assertEquals("logical-design:doc-1", plan.selected().taskKey());
    assertEquals("logical-design-doc-1", plan.selected().taskId());
    assertEquals(WorkTaskKind.LOGICAL_DESIGN, plan.selected().kind());
    assertEquals(WorkStage.LOGICAL_FLOW, plan.selected().stage());
    assertEquals(WorkLogicalFlow.SKILL_ID, plan.selected().skillId());
    assertEquals("doc-1", plan.selected().recordId());
    assertEquals(WorkTaskPlanner.Readiness.Status.WORK_REMAINING, plan.readiness().status());
    assertEquals(plan, planner.plan(document));
  }

  @Test
  void derivedSetFollowsRecordsAndOmitsABindingForLocalProcessing() {
    Plan plan = planner.plan(fourSteps());

    assertEquals(
        List.of(
            "logical-design:doc-1",
            "select-operation:call",
            "select-operation:reply",
            "select-operation:trigger",
            "define-transfers:call",
            "define-transfers:format",
            "define-transfers:local",
            "define-transfers:reply",
            "define-transfers:trigger"),
        keys(plan));
    assertEquals("logical-design:doc-1", plan.selected().taskKey());
    assertTrue(task(plan, "define-transfers:local").dependencyKeys().contains("logical-design:doc-1"));
    assertFalse(keys(plan).contains("select-operation:local"));
  }

  @Test
  void permutedRecordsAndRenamedLabelsKeepKeysDependenciesAndSelection() {
    ChainWorkDocument original = fourSteps();
    Plan first = planner.plan(original);
    ChainWorkDocument permuted = permuteAndRename(original);

    Plan second = planner.plan(permuted);

    assertEquals(keys(first), keys(second));
    assertEquals(dependencies(first), dependencies(second));
    assertEquals(first.selected().taskKey(), second.selected().taskKey());
    assertEquals(fingerprints(first), fingerprints(second));
    assertEquals(first.selected().requiredInputFingerprint(), second.selected().requiredInputFingerprint());
  }

  @Test
  void addedExternalInteractionAddsOnlyItsBindingAndOutline() {
    Plan before = planner.plan(fourSteps());
    List<LogicalStep> steps = new ArrayList<>(fourSteps().flow().steps());
    steps.add(
        WorkPlanningDocuments.step(
            "audit-4",
            StepKind.SERVICE_CALL,
            WorkPlanningDocuments.binding("auditOp", "1.0.0"),
            StepData.empty(),
            WorkPlanningDocuments.REQUIREMENT_ID));
    Plan after = planner.plan(WorkPlanningDocuments.document("doc-1", steps, fourSteps().flow().connections(), WorkProgress.empty()));

    List<String> added = new ArrayList<>(keys(after));
    added.removeAll(keys(before));
    assertEquals(List.of("select-operation:audit-4", "define-transfers:audit-4"), added);
    assertTrue(keys(after).containsAll(keys(before)));
  }

  @Test
  void kindOrderSelectsABindingBeforeAnOutlineThatIsAlreadyReady() {
    ChainWorkDocument acceptedLogical = WorkPlanningDocuments.accept(fourSteps(), task -> task.kind() == WorkTaskKind.LOGICAL_DESIGN);

    Plan plan = planner.plan(acceptedLogical);

    assertEquals("select-operation:call", plan.selected().taskKey());
    assertTrue(task(plan, "define-transfers:format").ready());
    assertFalse(task(plan, "define-transfers:call").ready());
    assertEquals(Reason.WAITING_FOR_TASK, block(plan, "define-transfers:call").reason());
  }

  @Test
  void independentOutlinesAreBothReadyAndTheLowerKeyIsSelected() {
    ChainWorkDocument ready =
        WorkPlanningDocuments.accept(
            fourSteps(),
            task -> task.kind() == WorkTaskKind.LOGICAL_DESIGN || task.kind() == WorkTaskKind.SELECT_OPERATION);

    Plan plan = planner.plan(ready);

    assertEquals("define-transfers:call", plan.selected().taskKey());
    assertTrue(task(plan, "define-transfers:call").ready());
    assertTrue(task(plan, "define-transfers:local").ready());
    assertTrue(task(plan, "define-transfers:reply").ready());
    assertEquals(WorkStage.DATA_BEHAVIOR, plan.selected().stage());
  }

  @Test
  void emptyTransfersDoNotCountAsAFinishedOutline() {
    ChainWorkDocument ready =
        WorkPlanningDocuments.accept(
            fourSteps(),
            task -> task.kind() == WorkTaskKind.LOGICAL_DESIGN || task.kind() == WorkTaskKind.SELECT_OPERATION);

    Plan plan = planner.plan(ready);

    assertTrue(keys(plan).contains("define-transfers:call"));
    assertTrue(keys(plan).stream().noneMatch(key -> key.startsWith("map-transfer:")));
    assertEquals(WorkTaskPlanner.Readiness.Status.WORK_REMAINING, plan.readiness().status());
  }

  @Test
  void questionedReplyDoesNotBlockAnIndependentRequestOrBranch() {
    ChainWorkDocument mapped = mappingsReady(questionOnReply());

    Plan plan = planner.plan(mapped);

    assertTrue(task(plan, "map-transfer:to-branch").ready());
    assertTrue(task(plan, "map-transfer:to-request").ready());
    assertFalse(task(plan, "map-transfer:to-reply").ready());
    assertEquals(Reason.WAITING_FOR_INPUT, block(plan, "map-transfer:to-reply").reason());
    assertEquals("Question q-reply blocks map-transfer:to-reply.", block(plan, "map-transfer:to-reply").evidence());
    assertEquals("map-transfer:to-branch", plan.selected().taskKey());
    assertEquals(List.of("q-reply"), plan.readiness().questionIds());
    assertEquals(WorkTaskPlanner.Readiness.Status.WORK_REMAINING, plan.readiness().status());
  }

  @Test
  void consumerWaitsForUnresolvedRetainedValueAndContextDoesNotWaitForMapping() {
    ChainWorkDocument ready =
        WorkPlanningDocuments.accept(
            retainedDocument(),
            task -> task.kind() != WorkTaskKind.DESCRIBE_CONTEXT && task.kind() != WorkTaskKind.MAP_TRANSFER);

    Plan plan = planner.plan(ready);

    Task context = task(plan, "describe-context:trigger");
    Task mapping = task(plan, "map-transfer:to-request");
    assertEquals(List.of("describe-context:trigger"), keys(plan).stream().filter(key -> key.startsWith("describe-context:")).toList());
    assertFalse(context.dependencyKeys().stream().anyMatch(key -> key.startsWith("map-transfer:")));
    assertTrue(context.dependencyKeys().contains("define-transfers:call"));
    assertTrue(context.ready());
    assertFalse(mapping.ready());
    assertEquals(Reason.WAITING_FOR_TASK, block(plan, "map-transfer:to-request").reason());
    assertEquals(
        "Task map-transfer:to-request waits for describe-context:trigger.",
        block(plan, "map-transfer:to-request").evidence());
    assertEquals("describe-context:trigger", plan.selected().taskKey());
  }

  @Test
  void sharedRetainedIdPreparesContextOnce() {
    Plan plan = planner.plan(sharedRetainedDocument());

    assertEquals(1, keys(plan).stream().filter(key -> key.startsWith("describe-context:")).count());
    assertTrue(task(plan, "map-transfer:to-request").dependencyKeys().contains("describe-context:trigger"));
    assertTrue(task(plan, "map-transfer:to-other").dependencyKeys().contains("describe-context:trigger"));
  }

  @Test
  void missingRetainedInputHaltsWithTheMissingId() {
    ChainWorkDocument ready =
        WorkPlanningDocuments.accept(
            missingRetainedDocument(),
            task -> task.kind() != WorkTaskKind.MAP_TRANSFER);

    Plan plan = planner.plan(ready);

    assertNull(plan.selected());
    assertEquals(Reason.MISSING_INPUT, block(plan, "map-transfer:to-request").reason());
    assertEquals(
        "Transfer to-request requires retained value missing-id, and the document has no retained value with that id.",
        block(plan, "map-transfer:to-request").evidence());
    assertEquals(WorkTaskPlanner.Readiness.Status.HALTED, plan.readiness().status());
    assertEquals(plan, planner.plan(ready));
  }

  @Test
  void mutualSuccessEdgesHaltAsADesignCycle() {
    ChainWorkDocument acceptedLogical =
        WorkPlanningDocuments.accept(cycleDocument(), task -> task.kind() == WorkTaskKind.LOGICAL_DESIGN);

    Plan plan = planner.plan(acceptedLogical);

    assertNull(plan.selected());
    assertEquals(Reason.CYCLE, block(plan, "select-operation:a").reason());
    assertEquals(
        "Steps a and b form a design cycle. This cycle is not a loop, retry, or callback.",
        block(plan, "select-operation:a").evidence());
    assertEquals(WorkTaskPlanner.Readiness.Status.HALTED, plan.readiness().status());
  }

  @Test
  void loopRetryAndCallbackDoNotCreateADesignCycle() {
    Plan loop = planner.plan(loopDocument());
    Plan retry = planner.plan(retryDocument());
    Plan callback = planner.plan(callbackDocument());

    assertTrue(loop.blocked().stream().noneMatch(block -> block.reason() == Reason.CYCLE));
    assertTrue(retry.blocked().stream().noneMatch(block -> block.reason() == Reason.CYCLE));
    assertTrue(callback.blocked().stream().noneMatch(block -> block.reason() == Reason.CYCLE));
    assertNotNull(loop.selected());
    assertEquals(Reason.UNAVAILABLE_INPUT, block(loop, "map-transfer:to-exit").reason());
    assertEquals(
        "Retained value kept-loop is produced on step call and is not available at step reply.",
        block(loop, "map-transfer:to-exit").evidence());
    assertTrue(
        callback.blocked().stream()
            .noneMatch(
                item ->
                    item.taskKey().equals("map-transfer:to-callback") && item.reason() == Reason.UNAVAILABLE_INPUT));
  }

  @Test
  void branchOnlyRetainedValueIsUnavailableOnTheOtherBranchAndAfterTheJoin() {
    Plan plan = planner.plan(branchDocument());

    assertTrue(plan.blocked().stream().noneMatch(block -> block.reason() == Reason.CYCLE));
    assertEquals(Reason.UNAVAILABLE_INPUT, block(plan, "map-transfer:to-reply").reason());
    assertEquals(
        "Retained value kept-a is produced on step call-a and is not available at step reply.",
        block(plan, "map-transfer:to-reply").evidence());
    assertEquals(Reason.UNAVAILABLE_INPUT, block(plan, "map-transfer:to-other").reason());
    assertEquals(Reason.WAITING_FOR_TASK, block(plan, "map-transfer:to-same").reason());
  }

  @Test
  void bindingAndSourceChangesRecheckAffectedConsumersOnly() {
    ChainWorkDocument accepted = WorkPlanningDocuments.accept(twoCalls(), task -> true);
    Plan unchanged = planner.plan(accepted);
    assertEquals(WorkTaskPlanner.Readiness.Status.READY_FOR_PRESENTATION, unchanged.readiness().status());

    ChainWorkDocument relabeled = WorkPlanningDocuments.relabel(accepted, "call-a", "renamed call");
    Plan afterLabel = planner.plan(relabeled);
    assertEquals(WorkTaskState.ACCEPTED, task(afterLabel, "map-transfer:to-a").state());
    assertEquals(WorkTaskPlanner.Readiness.Status.READY_FOR_PRESENTATION, afterLabel.readiness().status());

    ChainWorkDocument rebound = replaceBindingVersion(accepted, "call-a", "2.0.0");
    Plan afterBinding = planner.plan(rebound);
    assertEquals(WorkTaskState.NEEDS_RECHECK, task(afterBinding, "select-operation:call-a").state());
    assertEquals(WorkTaskState.NEEDS_RECHECK, task(afterBinding, "map-transfer:to-a").state());
    assertEquals(WorkTaskState.ACCEPTED, task(afterBinding, "map-transfer:to-b").state());
    assertEquals(WorkTaskState.ACCEPTED, task(afterBinding, "select-operation:call-b").state());
    assertEquals("select-operation:call-a", afterBinding.selected().taskKey());

    ChainWorkDocument rehashed = replaceSourceHash(accepted, WorkPlanningDocuments.SOURCE_ID, "hash-source-changed");
    Plan afterSource = planner.plan(rehashed);
    assertEquals(WorkTaskState.NEEDS_RECHECK, task(afterSource, "logical-design:doc-1").state());
    assertEquals(WorkTaskState.NEEDS_RECHECK, task(afterSource, "map-transfer:to-a").state());
    assertEquals(WorkTaskState.ACCEPTED, task(afterSource, "map-transfer:to-b").state());
    assertNotEqualsFingerprint(unchanged, afterSource, "logical-design:doc-1");
  }

  @Test
  void duplicateTransferIdsAreBlockedInsteadOfMerged() {
    Plan plan = planner.plan(duplicateTransferDocument());

    assertEquals(Reason.DUPLICATE_KEY, block(plan, "map-transfer:same").reason());
    assertFalse(task(plan, "map-transfer:same").ready());
  }

  @Test
  void answeredQuestionDoesNotReopenAnAcceptedOwnerAndDependentsResume() {
    ChainWorkDocument accepted = WorkPlanningDocuments.accept(answeredReply(), task -> true);

    Plan plan = planner.plan(accepted);

    assertEquals(WorkTaskState.ACCEPTED, task(plan, "map-transfer:to-reply").state());
    assertTrue(plan.blocked().stream().noneMatch(item -> item.taskKey().equals("select-operation:reply")));
    assertEquals(WorkTaskPlanner.Readiness.Status.READY_FOR_PRESENTATION, plan.readiness().status());
  }

  @Test
  void schemaOrPassageChangeRechecksTheConsumer() {
    ChainWorkDocument accepted = WorkPlanningDocuments.accept(hashedCalls(), task -> true);

    ChainWorkDocument rehashed =
        replaceSchemaHash(accepted, "call-a", "request", "hash-schema-b");
    Plan afterSchema = planner.plan(rehashed);
    assertEquals(WorkTaskState.NEEDS_RECHECK, task(afterSchema, "map-transfer:to-a").state());
    assertEquals(WorkTaskState.ACCEPTED, task(afterSchema, "map-transfer:to-b").state());

    ChainWorkDocument repassaged =
        replacePassageHash(accepted, WorkPlanningDocuments.PASSAGE_ID, "hash-passage-changed");
    Plan afterPassage = planner.plan(repassaged);
    assertEquals(WorkTaskState.NEEDS_RECHECK, task(afterPassage, "map-transfer:to-a").state());
    assertEquals(WorkTaskState.ACCEPTED, task(afterPassage, "map-transfer:to-b").state());

    ChainWorkDocument reworded = replaceRequirementText(accepted, WorkPlanningDocuments.REQUIREMENT_ID, "Map the corrected order");
    Plan afterText = planner.plan(reworded);
    assertEquals(WorkTaskState.NEEDS_RECHECK, task(afterText, "map-transfer:to-a").state());
    assertEquals(WorkTaskState.ACCEPTED, task(afterText, "map-transfer:to-b").state());
    assertEquals(WorkTaskState.ACCEPTED, task(afterText, "logical-design:doc-1").state());
  }

  @Test
  void storedBindingWithoutPortHashesStillReads() throws Exception {
    String legacy =
        "{\"catalogId\":\"sys\",\"version\":\"1\",\"operationId\":\"op\",\"protocol\":\"http\",\"method\":\"POST\",\"path\":\"/op\",\"contractReferences\":[\"spec\"],\"exposedPorts\":[\"request\"]}";
    ResolvedWorkBinding binding = new ObjectMapper().readValue(legacy, ResolvedWorkBinding.class);

    assertEquals(List.of(), binding.portContentHashes());
  }

  @Test
  void findingOnAStepBlocksThatTaskAndHaltsWhenNothingElseIsReady() {
    ChainWorkDocument accepted =
        WorkPlanningDocuments.accept(singleCall(), task -> task.kind() == WorkTaskKind.LOGICAL_DESIGN);
    WorkFinding finding =
        new WorkFinding(
            "finding-1",
            "call",
            "WRONG_ACTION",
            "The call is the wrong action.",
            List.of(WorkPlanningDocuments.SOURCE_ID),
            "");
    ChainWorkDocument withFinding =
        WorkPlanningDocuments.replaceProgress(
            accepted,
            new WorkProgress(accepted.progress().tasks(), List.of(finding), List.of(), "", List.of(), List.of()));

    Plan plan = planner.plan(withFinding);

    assertFalse(task(plan, "select-operation:call").ready());
    assertEquals(Reason.ACTIVE_FINDING, block(plan, "select-operation:call").reason());
    assertEquals(
        "Finding finding-1 records an open defect on call.",
        block(plan, "select-operation:call").evidence());
    assertNull(plan.selected());
    assertEquals(WorkTaskPlanner.Readiness.Status.HALTED, plan.readiness().status());
  }

  @Test
  void openQuestionOnAPrerequisiteWithholdsDependents() {
    ChainWorkDocument accepted =
        WorkPlanningDocuments.accept(
            twoBareCalls(),
            task -> task.kind() == WorkTaskKind.LOGICAL_DESIGN || task.kind() == WorkTaskKind.SELECT_OPERATION);
    WorkQuestion question =
        new WorkQuestion(
            "q-a",
            "operation",
            "Which operation creates the task?",
            List.of(WorkPlanningDocuments.SOURCE_ID),
            "select-operation:call-a",
            QuestionSubject.unspecified(),
            List.of(),
            List.of(),
            QuestionResolution.OPEN);
    ChainWorkDocument questioned =
        WorkPlanningDocuments.replaceProgress(
            accepted,
            new WorkProgress(accepted.progress().tasks(), List.of(), List.of(question), "", List.of(), List.of()));

    Plan plan = planner.plan(questioned);

    assertFalse(task(plan, "define-transfers:call-a").ready());
    assertEquals(Reason.WAITING_FOR_TASK, block(plan, "define-transfers:call-a").reason());
    assertTrue(task(plan, "define-transfers:call-b").ready());
    assertEquals("define-transfers:call-b", plan.selected().taskKey());
  }

  @Test
  void resolvedRetainedValueDoesNotCreateAContextTask() {
    ChainWorkDocument ready =
        WorkPlanningDocuments.accept(
            resolvedRetainedDocument(),
            task -> task.kind() != WorkTaskKind.MAP_TRANSFER);

    Plan plan = planner.plan(ready);

    assertTrue(keys(plan).stream().noneMatch(key -> key.startsWith("describe-context:")));
    assertFalse(task(plan, "map-transfer:to-request").dependencyKeys().stream().anyMatch(key -> key.startsWith("describe-context:")));
    assertTrue(task(plan, "map-transfer:to-request").ready());
  }

  @Test
  void callbackCorrelationKeepsTheCallerValueAvailable() {
    Plan plan = planner.plan(callbackDocument());

    assertTrue(plan.blocked().stream().noneMatch(item -> item.reason() == Reason.CYCLE));
    assertTrue(
        plan.blocked().stream()
            .noneMatch(
                item ->
                    item.taskKey().equals("map-transfer:to-callback") && item.reason() == Reason.UNAVAILABLE_INPUT));
  }

  @Test
  void openQuestionWithholdsTheOutlineAndALaterRepair() {
    ChainWorkDocument accepted =
        WorkPlanningDocuments.accept(
            repairClosureDocument(), task -> !task.taskKey().equals("define-transfers:format"));
    WorkQuestion question =
        new WorkQuestion(
            "q-call",
            "operation",
            "Which operation creates the task?",
            List.of(WorkPlanningDocuments.SOURCE_ID),
            "select-operation:call",
            QuestionSubject.unspecified(),
            List.of(),
            List.of(),
            QuestionResolution.OPEN);
    WorkFinding finding =
        new WorkFinding(
            "finding-1",
            "rule-1",
            "WRONG_MAPPING",
            "Priority dropped the low branch.",
            List.of(WorkPlanningDocuments.SOURCE_ID),
            "");
    ChainWorkDocument questioned =
        WorkPlanningDocuments.replaceProgress(
            accepted,
            new WorkProgress(
                accepted.progress().tasks(),
                List.of(finding),
                List.of(question),
                "",
                List.of(),
                List.of()));

    Plan plan = planner.plan(questioned);

    assertEquals(Reason.WAITING_FOR_TASK, block(plan, "define-transfers:call").reason());
    assertEquals(Reason.WAITING_FOR_TASK, block(plan, "map-transfer:to-request").reason());
    assertFalse(task(plan, "repair-rule:rule-1").ready());
    assertTrue(task(plan, "define-transfers:format").ready());
  }

  @Test
  void handlerOnlyValueIsUnavailableOutsideTheHandler() {
    Plan plan = planner.plan(handlerDocument());

    assertEquals(Reason.UNAVAILABLE_INPUT, block(plan, "map-transfer:to-reply").reason());
    assertEquals(
        "Retained value kept-handler is produced on step handler and is not available at step reply.",
        block(plan, "map-transfer:to-reply").evidence());
  }

  @Test
  void handlerWithoutScopeExitKeepsTheValueOffTheSuccessPath() {
    Plan plan = planner.plan(handlerWithoutScopeExit());

    assertEquals(Reason.UNAVAILABLE_INPUT, block(plan, "map-transfer:to-reply").reason());
    assertEquals(
        "Retained value kept-handler is produced on step handler and is not available at step reply.",
        block(plan, "map-transfer:to-reply").evidence());
  }

  private static ChainWorkDocument answeredReply() {
    LogicalStep trigger =
        WorkPlanningDocuments.step(
            "trigger", StepKind.TRIGGER, WorkPlanningDocuments.binding("receive", "1.0.0"), StepData.empty());
    LogicalStep reply =
        WorkPlanningDocuments.step(
            "reply",
            StepKind.REPLY,
            WorkPlanningDocuments.binding("respond", "1.0.0"),
            new StepData(
                List.of(
                    WorkPlanningDocuments.transfer(
                        "to-reply",
                        "trigger",
                        "reply",
                        "request",
                        TransferOutcome.UNSPECIFIED,
                        MappingDecision.UNSPECIFIED,
                        List.of(WorkPlanningDocuments.rule("rule-reply", "trigger", "reply")),
                        List.of(),
                        WorkPlanningDocuments.REQUIREMENT_ID)),
                List.of(),
                WorkPlanningDocuments.covered(
                    WorkPlanningDocuments.REQUIREMENT_ID,
                    WorkPlanningDocuments.PASSAGE_ID,
                    "to-reply",
                    CoverageDisposition.ASSIGNED)),
            WorkPlanningDocuments.REQUIREMENT_ID);
    WorkQuestion question =
        new WorkQuestion(
            "q-reply",
            "field",
            "Which reply field carries the id?",
            List.of(WorkPlanningDocuments.SOURCE_ID),
            "map-transfer:to-reply",
            QuestionSubject.unspecified(),
            List.of("to-reply", "reply"),
            List.of(WorkPlanningDocuments.SOURCE_ID),
            QuestionResolution.ANSWERED);
    return WorkPlanningDocuments.replaceProgress(
        WorkPlanningDocuments.document(
            "doc-1",
            List.of(trigger, reply),
            List.of(WorkPlanningDocuments.connection("c1", "trigger", "success", "reply")),
            WorkProgress.empty()),
        new WorkProgress(List.of(), List.of(), List.of(question), "", List.of(), List.of()));
  }

  private static ChainWorkDocument hashedCalls() {
    return replaceSchemaHash(twoCalls(), "call-a", "request", "hash-schema-a");
  }

  private static ChainWorkDocument singleCall() {
    return WorkPlanningDocuments.document(
        "doc-1",
        List.of(
            WorkPlanningDocuments.step(
                "call",
                StepKind.SERVICE_CALL,
                WorkPlanningDocuments.binding("createTask", "1.0.0"),
                StepData.empty(),
                WorkPlanningDocuments.REQUIREMENT_ID)),
        List.of(),
        WorkProgress.empty());
  }

  private static ChainWorkDocument twoBareCalls() {
    return WorkPlanningDocuments.document(
        "doc-1",
        List.of(
            WorkPlanningDocuments.step(
                "call-a",
                StepKind.SERVICE_CALL,
                WorkPlanningDocuments.binding("createTask", "1.0.0"),
                StepData.empty(),
                WorkPlanningDocuments.REQUIREMENT_ID),
            WorkPlanningDocuments.step(
                "call-b",
                StepKind.SERVICE_CALL,
                WorkPlanningDocuments.binding("notify", "1.0.0"),
                StepData.empty(),
                WorkPlanningDocuments.OTHER_REQUIREMENT_ID)),
        List.of(),
        WorkProgress.empty());
  }

  private static ChainWorkDocument resolvedRetainedDocument() {
    RetainedValue kept = WorkPlanningDocuments.resolved("kept-order", "trigger");
    LogicalStep trigger =
        WorkPlanningDocuments.step(
            "trigger",
            StepKind.TRIGGER,
            WorkPlanningDocuments.binding("receive", "1.0.0"),
            new StepData(List.of(), List.of(kept), DataOutline.empty()),
            WorkPlanningDocuments.REQUIREMENT_ID);
    LogicalStep call =
        WorkPlanningDocuments.step(
            "call",
            StepKind.SERVICE_CALL,
            WorkPlanningDocuments.binding("createTask", "1.0.0"),
            new StepData(
                List.of(
                    WorkPlanningDocuments.transfer(
                        "to-request",
                        "trigger",
                        "call",
                        "request",
                        TransferOutcome.UNSPECIFIED,
                        MappingDecision.UNSPECIFIED,
                        List.of(WorkPlanningDocuments.rule("rule-1", "trigger", "call")),
                        List.of("kept-order"),
                        WorkPlanningDocuments.REQUIREMENT_ID)),
                List.of(),
                WorkPlanningDocuments.covered(
                    WorkPlanningDocuments.REQUIREMENT_ID,
                    WorkPlanningDocuments.PASSAGE_ID,
                    "to-request",
                    CoverageDisposition.ASSIGNED)),
            WorkPlanningDocuments.REQUIREMENT_ID);
    return WorkPlanningDocuments.document(
        "doc-1",
        List.of(trigger, call),
        List.of(WorkPlanningDocuments.connection("c1", "trigger", "success", "call")),
        WorkProgress.empty());
  }

  private static ChainWorkDocument repairClosureDocument() {
    LogicalStep call =
        WorkPlanningDocuments.step(
            "call",
            StepKind.SERVICE_CALL,
            WorkPlanningDocuments.binding("createTask", "1.0.0"),
            new StepData(
                List.of(
                    WorkPlanningDocuments.transfer(
                        "to-request",
                        "call",
                        "call",
                        "request",
                        TransferOutcome.UNSPECIFIED,
                        MappingDecision.UNSPECIFIED,
                        List.of(WorkPlanningDocuments.rule("rule-1", "call", "call")),
                        List.of(),
                        WorkPlanningDocuments.REQUIREMENT_ID)),
                List.of(),
                WorkPlanningDocuments.covered(
                    WorkPlanningDocuments.REQUIREMENT_ID,
                    WorkPlanningDocuments.PASSAGE_ID,
                    "to-request",
                    CoverageDisposition.ASSIGNED)),
            WorkPlanningDocuments.REQUIREMENT_ID);
    LogicalStep format =
        WorkPlanningDocuments.step(
            "format",
            StepKind.LOCAL,
            null,
            StepData.empty(),
            WorkPlanningDocuments.OTHER_REQUIREMENT_ID);
    return WorkPlanningDocuments.document("doc-1", List.of(call, format), List.of(), WorkProgress.empty());
  }

  private static ChainWorkDocument handlerDocument() {
    RetainedValue kept = WorkPlanningDocuments.resolved("kept-handler", "handler");
    LogicalStep call =
        WorkPlanningDocuments.step(
            "call", StepKind.SERVICE_CALL, WorkPlanningDocuments.binding("createTask", "1.0.0"), StepData.empty());
    LogicalStep handler =
        WorkPlanningDocuments.step(
            "handler",
            StepKind.LOCAL,
            null,
            new StepData(List.of(), List.of(kept), DataOutline.empty()));
    LogicalStep reply =
        WorkPlanningDocuments.step(
            "reply",
            StepKind.REPLY,
            WorkPlanningDocuments.binding("respond", "1.0.0"),
            new StepData(
                List.of(
                    WorkPlanningDocuments.transfer(
                        "to-reply",
                        "handler",
                        "reply",
                        "request",
                        TransferOutcome.UNSPECIFIED,
                        MappingDecision.UNSPECIFIED,
                        List.of(),
                        List.of("kept-handler"),
                        WorkPlanningDocuments.REQUIREMENT_ID)),
                List.of(),
                DataOutline.empty()),
            WorkPlanningDocuments.REQUIREMENT_ID);
    ChainWorkDocument document =
        WorkPlanningDocuments.document(
            "doc-1",
            List.of(call, handler, reply),
            List.of(
                WorkPlanningDocuments.connection("ok", "call", "success", "reply"),
                WorkPlanningDocuments.connection("fail", "call", "failure", "handler"),
                WorkPlanningDocuments.connection("handled", "handler", "success", "reply")),
            WorkProgress.empty());
    return WorkPlanningDocuments.withErrorScopes(
        document, List.of(WorkPlanningDocuments.errorScope("scope-1", "call", "call", "handler", "reply")));
  }

  private static ChainWorkDocument handlerWithoutScopeExit() {
    RetainedValue kept = WorkPlanningDocuments.resolved("kept-handler", "handler");
    LogicalStep call =
        WorkPlanningDocuments.step(
            "call", StepKind.SERVICE_CALL, WorkPlanningDocuments.binding("createTask", "1.0.0"), StepData.empty());
    LogicalStep handler =
        WorkPlanningDocuments.step(
            "handler",
            StepKind.LOCAL,
            null,
            new StepData(List.of(), List.of(kept), DataOutline.empty()));
    LogicalStep reply =
        WorkPlanningDocuments.step(
            "reply",
            StepKind.REPLY,
            WorkPlanningDocuments.binding("respond", "1.0.0"),
            new StepData(
                List.of(
                    WorkPlanningDocuments.transfer(
                        "to-reply",
                        "handler",
                        "reply",
                        "request",
                        TransferOutcome.UNSPECIFIED,
                        MappingDecision.UNSPECIFIED,
                        List.of(),
                        List.of("kept-handler"),
                        WorkPlanningDocuments.REQUIREMENT_ID)),
                List.of(),
                DataOutline.empty()),
            WorkPlanningDocuments.REQUIREMENT_ID);
    ChainWorkDocument document =
        WorkPlanningDocuments.document(
            "doc-1",
            List.of(call, handler, reply),
            List.of(
                WorkPlanningDocuments.connection("ok", "call", "success", "reply"),
                WorkPlanningDocuments.connection("fail", "call", "failure", "handler"),
                WorkPlanningDocuments.connection("handled", "handler", "success", "reply")),
            WorkProgress.empty());
    return WorkPlanningDocuments.withErrorScopes(
        document,
        List.of(
            new ErrorScopeGroup(
                "scope-1",
                "call",
                "call",
                List.of(new ErrorHandler("handler-1", "Exception", "handler", List.of("handler"))),
                "",
                List.of())));
  }

  private static ChainWorkDocument fourSteps() {
    return WorkPlanningDocuments.document(
        "doc-1",
        List.of(
            WorkPlanningDocuments.step("trigger", StepKind.TRIGGER, WorkPlanningDocuments.binding("receive", "1.0.0"), StepData.empty(), WorkPlanningDocuments.REQUIREMENT_ID),
            WorkPlanningDocuments.step("call", StepKind.SERVICE_CALL, WorkPlanningDocuments.binding("createTask", "1.0.0"), StepData.empty(), WorkPlanningDocuments.REQUIREMENT_ID),
            WorkPlanningDocuments.step("local", StepKind.LOCAL, null, StepData.empty(), WorkPlanningDocuments.REQUIREMENT_ID),
            WorkPlanningDocuments.step("format", StepKind.LOCAL, null, StepData.empty(), WorkPlanningDocuments.REQUIREMENT_ID),
            WorkPlanningDocuments.step("reply", StepKind.REPLY, WorkPlanningDocuments.binding("respond", "1.0.0"), StepData.empty(), WorkPlanningDocuments.REQUIREMENT_ID)),
        List.of(
            WorkPlanningDocuments.connection("c1", "trigger", "success", "call"),
            WorkPlanningDocuments.connection("c2", "call", "success", "local"),
            WorkPlanningDocuments.connection("c3", "local", "success", "reply")),
        WorkProgress.empty());
  }

  private static ChainWorkDocument questionOnReply() {
    LogicalStep trigger =
        WorkPlanningDocuments.step("trigger", StepKind.TRIGGER, WorkPlanningDocuments.binding("receive", "1.0.0"), StepData.empty(), WorkPlanningDocuments.REQUIREMENT_ID);
    LogicalStep call =
        WorkPlanningDocuments.step(
            "call",
            StepKind.SERVICE_CALL,
            WorkPlanningDocuments.binding("createTask", "1.0.0"),
            new StepData(
                List.of(
                    WorkPlanningDocuments.transfer(
                        "to-request",
                        "trigger",
                        "call",
                        "request",
                        TransferOutcome.UNSPECIFIED,
                        MappingDecision.UNSPECIFIED,
                        List.of(),
                        List.of(),
                        WorkPlanningDocuments.REQUIREMENT_ID)),
                List.of(),
                WorkPlanningDocuments.covered(WorkPlanningDocuments.REQUIREMENT_ID, WorkPlanningDocuments.PASSAGE_ID, "to-request", CoverageDisposition.ASSIGNED)),
            WorkPlanningDocuments.REQUIREMENT_ID);
    LogicalStep branch =
        WorkPlanningDocuments.step(
            "branch",
            StepKind.SERVICE_CALL,
            WorkPlanningDocuments.binding("notify", "1.0.0"),
            new StepData(
                List.of(
                    WorkPlanningDocuments.transfer(
                        "to-branch",
                        "trigger",
                        "branch",
                        "request",
                        TransferOutcome.UNSPECIFIED,
                        MappingDecision.UNSPECIFIED,
                        List.of(),
                        List.of(),
                        WorkPlanningDocuments.REQUIREMENT_ID)),
                List.of(),
                WorkPlanningDocuments.covered(WorkPlanningDocuments.REQUIREMENT_ID, WorkPlanningDocuments.PASSAGE_ID, "to-branch", CoverageDisposition.ASSIGNED)),
            WorkPlanningDocuments.REQUIREMENT_ID);
    LogicalStep reply =
        WorkPlanningDocuments.step(
            "reply",
            StepKind.REPLY,
            WorkPlanningDocuments.binding("respond", "1.0.0"),
            new StepData(
                List.of(
                    WorkPlanningDocuments.transfer(
                        "to-reply",
                        "trigger",
                        "reply",
                        "request",
                        TransferOutcome.UNSPECIFIED,
                        MappingDecision.UNSPECIFIED,
                        List.of(),
                        List.of(),
                        WorkPlanningDocuments.REQUIREMENT_ID)),
                List.of(),
                WorkPlanningDocuments.covered(WorkPlanningDocuments.REQUIREMENT_ID, WorkPlanningDocuments.PASSAGE_ID, "to-reply", CoverageDisposition.ASSIGNED)),
            WorkPlanningDocuments.REQUIREMENT_ID);
    WorkQuestion question =
        new WorkQuestion(
            "q-reply",
            "field",
            "Which reply field carries the id?",
            List.of(WorkPlanningDocuments.SOURCE_ID),
            "map-transfer:to-reply",
            QuestionSubject.unspecified(),
            List.of("to-reply"),
            List.of(),
            QuestionResolution.OPEN);
    return WorkPlanningDocuments.replaceProgress(
        WorkPlanningDocuments.document(
            "doc-1",
            List.of(trigger, call, branch, reply),
            List.of(
                WorkPlanningDocuments.connection("c1", "trigger", "success", "call"),
                WorkPlanningDocuments.connection("c2", "trigger", "success", "branch"),
                WorkPlanningDocuments.connection("c3", "trigger", "success", "reply")),
            WorkProgress.empty()),
        new WorkProgress(List.of(), List.of(), List.of(question), "", List.of(), List.of()));
  }

  private static ChainWorkDocument mappingsReady(ChainWorkDocument document) {
    return WorkPlanningDocuments.accept(document, task -> task.kind() != WorkTaskKind.MAP_TRANSFER);
  }

  private static ChainWorkDocument retainedDocument() {
    RetainedValue kept = WorkPlanningDocuments.unresolved("kept-order", "trigger");
    LogicalStep trigger =
        WorkPlanningDocuments.step(
            "trigger",
            StepKind.TRIGGER,
            WorkPlanningDocuments.binding("receive", "1.0.0"),
            new StepData(List.of(), List.of(kept), DataOutline.empty()),
            WorkPlanningDocuments.REQUIREMENT_ID);
    LogicalStep call =
        WorkPlanningDocuments.step(
            "call",
            StepKind.SERVICE_CALL,
            WorkPlanningDocuments.binding("createTask", "1.0.0"),
            new StepData(
                List.of(
                    WorkPlanningDocuments.transfer(
                        "to-request",
                        "trigger",
                        "call",
                        "request",
                        TransferOutcome.UNSPECIFIED,
                        MappingDecision.UNSPECIFIED,
                        List.of(),
                        List.of("kept-order"),
                        WorkPlanningDocuments.REQUIREMENT_ID)),
                List.of(),
                WorkPlanningDocuments.covered(WorkPlanningDocuments.REQUIREMENT_ID, WorkPlanningDocuments.PASSAGE_ID, "to-request", CoverageDisposition.ASSIGNED)),
            WorkPlanningDocuments.REQUIREMENT_ID);
    return WorkPlanningDocuments.document(
        "doc-1",
        List.of(trigger, call),
        List.of(WorkPlanningDocuments.connection("c1", "trigger", "success", "call")),
        WorkProgress.empty());
  }

  private static ChainWorkDocument sharedRetainedDocument() {
    RetainedValue kept = WorkPlanningDocuments.unresolved("kept-order", "trigger");
    LogicalStep trigger =
        WorkPlanningDocuments.step(
            "trigger",
            StepKind.TRIGGER,
            WorkPlanningDocuments.binding("receive", "1.0.0"),
            new StepData(List.of(), List.of(kept), DataOutline.empty()),
            WorkPlanningDocuments.REQUIREMENT_ID);
    LogicalStep call =
        WorkPlanningDocuments.step(
            "call",
            StepKind.SERVICE_CALL,
            WorkPlanningDocuments.binding("createTask", "1.0.0"),
            new StepData(
                List.of(
                    WorkPlanningDocuments.transfer(
                        "to-request",
                        "trigger",
                        "call",
                        "request",
                        TransferOutcome.UNSPECIFIED,
                        MappingDecision.UNSPECIFIED,
                        List.of(),
                        List.of("kept-order"),
                        WorkPlanningDocuments.REQUIREMENT_ID),
                    WorkPlanningDocuments.transfer(
                        "to-other",
                        "trigger",
                        "call",
                        "success",
                        TransferOutcome.SUCCESS,
                        MappingDecision.UNSPECIFIED,
                        List.of(),
                        List.of("kept-order"),
                        WorkPlanningDocuments.REQUIREMENT_ID)),
                List.of(),
                WorkPlanningDocuments.covered(WorkPlanningDocuments.REQUIREMENT_ID, WorkPlanningDocuments.PASSAGE_ID, "to-request", CoverageDisposition.ASSIGNED)),
            WorkPlanningDocuments.REQUIREMENT_ID);
    return WorkPlanningDocuments.document(
        "doc-1",
        List.of(trigger, call),
        List.of(WorkPlanningDocuments.connection("c1", "trigger", "success", "call")),
        WorkProgress.empty());
  }

  private static ChainWorkDocument missingRetainedDocument() {
    LogicalStep trigger =
        WorkPlanningDocuments.step("trigger", StepKind.TRIGGER, WorkPlanningDocuments.binding("receive", "1.0.0"), StepData.empty(), WorkPlanningDocuments.REQUIREMENT_ID);
    LogicalStep call =
        WorkPlanningDocuments.step(
            "call",
            StepKind.SERVICE_CALL,
            WorkPlanningDocuments.binding("createTask", "1.0.0"),
            new StepData(
                List.of(
                    WorkPlanningDocuments.transfer(
                        "to-request",
                        "trigger",
                        "call",
                        "request",
                        TransferOutcome.UNSPECIFIED,
                        MappingDecision.UNSPECIFIED,
                        List.of(),
                        List.of("missing-id"),
                        WorkPlanningDocuments.REQUIREMENT_ID)),
                List.of(),
                WorkPlanningDocuments.covered(WorkPlanningDocuments.REQUIREMENT_ID, WorkPlanningDocuments.PASSAGE_ID, "to-request", CoverageDisposition.ASSIGNED)),
            WorkPlanningDocuments.REQUIREMENT_ID);
    return WorkPlanningDocuments.document(
        "doc-1",
        List.of(trigger, call),
        List.of(WorkPlanningDocuments.connection("c1", "trigger", "success", "call")),
        WorkProgress.empty());
  }

  private static ChainWorkDocument cycleDocument() {
    return WorkPlanningDocuments.document(
        "doc-1",
        List.of(
            WorkPlanningDocuments.step("a", StepKind.SERVICE_CALL, null, StepData.empty(), WorkPlanningDocuments.REQUIREMENT_ID),
            WorkPlanningDocuments.step("b", StepKind.SERVICE_CALL, null, StepData.empty(), WorkPlanningDocuments.REQUIREMENT_ID)),
        List.of(
            WorkPlanningDocuments.connection("ab", "a", "success", "b"),
            WorkPlanningDocuments.connection("ba", "b", "success", "a")),
        WorkProgress.empty());
  }

  private static ChainWorkDocument loopDocument() {
    RetainedValue kept = WorkPlanningDocuments.resolved("kept-loop", "call");
    LogicalStep call =
        WorkPlanningDocuments.step(
            "call",
            StepKind.SERVICE_CALL,
            WorkPlanningDocuments.binding("createTask", "1.0.0"),
            new StepData(List.of(), List.of(kept), DataOutline.empty()),
            WorkPlanningDocuments.REQUIREMENT_ID);
    LogicalStep reply =
        WorkPlanningDocuments.step(
            "reply",
            StepKind.REPLY,
            WorkPlanningDocuments.binding("respond", "1.0.0"),
            new StepData(
                List.of(
                    WorkPlanningDocuments.transfer(
                        "to-exit",
                        "call",
                        "reply",
                        "request",
                        TransferOutcome.UNSPECIFIED,
                        MappingDecision.UNSPECIFIED,
                        List.of(),
                        List.of("kept-loop"),
                        WorkPlanningDocuments.REQUIREMENT_ID)),
                List.of(),
                WorkPlanningDocuments.covered(WorkPlanningDocuments.REQUIREMENT_ID, WorkPlanningDocuments.PASSAGE_ID, "to-exit", CoverageDisposition.ASSIGNED)),
            WorkPlanningDocuments.REQUIREMENT_ID);
    return WorkPlanningDocuments.document(
        "doc-1",
        List.of(call, reply),
        List.of(
            WorkPlanningDocuments.connection("back", "call", "success", "call"),
            WorkPlanningDocuments.connection("out", "call", "success", "reply")),
        List.of(),
        List.of(WorkPlanningDocuments.loop("loop-1", "call", "call", "reply")),
        List.of(),
        WorkProgress.empty());
  }

  private static ChainWorkDocument retryDocument() {
    return WorkPlanningDocuments.document(
        "doc-1",
        List.of(
            WorkPlanningDocuments.step("call", StepKind.SERVICE_CALL, WorkPlanningDocuments.binding("createTask", "1.0.0"), StepData.empty(), WorkPlanningDocuments.REQUIREMENT_ID),
            WorkPlanningDocuments.step("exhausted", StepKind.REPLY, WorkPlanningDocuments.binding("respond", "1.0.0"), StepData.empty(), WorkPlanningDocuments.REQUIREMENT_ID)),
        List.of(
            WorkPlanningDocuments.connection("back", "call", "failure", "call"),
            WorkPlanningDocuments.connection("out", "call", "failure", "exhausted")),
        List.of(),
        List.of(),
        List.of(WorkPlanningDocuments.retry("retry-1", "call", "call", "exhausted")),
        WorkProgress.empty());
  }

  private static ChainWorkDocument callbackDocument() {
    RetainedValue kept = WorkPlanningDocuments.resolved("kept-call", "call");
    LogicalStep call =
        WorkPlanningDocuments.step(
            "call",
            StepKind.SERVICE_CALL,
            WorkPlanningDocuments.binding("createTask", "1.0.0"),
            new StepData(List.of(), List.of(kept), DataOutline.empty()),
            WorkPlanningDocuments.REQUIREMENT_ID);
    LogicalStep callback =
        WorkPlanningDocuments.step(
            "callback",
            StepKind.TRIGGER,
            WorkPlanningDocuments.binding("callbackOp", "1.0.0"),
            new StepData(
                List.of(
                    WorkPlanningDocuments.transfer(
                        "to-callback",
                        "call",
                        "callback",
                        "payload",
                        TransferOutcome.UNSPECIFIED,
                        MappingDecision.UNSPECIFIED,
                        List.of(),
                        List.of("kept-call"),
                        WorkPlanningDocuments.REQUIREMENT_ID)),
                List.of(),
                WorkPlanningDocuments.covered(WorkPlanningDocuments.REQUIREMENT_ID, WorkPlanningDocuments.PASSAGE_ID, "to-callback", CoverageDisposition.ASSIGNED)),
            WorkPlanningDocuments.REQUIREMENT_ID);
    return WorkPlanningDocuments.document(
        "doc-1",
        List.of(call, callback),
        List.of(
            WorkPlanningDocuments.connection("corr", "call", "correlation", "callback"),
            WorkPlanningDocuments.connection("back", "callback", "success", "call")),
        WorkProgress.empty());
  }

  private static ChainWorkDocument branchDocument() {
    RetainedValue kept = WorkPlanningDocuments.resolved("kept-a", "call-a");
    LogicalStep callA =
        WorkPlanningDocuments.step(
            "call-a",
            StepKind.SERVICE_CALL,
            WorkPlanningDocuments.binding("createTask", "1.0.0"),
            new StepData(
                List.of(
                    WorkPlanningDocuments.transfer(
                        "to-same",
                        "call-a",
                        "call-a",
                        "request",
                        TransferOutcome.UNSPECIFIED,
                        MappingDecision.UNSPECIFIED,
                        List.of(),
                        List.of("kept-a"),
                        WorkPlanningDocuments.REQUIREMENT_ID)),
                List.of(kept),
                WorkPlanningDocuments.covered(WorkPlanningDocuments.REQUIREMENT_ID, WorkPlanningDocuments.PASSAGE_ID, "to-same", CoverageDisposition.ASSIGNED)),
            WorkPlanningDocuments.REQUIREMENT_ID);
    LogicalStep callB =
        WorkPlanningDocuments.step(
            "call-b",
            StepKind.SERVICE_CALL,
            WorkPlanningDocuments.binding("notify", "1.0.0"),
            new StepData(
                List.of(
                    WorkPlanningDocuments.transfer(
                        "to-other",
                        "call-a",
                        "call-b",
                        "request",
                        TransferOutcome.UNSPECIFIED,
                        MappingDecision.UNSPECIFIED,
                        List.of(),
                        List.of("kept-a"),
                        WorkPlanningDocuments.REQUIREMENT_ID)),
                List.of(),
                WorkPlanningDocuments.covered(WorkPlanningDocuments.REQUIREMENT_ID, WorkPlanningDocuments.PASSAGE_ID, "to-other", CoverageDisposition.ASSIGNED)),
            WorkPlanningDocuments.REQUIREMENT_ID);
    LogicalStep reply =
        WorkPlanningDocuments.step(
            "reply",
            StepKind.REPLY,
            WorkPlanningDocuments.binding("respond", "1.0.0"),
            new StepData(
                List.of(
                    WorkPlanningDocuments.transfer(
                        "to-reply",
                        "call-a",
                        "reply",
                        "request",
                        TransferOutcome.UNSPECIFIED,
                        MappingDecision.UNSPECIFIED,
                        List.of(),
                        List.of("kept-a"),
                        WorkPlanningDocuments.REQUIREMENT_ID)),
                List.of(),
                WorkPlanningDocuments.covered(WorkPlanningDocuments.REQUIREMENT_ID, WorkPlanningDocuments.PASSAGE_ID, "to-reply", CoverageDisposition.ASSIGNED)),
            WorkPlanningDocuments.REQUIREMENT_ID);
    LogicalStep gate =
        WorkPlanningDocuments.step("gate", StepKind.LOCAL, null, StepData.empty(), WorkPlanningDocuments.REQUIREMENT_ID);
    ConditionGroup group =
        new ConditionGroup(
            "gate-group",
            "gate",
            List.of(
                new ConditionBranch("branch-a", ConditionBranchRole.IF, "priority is high", 1, "call-a", List.of("call-a")),
                new ConditionBranch("branch-b", ConditionBranchRole.ELSE, "", 0, "call-b", List.of("call-b"))),
            "reply");
    return WorkPlanningDocuments.document(
        "doc-1",
        List.of(gate, callA, callB, reply),
        List.of(
            WorkPlanningDocuments.connection("to-a", "gate", "success", "call-a"),
            WorkPlanningDocuments.connection("to-b", "gate", "success", "call-b"),
            WorkPlanningDocuments.connection("a-join", "call-a", "success", "reply"),
            WorkPlanningDocuments.connection("b-join", "call-b", "success", "reply")),
        List.of(group),
        List.of(),
        List.of(),
        WorkProgress.empty());
  }

  private static ChainWorkDocument twoCalls() {
    LogicalStep trigger =
        WorkPlanningDocuments.step("trigger", StepKind.TRIGGER, WorkPlanningDocuments.binding("receive", "1.0.0"), StepData.empty());
    LogicalStep callA =
        WorkPlanningDocuments.step(
            "call-a",
            StepKind.SERVICE_CALL,
            WorkPlanningDocuments.binding("createTask", "1.0.0"),
            new StepData(
                List.of(
                    WorkPlanningDocuments.transfer(
                        "to-a",
                        "trigger",
                        "call-a",
                        "request",
                        TransferOutcome.UNSPECIFIED,
                        MappingDecision.UNSPECIFIED,
                        List.of(WorkPlanningDocuments.rule("rule-a", "trigger", "call-a")),
                        List.of(),
                        WorkPlanningDocuments.REQUIREMENT_ID)),
                List.of(),
                WorkPlanningDocuments.covered(WorkPlanningDocuments.REQUIREMENT_ID, WorkPlanningDocuments.PASSAGE_ID, "to-a", CoverageDisposition.ASSIGNED)),
            WorkPlanningDocuments.REQUIREMENT_ID);
    LogicalStep callB =
        WorkPlanningDocuments.step(
            "call-b",
            StepKind.SERVICE_CALL,
            WorkPlanningDocuments.binding("notify", "1.0.0"),
            new StepData(
                List.of(
                    WorkPlanningDocuments.transfer(
                        "to-b",
                        "trigger",
                        "call-b",
                        "request",
                        TransferOutcome.UNSPECIFIED,
                        MappingDecision.UNSPECIFIED,
                        List.of(WorkPlanningDocuments.rule("rule-b", "trigger", "call-b")),
                        List.of(),
                        WorkPlanningDocuments.OTHER_REQUIREMENT_ID)),
                List.of(),
                WorkPlanningDocuments.covered(WorkPlanningDocuments.OTHER_REQUIREMENT_ID, WorkPlanningDocuments.OTHER_PASSAGE_ID, "to-b", CoverageDisposition.ASSIGNED)),
            WorkPlanningDocuments.OTHER_REQUIREMENT_ID);
    ChainWorkDocument base =
        WorkPlanningDocuments.document(
            "doc-1",
            List.of(trigger, callA, callB),
            List.of(
                WorkPlanningDocuments.connection("to-a", "trigger", "success", "call-a"),
                WorkPlanningDocuments.connection("to-b", "trigger", "success", "call-b")),
            WorkProgress.empty());
    return WorkPlanningDocuments.withSources(
        base,
        List.of(
            WorkPlanningDocuments.source(WorkPlanningDocuments.SOURCE_ID, "hash-source-1", WorkPlanningDocuments.PASSAGE_ID, "Map the order."),
            WorkPlanningDocuments.source(WorkPlanningDocuments.OTHER_SOURCE_ID, "hash-source-2", WorkPlanningDocuments.OTHER_PASSAGE_ID, "Notify the team.")),
        List.of(
            new WorkRequirement(WorkPlanningDocuments.REQUIREMENT_ID, "Map the order", List.of(WorkPlanningDocuments.SOURCE_ID), ""),
            new WorkRequirement(WorkPlanningDocuments.OTHER_REQUIREMENT_ID, "Notify the team", List.of(WorkPlanningDocuments.OTHER_SOURCE_ID), "")));
  }

  private static ChainWorkDocument duplicateTransferDocument() {
    LogicalStep first =
        WorkPlanningDocuments.step(
            "call-a",
            StepKind.SERVICE_CALL,
            WorkPlanningDocuments.binding("createTask", "1.0.0"),
            new StepData(
                List.of(
                    WorkPlanningDocuments.transfer(
                        "same",
                        "call-a",
                        "call-a",
                        "request",
                        TransferOutcome.UNSPECIFIED,
                        MappingDecision.UNSPECIFIED,
                        List.of(),
                        List.of(),
                        WorkPlanningDocuments.REQUIREMENT_ID)),
                List.of(),
                DataOutline.empty()),
            WorkPlanningDocuments.REQUIREMENT_ID);
    LogicalStep second =
        WorkPlanningDocuments.step(
            "call-b",
            StepKind.SERVICE_CALL,
            WorkPlanningDocuments.binding("notify", "1.0.0"),
            new StepData(
                List.of(
                    WorkPlanningDocuments.transfer(
                        "same",
                        "call-b",
                        "call-b",
                        "request",
                        TransferOutcome.UNSPECIFIED,
                        MappingDecision.UNSPECIFIED,
                        List.of(),
                        List.of(),
                        WorkPlanningDocuments.REQUIREMENT_ID)),
                List.of(),
                DataOutline.empty()),
            WorkPlanningDocuments.REQUIREMENT_ID);
    return WorkPlanningDocuments.document("doc-1", List.of(first, second), List.of(), WorkProgress.empty());
  }

  private static ChainWorkDocument permuteAndRename(ChainWorkDocument document) {
    List<LogicalStep> steps = new ArrayList<>(document.flow().steps());
    java.util.Collections.reverse(steps);
    List<LogicalStep> renamed = new ArrayList<>();
    for (LogicalStep step : steps) {
      renamed.add(
          new LogicalStep(
              step.id(),
              step.kind(),
              "renamed-" + step.id(),
              step.intent(),
              reversed(step.sourceIds()),
              reversed(step.requirementIds()),
              reorderBinding(step.binding()),
              step.data()));
    }
    List<LogicalConnection> connections = new ArrayList<>(document.flow().connections());
    java.util.Collections.reverse(connections);
    List<WorkRequirement> requirements = new ArrayList<>(document.requirements());
    java.util.Collections.reverse(requirements);
    ChainWorkDocument replaced = WorkPlanningDocuments.replaceFlow(document, renamed, connections);
    return WorkPlanningDocuments.withSources(replaced, reversed(document.sources()), requirements);
  }

  private static ResolvedWorkBinding reorderBinding(ResolvedWorkBinding binding) {
    if (binding == null) {
      return null;
    }
    List<String> refs = new ArrayList<>(binding.contractReferences());
    java.util.Collections.reverse(refs);
    List<String> ports = new ArrayList<>(binding.exposedPorts());
    java.util.Collections.reverse(ports);
    return new ResolvedWorkBinding(
        binding.catalogId(),
        binding.version(),
        binding.operationId(),
        binding.protocol(),
        binding.method(),
        binding.path(),
        refs,
        ports);
  }

  private static ChainWorkDocument replaceBindingVersion(ChainWorkDocument document, String stepId, String version) {
    List<LogicalStep> steps = new ArrayList<>();
    for (LogicalStep step : document.flow().steps()) {
      if (step.id().equals(stepId) && step.binding() != null) {
        ResolvedWorkBinding prior = step.binding();
        steps.add(
            new LogicalStep(
                step.id(),
                step.kind(),
                step.label(),
                step.intent(),
                step.sourceIds(),
                step.requirementIds(),
                new ResolvedWorkBinding(
                    prior.catalogId(),
                    version,
                    prior.operationId(),
                    prior.protocol(),
                    prior.method(),
                    prior.path(),
                    prior.contractReferences(),
                    prior.exposedPorts()),
                step.data()));
      } else {
        steps.add(step);
      }
    }
    return WorkPlanningDocuments.replaceFlow(document, steps, document.flow().connections());
  }

  private static ChainWorkDocument replaceSchemaHash(
      ChainWorkDocument document, String stepId, String port, String contentHash) {
    List<LogicalStep> steps = new ArrayList<>();
    for (LogicalStep step : document.flow().steps()) {
      if (step.id().equals(stepId) && step.binding() != null) {
        ResolvedWorkBinding prior = step.binding();
        steps.add(
            new LogicalStep(
                step.id(),
                step.kind(),
                step.label(),
                step.intent(),
                step.sourceIds(),
                step.requirementIds(),
                new ResolvedWorkBinding(
                    prior.catalogId(),
                    prior.version(),
                    prior.operationId(),
                    prior.protocol(),
                    prior.method(),
                    prior.path(),
                    prior.contractReferences(),
                    prior.exposedPorts(),
                    List.of(new PortContentHash(port, contentHash))),
                step.data()));
      } else {
        steps.add(step);
      }
    }
    return WorkPlanningDocuments.replaceFlow(document, steps, document.flow().connections());
  }

  private static ChainWorkDocument replacePassageHash(
      ChainWorkDocument document, String passageId, String contentHash) {
    List<WorkSource> sources = new ArrayList<>();
    for (WorkSource source : document.sources()) {
      List<SourcePassage> passages = new ArrayList<>();
      for (SourcePassage passage : source.passages()) {
        if (passage.id().equals(passageId)) {
          passages.add(
              new SourcePassage(passage.id(), passage.sourceId(), contentHash, passage.text(), passage.parentHeading()));
        } else {
          passages.add(passage);
        }
      }
      sources.add(
          new WorkSource(
              source.id(),
              source.role(),
              source.contentReference(),
              source.contentHash(),
              source.originalName(),
              source.suppliedIdentifier(),
              source.correctionOf(),
              source.content(),
              passages));
    }
    return WorkPlanningDocuments.withSources(document, sources, document.requirements());
  }

  private static ChainWorkDocument replaceRequirementText(
      ChainWorkDocument document, String requirementId, String text) {
    List<WorkRequirement> requirements = new ArrayList<>();
    for (WorkRequirement requirement : document.requirements()) {
      if (requirement.id().equals(requirementId)) {
        requirements.add(
            new WorkRequirement(requirement.id(), text, requirement.sourceIds(), requirement.supersededRequirementId()));
      } else {
        requirements.add(requirement);
      }
    }
    return WorkPlanningDocuments.withSources(document, document.sources(), requirements);
  }

  private static ChainWorkDocument replaceSourceHash(ChainWorkDocument document, String sourceId, String contentHash) {
    List<WorkSource> sources = new ArrayList<>();
    for (WorkSource source : document.sources()) {
      if (source.id().equals(sourceId)) {
        sources.add(
            new WorkSource(
                source.id(),
                source.role(),
                source.contentReference(),
                contentHash,
                source.originalName(),
                source.suppliedIdentifier(),
                source.correctionOf(),
                source.content(),
                source.passages()));
      } else {
        sources.add(source);
      }
    }
    return WorkPlanningDocuments.withSources(document, sources, document.requirements());
  }

  private static void assertNotEqualsFingerprint(Plan left, Plan right, String taskKey) {
    assertFalse(task(left, taskKey).requiredInputFingerprint().equals(task(right, taskKey).requiredInputFingerprint()));
  }

  private static List<String> keys(Plan plan) {
    return plan.tasks().stream().map(Task::taskKey).toList();
  }

  private static List<List<String>> dependencies(Plan plan) {
    return plan.tasks().stream().map(Task::dependencyKeys).toList();
  }

  private static List<String> fingerprints(Plan plan) {
    return plan.tasks().stream().map(Task::requiredInputFingerprint).toList();
  }

  private static Task task(Plan plan, String taskKey) {
    return plan.tasks().stream().filter(item -> item.taskKey().equals(taskKey)).findFirst().orElseThrow();
  }

  private static Block block(Plan plan, String taskKey) {
    return plan.blocked().stream().filter(item -> item.taskKey().equals(taskKey)).findFirst().orElseThrow();
  }

  private static <T> List<T> reversed(List<T> values) {
    List<T> copy = new ArrayList<>(values);
    java.util.Collections.reverse(copy);
    return copy;
  }
}
