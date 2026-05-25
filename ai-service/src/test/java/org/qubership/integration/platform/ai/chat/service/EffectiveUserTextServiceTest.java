package org.qubership.integration.platform.ai.chat.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chat.conversation.ConversationService;
import org.qubership.integration.platform.ai.chat.model.ChatRequest;
import org.qubership.integration.platform.ai.configuration.AppConfig;
import org.qubership.integration.platform.ai.model.ScenarioType;
import org.qubership.integration.platform.ai.storage.S3Service;

import java.lang.reflect.Field;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EffectiveUserTextServiceTest {

  private static final String OBJECT_KEY = "conv-uuid/design.md";
  private static final String IDS_BODY =
      "# Integration Design Specification (IDS)\n\n**Document ID:** QIP.INT.IDS.Test\n";

  private S3Service s3Service;
  private ConversationService conversationService;
  private EffectiveUserTextService service;

  @BeforeEach
  void setUp() throws Exception {
    s3Service = mock(S3Service.class);
    when(s3Service.readObjectUtf8(OBJECT_KEY)).thenReturn(IDS_BODY);

    conversationService = new ConversationService();
    AppConfig config = mock(AppConfig.class);
    AppConfig.ConversationConfig convConfig = mock(AppConfig.ConversationConfig.class);
    when(config.conversation()).thenReturn(convConfig);
    when(convConfig.maxMessages()).thenReturn(100);
    inject(conversationService, "config", config);

    service = new EffectiveUserTextService();
    inject(service, "s3Service", s3Service);
    inject(service, "conversationService", conversationService);
  }

  @Test
  void firstTurnWithKeyInlinesAttachmentBody() {
    String conversationId = UUID.randomUUID().toString();
    conversationService.getOrCreate(conversationId, ScenarioType.CREATE_CHAIN_PLAN);

    ChatRequest request = new ChatRequest();
    request.setMessage("Create chain from design");
    request.setAttachmentObjectKeys(List.of(OBJECT_KEY));

    String effective = service.resolve(request, conversationId);

    assertTrue(effective.contains("Create chain from design"));
    assertTrue(effective.contains("(inlined)"));
    assertTrue(effective.contains("QIP.INT.IDS.Test"));
    assertTrue(conversationService.isAttachmentKeyMaterialized(conversationId, OBJECT_KEY));
  }

  @Test
  void secondTurnSameKeyOnRequestReinlinesDespiteMaterialized() {
    String conversationId = UUID.randomUUID().toString();
    conversationService.getOrCreate(conversationId, ScenarioType.CREATE_CHAIN_PLAN);
    conversationService.addMaterializedAttachmentKeys(conversationId, List.of(OBJECT_KEY));
    conversationService.registerAllowedAttachmentKeys(conversationId, List.of(OBJECT_KEY));

    ChatRequest request = new ChatRequest();
    request.setMessage("ok");
    request.setAttachmentObjectKeys(List.of(OBJECT_KEY));

    String effective = service.resolve(request, conversationId);

    assertTrue(effective.contains("(inlined)"));
    assertTrue(effective.contains("QIP.INT.IDS.Test"));
    verify(s3Service, times(1)).readObjectUtf8(eq(OBJECT_KEY));
  }

  @Test
  void secondTurnNoKeysOnRequestUsesAllowedConversationKeys() {
    String conversationId = UUID.randomUUID().toString();
    conversationService.getOrCreate(conversationId, ScenarioType.CREATE_CHAIN_PLAN);
    conversationService.registerAllowedAttachmentKeys(conversationId, List.of(OBJECT_KEY));
    conversationService.addMaterializedAttachmentKeys(conversationId, List.of(OBJECT_KEY));

    ChatRequest request = new ChatRequest();
    request.setMessage("proceed");

    String effective = service.resolve(request, conversationId);

    assertTrue(effective.contains("(inlined)"));
    assertTrue(effective.contains("QIP.INT.IDS.Test"));
  }

  @Test
  void noKeysReturnsMessageOnly() {
    String conversationId = UUID.randomUUID().toString();
    conversationService.getOrCreate(conversationId, ScenarioType.CREATE_CHAIN_PLAN);

    ChatRequest request = new ChatRequest();
    request.setMessage("hello");

    String effective = service.resolve(request, conversationId);

    assertFalse(effective.contains("(inlined)"));
    assertFalse(effective.contains("---"));
  }

  private static void inject(Object target, String fieldName, Object value) throws Exception {
    Field f = target.getClass().getDeclaredField(fieldName);
    f.setAccessible(true);
    f.set(target, value);
  }
}
