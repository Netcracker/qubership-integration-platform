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
  void creationAliasResolvesAndReplacementPreservesId() {
    GrownDocument grown = WorkDocumentFixture.grow(service);
    assertNotEquals("trigger", grown.triggerId());
    assertEquals(grown.triggerId(), step(grown.afterMappings(), grown.triggerId()).id());
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
        List.of());
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
