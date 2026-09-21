package org.qubership.integration.platform.ai.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Direction.INBOUND;
import static org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Direction.OUTBOUND;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.qubership.integration.platform.ai.chat.ToolSession;
import org.qubership.integration.platform.ai.integration.apihub.ApiHubMcpTools;
import org.qubership.integration.platform.ai.integration.apihub.ApiHubSearchAuthorizations;
import org.qubership.integration.platform.ai.integration.catalog.cache.ConversationCatalogCache;
import org.qubership.integration.platform.ai.integration.catalog.lookup.CatalogLookupResult;
import org.qubership.integration.platform.ai.integration.catalog.lookup.CatalogMatch;
import org.qubership.integration.platform.ai.integration.catalog.lookup.CatalogOperationLookup;
import org.qubership.integration.platform.ai.integration.catalog.tool.CatalogSystemReadTool;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Interaction;
import org.qubership.integration.platform.ai.schema.ChainElementFamilies;

class CatalogFirstApiHubDiscoveryToolTest {

  @Test
  void exactCatalogMatchDoesNotQueryApiHub() {
    CatalogOperationLookup lookup = mock(CatalogOperationLookup.class);
    ApiHubMcpTools apiHub = mock(ApiHubMcpTools.class);
    when(lookup.resolve(any()))
        .thenReturn(
            new CatalogLookupResult.Exact(
                new CatalogMatch(
                    "system-1",
                    "group-1",
                    "spec-1",
                    "operation-1",
                    "Petstore Ext",
                    "http",
                    "GET",
                    "/store/inventory",
                    "getInventory",
                    "catalog-read:system-1/spec-1/operation-1")));
    RequirementDraftStore store = new RequirementDraftStore();
    storeCatalogBackedFlow(
        store,
        "conv-exact",
        catalogInteraction(
            "call-stock",
            "Petstore Ext",
            "getInventory",
            "The chain calls Petstore Ext to read stock levels"));

    String result;
    try (ToolSession.Handle ignored = ToolSession.open("conv-exact")) {
      result =
          tool(lookup, apiHub, store)
              .resolveApiOperation(
                  "call-stock", "GET", "/store/inventory", null, null, "2024.4");
    }

    assertTrue(result.contains("CATALOG_BOUND"), result);
    assertTrue(result.contains("operation-1"), result);
    verify(apiHub, never()).searchApiOperations(any(), any(), any(), any(), any(), any());
  }

  @Test
  void catalogMissUsesApiHubDiscovery() {
    CatalogOperationLookup lookup = mock(CatalogOperationLookup.class);
    ApiHubMcpTools apiHub = mock(ApiHubMcpTools.class);
    when(lookup.resolve(any())).thenReturn(new CatalogLookupResult.None());
    when(apiHub.searchApiOperations(
            eq("getInventory"), eq("rest"), eq("2024.4"), eq(0), eq(100), eq(null)))
        .thenReturn("{\"hits\":[\"candidate\"]}");
    RequirementDraftStore store = new RequirementDraftStore();
    storeCatalogBackedFlow(
        store,
        "conv-miss",
        catalogInteraction(
            "call-stock",
            "Petstore",
            "getInventory",
            "The chain calls Petstore to read stock levels"));

    String result;
    try (ToolSession.Handle ignored = ToolSession.open("conv-miss")) {
      result =
          tool(lookup, apiHub, store)
              .resolveApiOperation("call-stock", "", "", null, null, "2024.4");
    }

    assertTrue(result.contains("candidate"), result);
    verify(apiHub).searchApiOperations("getInventory", "rest", "2024.4", 0, 100, null);
  }

