package org.qubership.integration.platform.ai.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jboss.logmanager.MDC;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chat.ToolSession;
import org.qubership.integration.platform.ai.chat.ChatMdc;
import org.qubership.integration.platform.ai.integration.apihub.ApiHubRequirementRefs;
import org.qubership.integration.platform.ai.integration.apihub.ConversationApiHubCache;
import org.qubership.integration.platform.ai.integration.catalog.cache.CatalogOperationsReadCache;
import org.qubership.integration.platform.ai.integration.catalog.cache.ConversationCatalogCache;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.productpipeline.create.design.execution.CatalogBindingMatcher;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementServiceCall;
import org.qubership.integration.platform.ai.qipknowledge.pack.QipKnowledgePackManifest;
import org.qubership.integration.platform.ai.qipknowledge.pack.QipKnowledgePackRepository;
import org.qubership.integration.platform.ai.qipknowledge.pack.QipKnowledgePackVersion;

class RequirementDraftToolTest {

  private final RequirementDraftStore store = new RequirementDraftStore();
  private final RequirementDraftTool tool = new RequirementDraftTool(store);

  @AfterEach
  void clearMdc() {
    MDC.remove(ChatMdc.CONVERSATION_ID);
  }

  @Test
  void captureStoresDraftAndMarksTurn() {
    MDC.put(ChatMdc.CONVERSATION_ID, "draft-conv");
    store.beginTurn("draft-conv");

    String result =
        tool.captureRequirementDraft(
            new RequirementDraftCapture(true, "HTTP GET /orders returns status", DraftDecision.READY_FOR_PLAN, List.of(), null, null, sampleFacts()));

    assertTrue(result.contains("Requirement draft captured"));
    assertTrue(store.wasCapturedThisTurn("draft-conv"));
    RequirementDraft draft = store.get("draft-conv").orElseThrow();
    assertTrue(draft.complete());
    assertEquals("HTTP GET /orders returns status", draft.assembledText());
    assertEquals(DraftDecision.READY_FOR_PLAN, draft.decision());
    assertEquals("brainstorming", draft.sourceSkillId());
    assertEquals("unknown", draft.sourceSkillVersion());
    assertEquals("unknown", draft.sourceSkillHash());
  }

  @Test
  void captureStoresDraftWhenBoundViaToolSessionWithoutChatMdc() {
    store.beginTurn("draft-tool-session");
    try (ToolSession.Handle ignored = ToolSession.open("draft-tool-session")) {
      String result =
          tool.captureRequirementDraft(
              new RequirementDraftCapture(
                  true,
                  "HTTP GET /hello returns Good day",
                  DraftDecision.READY_FOR_PLAN,
                  List.of(),
                  null,
                  null,
                  sampleFacts()));
      assertTrue(result.contains("Requirement draft captured"));
    }
    assertTrue(store.wasCapturedThisTurn("draft-tool-session"));
  }

  @Test
  void captureStoresSourceSkillHashWhenPackManifestAvailable() {
    RequirementDraftTool tool = new RequirementDraftTool(store, repositoryWithBrainstorming());
    MDC.put(ChatMdc.CONVERSATION_ID, "draft-conv");
    store.beginTurn("draft-conv");

    tool.captureRequirementDraft(
        new RequirementDraftCapture(true, "HTTP GET /orders returns status", DraftDecision.READY_FOR_PLAN, List.of(), null, null, sampleFacts()));

    RequirementDraft draft = store.get("draft-conv").orElseThrow();
    assertEquals("cip_compiler_v2", draft.sourceSkillVersion());
    assertEquals(64, draft.sourceSkillHash().length());
    assertFalse("unknown".equals(draft.sourceSkillHash()));
  }

  @Test
  void captureKeepsPinnedSourceVersionAndHash() {
    RequirementDraftTool tool = new RequirementDraftTool(store, repositoryWithBrainstorming());
    MDC.put(ChatMdc.CONVERSATION_ID, "draft-conv");
    store.beginTurn("draft-conv");
    store.put(
        "draft-conv",
        new RequirementDraft(
            false,
            "old vision",
            DraftDecision.NEEDS_INPUT,
            List.of("Question?"),
            RequirementDraftTool.SOURCE_SKILL_ID,
            "old_pack",
            "old_hash"));

    tool.captureRequirementDraft(
        new RequirementDraftCapture(true, "HTTP GET /orders returns status", DraftDecision.READY_FOR_PLAN, List.of(), null, null, sampleFacts()));

    RequirementDraft draft = store.get("draft-conv").orElseThrow();
    assertEquals("old_pack", draft.sourceSkillVersion());
    assertEquals("old_hash", draft.sourceSkillHash());
  }

  @Test
  void readyForPlanWithOpenQuestionsIsRejected() {
    MDC.put(ChatMdc.CONVERSATION_ID, "draft-conv");
    store.beginTurn("draft-conv");

    String result =
        tool.captureRequirementDraft(
            new RequirementDraftCapture(
                true,
                "HTTP GET /orders returns status",
                DraftDecision.READY_FOR_PLAN,
                List.of("Which response fields should be returned?"),
                null,
                null,
                sampleFacts()));

    assertTrue(result.contains("openQuestions must be empty"));
    assertTrue(store.get("draft-conv").isEmpty());
    assertFalse(store.wasCapturedThisTurn("draft-conv"));
  }

  @Test
  void needsInputWithoutOpenQuestionsIsRejected() {
    MDC.put(ChatMdc.CONVERSATION_ID, "draft-conv");
    store.beginTurn("draft-conv");

    String result =
        tool.captureRequirementDraft(
            new RequirementDraftCapture(
                false,
                "HTTP GET /orders returns status",
                DraftDecision.NEEDS_INPUT,
                List.of()));

    assertTrue(result.contains("openQuestions is required"));
    assertTrue(store.get("draft-conv").isEmpty());
    assertFalse(store.wasCapturedThisTurn("draft-conv"));
  }

