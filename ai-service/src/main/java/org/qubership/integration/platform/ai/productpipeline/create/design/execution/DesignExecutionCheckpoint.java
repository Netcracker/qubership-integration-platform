package org.qubership.integration.platform.ai.productpipeline.create.design.execution;

import java.util.List;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Reference;
import org.qubership.integration.platform.ai.productpipeline.artifact.ArtifactProvenance;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignArtifacts;

/**
 * Restart checkpoint for post-approval design execution.
 *
 * <p>Reuse of a completed step requires an exact match of every field in {@link CompletedStep}.
 */
public record DesignExecutionCheckpoint(
    String schemaVersion,
    Reference approvalRef,
    String designPlanReportHash,
    String designExecutionPlanHash,
    String runManifestHash,
    DesignExecutionPhase phase,
    List<CompletedStep> completedSteps) {

  public DesignExecutionCheckpoint {
    schemaVersion = DesignArtifacts.requireText(schemaVersion, "schemaVersion");
    approvalRef = DesignArtifacts.requireNonNull(approvalRef, "approvalRef");
    designPlanReportHash =
        DesignArtifacts.requireText(designPlanReportHash, "designPlanReportHash");
    designExecutionPlanHash =
        DesignArtifacts.requireText(designExecutionPlanHash, "designExecutionPlanHash");
    runManifestHash = DesignArtifacts.requireText(runManifestHash, "runManifestHash");
    phase = DesignArtifacts.requireNonNull(phase, "phase");
    completedSteps = DesignArtifacts.copyList(completedSteps);
  }

  public record CompletedStep(
      String stepId,
      List<String> inputHashes,
      List<Reference> outputRefs,
      List<String> outputHashes,
      ArtifactProvenance provenance,
      String outcome) {

    public CompletedStep {
      stepId = DesignArtifacts.requireText(stepId, "stepId");
      inputHashes = DesignArtifacts.copyList(inputHashes);
      outputRefs = DesignArtifacts.copyList(outputRefs);
      outputHashes = DesignArtifacts.copyList(outputHashes);
      provenance = DesignArtifacts.requireNonNull(provenance, "provenance");
      outcome = DesignArtifacts.requireText(outcome, "outcome");
    }
  }
}
