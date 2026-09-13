package org.qubership.integration.platform.ai.llm;

import dev.langchain4j.model.anthropic.AnthropicStreamingChatModel;
import io.quarkiverse.langchain4j.ModelBuilderCustomizer;
import io.quarkiverse.langchain4j.ModelName;
import jakarta.enterprise.context.ApplicationScoped;
import org.qubership.integration.platform.ai.llm.ratelimit.RateLimitChatModelProducer;

/** Removes the extension's legacy top-k default from Anthropic streaming requests. */
@ApplicationScoped
@ModelName(RateLimitChatModelProducer.ANTHROPIC_UPSTREAM_MODEL_NAME)
public class AnthropicStreamingSamplingCustomizer
    implements ModelBuilderCustomizer<AnthropicStreamingChatModel.AnthropicStreamingChatModelBuilder> {

  @Override
  public void customize(AnthropicStreamingChatModel.AnthropicStreamingChatModelBuilder builder) {
    builder.topK(null);
  }
}
