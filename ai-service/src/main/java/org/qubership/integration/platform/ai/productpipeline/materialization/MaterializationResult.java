package org.qubership.integration.platform.ai.productpipeline.materialization;

import java.util.Map;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Reference;
import org.qubership.integration.platform.ai.integration.catalog.materialize.MaterializationMap;

/** Durable output of the Task-14 materialization core, optionally enriched with Phase 6 evidence. */
public record MaterializationResult(
    int schemaVersion,
    String chainId,
    MaterializationMap materializationMap,
    String approvedGraphDigest,
    MaterializationPhase completedPhase,
    Reference catalogSnapshotRef,
    Reference reconcileResultRef) {

  public MaterializationResult {
    materializationMap =
        materializationMap == null
            ? new MaterializationMap(chainId, Map.of())
            : new MaterializationMap(
                materializationMap.chainId(),
                materializationMap.nodeIdToElementId() == null
                    ? Map.of()
                    : Map.copyOf(materializationMap.nodeIdToElementId()));
  }

  /** Core-only constructor used by {@link ProductChainMaterializer}. */
  public MaterializationResult(
      int schemaVersion,
      String chainId,
      MaterializationMap materializationMap,
      String approvedGraphDigest,
      MaterializationPhase completedPhase) {
    this(
        schemaVersion,
        chainId,
        materializationMap,
        approvedGraphDigest,
        completedPhase,
        null,
        null);
  }

  /** Returns a copy that carries catalog read-back and reconcile references. */
  public MaterializationResult withCatalogEvidence(
      Reference catalogSnapshotRef, Reference reconcileResultRef) {
    return new MaterializationResult(
        schemaVersion,
        chainId,
        materializationMap,
        approvedGraphDigest,
        completedPhase,
        catalogSnapshotRef,
        reconcileResultRef);
  }
}
