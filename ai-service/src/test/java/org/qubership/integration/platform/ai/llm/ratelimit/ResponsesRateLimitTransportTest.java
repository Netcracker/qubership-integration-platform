package org.qubership.integration.platform.ai.llm.ratelimit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.InvalidRequestException;
import dev.langchain4j.exception.RateLimitException;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiResponsesChatModel;
import dev.langchain4j.model.openai.OpenAiResponsesStreamingChatModel;
import io.quarkiverse.langchain4j.openai.runtime.config.LangChain4jOpenAiConfig;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.tls.TlsConfigurationRegistry;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.qubership.integration.platform.ai.configuration.AppConfig;

/** Exercises the real JAX-RS transport and SDK exception mapper, without a remote provider. */
@QuarkusTest
class ResponsesRateLimitTransportTest {
  private static final String ERROR = "  {\"error\":{\"message\":\"capacity exhausted Δ\"}}\n";
  private static final String COMPLETION = """
      {"id":"local-response","object":"response","model":"transport-fixture","status":"completed",
       "output":[{"type":"message","role":"assistant","content":[{"type":"output_text","text":"done"}]}],
       "usage":{"input_tokens":1,"output_tokens":1,"total_tokens":2}}
      """;
  private final AtomicInteger physicalRequests = new AtomicInteger();
  private HttpServer server;
  private volatile int status;
  private volatile String timingHeader;
  private volatile String timingValue;

  @BeforeEach
  void startLocalTransport() throws IOException {
    physicalRequests.set(0);
    status = 429;
    timingHeader = "rEtRy-AfTeR";
    timingValue = "11";
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/v1/responses", exchange -> {
      var request = new ObjectMapper().readTree(exchange.getRequestBody());
      physicalRequests.incrementAndGet();
      boolean stream = request.path("stream").asBoolean();
      String body = status == 200
          ? stream ? "event: response.completed\ndata: {\"type\":\"response.completed\",\"response\":"
              + COMPLETION.replace("\n", "") + "}\n\n" : COMPLETION
          : ERROR;
      exchange.getResponseHeaders().set("Content-Type", status == 200 && stream
          ? "text/event-stream" : "application/json; charset=UTF-8");
      if (timingHeader != null) exchange.getResponseHeaders().set(timingHeader, timingValue);
      exchange.getResponseHeaders().set("Authorization", "must-not-enter-the-exception");
      byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(status, bytes.length);
      try (var output = exchange.getResponseBody()) {
        output.write(bytes);
      }
      exchange.close();
    });
    server.start();
  }

  @AfterEach
  void stopLocalTransport() {
    if (server != null) server.stop(0);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void actualHttpRetryAfterSurvivesSdkMappingForBlockingAndStreaming(boolean streaming) throws Exception {
    Throwable error = invoke(streaming);

    var mapped = assertInstanceOf(RateLimitException.class, error);
    var http = assertInstanceOf(RateLimitHttpException.class, mapped.getCause());
    assertEquals(429, http.statusCode());
    assertEquals(ERROR, mapped.getMessage());
    assertEquals(ERROR, http.getMessage());
    assertEquals(Map.of("Retry-After", "11"), http.headers());
    assertNull(http.getCause());
    assertEquals(1, physicalRequests.get(), "The transport filter must not retry");
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void actualHttpResetIsPreservedWithoutTimingInterpretation(boolean streaming) throws Exception {
    timingHeader = "x-ratelimit-reset";
    timingValue = "2139722880123";

    var mapped = assertInstanceOf(RateLimitException.class, invoke(streaming));
    var http = assertInstanceOf(RateLimitHttpException.class, mapped.getCause());
    assertEquals(Map.of("X-RateLimit-Reset", "2139722880123"), http.headers());
    assertEquals(ERROR, http.getMessage());
    assertEquals(1, physicalRequests.get());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void absentTimingHeadersStillPreserveTheOriginalRateLimitFailure(boolean streaming) throws Exception {
    timingHeader = null;

    var mapped = assertInstanceOf(RateLimitException.class, invoke(streaming));
    var http = assertInstanceOf(RateLimitHttpException.class, mapped.getCause());
    assertEquals(Map.of(), http.headers());
    assertEquals(ERROR, mapped.getMessage());
    assertEquals(1, physicalRequests.get());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void nonRateLimitHttpFailureKeepsTheOriginalSdkClassification(boolean streaming) throws Exception {
    status = 400;

    var mapped = assertInstanceOf(InvalidRequestException.class, invoke(streaming));
    var http = assertInstanceOf(HttpException.class, mapped.getCause());
    assertEquals(HttpException.class, http.getClass());
    assertEquals(400, http.statusCode());
    assertEquals(ERROR, http.getMessage());
    assertEquals(1, physicalRequests.get());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void successfulHttpAndSseResponsesAreNotConsumedOrChanged(boolean streaming) throws Exception {
    status = 200;

    assertNull(invoke(streaming));
    assertEquals(1, physicalRequests.get());
  }

  private Throwable invoke(boolean streaming) throws Exception {
    String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    var customizer = customizer();
    if (!streaming) {
      var builder = OpenAiResponsesChatModel.builder().baseUrl(baseUrl).apiKey("local-fixture-key")
          .modelName("transport-fixture").temperature(0.2).maxOutputTokens(24);
      customizer.chatCustomizer().customize(builder);
      if (status == 200) {
        assertEquals("done", builder.build().chat("unchanged request"));
        return null;
      }
      return assertThrows(RuntimeException.class, () -> builder.build().chat("unchanged request"));
    }
    var builder = OpenAiResponsesStreamingChatModel.builder().baseUrl(baseUrl).apiKey("local-fixture-key")
        .modelName("transport-fixture").temperature(0.2).maxOutputTokens(24);
    customizer.streamingCustomizer().customize(builder);
    var completed = new CompletableFuture<ChatResponse>();
    builder.build().chat("unchanged request", new StreamingChatResponseHandler() {
      @Override public void onPartialResponse(String partial) {}
      @Override public void onCompleteResponse(ChatResponse response) { completed.complete(response); }
      @Override public void onError(Throwable error) { completed.completeExceptionally(error); }
    });
    if (status == 200) {
      assertEquals("done", completed.get(5, TimeUnit.SECONDS).aiMessage().text());
      return null;
    }
    return assertThrows(ExecutionException.class, () -> completed.get(5, TimeUnit.SECONDS)).getCause();
  }

  private ResponsesDeadlineCustomizer customizer() {
    var config = mock(LangChain4jOpenAiConfig.class, RETURNS_DEEP_STUBS);
    var named = mock(LangChain4jOpenAiConfig.OpenAiConfig.class);
    when(config.namedConfig()).thenReturn(Map.of(RateLimitChatModelProducer.OPENAI_UPSTREAM_MODEL_NAME, named));
    when(named.timeout()).thenReturn(Optional.of(Duration.ofSeconds(3)));
    when(named.tlsConfigurationName()).thenReturn(Optional.empty());
    var tls = mock(TlsConfigurationRegistry.class);
    when(tls.getDefault()).thenReturn(Optional.empty());
    var app = mock(AppConfig.class, RETURNS_DEEP_STUBS);
    when(app.llm().openRouterProvider()).thenReturn(Optional.empty());
    return new ResponsesDeadlineCustomizer(config, tls, app, new ObjectMapper());
  }
}