  @Test
  void readyForPlanWithPendingApiHubCandidateSoftDowngradesToNeedsInput() {
    MDC.put(ChatMdc.CONVERSATION_ID, "draft-conv");
    store.beginTurn("draft-conv");

    String result =
        tool.captureRequirementDraft(
            new RequirementDraftCapture(
                true,
                "Party lookup proxy via Party Management API",
                DraftDecision.READY_FOR_PLAN,
                List.of(),
                new ApiHubRequirementRefs(
                    "S.ProdCat.PartyMgmt", "2026.2@1", "op-get", null, "rest", null, null),
                null,
                sampleFacts()));

    assertTrue(result.contains("pending"));
    assertTrue(result.contains("offered the import as a decision"));
    RequirementDraft draft = store.get("draft-conv").orElseThrow();
    assertEquals(DraftDecision.NEEDS_INPUT, draft.decision());
    assertTrue(draft.hasPendingImport());
    assertTrue(draft.importIntent());
    assertTrue(draft.openQuestions().isEmpty());
    assertEquals("S.ProdCat.PartyMgmt", draft.apiHubCandidate().packageId());
    assertFalse(draft.readyForPlan());
  }

  @Test
  void captureFillsApiHubCandidateFromConversationCacheWhenAgentOmitsIt() {
    ConversationApiHubCache apiHubCache = new ConversationApiHubCache();
    apiHubCache.rememberCandidate(
        "draft-conv",
        new ApiHubRequirementRefs(
            "S.CustParty.Care.GeoSite",
            "2026.2@1",
            "geographicSiteManagement-v4-geographicSite-_id_-get",
            "api",
            "rest",
            "Geographic Site",
            null));
    RequirementDraftTool tool = RequirementDraftTool.withCaches(store, null, apiHubCache);
    MDC.put(ChatMdc.CONVERSATION_ID, "draft-conv");
    store.beginTurn("draft-conv");

    String result =
        tool.captureRequirementDraft(
            new RequirementDraftCapture(
                false,
                "Proxy Geographic Site GET by id from APIHub",
                DraftDecision.NEEDS_INPUT,
                List.of("Reply Import specification to import the API."),
                null,
                null,
                sampleFacts()));

    assertTrue(result.contains("Requirement draft captured"));
    RequirementDraft draft = store.get("draft-conv").orElseThrow();
    assertTrue(draft.hasPendingImport());
    assertEquals("S.CustParty.Care.GeoSite", draft.apiHubCandidate().packageId());
    assertEquals("2026.2@1", draft.apiHubCandidate().version());
  }

  @Test
  void captureSoftDowngradesBlockedWhenApiHubCacheHasHit() {
    ConversationApiHubCache apiHubCache = new ConversationApiHubCache();
    apiHubCache.rememberCandidate(
        "draft-conv",
        new ApiHubRequirementRefs(
            "S.ProdCat.PartyMgmt",
            "2026.2@1",
            "partyManagement-v5-partyManagement-v5-party-_id_-get",
            "api",
            "rest",
            "Party Management",
            null));
    RequirementDraftTool tool = RequirementDraftTool.withCaches(store, null, apiHubCache);
    MDC.put(ChatMdc.CONVERSATION_ID, "draft-conv");
    store.beginTurn("draft-conv");

    String result =
        tool.captureRequirementDraft(
            new RequirementDraftCapture(
                false,
                "Create a chain that periodically checks Party Management",
                DraftDecision.BLOCKED,
                List.of("Could you provide the search criteria?"),
                null,
                null,
                sampleFacts()));

    assertTrue(result.contains("not BLOCKED"));
    assertTrue(result.contains("offered the import as a decision"));
    RequirementDraft draft = store.get("draft-conv").orElseThrow();
    assertEquals(DraftDecision.NEEDS_INPUT, draft.decision());
    assertTrue(draft.hasPendingImport());
    assertEquals("S.ProdCat.PartyMgmt", draft.apiHubCandidate().packageId());
    assertTrue(draft.openQuestions().isEmpty());
  }

  @Test
  void secondReadyForPlanCaptureSameTurnReturnsStopHint() {
    MDC.put(ChatMdc.CONVERSATION_ID, "draft-conv");
    store.beginTurn("draft-conv");

    tool.captureRequirementDraft(
        new RequirementDraftCapture(
            true,
            "HTTP GET /orders returns status",
            DraftDecision.READY_FOR_PLAN,
            List.of(),
            null,
            null,
            sampleFacts()));

    String second =
        tool.captureRequirementDraft(
            new RequirementDraftCapture(
                true,
                "HTTP GET /orders returns status",
                DraftDecision.READY_FOR_PLAN,
                List.of(),
                null,
                null,
                sampleFacts()));

    assertTrue(second.contains("already READY_FOR_PLAN"));
    assertTrue(second.contains("Do not call captureRequirementDraft again"));
    assertEquals(DraftDecision.READY_FOR_PLAN, store.get("draft-conv").orElseThrow().decision());
  }

  @Test
  void planningTextIncludesResolvedCatalogBinding() {
    store.put(
        "draft-conv",
        new RequirementDraft(
            true,
            "Party lookup proxy",
            DraftDecision.READY_FOR_PLAN,
            List.of(),
            RequirementDraftTool.SOURCE_SKILL_ID,
            "pack",
            "hash",
            null,
            new ResolvedCatalogBinding("sys-1", "spec-1", "group-1", "op-1", "EXTERNAL"),
            false));

    RequirementDraft draft = store.get("draft-conv").orElseThrow();
    assertTrue(draft.planningText().contains("Resolved catalog binding:"));
    assertTrue(draft.planningText().contains("systemId: sys-1"));
    assertTrue(draft.planningText().contains("systemType: EXTERNAL"));
  }

