package org.qubership.integration.platform.ai.productpipeline.create;

import java.util.List;
import java.util.Objects;
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
    List<PlanValidationFinding> degradationFindings) {

  public CompilerDagExecutionResult {
    Objects.requireNonNull(outcomeClass, "outcomeClass");
    executedSkillIds = executedSkillIds == null ? List.of() : List.copyOf(executedSkillIds);
    patchLedger =
        patchLedger == null ? new PlanningPatchLedger(List.of(), List.of()) : patchLedger;
    degradationFindings =
        degradationFindings == null ? List.of() : List.copyOf(degradationFindings);
  }

  /** Result of a pass that degraded nothing. */
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
        List.of());
  }
}
