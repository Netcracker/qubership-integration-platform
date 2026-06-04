package org.qubership.integration.platform.ai.chat.planning;

import org.jboss.logmanager.MDC;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chat.ChatMdc;
import org.qubership.integration.platform.ai.model.ScenarioType;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiHubImportCandidateRecorderTest {

  private final ConversationPlanningDiaryService diaryService = new ConversationPlanningDiaryService();
  private final ApiHubImportCandidateRecorder recorder =
      new ApiHubImportCandidateRecorder(diaryService, new com.fasterxml.jackson.databind.ObjectMapper());

  @AfterEach
  void clearMdc() {
    MDC.remove(ChatMdc.CONVERSATION_ID);
    MDC.remove(ChatMdc.SCENARIO_TYPE);
  }

  @Test
  void recordsCandidateFromPackagesListWhenCatalogWasEmpty() {
    String conversationId = "conv-packages-list";
    diaryService.recordCatalogLookupNote(
        conversationId,
        "searchCatalogSystems",
        "searchCondition=Service Catalog Management",
        "empty",
        "[]");
    MDC.put(ChatMdc.CONVERSATION_ID, conversationId);
    MDC.put(ChatMdc.SCENARIO_TYPE, ScenarioType.CREATE_CHAIN_PLAN.name());

    recorder.recordFromPackagesListIfApplicable(
        """
        {"packages":[
          {"packageId":"S.ProdCat.AgrMgmt","name":"Agreement Management","versions":[{"version":"2026.1@2"}]},
          {"packageId":"S.ActProv.SvcCat","name":"Service Catalog","versions":[{"version":"2026.1@1"}]}
        ]}
        """);

    Optional<ApiHubImportCandidate> candidate =
        diaryService.resolveImportCandidate(conversationId, null);
    assertTrue(candidate.isPresent());
    assertEquals("S.ActProv.SvcCat", candidate.get().apiHubPackageId());
    assertEquals("2026.1@1", candidate.get().apiHubVersion());
  }

  @Test
  void recordsCandidateFromSearchWhenCatalogWasEmpty() {
    String conversationId = "conv-auto-import";
    diaryService.recordCatalogLookupNote(
        conversationId,
        "searchCatalogSystems",
        "searchCondition=Service Catalog Management",
        "empty",
        "[]");
    MDC.put(ChatMdc.CONVERSATION_ID, conversationId);
    MDC.put(ChatMdc.SCENARIO_TYPE, ScenarioType.CREATE_CHAIN_PLAN.name());

    recorder.recordFromSearchResultIfApplicable(
        """
        {"items":[{"operationId":"op-get","documentId":"api","packageId":"S.ActProv.SvcCat",\
        "packageName":"Service Catalog","version":"2026.1@1","title":"Retrieve spec","method":"get"}]}
        """);

    Optional<ApiHubImportCandidate> candidate =
        diaryService.resolveImportCandidate(conversationId, null);
    assertTrue(candidate.isPresent());
    assertEquals("S.ActProv.SvcCat", candidate.get().apiHubPackageId());
    assertEquals("2026.1@1", candidate.get().apiHubVersion());
    assertEquals("Service Catalog", candidate.get().apiHubSpecificationName());
    assertEquals("Service Catalog Management", candidate.get().catalogSystemName());
    assertEquals("INTERNAL", candidate.get().catalogSystemType());
  }

  @Test
  void parseSearchConditionFromDiaryDetail() {
    assertEquals(
        "Service Catalog Management",
        ApiHubImportCandidateRecorder.parseSearchConditionFromDiaryDetail(
            "searchCondition=Service Catalog Management"));
  }

  @Test
  void searchHitUpdatesVersionOverPackagesListGuess() {
    String conversationId = "conv-version-upsert";
    diaryService.recordCatalogLookupNote(
        conversationId,
        "searchCatalogSystems",
        "searchCondition=Service Catalog Management",
        "empty",
        "[]");
    MDC.put(ChatMdc.CONVERSATION_ID, conversationId);
    MDC.put(ChatMdc.SCENARIO_TYPE, ScenarioType.CREATE_CHAIN_PLAN.name());

    recorder.recordFromPackagesListIfApplicable(
        """
        {"packages":[
          {"packageId":"S.ActProv.SvcCat","name":"Service Catalog","versions":[{"version":"2026.3@1"}]}
        ]}
        """);
    recorder.recordFromSearchResultIfApplicable(
        """
        {"items":[{"operationId":"op-get","documentId":"api","packageId":"S.ActProv.SvcCat",\
        "packageName":"Service Catalog","version":"2026.1@1","title":"Retrieve spec","method":"get"}]}
        """);

    Optional<ApiHubImportCandidate> candidate =
        diaryService.resolveImportCandidate(conversationId, null);
    assertTrue(candidate.isPresent());
    assertEquals("2026.1@1", candidate.get().apiHubVersion());
  }
}
