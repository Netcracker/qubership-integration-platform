package org.qubership.integration.platform.ai.chat.service;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.ai.chat.conversation.ConversationService;
import org.qubership.integration.platform.ai.chat.model.ChatRequest;
import org.qubership.integration.platform.ai.storage.S3Service;

@ExtendWith(MockitoExtension.class)
class EffectiveUserTextServiceTest {

  @Mock S3Service s3Service;
  @Mock ConversationService conversationService;
  @InjectMocks EffectiveUserTextService service;

  @Test
  void normalizesMalformedAttachmentObjectKeysBeforeRegistration() {
    ChatRequest request = new ChatRequest();
    request.setMessage("create a chain");
    request.setAttachmentObjectKeys(
        List.of(
            "sessions/conv/a.json\n"
                + "- http://localhost:8080/api/v1/storage/objects?key=sessions/conv/b.json"));

    service.resolve(request, "conv-1");

    verify(conversationService)
        .registerAllowedAttachmentKeys(
            "conv-1", List.of("sessions/conv/a.json", "sessions/conv/b.json"));
  }

  @Test
  void openChainContextAttachmentIsNotAnUploadedSpecKey() {
    ConversationService conversations = new ConversationService();
    EffectiveUserTextService textService =
        new EffectiveUserTextService(mock(S3Service.class), conversations);
    ChatRequest request = new ChatRequest();
    request.setMessage("Describe chain");
    request.setAttachment(
        "## Current Chain: OM to Salesforce WFM (ID: 7c6b6568-e338-4007-9f7f-b942aef6ea76)");

    String text = textService.resolve(request, "conv-1");

    assertTrue(text.contains("Current Chain"));
    assertTrue(conversations.getAllowedAttachmentKeys("conv-1").isEmpty());
  }
}