  @Test
  void captureSoftDowngradesReadyWithoutBindingAfterOperationsLoaded() {
    ConversationCatalogCache cache = new ConversationCatalogCache(mock(CatalogOperationsReadCache.class));
    RequirementDraftTool tool = RequirementDraftTool.withCache(store, cache);
    MDC.put(ChatMdc.CONVERSATION_ID, "draft-conv");
    store.beginTurn("draft-conv");
    seedCatalogCache(cache, "draft-conv");

    String result =
        tool.captureRequirementDraft(
            new RequirementDraftCapture(
                true,
                "Call Petstore Ext findPetsByStatus Pending",
                DraftDecision.READY_FOR_PLAN,
                List.of(),
                null,
                null,
                sampleFacts()));

    assertFalse(result.contains("catalogBinding"), result);
    assertTrue(result.contains("resolveApiOperation"), result);
    RequirementDraft draft = store.get("draft-conv").orElseThrow();
    assertEquals(DraftDecision.NEEDS_INPUT, draft.decision());
    assertNull(draft.catalogBinding());
    assertEquals(List.of(RequirementDraftTool.BINDING_REQUIRED_OPEN_QUESTION), draft.openQuestions());
  }

  @Test
  void captureRequiresBindingForEveryServiceCallEvenBeforeCatalogToolsWereUsed() {
    RequirementDraftTool tool = new RequirementDraftTool(store);
    MDC.put(ChatMdc.CONVERSATION_ID, "draft-conv");
    store.beginTurn("draft-conv");

    String result =
        tool.captureRequirementDraft(
            new RequirementDraftCapture(
                true,
                "Call Petstore Ext getInventory",
                DraftDecision.READY_FOR_PLAN,
                List.of(),
                null,
                null,
                List.of(
                    RequirementFact.of(
                        RequirementFactPolarity.POSITIVE,
                        RequirementFactKind.SERVICE_CALL,
                        "Petstore Ext",
                        "GET /store/inventory"))));

    assertFalse(result.contains("catalogBinding"), result);
    assertTrue(result.contains("serviceCallId="), result);
    RequirementDraft draft = store.get("draft-conv").orElseThrow();
    assertEquals(DraftDecision.NEEDS_INPUT, draft.decision());
    assertTrue(draft.openQuestions().getFirst().contains("serviceCallId="), draft.openQuestions().getFirst());
  }

  @Test
  void captureNamesTheServiceCallThatIsStillUnresolved() {
    ConversationApiResolutions resolutions = new ConversationApiResolutions();
    RequirementDraftTool tool = RequirementDraftTool.withResolutions(store, resolutions);
    MDC.put(ChatMdc.CONVERSATION_ID, "draft-conv");
    store.beginTurn("draft-conv");
    RequirementFact inventory = serviceCallFact("call-inventory", "Petstore Ext", "GET /store/inventory");
    RequirementFact invoice = serviceCallFact("call-invoice", "Billing", "POST /invoices");
    resolutions.remember("draft-conv", assessment(inventory));

    tool.captureRequirementDraft(
        new RequirementDraftCapture(
            true,
            "Read stock, then raise an invoice",
            DraftDecision.READY_FOR_PLAN,
            List.of(),
            null,
            null,
            List.of(inventory, invoice)));

    RequirementDraft draft = store.get("draft-conv").orElseThrow();
    assertEquals(DraftDecision.NEEDS_INPUT, draft.decision());
    String question = draft.openQuestions().getFirst();
    assertTrue(question.contains("serviceCallId=call-invoice"), question);
    assertTrue(question.contains("participant=Billing"), question);
    assertTrue(question.contains("operation=POST /invoices"), question);
    assertFalse(question.contains("call-inventory"), question);
    assertFalse(question.contains("/store/inventory"), question);
  }

  @Test
  void captureIsReadyWhenEveryServiceCallHasItsOwnResolution() {
    ConversationApiResolutions resolutions = new ConversationApiResolutions();
    RequirementDraftTool tool = RequirementDraftTool.withResolutions(store, resolutions);
    MDC.put(ChatMdc.CONVERSATION_ID, "draft-conv");
    store.beginTurn("draft-conv");
    RequirementFact inventory = serviceCallFact("call-inventory", "Petstore Ext", "GET /store/inventory");
    RequirementFact invoice = serviceCallFact("call-invoice", "Billing", "POST /invoices");
    resolutions.remember("draft-conv", assessment(inventory));
    resolutions.remember("draft-conv", assessment(invoice));

    tool.captureRequirementDraft(
        new RequirementDraftCapture(
            true,
            "Read stock, then raise an invoice",
            DraftDecision.READY_FOR_PLAN,
            List.of(),
            null,
            null,
            List.of(inventory, invoice)));

    RequirementDraft draft = store.get("draft-conv").orElseThrow();
    assertEquals(DraftDecision.READY_FOR_PLAN, draft.decision());
    assertTrue(draft.openQuestions().isEmpty());
    assertNull(draft.catalogBinding());
    assertEquals(2, draft.serviceCalls().size());
    assertEquals("call-inventory", draft.serviceCalls().get(0).serviceCallId());
    assertEquals("call-invoice", draft.serviceCalls().get(1).serviceCallId());
    assertEquals(
        "op-call-inventory",
        draft.serviceCalls().get(0).catalogBinding().integrationOperationId());
    assertEquals(
        "op-call-invoice",
        draft.serviceCalls().get(1).catalogBinding().integrationOperationId());
  }

