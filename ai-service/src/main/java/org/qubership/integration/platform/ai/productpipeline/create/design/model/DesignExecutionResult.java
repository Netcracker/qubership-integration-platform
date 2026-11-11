package org.qubership.integration.platform.ai.productpipeline.create.design.model;

import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Reference;

/** Final executor outcome after materialization and catalog reconciliation. */
public record DesignExecutionResult(
    String schemaVersion,
    Reference approvalRef,
    String designPlanReportHash,
    String designExecutionPlanHash,
    Reference materializationResultRef,
    Reference reconcileRef,
    String outcome) {

  public DesignExecutionResult {
    schemaVersion = DesignArtifacts.requireText(schemaVersion, "schemaVersion");
    approvalRef = DesignArtifacts.requireNonNull(approvalRef, "approvalRef");
    designPlanReportHash =
        DesignArtifacts.requireText(designPlanReportHash, "designPlanReportHash");
    designExecutionPlanHash =
        DesignArtifacts.requireText(designExecutionPlanHash, "designExecutionPlanHash");
    materializationResultRef =
        DesignArtifacts.requireNonNull(materializationResultRef, "materializationResultRef");
    reconcileRef = DesignArtifacts.requireNonNull(reconcileRef, "reconcileRef");
    outcome = DesignArtifacts.requireText(outcome, "outcome");
  }
}
