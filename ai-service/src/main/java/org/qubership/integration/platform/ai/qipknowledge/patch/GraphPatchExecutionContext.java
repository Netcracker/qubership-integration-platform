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
    List<String> editTargetNodeIds) {

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
        List.of());
  }
}
