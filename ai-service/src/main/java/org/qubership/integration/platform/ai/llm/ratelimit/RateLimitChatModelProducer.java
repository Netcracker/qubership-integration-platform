package org.qubership.integration.platform.ai.llm.ratelimit;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import io.quarkiverse.langchain4j.ModelName;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import java.net.URI;
import java.util.Locale;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.configuration.AppConfig;

/**
 * Produces the unqualified {@link ChatModel} / {@link StreamingChatModel} beans used by {@code
 * @RegisterAiService} agents.
 *
 * <p>Provider-specific synthetic beans use separate names. The producer selects one at runtime and
 * remains the sole unqualified ChatModel for registered agents.
 */
@ApplicationScoped
public class RateLimitChatModelProducer {

  private static final Logger LOG = Logger.getLogger(RateLimitChatModelProducer.class);

  public static final String OPENAI_UPSTREAM_MODEL_NAME = "openai-upstream";
  public static final String ANTHROPIC_UPSTREAM_MODEL_NAME = "anthropic-upstream";

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
      @ModelName(OPENAI_UPSTREAM_MODEL_NAME) ChatModel openAiChatModel,
      @ModelName(OPENAI_UPSTREAM_MODEL_NAME) StreamingChatModel openAiStreamingChatModel,
      @ModelName(ANTHROPIC_UPSTREAM_MODEL_NAME) ChatModel anthropicChatModel,
      @ModelName(ANTHROPIC_UPSTREAM_MODEL_NAME) StreamingChatModel anthropicStreamingChatModel) {
    this.appConfig = appConfig;
    this.sleeper = sleeper;
    boolean useAnthropic = useAnthropic(appConfig.llm().provider(), appConfig.llm().baseUrl());
    this.upstreamChatModel = useAnthropic ? anthropicChatModel : openAiChatModel;
    this.upstreamStreamingChatModel =
        useAnthropic ? anthropicStreamingChatModel : openAiStreamingChatModel;
  }

  static boolean useAnthropic(String provider, String baseUrl) {
    String normalized = provider == null ? "auto" : provider.trim().toLowerCase(Locale.ROOT);
    return switch (normalized) {
      case "anthropic" -> true;
      case "openai" -> false;
      case "auto" -> "api.anthropic.com".equalsIgnoreCase(URI.create(baseUrl).getHost());
      default ->
          throw new IllegalArgumentException(
              "Unsupported LLM provider '" + provider + "'. Use auto, openai, or anthropic.");
    };
  }

  void onStart(@Observes StartupEvent ignored) {
    LOG.infof("LLM chat model configured model=%s", appConfig.llm().modelName());
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
