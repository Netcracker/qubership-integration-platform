package org.qubership.integration.platform.ai.productpipeline.profile;

import java.util.List;

/** Product-profile policy that selects a compiler-index closure for planning. */
public record CompilerPipelinePolicy(
    List<Integer> supportedIndexSchemas,
    List<String> allowedPhases,
    List<ArtifactTypeRef> preSatisfiedArtifacts,
    List<ArtifactTypeRef> requiredTerminalArtifacts) {

  public CompilerPipelinePolicy {
    supportedIndexSchemas =
        supportedIndexSchemas == null ? List.of() : List.copyOf(supportedIndexSchemas);
    allowedPhases = allowedPhases == null ? List.of() : List.copyOf(allowedPhases);
    preSatisfiedArtifacts =
        preSatisfiedArtifacts == null ? List.of() : List.copyOf(preSatisfiedArtifacts);
    requiredTerminalArtifacts =
        requiredTerminalArtifacts == null ? List.of() : List.copyOf(requiredTerminalArtifacts);
  }
}
