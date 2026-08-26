package org.qubership.integration.platform.ai.productpipeline.create;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.productpipeline.profile.ApprovalPolicy;
import org.qubership.integration.platform.ai.productpipeline.profile.ArtifactTypeRef;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfile;
import org.qubership.integration.platform.ai.productpipeline.profile.ProfileStage;
import org.qubership.integration.platform.ai.productpipeline.profile.RetryPolicy;
import org.qubership.integration.platform.ai.productpipeline.profile.TerminalPolicy;

class OwnerCandidateSetTest {

  @Test
  void firstLayerIncludesTheFailedStageAndProducersOfItsInputs() {
    List<OwnerCandidate> first = OwnerCandidateSet.firstLayer(threeStageProfile(), "planning");
    assertEquals(List.of("planning", "analysis"), OwnerCandidateSet.stageIds(first));
    assertTrue(OwnerCandidateSet.format(first).contains("planning:"));
    assertTrue(OwnerCandidateSet.format(first).contains("analysis:requirement-brief"));
    assertFalse(OwnerCandidateSet.format(first).contains("discovery"));
  }

  @Test
  void deepenAddsProducersOfTheFirstLayer() {
    List<OwnerCandidate> first = OwnerCandidateSet.firstLayer(threeStageProfile(), "planning");
    List<OwnerCandidate> deeper = OwnerCandidateSet.deepen(threeStageProfile(), first);
    assertEquals(List.of("planning", "analysis", "discovery"), OwnerCandidateSet.stageIds(deeper));
  }

  @Test
  void firstLayerIsOnlyTheFailedStageWhenInputsHaveNoProducer() {
    List<OwnerCandidate> first = OwnerCandidateSet.firstLayer(threeStageProfile(), "discovery");
    assertEquals(List.of("discovery"), OwnerCandidateSet.stageIds(first));
  }

  @Test
  void namedStagesMapsRequirementsGatheringOntoTheRequirementsStageInTheSet() {
    List<OwnerCandidate> candidates =
        List.of(
            new OwnerCandidate("design-execution", "plan-validation-result"),
            new OwnerCandidate("design-planning", "implementation-plan"),
            new OwnerCandidate("requirement-analysis", "requirement-brief"));

    assertEquals(
        List.of("requirement-analysis"),
        OwnerCandidateSet.namedStages(
            "go back to requirements gathering and add that we need RBAC", candidates));
  }

  @Test
  void namedStagesIsEmptyWhenTheFollowUpNamesAStageOutsideTheSet() {
    List<OwnerCandidate> candidates =
        List.of(
            new OwnerCandidate("design-execution", "plan-validation-result"),
            new OwnerCandidate("design-planning", "implementation-plan"));

    assertEquals(
        List.of(),
        OwnerCandidateSet.namedStages("go back to compiler and add RBAC", candidates));
    assertTrue(OwnerCandidateSet.requestsNamedStage("go back to compiler and add RBAC"));
  }

  @Test
  void namedStagesIsAmbiguousWhenRequirementsMatchesTwoCandidates() {
    List<OwnerCandidate> candidates =
        List.of(
            new OwnerCandidate("requirement-discovery", "requirement-draft"),
            new OwnerCandidate("requirement-analysis", "requirement-brief"),
            new OwnerCandidate("design-planning", "implementation-plan"));

    assertEquals(
        List.of("requirement-discovery", "requirement-analysis"),
        OwnerCandidateSet.namedStages("go back to requirements and add RBAC", candidates));
  }

  @Test
  void aCorrectionWithoutNamingAStageDoesNotLookLikeAStageRequest() {
    assertEquals(
        List.of(),
        OwnerCandidateSet.namedStages(
            "add rbac",
            List.of(new OwnerCandidate("requirement-analysis", "requirement-brief"))));
    assertFalse(OwnerCandidateSet.requestsNamedStage("add rbac"));
    assertEquals(
        List.of(),
        OwnerCandidateSet.namedStages(
            "go back to compiler and add RBAC", List.of(new OwnerCandidate("work", ""))));
  }

