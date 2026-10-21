package org.qubership.integration.platform.ai.skill.workspace;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** In-memory {@link SkillWorkspace} implementation. */
public final class InMemorySkillWorkspace implements SkillWorkspace {

  private final String conversationId;
  private final Map<SkillArtifactType, SkillArtifact> artifacts = new EnumMap<>(SkillArtifactType.class);
  private final Map<String, Integer> runCounts = new HashMap<>();

  public InMemorySkillWorkspace(String conversationId) {
    this.conversationId = conversationId;
  }

  @Override
  public String conversationId() {
    return conversationId;
  }

  @Override
  public Optional<SkillArtifact> get(SkillArtifactType type) {
    return Optional.ofNullable(artifacts.get(type));
  }

  @Override
  public void put(SkillArtifact artifact) {
    artifacts.put(artifact.type(), artifact);
  }

  @Override
  public void remove(SkillArtifactType type) {
    artifacts.remove(type);
  }

  @Override
  public Set<SkillArtifactType> presentTypes() {
    return Set.copyOf(artifacts.keySet());
  }

  @Override
  public int runCount(String skillId) {
    return runCounts.getOrDefault(skillId, 0);
  }

  @Override
  public void incrementRunCount(String skillId) {
    runCounts.merge(skillId, 1, Integer::sum);
  }

  /** Skill ids that completed at least one run in this workspace. */
  public java.util.Set<String> completedSkillIds() {
    return java.util.Set.copyOf(runCounts.keySet());
  }
}
