package org.qubership.integration.platform.ai.llm.agent;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.invocation.InvocationParameters;
import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.RegisterAiService;
import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.ApplicationScoped;
import org.qubership.integration.platform.ai.plan.ProductRequirementBriefTool;

/** Analyzes a selected product draft through the restricted brief capture tool. */
@RegisterAiService(
    toolProviderSupplier = ProductRequirementBriefTool.ProviderSupplier.class,
    chatMemoryProviderSupplier = RegisterAiService.NoChatMemoryProviderSupplier.class,
    maxSequentialToolInvocations = 6)
@ApplicationScoped
public interface ProductDiscoveryAgent {

  @dev.langchain4j.service.SystemMessage(fromResource = "prompts/requirement-analyzer-product-system.md")
  Multi<String> chat(@MemoryId String conversationId, @UserMessage String userMessage,
      InvocationParameters invocationParameters);
}
