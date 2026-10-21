package org.qubership.integration.platform.ai.productpipeline.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.qubership.integration.platform.ai.compiler.artifact.StaleBlobVersionException;
import org.qubership.integration.platform.ai.compiler.artifact.VersionedBlob;

/**
 * Durable command evidence lives on {@link RunTransition} and is written in the same
 * compare-and-set update as the transition it proves.
 */
class ProductPipelineCommandEvidenceTest {

  private static final Instant FIXED = Instant.parse("2026-08-04T10:00:00Z");
  private static final String CONVERSATION_ID = "conversation-1";
  private static final String RUN_ID = "run-1";
  private static final String RUN_KEY = "product-pipeline-runs/run-1.json";

  private InMemoryArtifactBlobStore blobStore;
  private ObjectMapper mapper;
  private ProductPipelineRunStore runStore;

  @BeforeEach
  void setUp() {
    mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    blobStore = new InMemoryArtifactBlobStore();
    runStore = new ProductPipelineRunStore(blobStore, mapper, Clock.fixed(FIXED, ZoneOffset.UTC));
  }

  @Test
  void legacyDocumentWithoutEvidenceFieldsLoads() throws Exception {
    ProductPipelineRunDocument created = runStore.create(snapshot(1L, RunStatus.RUNNING));
    runStore.commit(1L, commit(created, "cmd-legacy", "hash-legacy", RunStatus.WAITING_FOR_INPUT));

    stripEvidenceFieldsFromStoredDocument();

    ProductPipelineRunDocument loaded = runStore.load(RUN_ID).orElseThrow();
    RunTransition transition = loaded.transitions().get(loaded.transitions().size() - 1);
    assertNull(transition.commandId());
    assertNull(transition.commandPayloadHash());
    assertTrue(loaded.appliedCommand("cmd-legacy", "hash-legacy").isEmpty());
  }

  @Test
  void createRecordsInitialCommandEvidence() {
    ProductPipelineRunDocument created =
        runStore.create(snapshot(1L, RunStatus.RUNNING), "cmd-ensure-run", "hash-ensure-run");

    ProductPipelineRunDocument loaded = runStore.load(RUN_ID).orElseThrow();
    Optional<RunTransition> evidence = loaded.appliedCommand("cmd-ensure-run", "hash-ensure-run");
    assertTrue(evidence.isPresent());
    assertEquals(1L, evidence.orElseThrow().toRevision());
    assertEquals(created.run().runId(), loaded.run().runId());
  }

  @Test
  void commitRecordsTransitionAndEvidenceUnderOneCasVersion() {
    ProductPipelineRunDocument created = runStore.create(snapshot(1L, RunStatus.RUNNING));
    long versionBefore = storedBlobVersion();

    runStore.commit(1L, commit(created, "cmd-accept", "hash-accept", RunStatus.RUNNING));

    ProductPipelineRunDocument loaded = runStore.load(RUN_ID).orElseThrow();
    assertEquals(2L, loaded.run().runRevision());
    assertEquals(versionBefore + 1L, storedBlobVersion(), "transition and evidence need one write");
    RunTransition transition = loaded.appliedCommand("cmd-accept", "hash-accept").orElseThrow();
    assertEquals(2L, transition.toRevision());
  }

  @Test
  void appliedCommandRejectsDifferentPayloadHash() {
    ProductPipelineRunDocument created = runStore.create(snapshot(1L, RunStatus.RUNNING));
    runStore.commit(1L, commit(created, "cmd-accept", "hash-original", RunStatus.RUNNING));

    ProductPipelineRunDocument loaded = runStore.load(RUN_ID).orElseThrow();
    assertThrows(
        CommandPayloadConflictException.class,
        () -> loaded.appliedCommand("cmd-accept", "hash-different"));
  }

  @Test
  void unknownCommandIsNotApplied() {
    ProductPipelineRunDocument created = runStore.create(snapshot(1L, RunStatus.RUNNING));
    runStore.commit(1L, commit(created, "cmd-accept", "hash-accept", RunStatus.RUNNING));

    ProductPipelineRunDocument loaded = runStore.load(RUN_ID).orElseThrow();
    assertTrue(loaded.appliedCommand("cmd-other", "hash-other").isEmpty());
  }

  @Test
  void failedCasLeavesNoEvidenceForUncommittedTransition() {
    ProductPipelineRunDocument created = runStore.create(snapshot(1L, RunStatus.RUNNING));
    runStore.commit(1L, commit(created, "cmd-winner", "hash-winner", RunStatus.RUNNING));

    assertThrows(
        StaleBlobVersionException.class,
        () -> runStore.commit(1L, commit(created, "cmd-loser", "hash-loser", RunStatus.FAILED)));

    ProductPipelineRunDocument loaded = runStore.load(RUN_ID).orElseThrow();
    assertTrue(loaded.appliedCommand("cmd-winner", "hash-winner").isPresent());
    assertTrue(loaded.appliedCommand("cmd-loser", "hash-loser").isEmpty());
    assertEquals(RunStatus.RUNNING, loaded.run().status());
  }

  @Test
  void twoAttemptsAtSameRevisionProduceOneCommittedTransition() {
    ProductPipelineRunDocument created = runStore.create(snapshot(1L, RunStatus.RUNNING));

    runStore.commit(1L, commit(created, "cmd-same", "hash-same", RunStatus.RUNNING));
    assertThrows(
        StaleBlobVersionException.class,
        () -> runStore.commit(1L, commit(created, "cmd-same", "hash-same", RunStatus.RUNNING)));

    ProductPipelineRunDocument loaded = runStore.load(RUN_ID).orElseThrow();
    assertEquals(
        1L, loaded.transitions().stream().filter(t -> "cmd-same".equals(t.commandId())).count());
  }

  private void stripEvidenceFieldsFromStoredDocument() throws Exception {
    VersionedBlob stored = blobStore.getVersioned(RUN_KEY).orElseThrow();
    ObjectNode document = (ObjectNode) mapper.readTree(stored.content());
    ArrayNode transitions = (ArrayNode) document.get("transitions");
    transitions.forEach(
        node -> ((ObjectNode) node).remove(List.of("commandId", "commandPayloadHash")));
    blobStore.putIfVersion(RUN_KEY, mapper.writeValueAsBytes(document), stored.version());
  }

  private long storedBlobVersion() {
    return Long.parseLong(
        blobStore.getVersioned(RUN_KEY).map(VersionedBlob::version).orElseThrow());
  }

  private static LogicalCommit commit(
      ProductPipelineRunDocument current,
      String commandId,
      String payloadHash,
      RunStatus nextStatus) {
    long expected = current.run().runRevision();
    long next = expected + 1L;
    return new LogicalCommit(
        current.run().runId(),
        expected,
        nextStatus,
        "collect",
        List.of(new StageSnapshot("collect", StageStatus.RUNNING, List.of(), null)),
        new StageAttempt(
            "attempt-" + next, "collect", next, StageStatus.RUNNING, FIXED, FIXED, List.of(), null),
        new RunTransition(
            expected,
            next,
            current.run().status(),
            nextStatus,
            "collect",
            FIXED,
            "advance",
            commandId,
            payloadHash));
  }

  private static RunSnapshot snapshot(long revision, RunStatus status) {
    return new RunSnapshot(
        RUN_ID,
        CONVERSATION_ID,
        revision,
        status,
        "collect",
        List.of(new StageSnapshot("collect", StageStatus.RUNNING, List.of(), null)),
        null);
  }
}
