package org.qubership.integration.platform.ai.skill.executor;

import org.qubership.integration.platform.ai.skill.workspace.SkillArtifact;

import java.util.List;
import java.util.Optional;

/** Result returned by {@link SkillExecutor#run}. */
public record SkillExecutionResult(
    SkillRunStatus status,
    List<SkillArtifact> outputs,
    String message,
    Optional<HitlCheckpoint> hitl) {

  public static SkillExecutionResult completed(List<SkillArtifact> outputs, String message) {
    return new SkillExecutionResult(
        SkillRunStatus.COMPLETED, outputs, message, Optional.empty());
  }

  public static SkillExecutionResult skipped(String message) {
    return new SkillExecutionResult(SkillRunStatus.SKIPPED, List.of(), message, Optional.empty());
  }

  public static SkillExecutionResult failed(String message) {
    return new SkillExecutionResult(SkillRunStatus.FAILED, List.of(), message, Optional.empty());
  }

  public static SkillExecutionResult hitl(HitlCheckpoint checkpoint, String message) {
    return new SkillExecutionResult(
        SkillRunStatus.HITL_PENDING, List.of(), message, Optional.of(checkpoint));
  }
}
