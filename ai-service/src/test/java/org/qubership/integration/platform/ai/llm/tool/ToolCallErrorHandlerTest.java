package org.qubership.integration.platform.ai.llm.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.exception.ToolArgumentsException;
import dev.langchain4j.service.tool.ToolErrorContext;
import dev.langchain4j.service.tool.ToolErrorHandlerResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.productpipeline.create.ProductCapabilityCaptureContext;

class ToolCallErrorHandlerTest {

  private final ToolCallErrorHandler handler = new ToolCallErrorHandler();

  @AfterEach
  void unbind() {
    ProductCapabilityCaptureContext.unbind();
  }

  @Test
  void designMapperFailureUsesTheSameStructuredOutcome() {
    ProductCapabilityCaptureContext.bindDesign("run-1", "conv-1", null, payload -> {});
    ToolErrorContext context = ToolErrorContext.builder()
        .toolExecutionRequest(ToolExecutionRequest.builder()
            .id("call-1").name("captureChainSemanticRevision").arguments("{}").build())
        .memoryId("conv-1")
        .build();

    String response = handler.handle(new ToolArgumentsException("bad type"), context).text();

    assertTrue(response.contains("\"accepted\":false"), response);
    assertTrue(response.contains("\"nextAction\":\"REPAIR_CAPTURE\""), response);
    assertTrue(response.contains("\"code\":\"INVALID_TYPE\""), response);
    assertTrue(ProductCapabilityCaptureContext.designBinding("conv-1")
        .orElseThrow().captureRejection().get().contains("INVALID_TYPE"));
  }

  @Test
  void stringifiedArgumentsTellTheModelToSendJsonOfTheDeclaredType() {
    String message =
        ToolCallErrorHandler.message(
            "captureChainSemanticRevision",
            new RuntimeException(
                "params '{\"capture\": \"{...}\"}' from request do not map onto the parameters"
                    + " needed by captureChainSemanticRevision"));

    assertTrue(message.contains("captureChainSemanticRevision"), message);
    assertTrue(message.contains("never as a string holding JSON"), message);
    assertTrue(message.contains("call captureChainSemanticRevision again"), message);
  }

  @Test
  void jacksonRejectionReadsAsAnArgumentShapeProblem() {
    String message =
        ToolCallErrorHandler.message(
            "captureRequirementDraft",
            new IllegalStateException(
                "Cannot construct instance of `RequirementDraftCapture`: no String-argument"
                    + " constructor/factory method to deserialize from String value"));

    assertTrue(message.contains("do not match its schema"), message);
  }

  @Test
  void otherFailuresCarryTheirOwnReason() {
    String message =
        ToolCallErrorHandler.message("resolveApiOperation", new IllegalStateException("catalog is offline"));

    assertTrue(message.contains("catalog is offline."), message);
    assertTrue(message.contains("Correct the call and try once more."), message);
    assertFalse(message.contains("schema"), message);
  }

  @Test
  void aReasonlessFailureStillAnswersTheModel() {
    String message = ToolCallErrorHandler.message("resolveApiOperation", new IllegalStateException());

    assertEquals(
        "Tool resolveApiOperation failed: the service could not run it. Correct the call and try"
            + " once more.",
        message);
  }

  @Test
  void aLongReasonIsCutToTheCap() {
    String reason = "x".repeat(ToolCallErrorHandler.MAX_REASON_CHARS + 120);

    String message = ToolCallErrorHandler.message("anyTool", new IllegalStateException(reason));

    assertTrue(message.length() < reason.length(), message.length() + " chars");
    assertTrue(message.contains("x".repeat(ToolCallErrorHandler.MAX_REASON_CHARS)), message);
  }

  @Test
  void aContextlessFailureStillAnswersTheModel() {
    ToolErrorHandlerResult result =
        handler.handle(new RuntimeException("do not map onto the parameters"), null);

    assertTrue(result.text().startsWith("Tool the tool"), result.text());
    assertTrue(result.text().contains("never as a string holding JSON"), result.text());
  }
}