  @Test
  void requestsNamedStageMatchesBareGoBackPhrases() {
    assertTrue(OwnerCandidateSet.requestsNamedStage("go back"));
    assertTrue(OwnerCandidateSet.requestsNamedStage("back"));
    assertTrue(OwnerCandidateSet.requestsNamedStage("return to"));
    assertTrue(OwnerCandidateSet.requestsNamedStage("reopen"));
    assertFalse(OwnerCandidateSet.requestsNamedStage("callback"));
  }

  @Test
  void namedStagesIsEmptyForABareGoBackAndIsBareGoBackIsTrue() {
    List<OwnerCandidate> candidates =
        List.of(
            new OwnerCandidate("design-execution", "plan-validation-result"),
            new OwnerCandidate("design-planning", "implementation-plan"),
            new OwnerCandidate("requirement-analysis", "requirement-brief"));

    assertEquals(List.of(), OwnerCandidateSet.namedStages("go back", candidates));
    assertEquals(List.of(), OwnerCandidateSet.namedStages("reopen", candidates));
    assertTrue(OwnerCandidateSet.isBareGoBack("go back"));
    assertTrue(OwnerCandidateSet.isBareGoBack("back"));
    assertTrue(OwnerCandidateSet.isBareGoBack("reopen"));
    assertFalse(OwnerCandidateSet.isBareGoBack("go back to compiler and add RBAC"));
    assertFalse(OwnerCandidateSet.isBareGoBack("add rbac"));
  }

  @Test
  void clarifyRoleUsesAShortRoleNotTheStageId() {
    assertEquals(
        "the plan",
        OwnerCandidateSet.clarifyRole(new OwnerCandidate("design-planning", "implementation-plan")));
    assertEquals(
        "requirements",
        OwnerCandidateSet.clarifyRole(
            new OwnerCandidate("requirement-analysis", "requirement-brief")));
    assertEquals(
        "design-planning:the plan,requirement-analysis:requirements",
        OwnerCandidateSet.formatClarifyRoles(
            List.of(
                new OwnerCandidate("design-planning", "implementation-plan"),
                new OwnerCandidate("requirement-analysis", "requirement-brief"))));
  }

  @Test
  void preferNamedOwnerWinsOverAnEarliestSufficientDiagnosis() {
    OwnerDiagnosis remapped =
        OwnerDiagnosis.of("Design execution could not complete.", "design-planning");
    List<OwnerCandidate> candidates =
        List.of(
            new OwnerCandidate("design-execution", "plan-validation-result"),
            new OwnerCandidate("design-planning", "implementation-plan"),
            new OwnerCandidate("requirement-analysis", "requirement-brief"));

    OwnerDiagnosis named =
        OwnerCandidateSet.preferNamedOwner(
            remapped,
            candidates,
            "go back to requirements gathering and add that we need RBAC");

    assertEquals("requirement-analysis", named.owner().orElseThrow());
    assertFalse(named.ambiguous());
  }

  @Test
  void preferNamedOwnerAsksWhenTwoRequirementStagesMatch() {
    OwnerDiagnosis remapped = OwnerDiagnosis.of("The plan omitted RBAC.", "design-planning");
    OwnerDiagnosis named =
        OwnerCandidateSet.preferNamedOwner(
            remapped,
            List.of(
                new OwnerCandidate("requirement-discovery", "requirement-draft"),
                new OwnerCandidate("requirement-analysis", "requirement-brief"),
                new OwnerCandidate("design-planning", "implementation-plan")),
            "go back to requirements and add RBAC");

    assertTrue(named.ambiguous());
    assertTrue(named.owner().isEmpty());
  }

  @Test
  void preferNamedOwnerKeepsTheAutomaticOwnerWhenNoStageIsNamed() {
    OwnerDiagnosis remapped =
        OwnerDiagnosis.of("Design execution could not complete.", "design-planning");

    OwnerDiagnosis kept =
        OwnerCandidateSet.preferNamedOwner(
            remapped,
            List.of(
                new OwnerCandidate("design-execution", "plan-validation-result"),
                new OwnerCandidate("design-planning", "implementation-plan")),
            "add rbac");

    assertEquals("design-planning", kept.owner().orElseThrow());
  }

