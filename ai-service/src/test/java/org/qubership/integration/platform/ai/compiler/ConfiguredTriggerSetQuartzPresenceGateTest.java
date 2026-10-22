package org.qubership.integration.platform.ai.compiler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.qipknowledge.artifact.ConfiguredTrigger;
import org.qubership.integration.platform.ai.qipknowledge.artifact.ConfiguredTriggerSet;
import org.qubership.integration.platform.ai.qipknowledge.artifact.ElementRole;
import org.qubership.integration.platform.ai.qipknowledge.artifact.ElementSkeleton;

class ConfiguredTriggerSetQuartzPresenceGateTest {

  @Test
  void acceptsWhenSkeletonDoesNotRequireQuartz() {
    ElementSkeleton skeleton = httpOnlySkeleton();
    ConfiguredTriggerSet capture = httpOnlyCapture();

    assertNull(ConfiguredTriggerSetQuartzPresenceGate.validate(skeleton, capture));
    assertFalse(ConfiguredTriggerSetQuartzPresenceGate.skeletonRequiresQuartz(skeleton));
  }

  @Test
  void rejectsHttpOnlyWhenSkeletonRequiresQuartz() {
    ElementSkeleton skeleton = dualTriggerSkeleton();
    ConfiguredTriggerSet capture = httpOnlyCapture();

    assertEquals(
        ConfiguredTriggerSetQuartzPresenceGate.MISSING_QUARTZ_TRIGGER_MESSAGE,
        ConfiguredTriggerSetQuartzPresenceGate.validate(skeleton, capture));
    assertTrue(ConfiguredTriggerSetQuartzPresenceGate.skeletonRequiresQuartz(skeleton));
    assertFalse(ConfiguredTriggerSetQuartzPresenceGate.captureHasQuartz(capture));
  }

  @Test
  void acceptsDualTriggerWhenSkeletonRequiresQuartz() {
    ElementSkeleton skeleton = dualTriggerSkeleton();
    ConfiguredTriggerSet capture = dualTriggerCapture();

    assertNull(ConfiguredTriggerSetQuartzPresenceGate.validate(skeleton, capture));
    assertTrue(ConfiguredTriggerSetQuartzPresenceGate.captureHasQuartz(capture));
  }

  @Test
  void skipsGateWhenSkeletonMissing() {
    assertNull(ConfiguredTriggerSetQuartzPresenceGate.validate(null, httpOnlyCapture()));
  }

  private static ElementSkeleton httpOnlySkeleton() {
    return new ElementSkeleton(
        1,
        "GP-01",
        List.of("http-entry"),
        List.of(new ElementRole("http-entry", "http-trigger", null, 1, 1)),
        List.of(),
        List.of(),
        List.of(),
        List.of());
  }

  private static ElementSkeleton dualTriggerSkeleton() {
    return new ElementSkeleton(
        1,
        "GP-01",
        List.of("http-entry", "quartz-entry"),
        List.of(
            new ElementRole("http-entry", "http-trigger", null, 1, 1),
            new ElementRole("quartz-entry", "quartz-scheduler", null, 1, 1)),
        List.of(),
        List.of(),
        List.of(),
        List.of());
  }

  private static ConfiguredTriggerSet httpOnlyCapture() {
    return new ConfiguredTriggerSet(
        1,
        List.of(
            new ConfiguredTrigger(
                "http-entry",
                "http-trigger-1",
                "http-trigger",
                "Customer API",
                List.of(new PlanProperty("contextPath", "/api/customers")))),
        List.of(),
        List.of());
  }

  private static ConfiguredTriggerSet dualTriggerCapture() {
    return new ConfiguredTriggerSet(
        1,
        List.of(
            new ConfiguredTrigger(
                "http-entry",
                "http-trigger-1",
                "http-trigger",
                "Customer API",
                List.of(new PlanProperty("contextPath", "/api/customers"))),
            new ConfiguredTrigger(
                "quartz-entry",
                "quartz-scheduler-1",
                "quartz-scheduler",
                "Hourly schedule",
                List.of())),
        List.of(),
        List.of());
  }
}
