package org.qubership.integration.platform.ai.chat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chat.ChatEvent;

class ChatSseFormattingTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void tokenWithNewlinesIsDataPrefixedPerLine() {
    String sse = ChatExecutionService.toSse(new ChatEvent.Token("line1\nline2"), objectMapper);

    assertEquals("event: token\ndata: line1\ndata: line2\n\n", sse);
  }

  @Test
  void hitlQuestionWithQuotesAndNewlineStaysValidJson() throws Exception {
    String question = "Which \"binding\"?\nPick one";
    String sse = ChatExecutionService.toSse(new ChatEvent.Hitl("cp-1", question), objectMapper);

    assertTrue(sse.startsWith("event: hitl\ndata: "));
    String payload = sse.substring("event: hitl\ndata: ".length(), sse.length() - 2);
    JsonNode node = objectMapper.readTree(payload);
    assertEquals("cp-1", node.get("checkpointId").asText());
    assertEquals(question, node.get("question").asText());
  }

  @Test
  void framesMetaWithConversationId() {
    String sse = ChatExecutionService.toSse(ChatEvent.meta("conv-1"), objectMapper);
    assertTrue(sse.startsWith("event: meta\n"));
    assertTrue(sse.contains("\"conversationId\":\"conv-1\""));
  }

  @Test
  void framesStepReplacePayloadWithIdKindStatus() {
    String sse =
        ChatExecutionService.toSse(
            ChatEvent.step("skill:auth", "skill", "running", "Implement auth", null), objectMapper);
    assertTrue(sse.startsWith("event: step\n"));
    assertTrue(sse.contains("\"id\":\"skill:auth\""));
    assertTrue(sse.contains("\"kind\":\"skill\""));
    assertTrue(sse.contains("\"status\":\"running\""));
    assertTrue(sse.contains("\"label\":\"Implement auth\""));
    assertTrue(!sse.contains("\"step\":\"skill:auth\""));
  }

  @Test
  void stepIsSerializedAsJson() throws Exception {
    String sse =
        ChatExecutionService.toSse(
            ChatEvent.step("pipeline:skeleton", "pipeline", "error", "skeleton", null), objectMapper);

    String payload = sse.substring("event: step\ndata: ".length(), sse.length() - 2);
    JsonNode node = objectMapper.readTree(payload);
    assertEquals("pipeline:skeleton", node.get("id").asText());
    assertEquals("pipeline", node.get("kind").asText());
    assertEquals("error", node.get("status").asText());
    assertEquals("skeleton", node.get("label").asText());
  }

  @Test
  void errorMessageWithNewlineIsDataPrefixed() {
    String sse = ChatExecutionService.toSse(new ChatEvent.Error("boom\ndetail"), objectMapper);

    assertEquals("event: error\ndata: boom\ndata: detail\n\n", sse);
  }
}
