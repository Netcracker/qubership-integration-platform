package org.qubership.integration.platform.ai.llm.ratelimit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiResponsesChatModel;
import dev.langchain4j.model.openai.OpenAiResponsesStreamingChatModel;
import io.quarkus.test.junit.QuarkusTest;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.qubership.integration.platform.ai.configuration.AppConfig;

/** The real SDK sends the repaired history to a local provider that rejects invalid argument JSON. */
@QuarkusTest
class MalformedToolHistoryTransportTest {
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void actualBlockingAndStreamingWireHistoryRetainsRejectedDataAndValidSibling(boolean streaming) throws Exception {
    var json = new ObjectMapper();
    String raw = "{\"patch\": {\"script\": \"quoted \\\"value\\\"\"} trailing}";
    String feedback = "INVALID_JSON /: Send one valid JSON argument object. Resubmit the complete captureGraphPatch argument object.";
    var bad = ToolExecutionRequest.builder().id("bad").name("captureGraphPatch").arguments(raw).build();
    var valid = ToolExecutionRequest.builder().id("valid").name("readSchema").arguments(" { \"kind\" : \"script\" } ").build();
    var original = AiMessage.builder().text("Two independent calls").toolExecutionRequests(List.of(bad, valid)).build();
    var declaration = ToolSpecification.builder().name("captureGraphPatch").description("Native capture schema")
        .parameters(JsonObjectSchema.builder().addStringProperty("patch").required("patch").additionalProperties(false).build()).build();
    var request = ChatRequest.builder().messages(UserMessage.from("source and current state"), original,
        ToolExecutionResultMessage.from(bad, feedback), ToolExecutionResultMessage.from(valid, "schema response"))
        .toolSpecifications(List.of(declaration)).build();
    var wire = new AtomicReference<JsonNode>();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/v1/responses", exchange -> {
      var body = json.readTree(exchange.getRequestBody());
      wire.set(body);
      int status = 200;
      for (var input : body.path("input")) {
        if ("function_call".equals(input.path("type").asText())) {
          try { json.readTree(input.path("arguments").asText()); }
          catch (java.io.IOException malformed) { status = 400; }
        }
      }
      String completed = "{\"id\":\"local\",\"object\":\"response\",\"model\":\"history-fixture\",\"status\":\"completed\","
          + "\"output\":[{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"delivered\"}]}]}";
      String response = status == 400 ? "{\"error\":{\"message\":\"Historical function arguments are invalid JSON\"}}"
          : streaming ? "event: response.completed\ndata: {\"type\":\"response.completed\",\"response\":" + completed + "}\n\n" : completed;
      exchange.getResponseHeaders().set("Content-Type", status == 200 && streaming ? "text/event-stream" : "application/json");
      byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(status, bytes.length);
      try (var out = exchange.getResponseBody()) { out.write(bytes); }
      exchange.close();
    });
    server.start();
    try {
      String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
      var config = mock(AppConfig.class, RETURNS_DEEP_STUBS);
      when(config.e2e().recoveryControlDirectory()).thenReturn(Optional.empty());
      if (!streaming) {
        var sdk = OpenAiResponsesChatModel.builder().baseUrl(baseUrl).apiKey("local-fixture-key")
            .httpClientBuilder(dev.langchain4j.http.client.jdk.JdkHttpClient.builder())
            .modelName("history-fixture").maxOutputTokens(24).build();
        var model = new RateLimitChatModel(sdk, config, seconds -> {}, new RateLimitErrorClassifier(), new RateLimitWaitPolicy());
        assertEquals("delivered", model.chat(request).aiMessage().text());
      } else {
        var sdk = OpenAiResponsesStreamingChatModel.builder().baseUrl(baseUrl).apiKey("local-fixture-key")
            .httpClientBuilder(dev.langchain4j.http.client.jdk.JdkHttpClient.builder())
            .modelName("history-fixture").maxOutputTokens(24).build();
        var model = new RateLimitStreamingChatModel(sdk, config, seconds -> {}, new RateLimitErrorClassifier(), new RateLimitWaitPolicy());
        var completed = new CompletableFuture<ChatResponse>();
        model.chat(request, new StreamingChatResponseHandler() {
          @Override public void onPartialResponse(String text) {}
          @Override public void onCompleteResponse(ChatResponse response) { completed.complete(response); }
          @Override public void onError(Throwable error) { completed.completeExceptionally(error); }
        });
        assertEquals("delivered", completed.get(5, TimeUnit.SECONDS).aiMessage().text());
      }
      var calls = new java.util.HashMap<String, JsonNode>();
      var results = new java.util.HashMap<String, String>();
      for (var item : wire.get().path("input")) {
        if ("function_call".equals(item.path("type").asText())) calls.put(item.path("call_id").asText(), item);
        if ("function_call_output".equals(item.path("type").asText())) results.put(item.path("call_id").asText(), item.path("output").asText());
      }
      assertEquals(Set.of("bad", "valid"), calls.keySet());
      var envelope = json.readTree(calls.get("bad").path("arguments").asText()).path("rejectedToolCall");
      assertEquals("REJECTED", envelope.path("status").asText());
      assertEquals("INVALID_JSON", envelope.path("reason").asText());
      assertEquals(raw, envelope.path("rawArguments").asText());
      assertEquals(valid.arguments(), calls.get("valid").path("arguments").asText());
      assertEquals(Map.of("bad", feedback, "valid", "schema response"), results);
      assertEquals(raw, original.toolExecutionRequests().getFirst().arguments());
      assertEquals("patch", wire.get().path("tools").get(0).path("parameters").path("required").get(0).asText());
    } finally { server.stop(0); }
  }
}
