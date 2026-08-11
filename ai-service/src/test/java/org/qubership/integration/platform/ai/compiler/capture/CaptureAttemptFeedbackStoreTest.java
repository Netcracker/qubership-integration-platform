package org.qubership.integration.platform.ai.compiler.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.compiler.capture.policy.CaptureFailureClass;

class CaptureAttemptFeedbackStoreTest {

  private CaptureAttemptFeedbackStore store;

  @BeforeEach
  void setUp() {
    store = new CaptureAttemptFeedbackStore();
  }

  @Test
  void classifiedPlanFailurePersistsFieldHints() {
    List<CaptureFieldHint> hints =
        List.of(new CaptureFieldHint("patternId", "elementSkeleton.selectedPatternId", "GP-01"));

    store.recordClassifiedPlanFailure(
        "conv-1",
        CaptureFailureKind.VALIDATION,
        CaptureFailureClass.CORRECTABLE,
        true,
        "patternId is required",
        hints);

    CaptureAttemptFeedback feedback = store.lastPlanFailure("conv-1").orElseThrow();
    assertEquals(hints, feedback.fieldHints());
  }

  @Test
  void classifiedPlanFailureWithoutHintsDefaultsToEmptyList() {
    store.recordClassifiedPlanFailure(
        "conv-1",
        CaptureFailureKind.VALIDATION,
        CaptureFailureClass.CORRECTABLE,
        true,
        "patternId is required");

    assertTrue(store.lastPlanFailure("conv-1").orElseThrow().fieldHints().isEmpty());
  }

  @Test
  void recordsAndClearsPlanFailure() {
    assertFalse(
        store.recordPlanFailure("conv-1", CaptureFailureKind.VALIDATION, "Plan validation failed"));

    assertTrue(store.lastPlanFailure("conv-1").isPresent());
    assertEquals(CaptureFailureKind.VALIDATION, store.lastPlanFailure("conv-1").get().kind());

    store.clearPlan("conv-1");
    assertTrue(store.lastPlanFailure("conv-1").isEmpty());
  }

  @Test
  void reportsRepeatedPlanValidationEvenWhenMessageChanges() {
    assertFalse(store.recordPlanFailure("conv-1", CaptureFailureKind.VALIDATION, "bad edge A"));

    assertTrue(store.recordPlanFailure("conv-1", CaptureFailureKind.VALIDATION, "bad edge B"));
  }

  @Test
  void recordsPatchFailurePerCapability() {
    assertFalse(
        store.recordPatchFailure(
            "conv-1", "cip-security-generator", CaptureFailureKind.VALIDATION, "shape error"));

    assertTrue(store.lastPatchFailure("conv-1", "cip-security-generator").isPresent());
    assertTrue(store.lastPatchFailure("conv-1", "other-skill").isEmpty());

    store.clearPatch("conv-1", "cip-security-generator");
    assertTrue(store.lastPatchFailure("conv-1", "cip-security-generator").isEmpty());
  }

  @Test
  void reportsRepeatedPatchValidationPerCapability() {
    assertFalse(
        store.recordPatchValidationFailure("conv-1", "cip-script-generator", "missing scripts"));
    assertTrue(
        store.recordPatchValidationFailure("conv-1", "cip-script-generator", "missing scripts"));
    assertFalse(
        store.recordPatchValidationFailure("conv-1", "cip-routing-generator", "missing scripts"));
  }

  @Test
  void reportsRepeatedPatchConversionOnlyWhenSummaryMatches() {
    assertFalse(
        store.recordPatchConversionFailure(
            "conv-1", "cip-trigger-generator", "invalid HTTP method"));
    assertFalse(
        store.recordPatchConversionFailure(
            "conv-1", "cip-trigger-generator", "invalid path value"));
    assertTrue(
        store.recordPatchConversionFailure(
            "conv-1", "cip-trigger-generator", "invalid path value"));
    assertFalse(
        store.recordPatchConversionFailure(
            "conv-1", "cip-routing-generator", "invalid path value"));
  }

  @Test
  void clearAllRemovesPlanAndPatchFailures() {
    store.recordPlanFailure("conv-1", CaptureFailureKind.CONVERSION, "conversion");
    store.recordPatchFailure("conv-1", "skill-a", CaptureFailureKind.VALIDATION, "validation");

    store.clearAll("conv-1");

    assertTrue(store.lastPlanFailure("conv-1").isEmpty());
    assertTrue(store.lastPatchFailure("conv-1", "skill-a").isEmpty());
  }
}
