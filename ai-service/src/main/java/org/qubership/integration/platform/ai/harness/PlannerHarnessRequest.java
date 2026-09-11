package org.qubership.integration.platform.ai.harness;

/** Request body for {@code POST /api/v1/harness/planner-run}. */
public record PlannerHarnessRequest(
    String conversationId, String input, String repairEvidenceText) {}
