package org.qubership.integration.platform.ai.llm.agent;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.RegisterAiService;
import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * No-tools process agent for immutable design skills such as {@code cip-design-planner}.
 *
 * <p>Follows the supplied skill body and returns only the skill output.
 */
@RegisterAiService(
    chatMemoryProviderSupplier = RegisterAiService.NoChatMemoryProviderSupplier.class)
@ApplicationScoped
public interface DesignProcessSkillAgent {

  @SystemMessage(fromResource = "prompts/design-process-skill-system.md")
  Multi<String> chat(@MemoryId String conversationId, @UserMessage String userMessage);
}
