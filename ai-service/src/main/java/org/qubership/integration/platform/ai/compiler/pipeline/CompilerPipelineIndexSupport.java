package org.qubership.integration.platform.ai.compiler.pipeline;

import java.util.Comparator;
import java.util.List;

/** Shared helpers for loading and validating compiler pipeline index artifacts. */
public final class CompilerPipelineIndexSupport {

  private CompilerPipelineIndexSupport() {}

  public static void requireSupportedSchema(CompilerPipelineIndex index) {
    int version = index.schemaVersion();
    if (version != CompilerPipelineIndexBuilder.SCHEMA_VERSION
        && version != CompilerPipelineIndexBuilder.SCHEMA_VERSION_V1) {
      throw new IllegalStateException(
          "Unsupported compiler pipeline index schema version: " + version);
    }
  }

  public static List<String> generationSkillIds(CompilerPipelineIndex index) {
    requireSupportedSchema(index);
    if (!index.entries().isEmpty()) {
      return index.entries().stream()
          .filter(CompilerPipelineEntry::generationCandidate)
          .sorted((left, right) -> Integer.compare(left.order(), right.order()))
          .map(CompilerPipelineEntry::skillId)
          .toList();
    }
    if (index.schemaVersion() == CompilerPipelineIndexBuilder.SCHEMA_VERSION) {
      return index.nodes().stream()
          .filter(node -> node.executionMode() == CompilerNodeExecutionMode.LLM_SKILL)
          .filter(node -> node.generatorId() != null && node.generatorId().startsWith("GEN-"))
          .sorted(
              Comparator.comparingInt(CompilerPipelineNode::topologicalLevel)
                  .thenComparingInt(CompilerPipelineNode::stableTieBreaker)
                  .thenComparing(CompilerPipelineNode::skillId))
          .map(CompilerPipelineNode::skillId)
          .toList();
    }
    return List.of();
  }
}
