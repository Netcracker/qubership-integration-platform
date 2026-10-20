package org.qubership.integration.platform.ai.chat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.subscription.Cancellable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chat.ChatEvent;
import org.qubership.integration.platform.ai.chat.activity.LlmRateLimitBackoffSink;
import org.qubership.integration.platform.ai.chat.activity.ToolInvocationSink;
import org.qubership.integration.platform.ai.llm.ratelimit.RateLimitTurnBudgetExhaustedException;

class ChatExecutionServiceBackoffSinkTest {
  @Test
  void explicitTurnContextDoesNotDependOnAmbientConversationFromAnotherTurn() {
    List<ChatEvent> events = new ArrayList<>();
    org.qubership.integration.platform.ai.chat.ToolSession.bind("previous-turn");
    Multi<ChatEvent> routed = Multi.createFrom().emitter(emitter -> {
      org.qubership.integration.platform.ai.chat.ToolSession.bind("current-turn");
      ToolInvocationSink.catalogChanges().orElseThrow().chainChanged("a");
      org.qubership.integration.platform.ai.chat.ToolSession.clear();
      emitter.complete();
    });
    try {
      ChatExecutionService.bindBackoffSinkForTurn(routed, new AtomicReference<>(), 2, "current-turn")
          .subscribe().with(events::add);
      assertEquals(List.of(new ChatEvent.CatalogChanged(List.of("a"), true)), events);
    } finally {
      org.qubership.integration.platform.ai.chat.ToolSession.clear();
    }
  }

  @Test
  void partialWritesAreDeliveredBeforeTheErrorAndDrainedOnlyOnce() {
    List<ChatEvent> events = new ArrayList<>();
    Multi<ChatEvent> routed = Multi.createFrom().emitter(emitter -> {
      ToolInvocationSink.catalogChanges().orElseThrow().chainChanged("a");
      emitter.emit(ChatEvent.error("write failed after a saved change"));
      emitter.complete();
    });
    ChatExecutionService.bindBackoffSinkForTurn(routed, new AtomicReference<>())
        .subscribe().with(events::add);
    assertEquals(2, events.size());
    assertEquals(new ChatEvent.CatalogChanged(List.of("a"), true), events.getFirst());
    assertInstanceOf(ChatEvent.Error.class, events.getLast());
    assertTrue(ToolInvocationSink.catalogChanges().isEmpty());
  }

  @AfterEach
  void tearDown() {
    LlmRateLimitBackoffSink.unbind();
    ToolInvocationSink.unbind();
  }

  @Test
  void bindsBackoffSinkForRoutedEvents() {
    List<ChatEvent> out = new ArrayList<>();
    AtomicReference<Cancellable> cancellation = new AtomicReference<>();

    ChatExecutionService.bindBackoffSinkForTurn(
            Multi.createFrom().items(ChatEvent.token("hello"), ChatEvent.token("world")),
            cancellation)
        .subscribe()
        .with(out::add);

    assertEquals(2, out.size());
    assertInstanceOf(ChatEvent.Token.class, out.get(0));
    assertInstanceOf(ChatEvent.Token.class, out.get(1));
  }

  @Test
  void emitsBackoffStepsWhileTurnIsBound() {
    List<ChatEvent> out = new ArrayList<>();
    AtomicReference<Cancellable> cancellation = new AtomicReference<>();

    ChatExecutionService.bindBackoffSinkForTurn(
            Multi.createFrom()
                .emitter(
                    emitter -> {
                      LlmRateLimitBackoffSink.onBackoff(1, 4);
                      emitter.emit(ChatEvent.token("after-backoff"));
                      emitter.complete();
                    }),
            cancellation)
        .subscribe()
        .with(out::add);

    assertEquals(2, out.size());
    ChatEvent.Step step = assertInstanceOf(ChatEvent.Step.class, out.get(0));
    assertEquals("llm:rate-limit-backoff", step.id());
    assertEquals("Taking another pass", step.label());
    assertInstanceOf(ChatEvent.Token.class, out.get(1));
  }

