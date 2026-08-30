package org.qubership.integration.platform.ai.productpipeline.create;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.productpipeline.artifact.PlanValidationFinding;
import org.qubership.integration.platform.ai.productpipeline.artifact.PlanValidationResult;
import org.qubership.integration.platform.ai.productpipeline.capability.ArtifactCandidate;
import org.qubership.integration.platform.ai.productpipeline.capability.RecoveryCause;
import org.qubership.integration.platform.ai.productpipeline.capability.RecoveryCauseCode;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcomeClass;
import org.qubership.integration.platform.ai.productpipeline.recovery.RecoveryAction;
import org.qubership.integration.platform.ai.productpipeline.recovery.RecoveryCauseClass;
import org.qubership.integration.platform.ai.productpipeline.recovery.RecoveryContext;
import org.qubership.integration.platform.ai.productpipeline.recovery.RecoveryDecision;
import org.qubership.integration.platform.ai.productpipeline.recovery.RecoveryEvidence;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.validation.ValidationIssue;
import org.qubership.integration.platform.ai.qipknowledge.validation.ValidationResult;
import org.qubership.integration.platform.ai.qipknowledge.validation.ValidationSeverity;

class FailureNarrativeTest {

  @Test
  void returnsTheModelTextAndPassesOnlyStructuredEvidence() {
    FakeFailureNarrativeAgent agent =
        FakeFailureNarrativeAgent.narrates(
            "The catalog call timed out while looking up the service.");

    Optional<String> narrated =
        new FailureNarrative(agent)
            .narrate(
                "run-1",
                "en",
                "work",
                StageOutcomeClass.DOMAIN_FAILURE,
                "bad domain",
                "PLAN_BLOCKER: missing quartz",
                "use a different service");

    assertEquals("The catalog call timed out while looking up the service.", narrated.orElseThrow());
    assertEquals("DOMAIN_FAILURE", agent.lastOutcome.get());
    assertEquals("use a different service", agent.lastFollowUp.get());
  }

  @Test
  void emptyWhenTheTurnFailsSoTheCallerKeepsRawEvidence() {
    assertTrue(
        new FailureNarrative(FakeFailureNarrativeAgent.boom())
            .narrate("run-1", "en", "work", StageOutcomeClass.DOMAIN_FAILURE, "bad domain", "")
            .isEmpty());
    assertTrue(
        new FailureNarrative()
            .narrate("run-1", "en", "work", StageOutcomeClass.DOMAIN_FAILURE, "bad domain", "")
            .isEmpty());
  }

  @Test
  void emptyWhenTheModelReturnsBlank() {
    assertTrue(
        new FailureNarrative(FakeFailureNarrativeAgent.narrates("  "))
            .narrate(
                "run-1",
                "en",
                "work",
                StageOutcomeClass.RETRYABLE_TECHNICAL_FAILURE,
                "timeout",
                "")
            .isEmpty());
  }

  @Test
  void narrateStopsCallingTheModelOnceTheRunSpendsItsBudget() {
    FakeFailureNarrativeAgent agent = FakeFailureNarrativeAgent.narrates("The catalog timed out.");
    FailureNarrative narrative = new FailureNarrative(agent, 1, null);

    Optional<String> first = narrate(narrative, "run-1");
    Optional<String> afterTheBudget = narrate(narrative, "run-1");

    assertEquals("The catalog timed out.", first.orElseThrow());
    assertTrue(afterTheBudget.isEmpty());
    assertEquals(1, agent.calls.get());
    assertTrue(narrative.explanationBudgetSpent("run-1"));
  }

  @Test
  void theNarrativeBudgetIsCountedPerRun() {
    FakeFailureNarrativeAgent agent = FakeFailureNarrativeAgent.narrates("The catalog timed out.");
    FailureNarrative narrative = new FailureNarrative(agent, 1, null);

    narrate(narrative, "run-1");
    narrate(narrative, "run-1");
    Optional<String> secondRun = narrate(narrative, "run-2");

    assertEquals("The catalog timed out.", secondRun.orElseThrow());
    assertEquals(2, agent.calls.get());
  }

  @Test
  void narrateIsEmptyWhenTheTurnOutlivesItsTimeout() {
    FakeFailureNarrativeAgent agent =
        FakeFailureNarrativeAgent.slow("Too late for the card.", Duration.ofSeconds(30));

    Optional<String> narrated =
        narrate(new FailureNarrative(agent, 12, Duration.ofMillis(50)), "run-1");

    assertTrue(narrated.isEmpty());
    assertEquals(1, agent.calls.get());
  }

