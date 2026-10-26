package org.qubership.integration.platform.ai.plan;

import java.util.List;

/**
 * One capture diagnostic. A conflict finding names every participant in that conflict group.
 * Repair alternatives stay empty until repair policy assigns them.
 */
public record RequirementFinding(
    String code,
    String path,
    String entityId,
    List<String> relatedPaths,
    List<String> relatedEntityIds,
    List<String> repairAlternatives,
    String message) {

  public RequirementFinding {
    relatedPaths = relatedPaths == null ? List.of() : List.copyOf(relatedPaths);
    relatedEntityIds = relatedEntityIds == null ? List.of() : List.copyOf(relatedEntityIds);
    repairAlternatives = repairAlternatives == null ? List.of() : List.copyOf(repairAlternatives);
  }
}
