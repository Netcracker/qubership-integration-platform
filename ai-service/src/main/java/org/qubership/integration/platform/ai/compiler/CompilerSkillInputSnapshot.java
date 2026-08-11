package org.qubership.integration.platform.ai.compiler;

import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;

/** Prompt-time snapshot of workspace inputs for a compiler skill run. */
public record CompilerSkillInputSnapshot(
    String rawUserRequest,
    String requirementBrief,
    String selectedPatternId,
    ChainPlanGraph chainPlanGraph,
    String generatorPlanManifestSummary) {}
