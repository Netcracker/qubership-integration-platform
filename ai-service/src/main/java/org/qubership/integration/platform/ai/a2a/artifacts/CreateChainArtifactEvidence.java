package org.qubership.integration.platform.ai.a2a.artifacts;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Durable pipeline evidence used to project a public A2A artifact.
 *
 * <p>May contain internal fields; {@link CreateChainPublicArtifactProjector} strips them.
 */
public record CreateChainArtifactEvidence(
    String artifactId,
    String type,
    long revision,
    String contentHash,
    Map<String, Object> durableFields) {

  public CreateChainArtifactEvidence {
    Objects.requireNonNull(artifactId, "artifactId");
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(contentHash, "contentHash");
    if (artifactId.isBlank()) {
      throw new IllegalArgumentException("artifactId is required");
    }
    if (type.isBlank()) {
      throw new IllegalArgumentException("type is required");
    }
    if (contentHash.isBlank()) {
      throw new IllegalArgumentException("contentHash is required");
    }
    durableFields =
        durableFields == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(durableFields));
  }
}
