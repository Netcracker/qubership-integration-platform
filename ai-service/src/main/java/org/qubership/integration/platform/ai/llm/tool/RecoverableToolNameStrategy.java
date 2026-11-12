package org.qubership.integration.platform.ai.llm.tool;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import jakarta.inject.Singleton;
import java.util.function.Function;

@Singleton
public class RecoverableToolNameStrategy implements Function<ToolExecutionRequest, ToolExecutionResultMessage> {
  @Override
  public ToolExecutionResultMessage apply(ToolExecutionRequest request) {
    return ToolExecutionResultMessage.from(request,
        "UNKNOWN_TOOL: This tool is not available in the current step. No operation was executed."
            + " Continue the same task using the tools in the current request and the saved inputs."
            + " Choose a supported tool or finish the requested answer; do not repeat the unavailable tool call.");
  }
}
