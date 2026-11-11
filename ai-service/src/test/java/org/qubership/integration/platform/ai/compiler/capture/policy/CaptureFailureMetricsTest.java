package org.qubership.integration.platform.ai.compiler.capture.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.compiler.capture.CaptureAttemptFeedbackStore;
import org.qubership.integration.platform.ai.compiler.capture.CaptureFailureKind;
import org.qubership.integration.platform.ai.compiler.capture.CaptureValidationException;

class CaptureFailureMetricsTest {

  private CaptureFailureMetrics metrics;
  private CaptureToolOutcomeGateway gateway;

  @BeforeEach
  void setUp() {
    metrics = new CaptureFailureMetrics();
    ToolCallFingerprintStore fingerprintStore = new ToolCallFingerprintStore();
    CaptureAttemptFeedbackStore feedbackStore = new CaptureAttemptFeedbackStore(fingerprintStore);
    gateway =
        new CaptureToolOutcomeGateway(
            new CaptureFailurePolicy(), fingerprintStore, feedbackStore, metrics);
  }

  @Test
  void recordsSoftThenIdenticalSpamPerCapability() {
    Object args = Map.of("scripts", java.util.List.of());

    gateway.onFailure(
        CaptureFeedbackChannel.PATCH,
        "conv-1",
        "cap-script",
        CaptureFailureKind.VALIDATION,
        CaptureFailureClass.CORRECTABLE,
        "repairScriptBodies",
        args,
        "empty");

    assertEquals(1.0d, metrics.count(CaptureFailureMetrics.OUTCOME_SOFT, "cap-script"));
    assertEquals(0.0d, metrics.count(CaptureFailureMetrics.OUTCOME_IDENTICAL_SPAM, "cap-script"));

    try {
      gateway.onFailure(
          CaptureFeedbackChannel.PATCH,
          "conv-1",
          "cap-script",
          CaptureFailureKind.VALIDATION,
          CaptureFailureClass.CORRECTABLE,
          "repairScriptBodies",
          args,
          "empty");
    } catch (CaptureValidationException ignored) {
      // expected
    }

    assertEquals(1.0d, metrics.count(CaptureFailureMetrics.OUTCOME_SOFT, "cap-script"));
    assertEquals(1.0d, metrics.count(CaptureFailureMetrics.OUTCOME_IDENTICAL_SPAM, "cap-script"));
  }

  @Test
  void recordsPermanent() {
    try {
      gateway.onFailure(
          CaptureFeedbackChannel.PATCH,
          "conv-1",
          "cap-patch",
          CaptureFailureKind.VALIDATION,
          CaptureFailureClass.PERMANENT,
          "captureGraphPatch",
          Map.of("propertyPatches", java.util.List.of()),
          "ownership");
    } catch (CaptureValidationException ignored) {
      // expected
    }

    assertEquals(1.0d, metrics.count(CaptureFailureMetrics.OUTCOME_PERMANENT, "cap-patch"));
    assertEquals(0.0d, metrics.count(CaptureFailureMetrics.OUTCOME_SOFT, "cap-patch"));
  }

  @Test
  void recordsOuterRepair() {
    metrics.recordOuterRepair("captureGraphPatch");
    assertEquals(1.0d, metrics.count(CaptureFailureMetrics.OUTCOME_OUTER_REPAIR, "captureGraphPatch"));
  }
}
