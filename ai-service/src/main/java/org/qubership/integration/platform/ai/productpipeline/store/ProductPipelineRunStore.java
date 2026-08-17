package org.qubership.integration.platform.ai.productpipeline.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.qubership.integration.platform.ai.compiler.artifact.ArtifactBlobStore;
import org.qubership.integration.platform.ai.compiler.artifact.StaleBlobVersionException;
import org.qubership.integration.platform.ai.compiler.artifact.VersionedBlob;

/**
 * Persists one authoritative product-pipeline run document behind compare-and-set blob writes.
 */
public final class ProductPipelineRunStore {

  private static final String RUN_PREFIX = "product-pipeline-runs/";
  private static final String CONVERSATION_PREFIX = "product-pipeline-conversations/";

  private final ArtifactBlobStore blobStore;
  private final ObjectMapper objectMapper;
  private final Clock clock;

  public ProductPipelineRunStore(
      ArtifactBlobStore blobStore, ObjectMapper objectMapper, Clock clock) {
    this.blobStore = Objects.requireNonNull(blobStore, "blobStore");
    this.objectMapper =
        Objects.requireNonNull(objectMapper, "objectMapper")
            .copy()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  public ProductPipelineRunDocument create(RunSnapshot snapshot) {
    return create(snapshot, null, null);
  }

  /**
   * Creates the run and records durable evidence that {@code commandId} caused it, in the same
   * create-only write.
   */
  public ProductPipelineRunDocument create(
      RunSnapshot snapshot, String commandId, String commandPayloadHash) {
    Objects.requireNonNull(snapshot, "snapshot");
    requireText(snapshot.runId(), "runId");
    requireText(snapshot.conversationId(), "conversationId");
    if (snapshot.runRevision() < 1L) {
      throw new IllegalArgumentException("runRevision must be >= 1");
    }

    List<RunTransition> initialTransitions =
        commandId == null || commandId.isBlank()
            ? List.of()
            : List.of(
                new RunTransition(
                    0L,
                    snapshot.runRevision(),
                    snapshot.status(),
                    snapshot.status(),
                    snapshot.currentStageId(),
                    clock.instant(),
                    "created",
                    commandId,
                    commandPayloadHash));
    ProductPipelineRunDocument document =
        new ProductPipelineRunDocument(snapshot, List.of(), initialTransitions, null);
    byte[] payload = write(document);
    try {
      blobStore.putIfVersion(runKey(snapshot.runId()), payload, null);
    } catch (StaleBlobVersionException e) {
      throw new StaleBlobVersionException("run already exists: " + snapshot.runId(), e);
    }
    try {
      blobStore.putIfVersion(
          conversationKey(snapshot.conversationId()),
          snapshot.runId().getBytes(StandardCharsets.UTF_8),
          null);
    } catch (StaleBlobVersionException e) {
      throw new StaleBlobVersionException(
          "conversation already bound: " + snapshot.conversationId(), e);
    }
    return load(snapshot.runId())
        .orElseThrow(() -> new IllegalStateException("created run disappeared"));
  }

  public Optional<ProductPipelineRunDocument> load(String runId) {
    requireText(runId, "runId");
    return blobStore
        .getVersioned(runKey(runId))
        .map(versioned -> read(versioned).withBlobVersion(versioned.version()));
  }

  public Optional<ProductPipelineRunDocument> loadByConversation(String conversationId) {
    requireText(conversationId, "conversationId");
    return blobStore
        .get(conversationKey(conversationId))
        .map(bytes -> new String(bytes, StandardCharsets.UTF_8))
        .flatMap(this::load);
  }

  public ProductPipelineRunDocument commit(long expectedRunRevision, LogicalCommit mutation) {
    Objects.requireNonNull(mutation, "mutation");
    requireText(mutation.runId(), "runId");
    if (mutation.expectedRunRevision() != expectedRunRevision) {
      throw new IllegalArgumentException("LogicalCommit.expectedRunRevision mismatch");
    }
    if (mutation.attempt() == null || mutation.transition() == null) {
      throw new IllegalArgumentException("attempt and transition are required");
    }
    if (mutation.transition().toRevision() != expectedRunRevision + 1L) {
      throw new IllegalArgumentException("transition.toRevision must be expectedRunRevision + 1");
    }

    VersionedBlob versioned =
        blobStore
            .getVersioned(runKey(mutation.runId()))
            .orElseThrow(
                () -> new IllegalArgumentException("run was not found: " + mutation.runId()));
    ProductPipelineRunDocument current = read(versioned).withBlobVersion(versioned.version());
    if (current.run().runRevision() != expectedRunRevision) {
      throw new StaleBlobVersionException(
          "expected runRevision "
              + expectedRunRevision
              + " but document has "
              + current.run().runRevision());
    }

    RunSnapshot nextSnapshot =
        new RunSnapshot(
            current.run().runId(),
            current.run().conversationId(),
            expectedRunRevision + 1L,
            mutation.nextStatus(),
            mutation.currentStageId(),
            mutation.stages(),
            current.run().runManifestRef(),
            current.run().flowInstanceId());
    List<StageAttempt> attempts = new ArrayList<>(current.attempts());
    attempts.add(mutation.attempt());
    List<RunTransition> transitions = new ArrayList<>(current.transitions());
    transitions.add(mutation.transition());
    ProductPipelineRunDocument next =
        new ProductPipelineRunDocument(nextSnapshot, attempts, transitions, null);

    blobStore.putIfVersion(runKey(mutation.runId()), write(next), versioned.version());
    return load(mutation.runId())
        .orElseThrow(() -> new IllegalStateException("committed run disappeared"));
  }

  private ProductPipelineRunDocument read(VersionedBlob versioned) {
    try {
      return objectMapper.readValue(versioned.content(), ProductPipelineRunDocument.class);
    } catch (Exception e) {
      throw new IllegalStateException("cannot deserialize product-pipeline run", e);
    }
  }

  private byte[] write(ProductPipelineRunDocument document) {
    try {
      return objectMapper.writeValueAsBytes(document);
    } catch (Exception e) {
      throw new IllegalStateException("cannot serialize product-pipeline run", e);
    }
  }

  private static String runKey(String runId) {
    return RUN_PREFIX + runId + ".json";
  }

  private static String conversationKey(String conversationId) {
    return CONVERSATION_PREFIX + conversationId;
  }

  private static void requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " is required");
    }
  }
}
