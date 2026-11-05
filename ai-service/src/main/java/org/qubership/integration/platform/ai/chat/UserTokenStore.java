package org.qubership.integration.platform.ai.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.NotAuthorizedException;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import org.eclipse.microprofile.config.ConfigProvider;
import org.qubership.integration.platform.ai.chat.activity.ToolInvocationSink;

/**
 * Bearer token of the user who owns a conversation's current turn, keyed by conversation id so it
 * is visible on every worker thread {@link ToolSession} already reaches. A newer token replaces
 * the old one, which lets the client renew an expiring token in the middle of a turn.
 */
public final class UserTokenStore {

  private static final Map<String, String> TOKENS = new ConcurrentHashMap<>();
  private static final Map<String, CompletableFuture<Void>> RENEW_SIGNALS = new ConcurrentHashMap<>();
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final long EXPIRY_SKEW_SECONDS = 5;
  static final String STEP_ID = "auth-token";

  private UserTokenStore() {}

  /** Stores a turn's token; refuses a token of another subject while the conversation's turn is still running. */
  public static void put(String conversationId, String token) {
    String subject = claim(token, "sub");
    TOKENS.compute(
        conversationId,
        (id, old) -> {
          // Same rule as renew: claim-less / opaque tokens cannot take over a running turn.
          if (old != null && (subject == null || !subject.equals(claim(old, "sub")))) {
            throw new ForbiddenException();
          }
          return token;
        });
  }

  /** Removes the entry only while it still holds {@code token}, so a finished turn cannot drop a newer turn's token. */
  public static void remove(String conversationId, String token) {
    TOKENS.remove(conversationId, token);
    completeRenewWaiters(conversationId);
  }

  /** Swaps in a renewed token for a running turn; false unless the turn exists and the token has the same {@code sub}. */
  public static boolean renew(String conversationId, String token) {
    String subject = claim(token, "sub");
    boolean[] replaced = {false};
    TOKENS.computeIfPresent(
        conversationId,
        (id, old) -> {
          if (subject == null || !subject.equals(claim(old, "sub"))) {
            return old;
          }
          replaced[0] = true;
          return token;
        });
    if (replaced[0]) {
      completeRenewWaiters(conversationId);
    }
    return replaced[0];
  }

  /** The token of the conversation bound to the current thread, if any. */
  public static Optional<String> current() {
    String conversationId = ToolSession.resolveConversationId();
    return conversationId == null ? Optional.empty() : Optional.ofNullable(TOKENS.get(conversationId));
  }

  /** The value of an {@code Authorization: Bearer} header, or {@code null} when it is absent. */
  public static String parseBearer(String authorization) {
    if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
      return null;
    }
    String token = authorization.substring(7).trim();
    return token.isEmpty() ? null : token;
  }

  public static String requireBearer(String authorization) {
    String token = parseBearer(authorization);
    if (token == null) {
      throw new NotAuthorizedException("Bearer");
    }
    return token;
  }

  /**
   * The conversation's token, waiting up to {@code waitMillis} for the client to renew it when it
   * has expired. Tells the client with {@code step(kind=auth, status=token_expired)} while waiting.
   * On timeout (or when the turn ends) returns empty so the caller fails closed instead of sending
   * an expired bearer.
   */
  public static Optional<String> currentUsable() {
    return currentUsable(
        ConfigProvider.getConfig().getOptionalValue("qip.ai.catalog.token-renew-wait-seconds", Long.class).orElse(120L)
            * 1000);
  }

  public static Optional<String> currentUsable(long waitMillis) {
    Optional<String> token = current();
    if (token.isEmpty() || !isExpired(token.get())) {
      return token;
    }
    if (waitMillis <= 0) {
      return Optional.empty();
    }
    String conversationId = ToolSession.resolveConversationId();
    Optional<Consumer<ChatEvent>> emit = ToolInvocationSink.currentEmit(conversationId);
    emit.ifPresent(e -> e.accept(new ChatEvent.Step(STEP_ID, "auth", "token_expired",
        "Session token expired, waiting for a new one", null)));
    CompletableFuture<Void> signal = new CompletableFuture<>();
    RENEW_SIGNALS.put(conversationId, signal);
    try {
      signal.get(waitMillis, TimeUnit.MILLISECONDS);
    } catch (TimeoutException timedOut) {
      return Optional.empty();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return Optional.empty();
    } catch (ExecutionException failed) {
      return Optional.empty();
    } finally {
      RENEW_SIGNALS.remove(conversationId, signal);
    }
    String latest = TOKENS.get(conversationId);
    if (latest == null) {
      return Optional.empty();
    }
    if (!isExpired(latest)) {
      emit.ifPresent(e -> e.accept(new ChatEvent.Step(STEP_ID, "auth", "done", null, null)));
      return Optional.of(latest);
    }
    return Optional.empty();
  }

  private static void completeRenewWaiters(String conversationId) {
    CompletableFuture<Void> signal = RENEW_SIGNALS.remove(conversationId);
    if (signal != null) {
      signal.complete(null);
    }
  }

  /** Reads {@code exp} without verifying the signature; an opaque or malformed token never expires here. */
  static boolean isExpired(String token) {
    String exp = claim(token, "exp");
    return exp != null && Long.parseLong(exp) <= Instant.now().getEpochSecond() + EXPIRY_SKEW_SECONDS;
  }

  /** A JWT payload claim as text, or null for an opaque, malformed or claim-less token. */
  private static String claim(String token, String name) {
    String[] parts = token.split("\\.");
    if (parts.length < 2) {
      return null;
    }
    try {
      var value = JSON.readTree(Base64.getUrlDecoder().decode(parts[1])).path(name);
      return value.isMissingNode() || value.isNull() ? null : value.asText();
    } catch (Exception notJwt) {
      return null;
    }
  }
}
