package org.qubership.integration.platform.ai.chat;

import io.smallrye.mutiny.Context;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import org.jboss.logmanager.MDC;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.qubership.integration.platform.ai.productpipeline.create.ProductCapabilityCaptureContext;

/**
 * Binds ambient {@link ChatMdc#CONVERSATION_ID} for LangChain4j {@code @Tool} invocations.
 *
 * <p>Browser chat sets MDC in {@link org.qubership.integration.platform.ai.chat.service.ChatExecutionService}.
 * Product-pipeline capabilities and compiler skills must bind here before agent+tool runs. Use
 * {@link #propagateBinding(Context, Multi)} or {@link #propagateBinding(Context, Uni)} when work may
 * run on another thread (for example {@code runSubscriptionOn}).
 */
public final class ToolSession {

  static final String CONTEXT_KEY = "tool-session-conversation-id";

  private static final ThreadLocal<String> THREAD_CONVERSATION_ID = new ThreadLocal<>();
  private static final ThreadLocal<Context> THREAD_CONTEXT = new ThreadLocal<>();
  private static final ThreadLocal<Context> SUBSCRIBE_PATH_CONTEXT = new ThreadLocal<>();

  private ToolSession() {}

  /** Binds {@code conversationId} until {@link Handle#close()}, which restores the previous binding. */
  public static Handle open(String conversationId) {
    ThreadState previous = captureThreadState();
    bind(conversationId);
    return new Handle(previous);
  }

  public static void bind(String conversationId) {
    String id = requireConversationId(conversationId);
    MDC.put(ChatMdc.CONVERSATION_ID, id);
    THREAD_CONVERSATION_ID.set(id);
    Context context = Context.of(CONTEXT_KEY, id);
    THREAD_CONTEXT.set(context);
    SUBSCRIBE_PATH_CONTEXT.set(context);
  }

  public static void clear() {
    MDC.remove(ChatMdc.CONVERSATION_ID);
    THREAD_CONVERSATION_ID.remove();
    THREAD_CONTEXT.remove();
    SUBSCRIBE_PATH_CONTEXT.remove();
  }

  /**
   * Resolves the active conversation id for capture and catalog tools.
   *
   * <p>Order: MDC, thread binding from {@link #bind}, then {@link ProductCapabilityCaptureContext}.
   */
  public static String resolveConversationId() {
    String fromMdc = readMdcConversationId();
    if (fromMdc != null) {
      return fromMdc;
    }
    String fromThread = THREAD_CONVERSATION_ID.get();
    if (fromThread != null && !fromThread.isBlank()) {
      return fromThread.trim();
    }
    return ProductCapabilityCaptureContext.current()
        .map(ProductCapabilityCaptureContext.Binding::conversationId)
        .filter(id -> id != null && !id.isBlank())
        .map(String::trim)
        .orElse(null);
  }

  public static Context attachedContext() {
    Context context = THREAD_CONTEXT.get();
    return context != null ? context : Context.empty();
  }

  public static <T> Uni<T> propagateBinding(Context context, Uni<T> upstream) {
    if (context == null || !context.contains(CONTEXT_KEY)) {
      return upstream;
    }
    return Uni.createFrom()
        .deferred(
            () -> {
              AtomicReference<ThreadState> previous =
                  new AtomicReference<>();
              return upstream
                  .onSubscription()
                  .invoke(
                      subscription -> {
                        previous.set(captureThreadState());
                        installOnSubscription(context);
                      })
                  .onTermination()
                  .invoke(() -> restoreIfCaptured(previous));
            });
  }

  public static <T> Multi<T> propagateBinding(Context context, Multi<T> upstream) {
    if (context == null || !context.contains(CONTEXT_KEY)) {
      return upstream;
    }
    return Multi.createFrom()
        .deferred(
            () -> {
              AtomicReference<ThreadState> previous =
                  new AtomicReference<>();
              return upstream
                  .onSubscription()
                  .invoke(
                      subscription -> {
                        previous.set(captureThreadState());
                        installOnSubscription(context);
                      })
                  .onTermination()
                  .invoke(() -> restoreIfCaptured(previous));
            });
  }

  private static void restoreIfCaptured(
      AtomicReference<ThreadState> previous) {
    ThreadState state = previous.getAndSet(null);
    if (state != null) {
      restoreThreadState(state);
    }
  }

  public static void executeInBoundContext(Context context, Runnable action) {
    callInBoundContext(context, () -> { action.run(); return null; });
  }

  public static <T> T callInBoundContext(Context context, Supplier<T> action) {
    if (context == null || !context.contains(CONTEXT_KEY)) {
      return action.get();
    }
    ThreadState previous = captureThreadState();
    installOnSubscription(context);
    try {
      return action.get();
    } finally {
      restoreThreadState(previous);
    }
  }

  public static final class Handle implements AutoCloseable {

    private final ThreadState previous;
    private boolean closed;

    private Handle(ThreadState previous) {
      this.previous = previous;
    }

    @Override
    public void close() {
      if (closed) {
        return;
      }
      closed = true;
      restoreThreadState(previous);
    }
  }

  private static void installOnSubscription(Context context) {
    String conversationId = context.get(CONTEXT_KEY);
    MDC.put(ChatMdc.CONVERSATION_ID, conversationId);
    THREAD_CONVERSATION_ID.set(conversationId);
    THREAD_CONTEXT.set(context);
    SUBSCRIBE_PATH_CONTEXT.set(context);
  }

  private record ThreadState(
      String conversationId, Context subscribeContext, Context threadContext, Object mdc) {}

  private static ThreadState captureThreadState() {
    return new ThreadState(
        THREAD_CONVERSATION_ID.get(),
        SUBSCRIBE_PATH_CONTEXT.get(),
        THREAD_CONTEXT.get(),
        MDC.get(ChatMdc.CONVERSATION_ID));
  }

  private static void restoreThreadState(ThreadState state) {
    if (state.conversationId() != null) {
      THREAD_CONVERSATION_ID.set(state.conversationId());
    } else {
      THREAD_CONVERSATION_ID.remove();
    }
    if (state.mdc() != null) {
      MDC.put(ChatMdc.CONVERSATION_ID, state.mdc().toString());
    } else {
      MDC.remove(ChatMdc.CONVERSATION_ID);
    }
    if (state.subscribeContext() != null) {
      SUBSCRIBE_PATH_CONTEXT.set(state.subscribeContext());
    } else {
      SUBSCRIBE_PATH_CONTEXT.remove();
    }
    if (state.threadContext() != null) {
      THREAD_CONTEXT.set(state.threadContext());
    } else {
      THREAD_CONTEXT.remove();
    }
  }

  private static String readMdcConversationId() {
    Object mdcValue = MDC.get(ChatMdc.CONVERSATION_ID);
    if (mdcValue == null) {
      return null;
    }
    String id = mdcValue.toString().trim();
    return id.isBlank() ? null : id;
  }

  private static String requireConversationId(String conversationId) {
    if (conversationId == null || conversationId.isBlank()) {
      throw new IllegalArgumentException("conversationId is required");
    }
    return conversationId.trim();
  }
}
