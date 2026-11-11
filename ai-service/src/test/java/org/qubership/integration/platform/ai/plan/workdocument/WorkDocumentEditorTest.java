package org.qubership.integration.platform.ai.plan.workdocument;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ConditionBranchRole;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.LoopMode;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SplitMode;

class WorkDocumentEditorTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private final WorkDocumentService service = new WorkDocumentService();

  @Test
  void changingPriorityLeavesUnassignedRulesByteForByteEqual() throws Exception {
    GrownDocument grown = WorkDocumentFixture.grow(service);
    Map<String, byte[]> before = ruleBytes(grown.afterMappings());
    WorkCommit repaired =
        service.apply(
            grown.afterMappings(),
            scope(grown.afterMappings(), List.of(grown.priorityRuleId()), false, true, false),
            WorkTaskCapture.prepared(
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(
                    new CapturedRule(
                        grown.priorityRuleId(),
                        "",
                        grown.transferId(),
                        List.of(
                            FieldReference.payload(
                                grown.triggerId(), PortRole.INBOUND_PAYLOAD, "$.priority")),
                        new FieldReference(
                            FieldReferenceKind.STEP_PORT,
                            grown.callId(),
                            PortRole.OUTBOUND_REQUEST,
                            "$.Priority",
                            ""),
                        List.of(),
                        "Map urgent to High.",
                        List.of(WorkDocumentFixture.SOURCE_ID))),
                List.of()),
            "cmd-priority");

    MappingRule priority = rule(repaired.state(), grown.priorityRuleId());
    assertEquals(grown.priorityRuleId(), priority.id());
    assertEquals("Map urgent to High.", priority.behavior());
    Map<String, byte[]> after = ruleBytes(repaired.state());
    for (String id : List.of(grown.subjectRuleId(), grown.statusRuleId())) {
      assertEquals(new String(before.get(id)), new String(after.get(id)));
    }
    assertNotEquals(new String(before.get(grown.priorityRuleId())), new String(after.get(grown.priorityRuleId())));
  }

  @Test
  void twoOccurrencesOfOneCatalogOperationKeepSeparateStableIds() {
    GrownDocument grown = WorkDocumentFixture.grow(service);
    LogicalStep first = step(grown.afterBinding(), grown.callId());
    LogicalStep second = step(grown.afterBinding(), grown.repeatCallId());
    assertNotEquals(first.id(), second.id());
    assertEquals(first.binding().operationId(), second.binding().operationId());
    assertEquals(grown.callId(), step(grown.afterMappings(), grown.callId()).id());
    assertEquals(grown.repeatCallId(), step(grown.afterMappings(), grown.repeatCallId()).id());
  }

  @Test
  void missingFutureBindingAndMappingIsAllowed() {
    WorkDocumentState created =
        service.read(
            WorkDocumentState.create(
                "doc-1",
                List.of(
                    new WorkSource(
                        WorkDocumentFixture.SOURCE_ID,
                        "request",
                        "artifact://request",
                        "hash-request",
                        "request.md",
                        "REQ-1",
                        List.of()))));
    WorkCommit flow =
        service.apply(
            created,
            scope(created, List.of(), true, false, false),
            WorkTaskCapture.prepared(
                List.of(new CapturedRequirement("", "req", "Notify WFM", List.of(WorkDocumentFixture.SOURCE_ID), "")),
                List.of(
                    new CapturedStep("", "trigger", StepKind.TRIGGER, "Start", "Receive the order", List.of(), List.of("req")),
                    new CapturedStep("", "call", StepKind.SERVICE_CALL, "Create task", "Create a WFM task", List.of(), List.of("req"))),
                List.of(new CapturedConnection("", "link", "trigger", "success", "call", "Then create the task", List.of(WorkDocumentFixture.SOURCE_ID))),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()),
            "cmd-flow");

    LogicalStep call = flow.state().document().flow().steps().get(1);
    assertNull(call.binding());
    assertTrue(call.data().transfers().isEmpty());
    assertEquals(2, flow.state().document().flow().steps().size());
  }

  @Test
  void malformedExistingReferenceIsRejected() {
    WorkDocumentState created = service.read(WorkDocumentState.create("doc-1"));
    WorkDocumentRejectedException rejected =
        assertThrows(
            WorkDocumentRejectedException.class,
            () ->
                service.apply(
                    created,
                    scope(created, List.of(), true, false, false),
                    WorkTaskCapture.prepared(
                        List.of(),
                        List.of(new CapturedStep("", "only", StepKind.LOCAL, "Check", "Validate", List.of(), List.of())),
                        List.of(
                            new CapturedConnection(
                                "", "link", "only", "success", "missing-step", "Continue", List.of())),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of()),
                    "cmd-bad-ref"));
    assertEquals("MALFORMED_REFERENCE", rejected.code());
  }

  @Test
  void modelAttemptsToWriteServerOwnedFieldsFail() {
    WorkDocumentRejectedException rejected =
        assertThrows(
            WorkDocumentRejectedException.class,
            () ->
                WorkDocumentCaptureSchema.parse(
                    """
                    {"outcome":"PREPARED","steps":[{"alias":"a","kind":"LOCAL","label":"L","intent":"I","id":"model-id"}]}
                    """));
    assertEquals("SERVER_OWNED_FIELD", rejected.code());
  }

  @Test
  void controlRegionsSaveWithoutCatalogCalls() {
    WorkDocumentState created = service.read(WorkDocumentState.create("doc-1"));
    WorkCommit saved =
        service.apply(
            created,
            scope(created, List.of(), true, false, false),
            WorkTaskCapture.prepared(
                List.of(),
                List.of(
                    new CapturedStep("", "gate", StepKind.LOCAL, "Gate", "Branch on amount", List.of(), List.of()),
                    new CapturedStep("", "fan", StepKind.LOCAL, "Fan", "Split the batch", List.of(), List.of()),
                    new CapturedStep("", "repeat", StepKind.LOCAL, "Repeat", "Walk each item", List.of(), List.of()),
                    new CapturedStep("", "again", StepKind.LOCAL, "Again", "Retry the call", List.of(), List.of()),
                    new CapturedStep("", "guard", StepKind.LOCAL, "Guard", "Catch failures", List.of(), List.of()),
                    new CapturedStep("", "body", StepKind.LOCAL, "Body", "Local work", List.of(), List.of())),
                List.of(),
                List.of(new CapturedSequenceGroup("", "seq", List.of("body"))),
                List.of(
                    new CapturedConditionGroup(
                        "",
                        "cond",
                        "gate",
                        List.of(
                            new CapturedConditionBranch(
                                "", "when", ConditionBranchRole.IF, "$.amount > 0", 1, "body", List.of("body")),
                            new CapturedConditionBranch(
                                "", "else", ConditionBranchRole.ELSE, "", 2, "body", List.of("body"))),
                        "")),
                List.of(
                    new CapturedSplitGroup(
                        "", "split", "fan", SplitMode.SYNC, List.of(new CapturedSplitBranch("", "arm", 1, "body", List.of("body"))), "")),
                List.of(new CapturedLoopGroup("", "loop", "repeat", "body", List.of("body"), "body", LoopMode.COPY, "$.items", 10)),
                List.of(new CapturedRetryGroup("", "retry", "again", "body", List.of("body"), "body", 3, 100)),
                List.of(
                    new CapturedErrorScopeGroup(
                        "",
                        "err",
                        "guard",
                        "body",
                        List.of(new CapturedErrorHandler("", "catch", "java.lang.RuntimeException", "body", List.of("body"))),
                        "",
                        List.of("body"))),
                List.of(),
                List.of(),
                List.of()),
            "cmd-regions");

    LogicalFlow flow = saved.state().document().flow();
    assertEquals(1, flow.conditionGroups().size());
    assertEquals(1, flow.splitGroups().size());
    assertEquals(1, flow.loopGroups().size());
    assertEquals(1, flow.retryGroups().size());
    assertEquals(1, flow.errorScopeGroups().size());
    assertTrue(flow.steps().stream().allMatch(step -> step.kind() == StepKind.LOCAL && step.binding() == null));
  }

  @Test
  void staleScopeIsRejected() {
    GrownDocument grown = WorkDocumentFixture.grow(service);
    WorkDocumentRejectedException rejected =
        assertThrows(
            WorkDocumentRejectedException.class,
            () ->
                service.apply(
                    grown.afterMappings(),
                    scope(grown.afterFlow(), List.of(grown.priorityRuleId()), false, true, false),
                    WorkTaskCapture.prepared(
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of()),
                    "cmd-stale"));
    assertEquals("STALE_SCOPE", rejected.code());
  }

  @Test
  void unauthorizedDeleteIsRejected() {
    GrownDocument grown = WorkDocumentFixture.grow(service);
    WorkDocumentRejectedException rejected =
        assertThrows(
            WorkDocumentRejectedException.class,
            () ->
                service.apply(
                    grown.afterMappings(),
                    scope(grown.afterMappings(), List.of(grown.priorityRuleId()), false, true, false),
                    WorkTaskCapture.prepared(
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(new CapturedDelete(grown.subjectRuleId(), List.of(WorkDocumentFixture.SOURCE_ID)))),
                    "cmd-delete"));
    assertEquals("UNAUTHORIZED_DELETE", rejected.code());
  }

  @Test
  void omittedGroupChildrenStayUntilDeleted() {
    WorkDocumentState created = service.read(WorkDocumentState.create("doc-groups"));
    WorkCommit saved =
        service.apply(
            created,
            scope(created, List.of(), true, false, false),
            regions(),
            "cmd-groups");
    String cond = saved.aliasToId().get("cond");
    String when = saved.aliasToId().get("when");
    String elseId = saved.aliasToId().get("else");
    String gate = saved.aliasToId().get("gate");
    String body = saved.aliasToId().get("body");
    WorkCommit replaced =
        service.apply(
            saved.state(),
            scope(saved.state(), List.of(cond, when), false, true, false),
            WorkTaskCapture.prepared(
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(
                    new CapturedConditionGroup(
                        cond,
                        "",
                        gate,
                        List.of(
                            new CapturedConditionBranch(
                                when, "", ConditionBranchRole.IF, "$.amount > 1", 1, body, List.of(body))),
                        "")),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()),
            "cmd-omit-branch");
    List<String> branchIds =
        replaced.state().document().flow().conditionGroups().get(0).branches().stream()
            .map(ConditionBranch::id)
            .toList();
    assertEquals(List.of(when, elseId), branchIds);
  }

  @Test
  void retargetKeepsASinglePermanentId() {
    WorkDocumentState created = service.read(WorkDocumentState.create("doc-move"));
    WorkCommit saved =
        service.apply(
            created,
            scope(created, List.of(), true, false, false),
            WorkTaskCapture.prepared(
                List.of(),
                List.of(
                    new CapturedStep("", "left", StepKind.LOCAL, "Left", "Hold the transfer", List.of(), List.of()),
                    new CapturedStep("", "right", StepKind.LOCAL, "Right", "Receive the transfer", List.of(), List.of())),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(
                    new CapturedTransfer(
                        "",
                        "move",
                        "left",
                        List.of(new PortRef("left", "payload")),
                        new PortRef("left", "request"),
                        List.of(),
                        "")),
                List.of(
                    new CapturedRule(
                        "",
                        "rule",
                        "move",
                        List.of(FieldReference.payload("left", PortRole.INBOUND_PAYLOAD, "$.name")),
                        FieldReference.payload("left", PortRole.OUTBOUND_REQUEST, "$.Name"),
                        List.of(),
                        "Copy the name.",
                        List.of())),
                List.of()),
            "cmd-place");
    String left = saved.aliasToId().get("left");
    String right = saved.aliasToId().get("right");
    String transferId = saved.aliasToId().get("move");
    String ruleId = saved.aliasToId().get("rule");
    WorkCommit moved =
        service.apply(
            saved.state(),
            new WorkTaskScope(
                "task-1",
                saved.state().revision(),
                WorkStage.LOGICAL_FLOW,
                "skill",
                List.of(transferId, ruleId),
                false,
                true,
                false,
                List.of(),
                List.of(),
                List.of(new CreationAllowance(WorkRecordKind.TRANSFER, right)),
                List.of(transferId, ruleId),
                "task-1",
                WorkTaskKind.UNSPECIFIED,
                "",
                null),
            WorkTaskCapture.prepared(
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(
                    new CapturedTransfer(
                        transferId,
                        "",
                        right,
                        List.of(new PortRef(left, "payload")),
                        new PortRef(right, "request"),
                        List.of(),
                        "")),
                List.of(
                    new CapturedRule(
                        ruleId,
                        "",
                        transferId,
                        List.of(FieldReference.payload(left, PortRole.INBOUND_PAYLOAD, "$.name")),
                        FieldReference.payload(right, PortRole.OUTBOUND_REQUEST, "$.Name"),
                        List.of(),
                        "Copy the name.",
                        List.of())),
                List.of()),
            "cmd-retarget");
    assertEquals(1, countTransfers(moved.state(), transferId));
    assertEquals(1, countRules(moved.state(), ruleId));
    assertEquals(right, stepHoldingTransfer(moved.state(), transferId));
  }

  @Test
  void retargetedConditionBranchStaysInOneGroup() {
    WorkDocumentState created = service.read(WorkDocumentState.create("doc-move-branch"));
    WorkCommit saved =
        service.apply(
            created,
            scope(created, List.of(), true, false, false),
            WorkTaskCapture.prepared(
                List.of(),
                List.of(
                    new CapturedStep("", "gate", StepKind.LOCAL, "Gate", "Branch", List.of(), List.of()),
                    new CapturedStep("", "body", StepKind.LOCAL, "Body", "Work", List.of(), List.of())),
                List.of(),
                List.of(),
                List.of(
                    new CapturedConditionGroup(
                        "",
                        "from",
                        "gate",
                        List.of(
                            new CapturedConditionBranch(
                                "", "when", ConditionBranchRole.IF, "$.amount > 0", 1, "body", List.of("body"))),
                        ""),
                    new CapturedConditionGroup(
                        "",
                        "to",
                        "gate",
                        List.of(
                            new CapturedConditionBranch(
                                "", "other", ConditionBranchRole.ELSE, "", 2, "body", List.of("body"))),
                        "")),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()),
            "cmd-two-groups");
    String from = saved.aliasToId().get("from");
    String to = saved.aliasToId().get("to");
    String when = saved.aliasToId().get("when");
    String gate = saved.aliasToId().get("gate");
    String body = saved.aliasToId().get("body");
    WorkCommit moved =
        service.apply(
            saved.state(),
            scope(saved.state(), List.of(to, when), false, true, false),
            WorkTaskCapture.prepared(
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(
                    new CapturedConditionGroup(
                        to,
                        "",
                        gate,
                        List.of(
                            new CapturedConditionBranch(
                                when, "", ConditionBranchRole.IF, "$.amount > 1", 1, body, List.of(body))),
                        "")),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()),
            "cmd-move-branch");
    assertEquals(1, countBranches(moved.state(), when));
    assertEquals(0, branchesIn(moved.state(), from, when));
    assertEquals(1, branchesIn(moved.state(), to, when));
  }

  @Test
  void authorizedDeleteRemovesNestedBranchAndRejectsDanglingStep() {
    WorkDocumentState created =
        service.read(
            WorkDocumentState.create(
                "doc-delete",
                List.of(
                    new WorkSource(
                        WorkDocumentFixture.SOURCE_ID,
                        "request",
                        "artifact://request",
                        "hash-request",
                        "request.md",
                        "REQ-1",
                        List.of()))));
    WorkCommit saved = service.apply(created, scope(created, List.of(), true, false, false), regions(), "cmd-groups");
    String elseId = saved.aliasToId().get("else");
    String when = saved.aliasToId().get("when");
    String body = saved.aliasToId().get("body");
    WorkCommit deleted =
        service.apply(
            saved.state(),
            scope(saved.state(), List.of(elseId), false, false, true),
            WorkTaskCapture.prepared(
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(new CapturedDelete(elseId, List.of(WorkDocumentFixture.SOURCE_ID)))),
            "cmd-delete-branch");
    List<String> branchIds =
        deleted.state().document().flow().conditionGroups().get(0).branches().stream()
            .map(ConditionBranch::id)
            .toList();
    assertEquals(List.of(when), branchIds);
    assertNotEquals(saved.state().revision(), deleted.state().revision());
    WorkDocumentRejectedException dangling =
        assertThrows(
            WorkDocumentRejectedException.class,
            () ->
                service.apply(
                    deleted.state(),
                    scope(deleted.state(), List.of(body), false, false, true),
                    WorkTaskCapture.prepared(
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(new CapturedDelete(body, List.of(WorkDocumentFixture.SOURCE_ID)))),
                    "cmd-dangling"));
    assertEquals("MALFORMED_REFERENCE", dangling.code());
  }

  @Test
  void writeOutsideScopeIsRejected() {
    GrownDocument grown = WorkDocumentFixture.grow(service);
    WorkDocumentRejectedException rejected =
        assertThrows(
            WorkDocumentRejectedException.class,
            () ->
                service.apply(
                    grown.afterMappings(),
                    scope(grown.afterMappings(), List.of(grown.priorityRuleId()), false, true, false),
                    WorkTaskCapture.prepared(
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(
                            new CapturedRule(
                                grown.subjectRuleId(),
                                "",
                                grown.transferId(),
                                List.of(),
                                FieldReference.payload(grown.callId(), PortRole.OUTBOUND_REQUEST, "$.Subject"),
                                List.of(),
                                "Changed outside scope.",
                                List.of())),
                        List.of()),
                    "cmd-outside"));
    assertEquals("OUTSIDE_SCOPE", rejected.code());
  }

  @Test
  void clarificationAndDefectPersistOnProgress() {
    GrownDocument grown = WorkDocumentFixture.grow(service);
    WorkDocumentState current = grown.afterMappings();
    int steps = current.document().flow().steps().size();

    WorkCommit clarified =
        service.apply(
            current,
            scope(current, List.of(), false, false, false),
            outcome(
                WorkOutcome.NEEDS_CLARIFICATION,
                "Which failure port should the reply use?",
                "success or failure port",
                List.of(WorkDocumentFixture.SOURCE_ID),
                "",
                "",
                List.of(),
                ""),
            "cmd-clarify");

    WorkQuestion question = clarified.state().document().progress().questions().get(0);
    assertEquals("Which failure port should the reply use?", question.question());
    assertEquals("success or failure port", question.choice());
    assertEquals(List.of(WorkDocumentFixture.SOURCE_ID), question.evidenceIds());
    assertEquals(steps, clarified.state().document().flow().steps().size());

    WorkCommit defect =
        service.apply(
            clarified.state(),
            scope(clarified.state(), List.of(grown.triggerId()), false, false, false),
            outcome(
                WorkOutcome.INPUT_DEFECT,
                "",
                "",
                List.of(),
                grown.triggerId(),
                "The trigger outcome contradicts the source.",
                List.of(WorkDocumentFixture.SOURCE_ID),
                "CONTRADICTION"),
            "cmd-defect");

    WorkFinding finding = defect.state().document().progress().findings().get(0);
    assertEquals(grown.triggerId(), finding.recordRef());
    assertEquals("CONTRADICTION", finding.issueCategory());
    assertEquals(List.of(WorkDocumentFixture.SOURCE_ID), finding.evidenceIds());
    assertEquals(1, defect.state().document().progress().questions().size());

    WorkDocumentRejectedException mixed =
        assertThrows(
            WorkDocumentRejectedException.class,
            () ->
                service.apply(
                    defect.state(),
                    scope(defect.state(), List.of(), false, false, false),
                    outcome(
                        WorkOutcome.NEEDS_CLARIFICATION,
                        "Another question?",
                        "a choice",
                        List.of(WorkDocumentFixture.SOURCE_ID),
                        "",
                        "",
                        List.of(),
                        "",
                        List.of(new CapturedStep("", "extra", StepKind.LOCAL, "Extra", "No", List.of(), List.of()))),
                    "cmd-mixed"));
    assertEquals("CONTRADICTORY_OUTCOME", mixed.code());
  }

  @Test
  void creationAliasResolvesAndReplacementPreservesId() {
    GrownDocument grown = WorkDocumentFixture.grow(service);
    assertNotEquals("trigger", grown.triggerId());
    assertEquals(grown.triggerId(), step(grown.afterMappings(), grown.triggerId()).id());
  }

  private static WorkTaskCapture outcome(
      WorkOutcome outcome,
      String question,
      String choice,
      List<String> clarificationEvidence,
      String defectRecordRef,
      String contradiction,
      List<String> defectEvidence,
      String issueCategory) {
    return outcome(
        outcome,
        question,
        choice,
        clarificationEvidence,
        defectRecordRef,
        contradiction,
        defectEvidence,
        issueCategory,
        List.of());
  }

  private static WorkTaskCapture outcome(
      WorkOutcome outcome,
      String question,
      String choice,
      List<String> clarificationEvidence,
      String defectRecordRef,
      String contradiction,
      List<String> defectEvidence,
      String issueCategory,
      List<CapturedStep> steps) {
    return new WorkTaskCapture(
        outcome,
        List.of(),
        steps,
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        question,
        choice,
        clarificationEvidence,
        defectRecordRef,
        contradiction,
        defectEvidence,
        issueCategory);
  }

  private static WorkTaskCapture regions() {
    return WorkTaskCapture.prepared(
        List.of(),
        List.of(
            new CapturedStep("", "gate", StepKind.LOCAL, "Gate", "Branch on amount", List.of(), List.of()),
            new CapturedStep("", "body", StepKind.LOCAL, "Body", "Local work", List.of(), List.of())),
        List.of(),
        List.of(new CapturedSequenceGroup("", "seq", List.of("body"))),
        List.of(
            new CapturedConditionGroup(
                "",
                "cond",
                "gate",
                List.of(
                    new CapturedConditionBranch(
                        "", "when", ConditionBranchRole.IF, "$.amount > 0", 1, "body", List.of("body")),
                    new CapturedConditionBranch(
                        "", "else", ConditionBranchRole.ELSE, "", 2, "body", List.of("body"))),
                "")),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of());
  }

  private static int countTransfers(WorkDocumentState state, String id) {
    int count = 0;
    for (LogicalStep step : state.document().flow().steps()) {
      for (DataTransfer transfer : step.data().transfers()) {
        if (transfer.id().equals(id)) {
          count++;
        }
      }
    }
    return count;
  }

  private static int countRules(WorkDocumentState state, String id) {
    int count = 0;
    for (LogicalStep step : state.document().flow().steps()) {
      for (DataTransfer transfer : step.data().transfers()) {
        for (MappingRule rule : transfer.rules()) {
          if (rule.id().equals(id)) {
            count++;
          }
        }
      }
    }
    return count;
  }

  private static int countBranches(WorkDocumentState state, String branchId) {
    int count = 0;
    for (ConditionGroup group : state.document().flow().conditionGroups()) {
      for (ConditionBranch branch : group.branches()) {
        if (branch.id().equals(branchId)) {
          count++;
        }
      }
    }
    return count;
  }

  private static int branchesIn(WorkDocumentState state, String groupId, String branchId) {
    for (ConditionGroup group : state.document().flow().conditionGroups()) {
      if (!group.id().equals(groupId)) {
        continue;
      }
      int count = 0;
      for (ConditionBranch branch : group.branches()) {
        if (branch.id().equals(branchId)) {
          count++;
        }
      }
      return count;
    }
    return 0;
  }

  private static String stepHoldingTransfer(WorkDocumentState state, String id) {
    for (LogicalStep step : state.document().flow().steps()) {
      for (DataTransfer transfer : step.data().transfers()) {
        if (transfer.id().equals(id)) {
          return step.id();
        }
      }
    }
    throw new AssertionError(id);
  }

  private static WorkTaskScope scope(
      WorkDocumentState state, List<String> owned, boolean create, boolean replace, boolean delete) {
    return new WorkTaskScope(
        "task-1",
        state.revision(),
        WorkStage.LOGICAL_FLOW,
        "skill",
        owned,
        create,
        replace,
        delete,
        List.of(),
        List.of(),
        create ? CreationAllowance.anyParent(WorkRecordKind.values()) : List.of(),
        replace ? owned : List.of(),
        "task-1",
        WorkTaskKind.UNSPECIFIED,
        "",
        null);
  }

  private static Map<String, byte[]> ruleBytes(WorkDocumentState state) throws Exception {
    java.util.LinkedHashMap<String, byte[]> bytes = new java.util.LinkedHashMap<>();
    for (LogicalStep step : state.document().flow().steps()) {
      for (DataTransfer transfer : step.data().transfers()) {
        for (MappingRule rule : transfer.rules()) {
          bytes.put(rule.id(), JSON.writeValueAsBytes(rule));
        }
      }
    }
    return bytes;
  }

  private static MappingRule rule(WorkDocumentState state, String id) {
    for (LogicalStep step : state.document().flow().steps()) {
      for (DataTransfer transfer : step.data().transfers()) {
        for (MappingRule rule : transfer.rules()) {
          if (rule.id().equals(id)) {
            return rule;
          }
        }
      }
    }
    throw new AssertionError(id);
  }

  private static LogicalStep step(WorkDocumentState state, String id) {
    return state.document().flow().steps().stream()
        .filter(candidate -> candidate.id().equals(id))
        .findFirst()
        .orElseThrow();
  }
}
