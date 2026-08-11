package org.qubership.integration.platform.ai.llm.ratelimit;

/**
 * Thrown when a chat turn has emitted too many {@code llm:rate-limit-backoff} waits. Surfaces as
 * SSE {@code event: error} so clients and e2e harnesses can fail fast instead of soft-looping.
 */
public final class RateLimitTurnBudgetExhaustedException extends RuntimeException {

  public RateLimitTurnBudgetExhaustedException(int maxTurnBackoffs) {
    super(
        "LLM rate-limit turn backoff budget exhausted after "
            + maxTurnBackoffs
            + " waits. Retry the turn after TPM recovers.");
  }
}
