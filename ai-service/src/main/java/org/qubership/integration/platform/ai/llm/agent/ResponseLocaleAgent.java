package org.qubership.integration.platform.ai.llm.agent;

import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.RegisterAiService;
import jakarta.enterprise.context.ApplicationScoped;

/** Identifies the language of the first substantive user prompt. */
@RegisterAiService(
    chatMemoryProviderSupplier = RegisterAiService.NoChatMemoryProviderSupplier.class)
@ApplicationScoped
public interface ResponseLocaleAgent {

  @UserMessage(
      """
Identify the language used for the prose instructions in the prompt below. Ignore product names, \
code, paths, identifiers, and quoted response messages.

Return only one IETF BCP 47 language tag, such as en, ru, de, es, or pt-BR. Use en when the \
language is ambiguous.
---
{firstPrompt}
---
Reply with the language tag only. No explanation, markdown, or punctuation.\
""")
  String detect(String firstPrompt);
}
