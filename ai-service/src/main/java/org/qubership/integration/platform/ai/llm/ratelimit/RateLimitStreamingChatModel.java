package org.qubership.integration.platform.ai.llm.ratelimit;

import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.qubership.integration.platform.ai.configuration.AppConfig;

/**
 * Unqualified {@link StreamingChatModel} CDI bean used by {@code @RegisterAiService} agents.
 *
 * <p>Wraps the named OpenAI {@code upstream} streaming model with rate-limit backoff. Kept as a
 * concrete class so {@link StreamingChatModel#supportedCapabilities()} forwards to the OpenAI
 * delegate.
 */
public class RateLimitStreamingChatModel implements StreamingChatModel {

  private final StreamingChatModel delegate;
  private final AppConfig appConfig;
  private final RateLimitBackoffSleeper sleeper;
  private final RateLimitErrorClassifier classifier;
  private final RateLimitWaitPolicy policy;

  RateLimitStreamingChatModel(
      StreamingChatModel delegate,
      AppConfig appConfig,
      RateLimitBackoffSleeper sleeper,
      RateLimitErrorClassifier classifier,
      RateLimitWaitPolicy policy) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
    this.appConfig = Objects.requireNonNull(appConfig, "appConfig");
    this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
    this.classifier = Objects.requireNonNull(classifier, "classifier");
    this.policy = Objects.requireNonNull(policy, "policy");
  }

  /** Upstream OpenAI (or other) streaming model being rate-limit wrapped. */
  StreamingChatModel delegate() {
    return delegate;
  }

  @Override
  public void chat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
    ChatRequest requestWithDefaults =
        chatRequest
            .toBuilder()
            .parameters(delegate.defaultRequestParameters().overrideWith(chatRequest.parameters()))
            .build();
    boolean enabled = appConfig.llm().rateLimit().enabled();
    int maxAttempts = appConfig.llm().rateLimit().maxAttempts();
    AtomicBoolean backoffInCall = new AtomicBoolean(false);
    Consumer<RateLimitAwareChatModel.BackoffEvent> onBackoff =
        RateLimitSinkSupport.backoffNotifier(backoffInCall);
    RateLimitAwareStreamingChatModel rateLimited =
        new RateLimitAwareStreamingChatModel(
            delegate, classifier, policy, sleeper, enabled, maxAttempts, onBackoff);
    rateLimited.chat(
        requestWithDefaults, RateLimitSinkSupport.completionAwareHandler(handler, backoffInCall));
  }

  @Override
  public ChatRequestParameters defaultRequestParameters() {
    return delegate.defaultRequestParameters();
  }

  @Override
  public List<ChatModelListener> listeners() {
    return delegate.listeners();
  }

  @Override
  public ModelProvider provider() {
    return delegate.provider();
  }

  @Override
  public Set<Capability> supportedCapabilities() {
    return delegate.supportedCapabilities();
  }
}
