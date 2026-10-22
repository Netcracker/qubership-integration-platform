package org.qubership.integration.platform.ai.plan.workdocument;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.plan.workdocument.task.SchemaFragment;
import org.qubership.integration.platform.ai.plan.workdocument.task.WorkTaskExecutor;
import org.qubership.integration.platform.ai.plan.workdocument.task.WorkTaskMaterials;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.qubership.integration.platform.ai.plan.workdocument.binding.ContractMaterial;
import org.qubership.integration.platform.ai.plan.workdocument.binding.PortSchemaMaterial;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunStore;
import org.qubership.integration.platform.ai.productpipeline.store.RunSnapshot;
import org.qubership.integration.platform.ai.productpipeline.store.RunStatus;
import org.qubership.integration.platform.ai.productpipeline.store.StageSnapshot;
import org.qubership.integration.platform.ai.productpipeline.store.StageStatus;

class WorkDataOutlineTest {

  private static final Instant FIXED = Instant.parse("2026-09-25T12:00:00Z");
  private static final String RUN_ID = "run-outline-1";
  private static final String REQUEST_TEXT = "Map the order id.";
  private static final String RESPONSE_TEXT = "Return the status on success and on failure.";
  private static final ObjectMapper JSON = new ObjectMapper();

  private ProductPipelineRunStore runs;
  private WorkDocumentService documents;
  private WorkDataOutline outlines;

  @BeforeEach
  void setUp() {
    ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    InMemoryArtifactBlobStore blobs = new InMemoryArtifactBlobStore();
    Clock clock = Clock.fixed(FIXED, ZoneOffset.UTC);
    CompilationArtifacts artifacts = new CompilationArtifacts(blobs, mapper, clock);
    runs = new ProductPipelineRunStore(blobs, mapper, clock);
    documents = new WorkDocumentService(runs, artifacts, mapper);
    runs.create(
        new RunSnapshot(
            RUN_ID,
            "conversation-outline",
            1L,
            RunStatus.RUNNING,
            "DATA_BEHAVIOR",
            List.of(new StageSnapshot("DATA_BEHAVIOR", StageStatus.RUNNING, List.of(), null)),
            null));
    outlines = new WorkDataOutline(documents);
  }

  @Test
  void outlineStoresPortContentHashAndABodyChangeRechecksTheConsumer() {
    seed(callDocument());
    outlines.define(
        RUN_ID,
        "call",
        new OutlineProposal(
            "call",
            List.of(
                transfer("to-request", "trigger", "payload", "call", "request", TransferOutcome.UNSPECIFIED, List.of("req-request"), List.of()),
                transfer("to-success", "trigger", "payload", "call", "success", TransferOutcome.SUCCESS, List.of("req-common"), List.of()),
                transfer("to-failure", "trigger", "payload", "call", "failure", TransferOutcome.FAILURE, List.of("req-common"), List.of())),
            List.of(),
            List.of(
                new OutlineCoverage("req-request", "passage-source-1-1", CoverageDisposition.ASSIGNED),
                new OutlineCoverage("req-common", "passage-source-1-2", CoverageDisposition.ASSIGNED))),
        contracts(),
        "outline-ports");
    ChainWorkDocument stored = documents.read(RUN_ID).document();
    assertTrue(
        step(stored, "call")
            .binding()
            .portContentHashes()
            .contains(new ResolvedWorkBinding.PortContentHash("request", "hash-op-call-request")));
    String transferId = transfer(step(stored, "call"), "request").id();

    ChainWorkDocument accepted = WorkPlanningDocuments.accept(stored, task -> true);
    ChainWorkDocument changed = replacePortHash(accepted, "call", "request", "hash-op-call-request-next");
    WorkTaskPlanner.Plan plan = new WorkTaskPlanner().plan(changed);

    assertEquals(WorkTaskState.NEEDS_RECHECK, plannerTask(plan, "map-transfer:" + transferId).state());
  }

  @Test
  void synchronousCallKeepsOneServiceCallAndDistinctOutcomes() {
    seed(callDocument());
    WorkCommit commit =
        outlines.define(
            RUN_ID,
            "call",
            new OutlineProposal(
                "call",
                List.of(
                    transfer("to-request", "trigger", "payload", "call", "request", TransferOutcome.UNSPECIFIED, List.of("req-request"), List.of()),
                    transfer("to-success", "trigger", "payload", "call", "success", TransferOutcome.SUCCESS, List.of("req-common"), List.of()),
                    transfer("to-failure", "trigger", "payload", "call", "failure", TransferOutcome.FAILURE, List.of("req-common"), List.of())),
                List.of(),
                List.of(
                    new OutlineCoverage("req-request", "passage-source-1-1", CoverageDisposition.ASSIGNED),
                    new OutlineCoverage("req-common", "passage-source-1-2", CoverageDisposition.ASSIGNED))),
            contracts(),
            "outline-call");

    ChainWorkDocument stored = documents.read(RUN_ID).document();
    assertEquals(1, stored.flow().steps().stream().filter(step -> step.kind() == StepKind.SERVICE_CALL).count());
    assertEquals(List.of("trigger", "call", "reply"), stored.flow().steps().stream().map(LogicalStep::id).toList());
    LogicalStep call = step(stored, "call");
    assertEquals(3, call.data().transfers().size());
    assertEquals(TransferOutcome.UNSPECIFIED, transfer(call, "request").outcome());
    assertEquals(TransferOutcome.SUCCESS, transfer(call, "success").outcome());
    assertEquals(TransferOutcome.FAILURE, transfer(call, "failure").outcome());
    assertTrue(call.data().transfers().stream().allMatch(item -> item.rules().isEmpty()));
    assertEquals(List.of("req-common"), transfer(call, "success").requirementIds());
    assertEquals(List.of("req-common"), transfer(call, "failure").requirementIds());
    assertEquals(CoverageDisposition.ASSIGNED, call.data().outline().coverage().get(1).disposition());
    assertEquals("passage-source-1-2", call.data().outline().coverage().get(1).passageId());
    assertEquals(2, source(stored, "source-1").passages().size());
    assertTrue(commit.acceptedRecordIds().size() >= 3);
    assertTrue(commit.runRevision() != null);
    assertEquals("define-transfers:call", task(stored, "define-transfers:call").taskKey());
    assertEquals(64, task(stored, "define-transfers:call").acceptedInputFingerprint().length());
  }

