package org.qubership.integration.platform.ai.llm.ratelimit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.exception.RateLimitException;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RateLimitAwareStreamingChatModelTest {

  private RateLimitErrorClassifier classifier;
  private RateLimitWaitPolicy policy;
  private ChatRequest request;
  private ChatResponse successResponse;
  private List<RateLimitAwareChatModel.BackoffEvent> events;

  @BeforeEach
  void setUp() {
    classifier = new RateLimitErrorClassifier();
    policy = new RateLimitWaitPolicy();
    request = ChatRequest.builder().messages(List.of(UserMessage.from("hi"))).build();
    successResponse = ChatResponse.builder().aiMessage(AiMessage.from("ok")).build();
    events = new ArrayList<>();
  }

  @Test
  void retriesWhenErrorBeforeAnyToken() {
    FakeStreamingChatModel delegate = new FakeStreamingChatModel();
    RateLimitAwareChatModelTest.RecordingSleeper sleeper = new RateLimitAwareChatModelTest.RecordingSleeper();
    StreamingChatModel model =
        new RateLimitAwareStreamingChatModel(delegate, classifier, policy, sleeper, true, 3, events::add);

    CapturingHandler outer = new CapturingHandler();
    model.chat(request, outer);

    assertEquals(1, delegate.handlers.size());
    RateLimitException rateLimit =
        new RateLimitException("Please try again in 812ms. rate_limit_exceeded");
    delegate.handlers.get(0).onError(rateLimit);

    assertEquals(List.of(1), sleeper.sleptSeconds);
    assertEquals(1, events.size());
    assertEquals(2, delegate.handlers.size());

    delegate.handlers.get(1).onCompleteResponse(successResponse);

    assertSame(successResponse, outer.completeResponse);
    assertNull(outer.error);
    assertTrue(outer.partialResponses.isEmpty());
  }

  @Test
  void doesNotRetryAfterPartialToken() {
    FakeStreamingChatModel delegate = new FakeStreamingChatModel();
    RateLimitAwareChatModelTest.RecordingSleeper sleeper = new RateLimitAwareChatModelTest.RecordingSleeper();
    StreamingChatModel model =
        new RateLimitAwareStreamingChatModel(delegate, classifier, policy, sleeper, true, 3, events::add);

    CapturingHandler outer = new CapturingHandler();
    model.chat(request, outer);

    delegate.handlers.get(0).onPartialResponse("Hi");
    RateLimitException rateLimit = new RateLimitException("rate_limit_exceeded");
    delegate.handlers.get(0).onError(rateLimit);

    assertTrue(sleeper.sleptSeconds.isEmpty());
    assertTrue(events.isEmpty());
    assertEquals(1, delegate.chatCallCount);
    assertSame(rateLimit, outer.error);
    assertEquals(List.of("Hi"), outer.partialResponses);
    assertNull(outer.completeResponse);
  }

  static final class FakeStreamingChatModel implements StreamingChatModel {

    final List<StreamingChatResponseHandler> handlers = new ArrayList<>();
    int chatCallCount;

    @Override
    public void chat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
      chatCallCount++;
      handlers.add(handler);
    }
  }

  static final class CapturingHandler implements StreamingChatResponseHandler {

    final List<String> partialResponses = new ArrayList<>();
    ChatResponse completeResponse;
    Throwable error;

    @Override
    public void onPartialResponse(String partialResponse) {
      partialResponses.add(partialResponse);
    }

    @Override
    public void onCompleteResponse(ChatResponse completeResponse) {
      this.completeResponse = completeResponse;
    }

    @Override
    public void onError(Throwable error) {
      this.error = error;
    }
  }
}
