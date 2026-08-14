package org.qubership.integration.platform.ai.chain.patch;

import java.util.List;
import java.util.Map;
import org.qubership.integration.platform.ai.integration.catalog.materialize.MaterializationMap;

/**
 * What reached the catalog when a chain patch was written.
 *
 * <p>A patch that fails partway is reported, not unwound: {@code changedElementIds} names what did
 * land, so the reader is told the chain's real state rather than that nothing happened.
 * {@code changedElementIds} and {@code failedElementIds} are node ids; {@code materializationMap}
 * resolves a newly added node to the catalog element id the write gave it. Existing nodes need no
 * resolution -- their node id already is the catalog element id, by the importer's own contract --
 * so {@link #changedCatalogElementIds()} falls back to the node id when the map has no entry.
 */
public record ChainPatchWriteResult(
    List<String> changedElementIds,
    List<String> failedElementIds,
    String error,
    MaterializationMap materializationMap,
    List<String> removedElementIds) {

  /** For a write that removed nothing. */
  public ChainPatchWriteResult(
      List<String> changedElementIds,
      List<String> failedElementIds,
      String error,
      MaterializationMap materializationMap) {
    this(changedElementIds, failedElementIds, error, materializationMap, List.of());
  }

  public ChainPatchWriteResult {
    changedElementIds = changedElementIds == null ? List.of() : List.copyOf(changedElementIds);
    failedElementIds = failedElementIds == null ? List.of() : List.copyOf(failedElementIds);
    removedElementIds = removedElementIds == null ? List.of() : List.copyOf(removedElementIds);
  }

  public boolean succeeded() {
    return failedElementIds.isEmpty() && error == null;
  }

  /** {@link #changedElementIds()}, resolved to catalog element ids. */
  public List<String> changedCatalogElementIds() {
    return catalogElementIds(changedElementIds);
  }

  /** {@link #failedElementIds()}, resolved to catalog element ids. */
  public List<String> failedCatalogElementIds() {
    return catalogElementIds(failedElementIds);
  }

  private List<String> catalogElementIds(List<String> nodeIds) {
    Map<String, String> nodeIdToElementId =
        materializationMap == null ? Map.of() : materializationMap.nodeIdToElementId();
    return nodeIds.stream().map(nodeId -> nodeIdToElementId.getOrDefault(nodeId, nodeId)).toList();
  }
}