  @Test
  void schemaContentHashChangesTheAcceptedFingerprint() {
    seed(callDocument());
    OutlineProposal proposal =
        new OutlineProposal(
            "call",
            List.of(
                transfer(
                    "to-request",
                    "trigger",
                    "payload",
                    "call",
                    "request",
                    TransferOutcome.UNSPECIFIED,
                    List.of("req-request"),
                    List.of()),
                transfer(
                    "to-success",
                    "trigger",
                    "payload",
                    "call",
                    "success",
                    TransferOutcome.SUCCESS,
                    List.of("req-common"),
                    List.of()),
                transfer(
                    "to-failure",
                    "trigger",
                    "payload",
                    "call",
                    "failure",
                    TransferOutcome.FAILURE,
                    List.of("req-common"),
                    List.of())),
            List.of(),
            List.of(
                new OutlineCoverage("req-request", "passage-source-1-1", CoverageDisposition.ASSIGNED),
                new OutlineCoverage("req-common", "passage-source-1-2", CoverageDisposition.ASSIGNED)));
    outlines.define(RUN_ID, "call", proposal, contracts(), "outline-hash-a");
    String first =
        task(documents.read(RUN_ID).document(), "define-transfers:call").acceptedInputFingerprint();

    String otherRun = "run-outline-2";
    runs.create(
        new RunSnapshot(
            otherRun,
            "conversation-outline-2",
            1L,
            RunStatus.RUNNING,
            "DATA_BEHAVIOR",
            List.of(new StageSnapshot("DATA_BEHAVIOR", StageStatus.RUNNING, List.of(), null)),
            null));
    documents.intake(otherRun, WorkDocumentState.of(callDocument()), "cmd-seed", new WorkRepairBudget(3));
    List<ContractMaterial> changed = new ArrayList<>(contracts());
    changed.removeIf(item -> "op-call".equals(item.operationId()));
    changed.add(readyHashed("spec-op-call", "op-call", "-v2", "request", "success", "failure"));
    outlines.define(otherRun, "call", proposal, changed, "outline-hash-b");
    String second =
        task(documents.read(otherRun).document(), "define-transfers:call").acceptedInputFingerprint();

    assertEquals(64, first.length());
    assertEquals(64, second.length());
    assertFalse(first.equals(second));
  }

  @Test
  void sameLabelCallsKeepDistinctOutlines() {
    seed(duplicateCalls());
    outlines.define(RUN_ID, "call-1", requestOutline("call-1", "req-1", "passage-source-1-1"), contracts(), "outline-1");
    outlines.define(RUN_ID, "call-2", requestOutline("call-2", "req-1", "passage-source-1-1"), contracts(), "outline-2");

    ChainWorkDocument stored = documents.read(RUN_ID).document();
    String first = step(stored, "call-1").data().transfers().get(0).id();
    String second = step(stored, "call-2").data().transfers().get(0).id();
    assertFalse(first.equals(second));
    assertEquals("Create task", step(stored, "call-1").label());
    assertEquals("Create task", step(stored, "call-2").label());
    assertEquals("op-call", step(stored, "call-1").binding().operationId());
    assertEquals(step(stored, "call-1").binding().operationId(), step(stored, "call-2").binding().operationId());
    assertEquals(List.of(first), step(stored, "call-1").data().outline().transferIds());
    assertEquals(List.of(second), step(stored, "call-2").data().outline().transferIds());
  }

  @Test
  void multiSourceTransferKeepsARetainedPlaceholder() {
    seed(enrichedCall());
    WorkCommit commit =
        outlines.define(
            RUN_ID,
            "call",
            new OutlineProposal(
                "call",
                List.of(
                    new OutlineTransfer(
                        "to-request",
                        "",
                        List.of(new PortRef("trigger", "payload"), new PortRef("enrich", "success")),
                        new PortRef("call", "request"),
                        TransferOutcome.UNSPECIFIED,
                        List.of("req-request"),
                        List.of("kept-order"),
                        "")),
                List.of(
                    new OutlineRetained(
                        "kept-order", "", "trigger", "Order id for the call", List.of("passage-source-1-1"))),
                List.of(new OutlineCoverage("req-request", "passage-source-1-1", CoverageDisposition.ASSIGNED))),
            contracts(),
            "outline-multi");

    LogicalStep call = step(documents.read(RUN_ID).document(), "call");
    DataTransfer transfer = call.data().transfers().get(0);
    assertEquals(2, transfer.sourcePorts().size());
    assertEquals("trigger", transfer.sourcePorts().get(0).stepId());
    assertEquals("enrich", transfer.sourcePorts().get(1).stepId());
    assertTrue(transfer.rules().isEmpty());
    String retainedId = commit.aliasToId().get("kept-order");
    assertEquals(List.of(retainedId), transfer.requiredRetainedIds());
    RetainedValue retained = step(documents.read(RUN_ID).document(), "trigger").data().retainedValues().get(0);
    assertEquals(retainedId, retained.id());
    assertEquals("trigger", retained.producerStepId());
    assertEquals(RetainedResolution.UNRESOLVED, retained.resolution());
    assertFalse(retained.satisfiesConsumer());
  }

  @Test
  void missingSchemaDoesNotPublishAnOutline() {
    seed(callDocument());
    String before = documents.read(RUN_ID).revision();
    ContractMaterial.MissingSchema missing =
        new ContractMaterial.MissingSchema(
            "spec-op-call", "op-call", "2024.4", "failure", "Operation op-call version 2024.4 has no failure schema.");
    List<ContractMaterial> supplied = new ArrayList<>(contracts());
    supplied.removeIf(item -> "op-call".equals(item.operationId()));
    supplied.add(missing);

    WorkDocumentRejectedException rejected =
        assertThrows(
            WorkDocumentRejectedException.class,
            () ->
                outlines.define(
                    RUN_ID,
                    "call",
                    requestOutline("call", "req-request", "passage-source-1-1"),
                    supplied,
                    "outline-missing"));

    assertEquals("MISSING_SCHEMA", rejected.code());
    assertEquals(before, documents.read(RUN_ID).revision());
    assertTrue(step(documents.read(RUN_ID).document(), "call").data().outline().transferIds().isEmpty());
  }

