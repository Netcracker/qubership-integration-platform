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
import org.junit.jupiter.params.provider.CsvSource;
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
  void acceptedPatchRetiresRecoveryHistoryAfterReconstructionAndPreservesAudit() {
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
    var resumed = new CaptureAttemptFeedbackStore(new ToolCallFingerprintStore(mapper, artifacts, sessions), artifacts, sessions);
    resumed.beginScope("chat", "skill", java.util.Map.of("base", "v1"));
    assertEquals("", resumed.repairHistory("chat", "skill"));
    assertTrue(resumed.isResolved("chat", captured.evidenceReference()), captured.evidenceReference()::toString);
    org.junit.jupiter.api.Assertions.assertDoesNotThrow(
        () -> resumed.beginProposal("chat", "skill", "capture", java.util.Map.of("value", "A")));
    assertEquals(3, artifacts.history(sessions.active("chat"),
        CompilationArtifacts.Kind.CAPTURE_ATTEMPT_FEEDBACK).size());
    assertEquals("A", artifacts.payload(saved, CaptureAttemptFeedbackStore.Observation.class)
        .proposal().arguments().path("value").asText());
    assertTrue(restored.lastPatchFailure("other-chat", "skill").isEmpty());
  }

  @Test
  void clearAllRetiresPlanFailuresFromEachCapability() {
    rejectScopedPlan("first");
    rejectScopedPlan("second");

    store.clearAll("chat");

    org.junit.jupiter.api.Assertions.assertAll(
        () -> assertEquals("", store.repairHistory("chat", "first")),
        () -> assertEquals("", store.repairHistory("chat", "second")));
  }

  @Test
  void resetAllKeepsUnresolvedPlanFailuresFromEachCapability() throws Exception {
    rejectScopedPlan("first");
    rejectScopedPlan("second");

    store.resetAll("chat");

    var mapper = new ObjectMapper();
    var first = mapper.readTree(store.repairHistory("chat", "first")).path("attempts");
    var second = mapper.readTree(store.repairHistory("chat", "second")).path("attempts");
    org.junit.jupiter.api.Assertions.assertAll(
        () -> assertEquals(1, first.size(), first::toString),
        () -> assertEquals("first", first.get(0).path("capabilityId").asText()),
        () -> assertEquals(1, second.size(), second::toString),
        () -> assertEquals("second", second.get(0).path("capabilityId").asText()));
  }

  private void rejectScopedPlan(String capability) {
    store.beginScope("chat", capability, Map.of("source", capability));
    store.beginProposal("chat", capability, "capturePlan", Map.of("value", "bad"));
    store.recordClassifiedPlanFailure("chat", CaptureFailureKind.VALIDATION,
        CaptureFailureClass.CORRECTABLE, true, "Invalid plan");
  }

  @Test
  void acceptedPatchKeepsUnresolvedValidationInTheSameCapability() throws Exception {
    var mapper = new ObjectMapper();
    store.beginScope("chat", "skill", Map.of("inputs", "source-1"));
    store.beginProposal("chat", "skill", "captureGraphPatch", Map.of("value", "bad-patch"));
    store.recordClassifiedPatchFailure("chat", "skill", CaptureFailureKind.VALIDATION,
        CaptureFailureClass.CORRECTABLE, true, "Patch rejected");
    store.beginProposal("chat", "skill", "captureValidationResult", Map.of("value", "bad-report"));
    store.recordClassifiedValidationFailure("chat", "skill", CaptureFailureKind.VALIDATION,
        CaptureFailureClass.CORRECTABLE, true, "Validation rejected");
    store.clearPatch("chat", "skill");

    var attempts = mapper.readTree(store.repairHistory("chat", "skill")).path("attempts");
    assertEquals(1, attempts.size(), attempts::toString);
    assertEquals("Validation rejected", attempts.get(0).path("feedback").path("summary").asText());
    org.junit.jupiter.api.Assertions.assertThrows(CaptureNoProgressException.class,
        () -> store.beginProposal("chat", "skill", "captureValidationResult", Map.of("value", "bad-report")));
  }

  @Test
  void acceptedPatchKeepsUnresolvedSiblingCapability() throws Exception {
    var mapper = new ObjectMapper();
    store.beginScope("chat", "first", Map.of("inputs", "source-1"));
    store.beginProposal("chat", "first", "captureGraphPatch", Map.of("value", "bad-first"));
    store.recordClassifiedPatchFailure("chat", "first", CaptureFailureKind.VALIDATION,
        CaptureFailureClass.CORRECTABLE, true, "First rejected");
    store.beginScope("chat", "sibling", Map.of("inputs", "source-1"));
    store.beginProposal("chat", "sibling", "captureGraphPatch", Map.of("value", "bad-sibling"));
    store.recordClassifiedPatchFailure("chat", "sibling", CaptureFailureKind.VALIDATION,
        CaptureFailureClass.CORRECTABLE, true, "Sibling rejected");
    store.clearPatch("chat", "first");

    assertEquals("", store.repairHistory("chat", "first"));
    var attempts = mapper.readTree(store.repairHistory("chat", "sibling")).path("attempts");
    assertEquals(1, attempts.size(), attempts::toString);
    assertEquals("Sibling rejected", attempts.get(0).path("feedback").path("summary").asText());
  }

  @Test
  void newRejectionAfterAcceptanceContainsOnlyTheNewActiveFailure() throws Exception {
    var mapper = new ObjectMapper();
    store.beginScope("chat", "skill", Map.of("inputs", "source-1"));
    store.beginProposal("chat", "skill", "captureGraphPatch", Map.of("value", "bad-old"));
    store.recordClassifiedPatchFailure("chat", "skill", CaptureFailureKind.VALIDATION,
        CaptureFailureClass.IDENTICAL_SPAM, false, "Old rejection");
    store.clearPatch("chat", "skill");
    store.beginProposal("chat", "skill", "captureGraphPatch", Map.of("value", "bad-new"));
    store.recordClassifiedPatchFailure("chat", "skill", CaptureFailureKind.VALIDATION,
        CaptureFailureClass.CORRECTABLE, true, "New rejection");

    var feedback = store.lastPatchFailure("chat", "skill").orElseThrow();
    var attempts = mapper.readTree(feedback.repairHistoryJson()).path("attempts");
    assertEquals(1, attempts.size(), attempts::toString);
    assertEquals("New rejection", attempts.get(0).path("feedback").path("summary").asText());
    assertEquals(0, store.identicalFailureCount("chat", "skill"));
  }

  @Test
  void invocationResetKeepsUnresolvedHistoryAndDuplicateGuard() {
    store.beginScope("chat", "skill", Map.of("source", "one"));
    store.beginProposal("chat", "skill", "captureGraphPatch", Map.of("value", "bad"));
    store.recordClassifiedPatchFailure("chat", "skill", CaptureFailureKind.VALIDATION,
        CaptureFailureClass.CORRECTABLE, true, "Still rejected");
    store.resetAll("chat");

    assertEquals(java.util.Optional.empty(), store.lastPatchFailure("chat", "skill"));
    assertTrue(store.repairHistory("chat", "skill").contains("Still rejected"),
        () -> store.repairHistory("chat", "skill"));
    org.junit.jupiter.api.Assertions.assertThrows(CaptureNoProgressException.class,
        () -> store.beginProposal("chat", "skill", "captureGraphPatch", Map.of("value", "bad")));
  }

  @Test
  void acceptanceOnlyRetiresFailuresForItsInputScope() {
    store.beginScope("chat", "skill", Map.of("source", "one"));
    store.beginProposal("chat", "skill", "captureGraphPatch", Map.of("value", "bad"));
    store.recordClassifiedPatchFailure("chat", "skill", CaptureFailureKind.VALIDATION,
        CaptureFailureClass.CORRECTABLE, true, "Source one rejected");
    store.beginScope("chat", "skill", Map.of("source", "two"));
    store.clearPatch("chat", "skill");
    store.beginScope("chat", "skill", Map.of("source", "one"));

    assertTrue(store.repairHistory("chat", "skill").contains("Source one rejected"),
        () -> store.repairHistory("chat", "skill"));
    org.junit.jupiter.api.Assertions.assertThrows(CaptureNoProgressException.class,
        () -> store.beginProposal("chat", "skill", "captureGraphPatch", Map.of("value", "bad")));
  }

  @Test
  void changedSourceDoesNotRestoreCachedFailureOrItsHistory() {
    store.beginScope("chat", "skill", Map.of("source", "one"));
    store.beginProposal("chat", "skill", "captureGraphPatch", Map.of("value", "bad"));
    store.recordClassifiedPatchFailure("chat", "skill", CaptureFailureKind.VALIDATION,
        CaptureFailureClass.CORRECTABLE, true, "Source one rejected");
    store.beginScope("chat", "skill", Map.of("source", "two"));

    assertEquals(java.util.Optional.empty(), store.lastPatchFailure("chat", "skill"));
    assertEquals("", store.repairHistory("chat", "skill"));
    org.junit.jupiter.api.Assertions.assertDoesNotThrow(
        () -> store.beginProposal("chat", "skill", "captureGraphPatch", Map.of("value", "bad")));
  }

  @Test
  void legacyClearedFeedbackStaysInactiveAfterUpgradeWithoutRemovingAudit() {
    var mapper = new ObjectMapper().findAndRegisterModules();
    var blobs = new InMemoryArtifactBlobStore();
    var artifacts = new CompilationArtifacts(blobs, mapper, Clock.systemUTC());
    var sessions = new CompilationSessions(blobs, mapper, Clock.systemUTC());
    var first = new CaptureAttemptFeedbackStore(new ToolCallFingerprintStore(mapper, artifacts, sessions), artifacts, sessions);
    first.beginScope("chat", "skill", Map.of("source", "one"));
    first.beginProposal("chat", "skill", "captureGraphPatch", Map.of("value", "bad"));
    first.recordClassifiedPatchFailure("chat", "skill", CaptureFailureKind.VALIDATION,
        CaptureFailureClass.CORRECTABLE, true, "Legacy resolved rejection");
    artifacts.append(new CompilationArtifacts.AppendCommand(sessions.active("chat"),
        CompilationArtifacts.Kind.CAPTURE_ATTEMPT_FEEDBACK, "1", "capture-feedback", "1",
        mapper.createObjectNode().put("channel", "PATCH").put("capabilityId", "skill")
            .putNull("proposal").putNull("feedback"), List.of(), null));
    var restored = new CaptureAttemptFeedbackStore(new ToolCallFingerprintStore(mapper, artifacts, sessions), artifacts, sessions);
    restored.beginScope("chat", "skill", Map.of("source", "one"));

    assertEquals("", restored.repairHistory("chat", "skill"));
    assertEquals(java.util.Optional.empty(), restored.lastPatchFailure("chat", "skill"));
    org.junit.jupiter.api.Assertions.assertDoesNotThrow(
        () -> restored.beginProposal("chat", "skill", "captureGraphPatch", Map.of("value", "bad")));
    assertEquals(2, artifacts.history(sessions.active("chat"),
        CompilationArtifacts.Kind.CAPTURE_ATTEMPT_FEEDBACK).size());
  }

  @Test
  void legacyPlanClearRetiresEachCapabilityAfterReconstructionAndPreservesAudit() throws Exception {
    var mapper = new ObjectMapper().findAndRegisterModules();
    var blobs = new InMemoryArtifactBlobStore();
    var artifacts = new CompilationArtifacts(blobs, mapper, Clock.systemUTC());
    var sessions = new CompilationSessions(blobs, mapper, Clock.systemUTC());
    var original = new CaptureAttemptFeedbackStore(new ToolCallFingerprintStore(mapper, artifacts, sessions), artifacts, sessions);
    original.beginScope("chat", "first", Map.of("source", "first-input"));
    original.beginProposal("chat", "first", "capturePlan", Map.of("value", "first-rejected"));
    original.recordClassifiedPlanFailure("chat", CaptureFailureKind.VALIDATION,
        CaptureFailureClass.CORRECTABLE, true, "First plan rejected");
    original.beginScope("chat", "second", Map.of("source", "second-input"));
    original.beginProposal("chat", "second", "capturePlan", Map.of("value", "second-rejected"));
    original.recordClassifiedPlanFailure("chat", CaptureFailureKind.VALIDATION,
        CaptureFailureClass.CORRECTABLE, true, "Second plan rejected");
    var rejected = artifacts.history(sessions.active("chat"), CompilationArtifacts.Kind.CAPTURE_ATTEMPT_FEEDBACK);
    var legacyMarker = mapper.readTree("""
        {"channel":"PLAN","capabilityId":"","proposal":null,"feedback":null}
        """);
    artifacts.append(new CompilationArtifacts.AppendCommand(sessions.active("chat"),
        CompilationArtifacts.Kind.CAPTURE_ATTEMPT_FEEDBACK, "1", "capture-feedback", "1",
        legacyMarker, List.of(), null));
    var audit = blobSnapshot(blobs);
    var restored = new CaptureAttemptFeedbackStore(new ToolCallFingerprintStore(mapper, artifacts, sessions), artifacts, sessions);

    restored.beginScope("chat", "first", Map.of("source", "first-input"));
    org.junit.jupiter.api.Assertions.assertAll(
        () -> assertEquals(java.util.Optional.empty(), restored.lastPlanFailure("chat")),
        () -> assertEquals("", restored.repairHistory("chat", "first")),
        () -> org.junit.jupiter.api.Assertions.assertDoesNotThrow(
            () -> restored.beginProposal("chat", "first", "capturePlan", Map.of("value", "first-rejected"))));
    restored.beginScope("chat", "second", Map.of("source", "second-input"));
    org.junit.jupiter.api.Assertions.assertAll(
        () -> assertEquals(java.util.Optional.empty(), restored.lastPlanFailure("chat")),
        () -> assertEquals("", restored.repairHistory("chat", "second")),
        () -> org.junit.jupiter.api.Assertions.assertDoesNotThrow(
            () -> restored.beginProposal("chat", "second", "capturePlan", Map.of("value", "second-rejected"))));
    org.junit.jupiter.api.Assertions.assertAll(
        () -> assertTrue(restored.isResolved("chat", rejected.get(0).reference()), rejected.get(0).reference()::toString),
        () -> assertTrue(restored.isResolved("chat", rejected.get(1).reference()), rejected.get(1).reference()::toString));
    assertEquals(audit, blobSnapshot(blobs));
    assertEquals(3, artifacts.history(sessions.active("chat"),
        CompilationArtifacts.Kind.CAPTURE_ATTEMPT_FEEDBACK).size());
    assertEquals(List.of("first-rejected", "second-rejected"), rejected.stream()
        .map(revision -> artifacts.payload(revision, CaptureAttemptFeedbackStore.Observation.class)
            .proposal().arguments().path("value").asText()).toList());
  }

  @Test
  void resolvedNativeFeedbackReferenceCannotReenterRecovery() {
    var mapper = new ObjectMapper().findAndRegisterModules();
    var blobs = new InMemoryArtifactBlobStore();
    var artifacts = new CompilationArtifacts(blobs, mapper, Clock.systemUTC());
    var sessions = new CompilationSessions(blobs, mapper, Clock.systemUTC());
    var first = new CaptureAttemptFeedbackStore(new ToolCallFingerprintStore(mapper, artifacts, sessions), artifacts, sessions);
    first.beginScope("chat", "skill", Map.of("source", "one"));
    first.beginProposal("chat", "skill", "captureGraphPatch", Map.of("value", "bad"));
    first.recordClassifiedPatchFailure("chat", "skill", CaptureFailureKind.VALIDATION,
        CaptureFailureClass.CORRECTABLE, true, "Resolved native rejection");
    var rejected = first.lastPatchFailure("chat", "skill").orElseThrow();
    assertFalse(first.isResolved("chat", rejected.evidenceReference()), rejected.evidenceReference()::toString);
    first.clearPatch("chat", "skill");

    assertTrue(first.isResolved("chat", rejected.evidenceReference()), rejected.evidenceReference()::toString);
    org.junit.jupiter.api.Assertions.assertNull(first.failureEvidence("chat", "skill", rejected).nativeObservation());
  }

  @Test
  void resolvedAliasFeedbackRemainsResolvedAfterOwnerSwitchAndReconstruction() {
    var mapper = new ObjectMapper().findAndRegisterModules();
    var blobs = new InMemoryArtifactBlobStore();
    var artifacts = new CompilationArtifacts(blobs, mapper, Clock.systemUTC());
    var sessions = new CompilationSessions(blobs, mapper, Clock.systemUTC());
    var original = new CaptureAttemptFeedbackStore(new ToolCallFingerprintStore(mapper, artifacts, sessions), artifacts, sessions);
    original.beginScope("clean-alias", "first", Map.of("source", "first-input"), "chat");
    original.beginProposal("clean-alias", "first", "captureGraphPatch", Map.of("value", "bad"));
    original.recordClassifiedPatchFailure("clean-alias", "first", CaptureFailureKind.VALIDATION,
        CaptureFailureClass.CORRECTABLE, true, "First owner rejected");
    var rejected = original.lastPatchFailure("clean-alias", "first").orElseThrow().evidenceReference();
    original.clearPatch("clean-alias", "first");
    var restored = new CaptureAttemptFeedbackStore(new ToolCallFingerprintStore(mapper, artifacts, sessions),
        new CompilationArtifacts(blobs, mapper, Clock.systemUTC()), new CompilationSessions(blobs, mapper, Clock.systemUTC()));
    restored.beginScope("chat", "second", Map.of("source", "second-input"));
    var before = blobSnapshot(blobs);

    assertTrue(restored.isResolved("chat", rejected), rejected::toString);
    assertEquals(before, blobSnapshot(blobs));
  }

  @Test
  void invocationResetDoesNotResolveANativeFeedbackReference() {
    var mapper = new ObjectMapper().findAndRegisterModules();
    var blobs = new InMemoryArtifactBlobStore();
    var artifacts = new CompilationArtifacts(blobs, mapper, Clock.systemUTC());
    var sessions = new CompilationSessions(blobs, mapper, Clock.systemUTC());
    var durable = new CaptureAttemptFeedbackStore(new ToolCallFingerprintStore(mapper, artifacts, sessions), artifacts, sessions);
    durable.beginScope("chat", "first", Map.of("source", "first-input"));
    durable.beginProposal("chat", "first", "captureGraphPatch", Map.of("value", "bad"));
    durable.recordClassifiedPatchFailure("chat", "first", CaptureFailureKind.VALIDATION,
        CaptureFailureClass.CORRECTABLE, true, "First owner rejected");
    var rejected = durable.lastPatchFailure("chat", "first").orElseThrow().evidenceReference();
    durable.resetAll("chat");
    durable.beginScope("chat", "second", Map.of("source", "second-input"));
    var before = blobSnapshot(blobs);

    assertFalse(durable.isResolved("chat", rejected), rejected::toString);
    assertEquals(before, blobSnapshot(blobs));
  }

  @Test
  void acceptanceUnderDifferentInputsDoesNotResolveANativeFeedbackReference() {
    var mapper = new ObjectMapper().findAndRegisterModules();
    var blobs = new InMemoryArtifactBlobStore();
    var artifacts = new CompilationArtifacts(blobs, mapper, Clock.systemUTC());
    var sessions = new CompilationSessions(blobs, mapper, Clock.systemUTC());
    var durable = new CaptureAttemptFeedbackStore(new ToolCallFingerprintStore(mapper, artifacts, sessions), artifacts, sessions);
    durable.beginScope("chat", "owner", Map.of("source", "first-input"));
    durable.beginProposal("chat", "owner", "captureGraphPatch", Map.of("value", "bad"));
    durable.recordClassifiedPatchFailure("chat", "owner", CaptureFailureKind.VALIDATION,
        CaptureFailureClass.CORRECTABLE, true, "First input rejected");
    var rejected = durable.lastPatchFailure("chat", "owner").orElseThrow().evidenceReference();
    durable.beginScope("chat", "owner", Map.of("source", "other-input"));
    durable.clearPatch("chat", "owner");
    var before = blobSnapshot(blobs);

    assertFalse(durable.isResolved("chat", rejected), rejected::toString);
    assertEquals(before, blobSnapshot(blobs));
  }

  @Test
  void unknownNativeReferencesRemainUnresolvedWithoutWriting() {
    var mapper = new ObjectMapper().findAndRegisterModules();
    var blobs = new InMemoryArtifactBlobStore();
    var artifacts = new CompilationArtifacts(blobs, mapper, Clock.systemUTC());
    var sessions = new CompilationSessions(blobs, mapper, Clock.systemUTC());
    var durable = new CaptureAttemptFeedbackStore(new ToolCallFingerprintStore(mapper, artifacts, sessions), artifacts, sessions);
    durable.beginScope("chat", "owner", Map.of("source", "one"));
    durable.beginProposal("chat", "owner", "captureGraphPatch", Map.of("value", "bad"));
    durable.recordClassifiedPatchFailure("chat", "owner", CaptureFailureKind.VALIDATION,
        CaptureFailureClass.CORRECTABLE, true, "Rejected");
    var rejected = durable.lastPatchFailure("chat", "owner").orElseThrow().evidenceReference();
    durable.clearPatch("chat", "owner");
    var before = blobSnapshot(blobs);

    org.junit.jupiter.api.Assertions.assertAll(
        () -> assertTrue(durable.isResolved("chat", rejected), rejected::toString),
        () -> assertFalse(durable.isResolved("other-chat", rejected), "Foreign conversation"),
        () -> assertFalse(durable.isResolved("chat", null), "Missing reference"),
        () -> assertFalse(durable.isResolved("chat", new CompilationArtifacts.Reference(
            rejected.kind(), rejected.artifactId(), "different-hash")), "Different content hash"),
        () -> assertFalse(durable.isResolved("chat", new CompilationArtifacts.Reference(
            rejected.kind(), "missing-artifact", rejected.contentHash())), "Unknown artifact"),
        () -> assertFalse(durable.isResolved("chat", new CompilationArtifacts.Reference(
            CompilationArtifacts.Kind.RECOVERY_EVIDENCE, rejected.artifactId(), rejected.contentHash())), "Other evidence kind"));
    assertEquals(before, blobSnapshot(blobs));
  }

  @ParameterizedTest
  @ValueSource(strings = {"captureRequirementDraft", "updateRequirementDraft"})
  void acceptedDraftCaptureResolvesEarlierRequirementFeedbackAcrossSourceChanges(String toolName) throws Exception {
    var mapper = new ObjectMapper().findAndRegisterModules();
    var blobs = new InMemoryArtifactBlobStore();
    var artifacts = new CompilationArtifacts(blobs, mapper, Clock.systemUTC());
    var sessions = new CompilationSessions(blobs, mapper, Clock.systemUTC());
    var original = new CaptureAttemptFeedbackStore(new ToolCallFingerprintStore(mapper, artifacts, sessions), artifacts, sessions);
    original.beginScope("chat", "requirement-discovery", Map.of("source", "old-author-input"));
    original.recordClassifiedRequirementFailure("chat", CaptureFailureKind.VALIDATION,
        CaptureFailureClass.CORRECTABLE, true, "Earlier rejected draft", List.of());
    var earlier = original.lastRequirementFailure("chat").orElseThrow().evidenceReference();
    original.beginScope("chat", "requirement-discovery", Map.of("source", "new-author-input"));
    var accepted = mapper.readTree("""
        {"sourceGeneration":"new-generation","sourceTurnId":"new-author-input","authorText":"Revised request",
         "mutationScope":"new-scope","toolName":"%s","arguments":{},"accepted":true,"issues":[],
         "acceptedDraftReference":null}
        """.formatted(toolName));
    artifacts.append(new CompilationArtifacts.AppendCommand(sessions.active("chat"),
        CompilationArtifacts.Kind.REQUIREMENT_CAPTURE_ATTEMPT, "1", "requirement-capture-tools", "1",
        accepted, List.of(), null));
    var resumedAfterAcceptance = new CaptureAttemptFeedbackStore(new ToolCallFingerprintStore(mapper, artifacts, sessions),
        new CompilationArtifacts(blobs, mapper, Clock.systemUTC()), new CompilationSessions(blobs, mapper, Clock.systemUTC()));
    var acceptedAudit = blobSnapshot(blobs);
    assertEquals(java.util.Optional.empty(), resumedAfterAcceptance.lastRequirementRejection("chat"));
    assertEquals(acceptedAudit, blobSnapshot(blobs));
    original.recordClassifiedRequirementFailure("chat", CaptureFailureKind.VALIDATION,
        CaptureFailureClass.CORRECTABLE, true, "Later rejected draft", List.of());
    var later = original.lastRequirementFailure("chat").orElseThrow().evidenceReference();
    var restored = new CaptureAttemptFeedbackStore(new ToolCallFingerprintStore(mapper, artifacts, sessions),
        new CompilationArtifacts(blobs, mapper, Clock.systemUTC()), new CompilationSessions(blobs, mapper, Clock.systemUTC()));
    var before = blobSnapshot(blobs);

    org.junit.jupiter.api.Assertions.assertAll(
        () -> assertTrue(restored.isResolved("chat", earlier), earlier::toString),
        () -> assertFalse(restored.isResolved("chat", later), later::toString),
        () -> assertEquals(later, restored.lastRequirementFailure("chat").orElseThrow().evidenceReference()));
    assertEquals(before, blobSnapshot(blobs));
  }

  @ParameterizedTest
  @CsvSource({"reset,true,requirement-capture-tools", "projection-rebuild,true,requirement-capture-tools",
      "captureRequirementDraft,false,requirement-capture-tools", "updateRequirementDraft,false,requirement-capture-tools",
      "captureRequirementDraft,true,foreign-producer"})
  void bookkeepingAndRejectedCapturesDoNotResolveRequirementFeedback(String toolName, boolean accepted, String producer) {
    var mapper = new ObjectMapper().findAndRegisterModules();
    var blobs = new InMemoryArtifactBlobStore();
    var artifacts = new CompilationArtifacts(blobs, mapper, Clock.systemUTC());
    var sessions = new CompilationSessions(blobs, mapper, Clock.systemUTC());
    var durable = new CaptureAttemptFeedbackStore(new ToolCallFingerprintStore(mapper, artifacts, sessions), artifacts, sessions);
    durable.recordClassifiedRequirementFailure("chat", CaptureFailureKind.VALIDATION,
        CaptureFailureClass.CORRECTABLE, true, "Unresolved draft", List.of());
    var rejected = durable.lastRequirementFailure("chat").orElseThrow().evidenceReference();
    var bookkeeping = new org.qubership.integration.platform.ai.plan.RequirementDraftStore.RequirementCaptureAttempt(
        "generation", "author-turn", "Request", "scope", toolName, Map.of(), accepted, List.of(), null);
    artifacts.append(new CompilationArtifacts.AppendCommand(sessions.active("chat"),
        CompilationArtifacts.Kind.REQUIREMENT_CAPTURE_ATTEMPT, "1", producer, "1", bookkeeping, List.of(), null));
    var before = blobSnapshot(blobs);

    assertFalse(durable.isResolved("chat", rejected), rejected::toString);
    assertEquals(before, blobSnapshot(blobs));
  }

  @Test
  void legacyRequirementClearDoesNotProveAnAcceptedCapture() throws Exception {
    var mapper = new ObjectMapper().findAndRegisterModules();
    var blobs = new InMemoryArtifactBlobStore();
    var artifacts = new CompilationArtifacts(blobs, mapper, Clock.systemUTC());
    var sessions = new CompilationSessions(blobs, mapper, Clock.systemUTC());
    var original = new CaptureAttemptFeedbackStore(new ToolCallFingerprintStore(mapper, artifacts, sessions), artifacts, sessions);
    original.recordClassifiedRequirementFailure("chat", CaptureFailureKind.VALIDATION,
        CaptureFailureClass.CORRECTABLE, true, "Unresolved draft", List.of());
    var rejected = original.lastRequirementFailure("chat").orElseThrow().evidenceReference();
    var marker = artifacts.append(new CompilationArtifacts.AppendCommand(sessions.active("chat"),
        CompilationArtifacts.Kind.REQUIREMENT_CAPTURE_FEEDBACK, "1", "requirement-capture-feedback", "1",
        mapper.readTree("{\"rejection\":null}"), List.of(), null));
    var restored = new CaptureAttemptFeedbackStore(new ToolCallFingerprintStore(mapper, artifacts, sessions),
        new CompilationArtifacts(blobs, mapper, Clock.systemUTC()), new CompilationSessions(blobs, mapper, Clock.systemUTC()));
    var before = blobSnapshot(blobs);

    org.junit.jupiter.api.Assertions.assertAll(
        () -> assertFalse(restored.isResolved("chat", rejected), rejected::toString),
        () -> assertFalse(restored.isResolved("chat", marker.reference()), marker.reference()::toString));
    assertEquals(before, blobSnapshot(blobs));
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
  void clarificationFailureIdentityIgnoresIssueWording() {
    var mapper = new ObjectMapper().findAndRegisterModules();
    var before = mapper.createObjectNode().put("interactionId", "submit").put("propertyKey", "path");
    before.putArray("requestedValues");
    var key = new CaptureAttemptFeedbackStore.ClarificationFailureKey(
        "turn-2", "offer-1", "submit", "path", before, before.deepCopy());
    var movedAfter = mapper.createObjectNode().put("interactionId", "submit").put("propertyKey", "path");
    movedAfter.putArray("requestedValues").add("https://example.com/tasks");
    var moved = new CaptureAttemptFeedbackStore.ClarificationFailureKey(
        "turn-2", "offer-1", "submit", "path", before, movedAfter);

    assertFalse(store.repeatedClarificationFailure("conv-1", key));
    store.recordClarificationFailure("conv-1", "requirement-discovery", key, List.of(new TypedIssue(
        "CLARIFICATION_ANSWER_NO_PROGRESS", "/draft/openQuestions/target-uri",
        "The pinned target is unchanged.", "target-uri")));

    assertTrue(store.repeatedClarificationFailure("conv-1", key));
    store.recordClarificationFailure("conv-1", "requirement-discovery", key, List.of(new TypedIssue(
        "CLARIFICATION_ANSWER_NO_PROGRESS", "/draft/openQuestions/target-uri",
        "A different sentence about the same target.", "target-uri")));
    assertFalse(store.repeatedClarificationFailure("conv-1", moved));
  }

  @Test
  void theFirstClarificationTargetIsNotAlreadyNoProgress() {
    var mapper = new ObjectMapper().findAndRegisterModules();
    var projection = mapper.createObjectNode().put("interactionId", "submit").put("propertyKey", "path");
    projection.putArray("requestedValues");
    var key = new CaptureAttemptFeedbackStore.ClarificationFailureKey(
        "turn-2", "offer-1", "submit", "path", projection, projection.deepCopy());
    var issue = List.of(new TypedIssue(
        "CLARIFICATION_ANSWER_NO_PROGRESS", "/draft/openQuestions/target-uri",
        "The pinned target is unchanged.", "target-uri"));

    var first = store.recordClarificationFailure("conv-1", "requirement-discovery", key, issue);
    assertEquals(false, first.noProgress());
    var second = store.recordClarificationFailure("conv-1", "requirement-discovery", key, issue);
    assertEquals(true, second.noProgress());
  }

  @Test
  void clarificationFailureSurvivesANewStoreOnTheSameArtifacts() {
    var mapper = new ObjectMapper().findAndRegisterModules();
    var blobs = new InMemoryArtifactBlobStore();
    var artifacts = new CompilationArtifacts(blobs, mapper, java.time.Clock.systemUTC());
    var sessions = new CompilationSessions(blobs, mapper, java.time.Clock.systemUTC());
    var first = new CaptureAttemptFeedbackStore(
        new ToolCallFingerprintStore(mapper, artifacts, sessions), artifacts, sessions);
    var projection = mapper.createObjectNode().put("interactionId", "submit").put("propertyKey", "path");
    projection.putArray("requestedValues");
    var key = new CaptureAttemptFeedbackStore.ClarificationFailureKey(
        "turn-2", "offer-1", "submit", "path", projection, projection.deepCopy());

    var feedback = first.recordClarificationFailure("conv-1", "requirement-discovery", key, List.of(new TypedIssue(
        "CLARIFICATION_ANSWER_NO_PROGRESS", "/draft/openQuestions/target-uri",
        "The pinned target is unchanged.", "target-uri")));

    org.junit.jupiter.api.Assertions.assertNotNull(feedback.evidenceReference());
    var restored = new CaptureAttemptFeedbackStore(
        new ToolCallFingerprintStore(mapper, artifacts, sessions), artifacts, sessions);
    assertTrue(restored.repeatedClarificationFailure("conv-1", key));
    var saved = artifacts.history(sessions.active("conv-1"), CompilationArtifacts.Kind.CAPTURE_ATTEMPT_FEEDBACK);
    var observation = artifacts.payload(saved.getLast(), CaptureAttemptFeedbackStore.Observation.class);
    assertEquals(CaptureAttemptFeedbackStore.CLARIFICATION_TARGET_TOOL, observation.proposal().toolName());
    assertEquals("turn-2", observation.proposal().arguments().get("answerTurnId").asText());
    assertFalse(observation.proposal().arguments().toString().contains("Which URI"));
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
