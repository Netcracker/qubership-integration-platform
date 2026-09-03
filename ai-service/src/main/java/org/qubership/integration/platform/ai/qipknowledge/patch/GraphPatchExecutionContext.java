package org.qubership.integration.platform.ai.qipknowledge.patch;

import java.util.List;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;

/** Immutable runtime context used to validate and apply one graph patch attempt. */
public record GraphPatchExecutionContext(
    String runId,
    String skillId,
    String requirementDigest,
    String inputGraphDigest,
    String compilerPackageDigest,
    String languageVersion,
    RequirementBrief requirementBrief,
    List<CompilationArtifacts.Reference> consumedArtifacts,
    ChainPlanGraph inputGraph,
    GraphPatchOwnershipPolicy ownership,
    String attemptId,
    List<String> editTargetNodeIds,
    String mappingGenerationContext) {

  public GraphPatchExecutionContext {
    consumedArtifacts = consumedArtifacts == null ? List.of() : List.copyOf(consumedArtifacts);
    ownership = ownership == null ? GraphPatchOwnershipPolicy.denyAll() : ownership;
    editTargetNodeIds = editTargetNodeIds == null ? List.of() : List.copyOf(editTargetNodeIds);
  }

  /** Create path and callers with no named edit targets. */
  public GraphPatchExecutionContext(
      String runId,
      String skillId,
      String requirementDigest,
      String inputGraphDigest,
      String compilerPackageDigest,
      String languageVersion,
      RequirementBrief requirementBrief,
      List<CompilationArtifacts.Reference> consumedArtifacts,
      ChainPlanGraph inputGraph,
      GraphPatchOwnershipPolicy ownership,
      String attemptId) {
    this(
        runId,
        skillId,
        requirementDigest,
        inputGraphDigest,
        compilerPackageDigest,
        languageVersion,
        requirementBrief,
        consumedArtifacts,
        inputGraph,
        ownership,
        attemptId,
        List.of(),
        null);
  }

  /** Callers that name edit targets and have no mapping prompt overlay. */
  public GraphPatchExecutionContext(
      String runId,
      String skillId,
      String requirementDigest,
      String inputGraphDigest,
      String compilerPackageDigest,
      String languageVersion,
      RequirementBrief requirementBrief,
      List<CompilationArtifacts.Reference> consumedArtifacts,
      ChainPlanGraph inputGraph,
      GraphPatchOwnershipPolicy ownership,
      String attemptId,
      List<String> editTargetNodeIds) {
    this(
        runId,
        skillId,
        requirementDigest,
        inputGraphDigest,
        compilerPackageDigest,
        languageVersion,
        requirementBrief,
        consumedArtifacts,
        inputGraph,
        ownership,
        attemptId,
        editTargetNodeIds,
        null);
  }

  public GraphPatchExecutionContext withEditTargetNodeIds(List<String> targetNodeIds) {
    return new GraphPatchExecutionContext(
        runId,
        skillId,
        requirementDigest,
        inputGraphDigest,
        compilerPackageDigest,
        languageVersion,
        requirementBrief,
        consumedArtifacts,
        inputGraph,
        ownership,
        attemptId,
        targetNodeIds,
        mappingGenerationContext);
  }

  public GraphPatchExecutionContext withConsumedArtifacts(
      List<CompilationArtifacts.Reference> consumed) {
    return new GraphPatchExecutionContext(
        runId,
        skillId,
        requirementDigest,
        inputGraphDigest,
        compilerPackageDigest,
        languageVersion,
        requirementBrief,
        consumed,
        inputGraph,
        ownership,
        attemptId,
        editTargetNodeIds,
        mappingGenerationContext);
  }

  public GraphPatchExecutionContext withMappingGenerationContext(String mappingContext) {
    return new GraphPatchExecutionContext(
        runId,
        skillId,
        requirementDigest,
        inputGraphDigest,
        compilerPackageDigest,
        languageVersion,
        requirementBrief,
        consumedArtifacts,
        inputGraph,
        ownership,
        attemptId,
        editTargetNodeIds,
        mappingContext);
  }
}