  @Test
  void omittedRequirementStaysUncovered() {
    seed(callDocument());
    WorkDocumentRejectedException rejected =
        assertThrows(
            WorkDocumentRejectedException.class,
            () ->
                outlines.define(
                    RUN_ID,
                    "call",
                    new OutlineProposal(
                        "call",
                        List.of(
                            transfer(
                                "to-request",
                                "trigger",
                                "payload",
                                "call",
                                "request",
                                TransferOutcome.UNSPECIFIED,
                                List.of("req-request"),
                                List.of())),
                        List.of(),
                        List.of(new OutlineCoverage("req-request", "passage-source-1-1", CoverageDisposition.ASSIGNED))),
                    contracts(),
                    "outline-partial"));

    assertEquals("MISSING_COVERAGE", rejected.code());
    assertTrue(step(documents.read(RUN_ID).document(), "call").data().transfers().isEmpty());
  }

  @Test
  void correctionReplacesTheSupersededRequirement() {
    String oldText = "Map the order id.";
    String newText = "Map the header order id.";
    seed(
        new ChainWorkDocument(
            ChainWorkDocument.SCHEMA_VERSION,
            "doc-outline",
            List.of(
                new WorkSource(
                    "source-1",
                    "MESSAGE",
                    "message:" + sha256(oldText),
                    sha256(oldText),
                    "message",
                    "",
                    List.of(),
                    oldText,
                    List.of(passage("passage-source-1-1", oldText, ""))),
                new WorkSource(
                    "source-2",
                    "CORRECTION",
                    "correction:" + sha256(newText),
                    sha256(newText),
                    "correction",
                    "",
                    List.of("source-1"),
                    newText,
                    List.of(new SourcePassage("passage-source-2-1", "source-2", sha256(newText), newText, "")))),
            List.of(
                new WorkRequirement("req-old", oldText, List.of("source-1"), ""),
                new WorkRequirement("req-new", newText, List.of("source-2"), "req-old")),
            new LogicalFlow(
                List.of(
                    triggerStep(),
                    serviceStep("call", "Create task", List.of("req-old")),
                    replyStep()),
                List.of(link("c-request", "trigger", "request", "call")),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()),
            WorkProgress.empty()));

    outlines.define(
        RUN_ID,
        "call",
        requestOutline("call", "req-new", "passage-source-2-1"),
        contracts(),
        "outline-correction");

    DataOutline outline = step(documents.read(RUN_ID).document(), "call").data().outline();
    assertEquals("req-new", outline.coverage().get(0).requirementId());
    assertEquals("passage-source-2-1", outline.coverage().get(0).passageId());
    assertEquals(1, outline.coverage().size());
  }

  @Test
  void sharedSourceDoesNotForceAnotherStepsRequirement() {
    String other = "Keep the trigger id.";
    seed(
        document(
            REQUEST_TEXT,
            List.of(passage("passage-source-1-1", REQUEST_TEXT, "")),
            List.of(
                new WorkRequirement("req-call", REQUEST_TEXT, List.of("source-1"), ""),
                new WorkRequirement("req-other", other, List.of("source-1"), "")),
            List.of(
                new LogicalStep(
                    "trigger",
                    StepKind.TRIGGER,
                    "Start",
                    "Receive",
                    List.of("source-1"),
                    List.of("req-other"),
                    new ResolvedWorkBinding(
                        "sys",
                        "2024.4",
                        "op-trigger",
                        "http",
                        "POST",
                        "/in",
                        List.of("spec-op-trigger"),
                        List.of("payload")),
                    StepData.empty()),
                serviceStep("call", "Create task", List.of("req-call"))),
            List.of(link("c-request", "trigger", "request", "call"))));

    outlines.define(
        RUN_ID, "call", requestOutline("call", "req-call", "passage-source-1-1"), contracts(), "outline-owned");

    DataOutline outline = step(documents.read(RUN_ID).document(), "call").data().outline();
    assertEquals(List.of("req-call"), outline.coverage().stream().map(CoverageEntry::requirementId).toList());
  }

  @Test
  void questionCoverageDoesNotPublishTheOutline() {
    seed(callDocument());
    String before = documents.read(RUN_ID).revision();

    WorkDocumentRejectedException rejected =
        assertThrows(
            WorkDocumentRejectedException.class,
            () ->
                outlines.define(
                    RUN_ID,
                    "call",
                    new OutlineProposal(
                        "call",
                        List.of(
                            transfer(
                                "to-request",
                                "trigger",
                                "payload",
                                "call",
                                "request",
                                TransferOutcome.UNSPECIFIED,
                                List.of("req-request"),
                                List.of())),
                        List.of(),
                        List.of(
                            new OutlineCoverage("req-request", "passage-source-1-1", CoverageDisposition.ASSIGNED),
                            new OutlineCoverage("req-common", "passage-source-1-2", CoverageDisposition.QUESTION))),
                    contracts(),
                    "outline-question"));

    assertEquals("OPEN_QUESTION", rejected.code());
    ChainWorkDocument stored = documents.read(RUN_ID).document();
    assertEquals(before, documents.read(RUN_ID).revision());
    assertTrue(step(stored, "call").data().transfers().isEmpty());
    assertTrue(stored.progress().tasks().isEmpty());
    assertTrue(stored.progress().questions().isEmpty());
  }

  @Test
  void noMappingDispositionStaysVisible() {
    seed(callDocument());
    outlines.define(
        RUN_ID,
        "call",
        new OutlineProposal(
            "call",
            List.of(),
            List.of(),
            List.of(
                new OutlineCoverage("req-request", "passage-source-1-1", CoverageDisposition.NO_MAPPING),
                new OutlineCoverage("req-common", "passage-source-1-2", CoverageDisposition.NO_MAPPING))),
        contracts(),
        "outline-none");

    DataOutline outline = step(documents.read(RUN_ID).document(), "call").data().outline();
    assertTrue(outline.transferIds().isEmpty());
    assertEquals(CoverageDisposition.NO_MAPPING, outline.coverage().get(0).disposition());
    assertEquals(CoverageDisposition.NO_MAPPING, outline.coverage().get(1).disposition());
    assertEquals("passage-source-1-1", outline.coverage().get(0).passageId());
  }

