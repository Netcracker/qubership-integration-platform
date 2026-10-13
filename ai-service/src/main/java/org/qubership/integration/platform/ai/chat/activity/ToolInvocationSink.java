package org.qubership.integration.platform.ai.chat.activity;

import io.smallrye.mutiny.Context;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import java.util.ArrayDeque;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.chat.ChatEvent;
import org.qubership.integration.platform.ai.chat.ToolSession;

/**
 * Emits name-only {@code step(kind=tool)} events while a skill run is active.
 *
 * <p>Propagation uses Mutiny {@link Context}, not a bare {@link ThreadLocal} alone. Call
 * {@link #bind(Consumer, String)} before a skill {@code Uni}/{@code Multi} subscription, pass
 * {@link #attachedContext()} into {@code subscribe().with(context, ...)} or
 * {@code awaitUsing(context)}, and use {@link #executeInBoundContext(Context, Runnable)} when work
 * may run on another thread (for example {@code runSubscriptionOn}). A thread-local mirror avoids
 * context lookup on the binding thread where tools usually run synchronously.
 *
 * <p>The chat turn also registers the emit consumer by conversation id so catalog and LangChain4j
 * tools on worker threads can emit {@code event: step} while {@link ToolSession} is bound.
 */
public final class ToolInvocationSink {

  static final String CONTEXT_KEY = "tool-invocation-sink-binding";

  private static final ThreadLocal<Binding> THREAD_BINDING = new ThreadLocal<>();
  private static final ThreadLocal<Context> THREAD_CONTEXT = new ThreadLocal<>();
  /** Mutiny subscribe-path context (from {@code subscribe().with(context, ...)}). */
  private static final ThreadLocal<Context> SUBSCRIBE_PATH_CONTEXT = new ThreadLocal<>();
  private static final ThreadLocal<Integer> BIND_DEPTH = new ThreadLocal<>();
  private static final ThreadLocal<String> REGISTERED_CONVERSATION_ID = new ThreadLocal<>();
  private static final ThreadLocal<ArrayDeque<Binding>> NESTED_PREVIOUS = new ThreadLocal<>();
  private static final ConcurrentHashMap<String, Binding> BY_CONVERSATION = new ConcurrentHashMap<>();

  private ToolInvocationSink() {}

  private record Binding(Consumer<ChatEvent> emit, String parentSkillId,
      CatalogChangeTracker changes, String conversationId) {}

  public static void bind(Consumer<ChatEvent> emit) {
    bind(emit, null);
  }

  public static void bind(Consumer<ChatEvent> emit, String parentSkillId) {
    bind(emit, parentSkillId, ToolSession.resolveConversationId());
  }

  public static Context bind(Consumer<ChatEvent> emit, String parentSkillId, String conversationId) {
    conversationId = conversationId == null || conversationId.isBlank() ? null : conversationId.trim();
    Binding previous = conversationId == null ? null : BY_CONVERSATION.get(conversationId);
    if (previous == null || previous.emit() != emit) previous = resolveBinding().orElse(null);
    CatalogChangeTracker changes = previous != null && previous.emit() == emit
        ? previous.changes() : new CatalogChangeTracker();
    Binding binding = new Binding(emit, parentSkillId, changes, conversationId);
    Context context = Context.of(CONTEXT_KEY, binding);
    Binding current = THREAD_BINDING.get();
    if (current != null && current.conversationId() != null
        && !isActiveBinding(current, current.conversationId())) {
      BIND_DEPTH.remove();
      NESTED_PREVIOUS.remove();
      REGISTERED_CONVERSATION_ID.remove();
      current = null;
    }
    int depth = bindDepth() + 1;
    BIND_DEPTH.set(depth);
    if (depth > 1 && current != null) {
      nestedPrevious().push(current);
    }
    installBinding(binding, context);
    if (conversationId != null && !conversationId.isBlank()) {
      String id = conversationId.trim();
      Binding previousConversation = BY_CONVERSATION.put(id, binding);
      // Nested worker binds may refresh parentSkillId; only the first binder may unbind the map.
      if (previousConversation == null) {
        REGISTERED_CONVERSATION_ID.set(id);
      }
    }
    return context;
  }

  public static void unbind() {
    unbindThreadLocals(true);
  }

  /**
   * Drops the conversation map entry even when this thread did not call {@link #bind}. Chat SSE
   * termination can run on a different thread than the binder.
   */
  public static void unbind(String conversationId) {
    if (conversationId != null && !conversationId.isBlank()) {
      BY_CONVERSATION.remove(conversationId.trim());
    }
    unbindThreadLocals(false);
  }

  private static void unbindThreadLocals(boolean removeConversation) {
    int depth = bindDepth() - 1;
    if (depth > 0) {
      BIND_DEPTH.set(depth);
      restoreNestedPrevious();
      return;
    }
    BIND_DEPTH.remove();
    NESTED_PREVIOUS.remove();
    String registered = REGISTERED_CONVERSATION_ID.get();
    if (removeConversation && registered != null) {
      BY_CONVERSATION.remove(registered);
    }
    REGISTERED_CONVERSATION_ID.remove();
    THREAD_BINDING.remove();
    THREAD_CONTEXT.remove();
    SUBSCRIBE_PATH_CONTEXT.remove();
  }

