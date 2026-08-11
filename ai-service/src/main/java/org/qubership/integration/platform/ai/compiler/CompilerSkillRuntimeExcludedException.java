package org.qubership.integration.platform.ai.compiler;

/** Raised when a compiler skill is blocked from runtime traversal or prompt context. */
public class CompilerSkillRuntimeExcludedException extends RuntimeException {

  private final String skillId;

  public CompilerSkillRuntimeExcludedException(String skillId, String reason) {
    super(reason);
    this.skillId = skillId;
  }

  public String skillId() {
    return skillId;
  }
}
