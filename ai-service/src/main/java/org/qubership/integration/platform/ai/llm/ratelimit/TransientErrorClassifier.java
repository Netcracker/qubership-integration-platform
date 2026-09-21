package org.qubership.integration.platform.ai.llm.ratelimit;

import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.InternalServerException;
import java.util.Locale;
import java.util.Set;

public final class TransientErrorClassifier {

  private static final Set<Integer> RETRYABLE_STATUS_CODES = Set.of(502, 503, 504);

  private static final String[] RETRYABLE_MESSAGE_FRAGMENTS = {
    "upstream connect error",
    "disconnect/reset before headers",
    "connection termination",
    "connection reset"
  };

  public boolean isTransient(Throwable t) {
    for (Throwable current = t; current != null; current = current.getCause()) {
      if (current instanceof HttpException http && isRetryableStatus(http.statusCode())) {
        return true;
      }
      if (current instanceof InternalServerException && messageLooksTransient(current.getMessage())) {
        return true;
      }
      if (messageLooksTransient(current.getMessage())) {
        return true;
      }
    }
    return false;
  }

  private static boolean isRetryableStatus(int statusCode) {
    return RETRYABLE_STATUS_CODES.contains(statusCode);
  }

  private static boolean messageLooksTransient(String message) {
    if (message == null || message.isBlank()) {
      return false;
    }
    String normalized = message.toLowerCase(Locale.ROOT);
    for (String fragment : RETRYABLE_MESSAGE_FRAGMENTS) {
      if (normalized.contains(fragment)) {
        return true;
      }
    }
    return false;
  }
}
