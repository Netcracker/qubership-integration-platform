package org.qubership.integration.platform.ai.productpipeline.create.design.planning;

import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignArtifacts;

/** Input for one pinned {@code cip-design-planner} invocation. */
public record PlannerRequest(String conversationId, String input, String pinnedSkillHash) {

  public PlannerRequest {
    conversationId = DesignArtifacts.requireText(conversationId, "conversationId");
    input = DesignArtifacts.requireText(input, "input");
    pinnedSkillHash = DesignArtifacts.requireText(pinnedSkillHash, "pinnedSkillHash");
  }
}
