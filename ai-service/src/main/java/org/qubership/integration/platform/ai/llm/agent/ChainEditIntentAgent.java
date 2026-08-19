package org.qubership.integration.platform.ai.llm.agent;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.RegisterAiService;
import jakarta.enterprise.context.ApplicationScoped;
import org.qubership.integration.platform.ai.chain.edit.ChainEditCapture;

/**
 * Reads a change request and returns a typed {@link ChainEditCapture}.
 *
 * <p>It holds no tools and writes no patch. Java validates the capture against the imported graph
 * and applies it. It does not infer the action, type, targets, or placement from English in the
 * request.
 */
@RegisterAiService(
    chatMemoryProviderSupplier = RegisterAiService.NoChatMemoryProviderSupplier.class)
@ApplicationScoped
public interface ChainEditIntentAgent {

  @SystemMessage(fromResource = "prompts/roles/chain-edit-intent.md")
  @UserMessage(
      """
Chain elements (id | type | label):
{elements}

User request:
{userRequest}\
""")
  ChainEditCapture resolve(String elements, String userRequest);
}
