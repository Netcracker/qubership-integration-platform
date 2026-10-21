package org.qubership.integration.platform.ai.productpipeline.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Reference;
import org.qubership.integration.platform.ai.compiler.capture.CaptureAttemptFeedback;
import org.qubership.integration.platform.ai.compiler.capture.CaptureFailureKind;
import org.qubership.integration.platform.ai.compiler.capture.policy.CaptureFailureClass;
import org.qubership.integration.platform.ai.compiler.capture.policy.CaptureToolOutcomeGateway;
import org.qubership.integration.platform.ai.compiler.capture.policy.CaptureToolOutcomeGateway.TypedIssue;
import org.qubership.integration.platform.ai.productpipeline.artifact.PlanValidationFinding;
import org.qubership.integration.platform.ai.productpipeline.runtime.InputOrigin;
import org.qubership.integration.platform.ai.productpipeline.runtime.RecoveryAttemptLedger;
import org.qubership.integration.platform.ai.productpipeline.store.RunStatus;
import org.qubership.integration.platform.ai.productpipeline.store.RunTransition;

class RecoveryCauseCaptureIdentityTest {

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"context", " "})
  void repeatedRequirementCaptureKeepsTheOriginalDefectBudget(String entityId) {
    var issue = new TypedIssue("UNKNOWN_INTERACTION_REFERENCE", "/draft/facts/1/interactionIds/0",
        "Target must name an accepted interaction.", entityId);
    var firstFeedback = feedback(issue, false);
    var firstEvidence = evidence(Kind.REQUIREMENT_CAPTURE_ATTEMPT, firstFeedback);
    var nativeFinding = new PlanValidationFinding(issue.code(), issue.path() + ": " + issue.message(), true,
        null, entityId == null || entityId.isBlank() ? "requirement-draft" : entityId,
        CaptureToolOutcomeGateway.logicalRequirementPath(issue.path()));
    var first = RecoveryCause.captureFailure(firstFeedback, List.of(nativeFinding), firstEvidence);
    var repeatedFeedback = feedback(issue, true);
    var repeatedEvidence = evidence(Kind.REQUIREMENT_CAPTURE_ATTEMPT, repeatedFeedback);
    var repeated = RecoveryCause.captureFailure(repeatedFeedback, List.of(), repeatedEvidence);

    assertFalse(first.noProgress());
    assertTrue(repeated.noProgress());
    assertEquals(nativeFinding.originRecordId(), repeated.findings().getFirst().originRecordId());
    assertEquals(nativeFinding.fieldPointer(), repeated.findings().getFirst().fieldPointer());
    assertEquals(RecoveryAttemptLedger.evidenceIdentity(first), RecoveryAttemptLedger.evidenceIdentity(repeated));
    assertSame(repeatedEvidence, repeated.captureFailure());
    assertEquals(List.of(issue), repeated.captureFailure().feedback().issues());

    var ledger = new RecoveryAttemptLedger(new RecoveryAttemptLedger.Limits(1, 1, 12, 1));
    var firstKey = ledger.key("requirement-discovery", first, "accepted-base", List.of());
    var journal = List.of(
        new RunTransition(1, 2, RunStatus.RUNNING, RunStatus.RUNNING, "requirement-discovery", Instant.EPOCH,
            ledger.recordRepair(firstKey, "accepted-base")),
        new RunTransition(2, 3, RunStatus.RUNNING, RunStatus.RUNNING, "requirement-discovery", Instant.EPOCH,
            ledger.recordCleanRebuild(firstKey, "accepted-base")));
    var repeatedKey = ledger.key("requirement-discovery", repeated, "accepted-base", journal);
    assertEquals(firstKey, repeatedKey);
    assertFalse(ledger.mayRepair(journal, repeatedKey, InputOrigin.TRUSTED));
    assertFalse(ledger.mayCleanRebuild(journal, repeatedKey));
  }

  @ParameterizedTest
  @ValueSource(strings = {"CHAIN_PLAN_GRAPH", "GRAPH_PATCH_ARTIFACT"})
  void graphCaptureKeepsItsNativeOwnerAndField(String kind) {
    var issue = new TypedIssue("INVALID_VALUE", "/capture/patches/0/properties/contextPath",
        "Use the approved path.", null);
    var feedback = feedback(issue, true);
    var evidence = evidence(Kind.valueOf(kind), feedback);
    var cause = RecoveryCause.captureFailure(feedback, List.of(), evidence);

    assertEquals("", cause.findings().getFirst().originRecordId());
    assertEquals(issue.path(), cause.findings().getFirst().fieldPointer());
    assertSame(evidence, cause.captureFailure());
    assertEquals(List.of(issue), cause.captureFailure().feedback().issues());
  }

  private static CaptureAttemptFeedback feedback(TypedIssue issue, boolean repeated) {
    return new CaptureAttemptFeedback(CaptureFailureKind.VALIDATION, issue.message(),
        repeated ? CaptureFailureClass.IDENTICAL_SPAM : CaptureFailureClass.CORRECTABLE,
        !repeated, List.of(), "", repeated,
        new Reference(Kind.REQUIREMENT_CAPTURE_FEEDBACK, "feedback", "feedback-hash"), List.of(issue));
  }

  private static CaptureFailureEvidence evidence(Kind kind, CaptureAttemptFeedback feedback) {
    return new CaptureFailureEvidence(feedback,
        new Reference(Kind.REQUIREMENT_DRAFT, "accepted", "accepted-hash"),
        new Reference(kind, "rejected", "rejected-hash"), List.of());
  }
}
