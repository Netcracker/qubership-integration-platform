package org.qubership.integration.platform.ai.integration.catalog.client;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Recognizes a catalog HTTP 401 answered for a turn bearer and remembers it for the conversation
 * until the next stage begins.
 *
 * <p>A catalog tool can turn the rejection into an ordinary result before the stage sees it. The
 * recorded rejection keeps the raw status available so a later message rewrite cannot hide the 401.
 */
public final class CatalogAuthenticationFailures {

  public static final int UNAUTHORIZED_STATUS = 401;

  private static final Map<String, RecordedRejection> RECORDED = new ConcurrentHashMap<>();

  private CatalogAuthenticationFailures() {}

  /** A catalog HTTP 401 answered for a call made with the turn bearer. */
  public record RecordedRejection(int status, String bodySnippet) {

    /** Raw evidence for the recovery card; names the status and keeps the catalog body. */
    public String technicalDetails() {
      String detail = "Catalog HTTP " + status + " on the turn bearer";
      if (bodySnippet != null && !bodySnippet.isBlank()) {
        detail += " — " + bodySnippet.trim();
      }
      return detail;
    }
  }

  public static void record(String conversationId, int status, String bodySnippet) {
    if (status != UNAUTHORIZED_STATUS) {
      return;
    }
    RECORDED.put(normalize(conversationId), new RecordedRejection(status, bodySnippet));
  }

  public static Optional<RecordedRejection> recordedFor(String conversationId) {
    return Optional.ofNullable(RECORDED.get(normalize(conversationId)));
  }

  public static void clear(String conversationId) {
    RECORDED.remove(normalize(conversationId));
  }

  public static boolean isTurnBearerUnauthorized(Throwable failure) {
    return fromFailure(failure).isPresent();
  }

  public static Optional<RecordedRejection> fromFailure(Throwable failure) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof CatalogNonRetryableResponseException catalog
          && catalog.turnBearer()
          && catalog.status() == UNAUTHORIZED_STATUS) {
        return Optional.of(new RecordedRejection(catalog.status(), catalog.bodySnippet()));
      }
    }
    return Optional.empty();
  }

  private static String normalize(String conversationId) {
    return conversationId == null ? "" : conversationId.trim();
  }
}
