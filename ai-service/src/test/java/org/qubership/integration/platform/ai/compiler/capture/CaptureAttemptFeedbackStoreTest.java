package org.qubership.integration.platform.ai.compiler.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.time.Clock;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.compiler.capture.policy.CaptureFailureClass;
import org.qubership.integration.platform.ai.compiler.capture.policy.ToolCallFingerprintStore;
import org.qubership.integration.platform.ai.compiler.capture.policy.CaptureToolOutcomeGateway.TypedIssue;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationSessions;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;

class CaptureAttemptFeedbackStoreTest {

  private CaptureAttemptFeedbackStore store;

  @BeforeEach
  void setUp() {
    store = new CaptureAttemptFeedbackStore();
  }

  @Test
  void requirementFailureClassificationSurvivesReconstructionAndExplicitClear() {
    var mapper = new ObjectMapper().findAndRegisterModules();
    var blobs = new InMemoryArtifactBlobStore();
    var artifacts = new CompilationArtifacts(blobs, mapper, Clock.systemUTC());
    var sessions = new CompilationSessions(blobs, mapper, Clock.systemUTC());
    var first = new CaptureAttemptFeedbackStore(new ToolCallFingerprintStore(mapper, artifacts, sessions),
        artifacts, sessions);
    var issues = List.of(new TypedIssue("INVALID_REQUESTED_PROPERTY", "/facts/config/property/method", "Use a supported key."));
    first.recordClassifiedRequirementFailure("chat", CaptureFailureKind.VALIDATION,
        CaptureFailureClass.IDENTICAL_SPAM, false, "Repeated property rejection", issues);

    var restored = new CaptureAttemptFeedbackStore(new ToolCallFingerprintStore(mapper, artifacts, sessions),
        new CompilationArtifacts(blobs, mapper, Clock.systemUTC()),
        new CompilationSessions(blobs, mapper, Clock.systemUTC()));
    assertTrue(restored.lastRequirementRejection("chat").isPresent());
    assertEquals(first.lastRequirementRejection("chat"), restored.lastRequirementRejection("chat"));
    restored.clearRequirement("chat");
    assertTrue(new CaptureAttemptFeedbackStore(new ToolCallFingerprintStore(mapper, artifacts, sessions),
        artifacts, sessions).lastRequirementRejection("chat").isEmpty());
    assertEquals(2, artifacts.history(sessions.active("chat"),
        CompilationArtifacts.Kind.REQUIREMENT_CAPTURE_FEEDBACK).size());
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
  void changedPlanValidationMessageGetsAnotherAttempt() {
    assertFalse(store.recordPlanFailure("conv-1", CaptureFailureKind.VALIDATION, "bad edge A"));

    assertFalse(store.recordPlanFailure("conv-1", CaptureFailureKind.VALIDATION, "bad edge B"));
  }

  @Test
  void planValidationRepeatsOnlyForTheSamePayloadAndMessage() {
    assertFalse(store.recordPlanValidationFailure("conv-1", "bad edge", "payload A"));
    assertFalse(store.recordPlanValidationFailure("conv-1", "bad edge", "payload B"));
    assertTrue(store.recordPlanValidationFailure("conv-1", "bad edge", "payload B"));
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
