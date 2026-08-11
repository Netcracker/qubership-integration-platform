package org.qubership.integration.platform.ai.a2a.artifacts;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Allowlisted public A2A artifact projection for create-chain.
 *
 * <p>Payload never carries storage coordinates, prompts, model traces, credentials, raw logs, or
 * pipeline snapshots.
 */
public record CreateChainPublicArtifact(
    String id, String type, long revision, String contentHash, Map<String, Object> payload) {

  public CreateChainPublicArtifact {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(contentHash, "contentHash");
    if (id.isBlank()) {
      throw new IllegalArgumentException("id is required");
    }
    if (type.isBlank()) {
      throw new IllegalArgumentException("type is required");
    }
    if (contentHash.isBlank()) {
      throw new IllegalArgumentException("contentHash is required");
    }
    payload = payload == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(payload));
  }

  /** Stable identity for idempotent Task and SSE updates. */
  public String revisionKey() {
    return id + "@" + revision;
  }
}
