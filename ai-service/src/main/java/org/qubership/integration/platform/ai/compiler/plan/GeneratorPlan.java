package org.qubership.integration.platform.ai.compiler.plan;

import java.util.List;

/** One generator entry in the workspace execution manifest. */
public record GeneratorPlan(
    String generatorId,
    String skillId,
    GeneratorPlanStatus status,
    List<String> matchedSignals,
    List<String> targetNodeIds) {}
