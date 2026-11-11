package org.qubership.integration.platform.ai.llm.agent;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.RegisterAiService;
import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.ApplicationScoped;
import org.qubership.integration.platform.ai.plan.RequirementCaptureToolProvider;

/**
 * Gathers requirements from an explicit draft and source packet for each author turn.
 */
@RegisterAiService(
    toolProviderSupplier = RequirementCaptureToolProvider.ProviderSupplier.class,
    chatMemoryProviderSupplier = RegisterAiService.NoChatMemoryProviderSupplier.class,
    maxSequentialToolInvocations = 10)
@ApplicationScoped
public interface GatherRequirementsAgent {

  @dev.langchain4j.service.SystemMessage(fromResource = "prompts/gather-requirements-system.md")
  Multi<String> chat(@MemoryId String conversationId, @UserMessage String userMessage,
      dev.langchain4j.invocation.InvocationParameters invocationParameters);
}
