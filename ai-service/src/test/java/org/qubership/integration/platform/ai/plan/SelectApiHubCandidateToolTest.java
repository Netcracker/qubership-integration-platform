package org.qubership.integration.platform.ai.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import org.jboss.logmanager.MDC;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chat.ChatMdc;
import org.qubership.integration.platform.ai.integration.apihub.ConversationApiHubCache;
import org.qubership.integration.platform.ai.integration.catalog.ApiHubExistingCatalogBinder;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Direction;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Interaction;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Transition;

class SelectApiHubCandidateToolTest {

  private final RequirementDraftStore store = new RequirementDraftStore();
  private final ConversationApiHubCache apiHubCache = new ConversationApiHubCache();
  private final SelectApiHubCandidateTool tool = new SelectApiHubCandidateTool(store, apiHubCache);

  @AfterEach
  void clearMdc() {
    MDC.remove(ChatMdc.CONVERSATION_ID);
  }

  @Test
  void selectStoresCandidateOnDraftAndCache() {
    MDC.put(ChatMdc.CONVERSATION_ID, "select-conv");
    store.beginTurn("select-conv");
    RequirementFact call = serviceCall("call-party-search", "Party Management", "party search");
    store.put(
        "select-conv",
        withOutboundFlow(
            new RequirementDraft(
                false,
                "Periodically check Party Management",
                DraftDecision.NEEDS_INPUT,
                List.of("Which criteria?"),
                RequirementDraftTool.SOURCE_SKILL_ID,
                "pack",
                "hash",
                null,
                false,
                List.of(call),
                false),
            call));

    String result =
        tool.selectApiHubCandidate(
            "S.ProdCat.PartyMgmt",
            "2026.2@1",
            "partyManagement-v5-partyManagement-v5-party-search-post",
            null,
            "rest",
            "Party Management",
            call.serviceCallId());

    assertTrue(result.contains("\"ok\":true"));
    assertTrue(result.contains("S.ProdCat.PartyMgmt"));
    assertTrue(result.contains("offered the import as a decision"));
    RequirementDraft draft = store.get("select-conv").orElseThrow();
    assertEquals(DraftDecision.NEEDS_INPUT, draft.decision());
    assertTrue(draft.hasPendingImport());
    assertEquals("S.ProdCat.PartyMgmt", draft.apiHubCandidate().packageId());
    assertEquals("2026.2@1", draft.apiHubCandidate().version());
    assertEquals(
        "partyManagement-v5-partyManagement-v5-party-search-post",
        draft.apiHubCandidate().operationId());
    assertTrue(draft.openQuestions().isEmpty());
    assertEquals(
        "S.ProdCat.PartyMgmt",
        apiHubCache.latestCandidate("select-conv").orElseThrow().packageId());
  }

  @Test
  void selectDefaultsDocumentIdWhenOperationMissing() {
    MDC.put(ChatMdc.CONVERSATION_ID, "select-conv");
    store.beginTurn("select-conv");

    String result =
        tool.selectApiHubCandidate(
            "S.ProdCat.PartyMgmt", "2026.2@1", null, null, null, "Party Management", null);

    assertTrue(result.contains("\"ok\":true"));
    RequirementDraft draft = store.get("select-conv").orElseThrow();
    assertNull(draft.apiHubCandidate().operationId());
    assertEquals("api", draft.apiHubCandidate().documentId());
  }

