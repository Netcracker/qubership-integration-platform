package org.qubership.integration.platform.ai.productpipeline.recovery;

import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import java.util.List;
import org.qubership.integration.platform.ai.productpipeline.artifact.UserInput;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Reference;

/** Complete recovery input for the failure narrative agent. */
public record RecoveryContext(
    RecoveryEvidence evidence,
    RequirementBrief approvedBrief,
    Object rejectedArtifact,
    String responseLocale,
    List<UserInput> authorInputs,
    Reference approvedPlanRef,
    Object approvedPlan,
    List<RepairHistory.Attempt> priorAttempts) {
  public RecoveryContext {
    priorAttempts = priorAttempts == null ? List.of() : List.copyOf(priorAttempts);
    authorInputs = authorInputs == null ? List.of() : List.copyOf(authorInputs);
  }

  public RecoveryContext(RecoveryEvidence evidence, RequirementBrief approvedBrief,
      Object rejectedArtifact, String responseLocale, List<UserInput> authorInputs,
      Reference approvedPlanRef, Object approvedPlan) {
    this(evidence, approvedBrief, rejectedArtifact, responseLocale, authorInputs, approvedPlanRef,
        approvedPlan, List.of());
  }

  public RecoveryContext(RecoveryEvidence evidence, RequirementBrief approvedBrief,
      Object rejectedArtifact, String responseLocale) {
    this(evidence, approvedBrief, rejectedArtifact, responseLocale, List.of());
  }

  public RecoveryContext(RecoveryEvidence evidence, RequirementBrief approvedBrief,
      Object rejectedArtifact, String responseLocale, List<UserInput> authorInputs) {
    this(evidence, approvedBrief, rejectedArtifact, responseLocale, authorInputs, null, null);
  }
}
