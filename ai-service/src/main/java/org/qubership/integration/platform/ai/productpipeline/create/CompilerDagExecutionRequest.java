package org.qubership.integration.platform.ai.productpipeline.create;

import java.util.List;
import java.util.Objects;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.productpipeline.artifact.ResolvedCompilerDag;
import org.qubership.integration.platform.ai.productpipeline.artifact.RunManifest;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.CatalogBindingResolution;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.NormalizedDesignFlow;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;

/** Inputs for one shared compiler DAG execution pass. */
public record CompilerDagExecutionRequest(
    String runId,
    String conversationId,
    RunManifest runManifest,
    RequirementBrief requirementBrief,
    NormalizedDesignFlow normalizedFlow,
    ResolvedCompilerDag executionDag,
    List<String> approvedOwningSkillIds,
    List<CatalogBindingResolution> catalogBindings,
    List<CompilationArtifacts.Reference> preSatisfiedArtifactRefs) {

  public CompilerDagExecutionRequest {
    Objects.requireNonNull(runId, "runId");
    Objects.requireNonNull(conversationId, "conversationId");
    Objects.requireNonNull(runManifest, "runManifest");
    Objects.requireNonNull(requirementBrief, "requirementBrief");
    Objects.requireNonNull(executionDag, "executionDag");
    approvedOwningSkillIds =
        approvedOwningSkillIds == null ? List.of() : List.copyOf(approvedOwningSkillIds);
    catalogBindings = catalogBindings == null ? List.of() : List.copyOf(catalogBindings);
    preSatisfiedArtifactRefs =
        preSatisfiedArtifactRefs == null ? List.of() : List.copyOf(preSatisfiedArtifactRefs);
  }
}