  @Test
  void tooBroadCatalogDoesNotQueryApiHub() {
    CatalogOperationLookup lookup = mock(CatalogOperationLookup.class);
    ApiHubMcpTools apiHub = mock(ApiHubMcpTools.class);
    when(lookup.resolve(any())).thenReturn(new CatalogLookupResult.TooBroad(80));
    RequirementDraftStore store = new RequirementDraftStore();
    storeCatalogBackedFlow(
        store,
        "conv-broad",
        catalogInteraction("call-om", "OM", "onTaskResult", "The chain consumes OM task results"));

    String result;
    try (ToolSession.Handle ignored = ToolSession.open("conv-broad")) {
      result =
          tool(lookup, apiHub, store)
              .resolveApiOperation("call-om", "", "", null, "kafka", "");
    }

    assertTrue(result.contains("INCOMPLETE"), result);
    assertTrue(result.contains("systemHint"), result);
    verifyNoInteractions(apiHub);
  }

  @Test
  void intentWithoutOperationIdentityIsIncompleteAndNeverSearches() {
    CatalogOperationLookup lookup = mock(CatalogOperationLookup.class);
    ApiHubMcpTools apiHub = mock(ApiHubMcpTools.class);
    RequirementDraftStore store = new RequirementDraftStore();
    storeCatalogBackedFlow(
        store,
        "conv-incomplete",
        new Interaction("call-stock", INBOUND, "Petstore", "", "The chain reads stock levels from somewhere"));

    String result;
    try (ToolSession.Handle ignored = ToolSession.open("conv-incomplete")) {
      result =
          tool(lookup, apiHub, store)
              .resolveApiOperation("call-stock", "", "", null, null, "");
    }

    assertTrue(result.contains("INCOMPLETE"), result);
    assertTrue(result.contains("operationHint"), result);
    verifyNoInteractions(apiHub);
    verifyNoInteractions(lookup);
  }

  @Test
  void everyInteractionKeepsItsOwnAssessment() {
    CatalogOperationLookup lookup = mock(CatalogOperationLookup.class);
    ApiHubMcpTools apiHub = mock(ApiHubMcpTools.class);
    when(lookup.resolve(any()))
        .thenReturn(
            new CatalogLookupResult.Exact(
                new CatalogMatch(
                    "system-1",
                    "group-1",
                    "spec-1",
                    "operation-1",
                    "Petstore Ext",
                    "http",
                    "GET",
                    "/store/inventory",
                    "getInventory",
                    "catalog-read:system-1/spec-1/operation-1")))
        .thenReturn(new CatalogLookupResult.None());
    ConversationApiResolutions resolutions = new ConversationApiResolutions();
    RequirementDraftStore store = new RequirementDraftStore();
    storeCatalogBackedFlow(
        store,
        "conv-assessments",
        catalogInteraction("call-stock", "Petstore Ext", "getInventory", "Read stock levels from Petstore Ext"),
        catalogInteraction("call-invoice", "Billing", "createInvoice", "Raise an invoice in Billing"));
    CatalogFirstApiHubDiscoveryTool discovery = tool(lookup, apiHub, resolutions, store);
    try (ToolSession.Handle ignored = ToolSession.open("conv-assessments")) {
      discovery.resolveApiOperation(
          "call-stock", "GET", "/store/inventory", null, null, "");
      discovery.resolveApiOperation("call-invoice", "", "", null, null, "");
    }

    List<InteractionAssessment> assessments = resolutions.assessments("conv-assessments");
    assertEquals(2, assessments.size());
    assertEquals(InteractionAssessment.Outcome.RESOLVED, assessments.get(0).outcome());
    assertEquals(InteractionAssessment.Outcome.CATALOG_MISS, assessments.get(1).outcome());
    assertEquals(
        "operation-1",
        resolutions
            .forInteraction("conv-assessments", "call-stock")
            .orElseThrow()
            .binding()
            .integrationOperationId());
  }

