package org.qubership.integration.platform.ai.llm.agent;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.RegisterAiService;
import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.ApplicationScoped;

/** AI agent for Scenario 6 — Generate structured test cases from a QIP chain or design. */
@RegisterAiService
@ApplicationScoped
public interface CreateTestCasesAgent {

  @SystemMessage(fromResource = "prompts/create-test-cases-system.md")
  Multi<String> chat(@MemoryId String conversationId, @UserMessage String userMessage);
}
