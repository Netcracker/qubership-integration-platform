package org.qubership.integration.platform.ai.llm.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
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
import org.qubership.integration.platform.ai.productpipeline.create.design.model.CatalogBindingHint;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementServiceCall;

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
        pendingDraft("Create a CIP HTTP proxy for Geographic Site Care API"));

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
  void legacyAwaitingPlanContinuationAdvancesWithoutContinueKeyword() {
    // Ticket 10: "Continue" is no longer a prose gate. A leftover awaiting flag clears and
    // routes to CREATE_CHAIN_PLAN on the next turn.
    draftStore.put(
        CONVERSATION_ID,
        new RequirementDraft(true, "GeoSite proxy").withAwaitingPlanContinuation(true));

    ChatRequest request = new ChatRequest();
    request.setResolvedEffectiveUserText("what is next?");

    Optional<ScenarioRouter.RoutingOutcome> outcome =
        ImportSpecificationRoutingPolicy.tryResolveManagedImportRouting(
            request, CONVERSATION_ID, draftStore);

    assertTrue(outcome.isPresent());
    assertEquals(ScenarioType.CREATE_CHAIN_PLAN, outcome.get().scenarioType());
    assertFalse(draftStore.get(CONVERSATION_ID).orElseThrow().awaitingPlanContinuation());
  }

  @Test
  void scenarioHintWithoutPendingImportDoesNotRouteToImport() {
    draftStore.put(CONVERSATION_ID, new RequirementDraft(false, "GeoSite proxy"));

    Optional<ScenarioRouter.RoutingOutcome> outcome =
        ImportSpecificationRoutingPolicy.tryResolveManagedImportRouting(
            importDecisionRequest(), CONVERSATION_ID, draftStore);

    assertTrue(outcome.isEmpty());
  }

  @Test
  void scenarioHintForBoundSelectedCallReturnsAlreadyImported() {
    draftStore.put(CONVERSATION_ID, alreadyBoundDraft());

    Optional<ScenarioRouter.RoutingOutcome> outcome =
        ImportSpecificationRoutingPolicy.tryResolveManagedImportRouting(
            importDecisionRequest(), CONVERSATION_ID, draftStore);

    assertTrue(outcome.isPresent());
    assertNull(outcome.get().scenarioType());
    assertEquals(
        ImportSpecificationRoutingPolicy.ALREADY_IMPORTED_MESSAGE, outcome.get().terminalMessage());
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
    return pendingDraft("GeoSite proxy");
  }

  private static RequirementDraft pendingDraft(String assembledText) {
    RequirementServiceCall call =
        new RequirementServiceCall("call-1", "fact-1", "GeoSite", "getGeographicSite");
    return new RequirementDraft(
        false,
        assembledText,
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
        false,
        List.of(),
        true,
        List.of(call),
        "call-1",
        null);
  }

  private static RequirementDraft alreadyBoundDraft() {
    RequirementServiceCall call =
        new RequirementServiceCall("call-1", "fact-1", "GeoSite", "getGeographicSite");
    CatalogBindingHint hint =
        new CatalogBindingHint(
            "2",
            "call-1",
            "fact-1",
            "getGeographicSite",
            "system-1",
            "group-1",
            "specification-1",
            "operation-1",
            null,
            null,
            null,
            "catalog",
            Instant.EPOCH,
            "test");
    return new RequirementDraft(
            false,
            "GeoSite proxy",
            DraftDecision.NEEDS_INPUT,
            List.of(),
            null,
            null,
            null,
            pendingDraft().apiHubCandidate(),
            false,
            List.of(),
            true,
            List.of(call),
            "call-1",
            null)
        .withBoundServiceCall("call-1", hint);
  }
}
