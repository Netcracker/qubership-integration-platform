package org.qubership.integration.platform.ai.compiler.policy;

/** One unresolved runtime-supported generator discovered during policy validation. */
public record CompilerGeneratorPolicyIssue(String generatorId, String skillId, String reason) {}
