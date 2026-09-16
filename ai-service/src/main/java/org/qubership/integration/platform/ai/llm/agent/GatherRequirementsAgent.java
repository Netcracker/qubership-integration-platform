package org.qubership.integration.platform.ai.llm.agent;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.RegisterAiService;
import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.ApplicationScoped;
import org.qubership.integration.platform.ai.integration.catalog.tool.CatalogSystemTools;
import org.qubership.integration.platform.ai.plan.CatalogFirstApiHubDiscoveryTool;
import org.qubership.integration.platform.ai.plan.RequirementDraftTool;
import org.qubership.integration.platform.ai.plan.SelectApiHubCandidateTool;
import org.qubership.integration.platform.ai.productpipeline.knowledge.RequirementDiscoveryKnowledgeTool;

/**
 * Agent for iterative requirement gathering before the compiler spine runs. Uses TOKEN_WINDOW chat
 * memory from {@code application.properties}, same as {@link DiscoveryAgent}.
 */
@RegisterAiService(
    tools = {
      RequirementDraftTool.class,
      SelectApiHubCandidateTool.class,
      CatalogSystemTools.class,
      CatalogFirstApiHubDiscoveryTool.class,
      RequirementDiscoveryKnowledgeTool.class
    },
    maxSequentialToolInvocations = 10)
@ApplicationScoped
public interface GatherRequirementsAgent {

  @dev.langchain4j.service.SystemMessage(fromResource = "prompts/gather-requirements-system.md")
  Multi<String> chat(@MemoryId String conversationId, @UserMessage String userMessage);
}
