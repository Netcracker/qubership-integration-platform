package org.qubership.integration.platform.ai.llm.ratelimit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import io.quarkiverse.langchain4j.ModelName;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.Test;

/**
 * Fail-closed CDI wiring gate: the unqualified ChatModel used by {@code @RegisterAiService} must
 * be the rate-limit wrapper over the named OpenAI {@code upstream} model.
 */
@QuarkusTest
class RateLimitChatModelWiringIT {

  private static final Logger LOG = Logger.getLogger(RateLimitChatModelWiringIT.class);

  @Inject ChatModel chatModel;
  @Inject StreamingChatModel streamingChatModel;

  @Inject
  @ModelName(RateLimitChatModelProducer.UPSTREAM_MODEL_NAME)
  ChatModel upstreamChatModel;

  @Inject
  @ModelName(RateLimitChatModelProducer.UPSTREAM_MODEL_NAME)
  StreamingChatModel upstreamStreamingChatModel;

  @Test
  void unqualifiedModelsAreRateLimitWrappersOverNamedUpstream() {
    assertNotNull(chatModel, "ChatModel must inject (no 'No delegate ChatModel bean found')");
    assertNotNull(
        streamingChatModel,
        "StreamingChatModel must inject (no 'No delegate StreamingChatModel bean found')");
    assertNotNull(upstreamChatModel, "Named upstream ChatModel must inject");
    assertNotNull(upstreamStreamingChatModel, "Named upstream StreamingChatModel must inject");

    LOG.infof("CDI ChatModel concrete class: %s", chatModel.getClass().getName());
    LOG.infof(
        "CDI StreamingChatModel concrete class: %s", streamingChatModel.getClass().getName());
    LOG.infof("Named upstream ChatModel class: %s", upstreamChatModel.getClass().getName());
    LOG.infof(
        "Named upstream StreamingChatModel class: %s",
        upstreamStreamingChatModel.getClass().getName());

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
        upstreamChatModel,
        "Unqualified ChatModel must not be the raw named upstream bean");
    assertNotSame(
        streamingChatModel,
        upstreamStreamingChatModel,
        "Unqualified StreamingChatModel must not be the raw named upstream bean");
    assertFalse(
        upstreamChatModel instanceof RateLimitChatModel,
        "Named upstream ChatModel must stay unwrapped (OpenAI synthetic / client)");
    assertFalse(
        upstreamStreamingChatModel instanceof RateLimitStreamingChatModel,
        "Named upstream StreamingChatModel must stay unwrapped");
    assertTrue(
        rateLimited.delegate() == upstreamChatModel
            || rateLimited.delegate().getClass().equals(upstreamChatModel.getClass()),
        "RateLimitChatModel must wrap the named upstream ChatModel");
    assertTrue(
        rateLimitedStreaming.delegate() == upstreamStreamingChatModel
            || rateLimitedStreaming
                .delegate()
                .getClass()
                .equals(upstreamStreamingChatModel.getClass()),
        "RateLimitStreamingChatModel must wrap the named upstream StreamingChatModel");
  }
}