  @Test
  void diagnoseKeepsRawEvidenceOnceTheRunSpendsItsBudget() {
    FakeFailureNarrativeAgent agent =
        FakeFailureNarrativeAgent.owner("The brief omitted the scheduler.", "analysis");
    FailureNarrative narrative = new FailureNarrative(agent, 1, null);

    OwnerDiagnosis first = diagnose(narrative, "run-1");
    OwnerDiagnosis afterTheBudget = diagnose(narrative, "run-1");

    assertEquals("The brief omitted the scheduler.", first.narrative());
    assertEquals("", afterTheBudget.narrative());
    assertEquals(1, agent.calls.get());
  }

  @Test
  void diagnoseKeepsRawEvidenceWhenTheTurnOutlivesItsTimeout() {
    FakeFailureNarrativeAgent agent =
        FakeFailureNarrativeAgent.slow("Too late for the card.", Duration.ofSeconds(30));

    OwnerDiagnosis diagnosis =
        diagnose(new FailureNarrative(agent, 12, Duration.ofMillis(50)), "run-1");

    assertEquals("", diagnosis.narrative());
    assertEquals(1, agent.calls.get());
  }

  @Test
  void diagnosePassesTheCandidateListAndFollowUpAndTheRouterPicksTheOwner() {
    FakeFailureNarrativeAgent agent =
        FakeFailureNarrativeAgent.owner("The brief omitted the scheduler.", "compiler");
    List<OwnerCandidate> candidates =
        List.of(
            new OwnerCandidate("planning", "plan-validation-result"),
            new OwnerCandidate("analysis", "requirement-brief"));

    OwnerDiagnosis diagnosis =
        new FailureNarrative(agent)
            .diagnose(
                "run-1",
                "en",
                "planning",
                StageOutcomeClass.VALIDATION_FAILURE,
                "planning validation failed",
                "security-1: External route requires accessControlType=RBAC (blocker)",
                candidates,
                "the quartz job is required");

    assertEquals("The brief omitted the scheduler.", diagnosis.narrative());
    assertEquals("analysis", diagnosis.owner().orElseThrow());
    assertFalse(diagnosis.ambiguous());
    assertEquals(
        "planning:plan-validation-result,analysis:requirement-brief", agent.lastCandidateSet.get());
    assertEquals("the quartz job is required", agent.lastFollowUp.get());
    assertEquals("VALIDATION_FAILURE", agent.lastOutcome.get());
  }

  @Test
  void diagnoseIgnoresAModelOwnerOutsideTheCandidateSetAndBindsTheOnlyCandidate() {
    FakeFailureNarrativeAgent agent =
        FakeFailureNarrativeAgent.owner("Blaming compiler.", "compiler");
    OwnerDiagnosis diagnosis =
        new FailureNarrative(agent)
            .diagnose(
                "run-1",
                "en",
                "planning",
                StageOutcomeClass.VALIDATION_FAILURE,
                "failed",
                "",
                List.of(new OwnerCandidate("planning", "plan-validation-result")),
                "");

    assertEquals("Blaming compiler.", diagnosis.narrative());
    assertEquals("planning", diagnosis.owner().orElseThrow());
    assertFalse(diagnosis.ambiguous());
  }

  @Test
  void diagnoseAsksWhenTheCauseDoesNotNameAUniqueOwner() {
    FakeFailureNarrativeAgent agent = FakeFailureNarrativeAgent.ask("Either artifact could be wrong.");
    OwnerDiagnosis diagnosis =
        new FailureNarrative(agent)
            .diagnose(
                "run-1",
                "en",
                "planning",
                StageOutcomeClass.DOMAIN_FAILURE,
                "failed",
                "",
                List.of(
                    new OwnerCandidate("planning", "plan-validation-result"),
                    new OwnerCandidate("analysis", "requirement-brief")),
                "");

    assertTrue(diagnosis.ambiguous());
    assertTrue(diagnosis.owner().isEmpty());
    assertEquals("Either artifact could be wrong.", diagnosis.narrative());
    assertEquals("Pick which artifact to revise.", diagnosis.instruction());
  }

  @Test
  void diagnoseKeepsRouterOwnerWhenTheTurnFails() {
    OwnerDiagnosis diagnosis =
        new FailureNarrative(FakeFailureNarrativeAgent.boom())
            .diagnose(
                "run-1",
                "en",
                "work",
                StageOutcomeClass.DOMAIN_FAILURE,
                "bad domain",
                "",
                List.of(new OwnerCandidate("work", "")),
                "");
    assertEquals("", diagnosis.narrative());
    assertEquals("work", diagnosis.owner().orElseThrow());
    assertEquals("Correct the domain error in the owning artifact.", diagnosis.instruction());
  }

