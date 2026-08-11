package org.qubership.integration.platform.ai.llm.agent;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.RegisterAiService;
import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.ApplicationScoped;
import org.qubership.integration.platform.ai.integration.catalog.tool.CatalogElementWriteTools;
import org.qubership.integration.platform.ai.integration.catalog.tool.ElementSchemaTools;

/**
 * Agent for skill harness runs against an existing catalog chain.
 *
 * <p>Uses catalog write and schema tools only. No ChainPlanGraph or graph patch capture.
 */
@RegisterAiService(
    tools = {CatalogElementWriteTools.class, ElementSchemaTools.class})
@ApplicationScoped
public interface HarnessSkillAgent {

  @SystemMessage(fromResource = "prompts/harness-skill-system.md")
  Multi<String> chat(@MemoryId String conversationId, @UserMessage String userMessage);
}
