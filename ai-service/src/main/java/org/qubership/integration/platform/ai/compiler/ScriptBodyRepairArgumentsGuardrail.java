package org.qubership.integration.platform.ai.compiler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import io.quarkiverse.langchain4j.guardrails.ToolInputGuardrail;
import io.quarkiverse.langchain4j.guardrails.ToolInputGuardrailRequest;
import io.quarkiverse.langchain4j.guardrails.ToolInputGuardrailResult;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

/**
 * Rejects malformed {@code repairScriptBodies} arguments before Quarkus tool binding.
 *
 * <p>Runs ahead of Jackson (see {@code QuarkusToolExecutor}), so a capture that does not parse
 * never reaches the tool. A non-fatal failure becomes an error tool result carrying {@link
 * #REPAIR_HINT}, which the model reads and answers with a corrected capture in the same turn.
 *
 * <p>Arguments are parsed strictly. An earlier version escaped quotes inside {@code script} string
 * values at the character level to make the payload parse; that could yield JSON that parsed but no
 * longer matched the script the model meant to send, and the corruption was invisible downstream.
 * Rejecting and reprompting costs one model turn and keeps the captured script exact.
 */
@ApplicationScoped
public class ScriptBodyRepairArgumentsGuardrail implements ToolInputGuardrail {

  private static final Logger LOG = Logger.getLogger(ScriptBodyRepairArgumentsGuardrail.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  static final String REPAIR_HINT =
      "Invalid repairScriptBodies JSON (often unescaped quotes inside Groovy). Resubmit one valid"
          + " capture. For catch error bodies prefer:"
          + " exchange.in.body = groovy.json.JsonOutput.toJson([error: exception?.message])"
          + " — do not embed JSON object literals with double quotes inside the script string."
          + " Escape every \" in script as \\\". Include targetNodeId on every scripts entry.";

  @Override
  public ToolInputGuardrailResult validate(ToolInputGuardrailRequest request) {
    if (request == null || request.executionRequest() == null) {
      return ToolInputGuardrailResult.success();
    }
    ToolExecutionRequest execution = request.executionRequest();
    String arguments = execution.arguments();
    if (arguments == null || arguments.isBlank()) {
      return ToolInputGuardrailResult.success();
    }
    if (isValidJson(arguments)) {
      return ToolInputGuardrailResult.success();
    }
    LOG.warnf(
        "repairScriptBodies arguments are not valid JSON; len=%d. Returning a repair hint so the"
            + " model resubmits the capture.",
        arguments.length());
    return ToolInputGuardrailResult.failure(REPAIR_HINT);
  }

  /** Single JSON-validity check for tool arguments; no coercion and no text-level repair. */
  static boolean isValidJson(String json) {
    try {
      MAPPER.readTree(json);
      return true;
    } catch (JsonProcessingException e) {
      return false;
    }
  }
}