  @Test
  void diagnoseRemapsASelfOwnerToTheBriefProducerForPolicyFindings() {
    FakeFailureNarrativeAgent agent =
        FakeFailureNarrativeAgent.owner("Design execution could not complete.", "design-execution");
    List<OwnerCandidate> candidates =
        List.of(
            new OwnerCandidate("design-execution", "plan-validation-result"),
            new OwnerCandidate("design-planning", "implementation-plan"),
            new OwnerCandidate("requirement-analysis", "requirement-brief"));

    OwnerDiagnosis diagnosis =
        new FailureNarrative(agent)
            .diagnose(
                "run-1",
                "en",
                "design-execution",
                StageOutcomeClass.VALIDATION_FAILURE,
                "Phase 5 plan validation failed",
                "security-1: External route requires accessControlType=RBAC (blocker)",
                candidates,
                "add rbac");

    assertEquals("Design execution could not complete.", diagnosis.narrative());
    assertEquals("requirement-analysis", diagnosis.owner().orElseThrow());
    assertFalse(diagnosis.ambiguous());
    assertEquals("add rbac", agent.lastFollowUp.get());
  }

  @Test
  void diagnoseRemapsASelfOwnerToThePlanProducerWhenBriefIsAbsent() {
    FakeFailureNarrativeAgent agent =
        FakeFailureNarrativeAgent.owner("Design execution could not complete.", "design-execution");
    List<OwnerCandidate> candidates =
        List.of(
            new OwnerCandidate("design-execution", "plan-validation-result"),
            new OwnerCandidate("design-planning", "implementation-plan"));

    OwnerDiagnosis diagnosis =
        new FailureNarrative(agent)
            .diagnose(
                "run-1",
                "en",
                "design-execution",
                StageOutcomeClass.VALIDATION_FAILURE,
                "Phase 5 plan validation failed",
                "security-1: External route requires accessControlType=RBAC (blocker)",
                candidates,
                "add rbac");

    assertEquals("design-planning", diagnosis.owner().orElseThrow());
  }

  @Test
  void diagnoseRemapsASelfOwnerToThePlanProducerForPlanFillFindings() {
    FakeFailureNarrativeAgent agent =
        FakeFailureNarrativeAgent.owner("Design execution could not complete.", "design-execution");

    OwnerDiagnosis diagnosis =
        new FailureNarrative(agent)
            .diagnose(
                "run-1",
                "en",
                "design-execution",
                StageOutcomeClass.VALIDATION_FAILURE,
                "Phase 5 plan validation failed",
                "plan-1: Missing required property on http-trigger (blocker)",
                List.of(
                    new OwnerCandidate("design-execution", "plan-validation-result"),
                    new OwnerCandidate("design-planning", "implementation-plan"),
                    new OwnerCandidate("requirement-analysis", "requirement-brief")),
                "",
                RecoveryCause.of(RecoveryCauseCode.MISSING_REQUIRED_PROPERTY));

    assertEquals("design-planning", diagnosis.owner().orElseThrow());
  }

  @Test
  void diagnoseKeepsAnEarlierOwnerWhenFindingsArePolicyAndOwnerIsBrief() {
    FakeFailureNarrativeAgent agent =
        FakeFailureNarrativeAgent.owner("The brief omitted RBAC.", "requirement-analysis");
    OwnerDiagnosis diagnosis =
        new FailureNarrative(agent)
            .diagnose(
                "run-1",
                "en",
                "design-execution",
                StageOutcomeClass.VALIDATION_FAILURE,
                "Phase 5 plan validation failed",
                "security-1: External route requires accessControlType=RBAC (blocker)",
                List.of(
                    new OwnerCandidate("design-execution", "plan-validation-result"),
                    new OwnerCandidate("design-planning", "implementation-plan"),
                    new OwnerCandidate("requirement-analysis", "requirement-brief")),
                "");

    assertEquals("requirement-analysis", diagnosis.owner().orElseThrow());
  }

  @Test
  void diagnosePassesClarifyRolesAndFakeOffersGoBack() {
    FakeFailureNarrativeAgent agent =
        FakeFailureNarrativeAgent.offeringGoBack(
            "Validation failed: the external route needs RBAC.", "design-planning");
    List<OwnerCandidate> candidates =
        List.of(
            new OwnerCandidate("design-execution", "plan-validation-result"),
            new OwnerCandidate("design-planning", "implementation-plan"),
            new OwnerCandidate("requirement-analysis", "requirement-brief"));

    OwnerDiagnosis diagnosis =
        new FailureNarrative(agent)
            .diagnose(
                "run-1",
                "en",
                "design-execution",
                StageOutcomeClass.VALIDATION_FAILURE,
                "Phase 5 plan validation failed",
                "security-1: External route requires accessControlType=RBAC (blocker)",
                candidates,
                "");

    assertTrue(diagnosis.narrative().contains(FakeFailureNarrativeAgent.GO_BACK_OFFER));
    assertEquals("requirement-analysis", diagnosis.owner().orElseThrow());
    assertEquals("VALIDATION_FAILURE", agent.lastOutcome.get());
    assertTrue(agent.lastFindings.get().toLowerCase(Locale.ROOT).contains("rbac"));
    assertTrue(agent.lastClarifyRoles.get().contains("design-planning:the plan"));
    assertTrue(agent.lastClarifyRoles.get().contains("requirement-analysis:requirements"));
  }