  @Test
  void listedFieldNamesDoNotCreateARename() {
    String text = "The payload lists processInstanceId and processId.";
    seed(singleRequirement("req-names", text, "passage-source-1-1"));
    outlines.define(
        RUN_ID,
        "call",
        requestOutline("call", "req-names", "passage-source-1-1"),
        contracts(),
        "outline-names");

    ChainWorkDocument stored = documents.read(RUN_ID).document();
    assertEquals(text, source(stored, "source-1").content());
    assertTrue(step(stored, "call").data().transfers().get(0).rules().isEmpty());
    assertTrue(step(stored, "trigger").data().retainedValues().isEmpty());
    assertFalse(JSON.valueToTree(stored).toString().contains("processId is processInstanceId"));
  }

  @Test
  void downstreamProducerIsRejected() {
    seed(callDocument());
    WorkDocumentRejectedException rejected =
        assertThrows(
            WorkDocumentRejectedException.class,
            () ->
                outlines.define(
                    RUN_ID,
                    "call",
                    new OutlineProposal(
                        "call",
                        List.of(
                            transfer(
                                "to-request",
                                "trigger",
                                "payload",
                                "call",
                                "request",
                                TransferOutcome.UNSPECIFIED,
                                List.of("req-request"),
                                List.of("from-reply"))),
                        List.of(new OutlineRetained("from-reply", "", "reply", "Later value", List.of("passage-source-1-1"))),
                        List.of(
                            new OutlineCoverage("req-request", "passage-source-1-1", CoverageDisposition.ASSIGNED),
                            new OutlineCoverage("req-common", "passage-source-1-2", CoverageDisposition.NO_MAPPING))),
                    contracts(),
                    "outline-downstream"));

    assertEquals("OUTSIDE_SCOPE", rejected.code());
    assertTrue(step(documents.read(RUN_ID).document(), "reply").data().retainedValues().isEmpty());
  }

  @Test
  void proposeReservesTheAssignedStepsAndLeavesAnUnrelatedSchemaOut() {
    seed(callDocument());
    WorkDocumentState before = documents.read(RUN_ID);
    Clock clock = Clock.fixed(FIXED, ZoneOffset.UTC);
    WorkDataOutline proposing =
        new WorkDataOutline(documents, new WorkTaskExecutor(documents, runs, clock));
    String[] prompt = {""};
    String[] schema = {""};
    int[] calls = {0};
    WorkCommit commit =
        proposing.propose(
            RUN_ID,
            "call",
            outlineMaterials(),
            contracts(),
            request -> {
              calls[0]++;
              prompt[0] = request.prompt();
              schema[0] = request.responseSchema().toString();
              return preparedOutline();
            },
            "caller-command");

    assertEquals(1, calls[0]);
    assertTrue(prompt[0].contains("TRIGGER_SCHEMA_BODY"));
    assertTrue(prompt[0].contains("CALL_SCHEMA_BODY"));
    assertTrue(prompt[0].contains("passage-source-1-1"));
    assertTrue(prompt[0].contains(sha256(REQUEST_TEXT)));
    assertTrue(prompt[0].contains(REQUEST_TEXT));
    assertFalse(prompt[0].contains("UNRELATED_REPLY_SCHEMA"));
    assertTrue(schema[0].contains("trigger"));
    assertTrue(schema[0].contains("payload"));
    assertFalse(schema[0].contains("reply"));
    assertEquals(
        WorkTaskPlanner.taskId(WorkTaskKind.DEFINE_TRANSFERS, "call") + ":" + before.revision(),
        commit.commandId());
    assertEquals(1, step(commit.state().document(), "call").data().transfers().size());
    assertTrue(
        new WorkTaskExecutor(documents, runs, clock)
            .publishedResult(
                RUN_ID,
                new WorkTaskScope(
                    WorkTaskPlanner.taskId(WorkTaskKind.DEFINE_TRANSFERS, "call"),
                    before.revision(),
                    WorkStage.DATA_BEHAVIOR,
                    WorkDataOutline.SKILL_ID,
                    List.of("call", "trigger"),
                    false,
                    false,
                    false,
                    List.of(),
                    List.of()))
            .isPresent());

    proposing.propose(
        RUN_ID,
        "call",
        outlineMaterials(),
        contracts(),
        request -> {
          calls[0]++;
          return openQuestion();
        },
        "caller-command");
    assertEquals(2, calls[0]);
  }

  @Test
  void outlineClarificationRejectsCoverageAndAStepOutsideThePredecessors() {
    seed(callDocument());
    WorkDataOutline proposing = proposing();

    assertEquals(
        "CONTRADICTORY_OUTCOME",
        assertThrows(
                WorkDocumentRejectedException.class,
                () ->
                    proposing.propose(
                        RUN_ID, "call", outlineMaterials(), contracts(), request -> coveredQuestion(), "cmd"))
            .code());
    assertEquals(
        "MALFORMED_REFERENCE",
        assertThrows(
                WorkDocumentRejectedException.class,
                () ->
                    proposing.propose(
                        RUN_ID, "call", outlineMaterials(), contracts(), request -> replyQuestion(), "cmd"))
            .code());
    assertTrue(documents.read(RUN_ID).document().progress().questions().isEmpty());
  }

  @Test
  void outlineDefectAndPreparedRejectTheOtherBranches() {
    seed(callDocument());
    WorkDataOutline proposing = proposing();

    assertEquals(
        "CONTRADICTORY_OUTCOME",
        assertThrows(
                WorkDocumentRejectedException.class,
                () ->
                    proposing.propose(
                        RUN_ID, "call", outlineMaterials(), contracts(), request -> defectWithTransfer(), "cmd"))
            .code());
    assertEquals(
        "CONTRADICTORY_OUTCOME",
        assertThrows(
                WorkDocumentRejectedException.class,
                () ->
                    proposing.propose(
                        RUN_ID, "call", outlineMaterials(), contracts(), request -> preparedWithDefect(), "cmd"))
            .code());
    assertEquals(
        "CONTRADICTORY_OUTCOME",
        assertThrows(
                WorkDocumentRejectedException.class,
                () ->
                    proposing.propose(
                        RUN_ID, "call", outlineMaterials(), contracts(), request -> questionDisposition(), "cmd"))
            .code());
    assertTrue(step(documents.read(RUN_ID).document(), "call").data().transfers().isEmpty());
  }

