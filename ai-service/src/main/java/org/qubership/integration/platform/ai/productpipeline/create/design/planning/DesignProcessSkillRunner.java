package org.qubership.integration.platform.ai.productpipeline.create.design.planning;

import java.util.Optional;

/** Runs one pinned process skill turn with an optional format-retry diagnostic. */
public interface DesignProcessSkillRunner {

  /**
   * Invokes the skill once after verifying {@code pinnedSkillHash} against the loaded skill body.
   * Hash verification is mandatory — callers must pass the expected pin; verification is never
   * skipped. {@code repairEvidence} is empty on a first turn; on a repair turn it carries the
   * formatted halt evidence for the model to read, distinct from {@code formatFailure}, which is
   * this turn's own format-contract retry rather than a prior halt.
   */
  String runOnce(
      String conversationId,
      String skillId,
      String input,
      Optional<String> formatFailure,
      Optional<String> repairEvidence,
      String pinnedSkillHash);
}
