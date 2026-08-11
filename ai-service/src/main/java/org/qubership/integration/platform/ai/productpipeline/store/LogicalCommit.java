package org.qubership.integration.platform.ai.productpipeline.store;

import java.util.List;

/**
 * One atomic logical commit: next status, stage snapshots, attempt, and transition applied together
 * under compare-and-set on {@code expectedRunRevision}.
 */
public record LogicalCommit(
    String runId,
    long expectedRunRevision,
    RunStatus nextStatus,
    String currentStageId,
    List<StageSnapshot> stages,
    StageAttempt attempt,
    RunTransition transition) {

  public LogicalCommit {
    stages = stages == null ? List.of() : List.copyOf(stages);
  }
}
