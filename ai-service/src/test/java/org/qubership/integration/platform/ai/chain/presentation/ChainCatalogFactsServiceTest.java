package org.qubership.integration.platform.ai.chain.presentation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogElementResponseDto;

class ChainCatalogFactsServiceTest {

  @Test
  void flattenElementsIncludesNestedChildren() {
    CatalogElementResponseDto child = new CatalogElementResponseDto();
    child.id = "el-script";
    child.name = "Parse";
    child.type = "script";
    child.parentElementId = "el-trigger";
    child.properties = Map.of("script", "return body;");

    CatalogElementResponseDto root = new CatalogElementResponseDto();
    root.id = "el-trigger";
    root.name = "HTTP Trigger";
    root.type = "http-trigger";
    root.children = List.of(child);

    List<ChainCatalogElement> flat = ChainCatalogFactsService.flattenElements(List.of(root));

    assertEquals(2, flat.size());
    assertEquals("el-trigger", flat.get(0).elementId());
    assertEquals("el-script", flat.get(1).elementId());
    assertEquals("return body;", flat.get(1).scriptProperties().get("script"));
    assertEquals("return body;", flat.get(1).properties().get("script"));
  }

  @Test
  void flattenElementsCountsAnElementOnceWhenTheCatalogListsItTwice() {
    // GET /v1/chains/{id}/elements returns every element at the top level AND repeats the nested
    // ones inside their container's children array. Counting an element twice puts two nodes with
    // the same id in the imported graph, and a patch then writes that element twice -- the second
    // write carrying the stale copy's values, silently undoing the first.
    CatalogElementResponseDto nested = new CatalogElementResponseDto();
    nested.id = "el-catch";
    nested.name = "Catch";
    nested.type = "catch-2";
    nested.parentElementId = "el-tcf";
    nested.properties = Map.of("priority", 1);

    CatalogElementResponseDto container = new CatalogElementResponseDto();
    container.id = "el-tcf";
    container.name = "Handle failures";
    container.type = "try-catch-finally-2";
    container.children = List.of(nested);

    List<ChainCatalogElement> flat =
        ChainCatalogFactsService.flattenElements(List.of(container, nested));

    assertEquals(2, flat.size());
    assertEquals(
        1, flat.stream().filter(element -> "el-catch".equals(element.elementId())).count());
  }

  @Test
  void flattenElementsRetainsAllNormalizedProperties() {
    CatalogElementResponseDto root = new CatalogElementResponseDto();
    root.id = "el-http";
    root.name = "Call";
    root.type = "service-call";
    root.properties =
        Map.of(
            "integrationSystemId", "svc-1",
            "integrationOperationId", "op-1",
            "uri", "/api/v1",
            "method", "GET");

    List<ChainCatalogElement> flat = ChainCatalogFactsService.flattenElements(List.of(root));

    assertEquals(1, flat.size());
    assertEquals("svc-1", flat.get(0).properties().get("integrationSystemId"));
    assertEquals("/api/v1", flat.get(0).properties().get("uri"));
    assertEquals("GET", flat.get(0).properties().get("method"));
  }

  @Test
  void formatFallbackSummaryMentionsTriggerAndFlow() {
    ChainCatalogFactsService service = new ChainCatalogFactsService(mock(CatalogRestClient.class));

    ChainCatalogFacts facts =
        new ChainCatalogFacts(
            "chain-1",
            "Greetings",
            "",
            2,
            1,
            "HTTP Trigger (http-trigger)",
            List.of(
                new ChainCatalogElement(
                    "el-trigger", "http-trigger", "HTTP Trigger", null, null, null, null, Map.of()),
                new ChainCatalogElement(
                    "el-script", "script", "Parse", null, null, null, null, Map.of())),
            List.of(new ChainCatalogDependency("el-trigger", "el-script")),
            "built_in_catalog");

    String summary = service.formatFallbackSummary(facts);

    assertTrue(summary.contains("Greetings"));
    assertTrue(summary.contains("HTTP Trigger"));
    assertTrue(summary.contains("http-trigger"));
  }
}
