package org.qubership.integration.platform.ai.productpipeline.store;

import java.util.List;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.plan.workdocument.WorkRepairBudget;

/**
 * One atomic logical commit: next status, stage snapshots, attempt, and transition applied together
 * under compare-and-set on {@code expectedRunRevision}.
 *
 * <p>A null document reference or repair budget keeps the value already stored on the run.
 */
public record LogicalCommit(
    String runId,
    long expectedRunRevision,
    RunStatus nextStatus,
    String currentStageId,
    List<StageSnapshot> stages,
    StageAttempt attempt,
    RunTransition transition,
    CompilationArtifacts.Reference workDocumentRef,
    WorkRepairBudget workRepairBudget) {

  public LogicalCommit {
    stages = stages == null ? List.of() : List.copyOf(stages);
  }

  /** Changes stage status while preserving its outputs, candidates, and approval binding. */
  public static LogicalCommit statusChange(
      RunSnapshot current, StageStatus stageStatus, List<StageSnapshot> stages,
      StageAttempt attempt, RunTransition transition) {
    List<StageSnapshot> nextStages = stages.stream()
        .map(snapshot -> snapshot.stageId().equals(current.currentStageId())
            ? new StageSnapshot(
                snapshot.stageId(), stageStatus, snapshot.outputRefs(),
                snapshot.approvedArtifactId(), snapshot.candidateReferences(),
                snapshot.approvableReference(), snapshot.candidateRevision())
            : snapshot)
        .toList();
    return new LogicalCommit(
        current.runId(), current.runRevision(), transition.toStatus(),
        current.currentStageId(), nextStages, attempt, transition);
  }

  /** Compatibility constructor for commits that do not move the work document. */
  public LogicalCommit(
      String runId,
      long expectedRunRevision,
      RunStatus nextStatus,
      String currentStageId,
      List<StageSnapshot> stages,
      StageAttempt attempt,
      RunTransition transition) {
    this(
        runId,
        expectedRunRevision,
        nextStatus,
        currentStageId,
        stages,
        attempt,
        transition,
        null,
        null);
  }
}
