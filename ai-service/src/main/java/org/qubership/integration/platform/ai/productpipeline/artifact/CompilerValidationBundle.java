package org.qubership.integration.platform.ai.productpipeline.artifact;

import java.util.List;

/** Aggregates deterministic compiler validation pass results for one graph digest. */
public record CompilerValidationBundle(
    int schemaVersion, String graphDigest, List<CompilerValidationPass> passes) {

  public CompilerValidationBundle {
    passes = passes == null ? List.of() : List.copyOf(passes);
  }

  public boolean approvalEligible() {
    return passes.stream().allMatch(pass -> pass != null && pass.result() != null && pass.result().valid());
  }
}
