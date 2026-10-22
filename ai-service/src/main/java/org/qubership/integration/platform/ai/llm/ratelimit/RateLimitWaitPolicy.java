package org.qubership.integration.platform.ai.llm.ratelimit;

import java.time.Duration;
import java.util.Optional;

public final class RateLimitWaitPolicy {

  public int ceilWaitSeconds(Duration raw) {
    if (raw.isZero() || raw.isNegative()) {
      return 0;
    }
    long seconds = raw.getSeconds();
    if (seconds >= Integer.MAX_VALUE) return Integer.MAX_VALUE;
    return (int) seconds + (raw.getNano() == 0 ? 0 : 1);
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
