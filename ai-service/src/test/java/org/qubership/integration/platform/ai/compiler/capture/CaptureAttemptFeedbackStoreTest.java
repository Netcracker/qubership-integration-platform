package org.qubership.integration.platform.ai.compiler.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Base64;
import java.time.Clock;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.qubership.integration.platform.ai.compiler.capture.policy.CaptureFailureClass;
import org.qubership.integration.platform.ai.compiler.capture.policy.ToolCallFingerprintStore;
import org.qubership.integration.platform.ai.compiler.capture.policy.CaptureToolOutcomeGateway.TypedIssue;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationSessions;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.qubership.integration.platform.ai.productpipeline.capability.RecoveryCause;

class CaptureAttemptFeedbackStoreTest {

  private CaptureAttemptFeedbackStore store;

  @BeforeEach
  void setUp() {
    store = new CaptureAttemptFeedbackStore();
  }

  @Test
  void optionalFailureCarriersPreserveLegacyNullSerializationAndRoundTripExactNativeData() throws Exception {
    var mapper = new ObjectMapper().findAndRegisterModules();
    var old = new org.qubership.integration.platform.ai.productpipeline.capability.CaptureFailureEvidence(
        new CaptureAttemptFeedback(CaptureFailureKind.VALIDATION, "Rejected"), null, null, List.of());
    assertFalse(mapper.valueToTree(old).has("nativeObservation"));
    assertEquals(old, mapper.readValue(mapper.writeValueAsBytes(old),
        org.qubership.integration.platform.ai.productpipeline.capability.CaptureFailureEvidence.class));
    var result = org.qubership.integration.platform.ai.skill.executor.SkillExecutionResult.completed(List.of(), "done");
    assertFalse(mapper.valueToTree(result).has("nativeFailure"));
    assertEquals(result, mapper.readValue(mapper.writeValueAsBytes(result),
        org.qubership.integration.platform.ai.skill.executor.SkillExecutionResult.class));
  }

  @Test
  void exactNativeFailureObservationRequiresItsSessionCapabilityScopeAndReference() {
    var mapper = new ObjectMapper().findAndRegisterModules();
    var blobs = new InMemoryArtifactBlobStore();
    var artifacts = new CompilationArtifacts(blobs, mapper, Clock.systemUTC());
    var sessions = new CompilationSessions(blobs, mapper, Clock.systemUTC());
    var durable = new CaptureAttemptFeedbackStore(new ToolCallFingerprintStore(mapper, artifacts, sessions), artifacts, sessions);
    durable.beginScope("chat", "owner", Map.of("runId", "run", "inputHash", "input-1"));
    String raw = "{\"patch\": { broken ";
    durable.beginRejectedProposal("chat", "owner", "captureGraphPatch", raw, "native-call");
    durable.recordClassifiedPatchFailure("chat", "owner", CaptureFailureKind.VALIDATION,
        CaptureFailureClass.IDENTICAL_SPAM, false, "INVALID_JSON /: Send one valid JSON argument object.");
    var failure = durable.lastPatchFailure("chat", "owner").orElseThrow();
    var before = artifacts.history(sessions.active("chat"), CompilationArtifacts.Kind.CAPTURE_ATTEMPT_FEEDBACK);
    var packet = durable.failureEvidence("chat", "owner", failure);
    org.junit.jupiter.api.Assertions.assertNotNull(packet.nativeObservation());
    assertEquals(raw, packet.nativeObservation().proposal().arguments().asText());
    assertEquals("native-call", packet.nativeObservation().proposal().toolCallId());
    assertEquals(failure, packet.feedback());
    assertEquals(failure.evidenceReference(), packet.rejectedCandidate());
    org.junit.jupiter.api.Assertions.assertNull(durable.failureEvidence("other-chat", "owner", failure).nativeObservation());
    org.junit.jupiter.api.Assertions.assertNull(durable.failureEvidence("chat", "foreign-owner", failure).nativeObservation());
    var foreignReference = new CompilationArtifacts.Reference(failure.evidenceReference().kind(),
        failure.evidenceReference().artifactId(), "foreign-hash");
    org.junit.jupiter.api.Assertions.assertNull(durable.failureEvidence("chat", "owner",
        failure.withEvidenceReference(foreignReference)).nativeObservation());
    org.junit.jupiter.api.Assertions.assertNull(durable.failureEvidence("chat", "owner",
        new CaptureAttemptFeedback(failure.kind(), "Changed summary", failure.failureClass(), false, List.of(),
            "", false, failure.evidenceReference())).nativeObservation());
    durable.beginScope("chat", "owner", Map.of("runId", "run", "inputHash", "input-2"));
    org.junit.jupiter.api.Assertions.assertNull(durable.failureEvidence("chat", "owner", failure).nativeObservation());
    assertEquals(before, artifacts.history(sessions.active("chat"), CompilationArtifacts.Kind.CAPTURE_ATTEMPT_FEEDBACK));
  }

