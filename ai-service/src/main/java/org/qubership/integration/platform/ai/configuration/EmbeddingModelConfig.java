package org.qubership.integration.platform.ai.configuration;

import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;

/**
 * Provides an EmbeddingModel with explicit encoding format for providers (e.g. LiteLLM/vLLM) that
 * require this field in /v1/embeddings payload.
 */
@ApplicationScoped
public class EmbeddingModelConfig {

  @Produces
  @Singleton
  public EmbeddingModel embeddingModel(
      @ConfigProperty(name = "quarkus.langchain4j.openai.base-url") String baseUrl,
      @ConfigProperty(name = "quarkus.langchain4j.openai.api-key") String apiKey,
      @ConfigProperty(name = "quarkus.langchain4j.openai.embedding-model.model-name")
          String modelName,
      @ConfigProperty(name = "quarkus.langchain4j.timeout", defaultValue = "15m") Duration timeout,
      @ConfigProperty(name = "qip.ai.rag.embedding-encoding-format", defaultValue = "float")
          String encodingFormat) {
    return OpenAiEmbeddingModel.builder()
        .baseUrl(baseUrl)
        .apiKey(apiKey)
        .modelName(modelName)
        .timeout(timeout)
        .encodingFormat(encodingFormat)
        .build();
  }
}
