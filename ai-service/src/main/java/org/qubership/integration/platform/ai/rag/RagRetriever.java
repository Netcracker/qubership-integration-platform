package org.qubership.integration.platform.ai.rag;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.MetadataFilterBuilder;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.logging.AiTraceLog;

import java.util.List;

/**
 * Retrieves relevant QIP knowledge chunks from the in-memory embedding store for RAG augmentation.
 *
 * <p>Supports optional filtering by {@code source} (docs/schema) and {@code elementType} metadata
 * to scope retrieval for chain-generation scenarios.
 */
@ApplicationScoped
public class RagRetriever {

  private static final Logger LOG = Logger.getLogger(RagRetriever.class);

  private static final int DEFAULT_MAX_RESULTS = 5;
  private static final double DEFAULT_MIN_SCORE = 0.70;

  @Inject EmbeddingModel embeddingModel;

  @Inject EmbeddingStore<TextSegment> embeddingStore;

  /**
   * Retrieves the top-N most relevant chunks for the given query. Searches across the entire
   * knowledge base (docs + schemas).
   */
  public List<Content> retrieve(String query) {
    return retrieve(query, null, null, DEFAULT_MAX_RESULTS);
  }

  /**
   * Retrieves relevant chunks, optionally filtered by source and/or elementType metadata.
   *
   * @param query natural language query
   * @param source optional — "docs" or "schema"; null means search all
   * @param elementType optional — e.g. "service-call"; null means no filter
   * @param maxResults max number of results to return
   */
  public List<Content> retrieve(String query, String source, String elementType, int maxResults) {
    LOG.infof(
        "RAG retrieve: maxResults=%d, source=%s, elementType=%s, queryPreview=%s",
        maxResults,
        source,
        elementType,
        AiTraceLog.previewOneLine(query != null ? query : "", 240));
    // Use var — the Lombok-generated builder type is EmbeddingStoreContentRetrieverBuilder,
    // not the non-existent static inner class Builder.
    var builder =
        EmbeddingStoreContentRetriever.builder()
            .embeddingModel(embeddingModel)
            .embeddingStore(embeddingStore)
            .maxResults(maxResults)
            .minScore(DEFAULT_MIN_SCORE);

    if (source != null && elementType != null) {
      builder.filter(
          MetadataFilterBuilder.metadataKey("source")
              .isEqualTo(source)
              .and(MetadataFilterBuilder.metadataKey("elementType").isEqualTo(elementType)));
    } else if (source != null) {
      builder.filter(MetadataFilterBuilder.metadataKey("source").isEqualTo(source));
    } else if (elementType != null) {
      builder.filter(MetadataFilterBuilder.metadataKey("elementType").isEqualTo(elementType));
    }

    EmbeddingStoreContentRetriever retriever = builder.build();
    try {
      return retriever.retrieve(Query.from(query));
    } catch (RuntimeException e) {
      LOG.error(
          String.format(
              "RAG embedding retrieval failed (query length=%d)",
              query != null ? query.length() : 0),
          e);
      throw e;
    }
  }

  /** Convenience: retrieve schema chunks for a specific element type. */
  public List<Content> retrieveElementSchema(String elementType) {
    return retrieve("properties of element type " + elementType, "schema", elementType, 3);
  }

  /** Converts a list of content chunks to a single context string for prompt injection. */
  public String toContextString(List<Content> contents) {
    if (contents.isEmpty()) {
      return "";
    }
    StringBuilder sb = new StringBuilder();
    contents.forEach(c -> sb.append(c.textSegment().text()).append("\n\n---\n\n"));
    return sb.toString().trim();
  }
}
