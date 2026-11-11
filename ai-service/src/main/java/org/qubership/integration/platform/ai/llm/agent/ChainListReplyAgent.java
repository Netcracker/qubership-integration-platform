package org.qubership.integration.platform.ai.llm.agent;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.RegisterAiService;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Words the outcome of a chains page request in the author's language.
 *
 * <p>Java has already found, counted, or changed the chains and passes the facts as JSON. The
 * agent writes only the lead text; Java appends the numbered list, the counts, or the chains a
 * card changes under it, so no number or name passes through the model.
 *
 * <p>System prompt: assembled at build time from qip-base-system.md + roles/chain-list-reply.md
 * → prompts/chain-list-reply-system.md.
 */
@RegisterAiService(
    chatMemoryProviderSupplier = RegisterAiService.NoChatMemoryProviderSupplier.class)
@ApplicationScoped
public interface ChainListReplyAgent {

  @SystemMessage(fromResource = "prompts/chain-list-reply-system.md")
  @UserMessage(
      """
Recent transcript:
{transcriptWindow}

User request (empty when the user pressed a card button):
{userRequest}

Outcome:
{outcome}\
""")
  String reply(String transcriptWindow, String userRequest, String outcome);
}
