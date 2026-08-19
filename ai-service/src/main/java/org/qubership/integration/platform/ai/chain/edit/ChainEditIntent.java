package org.qubership.integration.platform.ai.chain.edit;

import java.util.List;
import java.util.Objects;

/**
 * What the reader asked for, resolved against the imported chain but not yet compiled.
 *
 * <p>An intent names the action and the elements it acts on. It never names a schema property key,
 * a catalog id, or a topology change: structure belongs to the shared structure stage and
 * configuration belongs to the generators selected from pinned ownership metadata.
 * {@code ADD_ELEMENTS} is complete when the capture
 * names the catalog type and a placement; existing element ids are neighbors or wrap targets, not
 * a requirement for a new root trigger. A resolver that cannot decide which existing element an
 * edit means says so through {@code unresolvedAmbiguities} and leaves {@code targetNodeIds} empty.
 */
public record ChainEditIntent(
    ChainEditAction action,
    List<String> targetNodeIds,
    String requestedChange,
    String externalBindingQuery,
    String requestedElementType,
    String cronExpression,
    ChainEditPlacement placement,
    List<String> unresolvedAmbiguities) {

  /** An edit of elements the chain already has, without a requested element type. */
  public ChainEditIntent(
      ChainEditAction action,
      List<String> targetNodeIds,
      String requestedChange,
      String externalBindingQuery,
      List<String> unresolvedAmbiguities) {
    this(
        action,
        targetNodeIds,
        requestedChange,
        externalBindingQuery,
        null,
        null,
        ChainEditPlacement.UNSET,
        unresolvedAmbiguities);
  }

  /** An addition or edit that names a type but not a schedule or placement. */
  public ChainEditIntent(
      ChainEditAction action,
      List<String> targetNodeIds,
      String requestedChange,
      String externalBindingQuery,
      String requestedElementType,
      List<String> unresolvedAmbiguities) {
    this(
        action,
        targetNodeIds,
        requestedChange,
        externalBindingQuery,
        requestedElementType,
        null,
        ChainEditPlacement.UNSET,
        unresolvedAmbiguities);
  }

  public ChainEditIntent {
    Objects.requireNonNull(action, "action");
    requestedElementType =
        requestedElementType == null || requestedElementType.isBlank()
            ? null
            : requestedElementType.trim();
    cronExpression =
        cronExpression == null || cronExpression.isBlank() ? null : cronExpression.trim();
    placement = placement == null ? ChainEditPlacement.UNSET : placement;
    targetNodeIds = targetNodeIds == null ? List.of() : List.copyOf(targetNodeIds);
    requestedChange = requestedChange == null ? "" : requestedChange;
    externalBindingQuery =
        externalBindingQuery == null || externalBindingQuery.isBlank()
            ? null
            : externalBindingQuery.trim();
    unresolvedAmbiguities =
        unresolvedAmbiguities == null ? List.of() : List.copyOf(unresolvedAmbiguities);
  }

  public boolean resolved() {
    if (action == ChainEditAction.UNRESOLVED || !unresolvedAmbiguities.isEmpty()) {
      return false;
    }
    if (action == ChainEditAction.NO_CHANGE) {
      return true;
    }
    if (action == ChainEditAction.ADD_ELEMENTS) {
      return requestedElementType != null && placement != ChainEditPlacement.UNSET
          && (placement != ChainEditPlacement.AFTER_TARGET || !targetNodeIds.isEmpty());
    }
    return !targetNodeIds.isEmpty();
  }

  ChainEditIntent withTargets(List<String> targets) {
    return new ChainEditIntent(
        action,
        targets,
        requestedChange,
        externalBindingQuery,
        requestedElementType,
        cronExpression,
        placement,
        List.of());
  }
}
