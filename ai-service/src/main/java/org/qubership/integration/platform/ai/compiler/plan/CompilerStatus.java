package org.qubership.integration.platform.ai.compiler.plan;

import java.util.List;

/** Current compiler generation stage snapshot for the workspace. */
public record CompilerStatus(
    String currentStage,
    String nextSkillId,
    List<String> completedGenerators,
    List<String> skippedGenerators) {}
