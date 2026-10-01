package org.qubership.integration.platform.ai.integration.catalog.tool;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.compiler.CompilerSkillMemoryIds;
import org.qubership.integration.platform.ai.integration.catalog.cache.CatalogOperationsLookupService;
import org.qubership.integration.platform.ai.integration.catalog.cache.CatalogOperationsReadCache;
import org.qubership.integration.platform.ai.integration.catalog.cache.ConversationCatalogCache;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;

class CatalogSystemToolContextTest {

  @Test
  void workerToolsPreserveCatalogContextForChatAndCompilerMemoryIds() throws Exception {
    CatalogRestClient client = mock(CatalogRestClient.class);
    List<CatalogRestClient.OperationDto> operations = List.of(
        new CatalogRestClient.OperationDto("op-create", "Create Work Order", "POST", "/workOrder", "spec-sf"),
        new CatalogRestClient.OperationDto("op-update", "Update Work Order", "PATCH", "/workOrder/{id}", "spec-sf"));
    ConversationCatalogCache cache = new ConversationCatalogCache(new CatalogOperationsReadCache(null) {
      @Override
      public List<CatalogRestClient.OperationDto> loadByModelId(String modelId) {
        return operations;
      }
    });
    CatalogToolSupport support = new CatalogToolSupport();
    support.objectMapper = new ObjectMapper();
    CatalogSystemTools tools = new CatalogSystemTools(
        new CatalogSystemReadTool(client, new CatalogOperationsLookupService(cache), support), support);
    for (String memoryId : List.of("chat-1", CompilerSkillMemoryIds.forSkill("chat-1", "chain-generator"))) {
      String json;
      try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
        json = executor.submit(() -> tools.listCatalogOperations("spec-sf", "sys-sf", null, memoryId)).get();
      }
      var result = support.objectMapper.readTree(json);
      assertTrue(result.get("ok").asBoolean(), json);
      assertEquals(2, result.get("data").size());
      assertEquals(new java.util.HashSet<>(operations),
          new java.util.HashSet<>(cache.rememberedOperations("chat-1")));
    }
    String missing = tools.listCatalogOperations("spec-sf", "sys-sf", null, null);
    assertFalse(support.objectMapper.readTree(missing).get("ok").asBoolean());
    assertTrue(missing.contains("CONVERSATION_CONTEXT_REQUIRED"), missing);
    assertThrows(IllegalStateException.class, () -> cache.refreshOperations(null, "spec-sf", "sys-sf"));
    assertEquals("chat-1", CompilerSkillMemoryIds.conversationId(
        CompilerSkillMemoryIds.forSkill("chat-1", "chain-generator")));
  }
}
