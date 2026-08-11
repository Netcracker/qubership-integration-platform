package org.qubership.integration.platform.ai.skill.executor;

/** Outcome of a single skill invocation. */
public enum SkillRunStatus {
  COMPLETED,
  FAILED,
  HITL_PENDING,
  SKIPPED
}
