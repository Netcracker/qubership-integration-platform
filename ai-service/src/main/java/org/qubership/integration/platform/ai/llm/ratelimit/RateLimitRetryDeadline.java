package org.qubership.integration.platform.ai.llm.ratelimit;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;

/** Keeps a known provider wait outside an enclosing call's unchanged execution deadline. */
public final class RateLimitRetryDeadline {
  private static final ThreadLocal<Instant> DEADLINE = new ThreadLocal<>();

  private RateLimitRetryDeadline() {}

  public static <T> T withDeadline(Duration timeout, Supplier<T> action) {
    Instant previous = DEADLINE.get();
    Instant deadline = Instant.now().plus(timeout);
    DEADLINE.set(previous != null && previous.isBefore(deadline) ? previous : deadline);
    try {
      return action.get();
    } finally {
      if (previous == null) DEADLINE.remove();
      else DEADLINE.set(previous);
    }
  }

  static void deferIfOutsideDeadline(Instant deadline, int waitSeconds, Throwable cause) {
    Instant retryAt = Instant.now().plusSeconds(waitSeconds);
    if (deadline != null && !retryAt.isBefore(deadline)) {
      throw new RateLimitRetryDeferredException(retryAt, cause);
    }
  }

  static Instant current() {
    return DEADLINE.get();
  }
}
