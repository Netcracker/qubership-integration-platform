package org.qubership.integration.platform.ai.llm.agent;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.Result;
import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.RegisterAiService;
import jakarta.enterprise.context.ApplicationScoped;
import org.qubership.integration.platform.ai.productpipeline.create.design.input.ChainSemanticCaptureTool;

/**
 * Captures a typed chain semantic revision from an approved requirement brief. The only tool is
 * {@link ChainSemanticCaptureTool}. IDS markdown is rendered by the server after capture.
 */
@RegisterAiService(
    tools = {ChainSemanticCaptureTool.class},
    maxSequentialToolInvocations = 4)
@ApplicationScoped
public interface ChainSemanticDesignAgent {

  @dev.langchain4j.service.SystemMessage(fromResource = "prompts/chain-semantic-design-system.md")
  Result<String> chat(@MemoryId String conversationId, @UserMessage String userMessage);
}