  @Test
  void ownerForBareGoBackPrefersTheDiagnosedOwnerThenBriefThenPlan() {
    List<OwnerCandidate> candidates =
        List.of(
            new OwnerCandidate("design-execution", "plan-validation-result"),
            new OwnerCandidate("design-planning", "implementation-plan"),
            new OwnerCandidate("requirement-analysis", "requirement-brief"));

    assertEquals(
        "design-planning",
        OwnerCandidateSet.ownerForBareGoBack("design-planning", candidates, "design-execution")
            .orElseThrow());
    assertEquals(
        "requirement-analysis",
        OwnerCandidateSet.ownerForBareGoBack(
                "requirement-analysis", candidates, "design-execution")
            .orElseThrow());
    assertEquals(
        "requirement-analysis",
        OwnerCandidateSet.ownerForBareGoBack("design-execution", candidates, "design-execution")
            .orElseThrow());
    assertEquals(
        "design-planning",
        OwnerCandidateSet.ownerForBareGoBack(
                "design-execution",
                List.of(
                    new OwnerCandidate("design-execution", "plan-validation-result"),
                    new OwnerCandidate("design-planning", "implementation-plan")),
                "design-execution")
            .orElseThrow());
  }

  @Test
  void preferEarliestSufficientOwnerMapsPolicyFindingsToTheBriefProducer() {
    OwnerDiagnosis remapped =
        OwnerCandidateSet.preferEarliestSufficientOwner(
            OwnerDiagnosis.of("Design execution could not complete.", "design-execution"),
            List.of(
                new OwnerCandidate("design-execution", "plan-validation-result"),
                new OwnerCandidate("design-planning", "implementation-plan"),
                new OwnerCandidate("requirement-analysis", "requirement-brief")),
            "design-execution",
            "security-1: External route requires accessControlType=RBAC (blocker)",
            "Phase 5 plan validation failed");

    assertEquals("requirement-analysis", remapped.owner().orElseThrow());
  }

  @Test
  void preferEarliestSufficientOwnerMapsMissingApprovedBriefFactsToTheBriefProducer() {
    OwnerDiagnosis remapped =
        OwnerCandidateSet.preferEarliestSufficientOwner(
            OwnerDiagnosis.of("Design input needs more information.", "design-input"),
            List.of(
                new OwnerCandidate("design-input", "normalized-design-flow"),
                new OwnerCandidate("requirement-analysis", "requirement-brief")),
            "design-input",
            "",
            "The approved requirement brief is missing required facts: SERVICE_CALL participant");

    assertEquals("requirement-analysis", remapped.owner().orElseThrow());
  }

  @Test
  void preferEarliestSufficientOwnerMapsEmbeddedSecurityCodesInEvidence() {
    OwnerDiagnosis remapped =
        OwnerCandidateSet.preferEarliestSufficientOwner(
            OwnerDiagnosis.none("Validation failed."),
            List.of(
                new OwnerCandidate("design-execution", "plan-validation-result"),
                new OwnerCandidate("design-planning", "implementation-plan"),
                new OwnerCandidate("requirement-analysis", "requirement-brief")),
            "design-execution",
            "",
            "Phase 5 plan validation failed. Findings: security-1: External route RBAC"
                + " requires a non-empty roles list");

    assertEquals("requirement-analysis", remapped.owner().orElseThrow());
  }

  @Test
  void preferEarliestSufficientOwnerMapsPlanFillFindingsToThePlanProducer() {
    OwnerDiagnosis remapped =
        OwnerCandidateSet.preferEarliestSufficientOwner(
            OwnerDiagnosis.of("Design execution could not complete.", "design-execution"),
            List.of(
                new OwnerCandidate("design-execution", "plan-validation-result"),
                new OwnerCandidate("design-planning", "implementation-plan"),
                new OwnerCandidate("requirement-analysis", "requirement-brief")),
            "design-execution",
            "plan-1: Missing required property on http-trigger (blocker)",
            "Phase 5 plan validation failed");

    assertEquals("design-planning", remapped.owner().orElseThrow());
  }

