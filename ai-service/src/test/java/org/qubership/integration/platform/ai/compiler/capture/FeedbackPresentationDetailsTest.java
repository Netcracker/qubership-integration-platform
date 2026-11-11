package org.qubership.integration.platform.ai.compiler.capture;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationSessions;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.qubership.integration.platform.ai.compiler.capture.policy.CaptureFailureClass;
import org.qubership.integration.platform.ai.compiler.capture.policy.CaptureFeedbackChannel;
import org.qubership.integration.platform.ai.compiler.capture.policy.CaptureToolOutcomeGateway;
import org.qubership.integration.platform.ai.compiler.capture.policy.ToolCallFingerprintStore;
import org.qubership.integration.platform.ai.compiler.capture.policy.ToolCallFingerprints;
import org.qubership.integration.platform.ai.productpipeline.capability.RecoveryCause;
import org.qubership.integration.platform.ai.productpipeline.runtime.InputOrigin;
import org.qubership.integration.platform.ai.productpipeline.runtime.RecoveryAttemptLedger;
import org.qubership.integration.platform.ai.productpipeline.store.RunStatus;
import org.qubership.integration.platform.ai.productpipeline.store.RunTransition;

class FeedbackPresentationDetailsTest {
  private static final String SUMMARY = "BRIEF_CONTRACT_VIOLATION at /mappingIntents: "
      + "Field mapping differs from the approved requirement: upstream -> downstream / $.Receipt";
  private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

  @Test
  void legacyWireShapeAndConstructorRoundTripStayUnchanged() throws Exception {
    var legacy = new CaptureAttemptFeedback(CaptureFailureKind.VALIDATION, SUMMARY);
    var tree = json.valueToTree(legacy);
    assertFalse(tree.has("presentationDetails"));
    assertFalse(tree.has("modelSummary"));
    assertEquals(legacy, json.treeToValue(tree, CaptureAttemptFeedback.class));
    assertEquals(SUMMARY, legacy.modelSummary());
    var detailed = withDetails("exact detail");
    assertEquals(detailed, json.readValue(json.writeValueAsBytes(detailed), CaptureAttemptFeedback.class));
    assertEquals(SUMMARY, detailed.summary());
    assertEquals(SUMMARY + "\nexact detail", detailed.modelSummary());
  }

  @Test
  void changingDetailsCannotBuyLocalOrOuterCreditAfterNativeCacheClear() {
    var fingerprints = new ToolCallFingerprintStore(json);
    var store = new CaptureAttemptFeedbackStore(fingerprints);
    var gateway = new CaptureToolOutcomeGateway(fingerprints, store);
    gateway.onFailure(CaptureFeedbackChannel.PLAN, "chat", "scope", CaptureFailureKind.VALIDATION,
        CaptureFailureClass.CORRECTABLE, "captureRequirementBrief", null, SUMMARY, List.of(), "first detail");
    var first = store.lastPlanFailure("chat").orElseThrow();
    assertEquals(ToolCallFingerprints.failureFingerprint("captureRequirementBrief", "scope", SUMMARY),
        ToolCallFingerprints.failureFingerprint("captureRequirementBrief", "scope", first.summary()));
    assertThrows(CaptureValidationException.class, () -> gateway.onFailure(CaptureFeedbackChannel.PLAN,
        "chat", "scope", CaptureFailureKind.VALIDATION, CaptureFailureClass.CORRECTABLE,
        "captureRequirementBrief", null, SUMMARY, List.of(), "changed 'value' [extra] detail"));
    var terminal = store.lastPlanFailure("chat").orElseThrow();
    assertEquals(CaptureFailureClass.IDENTICAL_SPAM, terminal.failureClass());
    assertFalse(terminal.outerAllowed());
    assertEquals("changed 'value' [extra] detail", terminal.presentationDetails());
    var legacyCause = RecoveryCause.captureFailure(new CaptureAttemptFeedback(CaptureFailureKind.VALIDATION,
        SUMMARY), List.of(), null);
    var firstCause = RecoveryCause.captureFailure(first, List.of(), null);
    assertEquals(legacyCause.findings(), firstCause.findings());
    var ledger = new RecoveryAttemptLedger(new RecoveryAttemptLedger.Limits(1, 1, 12, 1));
    var originalKey = ledger.key("analysis", legacyCause, "approved-source", List.of());
    var journal = List.of(new RunTransition(1, 2, RunStatus.RUNNING, RunStatus.RUNNING,
        "analysis", Instant.EPOCH, ledger.recordRepair(originalKey, "approved-source")));
    store.clearPlan("chat");
    gateway.onFailure(CaptureFeedbackChannel.PLAN, "chat", "scope", CaptureFailureKind.VALIDATION,
        CaptureFailureClass.CORRECTABLE, "captureRequirementBrief", null, SUMMARY, List.of(), "third detail");
    var afterClear = store.lastPlanFailure("chat").orElseThrow();
    var restoredCause = RecoveryCause.captureFailure(afterClear, List.of(), null);
    assertEquals(RecoveryAttemptLedger.evidenceIdentity(legacyCause), RecoveryAttemptLedger.evidenceIdentity(restoredCause));
    var restoredKey = ledger.key("analysis", restoredCause, "approved-source", journal);
    assertEquals(originalKey, restoredKey);
    assertFalse(new RecoveryAttemptLedger(new RecoveryAttemptLedger.Limits(1, 1, 12, 1))
        .mayRepair(journal, restoredKey, InputOrigin.TRUSTED));
  }

