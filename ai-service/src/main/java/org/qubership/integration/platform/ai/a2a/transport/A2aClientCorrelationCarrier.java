package org.qubership.integration.platform.ai.a2a.transport;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-owned correlation carrier for client-supplied Task and context identifiers.
 *
 * <p>Captures values at the request-handler boundary before the A2A SDK stamps generated IDs, then
 * survives asynchronous executor dispatch. Each inbound request receives an immutable server-owned
 * {@code requestId} that is distinct from the caller-scoped receipt key {@code (tenantId,
 * subjectId, messageId)}. Concurrent requests never share a holder. Client Message or params
 * metadata cannot select or replace the server-owned key: the handler overwrites the reserved
 * metadata entry after bind. Only {@link #clear(String)} with the owning {@code requestId} removes
 * an entry. Stale entries expire after a bounded TTL.
 */
public final class A2aClientCorrelationCarrier {

  /** Reserved MessageSendParams metadata key for the server-owned request correlation id. */
  public static final String METADATA_KEY = "qip.a2a.requestCorrelationId";

  public static final Duration DEFAULT_TTL = Duration.ofMinutes(15);

  private static final ConcurrentHashMap<String, Entry> ENTRIES = new ConcurrentHashMap<>();

  private A2aClientCorrelationCarrier() {}

  public record Holder(String taskId, String contextId) {}

  /** Immutable bind result: server-owned request id plus the captured client correlation IDs. */
  public record Binding(String requestId, Holder holder) {
    public Binding {
      Objects.requireNonNull(requestId, "requestId");
      Objects.requireNonNull(holder, "holder");
    }
  }

  /**
   * Inserts a new per-request entry and returns its server-owned identity. Always succeeds with a
   * distinct holder; never shares state with another concurrent request.
   */
  public static Binding bind(String taskId, String contextId) {
    String requestId = UUID.randomUUID().toString();
    Holder holder = new Holder(blankToNull(taskId), blankToNull(contextId));
    ENTRIES.put(requestId, new Entry(holder, Instant.now()));
    return new Binding(requestId, holder);
  }

  /**
   * Returns the bound holder for {@code requestId} when present and not expired. Does not fall back
   * to Message metadata or the caller-scoped receipt key.
   */
  public static Holder lookup(String requestId) {
    if (requestId == null || requestId.isBlank()) {
      return new Holder(null, null);
    }
    purgeExpired(Instant.now());
    Entry entry = ENTRIES.get(requestId);
    return entry == null ? new Holder(null, null) : entry.holder();
  }

  /**
   * Removes the entry for {@code requestId} only. A non-owner that does not know the id cannot
   * clear another request's carrier state.
   *
   * @return {@code true} when an entry was removed
   */
  public static boolean clear(String requestId) {
    if (requestId == null || requestId.isBlank()) {
      return false;
    }
    return ENTRIES.remove(requestId) != null;
  }

  /** Test seam: drops every entry. */
  static void clearAll() {
    ENTRIES.clear();
  }

  /** Test seam: current map size after TTL purge. */
  static int sizeForTest() {
    purgeExpired(Instant.now());
    return ENTRIES.size();
  }

  /** Test seam: whether {@code requestId} is still present. */
  static boolean containsForTest(String requestId) {
    if (requestId == null || requestId.isBlank()) {
      return false;
    }
    purgeExpired(Instant.now());
    return ENTRIES.containsKey(requestId);
  }

  private static void purgeExpired(Instant now) {
    Instant cutoff = now.minus(DEFAULT_TTL);
    ENTRIES.entrySet().removeIf(e -> e.getValue().boundAt().isBefore(cutoff));
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  private record Entry(Holder holder, Instant boundAt) {}
}