  @Test
  void instructionsForbidTaskKeysOrderingHandlersAndFieldRules() {
    String prompt = WorkDataOutline.instructions();
    assertTrue(prompt.contains("task key"));
    assertTrue(prompt.contains("ordering"));
    assertTrue(prompt.contains("handler"));
    assertTrue(prompt.contains("field rule"));
    assertFalse(prompt.contains("completeTask"));
  }

  private WorkDataOutline proposing() {
    return new WorkDataOutline(
        documents, new WorkTaskExecutor(documents, runs, Clock.fixed(FIXED, ZoneOffset.UTC)));
  }

  private static WorkTaskMaterials outlineMaterials() {
    return new WorkTaskMaterials(
        List.of(
            new SchemaFragment(
                "schema-trigger",
                "trigger",
                "payload",
                "hash-trigger",
                "ref-trigger",
                "TRIGGER_SCHEMA_BODY"),
            new SchemaFragment(
                "schema-call", "call", "request", "hash-call", "ref-call", "CALL_SCHEMA_BODY"),
            new SchemaFragment(
                "schema-reply", "reply", "request", "hash-reply", "ref-reply", "UNRELATED_REPLY_SCHEMA")),
        List.of(),
        Map.of("source-1", "parent source text"));
  }

  private static String preparedOutline() {
    return """
        {"outcome":"PREPARED","transfers":[{"alias":"to-request","sourceStepId":"trigger","sourcePort":"payload","targetPort":"request","outcome":"UNSPECIFIED","requirementIds":["req-request"],"requiredRetainedIds":[],"decision":""}],"retainedPlaceholders":[],"coverage":[{"requirementId":"req-request","passageId":"passage-source-1-1","disposition":"ASSIGNED"},{"requirementId":"req-common","passageId":"passage-source-1-2","disposition":"NO_MAPPING"}]}
        """;
  }

  private static String openQuestion() {
    return """
        {"outcome":"NEEDS_CLARIFICATION","transfers":[],"retainedPlaceholders":[],"coverage":[],"question":{"text":"Which port receives the body?","choiceKind":"UNSPECIFIED","sourceStepId":"trigger","sourcePort":"payload","sourceField":"","sourceRetainedId":"","targetStepId":"call","targetPort":"request","targetField":"","targetRetainedId":"","evidenceRefs":[]}}
        """;
  }

  private static String coveredQuestion() {
    return """
        {"outcome":"NEEDS_CLARIFICATION","transfers":[],"retainedPlaceholders":[],"coverage":[{"requirementId":"req-request","passageId":"passage-source-1-1","disposition":"ASSIGNED"}],"question":{"text":"Which port?","choiceKind":"UNSPECIFIED","sourceStepId":"trigger","sourcePort":"payload","sourceField":"","sourceRetainedId":"","targetStepId":"call","targetPort":"request","targetField":"","targetRetainedId":"","evidenceRefs":[]}}
        """;
  }

  private static String replyQuestion() {
    return """
        {"outcome":"NEEDS_CLARIFICATION","transfers":[],"retainedPlaceholders":[],"coverage":[],"question":{"text":"Use the reply?","choiceKind":"UNSPECIFIED","sourceStepId":"reply","sourcePort":"request","sourceField":"","sourceRetainedId":"","targetStepId":"call","targetPort":"request","targetField":"","targetRetainedId":"","evidenceRefs":[]}}
        """;
  }

  private static String defectWithTransfer() {
    return """
        {"outcome":"INPUT_DEFECT","transfers":[{"alias":"to-request","sourceStepId":"trigger","sourcePort":"payload","targetPort":"request","outcome":"UNSPECIFIED","requirementIds":[],"requiredRetainedIds":[],"decision":""}],"retainedPlaceholders":[],"coverage":[],"defect":{"recordRef":"call","category":"OUTLINE","contradiction":"The target has no accepted contract.","evidenceRefs":["source-1"]}}
        """;
  }

  private static String preparedWithDefect() {
    return """
        {"outcome":"PREPARED","transfers":[],"retainedPlaceholders":[],"coverage":[],"question":{"text":"","choiceKind":"UNSPECIFIED","sourceStepId":"","sourcePort":"","sourceField":"","sourceRetainedId":"","targetStepId":"","targetPort":"","targetField":"","targetRetainedId":"","evidenceRefs":[]},"defect":{"recordRef":"call","category":"OUTLINE","contradiction":"The target has no accepted contract.","evidenceRefs":[]}}
        """;
  }

  private static String questionDisposition() {
    return """
        {"outcome":"PREPARED","transfers":[{"alias":"to-request","sourceStepId":"trigger","sourcePort":"payload","targetPort":"request","outcome":"UNSPECIFIED","requirementIds":["req-request"],"requiredRetainedIds":[],"decision":""}],"retainedPlaceholders":[],"coverage":[{"requirementId":"req-request","passageId":"passage-source-1-1","disposition":"ASSIGNED"},{"requirementId":"req-common","passageId":"passage-source-1-2","disposition":"QUESTION"}]}
        """;
  }

