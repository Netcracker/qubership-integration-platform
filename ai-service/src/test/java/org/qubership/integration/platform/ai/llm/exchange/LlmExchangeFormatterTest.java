package org.qubership.integration.platform.ai.llm.exchange;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LlmExchangeFormatterTest {

  private LlmExchangeFormatter formatter;
  private LlmExchangeMdcContext mdc;

  @BeforeEach
  void setUp() {
    formatter = new LlmExchangeFormatter();
    mdc = new LlmExchangeMdcContext("conv-1", "CREATE_CHAIN_PLAN", "cip-structure-generator");
  }

  @Test
  void formatRequestIncludesRolesAndMdc() {
    String text =
        formatter.formatRequest(
            List.of(
                SystemMessage.from("You are helpful."),
                UserMessage.from("Build a greetings chain.")),
            mdc,
            -1,
            500);

    assertTrue(text.startsWith("LLM request conversationId=conv-1"));
    assertTrue(text.contains("scenarioType=CREATE_CHAIN_PLAN"));
    assertTrue(text.contains("capabilityId=cip-structure-generator"));
    assertTrue(text.contains("[0] system"));
    assertTrue(text.contains("[1] user"));
    assertTrue(text.contains("Build a greetings chain."));
  }

  @Test
  void formatRequestTruncatesLongPreview() {
    String longText = "x".repeat(1000);

    String text =
        formatter.formatRequest(
            List.of(UserMessage.from(longText)), mdc, -1, 100);

    assertTrue(text.contains("chars)"));
    assertFalse(text.contains(longText));
  }

  @Test
  void formatRequestHandlesEmptyMessages() {
    String text = formatter.formatRequest(List.of(), mdc, -1, 500);

    assertTrue(text.contains("(no messages)"));
  }

  @Test
  void formatResponseIncludesToolCallsAndFinishReason() {
    AiMessage aiMessage =
        new AiMessage(
            "done",
            List.of(
                dev.langchain4j.agent.tool.ToolExecutionRequest.builder()
                    .name("captureChainPlan")
                    .arguments("{}")
                    .build()));
    ChatResponse response =
        ChatResponse.builder()
            .aiMessage(aiMessage)
            .finishReason(FinishReason.TOOL_EXECUTION)
            .tokenUsage(new TokenUsage(1, 2))
            .build();

    String text = formatter.formatResponse(response, mdc, 42, 500);

    assertTrue(text.startsWith("LLM response conversationId=conv-1"));
    assertTrue(text.contains("durationMs=42"));
    assertTrue(text.contains("finishReason=TOOL_EXECUTION"));
    assertTrue(text.contains("toolCalls=[captureChainPlan]"));
    assertTrue(text.contains("preview=done"));
  }

  @Test
  void formatMessagesIncludesToolResult() {
    String line =
        formatter.formatMessages(
            List.of(ToolExecutionResultMessage.from("req-1", "captureChainPlan", "ok")), 200);

    assertTrue(line.contains("tool name=captureChainPlan"));
    assertTrue(line.contains("preview=ok"));
  }

  @Test
  void formatErrorIncludesMessage() {
    String text =
        formatter.formatError(mdc, 99, new IllegalStateException("model timeout"));

    assertTrue(text.startsWith("LLM error"));
    assertTrue(text.contains("durationMs=99"));
    assertTrue(text.contains("model timeout"));
  }
}
