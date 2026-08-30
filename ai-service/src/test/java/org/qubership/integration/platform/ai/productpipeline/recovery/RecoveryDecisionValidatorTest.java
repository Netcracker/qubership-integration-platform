package org.qubership.integration.platform.ai.productpipeline.recovery;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Reference;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainSection;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;

class RecoveryDecisionValidatorTest {

  private RecoveryEvidence evidence;
  private RecoveryContext context;
  private Reference briefRef;
  private Reference planRef;
  private Reference semanticRef;
  private Reference graphRef;

  @BeforeEach
  void setUp() {
    briefRef = new Reference(Kind.REQUIREMENT_BRIEF, "brief-1", "hash-brief");
    planRef = new Reference(Kind.IMPLEMENTATION_PLAN, "plan-1", "hash-plan");
    semanticRef = new Reference(Kind.CHAIN_PLAN_GRAPH, "graph-1", "hash-graph");
    graphRef = semanticRef;
    evidence =
        new RecoveryEvidence(
            1,
            "failure-1",
            "MISSING_REQUIRED_PROPERTY",
            "design-execution",
            briefRef,
            semanticRef,
            List.of(planRef),
            List.of(),
            null,
            List.of());
    RequirementBrief brief =
        new RequirementBrief("Proxy inventory", List.of(), List.of(), List.of(), List.of(), "");
    ChainPlanGraph graph =
        new ChainPlanGraph("1.0", new ChainSection("c1", "HealthProxy"), List.of(), List.of());
    context = new RecoveryContext(evidence, brief, graph, "en");
  }

  @Test
  void nullDecisionOrContextIsRejected() {
    RecoveryDecision decision = validReviseBrief();
    assertFalse(RecoveryDecisionValidator.validate(null, context).accepted());
    assertFalse(RecoveryDecisionValidator.validate(decision, null).accepted());
  }

  @Test
  void reviseBriefMustTargetTheApprovedBrief() {
    RecoveryDecision decision =
        new RecoveryDecision(
            RecoveryCauseClass.BRIEF_DEFECT,
            planRef,
            List.of(evidence.failureId()),
            RecoveryAction.REVISE_BRIEF,
            List.of(),
            "",
            "Fix the brief.");
    RecoveryDecisionValidator.Result result = RecoveryDecisionValidator.validate(decision, context);
    assertFalse(result.accepted());
    assertTrue(result.findings().stream().anyMatch(f -> f.contains("approved brief")));
  }

  @Test
  void derivationDefectCannotReviseTheBrief() {
    RecoveryDecision decision =
        new RecoveryDecision(
            RecoveryCauseClass.DERIVATION_DEFECT,
            evidence.approvedBriefRef(),
            List.of(evidence.failureId()),
            RecoveryAction.REVISE_BRIEF,
            List.of(),
            "",
            "Rewrite requirements.");
    assertFalse(RecoveryDecisionValidator.validate(decision, context).accepted());
  }

  @Test
  void askUserMustBeAProductQuestion() {
    RecoveryDecision decision =
        new RecoveryDecision(
            RecoveryCauseClass.BRIEF_DEFECT,
            evidence.approvedBriefRef(),
            List.of(evidence.failureId()),
            RecoveryAction.ASK_USER,
            List.of(),
            "Pick design-execution or requirement-analysis",
            "Choose a stage.");
    assertFalse(RecoveryDecisionValidator.validate(decision, context).accepted());
  }

  @Test
  void missingEvidenceRefIsRejected() {
    RecoveryDecision decision =
        new RecoveryDecision(
            RecoveryCauseClass.BRIEF_DEFECT,
            briefRef,
            List.of(),
            RecoveryAction.REVISE_BRIEF,
            List.of(),
            "",
            "Fix the brief.");
    RecoveryDecisionValidator.Result result = RecoveryDecisionValidator.validate(decision, context);
    assertFalse(result.accepted());
    assertTrue(result.findings().stream().anyMatch(f -> f.toLowerCase().contains("missing evidence")));
  }

  @Test
  void unknownEvidenceRefIsRejected() {
    RecoveryDecision decision =
        new RecoveryDecision(
            RecoveryCauseClass.BRIEF_DEFECT,
            briefRef,
            List.of("unknown-ref"),
            RecoveryAction.REVISE_BRIEF,
            List.of(),
            "",
            "Fix the brief.");
    assertFalse(RecoveryDecisionValidator.validate(decision, context).accepted());
  }

  @Test
  void retryOperationWithUnknownSideEffectIsRejected() {
    RecoveryDecision decision =
        new RecoveryDecision(
            RecoveryCauseClass.TECHNICAL_FAILURE,
            semanticRef,
            List.of(evidence.failureId()),
            RecoveryAction.RETRY_OPERATION,
            List.of(),
            "",
            "Retry the catalog lookup.");
    RecoveryDecisionValidator.Result result = RecoveryDecisionValidator.validate(decision, context);
    assertFalse(result.accepted());
    assertTrue(
        result.findings().stream()
            .anyMatch(
                f -> {
                  String lower = f.toLowerCase();
                  return lower.contains("park") || lower.contains("side-effect");
                }));
  }

