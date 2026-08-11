package org.qubership.integration.platform.ai.llm.agent;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.RegisterAiService;
import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.ApplicationScoped;
import org.qubership.integration.platform.ai.compiler.ScriptBodyRepairTool;

/** Constrained agent that fills missing script bodies with a script-only patch. */
@RegisterAiService(tools = {ScriptBodyRepairTool.class}, maxSequentialToolInvocations = 4)
@ApplicationScoped
public interface ScriptBodyRepairAgent {

  @dev.langchain4j.service.SystemMessage(fromResource = "prompts/script-body-repair-system.md")
  Multi<String> chat(@MemoryId String conversationId, @UserMessage String userMessage);
}
