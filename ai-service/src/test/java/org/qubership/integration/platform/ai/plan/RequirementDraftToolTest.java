package org.qubership.integration.platform.ai.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jboss.logmanager.MDC;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.qubership.integration.platform.ai.chat.ToolSession;
import org.qubership.integration.platform.ai.chat.ChatMdc;
import org.qubership.integration.platform.ai.chat.conversation.ConversationMessage;
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
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogElementResponseDto;
import org.qubership.integration.platform.ai.catalog.binding.CompositionCatalogBinder;
import org.qubership.integration.platform.ai.catalog.binding.McpSystemCatalogBinder;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogMcpSystemDto;
import org.qubership.integration.platform.ai.productpipeline.create.RequirementFactFixtures;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.CatalogBindingHint;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Direction;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Interaction;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Transition;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementServiceCall;
import org.qubership.integration.platform.ai.qipknowledge.pack.QipKnowledgePackManifest;
import org.qubership.integration.platform.ai.qipknowledge.pack.QipKnowledgePackRepository;
import org.qubership.integration.platform.ai.qipknowledge.pack.QipKnowledgePackVersion;
import org.qubership.integration.platform.ai.schema.ChainElementFamilies;

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
                sampleFactsWithTrigger("orders-http"),
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
  void captureSoftDowngradesHttpTriggerWithoutMethod() {
    MDC.put(ChatMdc.CONVERSATION_ID, "draft-conv");
    store.beginTurn("draft-conv");
    RequirementFact trigger =
        new RequirementFact(
            "orders-http",
            RequirementFactPolarity.POSITIVE,
            RequirementFactKind.CAPABILITY,
            "http-trigger",
            "Expose /orders",
            "",
            "",
            "",
            "",
            "/orders");

    String result =
        tool.captureRequirementDraft(
            new RequirementDraftCapture(
                true,
                "Expose /orders over HTTP",
                DraftDecision.READY_FOR_PLAN,
                List.of(),
                null,
                List.of(trigger),
                null,
                nativeHttpFlow()));

    RequirementDraft draft = store.get("draft-conv").orElseThrow();
    assertTrue(result.contains("NEEDS_INPUT"), result);
    assertTrue(result.contains("has no HTTP method"), result);
    assertFalse(result.contains("resolveApiOperation"), result);
    assertEquals(DraftDecision.NEEDS_INPUT, draft.decision());
    assertFalse(draft.complete());
    assertTrue(
        draft.openQuestions().getFirst().contains("HTTP method"),
        draft.openQuestions().toString());
  }

  @Test
  void captureWritesUniqueChainCallTriggerIdAndStaysReadyForPlan() {
    CatalogRestClient catalog = mock(CatalogRestClient.class);
    when(catalog.getElementsByType("any-chain", "chain-trigger-2"))
        .thenReturn(
            List.of(
                catalogTrigger("trig-other", "Other chain"),
                catalogTrigger("trig-header", "Chain trigger + Header modification")));
    RequirementDraftTool captureTool =
        RequirementDraftTool.withCompositionBinder(store, new CompositionCatalogBinder(catalog));
    MDC.put(ChatMdc.CONVERSATION_ID, "draft-conv");
    store.beginTurn("draft-conv");

    String result =
        captureTool.captureRequirementDraft(
            new RequirementDraftCapture(
                true,
                "GET /auto-tests/chain-call then call Chain trigger + Header modification",
                DraftDecision.READY_FOR_PLAN,
                List.of(),
                null,
                List.of(
                    new RequirementFact(
                        "http-entry",
                        RequirementFactPolarity.POSITIVE,
                        RequirementFactKind.CAPABILITY,
                        "http-trigger",
                        "Expose GET /auto-tests/chain-call",
                        "",
                        "",
                        "",
                        "GET",
                        "/auto-tests/chain-call"),
                    new RequirementFact(
                        "call-other",
                        RequirementFactPolarity.POSITIVE,
                        RequirementFactKind.CAPABILITY,
                        "chain-call-2",
                        "Call the header modification chain",
                        "Chain trigger + Header modification",
                        "",
                        "",
                        "",
                        "")),
                null,
                chainCallFlow()));

    RequirementDraft draft = store.get("draft-conv").orElseThrow();
    assertTrue(result.contains("Requirement draft captured"), result);
    assertTrue(draft.readyForPlan());
    assertEquals(
        "trig-header",
        draft.facts().stream()
            .filter(fact -> "chain-call-2".equals(fact.capabilityKey()))
            .map(RequirementFact::path)
            .findFirst()
            .orElse(""));
  }

  @Test
  void captureAsksChainCallPickerWhenCatalogChainNameAppearsWithoutCapability() {
    CatalogRestClient catalog = mock(CatalogRestClient.class);
    when(catalog.getElementsByType("any-chain", "chain-trigger-2"))
        .thenReturn(List.of(catalogTrigger("trig-header", "Chain trigger + Header modification")));
    RequirementDraftTool captureTool =
        RequirementDraftTool.withCompositionBinder(store, new CompositionCatalogBinder(catalog));
    MDC.put(ChatMdc.CONVERSATION_ID, "draft-conv");
    store.beginTurn("draft-conv");

    String result =
        captureTool.captureRequirementDraft(
            new RequirementDraftCapture(
                true,
                "GET /auto-tests/chain-call then invoke Chain trigger + Header modification",
                DraftDecision.READY_FOR_PLAN,
                List.of(),
                null,
                List.of(
                    new RequirementFact(
                        "http-entry",
                        RequirementFactPolarity.POSITIVE,
                        RequirementFactKind.CAPABILITY,
                        "http-trigger",
                        "Expose GET /auto-tests/chain-call",
                        "",
                        "",
                        "",
                        "GET",
                        "/auto-tests/chain-call")),
                null,
                chainCallFlow()));

    RequirementDraft draft = store.get("draft-conv").orElseThrow();
    assertTrue(result.contains("NEEDS_INPUT"), result);
    assertEquals(DraftDecision.NEEDS_INPUT, draft.decision());
    assertTrue(
        draft
            .openQuestions()
            .getFirst()
            .contains("Choose the catalog chain to call"),
        draft.openQuestions().toString());
    assertFalse(
        draft.openQuestions().getFirst().contains("trig-header"),
        draft.openQuestions().toString());
    assertTrue(result.contains("trig-header"), result);
  }

  @Test
  void captureBindsUniqueChainCallWhenAgentAsksForTriggerUuid() {
    CatalogRestClient catalog = mock(CatalogRestClient.class);
    when(catalog.getElementsByType("any-chain", "chain-trigger-2"))
        .thenReturn(
            List.of(
                catalogTrigger("trig-other", "Other chain"),
                catalogTrigger("trig-header", "Chain trigger + Header modification")));
    RequirementDraftTool captureTool =
        RequirementDraftTool.withCompositionBinder(store, new CompositionCatalogBinder(catalog));
    MDC.put(ChatMdc.CONVERSATION_ID, "draft-conv");
    store.beginTurn("draft-conv");

    captureTool.captureRequirementDraft(
        new RequirementDraftCapture(
            false,
            "GET /auto-tests/chain-call then call Chain trigger + Header modification",
            DraftDecision.NEEDS_INPUT,
            List.of(
                "Please provide the catalog UUID of the chain-trigger for the"
                    + " Chain trigger + Header modification chain."),
            null,
            List.of(
                new RequirementFact(
                    "http-entry",
                    RequirementFactPolarity.POSITIVE,
                    RequirementFactKind.CAPABILITY,
                    "http-trigger",
                    "Expose GET /auto-tests/chain-call",
                    "",
                    "",
                    "",
                    "GET",
                    "/auto-tests/chain-call"),
                new RequirementFact(
                    "call-other",
                    RequirementFactPolarity.POSITIVE,
                    RequirementFactKind.CAPABILITY,
                    "chain-call-2",
                    "Call the header modification chain",
                    "Chain trigger + Header modification",
                    "",
                    "",
                    "",
                    "")),
            null,
            chainCallFlow()));

    RequirementDraft draft = store.get("draft-conv").orElseThrow();
    assertTrue(draft.readyForPlan(), draft.openQuestions().toString());
    assertTrue(draft.openQuestions().isEmpty(), draft.openQuestions().toString());
    assertEquals(
        "trig-header",
        draft.facts().stream()
            .filter(fact -> "chain-call-2".equals(fact.capabilityKey()))
            .map(RequirementFact::path)
            .findFirst()
            .orElse(""));
  }

  @Test
  void captureReplacesAgentUuidPromptWithCatalogChainTriggerPicker() {
    CatalogRestClient catalog = mock(CatalogRestClient.class);
    when(catalog.getElementsByType("any-chain", "chain-trigger-2"))
        .thenReturn(
            List.of(
                catalogTrigger("trig-other", "Other chain"),
                catalogTrigger("trig-header", "Chain trigger + Header modification")));
    RequirementDraftTool captureTool =
        RequirementDraftTool.withCompositionBinder(store, new CompositionCatalogBinder(catalog));
    MDC.put(ChatMdc.CONVERSATION_ID, "draft-conv");
    store.beginTurn("draft-conv");

    captureTool.captureRequirementDraft(
        new RequirementDraftCapture(
            false,
            "GET /auto-tests/chain-call then call another chain",
            DraftDecision.NEEDS_INPUT,
            List.of("Please provide the catalog UUID of the chain-trigger."),
            null,
            List.of(
                new RequirementFact(
                    "http-entry",
                    RequirementFactPolarity.POSITIVE,
                    RequirementFactKind.CAPABILITY,
                    "http-trigger",
                    "Expose GET /auto-tests/chain-call",
                    "",
                    "",
                    "",
                    "GET",
                    "/auto-tests/chain-call"),
                new RequirementFact(
                    "call-other",
                    RequirementFactPolarity.POSITIVE,
                    RequirementFactKind.CAPABILITY,
                    "chain-call-2",
                    "Call another chain",
                    "",
                    "",
                    "",
                    "",
                    "")),
            null,
            chainCallFlow()));

    RequirementDraft draft = store.get("draft-conv").orElseThrow();
    assertEquals(DraftDecision.NEEDS_INPUT, draft.decision());
    String question = draft.openQuestions().getFirst();
    assertTrue(
        question.contains("Choose the catalog chain to call"), question);
    assertFalse(question.contains("trig-header"), question);
    assertFalse(question.contains("Please provide the catalog UUID"), question);
  }

  @Test
  void captureTellsModelToMatchListedChainTriggerByMeaningWhenExactNameMisses() {
    CatalogRestClient catalog = mock(CatalogRestClient.class);
    when(catalog.getElementsByType("any-chain", "chain-trigger-2"))
        .thenReturn(
            List.of(catalogTrigger("trig-header", "chain-trigger-header-modification")));
    RequirementDraftTool captureTool =
        RequirementDraftTool.withCompositionBinder(store, new CompositionCatalogBinder(catalog));
    MDC.put(ChatMdc.CONVERSATION_ID, "draft-conv");
    store.beginTurn("draft-conv");

    String result =
        captureTool.captureRequirementDraft(
            new RequirementDraftCapture(
                true,
                "GET /auto-tests/chain-call then call Chain trigger + Header modification",
                DraftDecision.READY_FOR_PLAN,
                List.of(),
                null,
                List.of(
                    new RequirementFact(
                        "http-entry",
                        RequirementFactPolarity.POSITIVE,
                        RequirementFactKind.CAPABILITY,
                        "http-trigger",
                        "Expose GET /auto-tests/chain-call",
                        "",
                        "",
                        "",
                        "GET",
                        "/auto-tests/chain-call"),
                    new RequirementFact(
                        "call-other",
                        RequirementFactPolarity.POSITIVE,
                        RequirementFactKind.CAPABILITY,
                        "chain-call-2",
                        "Call the header modification chain",
                        "Chain trigger + Header modification",
                        "",
                        "",
                        "",
                        "")),
                null,
                chainCallFlow()));

    RequirementDraft draft = store.get("draft-conv").orElseThrow();
    assertEquals(DraftDecision.NEEDS_INPUT, draft.decision());
    assertTrue(result.contains("by meaning"), result);
    assertTrue(
        result.contains("Do not pick a trigger only because it is the only one listed"), result);
    assertTrue(result.contains("trig-header"), result);
    assertTrue(
        draft
            .openQuestions()
            .getFirst()
            .contains("Choose the catalog chain to call"),
        draft.openQuestions().toString());
    assertFalse(
        draft.openQuestions().getFirst().contains("trig-header"),
        draft.openQuestions().toString());
    assertEquals(
        "",
        draft.facts().stream()
            .filter(fact -> "chain-call-2".equals(fact.capabilityKey()))
            .map(RequirementFact::path)
            .findFirst()
            .orElse("missing"));
  }

  @Test
  void captureWritesUniqueMcpSystemIdAndStaysReadyForPlan() {
    CatalogRestClient catalog = mock(CatalogRestClient.class);
    when(catalog.listMcpSystems())
        .thenReturn(List.of(mcpSystem("sys-1", "Orders MCP", "orders-mcp")));
    RequirementDraftTool captureTool =
        RequirementDraftTool.withMcpSystemBinder(store, new McpSystemCatalogBinder(catalog));
    MDC.put(ChatMdc.CONVERSATION_ID, "draft-conv");
    store.beginTurn("draft-conv");

    String result =
        captureTool.captureRequirementDraft(
            new RequirementDraftCapture(
                true,
                "Expose the chain as an MCP tool on Orders MCP",
                DraftDecision.READY_FOR_PLAN,
                List.of(),
                null,
                List.of(
                    new RequirementFact(
                        "mcp-entry",
                        RequirementFactPolarity.POSITIVE,
                        RequirementFactKind.CAPABILITY,
                        "mcp-trigger",
                        "Expose the chain as an MCP tool",
                        "Orders MCP",
                        "",
                        "",
                        "",
                        "")),
                null,
                mcpTriggerFlow()));

    RequirementDraft draft = store.get("draft-conv").orElseThrow();
    assertTrue(result.contains("Requirement draft captured"), result);
    assertEquals(
        "sys-1",
        draft.facts().stream()
            .filter(fact -> "mcp-trigger".equals(fact.capabilityKey()))
            .map(RequirementFact::path)
            .findFirst()
            .orElse(""));
  }

  @Test
  void captureAsksMcpPickerWhenSeveralSystemsMatch() {
    CatalogRestClient catalog = mock(CatalogRestClient.class);
    when(catalog.listMcpSystems())
        .thenReturn(
            List.of(
                mcpSystem("a", "Orders", "orders"),
                mcpSystem("b", "Orders", "orders-2")));
    RequirementDraftTool captureTool =
        RequirementDraftTool.withMcpSystemBinder(store, new McpSystemCatalogBinder(catalog));
    MDC.put(ChatMdc.CONVERSATION_ID, "draft-conv");
    store.beginTurn("draft-conv");

    String result =
        captureTool.captureRequirementDraft(
            new RequirementDraftCapture(
                true,
                "Expose the chain as an MCP tool on Orders",
                DraftDecision.READY_FOR_PLAN,
                List.of(),
                null,
                List.of(
                    new RequirementFact(
                        "mcp-entry",
                        RequirementFactPolarity.POSITIVE,
                        RequirementFactKind.CAPABILITY,
                        "mcp-trigger",
                        "Expose the chain as an MCP tool",
                        "Orders",
                        "",
                        "",
                        "",
                        "")),
                null,
                mcpTriggerFlow()));

    RequirementDraft draft = store.get("draft-conv").orElseThrow();
    assertTrue(result.contains("NEEDS_INPUT"), result);
    assertEquals(DraftDecision.NEEDS_INPUT, draft.decision());
    assertTrue(
        draft.openQuestions().getFirst().contains("new MCP service"),
        draft.openQuestions().toString());
    assertEquals(
        "",
        draft.facts().stream()
            .filter(fact -> "mcp-trigger".equals(fact.capabilityKey()))
            .map(RequirementFact::path)
            .findFirst()
            .orElse("missing"));
  }

  @Test
  void capturePrefersMcpPickerOverChainCallWhenInboundIsMcpTrigger() {
    CatalogRestClient catalog = mock(CatalogRestClient.class);
    when(catalog.getElementsByType("any-chain", "chain-trigger-2"))
        .thenReturn(
            List.of(
                catalogTrigger("trig-a", "Orders"),
                catalogTrigger("trig-b", "Orders")));
    when(catalog.listMcpSystems())
        .thenReturn(
            List.of(
                mcpSystem("sys-a", "Orders", "orders"),
                mcpSystem("sys-b", "Orders", "orders-2")));
    RequirementDraftTool captureTool =
        new RequirementDraftTool(
            store,
            null,
            null,
            null,
            null,
            null,
            null,
            new CompositionCatalogBinder(catalog),
            new McpSystemCatalogBinder(catalog));
    MDC.put(ChatMdc.CONVERSATION_ID, "draft-conv");
    store.beginTurn("draft-conv");

    String result =
        captureTool.captureRequirementDraft(
            new RequirementDraftCapture(
                true,
                "Expose Orders as an MCP tool, then call the Orders chain",
                DraftDecision.READY_FOR_PLAN,
                List.of(),
                null,
                List.of(
                    new RequirementFact(
                        "mcp-entry",
                        RequirementFactPolarity.POSITIVE,
                        RequirementFactKind.CAPABILITY,
                        "mcp-trigger",
                        "Expose the chain as an MCP tool",
                        "Orders",
                        "",
                        "",
                        "",
                        ""),
                    new RequirementFact(
                        "call-other",
                        RequirementFactPolarity.POSITIVE,
                        RequirementFactKind.CAPABILITY,
                        "chain-call-2",
                        "Call the Orders chain",
                        "Orders",
                        "",
                        "",
                        "",
                        "")),
                null,
                mcpTriggerWithChainCallFlow()));

    RequirementDraft draft = store.get("draft-conv").orElseThrow();
    assertTrue(result.contains("NEEDS_INPUT"), result);
    assertEquals(DraftDecision.NEEDS_INPUT, draft.decision());
    assertEquals(1, draft.openQuestions().size(), draft.openQuestions().toString());
    String question = draft.openQuestions().getFirst();
    assertTrue(question.contains("new MCP service"), question);
    assertFalse(question.contains("Choose the catalog chain to call"), question);
    assertFalse(question.toLowerCase().contains("uuid"), question);
    assertEquals(
        "",
        draft.facts().stream()
            .filter(fact -> "mcp-trigger".equals(fact.capabilityKey()))
            .map(RequirementFact::path)
            .findFirst()
            .orElse("missing"));
    assertEquals(
        "",
        draft.facts().stream()
            .filter(fact -> "chain-call-2".equals(fact.capabilityKey()))
            .map(RequirementFact::path)
            .findFirst()
            .orElse("missing"));
  }

  @Test
  void finishDiscoveryTurnStoresStayDirectiveWithoutCapturingADraft() {
    store.beginTurn("draft-conv");

    String result;
    try (ToolSession.Handle ignored = ToolSession.open("draft-conv")) {
      result = tool.finishRequirementDiscoveryTurn(RequirementDiscoveryDirective.STAY);
    }

    assertTrue(result.contains("stay in requirement discovery"), result);
    assertEquals(RequirementDiscoveryDirective.STAY, store.turnDirective("draft-conv"));
    assertFalse(store.wasCapturedThisTurn("draft-conv"));
    assertTrue(store.get("draft-conv").isEmpty());
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
    assertTrue(
        description.contains("Do not model an HTTP response as a separate OUTBOUND interaction"),
        description);
    assertTrue(
        description.contains("Catalog verbs such as publish or subscribe do not choose the role"),
        description);
    assertTrue(description.contains("absolute or"), description);
    assertTrue(description.contains("relative URI"), description);
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
    assertTrue(description.contains("process checkpoint"), description);
    assertTrue(description.contains("do not invent a user question"), description);
    assertTrue(description.contains("by meaning"), description);
    assertTrue(description.contains("A chain picker is shown to the user"), description);
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
  void needsInputWithoutOpenQuestionsIsAllowedForCatalogBindCheckpoint() {
    MDC.put(ChatMdc.CONVERSATION_ID, "draft-conv");
    store.beginTurn("draft-conv");

    String result =
        tool.captureRequirementDraft(
            flowCapture(false, DraftDecision.NEEDS_INPUT, rockyFlow()));

    assertFalse(result.contains("openQuestions is required"), result);
    assertTrue(result.contains("Unresolved interactions"), result);
    assertTrue(result.contains("resolveApiOperation"), result);
    assertFalse(result.contains("CATALOG_BOUND"), result);
    RequirementDraft draft = store.get("draft-conv").orElseThrow();
    assertEquals(DraftDecision.NEEDS_INPUT, draft.decision());
    assertTrue(draft.openQuestions().isEmpty());
    assertFalse(draft.readyForPlan());
    assertEquals(rockyFlow(), draft.flow());
  }

  @Test
  void needsInputWithoutOpenQuestionsIsRejectedWhenFactsAreEmpty() {
    MDC.put(ChatMdc.CONVERSATION_ID, "draft-conv");
    store.beginTurn("draft-conv");

    String result =
        tool.captureRequirementDraft(
            new RequirementDraftCapture(
                false,
                "OM starts a task, Salesforce creates it, OM receives the result",
                DraftDecision.NEEDS_INPUT,
                List.of(),
                null,
                List.of(),
                null,
                rockyFlow()));

    assertTrue(result.contains("openQuestions is required"));
    assertTrue(store.get("draft-conv").isEmpty());
  }

  @Test
  void needsInputWithoutOpenQuestionsIsRejectedWhenAssessmentIsIncomplete() {
    ConversationApiResolutions resolutions = new ConversationApiResolutions();
    RequirementDraftTool captureTool = RequirementDraftTool.withResolutions(store, resolutions);
    MDC.put(ChatMdc.CONVERSATION_ID, "draft-conv");
    store.beginTurn("draft-conv");
    resolutions.remember(
        "draft-conv",
        InteractionAssessment.incomplete(
            "create-task",
            new InteractionAssessment.Intent(
                "createTask", "Salesforce", "createTask", "POST", "/tasks")));

    String result =
        captureTool.captureRequirementDraft(
            flowCapture(false, DraftDecision.NEEDS_INPUT, rockyFlow()));

    assertTrue(result.contains("openQuestions is required"));
    assertTrue(store.get("draft-conv").isEmpty());
  }

  @Test
  void needsInputWithoutOpenQuestionsIsRejectedWhenAssessmentIsAmbiguous() {
    ConversationApiResolutions resolutions = new ConversationApiResolutions();
    RequirementDraftTool captureTool = RequirementDraftTool.withResolutions(store, resolutions);
    MDC.put(ChatMdc.CONVERSATION_ID, "draft-conv");
    store.beginTurn("draft-conv");
    resolutions.remember(
        "draft-conv",
        InteractionAssessment.ambiguous(
            "create-task",
            new InteractionAssessment.Intent(
                "createTask", "Salesforce", "createTask", "POST", "/tasks"),
            List.of("op-a", "op-b")));

    String result =
        captureTool.captureRequirementDraft(
            flowCapture(false, DraftDecision.NEEDS_INPUT, rockyFlow()));

    assertTrue(result.contains("openQuestions is required"));
    assertTrue(store.get("draft-conv").isEmpty());
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
  void readyForPlanIgnoresCachedApiHubCandidateWhenCatalogAlreadyBindsItsInteraction() {
    ConversationApiHubCache apiHubCache = new ConversationApiHubCache();
    apiHubCache.rememberCandidate(
        "draft-conv",
        new ApiHubRequirementRefs(
            "salesforce-wfm",
            "1.0.0",
            "createTask",
            "salesforce-wfm",
            "rest",
            "Salesforce WFM",
            null));
    CatalogOperationLookup lookup = mock(CatalogOperationLookup.class);
    when(lookup.resolve(org.mockito.ArgumentMatchers.any(CatalogQuery.class)))
        .thenAnswer(
            invocation -> {
              String operation = invocation.<CatalogQuery>getArgument(0).operationHint();
              return switch (operation) {
                case "onTaskStart" -> new CatalogLookupResult.Exact(omStartMatch());
                case "createTask" -> new CatalogLookupResult.Exact(salesforceMatch());
                case "onTaskResult" -> new CatalogLookupResult.Exact(omResultMatch());
                default -> new CatalogLookupResult.None();
              };
            });
    RequirementDraftTool captureTool =
        new RequirementDraftTool(
            store,
            null,
            null,
            apiHubCache,
            new ConversationApiResolutions(),
            null,
            lookup);
    MDC.put(ChatMdc.CONVERSATION_ID, "draft-conv");
    store.beginTurn("draft-conv");

    String result =
        captureTool.captureRequirementDraft(
            flowCapture(true, DraftDecision.READY_FOR_PLAN, rockyFlow()));

    RequirementDraft draft = store.get("draft-conv").orElseThrow();
    assertEquals(DraftDecision.NEEDS_INPUT, draft.decision());
    assertFalse(draft.readyForPlan());
    assertFalse(draft.importIntent());
    assertFalse(result.contains("pending"), result);
    assertTrue(
        draft.catalogBindings().stream().anyMatch(hint -> "task-start".equals(hint.interactionId())));
  }

  @Test
  void readyForPlanIgnoresDocumentCandidateWhenCatalogAlreadyBindsTheFlow() {
    ConversationApiHubCache apiHubCache = new ConversationApiHubCache();
    apiHubCache.rememberCandidate(
        "draft-conv",
        new ApiHubRequirementRefs(
            "uploaded-approved-specifications",
            "1.0.0",
            null,
            "uploaded-specifications",
            "rest",
            "Uploaded specifications",
            null));
    CatalogOperationLookup lookup = mock(CatalogOperationLookup.class);
    when(lookup.resolve(org.mockito.ArgumentMatchers.any(CatalogQuery.class)))
        .thenAnswer(
            invocation -> {
              String operation = invocation.<CatalogQuery>getArgument(0).operationHint();
              return switch (operation) {
                case "onTaskStart" -> new CatalogLookupResult.Exact(omStartMatch());
                case "createTask" -> new CatalogLookupResult.Exact(salesforceMatch());
                case "onTaskResult" -> new CatalogLookupResult.Exact(omResultMatch());
                default -> new CatalogLookupResult.None();
              };
            });
    RequirementDraftTool captureTool =
        new RequirementDraftTool(
            store,
            null,
            null,
            apiHubCache,
            new ConversationApiResolutions(),
            null,
            lookup);
    MDC.put(ChatMdc.CONVERSATION_ID, "draft-conv");
    store.beginTurn("draft-conv");

    String result =
        captureTool.captureRequirementDraft(
            flowCapture(true, DraftDecision.READY_FOR_PLAN, rockyFlow()));

    RequirementDraft draft = store.get("draft-conv").orElseThrow();
    assertEquals(DraftDecision.NEEDS_INPUT, draft.decision());
    assertFalse(draft.readyForPlan());
    assertFalse(draft.importIntent());
    assertFalse(result.contains("pending"), result);
    assertTrue(
        draft.catalogBindings().stream().anyMatch(hint -> "task-start".equals(hint.interactionId())));
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
            sampleFactsWithTrigger("orders"),
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
                sampleFactsWithTrigger("orders"),
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
                rockyHttpFacts(),
                null,
                rockyHttpFlow()));

    assertFalse(result.contains("catalogBinding"), result);
    assertFalse(result.contains("catalog-backed interactions are unresolved"), result);
    RequirementDraft draft = store.get("draft-conv").orElseThrow();
    assertEquals(DraftDecision.NEEDS_INPUT, draft.decision());
    assertTrue(
        draft.openQuestions().getFirst().contains("create-order"),
        draft.openQuestions().toString());
    assertTrue(
        draft.openQuestions().getFirst().contains("sender type"),
        draft.openQuestions().toString());
    assertFalse(
        draft.openQuestions().getFirst().contains("order-received has no catalog binding"),
        draft.openQuestions().toString());
  }

  @Test
  void captureDirectHttpFlowDoesNotSearchTheCatalog() {
    CatalogOperationLookup lookup = mock(CatalogOperationLookup.class);
    RequirementDraftTool captureTool =
        RequirementDraftTool.withLookup(store, new ConversationApiResolutions(), lookup);
    MDC.put(ChatMdc.CONVERSATION_ID, "draft-conv");
    store.beginTurn("draft-conv");
    RequirementFlow flow =
        new RequirementFlow(
            List.of(
                new Interaction("http-entry", Direction.INBOUND, "Caller", "GET /greeting", ""),
                new Interaction(
                    "send-greeting",
                    Direction.OUTBOUND,
                    "Greeting service",
                    "GET /hello",
                    "")),
            List.of(new Transition("http-entry", "send-greeting")));
    List<RequirementFact> facts =
        List.of(
            new RequirementFact(
                "http-entry",
                RequirementFactPolarity.POSITIVE,
                RequirementFactKind.CAPABILITY,
                "http-trigger",
                "Expose GET /greeting",
                "",
                "",
                "",
                "GET",
                "/greeting"),
            new RequirementFact(
                "send-greeting",
                RequirementFactPolarity.POSITIVE,
                RequirementFactKind.CAPABILITY,
                "http-sender",
                "Send GET to https://greetings.com/hello",
                "",
                "",
                "",
                "GET",
                "https://greetings.com/hello",
                ""));

    String result =
        captureTool.captureRequirementDraft(
            new RequirementDraftCapture(
                true,
                "Receive GET /greeting and send GET to https://greetings.com/hello",
                DraftDecision.READY_FOR_PLAN,
                List.of(),
                null,
                facts,
                null,
                flow));

    assertTrue(result.contains("Requirement draft captured"), result);
    assertTrue(store.get("draft-conv").orElseThrow().readyForPlan());
    verifyNoInteractions(lookup);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "chain-trigger-2",
        "jms-trigger",
        "kafka-trigger-2",
        "pubsub-trigger",
        "quartz-scheduler",
        "rabbitmq-trigger-2",
        "sds-trigger",
        "sftp-trigger-2",
        "chain-call-2",
        "graphql-sender",
        "http-sender",
        "jms-sender",
        "kafka-sender-2",
        "mail-sender",
        "pubsub-sender",
        "rabbitmq-sender-2",
        "scs-sender"
      })
  void directCapabilityCaptureDoesNotSearchCatalog(String capabilityKey) {
    CatalogOperationLookup lookup = mock(CatalogOperationLookup.class);
    RequirementDraftTool captureTool =
        RequirementDraftTool.withLookup(store, new ConversationApiResolutions(), lookup);
    MDC.put(ChatMdc.CONVERSATION_ID, "draft-conv");
    store.beginTurn("draft-conv");
    boolean inbound = ChainElementFamilies.isTrigger(capabilityKey);
    String interactionId = inbound ? "entry" : "send";
    RequirementFact capability =
        new RequirementFact(
            interactionId,
            RequirementFactPolarity.POSITIVE,
            RequirementFactKind.CAPABILITY,
            capabilityKey,
            "Direct capability");
    RequirementFlow flow =
        inbound
            ? new RequirementFlow(
                List.of(new Interaction(interactionId, Direction.INBOUND, "System", "start", "")),
                List.of())
            : new RequirementFlow(
                List.of(
                    new Interaction(
                        "http-entry", Direction.INBOUND, "Caller", "GET /start", ""),
                    new Interaction(
                        interactionId, Direction.OUTBOUND, "Target", "publish", "")),
                List.of(new Transition("http-entry", interactionId)));
    List<RequirementFact> facts =
        inbound
            ? List.of(capability)
            : List.of(
                new RequirementFact(
                    "http-entry",
                    RequirementFactPolarity.POSITIVE,
                    RequirementFactKind.CAPABILITY,
                    "http-trigger",
                    "Expose GET /start",
                    "",
                    "",
                    "",
                    "GET",
                    "/start"),
                capability);

    String result =
        captureTool.captureRequirementDraft(
            new RequirementDraftCapture(
                true,
                "Direct capability flow",
                DraftDecision.READY_FOR_PLAN,
                List.of(),
                null,
                facts,
                null,
                flow));

    RequirementDraft draft = store.get("draft-conv").orElseThrow();
    assertTrue(result.contains("Requirement draft captured"), result);
    assertFalse(result.contains("catalog-backed interactions are unresolved"), result);
    assertTrue(draft.readyForPlan());
    assertTrue(draft.catalogBindings().isEmpty());
    verifyNoInteractions(lookup);
  }

  @Test
  void outboundWithoutSenderDoesNotAutoLookupOrBindingSoftDowngrade() {
    CatalogOperationLookup lookup = mock(CatalogOperationLookup.class);
    RequirementDraftTool captureTool =
        RequirementDraftTool.withLookup(store, new ConversationApiResolutions(), lookup);
    MDC.put(ChatMdc.CONVERSATION_ID, "draft-conv");
    store.beginTurn("draft-conv");
    RequirementFlow flow =
        new RequirementFlow(
            List.of(
                new Interaction(
                    "order-received", Direction.INBOUND, "Caller", "POST /orders", ""),
                new Interaction(
                    "create-order", Direction.OUTBOUND, "Order System", "createOrder", "")),
            List.of(new Transition("order-received", "create-order")));
    List<RequirementFact> facts =
        List.of(
            new RequirementFact(
                "order-received",
                RequirementFactPolarity.POSITIVE,
                RequirementFactKind.CAPABILITY,
                "http-trigger",
                "Expose POST /orders",
                "",
                "",
                "",
                "POST",
                "/orders"));

    String result =
        captureTool.captureRequirementDraft(
            new RequirementDraftCapture(
                true,
                "Receive POST /orders and create an order",
                DraftDecision.READY_FOR_PLAN,
                List.of(),
                null,
                facts,
                null,
                flow));

    RequirementDraft draft = store.get("draft-conv").orElseThrow();
    assertEquals(DraftDecision.NEEDS_INPUT, draft.decision());
    assertFalse(result.contains("catalog-backed interactions are unresolved"), result);
    assertTrue(
        draft.openQuestions().getFirst().contains("create-order"),
        draft.openQuestions().toString());
    verifyNoInteractions(lookup);
  }

  @Test
  void captureInfersDirectHttpSenderFromOwnedBehaviorFact() {
    CatalogOperationLookup lookup = mock(CatalogOperationLookup.class);
    RequirementDraftTool captureTool =
        RequirementDraftTool.withLookup(store, new ConversationApiResolutions(), lookup);
    MDC.put(ChatMdc.CONVERSATION_ID, "draft-conv");
    store.beginTurn("draft-conv");
    RequirementFlow flow =
        new RequirementFlow(
            List.of(
                new Interaction(
                    "request-received", Direction.INBOUND, "Caller", "GET /check-context", ""),
                new Interaction(
                    "with-context-call",
                    Direction.OUTBOUND,
                    "Get Context Headers",
                    "GET /auto-tests/get-context-headers",
                    "Call Get Context Headers for the with-context branch.")),
            List.of(new Transition("request-received", "with-context-call")));
    List<RequirementFact> facts =
        List.of(
            new RequirementFact(
                "request-received",
                RequirementFactPolarity.POSITIVE,
                RequirementFactKind.CAPABILITY,
                "http-trigger",
                "Expose GET /check-context",
                "",
                "",
                "",
                "GET",
                "/check-context"),
            new RequirementFact(
                "with-context-config",
                RequirementFactPolarity.POSITIVE,
                RequirementFactKind.BEHAVIOR,
                "",
                "The with-context branch uses HTTP Sender with propagateContext=true.",
                "with-context branch",
                "",
                "",
                "",
                "",
                ""));

    String result =
        captureTool.captureRequirementDraft(
            new RequirementDraftCapture(
                true,
                "Use HTTP Sender to call GET /auto-tests/get-context-headers.",
                DraftDecision.READY_FOR_PLAN,
                List.of(),
                null,
                facts,
                null,
                flow));

    assertTrue(result.contains("Requirement draft captured"), result);
    RequirementDraft draft = store.get("draft-conv").orElseThrow();
    assertTrue(draft.readyForPlan());
    assertTrue(
        draft.facts().stream()
            .anyMatch(
                fact ->
                    "with-context-call".equals(fact.sourceFactId())
                        && "http-sender".equals(fact.capabilityKey())));
    verifyNoInteractions(lookup);
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
  void captureKeepsReadyForPlanFlowWhenUploadedSpecsAreApproved() {
    ConversationService conversations = new ConversationService();
    conversations.registerAllowedAttachmentKeys(
        "draft-conv", List.of("sessions/conv/salesforce-wfm.json"));
    RequirementDraftTool tool = RequirementDraftTool.withConversationService(store, conversations);
    MDC.put(ChatMdc.CONVERSATION_ID, "draft-conv");
    store.beginTurn("draft-conv");

    String result =
        tool.captureRequirementDraft(flowCapture(true, DraftDecision.READY_FOR_PLAN, rockyFlow()));

    assertFalse(result.contains("resolveApiOperation"), result);
    RequirementDraft draft = store.get("draft-conv").orElseThrow();
    assertEquals(DraftDecision.READY_FOR_PLAN, draft.decision());
    assertTrue(draft.openQuestions().isEmpty());
    assertEquals(rockyFlow(), draft.flow());
  }

  @Test
  void captureAlignsUploadedHttpTriggerFactWithItsInboundInteraction() {
    ConversationService conversations = new ConversationService();
    conversations.registerAllowedAttachmentKeys(
        "draft-conv", List.of("sessions/conv/orders.yaml"));
    RequirementDraftTool tool = RequirementDraftTool.withConversationService(store, conversations);
    MDC.put(ChatMdc.CONVERSATION_ID, "draft-conv");
    store.beginTurn("draft-conv");

    String result =
        tool.captureRequirementDraft(
            new RequirementDraftCapture(
                true,
                "Expose GET /orders using the attached specifications",
                DraftDecision.READY_FOR_PLAN,
                List.of(),
                null,
                sampleFactsWithTrigger("orders-http-trigger"),
                null,
                nativeHttpFlow()));

    RequirementDraft draft = store.get("draft-conv").orElseThrow();
    assertTrue(result.contains("Requirement draft captured"), result);
    assertTrue(draft.readyForPlan(), draft.toString());
    RequirementFact trigger =
        draft.facts().stream()
            .filter(fact -> "http-trigger".equals(fact.capabilityKey()))
            .findFirst()
            .orElseThrow();
    assertEquals("orders-http", trigger.sourceFactId());
  }

  @Test
  void captureDoesNotGuessHttpTriggerOwnerWhenMultipleInboundInteractionsMatch() {
    ConversationService conversations = new ConversationService();
    conversations.registerAllowedAttachmentKeys(
        "draft-conv", List.of("sessions/conv/orders.yaml"));
    RequirementDraftTool tool = RequirementDraftTool.withConversationService(store, conversations);
    MDC.put(ChatMdc.CONVERSATION_ID, "draft-conv");
    store.beginTurn("draft-conv");
    RequirementFlow ambiguousFlow =
        new RequirementFlow(
            List.of(
                new Interaction("orders-primary", Direction.INBOUND, "Caller", "GET /orders", ""),
                new Interaction("orders-secondary", Direction.INBOUND, "Caller", "GET /orders", "")),
            List.of());

    tool.captureRequirementDraft(
        new RequirementDraftCapture(
            true,
            "Expose GET /orders using the attached specifications",
            DraftDecision.READY_FOR_PLAN,
            List.of(),
            null,
            sampleFactsWithTrigger("orders-http-trigger"),
            null,
            ambiguousFlow));

    RequirementDraft draft = store.get("draft-conv").orElseThrow();
    assertFalse(draft.readyForPlan());
    assertEquals(
        "orders-http-trigger",
        draft.facts().stream()
            .filter(fact -> "http-trigger".equals(fact.capabilityKey()))
            .findFirst()
            .orElseThrow()
            .sourceFactId());
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
  void unknownTriggerKeyCannotBecomeAnApprovedDraft() {
    MDC.put(ChatMdc.CONVERSATION_ID, "draft-conv");
    store.beginTurn("draft-conv");
    RequirementFlow flow =
        new RequirementFlow(
            List.of(
                new Interaction(
                    "scheduled-run", Direction.INBOUND, "Scheduler", "hourly", "")),
            List.of());

    String result =
        tool.captureRequirementDraft(
            new RequirementDraftCapture(
                true,
                "Run hourly",
                DraftDecision.READY_FOR_PLAN,
                List.of(),
                null,
                List.of(
                    new RequirementFact(
                        "scheduled-run",
                        RequirementFactPolarity.POSITIVE,
                        RequirementFactKind.CAPABILITY,
                        "quartz-trigger",
                        "Run hourly"),
                    RequirementFact.of(
                        RequirementFactPolarity.NEGATIVE,
                        RequirementFactKind.CONSTRAINT,
                        "",
                        "Do not call MCP")),
                null,
                flow));

    RequirementDraft stored = store.get("draft-conv").orElseThrow();
    assertEquals(DraftDecision.NEEDS_INPUT, stored.decision());
    assertFalse(stored.readyForPlan());
    assertTrue(result.contains("NEEDS_INPUT"), result);
    assertTrue(stored.openQuestions().getFirst().contains("quartz-trigger"));
    assertTrue(stored.openQuestions().getFirst().contains("quartz-scheduler"));
  }

  @Test
  void uploadedSpecificationsDoNotBypassInboundCapabilityValidation() {
    ConversationService conversations = new ConversationService();
    conversations.registerAllowedAttachmentKeys(
        "draft-conv", List.of("sessions/conv/scheduler-api.json"));
    RequirementDraftTool tool = RequirementDraftTool.withConversationService(store, conversations);
    MDC.put(ChatMdc.CONVERSATION_ID, "draft-conv");
    store.beginTurn("draft-conv");
    RequirementFlow flow =
        new RequirementFlow(
            List.of(
                new Interaction(
                    "scheduled-run", Direction.INBOUND, "Scheduler", "hourly", "")),
            List.of());

    tool.captureRequirementDraft(
        new RequirementDraftCapture(
            true,
            "Run hourly",
            DraftDecision.READY_FOR_PLAN,
            List.of(),
            null,
            List.of(
                new RequirementFact(
                    "scheduled-run",
                    RequirementFactPolarity.POSITIVE,
                    RequirementFactKind.CAPABILITY,
                    "quartz-trigger",
                    "Run hourly"),
                RequirementFact.of(
                    RequirementFactPolarity.NEGATIVE,
                    RequirementFactKind.CONSTRAINT,
                    "",
                    "Do not call MCP")),
            null,
            flow));

    RequirementDraft stored = store.get("draft-conv").orElseThrow();
    assertEquals(DraftDecision.NEEDS_INPUT, stored.decision());
    assertTrue(stored.openQuestions().getFirst().contains("quartz-trigger"));
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
    assertTrue(result.contains("interactionId=task-start"), result);
    assertTrue(result.contains("catalog-backed interactions are unresolved"), result);
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
    assertEquals(DraftDecision.NEEDS_INPUT, stored.decision());
    assertFalse(stored.readyForPlan());
    assertEquals(1, stored.catalogBindings().size(), stored.catalogBindings().toString());
    assertTrue(
        stored.catalogBindings().stream()
            .anyMatch(hint -> "task-start".equals(hint.interactionId())));
    assertFalse(result.contains("has no catalog binding"), result);
    assertTrue(
        stored.openQuestions().getFirst().contains("create-task"),
        stored.openQuestions().toString());
  }

  @Test
  void captureUsesResolvedCatalogIdentityForTheBoundInteraction() {
    ConversationApiResolutions resolutions = new ConversationApiResolutions();
    resolutions.remember(
        "draft-conv",
        InteractionAssessment.resolved(
            "task-result",
            new InteractionAssessment.Intent(
                "Return the task result", "OM", "onTaskResult", null, null),
            omResultMatch()));
    RequirementDraftTool captureTool =
        RequirementDraftTool.withLookup(store, resolutions, rockyCatalogLookup());
    MDC.put(ChatMdc.CONVERSATION_ID, "draft-conv");
    store.beginTurn("draft-conv");
    RequirementFlow confusedFlow =
        new RequirementFlow(
            List.of(
                new Interaction("task-start", Direction.INBOUND, "OM", "onTaskStart", ""),
                new Interaction("create-task", Direction.OUTBOUND, "Salesforce", "createTask", ""),
                new Interaction(
                    "task-result", Direction.OUTBOUND, "Salesforce", "onTaskStart", "")),
            List.of(
                new Transition("task-start", "create-task"),
                new Transition("create-task", "task-result")));

    captureTool.captureRequirementDraft(
        flowCapture(true, DraftDecision.READY_FOR_PLAN, confusedFlow));

    RequirementDraft stored = store.get("draft-conv").orElseThrow();
    assertEquals(
        "OM", stored.flow().interaction("task-result").orElseThrow().participant());
    assertEquals(
        "onTaskResult", stored.flow().interaction("task-result").orElseThrow().operation());
    assertEquals(
        "onTaskResult",
        RequirementBriefProjector.serviceCallsFrom(
                stored.flow(),
                stored.catalogBindings().stream()
                    .collect(
                        java.util.stream.Collectors.toMap(
                            CatalogBindingHint::interactionId, hint -> hint)),
                stored.facts())
            .stream()
            .filter(call -> "task-result".equals(call.serviceCallId()))
            .findFirst()
            .orElseThrow()
            .operation());
  }

  @Test
  void captureRejectsReadyFlowWhenExplicitCatalogOperationWasOmitted() {
    RequirementDraftTool captureTool = rockyCatalogCaptureTool(null);
    MDC.put(ChatMdc.CONVERSATION_ID, "draft-conv");
    store.beginTurn("draft-conv");

    String result =
        captureTool.captureRequirementDraft(
            new RequirementDraftCapture(
                true,
                "OM onTaskStart calls Salesforce createTask, then sends completeTask through OM"
                    + " onTaskResult.",
                DraftDecision.READY_FOR_PLAN,
                List.of(),
                null,
                rockyFacts(),
                null,
                rockyTriggerOnlyFlow()));

    RequirementDraft stored = store.get("draft-conv").orElseThrow();
    assertEquals(DraftDecision.NEEDS_INPUT, stored.decision());
    assertFalse(stored.readyForPlan());
    assertTrue(
        stored.openQuestions().stream().anyMatch(question -> question.contains("onTaskResult")),
        stored.openQuestions().toString());
    assertTrue(result.contains("onTaskResult"), result);
  }

  @Test
  void captureAcceptsReadyFlowWhenEveryExplicitCatalogOperationIsBound() {
    RequirementDraftTool captureTool = rockyCatalogCaptureTool(null);
    MDC.put(ChatMdc.CONVERSATION_ID, "draft-conv");
    store.beginTurn("draft-conv");

    captureTool.captureRequirementDraft(
        new RequirementDraftCapture(
            true,
            "OM onTaskStart calls Salesforce createTask, then OM onTaskResult returns the result.",
            DraftDecision.READY_FOR_PLAN,
            List.of(),
            null,
            rockyFacts(),
            null,
            rockyFlow()));

    RequirementDraft stored = store.get("draft-conv").orElseThrow();
    assertEquals(DraftDecision.NEEDS_INPUT, stored.decision());
    assertTrue(
        stored.openQuestions().getFirst().contains("create-task"),
        stored.openQuestions().toString());
  }

  @Test
  void captureDetectsOmittedCatalogOperationByMethodAndPath() {
    RequirementDraftTool captureTool = rockyCatalogCaptureTool(null);
    MDC.put(ChatMdc.CONVERSATION_ID, "draft-conv");
    store.beginTurn("draft-conv");

    String result =
        captureTool.captureRequirementDraft(
            new RequirementDraftCapture(
                true,
                "OM onTaskStart calls Salesforce createTask, then subscribe task.result.",
                DraftDecision.READY_FOR_PLAN,
                List.of(),
                null,
                rockyFacts(),
                null,
                rockyFlowWithoutResult()));

    assertEquals(DraftDecision.NEEDS_INPUT, store.get("draft-conv").orElseThrow().decision());
    assertTrue(
        result.contains("onTaskResult") || result.contains("create-task"),
        result);
  }

  @Test
  void captureDetectsOmittedIntermediateCatalogOperation() {
    RequirementDraftTool captureTool = rockyCatalogCaptureTool(null);
    MDC.put(ChatMdc.CONVERSATION_ID, "draft-conv");
    store.beginTurn("draft-conv");
    RequirementFlow flowWithoutCreateTask =
        new RequirementFlow(
            List.of(
                new Interaction("task-start", Direction.INBOUND, "OM", "onTaskStart", ""),
                new Interaction("task-result", Direction.OUTBOUND, "OM", "onTaskResult", "")),
            List.of(new Transition("task-start", "task-result")));

    String result =
        captureTool.captureRequirementDraft(
            new RequirementDraftCapture(
                true,
                "OM onTaskStart calls Salesforce createTask, then OM onTaskResult returns the result.",
                DraftDecision.READY_FOR_PLAN,
                List.of(),
                null,
                rockyFacts(),
                null,
                flowWithoutCreateTask));

    assertEquals(DraftDecision.NEEDS_INPUT, store.get("draft-conv").orElseThrow().decision());
    assertTrue(result.contains("createTask"), result);
  }

  @Test
  void captureReportsAllOmittedCatalogOperationsInOneTurn() {
    RequirementDraftTool captureTool = rockyCatalogCaptureTool(null);
    MDC.put(ChatMdc.CONVERSATION_ID, "draft-conv");
    store.beginTurn("draft-conv");
    RequirementFlow triggerOnly =
        new RequirementFlow(
            List.of(
                new Interaction("task-start", Direction.INBOUND, "OM", "onTaskStart", "")),
            List.of());

    String result =
        captureTool.captureRequirementDraft(
            new RequirementDraftCapture(
                true,
                "OM onTaskStart calls Salesforce createTask, then OM onTaskResult returns the result.",
                DraftDecision.READY_FOR_PLAN,
                List.of(),
                null,
                rockyFacts(),
                null,
                triggerOnly));

    assertEquals(DraftDecision.NEEDS_INPUT, store.get("draft-conv").orElseThrow().decision());
    assertTrue(result.contains("createTask"), result);
    assertTrue(result.contains("onTaskResult"), result);
  }

  @Test
  void captureDetectsOmittedCatalogTriggerWhenAnotherTriggerRemains() {
    RequirementDraftTool captureTool = rockyCatalogCaptureTool(null);
    MDC.put(ChatMdc.CONVERSATION_ID, "draft-conv");
    store.beginTurn("draft-conv");
    RequirementFlow flowWithoutCatalogTrigger =
        new RequirementFlow(
            List.of(
                new Interaction("http-entry", Direction.INBOUND, "Caller", "POST /tasks", ""),
                new Interaction("create-task", Direction.OUTBOUND, "Salesforce", "createTask", ""),
                new Interaction("task-result", Direction.OUTBOUND, "OM", "onTaskResult", "")),
            List.of(
                new Transition("http-entry", "create-task"),
                new Transition("create-task", "task-result")));
    List<RequirementFact> facts =
        List.of(
            RequirementFactFixtures.httpTriggerFact("http-entry", "POST", "/tasks"),
            rockyFacts().getFirst());

    String result =
        captureTool.captureRequirementDraft(
            new RequirementDraftCapture(
                true,
                "Caller POST /tasks and OM onTaskStart call Salesforce createTask, then OM"
                    + " onTaskResult returns the result.",
                DraftDecision.READY_FOR_PLAN,
                List.of(),
                null,
                facts,
                null,
                flowWithoutCatalogTrigger));

    assertEquals(DraftDecision.NEEDS_INPUT, store.get("draft-conv").orElseThrow().decision());
    assertTrue(result.contains("onTaskStart"), result);
  }

  @Test
  void captureIgnoresUncatalogedOperationLikePayloadFields() {
    RequirementDraftTool captureTool = rockyCatalogCaptureTool(null);
    MDC.put(ChatMdc.CONVERSATION_ID, "draft-conv");
    store.beginTurn("draft-conv");

    captureTool.captureRequirementDraft(
        new RequirementDraftCapture(
            true,
            "OM onTaskStart consumes the task start event and maps completeTaskResultCode.",
            DraftDecision.READY_FOR_PLAN,
            List.of(),
            null,
            rockyFacts(),
            null,
            rockyTriggerOnlyFlow()));

    assertEquals(DraftDecision.READY_FOR_PLAN, store.get("draft-conv").orElseThrow().decision());
  }

  @Test
  void captureAllowsExplicitlyExcludedCatalogOperation() {
    RequirementDraftTool captureTool = rockyCatalogCaptureTool(null);
    MDC.put(ChatMdc.CONVERSATION_ID, "draft-conv");
    store.beginTurn("draft-conv");
    List<RequirementFact> facts =
        List.of(
            rockyFacts().getFirst(),
            RequirementFact.of(
                RequirementFactPolarity.NEGATIVE,
                RequirementFactKind.CONSTRAINT,
                "excluded-result",
                "Do not call onTaskResult"));

    captureTool.captureRequirementDraft(
        new RequirementDraftCapture(
            true,
            "OM onTaskStart calls Salesforce createTask; do not call onTaskResult.",
            DraftDecision.READY_FOR_PLAN,
            List.of(),
            null,
            facts,
            null,
            rockyTriggerOnlyFlow()));

    assertEquals(DraftDecision.READY_FOR_PLAN, store.get("draft-conv").orElseThrow().decision());
  }

  @Test
  void captureChecksKnownCatalogOperationsWithUploadedSpecifications() {
    ConversationService conversations = new ConversationService();
    conversations.registerAllowedAttachmentKeys("draft-conv", List.of("salesforce-openapi"));
    RequirementDraftTool captureTool = rockyCatalogCaptureTool(conversations);
    MDC.put(ChatMdc.CONVERSATION_ID, "draft-conv");
    store.beginTurn("draft-conv");

    captureTool.captureRequirementDraft(
        new RequirementDraftCapture(
            true,
            "OM onTaskStart calls Salesforce createTask, then OM onTaskResult returns the result.",
            DraftDecision.READY_FOR_PLAN,
            List.of(),
            null,
            rockyFacts(),
            null,
            rockyFlowWithoutResult()));

    assertEquals(DraftDecision.NEEDS_INPUT, store.get("draft-conv").orElseThrow().decision());
  }

  @Test
  void captureRejectsReadyFlowWhenPositiveFactNamesAnOmittedCatalogOperation() {
    RequirementDraftTool captureTool = rockyCatalogCaptureTool(null);
    MDC.put(ChatMdc.CONVERSATION_ID, "draft-conv");
    store.beginTurn("draft-conv");
    List<RequirementFact> facts =
        List.of(
            rockyFacts().getFirst(),
            RequirementFact.of(
                RequirementFactPolarity.POSITIVE,
                RequirementFactKind.BEHAVIOR,
                "",
                "Return the result through onTaskResult"));

    captureTool.captureRequirementDraft(
        new RequirementDraftCapture(
            true,
            "OM onTaskStart calls Salesforce createTask.",
            DraftDecision.READY_FOR_PLAN,
            List.of(),
            null,
            facts,
            null,
            rockyTriggerOnlyFlow()));

    RequirementDraft stored = store.get("draft-conv").orElseThrow();
    assertEquals(DraftDecision.NEEDS_INPUT, stored.decision());
    assertTrue(
        stored.openQuestions().stream().anyMatch(question -> question.contains("onTaskResult")),
        stored.openQuestions().toString());
  }

  @Test
  void captureDoesNotCountRepeatedFactReferencesAsRepeatedCalls() {
    RequirementDraftTool captureTool = rockyCatalogCaptureTool(null);
    MDC.put(ChatMdc.CONVERSATION_ID, "draft-conv");
    store.beginTurn("draft-conv");
    List<RequirementFact> facts =
        List.of(
            rockyFacts().getFirst(),
            RequirementFact.of(
                RequirementFactPolarity.POSITIVE,
                RequirementFactKind.BEHAVIOR,
                "",
                "Map the createTask request"),
            RequirementFact.of(
                RequirementFactPolarity.POSITIVE,
                RequirementFactKind.BEHAVIOR,
                "",
                "Handle the createTask response"));

    captureTool.captureRequirementDraft(
        new RequirementDraftCapture(
            true,
            "OM onTaskStart consumes the task start event.",
            DraftDecision.READY_FOR_PLAN,
            List.of(),
            null,
            facts,
            null,
            rockyTriggerOnlyFlow()));

    assertEquals(DraftDecision.READY_FOR_PLAN, store.get("draft-conv").orElseThrow().decision());
  }

  @Test
  void captureDoesNotCountMappingHeadersAsRepeatedCalls() {
    RequirementDraftTool captureTool = rockyCatalogCaptureTool(null);
    MDC.put(ChatMdc.CONVERSATION_ID, "draft-conv");
    store.beginTurn("draft-conv");

    captureTool.captureRequirementDraft(
        new RequirementDraftCapture(
            true,
            "OM onTaskStart consumes the task start event.\n"
                + "Request mapping: Subject = name.\n"
                + "Response mapping: commandType = completeTask.",
            DraftDecision.READY_FOR_PLAN,
            List.of(),
            null,
            rockyFacts(),
            null,
            rockyTriggerOnlyFlow()));

    assertEquals(DraftDecision.READY_FOR_PLAN, store.get("draft-conv").orElseThrow().decision());
  }

  @Test
  void capturePinsAsyncApiTriggerFromCatalogOnCapture() {
    CatalogOperationLookup lookup = mock(CatalogOperationLookup.class);
    when(lookup.resolve(org.mockito.ArgumentMatchers.any(CatalogQuery.class)))
        .thenAnswer(
            invocation -> {
              String operation = invocation.<CatalogQuery>getArgument(0).operationHint();
              if ("onTaskStart".equals(operation)) {
                return new CatalogLookupResult.Exact(omStartMatch());
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
    assertEquals(DraftDecision.NEEDS_INPUT, stored.decision());
    assertFalse(stored.readyForPlan());
    assertTrue(
        stored.catalogBindings().stream()
            .anyMatch(
                hint ->
                    "task-start".equals(hint.interactionId())
                        && "op-start".equals(hint.integrationOperationId())),
        stored.catalogBindings().toString());
    assertTrue(
        stored.openQuestions().getFirst().contains("create-task"),
        stored.openQuestions().toString());
    assertFalse(result.contains("has no catalog binding"), result);
  }

  @Test
  void captureLeavesCreateTaskUnboundWhenTheLatestUserMessageDoesNotNameAnId() {
    String titleOpId =
        "80be9ebb-b528-48e1-8803-e355c1f109c1-Salesforce WFM-1.0.0-createTask";
    CatalogOperationLookup lookup = tiedCreateTaskLookup(titleOpId);
    ConversationApiResolutions resolutions = new ConversationApiResolutions();
    RequirementDraftTool captureTool =
        RequirementDraftTool.withLookup(store, resolutions, lookup);
    MDC.put(ChatMdc.CONVERSATION_ID, "draft-conv");
    store.beginTurn("draft-conv");

    String result =
        captureTool.captureRequirementDraft(
            flowCapture(true, DraftDecision.READY_FOR_PLAN, rockyFlow()));

    RequirementDraft stored = store.get("draft-conv").orElseThrow();
    assertEquals(DraftDecision.NEEDS_INPUT, stored.decision());
    assertFalse(stored.readyForPlan());
    assertFalse(result.contains("catalog-backed interactions are unresolved"), result);
    assertTrue(
        stored.openQuestions().getFirst().contains("create-task"),
        stored.openQuestions().toString());
    assertFalse(
        stored.catalogBindings().stream()
            .anyMatch(hint -> "create-task".equals(hint.interactionId())),
        stored.catalogBindings().toString());
  }

  @Test
  void needsInputCaptureEchoesCatalogBoundAfterExactLocalMatches() {
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

    String first =
        captureTool.captureRequirementDraft(
            flowCapture(false, DraftDecision.NEEDS_INPUT, rockyFlow()));

    assertFalse(first.contains("openQuestions is required"), first);
    assertTrue(first.contains("CATALOG_BOUND"), first);
    assertTrue(first.contains("task-start"), first);
    assertTrue(first.contains("sys-om"), first);
    assertTrue(first.contains("READY_FOR_PLAN"), first);
    assertTrue(first.contains("Do not ask the user"), first);
    assertFalse(first.contains("Unresolved interactions"), first);
    RequirementDraft afterBind = store.get("draft-conv").orElseThrow();
    assertEquals(DraftDecision.NEEDS_INPUT, afterBind.decision());
    assertFalse(afterBind.readyForPlan());
    assertTrue(afterBind.openQuestions().isEmpty());
    assertEquals(1, afterBind.catalogBindings().size(), afterBind.catalogBindings().toString());

    String second =
        captureTool.captureRequirementDraft(
            flowCapture(true, DraftDecision.READY_FOR_PLAN, rockyFlow()));

    RequirementDraft ready = store.get("draft-conv").orElseThrow();
    assertEquals(DraftDecision.NEEDS_INPUT, ready.decision());
    assertFalse(ready.readyForPlan());
    assertTrue(
        ready.openQuestions().getFirst().contains("create-task"),
        ready.openQuestions().toString());
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
  void readyForPlanDoesNotAutoBindUnclassifiedOutboundKafkaPublish() {
    CatalogOperationLookup lookup = mock(CatalogOperationLookup.class);
    RequirementDraftTool captureTool =
        RequirementDraftTool.withLookup(store, new ConversationApiResolutions(), lookup);
    MDC.put(ChatMdc.CONVERSATION_ID, "draft-conv");
    store.beginTurn("draft-conv");
    RequirementFlow flow =
        new RequirementFlow(
            List.of(
                new Interaction("http-start", Direction.INBOUND, "Caller", "GET /start", ""),
                new Interaction(
                    "publish-event",
                    Direction.OUTBOUND,
                    "Kafka service",
                    "onTaskStart",
                    "")),
            List.of(new Transition("http-start", "publish-event")));
    RequirementFact httpTrigger =
        new RequirementFact(
            "http-start",
            RequirementFactPolarity.POSITIVE,
            RequirementFactKind.CAPABILITY,
            "http-trigger",
            "Expose GET /start",
            "",
            "",
            "",
            "GET",
            "/start");

    captureTool.captureRequirementDraft(
        new RequirementDraftCapture(
            true,
            "Receive GET /start and publish onTaskStart to Kafka.",
            DraftDecision.READY_FOR_PLAN,
            List.of(),
            null,
            List.of(httpTrigger),
            null,
            flow));

    RequirementDraft stored = store.get("draft-conv").orElseThrow();
    assertEquals(DraftDecision.NEEDS_INPUT, stored.decision());
    assertTrue(stored.catalogBindings().isEmpty());
    assertTrue(
        stored.openQuestions().getFirst().contains("publish-event"),
        stored.openQuestions().toString());
    verifyNoInteractions(lookup);
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
            List.of(v2),
            null));

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
        new RequirementFact(
            "task-start",
            RequirementFactPolarity.POSITIVE,
            RequirementFactKind.CAPABILITY,
            "async-api-trigger",
            "Consume OM task start events"),
        RequirementFact.of(
            RequirementFactPolarity.POSITIVE,
            RequirementFactKind.BEHAVIOR,
            "",
            "commandType is completeTask"));
  }

  private static List<RequirementFact> rockyHttpFacts() {
    return List.of(
        new RequirementFact(
            "order-received",
            RequirementFactPolarity.POSITIVE,
            RequirementFactKind.CAPABILITY,
            "http-trigger",
            "Expose POST /orders",
            "",
            "",
            "",
            "POST",
            "/orders"),
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

  private static RequirementFlow rockyTriggerOnlyFlow() {
    return new RequirementFlow(
        List.of(new Interaction("task-start", Direction.INBOUND, "OM", "onTaskStart", "")),
        List.of());
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

  private static RequirementFlow chainCallFlow() {
    return new RequirementFlow(
        List.of(
            new Interaction(
                "http-entry", Direction.INBOUND, "Caller", "GET /auto-tests/chain-call", ""),
            new Interaction(
                "call-other",
                Direction.OUTBOUND,
                "Chain trigger + Header modification",
                "chain-trigger",
                "")),
        List.of(new Transition("http-entry", "call-other")));
  }

  private static RequirementFlow mcpTriggerFlow() {
    return new RequirementFlow(
        List.of(
            new Interaction("mcp-entry", Direction.INBOUND, "Agent", "tool", "")),
        List.of());
  }

  private static RequirementFlow mcpTriggerWithChainCallFlow() {
    return new RequirementFlow(
        List.of(
            new Interaction("mcp-entry", Direction.INBOUND, "Agent", "tool", ""),
            new Interaction("call-other", Direction.OUTBOUND, "Orders", "chain-trigger", "")),
        List.of(new Transition("mcp-entry", "call-other")));
  }

  private static CatalogMcpSystemDto mcpSystem(String id, String name, String identifier) {
    CatalogMcpSystemDto dto = new CatalogMcpSystemDto();
    dto.id = id;
    dto.name = name;
    dto.identifier = identifier;
    return dto;
  }

  private static CatalogElementResponseDto catalogTrigger(String id, String chainName) {
    CatalogElementResponseDto dto = new CatalogElementResponseDto();
    dto.id = id;
    dto.type = "chain-trigger-2";
    dto.chainName = chainName;
    dto.name = "Trigger";
    return dto;
  }

  private static CatalogOperationLookup tiedCreateTaskLookup(String titleOpId) {
    CatalogOperationLookup lookup = mock(CatalogOperationLookup.class);
    when(lookup.resolve(org.mockito.ArgumentMatchers.any(CatalogQuery.class)))
        .thenAnswer(
            invocation -> {
              CatalogQuery query = invocation.getArgument(0);
              String operation = query.operationHint();
              if ("onTaskStart".equals(operation)) {
                return new CatalogLookupResult.Exact(omStartMatch());
              }
              if ("onTaskResult".equals(operation)) {
                return new CatalogLookupResult.Exact(omResultMatch());
              }
              if ("createTask".equals(operation)) {
                boolean namedChosen =
                    query.namedInRequest().stream().anyMatch(named -> named.contains(titleOpId));
                if (namedChosen) {
                  return new CatalogLookupResult.Exact(salesforceMatch());
                }
                return new CatalogLookupResult.Ambiguous(
                    List.of(
                        titleOpId,
                        "80be9ebb-b528-48e1-8803-e355c1f109c1-Salesforce WFM Specification-1.0.0-createTask"));
              }
              return new CatalogLookupResult.None();
            });
    return lookup;
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

  private RequirementDraftTool rockyCatalogCaptureTool(ConversationService conversations) {
    return new RequirementDraftTool(
        store,
        null,
        rockyCatalogCache(),
        null,
        new ConversationApiResolutions(),
        conversations,
        rockyCatalogLookup());
  }

  private static CatalogOperationLookup rockyCatalogLookup() {
    CatalogOperationLookup lookup = mock(CatalogOperationLookup.class);
    when(lookup.resolve(org.mockito.ArgumentMatchers.any(CatalogQuery.class)))
        .thenAnswer(
            invocation -> {
              String operation = invocation.<CatalogQuery>getArgument(0).operationHint();
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
    return lookup;
  }

  private static ConversationCatalogCache rockyCatalogCache() {
    ConversationCatalogCache cache =
        new ConversationCatalogCache(mock(CatalogOperationsReadCache.class));
    cache.rememberOperation(
        "draft-conv",
        new CatalogRestClient.OperationDto(
            "op-start", "onTaskStart", "publish", "task.start", "spec-om"));
    cache.rememberOperation(
        "draft-conv",
        new CatalogRestClient.OperationDto(
            "op-create", "createTask", "POST", "/tasks", "spec-sf"));
    cache.rememberOperation(
        "draft-conv",
        new CatalogRestClient.OperationDto(
            "op-result", "onTaskResult", "subscribe", "task.result", "spec-om"));
    return cache;
  }

  private static RequirementFlow rockyFlowWithoutResult() {
    return new RequirementFlow(
        List.of(
            new Interaction("task-start", Direction.INBOUND, "OM", "onTaskStart", ""),
            new Interaction("create-task", Direction.OUTBOUND, "Salesforce", "createTask", "")),
        List.of(new Transition("task-start", "create-task")));
  }

  private static List<RequirementFact> sampleFactsWithTrigger(String interactionId) {
    List<RequirementFact> facts = new java.util.ArrayList<>();
    facts.add(RequirementFactFixtures.httpTriggerFact(interactionId, "GET", "/orders"));
    facts.addAll(sampleFacts());
    return List.copyOf(facts);
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
