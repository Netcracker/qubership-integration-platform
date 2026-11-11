package org.qubership.integration.platform.ai.productpipeline.create.design.model;

import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Reference;

/**
 * Handshake from the design executor into Java materialization after validation completes.
 */
public record MaterializationRequest(
    String schemaVersion,
    Reference approvalRef,
    Reference designPlanReportRef,
    Reference designExecutionPlanRef,
    String graphDigest,
    String orderedPatchDigest,
    Reference validatedExecutionBundleRef) {

  public MaterializationRequest {
    schemaVersion = DesignArtifacts.requireText(schemaVersion, "schemaVersion");
    approvalRef = DesignArtifacts.requireNonNull(approvalRef, "approvalRef");
    designPlanReportRef =
        DesignArtifacts.requireNonNull(designPlanReportRef, "designPlanReportRef");
    designExecutionPlanRef =
        DesignArtifacts.requireNonNull(designExecutionPlanRef, "designExecutionPlanRef");
    graphDigest = DesignArtifacts.requireText(graphDigest, "graphDigest");
    orderedPatchDigest = DesignArtifacts.requireText(orderedPatchDigest, "orderedPatchDigest");
    validatedExecutionBundleRef =
        DesignArtifacts.requireNonNull(
            validatedExecutionBundleRef, "validatedExecutionBundleRef");
  }
}
