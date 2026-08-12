package org.qubership.integration.platform.ai.llm.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chat.ChatEvent;
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
  void decisionScenarioHintRoutesPendingImportToImportSpecification() {
    draftStore.put(CONVERSATION_ID, pendingDraft());

    Optional<ScenarioRouter.RoutingOutcome> outcome =
        ImportSpecificationRoutingPolicy.tryResolveManagedImportRouting(
            importDecisionRequest(), CONVERSATION_ID, draftStore);

    assertTrue(outcome.isPresent());
    assertEquals(ScenarioType.IMPORT_SPECIFICATION, outcome.get().scenarioType());
  }

  @Test
  void decisionScenarioHintRoutesSoftDowngradedUiDraftToImportSpecification() {
    // Mirrors browser QA: a soft-downgrade leaves NEEDS_INPUT + candidate + importIntent with no
    // pinned open question. The decision's scenario hint alone still advances the import.
    draftStore.put(
        CONVERSATION_ID,
        new RequirementDraft(
            false,
            "Create a CIP HTTP proxy for Geographic Site Care API",
            DraftDecision.NEEDS_INPUT,
            List.of(),
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
            importDecisionRequest(), CONVERSATION_ID, draftStore);

    assertTrue(outcome.isPresent());
    assertEquals(ScenarioType.IMPORT_SPECIFICATION, outcome.get().scenarioType());
  }

  @Test
  void importSpecificationTypedAsProseNoLongerRoutesToImport() {
    // Mirror of the deleted phrase-matching contract: the wording that used to trigger the
    // import is now inert. Only the decision's scenario hint advances it.
    draftStore.put(CONVERSATION_ID, pendingDraft());

    ChatRequest request = new ChatRequest();
    request.setResolvedEffectiveUserText("Import specification");

    Optional<ScenarioRouter.RoutingOutcome> outcome =
        ImportSpecificationRoutingPolicy.tryResolveManagedImportRouting(
            request, CONVERSATION_ID, draftStore);

    assertTrue(outcome.isEmpty());
  }

  @Test
  void agreeWithoutScenarioHintDoesNotRouteToImport() {
    draftStore.put(CONVERSATION_ID, pendingDraft());

    Optional<ScenarioRouter.RoutingOutcome> outcome =
        ImportSpecificationRoutingPolicy.tryResolveManagedImportRouting(
            agreeRequest(), CONVERSATION_ID, draftStore);

    assertTrue(outcome.isEmpty());
  }

  @Test
  void scenarioHintWithoutPendingImportDoesNotRouteToImport() {
    draftStore.put(CONVERSATION_ID, new RequirementDraft(false, "GeoSite proxy"));

    Optional<ScenarioRouter.RoutingOutcome> outcome =
        ImportSpecificationRoutingPolicy.tryResolveManagedImportRouting(
            importDecisionRequest(), CONVERSATION_ID, draftStore);

    assertTrue(outcome.isEmpty());
  }

  private static ChatRequest agreeRequest() {
    ChatRequest request = new ChatRequest();
    request.setResolvedEffectiveUserText("Agree");
    return request;
  }

  /** What the server sends when the reader clicks the import decision card. */
  private static ChatRequest importDecisionRequest() {
    ChatRequest request = new ChatRequest();
    request.setResolvedEffectiveUserText(ChatEvent.IMPORT_MARKER);
    request.setScenarioHint(ScenarioType.IMPORT_SPECIFICATION);
    return request;
  }

  private static RequirementDraft pendingDraft() {
    return new RequirementDraft(
        false,
        "GeoSite proxy",
        DraftDecision.NEEDS_INPUT,
        List.of(),
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
