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
import org.qubership.integration.platform.ai.chat.conversation.ConversationService;
import org.qubership.integration.platform.ai.integration.apihub.ApiHubRequirementRefs;
import org.qubership.integration.platform.ai.integration.apihub.ConversationApiHubCache;
import org.qubership.integration.platform.ai.integration.catalog.cache.CatalogOperationsReadCache;
import org.qubership.integration.platform.ai.integration.catalog.cache.ConversationCatalogCache;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.lookup.CatalogLookupResult;
import org.qubership.integration.platform.ai.integration.catalog.lookup.CatalogMatch;
import org.qubership.integration.platform.ai.integration.catalog.lookup.CatalogOperationLookup;
import org.qubership.integration.platform.ai.integration.catalog.lookup.CatalogQuery;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.CatalogBindingHint;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Direction;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Interaction;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Transition;
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
            new RequirementDraftCapture(
                true,
                "HTTP GET /orders returns status",
                DraftDecision.READY_FOR_PLAN,
                List.of(),
                null,
                sampleFacts(),
                null,
                nativeHttpFlow()));

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
  void captureToolDescriptionAuthorsBusinessFlowFirst() throws Exception {
    String description =
        String.join(
            "\n",
            RequirementDraftTool.class
                .getMethod("captureRequirementDraft", RequirementDraftCapture.class)
                .getAnnotation(dev.langchain4j.agent.tool.Tool.class)
                .value());
    assertTrue(description.contains("RequirementFlow"), description);
    assertTrue(description.contains("interactionId"), description);
    assertTrue(description.contains("INBOUND"), description);
    assertTrue(description.contains("OUTBOUND"), description);
    assertTrue(description.contains("Do not author ENDPOINT or SERVICE_CALL topology facts"), description);
    assertTrue(description.contains("binds a unique local catalog match"), description);
    assertFalse(description.contains("only outbound"), description);
    assertFalse(description.contains("Only then run catalog"), description);
    int flowJson = description.indexOf("\"flow\"");
    assertTrue(flowJson >= 0, description);
    String example = description.substring(flowJson);
    assertFalse(example.contains("publish"), example);
    assertFalse(example.contains("subscribe"), example);
    assertFalse(example.contains("\"kind\": \"ENDPOINT\""), example);
    assertFalse(example.contains("\"kind\": \"SERVICE_CALL\""), example);
    assertTrue(example.contains("order-received"), example);
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
        new RequirementDraftCapture(true, "HTTP GET /orders returns status", DraftDecision.READY_FOR_PLAN, List.of(), null, sampleFacts()));

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
        new RequirementDraftCapture(true, "HTTP GET /orders returns status", DraftDecision.READY_FOR_PLAN, List.of(), null, sampleFacts()));

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
                List.of(serviceCallFact("call-party", "Party Management", "getParty"))));

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
                List.of(serviceCallFact("call-geosite", "Geographic Site", "getSite"))));

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
                List.of(serviceCallFact("call-party", "Party Management", "getParty"))));

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
    RequirementFlow inbound =
        new RequirementFlow(
            List.of(new Interaction("orders", Direction.INBOUND, "Caller", "GET /orders", "")),
            List.of());

    tool.captureRequirementDraft(
        new RequirementDraftCapture(
            true,
            "HTTP GET /orders returns status",
            DraftDecision.READY_FOR_PLAN,
            List.of(),
            null,
            sampleFacts(),
            null,
            inbound));

    String second =
        tool.captureRequirementDraft(
            new RequirementDraftCapture(
                true,
                "HTTP GET /orders returns status",
                DraftDecision.READY_FOR_PLAN,
                List.of(),
                null,
                sampleFacts(),
                null,
                inbound));

    assertTrue(second.contains("already READY_FOR_PLAN"));
    assertTrue(second.contains("Do not call captureRequirementDraft again"));
    assertEquals(DraftDecision.READY_FOR_PLAN, store.get("draft-conv").orElseThrow().decision());
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
                sampleFacts(),
                null,
                rockyHttpFlow()));

    assertFalse(result.contains("catalogBinding"), result);
    assertTrue(result.contains("resolveApiOperation"), result);
    RequirementDraft draft = store.get("draft-conv").orElseThrow();
    assertEquals(DraftDecision.NEEDS_INPUT, draft.decision());
    assertTrue(
        draft.openQuestions().getFirst().contains("interaction create-order has no catalog binding"),
        draft.openQuestions().toString());
    assertFalse(
        draft.openQuestions().getFirst().contains("order-received has no catalog binding"),
        draft.openQuestions().toString());
  }

  @Test
  void captureRejectsServiceCallWithoutServiceCallId() {
    RequirementDraftTool tool = new RequirementDraftTool(store);
    MDC.put(ChatMdc.CONVERSATION_ID, "draft-conv");
    store.beginTurn("draft-conv");
    RequirementFact call = mock(RequirementFact.class);
    when(call.sourceFactId()).thenReturn("fact-inventory");
    when(call.polarity()).thenReturn(RequirementFactPolarity.POSITIVE);
    when(call.kind()).thenReturn(RequirementFactKind.SERVICE_CALL);
    when(call.capabilityKey()).thenReturn("");
    when(call.text()).thenReturn("GET /store/inventory");
    when(call.serviceCallId()).thenReturn("");
    when(call.needsCatalogBinding()).thenReturn(true);

    String result =
        tool.captureRequirementDraft(
            new RequirementDraftCapture(
                true,
                "Call Petstore Ext getInventory",
                DraftDecision.READY_FOR_PLAN,
                List.of(),
                null,
                List.of(call)));

    assertTrue(result.contains("serviceCallId is required"), result);
    assertTrue(store.get("draft-conv").isEmpty());
    assertFalse(store.wasCapturedThisTurn("draft-conv"));
  }

  @Test
  void captureRejectsFactWithoutText() {
    RequirementDraftTool tool = new RequirementDraftTool(store);
    MDC.put(ChatMdc.CONVERSATION_ID, "draft-conv");
    store.beginTurn("draft-conv");
    RequirementFact endpoint =
        new RequirementFact(
            null,
            RequirementFactPolarity.POSITIVE,
            RequirementFactKind.ENDPOINT,
            "http-trigger",
            "",
            "",
            "Internal health proxy endpoint",
            "",
            "GET",
            "/health-proxy",
            "");

    String result =
        tool.captureRequirementDraft(
            new RequirementDraftCapture(
                true,
                "HTTP GET /health-proxy then Petstore Ext getInventory",
                DraftDecision.READY_FOR_PLAN,
                List.of(),
                null,
                List.of(endpoint)));

    assertTrue(result.contains("text is required for every fact"), result);
    assertTrue(store.get("draft-conv").isEmpty());
    assertFalse(store.wasCapturedThisTurn("draft-conv"));
  }

  @Test
  void captureRejectsDuplicateSourceFactIdWithBothFactsAndNextAction() {
    MDC.put(ChatMdc.CONVERSATION_ID, "draft-conv");
    store.beginTurn("draft-conv");

    RequirementFact capability =
        new RequirementFact(
            "om-on-task-start",
            RequirementFactPolarity.POSITIVE,
            RequirementFactKind.CAPABILITY,
            "http-trigger",
            "Receive OM onTaskStart",
            "",
            "",
            "",
            "POST",
            "/tasks/start");
    RequirementFact behavior =
        new RequirementFact(
            "om-on-task-start",
            RequirementFactPolarity.POSITIVE,
            RequirementFactKind.BEHAVIOR,
            "",
            "commandType is completeTask");

    String result =
        tool.captureRequirementDraft(
            new RequirementDraftCapture(
                true,
                "OM onTaskStart then Salesforce createTask",
                DraftDecision.READY_FOR_PLAN,
                List.of(),
                null,
                List.of(capability, behavior),
                null,
                rockyFlow()));

    assertTrue(result.contains("CAPABILITY sourceFactId=om-on-task-start"), result);
    assertTrue(result.contains("BEHAVIOR sourceFactId=om-on-task-start"), result);
    assertTrue(result.contains("Call captureRequirementDraft again"), result);
    assertTrue(result.contains("unique sourceFactId"), result);
    assertTrue(store.get("draft-conv").isEmpty());
    assertFalse(store.wasCapturedThisTurn("draft-conv"));
    assertEquals(result, store.lastCaptureRejection("draft-conv").orElseThrow());
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
  void captureKeepsReadyForPlanWhenUploadedSpecsAreApproved() {
    ConversationService conversations = new ConversationService();
    conversations.registerAllowedAttachmentKeys(
        "draft-conv", List.of("sessions/conv/salesforce-wfm.json"));
    RequirementDraftTool tool = RequirementDraftTool.withConversationService(store, conversations);
    MDC.put(ChatMdc.CONVERSATION_ID, "draft-conv");
    store.beginTurn("draft-conv");
    RequirementFact createTask =
        serviceCallFact("call-wfm-create-task", "Salesforce WFM", "createTask");

    String result =
        tool.captureRequirementDraft(
            new RequirementDraftCapture(
                true,
                "Call Salesforce WFM createTask from the attached spec",
                DraftDecision.READY_FOR_PLAN,
                List.of(),
                null,
                List.of(
                    RequirementFact.of(
                        RequirementFactPolarity.POSITIVE,
                        RequirementFactKind.GOAL,
                        "chain",
                        "Create OM to Salesforce WFM"),
                    createTask,
                    RequirementFact.of(
                        RequirementFactPolarity.NEGATIVE,
                        RequirementFactKind.CONSTRAINT,
                        "",
                        "Do not search API Hub for the attached spec"))));

    assertFalse(result.contains("Unresolved service calls"), result);
    RequirementDraft draft = store.get("draft-conv").orElseThrow();
    assertEquals(DraftDecision.READY_FOR_PLAN, draft.decision());
    assertTrue(draft.openQuestions().isEmpty());
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
            List.of(inventory, invoice)));

    RequirementDraft draft = store.get("draft-conv").orElseThrow();
    assertEquals(DraftDecision.READY_FOR_PLAN, draft.decision());
    assertTrue(draft.openQuestions().isEmpty());
    assertEquals(2, draft.catalogBindings().size());
    assertEquals("call-inventory", draft.catalogBindings().get(0).interactionId());
    assertEquals("call-invoice", draft.catalogBindings().get(1).interactionId());
    assertEquals(
        "op-call-inventory",
        draft.catalogBindings().get(0).integrationOperationId());
    assertEquals(
        "op-call-invoice",
        draft.catalogBindings().get(1).integrationOperationId());
  }

  @Test
  void captureKeepsResolvedBindingWhenFactOmitsMethodAndPath() {
    ConversationApiResolutions resolutions = new ConversationApiResolutions();
    RequirementDraftTool tool = RequirementDraftTool.withResolutions(store, resolutions);
    MDC.put(ChatMdc.CONVERSATION_ID, "draft-conv");
    store.beginTurn("draft-conv");
    RequirementFact call = serviceCallFact("call-petstore-inventory", "Petstore Ext", "getInventory");
    resolutions.remember(
        "draft-conv",
        new InteractionAssessment(
            call.serviceCallId(),
            new InteractionAssessment.Intent(
                call.text(), "Petstore Ext", "getInventory", "GET", "/store/inventory"),
            InteractionAssessment.Outcome.RESOLVED,
            new CatalogMatch(
                "bbf14771-sys",
                "bbf14771-group",
                "bbf14771-spec",
                "bbf14771-spec-getInventory",
                "Petstore Ext",
                "http",
                "GET",
                "/store/inventory",
                "getInventory",
                "catalog-read:getInventory"),
            List.of(),
            List.of(),
            "catalog-read:getInventory",
            Instant.parse("2026-08-29T11:00:00Z")));

    tool.captureRequirementDraft(
        new RequirementDraftCapture(
            true,
            "HealthProxy calls Petstore Ext getInventory",
            DraftDecision.READY_FOR_PLAN,
            List.of(),
            null,
            List.of(call)));

    RequirementDraft draft = store.get("draft-conv").orElseThrow();
    assertEquals(DraftDecision.READY_FOR_PLAN, draft.decision());
    assertEquals(
        "bbf14771-spec-getInventory",
        draft.catalogBindings().getFirst().integrationOperationId());
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
            List.of(omCall, wfmCall)));

    RequirementDraft draft = store.get("draft-conv").orElseThrow();
    assertEquals(DraftDecision.READY_FOR_PLAN, draft.decision());
    assertTrue(draft.openQuestions().isEmpty());
    assertEquals(2, draft.catalogBindings().size());
    CatalogBindingHint storedOm = draft.catalogBindings().get(0);
    CatalogBindingHint storedWfm = draft.catalogBindings().get(1);
    assertEquals("call-om-result", storedOm.interactionId());
    assertEquals("call-wfm-create-task", storedWfm.interactionId());
    assertEquals("op-onTaskResult", storedOm.integrationOperationId());
    assertEquals("op-createTask", storedWfm.integrationOperationId());
    assertTrue(
        resolutions.forInteraction("draft-conv", "call-om-result").orElseThrow().isResolved());
    assertTrue(
        resolutions
            .forInteraction("draft-conv", "call-wfm-create-task")
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
        serviceCallFact("call-invoice", "Billing", "Raise an invoice somewhere in Billing");
    resolutions.remember(
        "draft-conv",
        InteractionAssessment.incomplete(
            call.serviceCallId(),
            new InteractionAssessment.Intent(call.text(), "Billing", null, null, null)));

    tool.captureRequirementDraft(
        new RequirementDraftCapture(
            true,
            "Raise an invoice",
            DraftDecision.READY_FOR_PLAN,
            List.of(),
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
        serviceCallFact("call-inventory", "Petstore Ext", "GET /store/inventory");
    resolutions.remember(
        "draft-conv",
        InteractionAssessment.ambiguous(
            call.serviceCallId(),
            new InteractionAssessment.Intent(call.text(), "Petstore", null, "GET", "/store/inventory"),
            List.of("op-v1", "op-v2")));

    tool.captureRequirementDraft(
        new RequirementDraftCapture(
            true,
            "Read stock levels",
            DraftDecision.READY_FOR_PLAN,
            List.of(),
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
    CatalogMatch shared = sharedOmMatch();
    resolutions.remember("draft-conv", resolved(first, shared));
    resolutions.remember("draft-conv", resolved(second, shared));

    tool.captureRequirementDraft(
        new RequirementDraftCapture(
            true,
            "Notify OM twice",
            DraftDecision.READY_FOR_PLAN,
            List.of(),
            null,
            List.of(first, second)));

    RequirementDraft draft = store.get("draft-conv").orElseThrow();
    assertEquals(DraftDecision.READY_FOR_PLAN, draft.decision());
    assertEquals(2, draft.catalogBindings().size());
    assertEquals("op-shared", draft.catalogBindings().get(0).integrationOperationId());
    assertEquals("op-shared", draft.catalogBindings().get(1).integrationOperationId());
    assertEquals("call-om-result", draft.catalogBindings().get(0).interactionId());
    assertEquals("call-om-again", draft.catalogBindings().get(1).interactionId());
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
            List.of(om, wfm)));
    store.beginTurn("draft-conv");

    tool.captureRequirementDraft(
        new RequirementDraftCapture(
            true,
            "WFM then OM",
            DraftDecision.READY_FOR_PLAN,
            List.of(),
            null,
            List.of(wfm, om)));

    RequirementDraft draft = store.get("draft-conv").orElseThrow();
    assertEquals(DraftDecision.READY_FOR_PLAN, draft.decision());
    assertEquals("call-wfm-create-task", draft.catalogBindings().get(0).interactionId());
    assertEquals("call-om-result", draft.catalogBindings().get(1).interactionId());
    assertEquals(
        "op-call-wfm-create-task",
        draft.catalogBindings().get(0).integrationOperationId());
    assertEquals(
        "op-call-om-result",
        draft.catalogBindings().get(1).integrationOperationId());
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
            List.of(editedOm, wfm)));

    RequirementDraft draft = store.get("draft-conv").orElseThrow();
    assertEquals(DraftDecision.NEEDS_INPUT, draft.decision());
    assertTrue(
        draft.catalogBindings().stream()
            .noneMatch(hint -> "call-om-result".equals(hint.interactionId())));
    assertEquals(1, draft.catalogBindings().size());
    assertEquals("call-wfm-create-task", draft.catalogBindings().getFirst().interactionId());
    assertEquals(
        "op-call-wfm-create-task",
        draft.catalogBindings().getFirst().integrationOperationId());
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
            List.of(om, wfm)));
    store.beginTurn("draft-conv");

    tool.captureRequirementDraft(
        new RequirementDraftCapture(
            true,
            "OM only",
            DraftDecision.READY_FOR_PLAN,
            List.of(),
            null,
            List.of(om)));

    RequirementDraft draft = store.get("draft-conv").orElseThrow();
    assertEquals(1, draft.catalogBindings().size());
    assertEquals("call-om-result", draft.catalogBindings().getFirst().interactionId());
    assertTrue(draft.catalogBindings().getFirst() != null);
    assertTrue(resolutions.forInteraction("draft-conv", "call-om-result").isPresent());
    assertTrue(resolutions.forInteraction("draft-conv", "call-wfm-create-task").isEmpty());
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
            List.of(om)));
    Instant stored =
        store.get("draft-conv").orElseThrow().catalogBindings().getFirst().observedAt();
    store.beginTurn("draft-conv");

    tool.captureRequirementDraft(
        new RequirementDraftCapture(
            true,
            "Call OM",
            DraftDecision.READY_FOR_PLAN,
            List.of(),
            null,
            List.of(om)));

    RequirementDraft draft = store.get("draft-conv").orElseThrow();
    assertEquals(stored, draft.catalogBindings().getFirst().observedAt());
    assertEquals(observedAt, draft.catalogBindings().getFirst().observedAt());
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

  private static InteractionAssessment assessment(RequirementFact call) {
    return resolved(
        call,
        new CatalogMatch(
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

  private static InteractionAssessment resolved(
      RequirementFact call, CatalogMatch match) {
    return resolvedAt(call, match, Instant.parse("2026-08-27T09:00:00Z"));
  }

  private static InteractionAssessment resolvedAt(
      RequirementFact call, CatalogMatch match, Instant observedAt) {
    return new InteractionAssessment(
        call.serviceCallId(),
        new InteractionAssessment.Intent(
            call.text(), call.participant(), call.operation(), call.httpMethod(), call.path()),
        InteractionAssessment.Outcome.RESOLVED,
        match,
        List.of(),
        List.of(),
        match.evidenceRef(),
        observedAt);
  }

  private static CatalogMatch sharedOmMatch() {
    return new CatalogMatch(
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
    assertEquals(RequirementFactKind.CAPABILITY, stored.kind());
    assertEquals("kafka-trigger-2", stored.capabilityKey());
  }

  @Test
  void captureBindsCatalogOnAsyncApiTriggerEndpoint() {
    ConversationApiResolutions resolutions = new ConversationApiResolutions();
    RequirementDraftTool tool = RequirementDraftTool.withResolutions(store, resolutions);
    MDC.put(ChatMdc.CONVERSATION_ID, "draft-conv");
    store.beginTurn("draft-conv");
    RequirementFact consume =
        new RequirementFact(
            "fact-consume",
            RequirementFactPolarity.POSITIVE,
            RequirementFactKind.ENDPOINT,
            "async-api-trigger",
            "Consume WFMS create work order",
            "om-order-lifecycle-manager WFMS",
            "onTaskStart",
            "",
            "",
            "",
            "consume-om");
    resolutions.remember(
        "draft-conv",
        resolved(
            consume,
            new CatalogMatch(
                "sys-om",
                "sg-om",
                "spec-om",
                "op-om",
                "om-order-lifecycle-manager WFMS",
                "kafka",
                "subscribe",
                "task.wfms_createWorkOrder.start",
                "onTaskStart",
                "catalog-read:om")));

    String result =
        tool.captureRequirementDraft(
            new RequirementDraftCapture(
                true,
                "Consume OM WFMS create work order",
                DraftDecision.READY_FOR_PLAN,
                List.of(),
                null,
                List.of(consume)));

    assertTrue(result.contains("Requirement draft captured"), result);
    RequirementDraft draft = store.get("draft-conv").orElseThrow();
    assertEquals(DraftDecision.READY_FOR_PLAN, draft.decision());
    assertEquals(1, draft.catalogBindings().size());
    assertEquals("consume-om", draft.catalogBindings().getFirst().interactionId());
    assertEquals("op-om", draft.catalogBindings().getFirst().integrationOperationId());
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
                        "",
                        "call-trigger"),
                    RequirementFact.of(
                        RequirementFactPolarity.NEGATIVE,
                        RequirementFactKind.CONSTRAINT,
                        "",
                        "Do not call MCP")),
                null,
                new RequirementFlow(
                    List.of(
                        new Interaction(
                            "trigger-1", Direction.INBOUND, "Kafka", "consumeUserEvent", "")),
                    List.of())));

    assertTrue(result.contains("kind=ENDPOINT or kind=SERVICE_CALL"), result);
    assertTrue(store.get("draft-conv").isEmpty());
  }

  @Test
  void storesBusinessFlowBeforeProjectingTechnicalRoles() {
    ConversationApiResolutions resolutions = new ConversationApiResolutions();
    RequirementDraftTool captureTool = RequirementDraftTool.withResolutions(store, resolutions);
    MDC.put(ChatMdc.CONVERSATION_ID, "draft-conv");
    store.beginTurn("draft-conv");
    RequirementFlow flow = rockyFlow();

    assertTrue(
        captureTool
            .captureRequirementDraft(flowCapture(true, DraftDecision.READY_FOR_PLAN, flow))
            .contains("Requirement draft captured"));
    resolutions.remember(
        "draft-conv",
        InteractionAssessment.resolved(
            "task-start",
            new InteractionAssessment.Intent("onTaskStart", "OM", "onTaskStart", "publish", null),
            omStartMatch()));
    resolutions.remember(
        "draft-conv",
        InteractionAssessment.resolved(
            "create-task",
            new InteractionAssessment.Intent("createTask", "Salesforce", "createTask", "POST", "/tasks"),
            salesforceMatch()));
    resolutions.remember(
        "draft-conv",
        InteractionAssessment.resolved(
            "task-result",
            new InteractionAssessment.Intent("onTaskResult", "OM", "onTaskResult", "subscribe", null),
            omResultMatch()));

    assertTrue(
        captureTool
            .captureRequirementDraft(flowCapture(true, DraftDecision.READY_FOR_PLAN, flow))
            .contains("Requirement draft captured"));

    RequirementDraft stored = store.get("draft-conv").orElseThrow();
    assertEquals(flow, stored.flow());
    assertEquals(
        List.of("task-start", "create-task", "task-result"),
        stored.catalogBindings().stream().map(CatalogBindingHint::interactionId).toList());
    assertTrue(stored.readyForPlan());
  }

  @Test
  void rejectsEndpointAndServiceCallFactsWhenFlowIsCaptured() {
    MDC.put(ChatMdc.CONVERSATION_ID, "draft-conv");
    store.beginTurn("draft-conv");

    String result =
        tool.captureRequirementDraft(
            new RequirementDraftCapture(
                true,
                "Receive an order",
                DraftDecision.READY_FOR_PLAN,
                List.of(),
                null,
                List.of(
                    new RequirementFact(
                        "order-received",
                        RequirementFactPolarity.POSITIVE,
                        RequirementFactKind.ENDPOINT,
                        "http-trigger",
                        "Expose POST /orders")),
                null,
                rockyHttpFlow()));

    assertTrue(result.contains("kind=ENDPOINT"), result);
    assertTrue(store.get("draft-conv").isEmpty());
  }

  @Test
  void rejectsServiceCallFactsWhenFlowIsCaptured() {
    MDC.put(ChatMdc.CONVERSATION_ID, "draft-conv");
    store.beginTurn("draft-conv");

    String result =
        tool.captureRequirementDraft(
            new RequirementDraftCapture(
                true,
                "Call Salesforce",
                DraftDecision.READY_FOR_PLAN,
                List.of(),
                null,
                List.of(serviceCallFact("create-task", "Salesforce", "createTask")),
                null,
                rockyHttpFlow()));

    assertTrue(result.contains("kind=SERVICE_CALL"), result);
    assertTrue(store.get("draft-conv").isEmpty());
  }

  @Test
  void emptyFlowIsNotReadyForPlan() {
    MDC.put(ChatMdc.CONVERSATION_ID, "draft-conv");
    store.beginTurn("draft-conv");

    String result =
        tool.captureRequirementDraft(
            new RequirementDraftCapture(
                true,
                "HTTP GET /orders",
                DraftDecision.READY_FOR_PLAN,
                List.of(),
                null,
                sampleFacts()));

    RequirementDraft stored = store.get("draft-conv").orElseThrow();
    assertEquals(DraftDecision.NEEDS_INPUT, stored.decision());
    assertTrue(stored.flow().interactions().isEmpty());
    assertFalse(stored.readyForPlan());
    assertTrue(stored.openQuestions().getFirst().contains("RequirementFlow"), stored.toString());
    assertTrue(result.contains("flow was empty"), result);
  }

  @Test
  void missingRequiredBindingSoftDowngradesReadyCapture() {
    MDC.put(ChatMdc.CONVERSATION_ID, "draft-conv");
    store.beginTurn("draft-conv");
    RequirementFlow flow = rockyFlow();

    String result =
        tool.captureRequirementDraft(flowCapture(true, DraftDecision.READY_FOR_PLAN, flow));

    RequirementDraft stored = store.get("draft-conv").orElseThrow();
    assertEquals(flow, stored.flow());
    assertEquals(DraftDecision.NEEDS_INPUT, stored.decision());
    assertFalse(stored.readyForPlan());
    assertTrue(
        stored.openQuestions().getFirst().contains("has no catalog binding"),
        stored.openQuestions().toString());
    assertTrue(result.contains("interactionId=create-task"), result);
    assertTrue(result.contains("interactionId=task-result"), result);
    assertFalse(result.contains("serviceCallId"), result);
    assertFalse(result.contains("SERVICE_CALL"), result);
  }

  @Test
  void captureBindsUniqueLocalCatalogMatchesWithoutResolveTool() {
    CatalogOperationLookup lookup = mock(CatalogOperationLookup.class);
    when(lookup.resolve(org.mockito.ArgumentMatchers.any(CatalogQuery.class)))
        .thenAnswer(
            invocation -> {
              CatalogQuery query = invocation.getArgument(0);
              String operation = query.operationHint();
              if ("onTaskStart".equals(operation)) {
                return new CatalogLookupResult.Exact(omStartMatch());
              }
              if ("createTask".equals(operation)) {
                return new CatalogLookupResult.Exact(salesforceMatch());
              }
              if ("onTaskResult".equals(operation)) {
                return new CatalogLookupResult.Exact(omResultMatch());
              }
              return new CatalogLookupResult.None();
            });
    ConversationApiResolutions resolutions = new ConversationApiResolutions();
    RequirementDraftTool captureTool =
        RequirementDraftTool.withLookup(store, resolutions, lookup);
    MDC.put(ChatMdc.CONVERSATION_ID, "draft-conv");
    store.beginTurn("draft-conv");

    String result =
        captureTool.captureRequirementDraft(
            flowCapture(true, DraftDecision.READY_FOR_PLAN, rockyFlow()));

    RequirementDraft stored = store.get("draft-conv").orElseThrow();
    assertEquals(DraftDecision.READY_FOR_PLAN, stored.decision());
    assertTrue(stored.readyForPlan());
    assertEquals(3, stored.catalogBindings().size(), stored.catalogBindings().toString());
    assertTrue(
        stored.catalogBindings().stream()
            .anyMatch(hint -> "task-start".equals(hint.interactionId())));
    assertTrue(
        stored.catalogBindings().stream()
            .anyMatch(hint -> "create-task".equals(hint.interactionId())));
    assertTrue(
        stored.catalogBindings().stream()
            .anyMatch(hint -> "task-result".equals(hint.interactionId())));
    assertTrue(result.contains("Requirement draft captured"), result);
    assertFalse(result.contains("has no catalog binding"), result);
  }

  @Test
  void directionConflictSoftDowngradesReadyCapture() {
    ConversationApiResolutions resolutions = new ConversationApiResolutions();
    RequirementDraftTool captureTool = RequirementDraftTool.withResolutions(store, resolutions);
    MDC.put(ChatMdc.CONVERSATION_ID, "draft-conv");
    store.beginTurn("draft-conv");
    RequirementFlow flow = rockyFlow();
    resolutions.remember(
        "draft-conv",
        InteractionAssessment.resolved(
            "task-start",
            new InteractionAssessment.Intent("onTaskStart", "OM", "onTaskStart", "POST", "/start"),
            new CatalogMatch(
                "sys-om",
                "sg-om",
                "spec-om",
                "op-start",
                "OM",
                "http",
                "POST",
                "/start",
                "onTaskStart",
                "catalog-read:om-start")));
    resolutions.remember(
        "draft-conv",
        InteractionAssessment.resolved(
            "create-task",
            new InteractionAssessment.Intent("createTask", "Salesforce", "createTask", "POST", "/tasks"),
            salesforceMatch()));
    resolutions.remember(
        "draft-conv",
        InteractionAssessment.resolved(
            "task-result",
            new InteractionAssessment.Intent("onTaskResult", "OM", "onTaskResult", "subscribe", null),
            omResultMatch()));

    assertTrue(
        captureTool
            .captureRequirementDraft(flowCapture(true, DraftDecision.READY_FOR_PLAN, flow))
            .contains("Requirement draft captured"));

    RequirementDraft stored = store.get("draft-conv").orElseThrow();
    assertEquals(DraftDecision.NEEDS_INPUT, stored.decision());
    assertFalse(stored.readyForPlan());
    assertTrue(
        stored.openQuestions().getFirst().contains("conflicts with catalog direction"),
        stored.openQuestions().toString());
  }

  @Test
  void oldV2HintIsRejectedOnRecapture() {
    MDC.put(ChatMdc.CONVERSATION_ID, "draft-conv");
    store.beginTurn("draft-conv");
    RequirementFlow flow = rockyHttpFlow();
    CatalogBindingHint v2 =
        new CatalogBindingHint(
            "2",
            "create-order",
            "create-order",
            "createOrder",
            "sys-1",
            "sg-1",
            "spec-1",
            "op-1",
            "http",
            "POST",
            "/orders",
            "catalog",
            Instant.EPOCH,
            "test");
    store.put(
        "draft-conv",
        new RequirementDraft(
            false,
            "Receive an order",
            DraftDecision.NEEDS_INPUT,
            List.of("Bind create-order"),
            RequirementDraftTool.SOURCE_SKILL_ID,
            "pack",
            null,
            null,
            false,
            rockyHttpFacts(),
            false,
            null,
            null,
            flow,
            List.of(v2)));

    assertTrue(
        tool.captureRequirementDraft(
                new RequirementDraftCapture(
                    true,
                    "Receive an order",
                    DraftDecision.READY_FOR_PLAN,
                    List.of(),
                    null,
                    rockyHttpFacts(),
                    null,
                    flow))
            .contains("Requirement draft captured"));

    RequirementDraft stored = store.get("draft-conv").orElseThrow();
    assertEquals(DraftDecision.NEEDS_INPUT, stored.decision());
    assertFalse(stored.readyForPlan());
    assertTrue(
        stored.openQuestions().getFirst().contains("schemaVersion=3"),
        stored.openQuestions().toString());
  }

  private static RequirementDraftCapture flowCapture(
      boolean complete, DraftDecision decision, RequirementFlow flow) {
    return new RequirementDraftCapture(
        complete,
        "OM starts a task, Salesforce creates it, OM receives the result",
        decision,
        List.of(),
        null,
        rockyFacts(),
        null,
        flow);
  }

  private static List<RequirementFact> rockyFacts() {
    return List.of(
        RequirementFact.of(
            RequirementFactPolarity.POSITIVE,
            RequirementFactKind.BEHAVIOR,
            "",
            "commandType is completeTask"));
  }

  private static List<RequirementFact> rockyHttpFacts() {
    return List.of(
        RequirementFact.of(
            RequirementFactPolarity.POSITIVE,
            RequirementFactKind.BEHAVIOR,
            "",
            "Receive an order and create it"));
  }

  private static RequirementFlow rockyFlow() {
    return new RequirementFlow(
        List.of(
            new Interaction("task-start", Direction.INBOUND, "OM", "onTaskStart", ""),
            new Interaction("create-task", Direction.OUTBOUND, "Salesforce", "createTask", ""),
            new Interaction("task-result", Direction.OUTBOUND, "OM", "onTaskResult", "")),
        List.of(
            new Transition("task-start", "create-task"),
            new Transition("create-task", "task-result")));
  }

  private static RequirementFlow rockyHttpFlow() {
    return new RequirementFlow(
        List.of(
            new Interaction("order-received", Direction.INBOUND, "Caller", "POST /orders", ""),
            new Interaction("create-order", Direction.OUTBOUND, "Order System", "createOrder", "")),
        List.of(new Transition("order-received", "create-order")));
  }

  private static RequirementFlow nativeHttpFlow() {
    return new RequirementFlow(
        List.of(new Interaction("orders-http", Direction.INBOUND, "Caller", "GET /orders", "")),
        List.of());
  }

  private static CatalogMatch omStartMatch() {
    return new CatalogMatch(
        "sys-om",
        "sg-om",
        "spec-om",
        "op-start",
        "OM",
        "kafka",
        "publish",
        "task.start",
        "onTaskStart",
        "catalog-read:om-start");
  }

  private static CatalogMatch salesforceMatch() {
    return new CatalogMatch(
        "sys-sf",
        "sg-sf",
        "spec-sf",
        "op-create",
        "Salesforce",
        "http",
        "POST",
        "/tasks",
        "createTask",
        "catalog-read:sf-create");
  }

  private static CatalogMatch omResultMatch() {
    return new CatalogMatch(
        "sys-om",
        "sg-om",
        "spec-om",
        "op-result",
        "OM",
        "kafka",
        "subscribe",
        "task.result",
        "onTaskResult",
        "catalog-read:om-result");
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
