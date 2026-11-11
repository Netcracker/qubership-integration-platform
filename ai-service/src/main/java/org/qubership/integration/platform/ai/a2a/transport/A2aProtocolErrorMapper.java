package org.qubership.integration.platform.ai.a2a.transport;

import java.util.Map;
import java.util.Objects;
import org.a2aproject.sdk.spec.A2AError;
import org.a2aproject.sdk.spec.ContentTypeNotSupportedError;
import org.a2aproject.sdk.spec.InvalidParamsError;
import org.a2aproject.sdk.spec.TaskNotCancelableError;
import org.a2aproject.sdk.spec.TaskNotFoundError;
import org.a2aproject.sdk.spec.UnsupportedOperationError;
import org.qubership.integration.platform.ai.a2a.access.TaskAccessDeniedException;
import org.qubership.integration.platform.ai.a2a.persistence.A2aTaskTransitionResult;
import org.qubership.integration.platform.ai.productpipeline.create.facade.ApproveCreateChainOutcome;

/**
 * Maps adapter-domain failures onto A2A protocol errors selected by prompt 01.
 */
public final class A2aProtocolErrorMapper {

  private A2aProtocolErrorMapper() {}

  public static A2AError taskNotFound() {
    return new TaskNotFoundError();
  }

  public static A2AError taskNotCancelable() {
    return new TaskNotCancelableError(
        "Task cancellation is not supported for create-chain in this launch horizon");
  }

  public static A2AError unsupportedContentType(String detail) {
    return new ContentTypeNotSupportedError(
        null,
        detail == null || detail.isBlank() ? "Unsupported content type" : detail,
        null);
  }

  public static A2AError malformedStructuredData(String detail) {
    return new InvalidParamsError(
        detail == null || detail.isBlank() ? "Malformed structured data" : detail);
  }

  /**
   * Same caller-scoped {@code messageId} reused with a different command fingerprint.
   */
  public static A2AError idempotencyConflict(String messageId, String taskId) {
    return new InvalidParamsError(
        null,
        "Idempotency conflict: messageId was reused with a different command",
        Map.of(
            "messageId", messageId == null ? "" : messageId,
            "taskId", taskId == null ? "" : taskId));
  }

  public static A2AError unsupportedImplementAction() {
    return new UnsupportedOperationError(
        null,
        "Public implement action is not supported; use approve or clarify recovery",
        null);
  }

  public static A2AError terminalContinuation(String taskId, String state) {
    return new UnsupportedOperationError(
        null,
        String.format(
            "Cannot send message to task %s: task is in terminal state %s and cannot accept further messages",
            taskId, state),
        null);
  }

  public static A2AError staleTransition(A2aTaskTransitionResult.StaleRevision stale) {
    Objects.requireNonNull(stale, "stale");
    return new InvalidParamsError(
        null,
        "Stale Task revision",
        Map.of(
            "expectedRevision",
            stale.current().revision(),
            "taskId",
            stale.current().taskId()));
  }

  public static A2AError fromApproveOutcome(ApproveCreateChainOutcome outcome) {
    Objects.requireNonNull(outcome, "outcome");
    if (outcome instanceof ApproveCreateChainOutcome.StaleRevision stale) {
      return new InvalidParamsError(
          null,
          "Stale approval revision",
          Map.of(
              "expectedRevision", stale.expectedRevision(),
              "actualRevision", stale.actualRevision()));
    }
    if (outcome instanceof ApproveCreateChainOutcome.WrongArtifactHash wrongHash) {
      return new InvalidParamsError(
          null,
          "Wrong artifact hash",
          Map.of(
              "expectedHash", wrongHash.expectedHash(),
              "providedHash", wrongHash.providedHash()));
    }
    if (outcome instanceof ApproveCreateChainOutcome.WrongArtifactType wrongType) {
      return new InvalidParamsError(
          null,
          "Wrong artifact type",
          Map.of(
              "expectedType", wrongType.expectedType(),
              "providedType", wrongType.providedType()));
    }
    if (outcome instanceof ApproveCreateChainOutcome.NotWaitingForApproval notWaiting) {
      return new UnsupportedOperationError(
          null, "Task is not waiting for approval; status=" + notWaiting.status(), null);
    }
    throw new IllegalArgumentException("Outcome is not an error: " + outcome.getClass().getName());
  }

  public static A2AError fromAccessDenied(TaskAccessDeniedException denied) {
    // Match SDK authorization: denied looks like not found.
    String message =
        denied.getMessage() == null || denied.getMessage().isBlank()
            ? "Task not found"
            : denied.getMessage();
    return new TaskNotFoundError(message, null);
  }
}
