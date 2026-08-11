package org.qubership.integration.platform.ai.productpipeline.artifact;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.AppendCommand;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Reference;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Revision;

/**
 * Product-pipeline facade over {@link CompilationArtifacts} that requires provenance on every
 * append.
 */
public final class ProductPipelineArtifactStore {

  private final CompilationArtifacts artifacts;

  public ProductPipelineArtifactStore(CompilationArtifacts artifacts) {
    this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
  }

  public Revision append(AppendCommand command) {
    Objects.requireNonNull(command, "command");
    if (command.provenance() == null) {
      throw new IllegalArgumentException("provenance is required");
    }
    return artifacts.append(command);
  }

  public Optional<Revision> get(String compilationId, Reference reference) {
    return artifacts.get(compilationId, reference);
  }

  public Optional<Revision> latest(String compilationId, Kind kind) {
    return artifacts.latest(compilationId, kind);
  }

  public List<Revision> history(String compilationId, Kind kind) {
    return artifacts.history(compilationId, kind);
  }

  /**
   * Finds an existing {@link Kind#GRAPH_PATCH_ARTIFACT} revision whose payload {@code
   * invocationKey} matches.
   */
  public Optional<Revision> findGraphPatchByInvocationKey(String runId, String invocationKey) {
    Objects.requireNonNull(runId, "runId");
    if (invocationKey == null || invocationKey.isBlank()) {
      return Optional.empty();
    }
    for (Revision revision : history(runId, Kind.GRAPH_PATCH_ARTIFACT)) {
      GraphPatchArtifact payload = payload(revision, GraphPatchArtifact.class);
      if (payload != null && invocationKey.equals(payload.invocationKey())) {
        return Optional.of(revision);
      }
    }
    return Optional.empty();
  }

  public <T> T payload(Revision revision, Class<T> payloadType) {
    return artifacts.payload(revision, payloadType);
  }
}
