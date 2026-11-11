package org.qubership.integration.platform.ai.chain.edit;

import org.qubership.integration.platform.ai.integration.apihub.ApiHubRequirementRefs;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;

/** Captures the structural result needed to resume one service-call binding. */
public record StructuralBindingContinuation(
    ChainPlanGraph structuredGraph,
    String targetNodeId,
    String serviceCallId,
    String bindingQuery,
    ApiHubRequirementRefs importRefs) {}
