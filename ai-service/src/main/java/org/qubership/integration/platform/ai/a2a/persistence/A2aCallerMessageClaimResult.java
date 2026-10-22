package org.qubership.integration.platform.ai.a2a.persistence;

import java.util.Objects;

/**
 * Result of claiming a caller-scoped Message receipt keyed by trusted caller identity and {@code
 * messageId}.
 */
public sealed interface A2aCallerMessageClaimResult
    permits A2aCallerMessageClaimResult.Claimed,
        A2aCallerMessageClaimResult.AlreadyBound,
        A2aCallerMessageClaimResult.Incomplete,
        A2aCallerMessageClaimResult.FingerprintConflict,
        A2aCallerMessageClaimResult.TaskBindingConflict {

  /** First claim for this caller and {@code messageId}; binds {@code taskId}. */
  record Claimed(String taskId) implements A2aCallerMessageClaimResult {
    public Claimed {
      Objects.requireNonNull(taskId, "taskId");
    }
  }

  /** Receipt already exists with a matching fingerprint and is {@code COMPLETED}. */
  record AlreadyBound(String taskId) implements A2aCallerMessageClaimResult {
    public AlreadyBound {
      Objects.requireNonNull(taskId, "taskId");
    }
  }

  /**
   * Receipt already exists with a matching fingerprint but dispatch is still {@code CLAIMED} or
   * {@code DISPATCHING}. Resume the same command for the bound {@code taskId}, even when the
   * request carries a different SDK-stamped id (lost-initial retries omit {@code taskId}).
   */
  record Incomplete(String taskId, A2aReceiptProcessingState processingState)
      implements A2aCallerMessageClaimResult {
    public Incomplete {
      Objects.requireNonNull(taskId, "taskId");
      Objects.requireNonNull(processingState, "processingState");
    }
  }

  /**
   * Receipt already exists for this caller and {@code messageId}, but the command fingerprint does
   * not match.
   */
  record FingerprintConflict(String taskId, String existingFingerprint)
      implements A2aCallerMessageClaimResult {
    public FingerprintConflict {
      Objects.requireNonNull(taskId, "taskId");
    }
  }

  /**
   * Completed receipt for this caller and {@code messageId} is bound to a different {@code
   * taskId}. Incomplete receipts with a matching fingerprint return {@link Incomplete} instead so
   * lost-initial retries can resume the durable Task.
   */
  record TaskBindingConflict(String boundTaskId, String requestedTaskId)
      implements A2aCallerMessageClaimResult {
    public TaskBindingConflict {
      Objects.requireNonNull(boundTaskId, "boundTaskId");
      Objects.requireNonNull(requestedTaskId, "requestedTaskId");
    }
  }
}
