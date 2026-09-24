package org.qubership.integration.platform.ai.plan.workdocument;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.compiler.artifact.ArtifactBlobStore;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Reference;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.qubership.integration.platform.ai.compiler.artifact.StaleBlobVersionException;
import org.qubership.integration.platform.ai.compiler.artifact.VersionedBlob;
import org.qubership.integration.platform.ai.productpipeline.store.CommandPayloadConflictException;
import org.qubership.integration.platform.ai.productpipeline.store.LogicalCommit;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunDocument;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunStore;
import org.qubership.integration.platform.ai.productpipeline.store.RunSnapshot;
import org.qubership.integration.platform.ai.productpipeline.store.RunStatus;
import org.qubership.integration.platform.ai.productpipeline.store.RunTransition;
import org.qubership.integration.platform.ai.productpipeline.store.StageAttempt;
import org.qubership.integration.platform.ai.productpipeline.store.StageSnapshot;
import org.qubership.integration.platform.ai.productpipeline.store.StageStatus;

class WorkDocumentStoreTest {

  private static final Instant FIXED = Instant.parse("2026-09-24T12:00:00Z");
  private static final String RUN_ID = "run-work-1";
  private static final String CONVERSATION_ID = "conversation-work-1";

  private InMemoryArtifactBlobStore blobStore;
  private CompilationArtifacts artifacts;
  private ProductPipelineRunStore runStore;
  private WorkDocumentService documents;

  @BeforeEach
  void setUp() {
    ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    blobStore = new InMemoryArtifactBlobStore();
    Clock clock = Clock.fixed(FIXED, ZoneOffset.UTC);
    artifacts = new CompilationArtifacts(blobStore, mapper, clock);
    runStore = new ProductPipelineRunStore(blobStore, mapper, clock);
    documents = new WorkDocumentService(runStore, artifacts, mapper);
    runStore.create(openRun());
  }

  @Test
  void crashAfterPrepareDoesNotPublishTheArtifact() {
    WorkDocumentState initial = flowDocument();
    documents.intake(RUN_ID, initial, "cmd-intake", new WorkRepairBudget(3));
    Reference published = runStore.load(RUN_ID).orElseThrow().run().workDocumentRef();
    CrashBeforeCas crashing = new CrashBeforeCas(blobStore);
    WorkDocumentService failing =
        new WorkDocumentService(
            new ProductPipelineRunStore(crashing, mapper(), clock()), artifactsOn(crashing), mapper());
    crashing.failNextRunWrite = true;
    WorkDocumentState current = documents.read(RUN_ID);

    assertThrows(
        StaleBlobVersionException.class,
        () ->
            failing.apply(
                RUN_ID,
                scope(current),
                clarification("Which port?"),
                "cmd-crash",
                new WorkRepairBudget(2)));

    ProductPipelineRunDocument restored = runStore.load(RUN_ID).orElseThrow();
    assertEquals(published, restored.run().workDocumentRef());
    assertEquals(3, restored.run().workRepairBudget().repairsRemaining());
    assertEquals(1, restored.attempts().size());
    assertTrue(documents.read(RUN_ID).document().progress().questions().isEmpty());
    assertTrue(artifacts.history(RUN_ID, Kind.CHAIN_WORK_DOCUMENT).size() > 1);
    assertNotEquals(
        published,
        artifacts.latest(RUN_ID, Kind.CHAIN_WORK_DOCUMENT).orElseThrow().reference());
  }

