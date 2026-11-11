package org.qubership.integration.platform.ai.plan;

import java.util.List;
import org.qubership.integration.platform.ai.plan.model.ChainPlanEdge;

/** Typed validator facts that keep LLM plan repair scoped to a local graph problem. */
public record ChainPlanRepairIssue(
    String code,
    String message,
    String nodeId,
    String nodeType,
    String parentNodeId,
    List<String> siblingNodeIds,
    List<ChainPlanEdge> scopeEdges,
    String expectedScopeNodeId,
    String edgeId,
    List<String> invalidRefs) {}
