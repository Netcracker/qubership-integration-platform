package org.qubership.integration.platform.ai.skill.logging;

import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.logging.AiTraceLog;

/** Grep-friendly skill orchestrator logging. */
public final class SkillTraceLog {

  private SkillTraceLog() {}

  public static void logSkillInvoke(
      Logger log, String skillId, String conversationId, int stepIndex) {
    log.infof(
        "Skill invoked [%s]: conversationId=%s, stepIndex=%d",
        skillId, conversationId, stepIndex);
  }

  public static void logSkillComplete(
      Logger log,
      String skillId,
      String conversationId,
      long durationMs,
      String status,
      String messagePreview) {
    log.infof(
        "Skill completed [%s]: conversationId=%s, durationMs=%d, status=%s, message=%s",
        skillId,
        conversationId,
        durationMs,
        status,
        AiTraceLog.preview(messagePreview, 200));
  }

  public static void logArtifactsPresent(
      Logger log, String conversationId, String artifactTypes) {
    log.infof(
        "Skill workspace artifacts conversationId=%s present=%s",
        conversationId, artifactTypes);
  }

  public static void logSkillSkipped(Logger log, String skillId, String conversationId, String reason) {
    log.infof(
        "Skill skipped [%s]: conversationId=%s, reason=%s", skillId, conversationId, reason);
  }
}
