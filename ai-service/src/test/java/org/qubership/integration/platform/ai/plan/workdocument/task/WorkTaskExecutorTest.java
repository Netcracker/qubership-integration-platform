package org.qubership.integration.platform.ai.plan.workdocument.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Reference;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.qubership.integration.platform.ai.plan.workdocument.ChainWorkDocument;
import org.qubership.integration.platform.ai.plan.workdocument.WorkCommit;
import org.qubership.integration.platform.ai.plan.workdocument.WorkDocumentRejectedException;
import org.qubership.integration.platform.ai.plan.workdocument.WorkDocumentService;
import org.qubership.integration.platform.ai.plan.workdocument.WorkDocumentState;
import org.qubership.integration.platform.ai.plan.workdocument.WorkRepairBudget;
import org.qubership.integration.platform.ai.plan.workdocument.WorkStage;
import org.qubership.integration.platform.ai.plan.workdocument.WorkTaskScope;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunDocument;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunStore;
import org.qubership.integration.platform.ai.productpipeline.store.RunSnapshot;
import org.qubership.integration.platform.ai.productpipeline.store.RunStatus;
import org.qubership.integration.platform.ai.productpipeline.store.StageSnapshot;
import org.qubership.integration.platform.ai.productpipeline.store.StageStatus;

class WorkTaskExecutorTest {

