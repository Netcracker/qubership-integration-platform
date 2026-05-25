package org.qubership.integration.platform.ai.integration.catalog.cache;

import org.jboss.logmanager.MDC;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chat.ChatMdc;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CatalogOperationsLookupServiceTest {

  @AfterEach
  void clearMdc() {
    MDC.remove(ChatMdc.CONVERSATION_ID);
  }

  @Test
  void findOperationsWithFilterUsesSingleCatalogLoad() {
    MDC.put(ChatMdc.CONVERSATION_ID, "conv-filter");
    CatalogOperationsReadCache readCache = mock(CatalogOperationsReadCache.class);
    ConversationCatalogCache cache = new ConversationCatalogCache(readCache);
    List<CatalogRestClient.OperationDto> ops =
        List.of(
            new CatalogRestClient.OperationDto("id-1", "addPet", "POST", "/pet", null),
            new CatalogRestClient.OperationDto("id-2", "getPetById", "GET", "/pet/{id}", null));
    when(readCache.loadByModelId("model-x")).thenReturn(ops);

    CatalogOperationsLookupService service = new CatalogOperationsLookupService(cache);

    List<CatalogRestClient.OperationDto> first =
        service.findOperations("model-x", "sys-1", "addPet");
    List<CatalogRestClient.OperationDto> second =
        service.findOperations("model-x", "sys-1", "getPet");

    assertEquals(1, first.size());
    assertEquals("addPet", first.get(0).name());
    assertEquals(1, second.size());
    verify(readCache, times(1)).loadByModelId("model-x");
  }

  @Test
  void listOperationsLoadsMultiplePagesUntilShortPage() {
    MDC.put(ChatMdc.CONVERSATION_ID, "conv-pages");
    CatalogRestClient client = mock(CatalogRestClient.class);
    CatalogOperationsReadCache readCache = new CatalogOperationsReadCache(client);
    ConversationCatalogCache cache = new ConversationCatalogCache(readCache);
    String modelId = "model-pages-swagger-1.0";
    List<CatalogRestClient.OperationDto> page1 =
        java.util.stream.IntStream.range(0, 500)
            .mapToObj(i -> new CatalogRestClient.OperationDto("id-" + i, "n" + i, "GET", "/p" + i, null))
            .toList();
    List<CatalogRestClient.OperationDto> page2 =
        List.of(new CatalogRestClient.OperationDto("id-500", "last", "POST", "/last", null));
    when(client.getOperations(modelId, 0, 500, null)).thenReturn(page1);
    when(client.getOperations(modelId, 500, 500, null)).thenReturn(page2);

    CatalogOperationsLookupService service = new CatalogOperationsLookupService(cache);

    List<CatalogRestClient.OperationDto> loaded = service.listOperations(modelId, null);

    assertEquals(501, loaded.size());
    verify(client, times(1)).getOperations(modelId, 0, 500, null);
    verify(client, times(1)).getOperations(modelId, 500, 500, null);
  }
}
