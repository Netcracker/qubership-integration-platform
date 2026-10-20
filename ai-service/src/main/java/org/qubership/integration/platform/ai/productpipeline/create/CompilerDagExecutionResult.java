package org.qubership.integration.platform.ai.productpipeline.create;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.productpipeline.artifact.CompilerValidationBundle;
import org.qubership.integration.platform.ai.productpipeline.artifact.GraphAssemblyResult;
import org.qubership.integration.platform.ai.productpipeline.artifact.PlanValidationFinding;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcomeClass;

/**
 * Terminal result of one shared compiler DAG execution pass. {@code degradationFindings} carries the
 * fail-open skips and substitutions this pass made, so the caller can put them in front of whoever
 * approves the plan.
 */
public record CompilerDagExecutionResult(
    StageOutcomeClass outcomeClass,
    String message,
    List<String> executedSkillIds,
    PlanningPatchLedger patchLedger,
    ChainPlanGraph graph,
    GraphAssemblyResult assemblyResult,
    CompilerValidationBundle validationBundle,
    List<PlanValidationFinding> degradationFindings,
    Set<String> presentArtifactTypes) {

  public CompilerDagExecutionResult {
    Objects.requireNonNull(outcomeClass, "outcomeClass");
    executedSkillIds = executedSkillIds == null ? List.of() : List.copyOf(executedSkillIds);
    patchLedger =
        patchLedger == null ? new PlanningPatchLedger(List.of(), List.of()) : patchLedger;
    degradationFindings =
        degradationFindings == null ? List.of() : List.copyOf(degradationFindings);
    presentArtifactTypes =
        presentArtifactTypes == null ? Set.of() : Set.copyOf(presentArtifactTypes);
  }

  /** Result of a pass that recorded no degradations and no present-artifact set. */
  public CompilerDagExecutionResult(
      StageOutcomeClass outcomeClass,
      String message,
      List<String> executedSkillIds,
      PlanningPatchLedger patchLedger,
      ChainPlanGraph graph,
      GraphAssemblyResult assemblyResult,
      CompilerValidationBundle validationBundle) {
    this(
        outcomeClass,
        message,
        executedSkillIds,
        patchLedger,
        graph,
        assemblyResult,
        validationBundle,
        List.of(),
        Set.of());
  }

  /** Result of a pass that degraded nothing. */
  public CompilerDagExecutionResult(
      StageOutcomeClass outcomeClass,
      String message,
      List<String> executedSkillIds,
      PlanningPatchLedger patchLedger,
      ChainPlanGraph graph,
      GraphAssemblyResult assemblyResult,
      CompilerValidationBundle validationBundle,
      List<PlanValidationFinding> degradationFindings) {
    this(
        outcomeClass,
        message,
        executedSkillIds,
        patchLedger,
        graph,
        assemblyResult,
        validationBundle,
        degradationFindings,
        Set.of());
  }

  /**
   * Fail closed when a contract-declared artifact is absent from this completed run.
   *
   * @throws IllegalStateException naming the first missing artifact type
   */
  public void requireArtifacts(Set<String> requiredArtifactTypes) {
    if (requiredArtifactTypes == null || requiredArtifactTypes.isEmpty()) {
      return;
    }
    for (String type : requiredArtifactTypes) {
      if (type == null || type.isBlank()) {
        continue;
      }
      if (!presentArtifactTypes.contains(type)) {
        throw new IllegalStateException("Compiler run completed without required artifact: " + type);
      }
    }
  }
}
