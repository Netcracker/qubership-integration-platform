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

  /**
   * Finds the latest {@link Kind#APPROVAL_RECORD} revision whose target identifies a given artifact
   * type and content hash. The target artifactId is expected to start with
   * {@code artifactType + ":"}. When {@code contentHash} is null, matches the latest record of the
   * given artifact type regardless of hash.
   */
  public Optional<Revision> findLatestApprovalRecord(
      String runId, String artifactType, String contentHash) {
    Objects.requireNonNull(runId, "runId");
    Objects.requireNonNull(artifactType, "artifactType");
    List<Revision> revisions = history(runId, Kind.APPROVAL_RECORD);
    for (int i = revisions.size() - 1; i >= 0; i--) {
      Revision revision = revisions.get(i);
      ApprovalRecordV2 record = payload(revision, ApprovalRecordV2.class);
      if (record == null || record.target() == null) {
        continue;
      }
      CompilationArtifacts.Reference target = record.target();
      if (target.artifactId().startsWith(artifactType + ":")
          && (contentHash == null || contentHash.equals(target.contentHash()))) {
        return Optional.of(revision);
      }
    }
    return Optional.empty();
  }

  public <T> T payload(Revision revision, Class<T> payloadType) {
    return artifacts.payload(revision, payloadType);
  }
}
