package org.qubership.integration.platform.ai.productpipeline.create.design.model;

import java.util.List;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Reference;

/** Executor-owned validation result bound to the approved report and projection. */
public record ExecutorValidationBundle(
    String schemaVersion,
    String graphDigest,
    Reference designPlanReportRef,
    String designPlanReportHash,
    Reference designExecutionPlanRef,
    String designExecutionPlanHash,
    boolean passed,
    List<String> findings) {

  public ExecutorValidationBundle {
    schemaVersion = DesignArtifacts.requireText(schemaVersion, "schemaVersion");
    graphDigest = DesignArtifacts.requireText(graphDigest, "graphDigest");
    designPlanReportRef =
        DesignArtifacts.requireNonNull(designPlanReportRef, "designPlanReportRef");
    designPlanReportHash =
        DesignArtifacts.requireText(designPlanReportHash, "designPlanReportHash");
    designExecutionPlanRef =
        DesignArtifacts.requireNonNull(designExecutionPlanRef, "designExecutionPlanRef");
    designExecutionPlanHash =
        DesignArtifacts.requireText(designExecutionPlanHash, "designExecutionPlanHash");
    findings = DesignArtifacts.copyList(findings);
  }
}
