package org.qubership.integration.platform.ai.llm.agent;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.Result;
import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.RegisterAiService;
import jakarta.enterprise.context.ApplicationScoped;
import org.qubership.integration.platform.ai.productpipeline.create.design.planning.DesignPlanCaptureTool;

/** Runs the immutable design planner with only the typed plan capture tool. */
@RegisterAiService(
    tools = {DesignPlanCaptureTool.class},
    maxSequentialToolInvocations = 4)
@ApplicationScoped
public interface DesignPlanCaptureAgent {

  @dev.langchain4j.service.SystemMessage(fromResource = "prompts/design-process-skill-system.md")
  Result<String> chat(@MemoryId String conversationId, @UserMessage String userMessage);
}
