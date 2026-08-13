package org.qubership.integration.platform.ai.chain.imports;

import org.qubership.integration.platform.ai.integration.catalog.materialize.MaterializationMap;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;

/**
 * An existing catalog chain read into the plan model, ready to be patched.
 *
 * <p>{@code materializationMap} binds every node to the catalog element it was read from, so a patch
 * applied on top of {@code graph} materializes as an update of those elements rather than as a
 * second copy of the chain. {@code baseGraphDigest} identifies the state the patch was built
 * against.
 */
public record ImportedChainPlan(
    ChainPlanGraph graph, MaterializationMap materializationMap, String baseGraphDigest) {}
