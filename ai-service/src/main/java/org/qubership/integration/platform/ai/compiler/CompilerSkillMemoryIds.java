package org.qubership.integration.platform.ai.compiler;

/** Memory id helpers for compiler skill agent turns. */
public final class CompilerSkillMemoryIds {

  private static final String PREFIX = "compiler-skill";

  private CompilerSkillMemoryIds() {}

  /** Resolves trusted agent memory IDs using the format owned by this helper. */
  public static String conversationId(String memoryId) {
    if (memoryId == null || memoryId.isBlank()) {
      return null;
    }
    String id = memoryId.trim();
    String prefix = PREFIX + "/";
    if (!id.startsWith(prefix)) {
      return id;
    }
    int separator = id.lastIndexOf('/');
    return separator > prefix.length() && separator < id.length() - 1
        ? id.substring(prefix.length(), separator) : null;
  }

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
