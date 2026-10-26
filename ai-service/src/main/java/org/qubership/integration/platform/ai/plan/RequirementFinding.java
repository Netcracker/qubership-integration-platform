package org.qubership.integration.platform.ai.plan;

import java.util.List;
import java.util.Objects;

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
    relatedEntityIds = copyIds(relatedEntityIds);
    repairAlternatives = repairAlternatives == null ? List.of() : List.copyOf(repairAlternatives);
  }

  private static List<String> copyIds(List<String> ids) {
    if (ids == null) {
      return List.of();
    }
    return List.copyOf(ids.stream().filter(Objects::nonNull).toList());
  }
}
