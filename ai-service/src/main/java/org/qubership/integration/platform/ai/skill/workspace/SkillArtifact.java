package org.qubership.integration.platform.ai.skill.workspace;

import java.time.Instant;
import java.util.UUID;

/** One versioned artifact produced or updated by a skill executor. */
public record SkillArtifact(
    SkillArtifactType type,
    String artifactId,
    Instant updatedAt,
    String producerSkillId,
    SkillArtifactPayload payload) {

  public static SkillArtifact of(
      SkillArtifactType type, String producerSkillId, SkillArtifactPayload payload) {
    return new SkillArtifact(
        type, UUID.randomUUID().toString(), Instant.now(), producerSkillId, payload);
  }
}