  private static final Instant FIXED = Instant.parse("2026-09-24T12:00:00Z");
  private static final String RUN_ID = "run-task-1";
  private static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule());

  private ProductPipelineRunStore runs;
  private WorkDocumentService documents;
  private WorkTaskExecutor executor;

  @BeforeEach
  void setUp() throws Exception {
    InMemoryArtifactBlobStore blobs = new InMemoryArtifactBlobStore();
    Clock clock = Clock.fixed(FIXED, ZoneOffset.UTC);
    CompilationArtifacts artifacts = new CompilationArtifacts(blobs, JSON, clock);
    runs = new ProductPipelineRunStore(blobs, JSON, clock);
    documents = new WorkDocumentService(runs, artifacts, JSON);
    runs.create(openRun());
    ChainWorkDocument body = JSON.readValue(WorkTaskContextTest.documentJson(), ChainWorkDocument.class);
    documents.intake(RUN_ID, new WorkDocumentState("pending", body), "cmd-intake", new WorkRepairBudget(3));
    executor = new WorkTaskExecutor(documents, runs, clock);
  }

  @Test
  void malformedOutputDoesNotCompleteTheTask() {
    Reference before = runs.load(RUN_ID).orElseThrow().run().workDocumentRef();
    WorkTaskScope scope = scope();

    assertThrows(
        WorkDocumentRejectedException.class,
        () -> executor.execute(RUN_ID, scope, materials(), request -> "not-json"));
    assertThrows(
        WorkDocumentRejectedException.class,
        () ->
            executor.execute(
                RUN_ID,
                scope,
                materials(),
                request ->
                    "{\"outcome\":\"PREPARED\",\"question\":\"Which port?\",\"rules\":[]}"));

    ProductPipelineRunDocument run = runs.load(RUN_ID).orElseThrow();
    assertEquals(before, run.run().workDocumentRef());
    assertEquals(3, run.run().workRepairBudget().repairsRemaining());
    assertTrue(questions(documents.read(RUN_ID)).isEmpty());
    assertTrue(ruleBehaviors(documents.read(RUN_ID)).contains("RELATED_RULE_BEHAVIOR"));
  }

  @Test
  void incompletePreparedCaptureDoesNotPublish() {
    Reference before = runs.load(RUN_ID).orElseThrow().run().workDocumentRef();

    assertThrows(
        WorkDocumentRejectedException.class,
        () -> executor.execute(RUN_ID, scope(), materials(), request -> "{\"outcome\":\"PREPARED\"}"));

    assertEquals(before, runs.load(RUN_ID).orElseThrow().run().workDocumentRef());
    assertTrue(questions(documents.read(RUN_ID)).isEmpty());
  }

  @Test
  void replayReturnsTheAttemptDocumentAfterALaterCommand() {
    WorkTaskScope original = scope();
    WorkCommit first =
        executor.execute(
            RUN_ID,
            original,
            materials(),
            request -> completeClarification());
    WorkDocumentState laterBase = documents.read(RUN_ID);
    documents.apply(
        RUN_ID,
        new WorkTaskScope(
            "later-task",
            laterBase.revision(),
            WorkStage.DATA_BEHAVIOR,
            "mapping",
            List.of(),
            false,
            false,
            false,
            List.of(),
            List.of()),
        org.qubership.integration.platform.ai.plan.workdocument.WorkDocumentCaptureSchema.parse(
            """
            {"outcome":"NEEDS_CLARIFICATION","question":"A later question?","unresolvedChoice":"later choice","clarificationEvidenceIds":["source-gov"]}
            """),
        "cmd-later");

    WorkCommit replay = executor.execute(RUN_ID, original, materials(), request -> {
      throw new AssertionError("published command must not call the model");
    });

    assertEquals(first.documentRevision(), replay.documentRevision());
    assertEquals(first.state().revision(), replay.state().revision());
    assertEquals("Which failure port?", questions(replay.state()).get(0).path("question").asText());
    assertEquals("A later question?", questions(documents.read(RUN_ID)).get(1).path("question").asText());
  }

  @Test
  void clarificationKeepsAcceptedWorkAndOneQuestion() {
    WorkCommit commit =
        executor.execute(
            RUN_ID,
            scope(),
            materials(),
            request -> completeClarification());

    JsonNode questions = questions(commit.state());
    assertEquals(1, questions.size());
    assertEquals("Which failure port?", questions.get(0).path("question").asText());
    assertTrue(ruleBehaviors(commit.state()).contains("RELATED_RULE_BEHAVIOR"));
    assertTrue(ruleBehaviors(commit.state()).contains("UNRELATED_RULE_BEHAVIOR"));
    assertEquals(2, steps(commit.state()).size());
  }

  @Test
  void inputDefectRecordsEvidenceAndRejectsChosenStage() {
    WorkTaskScope scope = scope();
    assertThrows(
        WorkDocumentRejectedException.class,
        () ->
            executor.execute(
                RUN_ID,
                scope,
                materials(),
                request ->
                    """
                    {"outcome":"INPUT_DEFECT","defectRecordRef":"trigger","contradiction":"The trigger contradicts the source.","defectEvidenceIds":["source-gov"],"issueCategory":"CONTRADICTION","stage":"MATERIALIZATION"}
                    """));
    assertTrue(findings(documents.read(RUN_ID)).isEmpty());
    assertEquals("DATA_BEHAVIOR", runs.load(RUN_ID).orElseThrow().run().currentStageId());

    WorkCommit commit =
        executor.execute(
            RUN_ID,
            scope,
            materials(),
            request -> completeDefect());

    JsonNode finding = findings(commit.state()).get(0);
    assertEquals("trigger", finding.path("recordRef").asText());
    assertEquals("source-gov", finding.path("evidenceIds").get(0).asText());
    assertEquals("DATA_BEHAVIOR", commit.state().document() == null ? "" : runs.load(RUN_ID).orElseThrow().run().currentStageId());
    assertEquals("DATA_BEHAVIOR", tasks(commit.state()).get(0).path("stage").asText());
  }

  @Test
  void restartRepeatsUnfinishedCallWithoutDuplicatePublication() {
    AtomicInteger calls = new AtomicInteger();
    WorkTaskModel model =
        request -> {
          if (calls.incrementAndGet() == 1) {
            throw new IllegalStateException("provider dropped");
          }
          return completeClarification();
        };
    WorkTaskScope scope = scope();
    assertThrows(
        IllegalStateException.class, () -> executor.execute(RUN_ID, scope, materials(), model));
    Reference during = runs.load(RUN_ID).orElseThrow().run().workDocumentRef();

    WorkTaskExecutor restarted = new WorkTaskExecutor(documents, runs, Clock.fixed(FIXED, ZoneOffset.UTC));
    WorkCommit first = restarted.execute(RUN_ID, scope, materials(), model);
    long revision = runs.load(RUN_ID).orElseThrow().run().runRevision();
    int attempts = runs.load(RUN_ID).orElseThrow().attempts().size();
    Reference published = runs.load(RUN_ID).orElseThrow().run().workDocumentRef();

    WorkCommit replay = restarted.execute(RUN_ID, scope, materials(), model);

    assertEquals(2, calls.get());
    assertEquals(first.documentRevision(), replay.documentRevision());
    assertEquals(first.runRevision(), replay.runRevision());
    assertEquals(revision, runs.load(RUN_ID).orElseThrow().run().runRevision());
    assertEquals(attempts, runs.load(RUN_ID).orElseThrow().attempts().size());
    assertEquals(1, questions(documents.read(RUN_ID)).size());
    assertTrue(!published.equals(during));
  }

  private WorkTaskScope scope() {
    WorkDocumentState current = documents.read(RUN_ID);
    return new WorkTaskScope(
        "map-transfer-a",
        current.revision(),
        WorkStage.DATA_BEHAVIOR,
        "mapping",
        List.of("transfer-a"),
        false,
        true,
        false,
        List.of(),
        List.of());
  }

  private static final String CAPTURE_LISTS =
      """
      "requirements":[],"steps":[],"connections":[],"sequenceGroups":[],"conditionGroups":[],"splitGroups":[],"loopGroups":[],"retryGroups":[],"errorScopeGroups":[],"transfers":[],"rules":[],"retainedValues":[],"deletes":[]
      """;

  private static String completeClarification() {
    return "{"
        + "\"outcome\":\"NEEDS_CLARIFICATION\","
        + CAPTURE_LISTS
        + ",\"question\":\"Which failure port?\","
        + "\"unresolvedChoice\":\"success or failure port\","
        + "\"clarificationEvidenceIds\":[\"source-gov\"],"
        + "\"defectRecordRef\":\"\","
        + "\"contradiction\":\"\","
        + "\"defectEvidenceIds\":[],"
        + "\"issueCategory\":\"\""
        + "}";
  }

  private static String completeDefect() {
    return "{"
        + "\"outcome\":\"INPUT_DEFECT\","
        + CAPTURE_LISTS
        + ",\"question\":\"\","
        + "\"unresolvedChoice\":\"\","
        + "\"clarificationEvidenceIds\":[],"
        + "\"defectRecordRef\":\"trigger\","
        + "\"contradiction\":\"The trigger contradicts the source.\","
        + "\"defectEvidenceIds\":[\"source-gov\"],"
        + "\"issueCategory\":\"CONTRADICTION\""
        + "}";
  }

  private static WorkTaskMaterials materials() {
    return new WorkTaskMaterials(List.of(), List.of("runtime-catalog-only"), Map.of());
  }

  private static RunSnapshot openRun() {
    return new RunSnapshot(
        RUN_ID,
        "conversation-task-1",
        1L,
        RunStatus.RUNNING,
        "DATA_BEHAVIOR",
        List.of(new StageSnapshot("DATA_BEHAVIOR", StageStatus.RUNNING, List.of(), null)),
        null);
  }

  private static JsonNode questions(WorkDocumentState state) {
    return JSON.valueToTree(state.document()).path("progress").path("questions");
  }

  private static JsonNode findings(WorkDocumentState state) {
    return JSON.valueToTree(state.document()).path("progress").path("findings");
  }

  private static JsonNode tasks(WorkDocumentState state) {
    return JSON.valueToTree(state.document()).path("progress").path("tasks");
  }

  private static JsonNode steps(WorkDocumentState state) {
    return JSON.valueToTree(state.document()).path("flow").path("steps");
  }

  private static String ruleBehaviors(WorkDocumentState state) {
    return JSON.valueToTree(state.document()).toString();
  }
}
