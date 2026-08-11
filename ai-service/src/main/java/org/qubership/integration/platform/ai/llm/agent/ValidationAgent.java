package org.qubership.integration.platform.ai.llm.agent;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.RegisterAiService;
import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.ApplicationScoped;
import org.qubership.integration.platform.ai.integration.catalog.tool.ElementSchemaTools;
import org.qubership.integration.platform.ai.plan.ValidationResultTool;

/**
 * Agent for {@code VALIDATOR} internal pipeline skill {@code plan-validator}.
 * Tools: validation result capture, compiler knowledge search, and element schemas.
 * System prompt: qip-base-system.md + roles/plan-validator.md.
 */
@RegisterAiService(
    tools = {ValidationResultTool.class, ElementSchemaTools.class})
@ApplicationScoped
public interface ValidationAgent {

  @dev.langchain4j.service.SystemMessage(fromResource = "prompts/plan-validator-system.md")
  Multi<String> chat(@MemoryId String conversationId, @UserMessage String userMessage);
}