  @Test
  void unknownAliasAndStaleRevisionLeaveNoPlaceholderOrTransferLink() {
    seed(callDocument());
    outlines.define(RUN_ID, "call", callRequestOutline(), contracts(), "outline-base");
    ChainWorkDocument before = documents.read(RUN_ID).document();
    String transferId = step(before, "call").data().transfers().get(0).id();
    int retainedBefore = retainedCount(before);
    int transfersBefore = step(before, "call").data().transfers().size();
    WorkDocumentState state = documents.read(RUN_ID);
    List<CreationAllowance> allowances =
        List.of(
            new CreationAllowance(WorkRecordKind.OUTLINE, "call"),
            new CreationAllowance(WorkRecordKind.RETAINED_VALUE, "trigger"));
    WorkTaskScope current =
        outlineScope(state.revision(), allowances, List.of(transferId));
    OutlineProposal unknown =
        new OutlineProposal(
            "call",
            List.of(
                new OutlineTransfer(
                    "",
                    transferId,
                    List.of(new PortRef("trigger", "payload")),
                    new PortRef("call", "request"),
                    TransferOutcome.UNSPECIFIED,
                    List.of("req-request"),
                    List.of("missing-alias"),
                    "")),
            List.of(new OutlineRetained("keep-process", "", "trigger", "process id", List.of("not-a-passage"))),
            List.of(
                new OutlineCoverage("req-request", "passage-source-1-1", CoverageDisposition.ASSIGNED),
                new OutlineCoverage("req-common", "passage-source-1-2", CoverageDisposition.NO_MAPPING)));
    WorkDocumentRejectedException alias =
        assertThrows(
            WorkDocumentRejectedException.class,
            () -> documents.applyOutline(RUN_ID, current, unknown, "cmd-unknown-alias"));
    assertEquals("MALFORMED_REFERENCE", alias.code());
    assertUnchangedOutline(before, transferId, retainedBefore, transfersBefore);

    WorkTaskScope stale = outlineScope("stale-revision", allowances, List.of(transferId));
    OutlineProposal fresh =
        new OutlineProposal(
            "call",
            List.of(
                new OutlineTransfer(
                    "",
                    transferId,
                    List.of(new PortRef("trigger", "payload")),
                    new PortRef("call", "request"),
                    TransferOutcome.UNSPECIFIED,
                    List.of("req-request"),
                    List.of("keep-process"),
                    "")),
            List.of(
                new OutlineRetained(
                    "keep-process", "", "trigger", "process id", List.of("passage-source-1-1"))),
            List.of(
                new OutlineCoverage("req-request", "passage-source-1-1", CoverageDisposition.ASSIGNED),
                new OutlineCoverage("req-common", "passage-source-1-2", CoverageDisposition.NO_MAPPING)));
    WorkDocumentRejectedException revision =
        assertThrows(
            WorkDocumentRejectedException.class,
            () -> documents.applyOutline(RUN_ID, stale, fresh, "cmd-stale-outline"));
    assertEquals("STALE_SCOPE", revision.code());
    assertUnchangedOutline(before, transferId, retainedBefore, transfersBefore);
  }

  @Test
  void repairOutlineDoesNotCreateATransferBesideTheOriginal() {
    seed(callDocument());
    outlines.define(RUN_ID, "call", callRequestOutline(), contracts(), "outline-repair-base");
    ChainWorkDocument current = documents.read(RUN_ID).document();
    String transferId = step(current, "call").data().transfers().get(0).id();
    documents.commitRecoveredDocument(
        RUN_ID,
        new ChainWorkDocument(
            current.schemaVersion(),
            current.documentId(),
            current.sources(),
            current.requirements(),
            current.flow(),
            current
                .progress()
                .withRepairs(
                    List.of(
                        new RepairAssignment(
                            "finding-outline",
                            "cause-outline",
                            transferId,
                            "MISSING_RETAINED",
                            "",
                            "The transfer has no retained declaration.",
                            "map-transfer:" + transferId,
                            "map-transfer:" + transferId,
                            WorkTaskKind.DEFINE_TRANSFERS,
                            "call",
                            List.of(transferId),
                            List.of("trigger"),
                            "prior:",
                            RepairAssignment.OWNER)))),
        "plant-repair",
        "plant-repair",
        "repair-assignment",
        "DATA_BEHAVIOR",
        null);
    String[] prompt = {""};
    WorkDataOutline proposing = proposing();
    WorkDocumentRejectedException rejected =
        assertThrows(
            WorkDocumentRejectedException.class,
            () ->
                proposing.propose(
                    RUN_ID,
                    "call",
                    outlineMaterials(),
                    contracts(),
                    request -> {
                      prompt[0] = request.prompt();
                      return """
                          {"outcome":"PREPARED","transfers":[{"existingId":"","alias":"extra","sourceStepId":"trigger","sourcePort":"payload","targetPort":"request","outcome":"UNSPECIFIED","requirementIds":["req-request"],"requiredRetainedIds":[],"decision":""}],"retainedPlaceholders":[],"coverage":[{"requirementId":"req-request","passageId":"passage-source-1-1","disposition":"ASSIGNED"},{"requirementId":"req-common","passageId":"passage-source-1-2","disposition":"NO_MAPPING"}]}
                          """;
                    },
                    "cmd-extra-transfer"));
    assertEquals("MALFORMED_REFERENCE", rejected.code());
    assertFalse(prompt[0].contains("allowed-create TRANSFER"), prompt[0]);
    assertTrue(prompt[0].contains("allowed-create RETAINED_VALUE trigger"), prompt[0]);
    assertTrue(prompt[0].contains("allowed-update " + transferId), prompt[0]);
    ChainWorkDocument after = documents.read(RUN_ID).document();
    assertEquals(1, step(after, "call").data().transfers().size());
    assertEquals(transferId, step(after, "call").data().transfers().get(0).id());
  }

  @Test
  void equalLabelsRepairByIdWithoutMergingInteractions() {
    seed(duplicateCalls());
    outlines.define(
        RUN_ID, "call-1", requestOutline("call-1", "req-1", "passage-source-1-1"), contracts(), "outline-call-1");
    outlines.define(
        RUN_ID, "call-2", requestOutline("call-2", "req-1", "passage-source-1-1"), contracts(), "outline-call-2");
    ChainWorkDocument outlined = documents.read(RUN_ID).document();
    LogicalStep first = step(outlined, "call-1");
    LogicalStep second = step(outlined, "call-2");
    String firstTransfer = first.data().transfers().get(0).id();
    String secondTransfer = second.data().transfers().get(0).id();
    assertEquals(first.label(), second.label());
    assertEquals(first.binding().operationId(), second.binding().operationId());
    assertNotEquals(firstTransfer, secondTransfer);
    outlines.define(
        RUN_ID,
        "call-1",
        new OutlineProposal(
            "call-1",
            List.of(
                new OutlineTransfer(
                    "",
                    firstTransfer,
                    List.of(new PortRef("trigger", "payload")),
                    new PortRef("call-1", "request"),
                    TransferOutcome.UNSPECIFIED,
                    List.of("req-1"),
                    List.of(),
                    "")),
            List.of(),
            List.of(new OutlineCoverage("req-1", "passage-source-1-1", CoverageDisposition.ASSIGNED))),
        contracts(),
        "repair-call-1");
    ChainWorkDocument repaired = documents.read(RUN_ID).document();
    LogicalStep repairedFirst = step(repaired, "call-1");
    LogicalStep repairedSecond = step(repaired, "call-2");
    assertEquals(firstTransfer, repairedFirst.data().transfers().get(0).id());
    assertEquals(secondTransfer, repairedSecond.data().transfers().get(0).id());
    assertEquals(1, repairedFirst.data().transfers().size());
    assertEquals(1, repairedSecond.data().transfers().size());
    assertEquals(repairedFirst.label(), repairedSecond.label());
    assertEquals(repairedFirst.binding().operationId(), repairedSecond.binding().operationId());
    assertEquals(3, repaired.flow().steps().size());
  }

