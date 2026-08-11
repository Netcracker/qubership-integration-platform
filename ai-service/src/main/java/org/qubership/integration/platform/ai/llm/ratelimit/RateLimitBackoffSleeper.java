package org.qubership.integration.platform.ai.llm.ratelimit;

public interface RateLimitBackoffSleeper {

  void sleepSeconds(int seconds);
}
