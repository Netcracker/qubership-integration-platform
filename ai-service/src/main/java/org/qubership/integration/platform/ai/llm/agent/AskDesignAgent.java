package org.qubership.integration.platform.ai.llm.agent;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.RegisterAiService;
import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.ApplicationScoped;
import org.qubership.integration.platform.ai.integration.apihub.ApiHubMcpTools;
import org.qubership.integration.platform.ai.integration.catalog.tool.CatalogSystemTools;

/**
 * AI agent for Scenario 2 — Query an existing design document. Supports multi-turn conversations
 * about a design.
 */
@RegisterAiService(tools = {CatalogSystemTools.class, ApiHubMcpTools.class})
@ApplicationScoped
public interface AskDesignAgent {

  @SystemMessage(fromResource = "prompts/ask-design-system.md")
  Multi<String> chat(@MemoryId String conversationId, @UserMessage String userMessage);
}