  @Test
  void diagnosePrefersAUserNamedOwnerOverTheEarliestSufficientRemap() {
    FakeFailureNarrativeAgent agent =
        FakeFailureNarrativeAgent.owner("Design execution could not complete.", "design-execution");
    List<OwnerCandidate> candidates =
        List.of(
            new OwnerCandidate("design-execution", "plan-validation-result"),
            new OwnerCandidate("design-planning", "implementation-plan"),
            new OwnerCandidate("requirement-analysis", "requirement-brief"));

    OwnerDiagnosis diagnosis =
        new FailureNarrative(agent)
            .diagnose(
                "run-1",
                "en",
                "design-execution",
                StageOutcomeClass.VALIDATION_FAILURE,
                "Phase 5 plan validation failed",
                "security-1: External route requires accessControlType=RBAC (blocker)",
                candidates,
                "go back to requirements gathering and add that we need RBAC");

    assertEquals("requirement-analysis", diagnosis.owner().orElseThrow());
    assertFalse(diagnosis.ambiguous());
  }

  @Test
  void diagnoseSelectsTheBriefWhenSecurityFindingsArePresentEvenIfTheModelAsks() {
    FakeFailureNarrativeAgent agent =
        FakeFailureNarrativeAgent.ask("Either the brief or the plan could be wrong.");

    OwnerDiagnosis diagnosis =
        new FailureNarrative(agent)
            .diagnose(
                "run-1",
                "en",
                "design-execution",
                StageOutcomeClass.VALIDATION_FAILURE,
                "Phase 5 plan validation failed",
                "security-1: External route requires accessControlType=RBAC (blocker)",
                List.of(
                    new OwnerCandidate("design-execution", "plan-validation-result"),
                    new OwnerCandidate("design-planning", "implementation-plan"),
                    new OwnerCandidate("requirement-analysis", "requirement-brief")),
                "");

    assertFalse(diagnosis.ambiguous());
    assertEquals("requirement-analysis", diagnosis.owner().orElseThrow());
  }

  @Test
  void diagnoseRemapsEmptyOwnerUsingSecurityIssueIdFromBuildValidationResult() {
    var issue =
        new ValidationIssue(
            "security-1",
            ValidationSeverity.BLOCKER,
            "External route RBAC requires a non-empty roles list",
            "cip-security-validator",
            List.of("http-trigger-1"),
            List.of(),
            "Configure one or more explicit RBAC roles");
    PlanValidationResult planValidation =
        CompilerPlanningRunner.buildValidationResult(
            new ValidationResult(
                false, List.of(issue), "security validation failed with 1 blocker(s)"),
            List.of());
    String findings =
        FailureNarrative.findingsText(
            List.of(
                new ArtifactCandidate(Kind.PLAN_VALIDATION_RESULT, planValidation, List.of())));
    FakeFailureNarrativeAgent agent =
        FakeFailureNarrativeAgent.owner("Validation failed during Phase 5.", "");

    OwnerDiagnosis diagnosis =
        new FailureNarrative(agent)
            .diagnose(
                "run-1",
                "en",
                "design-execution",
                StageOutcomeClass.VALIDATION_FAILURE,
                "Phase 5 plan validation failed. Findings: " + findings,
                findings,
                List.of(
                    new OwnerCandidate("design-execution", "plan-validation-result"),
                    new OwnerCandidate("design-planning", "implementation-plan"),
                    new OwnerCandidate("requirement-analysis", "requirement-brief")),
                "");

    assertTrue(findings.toLowerCase(Locale.ROOT).startsWith("security-1:"));
    assertEquals("requirement-analysis", diagnosis.owner().orElseThrow());
  }

