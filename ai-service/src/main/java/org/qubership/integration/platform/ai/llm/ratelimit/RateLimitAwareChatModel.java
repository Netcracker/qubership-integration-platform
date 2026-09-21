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
import org.jboss.logging.Logger;

public final class RateLimitAwareChatModel implements ChatModel {

  private static final Logger LOG = Logger.getLogger(RateLimitAwareChatModel.class);

  public record BackoffEvent(int attempt, int waitSeconds) {}

  private final ChatModel delegate;
  private final RateLimitErrorClassifier rateLimitClassifier;
  private final TransientErrorClassifier transientClassifier;
  private final RateLimitWaitPolicy rateLimitPolicy;
  private final TransientWaitPolicy transientPolicy;
  private final RateLimitBackoffSleeper sleeper;
  private final boolean rateLimitEnabled;
  private final int rateLimitMaxAttempts;
  private final boolean transientEnabled;
  private final int transientMaxAttempts;
  private final Consumer<BackoffEvent> onBackoff;

  public RateLimitAwareChatModel(
      ChatModel delegate,
      RateLimitErrorClassifier classifier,
      RateLimitWaitPolicy policy,
      RateLimitBackoffSleeper sleeper,
      boolean enabled,
      int maxAttempts,
      Consumer<BackoffEvent> onBackoff) {
    this(
        delegate,
        classifier,
        new TransientErrorClassifier(),
        policy,
        TransientWaitPolicy.fromCsv("2,5,10"),
        sleeper,
        enabled,
        maxAttempts,
        false,
        0,
        onBackoff);
  }

  public RateLimitAwareChatModel(
      ChatModel delegate,
      RateLimitErrorClassifier rateLimitClassifier,
      TransientErrorClassifier transientClassifier,
      RateLimitWaitPolicy rateLimitPolicy,
      TransientWaitPolicy transientPolicy,
      RateLimitBackoffSleeper sleeper,
      boolean rateLimitEnabled,
      int rateLimitMaxAttempts,
      boolean transientEnabled,
      int transientMaxAttempts,
      Consumer<BackoffEvent> onBackoff) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
    this.rateLimitClassifier = Objects.requireNonNull(rateLimitClassifier, "rateLimitClassifier");
    this.transientClassifier = Objects.requireNonNull(transientClassifier, "transientClassifier");
    this.rateLimitPolicy = Objects.requireNonNull(rateLimitPolicy, "rateLimitPolicy");
    this.transientPolicy = Objects.requireNonNull(transientPolicy, "transientPolicy");
    this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
    this.rateLimitEnabled = rateLimitEnabled;
    this.rateLimitMaxAttempts = rateLimitMaxAttempts;
    this.transientEnabled = transientEnabled;
    this.transientMaxAttempts = transientMaxAttempts;
    this.onBackoff = Objects.requireNonNull(onBackoff, "onBackoff");
  }

  @Override
  public ChatResponse chat(ChatRequest chatRequest) {
    if (!rateLimitEnabled && !transientEnabled) {
      return delegate.chat(chatRequest);
    }
    int rateLimitAttempt = 0;
    int transientAttempt = 0;
    while (true) {
      try {
        return delegate.chat(chatRequest);
      } catch (RuntimeException error) {
        if (rateLimitEnabled
            && rateLimitClassifier.isRateLimit(error)
            && rateLimitPolicy.shouldRetry(rateLimitAttempt, rateLimitMaxAttempts)) {
          int waitSeconds =
              rateLimitPolicy.resolveWaitSeconds(
                  rateLimitClassifier.extractWait(error), rateLimitAttempt);
          onBackoff.accept(new BackoffEvent(rateLimitAttempt + 1, waitSeconds));
          sleeper.sleepSeconds(waitSeconds);
          rateLimitAttempt++;
          continue;
        }
        if (transientEnabled
            && transientClassifier.isTransient(error)
            && transientPolicy.shouldRetry(transientAttempt, transientMaxAttempts)) {
          int waitSeconds = transientPolicy.waitSeconds(transientAttempt);
          LOG.infof(
              "LLM transient retry attempt=%d/%d wait=%ds reason=%s",
              transientAttempt + 1,
              transientMaxAttempts,
              waitSeconds,
              rootMessage(error));
          sleeper.sleepSeconds(waitSeconds);
          transientAttempt++;
          continue;
        }
        throw error;
      }
    }
  }

  private static String rootMessage(Throwable error) {
    Throwable root = error;
    while (root.getCause() != null) {
      root = root.getCause();
    }
    String message = root.getMessage();
    return message == null ? root.getClass().getSimpleName() : message;
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
