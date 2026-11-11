package org.qubership.integration.platform.ai.llm;

import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import io.quarkiverse.langchain4j.ModelBuilderCustomizer;
import io.quarkiverse.langchain4j.ModelName;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.qubership.integration.platform.ai.llm.ratelimit.RateLimitChatModelProducer;

/** Applies the configured reasoning effort and thinking budget to the OpenAI streaming client. */
@ApplicationScoped
@ModelName(RateLimitChatModelProducer.OPENAI_UPSTREAM_MODEL_NAME)
public class OpenAiStreamingReasoningCustomizer
    implements ModelBuilderCustomizer<OpenAiStreamingChatModel.OpenAiStreamingChatModelBuilder> {

  private final String reasoningEffort;
  private final Optional<Integer> thinkingBudgetTokens;

  OpenAiStreamingReasoningCustomizer(
      @ConfigProperty(
              name = "quarkus.langchain4j.openai.openai-upstream.chat-model.reasoning-effort",
              defaultValue = "none")
          String reasoningEffort,
      @ConfigProperty(name = "qip.ai.llm.thinking.budget-tokens")
          Optional<Integer> thinkingBudgetTokens) {
    this.reasoningEffort = reasoningEffort;
    this.thinkingBudgetTokens = thinkingBudgetTokens;
  }

  @Override
  public void customize(OpenAiStreamingChatModel.OpenAiStreamingChatModelBuilder builder) {
    builder.reasoningEffort(reasoningEffort);
    ThinkingBudget.customParameters(thinkingBudgetTokens).ifPresent(builder::customParameters);
  }
}
