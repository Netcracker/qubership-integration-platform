package org.qubership.integration.platform.ai.integration.catalog.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.jboss.logmanager.MDC;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chat.ChatMdc;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;

class CatalogOperationsLookupServiceTest {

  private static final String CONVERSATION = "conv-ops-1";
  private static final String MODEL_ID = "spec-om";
  private static final String SYSTEM_ID = "sys-om";

  @AfterEach
  void clearMdc() {
    MDC.remove(ChatMdc.CONVERSATION_ID);
  }

  @Test
  void listOperationsByConversationIdHitsCacheWhenMdcIsEmpty() {
    CatalogOperationsLookupService lookup = new CatalogOperationsLookupService(cacheReturning());
    MDC.remove(ChatMdc.CONVERSATION_ID);

    assertTrue(
        lookup.listOperations(MODEL_ID, SYSTEM_ID).isEmpty(),
        "MDC-only list must not invent a conversation id");

    List<CatalogRestClient.OperationDto> listed =
        lookup.listOperations(CONVERSATION, MODEL_ID, SYSTEM_ID);

    assertEquals(1, listed.size());
    assertEquals("op-om", listed.getFirst().id());
  }

  private static ConversationCatalogCache cacheReturning() {
    return new ConversationCatalogCache(
        new CatalogOperationsReadCache(null) {
          @Override
          public List<CatalogRestClient.OperationDto> loadByModelId(String modelId) {
            return List.of(
                new CatalogRestClient.OperationDto(
                    "op-om", "onTaskStart", "subscribe", null, MODEL_ID));
          }
        });
  }
}
