package org.qubership.integration.platform.ai.chat.activity;

import io.smallrye.mutiny.Context;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import java.util.Optional;
import java.util.function.Consumer;
import org.qubership.integration.platform.ai.chat.ChatEvent;
import org.qubership.integration.platform.ai.llm.ratelimit.RateLimitTurnBudgetExhaustedException;

/**
 * Emits {@code step(kind=llm)} events while an LLM rate-limit backoff is active.
 *
 * <p>Propagation uses Mutiny {@link Context}, not a bare {@link ThreadLocal} alone. Call
 * {@link #bind(Consumer, String, int)} before the chat turn {@code Multi} subscription and
 * {@link #unbind()} on termination. Use {@link #setParentSkillId(String)} while a skill runs
 * so backoff steps nest under the active skill.
 *
 * <p>When the per-turn backoff count reaches {@code maxTurnBackoffs}, {@link #onBackoff(int, int)}
 * throws {@link RateLimitTurnBudgetExhaustedException} so the SSE stream can fail closed with
 * {@code event: error}.
 */
public final class LlmRateLimitBackoffSink {

  static final String CONTEXT_KEY = "llm-rate-limit-backoff-sink-binding";
  static final String STEP_ID = "llm:rate-limit-backoff";

  private static final ThreadLocal<Binding> THREAD_BINDING = new ThreadLocal<>();
  private static final ThreadLocal<Context> THREAD_CONTEXT = new ThreadLocal<>();
  private static final ThreadLocal<Context> SUBSCRIBE_PATH_CONTEXT = new ThreadLocal<>();

  private LlmRateLimitBackoffSink() {}

  private record Binding(
      Consumer<ChatEvent> emit,
      String parentSkillId,
      String lastBackoffLabel,
      int backoffCount,
      int maxTurnBackoffs) {}

  public static void bind(Consumer<ChatEvent> emit) {
    bind(emit, null, Integer.MAX_VALUE);
  }

  public static void bind(Consumer<ChatEvent> emit, String parentSkillWireId) {
    bind(emit, parentSkillWireId, Integer.MAX_VALUE);
  }

  public static void bind(Consumer<ChatEvent> emit, String parentSkillWireId, int maxTurnBackoffs) {
    int cappedMax = Math.max(1, maxTurnBackoffs);
    Binding binding = new Binding(emit, parentSkillWireId, null, 0, cappedMax);
    Context context = Context.of(CONTEXT_KEY, binding);
    installBinding(binding, context);
  }

  public static void unbind() {
    THREAD_BINDING.remove();
    THREAD_CONTEXT.remove();
    SUBSCRIBE_PATH_CONTEXT.remove();
  }

  public static void setParentSkillId(String parentSkillWireId) {
    resolveBinding()
        .ifPresent(
            binding -> {
              Binding updated =
                  new Binding(
                      binding.emit(),
                      parentSkillWireId,
                      binding.lastBackoffLabel(),
                      binding.backoffCount(),
                      binding.maxTurnBackoffs());
              installBinding(updated, Context.of(CONTEXT_KEY, updated));
            });
  }

  public static void clearParentSkillId() {
    setParentSkillId(null);
  }

  public static <T> Uni<T> propagateBinding(Context context, Uni<T> upstream) {
    if (context == null || !context.contains(CONTEXT_KEY)) {
      return upstream;
    }
    Binding binding = context.get(CONTEXT_KEY);
    return upstream.onSubscription().invoke(subscription -> installBinding(binding, context));
  }

  public static <T> Multi<T> propagateBinding(Context context, Multi<T> upstream) {
    if (context == null || !context.contains(CONTEXT_KEY)) {
      return upstream;
    }
    Binding binding = context.get(CONTEXT_KEY);
    return upstream.onSubscription().invoke(subscription -> installBinding(binding, context));
  }

  public static Context attachedContext() {
    Context context = THREAD_CONTEXT.get();
    return context != null ? context : Context.empty();
  }

  public static void executeInBoundContext(Context context, Runnable action) {
    Binding binding = context.getOrElse(CONTEXT_KEY, () -> null);
    if (binding == null) {
      action.run();
      return;
    }
    Binding previousBinding = THREAD_BINDING.get();
    Context previousSubscribeContext = SUBSCRIBE_PATH_CONTEXT.get();
    Context previousThreadContext = THREAD_CONTEXT.get();
    installBinding(binding, context);
    try {
      action.run();
    } finally {
      restoreThreadState(previousBinding, previousSubscribeContext, previousThreadContext);
    }
  }

  public static void onBackoff(int attempt, int waitSeconds) {
    Binding binding =
        resolveBinding()
            .orElse(null);
    if (binding == null) {
      return;
    }
    if (binding.backoffCount() >= binding.maxTurnBackoffs()) {
      throw new RateLimitTurnBudgetExhaustedException(binding.maxTurnBackoffs());
    }
    String label = "rate-limit backoff " + waitSeconds + "s";
    Binding updated =
        new Binding(
            binding.emit(),
            binding.parentSkillId(),
            label,
            binding.backoffCount() + 1,
            binding.maxTurnBackoffs());
    installBinding(updated, Context.of(CONTEXT_KEY, updated));
    emit(updated, "running", label);
  }

  public static void onBackoffCompleted() {
    resolveBinding()
        .ifPresent(
            binding -> {
              String label = binding.lastBackoffLabel();
              if (label == null) {
                label = "rate-limit backoff";
              }
              emit(binding, "completed", label);
            });
  }

  private static void emit(Binding binding, String status, String label) {
    binding
        .emit()
        .accept(ChatEvent.step(STEP_ID, "llm", status, label, binding.parentSkillId()));
  }

  private static Optional<Binding> resolveBinding() {
    Binding threadBinding = THREAD_BINDING.get();
    if (threadBinding != null) {
      return Optional.of(threadBinding);
    }
    return bindingFromContext(activeSubscribeContext());
  }

  private static Context activeSubscribeContext() {
    Context subscribeContext = SUBSCRIBE_PATH_CONTEXT.get();
    if (subscribeContext != null) {
      return subscribeContext;
    }
    return THREAD_CONTEXT.get();
  }

  private static Optional<Binding> bindingFromContext(Context context) {
    if (context == null || !context.contains(CONTEXT_KEY)) {
      return Optional.empty();
    }
    return Optional.of(context.get(CONTEXT_KEY));
  }

  private static void installBinding(Binding binding, Context context) {
    THREAD_BINDING.set(binding);
    THREAD_CONTEXT.set(context);
    SUBSCRIBE_PATH_CONTEXT.set(context);
  }

  private static void restoreThreadState(
      Binding previousBinding, Context previousSubscribeContext, Context previousThreadContext) {
    if (previousBinding != null) {
      THREAD_BINDING.set(previousBinding);
    } else {
      THREAD_BINDING.remove();
    }
    if (previousSubscribeContext != null) {
      SUBSCRIBE_PATH_CONTEXT.set(previousSubscribeContext);
    } else {
      SUBSCRIBE_PATH_CONTEXT.remove();
    }
    if (previousThreadContext != null) {
      THREAD_CONTEXT.set(previousThreadContext);
    } else {
      THREAD_CONTEXT.remove();
    }
  }
}
