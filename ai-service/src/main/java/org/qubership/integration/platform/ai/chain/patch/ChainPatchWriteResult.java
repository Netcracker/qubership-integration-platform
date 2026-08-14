package org.qubership.integration.platform.ai.chain.patch;

import java.util.List;
import java.util.Map;
import org.qubership.integration.platform.ai.integration.catalog.materialize.MaterializationMap;

/**
 * What reached the catalog when a chain patch was written.
 *
 * <p>A patch that fails partway is unwound where it can be, and reported either way:
 * {@code changedElementIds} names what did land and {@code rollback} says what became of it, so the
 * reader is told the chain's real state rather than that nothing happened.
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
    List<String> removedElementIds,
    RollbackOutcome rollback) {

  /**
   * What the writer did about a write that failed partway.
   *
   * <p>Only two of the five write steps cannot be compensated: a property key the patch introduced
   * (the merge never deletes, so the key stays) and a deleted element (nothing brings it back). The
   * first leaves {@link #PARTIAL}, the second {@link #REFUSED} -- improvising a replacement element
   * under the old name would be a lie about the chain.
   */
  public enum RollbackOutcome {
    NOT_ATTEMPTED,
    COMPLETED,
    PARTIAL,
    REFUSED
  }

  /** For a write that removed nothing and had nothing to unwind. */
  public ChainPatchWriteResult(
      List<String> changedElementIds,
      List<String> failedElementIds,
      String error,
      MaterializationMap materializationMap) {
    this(changedElementIds, failedElementIds, error, materializationMap, List.of());
  }

  /** For a write that had nothing to unwind. */
  public ChainPatchWriteResult(
      List<String> changedElementIds,
      List<String> failedElementIds,
      String error,
      MaterializationMap materializationMap,
      List<String> removedElementIds) {
    this(
        changedElementIds,
        failedElementIds,
        error,
        materializationMap,
        removedElementIds,
        RollbackOutcome.NOT_ATTEMPTED);
  }

  public ChainPatchWriteResult {
    changedElementIds = changedElementIds == null ? List.of() : List.copyOf(changedElementIds);
    failedElementIds = failedElementIds == null ? List.of() : List.copyOf(failedElementIds);
    removedElementIds = removedElementIds == null ? List.of() : List.copyOf(removedElementIds);
    rollback = rollback == null ? RollbackOutcome.NOT_ATTEMPTED : rollback;
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
