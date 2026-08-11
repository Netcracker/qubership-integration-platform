package org.qubership.integration.platform.ai.llm.ratelimit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.exception.RateLimitException;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RateLimitAwareChatModelTest {

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
  void retriesOnceThenSucceeds() {
    ChatModel delegate = mock(ChatModel.class);
    when(delegate.chat(any(ChatRequest.class)))
        .thenThrow(new RateLimitException("Please try again in 812ms. rate_limit_exceeded"))
        .thenReturn(successResponse);

    RecordingSleeper sleeper = new RecordingSleeper();
    ChatModel model = new RateLimitAwareChatModel(delegate, classifier, policy, sleeper, true, 3, events::add);

    ChatResponse out = model.chat(request);
    assertSame(successResponse, out);
    assertEquals(List.of(1), sleeper.sleptSeconds);
    assertEquals(1, events.size());
  }

  @Test
  void nonRateLimitDoesNotRetry() {
    ChatModel delegate = mock(ChatModel.class);
    when(delegate.chat(any(ChatRequest.class))).thenThrow(new IllegalStateException("nope"));
    RecordingSleeper sleeper = new RecordingSleeper();
    ChatModel model = new RateLimitAwareChatModel(delegate, classifier, policy, sleeper, true, 3, events::add);

    assertThrows(IllegalStateException.class, () -> model.chat(request));
    assertTrue(sleeper.sleptSeconds.isEmpty());
  }

  @Test
  void givesUpAfterMaxAttempts() {
    ChatModel delegate = mock(ChatModel.class);
    when(delegate.chat(any(ChatRequest.class))).thenThrow(new RateLimitException("rate_limit_exceeded"));
    RecordingSleeper sleeper = new RecordingSleeper();
    ChatModel model = new RateLimitAwareChatModel(delegate, classifier, policy, sleeper, true, 3, events::add);

    assertThrows(RateLimitException.class, () -> model.chat(request));
    assertEquals(2, sleeper.sleptSeconds.size());
    assertEquals(List.of(1, 2), sleeper.sleptSeconds);
  }

  @Test
  void turnBackoffBudgetExhaustionSurfacesWithoutFurtherSleep() {
    ChatModel delegate = mock(ChatModel.class);
    when(delegate.chat(any(ChatRequest.class)))
        .thenThrow(new RateLimitException("rate_limit_exceeded Please try again in 1s."));
    RecordingSleeper sleeper = new RecordingSleeper();
    ChatModel model =
        new RateLimitAwareChatModel(
            delegate,
            classifier,
            policy,
            sleeper,
            true,
            5,
            event -> {
              events.add(event);
              if (events.size() >= 2) {
                throw new RateLimitTurnBudgetExhaustedException(2);
              }
            });

    assertThrows(RateLimitTurnBudgetExhaustedException.class, () -> model.chat(request));
    assertEquals(1, sleeper.sleptSeconds.size());
    assertEquals(2, events.size());
  }

  static final class RecordingSleeper implements RateLimitBackoffSleeper {
    final List<Integer> sleptSeconds = new ArrayList<>();

    @Override
    public void sleepSeconds(int seconds) {
      sleptSeconds.add(seconds);
    }
  }
}