  @Test
  void selectBindsExistingCatalogInsteadOfImportConfirm() {
    MDC.put(ChatMdc.CONVERSATION_ID, "select-bound");
    store.beginTurn("select-bound");
    RequirementFact call = serviceCall("call-party-search", "Party Management", "party search");
    store.put(
        "select-bound",
        withOutboundFlow(
            new RequirementDraft(
                false,
                "Periodically check Party Management",
                DraftDecision.NEEDS_INPUT,
                List.of("Which criteria?"),
                RequirementDraftTool.SOURCE_SKILL_ID,
                "pack",
                "hash",
                null,
                false,
                List.of(call),
                false),
            call));

    ApiHubExistingCatalogBinder binder = mock(ApiHubExistingCatalogBinder.class);
    when(binder.resolve(eq("select-bound"), any()))
        .thenReturn(
            Optional.of(
                new ResolvedCatalogBinding("sys-1", "spec-1", "group-1", "op-1", "INTERNAL")));
    SelectApiHubCandidateTool boundTool =
        new SelectApiHubCandidateTool(store, apiHubCache, binder);

    String result =
        boundTool.selectApiHubCandidate(
            "S.ProdCat.PartyMgmt",
            "2026.2@1",
            "partyManagement-v5-partyManagement-v5-party-search-post",
            null,
            "rest",
            "Party Management",
            call.serviceCallId());

    assertTrue(result.contains("\"ok\":true"));
    assertTrue(result.contains("catalogBinding"));
    assertFalse(result.contains("\"openQuestion\""));
    assertTrue(result.contains("already has this service"));
    RequirementDraft draft = store.get("select-bound").orElseThrow();
    assertFalse(draft.hasPendingImport());
    assertEquals("sys-1", draft.catalogBindings().getFirst().systemId());
  }

  @Test
  void selectRejectsMissingPackageAndVersion() {
    MDC.put(ChatMdc.CONVERSATION_ID, "select-conv");
    store.beginTurn("select-conv");

    String result = tool.selectApiHubCandidate(null, null, "op-1", null, "rest", null, null);

    assertTrue(result.contains("\"ok\":false"));
    assertTrue(store.get("select-conv").isEmpty());
    assertFalse(apiHubCache.latestCandidate("select-conv").isPresent());
  }

  @Test
  void selectRequiresServiceCallIdWhenSeveralCallsAreUnresolved() {
    MDC.put(ChatMdc.CONVERSATION_ID, "select-conv");
    store.beginTurn("select-conv");
    store.put(
        "select-conv",
        withOutboundFlow(
            new RequirementDraft(
                false,
                "Call OM then Salesforce WFM",
                DraftDecision.NEEDS_INPUT,
                List.of(),
                RequirementDraftTool.SOURCE_SKILL_ID,
                "pack",
                "hash",
                null,
                false,
                List.of(
                    serviceCall("call-om-result", "OM", "onTaskResult"),
                    serviceCall("call-wfm-create-task", "Salesforce WFM", "createTask")),
                false),
            serviceCall("call-om-result", "OM", "onTaskResult"),
            serviceCall("call-wfm-create-task", "Salesforce WFM", "createTask")));

    String result =
        tool.selectApiHubCandidate(
            "S.ProdCat.PartyMgmt",
            "2026.2@1",
            "partyManagement-v5-partyManagement-v5-party-search-post",
            null,
            "rest",
            "Party Management",
            null);

    assertTrue(result.contains("\"ok\":false"), result);
    assertTrue(result.contains("interactionId is required"), result);
    assertTrue(result.contains("call-om-result"), result);
    assertTrue(result.contains("call-wfm-create-task"), result);
  }

  @Test
  void selectRejectsUnknownServiceCallIdForOneCallDraft() {
    MDC.put(ChatMdc.CONVERSATION_ID, "select-one");
    store.beginTurn("select-one");
    RequirementFact call = serviceCall("call-party-search", "Party Management", "party search");
    RequirementDraft original =
        withOutboundFlow(
            new RequirementDraft(
                false,
                "Call Party Management",
                DraftDecision.NEEDS_INPUT,
                List.of("Select the operation"),
                RequirementDraftTool.SOURCE_SKILL_ID,
                "pack",
                "hash",
                null,
                false,
                List.of(call),
                false),
            call);
    store.put("select-one", original);

    String result =
        tool.selectApiHubCandidate(
            "S.ProdCat.PartyMgmt",
            "2026.2@1",
            "party-search",
            null,
            "rest",
            "Party Management",
            "unknown-call");

    assertTrue(result.contains("\"ok\":false"), result);
    assertTrue(result.contains("unknown-call"), result);
    assertEquals(original, store.get("select-one").orElseThrow());
    assertTrue(apiHubCache.latestCandidate("select-one").isEmpty());
  }

