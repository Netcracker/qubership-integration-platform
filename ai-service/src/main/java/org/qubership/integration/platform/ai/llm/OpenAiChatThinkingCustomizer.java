package org.qubership.integration.platform.ai.llm;

import dev.langchain4j.model.openai.OpenAiChatModel;
import io.quarkiverse.langchain4j.ModelBuilderCustomizer;
import io.quarkiverse.langchain4j.ModelName;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.qubership.integration.platform.ai.llm.ratelimit.RateLimitChatModelProducer;

/**
 * Applies the configured thinking budget to the blocking OpenAI client. The extension already reads
 * reasoning effort from config for this builder, so only the budget needs a customizer.
 */
@ApplicationScoped
@ModelName(RateLimitChatModelProducer.UPSTREAM_MODEL_NAME)
public class OpenAiChatThinkingCustomizer
    implements ModelBuilderCustomizer<OpenAiChatModel.OpenAiChatModelBuilder> {

  private final Optional<Integer> thinkingBudgetTokens;

  OpenAiChatThinkingCustomizer(
      @ConfigProperty(name = "qip.ai.llm.thinking.budget-tokens")
          Optional<Integer> thinkingBudgetTokens) {
    this.thinkingBudgetTokens = thinkingBudgetTokens;
  }

  @Override
  public void customize(OpenAiChatModel.OpenAiChatModelBuilder builder) {
    ThinkingBudget.customParameters(thinkingBudgetTokens).ifPresent(builder::customParameters);
  }
}