  @Test
  void captureIsReadyWhenListedCatalogOperationsCoverEachServiceCall() {
    ConversationCatalogCache cache =
        new ConversationCatalogCache(mock(CatalogOperationsReadCache.class));
    ConversationApiResolutions resolutions = new ConversationApiResolutions();
    RequirementDraftTool tool = new RequirementDraftTool(store, null, cache, null, resolutions);
    MDC.put(ChatMdc.CONVERSATION_ID, "draft-conv");
    store.beginTurn("draft-conv");
    cache.rememberSystems(
        "draft-conv",
        List.of(
            new CatalogRestClient.SystemDto("sys-om", "OM", "EXTERNAL", "http"),
            new CatalogRestClient.SystemDto("sys-wfm", "Salesforce WFM", "EXTERNAL", "http")));
    cache.rememberSpecifications(
        "draft-conv",
        List.of(
            new CatalogRestClient.SpecificationDto("spec-om", "swagger", "group-om", "sys-om"),
            new CatalogRestClient.SpecificationDto("spec-wfm", "swagger", "group-wfm", "sys-wfm")));
    cache.rememberOperation(
        "draft-conv",
        new CatalogRestClient.OperationDto(
            "op-onTaskResult", "onTaskResult", "POST", "/onTaskResult", "spec-om"));
    cache.rememberOperation(
        "draft-conv",
        new CatalogRestClient.OperationDto(
            "op-createTask", "createTask", "POST", "/createTask", "spec-wfm"));
    RequirementFact omCall = serviceCallFact("call-om-result", "OM", "onTaskResult");
    RequirementFact wfmCall =
        serviceCallFact("call-wfm-create-task", "Salesforce WFM", "createTask");

    tool.captureRequirementDraft(
        new RequirementDraftCapture(
            true,
            "Bind OM and Salesforce WFM. Do not use APIHub.",
            DraftDecision.READY_FOR_PLAN,
            List.of(),
            null,
            null,
            List.of(omCall, wfmCall)));

    RequirementDraft draft = store.get("draft-conv").orElseThrow();
    assertEquals(DraftDecision.READY_FOR_PLAN, draft.decision());
    assertTrue(draft.openQuestions().isEmpty());
    assertNull(draft.catalogBinding());
    assertEquals(2, draft.serviceCalls().size());
    RequirementServiceCall storedOm = draft.serviceCalls().get(0);
    RequirementServiceCall storedWfm = draft.serviceCalls().get(1);
    assertEquals("call-om-result", storedOm.serviceCallId());
    assertEquals("call-wfm-create-task", storedWfm.serviceCallId());
    assertEquals("op-onTaskResult", storedOm.catalogBinding().integrationOperationId());
    assertEquals("op-createTask", storedWfm.catalogBinding().integrationOperationId());
    assertTrue(
        resolutions.forServiceCall("draft-conv", "call-om-result").orElseThrow().isResolved());
    assertTrue(
        resolutions
            .forServiceCall("draft-conv", "call-wfm-create-task")
            .orElseThrow()
            .isResolved());
  }

  @Test
  void captureAsksForTheFieldsAnIncompleteIntentLacks() {
    ConversationApiResolutions resolutions = new ConversationApiResolutions();
    RequirementDraftTool tool = RequirementDraftTool.withResolutions(store, resolutions);
    MDC.put(ChatMdc.CONVERSATION_ID, "draft-conv");
    store.beginTurn("draft-conv");
    RequirementFact call =
        RequirementFact.of(
            RequirementFactPolarity.POSITIVE,
            RequirementFactKind.SERVICE_CALL,
            "Billing",
            "Raise an invoice somewhere in Billing");
    resolutions.remember(
        "draft-conv",
        ServiceCallAssessment.incomplete(
            call.sourceFactId(),
            new ServiceCallAssessment.Intent(call.text(), "Billing", null, null, null)));

    tool.captureRequirementDraft(
        new RequirementDraftCapture(
            true,
            "Raise an invoice",
            DraftDecision.READY_FOR_PLAN,
            List.of(),
            null,
            null,
            List.of(call)));

    RequirementDraft draft = store.get("draft-conv").orElseThrow();
    assertEquals(DraftDecision.NEEDS_INPUT, draft.decision());
    String question = draft.openQuestions().getFirst();
    assertTrue(question.contains("operationHint"), question);
    assertTrue(question.contains("method"), question);
  }

  @Test
  void captureAsksWhichCandidateAnAmbiguousMatchMeant() {
    ConversationApiResolutions resolutions = new ConversationApiResolutions();
    RequirementDraftTool tool = RequirementDraftTool.withResolutions(store, resolutions);
    MDC.put(ChatMdc.CONVERSATION_ID, "draft-conv");
    store.beginTurn("draft-conv");
    RequirementFact call =
        RequirementFact.of(
            RequirementFactPolarity.POSITIVE,
            RequirementFactKind.SERVICE_CALL,
            "Petstore Ext",
            "GET /store/inventory");
    resolutions.remember(
        "draft-conv",
        ServiceCallAssessment.ambiguous(
            call.sourceFactId(),
            new ServiceCallAssessment.Intent(call.text(), "Petstore", null, "GET", "/store/inventory"),
            List.of("op-v1", "op-v2")));

    tool.captureRequirementDraft(
        new RequirementDraftCapture(
            true,
            "Read stock levels",
            DraftDecision.READY_FOR_PLAN,
            List.of(),
            null,
            null,
            List.of(call)));

    RequirementDraft draft = store.get("draft-conv").orElseThrow();
    assertEquals(DraftDecision.NEEDS_INPUT, draft.decision());
    String question = draft.openQuestions().getFirst();
    assertTrue(question.contains("op-v1"), question);
    assertTrue(question.contains("op-v2"), question);
  }

