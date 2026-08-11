package org.qubership.integration.platform.ai.skill.orchestration;

import org.qubership.integration.platform.ai.skill.workspace.SkillArtifactType;

import java.util.Set;

/** One compiler skill node in a subgraph manifest. */
public record SkillNode(
    String skillId,
    Set<SkillArtifactType> consumes,
    Set<SkillArtifactType> produces,
    boolean implementSegment,
    boolean optional) {}
