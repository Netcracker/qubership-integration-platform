package org.qubership.integration.platform.ai.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.jboss.logmanager.MDC;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Optional;
import org.qubership.integration.platform.ai.chat.ChatMdc;
import org.qubership.integration.platform.ai.integration.apihub.ConversationApiHubCache;
import org.qubership.integration.platform.ai.integration.catalog.ApiHubExistingCatalogBinder;

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
    store.put(
        "select-conv",
        new RequirementDraft(
            false,
            "Periodically check Party Management",
            DraftDecision.NEEDS_INPUT,
            List.of("Which criteria?"),
            RequirementDraftTool.SOURCE_SKILL_ID,
            "pack",
            "hash"));

    String result =
        tool.selectApiHubCandidate(
            "S.ProdCat.PartyMgmt",
            "2026.2@1",
            "partyManagement-v5-partyManagement-v5-party-search-post",
            null,
            "rest",
            "Party Management");

    assertTrue(result.contains("\"ok\":true"));
    assertTrue(result.contains("S.ProdCat.PartyMgmt"));
    assertTrue(result.contains("Import specification"));
    RequirementDraft draft = store.get("select-conv").orElseThrow();
    assertEquals(DraftDecision.NEEDS_INPUT, draft.decision());
    assertTrue(draft.hasPendingImport());
    assertEquals("S.ProdCat.PartyMgmt", draft.apiHubCandidate().packageId());
    assertEquals("2026.2@1", draft.apiHubCandidate().version());
    assertEquals(
        "partyManagement-v5-partyManagement-v5-party-search-post",
        draft.apiHubCandidate().operationId());
    assertEquals(List.of(RequirementDraft.IMPORT_CONFIRM_OPEN_QUESTION), draft.openQuestions());
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
            "S.ProdCat.PartyMgmt", "2026.2@1", null, null, null, "Party Management");

    assertTrue(result.contains("\"ok\":true"));
    RequirementDraft draft = store.get("select-conv").orElseThrow();
    assertNull(draft.apiHubCandidate().operationId());
    assertEquals("api", draft.apiHubCandidate().documentId());
  }

  @Test
  void selectBindsExistingCatalogInsteadOfImportConfirm() {
    MDC.put(ChatMdc.CONVERSATION_ID, "select-bound");
    store.beginTurn("select-bound");
    store.put(
        "select-bound",
        new RequirementDraft(
            false,
            "Periodically check Party Management",
            DraftDecision.NEEDS_INPUT,
            List.of("Which criteria?"),
            RequirementDraftTool.SOURCE_SKILL_ID,
            "pack",
            "hash"));

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
            "Party Management");

    assertTrue(result.contains("\"ok\":true"));
    assertTrue(result.contains("catalogBinding"));
    assertFalse(result.contains("\"openQuestion\""));
    assertTrue(result.contains("already has this service"));
    RequirementDraft draft = store.get("select-bound").orElseThrow();
    assertTrue(draft.readyForPlan());
    assertFalse(draft.hasPendingImport());
    assertEquals("sys-1", draft.catalogBinding().systemId());
  }

  @Test
  void selectRejectsMissingPackageAndVersion() {
    MDC.put(ChatMdc.CONVERSATION_ID, "select-conv");
    store.beginTurn("select-conv");

    String result = tool.selectApiHubCandidate(null, null, "op-1", null, "rest", null);

    assertTrue(result.contains("\"ok\":false"));
    assertTrue(store.get("select-conv").isEmpty());
    assertFalse(apiHubCache.latestCandidate("select-conv").isPresent());
  }
}
