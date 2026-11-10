package org.qubership.integration.platform.ai.integration.catalog.client;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Recognizes a catalog HTTP 403 answered for a turn bearer and remembers it for the conversation
 * until the turn ends.
 *
 * <p>A catalog tool can turn the denial into an ordinary result before the chat stream sees it. The
 * recorded denial lets the turn fail closed with one fixed message instead of a paraphrased answer.
 */
public final class CatalogAccessDeniedFailures {

  public static final int FORBIDDEN_STATUS = 403;

  public static final String ACCESS_DENIED_MESSAGE =
      "You don't have permission to perform this action in the catalog";

  private static final Map<String, RecordedRejection> RECORDED = new ConcurrentHashMap<>();

  private CatalogAccessDeniedFailures() {}

  /** A catalog HTTP 403 answered for a call made with the turn bearer. */
  public record RecordedRejection(int status, String bodySnippet) {

    /** Raw evidence for diagnostics; names the status and keeps the catalog body. */
    public String technicalDetails() {
      String detail = "Catalog HTTP " + status + " on the turn bearer";
      if (bodySnippet != null && !bodySnippet.isBlank()) {
        detail += " — " + bodySnippet.trim();
      }
      return detail;
    }
  }

  /** Fails a stream that would otherwise complete after a swallowed denial. */
  public static final class AccessDeniedException extends RuntimeException {
    public AccessDeniedException() {
      super(ACCESS_DENIED_MESSAGE);
    }
  }

  public static void record(String conversationId, int status, String bodySnippet) {
    if (status != FORBIDDEN_STATUS) {
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

  public static boolean isTurnBearerAccessDenied(Throwable failure) {
    return fromFailure(failure).isPresent();
  }

  public static Optional<RecordedRejection> fromFailure(Throwable failure) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof CatalogNonRetryableResponseException catalog
          && catalog.turnBearer()
          && catalog.status() == FORBIDDEN_STATUS) {
        return Optional.of(new RecordedRejection(catalog.status(), catalog.bodySnippet()));
      }
    }
    return Optional.empty();
  }

  private static String normalize(String conversationId) {
    return conversationId == null ? "" : conversationId.trim();
  }
}
