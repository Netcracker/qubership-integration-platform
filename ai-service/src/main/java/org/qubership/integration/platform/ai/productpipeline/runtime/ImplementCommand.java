package org.qubership.integration.platform.ai.productpipeline.runtime;

/**
 * Advances a run past the implementation gate with an exact approved plan content hash.
 *
 * <p>{@code commandId} and {@code commandPayloadHash} make the command idempotent across a crash.
 * When the run document already records the ID, the runtime resumes instead of moving past the gate
 * a second time. Both are {@code null} for callers that do not need replay safety.
 */
public record ImplementCommand(
    String runId,
    String approvedPlanContentHash,
    long expectedRunRevision,
    String commandId,
    String commandPayloadHash) {

  public ImplementCommand(String runId, String approvedPlanContentHash, long expectedRunRevision) {
    this(runId, approvedPlanContentHash, expectedRunRevision, null, null);
  }
}
