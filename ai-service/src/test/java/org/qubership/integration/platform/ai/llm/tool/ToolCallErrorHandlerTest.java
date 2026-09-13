package org.qubership.integration.platform.ai.llm.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.langchain4j.service.tool.ToolErrorHandlerResult;
import org.junit.jupiter.api.Test;

class ToolCallErrorHandlerTest {

  private final ToolCallErrorHandler handler = new ToolCallErrorHandler();

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
