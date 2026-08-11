package org.qubership.integration.platform.ai.compiler.policy;

import java.util.List;

/** One generator entry in the compiled policy artifact. */
public record CompilerGeneratorDescriptor(
    String generatorId,
    String skillId,
    int order,
    String planArtifact,
    List<String> ownedRules,
    CompilerGeneratorReadiness readiness) {}
