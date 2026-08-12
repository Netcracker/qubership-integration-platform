package org.qubership.integration.platform.ai.llm.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.model.ScenarioType;

class PhaseRoutingPolicyTest {

  @Test
  void coldCreateChainIntentFallsThroughToLlm() {
    var result =
        PhaseRoutingPolicy.tryResolve(
            ConversationPhase.COLD,
            "Create a chain named Greetings that returns Hello world on GET /greetings",
            false,
            false,
            false);

    assertTrue(result.isEmpty());
  }

  @Test
  void designReviewRoutesToCreateChainPlan() {
    var result =
        PhaseRoutingPolicy.tryResolve(
            ConversationPhase.DESIGN_REVIEW,
            "Agree",
            false,
            false,
            false);

    assertTrue(result.isPresent());
    assertEquals(ScenarioType.CREATE_CHAIN_PLAN, result.get());
  }

  @Test
  void discoveryRoutesIncompleteDraftToGather() {
    var result =
        PhaseRoutingPolicy.tryResolve(
            ConversationPhase.DISCOVERY,
            "take status and amount",
            false,
            false,
            false,
            false);

    assertTrue(result.isPresent());
    assertEquals(ScenarioType.GATHER_REQUIREMENTS, result.get());
  }

  @Test
  void discoveryAgreeWithReadyDraftRoutesToCreateChainPlan() {
    var result =
        PhaseRoutingPolicy.tryResolve(
            ConversationPhase.DISCOVERY, "Agree", false, false, false, true);

    assertTrue(result.isPresent());
    assertEquals(ScenarioType.CREATE_CHAIN_PLAN, result.get());
  }

  @Test
  void importPendingAgreeStaysOnGatherRegardlessOfWording() {
    // The import no longer advances on wording at all: only the decision's scenario hint does,
    // and that hint is checked before phase routing runs (ImportSpecificationRoutingPolicy).
    var result =
        PhaseRoutingPolicy.tryResolve(
            ConversationPhase.IMPORT_PENDING, "Agree", false, false, false);

    assertTrue(result.isPresent());
    assertEquals(ScenarioType.GATHER_REQUIREMENTS, result.get());
  }

  @Test
  void importPendingImportSpecificationPhraseNoLongerRoutesToImport() {
    // Mirror of the deleted phrase-matching contract: typing the old command phrase as prose
    // does not route to import any more.
    var result =
        PhaseRoutingPolicy.tryResolve(
            ConversationPhase.IMPORT_PENDING, "Import specification", false, false, false);

    assertTrue(result.isPresent());
    assertEquals(ScenarioType.GATHER_REQUIREMENTS, result.get());
  }

  @Test
  void importPendingRefinementStaysOnGather() {
    var result =
        PhaseRoutingPolicy.tryResolve(
            ConversationPhase.IMPORT_PENDING,
            "also map field X to Y",
            false,
            false,
            false);

    assertTrue(result.isPresent());
    assertEquals(ScenarioType.GATHER_REQUIREMENTS, result.get());
  }

  @Test
  void discoveryAgreeWithoutReadyDraftStaysOnGather() {
    var result =
        PhaseRoutingPolicy.tryResolve(
            ConversationPhase.DISCOVERY, "Agree", false, false, false, false);

    assertTrue(result.isPresent());
    assertEquals(ScenarioType.GATHER_REQUIREMENTS, result.get());
  }

  @Test
  void discoveryRefinementWithReadyDraftStaysOnGather() {
    var result =
        PhaseRoutingPolicy.tryResolve(
            ConversationPhase.DISCOVERY,
            "also map field X to Y",
            false,
            false,
            false,
            true);

    assertTrue(result.isPresent());
    assertEquals(ScenarioType.GATHER_REQUIREMENTS, result.get());
  }

  @Test
  void planDraftRoutesToCreateChainPlan() {
    var result =
        PhaseRoutingPolicy.tryResolve(
            ConversationPhase.PLAN_DRAFT,
            "Create the chain plan",
            false,
            false,
            false);

    assertTrue(result.isPresent());
    assertEquals(ScenarioType.CREATE_CHAIN_PLAN, result.get());
  }

  @Test
  void planDraftRefinementAlsoRoutesToCreateChainPlan() {
    var result =
        PhaseRoutingPolicy.tryResolve(
            ConversationPhase.PLAN_DRAFT,
            "also map field X to Y",
            false,
            false,
            false);

    assertTrue(result.isPresent());
    assertEquals(ScenarioType.CREATE_CHAIN_PLAN, result.get());
  }

  @Test
  void planProposalAgreeRoutesToCreateChainPlan() {
    var result =
        PhaseRoutingPolicy.tryResolve(
            ConversationPhase.PLAN_CANDIDATE, "Agree", false, false, false);

    assertTrue(result.isPresent());
    assertEquals(ScenarioType.CREATE_CHAIN_PLAN, result.get());
  }

  @Test
  void planProposalAgreeProceedMatchesViaImplementGateProceed() {
    // matchesShortPlanContinuation also checks matchesImplementGateAffirmative (find-based).
    var result =
        PhaseRoutingPolicy.tryResolve(
            ConversationPhase.PLAN_CANDIDATE, "Agree, proceed", false, false, false);

    assertTrue(result.isPresent());
    assertEquals(ScenarioType.CREATE_CHAIN_PLAN, result.get());
  }

