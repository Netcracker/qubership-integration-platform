package org.qubership.integration.platform.ai.productpipeline.create.design.model;

import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Reference;

/**
 * Fresh graph, plan, compiler, and executor validation evidence for one approved execution.
 *
 * <p>All four validation references must be fresh passes for the same graph and approval.
 */
public record ValidatedExecutionBundle(
    String schemaVersion,
    Reference approvalRef,
    Reference designPlanReportRef,
    String designPlanReportHash,
    Reference designExecutionPlanRef,
    String designExecutionPlanHash,
    Reference runManifestRef,
    Reference graphRef,
    String graphDigest,
    Reference orderedGraphPatchesRef,
    String orderedPatchDigest,
    Reference graphValidationRef,
    Reference planValidationRef,
    Reference compilerValidationRef,
    Reference executorValidationRef) {

  public ValidatedExecutionBundle {
    schemaVersion = DesignArtifacts.requireText(schemaVersion, "schemaVersion");
    approvalRef = DesignArtifacts.requireNonNull(approvalRef, "approvalRef");
    designPlanReportRef =
        DesignArtifacts.requireNonNull(designPlanReportRef, "designPlanReportRef");
    designPlanReportHash =
        DesignArtifacts.requireText(designPlanReportHash, "designPlanReportHash");
    designExecutionPlanRef =
        DesignArtifacts.requireNonNull(designExecutionPlanRef, "designExecutionPlanRef");
    designExecutionPlanHash =
        DesignArtifacts.requireText(designExecutionPlanHash, "designExecutionPlanHash");
    runManifestRef = DesignArtifacts.requireNonNull(runManifestRef, "runManifestRef");
    graphRef = DesignArtifacts.requireNonNull(graphRef, "graphRef");
    graphDigest = DesignArtifacts.requireText(graphDigest, "graphDigest");
    orderedGraphPatchesRef =
        DesignArtifacts.requireNonNull(orderedGraphPatchesRef, "orderedGraphPatchesRef");
    orderedPatchDigest = DesignArtifacts.requireText(orderedPatchDigest, "orderedPatchDigest");
    graphValidationRef = DesignArtifacts.requireNonNull(graphValidationRef, "graphValidationRef");
    planValidationRef = DesignArtifacts.requireNonNull(planValidationRef, "planValidationRef");
    compilerValidationRef =
        DesignArtifacts.requireNonNull(compilerValidationRef, "compilerValidationRef");
    executorValidationRef =
        DesignArtifacts.requireNonNull(executorValidationRef, "executorValidationRef");
  }
}
