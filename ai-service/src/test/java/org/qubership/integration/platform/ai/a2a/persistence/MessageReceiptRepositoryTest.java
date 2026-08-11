package org.qubership.integration.platform.ai.a2a.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.a2a.protocol.A2aTaskState;

/**
 * Slice 3: Message idempotency by {@code (taskId, messageId)} and caller-scoped initial recovery.
 */
@QuarkusTest
class MessageReceiptRepositoryTest {

  @Inject A2aTaskRepository taskRepository;
  @Inject A2aMessageReceiptRepository messageReceiptRepository;

  @Test
  void duplicateMessageIdIsObservableAndSideEffectFree() {
    String taskId = "task-msg-" + UUID.randomUUID();
    String messageId = "msg-" + UUID.randomUUID();
    String fingerprint = "fp-same";
    taskRepository.insert(
        new A2aTaskCreate(
            taskId,
            null,
            taskId,
            A2aTaskState.WORKING,
            null,
            null,
            "{\"id\":\"" + taskId + "\"}",
            "[]",
            "[]",
            null));

    A2aMessageReceiptResult first =
        messageReceiptRepository.recordIfAbsent(taskId, messageId, fingerprint);
    assertInstanceOf(A2aMessageReceiptResult.Accepted.class, first);
    assertTrue(messageReceiptRepository.exists(taskId, messageId));

    long revisionBefore = taskRepository.findByTaskId(taskId).orElseThrow().revision();
    A2aMessageReceiptResult second =
        messageReceiptRepository.recordIfAbsent(taskId, messageId, fingerprint);
    assertInstanceOf(A2aMessageReceiptResult.Duplicate.class, second);

    A2aPersistedTask after = taskRepository.findByTaskId(taskId).orElseThrow();
    assertEquals(revisionBefore, after.revision(), "duplicate receipt must not transition the Task");
    assertEquals(A2aTaskState.WORKING, after.state());
  }

  @Test
  void differentFingerprintOnSameMessageIdIsConflict() {
    String taskId = "task-fp-" + UUID.randomUUID();
    String messageId = "msg-fp-" + UUID.randomUUID();
    taskRepository.insert(
        new A2aTaskCreate(
            taskId,
            null,
            taskId,
            A2aTaskState.WORKING,
            null,
            null,
            "{\"id\":\"" + taskId + "\"}",
            "[]",
            "[]",
            null));

    assertInstanceOf(
        A2aMessageReceiptResult.Accepted.class,
        messageReceiptRepository.recordIfAbsent(taskId, messageId, "fp-a"));
    A2aMessageReceiptResult conflict =
        messageReceiptRepository.recordIfAbsent(taskId, messageId, "fp-b");
    assertInstanceOf(A2aMessageReceiptResult.FingerprintConflict.class, conflict);
    assertEquals(
        "fp-a", ((A2aMessageReceiptResult.FingerprintConflict) conflict).existingFingerprint());
  }

