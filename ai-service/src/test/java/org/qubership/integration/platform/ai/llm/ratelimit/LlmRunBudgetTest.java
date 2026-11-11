package org.qubership.integration.platform.ai.llm.ratelimit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.langchain4j.model.chat.request.ChatRequestParameters;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.DefaultChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.output.TokenUsage;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.qubership.integration.platform.ai.configuration.AppConfig;

class LlmRunBudgetTest {
  private final AppConfig config = mock(AppConfig.class, RETURNS_DEEP_STUBS);
  private final InMemoryArtifactBlobStore blobs = new InMemoryArtifactBlobStore();
  private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

  private LlmRunBudget budget() {
    when(config.llm().maxRequestsPerRun()).thenReturn(2);
    when(config.llm().maxTokensPerRun()).thenReturn(100_000L);
    when(config.llm().transientRetry().backoffSeconds()).thenReturn("2,5,10");
    return new LlmRunBudget(blobs, mapper, config.llm(), Clock.systemUTC());
  }

  @Test
  void admissionAtTheExactCeilingDoesNotReserveAnotherRequest() {
    var budget = budget();
    budget.complete(budget.reserve("run", 1, 1), new TokenUsage(1, 1));
    budget.complete(budget.reserve("run", 1, 1), new TokenUsage(1, 1));
    assertNull(budget.state("run").refusal());
    assertNotNull(budget.refusalForNextRequest("run"));
    assertEquals(2, budget.state("run").requests());
    assertEquals(4, budget.state("run").tokens());
    assertNotNull(budget.refusalForNextRequest("run"));
    budget.grant("run", "more");
    assertNull(budget.refusalForNextRequest("run"));
    assertEquals(2, budget.state("run").requests());
  }

  @Test
  void captureAliasesUseTheirOwnRunEvenOnAWorkerWithAnotherBinding() throws Exception {
    var budget = budget();
    String alias = "design-alias";
    org.qubership.integration.platform.ai.productpipeline.create.ProductCapabilityCaptureContext
        .bindDesign("design-run", alias, null, value -> {});
    try {
      org.qubership.integration.platform.ai.chat.ToolSession.bind(alias);
      assertEquals("design-run", budget.currentRun());
      var result = new java.util.concurrent.atomic.AtomicReference<String>();
      Thread worker = Thread.ofVirtual().start(() -> {
        org.qubership.integration.platform.ai.productpipeline.create.ProductCapabilityCaptureContext
            .bindDiscovery("other-run", "other-alias", value -> {});
        org.qubership.integration.platform.ai.chat.ToolSession.bind(alias);
        try { result.set(budget.currentRun()); }
        finally {
          org.qubership.integration.platform.ai.chat.ToolSession.clear();
          org.qubership.integration.platform.ai.productpipeline.create.ProductCapabilityCaptureContext.unbind();
        }
      });
      worker.join(2000);
      assertFalse(worker.isAlive());
      assertEquals("design-run", result.get());
      budget.linkConversation("compiler-run", "compiler-workspace");
      org.qubership.integration.platform.ai.chat.ToolSession.bind("compiler-workspace");
      assertEquals("compiler-run", budget.currentRun());
      assertEquals("narrative-run", LlmRunBudget.withRun("narrative-run", budget::currentRun));
      assertEquals("compiler-run", budget.currentRun());
    } finally {
      org.qubership.integration.platform.ai.chat.ToolSession.clear();
      org.qubership.integration.platform.ai.productpipeline.create.ProductCapabilityCaptureContext.unbind(alias);
    }
    assertNull(budget.currentRun());
  }

