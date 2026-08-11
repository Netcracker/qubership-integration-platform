package org.qubership.integration.platform.ai.llm.ratelimit;

import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

public final class RateLimitAwareChatModel implements ChatModel {

  public record BackoffEvent(int attempt, int waitSeconds) {}

  private final ChatModel delegate;
  private final RateLimitErrorClassifier classifier;
  private final RateLimitWaitPolicy policy;
  private final RateLimitBackoffSleeper sleeper;
  private final boolean enabled;
  private final int maxAttempts;
  private final Consumer<BackoffEvent> onBackoff;

  public RateLimitAwareChatModel(
      ChatModel delegate,
      RateLimitErrorClassifier classifier,
      RateLimitWaitPolicy policy,
      RateLimitBackoffSleeper sleeper,
      boolean enabled,
      int maxAttempts,
      Consumer<BackoffEvent> onBackoff) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
    this.classifier = Objects.requireNonNull(classifier, "classifier");
    this.policy = Objects.requireNonNull(policy, "policy");
    this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
    this.enabled = enabled;
    this.maxAttempts = maxAttempts;
    this.onBackoff = Objects.requireNonNull(onBackoff, "onBackoff");
  }

  @Override
  public ChatResponse chat(ChatRequest chatRequest) {
    if (!enabled) {
      return delegate.chat(chatRequest);
    }
    int attempt = 0;
    while (true) {
      try {
        return delegate.chat(chatRequest);
      } catch (RuntimeException error) {
        if (!classifier.isRateLimit(error) || !policy.shouldRetry(attempt, maxAttempts)) {
          throw error;
        }
        int waitSeconds = policy.resolveWaitSeconds(classifier.extractWait(error), attempt);
        onBackoff.accept(new BackoffEvent(attempt + 1, waitSeconds));
        sleeper.sleepSeconds(waitSeconds);
        attempt++;
      }
    }
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