  @Test
  void unmappedTypedFailureAndItsCreditSurviveRestart() {
    var mapper = new ObjectMapper().findAndRegisterModules();
    var blobs = new InMemoryArtifactBlobStore();
    var artifacts = new CompilationArtifacts(blobs, mapper, Clock.systemUTC());
    var sessions = new CompilationSessions(blobs, mapper, Clock.systemUTC());
    var first = new CaptureAttemptFeedbackStore(new ToolCallFingerprintStore(mapper, artifacts, sessions), artifacts, sessions);
    first.beginScope("chat", "http", java.util.Map.of("approved", "source-1"));
    first.beginRejectedProposal("chat", "http", "captureHttpTriggers", "{invalid", "call-1");
    var gateway = new org.qubership.integration.platform.ai.compiler.capture.policy.CaptureToolOutcomeGateway(
        first.fingerprintStore(), first);
    gateway.onTypedPlanFailure("chat", "http", "captureHttpTriggers", new TypedIssue("INVALID_JSON", "/capture", "Bad JSON"));
    var feedback = first.lastPlanFailure("chat").orElseThrow();
    var saved = artifacts.payload(artifacts.get(sessions.active("chat"), feedback.evidenceReference()).orElseThrow(),
        CaptureAttemptFeedbackStore.Observation.class);
    assertEquals("{invalid", saved.proposal().arguments().asText());
    assertEquals("call-1", saved.proposal().toolCallId());
    assertEquals("INVALID_JSON", saved.feedback().issues().getFirst().code());
    var restored = new CaptureAttemptFeedbackStore(new ToolCallFingerprintStore(mapper, artifacts, sessions), artifacts, sessions);
    restored.beginScope("chat", "http", java.util.Map.of("approved", "source-1"));
    assertEquals(feedback.evidenceReference(), restored.lastPlanFailure("chat").orElseThrow().evidenceReference());
    restored.beginRejectedProposal("chat", "http", "captureHttpTriggers", "{different", "call-2");
    var second = new org.qubership.integration.platform.ai.compiler.capture.policy.CaptureToolOutcomeGateway(
        restored.fingerprintStore(), restored);
    org.junit.jupiter.api.Assertions.assertThrows(CaptureValidationException.class, () -> second.onTypedPlanFailure(
        "chat", "http", "captureHttpTriggers", new TypedIssue("INVALID_JSON", "/capture", "Reworded explanation")));
    org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> second.onTypedPlanFailure("chat", "http", "captureHttpTriggers",
        new TypedIssue("MISSING_FIELD", "/capture/endpoints/0/externalRoute", "Bad JSON")));
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

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void nativeRequirementEvidenceReachesRecoveryAfterReloadWithoutWriting(boolean legacyPayload) {
    var mapper = new ObjectMapper().findAndRegisterModules();
    var blobs = new InMemoryArtifactBlobStore();
    var artifacts = new CompilationArtifacts(blobs, mapper, Clock.systemUTC());
    var sessions = new CompilationSessions(blobs, mapper, Clock.systemUTC());
    var first = new CaptureAttemptFeedbackStore(new ToolCallFingerprintStore(mapper, artifacts, sessions),
        artifacts, sessions);
    var issues = List.of(new TypedIssue("INVALID_REQUESTED_PROPERTY",
        "/draft/facts/http-settings/elementIntent/requestedProperties/roles", "Use an array of role names.",
        "http-settings", mapper.valueToTree(Map.of("expectedType", "array"))),
        new TypedIssue("UNKNOWN_INTERACTION_REFERENCE", "/draft/facts/2/interactionIds/0",
            "Target must name an accepted interaction.", "context"));
    if (legacyPayload) {
      var legacyFeedback = new CaptureAttemptFeedback(CaptureFailureKind.VALIDATION, "Rejected native fields",
          CaptureFailureClass.IDENTICAL_SPAM, false, List.of(), "", true);
      var payload = mapper.<com.fasterxml.jackson.databind.node.ObjectNode>valueToTree(
          new CaptureAttemptFeedbackStore.RequirementFeedback(
              new CaptureAttemptFeedbackStore.RequirementRejection(legacyFeedback, issues)));
      ((com.fasterxml.jackson.databind.node.ObjectNode) payload.at("/rejection/feedback"))
          .remove(List.of("issues", "evidenceReference"));
      artifacts.append(new CompilationArtifacts.AppendCommand(sessions.active("chat"),
          CompilationArtifacts.Kind.REQUIREMENT_CAPTURE_FEEDBACK, "1", "requirement-capture-feedback", "1",
          payload, List.of(), null));
    } else {
      first.recordClassifiedRequirementFailure("chat", CaptureFailureKind.VALIDATION,
          CaptureFailureClass.IDENTICAL_SPAM, false, "Rejected native fields", issues, true);
    }
    var saved = artifacts.latest(sessions.active("chat"),
        CompilationArtifacts.Kind.REQUIREMENT_CAPTURE_FEEDBACK).orElseThrow();
    var beforeReads = blobSnapshot(blobs);
    var original = first.lastRequirementFailure("chat").orElseThrow();
    var reloadedArtifacts = new CompilationArtifacts(blobs, mapper, Clock.systemUTC());
    var reloadedSessions = new CompilationSessions(blobs, mapper, Clock.systemUTC());
    var restored = new CaptureAttemptFeedbackStore(
        new ToolCallFingerprintStore(mapper, reloadedArtifacts, reloadedSessions), reloadedArtifacts, reloadedSessions);
    var feedback = restored.lastRequirementFailure("chat").orElseThrow();
    assertEquals(original, feedback);
    assertEquals(saved.reference(), feedback.evidenceReference());
    assertEquals(issues, feedback.issues());
    var cause = RecoveryCause.captureFailure(feedback, List.of(), null);
    assertTrue(cause.noProgress());
    assertEquals(issues.stream().map(TypedIssue::code).toList(),
        cause.findings().stream().map(finding -> finding.code()).toList());
    assertEquals(issues.stream().map(TypedIssue::entityId).toList(),
        cause.findings().stream().map(finding -> finding.originRecordId()).toList());
    assertEquals(issues.stream().map(TypedIssue::path).toList(),
        cause.findings().stream().map(finding -> finding.fieldPointer()).toList());
    assertEquals(issues, cause.captureFailure().feedback().issues());
    assertEquals(saved.reference(), cause.captureFailure().feedback().evidenceReference());
    assertEquals(saved.reference(), restored.lastRequirementFeedbackReference("chat").orElseThrow());
    assertEquals(beforeReads, blobSnapshot(blobs));
  }

  private static Map<String, String> blobSnapshot(InMemoryArtifactBlobStore blobs) {
    return blobs.list("").stream().collect(java.util.stream.Collectors.toMap(key -> key, key -> {
      var blob = blobs.getVersioned(key).orElseThrow();
      return blob.version() + ":" + Base64.getEncoder().encodeToString(blob.content());
    }));
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
