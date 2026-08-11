package org.qubership.integration.platform.ai.productpipeline.create;

import java.util.List;
import java.util.Objects;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.productpipeline.artifact.CompilerValidationBundle;
import org.qubership.integration.platform.ai.productpipeline.artifact.GraphAssemblyResult;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcomeClass;

/** Terminal result of one shared compiler DAG execution pass. */
public record CompilerDagExecutionResult(
    StageOutcomeClass outcomeClass,
    String message,
    List<String> executedSkillIds,
    PlanningPatchLedger patchLedger,
    ChainPlanGraph graph,
    GraphAssemblyResult assemblyResult,
    CompilerValidationBundle validationBundle) {

  public CompilerDagExecutionResult {
    Objects.requireNonNull(outcomeClass, "outcomeClass");
    executedSkillIds = executedSkillIds == null ? List.of() : List.copyOf(executedSkillIds);
    patchLedger =
        patchLedger == null ? new PlanningPatchLedger(List.of(), List.of()) : patchLedger;
  }
}
