package org.qubership.integration.platform.ai.llm;

import dev.langchain4j.model.anthropic.AnthropicChatModel;
import io.quarkiverse.langchain4j.ModelBuilderCustomizer;
import io.quarkiverse.langchain4j.ModelName;
import jakarta.enterprise.context.ApplicationScoped;
import org.qubership.integration.platform.ai.llm.ratelimit.RateLimitChatModelProducer;

/** Removes the extension's legacy top-k default from Anthropic chat requests. */
@ApplicationScoped
@ModelName(RateLimitChatModelProducer.ANTHROPIC_UPSTREAM_MODEL_NAME)
public class AnthropicChatSamplingCustomizer
    implements ModelBuilderCustomizer<AnthropicChatModel.AnthropicChatModelBuilder> {

  @Override
  public void customize(AnthropicChatModel.AnthropicChatModelBuilder builder) {
    builder.topK(null);
  }
}
