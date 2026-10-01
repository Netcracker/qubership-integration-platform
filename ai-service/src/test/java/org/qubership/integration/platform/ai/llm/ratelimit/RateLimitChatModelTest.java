package org.qubership.integration.platform.ai.llm.ratelimit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.DefaultChatRequestParameters;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.configuration.AppConfig;

class RateLimitChatModelTest {
  @Test
  void oversizedRequiredInputNeverReachesEitherProvider() {
    AppConfig config = mock(AppConfig.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
    when(config.llm().contextTokens()).thenReturn(10000);
    when(config.llm().transientRetry().backoffSeconds()).thenReturn("2,5,10");
    ChatModel sync = mock(ChatModel.class);
    StreamingChatModel stream = mock(StreamingChatModel.class);
    var defaults = DefaultChatRequestParameters.builder().maxOutputTokens(1000).build();
    when(sync.defaultRequestParameters()).thenReturn(defaults);
    when(stream.defaultRequestParameters()).thenReturn(defaults);
    ChatRequest request = ChatRequest.builder().messages(UserMessage.from("many words ".repeat(10000))).build();
    var syncWrapper = new RateLimitChatModel(sync, config, seconds -> {},
        new RateLimitErrorClassifier(), new RateLimitWaitPolicy());
    var streamWrapper = new RateLimitStreamingChatModel(stream, config, seconds -> {},
        new RateLimitErrorClassifier(), new RateLimitWaitPolicy());
    assertThrows(IllegalArgumentException.class, () -> syncWrapper.chat(request));
    assertThrows(IllegalArgumentException.class,
        () -> streamWrapper.chat(request, mock(StreamingChatResponseHandler.class)));
    verify(sync, never()).chat(any(ChatRequest.class));
    verify(stream, never()).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
  }

  @Test
  void shortAnalysisCeilingPreservesGeneratorOutputAndProviderSettings() {
    AppConfig config = mock(AppConfig.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
    when(config.llm().analysisOutputTokens()).thenReturn(4096);
    var budget = new LlmRequestBudget(config);
    var capture = ToolSpecification.builder().name("captureRequirementBrief")
        .parameters(JsonObjectSchema.builder().addProperty("capture", JsonObjectSchema.builder().build()).build())
        .build();
    ChatRequest request = ChatRequest.builder().messages(UserMessage.from("Complete input"))
        .parameters(OpenAiChatRequestParameters.builder().maxOutputTokens(30000).reasoningEffort("none")
            .toolSpecifications(capture).build()).build();
    var analysis = budget.prepare(request);
    assertEquals(4096, analysis.parameters().maxOutputTokens());
    assertEquals("none", ((OpenAiChatRequestParameters) analysis.parameters()).reasoningEffort());
    var generator = request.toBuilder().parameters(OpenAiChatRequestParameters.builder()
        .maxOutputTokens(30000).reasoningEffort("none").build()).build();
    assertEquals(30000, budget.prepare(generator).parameters().maxOutputTokens());
  }
}
