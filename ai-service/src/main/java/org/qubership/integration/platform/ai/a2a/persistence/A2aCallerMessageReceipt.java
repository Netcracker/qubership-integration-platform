package org.qubership.integration.platform.ai.a2a.persistence;

import java.util.Objects;

/**
 * Caller-scoped Message receipt: bound {@code taskId}, opaque command fingerprint, and processing
 * state for resumable dispatch.
 */
public record A2aCallerMessageReceipt(
    String taskId,
    String commandFingerprint,
    A2aReceiptProcessingState processingState,
    Long lastTaskRevision,
    Long responseTaskRevision,
    String commandKind,
    Long preconditionRevision) {

  public A2aCallerMessageReceipt {
    if (taskId == null || taskId.isBlank()) {
      throw new IllegalArgumentException("taskId is required");
    }
    commandFingerprint = commandFingerprint == null ? "" : commandFingerprint;
    processingState =
        processingState == null ? A2aReceiptProcessingState.COMPLETED : processingState;
  }

  public boolean completed() {
    return processingState == A2aReceiptProcessingState.COMPLETED;
  }

  public boolean incomplete() {
    return processingState == A2aReceiptProcessingState.CLAIMED
        || processingState == A2aReceiptProcessingState.DISPATCHING;
  }

  /** Compatibility constructor for completed receipts without revision metadata. */
  public A2aCallerMessageReceipt(String taskId, String commandFingerprint) {
    this(taskId, commandFingerprint, A2aReceiptProcessingState.COMPLETED, null, null, null, null);
  }

  public A2aCallerMessageReceipt(
      String taskId,
      String commandFingerprint,
      A2aReceiptProcessingState processingState,
      Long lastTaskRevision,
      Long responseTaskRevision) {
    this(
        taskId,
        commandFingerprint,
        processingState,
        lastTaskRevision,
        responseTaskRevision,
        null,
        null);
  }
}
