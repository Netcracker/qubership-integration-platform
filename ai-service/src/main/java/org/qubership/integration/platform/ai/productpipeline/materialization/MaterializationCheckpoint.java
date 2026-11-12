package org.qubership.integration.platform.ai.productpipeline.materialization;

import java.util.Map;
import org.qubership.integration.platform.ai.integration.catalog.materialize.MaterializationMap;

/** Durable restart point written around each external materialization mutation. */
public record MaterializationCheckpoint(
    int schemaVersion,
    String executionKey,
    String chainId,
    MaterializationPhase completedPhase,
    MaterializationMap materializationMap,
    String pendingNodeId,
    Map<String, String> externalRequestKeys) {

  public MaterializationCheckpoint {
    materializationMap =
        materializationMap == null
            ? new MaterializationMap(chainId, Map.of(), Map.of(), Map.of())
            : new MaterializationMap(
                materializationMap.chainId(),
                materializationMap.nodeIdToElementId(),
                materializationMap.semanticEdgeOwnerElementIds(),
                materializationMap.mappingIntentExecutionNodeIds());
    externalRequestKeys =
        externalRequestKeys == null ? Map.of() : Map.copyOf(externalRequestKeys);
  }

  public boolean canReadBack() {
    if (chainId == null || chainId.isBlank() || !chainId.equals(materializationMap.chainId())
        || materializationMap.nodeIdToElementId().isEmpty() || pendingNodeId != null || completedPhase == null) {
      return false;
    }
    return switch (completedPhase) {
      case ELEMENTS, READ_BACK, RECONCILE, COMPLETE -> true;
      default -> false;
    };
  }
}
