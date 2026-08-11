package org.qubership.integration.platform.ai.chat.activity;

import io.smallrye.mutiny.Context;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import java.util.Optional;
import java.util.function.Consumer;
import org.qubership.integration.platform.ai.chat.ChatEvent;

/**
 * Emits name-only {@code step(kind=tool)} events while a skill run is active.
 *
 * <p>Propagation uses Mutiny {@link Context}, not a bare {@link ThreadLocal} alone. Call
 * {@link #bind(Consumer, String)} before a skill {@code Uni}/{@code Multi} subscription, pass
 * {@link #attachedContext()} into {@code subscribe().with(context, ...)} or
 * {@code awaitUsing(context)}, and use {@link #executeInBoundContext(Context, Runnable)} when work
 * may run on another thread (for example {@code runSubscriptionOn}). A thread-local mirror avoids
 * context lookup on the binding thread where tools usually run synchronously.
 */
public final class ToolInvocationSink {

  static final String CONTEXT_KEY = "tool-invocation-sink-binding";

  private static final ThreadLocal<Binding> THREAD_BINDING = new ThreadLocal<>();
  private static final ThreadLocal<Context> THREAD_CONTEXT = new ThreadLocal<>();
  /** Mutiny subscribe-path context (from {@code subscribe().with(context, ...)}). */
  private static final ThreadLocal<Context> SUBSCRIBE_PATH_CONTEXT = new ThreadLocal<>();

  private ToolInvocationSink() {}

  private record Binding(Consumer<ChatEvent> emit, String parentSkillId) {}

  public static void bind(Consumer<ChatEvent> emit) {
    bind(emit, null);
  }

  public static void bind(Consumer<ChatEvent> emit, String parentSkillId) {
    Binding binding = new Binding(emit, parentSkillId);
    Context context = Context.of(CONTEXT_KEY, binding);
    installBinding(binding, context);
  }

  public static void unbind() {
    THREAD_BINDING.remove();
    THREAD_CONTEXT.remove();
    SUBSCRIBE_PATH_CONTEXT.remove();
  }

  /**
   * Updates the parent skill wire id on the active binding (for example {@code
   * skill:brainstorming}) so subsequent tool steps nest under that skill. No-op when unbound.
   */
  public static void setParentSkillId(String parentSkillWireId) {
    resolveBinding()
        .ifPresent(
            binding -> {
              Binding updated = new Binding(binding.emit(), parentSkillWireId);
              installBinding(updated, Context.of(CONTEXT_KEY, updated));
            });
  }

  public static void clearParentSkillId() {
    setParentSkillId(null);
  }

  /** Emit consumer from the active binding, if any — used to re-bind on worker threads. */
  public static Optional<Consumer<ChatEvent>> currentEmit() {
    return resolveBinding().map(Binding::emit);
  }

  /**
   * Installs the bound {@link Context} for the upstream {@link Uni} subscription thread, including
   * worker threads reached via {@code runSubscriptionOn}.
   */
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

  public static void onInvoke(String toolName) {
    onInvoke(toolName, null);
  }

  public static void onInvoke(String toolName, String parentSkillId) {
    resolveBinding()
        .ifPresent(binding -> emit(binding, toolName, "running", parentSkillId));
  }

  public static void onComplete(String toolName) {
    onComplete(toolName, null);
  }

  public static void onComplete(String toolName, String parentSkillId) {
    resolveBinding()
        .ifPresent(binding -> emit(binding, toolName, "completed", parentSkillId));
  }

  public static void onFailed(String toolName) {
    resolveBinding().ifPresent(binding -> emit(binding, toolName, "error", null));
  }

  /** Parent skill wire id from the active binding, if any (for example {@code skill:cip-pattern-selector}). */
  public static Optional<String> currentParentSkillId() {
    return resolveBinding().map(Binding::parentSkillId);
  }

  private static void emit(Binding binding, String toolName, String status, String parentOverride) {
    String parentId = parentOverride != null ? parentOverride : binding.parentSkillId();
    binding
        .emit()
        .accept(ChatEvent.step("tool:" + toolName, "tool", status, toolName, parentId));
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
