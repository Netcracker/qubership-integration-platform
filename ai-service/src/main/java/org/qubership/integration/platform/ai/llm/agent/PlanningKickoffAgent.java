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
generator skills are starting. Write in the pinned response locale {responseLocale}. This locale
is authoritative; do not infer a different language from the reference text. Preserve product terms:
---
{reference}
---
Reply with only that sentence. No markdown, no quotes, no preamble.\
""")
  String announce(String responseLocale, String reference);
}
