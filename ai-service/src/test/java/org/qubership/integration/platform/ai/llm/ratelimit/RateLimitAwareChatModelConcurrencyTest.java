package org.qubership.integration.platform.ai.llm.ratelimit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.exception.RateLimitException;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.smallrye.mutiny.Context;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chat.ChatEvent;
import org.qubership.integration.platform.ai.chat.activity.LlmRateLimitBackoffSink;

/**
 * Sink-completion concurrency coverage for the CDI chat path (RateLimitAwareChatModel + {@link
 * RateLimitSinkSupport}), matching {@link RateLimitChatModel#chat}.
 */
class RateLimitAwareChatModelConcurrencyTest {

  private static final String BACKOFF_THREAD = "with-backoff";

  private final List<ChatEvent> events = Collections.synchronizedList(new ArrayList<>());
  private final RateLimitErrorClassifier classifier = new RateLimitErrorClassifier();
  private final RateLimitWaitPolicy policy = new RateLimitWaitPolicy();

  @BeforeEach
  void setUp() {
    LlmRateLimitBackoffSink.bind(events::add);
  }

  @AfterEach
  void tearDown() {
    LlmRateLimitBackoffSink.unbind();
  }

  @Test
  void emitsBackoffCompletedAfterRetry() {
    ChatRequest request =
        ChatRequest.builder().messages(List.of(UserMessage.from("hi"))).build();
    ChatResponse successResponse = ChatResponse.builder().aiMessage(AiMessage.from("ok")).build();

    ChatModel delegate = mock(ChatModel.class);
    when(delegate.chat(any(ChatRequest.class)))
        .thenThrow(new RateLimitException("rate_limit_exceeded"))
        .thenReturn(successResponse);

    chatWithSink(delegate, seconds -> {}, request);

    long completed = completedBackoffSteps();
    assertEquals(1, completed);
  }

  @Test
  void concurrentCallsTrackBackoffCompletionIndependently() throws Exception {
    ChatRequest request =
        ChatRequest.builder().messages(List.of(UserMessage.from("hi"))).build();
    ChatResponse successResponse = ChatResponse.builder().aiMessage(AiMessage.from("ok")).build();
    AtomicBoolean firstBackoffAttempt = new AtomicBoolean(true);
    CountDownLatch backoffSleepStarted = new CountDownLatch(1);
    CountDownLatch allowBackoffRetry = new CountDownLatch(1);

    ChatModel delegate = mock(ChatModel.class);
    when(delegate.chat(any(ChatRequest.class)))
        .thenAnswer(
            invocation -> {
              if (BACKOFF_THREAD.equals(Thread.currentThread().getName())
                  && firstBackoffAttempt.compareAndSet(true, false)) {
                throw new RateLimitException("rate_limit_exceeded");
              }
              return successResponse;
            });

    RateLimitBackoffSleeper sleeper =
        seconds -> {
          if (BACKOFF_THREAD.equals(Thread.currentThread().getName())) {
            backoffSleepStarted.countDown();
            try {
              if (!allowBackoffRetry.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out waiting to retry");
              }
            } catch (InterruptedException interrupted) {
              Thread.currentThread().interrupt();
              throw new IllegalStateException("interrupted waiting to retry", interrupted);
            }
          }
        };

    Context boundContext = LlmRateLimitBackoffSink.attachedContext();

    Thread backoffThread =
        new Thread(
            () ->
                LlmRateLimitBackoffSink.executeInBoundContext(
                    boundContext, () -> chatWithSink(delegate, sleeper, request)),
            BACKOFF_THREAD);
    backoffThread.start();

    backoffSleepStarted.await(5, TimeUnit.SECONDS);
    chatWithSink(delegate, sleeper, request);
    allowBackoffRetry.countDown();
    backoffThread.join(5_000);

    long completed = completedBackoffSteps();
    assertEquals(1, completed, "only the retried call should emit backoff completed");
  }

  /** Mirrors {@link RateLimitChatModel#chat} sink bind/complete behavior. */
  private void chatWithSink(
      ChatModel delegate, RateLimitBackoffSleeper sleeper, ChatRequest request) {
    AtomicBoolean backoffInCall = new AtomicBoolean(false);
    Consumer<RateLimitAwareChatModel.BackoffEvent> onBackoff =
        RateLimitSinkSupport.backoffNotifier(backoffInCall);
    RateLimitAwareChatModel rateLimited =
        new RateLimitAwareChatModel(delegate, classifier, policy, sleeper, true, 3, onBackoff);
    rateLimited.chat(request);
    RateLimitSinkSupport.completeIfBackoffOccurred(backoffInCall);
  }

  private long completedBackoffSteps() {
    return events.stream()
        .filter(ChatEvent.Step.class::isInstance)
        .map(ChatEvent.Step.class::cast)
        .filter(step -> "completed".equals(step.status()))
        .count();
  }
}
