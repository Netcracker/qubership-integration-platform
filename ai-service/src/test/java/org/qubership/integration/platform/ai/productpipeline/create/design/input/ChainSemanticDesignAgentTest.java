package org.qubership.integration.platform.ai.productpipeline.create.design.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.qubership.integration.platform.ai.llm.agent.ChainSemanticDesignAgent;
import org.qubership.integration.platform.ai.llm.ratelimit.RateLimitChatModel;

/** Exercises the generated Quarkus agent and tool executor with a deterministic model response. */
@QuarkusTest
class ChainSemanticDesignAgentTest {

  @Inject ChainSemanticDesignAgent agent;
  @InjectMock RateLimitChatModel model;
  @InjectMock ChainSemanticCaptureTool captureTool;

  @ParameterizedTest
  @ValueSource(strings = {"Chain semantic revision captured.", "Invalid revision: missing region"})
  void captureReturnsToTheRuntimeWithoutAnotherModelCall(String captureResult) {
    AtomicInteger calls = new AtomicInteger();
    when(model.chat(any(ChatRequest.class)))
        .thenAnswer(
            invocation -> {
              assertEquals(1, calls.incrementAndGet(), "capture must finish this model attempt");
              return ChatResponse.builder()
                  .aiMessage(
                      AiMessage.from(
                          ToolExecutionRequest.builder()
                              .id("capture-1")
                              .name("captureChainSemanticRevision")
                              .arguments("{\"capture\":{}}")
                              .build()))
                  .build();
            });
    when(captureTool.captureChainSemanticRevision(any())).thenReturn(captureResult);

    var result = agent.chat("direct-capture-" + captureResult.hashCode(), "Capture the topology.");

    assertEquals(1, result.toolExecutions().size());
    assertEquals(captureResult, result.toolExecutions().getFirst().resultObject());
    verify(model, times(1)).chat(any(ChatRequest.class));
    verify(captureTool, times(1)).captureChainSemanticRevision(any());
  }
}