  @Test
  void duplicateCommandReturnsTheSameResultAndChangedContentConflicts() {
    documents.intake(RUN_ID, flowDocument(), "cmd-intake", new WorkRepairBudget(3));
    WorkDocumentState current = documents.read(RUN_ID);
    WorkTaskCapture capture = clarification("Which port?");
    WorkCommit first =
        documents.apply(RUN_ID, scope(current), capture, "cmd-question", new WorkRepairBudget(2));
    long revision = runStore.load(RUN_ID).orElseThrow().run().runRevision();
    int attempts = runStore.load(RUN_ID).orElseThrow().attempts().size();

    WorkCommit replay =
        documents.apply(RUN_ID, scope(current), capture, "cmd-question", new WorkRepairBudget(2));

    assertEquals(first.documentRevision(), replay.documentRevision());
    assertEquals(first.outcome(), replay.outcome());
    assertEquals(first.commandId(), replay.commandId());
    assertEquals(first.runRevision(), replay.runRevision());
    assertEquals(revision, runStore.load(RUN_ID).orElseThrow().run().runRevision());
    assertEquals(attempts, runStore.load(RUN_ID).orElseThrow().attempts().size());

    assertThrows(
        CommandPayloadConflictException.class,
        () ->
            documents.apply(
                RUN_ID,
                scope(current),
                clarification("A different question?"),
                "cmd-question",
                new WorkRepairBudget(2)));
  }

  @Test
  void preparedCommandReplayReturnsTheSameAliasMapAndAcceptedIds() {
    documents.intake(RUN_ID, flowDocument(), "cmd-intake", new WorkRepairBudget(3));
    WorkDocumentState current = documents.read(RUN_ID);
    WorkTaskScope create = createScope(current);
    WorkTaskCapture capture = createdStep("extra", "Local step");
    WorkCommit first =
        documents.apply(RUN_ID, create, capture, "cmd-create", new WorkRepairBudget(3));
    assertFalse(first.aliasToId().isEmpty());
    assertFalse(first.acceptedRecordIds().isEmpty());

    WorkCommit replay =
        documents.apply(RUN_ID, create, capture, "cmd-create", new WorkRepairBudget(3));

    assertEquals(first.aliasToId(), replay.aliasToId());
    assertEquals(first.acceptedRecordIds(), replay.acceptedRecordIds());
    assertEquals(first, replay);
    assertThrows(
        CommandPayloadConflictException.class,
        () ->
            documents.apply(
                RUN_ID,
                create,
                createdStep("extra", "A different step"),
                "cmd-create",
                new WorkRepairBudget(3)));
  }

  @Test
  void staleProposalCannotOverwriteANewerDocument() {
    documents.intake(RUN_ID, flowDocument(), "cmd-intake", new WorkRepairBudget(3));
    WorkDocumentState current = documents.read(RUN_ID);
    WorkDocumentState newer = WorkDocumentState.create("doc-user");
    CompilationArtifacts.Revision userArtifact = append(newer.document(), null);
    ProductPipelineRunDocument atIntake = runStore.load(RUN_ID).orElseThrow();
    InterleavingBlobStore interleaving = new InterleavingBlobStore(blobStore);
    CompilationArtifacts interleavingArtifacts = artifactsOn(interleaving);
    ProductPipelineRunStore interleavingRuns =
        new ProductPipelineRunStore(interleaving, mapper(), clock());
    WorkDocumentService racing =
        new WorkDocumentService(interleavingRuns, interleavingArtifacts, mapper());
    interleaving.onWorkDocumentPut =
        () ->
            interleavingRuns.commit(
                atIntake.run().runRevision(),
                publication(
                    atIntake,
                    userArtifact.reference(),
                    new WorkRepairBudget(3),
                    "cmd-user",
                    "user-hash"));

    assertThrows(
        StaleBlobVersionException.class,
        () ->
            racing.apply(
                RUN_ID,
                scope(current),
                clarification("Old proposal"),
                "cmd-old",
                new WorkRepairBudget(1)));

    assertEquals(userArtifact.reference(), runStore.load(RUN_ID).orElseThrow().run().workDocumentRef());
    assertEquals("doc-user", documents.read(RUN_ID).document().documentId());
  }