  @Test
  void apiHubSearchesOnlyForTheInteractionTheCatalogCouldNotAnswer() {
    CatalogOperationLookup lookup = mock(CatalogOperationLookup.class);
    ApiHubMcpTools apiHub = mock(ApiHubMcpTools.class);
    when(lookup.resolve(any()))
        .thenReturn(new CatalogLookupResult.Exact(petstoreMatch()))
        .thenReturn(new CatalogLookupResult.None());
    RequirementDraftStore store = new RequirementDraftStore();
    storeCatalogBackedFlow(
        store,
        "conv-mixed",
        catalogInteraction("call-stock", "Petstore Ext", "getInventory", "Read stock levels from Petstore Ext"),
        catalogInteraction("call-invoice", "Billing", "createInvoice", "Raise an invoice in Billing"));
    CatalogFirstApiHubDiscoveryTool discovery = tool(lookup, apiHub, store);

    try (ToolSession.Handle ignored = ToolSession.open("conv-mixed")) {
      discovery.resolveApiOperation(
          "call-stock", "GET", "/store/inventory", null, null, "");
      discovery.resolveApiOperation("call-invoice", "", "", null, null, "");
    }

    verify(apiHub, times(1))
        .searchApiOperations(eq("createInvoice"), any(), any(), any(), any(), any());
  }

  @Test
  void noApiHubCallWhenEveryOperationIsInTheCatalog() {
    CatalogOperationLookup lookup = mock(CatalogOperationLookup.class);
    ApiHubMcpTools apiHub = mock(ApiHubMcpTools.class);
    when(lookup.resolve(any())).thenReturn(new CatalogLookupResult.Exact(petstoreMatch()));
    RequirementDraftStore store = new RequirementDraftStore();
    storeCatalogBackedFlow(
        store,
        "conv-all-local",
        catalogInteraction("call-stock", "Petstore Ext", "getInventory", "Read stock levels from Petstore Ext"),
        catalogInteraction("call-stock-again", "Petstore Ext", "getInventory", "Read stock levels again"));
    CatalogFirstApiHubDiscoveryTool discovery = tool(lookup, apiHub, store);

    try (ToolSession.Handle ignored = ToolSession.open("conv-all-local")) {
      discovery.resolveApiOperation(
          "call-stock", "GET", "/store/inventory", null, null, "");
      discovery.resolveApiOperation("call-stock-again", "", "", null, null, "");
    }

    verifyNoInteractions(apiHub);
  }

  @Test
  void anApiHubFailureLeavesResolvedInteractionsAlone() {
    CatalogOperationLookup lookup = mock(CatalogOperationLookup.class);
    ApiHubMcpTools apiHub = mock(ApiHubMcpTools.class);
    when(lookup.resolve(any()))
        .thenReturn(new CatalogLookupResult.Exact(petstoreMatch()))
        .thenReturn(new CatalogLookupResult.None());
    when(apiHub.searchApiOperations(any(), any(), any(), any(), any(), any()))
        .thenThrow(new IllegalStateException("API Hub MCP timed out"));
    ConversationApiResolutions resolutions = new ConversationApiResolutions();
    RequirementDraftStore store = new RequirementDraftStore();
    storeCatalogBackedFlow(
        store,
        "conv-timeout",
        catalogInteraction("call-stock", "Petstore Ext", "getInventory", "Read stock levels from Petstore Ext"),
        catalogInteraction("call-invoice", "Billing", "createInvoice", "Raise an invoice in Billing"));
    CatalogFirstApiHubDiscoveryTool discovery = tool(lookup, apiHub, resolutions, store);

    try (ToolSession.Handle ignored = ToolSession.open("conv-timeout")) {
      discovery.resolveApiOperation(
          "call-stock", "GET", "/store/inventory", null, null, "");
      assertThrows(
          IllegalStateException.class,
          () -> discovery.resolveApiOperation("call-invoice", "", "", null, null, ""));
    }

    List<InteractionAssessment> assessments = resolutions.assessments("conv-timeout");
    assertEquals(2, assessments.size());
    assertEquals(InteractionAssessment.Outcome.RESOLVED, assessments.get(0).outcome());
    assertEquals("operation-1", assessments.get(0).binding().integrationOperationId());
  }