  @Test
  void diagnoseAuthorsTheInstructionFromTheTypedCauseNotTheModel() {
    FakeFailureNarrativeAgent agent =
        FakeFailureNarrativeAgent.owner("The brief omitted the scheduler.", "analysis")
            .remedying("REVISE_INPUT", "Add the nightly schedule to the requirements.");

    OwnerDiagnosis diagnosis = diagnose(new FailureNarrative(agent), "run-1");

    assertEquals("analysis", diagnosis.owner().orElseThrow());
    assertEquals("State the access policy in the requirements.", diagnosis.instruction());
    assertEquals(
        "The brief omitted the scheduler.\n\nState the access policy in the requirements.",
        diagnosis.cardBody("raw evidence"));
  }

  @Test
  void diagnoseKeepsTheRuntimeInstructionWhenTheModelNamesNoChange() {
    FakeFailureNarrativeAgent agent =
        FakeFailureNarrativeAgent.owner("The brief omitted the scheduler.", "analysis");

    OwnerDiagnosis diagnosis = diagnose(new FailureNarrative(agent), "run-1");

    assertEquals("State the access policy in the requirements.", diagnosis.instruction());
    assertEquals("The brief omitted the scheduler.", diagnosis.narrative());
    assertEquals("analysis", diagnosis.owner().orElseThrow());
  }

  @Test
  void diagnoseKeepsTheRuntimeInstructionWhenTheTurnFails() {
    FakeFailureNarrativeAgent agent = FakeFailureNarrativeAgent.boom();

    OwnerDiagnosis diagnosis = diagnose(new FailureNarrative(agent), "run-1");

    assertEquals("State the access policy in the requirements.", diagnosis.instruction());
    assertEquals(
        "planning validation failed\n\nState the access policy in the requirements.",
        diagnosis.cardBody("planning validation failed"));
    assertEquals("analysis", diagnosis.owner().orElseThrow());
  }

  @Test
  void diagnoseKeepsTheRuntimeInstructionOnceTheRunSpendsItsBudget() {
    FakeFailureNarrativeAgent agent =
        FakeFailureNarrativeAgent.owner("The brief omitted the scheduler.", "analysis");
    FailureNarrative narrative = new FailureNarrative(agent, 1, null);

    diagnose(narrative, "run-1");
    OwnerDiagnosis afterTheBudget = diagnose(narrative, "run-1");

    assertEquals("", afterTheBudget.narrative());
    assertEquals("analysis", afterTheBudget.owner().orElseThrow());
    assertEquals("State the access policy in the requirements.", afterTheBudget.instruction());
    assertEquals(1, agent.calls.get());
  }

  @Test
  void findingsTextFormatsValidationCandidates() {
    String text =
        FailureNarrative.findingsText(
            List.of(
                new ArtifactCandidate(
                    Kind.PLAN_VALIDATION_RESULT,
                    new PlanValidationResult(
                        List.of(new PlanValidationFinding("PLAN_BLOCKER", "missing quartz", true))),
                    List.of())));
    assertEquals("PLAN_BLOCKER: missing quartz (blocker)", text);
  }

  @Test
  void answersAHaltQuestionWithWhatTheModelWroteAndNothingSubstituted() {
    FakeFailureNarrativeAgent agent =
        FakeFailureNarrativeAgent.narrates("")
            .answering("The evidence does not say which service was unreachable.");

    PauseQuestionResult answered = ask(new FailureNarrative(agent), "run-1", "why did this stop?");

    assertTrue(answered.isAnswer());
    assertEquals(
        "The evidence does not say which service was unreachable.", answered.answer());
    assertEquals("why did this stop?", agent.lastQuestion.get());
    assertEquals("VALIDATION_FAILURE", agent.lastOutcome.get());
  }

  @Test
  void aHaltMessageReadAsAnInstructionLeavesTheCallerOnItsFollowUpPaths() {
    FakeFailureNarrativeAgent agent = FakeFailureNarrativeAgent.narrates("");

    assertTrue(ask(new FailureNarrative(agent), "run-1", "drop the scheduler step").isNotAQuestion());
    assertEquals(1, agent.questionCalls.get());
  }

  @Test
  void aVerdictOutsideTheClosedPairIsUnanswerable() {
    FakeFailureNarrativeAgent agent =
        FakeFailureNarrativeAgent.narrates("").answeringUnder("MAYBE", "Half an answer.");

    assertTrue(ask(new FailureNarrative(agent), "run-1", "why did this stop?").isUnanswerable());
  }

  @Test
  void aBlankQuestionAnswerIsUnanswerable() {
    FakeFailureNarrativeAgent agent = FakeFailureNarrativeAgent.narrates("").answering("   ");

    assertTrue(ask(new FailureNarrative(agent), "run-1", "why did this stop?").isUnanswerable());
  }

