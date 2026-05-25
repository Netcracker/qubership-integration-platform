package org.qubership.integration.platform.ai.chat.guardrail;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.RegisterAiService;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Guardrail AI service that checks whether an incoming message is on-topic for QIP.
 *
 * <p>{@code context} is recent conversation (oldest first); use a placeholder when empty so short
 * replies (e.g. confirmations) are judged in thread. Returns "YES" or "NO"; parsed by {@link
 * GuardrailFilter}.
 */
@RegisterAiService(
    chatMemoryProviderSupplier = RegisterAiService.NoChatMemoryProviderSupplier.class)
@ApplicationScoped
public interface GuardrailService {

  @SystemMessage(fromResource = "prompts/guardrail-system.md")
  @UserMessage(
      "Recent conversation (oldest first):\n{context}\n\n"
          + "New user message to classify:\n{message}\n\n")
  String check(String context, String message);
}
