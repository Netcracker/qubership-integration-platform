package org.qubership.integration.platform.ai.plan.workdocument;

import java.time.Duration;
import org.qubership.integration.platform.ai.productpipeline.profile.RetryPolicy;
import org.qubership.integration.platform.ai.productpipeline.stage.StageDecision;

/**
 * Reads the technical retry delay from the existing stage decision. Filling does not sleep and
 * does not start another retry loop.
 */
public final class FillingRuntimeDecision {

  private FillingRuntimeDecision() {}

  public static long retryDelayMs(RetryPolicy policy) {
    long configured = policy == null ? 0L : policy.defaultDelayMs();
    return new StageDecision.Retry("filling", Duration.ofMillis(Math.max(configured, 0L)))
        .delay()
        .toMillis();
  }
}