  @Test
  void vagueCapabilitySearchesByTheOperationHintNotTheSentence() {
    CatalogOperationLookup lookup = mock(CatalogOperationLookup.class);
    ApiHubMcpTools apiHub = mock(ApiHubMcpTools.class);
    when(lookup.resolve(any())).thenReturn(new CatalogLookupResult.None());
    RequirementDraftStore store = new RequirementDraftStore();
    storeCatalogBackedFlow(
        store,
        "conv-vague",
        catalogInteraction(
            "call-stock",
            "Petstore",
            "retrieve inventory levels",
            "The chain has to find out how many pets are left in stock before it answers"));

    try (ToolSession.Handle ignored = ToolSession.open("conv-vague")) {
      tool(lookup, apiHub, store)
          .resolveApiOperation("call-stock", "", "", null, null, "");
    }

    verify(apiHub)
        .searchApiOperations(
            eq("retrieve inventory levels"), eq("rest"), any(), any(), any(), any());
  }

  @Test
  void aBrokerOperationFallsBackToTheAsyncApiIndexNotTheRestOne() {
    assertEquals("asyncapi", CatalogFirstApiHubDiscoveryTool.apiTypeFor("kafka"));
    assertEquals("asyncapi", CatalogFirstApiHubDiscoveryTool.apiTypeFor("AMQP"));
    assertEquals("rest", CatalogFirstApiHubDiscoveryTool.apiTypeFor("http"));
    assertEquals("rest", CatalogFirstApiHubDiscoveryTool.apiTypeFor(""));
    assertEquals("rest", CatalogFirstApiHubDiscoveryTool.apiTypeFor(null));
  }

  @Test
  void omittedInteractionIdErrors() {
    CatalogOperationLookup lookup = mock(CatalogOperationLookup.class);
    ApiHubMcpTools apiHub = mock(ApiHubMcpTools.class);
    CatalogFirstApiHubDiscoveryTool discovery = tool(lookup, apiHub, new RequirementDraftStore());

    String result;
    try (ToolSession.Handle ignored = ToolSession.open("conv-many")) {
      result = discovery.resolveApiOperation("", "", "", null, null, "");
    }

    assertNotNull(result);
    assertTrue(result.contains("ERROR"), result);
    assertTrue(result.contains("interactionId is required"), result);
    verifyNoInteractions(lookup);
    verifyNoInteractions(apiHub);
  }

  @Test
  void unknownInteractionRequiresCapturedFlow() {
    CatalogOperationLookup lookup = mock(CatalogOperationLookup.class);
    ApiHubMcpTools apiHub = mock(ApiHubMcpTools.class);
    ConversationApiResolutions resolutions = new ConversationApiResolutions();
    CatalogFirstApiHubDiscoveryTool discovery =
        tool(lookup, apiHub, resolutions, new RequirementDraftStore());

    String result;
    try (ToolSession.Handle ignored = ToolSession.open("conv-no-draft")) {
      result = discovery.resolveApiOperation("call-stock", "", "", null, null, "");
    }

    assertNotNull(result);
    assertTrue(result.contains("ERROR"), result);
    assertTrue(
        result.contains("Capture RequirementFlow before resolving interactionId=call-stock"),
        result);
    assertTrue(resolutions.assessments("conv-no-draft").isEmpty());
    verifyNoInteractions(lookup);
    verifyNoInteractions(apiHub);
  }

