package org.qubership.integration.platform.ai.compiler;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import io.quarkiverse.langchain4j.guardrails.ToolInputGuardrail;
import io.quarkiverse.langchain4j.guardrails.ToolInputGuardrailRequest;
import io.quarkiverse.langchain4j.guardrails.ToolInputGuardrailResult;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Optional;
import org.jboss.logging.Logger;

/**
 * Repairs malformed {@code repairScriptBodies} arguments before Quarkus tool binding.
 *
 * <p>Runs ahead of Jackson (see {@code QuarkusToolExecutor}), so unescaped quotes inside Groovy
 * script strings do not become {@code ToolArgumentsException}. Mirrors the tolerant script handling
 * introduced for {@code captureGraphPatch} via {@code PlanPropertyListDeserializer}.
 */
@ApplicationScoped
public class ScriptBodyRepairArgumentsGuardrail implements ToolInputGuardrail {

  private static final Logger LOG = Logger.getLogger(ScriptBodyRepairArgumentsGuardrail.class);

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
    if (ScriptBodyToolArgumentsSanitizer.isValidJson(arguments)) {
      return ToolInputGuardrailResult.success();
    }
    Optional<String> repaired = ScriptBodyToolArgumentsSanitizer.sanitizeIfNeeded(arguments);
    if (repaired.isPresent()) {
      LOG.warnf(
          "Sanitized repairScriptBodies arguments (escaped unquoted chars in script values);"
              + " originalLen=%d repairedLen=%d",
          arguments.length(), repaired.get().length());
      ToolExecutionRequest modified =
          ToolExecutionRequest.builder()
              .id(execution.id())
              .name(execution.name())
              .arguments(repaired.get())
              .build();
      return ToolInputGuardrailResult.successWith(modified);
    }
    LOG.warnf(
        "repairScriptBodies arguments are not valid JSON and could not be sanitized; len=%d",
        arguments.length());
    return ToolInputGuardrailResult.failure(REPAIR_HINT);
  }
}
