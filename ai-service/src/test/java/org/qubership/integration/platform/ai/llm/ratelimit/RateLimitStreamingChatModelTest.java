package org.qubership.integration.platform.ai.llm.ratelimit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.DefaultChatRequestParameters;
import dev.langchain4j.model.chat.request.ToolChoice;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.configuration.AppConfig;

class RateLimitStreamingChatModelTest {

  private static final ToolExecutionRequest READ = ToolExecutionRequest.builder()
      .id("read-current-draft").name("readRequirementDraft").arguments("{}").build();

  @Test
  void preservesOpenAiParametersWhenTheCallerUsesGenericParameters() {
    CapturingStreamingChatModel delegate = new CapturingStreamingChatModel();
    RateLimitStreamingChatModel model = newModel(delegate);
    ChatRequest request =
        ChatRequest.builder()
            .messages(UserMessage.from("hi"))
            .parameters(DefaultChatRequestParameters.builder().temperature(0.8).build())
            .build();

    model.chat(request, new RateLimitAwareStreamingChatModelTest.CapturingHandler());

    OpenAiChatRequestParameters parameters =
        (OpenAiChatRequestParameters) delegate.request.parameters();
    assertEquals("none", parameters.reasoningEffort());
    assertEquals(0.8, parameters.temperature());
  }

  @Test
  void releasesRequiredAfterAMatchingCurrentTurnToolResultAndPreservesProviderParameters() {
    CapturingStreamingChatModel delegate = new CapturingStreamingChatModel();
    RateLimitStreamingChatModel model = newModel(delegate);
    var parameters = OpenAiChatRequestParameters.builder().toolChoice(ToolChoice.REQUIRED)
        .reasoningEffort("low").maxOutputTokens(1700).temperature(0.4).build();
    var messages = List.<ChatMessage>of(UserMessage.from("repair"), AiMessage.from(READ),
        ToolExecutionResultMessage.from(READ, "{\"accepted\":false}"));
    var request = ChatRequest.builder().messages(messages).parameters(parameters).build();

    model.chat(request, new RateLimitAwareStreamingChatModelTest.CapturingHandler());

    var outgoing = (OpenAiChatRequestParameters) delegate.request.parameters();
    assertEquals(ToolChoice.AUTO, outgoing.toolChoice());
    assertEquals("low", outgoing.reasoningEffort());
    assertEquals(1700, outgoing.maxOutputTokens());
    assertEquals(0.4, outgoing.temperature());
    assertEquals(messages, delegate.request.messages());
    assertEquals(ToolChoice.REQUIRED, request.parameters().toolChoice(), "The caller's request stays immutable.");
  }

  @Test
  void normalRequestsKeepTheirChoiceEvenWhenTheyContainACompletedTool() {
    CapturingStreamingChatModel delegate = new CapturingStreamingChatModel();
    RateLimitStreamingChatModel model = newModel(delegate);
    model.chat(ChatRequest.builder().messages(UserMessage.from("normal"), AiMessage.from(READ),
        ToolExecutionResultMessage.from(READ, "{}"))
        .parameters(DefaultChatRequestParameters.builder().toolChoice(ToolChoice.NONE).build()).build(),
        new RateLimitAwareStreamingChatModelTest.CapturingHandler());
    assertEquals(ToolChoice.NONE, delegate.request.parameters().toolChoice());
  }

  @Test
  void priorTurnsUnmatchedResultsAndUnexecutedCallsDoNotReleaseRequired() {
    var cases = List.of(
        List.<ChatMessage>of(UserMessage.from("repair: tool read-current-draft completed")),
        List.<ChatMessage>of(UserMessage.from("repair"), AiMessage.from(READ)),
        List.<ChatMessage>of(UserMessage.from("repair"), ToolExecutionResultMessage.from(READ, "{}")),
        List.<ChatMessage>of(UserMessage.from("repair"), AiMessage.from(READ),
            ToolExecutionResultMessage.from("foreign", READ.name(), "{}")),
        List.<ChatMessage>of(UserMessage.from("repair"), AiMessage.from(READ),
            ToolExecutionResultMessage.from(READ.id(), "finishRequirementDiscoveryTurn", "{}")),
        List.<ChatMessage>of(UserMessage.from("old turn"), AiMessage.from(READ),
            ToolExecutionResultMessage.from(READ, "{}"), UserMessage.from("new repair")));
    for (var messages : cases) {
      CapturingStreamingChatModel delegate = new CapturingStreamingChatModel();
      newModel(delegate).chat(required(messages), new RateLimitAwareStreamingChatModelTest.CapturingHandler());
      assertEquals(ToolChoice.REQUIRED, delegate.request.parameters().toolChoice(), messages.toString());
    }
  }

