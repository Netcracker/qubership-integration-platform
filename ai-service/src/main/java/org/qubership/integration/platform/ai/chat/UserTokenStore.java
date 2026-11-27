package org.qubership.integration.platform.ai.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.NotAuthorizedException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Bearer token of the user who owns a conversation, keyed by conversation id so it is visible on
 * every worker thread {@link ToolSession} already reaches. One user holds a conversation at a time:
 * turns of the same JWT {@code sub} share the slot, which renewals refresh. An expired token is never
 * returned, but its slot stays while holders remain, so a renewal can resume it. The slot is dropped
 * when its last hold ends. The store keeps the catalog's last 401 or 403 for the conversation.
 */
public final class UserTokenStore {

  /** The fixed copy a turn ends with when the catalog rejects its token as expired. */
  public static final String SESSION_EXPIRED_MESSAGE =
      "Your session has expired. Sign in again to continue.";
  /** The fixed copy a turn ends with when the catalog denies the caller's permission. */
  public static final String ACCESS_DENIED_MESSAGE =
      "You don't have permission to perform this action.";

  /** ABSENT: no slot. EXPIRED: a slot whose token has lapsed but which still has holders. LIVE: usable. */
  public enum Status { ABSENT, EXPIRED, LIVE }

  private static final Map<String, Slot> SLOTS = new ConcurrentHashMap<>();
  private static final ObjectMapper JSON = new ObjectMapper();
  /** Epoch seconds; tests replace it to move past a token's expiry. */
  static volatile LongSupplier clock = () -> Instant.now().getEpochSecond();

  private UserTokenStore() {}

  /** A conversation's credential: the token, the active holds, and the catalog's last 401 or 403. */
  private static final class Slot {
    private final String subject;
    private volatile String token;
    private volatile long expiresAt;
    private volatile int rejectedStatus;
    private final Map<String, Integer> holdsByKey = new HashMap<>();

    private Slot(String subject) {
      this.subject = subject;
    }

    private void refresh(String token) {
      this.token = token;
      this.expiresAt = expiresAt(token);
    }

    private void hold(String key) {
      holdsByKey.merge(key, 1, Integer::sum);
    }

    private void unhold(String key) {
      holdsByKey.computeIfPresent(key, (ignored, holds) -> holds > 1 ? holds - 1 : null);
    }

    private boolean heldBy(String key) {
      return holdsByKey.containsKey(key);
    }

    private boolean expired() {
      return clock.getAsLong() >= expiresAt;
    }
  }

  /**
   * One holder's claim on a conversation's credential. Release is by identity of the hold, so a
   * finished turn never drops a token a later turn still uses.
   */
  public static final class Hold implements AutoCloseable {
    private final String conversationId;
    private final Slot slot;
    private boolean released;

    private Hold(String conversationId, Slot slot) {
      this.conversationId = conversationId;
      this.slot = slot;
    }

    /** Idempotent: a second call does nothing. */
    public void release() {
      UserTokenStore.release(this);
    }

    @Override
    public void close() {
      release();
    }
  }

  /** Another user's turn holds the conversation; surfaces as HTTP 409. */
  public static final class ConversationBusyException extends WebApplicationException {

    public ConversationBusyException() {
      super("This conversation is in use by another user.", Response.Status.CONFLICT);
    }
  }

  /**
   * Holds the conversation for a turn bearing {@code token}, refreshing the slot's token. Reuses the
   * slot while it has holders, even after its token expired. Throws {@link
   * ConversationBusyException} while a different {@code sub} holds it.
   */
  public static synchronized Hold acquire(String conversationId, String token) {
    String subject = subjectOf(token);
    Slot slot = slotOf(conversationId);
    if (slot == null) {
      slot = new Slot(subject);
      SLOTS.put(conversationId, slot);
    } else if (!Objects.equals(subject, slot.subject)) {
      throw new ConversationBusyException();
    }
    slot.refresh(token);
    slot.rejectedStatus = 0;
    slot.hold(conversationId);
    return new Hold(conversationId, slot);
  }

