package org.qubership.integration.platform.ai.chat.planning;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversationPlanningDiaryImportCandidateTest {

  private final ConversationPlanningDiaryService diaryService = new ConversationPlanningDiaryService();

  @Test
  void recordAndResolveLatestImportCandidate() {
    ApiHubImportCandidate saved =
        diaryService.recordImportCandidate(
            "conv-import",
            "Service Catalog Management",
            "INTERNAL",
            "S.ActProv.SvcCat",
            "2026.1@1",
            "api",
            "Service Catalog",
            "from search");

    assertTrue(saved.hasRequiredFields());
    Optional<ApiHubImportCandidate> latest =
        diaryService.resolveImportCandidate("conv-import", null);
    assertTrue(latest.isPresent());
    assertEquals(saved.candidateId(), latest.get().candidateId());
    assertEquals("Service Catalog", latest.get().apiHubSpecificationName());
  }

  @Test
  void formatAppendixIncludesImportCandidateAndResult() {
    diaryService.recordImportCandidate(
        "conv-appendix",
        "Petstore",
        "EXTERNAL",
        "pkg",
        "1.0",
        "api",
        "Petstore API",
        null);

    String pendingAppendix = diaryService.formatAppendix("conv-appendix");
    assertTrue(pendingAppendix.contains("ApiHub import candidates"));
    assertTrue(pendingAppendix.contains("Petstore API"));
    assertTrue(pendingAppendix.contains("BLOCKING (CREATE_CHAIN_PLAN)"));

    diaryService.markImportHandoffPending("conv-appendix");
    String handoffAppendix = diaryService.formatAppendix("conv-appendix");
    assertTrue(handoffAppendix.contains("IMPORT handoff pending"));

    diaryService.recordImportResult(
        "conv-appendix",
        new ApiHubImportResult(
            "c1",
            Instant.now(),
            "sys-1",
            "spec-1",
            "grp-1",
            "imp-1",
            "Petstore API"));

    String appendix = diaryService.formatAppendix("conv-appendix");
    assertTrue(appendix.contains("Last ApiHub import result"));
    assertTrue(appendix.contains("spec-1"));
    assertFalse(appendix.contains("BLOCKING (CREATE_CHAIN_PLAN)"));
  }
}
