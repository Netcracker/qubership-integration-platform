package org.qubership.integration.platform.ai.llm.agent;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.RegisterAiService;
import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.ApplicationScoped;
import org.qubership.integration.platform.ai.integration.apihub.ApiHubMcpTools;
import org.qubership.integration.platform.ai.plan.RequirementBriefTool;

/**
 * Agent for {@code DISCOVERY} compiler skills (runtime: {@code cip-requirement-analyzer}).
 * Tools: requirement brief capture, compiler knowledge search, and API Hub lookup.
 * System prompt: qip-base-system.md + roles/requirement-analyzer.md.
 */
@RegisterAiService(
    tools = {RequirementBriefTool.class, ApiHubMcpTools.class},
    maxSequentialToolInvocations = 6)
@ApplicationScoped
public interface DiscoveryAgent {

  @dev.langchain4j.service.SystemMessage(fromResource = "prompts/requirement-analyzer-system.md")
  Multi<String> chat(@MemoryId String conversationId, @UserMessage String userMessage);
}