  @Test
  void captureAllowsTwoCallsToShareOneCatalogOperation() {
    ConversationApiResolutions resolutions = new ConversationApiResolutions();
    RequirementDraftTool tool = RequirementDraftTool.withResolutions(store, resolutions);
    MDC.put(ChatMdc.CONVERSATION_ID, "draft-conv");
    store.beginTurn("draft-conv");
    RequirementFact first = serviceCallFact("call-om-result", "OM", "onTaskResult");
    RequirementFact second = serviceCallFact("call-om-again", "OM", "onTaskResult");
    CatalogBindingMatcher.CatalogMatch shared = sharedOmMatch();
    resolutions.remember("draft-conv", resolved(first, shared));
    resolutions.remember("draft-conv", resolved(second, shared));

    tool.captureRequirementDraft(
        new RequirementDraftCapture(
            true,
            "Notify OM twice",
            DraftDecision.READY_FOR_PLAN,
            List.of(),
            null,
            null,
            List.of(first, second)));

    RequirementDraft draft = store.get("draft-conv").orElseThrow();
    assertEquals(DraftDecision.READY_FOR_PLAN, draft.decision());
    assertEquals(2, draft.serviceCalls().size());
    assertEquals("op-shared", draft.serviceCalls().get(0).catalogBinding().integrationOperationId());
    assertEquals("op-shared", draft.serviceCalls().get(1).catalogBinding().integrationOperationId());
    assertEquals("call-om-result", draft.serviceCalls().get(0).serviceCallId());
    assertEquals("call-om-again", draft.serviceCalls().get(1).serviceCallId());
  }

  @Test
  void captureRetainsBindingsWhenCallsAreReordered() {
    ConversationApiResolutions resolutions = new ConversationApiResolutions();
    RequirementDraftTool tool = RequirementDraftTool.withResolutions(store, resolutions);
    MDC.put(ChatMdc.CONVERSATION_ID, "draft-conv");
    store.beginTurn("draft-conv");
    RequirementFact om = serviceCallFact("call-om-result", "OM", "onTaskResult");
    RequirementFact wfm = serviceCallFact("call-wfm-create-task", "Salesforce WFM", "createTask");
    resolutions.remember("draft-conv", assessment(om));
    resolutions.remember("draft-conv", assessment(wfm));
    tool.captureRequirementDraft(
        new RequirementDraftCapture(
            true,
            "OM then WFM",
            DraftDecision.READY_FOR_PLAN,
            List.of(),
            null,
            null,
            List.of(om, wfm)));
    store.beginTurn("draft-conv");

    tool.captureRequirementDraft(
        new RequirementDraftCapture(
            true,
            "WFM then OM",
            DraftDecision.READY_FOR_PLAN,
            List.of(),
            null,
            null,
            List.of(wfm, om)));

    RequirementDraft draft = store.get("draft-conv").orElseThrow();
    assertEquals(DraftDecision.READY_FOR_PLAN, draft.decision());
    assertEquals("call-wfm-create-task", draft.serviceCalls().get(0).serviceCallId());
    assertEquals("call-om-result", draft.serviceCalls().get(1).serviceCallId());
    assertEquals(
        "op-call-wfm-create-task",
        draft.serviceCalls().get(0).catalogBinding().integrationOperationId());
    assertEquals(
        "op-call-om-result",
        draft.serviceCalls().get(1).catalogBinding().integrationOperationId());
  }

  @Test
  void captureClearsOnlyEditedCallBinding() {
    ConversationApiResolutions resolutions = new ConversationApiResolutions();
    RequirementDraftTool tool = RequirementDraftTool.withResolutions(store, resolutions);
    MDC.put(ChatMdc.CONVERSATION_ID, "draft-conv");
    store.beginTurn("draft-conv");
    RequirementFact om = serviceCallFact("call-om-result", "OM", "onTaskResult");
    RequirementFact wfm = serviceCallFact("call-wfm-create-task", "Salesforce WFM", "createTask");
    resolutions.remember("draft-conv", assessment(om));
    resolutions.remember("draft-conv", assessment(wfm));
    tool.captureRequirementDraft(
        new RequirementDraftCapture(
            true,
            "OM then WFM",
            DraftDecision.READY_FOR_PLAN,
            List.of(),
            null,
            null,
            List.of(om, wfm)));
    store.beginTurn("draft-conv");
    RequirementFact editedOm = serviceCallFact("call-om-result", "OM", "getOrder");

    tool.captureRequirementDraft(
        new RequirementDraftCapture(
            true,
            "OM getOrder then WFM",
            DraftDecision.READY_FOR_PLAN,
            List.of(),
            null,
            null,
            List.of(editedOm, wfm)));

    RequirementDraft draft = store.get("draft-conv").orElseThrow();
    assertEquals(DraftDecision.NEEDS_INPUT, draft.decision());
    assertEquals("call-om-result", draft.serviceCalls().get(0).serviceCallId());
    assertNull(draft.serviceCalls().get(0).catalogBinding());
    assertEquals("call-wfm-create-task", draft.serviceCalls().get(1).serviceCallId());
    assertEquals(
        "op-call-wfm-create-task",
        draft.serviceCalls().get(1).catalogBinding().integrationOperationId());
  }

  @Test
  void captureRemovesDeletedCallAndAssessment() {
    ConversationApiResolutions resolutions = new ConversationApiResolutions();
    RequirementDraftTool tool = RequirementDraftTool.withResolutions(store, resolutions);
    MDC.put(ChatMdc.CONVERSATION_ID, "draft-conv");
    store.beginTurn("draft-conv");
    RequirementFact om = serviceCallFact("call-om-result", "OM", "onTaskResult");
    RequirementFact wfm = serviceCallFact("call-wfm-create-task", "Salesforce WFM", "createTask");
    resolutions.remember("draft-conv", assessment(om));
    resolutions.remember("draft-conv", assessment(wfm));
    tool.captureRequirementDraft(
        new RequirementDraftCapture(
            true,
            "OM then WFM",
            DraftDecision.READY_FOR_PLAN,
            List.of(),
            null,
            null,
            List.of(om, wfm)));
    store.beginTurn("draft-conv");

    tool.captureRequirementDraft(
        new RequirementDraftCapture(
            true,
            "OM only",
            DraftDecision.READY_FOR_PLAN,
            List.of(),
            null,
            null,
            List.of(om)));

    RequirementDraft draft = store.get("draft-conv").orElseThrow();
    assertEquals(1, draft.serviceCalls().size());
    assertEquals("call-om-result", draft.serviceCalls().getFirst().serviceCallId());
    assertTrue(draft.serviceCalls().getFirst().catalogBinding() != null);
    assertTrue(resolutions.forServiceCall("draft-conv", "call-om-result").isPresent());
    assertTrue(resolutions.forServiceCall("draft-conv", "call-wfm-create-task").isEmpty());
  }