  /** Decrements bind depth if this thread called {@link #bind}. No-op when the depth is zero. */
  public static void unbindIfBound() {
    if (bindDepth() > 0) {
      unbind();
    }
  }

  /**
   * Updates the parent skill wire id on the active binding (for example {@code
   * skill:brainstorming}) so subsequent tool steps nest under that skill. No-op when unbound.
   */
  public static void setParentSkillId(String parentSkillWireId) {
    resolveBinding()
        .ifPresent(
            binding -> {
              Binding updated = new Binding(binding.emit(), parentSkillWireId, binding.changes(), binding.conversationId());
              installBinding(updated, Context.of(CONTEXT_KEY, updated));
              String conversationId = ToolSession.resolveConversationId();
              if (conversationId == null || conversationId.isBlank()) {
                conversationId = REGISTERED_CONVERSATION_ID.get();
              }
              if (conversationId != null && BY_CONVERSATION.containsKey(conversationId)) {
                BY_CONVERSATION.put(conversationId, updated);
              }
            });
  }

  public static void clearParentSkillId() {
    setParentSkillId(null);
  }

  /** Emit consumer from the active binding, if any — used to re-bind on worker threads. */
  public static Optional<Consumer<ChatEvent>> currentEmit() {
    return resolveBinding().map(Binding::emit);
  }

  public static Optional<Consumer<ChatEvent>> currentEmit(String conversationId) {
    if (conversationId == null || conversationId.isBlank()) return currentEmit();
    Binding requested = BY_CONVERSATION.get(conversationId.trim());
    if (requested != null) return Optional.of(requested.emit());
    return resolveBinding().filter(binding -> binding.conversationId() == null).map(Binding::emit);
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
    return resolveBinding().map(binding -> Context.of(CONTEXT_KEY, binding)).orElseGet(Context::empty);
  }

  public static void executeInBoundContext(Context context, Runnable action) {
    callInBoundContext(context, () -> { action.run(); return null; });
  }

  public static <T> T callInBoundContext(Context context, Supplier<T> action) {
    Binding binding = context.getOrElse(CONTEXT_KEY, () -> null);
    if (binding == null) {
      return action.get();
    }
    Binding previousBinding = THREAD_BINDING.get();
    Context previousSubscribeContext = SUBSCRIBE_PATH_CONTEXT.get();
    Context previousThreadContext = THREAD_CONTEXT.get();
    installBinding(binding, context);
    try {
      return action.get();
    } finally {
      restoreThreadState(previousBinding, previousSubscribeContext, previousThreadContext);
    }
  }

  public static Optional<CatalogChangeTracker> catalogChanges() {
    return resolveBinding().map(Binding::changes);
  }

  public static void flushCatalogChanges() {
    resolveBinding().ifPresent(ToolInvocationSink::flushCatalogChanges);
  }

  public static void flushCatalogChanges(Context context) {
    bindingFromContext(context).ifPresent(ToolInvocationSink::flushCatalogChanges);
  }

  private static void flushCatalogChanges(Binding binding) {
    ChatEvent.CatalogChanged changed = binding.changes().drain();
    if (changed != null) {
      try {
        binding.emit().accept(changed);
      } catch (RuntimeException e) {
        Logger.getLogger(ToolInvocationSink.class).debug("Catalog change delivery is unavailable", e);
      }
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
    String conversationId = ToolSession.resolveConversationId();
    if (isActiveBinding(threadBinding, conversationId)) {
      return Optional.of(threadBinding);
    }
    Optional<Binding> fromContext = bindingFromContext(activeSubscribeContext());
    if (fromContext.isPresent() && isActiveBinding(fromContext.get(), conversationId)) {
      return fromContext;
    }
    if (conversationId == null || conversationId.isBlank()) {
      return Optional.empty();
    }
    return Optional.ofNullable(BY_CONVERSATION.get(conversationId));
  }

  private static boolean isActiveBinding(Binding binding, String conversationId) {
    if (binding == null) return false;
    if (binding.conversationId() == null) return conversationId == null;
    if (conversationId != null && !conversationId.equals(binding.conversationId())) return false;
    Binding registered = BY_CONVERSATION.get(binding.conversationId());
    return registered != null && registered.changes() == binding.changes();
  }

  private static int bindDepth() {
    Integer depth = BIND_DEPTH.get();
    return depth == null ? 0 : depth;
  }

  private static ArrayDeque<Binding> nestedPrevious() {
    ArrayDeque<Binding> stack = NESTED_PREVIOUS.get();
    if (stack == null) {
      stack = new ArrayDeque<>();
      NESTED_PREVIOUS.set(stack);
    }
    return stack;
  }

  private static void restoreNestedPrevious() {
    ArrayDeque<Binding> stack = NESTED_PREVIOUS.get();
    if (stack == null || stack.isEmpty()) {
      return;
    }
    Binding previous = stack.pop();
    installBinding(previous, Context.of(CONTEXT_KEY, previous));
    String conversationId = REGISTERED_CONVERSATION_ID.get();
    if (conversationId == null || conversationId.isBlank()) {
      conversationId = ToolSession.resolveConversationId();
    }
    if (conversationId != null && BY_CONVERSATION.containsKey(conversationId)) {
      BY_CONVERSATION.put(conversationId, previous);
    }
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
