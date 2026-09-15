package org.qubership.integration.platform.ai.llm.ratelimit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiResponsesChatRequestParameters;
import io.quarkiverse.langchain4j.ModelName;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.Test;

/**
 * Fail-closed CDI wiring gate for the provider-specific named models and the rate-limit wrapper
 * used by {@code @RegisterAiService}.
 */
@QuarkusTest
class RateLimitChatModelWiringIT {

  private static final Logger LOG = Logger.getLogger(RateLimitChatModelWiringIT.class);

  @Inject ChatModel chatModel;
  @Inject StreamingChatModel streamingChatModel;

  @Inject
  @ModelName(RateLimitChatModelProducer.OPENAI_UPSTREAM_MODEL_NAME)
  ChatModel openAiChatModel;

  @Inject
  @ModelName(RateLimitChatModelProducer.OPENAI_UPSTREAM_MODEL_NAME)
  StreamingChatModel openAiStreamingChatModel;

  @Inject
  @ModelName(RateLimitChatModelProducer.ANTHROPIC_UPSTREAM_MODEL_NAME)
  ChatModel anthropicChatModel;

  @Inject
  @ModelName(RateLimitChatModelProducer.ANTHROPIC_UPSTREAM_MODEL_NAME)
  StreamingChatModel anthropicStreamingChatModel;

  @Test
  void unqualifiedModelsAreRateLimitWrappersOverNamedUpstream() {
    assertNotNull(chatModel, "ChatModel must inject (no 'No delegate ChatModel bean found')");
    assertNotNull(
        streamingChatModel,
        "StreamingChatModel must inject (no 'No delegate StreamingChatModel bean found')");
    assertEquals(ModelProvider.OPEN_AI, openAiChatModel.provider());
    assertEquals(ModelProvider.OPEN_AI, openAiStreamingChatModel.provider());
    assertEquals(ModelProvider.ANTHROPIC, anthropicChatModel.provider());
    assertEquals(ModelProvider.ANTHROPIC, anthropicStreamingChatModel.provider());
    assertNull(anthropicChatModel.defaultRequestParameters().topK());
    assertNull(anthropicStreamingChatModel.defaultRequestParameters().topK());

    LOG.infof("CDI ChatModel concrete class: %s", chatModel.getClass().getName());
    LOG.infof(
        "CDI StreamingChatModel concrete class: %s", streamingChatModel.getClass().getName());
    LOG.infof("Named OpenAI ChatModel class: %s", openAiChatModel.getClass().getName());
    LOG.infof(
        "Named OpenAI StreamingChatModel class: %s",
        openAiStreamingChatModel.getClass().getName());

    RateLimitChatModel rateLimited =
        assertInstanceOf(
            RateLimitChatModel.class,
            chatModel,
            "FAIL-CLOSED: unqualified ChatModel must be RateLimitChatModel on the call path");
    RateLimitStreamingChatModel rateLimitedStreaming =
        assertInstanceOf(
            RateLimitStreamingChatModel.class,
            streamingChatModel,
            "FAIL-CLOSED: unqualified StreamingChatModel must be RateLimitStreamingChatModel");

    assertNotSame(
        chatModel,
        openAiChatModel,
        "Unqualified ChatModel must not be the raw named upstream bean");
    assertNotSame(
        streamingChatModel,
        openAiStreamingChatModel,
        "Unqualified StreamingChatModel must not be the raw named upstream bean");
    assertFalse(
        openAiChatModel instanceof RateLimitChatModel,
        "Named upstream ChatModel must stay unwrapped (OpenAI synthetic / client)");
    assertFalse(
        openAiStreamingChatModel instanceof RateLimitStreamingChatModel,
        "Named upstream StreamingChatModel must stay unwrapped");
    OpenAiResponsesChatRequestParameters openAiParameters =
        assertInstanceOf(
            OpenAiResponsesChatRequestParameters.class,
            openAiChatModel.defaultRequestParameters(),
            "Named OpenAI ChatModel must use the Responses API");
    OpenAiResponsesChatRequestParameters openAiStreamingParameters =
        assertInstanceOf(
            OpenAiResponsesChatRequestParameters.class,
            openAiStreamingChatModel.defaultRequestParameters(),
            "Named OpenAI StreamingChatModel must use the Responses API");
    assertEquals(
        "medium",
        openAiParameters.reasoningEffort(),
        "Named upstream ChatModel must preserve the configured reasoning effort");
    assertEquals(
        "medium",
        openAiStreamingParameters.reasoningEffort(),
        "Named upstream StreamingChatModel must preserve the configured reasoning effort");
    assertEquals(16, openAiParameters.maxOutputTokens());
    assertEquals(16, openAiStreamingParameters.maxOutputTokens());
    assertTrue(
        rateLimited.delegate() == openAiChatModel
            || rateLimited.delegate().getClass().equals(openAiChatModel.getClass()),
        "RateLimitChatModel must wrap the named upstream ChatModel");
    assertTrue(
        rateLimitedStreaming.delegate() == openAiStreamingChatModel
            || rateLimitedStreaming
                .delegate()
                .getClass()
                .equals(openAiStreamingChatModel.getClass()),
        "RateLimitStreamingChatModel must wrap the named upstream StreamingChatModel");
  }

  @Test
  void selectsProviderExplicitlyOrFromTheOfficialAnthropicHost() {
    assertTrue(
        RateLimitChatModelProducer.useAnthropic(
            "auto", "https://api.anthropic.com/v1/"));
    assertTrue(
        RateLimitChatModelProducer.useAnthropic(
            "anthropic", "https://llm-proxy.example/v1/"));
    assertFalse(
        RateLimitChatModelProducer.useAnthropic(
            "openai", "https://api.anthropic.com/v1/"));
    assertThrows(
        IllegalArgumentException.class,
        () -> RateLimitChatModelProducer.useAnthropic("unknown", "https://example.com/"));
  }
}
