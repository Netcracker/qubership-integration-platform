package org.qubership.integration.platform.ai.skill.workspace;

import java.util.Optional;
import java.util.Set;

/** Conversation-scoped artifact bag for the skill orchestrator. */
public interface SkillWorkspace {

  String conversationId();

  Optional<SkillArtifact> get(SkillArtifactType type);

  void put(SkillArtifact artifact);

  void remove(SkillArtifactType type);

  Set<SkillArtifactType> presentTypes();

  int runCount(String skillId);

  void incrementRunCount(String skillId);
}
