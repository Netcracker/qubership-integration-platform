package org.qubership.integration.platform.ai.plan;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.integration.apihub.ApiHubMcpTools;
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

    String result = tool(matcher, apiHub).resolveApiOperation("Petstore Ext", "GET /store/inventory", "2024.4");

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

    String result = tool(matcher, apiHub).resolveApiOperation("Petstore", "getInventory", "2024.4");

    assertTrue(result.contains("candidate"), result);
    verify(apiHub).searchApiOperations("getInventory", "rest", "2024.4", 0, 100, null);
  }

  private static CatalogFirstApiHubDiscoveryTool tool(
      CatalogBindingMatcher matcher, ApiHubMcpTools apiHub) {
    CatalogSystemReadTool catalogRead = mock(CatalogSystemReadTool.class);
    when(catalogRead.searchCatalogSystems(any())).thenReturn(List.of());
    when(catalogRead.getApiSpecifications(any())).thenReturn(List.of());
    when(catalogRead.listCatalogOperations(any(), any(), any())).thenReturn(List.of());
    return new CatalogFirstApiHubDiscoveryTool(
        matcher,
        catalogRead,
        mock(ConversationCatalogCache.class),
        apiHub,
        new ObjectMapper());
  }
}
