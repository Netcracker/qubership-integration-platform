package org.qubership.integration.platform.ai.productpipeline.store;

import java.util.List;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;

/** Point-in-time stage state inside the authoritative run snapshot. */
public record StageSnapshot(
    String stageId,
    StageStatus status,
    List<CompilationArtifacts.Reference> outputRefs,
    String approvedArtifactId,
    List<CompilationArtifacts.Reference> candidateReferences,
    CompilationArtifacts.Reference approvableReference,
    Integer candidateRevision) {

  public StageSnapshot {
    outputRefs = outputRefs == null ? List.of() : List.copyOf(outputRefs);
    candidateReferences =
        candidateReferences == null ? List.of() : List.copyOf(candidateReferences);
  }

  /** Compatibility constructor used by earlier runtime commits. */
  public StageSnapshot(
      String stageId,
      StageStatus status,
      List<CompilationArtifacts.Reference> outputRefs,
      String approvedArtifactId) {
    this(stageId, status, outputRefs, approvedArtifactId, List.of(), null, null);
  }
}
