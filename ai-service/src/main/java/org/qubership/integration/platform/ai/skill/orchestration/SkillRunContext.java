package org.qubership.integration.platform.ai.skill.orchestration;

/** Context for a single skill invocation within a subgraph run. */
public record SkillRunContext(
    String conversationId,
    String skillId,
    String packVersion,
    SkillSubgraph subgraph,
    int stepIndex,
    boolean implementSegmentOnly,
    String effectiveUserText) {}
