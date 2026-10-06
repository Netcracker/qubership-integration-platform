package org.qubership.integration.platform.ai.integration.catalog.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Field;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.qubership.integration.platform.ai.chat.ToolSession;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.integration.catalog.cache.CatalogOperationsLookupService;
import org.qubership.integration.platform.ai.integration.catalog.cache.ConversationCatalogCache;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;

class CatalogSystemReadToolBoundOperationTest {

  private CatalogRestClient catalogRestClient;
  private CatalogSystemReadTool readTool;

  @Test
  void operationListingOmitsSchemasAndKeepsCompleteTypedOperations() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    CatalogOperationsLookupService lookup = mock(CatalogOperationsLookupService.class);
    CatalogToolSupport support = new CatalogToolSupport();
    support.objectMapper = mapper;
    CatalogSystemReadTool tool = new CatalogSystemReadTool(catalogRestClient, lookup, support);
    var schema = mapper.createObjectNode().put("largeDefinition", "x".repeat(300_000));
    var operations = java.util.stream.IntStream.range(0, 226)
        .mapToObj(i -> new CatalogRestClient.OperationDto(
            "op-" + i, "Operation " + i, "POST", "/tasks/" + i, "spec-1", schema))
        .toList();
    when(lookup.validateSpecificationIdForOperations("chat", "spec-1"))
        .thenReturn(java.util.Optional.empty());
    when(lookup.listOperations("chat", "spec-1", "sys-1")).thenReturn(operations);

    var json = mapper.readTree(tool.listCatalogOperationsJson("chat", "spec-1", "sys-1", null));

    assertEquals(226, json.path("data").size());
    assertTrue(json.path("complete").asBoolean());
    assertEquals("op-225", json.path("data").get(225).path("id").asText());
    assertFalse(json.path("data").get(0).has("specification"));
    assertEquals(schema, tool.listCatalogOperations("chat", "spec-1", "sys-1", null)
        .get(225).specification());
  }

  @Test
  void boundedListingReportsContinuationAndRejectsOversizedMetadata() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    CatalogOperationsLookupService lookup = mock(CatalogOperationsLookupService.class);
    CatalogToolSupport support = new CatalogToolSupport();
    support.objectMapper = mapper;
    CatalogSystemReadTool tool = new CatalogSystemReadTool(catalogRestClient, lookup, support);
    var operations = java.util.stream.IntStream.range(0, 10)
        .mapToObj(i -> new CatalogRestClient.OperationDto(
            "op-" + i, "x".repeat(20_000), "POST", "/tasks", "spec-1"))
        .toList();
    when(lookup.validateSpecificationIdForOperations("chat", "spec-1"))
        .thenReturn(java.util.Optional.empty());
    when(lookup.listOperations("chat", "spec-1", "sys-1")).thenReturn(operations);

    String first = tool.listCatalogOperationsJson("chat", "spec-1", "sys-1", null);
    var page = mapper.readTree(first);
    assertFalse(page.path("complete").asBoolean());
    assertTrue(first.length() <= CatalogSystemReadTool.MAX_OPERATION_LIST_CHARS);
    int next = page.path("nextOffset").asInt();
    var second = mapper.readTree(tool.listCatalogOperationsJson("chat", "spec-1", "sys-1", null, next));
    assertEquals("op-" + next, second.path("data").get(0).path("id").asText());
    assertEquals(10, tool.listCatalogOperations("chat", "spec-1", "sys-1", null).size());

    when(lookup.listOperations("chat", "spec-1", "sys-1")).thenReturn(List.of(
        new CatalogRestClient.OperationDto("large", "x".repeat(100_000), "POST", "/tasks", "spec-1")));
    var rejected = mapper.readTree(tool.listCatalogOperationsJson("chat", "spec-1", "sys-1", null));
    assertFalse(rejected.path("ok").asBoolean());
    assertEquals("CATALOG_METADATA_TOO_LARGE", rejected.path("error").path("code").asText());
  }

  @BeforeEach
  void setUp() throws Exception {
    ToolSession.bind("bound-operation-chat");
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

  @AfterEach
  void clearContext() {
    ToolSession.clear();
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