  @Test
  void claimInitialWithWorkingTaskIsAtomicAndFingerprintAware() {
    String taskId = "task-caller-" + UUID.randomUUID();
    String messageId = "msg-caller-" + UUID.randomUUID();
    String fingerprint = "fp-initial";
    A2aTaskCreate working =
        new A2aTaskCreate(
            taskId,
            "ctx-" + taskId,
            taskId,
            A2aTaskState.WORKING,
            "local",
            "local-user",
            "{\"id\":\"" + taskId + "\",\"status\":{\"state\":\"TASK_STATE_WORKING\"}}",
            "[]",
            "[]",
            null);

    A2aCallerMessageClaimResult claimed =
        messageReceiptRepository.claimInitialWithWorkingTask(
            "local", "local-user", messageId, fingerprint, working);
    assertInstanceOf(A2aCallerMessageClaimResult.Claimed.class, claimed);
    assertEquals(taskId, ((A2aCallerMessageClaimResult.Claimed) claimed).taskId());

    A2aPersistedTask persisted = taskRepository.findByTaskId(taskId).orElseThrow();
    assertEquals(A2aTaskState.WORKING, persisted.state());
    assertEquals(1L, persisted.revision());
    assertTrue(messageReceiptRepository.exists(taskId, messageId));
    assertEquals(
        taskId,
        messageReceiptRepository
            .findTaskIdForCallerMessage("local", "local-user", messageId)
            .orElseThrow());
    assertEquals(
        fingerprint,
        messageReceiptRepository
            .findCallerReceipt("local", "local-user", messageId)
            .orElseThrow()
            .commandFingerprint());

    // Incomplete + matching fingerprint resumes the bound Task even when the SDK stamped a new
    // taskId (lost-initial retries omit taskId).
    A2aCallerMessageClaimResult reboundIncomplete =
        messageReceiptRepository.claimInitialWithWorkingTask(
            "local",
            "local-user",
            messageId,
            fingerprint,
            new A2aTaskCreate(
                "other-" + UUID.randomUUID(),
                null,
                "other",
                A2aTaskState.WORKING,
                "local",
                "local-user",
                "{}",
                "[]",
                "[]",
                null));
    assertInstanceOf(A2aCallerMessageClaimResult.Incomplete.class, reboundIncomplete);
    assertEquals(
        taskId, ((A2aCallerMessageClaimResult.Incomplete) reboundIncomplete).taskId());

    A2aCallerMessageClaimResult incomplete =
        messageReceiptRepository.claimInitialWithWorkingTask(
            "local", "local-user", messageId, fingerprint, working);
    assertInstanceOf(A2aCallerMessageClaimResult.Incomplete.class, incomplete);
    assertEquals(taskId, ((A2aCallerMessageClaimResult.Incomplete) incomplete).taskId());

    assertTrue(
        messageReceiptRepository.markDispatching("local", "local-user", messageId));
    messageReceiptRepository.markCompleted("local", "local-user", messageId, 1L, 1L);

    A2aCallerMessageClaimResult completed =
        messageReceiptRepository.claimInitialWithWorkingTask(
            "local", "local-user", messageId, fingerprint, working);
    assertInstanceOf(A2aCallerMessageClaimResult.AlreadyBound.class, completed);
    assertEquals(taskId, ((A2aCallerMessageClaimResult.AlreadyBound) completed).taskId());

    String conflictingTaskId = "conflict-" + UUID.randomUUID();
    A2aCallerMessageClaimResult bindingConflict =
        messageReceiptRepository.claimInitialWithWorkingTask(
            "local",
            "local-user",
            messageId,
            fingerprint,
            new A2aTaskCreate(
                conflictingTaskId,
                null,
                conflictingTaskId,
                A2aTaskState.WORKING,
                "local",
                "local-user",
                "{}",
                "[]",
                "[]",
                null));
    assertInstanceOf(A2aCallerMessageClaimResult.TaskBindingConflict.class, bindingConflict);
    assertEquals(
        taskId, ((A2aCallerMessageClaimResult.TaskBindingConflict) bindingConflict).boundTaskId());
    assertEquals(
        conflictingTaskId,
        ((A2aCallerMessageClaimResult.TaskBindingConflict) bindingConflict).requestedTaskId());

    A2aCallerMessageClaimResult conflict =
        messageReceiptRepository.claimInitialWithWorkingTask(
            "local",
            "local-user",
            messageId,
            "fp-different",
            working);
    assertInstanceOf(A2aCallerMessageClaimResult.FingerprintConflict.class, conflict);
    assertEquals(taskId, ((A2aCallerMessageClaimResult.FingerprintConflict) conflict).taskId());
  }

  @Test
  void exclusiveDispatchOwnershipAllowsOnlyOneOwner() {
    String taskId = "own-" + UUID.randomUUID();
    String messageId = "msg-" + UUID.randomUUID();
    String fingerprint = "fp-" + UUID.randomUUID();
    A2aTaskCreate working =
        new A2aTaskCreate(
            taskId,
            null,
            taskId,
            A2aTaskState.WORKING,
            "local",
            "local-user",
            "{}",
            "[]",
            "[]",
            null);
    assertInstanceOf(
        A2aCallerMessageClaimResult.Claimed.class,
        messageReceiptRepository.claimInitialWithWorkingTask(
            "local", "local-user", messageId, fingerprint, working));

    A2aDispatchAcquisition first =
        messageReceiptRepository.acquireDispatch("local", "local-user", messageId);
    assertEquals(A2aDispatchAcquisition.Result.ACQUIRED, first.result());

    A2aDispatchAcquisition second =
        messageReceiptRepository.acquireDispatch("local", "local-user", messageId);
    assertEquals(A2aDispatchAcquisition.Result.BUSY, second.result());

    messageReceiptRepository.completeDispatch(
        "local", "local-user", messageId, first.ownerToken(), 1L, 1L);

    A2aDispatchAcquisition afterComplete =
        messageReceiptRepository.acquireDispatch("local", "local-user", messageId);
    assertEquals(A2aDispatchAcquisition.Result.COMPLETED, afterComplete.result());
  }

