package org.qubership.integration.platform.ai.chain.patch;

import org.qubership.integration.platform.ai.integration.catalog.materialize.MaterializationMap;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;

/**
 * An imported chain with a patch already applied to it, and the binding that says which catalog
 * element each node stands for.
 *
 * <p>{@code before} is the chain as it was read. The writer needs what the patched graph no longer
 * holds: the placement and connections of everything being removed.
 */
public record PatchedChain(
    ChainPlanGraph before, ChainPlanGraph graph, MaterializationMap materializationMap) {

  /** For a patch that removes nothing, where either graph answers the same questions. */
  public PatchedChain(ChainPlanGraph graph, MaterializationMap materializationMap) {
    this(graph, graph, materializationMap);
  }
}
