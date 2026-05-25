package org.qubership.integration.platform.ai.llm.agent;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.RegisterAiService;
import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.ApplicationScoped;

/** AI agent for Scenario 7 — Generate a Postman Collection v2.1 JSON from test cases. */
@RegisterAiService
@ApplicationScoped
public interface CreatePostmanAgent {

  @SystemMessage(fromResource = "prompts/create-postman-system.md")
  Multi<String> chat(@MemoryId String conversationId, @UserMessage String userMessage);
}
