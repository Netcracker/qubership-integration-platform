package org.qubership.integration.platform.ai.productpipeline.knowledge;

import java.util.List;

/** Relation traversal result. */
public record KnowledgeRelationResult(
    KnowledgeResponseIdentity identity, List<CanonicalKnowledgeObject.Relation> relations) {
  public KnowledgeRelationResult {
    relations = relations == null ? List.of() : List.copyOf(relations);
  }
}
