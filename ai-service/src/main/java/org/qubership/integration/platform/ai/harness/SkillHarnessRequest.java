package org.qubership.integration.platform.ai.harness;

/** Request body for {@code POST /api/v1/harness/skill-run}. */
public record SkillHarnessRequest(
    String conversationId, String chainId, String skillId, String prompt) {}
