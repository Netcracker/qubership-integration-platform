package org.qubership.integration.platform.ai.integration.catalog.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Field;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.integration.catalog.cache.CatalogOperationsLookupService;
import org.qubership.integration.platform.ai.integration.catalog.cache.ConversationCatalogCache;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;

class CatalogSystemReadToolBoundOperationTest {

  private CatalogRestClient catalogRestClient;
  private CatalogSystemReadTool readTool;

  @BeforeEach
  void setUp() throws Exception {
    catalogRestClient = mock(CatalogRestClient.class);
    CatalogToolSupport support = new CatalogToolSupport();
    Field mapperField = CatalogToolSupport.class.getDeclaredField("objectMapper");
    mapperField.setAccessible(true);
    mapperField.set(support, new ObjectMapper());
    ConversationCatalogCache cache = new ConversationCatalogCache(null);
    readTool =
        new CatalogSystemReadTool(
            catalogRestClient, new CatalogOperationsLookupService(cache), support);
  }

  @Test
  void describeBoundOperationDecodesPercentEncodedIdsBeforeLookup() {
    CatalogRestClient.OperationDto listed =
        new CatalogRestClient.OperationDto(
            "WFMS Create Work Order",
            "WFMS Create Work Order",
            "POST",
            "/workOrder",
            "spec-1");
    when(catalogRestClient.getOperation("WFMS Create Work Order")).thenReturn(listed);

    String out = readTool.describeBoundOperationJson("WFMS%20Create%20Work%20Order");

    assertTrue(out.contains("\"ok\":true"), out);
    assertTrue(out.contains("WFMS Create Work Order"), out);
    verify(catalogRestClient).getOperation("WFMS Create Work Order");
  }

  @Test
  void searchJsonTellsTheModelItDidNotBindAnInteraction() {
    when(catalogRestClient.searchSystems(org.mockito.ArgumentMatchers.any()))
        .thenReturn(
            List.of(new CatalogRestClient.SystemDto("sys-1", "Petstore Ext", "EXTERNAL", "http")));

    String json = readTool.searchCatalogSystemsJson("Petstore Ext");

    assertTrue(json.contains("resolveApiOperation"), json);
    assertTrue(json.contains("interactionId"), json);
    assertTrue(json.contains("does not bind an interaction"), json);
  }

  @Test
  void describeBoundOperationKeepsAnAlreadyDecodedId() {
    CatalogRestClient.OperationDto listed =
        new CatalogRestClient.OperationDto("op-1", "getInventory", "GET", "/store/inventory", "spec-1");
    when(catalogRestClient.getOperation("op-1")).thenReturn(listed);

    String out = readTool.describeBoundOperationJson("op-1");

    assertTrue(out.contains("\"ok\":true"), out);
    assertEquals("op-1", listed.id());
    verify(catalogRestClient).getOperation("op-1");
  }
}
