package org.qubership.integration.platform.ai.chat.chainplan;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.RegisterAiService;
import jakarta.enterprise.context.ApplicationScoped;

/** LLM binary classifier for Gate 1 plan approval from free-text chat (YES / NO). */
@RegisterAiService(
    chatMemoryProviderSupplier = RegisterAiService.NoChatMemoryProviderSupplier.class)
@ApplicationScoped
public interface PlanApprovalClassifier {

  @SystemMessage(fromResource = "prompts/plan-approval-system.md")
  @UserMessage(
      """
      Recent conversation (oldest first):
      {context}

      User intent line (UI chain appendix stripped):
      {intentLine}

      Reply with ONLY YES or NO.\
      """)
  String classify(String context, String intentLine);
}
