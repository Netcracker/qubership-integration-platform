package org.qubership.integration.platform.ai.productpipeline.create;

import java.time.Instant;
import java.util.Objects;
import org.qubership.integration.platform.ai.productpipeline.artifact.RunManifest;

/**
 * Immutable CREATE runtime ownership for one conversation (product create-chain@1 or
 * create-chain@2).
 */
public record CreateRunBinding(
    String conversationId, String productRunId, RunManifest runManifest, Instant createdAt) {

  public CreateRunBinding {
    Objects.requireNonNull(conversationId, "conversationId");
    Objects.requireNonNull(createdAt, "createdAt");
    if (productRunId == null || productRunId.isBlank()) {
      throw new IllegalArgumentException("product binding requires productRunId");
    }
    if (runManifest == null) {
      throw new IllegalArgumentException("product binding requires runManifest");
    }
  }
}
