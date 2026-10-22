package org.qubership.integration.platform.ai.productpipeline.knowledge;

import java.util.Set;

/** Capability-facing knowledge access API. */
public interface KnowledgeClient {
  KnowledgeObjectResult exact(KnowledgeQueryContext context, String id);

  KnowledgeSearchResult filter(KnowledgeQueryContext context, KnowledgeFilter filter);

  KnowledgeRelationResult relations(KnowledgeQueryContext context, String id, Set<String> kinds);

  KnowledgeContextPackage context(KnowledgeQueryContext context, KnowledgeContextRequest request);
}
