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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CatalogOperationBindingResolverHttpAsyncTest {

  @AfterEach
  void clearMdc() {
    MDC.remove(ChatMdc.CONVERSATION_ID);
  }

  @Test
  void enrichHttpTriggerImplementedSetsHttpMethodRestrict() {
    MDC.put(ChatMdc.CONVERSATION_ID, "conv-http");
    CatalogRestClient client = mock(CatalogRestClient.class);
    when(client.getOperation("model-h-op"))
        .thenReturn(
            new CatalogRestClient.OperationDto("model-h-op", "triggerOp", "POST", "/hook", "model-h"));
    ConversationCatalogCache cache =
        new ConversationCatalogCache(mock(CatalogOperationsReadCache.class));
    CatalogOperationBindingResolver resolver =
        new CatalogOperationBindingResolver(cache, client);

    ElementPlan node = new ElementPlan();
    node.setType("http-trigger");
    node.setExpectedProperties(
        Map.of("integrationSpecificationId", "model-h", "integrationOperationId", "model-h-op"));

    Map<String, Object> enriched = resolver.enrichForProperties(node).properties();

    assertEquals("POST", enriched.get("httpMethodRestrict"));
    assertEquals("/hook", enriched.get("integrationOperationPath"));
    assertFalse(enriched.containsKey("integrationOperationMethod"));
  }

  @Test
  void enrichUserAcceptedUnboundWithRealOperationIdStillEnriches() {
    MDC.put(ChatMdc.CONVERSATION_ID, "conv-unbound");
    CatalogOperationsReadCache readCache = mock(CatalogOperationsReadCache.class);
    when(readCache.loadByModelId("model-u"))
        .thenReturn(List.of(new CatalogRestClient.OperationDto("model-u-op", "op", "GET", "/x", null)));
    ConversationCatalogCache cache = new ConversationCatalogCache(readCache);
    cache.rememberSystems(
        "conv-unbound",
        List.of(new CatalogRestClient.SystemDto("sys-u", "S", "IMPLEMENTED", "http")));
    cache.getOrLoadOperations("conv-unbound", "model-u", "sys-u");

    CatalogOperationBindingResolver resolver =
        new CatalogOperationBindingResolver(cache, mock(CatalogRestClient.class));

    ElementPlan node = new ElementPlan();
    node.setType("service-call");
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("integrationSpecificationId", "model-u");
    props.put("integrationOperationId", "model-u-op");
    node.setBindingStatus("user_accepted_unbound");
    node.setExpectedProperties(props);

    Map<String, Object> enriched = resolver.enrichForProperties(node).properties();

    assertEquals("GET", enriched.get("integrationOperationMethod"));
  }
}
