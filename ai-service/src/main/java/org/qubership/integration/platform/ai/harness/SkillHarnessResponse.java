package org.qubership.integration.platform.ai.harness;

/** Response body for {@code POST /api/v1/harness/skill-run}. */
public record SkillHarnessResponse(
    String conversationId, SkillHarnessStatus status, String message) {}
