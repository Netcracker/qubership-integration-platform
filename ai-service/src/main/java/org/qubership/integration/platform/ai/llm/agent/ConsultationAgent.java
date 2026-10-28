package org.qubership.integration.platform.ai.llm.agent;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.RegisterAiService;
import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.ApplicationScoped;
import org.qubership.integration.platform.ai.integration.catalog.tool.ElementSchemaTools;

/** Read-only platform assistance independent of the private creation package. */
@RegisterAiService(tools = {ElementSchemaTools.class,
    org.qubership.integration.platform.ai.productpipeline.knowledge.DocumentationTools.class},
    maxSequentialToolInvocations = 6)
@ApplicationScoped
public interface ConsultationAgent {
  @SystemMessage(fromResource = "prompts/consultation-system.md")
  Multi<String> chat(@MemoryId String conversationId, @UserMessage String message);
}