  @Test
  void selectRejectsUnknownServiceCallIdForTwoCallDraft() {
    MDC.put(ChatMdc.CONVERSATION_ID, "select-two");
    store.beginTurn("select-two");
    RequirementDraft original =
        withOutboundFlow(
            new RequirementDraft(
                false,
                "Call OM then Salesforce WFM",
                DraftDecision.NEEDS_INPUT,
                List.of("Select the operations"),
                RequirementDraftTool.SOURCE_SKILL_ID,
                "pack",
                "hash",
                null,
                false,
                List.of(
                    serviceCall("call-om-result", "OM", "onTaskResult"),
                    serviceCall("call-wfm-create-task", "Salesforce WFM", "createTask")),
                false),
            serviceCall("call-om-result", "OM", "onTaskResult"),
            serviceCall("call-wfm-create-task", "Salesforce WFM", "createTask"));
    store.put("select-two", original);

    String result =
        tool.selectApiHubCandidate(
            "S.ProdCat.PartyMgmt",
            "2026.2@1",
            "party-search",
            null,
            "rest",
            "Party Management",
            "unknown-call");

    assertTrue(result.contains("\"ok\":false"), result);
    assertTrue(result.contains("unknown-call"), result);
    assertEquals(original, store.get("select-two").orElseThrow());
    assertTrue(apiHubCache.latestCandidate("select-two").isEmpty());
  }

  @Test
  void selectRejectsDocumentOnlyCandidateBeforeCatalogLookupForOwnedCall() {
    MDC.put(ChatMdc.CONVERSATION_ID, "select-document");
    store.beginTurn("select-document");
    RequirementFact call = serviceCall("call-party-search", "Party Management", "party search");
    RequirementDraft original =
        withOutboundFlow(
            new RequirementDraft(
                false,
                "Call Party Management",
                DraftDecision.NEEDS_INPUT,
                List.of("Select the operation"),
                RequirementDraftTool.SOURCE_SKILL_ID,
                "pack",
                "hash",
                null,
                false,
                List.of(call),
                false),
            call);
    store.put("select-document", original);
    ApiHubExistingCatalogBinder binder = mock(ApiHubExistingCatalogBinder.class);
    SelectApiHubCandidateTool documentTool =
        new SelectApiHubCandidateTool(store, apiHubCache, binder);

    String result =
        documentTool.selectApiHubCandidate(
            "S.ProdCat.PartyMgmt",
            "2026.2@1",
            null,
            "api",
            "rest",
            "Party Management",
            call.serviceCallId());

    assertTrue(result.contains("\"ok\":false"), result);
    assertTrue(result.contains("operationId is required"), result);
    assertEquals(original, store.get("select-document").orElseThrow());
    assertTrue(apiHubCache.latestCandidate("select-document").isEmpty());
    verify(binder, never()).resolve(any(), any());
  }

  private static RequirementFact serviceCall(
      String serviceCallId, String participant, String operation) {
    return new RequirementFact(
        serviceCallId,
        RequirementFactPolarity.POSITIVE,
        RequirementFactKind.SERVICE_CALL,
        "",
        "Call " + participant + " " + operation,
        participant,
        operation,
        "",
        "",
        "",
        serviceCallId);
  }

  private static RequirementDraft withOutboundFlow(RequirementDraft draft, RequirementFact... calls) {
    List<Interaction> interactions = new java.util.ArrayList<>();
    List<Transition> transitions = new java.util.ArrayList<>();
    interactions.add(new Interaction("start", Direction.INBOUND, "Caller", "start", ""));
    String previous = "start";
    for (RequirementFact call : calls) {
      interactions.add(
          new Interaction(
              call.serviceCallId(),
              Direction.OUTBOUND,
              call.participant(),
              call.operation(),
              ""));
      transitions.add(new Transition(previous, call.serviceCallId()));
      previous = call.serviceCallId();
    }
    return draft.withFlow(new RequirementFlow(interactions, transitions));
  }
}
