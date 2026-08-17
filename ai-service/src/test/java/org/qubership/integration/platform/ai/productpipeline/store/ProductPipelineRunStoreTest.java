package org.qubership.integration.platform.ai.productpipeline.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.AppendCommand;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Revision;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.qubership.integration.platform.ai.compiler.artifact.StaleBlobVersionException;
import org.qubership.integration.platform.ai.productpipeline.artifact.ArtifactProvenance;
import org.qubership.integration.platform.ai.productpipeline.artifact.ProductPipelineArtifactStore;
import org.qubership.integration.platform.ai.productpipeline.artifact.UserInput;

class ProductPipelineRunStoreTest {

  private static final Instant FIXED = Instant.parse("2026-07-22T10:00:00Z");
  private static final String CONVERSATION_ID = "conversation-1";

  private InMemoryArtifactBlobStore blobStore;
  private CompilationArtifacts artifacts;
  private ProductPipelineArtifactStore artifactStore;
  private ProductPipelineRunStore runStore;

  @BeforeEach
  void setUp() {
    ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    blobStore = new InMemoryArtifactBlobStore();
    artifacts =
        new CompilationArtifacts(
            blobStore, mapper, Clock.fixed(FIXED, ZoneOffset.UTC));
    artifactStore = new ProductPipelineArtifactStore(artifacts);
    runStore =
        new ProductPipelineRunStore(
            blobStore, mapper, Clock.fixed(FIXED, ZoneOffset.UTC));
  }

  @Test
  void rejectsSecondWriterAtSameRunRevision() {
    ProductPipelineRunDocument created = runStore.create(sampleSnapshot(1L, RunStatus.RUNNING));
    advanceToRevision(created, 3L);

    ProductPipelineRunDocument atRev3 = runStore.load(created.run().runId()).orElseThrow();
    assertEquals(3L, atRev3.run().runRevision());

    LogicalCommit first =
        sampleCommit(
            atRev3,
            "attempt-a",
            RunStatus.WAITING_FOR_APPROVAL,
            StageStatus.WAITING_FOR_APPROVAL);
    LogicalCommit second =
        sampleCommit(
            atRev3,
            "attempt-b",
            RunStatus.FAILED,
            StageStatus.FAILED);

    ProductPipelineRunDocument winner = runStore.commit(3L, first);
    assertEquals(4L, winner.run().runRevision());

    assertThrows(StaleBlobVersionException.class, () -> runStore.commit(3L, second));
    ProductPipelineRunDocument latest = runStore.load(created.run().runId()).orElseThrow();
    assertEquals(4L, latest.run().runRevision());
    assertEquals(RunStatus.WAITING_FOR_APPROVAL, latest.run().status());
    assertTrue(
        latest.attempts().stream().anyMatch(a -> "attempt-a".equals(a.attemptId())));
    assertTrue(
        latest.attempts().stream().noneMatch(a -> "attempt-b".equals(a.attemptId())));
  }

  @Test
  void createAndCommitPreserveFlowInstanceId() {
    RunSnapshot created =
        new RunSnapshot(
            "run-flow-1",
            CONVERSATION_ID,
            1L,
            RunStatus.RUNNING,
            "collect",
            List.of(new StageSnapshot("collect", StageStatus.RUNNING, List.of(), null)),
            null,
            "flow-instance-1");
    ProductPipelineRunDocument stored = runStore.create(created);
    assertEquals("flow-instance-1", stored.run().flowInstanceId());

    ProductPipelineRunDocument afterCommit = runStore.commit(1L, sampleCommit(stored, "a1", RunStatus.WAITING_FOR_INPUT, StageStatus.WAITING_FOR_INPUT));
    assertEquals("flow-instance-1", afterCommit.run().flowInstanceId());
    assertEquals("flow-instance-1", runStore.load("run-flow-1").orElseThrow().run().flowInstanceId());
  }

  @Test
  void commitAddsOutputsAttemptTransitionAndRevisionTogether() {
    ProductPipelineRunDocument created = runStore.create(sampleSnapshot(1L, RunStatus.RUNNING));
    advanceToRevision(created, 3L);
    ProductPipelineRunDocument atRev3 = runStore.load(created.run().runId()).orElseThrow();

    Revision output =
        artifactStore.append(
            new AppendCommand(
                created.run().runId(),
                Kind.USER_INPUT,
                "1",
                "test",
                "1",
                new UserInput("i1", "collect", "hello", FIXED),
                List.of(),
                null,
                provenance(created.run().runId())));

    LogicalCommit commit =
        new LogicalCommit(
            created.run().runId(),
            3L,
            RunStatus.WAITING_FOR_APPROVAL,
            "collect",
            List.of(
                new StageSnapshot(
                    "collect",
                    StageStatus.WAITING_FOR_APPROVAL,
                    List.of(output.reference()),
                    null)),
            new StageAttempt(
                "attempt-4",
                "collect",
                4L,
                StageStatus.WAITING_FOR_APPROVAL,
                FIXED,
                FIXED,
                List.of(output.reference()),
                null),
            new RunTransition(
                3L,
                4L,
                RunStatus.RUNNING,
                RunStatus.WAITING_FOR_APPROVAL,
                "collect",
                FIXED,
                "await approval"));

    ProductPipelineRunDocument committed = runStore.commit(3L, commit);
    assertEquals(4L, committed.run().runRevision());
    assertEquals(RunStatus.WAITING_FOR_APPROVAL, committed.run().status());
    assertEquals(List.of(output.reference()), committed.run().stages().get(0).outputRefs());
    assertEquals("attempt-4", committed.attempts().get(committed.attempts().size() - 1).attemptId());
    assertEquals(
        4L,
        committed.transitions().get(committed.transitions().size() - 1).toRevision());
    assertTrue(
        committed.attempts().stream()
            .anyMatch(a -> a.outputs().contains(output.reference())));
  }

