package org.qubership.integration.platform.ai.compiler;

/** Raised when a compiler skill cannot be resolved in the active knowledge pack. */
public class CompilerSkillNotFoundException extends RuntimeException {

  public CompilerSkillNotFoundException(String capabilityId) {
    super("Compiler skill not found: " + capabilityId);
  }
}
