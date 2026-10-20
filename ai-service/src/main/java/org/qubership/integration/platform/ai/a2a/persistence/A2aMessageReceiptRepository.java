package org.qubership.integration.platform.ai.a2a.persistence;

import java.util.Optional;

/**
 * Idempotent Message receipt store.
 *
 * <p>Initial and continuation dedupe use a caller-scoped key {@code (tenantId, subjectId,
 * messageId)} bound to exactly one {@code taskId}. Both store an opaque command fingerprint and a
 * processing state for resumable dispatch. Task-scoped rows remain for compatibility indexes.
 */
public interface A2aMessageReceiptRepository {

  boolean exists(String taskId, String messageId);

  /**
   * Atomically claims the caller-scoped receipt, records the task-scoped receipt, and inserts the
   * initial {@code WORKING} Task snapshot in {@link A2aReceiptProcessingState#CLAIMED}. Call this
   * before pipeline dispatch so a crash cannot leave a receipt without a recoverable Task.
   */
  A2aCallerMessageClaimResult claimInitialWithWorkingTask(
      String tenantId,
      String subjectId,
      String messageId,
      String commandFingerprint,
      String commandKind,
      A2aTaskCreate workingTask);

  /**
   * Claims a continuation Message under the caller-scoped key, bound to {@code taskId}. A
   * completed receipt reused against another Task is a {@link
   * A2aCallerMessageClaimResult.TaskBindingConflict}. An incomplete matching-fingerprint receipt
   * returns {@link A2aCallerMessageClaimResult.Incomplete} so retries resume the bound Task.
   */
  A2aCallerMessageClaimResult claimContinuation(
      String tenantId,
      String subjectId,
      String messageId,
      String commandFingerprint,
      String commandKind,
      String taskId);

  /** Claims a continuation and records the facade precondition revision for crash reconciliation. */
  A2aCallerMessageClaimResult claimContinuation(
      String tenantId,
      String subjectId,
      String messageId,
      String commandFingerprint,
      String commandKind,
      String taskId,
      Long preconditionRevision);

  /** Atomically records the resolved command payload with the continuation claim. */
  A2aCallerMessageClaimResult claimContinuation(
      String tenantId, String subjectId, String messageId, String commandFingerprint,
      String commandKind, String taskId, Long preconditionRevision, String commandPayloadJson);

  /**
   * Acquires exclusive dispatch ownership with an owner token and lease. Concurrent callers receive
   * {@link A2aDispatchAcquisition.Result#BUSY}. Completed receipts return {@link
   * A2aDispatchAcquisition.Result#COMPLETED}. Expired leases allow crash recovery.
   */
  A2aDispatchAcquisition acquireDispatch(String tenantId, String subjectId, String messageId);

  /**
   * Extends the active dispatch lease for {@code ownerToken}. No-op when the token no longer owns
   * the receipt. Used as a heartbeat while long-running facade work is in progress.
   *
   * @return {@code true} when the lease was renewed
   */
  boolean renewDispatch(
      String tenantId, String subjectId, String messageId, java.util.UUID ownerToken);

  /** Completes the receipt only when {@code ownerToken} still owns the active lease. */
  void completeDispatch(
      String tenantId,
      String subjectId,
      String messageId,
      java.util.UUID ownerToken,
      long lastTaskRevision,
      long responseTaskRevision);

  /** Releases ownership after a handled dispatch failure so another retry can acquire. */
  void releaseDispatch(
      String tenantId, String subjectId, String messageId, java.util.UUID ownerToken);

  /** Looks up the Task bound to a prior caller-scoped Message receipt. */
  Optional<String> findTaskIdForCallerMessage(String tenantId, String subjectId, String messageId);

  /** Looks up the caller-scoped receipt including fingerprint and processing state. */
  Optional<A2aCallerMessageReceipt> findCallerReceipt(
      String tenantId, String subjectId, String messageId);

  /**
   * Compatibility overload for the pre-remediation claim signature used by existing unit tests.
   */
  default A2aCallerMessageClaimResult claimInitialWithWorkingTask(
      String tenantId,
      String subjectId,
      String messageId,
      String commandFingerprint,
      A2aTaskCreate workingTask) {
    return claimInitialWithWorkingTask(
        tenantId, subjectId, messageId, commandFingerprint, "clarify", workingTask);
  }
}
