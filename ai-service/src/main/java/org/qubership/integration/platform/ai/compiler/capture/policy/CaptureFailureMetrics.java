package org.qubership.integration.platform.ai.compiler.capture.policy;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Micrometer counters for ADR 0003 capture / tool-failure outcomes.
 *
 * <p>Metric: {@code ai.capture.failure} with tags {@code outcome} and {@code capability}.
 */
@ApplicationScoped
public class CaptureFailureMetrics {

  public static final String METRIC_NAME = "ai.capture.failure";
  public static final String TAG_OUTCOME = "outcome";
  public static final String TAG_CAPABILITY = "capability";

  public static final String OUTCOME_SOFT = "soft";
  public static final String OUTCOME_IDENTICAL_SPAM = "identical_spam";
  public static final String OUTCOME_PERMANENT = "permanent";
  public static final String OUTCOME_OUTER_REPAIR = "outer_repair";

  private final MeterRegistry meterRegistry;

  @Inject
  public CaptureFailureMetrics(MeterRegistry meterRegistry) {
    this.meterRegistry = meterRegistry;
  }

  /** Test helper without CDI. */
  public CaptureFailureMetrics() {
    this(new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
  }

  public void recordDecision(CaptureFailureDecision decision, String capabilityId) {
    if (decision == null) {
      return;
    }
    if (decision.softToolResult()) {
      increment(OUTCOME_SOFT, capabilityId);
      return;
    }
    CaptureFailureClass failureClass = decision.failureClass();
    if (failureClass == CaptureFailureClass.IDENTICAL_SPAM) {
      increment(OUTCOME_IDENTICAL_SPAM, capabilityId);
      return;
    }
    if (failureClass == CaptureFailureClass.PERMANENT) {
      increment(OUTCOME_PERMANENT, capabilityId);
    }
  }

  public void recordOuterRepair(String capabilityOrTool) {
    increment(OUTCOME_OUTER_REPAIR, capabilityOrTool);
  }

  public double count(String outcome, String capabilityId) {
    Counter counter =
        meterRegistry
            .find(METRIC_NAME)
            .tag(TAG_OUTCOME, outcome)
            .tag(TAG_CAPABILITY, normalizeCapability(capabilityId))
            .counter();
    return counter == null ? 0.0d : counter.count();
  }

  private void increment(String outcome, String capabilityId) {
    Counter.builder(METRIC_NAME)
        .description("Capture tool-failure policy outcomes (ADR 0003)")
        .tag(TAG_OUTCOME, outcome)
        .tag(TAG_CAPABILITY, normalizeCapability(capabilityId))
        .register(meterRegistry)
        .increment();
  }

  private static String normalizeCapability(String capabilityId) {
    if (capabilityId == null || capabilityId.isBlank()) {
      return "unknown";
    }
    return capabilityId.strip();
  }
}
