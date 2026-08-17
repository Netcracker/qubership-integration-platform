package org.qubership.integration.platform.ai.llm.agent;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.RegisterAiService;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Reads a change request and says which action it asks for and which elements it acts on.
 *
 * <p>It holds no tools and writes no patch. Everything that needs a schema, a catalog lookup, or a
 * topology decision happens afterwards in the owning compiler skill, so a weaker model dropping a
 * required property here cannot reach the catalog.
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
  String resolve(String elements, String userRequest);
}