  @Test
  void planProposalBuildTheChainAdvancesToCreateChainPlan() {
    var result =
        PhaseRoutingPolicy.tryResolve(
            ConversationPhase.PLAN_CANDIDATE, "build the chain", false, false, false);

    assertTrue(result.isPresent());
    assertEquals(ScenarioType.CREATE_CHAIN_PLAN, result.get());
  }

  @Test
  void planProposalExecuteTheChainAdvancesToCreateChainPlan() {
    var result =
        PhaseRoutingPolicy.tryResolve(
            ConversationPhase.PLAN_CANDIDATE, "execute the chain", false, false, false);

    assertTrue(result.isPresent());
    assertEquals(ScenarioType.CREATE_CHAIN_PLAN, result.get());
  }

  @Test
  void planProposalBuildItAdvancesToCreateChainPlan() {
    var result =
        PhaseRoutingPolicy.tryResolve(
            ConversationPhase.PLAN_CANDIDATE, "build it", false, false, false);

    assertTrue(result.isPresent());
    assertEquals(ScenarioType.CREATE_CHAIN_PLAN, result.get());
  }

  @Test
  void planProposalBuildWithBundleRoutesImplement() {
    var result =
        PhaseRoutingPolicy.tryResolve(
            ConversationPhase.PLAN_CANDIDATE, "build the chain", false, true, false);

    assertTrue(result.isPresent());
    assertEquals(ScenarioType.IMPLEMENT_CHAIN, result.get());
  }

  @Test
  void planProposalNonAgreeReShowsProposal() {
    var result =
        PhaseRoutingPolicy.tryResolve(
            ConversationPhase.PLAN_CANDIDATE,
            "change step 2 to use routing",
            false,
            false,
            false);

    assertTrue(result.isPresent());
    assertEquals(ScenarioType.CREATE_CHAIN_PLAN, result.get());
  }

  @Test
  void planProposalRichBriefFallsThroughToLaterLayers() {
    var result =
        PhaseRoutingPolicy.tryResolve(
            ConversationPhase.PLAN_CANDIDATE,
            """
            Execute the approved CIP design-first plan for Geographic Site.
            IDS and plan are already approved. Treat this as Agree / Execute plan.
            Build catalog companions and implement the chain against the catalog.
            """,
            false,
            false,
            false);

    assertTrue(result.isEmpty());
  }

  @Test
  void doesNotRoutePlanQuestionViaRegexWhenActivePlan() {
    var result =
        PhaseRoutingPolicy.tryResolve(
            ConversationPhase.PLAN_REVIEW,
            "show graph",
            true,
            false,
            false);

    assertTrue(result.isEmpty());
  }

  @Test
  void doesNotRouteImplementChainViaRegexWhenActivePlan() {
    var result =
        PhaseRoutingPolicy.tryResolve(
            ConversationPhase.PLAN_REVIEW,
            "implement the chain",
            true,
            false,
            false);

    assertTrue(result.isEmpty());
  }

  @Test
  void doesNotRouteModifyPlanViaRegexWhenActivePlan() {
    var result =
        PhaseRoutingPolicy.tryResolve(
            ConversationPhase.PLAN_REVIEW,
            "modify plan",
            true,
            false,
            false);

    assertTrue(result.isEmpty());
  }

  @Test
  void planApprovedRoutesAskPlanForPlanQuestion() {
    var result =
        PhaseRoutingPolicy.tryResolve(
            ConversationPhase.PLAN_APPROVED, "show graph", true, true, false);

    assertTrue(result.isPresent());
    assertEquals(ScenarioType.ASK_PLAN, result.get());
  }

  @Test
  void planApprovedRoutesImplementChainForBuildIntent() {
    var result =
        PhaseRoutingPolicy.tryResolve(
            ConversationPhase.PLAN_APPROVED, "implement the chain", true, true, false);

    assertTrue(result.isPresent());
    assertEquals(ScenarioType.IMPLEMENT_CHAIN, result.get());
  }

  @Test
  void planApprovedRichImplementPromptFallsThroughToLaterLayers() {
    var result =
        PhaseRoutingPolicy.tryResolve(
            ConversationPhase.PLAN_APPROVED,
            """
            Execute the approved CIP design-first plan for Geographic Site.
            IDS and plan are already approved. Treat this as Agree / Execute plan.
            Build catalog companions and implement the chain against the catalog.
            """,
            true,
            true,
            false);

    assertTrue(result.isEmpty());
  }

  @Test
  void planApprovedRichImplementWithoutBundleAlsoFallsThrough() {
    var result =
        PhaseRoutingPolicy.tryResolve(
            ConversationPhase.PLAN_APPROVED,
            """
            Execute the approved CIP design-first plan.
            Please implement the chain once planning finishes.
            """,
            true,
            false,
            false);

    assertTrue(result.isEmpty());
  }

  @Test
  void routesChainQuestionToAskChainWhenChainContextPresent() {
    var result =
        PhaseRoutingPolicy.tryResolve(
            ConversationPhase.COLD,
            "explain this chain",
            false,
            false,
            true);

    assertTrue(result.isPresent());
    assertEquals(ScenarioType.ASK_CHAIN, result.get());
  }
}
