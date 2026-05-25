package org.qubership.integration.platform.ai.llm.agent;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.RegisterAiService;
import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.ApplicationScoped;
import org.qubership.integration.platform.ai.chat.chainplan.ChainPlanOpenDebtTool;
import org.qubership.integration.platform.ai.chat.hitl.HitlTool;
import org.qubership.integration.platform.ai.integration.apihub.ApiHubMcpTools;
import org.qubership.integration.platform.ai.integration.catalog.tool.CatalogChainTools;
import org.qubership.integration.platform.ai.integration.catalog.tool.CatalogConnectionTools;
import org.qubership.integration.platform.ai.integration.catalog.tool.CatalogElementTools;
import org.qubership.integration.platform.ai.integration.catalog.tool.CatalogSystemTools;
import org.qubership.integration.platform.ai.integration.catalog.tool.ElementSchemaTools;

/**
 * AI agent for Scenario 3 — Implement a QIP chain from a design document. Has access to APIHUB (API
 * lookup), Catalog (chain CRUD), and HITL (human confirmation) tools. The agent autonomously
 * decides when to request user confirmation before destructive operations.
 */
@RegisterAiService(
    tools = {
      ApiHubMcpTools.class,
      CatalogChainTools.class,
      CatalogElementTools.class,
      CatalogConnectionTools.class,
      CatalogSystemTools.class,
      ElementSchemaTools.class,
      HitlTool.class,
      ChainPlanOpenDebtTool.class
    })
@ApplicationScoped
public interface ImplementChainAgent {

  @SystemMessage(fromResource = "prompts/implement-chain-system.md")
  Multi<String> chat(@MemoryId String conversationId, @UserMessage String userMessage);
}
