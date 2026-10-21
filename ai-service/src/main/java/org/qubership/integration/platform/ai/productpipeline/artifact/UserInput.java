package org.qubership.integration.platform.ai.productpipeline.artifact;

import java.time.Instant;

/** Immutable user input targeted at a stage waiting for input or approval. */
public record UserInput(String inputId, String targetStageId, String text, Instant receivedAt,
    org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Reference draftReference,
    java.util.List<String> sourceTurnIds) {
  public UserInput {
    sourceTurnIds = sourceTurnIds == null ? java.util.List.of() : java.util.List.copyOf(sourceTurnIds);
  }

  public UserInput(String inputId, String targetStageId, String text, Instant receivedAt) {
    this(inputId, targetStageId, text, receivedAt, null, java.util.List.of());
  }
}
