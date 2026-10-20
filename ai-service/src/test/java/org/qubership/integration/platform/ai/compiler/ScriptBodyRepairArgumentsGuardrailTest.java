package org.qubership.integration.platform.ai.compiler;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import io.quarkiverse.langchain4j.guardrails.ToolInputGuardrailRequest;
import io.quarkiverse.langchain4j.guardrails.ToolInputGuardrailResult;
import org.junit.jupiter.api.Test;

class ScriptBodyRepairArgumentsGuardrailTest {

  private final ScriptBodyRepairArgumentsGuardrail guardrail =
      new ScriptBodyRepairArgumentsGuardrail();

  @Test
  void passesValidArgumentsUnchanged() {
    String valid =
        "{\"capture\":{\"patchId\":\"p\",\"scripts\":[{\"targetNodeId\":\"n\","
            + "\"script\":\"return 1\"}],\"rationale\":\"r\"}}";
    ToolInputGuardrailResult result = guardrail.validate(request(valid));
    assertTrue(result.isSuccess());
    assertNull(result.modifiedRequest());
  }

  @Test
  void repromptsOnUnescapedQuotesInScriptInsteadOfRewritingThem() {
    String broken =
        "{\"capture\":{\"patchId\":\"catch-error-response\",\"scripts\":[{"
            + "\"targetNodeId\":\"error-handler\","
            + "\"script\":\"def exception = exchange.getProperty('CamelExceptionCaught')\\n"
            + "exchange.in.body = '{\"error\": \"' + exception?.message + '\"}'\""
            + "}],\"rationale\":\"R-504\"}}";

    ToolInputGuardrailResult result = guardrail.validate(request(broken));

    assertFalse(result.isSuccess());
    assertFalse(result.isFatalFailure());
    assertNull(result.modifiedRequest());
    assertTrue(result.errorMessage().contains("JsonOutput.toJson"));
  }

  @Test
  void passesBlankArgumentsThroughToToolBinding() {
    ToolInputGuardrailResult result = guardrail.validate(request("   "));

    assertTrue(result.isSuccess());
    assertNull(result.modifiedRequest());
  }

  @Test
  void returnsNonFatalFailureWithJsonOutputHintWhenUnrepairable() {
    String garbage = "{not-json-at-all";
    ToolInputGuardrailResult result = guardrail.validate(request(garbage));
    assertFalse(result.isSuccess());
    assertFalse(result.isFatalFailure());
    assertTrue(result.errorMessage().contains("JsonOutput.toJson"));
  }

  private static ToolInputGuardrailRequest request(String arguments) {
    ToolExecutionRequest execution =
        ToolExecutionRequest.builder()
            .id("call-1")
            .name("repairScriptBodies")
            .arguments(arguments)
            .build();
    return new ToolInputGuardrailRequest(execution, null, null);
  }
}
