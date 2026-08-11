package org.qubership.integration.platform.ai.productpipeline.create.facade;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Transport-neutral create-chain progress events.
 *
 * <p>Does not expose browser {@code ChatEvent}, A2A SDK types, internal run enums, storage keys, or
 * prompt/model traces.
 */
public sealed interface CreateChainEvent {

  record Progress(String label) implements CreateChainEvent {

    public Progress {
      label = label == null ? "" : label;
    }
  }

  record Waiting(CreateChainPendingAction pendingAction) implements CreateChainEvent {

    public Waiting {
      Objects.requireNonNull(pendingAction, "pendingAction");
    }
  }

  /**
   * Public artifact ready for transport projection.
   *
   * @param content allowlisted reviewable fields; empty when the producer has no public body yet
   */
  record ArtifactReady(
      String artifactType,
      String artifactId,
      String artifactHash,
      long revision,
      Map<String, Object> content)
      implements CreateChainEvent {

    public ArtifactReady {
      Objects.requireNonNull(artifactType, "artifactType");
      Objects.requireNonNull(artifactId, "artifactId");
      Objects.requireNonNull(artifactHash, "artifactHash");
      content = content == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(content));
    }

    /** Compatibility constructor used by tests that omit reviewable content. */
    public ArtifactReady(
        String artifactType, String artifactId, String artifactHash, long revision) {
      this(artifactType, artifactId, artifactHash, revision, Map.of());
    }
  }

  record Completed(CreateChainExecutionSnapshot snapshot) implements CreateChainEvent {

    public Completed {
      Objects.requireNonNull(snapshot, "snapshot");
    }
  }

  record Failed(String message, CreateChainExecutionSnapshot snapshot)
      implements CreateChainEvent {

    public Failed {
      message = message == null || message.isBlank() ? "Something went wrong." : message;
      Objects.requireNonNull(snapshot, "snapshot");
    }
  }
}