  @Test
  void theSameQuestionAgainstUnchangedEvidenceIsAnsweredWithoutASecondCall() {
    FakeFailureNarrativeAgent agent =
        FakeFailureNarrativeAgent.narrates("").answering("The plan asked for an unknown element.");
    FailureNarrative narrative = new FailureNarrative(agent);

    PauseQuestionResult first = ask(narrative, "run-1", "why did this stop?");
    PauseQuestionResult again = ask(narrative, "run-1", "Why did this stop?  ");

    assertEquals(first.answer(), again.answer());
    assertEquals(1, agent.questionCalls.get());
  }

  @Test
  void evidenceThatHasMovedOnEarnsAFreshAnswer() {
    FakeFailureNarrativeAgent agent =
        FakeFailureNarrativeAgent.narrates("").answering("The plan asked for an unknown element.");
    FailureNarrative narrative = new FailureNarrative(agent);

    ask(narrative, "run-1", "why did this stop?");
    ask(narrative, "run-1", "why did this stop?", "the catalog refused the write");

    assertEquals(2, agent.questionCalls.get());
  }

  @Test
  void aCacheHitDoesNotSpendTheNarrationBudget() {
    FakeFailureNarrativeAgent agent =
        FakeFailureNarrativeAgent.narrates("The catalog timed out.")
            .answering("The plan asked for an unknown element.");
    FailureNarrative narrative = new FailureNarrative(agent, 2, null);

    ask(narrative, "run-1", "why did this stop?");
    ask(narrative, "run-1", "why did this stop?");

    assertEquals("The catalog timed out.", narrate(narrative, "run-1").orElseThrow());
    assertEquals(1, agent.questionCalls.get());
  }

  @Test
  void aQuestionTurnDoesNotSpendTheExplanationBudget() {
    FakeFailureNarrativeAgent agent =
        FakeFailureNarrativeAgent.narrates("The catalog timed out.")
            .answering("The plan asked for an unknown element.");
    FailureNarrative narrative = new FailureNarrative(agent, 1, null);

    assertTrue(ask(narrative, "run-1", "why did this stop?").isAnswer());
    assertFalse(narrative.explanationBudgetSpent("run-1"));
    assertEquals("The catalog timed out.", narrate(narrative, "run-1").orElseThrow());
  }

  @Test
  void aSpentExplanationBudgetStillAnswersAHaltQuestion() {
    FakeFailureNarrativeAgent agent =
        FakeFailureNarrativeAgent.narrates("The catalog timed out.")
            .answering("The plan asked for an unknown element.");
    FailureNarrative narrative = new FailureNarrative(agent, 1, null);

    narrate(narrative, "run-1");
    PauseQuestionResult asked = ask(narrative, "run-1", "why did this stop?");

    assertTrue(narrative.explanationBudgetSpent("run-1"));
    assertTrue(asked.isAnswer());
    assertEquals("The plan asked for an unknown element.", asked.answer());
  }

  @Test
  void aTimedOutHaltQuestionIsUnanswerableNotAnInstruction() {
    FakeFailureNarrativeAgent agent =
        FakeFailureNarrativeAgent.slow("Too late.", Duration.ofSeconds(30)).answering("An answer.");

    PauseQuestionResult asked =
        ask(new FailureNarrative(agent, 12, Duration.ofMillis(50)), "run-1", "why did this stop?");

    assertTrue(asked.isUnanswerable());
    assertFalse(asked.isNotAQuestion());
  }

  @Test
  void aZeroExplanationBudgetDoesNotConvertAQuestionIntoAnInstruction() {
    FakeFailureNarrativeAgent agent =
        FakeFailureNarrativeAgent.narrates("").answering("The plan asked for an unknown element.");

    PauseQuestionResult asked =
        ask(new FailureNarrative(agent, 0, null), "run-1", "why did this stop?");

    assertTrue(asked.isAnswer());
    assertEquals(1, agent.questionCalls.get());
  }

  @Test
  void answersAnApprovalQuestionFromTheCandidateEvidence() {
    FakeFailureNarrativeAgent agent =
        FakeFailureNarrativeAgent.narrates("")
            .answering("The brief covers pending pets and says nothing about billing.");

    PauseQuestionResult answered =
        askAtApproval(new FailureNarrative(agent), "run-1", "does it cover billing?");

    assertEquals(
        "The brief covers pending pets and says nothing about billing.", answered.answer());
    assertEquals("does it cover billing?", agent.lastQuestion.get());
    assertEquals(BRIEF_CANDIDATE, agent.lastApprovalCandidate.get());
  }

  @Test
  void anApprovalMessageReadAsAnInstructionLeavesTheRefinePathInCharge() {
    FakeFailureNarrativeAgent agent = FakeFailureNarrativeAgent.narrates("");

    assertTrue(
        askAtApproval(new FailureNarrative(agent), "run-1", "add the billing endpoint")
            .isNotAQuestion());
    assertEquals(1, agent.approvalQuestionCalls.get());
  }

