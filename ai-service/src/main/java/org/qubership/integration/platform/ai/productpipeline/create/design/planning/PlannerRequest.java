package org.qubership.integration.platform.ai.productpipeline.create.design.planning;

import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignArtifacts;

/**
 * Input for one pinned {@code cip-design-planner} invocation. {@code repairEvidenceText} is blank
 * on a first turn; on a repair turn it carries the formatted halt evidence the planner reads
 * alongside the design input, so the retried plan is not blind to why the prior one was rejected.
 */
public record PlannerRequest(
    String conversationId, String input, String pinnedSkillHash, String repairEvidenceText) {

  public PlannerRequest {
    conversationId = DesignArtifacts.requireText(conversationId, "conversationId");
    input = DesignArtifacts.requireText(input, "input");
    pinnedSkillHash = DesignArtifacts.requireText(pinnedSkillHash, "pinnedSkillHash");
    repairEvidenceText = repairEvidenceText == null ? "" : repairEvidenceText;
  }

  /** First-turn convenience: no repair evidence to carry. */
  public PlannerRequest(String conversationId, String input, String pinnedSkillHash) {
    this(conversationId, input, pinnedSkillHash, "");
  }
}
