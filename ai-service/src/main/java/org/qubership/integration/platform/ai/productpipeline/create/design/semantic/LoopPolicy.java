package org.qubership.integration.platform.ai.productpipeline.create.design.semantic;

import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignArtifacts;

/** Loop-2 repetition policy. An omitted {@code safetyBound} uses the element schema default. */
public record LoopPolicy(LoopMode mode, String expression, Integer safetyBound) {

  public LoopPolicy {
    mode = DesignArtifacts.requireNonNull(mode, "mode");
    expression = DesignArtifacts.requireText(expression, "expression");
    if (safetyBound != null && safetyBound <= 0) {
      throw new IllegalArgumentException("LoopPolicy safetyBound must be a positive integer");
    }
  }
}
