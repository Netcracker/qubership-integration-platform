package org.qubership.integration.platform.ai.productpipeline.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Reference;
import org.qubership.integration.platform.ai.productpipeline.artifact.PlanValidationFinding;
import org.qubership.integration.platform.ai.productpipeline.capability.RecoveryCause;
import org.qubership.integration.platform.ai.productpipeline.capability.RecoveryCauseCode;
import org.qubership.integration.platform.ai.productpipeline.stage.ProductPipelineStageExecutor;
import org.qubership.integration.platform.ai.productpipeline.store.ProductPipelineRunDocument;
import org.qubership.integration.platform.ai.productpipeline.store.RunSnapshot;
import org.qubership.integration.platform.ai.productpipeline.store.RunStatus;
import org.qubership.integration.platform.ai.productpipeline.store.RunTransition;
import org.qubership.integration.platform.ai.productpipeline.store.StageSnapshot;
import org.qubership.integration.platform.ai.productpipeline.store.StageStatus;

class RecoveryAttemptLedgerTest {

  private static final RecoveryCause UNKNOWN_PROPERTY =
      new RecoveryCause(
          RecoveryCauseCode.UNKNOWN_PROPERTY,
          List.of(new PlanValidationFinding("UNKNOWN_PROPERTY", "orders.v2", true)),
          "");
  private static final RecoveryCause UNKNOWN_PROPERTY_V3 =
      new RecoveryCause(
          RecoveryCauseCode.UNKNOWN_PROPERTY,
          List.of(new PlanValidationFinding("UNKNOWN_PROPERTY", "orders.v3", true)),
          "");

  private final RecoveryAttemptLedger ledger = new RecoveryAttemptLedger();

  @Test
  void aChangedInputArtifactAdvancesTheEpoch() {
    List<RunTransition> journal = new ArrayList<>();
    RecoveryAttemptKey first = ledger.key("analysis", UNKNOWN_PROPERTY, "brief-a", journal);
    journal.add(transition(ledger.recordRepair(first, "brief-a"), "planning"));

    RecoveryAttemptKey afterChange = ledger.key("analysis", UNKNOWN_PROPERTY, "brief-b", journal);
    assertEquals(1, afterChange.correctionEpoch());
    String correction =
        ledger.recordCorrection(journal, afterChange, "brief-b", InputOrigin.TRUSTED);
    assertFalse(correction.isEmpty());
    journal.add(transition(correction, "analysis"));
    assertTrue(ledger.mayRepair(journal, afterChange, InputOrigin.TRUSTED));
  }

  @Test
  void aRephrasingThatLeavesTheArtifactIdenticalDoesNotAdvanceTheEpoch() {
    List<RunTransition> journal = new ArrayList<>();
    RecoveryAttemptKey first = ledger.key("analysis", UNKNOWN_PROPERTY, "brief-a", journal);
    journal.add(transition(ledger.recordRepair(first, "brief-a"), "planning"));

    RecoveryAttemptKey rephrased = ledger.key("analysis", UNKNOWN_PROPERTY, "brief-a", journal);
    assertEquals(0, rephrased.correctionEpoch());
    assertEquals(
        "", ledger.recordCorrection(journal, rephrased, "brief-a", InputOrigin.TRUSTED));
    assertFalse(ledger.mayRepair(journal, rephrased, InputOrigin.TRUSTED));
  }

  @Test
  void questionsRetryClicksAndReviseClicksDoNotAdvanceTheEpoch() {
    List<RunTransition> journal = new ArrayList<>();
    RecoveryAttemptKey before = ledger.key("analysis", UNKNOWN_PROPERTY, "brief-a", journal);
    assertEquals(0, before.correctionEpoch());
    assertEquals(
        "", ledger.recordCorrection(journal, before, "brief-a", InputOrigin.TRUSTED));

    RecoveryAttemptKey afterQuestion = ledger.key("analysis", UNKNOWN_PROPERTY, "brief-a", journal);
    assertEquals(0, afterQuestion.correctionEpoch());
    assertEquals(
        ledger.remaining(journal, before, InputOrigin.TRUSTED),
        ledger.remaining(journal, afterQuestion, InputOrigin.TRUSTED));
  }

