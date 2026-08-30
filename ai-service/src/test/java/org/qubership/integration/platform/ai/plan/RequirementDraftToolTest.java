package org.qubership.integration.platform.ai.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jboss.logmanager.MDC;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chat.ChatEvent;
import org.qubership.integration.platform.ai.chat.ToolSession;
import org.qubership.integration.platform.ai.chat.ChatMdc;
import org.qubership.integration.platform.ai.chat.conversation.ConversationService;
import org.qubership.integration.platform.ai.chat.decision.UploadedSpecsApprovalHandler;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.qubership.integration.platform.ai.storage.S3Service;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.integration.apihub.ApiHubRequirementRefs;
import org.qubership.integration.platform.ai.integration.apihub.ConversationApiHubCache;
import org.qubership.integration.platform.ai.integration.catalog.cache.CatalogOperationsReadCache;
import org.qubership.integration.platform.ai.integration.catalog.cache.ConversationCatalogCache;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.productpipeline.artifact.ApprovalRecordV2;
import org.qubership.integration.platform.ai.productpipeline.artifact.ProductPipelineArtifactStore;
import org.qubership.integration.platform.ai.productpipeline.create.CreateRunSelectionService;
import org.qubership.integration.platform.ai.productpipeline.create.design.execution.CatalogBindingMatcher;
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

    assertTrue(result.contains("catalogBinding was missing"));
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

    assertTrue(result.contains("catalogBinding was missing"), result);
    RequirementDraft draft = store.get("draft-conv").orElseThrow();
    assertEquals(DraftDecision.NEEDS_INPUT, draft.decision());
    assertEquals(List.of(RequirementDraftTool.BINDING_REQUIRED_OPEN_QUESTION), draft.openQuestions());
  }

  @Test
  void readyForPlanStaysReadyWhenUploadedSpecImportIsApproved() {
    UploadedSpecsApprovalHandler handler = mock(UploadedSpecsApprovalHandler.class);
    ProductPipelineArtifactStore artifactStore = mock(ProductPipelineArtifactStore.class);
    RequirementDraftTool tool =
        RequirementDraftTool.withUploadApproval(store, handler, artifactStore);
    String conversationId = "draft-conv";
    MDC.put(ChatMdc.CONVERSATION_ID, conversationId);
    store.beginTurn(conversationId);

    String hash = "attachment-hash";
    String runId =
        conversationId
            + "-"
            + CreateRunSelectionService.CREATE_PROFILE_ID
            + "-"
            + CreateRunSelectionService.CREATE_PROFILE_VERSION;
    when(handler.needsApproval(conversationId)).thenReturn(true);
    when(handler.attachmentHash(conversationId)).thenReturn(hash);
    CompilationArtifacts.Revision revision = mock(CompilationArtifacts.Revision.class);
    when(artifactStore.findLatestApprovalRecord(
            runId, UploadedSpecsApprovalHandler.ARTIFACT_TYPE, hash))
        .thenReturn(Optional.of(revision));

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

    assertTrue(result.contains("Requirement draft captured"), result);
    RequirementDraft draft = store.get(conversationId).orElseThrow();
    assertEquals(DraftDecision.READY_FOR_PLAN, draft.decision());
    assertTrue(draft.openQuestions().isEmpty());
  }

  @Test
  void missingBindingDowngradesWhenUploadedSpecImportIsNotApproved() {
    UploadedSpecsApprovalHandler handler = mock(UploadedSpecsApprovalHandler.class);
    ProductPipelineArtifactStore artifactStore = mock(ProductPipelineArtifactStore.class);
    RequirementDraftTool tool =
        RequirementDraftTool.withUploadApproval(store, handler, artifactStore);
    String conversationId = "draft-conv";
    MDC.put(ChatMdc.CONVERSATION_ID, conversationId);
    store.beginTurn(conversationId);

    String hash = "attachment-hash";
    String runId =
        conversationId
            + "-"
            + CreateRunSelectionService.CREATE_PROFILE_ID
            + "-"
            + CreateRunSelectionService.CREATE_PROFILE_VERSION;
    when(handler.needsApproval(conversationId)).thenReturn(true);
    when(handler.attachmentHash(conversationId)).thenReturn(hash);
    when(artifactStore.findLatestApprovalRecord(
            runId, UploadedSpecsApprovalHandler.ARTIFACT_TYPE, hash))
        .thenReturn(Optional.empty());

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

    assertTrue(result.contains("catalogBinding was missing"), result);
    RequirementDraft draft = store.get(conversationId).orElseThrow();
    assertEquals(DraftDecision.NEEDS_INPUT, draft.decision());
  }

  @Test
  void captureNamesTheServiceCallThatIsStillUnresolved() {
    ConversationApiResolutions resolutions = new ConversationApiResolutions();
    RequirementDraftTool tool = RequirementDraftTool.withResolutions(store, resolutions);
    MDC.put(ChatMdc.CONVERSATION_ID, "draft-conv");
    store.beginTurn("draft-conv");
    RequirementFact inventory =
        RequirementFact.of(
            RequirementFactPolarity.POSITIVE,
            RequirementFactKind.SERVICE_CALL,
            "Petstore Ext",
            "GET /store/inventory");
    RequirementFact invoice =
        RequirementFact.of(
            RequirementFactPolarity.POSITIVE,
            RequirementFactKind.SERVICE_CALL,
            "Billing",
            "POST /invoices");
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
    assertTrue(question.contains("POST /invoices"), question);
    assertFalse(question.contains("/store/inventory"), question);
  }

  @Test
  void captureIsReadyWhenEveryServiceCallHasItsOwnResolution() {
    ConversationApiResolutions resolutions = new ConversationApiResolutions();
    RequirementDraftTool tool = RequirementDraftTool.withResolutions(store, resolutions);
    MDC.put(ChatMdc.CONVERSATION_ID, "draft-conv");
    store.beginTurn("draft-conv");
    RequirementFact inventory =
        RequirementFact.of(
            RequirementFactPolarity.POSITIVE,
            RequirementFactKind.SERVICE_CALL,
            "Petstore Ext",
            "GET /store/inventory");
    RequirementFact invoice =
        RequirementFact.of(
            RequirementFactPolarity.POSITIVE,
            RequirementFactKind.SERVICE_CALL,
            "Billing",
            "POST /invoices");
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

  private static ServiceCallAssessment assessment(RequirementFact call) {
    return ServiceCallAssessment.resolved(
        call.sourceFactId(),
        new ServiceCallAssessment.Intent(call.text(), call.capabilityKey(), null, null, null),
        new CatalogBindingMatcher.CatalogMatch(
            "sys-" + call.sourceFactId().substring(0, 4),
            "group-1",
            "spec-1",
            "op-" + call.sourceFactId().substring(0, 4),
            call.capabilityKey(),
            "http",
            "GET",
            "/probe",
            "probe",
            "catalog-read:probe"));
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
                sampleFacts()));

    assertTrue(result.contains("Requirement draft captured"));
    RequirementDraft draft = store.get("draft-conv").orElseThrow();
    assertEquals("sys-1", draft.catalogBinding().systemId());
    assertEquals("op-1", draft.catalogBinding().integrationOperationId());
    assertEquals("EXTERNAL", draft.catalogBinding().systemType());
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
  void uploadedSpecApprovalAllowsReadyForPlanWhenCurrentKeysAreEmpty() {
    MDC.put(ChatMdc.CONVERSATION_ID, "draft-conv");
    store.beginTurn("draft-conv");

    ConversationService conversationService = mock(ConversationService.class);
    S3Service s3Service = mock(S3Service.class);
    when(conversationService.getAllowedAttachmentKeys("draft-conv"))
        .thenReturn(List.of("uploads/stub.yaml"));
    when(s3Service.readObjectBytes("uploads/stub.yaml"))
        .thenReturn("{\"info\":{\"title\":\"Stub API\"}}".getBytes());

    UploadedSpecsApprovalHandler handler =
        new UploadedSpecsApprovalHandler(conversationService, s3Service);
    ChatEvent.Decision decision = handler.createDecision("draft-conv");

    ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    ProductPipelineArtifactStore artifactStore =
        new ProductPipelineArtifactStore(
            new CompilationArtifacts(
                new InMemoryArtifactBlobStore(), mapper, Clock.systemUTC()));
    String runId =
        "draft-conv-"
            + CreateRunSelectionService.CREATE_PROFILE_ID
            + "-"
            + CreateRunSelectionService.CREATE_PROFILE_VERSION;
    handler.appendApprovalRecord(runId, "draft-conv", decision, artifactStore);

    // Simulate a later turn where attachment keys are no longer present in conversation state.
    when(conversationService.getAllowedAttachmentKeys("draft-conv")).thenReturn(List.of());

    RequirementDraftTool tool = RequirementDraftTool.withUploadApproval(store, handler, artifactStore);
    String result =
        tool.captureRequirementDraft(
            new RequirementDraftCapture(
                true,
                "Call Stub stubOperation",
                DraftDecision.READY_FOR_PLAN,
                List.of(),
                null,
                null,
                List.of(
                    RequirementFact.of(
                        RequirementFactPolarity.POSITIVE,
                        RequirementFactKind.SERVICE_CALL,
                        "stub",
                        "Uploaded OPENAPI spec Stub API operation stubOperation path POST /stub/path"))));

    assertTrue(result.contains("Requirement draft captured"));
    RequirementDraft draft = store.get("draft-conv").orElseThrow();
    assertEquals(DraftDecision.READY_FOR_PLAN, draft.decision());
    assertTrue(draft.complete());
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
