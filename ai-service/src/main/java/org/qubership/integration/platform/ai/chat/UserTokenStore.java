package org.qubership.integration.platform.ai.chat;

import jakarta.ws.rs.NotAuthorizedException;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bearer token of the user who owns a conversation's current turn, keyed by conversation id so it
 * is visible on every worker thread {@link ToolSession} already reaches.
 */
public final class UserTokenStore {

  private static final Map<String, String> TOKENS = new ConcurrentHashMap<>();

  private UserTokenStore() {}

  public static void put(String conversationId, String token) {
    TOKENS.put(conversationId, token);
  }

  /** Removes the entry only while it still holds {@code token}, so a finished turn cannot drop a newer turn's token. */
  public static void remove(String conversationId, String token) {
    TOKENS.remove(conversationId, token);
  }

  /** The token of the conversation bound to the current thread, if any. */
  public static Optional<String> current() {
    return get(ToolSession.resolveConversationId());
  }

  public static Optional<String> get(String conversationId) {
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
   * The caller's bearer for a chat turn. Missing bearer is rejected unless {@link
   * AuthPolicy#required()} is off, in which case the turn runs without a user token ({@code null}).
   */
  public static String bearerForTurn(String authorization) {
    return AuthPolicy.required() ? requireBearer(authorization) : parseBearer(authorization);
  }
}
