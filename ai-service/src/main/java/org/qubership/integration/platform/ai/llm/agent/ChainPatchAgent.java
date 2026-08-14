package org.qubership.integration.platform.ai.llm.agent;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.RegisterAiService;
import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.ApplicationScoped;
import org.qubership.integration.platform.ai.chain.patch.ChainPatchTool;
import org.qubership.integration.platform.ai.chain.patch.ChainSnapshotTool;

/** Constrained agent that proposes property changes to a chain the user already has. */
@RegisterAiService(
    tools = {ChainPatchTool.class, ChainSnapshotTool.class},
    maxSequentialToolInvocations = 3)
@ApplicationScoped
public interface ChainPatchAgent {

  @SystemMessage(fromResource = "prompts/chain-patch-system.md")
  Multi<String> chat(@MemoryId String conversationId, @UserMessage String userMessage);
}
