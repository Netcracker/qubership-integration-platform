package org.qubership.integration.platform.ai.productpipeline.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Reference;
import org.qubership.integration.platform.ai.productpipeline.artifact.PlanValidationFinding;
import org.qubership.integration.platform.ai.productpipeline.capability.RecoveryCause;
import org.qubership.integration.platform.ai.productpipeline.capability.RecoveryCauseCode;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcomeClass;
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

  private final RecoveryAttemptLedger ledger = new RecoveryAttemptLedger(new RecoveryAttemptLedger.Limits(1, 2, 12, 1));

  @ParameterizedTest
  @ValueSource(strings = {"repair", "clean-rebuild"})
  void eachConfiguredStrategyHasTwoCorrectionsAfterOriginalGeneration(String strategy) {
    var budget = new RecoveryAttemptLedger(RecoveryAttemptLedger.Limits.defaults());
    var journal = new ArrayList<RunTransition>();
    var cause = structuredCause(false, "Invalid route.", "node-A", "/properties/path");
    for (int attempt = 0; attempt < 2; attempt++) {
      var key = budget.key("design-execution", cause, "approved-input", journal);
      assertTrue("repair".equals(strategy) ? budget.mayRepair(journal, key, InputOrigin.TRUSTED)
          : budget.mayCleanRebuild(journal, key));
      journal.add(transition("repair".equals(strategy) ? budget.recordRepair(key, "approved-input")
          : budget.recordCleanRebuild(key, "approved-input"), "design-execution"));
    }
    var restored = new RecoveryAttemptLedger(RecoveryAttemptLedger.Limits.defaults());
    var key = restored.key("design-execution", cause, "approved-input", journal);
    assertFalse("repair".equals(strategy) ? restored.mayRepair(journal, key, InputOrigin.TRUSTED)
        : restored.mayCleanRebuild(journal, key));
  }

  @ParameterizedTest
  @ValueSource(strings = {"repair", "reopen", "clean-rebuild"})
  void removingAnotherFindingDoesNotRestoreTheRemainingDefectsBudget(String action) {
    var budget = new RecoveryAttemptLedger(new RecoveryAttemptLedger.Limits(1, 1, 12, 1));
    var first = structuredCause(false, "Invalid method.", "fact-A", "/properties/method");
    var second = structuredCause(false, "Invalid route.", "fact-B", "/properties/route");
    var combined = new RecoveryCause(first.causeCode(),
        List.of(first.findings().getFirst(), second.findings().getFirst()), "");
    var journal = new ArrayList<RunTransition>();
    var key = budget.key("analysis", combined, "brief-a", journal);
    String reason = switch (action) {
      case "repair" -> budget.recordRepair(key, "brief-a");
      case "reopen" -> budget.recordReopen(key, RecoveryAttemptLedger.ReopenInitiator.AUTOMATIC, "brief-a");
      default -> budget.recordCleanRebuild(key, "brief-a");
    };
    journal.add(transition(reason, "analysis"));
    var restored = new RecoveryAttemptLedger(new RecoveryAttemptLedger.Limits(1, 1, 12, 1));
    for (var remaining : List.of(first, second)) {
      var remainingKey = restored.key("analysis", remaining, "brief-a", journal);
      boolean allowed = switch (action) {
        case "repair" -> restored.mayRepair(journal, remainingKey, InputOrigin.TRUSTED);
        case "reopen" -> restored.mayReopen(journal, remainingKey, InputOrigin.TRUSTED,
            RecoveryAttemptLedger.ReopenInitiator.AUTOMATIC, "");
        default -> restored.mayCleanRebuild(journal, remainingKey);
      };
      assertFalse(allowed, "An unchanged defect must retain its spent " + action + " allowance.");
    }
  }

  @Test
  void combinedFindingsRetainEachMembersUnequalGrantedCredit() {
    var budget = new RecoveryAttemptLedger(new RecoveryAttemptLedger.Limits(2, 2, 40, 2));
    var journal = new ArrayList<RunTransition>();
    var first = structuredCause(false, "Invalid method.", "fact-A", "/method");
    var second = structuredCause(false, "Invalid route.", "fact-B", "/route");
    for (int member = 0; member < 2; member++) {
      var key = budget.key("design-execution", member == 0 ? first : second, "approved", journal);
      for (int grant = 0; grant <= member; grant++) {
        journal.add(transition(budget.recordGrant(key, "approved"), "design-execution"));
      }
      for (int spent = 0; spent < 4 + member; spent++) {
        journal.add(transition(budget.recordRepair(key, "approved"), "design-execution"));
        journal.add(transition(budget.recordCleanRebuild(key, "approved"), "design-execution"));
      }
    }
    var combined = new RecoveryCause(first.causeCode(),
        List.of(first.findings().getFirst(), second.findings().getFirst()), "");
    var key = budget.key("design-execution", combined, "approved", journal);
    assertTrue(budget.mayRepair(journal, key, InputOrigin.TRUSTED));
    assertTrue(budget.mayCleanRebuild(journal, key));
    assertEquals(1, budget.remaining(journal, key, InputOrigin.TRUSTED).semanticRepairsRemaining());
    assertEquals(1, budget.remaining(journal, key, InputOrigin.TRUSTED).cleanRebuildsRemaining());
    assertFalse(budget.mayRepair(journal, budget.key("design-execution", first, "approved", journal), InputOrigin.TRUSTED));
  }

  @Test
  void theFirstGrantedAttemptIsDebitedEvenOnSuccessAndRefundedOnlyWhenUnsent() {
    var budget = new RecoveryAttemptLedger(RecoveryAttemptLedger.Limits.defaults());
    var cause = structuredCause(false, "Invalid method.", "fact-A", "/method");
    var journal = new ArrayList<RunTransition>();
    var key = budget.key("design-execution", cause, "approved", journal);
    journal.add(new RunTransition(4, 5, RunStatus.WAITING_FOR_INPUT, RunStatus.RUNNING,
        "design-execution", Instant.EPOCH, budget.recordGrantWithTargetedAttempt(key, "approved")));
    assertEquals(1, budget.repairsUsed(journal, key, InputOrigin.TRUSTED));
    assertEquals(3, budget.remaining(journal, key, InputOrigin.TRUSTED).semanticRepairsRemaining());
    journal.add(new RunTransition(5, 6, RunStatus.RUNNING, RunStatus.RUNNING,
        "design-execution", Instant.EPOCH, "successful correction"));
    assertEquals(1, budget.repairsUsed(journal, key, InputOrigin.TRUSTED));
    journal.add(transition(RecoveryAttemptLedger.UNSENT_ATTEMPT_REASON_PREFIX + "5", "design-execution"));
    assertEquals(0, budget.repairsUsed(journal, key, InputOrigin.TRUSTED));
    assertEquals(4, budget.remaining(journal, key, InputOrigin.TRUSTED).semanticRepairsRemaining());
  }

  @Test
  void aGrantAddsTwoAttemptsToEachStrategyWithoutResettingSpentCreditOrTheRunCeiling() {
    var budget = new RecoveryAttemptLedger(new RecoveryAttemptLedger.Limits(2, 2, 8, 2));
    var journal = new ArrayList<RunTransition>();
    var first = structuredCause(false, "Invalid method.", "fact-A", "/method");
    var second = structuredCause(false, "Invalid path.", "fact-B", "/path");
    var combined = new RecoveryCause(first.causeCode(), List.of(first.findings().getFirst(), second.findings().getFirst()), "");
    var key = budget.key("design-execution", combined, "approved", journal);
    for (int i = 0; i < 2; i++) {
      journal.add(transition(budget.recordRepair(key, "approved"), "design-execution"));
      journal.add(transition(budget.recordCleanRebuild(key, "approved"), "design-execution"));
    }
    journal.add(transition(budget.recordGrant(key, "approved"), "design-execution"));
    for (var cause : List.of(combined, first, second)) {
      var restoredKey = budget.key("design-execution", cause, "approved", journal);
      var remaining = budget.remaining(journal, restoredKey, InputOrigin.TRUSTED);
      assertEquals(2, remaining.semanticRepairsRemaining());
      assertEquals(2, remaining.cleanRebuildsRemaining());
    }
    for (int i = 0; i < 2; i++) {
      assertTrue(budget.mayRepair(journal, key, InputOrigin.TRUSTED));
      journal.add(transition(budget.recordRepair(key, "approved"), "design-execution"));
      assertTrue(budget.mayCleanRebuild(journal, key));
      journal.add(transition(budget.recordCleanRebuild(key, "approved"), "design-execution"));
    }
    journal.add(transition(budget.recordGrant(key, "approved"), "design-execution"));
    assertTrue(budget.perRunCeilingReached(journal));
    assertFalse(budget.mayRepair(journal, key, InputOrigin.TRUSTED));
    assertFalse(budget.mayCleanRebuild(journal, key));
    assertEquals(4, budget.repairsUsed(journal, key, InputOrigin.TRUSTED));
  }

  @Test
  void aNewFindingGetsItsOwnAllowanceWithoutRefundingOlderFindings() {
    var budget = new RecoveryAttemptLedger(new RecoveryAttemptLedger.Limits(1, 1, 12, 1));
    var first = structuredCause(false, "Invalid method.", "fact-A", "/properties/method");
    var second = structuredCause(false, "Invalid route.", "fact-B", "/properties/route");
    var added = structuredCause(false, "Invalid retry.", "fact-C", "/properties/retry");
    var journal = new ArrayList<RunTransition>();
    var combined = new RecoveryCause(first.causeCode(),
        List.of(first.findings().getFirst(), second.findings().getFirst()), "");
    journal.add(transition(budget.recordRepair(budget.key("analysis", combined, "brief-a", journal), "brief-a"),
        "analysis"));
    var changed = new RecoveryCause(first.causeCode(),
        List.of(second.findings().getFirst(), added.findings().getFirst(), added.findings().getFirst()), "");
    var changedKey = budget.key("analysis", changed, "brief-a", journal);
    assertTrue(budget.mayRepair(journal, changedKey, InputOrigin.TRUSTED));
    assertEquals(1, budget.remaining(journal, changedKey, InputOrigin.TRUSTED).semanticRepairsRemaining());
    assertFalse(budget.mayRepair(journal, budget.key("analysis", second, "brief-a", journal), InputOrigin.TRUSTED));
    journal.add(transition(budget.recordRepair(changedKey, "brief-a"), "analysis"));
    var restored = new RecoveryAttemptLedger(new RecoveryAttemptLedger.Limits(1, 1, 12, 1));
    assertFalse(restored.mayRepair(journal, restored.key("analysis", added, "brief-a", journal), InputOrigin.TRUSTED));
    assertEquals(1, restored.repairsUsed(journal, restored.key("analysis", added, "brief-a", journal), InputOrigin.TRUSTED));
    assertFalse(new RecoveryAttemptLedger(new RecoveryAttemptLedger.Limits(1, 1, 2))
        .mayRepair(journal, changedKey, InputOrigin.TRUSTED));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void combiningSeparatelySpentCauseCodesDoesNotCreateAnotherAllowance(boolean reversed) {
    var budget = new RecoveryAttemptLedger(new RecoveryAttemptLedger.Limits(1, 1, 12, 1));
    var unknown = structuredCause(false, "Unknown property.", "node-A", "/properties/method").findings().getFirst();
    var missing = structuredCause(false, "Missing value.", "node-B", "/properties/topic").findings().getFirst();
    var first = new PlanValidationFinding("UNKNOWN_PROPERTY", unknown.message(), true,
        unknown.mappingDetails(), unknown.originRecordId(), unknown.fieldPointer());
    var second = new PlanValidationFinding("MISSING_REQUIRED_PROPERTY", missing.message(), true,
        missing.mappingDetails(), missing.originRecordId(), missing.fieldPointer());
    var journal = new ArrayList<RunTransition>();
    for (var finding : List.of(first, second)) {
      var cause = RecoveryCause.fromFindings(List.of(finding), null);
      journal.add(transition(budget.recordRepair(budget.key("execution", cause, "plan-a", journal), "plan-a"),
          "execution"));
    }
    var combined = RecoveryCause.fromFindings(reversed ? List.of(second, first) : List.of(first, second), null);
    var restored = new RecoveryAttemptLedger(new RecoveryAttemptLedger.Limits(1, 1, 12, 1));
    var key = restored.key("execution", combined, "plan-a", journal);
    assertFalse(restored.mayRepair(journal, key, InputOrigin.TRUSTED));
    assertEquals(0, restored.remaining(journal, key, InputOrigin.TRUSTED).semanticRepairsRemaining());
  }

  @Test
  void aRemainingFindingKeepsItsBudgetWhenAnotherFindingHadSelectedTheAggregateCause() {
    var remaining = structuredCause(false, "Invalid method.", "fact-A", "/properties/method").findings().getFirst();
    var known = new PlanValidationFinding("UNKNOWN_PROPERTY", "Unknown route.", true);
    var initial = RecoveryCause.fromFindings(List.of(known, remaining), StageOutcomeClass.CONTRACT_FAILURE);
    var journal = new ArrayList<RunTransition>();
    journal.add(transition(ledger.recordRepair(ledger.key("analysis", initial, "brief-a", journal), "brief-a"),
        "analysis"));
    var next = RecoveryCause.fromFindings(List.of(remaining), StageOutcomeClass.CONTRACT_FAILURE);
    assertNotEquals(initial.causeCode(), next.causeCode());
    var restored = new RecoveryAttemptLedger(ledger.limits());
    assertFalse(restored.mayRepair(journal, restored.key("analysis", next, "brief-a", journal), InputOrigin.TRUSTED));
  }

  @Test
  void aChangedApprovedInputAdvancesEachMembersEpochAfterTheSetShrinks() {
    var first = structuredCause(false, "Invalid method.", "fact-A", "/properties/method");
    var second = structuredCause(false, "Invalid route.", "fact-B", "/properties/route");
    var combined = new RecoveryCause(first.causeCode(),
        List.of(first.findings().getFirst(), second.findings().getFirst()), "");
    var journal = new ArrayList<RunTransition>();
    var initial = ledger.key("analysis", combined, "brief-a", journal);
    journal.add(transition(ledger.recordRepair(initial, "brief-a"), "analysis"));
    var corrected = ledger.key("analysis", second, "brief-b", journal);
    assertEquals(1, corrected.correctionEpoch());
    assertTrue(ledger.mayRepair(journal, corrected, InputOrigin.TRUSTED));
    assertFalse(ledger.mayRepair(journal, corrected, InputOrigin.UNTRUSTED));
    journal.add(transition(ledger.recordCorrection(journal, corrected, "brief-b", InputOrigin.TRUSTED), "analysis"));
    journal.add(transition(ledger.recordRepair(corrected, "brief-b"), "analysis"));
    var restored = new RecoveryAttemptLedger(ledger.limits());
    assertFalse(restored.mayRepair(journal, restored.key("analysis", second, "brief-b", journal), InputOrigin.TRUSTED));
    assertTrue(restored.mayRepair(journal, restored.key("analysis", first, "brief-b", journal), InputOrigin.TRUSTED));
  }

  @Test
  void aLegacyAggregateJournalEntryRemainsSpentForTheSameFindingSet() {
    var first = structuredCause(false, "Invalid method.", "fact-A", "/properties/method");
    var second = structuredCause(false, "Invalid route.", "fact-B", "/properties/route");
    var combined = new RecoveryCause(first.causeCode(),
        List.of(first.findings().getFirst(), second.findings().getFirst()), "");
    var initial = ledger.key("analysis", combined, "brief-a", List.of());
    var legacy = new RecoveryAttemptKey(initial.ownerStageId(), initial.causeCode(), initial.evidenceIdentity(), 0);
    var journal = List.of(transition(ledger.recordRepair(legacy, "brief-a"), "analysis"));
    var restored = new RecoveryAttemptLedger(ledger.limits());
    assertFalse(restored.mayRepair(journal, restored.key("analysis", combined, "brief-a", journal), InputOrigin.TRUSTED));
  }

  @Test
  void malformedPersistedMemberBudgetsCannotRefundAnAttempt() {
    var first = structuredCause(false, "Invalid method.", "fact-A", "/properties/method");
    var second = structuredCause(false, "Invalid route.", "fact-B", "/properties/route");
    var combined = new RecoveryCause(first.causeCode(),
        List.of(first.findings().getFirst(), second.findings().getFirst()), "");
    var key = ledger.key("analysis", combined, "brief-a", List.of());
    var journal = List.of(transition(ledger.recordRepair(key, "brief-a") + '\u0000' + "incomplete", "analysis"));
    assertThrows(IllegalStateException.class, () -> new RecoveryAttemptLedger().mayRepair(journal, key, InputOrigin.TRUSTED));
  }

  @Test
  void legacyFindingRetainsTheBudgetKeyAlreadyStoredInTheJournal() {
    var prior = new RecoveryAttemptKey("analysis", RecoveryCauseCode.CONTRACT_SHAPE,
        "ed4e751f3d6c34af6f881203cf28f626a3e9afe149d7f08298fa990e04649b0c", 0);
    var journal = List.of(transition(ledger.recordRepair(prior, "brief-a"), "analysis"));
    var legacy = new RecoveryCause(RecoveryCauseCode.CONTRACT_SHAPE,
        List.of(new PlanValidationFinding("INVALID_REQUESTED_PROPERTY", "The generated value is invalid.", true)), "");
    var restored = new RecoveryAttemptLedger(ledger.limits());
    var key = restored.key("analysis", legacy, "brief-a", journal);
    assertEquals(prior, new RecoveryAttemptKey(key.ownerStageId(), key.causeCode(), key.evidenceIdentity(), key.correctionEpoch()));
    assertFalse(restored.mayRepair(journal, key, InputOrigin.TRUSTED));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void sameStructuredDefectKeepsSpentBudgetWhenWordingChanges(boolean mapping) {
    var first = structuredCause(mapping, "The generated value is invalid.", "fact-A", "/properties/method");
    var journal = new ArrayList<RunTransition>();
    var key = ledger.key("analysis", first, "brief-a", journal);
    journal.add(transition(ledger.recordRepair(key, "brief-a"), "analysis"));
    var restored = new RecoveryAttemptLedger(ledger.limits());
    var reworded = structuredCause(mapping, "Correct this value before continuing.", "fact-A", "/properties/method");
    var sameKey = restored.key("analysis", reworded, "brief-a", journal);
    assertEquals(key, sameKey);
    assertFalse(restored.mayRepair(journal, sameKey, InputOrigin.TRUSTED));

    var otherField = restored.key("analysis",
        structuredCause(mapping, "The generated value is invalid.", "fact-A", "/properties/route"), "brief-a", journal);
    var otherRecord = restored.key("analysis",
        structuredCause(mapping, "The generated value is invalid.", "fact-a", "/properties/method"), "brief-a", journal);
    assertNotEquals(key, otherField);
    assertNotEquals(key, otherRecord);
    assertTrue(restored.mayRepair(journal, otherField, InputOrigin.TRUSTED));
    assertTrue(restored.mayRepair(journal, otherRecord, InputOrigin.TRUSTED));
  }

  private static RecoveryCause structuredCause(boolean mapping, String message, String recordId, String pointer) {
    var fields = new java.util.LinkedHashMap<String, Object>();
    fields.put("code", mapping ? "MAPPING_UNKNOWN_TARGET" : "INVALID_REQUESTED_PROPERTY");
    fields.put("message", message);
    fields.put("blocker", true);
    fields.put("originRecordId", recordId);
    fields.put("fieldPointer", pointer);
    if (mapping) {
      fields.put("mappingDetails", Map.of("mappingIntentId", recordId, "targetPath", pointer,
          "sourceRef", "source", "sourcePort", "OUTPUT", "targetRef", "target", "targetPort", "REQUEST"));
    }
    var finding = new ObjectMapper().convertValue(fields, PlanValidationFinding.class);
    return new RecoveryCause(mapping ? RecoveryCauseCode.MAPPING_CONTRACT : RecoveryCauseCode.CONTRACT_SHAPE,
        List.of(finding), "");
  }

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
  void cleanRebuildHasASeparateOneShotBudgetThatSurvivesRestart() {
    List<RunTransition> journal = new ArrayList<>();
    RecoveryAttemptKey key = ledger.key("design-planning", UNKNOWN_PROPERTY, "brief-a", journal);
    journal.add(transition(ledger.recordRepair(key, "brief-a"), "design-planning"));

    assertFalse(ledger.mayRepair(journal, key, InputOrigin.TRUSTED));
    assertTrue(ledger.mayCleanRebuild(journal, key));
    journal.add(
        transition(ledger.recordCleanRebuild(key, "brief-a"), "design-planning"));

    RecoveryAttemptLedger restarted = new RecoveryAttemptLedger(ledger.limits());
    assertFalse(restarted.mayCleanRebuild(journal, key));
    assertFalse(restarted.mayRepair(journal, key, InputOrigin.TRUSTED));
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

    RecoveryAttemptLedger restarted = new RecoveryAttemptLedger(ledger.limits());
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
  void authorMayReopenMissingBriefFactsAfterAutomaticReplay() {
    RecoveryCause missing =
        RecoveryCause.missingBriefFacts(List.of("$.preserved.executionId"));
    List<RunTransition> journal = new ArrayList<>();
    RecoveryAttemptKey key = ledger.key("requirement-analysis", missing, "brief-a", journal);
    journal.add(
        transition(
            ledger.recordReopen(
                key, RecoveryAttemptLedger.ReopenInitiator.AUTOMATIC, "brief-a"),
            "requirement-analysis"));
    assertTrue(ledger.ownerAlreadyReopened(journal, key, ""));
    assertTrue(ledger.automaticReopenRecorded(journal, key, ""));
    assertTrue(
        ledger.mayReopen(
            journal,
            key,
            InputOrigin.TRUSTED,
            RecoveryAttemptLedger.ReopenInitiator.AUTHOR,
            ""));
    assertFalse(
        ledger.mayReopen(
            journal,
            key,
            InputOrigin.TRUSTED,
            RecoveryAttemptLedger.ReopenInitiator.AUTOMATIC,
            ""));
    assertEquals(
        1,
        ledger.remaining(journal, key, InputOrigin.TRUSTED).causalReopensRemaining());
  }

  @Test
  void authorMayReopenMappingContractAfterAutomaticReplay() {
    RecoveryCause mapping =
        RecoveryCause.mappingContract(
            List.of(
                new PlanValidationFinding(
                    "MAPPING_UNKNOWN_TARGET",
                    "Target path $.preserved.executionId is absent from the target contract.",
                    true)));
    List<RunTransition> journal = new ArrayList<>();
    RecoveryAttemptKey key = ledger.key("requirement-analysis", mapping, "brief-a", journal);
    journal.add(
        transition(
            ledger.recordReopen(
                key, RecoveryAttemptLedger.ReopenInitiator.AUTOMATIC, "brief-a"),
            "requirement-analysis"));
    assertTrue(ledger.ownerAlreadyReopened(journal, key, ""));
    assertTrue(ledger.automaticReopenRecorded(journal, key, ""));
    assertTrue(
        ledger.mayReopen(
            journal,
            key,
            InputOrigin.TRUSTED,
            RecoveryAttemptLedger.ReopenInitiator.AUTHOR,
            ""));
    assertFalse(
        ledger.mayReopen(
            journal,
            key,
            InputOrigin.TRUSTED,
            RecoveryAttemptLedger.ReopenInitiator.AUTOMATIC,
            ""));
    assertEquals(
        1,
        ledger.remaining(journal, key, InputOrigin.TRUSTED).causalReopensRemaining());
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
  void documentCauseKeyKeepsOneSlotAcrossStageNames() {
    RecoveryAttemptLedger budget =
        new RecoveryAttemptLedger(new RecoveryAttemptLedger.Limits(3, 2, 12));
    String identity = "run\0rule-priority\0WRONG_OPERATION\0behavior";
    RecoveryAttemptKey key = budget.documentCauseKey(identity);
    assertEquals(RecoveryAttemptLedger.DOCUMENT_CAUSE_OWNER, key.ownerStageId());
    assertFalse(key.evidenceIdentity().contains("DATA_BEHAVIOR"));
    List<RunTransition> journal = new ArrayList<>();
    journal.add(transition(budget.recordRepair(key, ""), "DATA_BEHAVIOR"));
    journal.add(transition(budget.recordRepair(key, ""), "SERVICES"));
    journal.add(transition(budget.recordRepair(key, ""), "LOGICAL_FLOW"));
    assertFalse(budget.mayRepair(journal, key, InputOrigin.TRUSTED));
    assertEquals(0, budget.remaining(journal, key, InputOrigin.TRUSTED).semanticRepairsRemaining());
    RecoveryAttemptLedger restarted =
        new RecoveryAttemptLedger(new RecoveryAttemptLedger.Limits(3, 2, 12));
    assertFalse(
        restarted.mayRepair(journal, restarted.documentCauseKey(identity), InputOrigin.TRUSTED));
  }

  @Test
  void technicalRetriesUseTheCeilingWithoutSpendingTheCorrectiveBudget() {
    RecoveryAttemptLedger open =
        new RecoveryAttemptLedger(new RecoveryAttemptLedger.Limits(3, 2, 12));
    RecoveryAttemptKey cause = open.documentCauseKey("run\0create\0WRONG_OPERATION\0binding");
    RecoveryAttemptKey technical = open.technicalKey("run");
    List<RunTransition> journal = new ArrayList<>();
    journal.add(transition(open.recordTechnicalRetry(technical), "DATA_BEHAVIOR"));
    journal.add(transition(open.recordTechnicalRetry(technical), "DATA_BEHAVIOR"));
    assertTrue(open.mayTechnicalRetry(journal, technical, 5));
    assertFalse(open.mayTechnicalRetry(journal, technical, 2));
    assertEquals(0, open.repairsUsed(journal, cause, InputOrigin.TRUSTED));
    assertEquals(3, open.remaining(journal, cause, InputOrigin.TRUSTED).semanticRepairsRemaining());

    RecoveryAttemptLedger tight =
        new RecoveryAttemptLedger(new RecoveryAttemptLedger.Limits(3, 2, 2));
    assertFalse(tight.mayTechnicalRetry(journal, technical, 5));
    assertEquals(0, tight.repairsUsed(journal, cause, InputOrigin.TRUSTED));
  }

  @Test
  void remainingFeedsTheSemanticRecoveryTuple() {
    List<RunTransition> journal = new ArrayList<>();
    RecoveryAttemptKey key = ledger.key("analysis", UNKNOWN_PROPERTY, "brief-a", journal);
    SemanticRecoveryState.RemainingAttempts remaining =
        ledger.remaining(journal, key, InputOrigin.TRUSTED);
    assertEquals(ledger.limits().maxSemanticRepairs(), remaining.semanticRepairsRemaining());
    assertEquals(ledger.limits().maxCausalReopens(), remaining.causalReopensRemaining());
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