  @Test
  void theLedgerReconstructsFromJournalTransitionsAfterARestart() {
    List<RunTransition> journal = new ArrayList<>();
    RecoveryAttemptKey first = ledger.key("analysis", UNKNOWN_PROPERTY, "brief-a", journal);
    journal.add(transition(ledger.recordRepair(first, "brief-a"), "planning"));
    RecoveryAttemptKey changed = ledger.key("analysis", UNKNOWN_PROPERTY, "brief-b", journal);
    journal.add(
        transition(
            ledger.recordCorrection(journal, changed, "brief-b", InputOrigin.TRUSTED), "analysis"));
    journal.add(transition(ledger.recordRepair(changed, "brief-b"), "planning"));

    RecoveryAttemptLedger restarted = new RecoveryAttemptLedger();
    RecoveryAttemptKey reconstructed =
        restarted.key("analysis", UNKNOWN_PROPERTY, "brief-b", journal);
    assertEquals(1, reconstructed.correctionEpoch());
    assertFalse(restarted.mayRepair(journal, reconstructed, InputOrigin.TRUSTED));
    assertEquals(0, restarted.remaining(journal, reconstructed, InputOrigin.TRUSTED).semanticRepairsRemaining());
  }

  @Test
  void legacyReopenPrefixesAreReadAndAutomaticBudgetIgnoresAuthorReopens() {
    List<RunTransition> journal = new ArrayList<>();
    RecoveryAttemptKey key = ledger.key("analysis", UNKNOWN_PROPERTY, "brief-a", journal);
    journal.add(
        transition(
            ProductPipelineRunSupport.causalReopenReason("analysis", "legacy-sig"), "planning"));
    assertTrue(
        ledger.ownerAlreadyReopened(journal, key, "legacy-sig"));
    assertEquals(1, ledger.remaining(journal, key, InputOrigin.TRUSTED).causalReopensRemaining());

    journal.add(
        transition(
            ledger.recordReopen(key, RecoveryAttemptLedger.ReopenInitiator.AUTHOR, "brief-a"),
            "analysis"));
    assertEquals(1, ledger.remaining(journal, key, InputOrigin.TRUSTED).causalReopensRemaining());
    assertFalse(ledger.mayReopen(journal, key, InputOrigin.TRUSTED, RecoveryAttemptLedger.ReopenInitiator.AUTOMATIC, "legacy-sig"));

    List<RunTransition> automaticOnly = new ArrayList<>();
    RecoveryAttemptKey fresh = ledger.key("design", UNKNOWN_PROPERTY, "plan-a", automaticOnly);
    automaticOnly.add(
        transition(
            ledger.recordReopen(fresh, RecoveryAttemptLedger.ReopenInitiator.AUTOMATIC, "plan-a"),
            "planning"));
    automaticOnly.add(
        transition(
            ledger.recordReopen(fresh, RecoveryAttemptLedger.ReopenInitiator.AUTHOR, "plan-a"),
            "planning"));
    assertEquals(
        1,
        new RecoveryAttemptLedger()
            .remaining(automaticOnly, fresh, InputOrigin.TRUSTED)
            .causalReopensRemaining());
  }

  @Test
  void ownerAlreadyReopenedCountsAuthorAndAutomaticPrefixes() {
    List<RunTransition> journal = new ArrayList<>();
    RecoveryAttemptKey key = ledger.key("analysis", UNKNOWN_PROPERTY, "brief-a", journal);
    journal.add(
        transition(
            ledger.recordReopen(key, RecoveryAttemptLedger.ReopenInitiator.AUTHOR, "brief-a"),
            "analysis"));
    assertTrue(ledger.ownerAlreadyReopened(journal, key, ""));
    assertFalse(
        ledger.mayReopen(
            journal, key, InputOrigin.TRUSTED, RecoveryAttemptLedger.ReopenInitiator.AUTHOR, ""));
  }

  @Test
  void anAbsolutePerRunCeilingRefusesFurtherAttempts() {
    RecoveryAttemptLedger tight =
        new RecoveryAttemptLedger(new RecoveryAttemptLedger.Limits(1, 2, 2));
    List<RunTransition> journal = new ArrayList<>();
    RecoveryAttemptKey first = tight.key("analysis", UNKNOWN_PROPERTY, "brief-a", journal);
    journal.add(transition(tight.recordRepair(first, "brief-a"), "planning"));
    RecoveryAttemptKey other = tight.key("design", UNKNOWN_PROPERTY_V3, "plan-a", journal);
    journal.add(transition(tight.recordRepair(other, "plan-a"), "planning"));
    assertFalse(tight.mayRepair(journal, other, InputOrigin.TRUSTED));
    assertFalse(
        tight.mayReopen(
            journal,
            other,
            InputOrigin.TRUSTED,
            RecoveryAttemptLedger.ReopenInitiator.AUTOMATIC,
            ""));
    assertEquals(SemanticRecoveryState.RemainingAttempts.none(), tight.remaining(journal, other, InputOrigin.TRUSTED));
  }

