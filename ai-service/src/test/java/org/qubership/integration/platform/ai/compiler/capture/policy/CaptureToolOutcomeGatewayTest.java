package org.qubership.integration.platform.ai.compiler.capture.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.compiler.capture.CaptureAttemptFeedback;
import org.qubership.integration.platform.ai.compiler.capture.CaptureAttemptFeedbackStore;
import org.qubership.integration.platform.ai.compiler.capture.CaptureFailureKind;
import org.qubership.integration.platform.ai.compiler.capture.CaptureFieldHint;
import org.qubership.integration.platform.ai.compiler.capture.CaptureValidationException;

class CaptureToolOutcomeGatewayTest {

  private ToolCallFingerprintStore fingerprintStore;
  private CaptureAttemptFeedbackStore feedbackStore;
  private CaptureToolOutcomeGateway gateway;

  @BeforeEach
  void setUp() {
    fingerprintStore = new ToolCallFingerprintStore(new ObjectMapper());
    feedbackStore = new CaptureAttemptFeedbackStore(fingerprintStore);
    gateway = new CaptureToolOutcomeGateway(new CaptureFailurePolicy(), fingerprintStore, feedbackStore);
  }

  @Test
  void onFailurePersistsFieldHintsOnPlanChannel() {
    List<CaptureFieldHint> hints =
        List.of(new CaptureFieldHint("patternId", "elementSkeleton.selectedPatternId", "GP-01"));

    gateway.onFailure(
        CaptureFeedbackChannel.PLAN,
        "conv-1",
        null,
        CaptureFailureKind.VALIDATION,
        CaptureFailureClass.CORRECTABLE,
        "captureSelectedPattern",
        Map.of("patternId", ""),
        "patternId is required",
        hints);

    CaptureAttemptFeedback feedback = feedbackStore.lastPlanFailure("conv-1").orElseThrow();
    assertEquals(hints, feedback.fieldHints());
  }

  @Test
  void softThenCveOnSameFingerprint() {
    Object args = Map.of("scripts", java.util.List.of());

    String soft =
        gateway.onFailure(
            CaptureFeedbackChannel.PATCH,
            "conv-1",
            "cap-1",
            CaptureFailureKind.VALIDATION,
            CaptureFailureClass.CORRECTABLE,
            "repairScriptBodies",
            args,
            "scripts are required");

    assertEquals("scripts are required", soft);
    CaptureAttemptFeedback first =
        feedbackStore.lastPatchFailure("conv-1", "cap-1").orElseThrow();
    assertEquals(CaptureFailureClass.CORRECTABLE, first.failureClass());
    assertTrue(first.outerAllowed());

    CaptureValidationException cve =
        assertThrows(
            CaptureValidationException.class,
            () ->
                gateway.onFailure(
                    CaptureFeedbackChannel.PATCH,
                    "conv-1",
                    "cap-1",
                    CaptureFailureKind.VALIDATION,
                    CaptureFailureClass.CORRECTABLE,
                    "repairScriptBodies",
                    args,
                    "scripts are required"));

    assertTrue(cve.getMessage().contains("Repeated capture validation failure"));
    CaptureAttemptFeedback second =
        feedbackStore.lastPatchFailure("conv-1", "cap-1").orElseThrow();
    assertEquals(CaptureFailureClass.IDENTICAL_SPAM, second.failureClass());
    assertTrue(second.outerAllowed());
  }

  @Test
  void rationaleOnlyChangeDoesNotGrantSecondSoft() {
    Object firstArgs = Map.of("scripts", java.util.List.of(), "rationale", "a");
    Object secondArgs = Map.of("scripts", java.util.List.of(), "rationale", "b");

    gateway.onFailure(
        CaptureFeedbackChannel.PATCH,
        "conv-1",
        "cap-1",
        CaptureFailureKind.VALIDATION,
        CaptureFailureClass.CORRECTABLE,
        "repairScriptBodies",
        firstArgs,
        "empty");

    assertThrows(
        CaptureValidationException.class,
        () ->
            gateway.onFailure(
                CaptureFeedbackChannel.PATCH,
                "conv-1",
                "cap-1",
                CaptureFailureKind.VALIDATION,
                CaptureFailureClass.CORRECTABLE,
                "repairScriptBodies",
                secondArgs,
                "empty"));
  }

  @Test
  void permanentNeverSoft() {
    CaptureValidationException cve =
        assertThrows(
            CaptureValidationException.class,
            () ->
                gateway.onFailure(
                    CaptureFeedbackChannel.PATCH,
                    "conv-1",
                    "cap-1",
                    CaptureFailureKind.VALIDATION,
                    CaptureFailureClass.PERMANENT,
                    "captureGraphPatch",
                    Map.of("propertyPatches", java.util.List.of()),
                    "script ownership"));

    assertEquals("script ownership", cve.getMessage());
    CaptureAttemptFeedback feedback =
        feedbackStore.lastPatchFailure("conv-1", "cap-1").orElseThrow();
    assertEquals(CaptureFailureClass.PERMANENT, feedback.failureClass());
    assertEquals(false, feedback.outerAllowed());
    assertEquals(0, fingerprintStore.softCreditsUsed("conv-1"));
  }

  @Test
  void acceptedAndDuplicateStillCve() {
    assertThrows(
        CaptureValidationException.class, () -> gateway.onTerminalAccept("captured ok"));
    assertThrows(
        CaptureValidationException.class, () -> gateway.onTerminalDuplicate("already captured"));
  }

  @Test
  void multiFingerprintSoftCreditsAreIndependent() {
    String soft1 =
        gateway.onFailure(
            CaptureFeedbackChannel.PATCH,
            "conv-1",
            "cap-1",
            CaptureFailureKind.VALIDATION,
            CaptureFailureClass.CORRECTABLE,
            "repairScriptBodies",
            Map.of("scripts", java.util.List.of(Map.of("id", "1"))),
            "fail-1");
    String soft2 =
        gateway.onFailure(
            CaptureFeedbackChannel.PATCH,
            "conv-1",
            "cap-1",
            CaptureFailureKind.VALIDATION,
            CaptureFailureClass.CORRECTABLE,
            "repairScriptBodies",
            Map.of("scripts", java.util.List.of(Map.of("id", "2"))),
            "fail-2");
    String soft3 =
        gateway.onFailure(
            CaptureFeedbackChannel.PATCH,
            "conv-1",
            "cap-1",
            CaptureFailureKind.VALIDATION,
            CaptureFailureClass.CORRECTABLE,
            "repairScriptBodies",
            Map.of("scripts", java.util.List.of(Map.of("id", "3"))),
            "fail-3");

    assertEquals("fail-1", soft1);
    assertEquals("fail-2", soft2);
    assertEquals("fail-3", soft3);
    assertEquals(3, fingerprintStore.softCreditsUsed("conv-1"));
    // Bound: sequential=3 cuts further softs at the agent layer; policy itself allows distinct fps.
  }
}
