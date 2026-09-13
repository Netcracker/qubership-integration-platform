package org.qubership.integration.platform.ai.chat.memory;

import dev.langchain4j.model.TokenCountEstimator;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import org.qubership.integration.platform.ai.configuration.AppConfig;

/** Supplies a local {@link TokenCountEstimator} for {@code TOKEN_WINDOW} chat memory. */
@ApplicationScoped
public class JtokkitTokenCountEstimatorProducer {

  @Produces
  @ApplicationScoped
  TokenCountEstimator tokenCountEstimator(AppConfig appConfig) {
    return new QipJtokkitTokenCountEstimator(appConfig.llm().modelName());
  }
}
