package org.qubership.integration.platform.ai.productpipeline.create.design.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
import org.qubership.integration.platform.ai.compiler.capture.CaptureValidationException;
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

  @Test
  void anAcceptedCaptureEndsTheTurnWithoutAnotherModelCall() throws Exception {
    String arguments = captureArguments();
    when(model.supportedCapabilities()).thenReturn(Set.of(
        dev.langchain4j.model.chat.Capability.RESPONSE_FORMAT_JSON_SCHEMA));
    when(model.chat(any(ChatRequest.class)))
        .thenAnswer(
            invocation -> {
              ChatRequest request = invocation.getArgument(0);
              assertEquals("captureChainSemanticRevision", request.toolSpecifications().getFirst().name());
              assertNull(request.responseFormat(), "a tool capture must not request a Result JSON envelope");
              return toolCall("capture-1", arguments);
            });
    when(captureTool.captureChainSemanticRevision(any()))
        .thenThrow(new CaptureValidationException("Chain semantic revision captured."));

    var ended = assertThrows(CaptureValidationException.class,
        () -> agent.chat("direct-capture-accepted", "Capture the topology."));

    assertEquals("Chain semantic revision captured.", ended.getMessage());
    verify(model, times(1)).chat(any(ChatRequest.class));
    verify(captureTool, times(1)).captureChainSemanticRevision(any());
  }

  /** The rejection is the tool result of the same turn, so the next model request carries it. */
  @Test
  void aRejectedCaptureGoesBackToTheModelInTheSameTurn() throws Exception {
    String arguments = captureArguments();
    AtomicInteger calls = new AtomicInteger();
    when(model.supportedCapabilities()).thenReturn(Set.of(
        dev.langchain4j.model.chat.Capability.RESPONSE_FORMAT_JSON_SCHEMA));
    when(model.chat(any(ChatRequest.class)))
        .thenAnswer(
            invocation -> {
              if (calls.incrementAndGet() == 1) {
                return toolCall("capture-1", arguments);
              }
              ChatRequest request = invocation.getArgument(0);
              var last = assertInstanceOf(dev.langchain4j.data.message.ToolExecutionResultMessage.class,
                  request.messages().getLast());
              assertTrue(last.text().contains("Invalid revision: missing region"), last.text());
              return ChatResponse.builder().aiMessage(AiMessage.from("Stopped.")).build();
            });
    when(captureTool.captureChainSemanticRevision(any())).thenReturn("Invalid revision: missing region");

    assertEquals("Stopped.", agent.chat("direct-capture-rejected", "Capture the topology."));
    verify(model, times(2)).chat(any(ChatRequest.class));
  }

  private static String captureArguments() throws Exception {
    var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
    var capture = mapper.valueToTree(ChainSemanticCaptureFixtures.rockyCapture());
    for (var edge : capture.path("edges")) {
      ((com.fasterxml.jackson.databind.node.ObjectNode) edge).put("routeKind", "SEQUENCE");
    }
    return mapper.writeValueAsString(java.util.Map.of("capture", capture));
  }

  private static ChatResponse toolCall(String id, String arguments) {
    return ChatResponse.builder()
        .aiMessage(AiMessage.from(ToolExecutionRequest.builder()
            .id(id).name("captureChainSemanticRevision").arguments(arguments).build()))
        .build();
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
    when(planCaptureTool.captureDesignPlan(any()))
        .thenThrow(new CaptureValidationException("Design plan captured."));

    assertThrows(CaptureValidationException.class,
        () -> planAgent.chat("direct-plan-capture", "Capture the plan."));
    verify(model, times(1)).chat(any(ChatRequest.class));
    verify(planCaptureTool, times(1)).captureDesignPlan(any());
  }
}
