package org.qubership.integration.platform.ai.skill.orchestration;

/** Context for a single skill invocation within a subgraph run. */
public record SkillRunContext(
    String conversationId,
    String skillId,
    String packVersion,
    SkillSubgraph subgraph,
    int stepIndex,
    boolean implementSegmentOnly,
    String effectiveUserText,
    String repairEvidence) {

  public SkillRunContext(
      String conversationId,
      String skillId,
      String packVersion,
      SkillSubgraph subgraph,
      int stepIndex,
      boolean implementSegmentOnly,
      String effectiveUserText) {
    this(conversationId, skillId, packVersion, subgraph, stepIndex, implementSegmentOnly,
        effectiveUserText, "");
  }

  public SkillRunContext {
    repairEvidence = repairEvidence == null ? "" : repairEvidence;
  }
}
