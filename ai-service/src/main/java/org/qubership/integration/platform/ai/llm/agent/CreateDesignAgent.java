package org.qubership.integration.platform.ai.llm.agent;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.RegisterAiService;
import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.ApplicationScoped;
import org.qubership.integration.platform.ai.chat.hitl.HitlTool;
import org.qubership.integration.platform.ai.integration.apihub.ApiHubMcpTools;

/**
 * AI agent for Scenario 1 — Create Integration Design Specification from free text. Streams the
 * generated IDS Markdown document token by token. Has HITL capability for clarifying ambiguous API
 * search results.
 */
@RegisterAiService(tools = {ApiHubMcpTools.class, HitlTool.class})
@ApplicationScoped
public interface CreateDesignAgent {

  @SystemMessage(fromResource = "prompts/create-design-system.md")
  Multi<String> chat(@MemoryId String conversationId, @UserMessage String userMessage);
}