  @Test
  void concurrentInvocationsDoNotConsumeEachOthersRequirement() throws Exception {
    var entered = new CountDownLatch(3);
    var release = new CountDownLatch(1);
    var requests = java.util.Collections.synchronizedList(new ArrayList<ChatRequest>());
    StreamingChatModel delegate = new StreamingChatModel() {
      public void chat(ChatRequest request, StreamingChatResponseHandler handler) {
        requests.add(request);
        entered.countDown();
        try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
        catch (InterruptedException interrupted) { throw new AssertionError(interrupted); }
        handler.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from("done")).build());
      }
      public OpenAiChatRequestParameters defaultRequestParameters() {
        return OpenAiChatRequestParameters.builder().reasoningEffort("none").build();
      }
    };
    var model = newModel(delegate);
    var first = required(List.of(UserMessage.from("first repair")));
    var completed = required(List.of(UserMessage.from("completed repair"), AiMessage.from(READ),
        ToolExecutionResultMessage.from(READ, "{}")));
    var ordinary = ChatRequest.builder().messages(UserMessage.from("ordinary")).build();
    try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var pending = List.of(first, completed, ordinary).stream().map(request -> executor.submit(() ->
          model.chat(request, new RateLimitAwareStreamingChatModelTest.CapturingHandler()))).toList();
      try { assertTrue(entered.await(5, TimeUnit.SECONDS)); }
      finally { release.countDown(); }
      for (var task : pending) task.get(5, TimeUnit.SECONDS);
    }
    assertEquals(3, requests.size());
    assertEquals(ToolChoice.REQUIRED, requests.stream().filter(request -> request.messages().equals(first.messages()))
        .findFirst().orElseThrow().parameters().toolChoice());
    assertEquals(ToolChoice.AUTO, requests.stream().filter(request -> request.messages().equals(completed.messages()))
        .findFirst().orElseThrow().parameters().toolChoice());
    assertNull(requests.stream().filter(request -> request.messages().equals(ordinary.messages()))
        .findFirst().orElseThrow().parameters().toolChoice());
  }

  @Test
  void aTransientPhysicalRetryRetainsRequiredUntilAToolActuallyRuns() {
    var requests = new ArrayList<ChatRequest>();
    StreamingChatModel delegate = new StreamingChatModel() {
      public void chat(ChatRequest request, StreamingChatResponseHandler handler) {
        requests.add(request);
        if (requests.size() == 1) handler.onError(new IllegalStateException("connection reset"));
        else handler.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from("provider ignored tools")).build());
      }
      public OpenAiChatRequestParameters defaultRequestParameters() {
        return OpenAiChatRequestParameters.builder().build();
      }
    };
    var handler = new RateLimitAwareStreamingChatModelTest.CapturingHandler();
    newModel(delegate, true).chat(required(List.of(UserMessage.from("repair"))), handler);
    assertEquals(2, requests.size());
    assertTrue(requests.stream().allMatch(request -> request.parameters().toolChoice() == ToolChoice.REQUIRED));
    assertEquals(requests.getFirst(), requests.getLast());
    assertInstanceOf(IllegalStateException.class, handler.error, "Ignored choice fails after the ordinary transport retry.");
    assertNull(handler.completeResponse);
  }

  @Test
  void unsupportedRequiredDoesNotAddARequestOrConsumeTheNextInvocationsRequirement() {
    var requests = new ArrayList<ChatRequest>();
    var failure = new IllegalArgumentException("Unsupported tool choice");
    StreamingChatModel delegate = new StreamingChatModel() {
      public void chat(ChatRequest request, StreamingChatResponseHandler handler) {
        requests.add(request);
        handler.onError(failure);
      }
      public OpenAiChatRequestParameters defaultRequestParameters() {
        return OpenAiChatRequestParameters.builder().build();
      }
    };
    var model = newModel(delegate, true);
    for (String input : List.of("repair one", "repair two")) {
      var handler = new RateLimitAwareStreamingChatModelTest.CapturingHandler();
      model.chat(required(List.of(UserMessage.from(input))), handler);
      assertSame(failure, handler.error);
    }
    assertEquals(2, requests.size());
    assertTrue(requests.stream().allMatch(request -> request.parameters().toolChoice() == ToolChoice.REQUIRED));
  }

  @Test
  void ignoredRequiredIsRefusedAfterActualUsageIsAccountedWithoutRetryOrGrant(
      @org.junit.jupiter.api.io.TempDir java.nio.file.Path evidence) throws Exception {
    var config = testConfig(true);
    when(config.llm().maxRequestsPerRun()).thenReturn(1);
    when(config.llm().maxTokensPerRun()).thenReturn(100_000L);
    when(config.llm().maxActiveTimePerRun()).thenReturn(java.time.Duration.ofMinutes(1));
    var e2e = mock(AppConfig.E2eConfig.class);
    when(config.e2e()).thenReturn(e2e);
    when(e2e.recoveryControlDirectory()).thenReturn(java.util.Optional.of(evidence.toString()));
    java.nio.file.Files.writeString(evidence.resolve("plan.json"), "{\"conversationId\":\"tool-protocol\"}");
    java.nio.file.Files.writeString(evidence.resolve("provider-context.json"),
        "{\"runId\":\"ignored-required\",\"conversationId\":\"tool-protocol\"}");
    var budget = new LlmRunBudget(new org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore(),
        new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules(), config.llm(), java.time.Clock.systemUTC());
    var requests = new ArrayList<ChatRequest>();
    var errors = new java.util.concurrent.atomic.AtomicInteger();
    var completions = new java.util.concurrent.atomic.AtomicInteger();
    StreamingChatModel delegate = new StreamingChatModel() {
      public void chat(ChatRequest request, StreamingChatResponseHandler handler) {
        requests.add(request);
        var response = ChatResponse.builder().aiMessage(AiMessage.from("I will repair it"))
            .tokenUsage(new dev.langchain4j.model.output.TokenUsage(7, 3)).build();
        handler.onCompleteResponse(response);
        handler.onCompleteResponse(response);
      }
      public OpenAiChatRequestParameters defaultRequestParameters() {
        return OpenAiChatRequestParameters.builder().build();
      }
    };
    var model = new RateLimitStreamingChatModel(delegate, config, seconds -> {
      throw new AssertionError("Protocol rejection cannot trigger transport retry");
    }, new RateLimitErrorClassifier(), new RateLimitWaitPolicy()).withRunBudget(budget);
    LlmRunBudget.withRun("ignored-required", () -> {
      model.chat(required(List.of(UserMessage.from("repair"))), new StreamingChatResponseHandler() {
        public void onPartialResponse(String text) {}
        public void onCompleteResponse(ChatResponse response) { completions.incrementAndGet(); }
        public void onError(Throwable failure) {
          assertInstanceOf(IllegalStateException.class, failure);
          assertEquals(10, budget.state("ignored-required").tokens(), "Actual usage is recorded before protocol refusal.");
          assertTrue(budget.state("ignored-required").active().isEmpty());
          errors.incrementAndGet();
        }
      });
      return null;
    });
    assertEquals(1, requests.size());
    assertEquals(1, errors.get());
    assertEquals(0, completions.get());
    assertEquals(1, budget.state("ignored-required").requests());
    assertEquals(10, budget.state("ignored-required").tokens());
    assertEquals(0, budget.state("ignored-required").grants());
    assertTrue(budget.state("ignored-required").grantCommands().isEmpty());
    try (var files = java.nio.file.Files.list(evidence.resolve("provider-boundary"))) {
      var receipts = files.toList();
      assertEquals(2, receipts.size(), "The real provider request and response are each recorded once.");
      var json = new com.fasterxml.jackson.databind.ObjectMapper();
      var response = json.readTree(receipts.stream().filter(path -> path.toString().endsWith("-response.json"))
          .findFirst().orElseThrow().toFile());
      assertEquals(7, response.path("value").path("usage").path("inputTokenCount").asInt());
      assertEquals(3, response.path("value").path("usage").path("outputTokenCount").asInt());
      assertTrue(response.path("value").path("message").toString().contains("I will repair it"));
    }
  }

  private static ChatRequest required(List<ChatMessage> messages) {
    return ChatRequest.builder().messages(messages)
        .parameters(DefaultChatRequestParameters.builder().toolChoice(ToolChoice.REQUIRED).build()).build();
  }

  private static RateLimitStreamingChatModel newModel(StreamingChatModel delegate) {
    return newModel(delegate, false);
  }

  private static RateLimitStreamingChatModel newModel(StreamingChatModel delegate, boolean retryTransient) {
    return new RateLimitStreamingChatModel(
        delegate,
        testConfig(retryTransient),
        seconds -> {},
        new RateLimitErrorClassifier(),
        new RateLimitWaitPolicy());
  }

  private static AppConfig testConfig(boolean retryTransient) {
    AppConfig appConfig = mock(AppConfig.class);
    AppConfig.LlmConfig llm = mock(AppConfig.LlmConfig.class);
    AppConfig.LlmConfig.RateLimitConfig rateLimit = mock(AppConfig.LlmConfig.RateLimitConfig.class);
    when(appConfig.llm()).thenReturn(llm);
    when(llm.rateLimit()).thenReturn(rateLimit);
    AppConfig.LlmConfig.TransientRetryConfig transientRetry = mock(AppConfig.LlmConfig.TransientRetryConfig.class);
    when(llm.transientRetry()).thenReturn(transientRetry);
    when(transientRetry.backoffSeconds()).thenReturn("2,5,10");
    when(transientRetry.enabled()).thenReturn(retryTransient);
    when(transientRetry.maxAttempts()).thenReturn(3);
    when(rateLimit.enabled()).thenReturn(false);

    return appConfig;
  }

  private static final class CapturingStreamingChatModel implements StreamingChatModel {

    private final OpenAiChatRequestParameters parameters =
        OpenAiChatRequestParameters.builder().reasoningEffort("none").build();
    private ChatRequest request;

    @Override
    public void chat(ChatRequest request, StreamingChatResponseHandler handler) {
      this.request = request;
    }

    @Override
    public OpenAiChatRequestParameters defaultRequestParameters() {
      return parameters;
    }
  }
}
