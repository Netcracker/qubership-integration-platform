package org.qubership.integration.platform.ai.productpipeline.artifact;

import java.util.List;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatch;

/** Minimal graph-patch fixtures for CREATE-chain artifact contract tests. */
final class GraphPatchFixtures {

  private GraphPatchFixtures() {}

  static GraphPatch empty(String skillId) {
    return new GraphPatch(
        skillId + "-empty",
        skillId,
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        "");
  }
}
