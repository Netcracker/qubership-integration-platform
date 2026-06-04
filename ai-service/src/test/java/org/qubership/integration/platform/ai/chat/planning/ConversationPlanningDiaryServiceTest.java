package org.qubership.integration.platform.ai.chat.planning;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversationPlanningDiaryServiceTest {

  @Test
  void formatAppendixIncludesIdsHitlAndKeys() {
    ConversationPlanningDiaryService svc = new ConversationPlanningDiaryService();
    String conv = "c1";
    svc.recordDesignHintsFromUserTurn(
        conv, "Intro\n\n**Document ID:** QIP.INT.IDS.Example\n", List.of("tenant/doc.md"));
    svc.recordHitlCheckpointOpened(conv, "cp1", "Pick an option?", List.of("A", "B"));
    svc.recordHitlCheckpointResolved(conv, "cp1", "A");
    svc.recordCatalogLookupNote(
        conv, "searchCatalogSystems", "searchCondition=Petshop", "empty", "[]");

    String out = svc.formatAppendix(conv);
    assertTrue(out.contains("QIP.INT.IDS.Example"));
    assertTrue(out.contains("tenant/doc.md"));
    assertTrue(out.contains("hitl_open"));
    assertTrue(out.contains("hitl_resolved"));
    assertTrue(out.contains("catalog_empty"));
    assertTrue(out.contains("searchCatalogSystems"));
  }

  @Test
  void formatAppendixIncludesCatalogServicesResolved() {
    ConversationPlanningDiaryService svc = new ConversationPlanningDiaryService();
    String conv = "c-catalog";
    svc.recordCatalogSystemsFound(
        conv,
        "Pet store",
        java.util.List.of(
            new org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient
                .SystemDto(
                "364ea2f4-8918-4e47-9fc3-17652f1706d3", "Pet store", "IMPLEMENTED", "http")));
    svc.recordCatalogSpecifications(
        conv,
        "364ea2f4-8918-4e47-9fc3-17652f1706d3",
        java.util.List.of(
            new org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient
                .SpecificationDto(
                "364ea2f4-8918-4e47-9fc3-17652f1706d3-swagger-1.0.7",
                "1.0.7",
                null,
                "364ea2f4-8918-4e47-9fc3-17652f1706d3")));
    svc.recordCatalogOperationsLoaded(
        conv, "364ea2f4-8918-4e47-9fc3-17652f1706d3-swagger-1.0.7", 20);

    String out = svc.formatAppendix(conv);
    assertTrue(out.contains("Catalog services resolved"));
    assertTrue(out.contains("Pet store"));
    assertTrue(out.contains("364ea2f4-8918-4e47-9fc3-17652f1706d3-swagger-1.0.7"));
    assertTrue(out.contains("20 operations"));
  }

  @Test
  void blocksApiHubUntilCatalogCandidateIsProbed() {
    ConversationPlanningDiaryService svc = new ConversationPlanningDiaryService();
    String conv = "c-catalog-first";
    svc.recordCatalogSystemsFound(
        conv,
        "Service Catalog Management",
        java.util.List.of(
            new org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient
                .SystemDto(
                "1dcba94b-9cec-477e-9e5b-4e24363d6a25",
                "Service Catalog Management",
                "INTERNAL",
                "http")));

    var beforeSpecs = svc.apiHubBlockedByIncompleteCatalogPath(conv);
    assertTrue(beforeSpecs.isPresent());
    assertTrue(beforeSpecs.get().contains("getApiSpecifications"));

    svc.recordCatalogLookupNote(
        conv, "getApiSpecifications", "systemId=1dcba94b-9cec-477e-9e5b-4e24363d6a25", "empty", "[]");

    assertFalse(svc.apiHubBlockedByIncompleteCatalogPath(conv).isPresent());
  }
}