  @Test
  void nativeObservationReloadAndNoProgressCopiesKeepExactDetails() {
    var blobs = new InMemoryArtifactBlobStore();
    var artifacts = new CompilationArtifacts(blobs, json, Clock.systemUTC());
    var sessions = new CompilationSessions(blobs, json, Clock.systemUTC());
    var fingerprints = new ToolCallFingerprintStore(json, artifacts, sessions);
    var first = new CaptureAttemptFeedbackStore(fingerprints, artifacts, sessions);
    first.beginScope("chat", "analysis", Map.of("source", "source-epoch"));
    first.beginProposal("chat", "analysis", "captureRequirementBrief", Map.of("sourcePath", "$.changed"));
    first.recordClassifiedPlanFailure("chat", CaptureFailureKind.VALIDATION, CaptureFailureClass.CORRECTABLE,
        true, SUMMARY, List.of(), "exact proposed and approved rules");
    var failure = first.lastPlanFailure("chat").orElseThrow();
    assertEquals("exact proposed and approved rules", failure.presentationDetails());
    assertTrue(failure.repairHistoryJson().contains("exact proposed and approved rules"));
    assertNotNull(first.failureEvidence("chat", "analysis", failure).nativeObservation());
    var altered = new CaptureAttemptFeedback(failure.kind(), failure.summary(), failure.failureClass(),
        failure.outerAllowed(), failure.fieldHints(), failure.repairHistoryJson(), failure.noProgress(),
        failure.evidenceReference(), failure.issues(), "tampered detail");
    assertNull(first.failureEvidence("chat", "analysis", altered).nativeObservation());
    var restored = new CaptureAttemptFeedbackStore(new ToolCallFingerprintStore(json, artifacts, sessions), artifacts, sessions);
    restored.beginScope("chat", "analysis", Map.of("source", "source-epoch"));
    assertEquals(failure.presentationDetails(), restored.lastPlanFailure("chat").orElseThrow().presentationDetails());
    assertThrows(CaptureNoProgressException.class, () -> restored.beginProposal("chat", "analysis",
        "captureRequirementBrief", Map.of("sourcePath", "$.changed")));
    var terminal = restored.lastPlanFailure("chat").orElseThrow();
    assertEquals(failure.presentationDetails(), terminal.presentationDetails());
    assertEquals(SUMMARY, terminal.summary());
    assertTrue(terminal.noProgress());
    assertFalse(terminal.outerAllowed());
  }

  @Test
  void modelWordingKeepsDetailsOutsideTheExistingProsePreviewLimit() {
    String details = "proposedRule/approvedRules:" + "\u03bb".repeat(1500) + "exact-tail";
    var feedback = withDetails(details);
    var builder = new CaptureRepairMessageBuilder(null);
    assertTrue(builder.build(feedback, "captureRequirementBrief").endsWith(details));
    assertTrue(builder.scriptBodiesRepairMessage(List.of("node"), feedback).endsWith(details));
    assertEquals(SUMMARY, feedback.summary());
  }

  @Test
  void freshPresentationDoesNotResetTheExistingRunnerDefectBudget() {
    var store = new CaptureAttemptFeedbackStore();
    var runner = new CaptureRepairRunner(new CaptureRepairMessageBuilder(null), store, 1);
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    runner.runWithRepair(message -> {
      int attempt = calls.incrementAndGet();
      store.recordClassifiedPlanFailure("chat", CaptureFailureKind.VALIDATION,
          CaptureFailureClass.CORRECTABLE, true, SUMMARY, List.of(), "detail iteration " + attempt);
      return io.smallrye.mutiny.Multi.createFrom().empty();
    }, () -> false, () -> store.lastPlanFailure("chat"), () -> {},
        "captureRequirementBrief", "initial").collect().asList().await().indefinitely();
    assertEquals(2, calls.get(), "Only the original call and its existing one repair are allowed");
    assertEquals(SUMMARY, store.lastPlanFailure("chat").orElseThrow().summary());
  }

  private static CaptureAttemptFeedback withDetails(String detail) {
    return new CaptureAttemptFeedback(CaptureFailureKind.VALIDATION, SUMMARY,
        CaptureFailureClass.CORRECTABLE, true, List.of(), "", false, null, List.of(), detail);
  }
}
