package org.qubership.integration.platform.ai.llm;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.junit.jupiter.api.Test;

class OpenAiStreamingReasoningCustomizerTest {

  @Test
  void appliesConfiguredReasoningEffortToStreamingModel() {
    OpenAiStreamingChatModel.OpenAiStreamingChatModelBuilder builder = mock();

    new OpenAiStreamingReasoningCustomizer("none").customize(builder);

    verify(builder).reasoningEffort("none");
  }
}
