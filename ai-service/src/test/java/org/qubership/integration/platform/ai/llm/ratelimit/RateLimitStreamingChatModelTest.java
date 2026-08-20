package org.qubership.integration.platform.ai.llm.ratelimit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.DefaultChatRequestParameters;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.configuration.AppConfig;

class RateLimitStreamingChatModelTest {

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

  private static RateLimitStreamingChatModel newModel(StreamingChatModel delegate) {
    AppConfig appConfig = mock(AppConfig.class);
    AppConfig.LlmConfig llm = mock(AppConfig.LlmConfig.class);
    AppConfig.LlmConfig.RateLimitConfig rateLimit = mock(AppConfig.LlmConfig.RateLimitConfig.class);
    when(appConfig.llm()).thenReturn(llm);
    when(llm.rateLimit()).thenReturn(rateLimit);
    when(rateLimit.enabled()).thenReturn(false);

    return new RateLimitStreamingChatModel(
        delegate,
        appConfig,
        seconds -> {},
        new RateLimitErrorClassifier(),
        new RateLimitWaitPolicy());
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
