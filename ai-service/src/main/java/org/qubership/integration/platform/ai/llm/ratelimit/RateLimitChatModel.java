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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.qubership.integration.platform.ai.configuration.AppConfig;

/**
 * Unqualified {@link ChatModel} CDI bean used by {@code @RegisterAiService} agents.
 *
 * <p>Wraps the named OpenAI {@code upstream} model with rate-limit backoff. Kept as a concrete
 * class (not an anonymous {@code ChatModel}) so default methods such as {@link
 * ChatModel#supportedCapabilities()} forward to the OpenAI delegate.
 */
public class RateLimitChatModel implements ChatModel {

  private final ChatModel delegate;
  private final AppConfig appConfig;
  private final RateLimitBackoffSleeper sleeper;
  private final RateLimitErrorClassifier classifier;
  private final RateLimitWaitPolicy policy;

  RateLimitChatModel(
      ChatModel delegate,
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

  /** Upstream OpenAI (or other) model being rate-limit wrapped. */
  ChatModel delegate() {
    return delegate;
  }

  @Override
  public ChatResponse chat(ChatRequest chatRequest) {
    boolean enabled = appConfig.llm().rateLimit().enabled();
    int maxAttempts = appConfig.llm().rateLimit().maxAttempts();
    AtomicBoolean backoffInCall = new AtomicBoolean(false);
    Consumer<RateLimitAwareChatModel.BackoffEvent> onBackoff =
        RateLimitSinkSupport.backoffNotifier(backoffInCall);
    RateLimitAwareChatModel rateLimited =
        new RateLimitAwareChatModel(
            delegate, classifier, policy, sleeper, enabled, maxAttempts, onBackoff);
    ChatResponse response = rateLimited.chat(chatRequest);
    RateLimitSinkSupport.completeIfBackoffOccurred(backoffInCall);
    return response;
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