  @Test
  void orphanArtifactIsInvisibleAfterFailedCommit() {
    ProductPipelineRunDocument created = runStore.create(sampleSnapshot(1L, RunStatus.RUNNING));
    Revision orphan =
        artifactStore.append(
            new AppendCommand(
                created.run().runId(),
                Kind.USER_INPUT,
                "1",
                "test",
                "1",
                new UserInput("orphan", "collect", "lost", FIXED),
                List.of(),
                null,
                provenance(created.run().runId())));

    LogicalCommit stale =
        new LogicalCommit(
            created.run().runId(),
            99L,
            RunStatus.WAITING_FOR_INPUT,
            "collect",
            List.of(
                new StageSnapshot(
                    "collect",
                    StageStatus.WAITING_FOR_INPUT,
                    List.of(orphan.reference()),
                    null)),
            new StageAttempt(
                "orphan-attempt",
                "collect",
                100L,
                StageStatus.WAITING_FOR_INPUT,
                FIXED,
                FIXED,
                List.of(orphan.reference()),
                null),
            new RunTransition(
                99L,
                100L,
                RunStatus.RUNNING,
                RunStatus.WAITING_FOR_INPUT,
                "collect",
                FIXED,
                "stale"));

    assertThrows(StaleBlobVersionException.class, () -> runStore.commit(99L, stale));

    ProductPipelineRunDocument loaded = runStore.load(created.run().runId()).orElseThrow();
    assertTrue(
        loaded.run().stages().stream()
            .flatMap(stage -> stage.outputRefs().stream())
            .noneMatch(ref -> ref.equals(orphan.reference())));
    assertTrue(loaded.attempts().isEmpty());
  }

  @Test
  void recreationLoadsWaitingStateAndCompleteHistory() {
    ProductPipelineRunDocument created = runStore.create(sampleSnapshot(1L, RunStatus.RUNNING));
    advanceToRevision(created, 2L);
    ProductPipelineRunDocument atRev2 = runStore.load(created.run().runId()).orElseThrow();

    LogicalCommit commit =
        sampleCommit(
            atRev2,
            "attempt-wait",
            RunStatus.WAITING_FOR_APPROVAL,
            StageStatus.WAITING_FOR_APPROVAL);
    runStore.commit(2L, commit);

    ProductPipelineRunStore recreated =
        new ProductPipelineRunStore(
            blobStore,
            new ObjectMapper().registerModule(new JavaTimeModule()),
            Clock.fixed(FIXED, ZoneOffset.UTC));

    ProductPipelineRunDocument byId = recreated.load(created.run().runId()).orElseThrow();
    ProductPipelineRunDocument byConversation =
        recreated.loadByConversation(CONVERSATION_ID).orElseThrow();

    assertEquals(RunStatus.WAITING_FOR_APPROVAL, byId.run().status());
    assertEquals(3L, byId.run().runRevision());
    assertEquals(2, byId.attempts().size());
    assertEquals(2, byId.transitions().size());
    assertEquals(byId.run().runId(), byConversation.run().runId());
    assertNotEquals(0, byId.blobVersion().length());
  }

  private void advanceToRevision(ProductPipelineRunDocument created, long targetRevision) {
    AtomicReference<ProductPipelineRunDocument> current = new AtomicReference<>(created);
    while (current.get().run().runRevision() < targetRevision) {
      long expected = current.get().run().runRevision();
      LogicalCommit bump =
          sampleCommit(
              current.get(),
              "attempt-" + expected,
              RunStatus.RUNNING,
              StageStatus.RUNNING);
      current.set(runStore.commit(expected, bump));
    }
  }

  private static LogicalCommit sampleCommit(
      ProductPipelineRunDocument current,
      String attemptId,
      RunStatus nextStatus,
      StageStatus stageStatus) {
    long expected = current.run().runRevision();
    long next = expected + 1L;
    return new LogicalCommit(
        current.run().runId(),
        expected,
        nextStatus,
        "collect",
        List.of(new StageSnapshot("collect", stageStatus, List.of(), null)),
        new StageAttempt(
            attemptId, "collect", next, stageStatus, FIXED, FIXED, List.of(), null),
        new RunTransition(
            expected,
            next,
            current.run().status(),
            nextStatus,
            "collect",
            FIXED,
            "advance"));
  }

  private static RunSnapshot sampleSnapshot(long revision, RunStatus status) {
    return new RunSnapshot(
        "run-1",
        CONVERSATION_ID,
        revision,
        status,
        "collect",
        List.of(new StageSnapshot("collect", StageStatus.RUNNING, List.of(), null)),
        null);
  }

  private static ArtifactProvenance provenance(String runId) {
    return new ArtifactProvenance(
        runId, "collect", "create-plan", "1", "profile-sha", "cap", "1", "closure-sha");
  }
}
