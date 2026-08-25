package org.qubership.integration.platform.ai.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
    when(matcher.match(any(), any()))
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
                "The chain calls Petstore Ext to read stock levels",
                "Petstore Ext",
                "",
                "GET",
                "/store/inventory",
                "2024.4");

    assertTrue(result.contains("CATALOG_BOUND"), result);
    assertTrue(result.contains("operation-1"), result);
    verify(apiHub, never()).searchApiOperations(any(), any(), any(), any(), any(), any());
  }

  @Test
  void catalogMissUsesApiHubDiscovery() {
    CatalogBindingMatcher matcher = mock(CatalogBindingMatcher.class);
    ApiHubMcpTools apiHub = mock(ApiHubMcpTools.class);
    when(matcher.match(any(), any())).thenReturn(new CatalogBindingMatcher.MatchResult.None());
    when(apiHub.searchApiOperations(eq("getInventory"), eq("rest"), eq("2024.4"), eq(0), eq(100), eq(null)))
        .thenReturn("{\"hits\":[\"candidate\"]}");

    String result = tool(matcher, apiHub)
            .resolveApiOperation(
                "The chain calls Petstore to read stock levels",
                "Petstore",
                "getInventory",
                "",
                "",
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
                "The chain reads stock levels from somewhere", "Petstore", "", "", "", "");

    assertTrue(result.contains("INCOMPLETE"), result);
    assertTrue(result.contains("operationHint"), result);
    verifyNoInteractions(apiHub);
    verifyNoInteractions(matcher);
  }

  @Test
  void everyServiceCallKeepsItsOwnAssessment() {
    CatalogBindingMatcher matcher = mock(CatalogBindingMatcher.class);
    ApiHubMcpTools apiHub = mock(ApiHubMcpTools.class);
    when(matcher.match(any(), any()))
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
          "Read stock levels from Petstore Ext", "Petstore Ext", "", "GET", "/store/inventory", "");
      tool.resolveApiOperation(
          "Raise an invoice in Billing", "Billing", "createInvoice", "", "", "");
    }

    String conversationId = "conv-assessments";
    List<ServiceCallAssessment> assessments = resolutions.assessments(conversationId);
    assertEquals(2, assessments.size());
    assertEquals(ServiceCallAssessment.Outcome.RESOLVED, assessments.get(0).outcome());
    assertEquals(ServiceCallAssessment.Outcome.CATALOG_MISS, assessments.get(1).outcome());
    assertEquals(
        "operation-1",
        resolutions
            .forFact(
                conversationId,
                RequirementFact.deriveSourceFactId(
                    RequirementFactPolarity.POSITIVE, "Read stock levels from Petstore Ext"))
            .orElseThrow()
            .binding()
            .integrationOperationId());
  }

  private static CatalogFirstApiHubDiscoveryTool tool(
      CatalogBindingMatcher matcher, ApiHubMcpTools apiHub) {
    return tool(matcher, apiHub, new ConversationApiResolutions());
  }

  private static CatalogFirstApiHubDiscoveryTool tool(
      CatalogBindingMatcher matcher, ApiHubMcpTools apiHub, ConversationApiResolutions resolutions) {
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
        new ObjectMapper());
  }
}
