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

public final class RateLimitAwareStreamingChatModel implements StreamingChatModel {

  private final StreamingChatModel delegate;
  private final RateLimitErrorClassifier classifier;
  private final RateLimitWaitPolicy policy;
  private final RateLimitBackoffSleeper sleeper;
  private final boolean enabled;
  private final int maxAttempts;
  private final Consumer<RateLimitAwareChatModel.BackoffEvent> onBackoff;

  public RateLimitAwareStreamingChatModel(
      StreamingChatModel delegate,
      RateLimitErrorClassifier classifier,
      RateLimitWaitPolicy policy,
      RateLimitBackoffSleeper sleeper,
      boolean enabled,
      int maxAttempts,
      Consumer<RateLimitAwareChatModel.BackoffEvent> onBackoff) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
    this.classifier = Objects.requireNonNull(classifier, "classifier");
    this.policy = Objects.requireNonNull(policy, "policy");
    this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
    this.enabled = enabled;
    this.maxAttempts = maxAttempts;
    this.onBackoff = Objects.requireNonNull(onBackoff, "onBackoff");
  }

  @Override
  public void chat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
    if (!enabled) {
      delegate.chat(chatRequest, handler);
      return;
    }
    chatWithRetry(chatRequest, handler, 0);
  }

  private void chatWithRetry(ChatRequest chatRequest, StreamingChatResponseHandler handler, int attempt) {
    AtomicBoolean tokensStarted = new AtomicBoolean(false);
    delegate.chat(chatRequest, observingHandler(handler, chatRequest, attempt, tokensStarted));
  }

  private StreamingChatResponseHandler observingHandler(
      StreamingChatResponseHandler handler, ChatRequest chatRequest, int attempt, AtomicBoolean tokensStarted) {
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
        if (tokensStarted.get()
            || !classifier.isRateLimit(error)
            || !policy.shouldRetry(attempt, maxAttempts)) {
          handler.onError(error);
          return;
        }
        int waitSeconds = policy.resolveWaitSeconds(classifier.extractWait(error), attempt);
        onBackoff.accept(new RateLimitAwareChatModel.BackoffEvent(attempt + 1, waitSeconds));
        sleeper.sleepSeconds(waitSeconds);
        chatWithRetry(chatRequest, handler, attempt + 1);
      }
    };
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
