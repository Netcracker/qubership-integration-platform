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
    List<String> unresolvedAmbiguities) {

  public ChainEditIntent {
    Objects.requireNonNull(action, "action");
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
    return unresolvedAmbiguities.isEmpty() && !targetNodeIds.isEmpty();
  }
}
