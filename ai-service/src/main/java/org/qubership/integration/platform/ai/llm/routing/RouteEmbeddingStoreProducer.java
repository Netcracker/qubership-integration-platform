package org.qubership.integration.platform.ai.llm.routing;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

@ApplicationScoped
public class RouteEmbeddingStoreProducer {

  @Produces
  @Singleton
  @RouteEmbedding
  public EmbeddingStore<TextSegment> routeEmbeddingStore() {
    return new InMemoryEmbeddingStore<>();
  }
}
