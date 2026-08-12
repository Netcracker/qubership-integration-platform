package org.qubership.integration.platform.ai.skill.executor;

import org.qubership.integration.platform.ai.skill.workspace.SkillArtifact;

import java.util.List;

/** Result returned by {@link SkillExecutor#run}. */
public record SkillExecutionResult(
    SkillRunStatus status, List<SkillArtifact> outputs, String message) {

  public static SkillExecutionResult completed(List<SkillArtifact> outputs, String message) {
    return new SkillExecutionResult(SkillRunStatus.COMPLETED, outputs, message);
  }

  public static SkillExecutionResult skipped(String message) {
    return new SkillExecutionResult(SkillRunStatus.SKIPPED, List.of(), message);
  }

  public static SkillExecutionResult failed(String message) {
    return new SkillExecutionResult(SkillRunStatus.FAILED, List.of(), message);
  }
}
