package org.qubership.integration.platform.ai.compiler.policy;

/** Source document checksums used to build the policy artifact. */
public record CompilerGeneratorPolicySource(
    String generatorContractsSha, String ruleMappingSha) {}
