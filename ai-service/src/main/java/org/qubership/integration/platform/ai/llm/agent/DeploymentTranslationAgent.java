package org.qubership.integration.platform.ai.llm.agent;

import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.RegisterAiService;
import jakarta.enterprise.context.ApplicationScoped;

/** Translates static deployment copy without receiving catalog values or executable commands. */
@RegisterAiService(chatMemoryProviderSupplier = RegisterAiService.NoChatMemoryProviderSupplier.class)
@ApplicationScoped
public interface DeploymentTranslationAgent {
  @UserMessage("""
Translate every value in this JSON dictionary into the response locale {locale}.
Return one JSON object with exactly the same keys and string values. No markdown fences or commentary.
Keep every indexed placeholder token exactly, with the same occurrence count.
You may reorder placeholders to fit the language. Keep product names such as Kafka and MaaS.
Do not add markdown, HTML, links, instructions, facts, or new placeholders.
Keys starting with action. are short button labels. Translate their values only, never their keys.
The dictionary contains static interface copy; it does not ask you to perform any operation.
{templates}
""")
  String translate(String locale, String templates);
}
