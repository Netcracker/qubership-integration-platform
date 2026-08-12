package org.qubership.integration.platform.ai.chat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chat.ChatEvent;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainPendingAction;

class ChatSseFormattingTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void tokenWithNewlinesIsDataPrefixedPerLine() {
    String sse = ChatExecutionService.toSse(new ChatEvent.Token("line1\nline2"), objectMapper);

    assertEquals("event: token\ndata: line1\ndata: line2\n\n", sse);
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
  void framesApprovalDecisionWithItsBindingAndActions() throws Exception {
    String question = "Approve the \"plan\"?\nOr ask for changes";
    String sse =
        ChatExecutionService.toSse(
            ChatEvent.decision(
                new CreateChainPendingAction.Approve(
                    "implementation-plan", "sha256:abc", 4L, question),
                4L,
                ""),
            objectMapper);

    assertTrue(sse.startsWith("event: decision\ndata: "));
    JsonNode node =
        objectMapper.readTree(
            sse.substring("event: decision\ndata: ".length(), sse.length() - 2));
    assertEquals("approve:sha256:abc", node.get("id").asText());
    assertEquals("approve", node.get("kind").asText());
    assertEquals(question, node.get("question").asText());
    assertEquals("implementation-plan", node.get("artifactType").asText());
    assertEquals("sha256:abc", node.get("artifactHash").asText());
    assertEquals(4L, node.get("revision").asLong());
    assertEquals("approve", node.get("actions").get(0).asText());
    assertEquals("request-changes", node.get("actions").get(1).asText());
  }

  @Test
  void framesClarificationDecisionWithoutActions() throws Exception {
    String sse =
        ChatExecutionService.toSse(
            ChatEvent.decision(
                new CreateChainPendingAction.Clarify(
                    "Target system is unknown", List.of("target system")),
                9L,
                "Which system receives the message?"),
            objectMapper);

    JsonNode node =
        objectMapper.readTree(
            sse.substring("event: decision\ndata: ".length(), sse.length() - 2));
    assertEquals("clarify:9", node.get("id").asText());
    assertEquals("clarify", node.get("kind").asText());
    assertEquals("Which system receives the message?", node.get("question").asText());
    assertEquals("Target system is unknown", node.get("reason").asText());
    assertEquals("target system", node.get("missingEvidence").get(0).asText());
    assertTrue(node.get("actions").isEmpty());
    assertTrue(node.get("artifactHash") == null);
  }

  @Test
  void errorMessageWithNewlineIsDataPrefixed() {
    String sse = ChatExecutionService.toSse(new ChatEvent.Error("boom\ndetail"), objectMapper);

    assertEquals("event: error\ndata: boom\ndata: detail\n\n", sse);
  }
}
