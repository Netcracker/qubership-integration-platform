package org.qubership.integration.platform.ai.chain.patch;

import org.qubership.integration.platform.ai.integration.catalog.materialize.MaterializationMap;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;

/**
 * An imported chain with a patch already applied to it, and the binding that says which catalog
 * element each node stands for.
 */
public record PatchedChain(ChainPlanGraph graph, MaterializationMap materializationMap) {}
