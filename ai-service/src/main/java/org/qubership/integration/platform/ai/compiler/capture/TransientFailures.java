package org.qubership.integration.platform.ai.compiler.capture;

import dev.langchain4j.exception.RateLimitException;
import jakarta.ws.rs.WebApplicationException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.security.cert.CertificateException;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLPeerUnverifiedException;
import org.qubership.integration.platform.ai.llm.ratelimit.RateLimitTurnBudgetExhaustedException;

/**
 * Detects transport and rate-limit failures that may succeed on a later pipeline attempt, and
 * environment restrictions that will not.
 */
public final class TransientFailures {

  /** User-facing summary when TLS or trust material blocked the request. */
  public static final String ENVIRONMENT_SUMMARY =
      "A secure connection or environment policy blocked creation. Repeating the same request will not help.";

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

  /**
   * True for certificate, trust-store, and peer-verification failures. A handshake that wraps a
   * connection timeout stays transient because {@link #isTransient(Throwable)} runs first.
   */
  public static boolean isPermanentEnvironment(Throwable error) {
    for (Throwable current = error; current != null; current = current.getCause()) {
      if (current instanceof SSLHandshakeException
          || current instanceof SSLPeerUnverifiedException
          || current instanceof CertificateException) {
        return true;
      }
    }
    return false;
  }

  private static boolean isTransientStatus(int status) {
    return status == 429 || status == 502 || status == 503 || status == 504;
  }
}
