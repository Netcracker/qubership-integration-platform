package org.qubership.integration.platform.ai.plan.workdocument;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.compiler.artifact.ArtifactBlobStore;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.qubership.integration.platform.ai.compiler.artifact.StaleBlobVersionException;
import org.qubership.integration.platform.ai.compiler.artifact.VersionedBlob;
import org.qubership.integration.platform.ai.productpipeline.store.CommandPayloadConflictException;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunDocument;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunStore;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunStore.ProviderDeliveryOutcome;
import org.qubership.integration.platform.ai.productpipeline.store.RunSnapshot;
import org.qubership.integration.platform.ai.productpipeline.store.RunStatus;
import org.qubership.integration.platform.ai.productpipeline.store.RunTransition;
import org.qubership.integration.platform.ai.productpipeline.store.StageSnapshot;
import org.qubership.integration.platform.ai.productpipeline.store.StageStatus;

class WorkFillingStateTest {

  private static final Instant FIXED = Instant.parse("2026-09-25T12:00:00Z");
  private static final String RUN_ID = "run-filling-1";
  private static final String SOURCE_TEXT = "Map priority from the trigger payload.";
  private static final ObjectMapper JSON = new ObjectMapper();

  private InMemoryArtifactBlobStore blobStore;
  private CompilationArtifacts artifacts;
  private ProductPipelineRunStore runs;
  private WorkDocumentService documents;

  @BeforeEach
  void setUp() {
    ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    blobStore = new InMemoryArtifactBlobStore();
    Clock clock = Clock.fixed(FIXED, ZoneOffset.UTC);
    artifacts = new CompilationArtifacts(blobStore, mapper, clock);
    runs = new ProductPipelineRunStore(blobStore, mapper, clock);
    documents = new WorkDocumentService(runs, artifacts, mapper);
    runs.create(openRun(RUN_ID));
  }

  @Test
  void newDocumentsUseSchemaVersion2() {
    assertEquals(2, ChainWorkDocument.SCHEMA_VERSION);
    assertEquals(2, WorkDocumentState.create("doc-1").document().schemaVersion());
  }

  @Test
  void mappingScopeRejectsARuleUnderAnotherTransferWhenCreationIsEnabled() {
    WorkDocumentState state = mappingDocument();
    byte[] before = bytes(state.document());
    WorkTaskScope scope = mappingScope(state, "transfer-a");
    WorkDocumentRejectedException rejected =
        assertThrows(
            WorkDocumentRejectedException.class,
            () -> documents.apply(state, scope, ruleCapture("foreign", "transfer-b", "call-b"), "cmd-foreign"));

    assertEquals("OUTSIDE_SCOPE", rejected.code());
    assertEquals(before.length, bytes(state.document()).length);
    WorkCommit owned =
        documents.apply(state, scope, ruleCapture("owned", "transfer-a", "call-a"), "cmd-owned");
    assertEquals("transfer-a", transferOfRule(owned.state(), owned.aliasToId().get("owned")));
  }

  @Test
  void mappingResultCannotCreateStepsRetainedValuesOrAnotherTransfer() {
    WorkDocumentState state = mappingDocument();
    WorkTaskScope scope = mappingScope(state, "transfer-a");

    assertEquals(
        "OUTSIDE_SCOPE",
        assertThrows(
                WorkDocumentRejectedException.class,
                () -> documents.apply(state, scope, stepCapture("extra-step"), "cmd-step"))
            .code());
    assertEquals(
        "OUTSIDE_SCOPE",
        assertThrows(
                WorkDocumentRejectedException.class,
                () -> documents.apply(state, scope, retainedCapture("extra-value", "trigger"), "cmd-retained"))
            .code());
    assertEquals(
        "OUTSIDE_SCOPE",
        assertThrows(
                WorkDocumentRejectedException.class,
                () -> documents.apply(state, scope, transferCapture("extra-transfer", "call-b"), "cmd-transfer"))
            .code());
    assertEquals(
        List.of("trigger", "call-a", "call-b"),
        state.document().flow().steps().stream().map(LogicalStep::id).toList());
    assertTrue(retained(state, "kept-context").isPresent());
  }

