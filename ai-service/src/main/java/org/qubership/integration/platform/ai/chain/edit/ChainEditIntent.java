package org.qubership.integration.platform.ai.chain.edit;

import java.util.List;
import java.util.Objects;

/**
 * What the reader asked for, resolved against the imported chain but not yet compiled.
 *
 * <p>An intent names the action and the elements it acts on. It never names a schema property key,
 * a catalog id, or a topology change: those belong to the owning generator skill, which reads the
 * element schemas and the knowledge package. A resolver that cannot decide which element is meant
 * says so through {@code unresolvedAmbiguities} and leaves {@code targetNodeIds} empty.
 */
public record ChainEditIntent(
    ChainEditAction action,
    List<String> targetNodeIds,
    String requestedChange,
    String externalBindingQuery,
    String requestedElementType,
    List<String> unresolvedAmbiguities) {

  /** An edit of elements the chain already has, without a requested element type. */
  public ChainEditIntent(
      ChainEditAction action,
      List<String> targetNodeIds,
      String requestedChange,
      String externalBindingQuery,
      List<String> unresolvedAmbiguities) {
    this(action, targetNodeIds, requestedChange, externalBindingQuery, null, unresolvedAmbiguities);
  }

  public ChainEditIntent {
    Objects.requireNonNull(action, "action");
    requestedElementType =
        requestedElementType == null || requestedElementType.isBlank()
            ? null
            : requestedElementType.trim();
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
    return action != ChainEditAction.UNRESOLVED
        && unresolvedAmbiguities.isEmpty()
        && !targetNodeIds.isEmpty();
  }
}