  @Test
  void documentPointerTaskCompletionAndRepairBudgetAdvanceTogether() {
    documents.intake(RUN_ID, flowDocument(), "cmd-intake", new WorkRepairBudget(3));
    ProductPipelineRunDocument current = runStore.load(RUN_ID).orElseThrow();
    Reference pointer = current.run().workDocumentRef();
    int attempts = current.attempts().size();

    assertThrows(
        IllegalArgumentException.class,
        () ->
            runStore.commit(
                current.run().runRevision(),
                new LogicalCommit(
                    RUN_ID,
                    current.run().runRevision(),
                    RunStatus.RUNNING,
                    "LOGICAL_FLOW",
                    current.run().stages(),
                    attempt(current, List.of()),
                    transition(current, "cmd-budget", "hash-budget"),
                    null,
                    new WorkRepairBudget(1))));

    ProductPipelineRunDocument unchanged = runStore.load(RUN_ID).orElseThrow();
    assertEquals(pointer, unchanged.run().workDocumentRef());
    assertEquals(3, unchanged.run().workRepairBudget().repairsRemaining());
    assertEquals(attempts, unchanged.attempts().size());

    assertThrows(
        IllegalArgumentException.class,
        () ->
            runStore.commit(
                current.run().runRevision(),
                new LogicalCommit(
                    RUN_ID,
                    current.run().runRevision(),
                    RunStatus.RUNNING,
                    "LOGICAL_FLOW",
                    current.run().stages(),
                    attempt(current, List.of()),
                    transition(current, "cmd-pointer", "hash-pointer"),
                    pointer,
                    null)));

    LogicalCommit unrelated =
        new LogicalCommit(
            RUN_ID,
            current.run().runRevision(),
            RunStatus.RUNNING,
            "LOGICAL_FLOW",
            current.run().stages(),
            attempt(current, List.of()),
            transition(current, null, null));
    ProductPipelineRunDocument preserved = runStore.commit(current.run().runRevision(), unrelated);
    assertEquals(pointer, preserved.run().workDocumentRef());
    assertEquals(3, preserved.run().workRepairBudget().repairsRemaining());
  }

  @Test
  void restartPreservesDocumentQuestionsIdsAndRepairBudget() {
    WorkDocumentState flow = flowDocument();
    String stepId = flow.document().flow().steps().get(0).id();
    documents.intake(RUN_ID, flow, "cmd-intake", new WorkRepairBudget(3));
    WorkDocumentState current = documents.read(RUN_ID);
    documents.apply(
        RUN_ID, scope(current), clarification("Which port?"), "cmd-question", new WorkRepairBudget(2));

    append(WorkDocumentState.create("doc-decoy").document(), null);
    WorkDocumentService restarted =
        new WorkDocumentService(new ProductPipelineRunStore(blobStore, mapper(), clock()), artifacts, mapper());
    WorkDocumentState restored = restarted.read(RUN_ID);

    assertEquals(stepId, restored.document().flow().steps().get(0).id());
    assertEquals("Which port?", restored.document().progress().questions().get(0).question());
    assertEquals("doc-orders", restored.document().documentId());
    assertEquals(2, runStore.load(RUN_ID).orElseThrow().run().workRepairBudget().repairsRemaining());
    assertEquals(
        "doc-decoy",
        artifacts
            .payload(artifacts.latest(RUN_ID, Kind.CHAIN_WORK_DOCUMENT).orElseThrow(), ChainWorkDocument.class)
            .documentId());
  }

  private CompilationArtifacts.Revision append(
      ChainWorkDocument document, String revisesArtifactId) {
    return artifacts.append(
        new CompilationArtifacts.AppendCommand(
            RUN_ID,
            Kind.CHAIN_WORK_DOCUMENT,
            "1",
            "test",
            "1",
            document,
            List.of(),
            revisesArtifactId));
  }

