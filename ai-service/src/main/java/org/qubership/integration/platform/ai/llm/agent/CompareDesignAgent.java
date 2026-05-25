package org.qubership.integration.platform.ai.llm.agent;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.RegisterAiService;
import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.ApplicationScoped;
import org.qubership.integration.platform.ai.chat.hitl.HitlTool;
import org.qubership.integration.platform.ai.integration.catalog.tool.CatalogChainTools;
import org.qubership.integration.platform.ai.integration.catalog.tool.CatalogConnectionTools;
import org.qubership.integration.platform.ai.integration.catalog.tool.CatalogElementTools;
import org.qubership.integration.platform.ai.integration.catalog.tool.CatalogSystemTools;

/**
 * AI agent for Scenario 4 — Compare design with existing chain and apply changes. Has access to
 * Catalog (chain CRUD) and HITL (human confirmation) tools. The agent autonomously decides when to
 * request user confirmation before applying changes.
 */
@RegisterAiService(
    tools = {
      CatalogChainTools.class,
      CatalogElementTools.class,
      CatalogConnectionTools.class,
      CatalogSystemTools.class,
      HitlTool.class
    })
@ApplicationScoped
public interface CompareDesignAgent {

  @SystemMessage(fromResource = "prompts/compare-patch-system.md")
  Multi<String> chat(@MemoryId String conversationId, @UserMessage String userMessage);
}
