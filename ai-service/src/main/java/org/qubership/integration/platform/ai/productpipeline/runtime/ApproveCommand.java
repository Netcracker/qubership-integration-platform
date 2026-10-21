package org.qubership.integration.platform.ai.productpipeline.runtime;

import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;

/**
 * Approves one exact candidate artifact revision at an expected run revision.
 *
 * <p>{@code commandId} and {@code commandPayloadHash} make the command idempotent across a crash.
 * When the run document already records the ID, the runtime resumes instead of approving the stage
 * a second time. Both are {@code null} for callers that do not need replay safety.
 */
public record ApproveCommand(
    String runId,
    CompilationArtifacts.Reference target,
    long expectedRunRevision,
    String commandId,
    String commandPayloadHash) {

  public ApproveCommand(
      String runId, CompilationArtifacts.Reference target, long expectedRunRevision) {
    this(runId, target, expectedRunRevision, null, null);
  }
}
