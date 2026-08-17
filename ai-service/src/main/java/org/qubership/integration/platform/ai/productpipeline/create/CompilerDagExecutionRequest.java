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
    List<CompilationArtifacts.Reference> preSatisfiedArtifactRefs,
    CompilerExecutionSeed seed) {

  public CompilerDagExecutionRequest {
    Objects.requireNonNull(runId, "runId");
    Objects.requireNonNull(conversationId, "conversationId");
    Objects.requireNonNull(runManifest, "runManifest");
    Objects.requireNonNull(executionDag, "executionDag");
    if (seed == null) {
      Objects.requireNonNull(requirementBrief, "requirementBrief");
    }
    approvedOwningSkillIds =
        approvedOwningSkillIds == null ? List.of() : List.copyOf(approvedOwningSkillIds);
    catalogBindings = catalogBindings == null ? List.of() : List.copyOf(catalogBindings);
    preSatisfiedArtifactRefs =
        preSatisfiedArtifactRefs == null ? List.of() : List.copyOf(preSatisfiedArtifactRefs);
  }

  /** A CREATE run whose seed is the requirement brief it has always started from. */
  @SuppressWarnings("java:S107")
  public CompilerDagExecutionRequest(
      String runId,
      String conversationId,
      RunManifest runManifest,
      RequirementBrief requirementBrief,
      NormalizedDesignFlow normalizedFlow,
      ResolvedCompilerDag executionDag,
      List<String> approvedOwningSkillIds,
      List<CatalogBindingResolution> catalogBindings,
      List<CompilationArtifacts.Reference> preSatisfiedArtifactRefs) {
    this(
        runId,
        conversationId,
        runManifest,
        requirementBrief,
        normalizedFlow,
        executionDag,
        approvedOwningSkillIds,
        catalogBindings,
        preSatisfiedArtifactRefs,
        null);
  }

  /** The seed this run starts from, falling back to the CREATE shape when none was given. */
  public CompilerExecutionSeed effectiveSeed() {
    return seed != null ? seed : CompilerExecutionSeed.forCreate(conversationId, requirementBrief);
  }
}
