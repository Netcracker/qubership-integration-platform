package org.qubership.integration.platform.ai.llm.ratelimit;

import jakarta.enterprise.context.ApplicationScoped;
import java.time.Duration;

/** Blocks the current thread for rate-limit backoff pauses. */
@ApplicationScoped
public class ThreadSleepBackoffSleeper implements RateLimitBackoffSleeper {

  @Override
  public void sleepSeconds(int seconds) {
    try {
      Thread.sleep(Duration.ofSeconds(seconds));
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Rate-limit backoff sleep interrupted", interrupted);
    }
  }
}
