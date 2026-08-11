package org.qubership.integration.platform.ai.llm.ratelimit;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import io.quarkiverse.langchain4j.ModelName;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.qubership.integration.platform.ai.configuration.AppConfig;

/**
 * Produces the unqualified {@link ChatModel} / {@link StreamingChatModel} beans used by {@code
 * @RegisterAiService} agents.
 *
 * <p>OpenAI synthetic beans are configured under the named model {@value #UPSTREAM_MODEL_NAME} so
 * they carry {@link ModelName} and are not disabled by this producer. The producer is the sole
 * unqualified ChatModel — agents inject the rate-limit wrapper without competing with OpenAI
 * {@code @DefaultBean}.
 */
@ApplicationScoped
public class RateLimitChatModelProducer {

  /** Named OpenAI config key: {@code quarkus.langchain4j.openai.upstream.*}. */
  public static final String UPSTREAM_MODEL_NAME = "upstream";

  private final AppConfig appConfig;
  private final RateLimitBackoffSleeper sleeper;
  private final ChatModel upstreamChatModel;
  private final StreamingChatModel upstreamStreamingChatModel;
  private final RateLimitErrorClassifier classifier = new RateLimitErrorClassifier();
  private final RateLimitWaitPolicy policy = new RateLimitWaitPolicy();

  @Inject
  RateLimitChatModelProducer(
      AppConfig appConfig,
      RateLimitBackoffSleeper sleeper,
      @ModelName(UPSTREAM_MODEL_NAME) ChatModel upstreamChatModel,
      @ModelName(UPSTREAM_MODEL_NAME) StreamingChatModel upstreamStreamingChatModel) {
    this.appConfig = appConfig;
    this.sleeper = sleeper;
    this.upstreamChatModel = upstreamChatModel;
    this.upstreamStreamingChatModel = upstreamStreamingChatModel;
  }

  @Produces
  @ApplicationScoped
  RateLimitChatModel rateLimitChatModel() {
    return new RateLimitChatModel(
        upstreamChatModel, appConfig, sleeper, classifier, policy);
  }

  @Produces
  @ApplicationScoped
  RateLimitStreamingChatModel rateLimitStreamingChatModel() {
    return new RateLimitStreamingChatModel(
        upstreamStreamingChatModel, appConfig, sleeper, classifier, policy);
  }
}
