package org.qubership.integration.platform.ai.llm.ratelimit;

import java.time.Duration;
import java.util.Optional;

public final class RateLimitWaitPolicy {

  public int ceilWaitSeconds(Duration raw) {
    if (raw.isZero() || raw.isNegative()) {
      return 0;
    }
    int seconds = (int) Math.ceil(raw.toMillis() / 1000.0);
    return Math.max(1, seconds);
  }

  public int fallbackWaitSeconds(int retryIndex) {
    return retryIndex == 0 ? 1 : 2;
  }

  public int resolveWaitSeconds(Optional<Duration> extracted, int retryIndex) {
    return extracted.map(this::ceilWaitSeconds).orElseGet(() -> fallbackWaitSeconds(retryIndex));
  }

  public boolean shouldRetry(int attemptIndex, int maxAttempts) {
    return attemptIndex + 1 < maxAttempts;
  }
}
