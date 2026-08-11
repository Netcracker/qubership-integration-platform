package org.qubership.integration.platform.ai.productpipeline.store;

import java.util.List;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;

/** Authoritative mutable-facing run snapshot persisted inside the CAS run document. */
public record RunSnapshot(
    String runId,
    String conversationId,
    long runRevision,
    RunStatus status,
    String currentStageId,
    List<StageSnapshot> stages,
    CompilationArtifacts.Reference runManifestRef) {

  public RunSnapshot {
    stages = stages == null ? List.of() : List.copyOf(stages);
  }
}
