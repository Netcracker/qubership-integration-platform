package org.qubership.integration.platform.ai.chat.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.smallrye.mutiny.Context;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chat.ChatEvent;
import org.qubership.integration.platform.ai.llm.ratelimit.RateLimitTurnBudgetExhaustedException;

class LlmRateLimitBackoffSinkTest {

  @AfterEach
  void tearDown() {
    LlmRateLimitBackoffSink.unbind();
  }

  @Test
  void emitsBackoffStepWhenBound() {
    List<ChatEvent> out = new ArrayList<>();
    LlmRateLimitBackoffSink.bind(out::add);
    try {
      LlmRateLimitBackoffSink.onBackoff(1, 4);
    } finally {
      LlmRateLimitBackoffSink.unbind();
    }

    ChatEvent.Step step = assertInstanceOf(ChatEvent.Step.class, out.get(0));
    assertEquals("llm:rate-limit-backoff", step.id());
    assertEquals("llm", step.kind());
    assertEquals("running", step.status());
    assertEquals("rate-limit backoff 4s", step.label());
  }

  @Test
  void emitsCompletedBackoffStep() {
    List<ChatEvent> out = new ArrayList<>();
    LlmRateLimitBackoffSink.bind(out::add);
    try {
      LlmRateLimitBackoffSink.onBackoff(1, 2);
      LlmRateLimitBackoffSink.onBackoffCompleted();
    } finally {
      LlmRateLimitBackoffSink.unbind();
    }

    ChatEvent.Step running = assertInstanceOf(ChatEvent.Step.class, out.get(0));
    assertEquals("running", running.status());

    ChatEvent.Step completed = assertInstanceOf(ChatEvent.Step.class, out.get(1));
    assertEquals("llm:rate-limit-backoff", completed.id());
    assertEquals("llm", completed.kind());
    assertEquals("completed", completed.status());
    assertEquals("rate-limit backoff 2s", completed.label());
  }

  @Test
  void usesParentSkillIdFromBind() {
    List<ChatEvent> out = new ArrayList<>();
    LlmRateLimitBackoffSink.bind(out::add, "skill:cip-auth-generator");
    try {
      LlmRateLimitBackoffSink.onBackoff(1, 1);
    } finally {
      LlmRateLimitBackoffSink.unbind();
    }

    ChatEvent.Step step = assertInstanceOf(ChatEvent.Step.class, out.get(0));
    assertEquals("skill:cip-auth-generator", step.parentId());
  }

  @Test
  void setParentSkillIdUpdatesBinding() {
    List<ChatEvent> out = new ArrayList<>();
    LlmRateLimitBackoffSink.bind(out::add);
    try {
      LlmRateLimitBackoffSink.setParentSkillId("skill:cip-x");
      LlmRateLimitBackoffSink.onBackoff(1, 3);
      LlmRateLimitBackoffSink.clearParentSkillId();
      LlmRateLimitBackoffSink.onBackoff(2, 1);
    } finally {
      LlmRateLimitBackoffSink.unbind();
    }

    ChatEvent.Step withParent = assertInstanceOf(ChatEvent.Step.class, out.get(0));
    assertEquals("skill:cip-x", withParent.parentId());

    ChatEvent.Step withoutParent = assertInstanceOf(ChatEvent.Step.class, out.get(1));
    assertEquals(null, withoutParent.parentId());
  }

  @Test
  void noOpWhenUnbound() {
    List<ChatEvent> out = new ArrayList<>();
    LlmRateLimitBackoffSink.onBackoff(1, 4);
    LlmRateLimitBackoffSink.onBackoffCompleted();
    assertTrue(out.isEmpty());
  }

  @Test
  void propagatesBindingThroughMutinyContextOnWorkerThread() {
    List<ChatEvent> out = new ArrayList<>();
    LlmRateLimitBackoffSink.bind(out::add, "skill:worker-skill");
    Context context = LlmRateLimitBackoffSink.attachedContext();
    try {
      Uni.createFrom()
          .voidItem()
          .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
          .onItem()
          .invoke(
              () ->
                  LlmRateLimitBackoffSink.executeInBoundContext(
                      context,
                      () -> {
                        LlmRateLimitBackoffSink.onBackoff(1, 5);
                        LlmRateLimitBackoffSink.onBackoffCompleted();
                      }))
          .awaitUsing(context)
          .indefinitely();
    } finally {
      LlmRateLimitBackoffSink.unbind();
    }

    assertEquals(2, out.size());
    ChatEvent.Step running = assertInstanceOf(ChatEvent.Step.class, out.get(0));
    assertEquals("skill:worker-skill", running.parentId());
    assertEquals("rate-limit backoff 5s", running.label());

    ChatEvent.Step completed = assertInstanceOf(ChatEvent.Step.class, out.get(1));
    assertEquals("completed", completed.status());
    assertEquals("rate-limit backoff 5s", completed.label());
  }

  @Test
  void resolvesBindingFromSubscribePathContextOnWorkerThread() {
    List<ChatEvent> out = new ArrayList<>();
    LlmRateLimitBackoffSink.bind(out::add, "skill:worker-skill");
    Context context = LlmRateLimitBackoffSink.attachedContext();
    try {
      LlmRateLimitBackoffSink.propagateBinding(
              context,
              Uni.createFrom()
                  .voidItem()
                  .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                  .onItem()
                  .invoke(
                      () -> {
                        LlmRateLimitBackoffSink.onBackoff(1, 7);
                        LlmRateLimitBackoffSink.onBackoffCompleted();
                      }))
          .awaitUsing(context)
          .indefinitely();
    } finally {
      LlmRateLimitBackoffSink.unbind();
    }

    assertEquals(2, out.size());
    ChatEvent.Step running = assertInstanceOf(ChatEvent.Step.class, out.get(0));
    assertEquals("skill:worker-skill", running.parentId());
    assertEquals("rate-limit backoff 7s", running.label());

    ChatEvent.Step completed = assertInstanceOf(ChatEvent.Step.class, out.get(1));
    assertEquals("completed", completed.status());
    assertEquals("rate-limit backoff 7s", completed.label());
  }

  @Test
  void failsClosedWhenTurnBackoffBudgetExhausted() {
    List<ChatEvent> out = new ArrayList<>();
    LlmRateLimitBackoffSink.bind(out::add, null, 2);
    try {
      LlmRateLimitBackoffSink.onBackoff(1, 1);
      LlmRateLimitBackoffSink.onBackoffCompleted();
      LlmRateLimitBackoffSink.onBackoff(2, 2);
      LlmRateLimitBackoffSink.onBackoffCompleted();
      assertThrows(
          RateLimitTurnBudgetExhaustedException.class,
          () -> LlmRateLimitBackoffSink.onBackoff(3, 1));
    } finally {
      LlmRateLimitBackoffSink.unbind();
    }
    assertEquals(4, out.size());
  }
}
