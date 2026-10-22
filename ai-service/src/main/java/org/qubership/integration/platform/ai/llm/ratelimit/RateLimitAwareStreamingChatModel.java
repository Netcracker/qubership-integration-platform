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
  // ponytail: providers may ignore cancellation; retain the reservation for an unknown outcome.
  private static final java.util.concurrent.ScheduledExecutorService DEADLINES =
      java.util.concurrent.Executors.newSingleThreadScheduledExecutor(action -> {
        Thread thread = new Thread(action, "model-deadlines");
        thread.setDaemon(true);
        return thread;
      });

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
  private LlmRunBudget runBudget;
  private String runId;
  private LlmRequestBudget requestBudget;

  RateLimitAwareStreamingChatModel withRunBudget(LlmRunBudget budget, String runId, LlmRequestBudget requests) {
    this.runBudget = budget;
    this.runId = runId;
    this.requestBudget = requests;
    return this;
  }

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
    chatWithRetry(chatRequest, handler, 0, 0, false, RateLimitRetryDeadline.current());
  }

  private void chatWithRetry(
      ChatRequest chatRequest,
      StreamingChatResponseHandler handler,
      int rateLimitAttempt,
      int transientAttempt, boolean contextRebuilt, java.time.Instant retryDeadline) {
    AtomicBoolean tokensStarted = new AtomicBoolean(false);
    var ticket = runBudget == null ? null : runBudget.reserve(runId,
        requestBudget.estimateInputTokens(chatRequest),
        chatRequest.maxOutputTokens() == null ? 8192 : chatRequest.maxOutputTokens());
    var deadline = new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.ScheduledFuture<?>>();
    String evidenceId = requestBudget == null ? null : requestBudget.recordProviderRequest(chatRequest, runId);
    var observer = observingHandler(handler, chatRequest, rateLimitAttempt, transientAttempt,
        tokensStarted, contextRebuilt, ticket, deadline, evidenceId, retryDeadline);
    if (ticket != null) { scheduleDeadline(ticket, observer, deadline); }
    try { delegate.chat(chatRequest, observer); }
    catch (RuntimeException failure) { observer.onError(failure); }
  }

  private void scheduleDeadline(LlmRunBudget.Ticket ticket, StreamingChatResponseHandler observer,
      java.util.concurrent.atomic.AtomicReference<java.util.concurrent.ScheduledFuture<?>> deadline) {
    deadline.set(DEADLINES.schedule(() -> Thread.ofVirtual().start(() -> {
      try {
        if (!runBudget.state(ticket.runId()).active().containsKey(ticket.id())) { return; }
        var failure = runBudget.expire(ticket);
        if (failure != null) { observer.onError(failure); }
        else { scheduleDeadline(ticket, observer, deadline); }
      } catch (RuntimeException failure) { observer.onError(failure); }
    }), runBudget.checkDelayMillis(ticket), java.util.concurrent.TimeUnit.MILLISECONDS));
  }

  private StreamingChatResponseHandler observingHandler(
      StreamingChatResponseHandler handler,
      ChatRequest chatRequest,
      int rateLimitAttempt,
      int transientAttempt,
      AtomicBoolean tokensStarted, boolean contextRebuilt, LlmRunBudget.Ticket ticket,
      java.util.concurrent.atomic.AtomicReference<java.util.concurrent.ScheduledFuture<?>> deadline, String evidenceId, java.time.Instant retryDeadline) {
    AtomicBoolean completed = new AtomicBoolean();
    var handle = new java.util.concurrent.atomic.AtomicReference<dev.langchain4j.model.chat.response.StreamingHandle>();
    return new StreamingChatResponseHandler() {

      @Override
      public synchronized void onPartialResponse(String partialResponse) {
        if (completed.get()) { return; }
        tokensStarted.set(true);
        forward(() -> handler.onPartialResponse(partialResponse));
      }

      @Override
      public synchronized void onPartialResponse(PartialResponse partialResponse, PartialResponseContext context) {
        handle.set(context.streamingHandle());
        if (completed.get()) { cancel(context.streamingHandle()); return; }
        tokensStarted.set(true);
        forward(() -> handler.onPartialResponse(partialResponse, context));
      }

      @Override
      public synchronized void onPartialThinking(PartialThinking partialThinking) {
        if (completed.get()) { return; }
        tokensStarted.set(true);
        forward(() -> handler.onPartialThinking(partialThinking));
      }

      @Override
      public synchronized void onPartialThinking(PartialThinking partialThinking, PartialThinkingContext context) {
        handle.set(context.streamingHandle());
        if (completed.get()) { cancel(context.streamingHandle()); return; }
        tokensStarted.set(true);
        forward(() -> handler.onPartialThinking(partialThinking, context));
      }

      @Override
      public synchronized void onPartialToolCall(PartialToolCall partialToolCall) {
        if (completed.get()) { return; }
        tokensStarted.set(true);
        forward(() -> handler.onPartialToolCall(partialToolCall));
      }

      @Override
      public synchronized void onPartialToolCall(PartialToolCall partialToolCall, PartialToolCallContext context) {
        handle.set(context.streamingHandle());
        if (completed.get()) { cancel(context.streamingHandle()); return; }
        tokensStarted.set(true);
        forward(() -> handler.onPartialToolCall(partialToolCall, context));
      }

      @Override
      public synchronized void onCompleteToolCall(CompleteToolCall completeToolCall) {
        if (completed.get()) { return; }
        tokensStarted.set(true);
        if (requestBudget != null) requestBudget.recordProviderOutcome(evidenceId, "tool-" + java.util.UUID.randomUUID(), completeToolCall);
        forward(() -> handler.onCompleteToolCall(completeToolCall));
      }

      @Override
      public synchronized void onCompleteResponse(ChatResponse completeResponse) {
        if (!completed.compareAndSet(false, true)) {
          if (runBudget != null) { runBudget.complete(ticket, null); }
          return;
        }
        if (deadline.get() != null) { deadline.get().cancel(false); }
        try {
          if (runBudget != null) {
            String refusal = runBudget.complete(ticket, completeResponse == null ? null : completeResponse.tokenUsage());
            if (refusal != null) { throw new LlmRunBudget.Exhausted(refusal); }
          }
        } catch (RuntimeException failure) { forward(() -> handler.onError(failure)); return; }
        if (requestBudget != null) requestBudget.recordProviderOutcome(evidenceId, "response", ProviderBoundaryEvidence.response(completeResponse));
        forward(() -> handler.onCompleteResponse(completeResponse));
      }

      @Override
      public synchronized void onError(Throwable error) {
        if (!completed.compareAndSet(false, true)) {
          if (runBudget != null && !(error instanceof LlmRunBudget.Exhausted)) { runBudget.complete(ticket, null); }
          return;
        }
        if (requestBudget != null) {
          var evidence = rateLimitClassifier.errorEvidence(error);
          evidence.put("partialOutput", tokensStarted.get());
          requestBudget.recordProviderOutcome(evidenceId, "error", evidence);
        }
        if (deadline.get() != null) { deadline.get().cancel(false); }
        if (error instanceof LlmRunBudget.Exhausted && handle.get() != null) { cancel(handle.get()); }
        try {
          if (runBudget != null && !(error instanceof LlmRunBudget.Exhausted)) { runBudget.complete(ticket, null); }
        } catch (RuntimeException failure) { forward(() -> handler.onError(failure)); return; }
        if (tokensStarted.get()) {
          forward(() -> handler.onError(error));
          return;
        }
        if (!contextRebuilt && requestBudget != null && LlmRequestBudget.isOverflow(error)) {
          ChatRequest rebuilt = requestBudget.rebuild(chatRequest);
          if (!rebuilt.messages().equals(chatRequest.messages())) {
            retry(requestBudget.prepare(rebuilt), handler, rateLimitAttempt, transientAttempt, true, retryDeadline);
            return;
          }
        }
        if (rateLimitEnabled
            && rateLimitClassifier.isRateLimit(error)
            && rateLimitPolicy.shouldRetry(rateLimitAttempt, rateLimitMaxAttempts)) {
          int waitSeconds =
              rateLimitPolicy.resolveWaitSeconds(
                  rateLimitClassifier.extractWait(error), rateLimitAttempt);
          try {
            RateLimitRetryDeadline.deferIfOutsideDeadline(retryDeadline, waitSeconds, error);
          } catch (RateLimitRetryDeferredException deferred) {
            forward(() -> handler.onError(deferred));
            return;
          }
          onBackoff.accept(new RateLimitAwareChatModel.BackoffEvent(rateLimitAttempt + 1, waitSeconds));
          sleeper.sleepSeconds(waitSeconds);
          retry(chatRequest, handler, rateLimitAttempt + 1, transientAttempt, contextRebuilt, retryDeadline);
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
          retry(chatRequest, handler, rateLimitAttempt, transientAttempt + 1, contextRebuilt, retryDeadline);
          return;
        }
        forward(() -> handler.onError(error));
      }
    };
  }

  private void retry(ChatRequest request, StreamingChatResponseHandler handler,
      int rateLimitAttempt, int transientAttempt, boolean contextRebuilt, java.time.Instant retryDeadline) {
    try { chatWithRetry(request, handler, rateLimitAttempt, transientAttempt, contextRebuilt, retryDeadline); }
    catch (RuntimeException failure) { forward(() -> handler.onError(failure)); }
  }

  private void forward(Runnable callback) {
    if (runId == null) { callback.run(); }
    else { LlmRunBudget.withRun(runId, () -> { callback.run(); return null; }); }
  }

  private static void cancel(dev.langchain4j.model.chat.response.StreamingHandle handle) {
    try { handle.cancel(); }
    catch (RuntimeException failure) { LOG.warn("Model transport cancellation failed", failure); }
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
