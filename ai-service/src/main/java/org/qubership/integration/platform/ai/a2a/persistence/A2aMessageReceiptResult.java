package org.qubership.integration.platform.ai.a2a.persistence;

/**
 * Result of recording a task-scoped Message receipt keyed by {@code (taskId, messageId)}.
 */
public sealed interface A2aMessageReceiptResult
    permits A2aMessageReceiptResult.Accepted,
        A2aMessageReceiptResult.Duplicate,
        A2aMessageReceiptResult.FingerprintConflict {

  /** First delivery of this Message for the Task. */
  record Accepted() implements A2aMessageReceiptResult {}

  /** Same Message body was already accepted; replay the durable result. */
  record Duplicate() implements A2aMessageReceiptResult {}

  /** Same {@code messageId} was used with a different command fingerprint. */
  record FingerprintConflict(String existingFingerprint) implements A2aMessageReceiptResult {}
}
