package org.qubership.integration.platform.ai.harness;

import java.util.List;
import java.util.Set;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.productpipeline.artifact.CompilerValidationBundle;
import org.qubership.integration.platform.ai.productpipeline.artifact.GraphAssemblyResult;
import org.qubership.integration.platform.ai.productpipeline.artifact.PlanValidationFinding;
import org.qubership.integration.platform.ai.productpipeline.create.PlanningPatchLedger;

/** Complete evidence from one executor run over a saved planner response. */
public record ExecutorHarnessResponse(
    String conversationId,
    SkillHarnessStatus status,
    String message,
    boolean plannerInvoked,
    String modelName,
    List<String> plannedSkillIds,
    List<String> executedSkillIds,
    List<SkillProgress> skillProgress,
    PlanningPatchLedger patchLedger,
    ChainPlanGraph graph,
    GraphAssemblyResult assemblyResult,
    CompilerValidationBundle validationBundle,
    List<PlanValidationFinding> degradationFindings,
    Set<String> presentArtifactTypes,
    long durationMillis) {

  public ExecutorHarnessResponse {
    plannedSkillIds = plannedSkillIds == null ? List.of() : List.copyOf(plannedSkillIds);
    executedSkillIds = executedSkillIds == null ? List.of() : List.copyOf(executedSkillIds);
    skillProgress = skillProgress == null ? List.of() : List.copyOf(skillProgress);
    degradationFindings =
        degradationFindings == null ? List.of() : List.copyOf(degradationFindings);
    presentArtifactTypes =
        presentArtifactTypes == null ? Set.of() : Set.copyOf(presentArtifactTypes);
  }

  /** One progress notification emitted by an owning skill or execution terminal. */
  public record SkillProgress(String skillId, String status) {}
}
