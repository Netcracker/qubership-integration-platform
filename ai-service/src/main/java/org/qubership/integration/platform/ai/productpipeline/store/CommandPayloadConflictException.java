package org.qubership.integration.platform.ai.productpipeline.store;

/**
 * Thrown when a command ID was already applied with a different canonical payload.
 *
 * <p>Retrying a command is safe; reusing its ID for different content is not. The caller maps this
 * to a typed protocol conflict instead of applying the transition twice.
 */
public class CommandPayloadConflictException extends RuntimeException {

  private final String commandId;
  private final String appliedPayloadHash;
  private final String requestedPayloadHash;

  public CommandPayloadConflictException(
      String commandId, String appliedPayloadHash, String requestedPayloadHash) {
    super(
        "command "
            + commandId
            + " was applied with payload hash "
            + appliedPayloadHash
            + " but was retried with "
            + requestedPayloadHash);
    this.commandId = commandId;
    this.appliedPayloadHash = appliedPayloadHash;
    this.requestedPayloadHash = requestedPayloadHash;
  }

  public String commandId() {
    return commandId;
  }

  public String appliedPayloadHash() {
    return appliedPayloadHash;
  }

  public String requestedPayloadHash() {
    return requestedPayloadHash;
  }
}
