package org.qubership.integration.platform.ai.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.integration.apihub.ApiHubRequirementRefs;

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
  void withCatalogBindingClearsCandidateAndImportIntent() {
    RequirementDraft draft =
        new RequirementDraft(false, "GeoSite proxy")
            .withImportIntent(true)
            .withApiHubCandidate(sampleCandidate())
            .withCatalogBinding(
                new ResolvedCatalogBinding("sys-1", "spec-1", "group-1", "op-1"));

    assertNull(draft.apiHubCandidate());
    assertFalse(draft.importIntent());
    assertEquals("sys-1", draft.catalogBinding().systemId());
    // Ticket 10: binding no longer opens a "Continue" prose gate after import.
    assertFalse(draft.awaitingPlanContinuation());
    assertFalse(draft.hasPendingImport());
  }

  @Test
  void withApiHubCandidateLeavesOpenQuestionsEmptyWhilePending() {
    // The import reaches the reader as a decision card, not a pinned open question.
    RequirementDraft draft =
        new RequirementDraft(
                false,
                "GeoSite proxy",
                DraftDecision.NEEDS_INPUT,
                List.of("What trigger?", "What response?"),
                null,
                null)
            .withApiHubCandidate(sampleCandidate());

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
  void legacySingletonPromotesToOnlyServiceCall() throws Exception {
    ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    String legacy =
        """
        {
          "complete": true,
          "assembledText": "HTTP then Petstore Ext getInventory",
          "decision": "READY_FOR_PLAN",
          "openQuestions": [],
          "catalogBinding": {
            "systemId": "sys-1",
            "specificationId": "spec-1",
            "specificationGroupId": "group-1",
            "integrationOperationId": "op-1"
          },
          "facts": [
            {
              "polarity": "POSITIVE",
              "kind": "SERVICE_CALL",
              "text": "Petstore Ext getInventory",
              "participant": "Petstore Ext",
              "operation": "getInventory"
            }
          ]
        }
        """;

    RequirementDraft draft = mapper.readValue(legacy, RequirementDraft.class);

    assertEquals(1, draft.serviceCalls().size());
    assertEquals("getInventory", draft.serviceCalls().getFirst().operation());
    assertEquals("sys-1", draft.serviceCalls().getFirst().catalogBinding().systemId());
    assertEquals("op-1", draft.serviceCalls().getFirst().catalogBinding().integrationOperationId());
    assertFalse(draft.serviceCalls().getFirst().serviceCallId().isBlank());
    assertNull(draft.catalogBinding());
  }

  @Test
  void legacySingletonDoesNotBindMultipleServiceCalls() throws Exception {
    ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    String legacy =
        """
        {
          "complete": false,
          "assembledText": "Call OM then Salesforce WFM",
          "decision": "NEEDS_INPUT",
          "openQuestions": [],
          "catalogBinding": {
            "systemId": "sys-shared",
            "specificationId": "spec-shared",
            "specificationGroupId": "group-shared",
            "integrationOperationId": "op-shared"
          },
          "facts": [
            {
              "polarity": "POSITIVE",
              "kind": "SERVICE_CALL",
              "text": "Call OM onTaskResult",
              "participant": "Order Management",
              "operation": "onTaskResult",
              "serviceCallId": "call-om-result"
            },
            {
              "polarity": "POSITIVE",
              "kind": "SERVICE_CALL",
              "text": "Call Salesforce WFM createTask",
              "participant": "Salesforce WFM",
              "operation": "createTask",
              "serviceCallId": "call-wfm-create-task"
            }
          ]
        }
        """;

    RequirementDraft draft = mapper.readValue(legacy, RequirementDraft.class);

    assertEquals(2, draft.serviceCalls().size());
    assertEquals("call-om-result", draft.serviceCalls().get(0).serviceCallId());
    assertEquals("call-wfm-create-task", draft.serviceCalls().get(1).serviceCallId());
    assertNull(draft.serviceCalls().get(0).catalogBinding());
    assertNull(draft.serviceCalls().get(1).catalogBinding());
    assertNull(draft.catalogBinding());
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
}
