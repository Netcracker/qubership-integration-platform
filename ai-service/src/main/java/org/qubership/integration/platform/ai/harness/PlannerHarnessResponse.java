package org.qubership.integration.platform.ai.harness;

import java.util.List;

/** Complete evidence from one isolated design-planner run. */
public record PlannerHarnessResponse(
    String conversationId,
    SkillHarnessStatus status,
    String message,
    String skillHash,
    String modelName,
    List<Attempt> attempts) {

  public PlannerHarnessResponse {
    attempts = attempts == null ? List.of() : List.copyOf(attempts);
  }

  /** One model call, including the format diagnostic supplied after a rejected response. */
  public record Attempt(
      int number,
      String formatFailure,
      String response,
      String error,
      long durationMillis) {}
}