  private static LogicalCommit publication(
      ProductPipelineRunDocument current,
      Reference reference,
      WorkRepairBudget budget,
      String commandId,
      String payloadHash) {
    long expected = current.run().runRevision();
    return new LogicalCommit(
        current.run().runId(),
        expected,
        RunStatus.WAITING_FOR_INPUT,
        "LOGICAL_FLOW",
        List.of(new StageSnapshot("LOGICAL_FLOW", StageStatus.WAITING_FOR_INPUT, List.of(reference), null)),
        new StageAttempt(
            "attempt-" + commandId,
            "LOGICAL_FLOW",
            expected + 1L,
            StageStatus.WAITING_FOR_INPUT,
            FIXED,
            FIXED,
            List.of(reference),
            null),
        new RunTransition(
            expected,
            expected + 1L,
            current.run().status(),
            RunStatus.WAITING_FOR_INPUT,
            "LOGICAL_FLOW",
            FIXED,
            "user input",
            commandId,
            payloadHash),
        reference,
        budget);
  }

  private static StageAttempt attempt(
      ProductPipelineRunDocument current, List<Reference> outputs) {
    return new StageAttempt(
        "attempt-extra",
        "LOGICAL_FLOW",
        current.run().runRevision() + 1L,
        StageStatus.RUNNING,
        FIXED,
        FIXED,
        outputs,
        null);
  }

  private static RunTransition transition(
      ProductPipelineRunDocument current, String commandId, String payloadHash) {
    long expected = current.run().runRevision();
    return new RunTransition(
        expected,
        expected + 1L,
        current.run().status(),
        RunStatus.RUNNING,
        "LOGICAL_FLOW",
        FIXED,
        "advance",
        commandId,
        payloadHash);
  }

  private static RunSnapshot openRun() {
    return new RunSnapshot(
        RUN_ID,
        CONVERSATION_ID,
        1L,
        RunStatus.RUNNING,
        "LOGICAL_FLOW",
        List.of(new StageSnapshot("LOGICAL_FLOW", StageStatus.RUNNING, List.of(), null)),
        null);
  }

  private static WorkDocumentState flowDocument() {
    return WorkDocumentFixture.grow(new WorkDocumentService()).afterFlow();
  }

  private static WorkTaskScope createScope(WorkDocumentState state) {
    return new WorkTaskScope(
        "create-task",
        state.revision(),
        WorkStage.LOGICAL_FLOW,
        "flow",
        List.of(),
        true,
        false,
        false,
        List.of(),
        List.of());
  }

  private static WorkTaskCapture createdStep(String alias, String intent) {
    return WorkTaskCapture.prepared(
        List.of(),
        List.of(
            new CapturedStep(
                "",
                alias,
                StepKind.LOCAL,
                "Extra",
                intent,
                List.of(WorkDocumentFixture.SOURCE_ID),
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
        List.of());
  }

  private static WorkTaskScope scope(WorkDocumentState state) {
    return new WorkTaskScope(
        "clarify-task",
        state.revision(),
        WorkStage.LOGICAL_FLOW,
        "clarify",
        List.of(),
        false,
        false,
        false,
        List.of(),
        List.of());
  }

  private static WorkTaskCapture clarification(String question) {
    return new WorkTaskCapture(
        WorkOutcome.NEEDS_CLARIFICATION,
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
        List.of(),
        List.of(),
        question,
        "a port",
        List.of(WorkDocumentFixture.SOURCE_ID),
        "",
        "",
        List.of(),
        "");
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

  private static final class InterleavingBlobStore implements ArtifactBlobStore {
    private final InMemoryArtifactBlobStore delegate;
    private Runnable onWorkDocumentPut;

    private InterleavingBlobStore(InMemoryArtifactBlobStore delegate) {
      this.delegate = delegate;
    }

    @Override
    public void put(String key, byte[] content) {
      delegate.put(key, content);
      if (onWorkDocumentPut != null && key.contains("chain_work_document")) {
        Runnable action = onWorkDocumentPut;
        onWorkDocumentPut = null;
        action.run();
      }
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
      delegate.putIfVersion(key, content, expectedVersion);
    }
  }
}
