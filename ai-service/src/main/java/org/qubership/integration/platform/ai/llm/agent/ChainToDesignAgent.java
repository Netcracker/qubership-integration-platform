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

/** AI agent for Scenario 5 — Reverse-engineer a QIP chain into a design document. */
@RegisterAiService(
    tools = {
      ApiHubMcpTools.class,
      CatalogChainTools.class,
      CatalogElementTools.class,
      CatalogConnectionTools.class,
      CatalogSystemTools.class
    })
@ApplicationScoped
public interface ChainToDesignAgent {

  @SystemMessage(fromResource = "prompts/chain-to-design-system.md")
  Multi<String> chat(@MemoryId String conversationId, @UserMessage String userMessage);
}
