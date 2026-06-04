package org.qubership.integration.platform.ai.llm.agent;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.RegisterAiService;
import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.ApplicationScoped;
import org.qubership.integration.platform.ai.chat.hitl.HitlTool;
import org.qubership.integration.platform.ai.integration.catalog.tool.ImportCandidateTool;
import org.qubership.integration.platform.ai.integration.catalog.tool.ImportSpecificationTools;

/**
 * AI agent for IMPORT_SPECIFICATION — imports a full ApiHub specification into runtime-catalog
 * using a saved import candidate, then directs the user back to CREATE_CHAIN_PLAN.
 */
@RegisterAiService(
    tools = {
      ImportSpecificationTools.class,
      ImportCandidateTool.class,
      HitlTool.class
    })
@ApplicationScoped
public interface ImportSpecificationAgent {

  @SystemMessage(fromResource = "prompts/import-specification-system.md")
  Multi<String> chat(@MemoryId String conversationId, @UserMessage String userMessage);
}
