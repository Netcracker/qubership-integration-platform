package org.qubership.integration.platform.ai.productpipeline.runtime;

/**
 * Supplies user text to a stage waiting for input.
 *
 * <p>{@code commandId} and {@code commandPayloadHash} make the command idempotent across a crash.
 * When the run document already records the ID, the runtime resumes instead of accepting the input
 * a second time. Both are {@code null} for callers that do not need replay safety.
 *
 * <p>{@code origin} is a trusted-adapter field. The transport does not prove a human typed the
 * text; absent or untrusted origin uses the ledger's flat budget.
 *
 * <p>{@code clarificationOfferId} names the requirement offer this text answers. It is empty for
 * every other input.
 */
public record AcceptInputCommand(
    String runId,
    String text,
    String commandId,
    String commandPayloadHash,
    InputOrigin origin,
    String clarificationOfferId) {

  public AcceptInputCommand(String runId, String text) {
    this(runId, text, null, null, InputOrigin.ABSENT, null);
  }

  public AcceptInputCommand(String runId, String text, String commandId, String commandPayloadHash) {
    this(runId, text, commandId, commandPayloadHash, InputOrigin.ABSENT, null);
  }

  public AcceptInputCommand(
      String runId, String text, String commandId, String commandPayloadHash, InputOrigin origin) {
    this(runId, text, commandId, commandPayloadHash, origin, null);
  }

  public AcceptInputCommand {
    origin = InputOrigin.of(origin);
    clarificationOfferId = clarificationOfferId == null ? "" : clarificationOfferId;
  }
}
