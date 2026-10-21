package org.qubership.integration.platform.ai.productpipeline.knowledge;

import java.util.List;

/** Search/filter/vector result set. */
public record KnowledgeSearchResult(
    KnowledgeResponseIdentity identity, List<CanonicalKnowledgeObject> objects) {
  public KnowledgeSearchResult {
    objects = objects == null ? List.of() : List.copyOf(objects);
  }
}
