package org.qubership.integration.platform.ai.llm.agent;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.RegisterAiService;
import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Agent for catalog chain presentation after implement or during ASK_CHAIN explain.
 *
 * <p>System prompt: assembled at build time from qip-base-system.md + roles/chain-presentation.md
 * → prompts/chain-presentation-system.md.
 */
@RegisterAiService(
    chatMemoryProviderSupplier = RegisterAiService.NoChatMemoryProviderSupplier.class)
@ApplicationScoped
public interface ChainPresentationAgent {

  @dev.langchain4j.service.SystemMessage(fromResource = "prompts/chain-presentation-system.md")
  Multi<String> chat(@MemoryId String conversationId, @UserMessage String userMessage);
}
