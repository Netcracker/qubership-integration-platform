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

    String result = tool(lookup, apiHub)
            .resolveApiOperation(
                "call-stock",
                "The chain calls Petstore Ext to read stock levels",
                "Petstore Ext",
                "",
                "GET",
                "/store/inventory", null, null,
                "2024.4");

    assertTrue(result.contains("CATALOG_BOUND"), result);
    assertTrue(result.contains("operation-1"), result);
    verify(apiHub, never()).searchApiOperations(any(), any(), any(), any(), any(), any());
  }

  @Test
  void catalogMissUsesApiHubDiscovery() {
    CatalogOperationLookup lookup = mock(CatalogOperationLookup.class);
    ApiHubMcpTools apiHub = mock(ApiHubMcpTools.class);
    when(lookup.resolve(any()))
        .thenReturn(new CatalogLookupResult.None());
    when(apiHub.searchApiOperations(
            eq("getInventory"), eq("rest"), eq("2024.4"), eq(0), eq(100), eq(null)))
        .thenReturn("{\"hits\":[\"candidate\"]}");

    String result = tool(lookup, apiHub)
            .resolveApiOperation(
                "call-stock",
                "The chain calls Petstore to read stock levels",
                "Petstore",
                "getInventory",
                "",
                "", null, null,
                "2024.4");

    assertTrue(result.contains("candidate"), result);
    verify(apiHub).searchApiOperations("getInventory", "rest", "2024.4", 0, 100, null);
  }


  @Test
  void tooBroadCatalogDoesNotQueryApiHub() {
    CatalogOperationLookup lookup = mock(CatalogOperationLookup.class);
    ApiHubMcpTools apiHub = mock(ApiHubMcpTools.class);
    when(lookup.resolve(any())).thenReturn(new CatalogLookupResult.TooBroad(80));

    String result =
        tool(lookup, apiHub)
            .resolveApiOperation(
                "call-om",
                "The chain consumes OM task results",
                "OM",
                "onTaskResult",
                "",
                "",
                null,
                "kafka",
                "");

    assertTrue(result.contains("INCOMPLETE"), result);
    assertTrue(result.contains("systemHint"), result);
    verifyNoInteractions(apiHub);
  }

  @Test
  void intentWithoutOperationIdentityIsIncompleteAndNeverSearches() {
    CatalogOperationLookup lookup = mock(CatalogOperationLookup.class);
    ApiHubMcpTools apiHub = mock(ApiHubMcpTools.class);

    String result =
        tool(lookup, apiHub)
            .resolveApiOperation(
                "call-stock",
                "The chain reads stock levels from somewhere", "Petstore", "", "", "", null, null, "");

    assertTrue(result.contains("INCOMPLETE"), result);
    assertTrue(result.contains("operationHint"), result);
    verifyNoInteractions(apiHub);
    verifyNoInteractions(lookup);
  }

  @Test
  void everyServiceCallKeepsItsOwnAssessment() {
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
    CatalogFirstApiHubDiscoveryTool tool = tool(lookup, apiHub, resolutions);
    try (ToolSession.Handle ignored = ToolSession.open("conv-assessments")) {
      tool.resolveApiOperation(
          "call-stock",
          "Read stock levels from Petstore Ext",
          "Petstore Ext",
          "",
          "GET",
          "/store/inventory",
          null,
          null,
          "");
      tool.resolveApiOperation(
          "call-invoice",
          "Raise an invoice in Billing",
          "Billing",
          "createInvoice",
          "",
          "",
          null,
          null,
          "");
    }

    String conversationId = "conv-assessments";
    List<ServiceCallAssessment> assessments = resolutions.assessments(conversationId);
    assertEquals(2, assessments.size());
    assertEquals(ServiceCallAssessment.Outcome.RESOLVED, assessments.get(0).outcome());
    assertEquals(ServiceCallAssessment.Outcome.CATALOG_MISS, assessments.get(1).outcome());
    assertEquals(
        "operation-1",
        resolutions
            .forServiceCall(conversationId, "call-stock")
            .orElseThrow()
            .binding()
            .integrationOperationId());
  }

  @Test
  void apiHubSearchesOnlyForTheCallTheCatalogCouldNotAnswer() {
    CatalogOperationLookup lookup = mock(CatalogOperationLookup.class);
    ApiHubMcpTools apiHub = mock(ApiHubMcpTools.class);
    when(lookup.resolve(any()))
        .thenReturn(new CatalogLookupResult.Exact(petstoreMatch()))
        .thenReturn(new CatalogLookupResult.None());
    CatalogFirstApiHubDiscoveryTool tool = tool(lookup, apiHub);

    try (ToolSession.Handle ignored = ToolSession.open("conv-mixed")) {
      tool.resolveApiOperation(
          "call-stock",
          "Read stock levels from Petstore Ext",
          "Petstore Ext",
          "",
          "GET",
          "/store/inventory",
          null,
          null,
          "");
      tool.resolveApiOperation(
          "call-invoice",
          "Raise an invoice in Billing",
          "Billing",
          "createInvoice",
          "",
          "",
          null,
          null,
          "");
    }

    verify(apiHub, times(1)).searchApiOperations(eq("createInvoice"), any(), any(), any(), any(), any());
  }

  @Test
  void noApiHubCallWhenEveryOperationIsInTheCatalog() {
    CatalogOperationLookup lookup = mock(CatalogOperationLookup.class);
    ApiHubMcpTools apiHub = mock(ApiHubMcpTools.class);
    when(lookup.resolve(any()))
        .thenReturn(new CatalogLookupResult.Exact(petstoreMatch()));
    CatalogFirstApiHubDiscoveryTool tool = tool(lookup, apiHub);

    try (ToolSession.Handle ignored = ToolSession.open("conv-all-local")) {
      tool.resolveApiOperation(
          "call-stock",
          "Read stock levels from Petstore Ext",
          "Petstore Ext",
          "",
          "GET",
          "/store/inventory",
          null,
          null,
          "");
      tool.resolveApiOperation(
          "call-stock-again",
          "Read stock levels again",
          "Petstore Ext",
          "getInventory",
          "",
          "",
          null,
          null,
          "");
    }

    verifyNoInteractions(apiHub);
  }

  @Test
  void anApiHubFailureLeavesResolvedCallsAlone() {
    CatalogOperationLookup lookup = mock(CatalogOperationLookup.class);
    ApiHubMcpTools apiHub = mock(ApiHubMcpTools.class);
    when(lookup.resolve(any()))
        .thenReturn(new CatalogLookupResult.Exact(petstoreMatch()))
        .thenReturn(new CatalogLookupResult.None());
    when(apiHub.searchApiOperations(any(), any(), any(), any(), any(), any()))
        .thenThrow(new IllegalStateException("API Hub MCP timed out"));
    ConversationApiResolutions resolutions = new ConversationApiResolutions();
    CatalogFirstApiHubDiscoveryTool tool = tool(lookup, apiHub, resolutions);

    try (ToolSession.Handle ignored = ToolSession.open("conv-timeout")) {
      tool.resolveApiOperation(
          "call-stock",
          "Read stock levels from Petstore Ext",
          "Petstore Ext",
          "",
          "GET",
          "/store/inventory",
          null,
          null,
          "");
      assertThrows(
          IllegalStateException.class,
          () ->
              tool.resolveApiOperation(
                  "call-invoice",
                  "Raise an invoice in Billing",
                  "Billing",
                  "createInvoice",
                  "",
                  "",
                  null,
                  null,
                  ""));
    }

    List<ServiceCallAssessment> assessments = resolutions.assessments("conv-timeout");
    assertEquals(2, assessments.size());
    assertEquals(ServiceCallAssessment.Outcome.RESOLVED, assessments.get(0).outcome());
    assertEquals("operation-1", assessments.get(0).binding().integrationOperationId());
  }

  @Test
  void vagueCapabilitySearchesByTheOperationHintNotTheSentence() {
    CatalogOperationLookup lookup = mock(CatalogOperationLookup.class);
    ApiHubMcpTools apiHub = mock(ApiHubMcpTools.class);
    when(lookup.resolve(any()))
        .thenReturn(new CatalogLookupResult.None());

    try (ToolSession.Handle ignored = ToolSession.open("conv-vague")) {
      tool(lookup, apiHub)
          .resolveApiOperation(
              "call-stock",
              "The chain has to find out how many pets are left in stock before it answers",
              "",
              "retrieve inventory levels",
              "",
              "", null, null,
              "");
    }

    verify(apiHub)
        .searchApiOperations(eq("retrieve inventory levels"), eq("rest"), any(), any(), any(), any());
  }

  @Test
  void aBrokerOperationFallsBackToTheAsyncApiIndexNotTheRestOne() {
    assertEquals("asyncapi", CatalogFirstApiHubDiscoveryTool.apiTypeFor("kafka"));
    assertEquals("asyncapi", CatalogFirstApiHubDiscoveryTool.apiTypeFor("AMQP"));
    assertEquals("rest", CatalogFirstApiHubDiscoveryTool.apiTypeFor("http"));
    // An unnamed transport is not evidence of asynchrony; most calls are REST.
    assertEquals("rest", CatalogFirstApiHubDiscoveryTool.apiTypeFor(""));
    assertEquals("rest", CatalogFirstApiHubDiscoveryTool.apiTypeFor(null));
  }

  @Test
  void omittedServiceCallIdErrorsWhenTheDraftHasSeveralCalls() {
    RequirementDraftStore store = new RequirementDraftStore();
    store.put(
        "conv-many",
        new RequirementDraft(
            false,
            "OM then WFM",
            DraftDecision.NEEDS_INPUT,
            List.of("Which operations?"),
            "brainstorming",
            "1",
            null,
            null,
            false,
            List.of(
                serviceCall("call-om-result", "OM", "onTaskResult"),
                serviceCall("call-wfm-create-task", "Salesforce WFM", "createTask")),
            false));
    CatalogOperationLookup lookup = mock(CatalogOperationLookup.class);
    ApiHubMcpTools apiHub = mock(ApiHubMcpTools.class);
    CatalogFirstApiHubDiscoveryTool discovery = tool(lookup, apiHub, new ConversationApiResolutions(), store);

    String result;
    try (ToolSession.Handle ignored = ToolSession.open("conv-many")) {
      result =
          discovery.resolveApiOperation(
              "",
              "Call OM onTaskResult",
              "OM",
              "onTaskResult",
              "",
              "",
              null,
              null,
              "");
    }

    assertNotNull(result);
    assertTrue(result.contains("ERROR"), result);
    assertTrue(result.contains("serviceCallId is required"), result);
    assertTrue(result.contains("call-om-result"), result);
    assertTrue(result.contains("call-wfm-create-task"), result);
    verifyNoInteractions(lookup);
    verifyNoInteractions(apiHub);
  }

  @Test
  void omittedServiceCallIdDoesNotStoreFactDerivedAssessment() {
    CatalogOperationLookup lookup = mock(CatalogOperationLookup.class);
    ApiHubMcpTools apiHub = mock(ApiHubMcpTools.class);
    ConversationApiResolutions resolutions = new ConversationApiResolutions();
    CatalogFirstApiHubDiscoveryTool discovery = tool(lookup, apiHub, resolutions);

    String result;
    try (ToolSession.Handle ignored = ToolSession.open("conv-no-draft")) {
      result =
          discovery.resolveApiOperation(
              null,
              "Call OM onTaskResult",
              "OM",
              "onTaskResult",
              "",
              "",
              null,
              null,
              "");
    }

    assertNotNull(result);
    assertTrue(result.contains("ERROR"), result);
    assertTrue(result.contains("serviceCallId is required"), result);
    assertTrue(resolutions.assessments("conv-no-draft").isEmpty());
    verifyNoInteractions(lookup);
    verifyNoInteractions(apiHub);
  }

  @Test
  void omittedServiceCallIdDoesNotUseTheOnlyDraftCall() {
    RequirementDraftStore store = new RequirementDraftStore();
    store.put(
        "conv-one",
        new RequirementDraft(
            false,
            "Call OM",
            DraftDecision.NEEDS_INPUT,
            List.of("Resolve the operation"),
            "brainstorming",
            "1",
            null,
            null,
            false,
            List.of(serviceCall("call-om-result", "OM", "onTaskResult")),
            false));
    CatalogOperationLookup lookup = mock(CatalogOperationLookup.class);
    ApiHubMcpTools apiHub = mock(ApiHubMcpTools.class);
    ConversationApiResolutions resolutions = new ConversationApiResolutions();
    CatalogFirstApiHubDiscoveryTool discovery = tool(lookup, apiHub, resolutions, store);

    String result;
    try (ToolSession.Handle ignored = ToolSession.open("conv-one")) {
      result =
          discovery.resolveApiOperation(
              "",
              "Call OM onTaskResult",
              "OM",
              "onTaskResult",
              "",
              "",
              null,
              null,
              "");
    }

    assertTrue(result.contains("ERROR"), result);
    assertTrue(result.contains("serviceCallId is required"), result);
    assertTrue(resolutions.assessments("conv-one").isEmpty());
    verifyNoInteractions(lookup);
    verifyNoInteractions(apiHub);
  }

  private static RequirementFact serviceCall(String serviceCallId, String participant, String operation) {
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
      CatalogOperationLookup lookup, ApiHubMcpTools apiHub) {
    return tool(lookup, apiHub, new ConversationApiResolutions());
  }

  private static CatalogFirstApiHubDiscoveryTool tool(
      CatalogOperationLookup lookup, ApiHubMcpTools apiHub, ConversationApiResolutions resolutions) {
    return tool(lookup, apiHub, resolutions, null);
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
