package org.qubership.integration.platform.ai.llm.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chat.model.ChatRequest;
import org.qubership.integration.platform.ai.integration.apihub.ApiHubRequirementRefs;
import org.qubership.integration.platform.ai.model.ScenarioType;
import org.qubership.integration.platform.ai.plan.DraftDecision;
import org.qubership.integration.platform.ai.plan.RequirementDraft;
import org.qubership.integration.platform.ai.plan.RequirementDraftStore;

class ImportSpecificationRoutingPolicyTest {

  private static final String CONVERSATION_ID = "import-routing-1";

  private RequirementDraftStore draftStore;

  @BeforeEach
  void setUp() {
    draftStore = new RequirementDraftStore();
  }

  @Test
  void agreeWithImportConfirmOpenQuestionRoutesToImport() {
    draftStore.put(
        CONVERSATION_ID,
        pendingDraft(List.of(RequirementDraft.IMPORT_CONFIRM_OPEN_QUESTION)));

    Optional<ScenarioRouter.RoutingOutcome> outcome =
        ImportSpecificationRoutingPolicy.tryResolveManagedImportRouting(
            agreeRequest(), CONVERSATION_ID, draftStore);

    assertTrue(outcome.isPresent());
    assertEquals(ScenarioType.IMPORT_SPECIFICATION, outcome.get().scenarioType());
  }

  @Test
  void agreeWithSoftDowngradedUiDraftRoutesToImport() {
    // Mirrors browser QA: soft-downgrade left NEEDS_INPUT + candidate + importIntent + pinned
    // import-confirm open-Q; user Agree must hit IMPORT_SPECIFICATION (not stay on gather).
    draftStore.put(
        CONVERSATION_ID,
        new RequirementDraft(
            false,
            "Create a CIP HTTP proxy for Geographic Site Care API",
            DraftDecision.NEEDS_INPUT,
            List.of(RequirementDraft.IMPORT_CONFIRM_OPEN_QUESTION),
            null,
            null,
            null,
            new ApiHubRequirementRefs(
                "S.CustParty.Care.GeoSite",
                "2026.2@1",
                "geographicSiteManagement-v4-geographicSite-_id_-get",
                "api",
                "rest",
                null,
                null),
            null,
            false,
            List.of(),
            true));

    Optional<ScenarioRouter.RoutingOutcome> outcome =
        ImportSpecificationRoutingPolicy.tryResolveManagedImportRouting(
            agreeRequest(), CONVERSATION_ID, draftStore);

    assertTrue(outcome.isPresent());
    assertEquals(ScenarioType.IMPORT_SPECIFICATION, outcome.get().scenarioType());
  }

  @Test
  void importSpecificationCommandRoutesToImportWithPendingCandidate() {
    draftStore.put(
        CONVERSATION_ID,
        pendingDraft(List.of(RequirementDraft.IMPORT_CONFIRM_OPEN_QUESTION)));

    ChatRequest request = new ChatRequest();
    request.setResolvedEffectiveUserText("Import specification");

    Optional<ScenarioRouter.RoutingOutcome> outcome =
        ImportSpecificationRoutingPolicy.tryResolveManagedImportRouting(
            request, CONVERSATION_ID, draftStore);

    assertTrue(outcome.isPresent());
    assertEquals(ScenarioType.IMPORT_SPECIFICATION, outcome.get().scenarioType());
  }

  @Test
  void agreeWithNonImportOpenQuestionDoesNotRouteToImport() {
    draftStore.put(
        CONVERSATION_ID,
        pendingDraft(List.of("What authentication does the upstream API require?")));

    Optional<ScenarioRouter.RoutingOutcome> outcome =
        ImportSpecificationRoutingPolicy.tryResolveManagedImportRouting(
            agreeRequest(), CONVERSATION_ID, draftStore);

    assertTrue(outcome.isEmpty());
  }

  private static ChatRequest agreeRequest() {
    ChatRequest request = new ChatRequest();
    request.setResolvedEffectiveUserText("Agree");
    return request;
  }

  private static RequirementDraft pendingDraft(List<String> openQuestions) {
    return new RequirementDraft(
        false,
        "GeoSite proxy",
        DraftDecision.NEEDS_INPUT,
        openQuestions,
        null,
        null,
        null,
        new ApiHubRequirementRefs(
            "S.CustParty.Care.GeoSite",
            "2026.2@1",
            "geographicSiteManagement-v4-geographicSite-_id_-get",
            "api",
            "rest",
            null,
            null),
        null,
        false);
  }
}
