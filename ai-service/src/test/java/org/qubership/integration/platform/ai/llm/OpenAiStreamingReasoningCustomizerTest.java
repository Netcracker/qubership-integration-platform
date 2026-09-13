package org.qubership.integration.platform.ai.llm;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class OpenAiStreamingReasoningCustomizerTest {

  @Test
  void appliesConfiguredReasoningEffortToStreamingModel() {
    OpenAiStreamingChatModel.OpenAiStreamingChatModelBuilder builder = mock();

    new OpenAiStreamingReasoningCustomizer("none", Optional.empty()).customize(builder);

    verify(builder).reasoningEffort("none");
    verify(builder, never()).customParameters(Map.of());
  }

  @Test
  void appliesTheThinkingBudgetWhenOneIsConfigured() {
    OpenAiStreamingChatModel.OpenAiStreamingChatModelBuilder builder = mock();

    new OpenAiStreamingReasoningCustomizer("none", Optional.of(2048)).customize(builder);

    verify(builder).customParameters(Map.of("thinking", Map.of("type", "enabled", "budget_tokens", 2048)));
  }
}