  @Test
  void reservationsAndCorruptAccountingNeverPermitAnUnpaidDispatch() {
    var budget = budget();
    when(config.llm().maxTokensPerRun()).thenReturn(100L);
    var ticket = budget.reserve("tokens", 30, 50);
    budget.complete(ticket, null);
    assertEquals(80, budget.state("tokens").tokens());
    assertThrows(LlmRunBudget.Exhausted.class, () -> budget.reserve("tokens", 10, 20));
    assertEquals(1, budget.state("tokens").requests());
    budget.grant("tokens", "grant-1");
    var next = budget.reserve("tokens", 10, 20);
    budget.complete(next, new TokenUsage(10, 5));
    budget.complete(next, new TokenUsage(10, 5));
    assertEquals(95, budget.state("tokens").tokens());
    String key = "product-pipeline-llm-budget/" + LlmRequestBudget.hash("corrupt") + ".json";
    blobs.put(key, "{\"requests\":-1}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    assertThrows(IllegalStateException.class, () -> budget.reserve("corrupt", 1, 1));
  }

  @Test
  void streamingDeadlineRejectsLateResponsesAndKeepsUnknownUsage() throws Exception {
    var budget = budget();
    when(config.llm().maxActiveTimePerRun()).thenReturn(java.time.Duration.ofMillis(150));
    StreamingChatModel upstream = mock(StreamingChatModel.class);
    when(upstream.defaultRequestParameters()).thenReturn(DefaultChatRequestParameters.builder().maxOutputTokens(100).build());
    var observer = new java.util.concurrent.atomic.AtomicReference<StreamingChatResponseHandler>();
    doAnswer(call -> { observer.set(call.getArgument(1)); return null; })
        .when(upstream).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
    var terminal = new java.util.concurrent.CompletableFuture<Throwable>();
    var completions = new java.util.concurrent.atomic.AtomicInteger();
    var partials = new java.util.concurrent.atomic.AtomicInteger();
    var outer = new StreamingChatResponseHandler() {
      public void onPartialResponse(String text) { partials.incrementAndGet(); }
      public void onCompleteResponse(ChatResponse response) { completions.incrementAndGet(); }
      public void onError(Throwable error) { terminal.complete(error); }
    };
    var wrapper = new RateLimitStreamingChatModel(upstream, config, seconds -> {},
        new RateLimitErrorClassifier(), new RateLimitWaitPolicy()).withRunBudget(budget);
    LlmRunBudget.withRun("stream", () -> {
      wrapper.chat(ChatRequest.builder().messages(UserMessage.from("source")).build(), outer); return null;
    });
    assertInstanceOf(LlmRunBudget.Exhausted.class, terminal.get(2, java.util.concurrent.TimeUnit.SECONDS));
    assertEquals(1, budget.state("stream").active().size(), "The provider has not confirmed termination");
    observer.get().onPartialResponse("late");
    observer.get().onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from("late"))
        .tokenUsage(new TokenUsage(1, 1)).build());
    assertEquals(0, completions.get());
    assertEquals(0, partials.get());
    assertTrue(budget.state("stream").tokens() >= 100);
    assertTrue(budget.state("stream").active().isEmpty());
    verify(upstream).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
  }

  @Test
  void aDiagnosisTimeoutKeepsAnIgnoringTransportAccountedUntilItActuallyReturns() throws Exception {
    var budget = budget();
    var release = new java.util.concurrent.CountDownLatch(1);
    var agentReturned = new java.util.concurrent.CountDownLatch(1);
    ChatModel upstream = mock(ChatModel.class);
    when(upstream.defaultRequestParameters()).thenReturn(DefaultChatRequestParameters.builder().maxOutputTokens(100).build());
    when(upstream.chat(any(ChatRequest.class))).thenAnswer(call -> {
      while (release.getCount() > 0) {
        try { release.await(); } catch (InterruptedException ignored) { }
      }
      return ChatResponse.builder().aiMessage(AiMessage.from("late diagnosis"))
          .tokenUsage(new TokenUsage(3, 2)).build();
    });
    var wrapper = new RateLimitChatModel(upstream, config, seconds -> {},
        new RateLimitErrorClassifier(), new RateLimitWaitPolicy()).withRunBudget(budget);
    var agent = mock(org.qubership.integration.platform.ai.llm.agent.FailureNarrativeAgent.class);
    when(agent.recover(anyString(), anyString(),
        org.mockito.ArgumentMatchers.any(ChatRequestParameters.class))).thenAnswer(call -> {
      try { wrapper.chat(ChatRequest.builder().messages(UserMessage.from("diagnose")).build()); return null; }
      finally { agentReturned.countDown(); }
    });
    var evidence = new org.qubership.integration.platform.ai.productpipeline.recovery.RecoveryEvidence(
        1, "late-failure", "VALIDATION_BLOCKER", "design-execution", null, null,
        java.util.List.of(), java.util.List.of(), null, java.util.List.of());
    var context = new org.qubership.integration.platform.ai.productpipeline.recovery.RecoveryContext(evidence, null, null, "en");
    try {
      var result = new org.qubership.integration.platform.ai.productpipeline.create.FailureNarrative(
          agent, 2, java.time.Duration.ofMillis(500)).diagnoseRecovery("late", context);
      assertEquals(org.qubership.integration.platform.ai.productpipeline.create.FailureNarrative.RecoveryStatus.TIMED_OUT,
          result.status());
      assertTrue(agentReturned.await(1, java.util.concurrent.TimeUnit.SECONDS));
      assertEquals(1, budget.state("late").requests());
      assertEquals(1, budget.state("late").active().size(), "Cancelled transport is still running");
    } finally {
      release.countDown();
      assertTimeoutPreemptively(java.time.Duration.ofSeconds(2), () -> {
        while (!budget.state("late").active().isEmpty()) { Thread.sleep(5); }
      });
    }
    assertEquals(5, budget.state("late").tokens());
  }

  @Test
  void asynchronousToolLoopContinuationsKeepTheSameRunBudget() throws Exception {
    var budget = budget();
    var observers = new java.util.concurrent.CopyOnWriteArrayList<StreamingChatResponseHandler>();
    StreamingChatModel upstream = mock(StreamingChatModel.class);
    when(upstream.defaultRequestParameters()).thenReturn(DefaultChatRequestParameters.builder().maxOutputTokens(100).build());
    doAnswer(call -> { observers.add(call.getArgument(1)); return null; })
        .when(upstream).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
    var wrapper = new RateLimitStreamingChatModel(upstream, config, seconds -> {},
        new RateLimitErrorClassifier(), new RateLimitWaitPolicy()).withRunBudget(budget);
    var request = ChatRequest.builder().messages(UserMessage.from("source")).build();
    var continuation = new StreamingChatResponseHandler() {
      public void onCompleteResponse(ChatResponse response) {
        wrapper.chat(request, mock(StreamingChatResponseHandler.class));
      }
      public void onError(Throwable error) { fail(error); }
    };
    LlmRunBudget.withRun("loop", () -> { wrapper.chat(request, continuation); return null; });
    var finished = new java.util.concurrent.CompletableFuture<Void>();
    Thread.ofVirtual().start(() -> {
      try {
        observers.getFirst().onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from("tool result"))
            .tokenUsage(new TokenUsage(10, 10)).build());
        assertNull(budget.currentRun());
        finished.complete(null);
      } catch (Throwable failure) { finished.completeExceptionally(failure); }
    });
    finished.get(2, java.util.concurrent.TimeUnit.SECONDS);
    assertEquals(2, budget.state("loop").requests());
    observers.getLast().onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from("done")).build());
  }

  @Test
  void wrapperRetriesCannotExceedTheSharedPhysicalRequestAllowance() {
    var budget = budget();
    when(config.llm().transientRetry().enabled()).thenReturn(true);
    when(config.llm().transientRetry().maxAttempts()).thenReturn(5);
    ChatModel upstream = mock(ChatModel.class);
    when(upstream.defaultRequestParameters()).thenReturn(DefaultChatRequestParameters.builder().build());
    when(upstream.chat(any(ChatRequest.class))).thenThrow(new dev.langchain4j.exception.HttpException(503, "Unavailable"));
    var wrapper = new RateLimitChatModel(upstream, config, seconds -> {},
        new RateLimitErrorClassifier(), new RateLimitWaitPolicy()).withRunBudget(budget);
    var request = ChatRequest.builder().messages(UserMessage.from("Preserve the approved brief")).build();
    assertThrows(LlmRunBudget.Exhausted.class, () -> LlmRunBudget.withRun("run", () -> wrapper.chat(request)));
    verify(upstream, times(2)).chat(any(ChatRequest.class));
    assertEquals(2, budget.state("run").requests());
    assertTrue(budget.state("run").tokens() >= 16_384);
  }

  @Test
  void syncAndStreamShareAccountingAcrossReconstructedWrappersAndExplicitGrants() {
    var budget = budget();
    ChatModel sync = mock(ChatModel.class);
    StreamingChatModel stream = mock(StreamingChatModel.class);
    var defaults = DefaultChatRequestParameters.builder().maxOutputTokens(100).build();
    when(sync.defaultRequestParameters()).thenReturn(defaults);
    when(stream.defaultRequestParameters()).thenReturn(defaults);
    var response = ChatResponse.builder().aiMessage(AiMessage.from("accepted"))
        .tokenUsage(new TokenUsage(10, 20)).build();
    when(sync.chat(any(ChatRequest.class))).thenReturn(response);
    doAnswer(call -> { ((StreamingChatResponseHandler) call.getArgument(1)).onCompleteResponse(response); return null; })
        .when(stream).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
    var syncWrapper = new RateLimitChatModel(sync, config, seconds -> {},
        new RateLimitErrorClassifier(), new RateLimitWaitPolicy()).withRunBudget(budget);
    var streamWrapper = new RateLimitStreamingChatModel(stream, config, seconds -> {},
        new RateLimitErrorClassifier(), new RateLimitWaitPolicy()).withRunBudget(budget());
    var request = ChatRequest.builder().messages(UserMessage.from("approved literals")).build();
    LlmRunBudget.withRun("run", () -> syncWrapper.chat(request));
    LlmRunBudget.withRun("run", () -> {
      streamWrapper.chat(request, mock(StreamingChatResponseHandler.class)); return null;
    });
    assertThrows(LlmRunBudget.Exhausted.class, () -> LlmRunBudget.withRun("run", () -> syncWrapper.chat(request)));
    assertEquals(2, budget.state("run").requests());
    assertEquals(60, budget.state("run").tokens());
    budget.grant("run", "author-grant-1");
    budget.grant("run", "author-grant-1");
    LlmRunBudget.withRun("run", () -> syncWrapper.chat(request));
    assertEquals(1, budget.state("run").grants());
    assertEquals(3, budget.state("run").requests());
    verify(sync, times(2)).chat(any(ChatRequest.class));
  }

  @Test
  void activeTimeLimitStopsAnOutstandingSynchronousRequest() {
    var budget = budget();
    when(config.llm().maxActiveTimePerRun()).thenReturn(java.time.Duration.ofMillis(50));
    ChatModel upstream = mock(ChatModel.class);
    when(upstream.defaultRequestParameters()).thenReturn(DefaultChatRequestParameters.builder().build());
    when(upstream.chat(any(ChatRequest.class))).thenAnswer(call -> {
      Thread.sleep(200);
      return ChatResponse.builder().aiMessage(AiMessage.from("late answer")).build();
    });
    var wrapper = new RateLimitChatModel(upstream, config, seconds -> {},
        new RateLimitErrorClassifier(), new RateLimitWaitPolicy()).withRunBudget(budget);
    var request = ChatRequest.builder().messages(UserMessage.from("approved source")).build();
    assertThrows(LlmRunBudget.Exhausted.class, () -> LlmRunBudget.withRun("run", () -> wrapper.chat(request)));
    assertNotNull(budget.state("run").refusal());
    assertEquals(1, budget.state("run").requests());
  }

  @ParameterizedTest
  @CsvSource({"false, false", "false, true", "true, false", "true, true"})
  void completionRejectsExhaustedResourcesBeforePublishingTheResponse(boolean streaming, boolean timeLimit) {
    budget();
    when(config.llm().maxTokensPerRun()).thenReturn(1000L);
    when(config.llm().maxActiveTimePerRun()).thenReturn(java.time.Duration.ofMillis(50));
    var start = java.time.Instant.parse("2026-10-19T17:00:00Z");
    var now = new java.util.concurrent.atomic.AtomicReference<>(start);
    var testClock = mock(Clock.class);
    when(testClock.instant()).thenAnswer(call -> now.get());
    var budget = new LlmRunBudget(blobs, mapper, config.llm(), testClock);
    var defaults = DefaultChatRequestParameters.builder().maxOutputTokens(100).build();
    var request = ChatRequest.builder().messages(UserMessage.from("approved source")).build();
    java.util.function.Supplier<ChatResponse> response = () -> {
      if (timeLimit) { now.set(start.plusMillis(50)); }
      return ChatResponse.builder().aiMessage(AiMessage.from("late answer"))
          .tokenUsage(timeLimit ? new TokenUsage(10, 20) : new TokenUsage(600, 600)).build();
    };
    if (streaming) {
      StreamingChatModel upstream = mock(StreamingChatModel.class);
      when(upstream.defaultRequestParameters()).thenReturn(defaults);
      doAnswer(call -> {
        ((StreamingChatResponseHandler) call.getArgument(1)).onCompleteResponse(response.get());
        return null;
      }).when(upstream).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
      var wrapper = new RateLimitStreamingChatModel(upstream, config, seconds -> {},
          new RateLimitErrorClassifier(), new RateLimitWaitPolicy()).withRunBudget(budget);
      var handler = mock(StreamingChatResponseHandler.class);
      LlmRunBudget.withRun("run", () -> { wrapper.chat(request, handler); return null; });
      verify(handler).onError(isA(LlmRunBudget.Exhausted.class));
      verify(handler, never()).onCompleteResponse(any());
    } else {
      ChatModel upstream = mock(ChatModel.class);
      when(upstream.defaultRequestParameters()).thenReturn(defaults);
      when(upstream.chat(any(ChatRequest.class))).thenAnswer(call -> response.get());
      var wrapper = new RateLimitChatModel(upstream, config, seconds -> {},
          new RateLimitErrorClassifier(), new RateLimitWaitPolicy()).withRunBudget(budget);
      assertThrows(LlmRunBudget.Exhausted.class,
          () -> LlmRunBudget.withRun("run", () -> wrapper.chat(request)));
    }
    assertNotNull(budget.state("run").refusal());
    assertEquals(1, budget.state("run").requests());
    assertTrue(budget.state("run").active().isEmpty());
    assertEquals(timeLimit ? 50 : 0, budget.state("run").activeMillis());
    assertEquals(timeLimit ? 30 : 1200, budget.state("run").tokens());
  }

  @Test
  void simultaneousRequestsShareActiveTimeInsteadOfReceivingSeparateFullDeadlines() throws Exception {
    budget();
    var start = java.time.Instant.parse("2026-10-07T09:00:00Z");
    var now = new java.util.concurrent.atomic.AtomicReference<>(start);
    var testClock = mock(Clock.class);
    when(testClock.instant()).thenAnswer(call -> now.get());
    var budget = new LlmRunBudget(blobs, mapper, config.llm(), testClock);
    var warm = budget.reserve("warm", 1, 1);
    budget.complete(warm, null);
    when(config.llm().maxActiveTimePerRun()).thenReturn(java.time.Duration.ofMillis(300));
    var tickets = java.util.List.of(budget.reserve("parallel", 1, 1), budget.reserve("parallel", 1, 1));
    var entered = new java.util.concurrent.CountDownLatch(2);
    var release = new java.util.concurrent.CountDownLatch(1);
    var failures = new java.util.concurrent.CopyOnWriteArrayList<Throwable>();
    var workers = new java.util.ArrayList<Thread>();
    for (var ticket : tickets) {
      workers.add(Thread.ofVirtual().start(() -> {
        try {
          budget.invoke(ticket, () -> {
            entered.countDown();
            try { release.await(); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            return null;
          });
        } catch (Throwable failure) { failures.add(failure); }
        finally { budget.complete(ticket, null); }
      }));
    }
    try {
      assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS));
      now.set(start.plusMillis(150));
      for (Thread worker : workers) { worker.join(2000); assertFalse(worker.isAlive()); }
    } finally {
      release.countDown();
    }
    assertEquals(0, entered.getCount());
    assertEquals(2, failures.size());
    failures.forEach(failure -> assertInstanceOf(LlmRunBudget.Exhausted.class, failure));
    assertEquals(300, budget.state("parallel").activeMillis(),
        "Both requests share the summed active time allowance");
    assertEquals(2, budget.state("parallel").requests());
  }

  @Test
  void staggeredRequestsUseTheSameSummedTimeAllowance() {
    var testClock = mock(Clock.class);
    var start = java.time.Instant.parse("2026-10-07T09:00:00Z");
    when(testClock.instant()).thenReturn(start);
    budget();
    when(config.llm().maxActiveTimePerRun()).thenReturn(java.time.Duration.ofMillis(300));
    var budget = new LlmRunBudget(blobs, mapper, config.llm(), testClock);
    var first = budget.reserve("staggered", 1, 1);
    when(testClock.instant()).thenReturn(start.plusMillis(100));
    var second = budget.reserve("staggered", 1, 1);
    when(testClock.instant()).thenReturn(start.plusMillis(199));
    assertEquals(2, budget.remainingMillis(second));
    assertNull(budget.expire(first));
    when(testClock.instant()).thenReturn(start.plusMillis(200));
    assertNotNull(budget.expire(second));
    budget.complete(first, null);
    budget.complete(second, null);
    assertEquals(300, budget.state("staggered").activeMillis());
    assertTrue(budget.state("staggered").active().isEmpty());
  }

  @Test
  void staleDeadlineCannotUndoAnExplicitTimeGrant() {
    var testClock = mock(Clock.class);
    var start = java.time.Instant.parse("2026-10-07T09:00:00Z");
    when(testClock.instant()).thenReturn(start);
    var budget = budget();
    when(config.llm().maxActiveTimePerRun()).thenReturn(java.time.Duration.ofMillis(300));
    budget = new LlmRunBudget(blobs, mapper, config.llm(), testClock);
    var ticket = budget.reserve("grant-race", 1, 1);
    when(testClock.instant()).thenReturn(start.plusMillis(300));
    assertNotNull(budget.expire(ticket));
    budget.grant("grant-race", "explicit-grant");
    assertNull(budget.expire(ticket));
    assertNull(budget.state("grant-race").refusal());
    budget.complete(ticket, null);
    when(testClock.instant()).thenReturn(start.plusSeconds(3600));
    assertEquals(300, budget.remainingMillis(ticket), "Human waiting does not use active request time");
  }

  @Test
  void observedUsageBeyondTheEstimatePausesTheRunBeforeFurtherEffects() {
    var budget = budget();
    when(config.llm().maxTokensPerRun()).thenReturn(100L);
    var ticket = budget.reserve("usage", 20, 30);
    budget.complete(ticket, new TokenUsage(70, 60));
    assertEquals(130, budget.state("usage").tokens());
    assertNotNull(budget.state("usage").refusal());
    assertThrows(LlmRunBudget.Exhausted.class, () -> budget.reserve("usage", 1, 1));
    assertEquals(1, budget.state("usage").requests());
  }
}