  @Test
  void renewDispatchExtendsLeaseAndBlocksConcurrentAcquire() {
    String taskId = "renew-" + UUID.randomUUID();
    String messageId = "msg-" + UUID.randomUUID();
    String fingerprint = "fp-" + UUID.randomUUID();
    A2aTaskCreate working =
        new A2aTaskCreate(
            taskId,
            null,
            taskId,
            A2aTaskState.WORKING,
            "local",
            "local-user",
            "{}",
            "[]",
            "[]",
            null);
    assertInstanceOf(
        A2aCallerMessageClaimResult.Claimed.class,
        messageReceiptRepository.claimInitialWithWorkingTask(
            "local", "local-user", messageId, fingerprint, working));

    var jdbc =
        (org.qubership.integration.platform.ai.a2a.persistence.jdbc.JdbcA2aMessageReceiptRepository)
            messageReceiptRepository;
    java.time.Instant start = java.time.Instant.parse("2026-08-04T00:00:00Z");
    jdbc.setClock(java.time.Clock.fixed(start, java.time.ZoneOffset.UTC));
    jdbc.setDispatchLease(java.time.Duration.ofSeconds(30));

    A2aDispatchAcquisition first =
        messageReceiptRepository.acquireDispatch("local", "local-user", messageId);
    assertEquals(A2aDispatchAcquisition.Result.ACQUIRED, first.result());

    jdbc.setClock(java.time.Clock.fixed(start.plusSeconds(20), java.time.ZoneOffset.UTC));
    assertTrue(
        messageReceiptRepository.renewDispatch(
            "local", "local-user", messageId, first.ownerToken()));

    jdbc.setClock(java.time.Clock.fixed(start.plusSeconds(40), java.time.ZoneOffset.UTC));
    A2aDispatchAcquisition stillBusy =
        messageReceiptRepository.acquireDispatch("local", "local-user", messageId);
    assertEquals(A2aDispatchAcquisition.Result.BUSY, stillBusy.result());

    jdbc.setClock(java.time.Clock.fixed(start.plusSeconds(51), java.time.ZoneOffset.UTC));
    A2aDispatchAcquisition recovered =
        messageReceiptRepository.acquireDispatch("local", "local-user", messageId);
    assertEquals(A2aDispatchAcquisition.Result.ACQUIRED, recovered.result());
  }

  @Test
  void expiredLeaseAllowsCrashRecoveryAndBlocksPreviousOwner() {
    String taskId = "lease-" + UUID.randomUUID();
    String messageId = "msg-" + UUID.randomUUID();
    String fingerprint = "fp-" + UUID.randomUUID();
    A2aTaskCreate working =
        new A2aTaskCreate(
            taskId,
            null,
            taskId,
            A2aTaskState.WORKING,
            "local",
            "local-user",
            "{}",
            "[]",
            "[]",
            null);
    assertInstanceOf(
        A2aCallerMessageClaimResult.Claimed.class,
        messageReceiptRepository.claimInitialWithWorkingTask(
            "local", "local-user", messageId, fingerprint, working));

    java.time.Instant start = java.time.Instant.parse("2026-08-04T00:00:00Z");
    var jdbc =
        (org.qubership.integration.platform.ai.a2a.persistence.jdbc.JdbcA2aMessageReceiptRepository)
            messageReceiptRepository;
    jdbc.setClock(java.time.Clock.fixed(start, java.time.ZoneOffset.UTC));
    jdbc.setDispatchLease(java.time.Duration.ofSeconds(30));
    A2aDispatchAcquisition first =
        messageReceiptRepository.acquireDispatch("local", "local-user", messageId);
    assertEquals(A2aDispatchAcquisition.Result.ACQUIRED, first.result());

    jdbc.setClock(java.time.Clock.fixed(start.plusSeconds(31), java.time.ZoneOffset.UTC));
    A2aDispatchAcquisition recovered =
        messageReceiptRepository.acquireDispatch("local", "local-user", messageId);
    assertEquals(A2aDispatchAcquisition.Result.ACQUIRED, recovered.result());

    try {
      messageReceiptRepository.completeDispatch(
          "local", "local-user", messageId, first.ownerToken(), 1L, 1L);
      throw new AssertionError("previous owner must not complete after lease recovery");
    } catch (A2aPersistenceException expected) {
      // expected
    }

    messageReceiptRepository.completeDispatch(
        "local", "local-user", messageId, recovered.ownerToken(), 1L, 1L);
  }
}
