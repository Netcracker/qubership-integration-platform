package org.qubership.integration.platform.ai.llm.agent;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.RegisterAiService;
import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.ApplicationScoped;
import org.qubership.integration.platform.ai.chat.hitl.HitlTool;
import org.qubership.integration.platform.ai.integration.apihub.ApiHubMcpTools;
import org.qubership.integration.platform.ai.integration.catalog.tool.CatalogSystemTools;
import org.qubership.integration.platform.ai.integration.catalog.tool.ElementSchemaTools;
import org.qubership.integration.platform.ai.integration.catalog.tool.ImportCandidateTool;

/**
 * AI agent for CREATE_CHAIN_PLAN — produces a {@code ChainImplementationPlan} JSON and HITL
 * confirmation. Catalog chain/element mutation tools are not registered; {@link CatalogSystemTools}
 * mutations are blocked by server guard.
 */
@RegisterAiService(
    tools = {
      ApiHubMcpTools.class,
      ElementSchemaTools.class,
      HitlTool.class,
      CatalogSystemTools.class,
      ImportCandidateTool.class
    })
@ApplicationScoped
public interface CreateChainPlanAgent {

  @SystemMessage(fromResource = "prompts/create-chain-plan-system.md")
  Multi<String> chat(@MemoryId String conversationId, @UserMessage String userMessage);
}
