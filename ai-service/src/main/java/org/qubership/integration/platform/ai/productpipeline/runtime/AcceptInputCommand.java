package org.qubership.integration.platform.ai.productpipeline.runtime;

/**
 * Supplies user text to a stage waiting for input.
 *
 * <p>{@code commandId} and {@code commandPayloadHash} make the command idempotent across a crash.
 * When the run document already records the ID, the runtime resumes instead of accepting the input
 * a second time. Both are {@code null} for callers that do not need replay safety.
 */
public record AcceptInputCommand(
    String runId, String text, String commandId, String commandPayloadHash) {

  public AcceptInputCommand(String runId, String text) {
    this(runId, text, null, null);
  }
}
