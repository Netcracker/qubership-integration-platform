package org.qubership.integration.platform.ai.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chat.ToolSession;
import org.qubership.integration.platform.ai.chat.attachment.UploadedSpecEntry;
import org.qubership.integration.platform.ai.chat.attachment.UploadedSpecStore;
import org.qubership.integration.platform.ai.chat.conversation.ConversationService;
import org.qubership.integration.platform.ai.storage.S3Service;

class RegisterUploadedSpecToolTest {

  private static final String SAMPLE_SPEC =
      "{\"openapi\":\"3.0.0\",\"info\":{\"title\":\"Order API\",\"version\":\"1.0.0\"},"
          + "\"paths\":{\"/orders\":{\"get\":{},\"post\":{}}}}";

  @Test
  void registersUploadedOpenApiSpec() {
    S3Service s3Service = mock(S3Service.class);
    ConversationService conversationService = mock(ConversationService.class);
    UploadedSpecStore store = new UploadedSpecStore();
    ObjectMapper objectMapper = new ObjectMapper();
    RegisterUploadedSpecTool tool =
        new RegisterUploadedSpecTool(s3Service, store, conversationService, objectMapper);

    String s3Key = "uploads/order-api.json";
    when(s3Service.readObjectBytes(s3Key)).thenReturn(SAMPLE_SPEC.getBytes(StandardCharsets.UTF_8));
    when(conversationService.getAllowedAttachmentKeys("conv-1")).thenReturn(List.of(s3Key));

    try (ToolSession.Handle ignored = ToolSession.open("conv-1")) {
      String result = tool.registerUploadedSpec(s3Key, "order-api.json");

      assertTrue(result.contains("\"ok\":true"), result);
      assertTrue(result.contains("Order API"), result);
      assertTrue(result.contains("OPENAPI"), result);
      assertTrue(result.contains("GET /orders"), result);
    }

    List<UploadedSpecEntry> entries = store.getAll("conv-1");
    assertEquals(1, entries.size());
    UploadedSpecEntry entry = entries.get(0);
    assertEquals(s3Key, entry.s3Key());
    assertEquals("order-api.json", entry.originalFilename());
    assertEquals("Order API", entry.title());
    assertEquals("1.0.0", entry.version());
    assertTrue(entry.operationsSummary().contains("GET /orders"));
  }
}
