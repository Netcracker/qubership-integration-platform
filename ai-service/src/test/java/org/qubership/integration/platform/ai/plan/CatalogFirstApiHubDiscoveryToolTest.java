package org.qubership.integration.platform.ai.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import org.qubership.integration.platform.ai.integration.catalog.tool.CatalogSystemReadTool;
import org.qubership.integration.platform.ai.productpipeline.create.design.execution.CatalogBindingMatcher;

class CatalogFirstApiHubDiscoveryToolTest {

  @Test
  void exactCatalogMatchDoesNotQueryApiHub() {
    CatalogBindingMatcher matcher = mock(CatalogBindingMatcher.class);
    ApiHubMcpTools apiHub = mock(ApiHubMcpTools.class);
    when(matcher.match(any(), any(), any(), any()))
        .thenReturn(
            new CatalogBindingMatcher.MatchResult.Exact(
                new CatalogBindingMatcher.CatalogMatch(
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

    String result = tool(matcher, apiHub)
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
    CatalogBindingMatcher matcher = mock(CatalogBindingMatcher.class);
    ApiHubMcpTools apiHub = mock(ApiHubMcpTools.class);
    when(matcher.match(any(), any(), any(), any()))
        .thenReturn(new CatalogBindingMatcher.MatchResult.None());
    when(apiHub.searchApiOperations(
            eq("getInventory"), eq("rest"), eq("2024.4"), eq(0), eq(100), eq(null)))
        .thenReturn("{\"hits\":[\"candidate\"]}");

    String result = tool(matcher, apiHub)
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
  void intentWithoutOperationIdentityIsIncompleteAndNeverSearches() {
    CatalogBindingMatcher matcher = mock(CatalogBindingMatcher.class);
    ApiHubMcpTools apiHub = mock(ApiHubMcpTools.class);

    String result =
        tool(matcher, apiHub)
            .resolveApiOperation(
                "call-stock",
                "The chain reads stock levels from somewhere", "Petstore", "", "", "", null, null, "");

    assertTrue(result.contains("INCOMPLETE"), result);
    assertTrue(result.contains("operationHint"), result);
    verifyNoInteractions(apiHub);
    verifyNoInteractions(matcher);
  }

  @Test
  void everyServiceCallKeepsItsOwnAssessment() {
    CatalogBindingMatcher matcher = mock(CatalogBindingMatcher.class);
    ApiHubMcpTools apiHub = mock(ApiHubMcpTools.class);
    when(matcher.match(any(), any(), any(), any()))
        .thenReturn(
            new CatalogBindingMatcher.MatchResult.Exact(
                new CatalogBindingMatcher.CatalogMatch(
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
        .thenReturn(new CatalogBindingMatcher.MatchResult.None());
    ConversationApiResolutions resolutions = new ConversationApiResolutions();
    CatalogFirstApiHubDiscoveryTool tool = tool(matcher, apiHub, resolutions);
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
    CatalogBindingMatcher matcher = mock(CatalogBindingMatcher.class);
    ApiHubMcpTools apiHub = mock(ApiHubMcpTools.class);
    when(matcher.match(any(), any(), any(), any()))
        .thenReturn(new CatalogBindingMatcher.MatchResult.Exact(petstoreMatch()))
        .thenReturn(new CatalogBindingMatcher.MatchResult.None());
    CatalogFirstApiHubDiscoveryTool tool = tool(matcher, apiHub);

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
    CatalogBindingMatcher matcher = mock(CatalogBindingMatcher.class);
    ApiHubMcpTools apiHub = mock(ApiHubMcpTools.class);
    when(matcher.match(any(), any(), any(), any()))
        .thenReturn(new CatalogBindingMatcher.MatchResult.Exact(petstoreMatch()));
    CatalogFirstApiHubDiscoveryTool tool = tool(matcher, apiHub);

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
    CatalogBindingMatcher matcher = mock(CatalogBindingMatcher.class);
    ApiHubMcpTools apiHub = mock(ApiHubMcpTools.class);
    when(matcher.match(any(), any(), any(), any()))
        .thenReturn(new CatalogBindingMatcher.MatchResult.Exact(petstoreMatch()))
        .thenReturn(new CatalogBindingMatcher.MatchResult.None());
    when(apiHub.searchApiOperations(any(), any(), any(), any(), any(), any()))
        .thenThrow(new IllegalStateException("API Hub MCP timed out"));
    ConversationApiResolutions resolutions = new ConversationApiResolutions();
    CatalogFirstApiHubDiscoveryTool tool = tool(matcher, apiHub, resolutions);

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
    CatalogBindingMatcher matcher = mock(CatalogBindingMatcher.class);
    ApiHubMcpTools apiHub = mock(ApiHubMcpTools.class);
    when(matcher.match(any(), any(), any(), any()))
        .thenReturn(new CatalogBindingMatcher.MatchResult.None());

    try (ToolSession.Handle ignored = ToolSession.open("conv-vague")) {
      tool(matcher, apiHub)
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
            null,
            false,
            List.of(
                serviceCall("call-om-result", "OM", "onTaskResult"),
                serviceCall("call-wfm-create-task", "Salesforce WFM", "createTask")),
            false));
    CatalogBindingMatcher matcher = mock(CatalogBindingMatcher.class);
    ApiHubMcpTools apiHub = mock(ApiHubMcpTools.class);
    CatalogFirstApiHubDiscoveryTool discovery = tool(matcher, apiHub, new ConversationApiResolutions(), store);

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

    assertTrue(result.contains("ERROR"), result);
    assertTrue(result.contains("serviceCallId is required"), result);
    assertTrue(result.contains("call-om-result"), result);
    assertTrue(result.contains("call-wfm-create-task"), result);
    verifyNoInteractions(matcher);
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

  private static CatalogBindingMatcher.CatalogMatch petstoreMatch() {
    return new CatalogBindingMatcher.CatalogMatch(
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
      CatalogBindingMatcher matcher, ApiHubMcpTools apiHub) {
    return tool(matcher, apiHub, new ConversationApiResolutions());
  }

  private static CatalogFirstApiHubDiscoveryTool tool(
      CatalogBindingMatcher matcher, ApiHubMcpTools apiHub, ConversationApiResolutions resolutions) {
    return tool(matcher, apiHub, resolutions, null);
  }

  private static CatalogFirstApiHubDiscoveryTool tool(
      CatalogBindingMatcher matcher,
      ApiHubMcpTools apiHub,
      ConversationApiResolutions resolutions,
      RequirementDraftStore draftStore) {
    CatalogSystemReadTool catalogRead = mock(CatalogSystemReadTool.class);
    when(catalogRead.searchCatalogSystems(any())).thenReturn(List.of());
    when(catalogRead.getApiSpecifications(any())).thenReturn(List.of());
    when(catalogRead.listCatalogOperations(any(), any(), any())).thenReturn(List.of());
    return new CatalogFirstApiHubDiscoveryTool(
        matcher,
        catalogRead,
        mock(ConversationCatalogCache.class),
        apiHub,
        resolutions,
        new ApiHubSearchAuthorizations(),
        new ObjectMapper(),
        draftStore);
  }
}
