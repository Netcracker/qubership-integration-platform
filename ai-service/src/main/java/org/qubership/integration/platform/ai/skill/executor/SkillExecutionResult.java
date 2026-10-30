package org.qubership.integration.platform.ai.skill.executor;

import org.qubership.integration.platform.ai.skill.workspace.SkillArtifact;

import java.util.List;

/** Result returned by {@link SkillExecutor#run}. */
public record SkillExecutionResult(
    SkillRunStatus status, List<SkillArtifact> outputs, String message, boolean retryAllowed,
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    org.qubership.integration.platform.ai.productpipeline.capability.CaptureFailureEvidence nativeFailure) {

  public SkillExecutionResult(SkillRunStatus status, List<SkillArtifact> outputs, String message, boolean retryAllowed) {
    this(status, outputs, message, retryAllowed, null);
  }

  public SkillExecutionResult(SkillRunStatus status, List<SkillArtifact> outputs, String message) {
    this(status, outputs, message, true);
  }

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
