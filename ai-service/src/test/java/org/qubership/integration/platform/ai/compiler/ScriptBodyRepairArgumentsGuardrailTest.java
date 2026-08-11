package org.qubership.integration.platform.ai.compiler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import io.quarkiverse.langchain4j.guardrails.ToolInputGuardrailRequest;
import io.quarkiverse.langchain4j.guardrails.ToolInputGuardrailResult;
import org.junit.jupiter.api.Test;

class ScriptBodyRepairArgumentsGuardrailTest {

  private final ScriptBodyRepairArgumentsGuardrail guardrail =
      new ScriptBodyRepairArgumentsGuardrail();
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void passesValidArgumentsUnchanged() {
    String valid =
        "{\"capture\":{\"patchId\":\"p\",\"scripts\":[{\"targetNodeId\":\"n\","
            + "\"script\":\"return 1\"}],\"rationale\":\"r\"}}";
    ToolInputGuardrailResult result = guardrail.validate(request(valid));
    assertTrue(result.isSuccess());
    assertEquals(null, result.modifiedRequest());
  }

  @Test
  void sanitizesUnescapedQuotesInScriptAndReturnsModifiedRequest() throws Exception {
    String broken =
        "{\"capture\":{\"patchId\":\"catch-error-response\",\"scripts\":[{"
            + "\"targetNodeId\":\"error-handler\","
            + "\"script\":\"def exception = exchange.getProperty('CamelExceptionCaught')\\n"
            + "exchange.in.body = '{\"error\": \"' + exception?.message + '\"}'\""
            + "}],\"rationale\":\"R-504\"}}";

    ToolInputGuardrailResult result = guardrail.validate(request(broken));
    assertTrue(result.isSuccess());
    assertNotNull(result.modifiedRequest());
    String repairedArgs = result.modifiedRequest().arguments();
    assertTrue(ScriptBodyToolArgumentsSanitizer.isValidJson(repairedArgs));
    JsonNode script =
        mapper.readTree(repairedArgs).path("capture").path("scripts").get(0).path("script");
    assertTrue(script.asText().contains("exception?.message"));
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
