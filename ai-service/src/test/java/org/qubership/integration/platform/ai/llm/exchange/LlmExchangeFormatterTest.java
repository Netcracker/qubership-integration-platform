package org.qubership.integration.platform.ai.llm.exchange;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
    formatter = new LlmExchangeFormatter(new com.fasterxml.jackson.databind.ObjectMapper());
    mdc = new LlmExchangeMdcContext("conv-1", "CREATE_CHAIN_PLAN", "cip-structure-generator");
  }

  @Test
  void completeCapturePreservesArgumentsAndRecordsInputAndSchemaIdentity() throws Exception {
    var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
    String arguments = "{\"script\":\"" + "x".repeat(60000) + "tail\"}";
    var response = ChatResponse.builder().id("response-1").modelName("actual-model")
        .aiMessage(AiMessage.from(dev.langchain4j.agent.tool.ToolExecutionRequest.builder()
            .id("tool-1").name("captureGraphPatch").arguments(arguments).build())).build();
    var capture = mapper.readTree(formatter.responseCapture(response, "exchange-1", mdc, 123));
    assertEquals(arguments, capture.path("toolCalls").get(0).path("arguments").asText());
    assertEquals("actual-model", capture.path("model").asText());
    assertEquals("response-1", capture.path("responseId").asText());
    assertFalse(formatter.formatResponse(response, mdc, 123, 20).contains(arguments));

    var metadata = dev.langchain4j.model.openai.OpenAiResponsesChatResponseMetadata.builder()
        .id("response-2").modelName("actual-model")
        .rawHttpResponse(dev.langchain4j.http.client.SuccessfulHttpResponse.builder()
            .statusCode(200).body("complete response")
            .headers(java.util.Map.of("X-Request-Id", List.of("provider-request-1"),
                "Authorization", List.of("credential"))).build())
        .rawServerSentEvents(List.of(new dev.langchain4j.http.client.sse.ServerSentEvent("response.done", arguments)))
        .build();
    var raw = mapper.readTree(formatter.responseCapture(response.toBuilder().metadata(metadata).build(),
        "exchange-1", mdc, 123));
    assertEquals(arguments, raw.path("sdkSseEvents").get(0).path("data").asText());
    assertEquals("complete response", raw.path("rawResponseBody").asText());
    assertTrue(raw.path("responseHeaders").has("X-Request-Id"));
    assertFalse(raw.toString().contains("credential"));
    formatter.responseCapture(response.toBuilder().metadata(
        dev.langchain4j.model.openai.OpenAiResponsesChatResponseMetadata.builder().build()).build(),
        "exchange-1", mdc, 123);

    var schema = dev.langchain4j.model.chat.request.json.JsonObjectSchema.builder()
        .addStringProperty("value").required("value").build();
    var request = dev.langchain4j.model.chat.request.ChatRequest.builder()
        .messages(UserMessage.from("task with selected artifact ref-1"))
        .parameters(dev.langchain4j.model.openai.OpenAiResponsesChatRequestParameters.builder()
            .modelName("requested-model").temperature(0.2).maxOutputTokens(24000).reasoningEffort("none")
            .toolSpecifications(dev.langchain4j.agent.tool.ToolSpecification.builder()
                .name("captureGraphPatch").parameters(schema).build()).build()).build();
    String endpoint = "https://user:password@example.test/v1/?api_key=secret";
    var before = mapper.readTree(formatter.requestCapture(request, endpoint, "exchange-1", mdc));
    var changed = mapper.readTree(formatter.requestCapture(request.toBuilder()
        .messages(UserMessage.from("changed task")).build(), endpoint, "exchange-2", mdc));
    assertEquals("https://example.test/v1/", before.path("endpoint").asText());
    assertFalse(before.toString().contains("password"));
    assertFalse(before.toString().contains("secret"));
    assertEquals("none", before.path("reasoningEffort").asText());
    assertTrue(before.path("messages").asText().contains("ref-1"));
    assertNotEquals(before.path("inputSha256"), changed.path("inputSha256"));
    assertEquals(before.path("schemaSha256"), changed.path("schemaSha256"));
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
                    .id("call-1")
                    .name("captureChainPlan")
                    .arguments("{\"interactionId\":\"create-task\"}")
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
    assertTrue(text.contains("tool id=call-1 name=captureChainPlan arguments={\"interactionId\":\"create-task\"}"));
    assertTrue(text.contains("preview=done"));
  }

  @Test
  void zeroLimitPreservesToolArgumentsAndHistory() {
    String arguments = "{\"interactionId\":\"create-task\"}";
    AiMessage message = new AiMessage("", List.of(
        dev.langchain4j.agent.tool.ToolExecutionRequest.builder()
            .id("call-1")
            .name("resolveApiOperation")
            .arguments(arguments)
            .build()));

    String text = formatter.formatMessages(List.of(message,
        ToolExecutionResultMessage.from("call-1", "resolveApiOperation", "resolved")), 0);

    assertTrue(text.contains("tool id=call-1 name=resolveApiOperation arguments=" + arguments));
    assertTrue(text.contains("tool name=resolveApiOperation id=call-1 chars=8 preview=resolved"));
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
