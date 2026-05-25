package org.qubership.integration.platform.ai.integration.catalog.cache;

import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationCatalogCacheTest {

  @Test
  void getOrLoadOperationsLoadsOncePerModelIdPerConversation() {
    CatalogOperationsReadCache readCache = mock(CatalogOperationsReadCache.class);
    ConversationCatalogCache cache = new ConversationCatalogCache(readCache);
    List<CatalogRestClient.OperationDto> op =
        List.of(new CatalogRestClient.OperationDto("op-1", "getPet", "GET", "/pet/{id}", null));
    when(readCache.loadByModelId("model-1")).thenReturn(op);

    List<CatalogRestClient.OperationDto> first =
        cache.getOrLoadOperations("conv-1", "model-1", "sys-1");
    List<CatalogRestClient.OperationDto> second =
        cache.getOrLoadOperations("conv-1", "model-1", "sys-1");

    verify(readCache, times(1)).loadByModelId("model-1");
    assertEquals(1, first.size());
    assertEquals(first, second);
    assertTrue(cache.findOperation("conv-1", "op-1").isPresent());
  }

  @Test
  void refreshOperationsReplacesEmptyModelCacheAndIndexesOperations() {
    CatalogOperationsReadCache readCache = mock(CatalogOperationsReadCache.class);
    ConversationCatalogCache cache = new ConversationCatalogCache(readCache);
    when(readCache.loadByModelId("model-1")).thenReturn(List.of());
    when(readCache.loadByModelIdUncached("model-1"))
        .thenReturn(
            List.of(
                new CatalogRestClient.OperationDto(
                    "model-1-getPetById", "getPetById", "GET", "/pet/{petId}", null)));

    cache.getOrLoadOperations("conv-1", "model-1", "sys-1");
    assertTrue(cache.findOperation("conv-1", "model-1-getPetById").isEmpty());

    cache.refreshOperations("conv-1", "model-1", "sys-1");

    verify(readCache).loadByModelIdUncached("model-1");
    assertTrue(cache.findOperation("conv-1", "model-1-getPetById").isPresent());
    assertEquals("GET", cache.findOperation("conv-1", "model-1-getPetById").orElseThrow().method());
  }

  @Test
  void rememberSpecificationsForSystemTracksOwner() {
    ConversationCatalogCache cache =
        new ConversationCatalogCache(mock(CatalogOperationsReadCache.class));
    cache.rememberSpecificationsForSystem(
        "conv-1",
        "sys-impl",
        List.of(
            new CatalogRestClient.SpecificationDto(
                "model-1", "1.0.7", "spec-group-1", null)));

    assertEquals("sys-impl", cache.findSpecificationOwnerSystemId("conv-1", "model-1").orElseThrow());
  }

  @Test
  void filterOperationsMatchesNamePathOrMethod() {
    ConversationCatalogCache cache =
        new ConversationCatalogCache(mock(CatalogOperationsReadCache.class));
    List<CatalogRestClient.OperationDto> ops =
        List.of(
            new CatalogRestClient.OperationDto("id-a", "addPet", "POST", "/pet", null),
            new CatalogRestClient.OperationDto("id-b", "getPetById", "GET", "/pet/{petId}", null));

    List<CatalogRestClient.OperationDto> filtered = cache.filterOperations(ops, "getPet");

    assertEquals(1, filtered.size());
    assertEquals("getPetById", filtered.get(0).name());
  }
}
