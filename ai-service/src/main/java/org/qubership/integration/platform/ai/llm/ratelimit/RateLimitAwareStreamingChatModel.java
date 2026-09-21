package org.qubership.integration.platform.ai.llm.ratelimit;

import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.CompleteToolCall;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.chat.response.PartialResponseContext;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.PartialThinkingContext;
import dev.langchain4j.model.chat.response.PartialToolCall;
import dev.langchain4j.model.chat.response.PartialToolCallContext;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.jboss.logging.Logger;

public final class RateLimitAwareStreamingChatModel implements StreamingChatModel {

  private static final Logger LOG = Logger.getLogger(RateLimitAwareStreamingChatModel.class);

  private final StreamingChatModel delegate;
  private final RateLimitErrorClassifier rateLimitClassifier;
  private final TransientErrorClassifier transientClassifier;
  private final RateLimitWaitPolicy rateLimitPolicy;
  private final TransientWaitPolicy transientPolicy;
  private final RateLimitBackoffSleeper sleeper;
  private final boolean rateLimitEnabled;
  private final int rateLimitMaxAttempts;
  private final boolean transientEnabled;
  private final int transientMaxAttempts;
  private final Consumer<RateLimitAwareChatModel.BackoffEvent> onBackoff;

  public RateLimitAwareStreamingChatModel(
      StreamingChatModel delegate,
      RateLimitErrorClassifier classifier,
      RateLimitWaitPolicy policy,
      RateLimitBackoffSleeper sleeper,
      boolean enabled,
      int maxAttempts,
      Consumer<RateLimitAwareChatModel.BackoffEvent> onBackoff) {
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

  public RateLimitAwareStreamingChatModel(
      StreamingChatModel delegate,
      RateLimitErrorClassifier rateLimitClassifier,
      TransientErrorClassifier transientClassifier,
      RateLimitWaitPolicy rateLimitPolicy,
      TransientWaitPolicy transientPolicy,
      RateLimitBackoffSleeper sleeper,
      boolean rateLimitEnabled,
      int rateLimitMaxAttempts,
      boolean transientEnabled,
      int transientMaxAttempts,
      Consumer<RateLimitAwareChatModel.BackoffEvent> onBackoff) {
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
  public void chat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
    if (!rateLimitEnabled && !transientEnabled) {
      delegate.chat(chatRequest, handler);
      return;
    }
    chatWithRetry(chatRequest, handler, 0, 0);
  }

  private void chatWithRetry(
      ChatRequest chatRequest,
      StreamingChatResponseHandler handler,
      int rateLimitAttempt,
      int transientAttempt) {
    AtomicBoolean tokensStarted = new AtomicBoolean(false);
    delegate.chat(
        chatRequest,
        observingHandler(
            handler, chatRequest, rateLimitAttempt, transientAttempt, tokensStarted));
  }

  private StreamingChatResponseHandler observingHandler(
      StreamingChatResponseHandler handler,
      ChatRequest chatRequest,
      int rateLimitAttempt,
      int transientAttempt,
      AtomicBoolean tokensStarted) {
    return new StreamingChatResponseHandler() {

      @Override
      public void onPartialResponse(String partialResponse) {
        tokensStarted.set(true);
        handler.onPartialResponse(partialResponse);
      }

      @Override
      public void onPartialResponse(PartialResponse partialResponse, PartialResponseContext context) {
        tokensStarted.set(true);
        handler.onPartialResponse(partialResponse, context);
      }

      @Override
      public void onPartialThinking(PartialThinking partialThinking) {
        handler.onPartialThinking(partialThinking);
      }

      @Override
      public void onPartialThinking(PartialThinking partialThinking, PartialThinkingContext context) {
        handler.onPartialThinking(partialThinking, context);
      }

      @Override
      public void onPartialToolCall(PartialToolCall partialToolCall) {
        tokensStarted.set(true);
        handler.onPartialToolCall(partialToolCall);
      }

      @Override
      public void onPartialToolCall(PartialToolCall partialToolCall, PartialToolCallContext context) {
        tokensStarted.set(true);
        handler.onPartialToolCall(partialToolCall, context);
      }

      @Override
      public void onCompleteToolCall(CompleteToolCall completeToolCall) {
        handler.onCompleteToolCall(completeToolCall);
      }

      @Override
      public void onCompleteResponse(ChatResponse completeResponse) {
        handler.onCompleteResponse(completeResponse);
      }

      @Override
      public void onError(Throwable error) {
        if (tokensStarted.get()) {
          handler.onError(error);
          return;
        }
        if (rateLimitEnabled
            && rateLimitClassifier.isRateLimit(error)
            && rateLimitPolicy.shouldRetry(rateLimitAttempt, rateLimitMaxAttempts)) {
          int waitSeconds =
              rateLimitPolicy.resolveWaitSeconds(
                  rateLimitClassifier.extractWait(error), rateLimitAttempt);
          onBackoff.accept(new RateLimitAwareChatModel.BackoffEvent(rateLimitAttempt + 1, waitSeconds));
          sleeper.sleepSeconds(waitSeconds);
          chatWithRetry(chatRequest, handler, rateLimitAttempt + 1, transientAttempt);
          return;
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
          chatWithRetry(chatRequest, handler, rateLimitAttempt, transientAttempt + 1);
          return;
        }
        handler.onError(error);
      }
    };
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
