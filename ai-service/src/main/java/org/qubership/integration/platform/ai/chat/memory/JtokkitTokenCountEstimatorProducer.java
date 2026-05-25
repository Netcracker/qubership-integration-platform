package org.qubership.integration.platform.ai.chat.memory;

import dev.langchain4j.model.TokenCountEstimator;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Supplies a local {@link TokenCountEstimator} for {@code TOKEN_WINDOW} chat memory. */
@ApplicationScoped
public class JtokkitTokenCountEstimatorProducer {

  @Produces
  @ApplicationScoped
  TokenCountEstimator tokenCountEstimator(
      @ConfigProperty(name = "quarkus.langchain4j.openai.chat-model.model-name") String modelName) {
    return new QipJtokkitTokenCountEstimator(modelName);
  }
}