  /** Holds an existing conversation's credential, expired or not, for durable work; empty when none. */
  public static synchronized Optional<Hold> pin(String conversationId) {
    Slot slot = slotOf(conversationId);
    if (slot == null) {
      return Optional.empty();
    }
    slot.hold(conversationId);
    return Optional.of(new Hold(conversationId, slot));
  }

  /** Makes {@code alias} use the same credential as {@code conversationId}; null when there is none. */
  public static synchronized Hold share(String conversationId, String alias) {
    Slot slot = slotOf(conversationId);
    if (slot == null) {
      return null;
    }
    SLOTS.put(alias, slot);
    slot.hold(alias);
    return new Hold(alias, slot);
  }

  /** Drops the conversation's credential whatever holds remain. */
  public static void clear(String conversationId) {
    SLOTS.remove(conversationId);
  }

  /** Swaps in a renewed token; false unless the conversation has a slot and the token has the same {@code sub}. */
  public static synchronized boolean renew(String conversationId, String token) {
    Slot slot = slotOf(conversationId);
    String subject = subjectOf(token);
    if (slot == null || subject == null || !subject.equals(slot.subject)) {
      return false;
    }
    slot.refresh(token);
    return true;
  }

  /** Records a catalog 401 or 403 answered for the conversation bound to the current thread. */
  public static void rejectCurrent(int status) {
    String conversationId = ToolSession.resolveConversationId();
    Slot slot = conversationId == null ? null : slotOf(conversationId);
    if (slot != null) {
      slot.rejectedStatus = status;
    }
  }

  /** The fixed copy for a catalog 401 or 403 that ended a turn. */
  public static String rejectionMessage(int status) {
    return status == 401 ? SESSION_EXPIRED_MESSAGE : ACCESS_DENIED_MESSAGE;
  }

  /** The catalog 401 or 403 recorded for the conversation, or 0. */
  public static int rejection(String conversationId) {
    Slot slot = conversationId == null ? null : slotOf(conversationId);
    return slot == null ? 0 : slot.rejectedStatus;
  }

  /** The token of the conversation bound to the current thread, if any. */
  public static Optional<String> current() {
    return get(ToolSession.resolveConversationId());
  }

  /** The {@code sub} of the token of the conversation bound to the current thread, if any. */
  public static Optional<String> currentSubject() {
    return current().map(UserTokenStore::subjectOf);
  }

  /** The live token of the conversation; empty when there is none or it has expired. */
  public static Optional<String> get(String conversationId) {
    Slot slot = slotOf(conversationId);
    return slot == null || slot.expired() ? Optional.empty() : Optional.of(slot.token);
  }

  /** The credential state of the conversation bound to the current thread. */
  public static Status currentStatus() {
    return status(ToolSession.resolveConversationId());
  }

  /** The credential state of a conversation; reads only, so it never drops or renews a slot. */
  public static Status status(String conversationId) {
    Slot slot = slotOf(conversationId);
    if (slot == null) {
      return Status.ABSENT;
    }
    return slot.expired() ? Status.EXPIRED : Status.LIVE;
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

  private static synchronized void release(Hold hold) {
    if (hold.released) {
      return;
    }
    hold.released = true;
    hold.slot.unhold(hold.conversationId);
    if (!hold.slot.heldBy(hold.conversationId)) {
      SLOTS.remove(hold.conversationId, hold.slot);
    }
  }

  /** The slot held for the conversation, or null. An expired slot stays while it has holders. */
  private static Slot slotOf(String conversationId) {
    return conversationId == null ? null : SLOTS.get(conversationId);
  }

  /** The JWT {@code sub} claim, read without verifying the signature; null for an opaque token. */
  public static String subjectOf(String token) {
    JsonNode sub = claims(token).path("sub");
    return sub.isTextual() ? sub.asText() : null;
  }

  private static long expiresAt(String token) {
    JsonNode exp = claims(token).path("exp");
    return exp.isNumber() ? exp.asLong() : Long.MAX_VALUE;
  }

  private static JsonNode claims(String token) {
    String[] parts = Objects.requireNonNull(token).split("\\.");
    if (parts.length < 2) {
      return JSON.missingNode();
    }
    try {
      return JSON.readTree(Base64.getUrlDecoder().decode(parts[1]));
    } catch (Exception notJwt) {
      return JSON.missingNode();
    }
  }
}