  @Test
  void partialRetainedPlaceholderCannotSatisfyAConsumer() {
    RetainedValue partial =
        new RetainedValue(
            "task-id",
            null,
            "Salesforce task id",
            List.of("source-1"),
            "call-a",
            RetainedResolution.UNRESOLVED);
    RetainedValue resolved =
        new RetainedValue(
            "order-id",
            FieldReference.payload("trigger", PortRole.INBOUND_PAYLOAD, "$.orderId"),
            "Order id",
            List.of("source-1"),
            "trigger",
            RetainedResolution.RESOLVED);
    DataTransfer waiting =
        new DataTransfer(
            "transfer-reply",
            List.of(new PortRef("call-a", "success")),
            new PortRef("reply", "request"),
            List.of("req-1"),
            List.of(),
            MappingDecision.UNSPECIFIED,
            TransferOutcome.SUCCESS,
            List.of("task-id"));
    ChainWorkDocument open = documentWith(partial, resolved, waiting);

    assertFalse(partial.satisfiesConsumer());
    assertFalse(waiting.requiredRetainedSatisfied(open));
    DataTransfer ready =
        new DataTransfer(
            waiting.id(),
            waiting.sourcePorts(),
            waiting.targetPort(),
            waiting.requirementIds(),
            waiting.rules(),
            waiting.decision(),
            waiting.outcome(),
            List.of("order-id"));
    assertTrue(resolved.satisfiesConsumer());
    assertTrue(ready.requiredRetainedSatisfied(documentWith(partial, resolved, ready)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RetainedValue(
                "fake",
                FieldReference.payload("trigger", PortRole.INBOUND_PAYLOAD, ""),
                "purpose",
                List.of(),
                "trigger",
                RetainedResolution.RESOLVED));
  }

  @Test
  void identicalLabelsStayDistinctAndDoNotAuthorizeCrossStepEdits() {
    WorkDocumentState state = duplicateLabelDocument();
    assertEquals(2, stepsNamed(state, "Create task").size());
    String first = stepsNamed(state, "Create task").get(0);
    String second = stepsNamed(state, "Create task").get(1);
    assertNotEquals(first, second);

    WorkCommit edited =
        documents.apply(
            state,
            replaceStepScope(state, first),
            WorkTaskCapture.prepared(
                List.of(),
                List.of(
                    new CapturedStep(
                        first,
                        "",
                        StepKind.SERVICE_CALL,
                        "Create task",
                        "First call only",
                        List.of("source-1"),
                        List.of())),
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
            "cmd-edit-first");

    assertEquals("First call only", step(edited.state(), first).intent());
    assertEquals("Second call", step(edited.state(), second).intent());
    assertEquals(
        "OUTSIDE_SCOPE",
        assertThrows(
                WorkDocumentRejectedException.class,
                () ->
                    documents.apply(
                        state,
                        replaceStepScope(state, first),
                        WorkTaskCapture.prepared(
                            List.of(),
                            List.of(
                                new CapturedStep(
                                    second,
                                    "",
                                    StepKind.SERVICE_CALL,
                                    "Create task",
                                    "Overwrite the other call",
                                    List.of("source-1"),
                                    List.of())),
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
                        "cmd-cross"))
            .code());
  }

  @Test
  void stateChangesKeepOneCurrentTaskAndRejectedAttemptsStayInspectable() {
    WorkDocumentState initial = mappingDocument();
    documents.intake(RUN_ID, initial, "cmd-intake", new WorkRepairBudget(3));
    WorkDocumentState current = documents.read(RUN_ID);
    WorkTaskScope scope = mappingScope(current, "transfer-a");
    documents.apply(RUN_ID, scope, ruleCapture("owned", "transfer-a", "call-a"), "cmd-accept");
    WorkDocumentState accepted = documents.read(RUN_ID);
    WorkTaskScope recheck =
        scopeWithState(accepted, scope.taskKey(), WorkTaskKind.MAP_TRANSFER, "fingerprint-2");
    documents.recordQuestion(
        RUN_ID,
        recheck,
        "Which priority scale?",
        QuestionSubject.fieldRelationship(
            new QuestionFieldRef("trigger", "payload", "$.priority", ""),
            new QuestionFieldRef("call-a", "request", "$.Priority", "")),
        List.of("transfer-a"),
        List.of("source-1"),
        "cmd-question");

    List<WorkTaskRecord> tasks = documents.read(RUN_ID).document().progress().tasks();
    assertEquals(1, tasks.size());
    assertEquals(scope.taskKey(), tasks.get(0).taskKey());
    assertEquals(WorkTaskKind.MAP_TRANSFER, tasks.get(0).kind());
    assertEquals(WorkTaskState.NEEDS_INPUT, tasks.get(0).state());
    assertEquals("fingerprint-1", tasks.get(0).acceptedInputFingerprint());
    WorkQuestion question = documents.read(RUN_ID).document().progress().questions().get(0);
    assertEquals(QuestionChoiceKind.FIELD_RELATIONSHIP, question.subject().choiceKind());
    assertEquals("$.priority", question.subject().source().fieldPath());
    assertEquals("$.Priority", question.subject().target().fieldPath());
    assertEquals(List.of("transfer-a"), question.blockedRecordIds());

    WorkDocumentState beforeReject = documents.read(RUN_ID);
    assertThrows(
        WorkDocumentRejectedException.class,
        () ->
            documents.apply(
                RUN_ID,
                mappingScope(beforeReject, "transfer-a"),
                ruleCapture("foreign", "transfer-b", "call-b"),
                "cmd-reject"));

    ProductPipelineRunDocument run = runs.load(RUN_ID).orElseThrow();
    assertEquals(beforeReject.revision(), documents.read(RUN_ID).revision());
    assertEquals(1, documents.read(RUN_ID).document().progress().tasks().size());
    assertTrue(
        run.transitions().stream().anyMatch(transition -> transition.reason().startsWith("rejected-attempt:")));
    assertTrue(run.transitions().stream().anyMatch(transition -> "cmd-accept".equals(transition.commandId())));
  }

  @Test
  void questionPublicationIsAtomicAndInputDeliveryIsIdempotent() {
    documents.intake(RUN_ID, mappingDocument(), "cmd-intake", new WorkRepairBudget(3));
    WorkDocumentState current = documents.read(RUN_ID);
    documents.recordQuestion(
        RUN_ID,
        mappingScope(current, "transfer-a"),
        "Which field supplies processId?",
        QuestionSubject.fieldRelationship(
            new QuestionFieldRef("trigger", "payload", "$.processInstanceId", ""),
            new QuestionFieldRef("call-a", "request", "$.processId", "")),
        List.of("transfer-a"),
        List.of("source-1"),
        "cmd-question");
    String questionId = documents.read(RUN_ID).document().progress().questions().get(0).id();
    String answer = "processId is the trigger processInstanceId.";
    WorkCommit first = documents.acceptInput(RUN_ID, questionId, "answer-1", answer);
    WorkCommit replay = documents.acceptInput(RUN_ID, questionId, "answer-1", answer);

    assertEquals(first.documentRevision(), replay.documentRevision());
    assertEquals(first.runRevision(), replay.runRevision());
    WorkQuestion answered = question(documents.read(RUN_ID), questionId);
    assertEquals(QuestionResolution.ANSWERED, answered.resolution());
    assertNotEquals(QuestionResolution.RESOLVED, answered.resolution());
    assertEquals(1, answered.answerSourceIds().size());
    WorkSource stored = source(documents.read(RUN_ID), answered.answerSourceIds().get(0));
    assertEquals(answer, stored.content());
    assertEquals("answer-1", stored.suppliedIdentifier());
    assertEquals(WorkTaskState.NEEDS_RECHECK, documents.read(RUN_ID).document().progress().tasks().get(0).state());
    assertThrows(
        CommandPayloadConflictException.class,
        () -> documents.acceptInput(RUN_ID, questionId, "answer-1", "A different answer."));

    String otherRun = "run-filling-2";
    runs.create(openRun(otherRun));
    documents.intake(otherRun, mappingDocument(), "cmd-intake-2", new WorkRepairBudget(3));
    WorkDocumentState other = documents.read(otherRun);
    documents.recordQuestion(
        otherRun,
        mappingScope(other, "transfer-a"),
        "Which field supplies processId?",
        QuestionSubject.unspecified(),
        List.of(),
        List.of("source-1"),
        "cmd-question-2");
    String otherQuestion = documents.read(otherRun).document().progress().questions().get(0).id();
    documents.acceptInput(otherRun, otherQuestion, "answer-1", "Same id, other run.");
    assertEquals(answer, source(documents.read(RUN_ID), answered.answerSourceIds().get(0)).content());

    CrashBeforeCas crashing = new CrashBeforeCas(blobStore);
    WorkDocumentService failing =
        new WorkDocumentService(
            new ProductPipelineRunStore(crashing, mapper(), clock()), artifactsOn(crashing), mapper());
    crashing.failNextRunWrite = true;
    int sourcesBefore = documents.read(RUN_ID).document().sources().size();
    assertThrows(
        StaleBlobVersionException.class,
        () -> failing.acceptInput(RUN_ID, questionId, "answer-2", "Second answer that fails to link."));
    assertEquals(sourcesBefore, documents.read(RUN_ID).document().sources().size());
    assertEquals(1, question(documents.read(RUN_ID), questionId).answerSourceIds().size());
    assertTrue(artifacts.history(RUN_ID, Kind.USER_INPUT).size() >= 1);
    WorkCommit linked = documents.acceptInput(RUN_ID, questionId, "answer-2", "Second answer that fails to link.");
    assertNotEquals(first.documentRevision(), linked.documentRevision());
    assertEquals(2, question(documents.read(RUN_ID), questionId).answerSourceIds().size());
  }

  @Test
  void passagesResolveToTheirSourceAndCorrectionsPreserveHistory() {
    WorkDocumentState state = sourceDocument();
    String passageText = "Map priority";
    SourcePassage passage =
        new SourcePassage(
            "passage-1", "source-1", sha256(passageText), passageText, "Priority");
    WorkDocumentState indexed = documents.addPassages(state, "source-1", List.of(passage));
    assertEquals("source-1", documents.passageSource(indexed, "passage-1").id());
    assertEquals(SOURCE_TEXT, documents.passageSource(indexed, "passage-1").content());

    WorkDocumentRejectedException invented =
        assertThrows(
            WorkDocumentRejectedException.class,
            () -> documents.passageSource(indexed, "passage-missing"));
    assertEquals("MALFORMED_REFERENCE", invented.code());

    WorkDocumentState corrected =
        documents.appendSource(
            indexed,
            new WorkSource(
                "source-2",
                "CORRECTION",
                "artifact://correction",
                sha256("Use the numeric priority."),
                "correction.md",
                "CORR-1",
                List.of("source-1"),
                "Use the numeric priority.",
                List.of()));
    assertEquals(1, source(corrected, "source-1").passages().size());
    assertEquals("passage-1", source(corrected, "source-1").passages().get(0).id());
    assertEquals(SOURCE_TEXT, source(corrected, "source-1").content());
    assertEquals(List.of("source-1"), source(corrected, "source-2").correctionOf());
  }

  @Test
  void siblingEditsPreserveOldDataAndStalePublicationChangesNothing() {
    WorkDocumentState state = mappingDocument();
    WorkCommit first =
        documents.apply(state, mappingScope(state, "transfer-a"), ruleCapture("priority", "transfer-a", "call-a"), "cmd-priority");
    String priorityId = first.aliasToId().get("priority");
    byte[] priorityBytes = bytes(rule(first.state(), priorityId));
    WorkTaskScope statusScope =
        new WorkTaskScope(
            "map-status",
            first.state().revision(),
            WorkStage.DATA_BEHAVIOR,
            "data-mapping",
            List.of("transfer-b"),
            true,
            true,
            false,
            List.of(),
            List.of(),
            List.of(new CreationAllowance(WorkRecordKind.RULE, "transfer-b")),
            List.of(),
            "map-transfer:transfer-b",
            WorkTaskKind.MAP_TRANSFER,
            "fingerprint-b",
            new FixedTransferEndpoint("transfer-b", new PortRef("call-b", "request"), TransferOutcome.SUCCESS));
    WorkCommit second =
        documents.apply(first.state(), statusScope, ruleCapture("status", "transfer-b", "call-b"), "cmd-status");

    assertEquals(priorityBytes.length, bytes(rule(second.state(), priorityId)).length);
    assertArrayEqualsBytes(priorityBytes, bytes(rule(second.state(), priorityId)));
    assertEquals("fingerprint-1", task(first.state(), "map-transfer:transfer-a").acceptedInputFingerprint());
    assertEquals("fingerprint-1", task(second.state(), "map-transfer:transfer-a").acceptedInputFingerprint());
    assertEquals(WorkTaskState.ACCEPTED, task(second.state(), "map-transfer:transfer-b").state());

    byte[] beforeStale = bytes(second.state().document());
    WorkTaskScope stale =
        new WorkTaskScope(
            "map-transfer-a",
            state.revision(),
            WorkStage.DATA_BEHAVIOR,
            "data-mapping",
            List.of("transfer-a"),
            true,
            false,
            false,
            List.of(),
            List.of(),
            List.of(new CreationAllowance(WorkRecordKind.RULE, "transfer-a")),
            List.of(),
            "map-transfer:transfer-a",
            WorkTaskKind.MAP_TRANSFER,
            "fingerprint-stale",
            new FixedTransferEndpoint("transfer-a", new PortRef("call-a", "request"), TransferOutcome.SUCCESS));
    assertEquals(
        "STALE_SCOPE",
        assertThrows(
                WorkDocumentRejectedException.class,
                () -> documents.apply(second.state(), stale, ruleCapture("late", "transfer-a", "call-a"), "cmd-stale"))
            .code());
    assertArrayEqualsBytes(beforeStale, bytes(second.state().document()));
  }

  @Test
  void outlineHookWritesCoverageWithoutSatisfyingPartialRetainedDependencies() {
    WorkDocumentState state = targetDocument();
    WorkTaskScope scope =
        new WorkTaskScope(
            "outline-call-a",
            state.revision(),
            WorkStage.DATA_BEHAVIOR,
            "data-mapping",
            List.of("call-a"),
            true,
            false,
            false,
            List.of(),
            List.of(),
            List.of(
                new CreationAllowance(WorkRecordKind.OUTLINE, "call-a"),
                new CreationAllowance(WorkRecordKind.TRANSFER, "call-a"),
                new CreationAllowance(WorkRecordKind.RETAINED_VALUE, "trigger")),
            List.of(),
            "define-transfers:call-a",
            WorkTaskKind.DEFINE_TRANSFERS,
            "fingerprint-outline",
            null);
    OutlineProposal proposal =
        new OutlineProposal(
            "call-a",
            List.of(
                new OutlineTransfer(
                    "to-request",
                    "",
                    List.of(new PortRef("trigger", "payload")),
                    new PortRef("call-a", "request"),
                    TransferOutcome.SUCCESS,
                    List.of("req-1"),
                    List.of("task-id"),
                    "")),
            List.of(new OutlineRetained("task-id", "", "trigger", "Salesforce task id", List.of("source-1"))),
            List.of(new OutlineCoverage("req-1", "passage-1", CoverageDisposition.ASSIGNED)));

    WorkCommit committed = documents.applyOutline(state, scope, proposal, "cmd-outline");
    DataOutline outline = step(committed.state(), "call-a").data().outline();
    assertEquals(List.of("req-1"), outline.requirementIds());
    assertEquals(CoverageDisposition.ASSIGNED, outline.coverage().get(0).disposition());
    DataTransfer transfer = step(committed.state(), "call-a").data().transfers().get(0);
    assertEquals(TransferOutcome.SUCCESS, transfer.outcome());
    assertTrue(transfer.rules().isEmpty());
    assertFalse(transfer.requiredRetainedSatisfied(committed.state().document()));
    assertEquals(WorkTaskState.ACCEPTED, task(committed.state(), "define-transfers:call-a").state());
    assertEquals(
        "OUTSIDE_SCOPE",
        assertThrows(
                WorkDocumentRejectedException.class,
                () ->
                    documents.applyOutline(
                        state,
                        scope,
                        new OutlineProposal(
                            "call-b",
                            List.of(),
                            List.of(new OutlineRetained("other", "", "call-b", "Not assigned", List.of("source-1"))),
                            List.of()),
                        "cmd-other-target"))
            .code());
  }

  @Test
  void serverOwnedSubjectsAreLimitedToFlowAndOutlinePointers() {
    ChainWorkDocument document = targetDocument().document();
    assertEquals(WorkStage.LOGICAL_FLOW, ServerOwnedSubjects.require(document, "doc-map", "/flow"));
    assertEquals(WorkStage.DATA_BEHAVIOR, ServerOwnedSubjects.require(document, "call-a", "/data/outline"));
    assertEquals(
        "UNKNOWN_RECORD",
        assertThrows(
                WorkDocumentRejectedException.class,
                () -> ServerOwnedSubjects.require(document, "missing-step", "/data/outline"))
            .code());
    assertEquals(
        "UNKNOWN_RECORD",
        assertThrows(
                WorkDocumentRejectedException.class,
                () -> ServerOwnedSubjects.require(document, "doc-map", "/data/outline"))
            .code());
    assertEquals(
        "UNKNOWN_RECORD",
        assertThrows(
                WorkDocumentRejectedException.class,
                () -> ServerOwnedSubjects.require(document, "invented", "/flow"))
            .code());
  }

  @Test
  void providerDeliveryReservationsAreSeparateFromLogicalReceipts() {
    documents.intake(RUN_ID, mappingDocument(), "cmd-intake", new WorkRepairBudget(3));
    runs.reserveProviderDelivery(RUN_ID, "delivery-1");
    runs.reserveProviderDelivery(RUN_ID, "delivery-1");
    runs.recordProviderDelivery(RUN_ID, "delivery-1", ProviderDeliveryOutcome.COMPLETED);
    runs.reserveProviderDelivery(RUN_ID, "delivery-2");
    runs.recordProviderDelivery(RUN_ID, "delivery-2", ProviderDeliveryOutcome.UNCERTAIN);

    ProductPipelineRunDocument run = runs.load(RUN_ID).orElseThrow();
    assertEquals(1, countReason(run, "provider-delivery:RESERVED:delivery-1"));
    assertEquals(1, countReason(run, "provider-delivery:COMPLETED:delivery-1"));
    assertEquals(1, countReason(run, "provider-delivery:UNCERTAIN:delivery-2"));
    assertTrue(run.transitions().stream().anyMatch(transition -> "cmd-intake".equals(transition.commandId())));
    assertTrue(
        run.attempts().stream()
            .filter(attempt -> attempt.commandReceipt() != null && attempt.commandReceipt().contains("cmd-intake")
                || (attempt.commandReceipt() != null && attempt.commandReceipt().contains("acceptedRecordIds")))
            .count()
            >= 1);
    assertTrue(
        run.transitions().stream()
            .filter(transition -> transition.reason().startsWith("provider-delivery:"))
            .allMatch(transition -> transition.commandId() == null));
    assertEquals(2, runs.providerDeliveryReservations(run).size());
    assertEquals(1, runs.confirmedProviderDeliveries(run).size());
    assertEquals(1, runs.uncertainProviderDeliveries(run).size());
  }

  @Test
  void describeContextUpdatesOnlyAssignedRetainedRecords() {
    WorkDocumentState state = retainedDocument();
    WorkTaskScope scope =
        new WorkTaskScope(
            "context-trigger",
            state.revision(),
            WorkStage.DATA_BEHAVIOR,
            "data-mapping",
            List.of("order-id"),
            false,
            true,
            false,
            List.of(),
            List.of(),
            List.of(new CreationAllowance(WorkRecordKind.RETAINED_VALUE, "trigger")),
            List.of("order-id"),
            "describe-context:trigger",
            WorkTaskKind.DESCRIBE_CONTEXT,
            "fingerprint-context",
            null);
    WorkCommit resolved =
        documents.apply(
            state,
            scope,
            new WorkTaskCapture(
                WorkOutcome.PREPARED,
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
                List.of(
                    new CapturedRetainedValue(
                        "order-id",
                        "",
                        "trigger",
                        FieldReference.payload("trigger", PortRole.INBOUND_PAYLOAD, "$.orderId"),
                        "Order id",
                        List.of("source-1"))),
                List.of(),
                "",
                "",
                List.of(),
                "",
                "",
                List.of(),
                ""),
            "cmd-context");
    assertTrue(retained(resolved.state(), "order-id").orElseThrow().satisfiesConsumer());
    assertEquals(
        "OUTSIDE_SCOPE",
        assertThrows(
                WorkDocumentRejectedException.class,
                () ->
                    documents.apply(
                        state,
                        scope,
                        new WorkTaskCapture(
                            WorkOutcome.PREPARED,
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
                            List.of(
                                new CapturedRetainedValue(
                                    "task-id",
                                    "",
                                    "call-a",
                                    FieldReference.payload("call-a", PortRole.SUCCESS_RESPONSE, "$.id"),
                                    "Task id",
                                    List.of("source-1"))),
                            List.of(),
                            "",
                            "",
                            List.of(),
                            "",
                            "",
                            List.of(),
                            ""),
                        "cmd-other-retained"))
            .code());
  }

  @Test
  void outlineTransferOnAnotherStepIsRejected() {
    WorkDocumentState state = targetDocument();
    WorkTaskScope scope = outlineScope(state);

    assertEquals(
        "OUTSIDE_SCOPE",
        assertThrows(
                WorkDocumentRejectedException.class,
                () ->
                    documents.applyOutline(
                        state,
                        scope,
                        outlineProposal(
                            "call-a",
                            new OutlineTransfer(
                                "to-other",
                                "",
                                List.of(new PortRef("trigger", "payload")),
                                new PortRef("call-b", "request"),
                                TransferOutcome.SUCCESS,
                                List.of("req-1"),
                                List.of(),
                                "")),
                        "cmd-other-step"))
            .code());
    assertEquals(
        "MALFORMED_REFERENCE",
        assertThrows(
                WorkDocumentRejectedException.class,
                () ->
                    documents.applyOutline(
                        state,
                        scope,
                        outlineProposal(
                            "call-a",
                            new OutlineTransfer(
                                "missing-port",
                                "",
                                List.of(new PortRef("trigger", "payload")),
                                null,
                                TransferOutcome.SUCCESS,
                                List.of("req-1"),
                                List.of(),
                                "")),
                        "cmd-null-port"))
            .code());
    assertTrue(step(state, "call-a").data().transfers().isEmpty());
    assertTrue(step(state, "call-b").data().transfers().isEmpty());
  }

  @Test
  void outlineCannotReuseAStepOrRuleId() {
    WorkDocumentState state = mappingDocument();
    WorkTaskScope scope = outlineScope(state);

    assertEquals(
        "MALFORMED_REFERENCE",
        assertThrows(
                WorkDocumentRejectedException.class,
                () ->
                    documents.applyOutline(
                        state,
                        scope,
                        outlineProposal(
                            "call-a",
                            new OutlineTransfer(
                                "",
                                "call-a",
                                List.of(new PortRef("trigger", "payload")),
                                new PortRef("call-a", "request"),
                                TransferOutcome.SUCCESS,
                                List.of("req-1"),
                                List.of(),
                                "")),
                        "cmd-step-id"))
            .code());
    assertEquals(
        "MALFORMED_REFERENCE",
        assertThrows(
                WorkDocumentRejectedException.class,
                () ->
                    documents.applyOutline(
                        state,
                        scope,
                        outlineProposal(
                            "call-a",
                            new OutlineTransfer(
                                "",
                                "kept-rule",
                                List.of(new PortRef("trigger", "payload")),
                                new PortRef("call-a", "request"),
                                TransferOutcome.SUCCESS,
                                List.of("req-1"),
                                List.of(),
                                "")),
                        "cmd-rule-id"))
            .code());
    assertEquals(
        "MALFORMED_REFERENCE",
        assertThrows(
                WorkDocumentRejectedException.class,
                () ->
                    documents.applyOutline(
                        state,
                        scope,
                        new OutlineProposal(
                            "call-a",
                            List.of(),
                            List.of(
                                new OutlineRetained(
                                    "", "kept-rule", "trigger", "Reused rule id", List.of("source-1"))),
                            List.of()),
                        "cmd-retained-id"))
            .code());
    assertEquals(List.of("transfer-a"), transferIds(state, "call-a"));
    assertTrue(retained(state, "kept-rule").isEmpty());
  }

  @Test
  void fixedEndpointRejectsAPortlessOrRetainedTarget() {
    WorkDocumentState state = mappingDocument();
    WorkTaskScope scope = mappingScope(state, "transfer-a");

    assertEquals(
        "OUTSIDE_SCOPE",
        assertThrows(
                WorkDocumentRejectedException.class,
                () ->
                    documents.apply(
                        state,
                        scope,
                        ruleWithTarget(
                            "",
                            "portless",
                            "transfer-a",
                            new FieldReference(
                                FieldReferenceKind.STEP_PORT, "call-a", null, "$.Priority", "")),
                        "cmd-portless"))
            .code());
    assertEquals(
        "OUTSIDE_SCOPE",
        assertThrows(
                WorkDocumentRejectedException.class,
                () ->
                    documents.apply(
                        state,
                        scope,
                        ruleWithTarget(
                            "",
                            "retained-target",
                            "transfer-a",
                            new FieldReference(
                                FieldReferenceKind.RETAINED, "", null, "", "kept-context")),
                        "cmd-retained-target"))
            .code());
    assertEquals(1, step(state, "call-a").data().transfers().get(0).rules().size());
  }

  @Test
  void replaceOnlyScopeCannotMoveARuleOntoASiblingTransfer() {
    WorkDocumentState state = mappingDocument();
    WorkTaskScope scope =
        new WorkTaskScope(
            "map-repair-kept-rule",
            state.revision(),
            WorkStage.DATA_BEHAVIOR,
            "data-mapping",
            List.of("kept-rule"),
            false,
            true,
            false,
            List.of(),
            List.of());

    assertEquals(
        "OUTSIDE_SCOPE",
        assertThrows(
                WorkDocumentRejectedException.class,
                () ->
                    documents.apply(
                        state,
                        scope,
                        ruleWithTarget(
                            "kept-rule",
                            "",
                            "transfer-b",
                            FieldReference.payload("call-b", PortRole.OUTBOUND_REQUEST, "$.Priority")),
                        "cmd-move"))
            .code());
    assertEquals("transfer-a", transferOfRule(state, "kept-rule"));
  }

  @Test
  void acceptInputDoesNotAliasAnExistingSourceId() {
    documents.intake(RUN_ID, sourceIdDocument("src-om"), "cmd-intake", new WorkRepairBudget(3));
    WorkDocumentState current = documents.read(RUN_ID);
    documents.recordQuestion(
        RUN_ID,
        mappingScope(current, "transfer-a"),
        "Which field supplies priority?",
        QuestionSubject.unspecified(),
        List.of(),
        List.of("src-om"),
        "cmd-question");
    String questionId = documents.read(RUN_ID).document().progress().questions().get(0).id();
    String answer = "Priority comes from the trigger.";

    documents.acceptInput(RUN_ID, questionId, "om", answer);

    WorkDocumentState stored = documents.read(RUN_ID);
    assertEquals(1, sourcesNamed(stored, "src-om").size());
    assertEquals(SOURCE_TEXT, sourcesNamed(stored, "src-om").get(0).content());
    assertEquals("src-om", sourcesNamed(stored, "src-om").get(0).suppliedIdentifier());
    String answerId = question(stored, questionId).answerSourceIds().get(0);
    assertNotEquals("src-om", answerId);
    assertEquals(answer, source(stored, answerId).content());
  }

  @Test
  void secondAcceptKeepsEarlierProducedIds() {
    WorkDocumentState state = mappingDocument();
    WorkCommit first =
        documents.apply(
            state, mappingScope(state, "transfer-a"), ruleCapture("first", "transfer-a", "call-a"), "cmd-first");
    String firstId = first.aliasToId().get("first");
    WorkCommit second =
        documents.apply(
            first.state(),
            mappingScope(first.state(), "transfer-a"),
            ruleCapture("second", "transfer-a", "call-a"),
            "cmd-second");
    String secondId = second.aliasToId().get("second");

    List<String> produced = task(second.state(), "map-transfer:transfer-a").producedRecordIds();
    assertTrue(produced.contains(firstId));
    assertTrue(produced.contains(secondId));
  }

  private static WorkTaskScope outlineScope(WorkDocumentState state) {
    return new WorkTaskScope(
        "outline-call-a",
        state.revision(),
        WorkStage.DATA_BEHAVIOR,
        "data-mapping",
        List.of("call-a"),
        true,
        false,
        false,
        List.of(),
        List.of(),
        List.of(
            new CreationAllowance(WorkRecordKind.OUTLINE, "call-a"),
            new CreationAllowance(WorkRecordKind.TRANSFER, "call-a"),
            new CreationAllowance(WorkRecordKind.RETAINED_VALUE, "trigger")),
        List.of(),
        "define-transfers:call-a",
        WorkTaskKind.DEFINE_TRANSFERS,
        "fingerprint-outline",
        null);
  }

  private static OutlineProposal outlineProposal(String targetStepId, OutlineTransfer transfer) {
    return new OutlineProposal(targetStepId, List.of(transfer), List.of(), List.of());
  }

  private static WorkTaskCapture ruleWithTarget(
      String existingId, String alias, String transferId, FieldReference target) {
    return WorkTaskCapture.prepared(
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
                existingId,
                alias,
                transferId,
                List.of(FieldReference.payload("trigger", PortRole.INBOUND_PAYLOAD, "$.priority")),
                target,
                List.of(),
                "Map priority.",
                List.of("source-1"))),
        List.of());
  }

  private static WorkDocumentState sourceIdDocument(String sourceId) {
    return WorkDocumentState.of(
        new ChainWorkDocument(
            ChainWorkDocument.SCHEMA_VERSION,
            "doc-source",
            List.of(source(sourceId, SOURCE_TEXT, List.of())),
            List.of(),
            LogicalFlow.empty(),
            WorkProgress.empty()));
  }

  private static WorkTaskScope mappingScope(WorkDocumentState state, String transferId) {
    String callId = "transfer-a".equals(transferId) ? "call-a" : "call-b";
    return new WorkTaskScope(
        "map-" + transferId,
        state.revision(),
        WorkStage.DATA_BEHAVIOR,
        "data-mapping",
        List.of(transferId),
        true,
        false,
        false,
        List.of(),
        List.of(),
        List.of(new CreationAllowance(WorkRecordKind.RULE, transferId)),
        List.of(),
        "map-transfer:" + transferId,
        WorkTaskKind.MAP_TRANSFER,
        "fingerprint-1",
        new FixedTransferEndpoint(transferId, new PortRef(callId, "request"), TransferOutcome.SUCCESS));
  }

  private static WorkTaskScope scopeWithState(
      WorkDocumentState state, String taskKey, WorkTaskKind kind, String fingerprint) {
    return new WorkTaskScope(
        "map-transfer-a",
        state.revision(),
        WorkStage.DATA_BEHAVIOR,
        "data-mapping",
        List.of("transfer-a"),
        false,
        false,
        false,
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        taskKey,
        kind,
        fingerprint,
        new FixedTransferEndpoint("transfer-a", new PortRef("call-a", "request"), TransferOutcome.SUCCESS));
  }

  private static WorkTaskScope replaceStepScope(WorkDocumentState state, String stepId) {
    return new WorkTaskScope(
        "edit-" + stepId,
        state.revision(),
        WorkStage.LOGICAL_FLOW,
        "logical-design",
        List.of(stepId),
        false,
        true,
        false,
        List.of(),
        List.of(),
        List.of(),
        List.of(stepId),
        "logical-design:" + stepId,
        WorkTaskKind.LOGICAL_DESIGN,
        "",
        null);
  }

  private static WorkDocumentState mappingDocument() {
    MappingRule kept =
        new MappingRule(
            "kept-rule",
            List.of(FieldReference.payload("trigger", PortRole.INBOUND_PAYLOAD, "$.name")),
            FieldReference.payload("call-a", PortRole.OUTBOUND_REQUEST, "$.Subject"),
            List.of(),
            "Keep the subject.",
            List.of("source-1"));
    DataTransfer transferA =
        new DataTransfer(
            "transfer-a",
            List.of(new PortRef("trigger", "payload")),
            new PortRef("call-a", "request"),
            List.of("req-1"),
            List.of(kept),
            MappingDecision.UNSPECIFIED,
            TransferOutcome.SUCCESS,
            List.of());
    DataTransfer transferB =
        new DataTransfer(
            "transfer-b",
            List.of(new PortRef("trigger", "payload")),
            new PortRef("call-b", "request"),
            List.of("req-1"),
            List.of(),
            MappingDecision.UNSPECIFIED,
            TransferOutcome.SUCCESS,
            List.of());
    RetainedValue keptContext =
        new RetainedValue(
            "kept-context",
            FieldReference.payload("trigger", PortRole.INBOUND_PAYLOAD, "$.orderId"),
            "Order id",
            List.of("source-1"),
            "trigger",
            RetainedResolution.RESOLVED);
    return WorkDocumentState.of(
        new ChainWorkDocument(
            ChainWorkDocument.SCHEMA_VERSION,
            "doc-map",
            List.of(source("source-1", SOURCE_TEXT, List.of())),
            List.of(new WorkRequirement("req-1", "Create the task", List.of("source-1"), "")),
            new LogicalFlow(
                List.of(
                    step("trigger", "Receive", "Receive the order", StepData.empty()),
                    step("call-a", "Create task", "First call", new StepData(List.of(transferA), List.of())),
                    step(
                        "call-b",
                        "Create task",
                        "Second call",
                        new StepData(List.of(transferB), List.of(keptContext)))),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()),
            WorkProgress.empty()));
  }

  private static WorkDocumentState duplicateLabelDocument() {
    return WorkDocumentState.of(
        new ChainWorkDocument(
            ChainWorkDocument.SCHEMA_VERSION,
            "doc-labels",
            List.of(source("source-1", SOURCE_TEXT, List.of())),
            List.of(),
            new LogicalFlow(
                List.of(
                    step("call-1", "Create task", "First call", StepData.empty()),
                    step("call-2", "Create task", "Second call", StepData.empty())),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()),
            WorkProgress.empty()));
  }

  private static WorkDocumentState sourceDocument() {
    return WorkDocumentState.of(
        new ChainWorkDocument(
            ChainWorkDocument.SCHEMA_VERSION,
            "doc-source",
            List.of(source("source-1", SOURCE_TEXT, List.of())),
            List.of(),
            LogicalFlow.empty(),
            WorkProgress.empty()));
  }

  private static WorkDocumentState targetDocument() {
    return WorkDocumentState.of(
        new ChainWorkDocument(
            ChainWorkDocument.SCHEMA_VERSION,
            "doc-map",
            List.of(
                source(
                    "source-1",
                    SOURCE_TEXT,
                    List.of(
                        new SourcePassage(
                            "passage-1", "source-1", sha256("Map priority"), "Map priority", "Priority")))),
            List.of(new WorkRequirement("req-1", "Create the task", List.of("source-1"), "")),
            new LogicalFlow(
                List.of(
                    step("trigger", "Receive", "Receive the order", StepData.empty()),
                    step("call-a", "Create task", "First call", StepData.empty()),
                    step("call-b", "Create task again", "Second call", StepData.empty())),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()),
            WorkProgress.empty()));
  }

  private static WorkDocumentState retainedDocument() {
    RetainedValue order =
        new RetainedValue(
            "order-id", null, "Order id", List.of("source-1"), "trigger", RetainedResolution.UNRESOLVED);
    RetainedValue task =
        new RetainedValue(
            "task-id", null, "Task id", List.of("source-1"), "call-a", RetainedResolution.UNRESOLVED);
    return WorkDocumentState.of(
        new ChainWorkDocument(
            ChainWorkDocument.SCHEMA_VERSION,
            "doc-retained",
            List.of(source("source-1", SOURCE_TEXT, List.of())),
            List.of(),
            new LogicalFlow(
                List.of(
                    step("trigger", "Receive", "Receive", new StepData(List.of(), List.of(order))),
                    step("call-a", "Create task", "Create", new StepData(List.of(), List.of(task)))),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()),
            WorkProgress.empty()));
  }

  private static ChainWorkDocument documentWith(
      RetainedValue partial, RetainedValue resolved, DataTransfer transfer) {
    return new ChainWorkDocument(
        ChainWorkDocument.SCHEMA_VERSION,
        "doc-retained",
        List.of(source("source-1", SOURCE_TEXT, List.of())),
        List.of(),
        new LogicalFlow(
            List.of(
                step("trigger", "Receive", "Receive", new StepData(List.of(), List.of(resolved))),
                step("call-a", "Create", "Create", new StepData(List.of(), List.of(partial))),
                step("reply", "Reply", "Reply", new StepData(List.of(transfer), List.of()))),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of()),
        WorkProgress.empty());
  }

  private static WorkSource source(String id, String content, List<SourcePassage> passages) {
    return new WorkSource(
        id,
        "request",
        "artifact://" + id,
        sha256(content),
        id + ".md",
        id,
        List.of(),
        content,
        passages);
  }

  private static LogicalStep step(String id, String label, String intent, StepData data) {
    return new LogicalStep(
        id,
        StepKind.SERVICE_CALL,
        label,
        intent,
        List.of("source-1"),
        List.of(),
        null,
        data);
  }

  private static WorkTaskCapture ruleCapture(String alias, String transferId, String callId) {
    return WorkTaskCapture.prepared(
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
                "",
                alias,
                transferId,
                List.of(FieldReference.payload("trigger", PortRole.INBOUND_PAYLOAD, "$.priority")),
                FieldReference.payload(callId, PortRole.OUTBOUND_REQUEST, "$.Priority"),
                List.of(new JsonConstant("fallback", JsonNodeFactory.instance.textNode("Normal"))),
                "Map high to High.",
                List.of("source-1"))),
        List.of());
  }

  private static WorkTaskCapture stepCapture(String alias) {
    return WorkTaskCapture.prepared(
        List.of(),
        List.of(
            new CapturedStep(
                "", alias, StepKind.LOCAL, "Extra", "Not allowed", List.of("source-1"), List.of())),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of());
  }

  private static WorkTaskCapture retainedCapture(String alias, String stepId) {
    return new WorkTaskCapture(
        WorkOutcome.PREPARED,
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
        List.of(
            new CapturedRetainedValue(
                "",
                alias,
                stepId,
                FieldReference.payload(stepId, PortRole.INBOUND_PAYLOAD, "$.orderId"),
                "Extra context",
                List.of("source-1"))),
        List.of(),
        "",
        "",
        List.of(),
        "",
        "",
        List.of(),
        "");
  }

  private static WorkTaskCapture transferCapture(String alias, String stepId) {
    return WorkTaskCapture.prepared(
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
                "",
                alias,
                stepId,
                List.of(new PortRef("trigger", "payload")),
                new PortRef(stepId, "request"),
                List.of("req-1"),
                "")),
        List.of(),
        List.of());
  }

  private static LogicalStep step(WorkDocumentState state, String id) {
    return state.document().flow().steps().stream()
        .filter(candidate -> candidate.id().equals(id))
        .findFirst()
        .orElseThrow();
  }

  private static WorkSource source(WorkDocumentState state, String id) {
    return state.document().sources().stream()
        .filter(candidate -> candidate.id().equals(id))
        .findFirst()
        .orElseThrow();
  }

  private static WorkQuestion question(WorkDocumentState state, String id) {
    return state.document().progress().questions().stream()
        .filter(candidate -> candidate.id().equals(id))
        .findFirst()
        .orElseThrow();
  }

  private static WorkTaskRecord task(WorkDocumentState state, String taskKey) {
    return state.document().progress().tasks().stream()
        .filter(candidate -> candidate.taskKey().equals(taskKey))
        .findFirst()
        .orElseThrow();
  }

  private static Optional<RetainedValue> retained(WorkDocumentState state, String id) {
    for (LogicalStep step : state.document().flow().steps()) {
      for (RetainedValue value : step.data().retainedValues()) {
        if (value.id().equals(id)) {
          return Optional.of(value);
        }
      }
    }
    return Optional.empty();
  }

  private static String transferOfRule(WorkDocumentState state, String ruleId) {
    for (LogicalStep step : state.document().flow().steps()) {
      for (DataTransfer transfer : step.data().transfers()) {
        for (MappingRule rule : transfer.rules()) {
          if (rule.id().equals(ruleId)) {
            return transfer.id();
          }
        }
      }
    }
    throw new AssertionError(ruleId);
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

  private static List<String> transferIds(WorkDocumentState state, String stepId) {
    return step(state, stepId).data().transfers().stream().map(DataTransfer::id).toList();
  }

  private static List<WorkSource> sourcesNamed(WorkDocumentState state, String id) {
    return state.document().sources().stream().filter(candidate -> candidate.id().equals(id)).toList();
  }

  private static List<String> stepsNamed(WorkDocumentState state, String label) {
    return state.document().flow().steps().stream()
        .filter(step -> label.equals(step.label()))
        .map(LogicalStep::id)
        .toList();
  }

  private static int countReason(ProductPipelineRunDocument run, String reason) {
    int count = 0;
    for (RunTransition transition : run.transitions()) {
      if (reason.equals(transition.reason())) {
        count++;
      }
    }
    return count;
  }

  private static byte[] bytes(Object value) {
    try {
      return JSON.writeValueAsBytes(value);
    } catch (Exception failure) {
      throw new IllegalStateException(failure);
    }
  }

  private static void assertArrayEqualsBytes(byte[] expected, byte[] actual) {
    assertEquals(expected.length, actual.length);
    for (int i = 0; i < expected.length; i++) {
      assertEquals(expected[i], actual[i]);
    }
  }

  private static String sha256(String content) {
    try {
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception failure) {
      throw new IllegalStateException(failure);
    }
  }

  private static RunSnapshot openRun(String runId) {
    return new RunSnapshot(
        runId,
        "conversation-" + runId,
        1L,
        RunStatus.RUNNING,
        "DATA_BEHAVIOR",
        List.of(new StageSnapshot("DATA_BEHAVIOR", StageStatus.RUNNING, List.of(), null)),
        null);
  }

  private CompilationArtifacts artifactsOn(ArtifactBlobStore store) {
    return new CompilationArtifacts(store, mapper(), clock());
  }

  private static ObjectMapper mapper() {
    return new ObjectMapper().registerModule(new JavaTimeModule());
  }

  private static Clock clock() {
    return Clock.fixed(FIXED, ZoneOffset.UTC);
  }

  private static final class CrashBeforeCas implements ArtifactBlobStore {
    private final InMemoryArtifactBlobStore delegate;
    private boolean failNextRunWrite;

    private CrashBeforeCas(InMemoryArtifactBlobStore delegate) {
      this.delegate = delegate;
    }

    @Override
    public void put(String key, byte[] content) {
      delegate.put(key, content);
    }

    @Override
    public java.util.Optional<byte[]> get(String key) {
      return delegate.get(key);
    }

    @Override
    public java.util.List<String> list(String prefix) {
      return delegate.list(prefix);
    }

    @Override
    public java.util.Optional<VersionedBlob> getVersioned(String key) {
      return delegate.getVersioned(key);
    }

    @Override
    public void putIfVersion(String key, byte[] content, String expectedVersion) {
      if (failNextRunWrite && key.startsWith("product-pipeline-runs/")) {
        failNextRunWrite = false;
        throw new StaleBlobVersionException("crash before cas");
      }
      delegate.putIfVersion(key, content, expectedVersion);
    }
  }
}
