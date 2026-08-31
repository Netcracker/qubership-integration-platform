package org.qubership.integration.platform.ai.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.integration.apihub.ApiHubRequirementRefs;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.CatalogBindingHint;

class RequirementDraftImportIntentTest {

  @Test
  void importIntentDefaultsToFalse() {
    RequirementDraft draft = new RequirementDraft(false, "partial vision");

    assertFalse(draft.importIntent());
  }

  @Test
  void importIntentDefaultsWhenMissingFromLegacyJson() throws Exception {
    ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    String legacy =
        """
        {
          "complete": false,
          "assembledText": "legacy draft",
          "decision": "NEEDS_INPUT",
          "openQuestions": [],
          "sourceSkillId": null,
          "sourceSkillVersion": null,
          "sourceSkillHash": null,
          "apiHubCandidate": null,
          "catalogBinding": null,
          "awaitingPlanContinuation": false,
          "facts": []
        }
        """;

    RequirementDraft draft = mapper.readValue(legacy, RequirementDraft.class);

    assertFalse(draft.importIntent());
    assertEquals("legacy draft", draft.assembledText());
  }

  @Test
  void importIntentSurvivesStoreRoundTrip() {
    RequirementDraftStore store = new RequirementDraftStore();
    store.put(
        "conv-intent",
        new RequirementDraft(false, "cold import intent").withImportIntent(true));

    RequirementDraft recovered = store.get("conv-intent").orElseThrow();

    assertTrue(recovered.importIntent());
    assertEquals("cold import intent", recovered.assembledText());
  }

  @Test
  void clearApiHubCandidateKeepsImportIntent() {
    RequirementDraft draft =
        new RequirementDraft(false, "GeoSite proxy")
            .withImportIntent(true)
            .withApiHubCandidate(sampleCandidate());

    RequirementDraft cleared = draft.clearApiHubCandidate();

    assertNull(cleared.apiHubCandidate());
    assertTrue(cleared.importIntent());
    assertFalse(cleared.hasPendingImport());
  }

  @Test
  void withBoundServiceCallClearsCandidateAndImportIntent() {
    RequirementFact call = sampleCall();
    RequirementDraft draft =
        new RequirementDraft(
                false,
                "GeoSite proxy",
                DraftDecision.NEEDS_INPUT,
                List.of(),
                null,
                null,
                null,
                null,
                false,
                List.of(call),
                true)
            .withApiHubCandidate(sampleCandidate(), call.serviceCallId())
            .withBoundServiceCall(call.serviceCallId(), sampleHint(call));

    assertNull(draft.apiHubCandidate());
    assertFalse(draft.importIntent());
    assertEquals("sys-1", draft.catalogBindings().getFirst().systemId());
    // Ticket 10: binding no longer opens a "Continue" prose gate after import.
    assertFalse(draft.awaitingPlanContinuation());
    assertFalse(draft.hasPendingImport());
  }

  @Test
  void withApiHubCandidateLeavesOpenQuestionsEmptyWhilePending() {
    // The import reaches the reader as a decision card, not a pinned open question.
    RequirementFact call = sampleCall();
    RequirementDraft draft =
        new RequirementDraft(
                false,
                "GeoSite proxy",
                DraftDecision.NEEDS_INPUT,
                List.of("What trigger?", "What response?"),
                null,
                null,
                null,
                null,
                false,
                List.of(call),
                false)
            .withApiHubCandidate(sampleCandidate(), call.serviceCallId());

    assertTrue(draft.openQuestions().isEmpty());
    assertTrue(draft.importIntent());
    assertTrue(draft.hasPendingImport());
  }

  @Test
  void ensureImportIntentSeedsAssembledTextWhenBlank() {
    RequirementDraftStore store = new RequirementDraftStore();
    store.ensureImportIntent(
        "conv-seed",
        "Import OpenAPI from APIHub package S.CustParty.Care.GeoSite before design");

    RequirementDraft draft = store.get("conv-seed").orElseThrow();
    assertTrue(draft.importIntent());
    assertTrue(draft.assembledText().contains("S.CustParty.Care.GeoSite"));
  }

  @Test
  void ensureImportIntentDoesNotOverwriteExistingVision() {
    RequirementDraftStore store = new RequirementDraftStore();
    store.put("conv-keep", new RequirementDraft(false, "existing vision").withImportIntent(false));
    store.ensureImportIntent("conv-keep", "Import OpenAPI from S.CustParty.Care.GeoSite");

    RequirementDraft draft = store.get("conv-keep").orElseThrow();
    assertTrue(draft.importIntent());
    assertEquals("existing vision", draft.assembledText());
  }

  private static ApiHubRequirementRefs sampleCandidate() {
    return new ApiHubRequirementRefs(
        "S.CustParty.Care.GeoSite",
        "2026.2@1",
        "geographicSiteManagement-v4-geographicSite-_id_-get",
        "api",
        "rest",
        null,
        null);
  }

  private static RequirementFact sampleCall() {
    return new RequirementFact(
        "call-geosite",
        RequirementFactPolarity.POSITIVE,
        RequirementFactKind.SERVICE_CALL,
        "",
        "getGeographicSite",
        "GeoSite",
        "getGeographicSite",
        "",
        "",
        "",
        "call-geosite");
  }

  private static CatalogBindingHint sampleHint(RequirementFact call) {
    return new CatalogBindingHint(
        "2",
        call.serviceCallId(),
        call.sourceFactId(),
        call.operation().isBlank() ? "service-call" : call.operation(),
        "sys-1",
        "group-1",
        "spec-1",
        "op-1",
        null,
        null,
        null,
        "catalog",
        Instant.EPOCH,
        "test");
  }
}
