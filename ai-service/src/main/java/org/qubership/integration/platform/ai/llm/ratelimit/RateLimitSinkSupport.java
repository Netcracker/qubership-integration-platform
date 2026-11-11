package org.qubership.integration.platform.ai.llm.ratelimit;

import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.CompleteToolCall;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.chat.response.PartialResponseContext;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.PartialThinkingContext;
import dev.langchain4j.model.chat.response.PartialToolCall;
import dev.langchain4j.model.chat.response.PartialToolCallContext;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.qubership.integration.platform.ai.chat.activity.LlmRateLimitBackoffSink;

/** Shared sink bind/complete helpers for rate-limit ChatModel wrappers and unit tests. */
final class RateLimitSinkSupport {

  private RateLimitSinkSupport() {}

  static Consumer<RateLimitAwareChatModel.BackoffEvent> backoffNotifier(
      AtomicBoolean backoffInCall) {
    return event -> {
      backoffInCall.set(true);
      LlmRateLimitBackoffSink.onBackoff(event.attempt(), event.waitSeconds());
    };
  }

  static void completeIfBackoffOccurred(AtomicBoolean backoffInCall) {
    if (backoffInCall.get()) {
      LlmRateLimitBackoffSink.onBackoffCompleted();
    }
  }

  static StreamingChatResponseHandler completionAwareHandler(
      StreamingChatResponseHandler handler, AtomicBoolean backoffInCall) {
    return new StreamingChatResponseHandler() {

      @Override
      public void onPartialResponse(String partialResponse) {
        handler.onPartialResponse(partialResponse);
      }

      @Override
      public void onPartialResponse(PartialResponse partialResponse, PartialResponseContext context) {
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
        handler.onPartialToolCall(partialToolCall);
      }

      @Override
      public void onPartialToolCall(PartialToolCall partialToolCall, PartialToolCallContext context) {
        handler.onPartialToolCall(partialToolCall, context);
      }

      @Override
      public void onCompleteToolCall(CompleteToolCall completeToolCall) {
        handler.onCompleteToolCall(completeToolCall);
      }

      @Override
      public void onCompleteResponse(ChatResponse completeResponse) {
        completeIfBackoffOccurred(backoffInCall);
        handler.onCompleteResponse(completeResponse);
      }

      @Override
      public void onError(Throwable error) {
        handler.onError(error);
      }
    };
  }
}
