package org.qubership.integration.platform.ai.plan.workdocument;

import java.util.List;

/**
 * Outcome of one {@link WorkDocumentFilling#advance} call. The document reference is the committed
 * artifact. Reasons are readable evidence, not a second workflow status.
 */
public record FillingResult(
    Action action,
    String taskId,
    String documentRevision,
    String documentReference,
    List<String> questionIds,
    List<String> reasons,
    long retryDelayMs) {

  public FillingResult {
    taskId = taskId == null ? "" : taskId;
    documentRevision = documentRevision == null ? "" : documentRevision;
    documentReference = documentReference == null ? "" : documentReference;
    questionIds = questionIds == null ? List.of() : List.copyOf(questionIds);
    reasons = reasons == null ? List.of() : List.copyOf(reasons);
    retryDelayMs = Math.max(retryDelayMs, 0L);
  }

  public enum Action {
    ADVANCED,
    WAITING_FOR_INPUT,
    READY_FOR_PRESENTATION,
    HALTED,
    RETRY_CURRENT
  }
}
