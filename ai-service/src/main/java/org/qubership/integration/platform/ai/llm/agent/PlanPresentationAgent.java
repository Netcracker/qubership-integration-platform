package org.qubership.integration.platform.ai.llm.agent;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.RegisterAiService;
import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Agent for plan presentation after planning completes.
 *
 * <p>System prompt: assembled at build time from qip-base-system.md + roles/plan-presentation.md
 * → prompts/plan-presentation-system.md.
 */
@RegisterAiService
@ApplicationScoped
public interface PlanPresentationAgent {

  @dev.langchain4j.service.SystemMessage(fromResource = "prompts/plan-presentation-system.md")
  Multi<String> chat(@MemoryId String conversationId, @UserMessage String userMessage);
}
