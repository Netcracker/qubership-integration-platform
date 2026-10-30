package org.qubership.integration.platform.ai.llm;

import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.llm.ScriptedStreamingChatModel.Turn;

class ScriptedStreamingChatModelTest {
  private final ChatRequest request = ChatRequest.builder().messages(List.of(UserMessage.from("request"))).build();
  private final StreamingChatResponseHandler sink = new StreamingChatResponseHandler() {
    public void onPartialResponse(String text) {}
    public void onCompleteResponse(ChatResponse response) {}
    public void onError(Throwable failure) {}
  };

  @Test
  void missingAndUnexpectedCallsCannotPass() {
    var model = new ScriptedStreamingChatModel(Turn.text("answer", ignored -> {}, "done"));
    assertThrows(AssertionError.class, model::assertComplete);
    model.chat(request, sink);
    model.assertComplete();
    assertThrows(AssertionError.class, () -> model.chat(request, sink));
    assertThrows(AssertionError.class, model::assertComplete);
  }

  @Test
  void aSwallowedRequestMismatchStillFailsVerification() {
    var model = new ScriptedStreamingChatModel(Turn.text("needs-feedback", incoming -> {
      throw new AssertionError("Native rejection is missing");
    }, "done"));
    assertThrows(AssertionError.class, () -> model.chat(request, sink));
    assertThrows(AssertionError.class, model::assertComplete);
  }
}
