package org.qubership.integration.platform.ai.llm.agent;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.RegisterAiService;
import jakarta.enterprise.context.ApplicationScoped;
import org.qubership.integration.platform.ai.chain.folder.ChainListCommand;

/**
 * Reads a request about the chains page and returns a typed {@link ChainListCommand}.
 *
 * <p>It holds no tools and changes nothing. Java resolves the folders and chains it names and
 * decides what needs the author's confirmation.
 */
@RegisterAiService(
    chatMemoryProviderSupplier = RegisterAiService.NoChatMemoryProviderSupplier.class)
@ApplicationScoped
public interface ChainListIntentAgent {

  @SystemMessage(fromResource = "prompts/roles/chain-list-intent.md")
  @UserMessage(
      """
Today (UTC):
{today}

Open chain id (may be empty):
{openChainId}

Recent transcript:
{transcriptWindow}

User request:
{userRequest}\
""")
  ChainListCommand resolve(
      String today, String openChainId, String transcriptWindow, String userRequest);
}
