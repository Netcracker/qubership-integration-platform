package org.qubership.integration.platform.ai.productpipeline.create.facade;

import java.util.Objects;

/**
 * Approves the current expected artifact with exact type, content hash, and run revision.
 *
 * <p>Also recovers a blocked implementation gate when the expected plan evidence matches.
 */
public record ApproveCreateChainArtifactCommand(
    String taskId, String artifactType, String artifactHash, long revision, String commandId) {

  public ApproveCreateChainArtifactCommand(
      String taskId, String artifactType, String artifactHash, long revision) {
    this(taskId, artifactType, artifactHash, revision, null);
  }

  public ApproveCreateChainArtifactCommand {
    Objects.requireNonNull(taskId, "taskId");
    Objects.requireNonNull(artifactType, "artifactType");
    Objects.requireNonNull(artifactHash, "artifactHash");
    if (taskId.isBlank()) {
      throw new IllegalArgumentException("taskId is required");
    }
    if (artifactType.isBlank()) {
      throw new IllegalArgumentException("artifactType is required");
    }
    if (artifactHash.isBlank()) {
      throw new IllegalArgumentException("artifactHash is required");
    }
  }
}