  private void seed(ChainWorkDocument document) {
    documents.intake(RUN_ID, WorkDocumentState.of(document), "cmd-seed", new WorkRepairBudget(3));
  }

  private static ChainWorkDocument callDocument() {
    String content = "# Request\n\n" + REQUEST_TEXT + "\n\n# Response\n\n" + RESPONSE_TEXT + "\n";
    return document(
        content,
        List.of(
            passage("passage-source-1-1", REQUEST_TEXT, "Request"),
            passage("passage-source-1-2", RESPONSE_TEXT, "Response")),
        List.of(
            new WorkRequirement("req-request", REQUEST_TEXT, List.of("source-1"), ""),
            new WorkRequirement("req-common", RESPONSE_TEXT, List.of("source-1"), "")),
        List.of(
            triggerStep(),
            serviceStep("call", "Create task", List.of("req-request", "req-common")),
            replyStep()),
        List.of(link("c-request", "trigger", "request", "call"), link("c-success", "call", "success", "reply"), link("c-failure", "call", "failure", "reply")));
  }

  private static ChainWorkDocument duplicateCalls() {
    return document(
        REQUEST_TEXT,
        List.of(passage("passage-source-1-1", REQUEST_TEXT, "")),
        List.of(new WorkRequirement("req-1", REQUEST_TEXT, List.of("source-1"), "")),
        List.of(
            triggerStep(),
            serviceStep("call-1", "Create task", List.of("req-1")),
            serviceStep("call-2", "Create task", List.of("req-1"))),
        List.of(link("c-1", "trigger", "request", "call-1"), link("c-2", "trigger", "request", "call-2")));
  }

  private static ChainWorkDocument enrichedCall() {
    return document(
        REQUEST_TEXT,
        List.of(passage("passage-source-1-1", REQUEST_TEXT, "")),
        List.of(new WorkRequirement("req-request", REQUEST_TEXT, List.of("source-1"), "")),
        List.of(
            triggerStep(),
            new LogicalStep(
                "enrich",
                StepKind.LOCAL,
                "Enrich",
                "Local",
                List.of("source-1"),
                List.of(),
                null,
                StepData.empty()),
            serviceStep("call", "Create task", List.of("req-request"))),
        List.of(link("c-1", "trigger", "request", "enrich"), link("c-2", "enrich", "success", "call")));
  }

  private static ChainWorkDocument singleRequirement(String requirementId, String text, String passageId) {
    return document(
        text,
        List.of(passage(passageId, text, "")),
        List.of(new WorkRequirement(requirementId, text, List.of("source-1"), "")),
        List.of(triggerStep(), serviceStep("call", "Create task", List.of(requirementId)), replyStep()),
        List.of(link("c-request", "trigger", "request", "call")));
  }

  private static ChainWorkDocument document(
      String content,
      List<SourcePassage> passages,
      List<WorkRequirement> requirements,
      List<LogicalStep> steps,
      List<LogicalConnection> connections) {
    return new ChainWorkDocument(
        ChainWorkDocument.SCHEMA_VERSION,
        "doc-outline",
        List.of(
            new WorkSource(
                "source-1",
                "MESSAGE",
                "message:" + sha256(content),
                sha256(content),
                "message",
                "",
                List.of(),
                content,
                passages)),
        requirements,
        new LogicalFlow(steps, connections, List.of(), List.of(), List.of(), List.of(), List.of(), List.of()),
        WorkProgress.empty());
  }

  private static LogicalStep triggerStep() {
    return new LogicalStep(
        "trigger",
        StepKind.TRIGGER,
        "Start",
        "Receive",
        List.of("source-1"),
        List.of(),
        new ResolvedWorkBinding(
            "sys", "2024.4", "op-trigger", "http", "POST", "/in", List.of("spec-op-trigger"), List.of("payload")),
        StepData.empty());
  }

  private static LogicalStep serviceStep(String id, String label, List<String> requirementIds) {
    return new LogicalStep(
        id,
        StepKind.SERVICE_CALL,
        label,
        "Call",
        List.of("source-1"),
        requirementIds,
        new ResolvedWorkBinding(
            "sys", "2024.4", "op-call", "http", "POST", "/tasks", List.of("spec-op-call"), List.of("request", "success", "failure")),
        StepData.empty());
  }

  private static LogicalStep replyStep() {
    return new LogicalStep(
        "reply",
        StepKind.REPLY,
        "Reply",
        "Respond",
        List.of("source-1"),
        List.of(),
        new ResolvedWorkBinding(
            "sys", "2024.4", "op-reply", "http", "POST", "/out", List.of("spec-op-reply"), List.of("request")),
        StepData.empty());
  }

  private static LogicalConnection link(String id, String source, String outcome, String target) {
    return new LogicalConnection(id, source, outcome, target, "", List.of("source-1"));
  }

  private static SourcePassage passage(String id, String text, String heading) {
    return new SourcePassage(id, "source-1", sha256(text), text, heading);
  }