  @Test
  void emitsBackoffWithParentSkillIdDuringTurn() {
    List<ChatEvent> out = new ArrayList<>();
    AtomicReference<Cancellable> cancellation = new AtomicReference<>();

    ChatExecutionService.bindBackoffSinkForTurn(
            Multi.createFrom()
                .emitter(
                    emitter -> {
                      LlmRateLimitBackoffSink.setParentSkillId("skill:cip-x");
                      LlmRateLimitBackoffSink.onBackoff(1, 3);
                      LlmRateLimitBackoffSink.clearParentSkillId();
                      emitter.complete();
                    }),
            cancellation)
        .subscribe()
        .with(out::add);

    assertEquals(1, out.size());
    ChatEvent.Step step = assertInstanceOf(ChatEvent.Step.class, out.get(0));
    assertEquals("skill:cip-x", step.parentId());
  }

  @Test
  void unbindsBackoffSinkOnRoutedFailure() {
    AtomicReference<Cancellable> cancellation = new AtomicReference<>();
    List<ChatEvent> out = new ArrayList<>();

    ChatExecutionService.bindBackoffSinkForTurn(
            Multi.createFrom().failure(new IllegalStateException("boom")), cancellation)
        .subscribe()
        .with(out::add, err -> {});

    assertTrue(out.isEmpty());
    LlmRateLimitBackoffSink.onBackoff(1, 1);
    assertTrue(out.isEmpty());
  }

  @Test
  void unbindsBackoffSinkOnCancellation() {
    AtomicReference<Cancellable> cancellation = new AtomicReference<>();
    List<ChatEvent> out = new ArrayList<>();

    Cancellable subscription =
        ChatExecutionService.bindBackoffSinkForTurn(
                Multi.createFrom().emitter(emitter -> {}), cancellation)
            .subscribe()
            .with(out::add);

    subscription.cancel();
    LlmRateLimitBackoffSink.onBackoff(1, 1);
    assertTrue(out.isEmpty());
  }

  @Test
  void emitsToolStepsWhileTurnIsBound() {
    List<ChatEvent> out = new ArrayList<>();
    AtomicReference<Cancellable> cancellation = new AtomicReference<>();

    ChatExecutionService.bindBackoffSinkForTurn(
            Multi.createFrom()
                .emitter(
                    emitter -> {
                      ToolInvocationSink.onInvoke("searchApiHub");
                      ToolInvocationSink.onComplete("searchApiHub");
                      emitter.emit(ChatEvent.token("after-tools"));
                      emitter.complete();
                    }),
            cancellation)
        .subscribe()
        .with(out::add);

    assertEquals(3, out.size());
    ChatEvent.Step running = assertInstanceOf(ChatEvent.Step.class, out.get(0));
    assertEquals("tool", running.kind());
    assertEquals("tool:searchApiHub", running.id());
    assertEquals("running", running.status());
    ChatEvent.Step completed = assertInstanceOf(ChatEvent.Step.class, out.get(1));
    assertEquals("completed", completed.status());
    assertInstanceOf(ChatEvent.Token.class, out.get(2));
  }

  @Test
  void unbindsToolSinkOnRoutedFailure() {
    AtomicReference<Cancellable> cancellation = new AtomicReference<>();
    List<ChatEvent> out = new ArrayList<>();

    ChatExecutionService.bindBackoffSinkForTurn(
            Multi.createFrom().failure(new IllegalStateException("boom")), cancellation)
        .subscribe()
        .with(out::add, err -> {});

    assertTrue(out.isEmpty());
    ToolInvocationSink.onInvoke("searchApiHub");
    assertTrue(out.isEmpty());
  }

  @Test
  void failsTurnWhenBackoffBudgetExhaustedDuringRoutedWork() {
    AtomicReference<Cancellable> cancellation = new AtomicReference<>();
    AtomicReference<Throwable> failure = new AtomicReference<>();

    ChatExecutionService.bindBackoffSinkForTurn(
            Multi.createFrom()
                .emitter(
                    emitter -> {
                      LlmRateLimitBackoffSink.onBackoff(1, 1);
                      LlmRateLimitBackoffSink.onBackoff(2, 1);
                      LlmRateLimitBackoffSink.onBackoff(3, 1);
                      emitter.complete();
                    }),
            cancellation,
            2)
        .subscribe()
        .with(ignored -> {}, failure::set);

    assertInstanceOf(RateLimitTurnBudgetExhaustedException.class, failure.get());
  }
}
