package org.qubership.integration.platform.ai.rag;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

/** Provides a single in-process {@link EmbeddingStore} for RAG (no external vector DB). */
@ApplicationScoped
public class InMemoryEmbeddingStoreProducer {

  @Produces
  @Singleton
  public EmbeddingStore<TextSegment> embeddingStore() {
    return new InMemoryEmbeddingStore<>();
  }
}
