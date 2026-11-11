package org.qubership.integration.platform.ai.productpipeline.create.design.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.qubership.integration.platform.ai.llm.agent.ChainSemanticDesignAgent;
import org.qubership.integration.platform.ai.llm.agent.DesignPlanCaptureAgent;
import org.qubership.integration.platform.ai.productpipeline.create.design.planning.DesignPlanCaptureTool;
import org.qubership.integration.platform.ai.llm.ratelimit.RateLimitChatModel;

/** Exercises the generated Quarkus agent and tool executor with a deterministic model response. */
@QuarkusTest
class ChainSemanticDesignAgentTest {

  @Inject ChainSemanticDesignAgent agent;
  @InjectMock RateLimitChatModel model;
  @InjectMock ChainSemanticCaptureTool captureTool;
  @Inject DesignPlanCaptureAgent planAgent;
  @InjectMock DesignPlanCaptureTool planCaptureTool;

  @ParameterizedTest
  @ValueSource(strings = {"Chain semantic revision captured.", "Invalid revision: missing region"})
  void captureReturnsToTheRuntimeWithoutAnotherModelCall(String captureResult) throws Exception {
    var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
    var capture = mapper.valueToTree(ChainSemanticCaptureFixtures.rockyCapture());
    for (var edge : capture.path("edges")) {
      ((com.fasterxml.jackson.databind.node.ObjectNode) edge).put("routeKind", "SEQUENCE");
    }
    String arguments = mapper.writeValueAsString(java.util.Map.of("capture", capture));
    AtomicInteger calls = new AtomicInteger();
    when(model.supportedCapabilities()).thenReturn(Set.of(
        dev.langchain4j.model.chat.Capability.RESPONSE_FORMAT_JSON_SCHEMA));
    when(model.chat(any(ChatRequest.class)))
        .thenAnswer(
            invocation -> {
              assertEquals(1, calls.incrementAndGet(), "capture must finish this model attempt");
              ChatRequest request = invocation.getArgument(0);
              assertEquals("captureChainSemanticRevision", request.toolSpecifications().getFirst().name());
              assertNull(request.responseFormat(), "a tool capture must not request a Result JSON envelope");
              return ChatResponse.builder()
                  .aiMessage(
                      AiMessage.from(
                          ToolExecutionRequest.builder()
                              .id("capture-1")
                              .name("captureChainSemanticRevision")
                              .arguments(arguments)
                              .build()))
                  .build();
            });
    when(captureTool.captureChainSemanticRevision(any())).thenReturn(captureResult);

    var result = agent.chat("direct-capture-" + captureResult.hashCode(), "Capture the topology.");

    assertEquals(captureResult, result);
    verify(model, times(1)).chat(any(ChatRequest.class));
    verify(captureTool, times(1)).captureChainSemanticRevision(any());
  }

  @Test
  void planCaptureDoesNotRequestAResultEnvelope() {
    when(model.supportedCapabilities()).thenReturn(Set.of(
        dev.langchain4j.model.chat.Capability.RESPONSE_FORMAT_JSON_SCHEMA));
    when(model.chat(any(ChatRequest.class)))
        .thenAnswer(invocation -> {
          ChatRequest request = invocation.getArgument(0);
          assertNull(request.responseFormat());
          assertEquals("captureDesignPlan", request.toolSpecifications().getFirst().name());
          return ChatResponse.builder().aiMessage(AiMessage.from(
              ToolExecutionRequest.builder().id("plan-1").name("captureDesignPlan")
                  .arguments("{\"capture\":{}}").build())).build();
        });
    when(planCaptureTool.captureDesignPlan(any())).thenReturn("Design plan captured.");

    assertEquals("Design plan captured.", planAgent.chat("direct-plan-capture", "Capture the plan."));
    verify(model, times(1)).chat(any(ChatRequest.class));
    verify(planCaptureTool, times(1)).captureDesignPlan(any());
  }
}
