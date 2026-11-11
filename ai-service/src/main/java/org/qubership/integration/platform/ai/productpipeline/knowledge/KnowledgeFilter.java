package org.qubership.integration.platform.ai.productpipeline.knowledge;

/** Filter criteria for knowledge object search. */
public record KnowledgeFilter(String type, int limit) {
  public KnowledgeFilter {
    if (limit < 1 || limit > 100) {
      throw new IllegalArgumentException("limit must be between 1 and 100");
    }
  }
}
