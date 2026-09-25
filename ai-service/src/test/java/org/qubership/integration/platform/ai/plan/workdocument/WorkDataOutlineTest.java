package org.qubership.integration.platform.ai.plan.workdocument;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
  void instructionsForbidTaskKeysOrderingHandlersAndFieldRules() {
    String prompt = WorkDataOutline.instructions();
    assertTrue(prompt.contains("task key"));
    assertTrue(prompt.contains("ordering"));
    assertTrue(prompt.contains("handler"));
    assertTrue(prompt.contains("field rule"));
    assertFalse(prompt.contains("completeTask"));
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
