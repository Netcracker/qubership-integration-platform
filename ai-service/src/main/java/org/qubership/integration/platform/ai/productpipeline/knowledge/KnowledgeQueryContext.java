package org.qubership.integration.platform.ai.productpipeline.knowledge;

import java.util.Objects;

/** Pinned knowledge identity for one query. */
public record KnowledgeQueryContext(KnowledgePackageRef packageRef) {
  public KnowledgeQueryContext {
    Objects.requireNonNull(packageRef, "packageRef");
  }
}
