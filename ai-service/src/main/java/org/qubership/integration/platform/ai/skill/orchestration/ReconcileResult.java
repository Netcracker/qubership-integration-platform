package org.qubership.integration.platform.ai.skill.orchestration;

import java.util.List;

/** Structural read-back diff between intended plan and catalog state. */
public record ReconcileResult(
    boolean matches,
    List<String> missingElementIds,
    List<String> missingConnections,
    List<String> parentMismatches,
    List<String> labelMismatches,
    List<String> propertyMismatches,
    List<String> chainMismatches,
    String summary) {

  public ReconcileResult {
    missingElementIds = missingElementIds == null ? List.of() : List.copyOf(missingElementIds);
    missingConnections = missingConnections == null ? List.of() : List.copyOf(missingConnections);
    parentMismatches = parentMismatches == null ? List.of() : List.copyOf(parentMismatches);
    labelMismatches = labelMismatches == null ? List.of() : List.copyOf(labelMismatches);
    propertyMismatches = propertyMismatches == null ? List.of() : List.copyOf(propertyMismatches);
    chainMismatches = chainMismatches == null ? List.of() : List.copyOf(chainMismatches);
    summary = summary == null ? "" : summary;
  }

  public ReconcileResult(
      boolean matches,
      List<String> missingElementIds,
      List<String> missingConnections,
      List<String> parentMismatches,
      String summary) {
    this(
        matches,
        missingElementIds,
        missingConnections,
        parentMismatches,
        List.of(),
        List.of(),
        List.of(),
        summary);
  }
}
