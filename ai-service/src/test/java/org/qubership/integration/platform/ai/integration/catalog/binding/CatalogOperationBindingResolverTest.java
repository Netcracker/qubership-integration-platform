package org.qubership.integration.platform.ai.integration.catalog.binding;

import org.jboss.logmanager.MDC;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chat.ChatMdc;
import org.qubership.integration.platform.ai.integration.catalog.cache.CatalogOperationsReadCache;
import org.qubership.integration.platform.ai.integration.catalog.cache.ConversationCatalogCache;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.model.ChainImplementationPlan.ElementPlan;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CatalogOperationBindingResolverTest {

  @AfterEach
  void clearMdc() {
    MDC.remove(ChatMdc.CONVERSATION_ID);
  }

  @Test
  void enrichServiceCallPropertiesFillsMethodPathAndSystemFromCache() {
    MDC.put(ChatMdc.CONVERSATION_ID, "conv-enrich");
    CatalogOperationsReadCache readCache = mock(CatalogOperationsReadCache.class);
    when(readCache.loadByModelId("model-1"))
        .thenReturn(
            List.of(
                new CatalogRestClient.OperationDto("model-1-addPet", "addPet", "POST", "/pet", null)));
    ConversationCatalogCache cache = new ConversationCatalogCache(readCache);
    cache.rememberSystems(
        "conv-enrich",
        List.of(new CatalogRestClient.SystemDto("sys-1", "Pet store", "IMPLEMENTED", "http")));
    cache.rememberActiveSystemId("conv-enrich", "sys-1");
    cache.rememberSpecifications(
        "conv-enrich",
        List.of(
            new CatalogRestClient.SpecificationDto(
                "model-1", "1.0.0", "spec-group-1", null)));
    cache.getOrLoadOperations("conv-enrich", "model-1", "sys-1");

    CatalogRestClient client = mock(CatalogRestClient.class);
    CatalogOperationBindingResolver resolver =
        new CatalogOperationBindingResolver(cache, client);

    ElementPlan node = new ElementPlan();
    node.setType("service-call");
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("integrationSystemId", "pet-service");
    props.put("integrationSpecificationId", "model-1");
    props.put("integrationOperationId", "model-1-addPet");
    props.put("integrationGqlQueryHeader", "CamelGraphQLQuery");
    props.put("retryCount", 3);
    node.setExpectedProperties(props);

    Map<String, Object> enriched = resolver.enrichForProperties(node).properties();

    assertEquals("sys-1", enriched.get("integrationSystemId"));
    assertEquals("model-1", enriched.get("integrationSpecificationId"));
    assertEquals("model-1-addPet", enriched.get("integrationOperationId"));
    assertEquals("POST", enriched.get("integrationOperationMethod"));
    assertEquals("/pet", enriched.get("integrationOperationPath"));
    assertEquals("http", enriched.get("integrationOperationProtocolType"));
    assertEquals("IMPLEMENTED", enriched.get("systemType"));
    assertEquals("spec-group-1", enriched.get("integrationSpecificationGroupId"));
    assertEquals(3, enriched.get("retryCount"));
    assertFalse(enriched.containsKey("integrationGqlQueryHeader"));
  }

  @Test
  void enrichServiceCallPropertiesLoadsWhenNotCached() {
    MDC.put(ChatMdc.CONVERSATION_ID, "conv-load");
    CatalogRestClient client = mock(CatalogRestClient.class);
    when(client.getOperation("model-2-op"))
        .thenReturn(
            new CatalogRestClient.OperationDto(
                "model-2-op", "placeOrder", "POST", "/store/order", "model-2"));
    ConversationCatalogCache cache =
        new ConversationCatalogCache(mock(CatalogOperationsReadCache.class));
    CatalogOperationBindingResolver resolver =
        new CatalogOperationBindingResolver(cache, client);

    ElementPlan node = new ElementPlan();
    node.setType("service-call");
    node.setExpectedProperties(
        Map.of("integrationSpecificationId", "model-2", "integrationOperationId", "model-2-op"));
    Map<String, Object> enriched = resolver.enrichForProperties(node).properties();

    assertEquals("POST", enriched.get("integrationOperationMethod"));
    assertEquals("/store/order", enriched.get("integrationOperationPath"));
  }

  @Test
  void enrichServiceCallPropertiesFetchesSystemByIdWhenNotCached() {
    MDC.put(ChatMdc.CONVERSATION_ID, "conv-fetch-sys");
    CatalogRestClient client = mock(CatalogRestClient.class);
    when(client.getOperation("model-3-op"))
        .thenReturn(
            new CatalogRestClient.OperationDto("model-3-op", "op", "GET", "/x", "model-3"));
    String systemUuid = "a1b2c3d4-e5f6-4789-abcd-ef1234567890";
    when(client.getSystem(systemUuid))
        .thenReturn(
            new CatalogRestClient.SystemDto(systemUuid, "GQL API", "IMPLEMENTED", "graphql"));
    ConversationCatalogCache cache =
        new ConversationCatalogCache(mock(CatalogOperationsReadCache.class));
    CatalogOperationBindingResolver resolver =
        new CatalogOperationBindingResolver(cache, client);

    ElementPlan node = new ElementPlan();
    node.setType("service-call");
    node.setExpectedProperties(
        Map.of(
            "integrationSystemId",
            systemUuid,
            "integrationSpecificationId",
            "model-3",
            "integrationOperationId",
            "model-3-op"));

    Map<String, Object> enriched = resolver.enrichForProperties(node).properties();

    assertEquals("graphql", enriched.get("integrationOperationProtocolType"));
    assertEquals("IMPLEMENTED", enriched.get("systemType"));
    org.mockito.Mockito.verify(client).getSystem(systemUuid);
  }

  @Test
  void enrichServiceCallPropertiesLoadsSpecificationGroupFromCatalogWhenNotCached() {
    MDC.put(ChatMdc.CONVERSATION_ID, "conv-spec-group");
    CatalogRestClient client = mock(CatalogRestClient.class);
    when(client.getOperation("model-4-op"))
        .thenReturn(
            new CatalogRestClient.OperationDto("model-4-op", "op", "GET", "/x", "model-4"));
    when(client.getModel("model-4"))
        .thenReturn(
            new CatalogRestClient.SpecificationDto("model-4", "1.0.7", "petstore-spec-group", null));
    ConversationCatalogCache cache =
        new ConversationCatalogCache(mock(CatalogOperationsReadCache.class));
    CatalogOperationBindingResolver resolver =
        new CatalogOperationBindingResolver(cache, client);

    ElementPlan node = new ElementPlan();
    node.setType("service-call");
    node.setExpectedProperties(
        Map.of(
            "integrationSpecificationId", "model-4", "integrationOperationId", "model-4-op"));

    Map<String, Object> enriched = resolver.enrichForProperties(node).properties();

    assertEquals("petstore-spec-group", enriched.get("integrationSpecificationGroupId"));
    org.mockito.Mockito.verify(client).getModel("model-4");
  }

  @Test
  void enrichServiceCallPropertiesPrefersSpecificationOwnerSystem() {
    MDC.put(ChatMdc.CONVERSATION_ID, "conv-spec-owner");
    CatalogRestClient client = mock(CatalogRestClient.class);
    String externalId = "a1b2c3d4-e5f6-4789-abcd-ef1234567890";
    String implementedId = "b2c3d4e5-f6a7-4890-bcde-f12345678901";
    when(client.getOperation("model-s-op"))
        .thenReturn(
            new CatalogRestClient.OperationDto("model-s-op", "getPetById", "GET", "/pet", "model-s"));
    when(client.getSystem(implementedId))
        .thenReturn(
            new CatalogRestClient.SystemDto(implementedId, "PetStore", "IMPLEMENTED", "http"));
    ConversationCatalogCache cache =
        new ConversationCatalogCache(mock(CatalogOperationsReadCache.class));
    cache.rememberSystems(
        "conv-spec-owner",
        List.of(
            new CatalogRestClient.SystemDto(externalId, "PetStore Ext", "EXTERNAL", "http"),
            new CatalogRestClient.SystemDto(implementedId, "PetStore Impl", "IMPLEMENTED", "http")));
    cache.rememberSpecificationsForSystem(
        "conv-spec-owner",
        implementedId,
        List.of(new CatalogRestClient.SpecificationDto("model-s", "1.0.7", "group-1", null)));
    CatalogOperationBindingResolver resolver =
        new CatalogOperationBindingResolver(cache, client);

    ElementPlan node = new ElementPlan();
    node.setType("service-call");
    node.setExpectedProperties(
        Map.of(
            "integrationSystemId",
            externalId,
            "integrationSpecificationId",
            "model-s",
            "integrationOperationId",
            "model-s-op"));

    Map<String, Object> enriched = resolver.enrichForProperties(node).properties();

    assertEquals(implementedId, enriched.get("integrationSystemId"));
    assertEquals("IMPLEMENTED", enriched.get("systemType"));
  }

  @Test
  void enrichServiceCallPropertiesResolvesByOperationIdOnly() {
    MDC.put(ChatMdc.CONVERSATION_ID, "conv-op-only");
    CatalogRestClient client = mock(CatalogRestClient.class);
    String systemUuid = "c7b258ed-2454-45b6-a548-e294e923ae50";
    when(client.getOperation("model-only-op"))
        .thenReturn(
            new CatalogRestClient.OperationDto(
                "model-only-op", "getPetById", "GET", "/pet/{petId}", "model-only"));
    when(client.getModel("model-only"))
        .thenReturn(
            new CatalogRestClient.SpecificationDto(
                "model-only", "1.0.0", "spec-group-only", systemUuid));
    when(client.getSystem(systemUuid))
        .thenReturn(
            new CatalogRestClient.SystemDto(systemUuid, "PetStore", "IMPLEMENTED", "http"));
    ConversationCatalogCache cache =
        new ConversationCatalogCache(mock(CatalogOperationsReadCache.class));
    CatalogOperationBindingResolver resolver =
        new CatalogOperationBindingResolver(cache, client);

    ElementPlan node = new ElementPlan();
    node.setType("service-call");
    node.setExpectedProperties(Map.of("integrationOperationId", "model-only-op"));

    Map<String, Object> enriched = resolver.enrichForProperties(node).properties();

    assertEquals("model-only", enriched.get("integrationSpecificationId"));
    assertEquals("spec-group-only", enriched.get("integrationSpecificationGroupId"));
    assertEquals(systemUuid, enriched.get("integrationSystemId"));
    assertEquals("GET", enriched.get("integrationOperationMethod"));
    assertEquals("/pet/{petId}", enriched.get("integrationOperationPath"));
    org.mockito.Mockito.verify(client).getOperation("model-only-op");
    org.mockito.Mockito.verify(client, org.mockito.Mockito.never())
        .getOperations(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.isNull());
  }

  @Test
  void enrichServiceCallPropertiesPrefersCatalogModelIdOverStalePlanSpecId() {
    MDC.put(ChatMdc.CONVERSATION_ID, "conv-stale-spec");
    CatalogRestClient client = mock(CatalogRestClient.class);
    when(client.getOperation("model-correct-op"))
        .thenReturn(
            new CatalogRestClient.OperationDto(
                "model-correct-op", "addPet", "POST", "/pet", "model-correct"));
    when(client.getModel("model-correct"))
        .thenReturn(
            new CatalogRestClient.SpecificationDto(
                "model-correct", "1.0", "group-c", "sys-c"));
    when(client.getSystem("sys-c"))
        .thenReturn(new CatalogRestClient.SystemDto("sys-c", "S", "IMPLEMENTED", "http"));
    ConversationCatalogCache cache =
        new ConversationCatalogCache(mock(CatalogOperationsReadCache.class));
    CatalogOperationBindingResolver resolver =
        new CatalogOperationBindingResolver(cache, client);

    ElementPlan node = new ElementPlan();
    node.setType("service-call");
    node.setExpectedProperties(
        Map.of(
            "integrationSpecificationId",
            "model-wrong",
            "integrationOperationId",
            "model-correct-op"));

    Map<String, Object> enriched = resolver.enrichForProperties(node).properties();

    assertEquals("model-correct", enriched.get("integrationSpecificationId"));
  }

  @Test
  void enrichForPropertiesUnknownOperationIdReturnsUnresolvedReason() {
    ConversationCatalogCache cache =
        new ConversationCatalogCache(mock(CatalogOperationsReadCache.class));
    CatalogRestClient client = mock(CatalogRestClient.class);
    CatalogOperationBindingResolver resolver =
        new CatalogOperationBindingResolver(cache, client);

    ElementPlan node = new ElementPlan();
    node.setType("service-call");
    node.setExpectedProperties(
        Map.of(
            "integrationSpecificationId",
            "model-x",
            "integrationOperationId",
            "operation-id-placeholder"));

    CatalogOperationBindingEnrichResult result = resolver.enrichForProperties(node);

    assertTrue(result.unresolvedReason().isPresent());
    assertTrue(
        result.unresolvedReason().get().contains("placeholder"), result.unresolvedReason().get());
  }
}
