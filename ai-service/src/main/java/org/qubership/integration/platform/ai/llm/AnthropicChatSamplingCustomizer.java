package org.qubership.integration.platform.ai.llm;

import dev.langchain4j.model.anthropic.AnthropicChatModel;
import io.quarkiverse.langchain4j.ModelBuilderCustomizer;
import io.quarkiverse.langchain4j.ModelName;
import jakarta.enterprise.context.ApplicationScoped;
import org.qubership.integration.platform.ai.llm.ratelimit.RateLimitChatModelProducer;

/** Removes legacy sampling defaults and leaves transport retries to the accounting wrapper. */
@ApplicationScoped
@ModelName(RateLimitChatModelProducer.ANTHROPIC_UPSTREAM_MODEL_NAME)
public class AnthropicChatSamplingCustomizer
    implements ModelBuilderCustomizer<AnthropicChatModel.AnthropicChatModelBuilder> {

  @Override
  public void customize(AnthropicChatModel.AnthropicChatModelBuilder builder) {
    builder.topK(null);
    // The extension requires a positive config value, but the SDK accepts zero retries.
    builder.maxRetries(0);
  }
}