  @Test
  void repeatedCaptureKeepsBindingTimestamp() {
    ConversationApiResolutions resolutions = new ConversationApiResolutions();
    RequirementDraftTool tool = RequirementDraftTool.withResolutions(store, resolutions);
    MDC.put(ChatMdc.CONVERSATION_ID, "draft-conv");
    store.beginTurn("draft-conv");
    RequirementFact om = serviceCallFact("call-om-result", "OM", "onTaskResult");
    Instant observedAt = Instant.parse("2026-08-27T10:15:00Z");
    resolutions.remember("draft-conv", resolvedAt(om, assessment(om).binding(), observedAt));
    tool.captureRequirementDraft(
        new RequirementDraftCapture(
            true,
            "Call OM",
            DraftDecision.READY_FOR_PLAN,
            List.of(),
            null,
            null,
            List.of(om)));
    Instant stored =
        store.get("draft-conv").orElseThrow().serviceCalls().getFirst().catalogBinding().observedAt();
    store.beginTurn("draft-conv");

    tool.captureRequirementDraft(
        new RequirementDraftCapture(
            true,
            "Call OM",
            DraftDecision.READY_FOR_PLAN,
            List.of(),
            null,
            null,
            List.of(om)));

    RequirementDraft draft = store.get("draft-conv").orElseThrow();
    assertEquals(stored, draft.serviceCalls().getFirst().catalogBinding().observedAt());
    assertEquals(observedAt, draft.serviceCalls().getFirst().catalogBinding().observedAt());
  }

  @Test
  void captureRejectsDuplicateServiceCallId() {
    MDC.put(ChatMdc.CONVERSATION_ID, "draft-conv");
    store.beginTurn("draft-conv");
    RequirementFact first =
        new RequirementFact(
            "fact-om-1",
            RequirementFactPolarity.POSITIVE,
            RequirementFactKind.SERVICE_CALL,
            "",
            "Call OM onTaskResult",
            "OM",
            "onTaskResult",
            "",
            "",
            "",
            "call-om-result");
    RequirementFact duplicate =
        new RequirementFact(
            "fact-om-2",
            RequirementFactPolarity.POSITIVE,
            RequirementFactKind.SERVICE_CALL,
            "",
            "Call OM onTaskResult again",
            "OM",
            "onTaskResult",
            "",
            "",
            "",
            "call-om-result");

    String result =
        tool.captureRequirementDraft(
            new RequirementDraftCapture(
                true,
                "Notify OM twice with the same call id",
                DraftDecision.READY_FOR_PLAN,
                List.of(),
                null,
                null,
                List.of(first, duplicate)));

    assertTrue(result.contains("call-om-result"), result);
    assertTrue(result.contains("duplicate"), result.toLowerCase());
    assertTrue(store.get("draft-conv").isEmpty());
  }

  @Test
  void unresolvedMessageNamesServiceCallIdParticipantAndOperation() {
    RequirementDraftTool tool = new RequirementDraftTool(store);
    MDC.put(ChatMdc.CONVERSATION_ID, "draft-conv");
    store.beginTurn("draft-conv");
    RequirementFact om = serviceCallFact("call-om-result", "OM", "onTaskResult");
    RequirementFact wfm = serviceCallFact("call-wfm-create-task", "Salesforce WFM", "createTask");

    String result =
        tool.captureRequirementDraft(
            new RequirementDraftCapture(
                true,
                "Bind OM and Salesforce WFM",
                DraftDecision.READY_FOR_PLAN,
                List.of(),
                null,
                null,
                List.of(om, wfm)));

    assertFalse(result.contains("catalogBinding"), result);
    assertTrue(
        result.contains("serviceCallId=call-om-result, participant=OM, operation=onTaskResult"),
        result);
    assertTrue(
        result.contains(
            "serviceCallId=call-wfm-create-task, participant=Salesforce WFM, operation=createTask"),
        result);
    assertTrue(result.contains("resolveApiOperation"), result);
  }

  private static ServiceCallAssessment assessment(RequirementFact call) {
    return resolved(
        call,
        new CatalogBindingMatcher.CatalogMatch(
            "sys-" + call.serviceCallId(),
            "group-1",
            "spec-1",
            "op-" + call.serviceCallId(),
            call.participant(),
            "http",
            "GET",
            "/probe",
            "probe",
            "catalog-read:probe"));
  }

  private static ServiceCallAssessment resolved(
      RequirementFact call, CatalogBindingMatcher.CatalogMatch match) {
    return resolvedAt(call, match, Instant.parse("2026-08-27T09:00:00Z"));
  }

  private static ServiceCallAssessment resolvedAt(
      RequirementFact call, CatalogBindingMatcher.CatalogMatch match, Instant observedAt) {
    return new ServiceCallAssessment(
        call.serviceCallId(),
        call.sourceFactId(),
        new ServiceCallAssessment.Intent(
            call.text(), call.participant(), call.operation(), call.httpMethod(), call.path()),
        ServiceCallAssessment.Outcome.RESOLVED,
        match,
        List.of(),
        List.of(),
        match.evidenceRef(),
        observedAt);
  }

