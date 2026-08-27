package org.qubership.integration.platform.ai.productpipeline.create.design.input;

import java.util.List;
import java.util.Objects;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementDataMapping;

/**
 * Drops mapping rows that fail shape before coverage runs. An incomplete row is the absence of a
 * mapping: missing {@code stage}, or {@code PASS_THROUGH} with empty {@code sourceFactIds}.
 */
public final class DesignRequirementDataMappingNormalizer {

  private DesignRequirementDataMappingNormalizer() {}

  public static RequirementBrief normalize(RequirementBrief brief) {
    Objects.requireNonNull(brief, "brief");
    List<RequirementDataMapping> complete = completeMappings(brief.dataMappings());
    if (complete.size() == brief.dataMappings().size()) {
      return brief;
    }
    return brief.withDataMappings(complete);
  }

  static List<RequirementDataMapping> completeMappings(List<RequirementDataMapping> mappings) {
    if (mappings == null || mappings.isEmpty()) {
      return List.of();
    }
    return mappings.stream().filter(DesignRequirementDataMappingNormalizer::isComplete).toList();
  }

  static boolean isComplete(RequirementDataMapping mapping) {
    if (mapping == null || mapping.stage() == null) {
      return false;
    }
    return mapping.mode() != RequirementDataMapping.Mode.PASS_THROUGH
        || !mapping.sourceFactIds().isEmpty();
  }
}