  private static OutlineProposal callRequestOutline() {
    return new OutlineProposal(
        "call",
        List.of(
            transfer(
                "to-request",
                "trigger",
                "payload",
                "call",
                "request",
                TransferOutcome.UNSPECIFIED,
                List.of("req-request"),
                List.of())),
        List.of(),
        List.of(
            new OutlineCoverage("req-request", "passage-source-1-1", CoverageDisposition.ASSIGNED),
            new OutlineCoverage("req-common", "passage-source-1-2", CoverageDisposition.NO_MAPPING)));
  }

  private static OutlineProposal requestOutline(String target, String requirementId, String passageId) {
    return new OutlineProposal(
        target,
        List.of(
            transfer(
                "to-request",
                "trigger",
                "payload",
                target,
                "request",
                TransferOutcome.UNSPECIFIED,
                List.of(requirementId),
                List.of())),
        List.of(),
        List.of(new OutlineCoverage(requirementId, passageId, CoverageDisposition.ASSIGNED)));
  }

  private static OutlineTransfer transfer(
      String alias,
      String sourceStep,
      String sourcePort,
      String targetStep,
      String targetPort,
      TransferOutcome outcome,
      List<String> requirementIds,
      List<String> retainedIds) {
    return new OutlineTransfer(
        alias,
        "",
        List.of(new PortRef(sourceStep, sourcePort)),
        new PortRef(targetStep, targetPort),
        outcome,
        requirementIds,
        retainedIds,
        "");
  }

  private static List<ContractMaterial> contracts() {
    return List.of(
        ready("spec-op-trigger", "op-trigger", "payload"),
        ready("spec-op-call", "op-call", "request", "success", "failure"),
        ready("spec-op-reply", "op-reply", "request"));
  }

  private static ContractMaterial.Ready ready(String reference, String operationId, String... ports) {
    return readyHashed(reference, operationId, "", ports);
  }

  private static ContractMaterial.Ready readyHashed(
      String reference, String operationId, String hashSuffix, String... ports) {
    List<PortSchemaMaterial> materials = new ArrayList<>();
    for (String port : ports) {
      ObjectNode schema = JSON.createObjectNode();
      schema.put("type", "object");
      schema.putObject("properties").putObject("id").put("type", "string");
      materials.add(
          new PortSchemaMaterial(
              reference, operationId, "2024.4", port, "hash-" + operationId + "-" + port + hashSuffix, schema));
    }
    return new ContractMaterial.Ready(reference, operationId, "2024.4", materials);
  }

  private static ChainWorkDocument replacePortHash(
      ChainWorkDocument document, String stepId, String port, String contentHash) {
    List<LogicalStep> steps = new ArrayList<>();
    for (LogicalStep step : document.flow().steps()) {
      if (!step.id().equals(stepId) || step.binding() == null) {
        steps.add(step);
        continue;
      }
      ResolvedWorkBinding prior = step.binding();
      List<ResolvedWorkBinding.PortContentHash> hashes = new ArrayList<>();
      boolean replaced = false;
      for (ResolvedWorkBinding.PortContentHash hash : prior.portContentHashes()) {
        if (hash.port().equals(port)) {
          hashes.add(new ResolvedWorkBinding.PortContentHash(port, contentHash));
          replaced = true;
        } else {
          hashes.add(hash);
        }
      }
      if (!replaced) {
        hashes.add(new ResolvedWorkBinding.PortContentHash(port, contentHash));
      }
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
                  hashes),
              step.data()));
    }
    LogicalFlow prior = document.flow();
    return new ChainWorkDocument(
        document.schemaVersion(),
        document.documentId(),
        document.sources(),
        document.requirements(),
        new LogicalFlow(
            steps,
            prior.connections(),
            prior.sequenceGroups(),
            prior.conditionGroups(),
            prior.splitGroups(),
            prior.loopGroups(),
            prior.retryGroups(),
            prior.errorScopeGroups()),
        document.progress());
  }

  private static WorkTaskPlanner.Task plannerTask(WorkTaskPlanner.Plan plan, String taskKey) {
    return plan.tasks().stream().filter(item -> item.taskKey().equals(taskKey)).findFirst().orElseThrow();
  }

  private static WorkTaskScope outlineScope(
      String revision, List<CreationAllowance> allowances, List<String> replacements) {
    return new WorkTaskScope(
        "define-transfers-call",
        revision,
        WorkStage.DATA_BEHAVIOR,
        WorkDataOutline.SKILL_ID,
        List.of("call"),
        true,
        true,
        false,
        List.of(),
        List.of(),
        allowances,
        replacements,
        "define-transfers:call",
        WorkTaskKind.DEFINE_TRANSFERS,
        "",
        null);
  }

  private static int retainedCount(ChainWorkDocument document) {
    int count = 0;
    for (LogicalStep step : document.flow().steps()) {
      count += step.data().retainedValues().size();
    }
    return count;
  }

  private void assertUnchangedOutline(
      ChainWorkDocument before, String transferId, int retainedBefore, int transfersBefore) {
    ChainWorkDocument after = documents.read(RUN_ID).document();
    LogicalStep call = step(after, "call");
    assertEquals(transfersBefore, call.data().transfers().size());
    assertEquals(transferId, call.data().transfers().get(0).id());
    assertTrue(call.data().transfers().get(0).requiredRetainedIds().isEmpty());
    assertEquals(retainedBefore, retainedCount(after));
    assertEquals(before.progress().approvalReference(), after.progress().approvalReference());
  }

  private static LogicalStep step(ChainWorkDocument document, String id) {
    return document.flow().steps().stream().filter(candidate -> candidate.id().equals(id)).findFirst().orElseThrow();
  }

  private static WorkSource source(ChainWorkDocument document, String id) {
    return document.sources().stream().filter(candidate -> candidate.id().equals(id)).findFirst().orElseThrow();
  }

  private static DataTransfer transfer(LogicalStep step, String port) {
    return step.data().transfers().stream()
        .filter(candidate -> port.equals(candidate.targetPort().portName()))
        .findFirst()
        .orElseThrow();
  }

  private static WorkTaskRecord task(ChainWorkDocument document, String taskKey) {
    return document.progress().tasks().stream().filter(candidate -> taskKey.equals(candidate.taskKey())).findFirst().orElseThrow();
  }

  private static String sha256(String content) {
    try {
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception failure) {
      throw new IllegalStateException(failure);
    }
  }
}
