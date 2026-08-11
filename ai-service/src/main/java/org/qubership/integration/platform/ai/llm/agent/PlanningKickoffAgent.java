package org.qubership.integration.platform.ai.llm.agent;

import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.RegisterAiService;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * One-line chat announcement when product planning starts after brief approval. Matches the
 * language of the reference text so multilingual CREATE conversations stay consistent.
 */
@RegisterAiService(
    chatMemoryProviderSupplier = RegisterAiService.NoChatMemoryProviderSupplier.class)
@ApplicationScoped
public interface PlanningKickoffAgent {

  @UserMessage(
      """
Write one short sentence telling the user that the implementation plan is now being created and \
generator skills are starting. Match the language of this reference text (do not translate the \
user's product terms incorrectly; just match the language):
---
{reference}
---
Reply with only that sentence. No markdown, no quotes, no preamble.\
""")
  String announce(String reference);
}
