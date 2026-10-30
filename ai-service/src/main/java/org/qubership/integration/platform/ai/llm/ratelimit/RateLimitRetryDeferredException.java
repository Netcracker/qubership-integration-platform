package org.qubership.integration.platform.ai.llm.ratelimit;

import java.time.Instant;

/** A completed 429 whose retry must wait beyond the current bounded model call. */
public final class RateLimitRetryDeferredException extends RuntimeException {
  private final Instant retryAt;

  public RateLimitRetryDeferredException(Instant retryAt, Throwable cause) {
    super("Provider retry deferred until " + retryAt, cause);
    this.retryAt = retryAt;
  }

  public Instant retryAt() {
    return retryAt;
  }
}
