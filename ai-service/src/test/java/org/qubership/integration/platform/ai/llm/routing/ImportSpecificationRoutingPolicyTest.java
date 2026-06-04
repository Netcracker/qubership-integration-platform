package org.qubership.integration.platform.ai.llm.routing;

import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chat.planning.ConversationPlanningDiaryService;
import org.qubership.integration.platform.ai.model.ScenarioType;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ImportSpecificationRoutingPolicyTest {

  private final ConversationPlanningDiaryService diaryService = new ConversationPlanningDiaryService();

  @Test
  void routesToImportWhenHandoffPending() {
    String conversationId = "conv-import-handoff";
    diaryService.recordImportCandidate(
        conversationId,
        "Service Catalog Management",
        "INTERNAL",
        "S.ActProv.SvcCat",
        "2026.1@1",
        "api",
        "Service Catalog",
        "test");
    diaryService.markImportHandoffPending(conversationId);

    ScenarioType out =
        ImportSpecificationRoutingPolicy.effectiveScenario(
            ScenarioType.CREATE_CHAIN_PLAN,
            conversationId,
            "create a chain by design",
            diaryService);

    assertEquals(ScenarioType.IMPORT_SPECIFICATION, out);
  }

  @Test
  void staysOnCreateChainPlanWhenNoHandoff() {
    String conversationId = "conv-no-handoff";
    diaryService.recordImportCandidate(
        conversationId,
        "Service Catalog Management",
        "INTERNAL",
        "S.ActProv.SvcCat",
        "2026.1@1",
        "api",
        "Service Catalog",
        "test");

    ScenarioType out =
        ImportSpecificationRoutingPolicy.effectiveScenario(
            ScenarioType.CREATE_CHAIN_PLAN,
            conversationId,
            "create a chain by design",
            diaryService);

    assertEquals(ScenarioType.CREATE_CHAIN_PLAN, out);
  }

  @Test
  void suppressesImportOnShortConfirmationAfterImportCompleted() {
    String conversationId = "conv-import-done";
    diaryService.recordImportCandidate(
        conversationId,
        "Service Catalog Management",
        "INTERNAL",
        "S.ActProv.SvcCat",
        "2026.1@1",
        "api",
        "Service Catalog",
        "test");
    diaryService.recordImportResult(
        conversationId,
        new org.qubership.integration.platform.ai.chat.planning.ApiHubImportResult(
            "cand-1",
            java.time.Instant.now(),
            "sys-1",
            "spec-1",
            "group-1",
            "imp-1",
            "Service Catalog"));

    ScenarioType out =
        ImportSpecificationRoutingPolicy.effectiveScenario(
            ScenarioType.IMPORT_SPECIFICATION,
            conversationId,
            "i confirm",
            diaryService,
            true);

    assertEquals(ScenarioType.CREATE_CHAIN_PLAN, out);
  }

  @Test
  void suppressesEmbeddingImportRouteWhenNoImportPending() {
    String conversationId = "conv-no-pending-import";

    ScenarioType out =
        ImportSpecificationRoutingPolicy.effectiveScenario(
            ScenarioType.IMPORT_SPECIFICATION,
            conversationId,
            "Agree",
            diaryService,
            false);

    assertEquals(ScenarioType.CREATE_CHAIN_PLAN, out);
  }

  @Test
  void treatsCatalogSystemChoiceAsPlanningWhenNoImportPending() {
    String conversationId = "conv-catalog-system-choice";

    ScenarioType out =
        ImportSpecificationRoutingPolicy.effectiveScenario(
            ScenarioType.IMPORT_SPECIFICATION,
            conversationId,
            "use Service Catalog Management",
            diaryService,
            false);

    assertEquals(ScenarioType.CREATE_CHAIN_PLAN, out);
  }

  @Test
  void allowsExplicitImportBeforeImportCompleted() {
    String conversationId = "conv-explicit-import";
    diaryService.recordImportCandidate(
        conversationId,
        "Service Catalog Management",
        "INTERNAL",
        "S.ActProv.SvcCat",
        "2026.1@1",
        "api",
        "Service Catalog",
        "test");

    ScenarioType out =
        ImportSpecificationRoutingPolicy.effectiveScenario(
            ScenarioType.CREATE_CHAIN_PLAN,
            conversationId,
            "You should import this Service Catalog Management API",
            diaryService);

    assertEquals(ScenarioType.IMPORT_SPECIFICATION, out);
  }
}
