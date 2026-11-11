package org.qubership.integration.platform.ai.chain.edit.planning;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Validated branch assignments for wrapping a service-call with error handling.
 *
 * <p>{@code originalTargetNodeIds} is the intent the reader named. {@code tryMoveExisting} and
 * {@code finallyMoveExisting} are the ids Java may move. Structure capture must match those lists
 * exactly.
 */
public record ChainEditStructuralPlan(
    FailureDeliveryStrategy failureDelivery,
    List<String> originalTargetNodeIds,
    List<String> tryMoveExisting,
    CatchBranchRole catchRole,
    String catchScriptLabel,
    List<String> finallyMoveExisting,
    String reporterNodeId,
    String reporterSchemaVariant,
    List<String> ambiguities,
    String clarificationQuestion,
    List<String> clarificationChoices,
    String rationale) {

  public ChainEditStructuralPlan {
    Objects.requireNonNull(failureDelivery, "failureDelivery");
    originalTargetNodeIds = copyIds(originalTargetNodeIds);
    tryMoveExisting = copyIds(tryMoveExisting);
    catchRole = catchRole == null ? CatchBranchRole.NONE : catchRole;
    catchScriptLabel = blankToNull(catchScriptLabel);
    finallyMoveExisting = copyIds(finallyMoveExisting);
    reporterNodeId = blankToNull(reporterNodeId);
    reporterSchemaVariant = blankToNull(reporterSchemaVariant);
    ambiguities = copyIds(ambiguities);
    clarificationQuestion = blankToNull(clarificationQuestion);
    clarificationChoices = copyIds(clarificationChoices);
    rationale = rationale == null ? "" : rationale;
  }

  public boolean clarify() {
    return failureDelivery == FailureDeliveryStrategy.CLARIFY;
  }

  /** Existing ids this plan moves, in declaration order, without duplicates. */
  public List<String> expandedTargetNodeIds() {
    LinkedHashSet<String> ids = new LinkedHashSet<>();
    ids.addAll(originalTargetNodeIds);
    ids.addAll(tryMoveExisting);
    ids.addAll(finallyMoveExisting);
    return List.copyOf(ids);
  }

  public Set<String> tryMoveSet() {
    return new LinkedHashSet<>(tryMoveExisting);
  }

  public Set<String> finallyMoveSet() {
    return new LinkedHashSet<>(finallyMoveExisting);
  }

  private static List<String> copyIds(List<String> values) {
    if (values == null || values.isEmpty()) {
      return List.of();
    }
    LinkedHashSet<String> ids = new LinkedHashSet<>();
    for (String value : values) {
      if (value == null || value.isBlank()) {
        continue;
      }
      ids.add(value.trim());
    }
    return List.copyOf(ids);
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }
}
