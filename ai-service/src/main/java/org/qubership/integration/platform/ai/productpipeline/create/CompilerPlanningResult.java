package org.qubership.integration.platform.ai.productpipeline.create;

import java.util.List;
import org.qubership.integration.platform.ai.plan.ImplementationPlan;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.productpipeline.artifact.CompilerValidationBundle;
import org.qubership.integration.platform.ai.productpipeline.artifact.GraphAssemblyResult;
import org.qubership.integration.platform.ai.productpipeline.artifact.PlanValidationResult;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcomeClass;

/** Result of one isolated compiler planning run. */
public record CompilerPlanningResult(
    StageOutcomeClass outcomeClass,
    ImplementationPlan implementationPlan,
    PlanValidationResult validationResult,
    ChainPlanGraph graph,
    GraphAssemblyResult graphAssemblyResult,
    CompilerValidationBundle compilerValidationBundle,
    List<String> executedSkillIds,
    List<String> exclusionFindings,
    String message) {

  public CompilerPlanningResult {
    executedSkillIds = executedSkillIds == null ? List.of() : List.copyOf(executedSkillIds);
    exclusionFindings = exclusionFindings == null ? List.of() : List.copyOf(exclusionFindings);
  }

  public boolean approvalEligible() {
    return validationResult != null
        && validationResult.approvalEligible()
        && compilerValidationBundle != null
        && compilerValidationBundle.approvalEligible()
        && exclusionFindings.isEmpty()
        && implementationPlan != null;
  }
}
