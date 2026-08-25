package org.qubership.integration.platform.ai.compiler.capture;

import dev.langchain4j.exception.RateLimitException;
import jakarta.ws.rs.WebApplicationException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import org.qubership.integration.platform.ai.llm.ratelimit.RateLimitTurnBudgetExhaustedException;

/** Detects transport and rate-limit failures that may succeed on a later pipeline attempt. */
public final class TransientFailures {

  private TransientFailures() {}

  public static boolean isTransient(Throwable error) {
    for (Throwable current = error; current != null; current = current.getCause()) {
      if (current instanceof ConnectException
          || current instanceof SocketTimeoutException
          || current instanceof HttpTimeoutException
          || current instanceof RateLimitException
          || current instanceof RateLimitTurnBudgetExhaustedException) {
        return true;
      }
      if (current instanceof WebApplicationException webException
          && webException.getResponse() != null
          && isTransientStatus(webException.getResponse().getStatus())) {
        return true;
      }
    }
    return false;
  }

  private static boolean isTransientStatus(int status) {
    return status == 429 || status == 502 || status == 503 || status == 504;
  }
}
