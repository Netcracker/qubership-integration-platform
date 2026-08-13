package org.qubership.integration.platform.ai.productpipeline.create.facade;

import java.util.Objects;
import org.qubership.integration.platform.ai.productpipeline.facade.ExecutionSnapshot;

/** Latest transport-neutral create-chain execution snapshot. */
public record CreateChainExecutionSnapshot(
    String taskId,
    String runId,
    CreateChainExecutionStatus status,
    long revision,
    CreateChainPendingAction pendingAction,
    String failureMessage)
    implements ExecutionSnapshot {

  public CreateChainExecutionSnapshot {
    Objects.requireNonNull(taskId, "taskId");
    Objects.requireNonNull(runId, "runId");
    Objects.requireNonNull(status, "status");
    failureMessage = failureMessage == null ? "" : failureMessage;
  }

  public boolean hasPendingAction() {
    return pendingAction != null;
  }

  @Override
  public boolean finished() {
    return status == CreateChainExecutionStatus.COMPLETED
        || status == CreateChainExecutionStatus.FAILED;
  }
}
