package org.qubership.integration.platform.ai.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.NotAuthorizedException;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bearer token of the user who owns a conversation's current turn, keyed by conversation id so it
 * is visible on every worker thread {@link ToolSession} already reaches. The client may renew the
 * token while the turn runs, and the store keeps the catalog's last 401 or 403 for that token.
 */
public final class UserTokenStore {

  /** The fixed copy a turn ends with when the catalog rejects its token as expired. */
  public static final String SESSION_EXPIRED_MESSAGE =
      "Your session has expired. Sign in again to continue.";
  /** The fixed copy a turn ends with when the catalog denies the caller's permission. */
  public static final String ACCESS_DENIED_MESSAGE =
      "You don't have permission to perform this action.";

  private static final Map<String, Turn> TURNS = new ConcurrentHashMap<>();
  private static final ObjectMapper JSON = new ObjectMapper();

  private UserTokenStore() {}

  /**
   * One chat turn's hold on a conversation's token. Two turns can share a conversation and a
   * token, so release is by identity of this hold, never by token value.
   */
  public static final class Turn {
    private volatile String token;
    private volatile int rejectedStatus;

    private Turn(String token) {
      this.token = token;
    }
  }

  /** Registers the turn's token; the returned hold must be passed to {@link #remove} when it ends. */
  public static Turn put(String conversationId, String token) {
    Turn turn = new Turn(token);
    TURNS.put(conversationId, turn);
    return turn;
  }

  /** Releases {@code turn} only while it still owns the conversation, so a finished turn cannot drop a newer turn's token. */
  public static void remove(String conversationId, Turn turn) {
    if (turn != null) {
      TURNS.remove(conversationId, turn);
    }
  }

  /** Drops whatever turn holds the conversation, whichever hold registered it. */
  public static void clear(String conversationId) {
    TURNS.remove(conversationId);
  }

  /**
   * Makes {@code alias} use the same turn as {@code conversationId}, renewals included, and returns
   * the hold to pass to {@link #remove} for the alias; {@code null} when there is no turn.
   */
  public static Turn share(String conversationId, String alias) {
    Turn turn = TURNS.get(conversationId);
    if (turn != null) {
      TURNS.put(alias, turn);
    }
    return turn;
  }

  /** Swaps in a renewed token; false unless the turn is running and the token has the same {@code sub}. */
  public static boolean renew(String conversationId, String token) {
    Turn turn = TURNS.get(conversationId);
    String subject = subject(token);
    if (turn == null || subject == null || !subject.equals(subject(turn.token))) {
      return false;
    }
    turn.token = token;
    return true;
  }

  /** Records a catalog 401 or 403 answered for the turn bound to the current thread. */
  public static void rejectCurrent(int status) {
    String conversationId = ToolSession.resolveConversationId();
    Turn turn = conversationId == null ? null : TURNS.get(conversationId);
    if (turn != null) {
      turn.rejectedStatus = status;
    }
  }

  /** The fixed copy for a catalog 401 or 403 that ended a turn. */
  public static String rejectionMessage(int status) {
    return status == 401 ? SESSION_EXPIRED_MESSAGE : ACCESS_DENIED_MESSAGE;
  }

  /** The catalog 401 or 403 recorded for the conversation's turn, or 0. */
  public static int rejection(String conversationId) {
    Turn turn = conversationId == null ? null : TURNS.get(conversationId);
    return turn == null ? 0 : turn.rejectedStatus;
  }

  /** The token of the conversation bound to the current thread, if any. */
  public static Optional<String> current() {
    return get(ToolSession.resolveConversationId());
  }

  public static Optional<String> get(String conversationId) {
    Turn turn = conversationId == null ? null : TURNS.get(conversationId);
    return turn == null ? Optional.empty() : Optional.of(turn.token);
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

  /** The JWT {@code sub} claim, read without verifying the signature; null for an opaque token. */
  private static String subject(String token) {
    String[] parts = Objects.requireNonNull(token).split("\\.");
    if (parts.length < 2) {
      return null;
    }
    try {
      var sub = JSON.readTree(Base64.getUrlDecoder().decode(parts[1])).path("sub");
      return sub.isTextual() ? sub.asText() : null;
    } catch (Exception notJwt) {
      return null;
    }
  }
}