  @Test
  void preferEarliestSufficientOwnerKeepsAmbiguousDiagnoses() {
    OwnerDiagnosis kept =
        OwnerCandidateSet.preferEarliestSufficientOwner(
            OwnerDiagnosis.ask("Either the brief or the plan could be wrong."),
            List.of(
                new OwnerCandidate("design-planning", "implementation-plan"),
                new OwnerCandidate("requirement-analysis", "requirement-brief")),
            "design-execution",
            "security-1: External route requires accessControlType=RBAC (blocker)",
            "");

    assertTrue(kept.ambiguous());
    assertTrue(kept.owner().isEmpty());
  }

  @Test
  void preferEarliestSufficientOwnerFallsBackToPlanWhenBriefIsAbsent() {
    OwnerDiagnosis remapped =
        OwnerCandidateSet.preferEarliestSufficientOwner(
            OwnerDiagnosis.of("Design execution could not complete.", "design-execution"),
            List.of(
                new OwnerCandidate("design-execution", "plan-validation-result"),
                new OwnerCandidate("design-planning", "implementation-plan")),
            "design-execution",
            "security-1: External route requires accessControlType=RBAC (blocker)",
            "");

    assertEquals("design-planning", remapped.owner().orElseThrow());
  }

  @Test
  void preferEarliestSufficientOwnerRemapsAPlanOwnerToBriefForPolicyFindings() {
    OwnerDiagnosis remapped =
        OwnerCandidateSet.preferEarliestSufficientOwner(
            OwnerDiagnosis.of("The plan omitted RBAC.", "design-planning"),
            List.of(
                new OwnerCandidate("design-execution", "plan-validation-result"),
                new OwnerCandidate("design-planning", "implementation-plan"),
                new OwnerCandidate("requirement-analysis", "requirement-brief")),
            "design-execution",
            "security-1: External route requires accessControlType=RBAC (blocker)",
            "");

    assertEquals("requirement-analysis", remapped.owner().orElseThrow());
  }

  @Test
  void preferEarliestSufficientOwnerKeepsUnknownPropertyOnTheFailedExecutionStage() {
    OwnerDiagnosis remapped =
        OwnerCandidateSet.preferEarliestSufficientOwner(
            OwnerDiagnosis.of("Retry the planning stage.", "design-planning"),
            List.of(
                new OwnerCandidate("design-execution", "plan-validation-result"),
                new OwnerCandidate("design-planning", "implementation-plan"),
                new OwnerCandidate("requirement-analysis", "requirement-brief")),
            "design-execution",
            "",
            "Structure validation failed:\n"
                + "node 'kafka-trigger-1' (kafka-trigger-2) has unknown property key 'topic'.");

    assertEquals("design-execution", remapped.owner().orElseThrow());
  }

  private static ProductPipelineProfile threeStageProfile() {
    ArtifactTypeRef draft = new ArtifactTypeRef("requirement-draft", 1);
    ArtifactTypeRef brief = new ArtifactTypeRef("requirement-brief", 1);
    ArtifactTypeRef validation = new ArtifactTypeRef("plan-validation-result", 1);
    return new ProductPipelineProfile(
        1,
        "owner-candidates",
        "1",
        List.of(new ArtifactTypeRef("user-input", 1)),
        List.of(
            new ProfileStage(
                "discovery",
                "discovery-cap",
                List.of(new ArtifactTypeRef("user-input", 1)),
                List.of(draft),
                null,
                null,
                new RetryPolicy(0, 1L)),
            new ProfileStage(
                "analysis",
                "analysis-cap",
                List.of(draft),
                List.of(brief),
                new ApprovalPolicy(brief),
                null,
                new RetryPolicy(0, 1L)),
            new ProfileStage(
                "planning",
                "planning-cap",
                List.of(brief),
                List.of(validation),
                null,
                null,
                new RetryPolicy(0, 1L))),
        new TerminalPolicy("planning", "PLAN_APPROVED"),
        List.of("discovery-cap", "analysis-cap", "planning-cap"));
  }
}
