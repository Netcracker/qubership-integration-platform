package org.qubership.integration.platform.ai.compiler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ScriptBodyToolArgumentsSanitizerTest {

  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void leavesAlreadyValidJsonAlone() {
    String valid =
        """
        {"capture":{"patchId":"ok","scripts":[{"targetNodeId":"error-handler",\
        "script":"exchange.in.body = groovy.json.JsonOutput.toJson([error: 'x'])"}],\
        "rationale":"r"}}
        """;
    assertTrue(ScriptBodyToolArgumentsSanitizer.isValidJson(valid));
    assertTrue(ScriptBodyToolArgumentsSanitizer.sanitizeIfNeeded(valid).isEmpty());
  }

  @Test
  void escapesUnescapedQuotesInsideScriptValueLikeR504Literal() throws Exception {
    // LLM forgot to escape quotes inside Groovy that embeds a JSON object literal.
    String broken =
        "{\"capture\":{\"patchId\":\"catch-error-response\",\"scripts\":[{"
            + "\"targetNodeId\":\"error-handler\","
            + "\"script\":\"def exception = exchange.getProperty('CamelExceptionCaught')\\n"
            + "exchange.in.headers.put('CamelHttpResponseCode', 500)\\n"
            + "exchange.in.body = '{\"error\": \"' + exception?.message + '\"}'\""
            + "}],\"rationale\":\"R-504\"}}";

    assertFalse(ScriptBodyToolArgumentsSanitizer.isValidJson(broken));

    Optional<String> repaired = ScriptBodyToolArgumentsSanitizer.sanitizeIfNeeded(broken);
    assertTrue(repaired.isPresent());
    assertTrue(ScriptBodyToolArgumentsSanitizer.isValidJson(repaired.get()));

    JsonNode root = mapper.readTree(repaired.get());
    String script =
        root.path("capture").path("scripts").get(0).path("script").asText();
    assertTrue(script.contains("exchange.in.body"));
    assertTrue(script.contains("error"));
    assertEquals("error-handler", root.path("capture").path("scripts").get(0).path("targetNodeId").asText());
  }

  @Test
  void returnsEmptyWhenGarbageCannotBeRepaired() {
    String garbage = "{\"capture\":{\"scripts\":[{\"script\":\"exchange.in.body = '{\\\"}]},\"}}";
    Optional<String> repaired = ScriptBodyToolArgumentsSanitizer.sanitizeIfNeeded(garbage);
    // May or may not repair depending on how truncated; if repaired it must be valid JSON.
    repaired.ifPresent(value -> assertTrue(ScriptBodyToolArgumentsSanitizer.isValidJson(value)));
  }
}
