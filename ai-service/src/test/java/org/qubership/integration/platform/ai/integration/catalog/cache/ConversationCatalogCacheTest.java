package org.qubership.integration.platform.ai.integration.catalog.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chat.ToolSession;
import org.qubership.integration.platform.ai.chat.UserTokenStore;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;

class ConversationCatalogCacheTest {

  private static final String CONVERSATION = "conv-caller-switch";
  private static final String MODEL_ID = "petstore-1.0.0";

  private final AtomicInteger catalogLoads = new AtomicInteger();
  private final ConversationCatalogCache cache =
      new ConversationCatalogCache(
          new CatalogOperationsReadCache(null) {
            @Override
            public List<CatalogRestClient.OperationDto> loadByModelId(String modelId) {
              catalogLoads.incrementAndGet();
              return List.of(
                  new CatalogRestClient.OperationDto(
                      MODEL_ID + "-getPet", "getPet", "GET", "/pet", MODEL_ID));
            }
          });

  @AfterEach
  void unbind() {
    ToolSession.clear();
    UserTokenStore.clear(CONVERSATION);
  }

  @Test
  void aSameSubjectKeepsTheConversationsOperations() {
    lookupAs("alice");
    lookupAs("alice");

    assertEquals(1, catalogLoads.get());
  }

  @Test
  void aDifferentSubjectDropsTheConversationsOperations() {
    lookupAs("alice");
    lookupAs("bob");

    assertEquals(2, catalogLoads.get());
  }

  @Test
  void aCallerWithoutTokenDropsOperationsLoadedUnderASubject() {
    lookupAs("alice");
    ToolSession.bind(CONVERSATION);
    try {
      cache.getOrLoadOperations(CONVERSATION, MODEL_ID, null);
    } finally {
      ToolSession.clear();
    }

    assertEquals(2, catalogLoads.get());
  }

  private void lookupAs(String subject) {
    UserTokenStore.Hold hold = UserTokenStore.acquire(CONVERSATION, jwt(subject));
    try {
      ToolSession.bind(CONVERSATION);
      cache.getOrLoadOperations(CONVERSATION, MODEL_ID, null);
    } finally {
      ToolSession.clear();
      hold.release();
    }
  }

  private static String jwt(String subject) {
    String payload = "{\"sub\":\"" + subject + "\"}";
    return "h."
        + Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8))
        + ".s";
  }
}
