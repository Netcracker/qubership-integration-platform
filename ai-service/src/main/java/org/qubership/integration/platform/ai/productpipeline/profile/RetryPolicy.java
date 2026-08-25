package org.qubership.integration.platform.ai.productpipeline.profile;

/** Technical-failure retry budget and delay limits for a stage. */
public record RetryPolicy(
    int maxTechnicalRetries,
    long defaultDelayMs,
    double backoffCoefficient,
    long maximumDelayMs) {

  public RetryPolicy {
    backoffCoefficient = backoffCoefficient <= 0 ? 2.0 : backoffCoefficient;
    maximumDelayMs = maximumDelayMs <= 0 ? Math.max(defaultDelayMs, 30_000L) : maximumDelayMs;
  }

  /** Compatibility constructor for profiles that define only a fixed retry delay. */
  public RetryPolicy(int maxTechnicalRetries, long defaultDelayMs) {
    this(maxTechnicalRetries, defaultDelayMs, 2.0, Math.max(defaultDelayMs, 30_000L));
  }
}
