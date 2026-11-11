package org.qubership.integration.platform.ai.llm.agent;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.RegisterAiService;
import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.ApplicationScoped;
import org.qubership.integration.platform.ai.chat.memory.TaskPreservingChatMemory;
import org.qubership.integration.platform.ai.compiler.HttpTriggerCaptureTool;

/** Dedicated HTTP trigger capture agent with a restricted input schema. */
@RegisterAiService(
    toolHallucinationStrategy = org.qubership.integration.platform.ai.llm.tool.RecoverableToolNameStrategy.class,
    chatMemoryProviderSupplier = TaskPreservingChatMemory.Provider.class,
    tools = {HttpTriggerCaptureTool.class}, maxSequentialToolInvocations = 6)
@ApplicationScoped
public interface HttpTriggerCaptureAgent {

  @dev.langchain4j.service.SystemMessage(fromResource = "prompts/compiler-skill-system.md")
  Multi<String> chat(@MemoryId String conversationId, @UserMessage String userMessage);
}
