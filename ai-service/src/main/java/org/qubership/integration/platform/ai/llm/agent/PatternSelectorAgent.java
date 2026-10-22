package org.qubership.integration.platform.ai.llm.agent;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.RegisterAiService;
import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.ApplicationScoped;
import org.qubership.integration.platform.ai.plan.SelectedPatternTool;

/**
 * Agent for {@code cip-pattern-selector} (GEN-01 golden pattern selection via D-017).
 * System prompt: qip-base-system.md + roles/pattern-selector.md.
 */
@RegisterAiService(
    tools = {SelectedPatternTool.class},
    maxSequentialToolInvocations = 6)
@ApplicationScoped
public interface PatternSelectorAgent {

  @dev.langchain4j.service.SystemMessage(fromResource = "prompts/pattern-selector-system.md")
  Multi<String> chat(@MemoryId String conversationId, @UserMessage String userMessage);
}
