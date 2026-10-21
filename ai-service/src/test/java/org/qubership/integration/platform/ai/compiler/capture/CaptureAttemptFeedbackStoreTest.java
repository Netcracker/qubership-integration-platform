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
  void patchTraceIdsDoNotChangeTheMutationAndCleanAliasesRetainHistory() {
    store.beginScope("chat", "skill", java.util.Map.of("run", "r1", "inputs", "i1"));
    store.beginProposal("chat", "skill", "captureGraphPatch", java.util.Map.of(
        "patchId", "first", "rationale", "first explanation", "value", " A "));
    store.recordClassifiedPatchFailure("chat", "skill", CaptureFailureKind.VALIDATION,
        CaptureFailureClass.CORRECTABLE, true, "Invalid mutation");
    store.beginScope("clean-alias", "skill", java.util.Map.of("run", "r1", "inputs", "i1"), "chat");
    org.junit.jupiter.api.Assertions.assertThrows(CaptureNoProgressException.class,
        () -> store.beginProposal("clean-alias", "skill", "captureGraphPatch", java.util.Map.of(
            "patchId", "new-id", "rationale", "new explanation", "value", " A ")));
    assertTrue(store.lastPatchFailure("clean-alias", "skill").orElseThrow().noProgress());
    store.beginProposal("clean-alias", "skill", "captureGraphPatch", java.util.Map.of(
        "patchId", "new-id", "rationale", "new explanation", "value", "A"));
    store.beginScope("chat", "skill", java.util.Map.of("run", "r2", "inputs", "i1"));
    store.beginProposal("chat", "skill", "captureGraphPatch", java.util.Map.of(
        "patchId", "first", "rationale", "first explanation", "value", " A "));
  }

  @Test
  void genericProposalFeedbackSurvivesReconstructionAndClearKeepsHistory() {
    var mapper = new ObjectMapper().findAndRegisterModules();
    var blobs = new InMemoryArtifactBlobStore();
    var artifacts = new CompilationArtifacts(blobs, mapper, Clock.systemUTC());
    var sessions = new CompilationSessions(blobs, mapper, Clock.systemUTC());
    var first = new CaptureAttemptFeedbackStore(new ToolCallFingerprintStore(mapper, artifacts, sessions), artifacts, sessions);
    first.beginScope("chat", "skill", java.util.Map.of("base", "v1"));
    first.beginProposal("chat", "skill", "capture", java.util.Map.of("value", "A"));
    first.recordClassifiedPatchFailure("chat", "skill", CaptureFailureKind.VALIDATION,
        CaptureFailureClass.CORRECTABLE, true, "Invalid A");
    var captured = first.lastPatchFailure("chat", "skill").orElseThrow();
    assertEquals(CompilationArtifacts.Kind.CAPTURE_ATTEMPT_FEEDBACK, captured.evidenceReference().kind());
    first.recordPlanValidationFailure("chat", "Invalid A");
    assertEquals(captured.evidenceReference(), first.lastPatchFailure("chat", "skill").orElseThrow().evidenceReference());
    var saved = artifacts.get(sessions.active("chat"), captured.evidenceReference()).orElseThrow();
    assertEquals("A", artifacts.payload(saved, CaptureAttemptFeedbackStore.Observation.class)
        .proposal().arguments().path("value").asText());
    var restored = new CaptureAttemptFeedbackStore(new ToolCallFingerprintStore(mapper, artifacts, sessions), artifacts, sessions);
    assertTrue(restored.lastPatchFailure("chat", "skill").isPresent());
    assertEquals(captured.evidenceReference(), restored.lastPatchFailure("chat", "skill").orElseThrow().evidenceReference());
    restored.clearPatch("chat", "skill");
    assertTrue(restored.lastPatchFailure("chat", "skill").isEmpty());
    restored.beginScope("chat", "skill", java.util.Map.of("base", "v1"));
    assertTrue(restored.repairHistory("chat", "skill").contains("Invalid A"));
    org.junit.jupiter.api.Assertions.assertThrows(CaptureValidationException.class,
        () -> restored.beginProposal("chat", "skill", "capture", java.util.Map.of("value", "A")));
    assertTrue(restored.lastPatchFailure("other-chat", "skill").isEmpty());
  }

  @Test
  void proposalHistoryBlocksNonAdjacentRepeatsButAdmitsChangedInputAndArguments() {
    store.beginScope("chat", "skill", java.util.Map.of("base", "v1", "contract", "c1"));
    store.beginProposal("chat", "skill", "capture", java.util.Map.of("value", " A "));
    store.recordClassifiedPatchFailure("chat", "skill", CaptureFailureKind.VALIDATION,
        CaptureFailureClass.CORRECTABLE, true, "Invalid A");
    store.beginProposal("chat", "skill", "capture", java.util.Map.of("value", "B"));
    store.recordClassifiedPatchFailure("chat", "skill", CaptureFailureKind.VALIDATION,
        CaptureFailureClass.CORRECTABLE, true, "Invalid B");
    org.junit.jupiter.api.Assertions.assertThrows(CaptureValidationException.class,
        () -> store.beginProposal("chat", "skill", "capture", java.util.Map.of("value", " A ")));
    assertFalse(store.lastPatchFailure("chat", "skill").orElseThrow().outerAllowed());
    store.beginProposal("chat", "skill", "capture", java.util.Map.of("value", "A"));
    store.beginScope("chat", "skill", java.util.Map.of("base", "v2", "contract", "c1"));
    store.beginProposal("chat", "skill", "capture", java.util.Map.of("value", " A "));
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