  @Test
  void absentOrUntrustedOriginFallsBackToTheFlatBudget() {
    List<RunTransition> journal = new ArrayList<>();
    RecoveryAttemptKey first = ledger.key("analysis", UNKNOWN_PROPERTY, "brief-a", journal);
    journal.add(transition(ledger.recordRepair(first, "brief-a"), "planning"));
    RecoveryAttemptKey changed = ledger.key("analysis", UNKNOWN_PROPERTY, "brief-b", journal);
    assertTrue(ledger.mayRepair(journal, changed, InputOrigin.TRUSTED));
    assertFalse(ledger.mayRepair(journal, changed, InputOrigin.ABSENT));
    assertFalse(ledger.mayRepair(journal, changed, InputOrigin.UNTRUSTED));
    assertEquals(
        "", ledger.recordCorrection(journal, changed, "brief-b", InputOrigin.ABSENT));
    assertEquals(
        "", ledger.recordCorrection(journal, changed, "brief-b", InputOrigin.UNTRUSTED));
  }

  @Test
  void evidenceIdentityComesFromStructuredFindingsNotAuthorText() {
    String first = RecoveryAttemptLedger.evidenceIdentity(UNKNOWN_PROPERTY);
    String sameWording =
        RecoveryAttemptLedger.evidenceIdentity(
            new RecoveryCause(
                RecoveryCauseCode.UNKNOWN_PROPERTY,
                List.of(new PlanValidationFinding("UNKNOWN_PROPERTY", "orders.v2", true)),
                ""));
    String differentProperty = RecoveryAttemptLedger.evidenceIdentity(UNKNOWN_PROPERTY_V3);
    assertEquals(first, sameWording);
    assertNotEquals(first, differentProperty);
  }

  @Test
  void inputArtifactIdentityIgnoresUserInputAndUsesApprovedHashes() {
    StageSnapshot analysis =
        new StageSnapshot(
            "analysis",
            StageStatus.SUCCEEDED,
            List.of(new Reference(Kind.REQUIREMENT_BRIEF, "brief-1", "hash-a")),
            "brief-1",
            List.of(),
            null,
            1);
    StageSnapshot planning =
        new StageSnapshot(
            "planning",
            StageStatus.WAITING_FOR_INPUT,
            List.of(),
            null,
            List.of(),
            null,
            null);
    ProductPipelineRunDocument doc =
        new ProductPipelineRunDocument(
            new RunSnapshot(
                "run",
                "conv",
                1L,
                RunStatus.WAITING_FOR_INPUT,
                "planning",
                List.of(analysis, planning),
                null),
            List.of(),
            List.of(),
            "v1");
    String identity = RecoveryAttemptLedger.inputArtifactIdentity(doc, "analysis");
    assertTrue(identity.contains("hash-a"));
    assertFalse(identity.contains("please use orders"));
  }

  @Test
  void remainingFeedsTheSemanticRecoveryTuple() {
    List<RunTransition> journal = new ArrayList<>();
    RecoveryAttemptKey key = ledger.key("analysis", UNKNOWN_PROPERTY, "brief-a", journal);
    SemanticRecoveryState.RemainingAttempts remaining =
        ledger.remaining(journal, key, InputOrigin.TRUSTED);
    assertEquals(ProductPipelineStageExecutor.MAX_SEMANTIC_REPAIRS, remaining.semanticRepairsRemaining());
    assertEquals(ProductPipelineRunSupport.MAX_CAUSAL_REOPENS, remaining.causalReopensRemaining());
  }

  private static RunTransition transition(String reason, String stageId) {
    return new RunTransition(
        0L,
        1L,
        RunStatus.RUNNING,
        RunStatus.RUNNING,
        stageId,
        Instant.EPOCH,
        reason);
  }
}