  @Test
  void retryOperationAcceptsRetryableTechnicalFailure() {
    RecoveryEvidence retryableEvidence =
        new RecoveryEvidence(
            1,
            "failure-1",
            "TIMEOUT",
            "design-execution",
            briefRef,
            semanticRef,
            List.of(planRef),
            List.of(),
            new TechnicalFailureRecord(
                true, 1, "catalog", "lookup", "30s", "corr-1", "TimeoutException", "timed out", "", ""),
            List.of());
    RecoveryContext retryableContext =
        new RecoveryContext(
            retryableEvidence,
            context.approvedBrief(),
            context.rejectedArtifact(),
            "en");
    RecoveryDecision decision =
        new RecoveryDecision(
            RecoveryCauseClass.TECHNICAL_FAILURE,
            semanticRef,
            List.of(retryableEvidence.failureId()),
            RecoveryAction.RETRY_OPERATION,
            List.of(),
            "",
            "Retry the catalog lookup.");
    assertTrue(RecoveryDecisionValidator.validate(decision, retryableContext).accepted());
  }

  @Test
  void unclassifiedOnlyAllowsPark() {
    RecoveryDecision decision =
        new RecoveryDecision(
            RecoveryCauseClass.UNCLASSIFIED,
            null,
            List.of(evidence.failureId()),
            RecoveryAction.REVISE_BRIEF,
            List.of(),
            "",
            "Try again.");
    assertFalse(RecoveryDecisionValidator.validate(decision, context).accepted());
  }

  @Test
  void unclassifiedParkIsAccepted() {
    RecoveryDecision decision =
        new RecoveryDecision(
            RecoveryCauseClass.UNCLASSIFIED,
            null,
            List.of(evidence.failureId()),
            RecoveryAction.PARK,
            List.of(),
            "",
            "We need more information.");
    assertTrue(RecoveryDecisionValidator.validate(decision, context).accepted());
  }

  @Test
  void regenerateArtifactCannotTargetTheApprovedBrief() {
    RecoveryDecision decision =
        new RecoveryDecision(
            RecoveryCauseClass.DERIVATION_DEFECT,
            briefRef,
            List.of(evidence.failureId()),
            RecoveryAction.REGENERATE_ARTIFACT,
            List.of(),
            "",
            "Regenerate the plan.");
    RecoveryDecisionValidator.Result result = RecoveryDecisionValidator.validate(decision, context);
    assertFalse(result.accepted());
    assertTrue(result.findings().stream().anyMatch(f -> f.toLowerCase().contains("approved brief")));
  }

  @Test
  void askUserRejectsBlankQuestion() {
    RecoveryDecision decision =
        new RecoveryDecision(
            RecoveryCauseClass.BRIEF_DEFECT,
            briefRef,
            List.of(evidence.failureId()),
            RecoveryAction.ASK_USER,
            List.of(),
            "   ",
            "Which inventory API should we call?");
    assertFalse(RecoveryDecisionValidator.validate(decision, context).accepted());
  }

  @Test
  void userSummaryMustNotContainStageIds() {
    RecoveryDecision decision =
        new RecoveryDecision(
            RecoveryCauseClass.BRIEF_DEFECT,
            briefRef,
            List.of(evidence.failureId()),
            RecoveryAction.REVISE_BRIEF,
            List.of(),
            "",
            "Return to design-planning and fix the trigger.");
    assertFalse(RecoveryDecisionValidator.validate(decision, context).accepted());
  }

  @Test
  void parkMayOmitFaultArtifactRef() {
    RecoveryDecision decision =
        new RecoveryDecision(
            RecoveryCauseClass.DERIVATION_DEFECT,
            null,
            List.of(evidence.failureId()),
            RecoveryAction.PARK,
            List.of(),
            "",
            "The plan cannot be repaired automatically.");
    assertTrue(RecoveryDecisionValidator.validate(decision, context).accepted());
  }

  @Test
  void nonParkActionRequiresFaultArtifactRef() {
    RecoveryDecision decision =
        new RecoveryDecision(
            RecoveryCauseClass.DERIVATION_DEFECT,
            null,
            List.of(evidence.failureId()),
            RecoveryAction.REGENERATE_ARTIFACT,
            List.of(),
            "",
            "Regenerate the plan.");
    assertFalse(RecoveryDecisionValidator.validate(decision, context).accepted());
  }

  @Test
  void validReviseBriefIsAccepted() {
    assertTrue(RecoveryDecisionValidator.validate(validReviseBrief(), context).accepted());
  }

  private RecoveryDecision validReviseBrief() {
    return new RecoveryDecision(
        RecoveryCauseClass.BRIEF_DEFECT,
        briefRef,
        List.of(evidence.failureId()),
        RecoveryAction.REVISE_BRIEF,
        List.of(),
        "",
        "Add the missing scheduler to the brief.");
  }
}
