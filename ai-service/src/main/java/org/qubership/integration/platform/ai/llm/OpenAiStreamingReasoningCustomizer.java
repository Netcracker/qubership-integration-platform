package org.qubership.integration.platform.ai.llm;

import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import io.quarkiverse.langchain4j.ModelBuilderCustomizer;
import io.quarkiverse.langchain4j.ModelName;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.qubership.integration.platform.ai.llm.ratelimit.RateLimitChatModelProducer;

/** Applies the configured reasoning effort to the OpenAI streaming client. */
@ApplicationScoped
@ModelName(RateLimitChatModelProducer.UPSTREAM_MODEL_NAME)
public class OpenAiStreamingReasoningCustomizer
    implements ModelBuilderCustomizer<OpenAiStreamingChatModel.OpenAiStreamingChatModelBuilder> {

  private final String reasoningEffort;

  OpenAiStreamingReasoningCustomizer(
      @ConfigProperty(
              name = "quarkus.langchain4j.openai.upstream.chat-model.reasoning-effort",
              defaultValue = "none")
          String reasoningEffort) {
    this.reasoningEffort = reasoningEffort;
  }

  @Override
  public void customize(OpenAiStreamingChatModel.OpenAiStreamingChatModelBuilder builder) {
    builder.reasoningEffort(reasoningEffort);
  }
}
