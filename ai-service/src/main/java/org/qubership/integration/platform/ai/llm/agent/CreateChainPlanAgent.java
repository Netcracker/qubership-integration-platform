package org.qubership.integration.platform.ai.llm.agent;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.RegisterAiService;
import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.ApplicationScoped;
import org.qubership.integration.platform.ai.compiler.ChainStructureCaptureTool;
import org.qubership.integration.platform.ai.integration.catalog.tool.CatalogSystemTools;
import org.qubership.integration.platform.ai.integration.catalog.tool.ElementSchemaTools;
import org.qubership.integration.platform.ai.plan.ChainPlanTool;

/**
 * Agent for {@code GRAPH_CONSTRUCTION} compiler skills (runtime: {@code cip-chain-generator}).
 * Tools: read-only catalog, element schema, and graph capture.
 * System prompt: assembled at build time by merge-system-prompts.groovy from
 * qip-base-system.md + roles/structure-generator.md → prompts/structure-generator-system.md.
 */
@RegisterAiService(
    tools = {
      ElementSchemaTools.class,
      CatalogSystemTools.class,
      ChainStructureCaptureTool.class,
      ChainPlanTool.class
    },
    maxSequentialToolInvocations = 3)
@ApplicationScoped
public interface CreateChainPlanAgent {

  @dev.langchain4j.service.SystemMessage(fromResource = "prompts/structure-generator-system.md")
  Multi<String> chat(@MemoryId String conversationId, @UserMessage String userMessage);
}
