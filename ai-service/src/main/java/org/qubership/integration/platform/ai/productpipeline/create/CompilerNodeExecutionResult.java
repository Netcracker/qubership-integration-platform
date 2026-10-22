package org.qubership.integration.platform.ai.productpipeline.create;

import java.util.List;
import org.qubership.integration.platform.ai.productpipeline.capability.ArtifactCandidate;
import org.qubership.integration.platform.ai.skill.workspace.SkillArtifact;

/** Output of one JAVA_ADAPTER compiler node execution. */
public record CompilerNodeExecutionResult(
    List<SkillArtifact> workspaceOutputs, List<ArtifactCandidate> durableCandidates) {

  public CompilerNodeExecutionResult {
    workspaceOutputs = workspaceOutputs == null ? List.of() : List.copyOf(workspaceOutputs);
    durableCandidates = durableCandidates == null ? List.of() : List.copyOf(durableCandidates);
  }
}