  @Test
  void directEndpointsDoNotQueryCatalogOrApiHub() {
    CatalogOperationLookup lookup = mock(CatalogOperationLookup.class);
    ApiHubMcpTools apiHub = mock(ApiHubMcpTools.class);
    RequirementDraftStore store = new RequirementDraftStore();
    RequirementFact nativeTrigger =
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
            "/greeting");
    RequirementFact chainTrigger =
        new RequirementFact(
            "chain-entry",
            RequirementFactPolarity.POSITIVE,
            RequirementFactKind.CAPABILITY,
            "chain-trigger-2",
            "Start from a parent chain");
    RequirementFact directSender =
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
            "");
    store.put(
        "conv-direct",
        new RequirementDraft(false, "direct HTTP")
            .withFacts(List.of(nativeTrigger, chainTrigger, directSender))
            .withFlow(
                new RequirementFlow(
                    List.of(
                        new Interaction("http-entry", INBOUND, "Caller", "GET /greeting", ""),
                        new Interaction("chain-entry", INBOUND, "Parent chain", "start", ""),
                        interaction(
                            "send-greeting",
                            "Greeting service",
                            "GET /hello",
                            "Send a direct greeting request")),
                    List.of(new RequirementFlow.Transition("http-entry", "send-greeting")))));

    try (ToolSession.Handle ignored = ToolSession.open("conv-direct")) {
      assertTrue(
          tool(lookup, apiHub, store)
              .resolveApiOperation("http-entry", "GET", "/greeting", null, "http", "")
              .contains("must not use catalog or API Hub resolution"));
      assertTrue(
          tool(lookup, apiHub, store)
              .resolveApiOperation("chain-entry", "", "", null, "", "")
              .contains("must not use catalog or API Hub resolution"));
      assertTrue(
          tool(lookup, apiHub, store)
              .resolveApiOperation(
                  "send-greeting", "GET", "https://greetings.com/hello", null, "http", "")
              .contains("must not use catalog or API Hub resolution"));
    }

    verifyNoInteractions(lookup);
    verifyNoInteractions(apiHub);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "chain-trigger-2",
        "jms-trigger",
        "kafka-trigger-2",
        "mcp-trigger",
        "pubsub-trigger",
        "quartz-scheduler",
        "rabbitmq-trigger-2",
        "sds-trigger",
        "sftp-trigger-2",
        "graphql-sender",
        "http-sender",
        "jms-sender",
        "kafka-sender-2",
        "mail-sender",
        "pubsub-sender",
        "rabbitmq-sender-2",
        "scs-sender"
      })
  void directCapabilityKeysDoNotQueryCatalogOrApiHub(String capabilityKey) {
    CatalogOperationLookup lookup = mock(CatalogOperationLookup.class);
    ApiHubMcpTools apiHub = mock(ApiHubMcpTools.class);
    RequirementDraftStore store = new RequirementDraftStore();
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
                List.of(new Interaction(interactionId, INBOUND, "System", "start", "")),
                List.of())
            : new RequirementFlow(
                List.of(
                    new Interaction("http-entry", INBOUND, "Caller", "GET /start", ""),
                    new Interaction(interactionId, OUTBOUND, "Target", "publish", "")),
                List.of(new RequirementFlow.Transition("http-entry", interactionId)));
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
    store.put(
        "conv-direct-key",
        new RequirementDraft(false, "direct capability")
            .withFacts(facts)
            .withFlow(flow));

    try (ToolSession.Handle ignored = ToolSession.open("conv-direct-key")) {
      String result =
          tool(lookup, apiHub, store).resolveApiOperation(interactionId, "", "", null, "", "");
      assertTrue(result.contains("ERROR"), result);
      assertTrue(
          result.contains("must not use catalog or API Hub resolution")
              || result.contains("direct endpoint"),
          result);
    }

    verifyNoInteractions(lookup);
    verifyNoInteractions(apiHub);
  }

  @Test
  void ambiguousHttpTriggerDoesNotQueryCatalog() {
    CatalogOperationLookup lookup = mock(CatalogOperationLookup.class);
    ApiHubMcpTools apiHub = mock(ApiHubMcpTools.class);
    RequirementDraftStore store = new RequirementDraftStore();
    RequirementFact httpTrigger =
        new RequirementFact(
            "orders-http",
            RequirementFactPolarity.POSITIVE,
            RequirementFactKind.CAPABILITY,
            "http-trigger",
            "Expose an HTTP API");
    store.put(
        "conv-ambiguous-http",
        new RequirementDraft(false, "ambiguous HTTP")
            .withFacts(List.of(httpTrigger))
            .withFlow(
                new RequirementFlow(
                    List.of(
                        new Interaction(
                            "orders-http", INBOUND, "Caller", "HTTP API", "Expose an HTTP API")),
                    List.of())));

    String result;
    try (ToolSession.Handle ignored = ToolSession.open("conv-ambiguous-http")) {
      result =
          tool(lookup, apiHub, store).resolveApiOperation("orders-http", "", "", null, "http", "");
    }

    assertTrue(result.contains("ERROR"), result);
    assertTrue(result.contains("element type or HTTP mode is not decided"), result);
    verifyNoInteractions(lookup);
    verifyNoInteractions(apiHub);
  }

  @Test
  void mcpTriggerDoesNotQueryCatalog() {
    CatalogOperationLookup lookup = mock(CatalogOperationLookup.class);
    ApiHubMcpTools apiHub = mock(ApiHubMcpTools.class);
    RequirementDraftStore store = new RequirementDraftStore();
    RequirementFact mcpTrigger =
        new RequirementFact(
            "mcp-in",
            RequirementFactPolarity.POSITIVE,
            RequirementFactKind.CAPABILITY,
            "mcp-trigger",
            "Expose the chain as an MCP tool");
    store.put(
        "conv-mcp",
        new RequirementDraft(false, "MCP trigger")
            .withFacts(List.of(mcpTrigger))
            .withFlow(
                new RequirementFlow(
                    List.of(new Interaction("mcp-in", INBOUND, "Agent", "tool", "")),
                    List.of())));

    String result;
    try (ToolSession.Handle ignored = ToolSession.open("conv-mcp")) {
      result = tool(lookup, apiHub, store).resolveApiOperation("mcp-in", "", "", null, "", "");
    }

    assertTrue(result.contains("ERROR"), result);
    assertTrue(result.contains("must not use catalog or API Hub resolution"), result);
    verifyNoInteractions(lookup);
    verifyNoInteractions(apiHub);
  }

  @Test
  void implementedServiceHttpTriggerMayQueryCatalog() {
    CatalogOperationLookup lookup = mock(CatalogOperationLookup.class);
    ApiHubMcpTools apiHub = mock(ApiHubMcpTools.class);
    when(lookup.resolve(any())).thenReturn(new CatalogLookupResult.None());
    when(apiHub.searchApiOperations(any(), any(), any(), any(), any(), any()))
        .thenReturn("{\"hits\":[]}");
    RequirementDraftStore store = new RequirementDraftStore();
    RequirementFact httpTrigger =
        new RequirementFact(
            "orders-http",
            RequirementFactPolarity.POSITIVE,
            RequirementFactKind.CAPABILITY,
            "http-trigger",
            "Implement Orders API getOrder",
            "Orders API",
            "getOrder",
            "",
            "",
            "");
    store.put(
        "conv-implemented-http",
        new RequirementDraft(false, "implemented HTTP")
            .withFacts(List.of(httpTrigger))
            .withFlow(
                new RequirementFlow(
                    List.of(
                        new Interaction(
                            "orders-http", INBOUND, "Orders API", "getOrder", "Read an order")),
                    List.of())));

    try (ToolSession.Handle ignored = ToolSession.open("conv-implemented-http")) {
      tool(lookup, apiHub, store).resolveApiOperation("orders-http", "", "", null, "http", "");
    }

    verify(lookup).resolve(any());
  }

  @Test
  void outboundCatalogCallWithoutSenderMayQueryCatalog() {
    CatalogOperationLookup lookup = mock(CatalogOperationLookup.class);
    ApiHubMcpTools apiHub = mock(ApiHubMcpTools.class);
    when(lookup.resolve(any())).thenReturn(new CatalogLookupResult.None());
    when(apiHub.searchApiOperations(any(), any(), any(), any(), any(), any()))
        .thenReturn("{\"hits\":[]}");
    RequirementDraftStore store = new RequirementDraftStore();
    RequirementFact httpTrigger =
        new RequirementFact(
            "http-entry",
            RequirementFactPolarity.POSITIVE,
            RequirementFactKind.CAPABILITY,
            "http-trigger",
            "Expose GET /auto-tests/service-call",
            "",
            "",
            "",
            "GET",
            "/auto-tests/service-call");
    store.put(
        "conv-outbound-catalog",
        new RequirementDraft(false, "catalog outbound")
            .withFacts(List.of(httpTrigger))
            .withFlow(
                new RequirementFlow(
                    List.of(
                        new Interaction(
                            "http-entry",
                            INBOUND,
                            "Caller",
                            "GET /auto-tests/service-call",
                            ""),
                        new Interaction(
                            "get-variables",
                            OUTBOUND,
                            "cloud-integration-platform-catalog",
                            "getVariables_2",
                            "Fetch common variables")),
                    List.of(new RequirementFlow.Transition("http-entry", "get-variables")))));

    try (ToolSession.Handle ignored = ToolSession.open("conv-outbound-catalog")) {
      tool(lookup, apiHub, store)
          .resolveApiOperation("get-variables", "GET", "/v1/common-variables", null, "http", "");
    }

    verify(lookup).resolve(any());
  }

  private static void storeFlow(
      RequirementDraftStore store, String conversationId, Interaction... interactions) {
    store.put(
        conversationId,
        new RequirementDraft(false, "captured flow")
            .withFlow(new RequirementFlow(List.of(interactions), List.of())));
  }

  private static void storeCatalogBackedFlow(
      RequirementDraftStore store, String conversationId, Interaction... interactions) {
    List<RequirementFact> facts = new java.util.ArrayList<>();
    for (Interaction interaction : interactions) {
      if (interaction.direction() == INBOUND) {
        facts.add(
            new RequirementFact(
                interaction.interactionId(),
                RequirementFactPolarity.POSITIVE,
                RequirementFactKind.CAPABILITY,
                "async-api-trigger",
                "Catalog-backed " + interaction.operation()));
      }
    }
    store.put(
        conversationId,
        new RequirementDraft(false, "captured flow")
            .withFacts(List.copyOf(facts))
            .withFlow(new RequirementFlow(List.of(interactions), List.of())));
  }

  private static Interaction interaction(
      String interactionId, String participant, String operation, String description) {
    return new Interaction(interactionId, OUTBOUND, participant, operation, description);
  }

  private static Interaction catalogInteraction(
      String interactionId, String participant, String operation, String description) {
    return new Interaction(interactionId, INBOUND, participant, operation, description);
  }

  @Test
  void specificationHintParameterTreatsEmptyAsNormal() throws Exception {
    String description =
        CatalogFirstApiHubDiscoveryTool.class
            .getMethod(
                "resolveApiOperation",
                String.class,
                String.class,
                String.class,
                String.class,
                String.class,
                String.class)
            .getParameters()[3]
            .getAnnotation(dev.langchain4j.agent.tool.P.class)
            .value();
    assertTrue(description.contains("Empty is normal"), description);
    assertTrue(description.contains("Do not ask the user for a specification name"), description);
    assertFalse(description.contains("the reader gave"), description);
  }

  private static CatalogMatch petstoreMatch() {
    return new CatalogMatch(
        "system-1",
        "group-1",
        "spec-1",
        "operation-1",
        "Petstore Ext",
        "http",
        "GET",
        "/store/inventory",
        "getInventory",
        "catalog-read:system-1/spec-1/operation-1");
  }

  private static CatalogFirstApiHubDiscoveryTool tool(
      CatalogOperationLookup lookup, ApiHubMcpTools apiHub, RequirementDraftStore draftStore) {
    return tool(lookup, apiHub, new ConversationApiResolutions(), draftStore);
  }

  private static CatalogFirstApiHubDiscoveryTool tool(
      CatalogOperationLookup lookup,
      ApiHubMcpTools apiHub,
      ConversationApiResolutions resolutions,
      RequirementDraftStore draftStore) {
    CatalogSystemReadTool catalogRead = mock(CatalogSystemReadTool.class);
    when(catalogRead.searchCatalogSystems(any())).thenReturn(List.of());
    when(catalogRead.getApiSpecifications(any())).thenReturn(List.of());
    when(catalogRead.listCatalogOperations(any(), any(), any())).thenReturn(List.of());
    return new CatalogFirstApiHubDiscoveryTool(
        lookup,
        catalogRead,
        mock(ConversationCatalogCache.class),
        apiHub,
        resolutions,
        new ApiHubSearchAuthorizations(),
        new ObjectMapper(),
        draftStore);
  }
}
