package org.qubership.integration.platform.ai.llm.agent;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.RegisterAiService;
import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.ApplicationScoped;
import org.qubership.integration.platform.ai.plan.ChainPlanRepairTool;

/** Constrained agent that repairs invalid plan drafts with edge-only patches. */
@RegisterAiService(tools = {ChainPlanRepairTool.class}, maxSequentialToolInvocations = 2)
@ApplicationScoped
public interface ChainPlanRepairAgent {

  @dev.langchain4j.service.SystemMessage(fromResource = "prompts/chain-plan-repair-system.md")
  Multi<String> chat(@MemoryId String conversationId, @UserMessage String userMessage);
}
