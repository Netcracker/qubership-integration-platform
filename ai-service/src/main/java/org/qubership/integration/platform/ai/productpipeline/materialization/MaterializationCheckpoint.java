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
            ? new MaterializationMap(chainId, Map.of())
            : new MaterializationMap(
                materializationMap.chainId(),
                materializationMap.nodeIdToElementId() == null
                    ? Map.of()
                    : Map.copyOf(materializationMap.nodeIdToElementId()));
    externalRequestKeys =
        externalRequestKeys == null ? Map.of() : Map.copyOf(externalRequestKeys);
  }
}