  @Test
  void aSpentExplanationBudgetStillAnswersAnApprovalQuestion() {
    FakeFailureNarrativeAgent agent =
        FakeFailureNarrativeAgent.narrates("unused")
            .answering("The brief covers pending pets.");
    FailureNarrative narrative = new FailureNarrative(agent, 0, null);

    PauseQuestionResult asked = askAtApproval(narrative, "run-1", "does it cover billing?");

    assertTrue(asked.isAnswer());
    assertFalse(asked.isNotAQuestion());
    assertEquals(1, agent.approvalQuestionCalls.get());
  }

  @Test
  void aTimedOutApprovalQuestionIsUnanswerableNotARefine() {
    FakeFailureNarrativeAgent agent =
        FakeFailureNarrativeAgent.slow("Too late.", Duration.ofSeconds(30)).answering("An answer.");

    PauseQuestionResult asked =
        askAtApproval(
            new FailureNarrative(agent, 12, Duration.ofMillis(50)),
            "run-1",
            "does it cover billing?");

    assertTrue(asked.isUnanswerable());
    assertFalse(asked.isNotAQuestion());
  }

  @Test
  void theSameApprovalQuestionAgainstTheSameCandidateIsAnsweredWithoutASecondCall() {
    FakeFailureNarrativeAgent agent =
        FakeFailureNarrativeAgent.narrates("").answering("The brief covers pending pets.");
    FailureNarrative narrative = new FailureNarrative(agent);

    PauseQuestionResult first = askAtApproval(narrative, "run-1", "does it cover billing?");
    PauseQuestionResult again = askAtApproval(narrative, "run-1", "Does it cover billing?  ");

    assertEquals(first.answer(), again.answer());
    assertEquals(1, agent.approvalQuestionCalls.get());
  }

  @Test
  void anApprovalQuestionAgainstANewCandidateEarnsAFreshAnswer() {
    FakeFailureNarrativeAgent agent =
        FakeFailureNarrativeAgent.narrates("").answering("The brief covers pending pets.");
    FailureNarrative narrative = new FailureNarrative(agent);

    askAtApproval(narrative, "run-1", "does it cover billing?");
    askAtApproval(
        narrative, "run-1", "does it cover billing?", BRIEF_CANDIDATE + "\ncontentHash: b2");

    assertEquals(2, agent.approvalQuestionCalls.get());
  }

  @Test
  void anApprovalQuestionDoesNotReuseTheAnswerToTheSameHaltQuestionOnTheSameRun() {
    FakeFailureNarrativeAgent agent =
        FakeFailureNarrativeAgent.narrates("").answering("The brief covers pending pets.");
    FailureNarrative narrative = new FailureNarrative(agent);
    // The halt evidence field for field, so the two keys differ only by the pause they name.
    String colliding =
        String.join(
            "\n",
            "VALIDATION_FAILURE",
            "planning validation failed",
            "PLAN_BLOCKER: missing quartz (blocker)",
            OwnerCandidateSet.format(
                List.of(
                    new OwnerCandidate("planning", "plan-validation-result"),
                    new OwnerCandidate("analysis", "requirement-brief"))),
            "(none)");

    ask(narrative, "run-1", "what does this cover?");
    askAtApproval(narrative, "run-1", "what does this cover?", colliding);

    assertEquals(1, agent.questionCalls.get());
    assertEquals(1, agent.approvalQuestionCalls.get());
  }

  @Test
  void approvalCardTimeoutOverrideIsTreatedAsInstruction() {
    FakeFailureNarrativeAgent agent = FakeFailureNarrativeAgent.narrates("");
    FailureNarrative narrative = new FailureNarrative(agent);

    PauseQuestionResult result = askAtApproval(narrative, "run-1", "timeout 10 seconds");

    assertTrue(result.isNotAQuestion());
    assertEquals(1, agent.approvalQuestionCalls.get());
  }

  private static final String BRIEF_CANDIDATE =
      """
      kind: REQUIREMENT_BRIEF
      revision: 1
      contentHash: a1
      stageMessage: brief 1
      payload: {"goal":"pending pets"}""";

  private static PauseQuestionResult askAtApproval(
      FailureNarrative narrative, String runId, String question) {
    return askAtApproval(narrative, runId, question, BRIEF_CANDIDATE);
  }

  private static PauseQuestionResult askAtApproval(
      FailureNarrative narrative, String runId, String question, String candidate) {
    return narrative.answerApprovalQuestion(runId, "en", question, "planning", candidate);
  }

