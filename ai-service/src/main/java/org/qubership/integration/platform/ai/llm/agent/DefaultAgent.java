package org.qubership.integration.platform.ai.llm.agent;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.RegisterAiService;
import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.ApplicationScoped;
import org.qubership.integration.platform.ai.integration.apihub.ApiHubMcpTools;
import org.qubership.integration.platform.ai.integration.catalog.tool.CatalogChainTools;
import org.qubership.integration.platform.ai.integration.catalog.tool.CatalogConnectionTools;
import org.qubership.integration.platform.ai.integration.catalog.tool.CatalogElementTools;
import org.qubership.integration.platform.ai.integration.catalog.tool.CatalogSystemTools;

/**
 * AI agent for the default path: general QIP help with API Catalog and API Hub tools (search +
 * chain CRUD when appropriate).
 */
@RegisterAiService(
    tools = {
      ApiHubMcpTools.class,
      CatalogChainTools.class,
      CatalogElementTools.class,
      CatalogConnectionTools.class,
      CatalogSystemTools.class
    })
@ApplicationScoped
public interface DefaultAgent {

  @SystemMessage(fromResource = "prompts/default-system.md")
  Multi<String> chat(@MemoryId String conversationId, @UserMessage String userMessage);
}
