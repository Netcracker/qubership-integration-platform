package org.qubership.integration.platform.ai.compiler.pipeline;

import java.util.List;

/** One directed dependency edge between compiler pipeline skills. */
public record CompilerPipelineDependency(
    String producerSkillId, String consumerSkillId, List<String> artifactTypes) {

  public CompilerPipelineDependency {
    artifactTypes = artifactTypes == null ? List.of() : List.copyOf(artifactTypes);
  }
}
