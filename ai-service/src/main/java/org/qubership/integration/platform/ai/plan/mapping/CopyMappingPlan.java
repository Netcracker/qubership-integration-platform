package org.qubership.integration.platform.ai.plan.mapping;

import java.util.List;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingSource;

/** Frozen operands and save sites for one native COPY script. */
public record CopyMappingPlan(List<Read> reads, List<Save> saves) {
  public CopyMappingPlan {
    reads = reads == null ? List.of() : List.copyOf(reads);
    saves = saves == null ? List.of() : List.copyOf(saves);
  }

  public record Read(String ruleId, String targetField, MappingSource.Message origin,
      String schemaDigest, String targetSchemaDigest, String contextId, boolean nullable) {}

  public record Save(String sourceField, String contextId, boolean fromOutput, boolean nullable) {}
}
