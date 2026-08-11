package org.qubership.integration.platform.ai.chat;

import io.smallrye.mutiny.Context;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import org.jboss.logmanager.MDC;
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

  public static Handle open(String conversationId) {
    bind(conversationId);
    return new Handle();
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
    String conversationId = context.get(CONTEXT_KEY);
    return upstream.onSubscription().invoke(subscription -> installOnSubscription(conversationId, context));
  }

  public static <T> Multi<T> propagateBinding(Context context, Multi<T> upstream) {
    if (context == null || !context.contains(CONTEXT_KEY)) {
      return upstream;
    }
    String conversationId = context.get(CONTEXT_KEY);
    return upstream.onSubscription().invoke(subscription -> installOnSubscription(conversationId, context));
  }

  public static void executeInBoundContext(Context context, Runnable action) {
    if (context == null || !context.contains(CONTEXT_KEY)) {
      action.run();
      return;
    }
    String conversationId = context.get(CONTEXT_KEY);
    String previousConversationId = THREAD_CONVERSATION_ID.get();
    Context previousSubscribeContext = SUBSCRIBE_PATH_CONTEXT.get();
    Context previousThreadContext = THREAD_CONTEXT.get();
    Object previousMdc = MDC.get(ChatMdc.CONVERSATION_ID);
    installOnSubscription(conversationId, context);
    try {
      action.run();
    } finally {
      restoreThreadState(previousConversationId, previousSubscribeContext, previousThreadContext, previousMdc);
    }
  }

  public static final class Handle implements AutoCloseable {

    @Override
    public void close() {
      clear();
    }
  }

  private static void installOnSubscription(String conversationId, Context context) {
    MDC.put(ChatMdc.CONVERSATION_ID, conversationId);
    THREAD_CONVERSATION_ID.set(conversationId);
    THREAD_CONTEXT.set(context);
    SUBSCRIBE_PATH_CONTEXT.set(context);
  }

  private static void restoreThreadState(
      String previousConversationId,
      Context previousSubscribeContext,
      Context previousThreadContext,
      Object previousMdc) {
    if (previousConversationId != null) {
      THREAD_CONVERSATION_ID.set(previousConversationId);
    } else {
      THREAD_CONVERSATION_ID.remove();
    }
    if (previousMdc != null) {
      MDC.put(ChatMdc.CONVERSATION_ID, previousMdc.toString());
    } else {
      MDC.remove(ChatMdc.CONVERSATION_ID);
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
