package org.qubership.integration.platform.ai.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import static org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Direction.OUTBOUND;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
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
    storeFlow(
        store,
        "conv-exact",
        interaction(
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
    storeFlow(
        store,
        "conv-miss",
        interaction(
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
    storeFlow(
        store,
        "conv-broad",
        interaction("call-om", "OM", "onTaskResult", "The chain consumes OM task results"));

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
    storeFlow(
        store,
        "conv-incomplete",
        interaction(
            "call-stock", "Petstore", "", "The chain reads stock levels from somewhere"));

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
    storeFlow(
        store,
        "conv-assessments",
        interaction("call-stock", "Petstore Ext", "getInventory", "Read stock levels from Petstore Ext"),
        interaction("call-invoice", "Billing", "createInvoice", "Raise an invoice in Billing"));
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
    storeFlow(
        store,
        "conv-mixed",
        interaction("call-stock", "Petstore Ext", "getInventory", "Read stock levels from Petstore Ext"),
        interaction("call-invoice", "Billing", "createInvoice", "Raise an invoice in Billing"));
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
    storeFlow(
        store,
        "conv-all-local",
        interaction("call-stock", "Petstore Ext", "getInventory", "Read stock levels from Petstore Ext"),
        interaction("call-stock-again", "Petstore Ext", "getInventory", "Read stock levels again"));
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
    storeFlow(
        store,
        "conv-timeout",
        interaction("call-stock", "Petstore Ext", "getInventory", "Read stock levels from Petstore Ext"),
        interaction("call-invoice", "Billing", "createInvoice", "Raise an invoice in Billing"));
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
    storeFlow(
        store,
        "conv-vague",
        interaction(
            "call-stock",
            "",
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

  private static void storeFlow(
      RequirementDraftStore store, String conversationId, Interaction... interactions) {
    store.put(
        conversationId,
        new RequirementDraft(false, "captured flow")
            .withFlow(new RequirementFlow(List.of(interactions), List.of())));
  }

  private static Interaction interaction(
      String interactionId, String participant, String operation, String description) {
    return new Interaction(interactionId, OUTBOUND, participant, operation, description);
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
