package org.qubership.integration.platform.ai.integration.catalog.auth;

import io.smallrye.mutiny.Context;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.qubership.integration.platform.ai.chat.ToolSession;

/**
 * Holds the caller's catalog {@code Authorization} header for the active conversation.
 *
 * <p>Values live in a conversation-keyed map so worker threads can resolve auth via {@link
 * ToolSession#resolveConversationId()} without storing tokens in MDC or conversation persistence.
 */
public final class CatalogAuthorizationContext {

  static final String CONTEXT_KEY = "catalog-authorization";

  private static final ConcurrentHashMap<String, String> BY_CONVERSATION = new ConcurrentHashMap<>();
  private static final ThreadLocal<String> THREAD_AUTHORIZATION = new ThreadLocal<>();
  private static final ThreadLocal<Context> THREAD_CONTEXT = new ThreadLocal<>();

  private CatalogAuthorizationContext() {}

  public static void bind(String conversationId, String authorization) {
    String id = requireConversationId(conversationId);
    String auth = requireAuthorization(authorization);
    BY_CONVERSATION.put(id, auth);
    THREAD_AUTHORIZATION.set(auth);
    THREAD_CONTEXT.set(Context.of(CONTEXT_KEY, auth));
  }

  public static boolean update(String conversationId, String authorization) {
    String id = requireConversationId(conversationId);
    if (!BY_CONVERSATION.containsKey(id)) {
      return false;
    }
    String auth = requireAuthorization(authorization);
    BY_CONVERSATION.put(id, auth);
    if (Objects.equals(id, ToolSession.resolveConversationId())) {
      THREAD_AUTHORIZATION.set(auth);
      THREAD_CONTEXT.set(Context.of(CONTEXT_KEY, auth));
    }
    return true;
  }

  public static boolean isBound(String conversationId) {
    if (conversationId == null || conversationId.isBlank()) {
      return false;
    }
    return BY_CONVERSATION.containsKey(conversationId.trim());
  }

  public static Optional<String> resolveAuthorization() {
    String conversationId = ToolSession.resolveConversationId();
    if (conversationId != null && !conversationId.isBlank()) {
      String fromConversation = BY_CONVERSATION.get(conversationId.trim());
      if (fromConversation != null && !fromConversation.isBlank()) {
        return Optional.of(fromConversation);
      }
    }
    String fromThread = THREAD_AUTHORIZATION.get();
    if (fromThread != null && !fromThread.isBlank()) {
      return Optional.of(fromThread);
    }
    Context context = THREAD_CONTEXT.get();
    if (context != null && context.contains(CONTEXT_KEY)) {
      String fromContext = context.get(CONTEXT_KEY);
      if (fromContext != null && !fromContext.isBlank()) {
        return Optional.of(fromContext);
      }
    }
    return Optional.empty();
  }

  public static void clear(String conversationId) {
    if (conversationId != null && !conversationId.isBlank()) {
      BY_CONVERSATION.remove(conversationId.trim());
    }
    THREAD_AUTHORIZATION.remove();
    THREAD_CONTEXT.remove();
  }

  public static Context attachedContext() {
    Context context = THREAD_CONTEXT.get();
    return context != null ? context : Context.empty();
  }

  public static <T> Uni<T> propagateBinding(Context context, Uni<T> upstream) {
    if (context == null || !context.contains(CONTEXT_KEY)) {
      return upstream;
    }
    String authorization = context.get(CONTEXT_KEY);
    return upstream.onSubscription().invoke(subscription -> installOnSubscription(authorization, context));
  }

  public static <T> Multi<T> propagateBinding(Context context, Multi<T> upstream) {
    if (context == null || !context.contains(CONTEXT_KEY)) {
      return upstream;
    }
    String authorization = context.get(CONTEXT_KEY);
    return upstream.onSubscription().invoke(subscription -> installOnSubscription(authorization, context));
  }

  public static Context mergeInto(Context base) {
    Optional<String> authorization = resolveAuthorization();
    if (authorization.isEmpty()) {
      return base == null ? Context.empty() : base;
    }
    String toolSessionKey = "tool-session-conversation-id";
    if (base != null && base.contains(toolSessionKey)) {
      return Context.of(
          toolSessionKey,
          base.get(toolSessionKey),
          CONTEXT_KEY,
          authorization.get());
    }
    return Context.of(CONTEXT_KEY, authorization.get());
  }

  public static <T> Uni<T> propagateWithToolSession(Context toolContext, Uni<T> upstream) {
    Context merged = mergeInto(toolContext);
    return propagateBinding(merged, ToolSession.propagateBinding(merged, upstream));
  }

  public static <T> Multi<T> propagateWithToolSession(Context toolContext, Multi<T> upstream) {
    Context merged = mergeInto(toolContext);
    return propagateBinding(merged, ToolSession.propagateBinding(merged, upstream));
  }

  public static void executeInBoundContext(Context context, Runnable action) {
    if (context == null || !context.contains(CONTEXT_KEY)) {
      action.run();
      return;
    }
    String authorization = context.get(CONTEXT_KEY);
    String previousAuthorization = THREAD_AUTHORIZATION.get();
    Context previousContext = THREAD_CONTEXT.get();
    installOnSubscription(authorization, context);
    try {
      action.run();
    } finally {
      restoreThreadState(previousAuthorization, previousContext);
    }
  }

  private static void installOnSubscription(String authorization, Context context) {
    THREAD_AUTHORIZATION.set(authorization);
    THREAD_CONTEXT.set(context);
  }

  private static void restoreThreadState(String previousAuthorization, Context previousContext) {
    if (previousAuthorization != null) {
      THREAD_AUTHORIZATION.set(previousAuthorization);
    } else {
      THREAD_AUTHORIZATION.remove();
    }
    if (previousContext != null) {
      THREAD_CONTEXT.set(previousContext);
    } else {
      THREAD_CONTEXT.remove();
    }
  }

  private static String requireConversationId(String conversationId) {
    if (conversationId == null || conversationId.isBlank()) {
      throw new IllegalArgumentException("conversationId is required");
    }
    return conversationId.trim();
  }

  private static String requireAuthorization(String authorization) {
    return CatalogAuthorizationSupport.normalizeAuthorization(authorization)
        .orElseThrow(() -> new IllegalArgumentException("authorization must be a non-empty Bearer token"));
  }
}
