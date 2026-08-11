package org.qubership.integration.platform.ai.compiler;

/** Memory id helpers for compiler skill agent turns. */
public final class CompilerSkillMemoryIds {

  private static final String PREFIX = "compiler-skill";

  private CompilerSkillMemoryIds() {}

  public static String forSkill(String conversationId, String capabilityId) {
    if (conversationId == null || conversationId.isBlank()) {
      throw new IllegalArgumentException("conversationId is required");
    }
    if (capabilityId == null || capabilityId.isBlank()) {
      throw new IllegalArgumentException("capabilityId is required");
    }
    return PREFIX + "/" + conversationId + "/" + capabilityId;
  }
}