  private static PauseQuestionResult ask(
      FailureNarrative narrative, String runId, String question) {
    return ask(narrative, runId, question, "planning validation failed");
  }

  private static PauseQuestionResult ask(
      FailureNarrative narrative, String runId, String question, String evidence) {
    return narrative.answerHaltQuestion(
        runId,
        "en",
        question,
        "planning",
        StageOutcomeClass.VALIDATION_FAILURE,
        evidence,
        "PLAN_BLOCKER: missing quartz (blocker)",
        List.of(
            new OwnerCandidate("planning", "plan-validation-result"),
            new OwnerCandidate("analysis", "requirement-brief")),
        "");
  }

  @Test
  void askClarificationAuthorsTheQuestionInThePinnedLocale() {
    FakeFailureNarrativeAgent agent =
        FakeFailureNarrativeAgent.narrates("unused").clarifying("Which catalog service should I use?");

    Optional<String> question =
        new FailureNarrative(agent)
            .askClarification("run-1", "ru", "catalog service", "design-execution", "missing");

    assertEquals("Which catalog service should I use?", question.orElseThrow());
    assertEquals("ru", agent.lastClarificationLocale.get());
    assertEquals("catalog service", agent.lastRequestedFact.get());
  }

  @Test
  void askClarificationDoesNotFallBackToAnEnglishTemplateWhenTheTurnFails() {
    Optional<String> question =
        new FailureNarrative()
            .askClarification("run-1", "en", "catalog service", "design-execution", "missing");

    assertTrue(question.isEmpty());
  }

  @Test
  void recoverIsEmptyWhenTheRunSpendsItsBudget() {
    RecoveryDecision decision = sampleRecoveryDecision();
    FakeFailureNarrativeAgent agent = FakeFailureNarrativeAgent.narrates("unused").recoverReturns(decision);
    FailureNarrative narrative = new FailureNarrative(agent, 1, null);

    assertTrue(recover(narrative, "run-1", decision).isPresent());
    assertTrue(recover(narrative, "run-1", decision).isEmpty());
    assertEquals(1, agent.calls.get());
  }

  @Test
  void recoverIsEmptyWhenTheTurnFails() {
    RecoveryDecision decision = sampleRecoveryDecision();
    assertTrue(
        recover(new FailureNarrative(FakeFailureNarrativeAgent.boom()), "run-1", decision).isEmpty());
    assertTrue(
        recover(
                new FailureNarrative(
                    FakeFailureNarrativeAgent.slow("Too late.", Duration.ofSeconds(30)), 12, Duration.ofMillis(50)),
                "run-1",
                decision)
            .isEmpty());
  }

  private static Optional<RecoveryDecision> recover(
      FailureNarrative narrative, String runId, RecoveryDecision ignored) {
    return narrative.recover(runId, sampleRecoveryContext());
  }

  private static RecoveryContext sampleRecoveryContext() {
    var briefRef = new org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Reference(
        Kind.REQUIREMENT_BRIEF, "brief-1", "hash-brief");
    RecoveryEvidence evidence =
        new RecoveryEvidence(
            1,
            "failure-1",
            "MISSING_REQUIRED_PROPERTY",
            "design-execution",
            briefRef,
            null,
            List.of(),
            List.of(),
            null,
            List.of());
    RequirementBrief brief =
        new RequirementBrief("Proxy inventory", List.of(), List.of(), List.of(), List.of(), "");
    return new RecoveryContext(evidence, brief, null, "en");
  }

  private static RecoveryDecision sampleRecoveryDecision() {
    var briefRef = new org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Reference(
        Kind.REQUIREMENT_BRIEF, "brief-1", "hash-brief");
    return new RecoveryDecision(
        RecoveryCauseClass.BRIEF_DEFECT,
        briefRef,
        List.of("failure-1"),
        RecoveryAction.REVISE_BRIEF,
        List.of(),
        "",
        "Add the missing scheduler.");
  }

  private static Optional<String> narrate(FailureNarrative narrative, String runId) {
    return narrative.narrate(
        runId, "en", "planning", StageOutcomeClass.RETRYABLE_TECHNICAL_FAILURE, "timeout", "");
  }

  private static OwnerDiagnosis diagnose(FailureNarrative narrative, String runId) {
    return narrative.diagnose(
        runId,
        "en",
        "planning",
        StageOutcomeClass.VALIDATION_FAILURE,
        "planning validation failed",
        "security-1: External route requires accessControlType=RBAC (blocker)",
        List.of(
            new OwnerCandidate("planning", "plan-validation-result"),
            new OwnerCandidate("analysis", "requirement-brief")),
        "");
  }
}