  private static CatalogBindingMatcher.CatalogMatch sharedOmMatch() {
    return new CatalogBindingMatcher.CatalogMatch(
        "sys-om",
        "group-om",
        "spec-om",
        "op-shared",
        "OM",
        "http",
        "POST",
        "/onTaskResult",
        "onTaskResult",
        "catalog-read:om");
  }

  private static RequirementFact serviceCallFact(
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

  @Test
  void captureStoresVerifiedCatalogBindingAndClearsApiHubCandidate() {
    ConversationCatalogCache cache = new ConversationCatalogCache(mock(CatalogOperationsReadCache.class));
    RequirementDraftTool tool = RequirementDraftTool.withCache(store, cache);
    MDC.put(ChatMdc.CONVERSATION_ID, "draft-conv");
    store.beginTurn("draft-conv");
    seedCatalogCache(cache, "draft-conv");

    String result =
        tool.captureRequirementDraft(
            new RequirementDraftCapture(
                true,
                "Proxy Petstore Ext getInventory",
                DraftDecision.READY_FOR_PLAN,
                List.of(),
                new ApiHubRequirementRefs(
                    "pkg", "1.0", "op-get", null, "rest", null, null),
                new ResolvedCatalogBinding("sys-1", "spec-1", "group-1", "op-1"),
                List.of(serviceCallFact("call-inventory", "Petstore Ext", "getInventory"))));

    assertTrue(result.contains("Requirement draft captured"));
    RequirementDraft draft = store.get("draft-conv").orElseThrow();
    assertNull(draft.catalogBinding());
    assertEquals("sys-1", draft.serviceCalls().getFirst().catalogBinding().systemId());
    assertEquals("op-1", draft.serviceCalls().getFirst().catalogBinding().integrationOperationId());
    assertNull(draft.apiHubCandidate());
    assertEquals(DraftDecision.READY_FOR_PLAN, draft.decision());
  }

  @Test
  void captureIgnoresBogusCatalogBindingWhenApiHubCacheHasHit() {
    ConversationCatalogCache catalogCache =
        new ConversationCatalogCache(mock(CatalogOperationsReadCache.class));
    ConversationApiHubCache apiHubCache = new ConversationApiHubCache();
    apiHubCache.rememberCandidate(
        "draft-conv",
        new ApiHubRequirementRefs(
            "S.CustParty.Care.GeoSite",
            "2026.2@1",
            "geographicSiteManagement-v4-geographicSite-_id_-get",
            "api",
            "rest",
            "Geographic Site",
            null));
    RequirementDraftTool tool = RequirementDraftTool.withCaches(store, catalogCache, apiHubCache);
    MDC.put(ChatMdc.CONVERSATION_ID, "draft-conv");
    store.beginTurn("draft-conv");

    String result =
        tool.captureRequirementDraft(
            new RequirementDraftCapture(
                true,
                "Import Geographic Site specification from APIHub into CIP catalog",
                DraftDecision.READY_FOR_PLAN,
                List.of(),
                null,
                new ResolvedCatalogBinding("sys-fake", "spec-fake", "group-fake", "op-fake"),
                sampleFacts()));

    assertTrue(result.contains("pending") || result.contains("Requirement draft captured"));
    RequirementDraft draft = store.get("draft-conv").orElseThrow();
    assertNull(draft.catalogBinding());
    assertTrue(draft.hasPendingImport());
    assertTrue(draft.importIntent());
    assertEquals("S.CustParty.Care.GeoSite", draft.apiHubCandidate().packageId());
    assertEquals(DraftDecision.NEEDS_INPUT, draft.decision());
    assertTrue(draft.openQuestions().isEmpty());
  }

  @Test
  void captureIgnoresIncompleteCatalogBindingWhenApiHubCandidateProvided() {
    ConversationCatalogCache catalogCache =
        new ConversationCatalogCache(mock(CatalogOperationsReadCache.class));
    RequirementDraftTool tool = RequirementDraftTool.withCaches(store, catalogCache, null);
    MDC.put(ChatMdc.CONVERSATION_ID, "draft-conv");
    store.beginTurn("draft-conv");

    String result =
        tool.captureRequirementDraft(
            new RequirementDraftCapture(
                false,
                "Import Geographic Site specification from APIHub",
                DraftDecision.NEEDS_INPUT,
                List.of("Which system?"),
                new ApiHubRequirementRefs(
                    "S.CustParty.Care.GeoSite", "2026.2@1", "op-get", "api", "rest", null, null),
                new ResolvedCatalogBinding(null, null, null, null),
                sampleFacts()));

    assertTrue(result.contains("Requirement draft captured") || result.contains("pending"));
    RequirementDraft draft = store.get("draft-conv").orElseThrow();
    assertNull(draft.catalogBinding());
    assertTrue(draft.hasPendingImport());
    assertEquals("S.CustParty.Care.GeoSite", draft.apiHubCandidate().packageId());
  }

  @Test
  void captureRejectsInventedCatalogBindingWithoutToolCache() {
    ConversationCatalogCache cache = new ConversationCatalogCache(mock(CatalogOperationsReadCache.class));
    RequirementDraftTool tool = RequirementDraftTool.withCache(store, cache);
    MDC.put(ChatMdc.CONVERSATION_ID, "draft-conv");
    store.beginTurn("draft-conv");

    String result =
        tool.captureRequirementDraft(
            new RequirementDraftCapture(
                true,
                "Proxy Petstore Ext getInventory",
                DraftDecision.READY_FOR_PLAN,
                List.of(),
                null,
                new ResolvedCatalogBinding("sys-fake", "spec-fake", "group-fake", "op-fake"),
                sampleFacts()));

    assertTrue(result.contains("searchCatalogSystems first"));
    assertTrue(store.get("draft-conv").isEmpty());
  }

  @Test
  void captureRejectsCatalogBindingSystemNotInCache() {
    ConversationCatalogCache cache = new ConversationCatalogCache(mock(CatalogOperationsReadCache.class));
    RequirementDraftTool tool = RequirementDraftTool.withCache(store, cache);
    MDC.put(ChatMdc.CONVERSATION_ID, "draft-conv");
    store.beginTurn("draft-conv");
    seedCatalogCache(cache, "draft-conv");

    String result =
        tool.captureRequirementDraft(
            new RequirementDraftCapture(
                true,
                "Proxy other service",
                DraftDecision.READY_FOR_PLAN,
                List.of(),
                null,
                new ResolvedCatalogBinding("sys-other", "spec-1", "group-1", "op-1"),
                sampleFacts()));

    assertTrue(result.contains("searchCatalogSystems"));
    assertTrue(store.get("draft-conv").isEmpty());
  }

  @Test
  void readyForPlanWithoutFactsSoftDowngradesToNeedsInput() {
    MDC.put(ChatMdc.CONVERSATION_ID, "draft-conv");
    store.beginTurn("draft-conv");

    String result =
        tool.captureRequirementDraft(
            new RequirementDraftCapture(
                true,
                "HTTP GET /greetings returns Hello via script; no service calls",
                DraftDecision.READY_FOR_PLAN,
                List.of(),
                null,
                null,
                List.of()));

    assertTrue(result.contains("NEEDS_INPUT"));
    assertTrue(result.contains("facts were empty"));
    assertTrue(store.wasCapturedThisTurn("draft-conv"));
    RequirementDraft draft = store.get("draft-conv").orElseThrow();
    assertEquals(DraftDecision.NEEDS_INPUT, draft.decision());
    assertFalse(draft.complete());
    assertFalse(draft.readyForPlan());
    assertEquals(1, draft.openQuestions().size());
    assertTrue(draft.openQuestions().get(0).contains("must this chain do"));
    assertTrue(draft.facts().isEmpty());
  }

  @Test
  void captureCanonicalizesCatalogTriggerCapabilityKindToEndpoint() {
    MDC.put(ChatMdc.CONVERSATION_ID, "draft-conv");
    store.beginTurn("draft-conv");

    String result =
        tool.captureRequirementDraft(
            new RequirementDraftCapture(
                true,
                "Consume Kafka user events and look up a pet",
                DraftDecision.READY_FOR_PLAN,
                List.of(),
                null,
                null,
                List.of(
                    new RequirementFact(
                        "trigger-1",
                        RequirementFactPolarity.POSITIVE,
                        RequirementFactKind.CAPABILITY,
                        "kafka-trigger-2",
                        "Consume user events",
                        "",
                        "consumeUserEvent",
                        "user/events",
                        "",
                        ""),
                    RequirementFact.of(
                        RequirementFactPolarity.NEGATIVE,
                        RequirementFactKind.CONSTRAINT,
                        "",
                        "Do not call MCP"))));

    assertTrue(result.contains("Requirement draft captured"), result);
    RequirementFact stored = store.get("draft-conv").orElseThrow().facts().getFirst();
    assertEquals(RequirementFactKind.ENDPOINT, stored.kind());
    assertEquals("kafka-trigger-2", stored.capabilityKey());
  }

  @Test
  void captureRejectsAmbiguousTriggerKind() {
    MDC.put(ChatMdc.CONVERSATION_ID, "draft-conv");
    store.beginTurn("draft-conv");

    String result =
        tool.captureRequirementDraft(
            new RequirementDraftCapture(
                true,
                "Consume Kafka user events",
                DraftDecision.READY_FOR_PLAN,
                List.of(),
                null,
                null,
                List.of(
                    new RequirementFact(
                        "trigger-1",
                        RequirementFactPolarity.POSITIVE,
                        RequirementFactKind.SERVICE_CALL,
                        "kafka-trigger-2",
                        "Consume user events",
                        "Petstore Ext",
                        "getPetById",
                        "user/events",
                        "",
                        ""),
                    RequirementFact.of(
                        RequirementFactPolarity.NEGATIVE,
                        RequirementFactKind.CONSTRAINT,
                        "",
                        "Do not call MCP"))));

    assertTrue(result.contains("kafka-trigger-2"), result);
    assertTrue(result.contains("SERVICE_CALL"), result);
    assertTrue(store.get("draft-conv").isEmpty());
  }

  private static List<RequirementFact> sampleFacts() {
    return List.of(
        RequirementFact.of(
            RequirementFactPolarity.POSITIVE,
            RequirementFactKind.BEHAVIOR,
            "http",
            "HTTP GET /orders returns status"));
  }

  private static void seedCatalogCache(ConversationCatalogCache cache, String conversationId) {
    cache.rememberSystems(
        conversationId,
        List.of(new CatalogRestClient.SystemDto("sys-1", "Petstore Ext", "EXTERNAL", "http")));
    cache.rememberSpecifications(
        conversationId,
        List.of(new CatalogRestClient.SpecificationDto("spec-1", "swagger", "group-1", "sys-1")));
    cache.rememberOperation(
        conversationId,
        new CatalogRestClient.OperationDto("op-1", "getInventory", "GET", "/store/inventory", "spec-1"));
  }

  private static QipKnowledgePackRepository repositoryWithBrainstorming() {
    QipKnowledgePackRepository repository = mock(QipKnowledgePackRepository.class);
    QipKnowledgePackVersion version =
        new QipKnowledgePackVersion("cip_compiler_v2", "cip_compiler_v2");
    when(repository.activeVersion()).thenReturn(version);
    String brainstormingChecksum =
        "a".repeat(64);
    when(repository.loadManifest())
        .thenReturn(
            new QipKnowledgePackManifest(
                version,
                "/pack",
                Instant.EPOCH,
                Map.of("skills/brainstorming/SKILL.md", brainstormingChecksum),
                List.of("brainstorming"),
                List.of(),
                List.of()));
    return repository;
  }
}
