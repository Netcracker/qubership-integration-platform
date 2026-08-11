package org.qubership.integration.platform.ai.productpipeline.knowledge;

import java.util.Objects;

/** Provenance returned by every sidecar response. */
public record KnowledgeResponseIdentity(KnowledgePackageRef packageRef) {
  public KnowledgeResponseIdentity {
    Objects.requireNonNull(packageRef, "packageRef");
  }
}
