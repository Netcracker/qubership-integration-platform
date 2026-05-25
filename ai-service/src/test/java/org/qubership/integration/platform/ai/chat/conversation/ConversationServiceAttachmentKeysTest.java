package org.qubership.integration.platform.ai.chat.conversation;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.model.ScenarioType;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class ConversationServiceAttachmentKeysTest {

  @Inject ConversationService conversationService;

  @Test
  void getAllowedAttachmentKeysReturnsRegisteredSafeKeys() {
    String conversationId = UUID.randomUUID().toString();
    conversationService.getOrCreate(conversationId, ScenarioType.CREATE_DESIGN);
    conversationService.registerAllowedAttachmentKeys(
        conversationId, List.of("z/key.md", "a/key.md", "../evil"));
    List<String> allowed = conversationService.getAllowedAttachmentKeys(conversationId);
    assertTrue(allowed.contains("a/key.md"));
    assertTrue(allowed.contains("z/key.md"));
    assertFalse(allowed.contains("../evil"));
    assertEquals(List.of("a/key.md", "z/key.md"), allowed);
  }

  @Test
  void materializedKeysRejectPathTraversalSegments() {
    String conversationId = UUID.randomUUID().toString();
    conversationService.getOrCreate(conversationId, ScenarioType.CREATE_DESIGN);
    conversationService.addMaterializedAttachmentKeys(
        conversationId, List.of("safe/key.txt", "../evil", "a/../b", "ok/nested/file"));
    assertTrue(conversationService.isAttachmentKeyMaterialized(conversationId, "safe/key.txt"));
    assertTrue(conversationService.isAttachmentKeyMaterialized(conversationId, "ok/nested/file"));
    assertFalse(conversationService.isAttachmentKeyMaterialized(conversationId, "../evil"));
    assertFalse(conversationService.isAttachmentKeyMaterialized(conversationId, "a/../b"));
  }
}
