package org.qubership.integration.platform.ai.productpipeline.create.design.planning;

import java.util.Optional;

/** Runs one pinned process skill turn with an optional format-retry diagnostic. */
public interface DesignProcessSkillRunner {

  /**
   * Invokes the skill once after verifying {@code pinnedSkillHash} against the loaded skill body.
   * Hash verification is mandatory — callers must pass the expected pin; verification is never
   * skipped.
   */
  String runOnce(
      String conversationId,
      String skillId,
      String input,
      Optional<String> formatFailure,
      String pinnedSkillHash);
}
