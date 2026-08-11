package org.qubership.integration.platform.ai.plan;

import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;

/** Payload stored under {@link CompilationArtifacts.Kind#CHAIN_PLAN_GRAPH}. */
public record ChainPlanGraphArtifact(ChainPlanGraph graph) {}
